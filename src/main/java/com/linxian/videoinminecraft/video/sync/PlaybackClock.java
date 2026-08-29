package com.linxian.videoinminecraft.video.sync;

/**
 * 播放时钟：以音频为主时钟，视频按 PTS 对齐。
 *
 * <p>锚点放在 <b>Channel.play()（OpenAL 真正开始播放）</b> 时刻，而非首次 read：
 * MC 在 attachBufferStream 阶段会 pumpBuffers(4) 预缓存 4 个 buffer（尚未播放），
 * 若以首次 read 锚定会因主线程调度延迟导致提前几十毫秒，音画偏移。
 *
 * <p>播放位置 = 首音频帧 PTS（归一化≈0）+ play 时刻后的真实流逝时间 <b>减去暂停时长</b>。
 *
 * <p>暂停感知：音频为主时钟，音频暂停（Channel.pause()）即播放暂停，暂停期间
 * wall-clock 继续走但媒体不前进，故必须把暂停时段从流逝中扣除，否则恢复后
 * nowUs 远超视频帧 PTS 导致疯狂快进追赶（deltaUs 大负数）。
 *
 * <p>无音轨 / 音频尚未 play 时不门控（直接上屏），避免 pre-roll 黑屏。
 */
public class PlaybackClock {

    /** 音轨是否存在；无音轨则时钟永不启用。 */
    private final boolean hasAudio;

    /** 音频主时钟锚点：play 时刻的媒体 PTS（微秒）与墙钟（纳秒）。 */
    private volatile long audioStartPtsUs = -1;
    private volatile long audioStartNano = -1;

    /** 暂停累计总时长（纳秒），从流逝时间中扣除。 */
    private final java.util.concurrent.atomic.AtomicLong pauseOffsetNano =
            new java.util.concurrent.atomic.AtomicLong();
    /** 当前暂停起始墙钟（纳秒）；-1 表示未在暂停。 */
    private volatile long pauseStartNano = -1;

    /** 允许视频帧早于音频主时钟仍上屏的容差（微秒）：吸收上传抖动，20ms ≈ 半帧(25fps)。 */
    private static final long EARLY_TOLERANCE_US = 20_000L;

    public PlaybackClock(boolean hasAudio) {
        this.hasAudio = hasAudio;
    }

    /**
     * 音频主时钟锚定：OpenAL 真正开始播放（Channel.play()）时调用。
     * firstPtsUs = 首个进入队列的音频帧 PTS（归一化到 0 起点）。
     * 幂等：只以第一次为准，避免重复 play 重置。
     */
    public void onAudioPlayStart(long firstPtsUs) {
        if (audioStartNano == -1) {
            audioStartPtsUs = firstPtsUs;
            audioStartNano = System.nanoTime();
        }
    }

    /** 音频暂停（Channel.pause()）：冻结时钟。幂等。 */
    public void pause() {
        if (pauseStartNano == -1) {
            pauseStartNano = System.nanoTime();
        }
    }

    /** 音频恢复（Channel.unpause()）：把暂停时段计入 offset，继续推进。幂等。 */
    public void resume() {
        if (pauseStartNano != -1) {
            long pausedNano = System.nanoTime() - pauseStartNano;
            pauseOffsetNano.addAndGet(pausedNano);
            pauseStartNano = -1;
        }
    }

    /** 当前媒体播放位置（微秒）：播放总时长扣除全部暂停时段。 */
    public long nowUs() {
        if (audioStartNano == -1) return 0;
        long nowNano = System.nanoTime();
        long elapsedNano = nowNano - audioStartNano - pauseOffsetNano.get();
        if (pauseStartNano != -1) {
            elapsedNano -= (nowNano - pauseStartNano);   // 正在暂停中：暂停段也不计
        }
        return audioStartPtsUs + elapsedNano / 1000L;
    }

    /** 该帧是否该上屏：无音轨 / 音频尚未播放 → 恒 true；否则按 PTS 与主时钟对齐。 */
    public boolean shouldRender(long framePtsUs) {
        if (!hasAudio || audioStartNano == -1) return true;
        return framePtsUs <= nowUs() + EARLY_TOLERANCE_US;
    }
}