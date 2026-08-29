package com.linxian.videoinminecraft.video.tool;

import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
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
 *   sws_scale → slot.imagePointer        glTexSubImage2D(slot.address)
 *   publish(slot)                   release(slot)
 *        │                              │
 *   videoFreePool ──borrow──▶ 解码 ──publish──▶ readyQueue ──acquire──▶ GL上传 ──release──▶ videoFreePool
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link ImageBufferSlot} 用  以 <b>64 字节对齐</b>
 *       分配原生内存（swscale 的 x86 SIMD 路径要求 dst 64 字节对齐，见 FFmpeg ticket 1031；
 *       {@code ByteBuffer.allocateDirect} 只保证 8 字节对齐，会导致 "bad dst image pointers" 并越界写坏内存）。
 *       ByteBuffer / BytePointer / PointerPointer / 原生地址全部预分配为 final，生命周期内<b>零 new</b>。</li>
 *   <li>双队列天然无锁互斥：解码线程只碰 videoFreePool/readyQueue 的 put/取，渲染线程同理，
 *       同一 slot 永远不会同时被两个线程使用（借出后即独享）。</li>
 *   <li>readyQueue 容量 = 背压：解码太快时 publish 阻塞，自动限流，不会无限堆积。</li>
 * </ul>
 */
public class FrameBufferPoolWithQueue {

    /** 全局池/槽序号：物理实例判别（identityHashCode 会被 GC/碰撞污染，不可靠）。 */
    private static final java.util.concurrent.atomic.AtomicInteger POOL_SEQ = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger SLOT_SEQ = new java.util.concurrent.atomic.AtomicInteger();
    public final int poolSeq;

    /** 常驻的可复用缓冲槽。预分配一次，全程零再分配。 */
    public static final class ImageBufferSlot {
        /** 像素缓冲视图（64 字节对齐内存的 ByteBuffer 视图）：GL 上传的像素源 */
        public final ByteBuffer imageBuffer;
        /** 常驻指针，指向对齐内存地址，sws_scale 的 dst 直接写这块内存 */
        public final BytePointer imagePointer;
        /** sws_scale 的 dst 指针数组（每帧复用，避免 new PointerPointer） */
        public final PointerPointer imagePointerPtr;
        /** 64 字节对齐的原生内存地址（GL 上传 / sws_scale 使用） */
        public final long address;
        public final int capacity;
        public long ptsUs;

        public final int slotSeq;   // 全局唯一槽序号（物理判别）

        ImageBufferSlot(int capacity) {
            this.capacity = capacity;
            this.slotSeq = SLOT_SEQ.incrementAndGet();
            // 关键：swscale 的 SIMD 路径要求 dst 64 字节对齐。不用 av_malloc（仍在 FFmpeg 外分配），
            // 用 LWJGL 对齐分配器，外部/JVM 管理生命周期。
            this.address = MemoryUtil.nmemAlignedAlloc(64, capacity);
            if (address == 0L) throw new OutOfMemoryError("nmemAlignedAlloc(64, " + capacity + ") failed");
            // 同一块对齐内存的 ByteBuffer 视图（零拷贝给 GL 上传）
            this.imageBuffer = MemoryUtil.memByteBuffer(address, capacity);
            // 包装已有 imageBuffer（new BytePointer(long) 是"分配新内存"，会把 address 当 size → OOM）
            this.imagePointer = new BytePointer(imageBuffer);
            // 关键：sws_scale 会检查 dst[0]/dst[1]/dst[2] 三个槽（swscale.c: "bad dst image pointers"）。
            // 只给 1 个指针会导致 C 侧越界读。提供 4 个槽（同指针重复填充，packed RGBA 只用 dst[0]）。
            this.imagePointerPtr = new PointerPointer<>(imagePointer, imagePointer, imagePointer, imagePointer);
        }

        /** 复位读写位置，供借出/归还时调用 */
        public void resetPosition() {
            imageBuffer.position(0).limit(capacity);
        }
    }
    public static final class AudioBufferSlot{
        public final ByteBuffer audioBuffer;
        public final BytePointer audioPointer;
        public final PointerPointer audioPointerPtr;
        public final long address;
        public final int capacity;// 帧数据字节数
        public long ptsUs;

        AudioBufferSlot(int capacity) {
            this.capacity = capacity;
            // 关键：swresample 的 SIMD 路径（与 swscale 同理）要求 dst 64 字节对齐。
            // 用 LWJGL 对齐分配器，外部/JVM 管理生命周期。
            this.address = MemoryUtil.nmemAlignedAlloc(64, capacity);
            if (address == 0L) throw new OutOfMemoryError("nmemAlignedAlloc(64, " + capacity + ") failed");
            // 同一块对齐内存的 ByteBuffer 视图（零拷贝给 GL 上传）
            this.audioBuffer = MemoryUtil.memByteBuffer(address, capacity);
            // 包装已有 imageBuffer（new BytePointer(long) 是"分配新内存"，会把 address 当 size → OOM）
            this.audioPointer = new BytePointer(audioBuffer);
            // 关键：sws_scale 会检查 dst[0]/dst[1]/dst[2] 三个槽（swscale.c: "bad dst image pointers"）。
            // 只给 1 个指针会导致 C 侧越界读。提供 4 个槽（同指针重复填充，packed RGBA 只用 dst[0]）。
            this.audioPointerPtr = new PointerPointer<>(audioPointer, audioPointer, audioPointer, audioPointer);
        }

