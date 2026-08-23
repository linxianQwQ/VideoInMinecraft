package com.linxian.videoinminecraft.video.tool;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 双向 消费-生产 管道缓冲池（高性能 + 少 new）。
 *
 * <p>原理：
 * <pre>
 *   解码线程(生产者)                渲染线程(消费者)
 *   slot = borrow()                 slot = acquire()
 *   sws_scale → slot.pointer        glTexSubImage2D(slot.address)
 *   publish(slot)                   release(slot)
 *        │                              │
 *   freePool ──borrow──▶ 解码 ──publish──▶ readyQueue ──acquire──▶ GL上传 ──release──▶ freePool
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link BufferSlot} 用 {@link MemoryUtil#nmemAlignedAlloc(int, long)} 以 <b>64 字节对齐</b>
 *       分配原生内存（swscale 的 x86 SIMD 路径要求 dst 64 字节对齐，见 FFmpeg ticket 1031；
 *       {@code ByteBuffer.allocateDirect} 只保证 8 字节对齐，会导致 "bad dst image pointers" 并越界写坏内存）。
 *       ByteBuffer / BytePointer / PointerPointer / 原生地址全部预分配为 final，生命周期内<b>零 new</b>。</li>
 *   <li>双队列天然无锁互斥：解码线程只碰 freePool/readyQueue 的 put/取，渲染线程同理，
 *       同一 slot 永远不会同时被两个线程使用（借出后即独享）。</li>
 *   <li>readyQueue 容量 = 背压：解码太快时 publish 阻塞，自动限流，不会无限堆积。</li>
 * </ul>
 */
public class FrameBufferPoolWithQueue {

    /** 常驻的可复用缓冲槽。预分配一次，全程零再分配。 */
    public static final class BufferSlot {
        /** 像素缓冲视图（64 字节对齐内存的 ByteBuffer 视图）：GL 上传的像素源 */
        public final ByteBuffer buffer;
        /** 常驻指针，指向对齐内存地址，sws_scale 的 dst 直接写这块内存 */
        public final BytePointer pointer;
        /** sws_scale 的 dst 指针数组（每帧复用，避免 new PointerPointer） */
        public final PointerPointer pointerPtr;
        /** 64 字节对齐的原生内存地址（GL 上传 / sws_scale 使用） */
        public final long address;
        public final int capacity;

        BufferSlot(int capacity) {
            this.capacity = capacity;
            // 关键：swscale 的 SIMD 路径要求 dst 64 字节对齐。不用 av_malloc（仍在 FFmpeg 外分配），
            // 用 LWJGL 对齐分配器，外部/JVM 管理生命周期。
            this.address = MemoryUtil.nmemAlignedAlloc(64, capacity);
            if (address == 0L) throw new OutOfMemoryError("nmemAlignedAlloc(64, " + capacity + ") failed");
            // 同一块对齐内存的 ByteBuffer 视图（零拷贝给 GL 上传）
            this.buffer = MemoryUtil.memByteBuffer(address, capacity);
            // 包装已有 buffer（new BytePointer(long) 是"分配新内存"，会把 address 当 size → OOM）
            this.pointer = new BytePointer(buffer);
            // 关键：sws_scale 会检查 dst[0]/dst[1]/dst[2] 三个槽（swscale.c: "bad dst image pointers"）。
            // 只给 1 个指针会导致 C 侧越界读。提供 4 个槽（同指针重复填充，packed RGBA 只用 dst[0]）。
            this.pointerPtr = new PointerPointer<>(pointer, pointer, pointer, pointer);
        }

        /** 复位读写位置，供借出/归还时调用 */
        public void resetPosition() {
            buffer.position(0).limit(capacity);
        }
    }

    /** 空闲槽池：可借出（解码线程取、渲染线程还） */
    private final BlockingQueue<BufferSlot> freePool;
    /** 就绪队列：已解码待上传（解码线程发布、渲染线程获取） */
    private final BlockingQueue<BufferSlot> readyQueue;

    public final int width;
    public final int height;
    public final int dataCapacity;
    public final int bufferCount;

    /**
     * @param bufferCount   预分配槽总数（建议 2~3，即双/三缓冲流水线）
     * @param readyCapacity 就绪队列容量（背压上限，>=1）
     * @param width         视频宽
     * @param height        视频高
     */
    public FrameBufferPoolWithQueue(int bufferCount, int readyCapacity, int width, int height) {
        if (bufferCount < 1) throw new IllegalArgumentException("bufferCount must be >= 1");
        if (readyCapacity < 1) throw new IllegalArgumentException("readyCapacity must be >= 1");
        this.width = width;
        this.height = height;
        this.dataCapacity = width * height * 4;
        this.bufferCount = bufferCount;
        this.freePool = new LinkedBlockingQueue<>(bufferCount);
        this.readyQueue = new LinkedBlockingQueue<>(readyCapacity);
        // 预分配全部槽，之后生命周期内零 new
        for (int i = 0; i < bufferCount; i++) {
            freePool.offer(new BufferSlot(dataCapacity));
        }
    }

    // ==================== 解码线程（生产者） ====================

    /** 借出一个空闲槽；无空闲时阻塞等待渲染线程归还。 */
    public BufferSlot borrow() throws InterruptedException {
        BufferSlot slot = freePool.take();
        slot.resetPosition();
        return slot;
    }

    /** 非阻塞借出；无空闲返回 null。 */
    public BufferSlot tryBorrow() {
        BufferSlot slot = freePool.poll();
        if (slot != null) slot.resetPosition();
        return slot;
    }

    /** 解码完成，发布到就绪队列；队列满则背压阻塞（自动限流）。 */
    public void publish(BufferSlot slot) throws InterruptedException {
        readyQueue.put(slot);
    }

    // ==================== 渲染线程（消费者） ====================

    /** 获取一张已解码待上传的帧；无帧时阻塞等待解码线程。 */
    public BufferSlot acquire() throws InterruptedException {
        return readyQueue.take();
    }

    /** 非阻塞获取；无就绪帧返回 null。 */
    public BufferSlot tryAcquire() {
        return readyQueue.poll();
    }

    /** GL 上传完成，归还到空闲池。freePool 容量=总数，offer 恒成功。 */
    public void release(BufferSlot slot) {
        slot.resetPosition();
        freePool.offer(slot);
    }

    // ==================== 状态查询 ====================

    public boolean hasReady() {
        return !readyQueue.isEmpty();
    }

    public int getReadyCount() {
        return readyQueue.size();
    }

    public int getFreeCount() {
        return freePool.size();
    }

    /** 释放所有槽的对齐内存（nmemAlignedAlloc 的成对释放）。 */
    public void close() {
        BufferSlot slot;
        while ((slot = freePool.poll()) != null) {
            if (slot.address != 0L) MemoryUtil.nmemAlignedFree(slot.address);
        }
        while ((slot = readyQueue.poll()) != null) {
            if (slot.address != 0L) MemoryUtil.nmemAlignedFree(slot.address);
        }
    }
}