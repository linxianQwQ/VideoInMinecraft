package com.linxian.videoinminecraft.client;

import com.linxian.videoinminecraft.VideoInMinecraft;
import com.linxian.videoinminecraft.video.VideoPlayer;
import com.linxian.videoinminecraft.video.play.VideoDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;


import static com.linxian.videoinminecraft.Register.*;
@OnlyIn(Dist.CLIENT)
public class Client {
    public Client(){}
    private boolean ACTIVE = false;
    public VideoPlayer videoPlayer;
    /** OpenAL 上下文是否已就绪（SoundEngineMixin 在 reload TAIL 置位）。 */
    public boolean soundEngineReady = false;
    /** MC 原版创建的 OpenAL context 句柄（SoundEngineMixin 捕获，供 AudioConsumer 强制绑定）。 */
    public long alContext = 0;
    public static VideoDecoder create(String name){
        VideoDecoder.CreateDecoderResult result = VideoDecoder.createVideoDecode(name);
        if(result.isSuccess()) return result.videoDecoder();
        throw new RuntimeException(result.resultReason().getReason());
    }

    /**
     * 创建视频播放器：解码器 → 等 ready → 主线程建 VideoScreen → 自动播放。
     * 供客户端启动与进入游戏（LoggingIn）时调用。
     */
    public synchronized void startVideoPlayback() {
        VideoPlayer video_player = new VideoPlayer("1.mp4");
        this.videoPlayer = video_player;
        // 解码构造即就绪，但保持原有异步等待语义，等待 READY 后回到主线程创建画布
        this.videoPlayer.startDecoder();
    }

    /** 停止播放并销毁全部解码资源（退出到主界面 LoggingOut / 客户端关闭时调用）。 */
    public synchronized void stopVideoPlayback() {
        this.ACTIVE = false;
        // 先释放 OpenAL 等消费端资源，再置空引用
        this.videoPlayer.close();
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        // 只在游戏世界内处理（或根据需要调整）
        if (Minecraft.getInstance().player == null) return;

        // 使用 consumeClick() 确保只触发一次
        if (startKey.consumeClick()) {
            ACTIVE = true;
            videoPlayer.playAudio();


        }
        if (stopKey.consumeClick()) {
            ACTIVE = false;videoPlayer.dispose();
        }
    }
    @SubscribeEvent
    public void onGameShuttingDown(GameShuttingDownEvent event){
        // 客户端退出：停止并释放所有解码器（FFmpeg 上下文 + 管道池对齐内存 + 线程）
        VideoInMinecraft.LOGGER.info("Client shutting down, cleaning up video decoders...");
        VideoDecoder.StopAll();
        ACTIVE = false;
    }

    /** 退出到主界面：停止播放并销毁解码器（FFmpeg 上下文/管道池/线程）。 */
    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        VideoInMinecraft.LOGGER.info("Logging out to main menu, freeing video decoders...");
        // stopVideoPlayback 内部已统一处理 videoScreen.dispose()
        if (this.videoPlayer == null) return;
        stopVideoPlayback();
    }

    /** 进入游戏：重新创建视频播放器并自动开始。 */
    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        VideoInMinecraft.LOGGER.info("Logging in, (re)starting video playback...");
        startVideoPlayback();
    }

    @SubscribeEvent
    public void onRender(RenderGuiEvent.Pre event){
        if (!ACTIVE) return;
        videoPlayer.playImage();

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int videoWidth = videoPlayer.width;
        int videoHeight = videoPlayer.height;

        // 计算缩放比例，使视频完全放入屏幕
        float scaleX = (float) screenWidth / videoWidth;
        float scaleY = (float) screenHeight / videoHeight;
        float scale = Math.min(scaleX, scaleY);  // 取较小值，确保全部显示

        int renderWidth = (int) (videoWidth * scale);
        int renderHeight = (int) (videoHeight * scale);

        // 居中偏移
        int offsetX = (screenWidth - renderWidth) / 2;
        int offsetY = (screenHeight - renderHeight) / 2;

        // 10 参数重载：x,y=目标位置；blitWidth/Height=目标缩放尺寸；
        // u,v=采样起点；uWidth/vHeight=采样区域尺寸(必须用完整视频尺寸，否则裁切)；
        // textureWidth/Height=纹理总尺寸(用于 uv 归一化)
        guiGraphics.blit(
                videoPlayer.textureLocation,
                offsetX, offsetY,
                renderWidth, renderHeight,
                0.0f, 0.0f,
                videoWidth, videoHeight,
                videoWidth, videoHeight
        );
    }

}
