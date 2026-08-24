package com.linxian.videoinminecraft.client;

import com.linxian.videoinminecraft.VideoInMinecraft;
import com.linxian.videoinminecraft.video.VideoDecoder;
import com.linxian.videoinminecraft.video.VideoScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.lwjgl.glfw.GLFW;



import static com.linxian.videoinminecraft.Register.*;
@OnlyIn(Dist.CLIENT)
public class Client {
    public Client(){}
    private boolean ACTIVE = false;
    public VideoDecoder videoDecoder;
    public VideoScreen videoScreen;
    /** OpenAL 上下文是否已就绪（SoundEngineLoadEvent 置位）。 */
    public boolean soundEngineReady = false;
    public static VideoDecoder create(String name){
        VideoInMinecraft.LOGGER.debug("2");
        VideoDecoder.CreateDecoderResult result = VideoDecoder.createVideoDecode(name);
        VideoInMinecraft.LOGGER.debug(result.resultReason().getReason());
        if(result.isSuccess()) return result.videoDecoder();
        throw new RuntimeException(result.resultReason().getReason());
    }

    /**
     * 创建视频播放器：解码器 → 等 ready → 主线程建 VideoScreen → 自动播放。
     * 供客户端启动与进入游戏（LoggingIn）时调用。
     */
    public synchronized void startVideoPlayback() {
        if (this.videoDecoder != null) return; // 已有播放器
        VideoDecoder videoDecoder = create("1.mp4");
        this.videoDecoder = videoDecoder;
        // 解码构造即就绪，但保持原有异步等待语义，等待 READY 后回到主线程创建画布
        new Thread(() -> {
            VideoInMinecraft.LOGGER.debug("wait for ready");
            while (true) {
                // 解码器可能被 LoggingOut 销毁（DESTROYED），此时退出等待
                if (videoDecoder.isDestroyed()) {
                    VideoInMinecraft.LOGGER.debug("decoder destroyed while waiting, abort");
                    break;
                }
                if (videoDecoder.isReady()) {
                    VideoInMinecraft.LOGGER.debug("ready");
                    VideoInMinecraft.LOGGER.debug("back to main thread");
                    Minecraft.getInstance().tell(() -> {
                        VideoScreen screen = new VideoScreen(videoDecoder);
                        VideoInMinecraft.client.videoScreen = screen;
                        // 双向兜底：SoundEngineLoadEvent 可能早于 VideoScreen 创建；
                        // 若已就绪（事件先触发过），创建后立即补初始化 OpenAL 资源
                        if (VideoInMinecraft.client.soundEngineReady) {
                            screen.onSoundEngineReady();
                        }
                        VideoInMinecraft.LOGGER.debug("task created");
                        // 进入世界后自动开始播放（L 键仍然可用）
                        if (Minecraft.getInstance().player != null) {
                            VideoInMinecraft.client.ACTIVE = true;
                        }
                    });
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    /** 停止播放并销毁全部解码资源（退出到主界面 LoggingOut / 客户端关闭时调用）。 */
    public synchronized void stopVideoPlayback() {
        this.ACTIVE = false;
        if (this.videoDecoder != null) {
            VideoInMinecraft.LOGGER.info("Stopping video playback and freeing decoders...");
            VideoDecoder.StopAll();
            this.videoDecoder = null;
        }
        // 先释放 OpenAL 等消费端资源，再置空引用
        if (this.videoScreen != null) {
            this.videoScreen.dispose();
            this.videoScreen = null;
        }
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        // 只在游戏世界内处理（或根据需要调整）
        if (Minecraft.getInstance().player == null) return;

        // 使用 consumeClick() 确保只触发一次
        if (startKey.consumeClick()) {
            VideoInMinecraft.LOGGER.debug("L");
            VideoInMinecraft.LOGGER.debug(String.valueOf(ACTIVE));
            ACTIVE = true;
            videoDecoder.PlayVideo();
            VideoInMinecraft.LOGGER.debug(String.valueOf(ACTIVE));
        }
        if (stopKey.consumeClick()) {
            VideoInMinecraft.LOGGER.debug(String.valueOf(ACTIVE));
            VideoInMinecraft.LOGGER.debug("I");
            ACTIVE = false;
            if (videoScreen != null) videoScreen.dispose();
            VideoInMinecraft.LOGGER.debug(String.valueOf(ACTIVE));
        }
    }
    @SubscribeEvent
    public void onGameShuttingDown(GameShuttingDownEvent event){
        // 客户端退出：停止并释放所有解码器（FFmpeg 上下文 + 管道池对齐内存 + 线程）
        VideoInMinecraft.LOGGER.info("Client shutting down, cleaning up video decoders...");
        VideoDecoder.StopAll();
        if (videoScreen != null) {
            videoScreen.dispose();
            videoScreen = null;
        }
        videoDecoder = null;
        ACTIVE = false;
    }

    /** 退出到主界面：停止播放并销毁解码器（FFmpeg 上下文/管道池/线程）。 */
    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        VideoInMinecraft.LOGGER.info("Logging out to main menu, freeing video decoders...");
        // stopVideoPlayback 内部已统一处理 videoScreen.dispose()
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
        videoScreen.updateVideoTexture();

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int videoWidth = videoScreen.videoWidth;
        int videoHeight = videoScreen.videoHeight;

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
                videoScreen.textureLocation,
                offsetX, offsetY,
                renderWidth, renderHeight,
                0.0f, 0.0f,
                videoWidth, videoHeight,
                videoWidth, videoHeight
        );
    }

}
