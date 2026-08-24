package com.linxian.videoinminecraft.video;

import com.linxian.videoinminecraft.video.consumer.AudioConsumer;
import com.linxian.videoinminecraft.video.consumer.VideoConsumer;
import com.linxian.videoinminecraft.video.sync.PlaybackClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 视频画面层：负责创建纹理 + 组装播放时钟与双消费者（视频/音频），
 * 并把"取帧 → PTS 判定 → 上传"完全交给 {@link VideoConsumer}。
 */
public class VideoScreen {
    private final DynamicTexture dynamicTexture;
    public final ResourceLocation textureLocation;
    public final int videoWidth;
    public final int videoHeight;

    private final VideoConsumer videoConsumer;
    private final AudioConsumer audioConsumer;
    private final PlaybackClock clock;

    public VideoScreen(VideoDecoder videoDecoder) {
        VideoDecoder.VideoMeta meta = videoDecoder.getVIDEOMETA();
        this.videoWidth = meta.getWIDTH();
        this.videoHeight = meta.getHEIGHT();
        this.dynamicTexture = new DynamicTexture(videoWidth, videoHeight, false);
        this.textureLocation = Minecraft.getInstance().getTextureManager().register("video_frame", dynamicTexture);

        var pool = videoDecoder.getGrabber().getPool();
        this.clock = new PlaybackClock();
        this.videoConsumer = new VideoConsumer(pool, clock, dynamicTexture.getId(), videoWidth, videoHeight);
        // 有音频流才建音频消费者（它也是主时钟提供者）；无音频时回退系统时钟
        this.audioConsumer = videoDecoder.getGrabber().hasAudio() ? new AudioConsumer(pool, clock) : null;
    }

    /** 渲染线程每帧调用：推进音视频消费，只负责视觉上传（GL）与播放。 */
    public void updateVideoTexture() {
        if (audioConsumer != null) audioConsumer.render(); // 喂 OpenAL；无槽/未启动自动跳过
        videoConsumer.render();                            // 取视频帧→PTS→GL 上传/丢帧
    }

    /**
     * 由 {@code SoundEngineLoadEvent} 触发（在主线程）：MC SoundEngine 已 AL.create()，
     * OpenAL 上下文就绪，转发给音频消费者建 buffer/source。
     */
    public void onSoundEngineReady() {
        if (audioConsumer != null) audioConsumer.onOpenALReady();
    }

    /** 关闭时释放 OpenAL 资源。 */
    public void dispose() {
        if (audioConsumer != null) audioConsumer.dispose();
    }
}