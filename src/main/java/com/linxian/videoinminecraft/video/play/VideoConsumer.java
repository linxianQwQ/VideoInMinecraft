package com.linxian.videoinminecraft.video.play;

import com.linxian.videoinminecraft.video.sync.PlaybackClock;
import com.linxian.videoinminecraft.video.tool.FrameBufferPoolWithQueue;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 视频消费者：渲染线程每帧调用一次。
 * 按音频主时钟门控：到点才上屏，未到点等待（不弹出，留给后续渲染帧）。
 */
public class VideoConsumer {

    private final FrameBufferPoolWithQueue pool;
    private final PlaybackClock clock;
    private final int textureId;
    private final int width;
    private final int height;

    public VideoConsumer(FrameBufferPoolWithQueue pool, PlaybackClock clock, int textureId, int width, int height) {
        this.pool = pool;
        this.clock = clock;
        this.textureId = textureId;
        this.width = width;
        this.height = height;
    }

    /** 每渲染帧调用：按音频主时钟门控，到点才上屏，未到点等待（不弹出）。 */
    public void render() {
        // peek 队头（非破坏）：帧还没到点就不弹出，留给后续渲染帧
        FrameBufferPoolWithQueue.ImageBufferSlot head = pool.peekImageBuffer();
        if (head == null) return; // 无帧：跳过本帧（解码跟不上自然降帧）

        // 到点（或已过点、或音频尚未开始）→ 上屏；未来帧 → 等待音频主时钟追上
        if (!clock.shouldRender(head.ptsUs)) return;

        FrameBufferPoolWithQueue.ImageBufferSlot slot = pool.tryAcquireImageBuffer();
        if (slot == null) return;
        upload(slot);
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