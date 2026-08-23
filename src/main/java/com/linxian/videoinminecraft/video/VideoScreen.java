package com.linxian.videoinminecraft.video;

import com.linxian.videoinminecraft.video.tool.FrameBufferPoolWithQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public class VideoScreen {
    private final DynamicTexture dynamicTexture;
    public final ResourceLocation textureLocation;
    public final int videoWidth;
    public final int videoHeight;
    private final VideoDecoder videoDecoder;

    public VideoScreen(VideoDecoder videoDecoder) {
        this.videoDecoder = videoDecoder;
        VideoDecoder.VideoMeta meta = videoDecoder.getVIDEOMETA();
        this.videoWidth = meta.getWIDTH();
        this.videoHeight = meta.getHEIGHT();
        this.dynamicTexture = new DynamicTexture(videoWidth, videoHeight, false);
        this.textureLocation = Minecraft.getInstance().getTextureManager().register("video_frame", dynamicTexture);
    }

    /**
     * 渲染线程（GUI 渲染阶段）：从管道池取一帧 → GL 直传纹理（CPU→GPU 一次拷贝）→ 归还槽。
     * 全过程不经过 NativeImage，数据零中间副本。
     */
    public void updateVideoTexture() {
        FrameBufferPoolWithQueue pool = videoDecoder.getGrabber().getPool();
        // 非阻塞取帧：无就绪帧则跳过本渲染帧（解码线程持续填充 readyQueue，背压自动限流）
        FrameBufferPoolWithQueue.BufferSlot slot = pool.tryAcquire();
        if (slot == null) return;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, dynamicTexture.getId());
            // sws_scale 输出本身行距 = width*4（紧凑），GL 默认 ROW_LENGTH=0 即按 width 读取，
            // 只需 UNPACK_ALIGNMENT=4。绝不能设置 GL_UNPACK_ROW_LENGTH：
            // 它是 GL 全局状态，会泄漏污染 Minecraft 后续所有纹理上传（如 ESC 暂停菜单），导致越界读写崩溃。
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL30.glTexSubImage2D(
                    GL11.GL_TEXTURE_2D, 0,
                    0, 0,
                    videoWidth, videoHeight,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                    slot.buffer
            );
        } finally {
            pool.release(slot); // 归还空闲槽，解码线程可继续写入
        }
    }
}