package com.linxian.videoinminecraft.video.consumer;

import com.linxian.videoinminecraft.video.sync.PlaybackClock;
import com.linxian.videoinminecraft.video.tool.FrameBufferPoolWithQueue;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 视频消费者：渲染线程每帧调用一次。
 * 取最新视频帧：PTS 已到点 → GL 直传上屏；早/晚 → 丢弃（丢帧吸收延迟，绝不空转等待）。
 */
public class VideoConsumer {

    private final FrameBufferPoolWithQueue pool;
    private final PlaybackClock clock;
    private final int textureId;
    private final int width;
    private final int height;
    private boolean firstFrameDone = false;

    public VideoConsumer(FrameBufferPoolWithQueue pool, PlaybackClock clock, int textureId, int width, int height) {
        this.pool = pool;
        this.clock = clock;
        this.textureId = textureId;
        this.width = width;
        this.height = height;
    }

    /** 每渲染帧调用：取一帧并按时钟决定是否上屏。 */
    public void render() {
        FrameBufferPoolWithQueue.ImageBufferSlot slot = pool.tryAcquireImageBuffer();
        if (slot == null) return; // 无帧：跳过本帧（解码跟不上自然降帧）

        if (!firstFrameDone) {
            // 首个视频帧建立系统时钟基线（音频未启动时使用）
            clock.initSystemClock(slot.ptsUs);
            firstFrameDone = true;
        }

        // 到点 → GL 上传；早/晚 → 丢弃（丢帧吸收延迟）。无论如何都归还槽。
        if (clock.shouldRender(slot.ptsUs)) {
            upload(slot);
        }
        pool.releaseImageBuffer(slot);
    }

    private void upload(FrameBufferPoolWithQueue.ImageBufferSlot slot) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        // sws_scale 输出行距 = width*4 紧凑布局，且绝不能设 ROW_LENGTH（GL 全局状态泄漏会污染 MC 纹理上传）
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL30.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0,
                0, 0,
                width, height,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                slot.imageBuffer
        );
    }
}