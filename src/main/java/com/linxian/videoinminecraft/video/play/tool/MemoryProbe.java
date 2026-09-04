package com.linxian.videoinminecraft.video.play.tool;

import com.linxian.videoinminecraft.VideoInMinecraft;

/**
 * 内存占用统计探针（易插拔调试用，不参与任何播放逻辑）。
 *
 * <p>统计三类指标（单位：字节）：
 * <ul>
 *   <li>瞬时总占用峰值 = max(解码前 + 解码后)</li>
 *   <li>平均解码后占用 = 帧槽池活跃字节平均值</li>
 *   <li>平均解码前占用 = packet 队列压缩字节平均值</li>
 * </ul>
 *
 * <p><b>删除方式</b>：全局搜索 {@code [MEMPROBE]} 标记，删除本类 +
 * {@link FFmpegFrameGrabber} 中的 probe 字段及 sample/print 调用即可。
 */
public class MemoryProbe {

    private long peakTotalBytes = 0L;
    private long sumPreDecodeBytes = 0L;
    private long sumPostDecodeBytes = 0L;
    private long sampleCount = 0L;
    private boolean printed = false;

    /** 每次 grab 采样一次当前占用。 */
    public void sample(long preDecodeBytes, long postDecodeBytes) {
        long total = preDecodeBytes + postDecodeBytes;
        if (total > peakTotalBytes) {
            peakTotalBytes = total;
        }
        sumPreDecodeBytes += preDecodeBytes;
        sumPostDecodeBytes += postDecodeBytes;
        sampleCount++;
    }

    /** 播放结束打印一次统计（幂等）。 */
    public void printOnEnd() {
        if (printed || sampleCount == 0) {
            return;
        }
        printed = true;
        long avgPre = sumPreDecodeBytes / sampleCount;
        long avgPost = sumPostDecodeBytes / sampleCount;
        VideoInMinecraft.LOGGER.info(
                "[MEMPROBE] peakTotal={}B avgPostDecode={}B avgPreDecode={}B samples={}",
                peakTotalBytes, avgPost, avgPre, sampleCount);
    }
}