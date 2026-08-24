package com.linxian.videoinminecraft.video.sync;

import java.util.function.LongSupplier;

/**
 * 播放时钟：负责"现在该播到哪个时间点"。
 *
 * <p>设计：音频是连续流，播放位置精确 —— 以音频时钟为主设备；
 * 无音频（或音频未启动）时回退系统时钟 + 首帧 PTS 校准。
 *
 * <p>对外只提供两个核心问题：
 * <ul>
 *   <li>{@link #nowUs()}：当前播放位置（微秒）</li>
 *   <li>{@link #shouldRender(long)}：给定帧 PTS，是否应该现在上屏（早则不显示、到点/已过则显示）</li>
 * </ul>
 */
public class PlaybackClock {

    /** 音频时钟提供者：返回 OpenAL 已播放的音频位置（微秒）。null 表示无音频主时钟。 */
    private LongSupplier audioClockProvider;
    private boolean hasAudioClock = false;

    // 系统时钟回退相关的校准
    private long sysStartNano = -1;
    private long firstPtsUs = -1;

    /** 设置/更新音频主时钟。 */
    public void setAudioClockProvider(LongSupplier provider) {
        if (provider != null) {
            this.audioClockProvider = provider;
            this.hasAudioClock = true;
        } else {
            this.audioClockProvider = null;
            this.hasAudioClock = false;
        }
    }

    /** 以某个视频帧的 PTS 初始化系统时钟基线（首个视频帧到达时调用）。 */
    public void initSystemClock(long firstFramePtsUs) {
        if (sysStartNano == -1) {
            this.sysStartNano = System.nanoTime();
            this.firstPtsUs = firstFramePtsUs;
        }
    }

    /** 当前播放位置（微秒）：优先音频主时钟，否则系统时钟+首帧校准。 */
    public long nowUs() {
        if (hasAudioClock && audioClockProvider != null) {
            return audioClockProvider.getAsLong();
        }
        // 系统时钟回退：从第一帧起按真实流逝推进
        if (sysStartNano == -1) return 0;
        long elapsedUs = (System.nanoTime() - sysStartNano) / 1000L;
        return firstPtsUs + elapsedUs;
    }

    /**
     * 判断一帧是否该上屏。
     * <ul>
     *   <li>帧 PTS 还远早于当前时钟（早于 nowUs - EARLY_TOLERANCE）→ 不该显示（丢弃/留待更近帧）</li>
     *   <li>否则已到点或仅早一点点 → 该显示</li>
     * </ul>
     * @param framePtsUs 帧的 PTS（微秒）
     */
    public boolean shouldRender(long framePtsUs) {
        if (hasAudioClock && audioClockProvider != null) {
            return framePtsUs <= nowUs() + EARLY_TOLERANCE_US;
        }
        return framePtsUs <= nowUs() + EARLY_TOLERANCE_US;
    }

    /** 提前显示容差：避免 GL 上传比音频早出现微小的撕裂感（2ms 内都算准时）。 */
    private static final long EARLY_TOLERANCE_US = 2000L;
}