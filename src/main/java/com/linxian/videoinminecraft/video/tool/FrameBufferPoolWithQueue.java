package com.linxian.videoinminecraft.video.tool;

import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MP2;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MP3;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AC3;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_DTS;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VORBIS;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_FLAC;




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

        ImageBufferSlot(int capacity) {
            this.capacity = capacity;
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
    /*
    public static final class AudioBufferMeta {
        private boolean extraDeta; //描述当前这个Meta是否有数据，仅用于第一层（因为这个需要常驻，跟随slot，必须创建）
        private int offset;
        private int length;
        public AudioBufferMeta audioBufferMeta; //下一个meta数据，==null则没有，可以被extraData忽略
        public AudioBufferMeta(){
            this.extraDeta = false;
            this.offset = 0;
            this.length = 0;
            this.audioBufferMeta = null;
        }
        public void reset(){
            this.extraDeta = false;
            this.offset = 0;
            this.length = 0;
            this.audioBufferMeta = null;
        }
        public boolean hasExtraData(){return this.extraDeta;}
        public int getOffset(){return this.offset;}
        public int getLength(){return this.length;}
        public void write(int offset,int length,AudioBufferMeta audioBufferMeta){
            this.extraDeta = true;
            this.offset = offset;
            this.length = length;
            this.audioBufferMeta = audioBufferMeta;
        }
        public void write(int offset,int length){
            this.write(offset,length,null);
        }
        public AudioBufferMeta writeChain(int offset,int length){
            AudioBufferMeta audioBufferMeta = new AudioBufferMeta();
            this.write(offset,length,audioBufferMeta);
            return audioBufferMeta;
        }
        public void writeChainLast(int offset,int length){
            this.write(offset,length);
        }
    }*/
    public static final class AudioBufferMeta{
        public boolean hasData;    // 本条是否有数据（常驻第一条天然为 true/主帧）
        public int    offset;      // 帧数据在槽 buffer 中的起始字节
        public int    length;      // 帧数据字节数
        public long   ptsUs;       // 本帧时间戳（微秒）——逐帧独立，消费端同步用
        public AudioBufferMeta(){
            this.reset();
        }
        public void reset() {
            this.hasData = false;
            this.offset = 0;
            this.length = 0;
            this.ptsUs = 0L;
        }

        public void write(int offset, int length, long ptsUs) {
            this.hasData = true;
            this.offset = offset;
            this.length = length;
            this.ptsUs = ptsUs;
        }

    }
    public static final class AudioBufferSlot{
        public final ByteBuffer audioBuffer;
        public final BytePointer audioPointer;
        public final PointerPointer audioPointerPtr;
        public final long address;
        public final int capacity;
        public final AudioBufferMeta extraBufferMeta = new AudioBufferMeta();
        public final int index;
        public int length;      // 帧数据字节数
        public long ptsUs;

        AudioBufferSlot(int capacity,int index) {
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
            this.index = index;
        }

        public void resetPosition() {audioBuffer.position(0).limit(capacity);
        }
    }


    /** 空闲槽池：可借出（解码线程取、渲染线程还） */
    private final BlockingQueue<ImageBufferSlot> imageFreePool;
    /** 就绪队列：已解码待上传（解码线程发布、渲染线程获取） */
    private final BlockingQueue<ImageBufferSlot> imageReadyQueue;

    private final BlockingQueue<AudioBufferSlot> audioFreePoolSmall;//index0
    private final int audioDataCapacitySmall;
    private final BlockingQueue<AudioBufferSlot> audioFreePoolCommon;//index1
    private final int audioDataCapacityCommon;
    private final BlockingQueue<AudioBufferSlot> audioFreePoolMiddle;//index2
    private final int audioDataCapacityMiddle;
    private final BlockingQueue<AudioBufferSlot> audioFreePoolLarge;//index3
    private final int audioDataCapacityLarge;
    private final List<BlockingQueue<AudioBufferSlot>> audioFreePoolList;
    private final List<Integer> audioCapacityIndex;

    private final BlockingQueue<AudioBufferSlot> audioReadyQueue;

    public final int width;
    public final int height;
    public final int imageDataCapacity;
    public final int imageBufferCount;
    public final int audioBufferCount;

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
        // 预分配全部槽，之后生命周期内零 new
        for (int i = 0; i < imageBufferCount; i++) {
            imageFreePool.offer(new ImageBufferSlot(this.imageDataCapacity));
        }
        if(audioCodecContext != null) {
            int codecID = audioCodecContext.codec_id();
            Set<Integer> constanceCapacity = Set.of(
                    AV_CODEC_ID_AAC,
                    AV_CODEC_ID_MP2,
                    AV_CODEC_ID_MP3,
                    AV_CODEC_ID_DTS,
                    AV_CODEC_ID_AC3
            );
            Set<Integer> varietyCapacity = Set.of(
                    AV_CODEC_ID_OPUS,
                    AV_CODEC_ID_VORBIS,
                    AV_CODEC_ID_FLAC
            );
            if (constanceCapacity.contains(codecID)) {
                this.audioDataCapacitySmall = -1;
                this.audioDataCapacityMiddle = -1;
                this.audioDataCapacityLarge = -1;
                this.audioFreePoolSmall = null;
                this.audioFreePoolMiddle = null;
                this.audioFreePoolLarge = null;
                this.audioDataCapacityCommon = audioCodecContext.frame_size();
                this.audioFreePoolCommon = new LinkedBlockingQueue<>(audioBufferCount);
                for (int i = 0; i < audioBufferCount; i++) {
                    audioFreePoolCommon.offer(new AudioBufferSlot(this.audioDataCapacityCommon,1));
                }
                this.audioReadyQueue = new LinkedBlockingQueue<>(this.audioBufferCount);
                this.audioFreePoolList = null;
                this.audioCapacityIndex = null;
            } else if (varietyCapacity.contains(codecID)) {
                //2:3:3:2->大池多一点，比例不用精确
                float part = (float) audioBufferCount / 10;
                int part_small_integer = (int) (part * 2);
                int part_common_integer = (int) (part * 3);
                int part_middle_integer = (int) (part * 3);
                int part_large_integer = (int) (part * 2);
                int tmpTotal = audioBufferCount - (part_common_integer + part_large_integer + part_middle_integer + part_small_integer);
                if (tmpTotal == 3) {
                    part_middle_integer += 2;
                    part_large_integer += 1;
                } else if (tmpTotal == 2) {
                    part_middle_integer += 1;
                    part_large_integer += 1;
                } else if (tmpTotal == 1) {
                    part_middle_integer += 1;
                }
                this.audioFreePoolSmall = new LinkedBlockingQueue<>(part_small_integer);
                this.audioFreePoolCommon = new LinkedBlockingQueue<>(part_common_integer);
                this.audioFreePoolMiddle = new LinkedBlockingQueue<>(part_middle_integer);
                this.audioFreePoolLarge = new LinkedBlockingQueue<>(part_large_integer);
                this.audioReadyQueue = new LinkedBlockingQueue<>(this.audioBufferCount);
                //需要优化获取算法
                final int CHANNELS = 2;
                final int SAMPLE_16bit = 2;
                final int BYTE_PER_SAMPLE = CHANNELS * SAMPLE_16bit;
                if (codecID == AV_CODEC_ID_OPUS) {
                    this.audioDataCapacitySmall = 120 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityCommon = 960 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityMiddle = 1920 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityLarge = 5760 * BYTE_PER_SAMPLE;
                } else if (codecID == AV_CODEC_ID_VORBIS) {
                    this.audioDataCapacitySmall = 64 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityCommon = 2048 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityMiddle = 4096 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityLarge = 8192 * BYTE_PER_SAMPLE;
                } else {//codecID == AV_CODEC_ID_FLAC
                    this.audioDataCapacitySmall = 1024 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityCommon = 4096 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityMiddle = 8192 * BYTE_PER_SAMPLE;
                    this.audioDataCapacityLarge = 65535 * BYTE_PER_SAMPLE;
                }
                this.audioFreePoolList = List.of(
                        this.audioFreePoolSmall,
                        this.audioFreePoolCommon,
                        this.audioFreePoolMiddle,
                        this.audioFreePoolLarge
                );
                this.audioCapacityIndex = List.of(
                        this.audioDataCapacitySmall,
                        this.audioDataCapacityCommon,
                        this.audioDataCapacityMiddle,
                        this.audioDataCapacityLarge
                );
                for (int i = 0; i < part_small_integer; i++) {
                    audioFreePoolSmall.offer(new AudioBufferSlot(this.audioDataCapacitySmall,0));
                }
                for (int i = 0; i < part_common_integer; i++) {
                    audioFreePoolCommon.offer(new AudioBufferSlot(this.audioDataCapacityCommon,1));
                }
                for (int i = 0; i < part_middle_integer; i++) {
                    audioFreePoolMiddle.offer(new AudioBufferSlot(this.audioDataCapacityMiddle,2));
                }
                for (int i = 0; i < part_large_integer; i++) {
                    audioFreePoolLarge.offer(new AudioBufferSlot(this.audioDataCapacityLarge,3));
                }
            } else {
                throw new IllegalArgumentException("Unsupported Audio Format!");
            }
        }else {
            this.audioFreePoolSmall = null;
            this.audioFreePoolCommon = null;
            this.audioFreePoolMiddle = null;
            this.audioFreePoolLarge = null;
            this.audioDataCapacitySmall = -1;
            this.audioDataCapacityCommon = -1;
            this.audioDataCapacityMiddle = -1;
            this.audioDataCapacityLarge = -1;
            this.audioFreePoolList = null;
            this.audioReadyQueue = null;
            this.audioCapacityIndex = null;
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
        if (audioFreePoolList == null) {
            // 固定帧大小编码器：单档（common）
            AudioBufferSlot slot = audioFreePoolCommon.take();
            slot.resetPosition();
            return slot;
        }
        // 起始档：第一个容量 >= size 的档
        int level = 0;
        for (int i = 0; i < audioCapacityIndex.size(); i++) {
            if (size > audioCapacityIndex.get(i)) level = i + 1;
        }
        // 本档及更大档轮询；全空则阻塞等待（背压=消费端归还），不走特批 new（保持零 new 池化）
        while (true) {
            for (int i = level; i < audioFreePoolList.size(); i++) {
                AudioBufferSlot slot = audioFreePoolList.get(i).poll();
                if (slot != null) {
                    slot.resetPosition();
                    return slot;
                }
            }
            Thread.sleep(1L); // 短暂让出，等消费端 release 归还
        }
    }

    /** 非阻塞借出音频槽：按需选起始档向上找，全空返回 null（调用方据此让出）。 */
    public AudioBufferSlot tryBorrowAudioBuffer(int size) {
        if (audioFreePoolList == null) {
            AudioBufferSlot slot = audioFreePoolCommon.poll();
            if (slot != null) slot.resetPosition();
            return slot;
        }
        int level = 0;
        for (int i = 0; i < audioCapacityIndex.size(); i++) {
            if (size > audioCapacityIndex.get(i)) level = i + 1;
        }
        for (int i = level; i < audioFreePoolList.size(); i++) {
            AudioBufferSlot slot = audioFreePoolList.get(i).poll();
            if (slot != null) {
                slot.resetPosition();
                return slot;
            }
        }
        return null; // 全空：队列满，外部驱动循环稍后再试
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
        audioReadyQueue.put(slot);
    }
    // ==================== 渲染线程（消费者） ====================

    /** 获取一张已解码待上传的帧；无帧时阻塞等待解码线程。 */
    public ImageBufferSlot acquire() throws InterruptedException {
        return imageReadyQueue.take();
    }

    /** 非阻塞获取；无就绪帧返回 null。 */
    public ImageBufferSlot tryAcquireImageBuffer() {
        return imageReadyQueue.poll();
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
        slot.extraBufferMeta.reset(); // 复位打包元信息，防脏数据复用
        if (audioFreePoolList != null) {
            this.audioFreePoolList.get(slot.index).offer(slot);
        } else {
            // 固定帧大小编码器：单档，直接回 common 池
            this.audioFreePoolCommon.offer(slot);
        }
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

    public int getImageReadyCount() {
        return imageReadyQueue.size();
    }

    public int getImageFreeCount() {
        return imageFreePool.size();
    }

    /** 释放所有槽的对齐内存（nmemAlignedAlloc 的成对释放）。 */
    public void close() {
        ImageBufferSlot imageSlot;
        while ((imageSlot = imageFreePool.poll()) != null) {
            if (imageSlot.address != 0L) MemoryUtil.nmemAlignedFree(imageSlot.address);
        }
        while ((imageSlot = imageReadyQueue.poll()) != null) {
            if (imageSlot.address != 0L) MemoryUtil.nmemAlignedFree(imageSlot.address);
        }

        // 释放音频槽（多档 + 单档两种情况）
        if (audioFreePoolList != null) {
            for (BlockingQueue<AudioBufferSlot> q : audioFreePoolList) {
                AudioBufferSlot s;
                while ((s = q.poll()) != null) {
                    if (s.address != 0L) MemoryUtil.nmemAlignedFree(s.address);
                }
            }
        } else if (audioFreePoolCommon != null) {
            AudioBufferSlot s;
            while ((s = audioFreePoolCommon.poll()) != null) {
                if (s.address != 0L) MemoryUtil.nmemAlignedFree(s.address);
            }
        }
        if (audioReadyQueue != null) {
            AudioBufferSlot s;
            while ((s = audioReadyQueue.poll()) != null) {
                if (s.address != 0L) MemoryUtil.nmemAlignedFree(s.address);
            }
        }
    }
}