        public void resetPosition() {audioBuffer.position(0).limit(capacity);
        }
    }


    /** 空闲槽池：可借出（解码线程取、渲染线程还） */
    private final BlockingQueue<ImageBufferSlot> imageFreePool;
    /** 就绪队列：已解码待上传（解码线程发布、渲染线程获取） */
    private final BlockingQueue<ImageBufferSlot> imageReadyQueue;

    private final BlockingQueue<AudioBufferSlot> audioFreePool;
    private AudioBufferSlot tmpAudioSlot;

    private final BlockingQueue<AudioBufferSlot> audioReadyQueue;

    public final int width;
    public final int height;
    public final int imageDataCapacity;
    public final int audioDataCapacity;
    public final int imageBufferCount;
    public final int audioBufferCount;
    public boolean audioEND = false;

    /**
     * @param imageBufferCount   视频帧预分配槽总数（建议 2~3，即双/三缓冲流水线）
     * @param audioBufferCount   音频帧预分配槽总数
     * @param videoCodecContext         视频解码器上下文
     * @param audioCodecContext        音频解码器上下文
     */
    public FrameBufferPoolWithQueue(int imageBufferCount, int audioBufferCount, AVCodecContext videoCodecContext, AVCodecContext audioCodecContext) {
        if (imageBufferCount < 1 || audioBufferCount < 1) throw new IllegalArgumentException("bufferCount must be >= 1");
        this.width = videoCodecContext.width();
        this.height = videoCodecContext.height();
        this.imageDataCapacity = width * height * 4;
        this.imageBufferCount = imageBufferCount;
        this.audioBufferCount = audioBufferCount;
        this.imageFreePool = new LinkedBlockingQueue<>(imageBufferCount);
        this.imageReadyQueue = new LinkedBlockingQueue<>(imageBufferCount);
        this.poolSeq = POOL_SEQ.incrementAndGet();
        // 预分配全部槽，之后生命周期内零 new
        for (int i = 0; i < imageBufferCount; i++) {
            this.imageFreePool.offer(new ImageBufferSlot(this.imageDataCapacity));
        }
        if(audioCodecContext != null) {
            // ★ 每帧一槽：swr_convert 单帧输出（AAC 1024 样本≈8KB、MP3 1152≈4.6KB）
            //   给 16384 裕量，一帧放得下；MC read() 拿到 8~16KB 一帧即可上传播放。
            this.audioDataCapacity = 16384 * 4;
            this.audioFreePool = new LinkedBlockingQueue<>(audioBufferCount);
            this.audioReadyQueue = new LinkedBlockingQueue<>(audioBufferCount);
            // ★ 修复：for 末尾不能有分号！旧代码 `for(...);{...}` 循环体为空、块只执行一次，
            //   导致 audioBufferCount 个槽实际只预分配 1 个 → ready 恒 1、startAudio 预滚永远不足。
            for (int i = 0; i < audioBufferCount; i++) {
                this.audioFreePool.offer(new AudioBufferSlot(this.audioDataCapacity));
            }
        }else {
            this.audioFreePool = null;
            this.audioDataCapacity = -1;
            this.audioReadyQueue = null;
        }
    }

    // ==================== 解码线程（生产者） ====================

    /** 借出一个空闲槽；无空闲时阻塞等待渲染线程归还。 */
    public ImageBufferSlot borrowImageBuffer() throws InterruptedException {
        ImageBufferSlot slot = imageFreePool.take();
        slot.resetPosition();
        return slot;
    }
    /** 借出一个空闲槽：按需选起始档，本档空则向上借大档，全部空则阻塞等待。 */
    public AudioBufferSlot borrowAudioBuffer(int size) throws InterruptedException {
        AudioBufferSlot slot = audioFreePool.take();
        slot.resetPosition();
        return slot;
    }

    /** 非阻塞借出音频槽：按需选起始档向上找，全空返回 null（调用方据此让出）。 */
    public AudioBufferSlot tryBorrowAudioBuffer() {
        if (this.tmpAudioSlot != null) return this.tmpAudioSlot;
        AudioBufferSlot slot = audioFreePool.poll();
        if (slot != null) {
            slot.resetPosition();
            slot.audioBuffer.clear();
            this.tmpAudioSlot = slot;
        }
        return slot;
    }

    /** 非阻塞借出；无空闲返回 null。 */
    public ImageBufferSlot tryBorrowImageBuffer() {
        ImageBufferSlot slot = imageFreePool.poll();
        if (slot != null) slot.resetPosition();
        return slot;
    }

