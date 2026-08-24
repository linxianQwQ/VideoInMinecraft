package com.linxian.videoinminecraft.video.consumer;

import com.linxian.videoinminecraft.video.sync.PlaybackClock;
import com.linxian.videoinminecraft.video.tool.FrameBufferPoolWithQueue;
import org.lwjgl.openal.AL10;

/**
 * 音频消费者：取音频槽 → alBufferData 投喂 OpenAL 流式队列 → 归还槽。
 *
 * <p>同时作为播放主时钟：通过 {@code AL_BYTE_OFFSET} 估算已播放位置（微秒），
 * 注入 {@link PlaybackClock}，视频侧据此做 PTS 同步。
 */
public class AudioConsumer {

    private static final int BUFFER_COUNT = 3;
    private static final int SAMPLE_RATE = 44100;
    private static final int SAMPLE_SIZE = 4;          // 立体声 16bit = 4 字节/样本

    private final FrameBufferPoolWithQueue pool;
    private final PlaybackClock clock;

    private final int[] buffers = new int[BUFFER_COUNT];
    private int source;
    private boolean available = false;
    /** 是否已尝试过初始化（true 后不再重试）。 */
    private boolean attempted = false;

    private int ringIndex = 0;
    private boolean playing = false;

    public AudioConsumer(FrameBufferPoolWithQueue pool, PlaybackClock clock) {
        this.pool = pool;
        this.clock = clock;
        // ★ 不在构造/探测时触碰任何 AL API！
        //   OpenAL 上下文由 Minecraft SoundEngine（资源重载阶段 AL.create()）创建；
        //   在它之前调用任何 AL API 都会触发 AL$ICDStatic 类初始化失败（
        //   "No ALCapabilities instance has been set"），该静态类一旦初始化失败
        //   整个 JVM 的 AL 永久 NoClassDefFoundError，连 MC 自己的声音系统也会崩。
        //   因此用 SoundEngineLoadEvent 显式驱动（onOpenALReady）：
        //   该事件在 MC SoundEngine 完成 AL.create() 后触发，此时 OpenAL 必然就绪。
    }

    /**
     * 由 {@code SoundEngineLoadEvent} 触发：MC 已创建 OpenAL 上下文，此时建 buffer/source 才安全。
     * 若上下文重建（资源重载 / F3+T），先释放旧资源再重建。
     */
    public void onOpenALReady() {
        if (available) {
            // OpenAL 上下文已重建（资源 reload）→ 旧 buffer/source 已随旧 device 销毁，释放重建
            try {
                AL10.alSourceStop(source);
                AL10.alDeleteSources(source);
                for (int b : buffers) if (b != 0) AL10.alDeleteBuffers(b);
            } catch (Throwable ignored) {
            }
            available = false;
            attempted = false;
            ringIndex = 0;
            playing = false;
        }
        if (attempted) return;
        attempted = true;
        try {
            for (int i = 0; i < BUFFER_COUNT; i++) buffers[i] = AL10.alGenBuffers();
            source = AL10.alGenSources();
        } catch (Throwable t) {
            return;
        }
        available = source != 0;
        if (available) {
            // 音频为主时钟：每帧由 render 里更新 provider 读取位置
            clock.setAudioClockProvider(() -> currentPlaybackUs());
        }
    }

    /** 渲染线程/驱动线程每帧调用：取音频槽投喂；无槽则跳过。 */
    public void render() {
        if (!available) return;
        FrameBufferPoolWithQueue.AudioBufferSlot slot = pool.tryAcquireAudioBuffer();
        if (slot == null) return;

        try {
            // 回收已播完的 buffer，保持队列不无限增长
            int processedNow = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            for (int i = 0; i < processedNow; i++) {
                AL10.alSourceUnqueueBuffers(source);
            }

            int buf = buffers[ringIndex];
            ringIndex = (ringIndex + 1) % BUFFER_COUNT;

            AL10.alBufferData(buf, AL10.AL_FORMAT_STEREO16, slot.audioBuffer, slot.length);
            AL10.alSourceQueueBuffers(source, buf);

            if (!playing) {
                AL10.alSourcePlay(source);
                playing = true;
            }
        } finally {
            pool.releaseAudioBuffer(slot); // 必须归还
        }
    }

    /** 当前已播放位置（微秒）。立体声16bit：字节偏移 / 4 = 样本 / 44100 = 秒。 */
    private long currentPlaybackUs() {
        if (!playing) return 0;
        // AL_BYTE_OFFSET = 0x1010（部分 LWJGL 版本未导出该常量名，直接使用标准值）
        int byteOffset = AL10.alGetSourcei(source, 0x1010);
        if (byteOffset < 0) byteOffset = 0;
        long samples = byteOffset / (long) SAMPLE_SIZE;
        return samples * 1_000_000L / SAMPLE_RATE;
    }

    public void dispose() {
        if (!available) return;
        AL10.alSourceStop(source);
        AL10.alDeleteSources(source);
        for (int b : buffers) AL10.alDeleteBuffers(b);
        available = false;
    }
}