    /** 解码完成，发布到就绪队列；队列满则背压阻塞（自动限流）。 */
    public void publishImageBuffer(ImageBufferSlot slot) throws InterruptedException {
        imageReadyQueue.put(slot);
    }
    public void publishAudioBuffer(AudioBufferSlot slot) throws InterruptedException {
        if (slot != null){
            ByteBuffer byteBuffer = slot.audioBuffer;
            int pos = byteBuffer.position();
            if (pos > 0){
                byteBuffer.flip();               // position=0, limit=pos（有效字节）
                this.tmpAudioSlot = null;        // 每帧一槽：立即清累积
                audioReadyQueue.put(slot);       // ★ 有数据即发布，不等满（MC read 立刻能取）
            }else {
                this.tmpAudioSlot = null;        // 空槽退池，不发布
                releaseAudioBuffer(slot);
            }
        }else {
            if (this.tmpAudioSlot != null){
                AudioBufferSlot s = this.tmpAudioSlot;
                this.tmpAudioSlot = null;
                publishAudioBuffer(s);           // EOF 尾部有数据则发布
            }
        }
    }
    // ==================== 渲染线程（消费者） ====================

    /** 获取一张已解码待上传的帧；无帧时阻塞等待解码线程。 */
    public ImageBufferSlot acquireImage() throws InterruptedException {
        return imageReadyQueue.take();
    }
    public AudioBufferSlot acquireAudio() throws InterruptedException {
        return audioReadyQueue.take();
    }
    /** 非阻塞获取；无就绪帧返回 null。 */
    public ImageBufferSlot tryAcquireImageBuffer() {
        return imageReadyQueue.poll();
    }

    /** 非破坏性查看队头就绪帧（PTS 门控用，不弹出）。 */
    public ImageBufferSlot peekImageBuffer() {
        return imageReadyQueue.peek();
    }

    public AudioBufferSlot tryAcquireAudioBuffer() {
        return audioReadyQueue.poll();
    }

    /** GL 上传完成，归还到空闲池。imageFreePool 容量=总数，offer 恒成功。 */
    public void releaseImageBuffer(ImageBufferSlot slot) {
        slot.resetPosition();
        imageFreePool.offer(slot);
    }
    public void releaseAudioBuffer(AudioBufferSlot slot) {
        slot.resetPosition();
        this.audioFreePool.offer(slot);
    }
    // ==================== 状态查询 ====================

    public boolean imageHasReady() {
        return !imageReadyQueue.isEmpty();
    }

    /** 视频就绪队列是否已满（解码侧应先让出，等消费者释放槽）。 */
    public boolean isImageReadyFull() {
        return imageReadyQueue.remainingCapacity() == 0;
    }

    /** 音频就绪队列是否已满（解码侧应先让出，等消费者释放槽）。 */
    public boolean isAudioReadyFull() {
        return audioReadyQueue.remainingCapacity() == 0;
    }

    /** 视频空闲槽是否已耗尽（解码侧据此让出，防止无槽接收解码帧而丢帧）。 */
    public boolean isImageFreeEmpty() {
        return imageFreePool.isEmpty();
    }

    /** 音频空闲槽是否已耗尽。 */
    public boolean isAudioFreeEmpty() {
        return audioFreePool != null && audioFreePool.isEmpty();
    }

    public int getImageReadyCount() {
        return imageReadyQueue.size();
    }

    /** 音频就绪队列当前积压槽数（startAudio 预滚检查用）。 */
    public int getAudioReadyCount() {
        return audioReadyQueue != null ? audioReadyQueue.size() : 0;
    }

    public int getImageFreeCount() {
        return imageFreePool.size();
    }

    /** 音频空闲池当前槽数（drainAudioBuffer 是否因无空闲槽而卡住的判定用）。 */
    public int getAudioFreeCount() {
        return audioFreePool != null ? audioFreePool.size() : 0;
    }

    /** 释放所有槽的对齐内存（nmemAlignedAlloc 的成对释放）。 */
    public void close() {
        ImageBufferSlot imageSlot;
        AudioBufferSlot audioSlot;
        while ((imageSlot = imageFreePool.poll()) != null) {
            if (imageSlot.address != 0L) MemoryUtil.nmemAlignedFree(imageSlot.address);
        }
        while ((imageSlot = imageReadyQueue.poll()) != null) {
            if (imageSlot.address != 0L) MemoryUtil.nmemAlignedFree(imageSlot.address);
        }
        while ((audioSlot = audioFreePool.poll()) != null) {
            if (audioSlot.address != 0L) MemoryUtil.nmemAlignedFree(audioSlot.address);
        }
        while ((audioSlot = audioReadyQueue.poll()) != null) {
            if (audioSlot.address != 0L) MemoryUtil.nmemAlignedFree(audioSlot.address);
        }
    }
}
