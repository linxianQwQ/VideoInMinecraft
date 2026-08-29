package com.linxian.videoinminecraft.video;

import com.linxian.videoinminecraft.VideoInMinecraft;
import com.linxian.videoinminecraft.video.play.AudioPlay;
import com.linxian.videoinminecraft.video.play.VideoConsumer;
import com.linxian.videoinminecraft.video.sync.PlaybackClock;
import com.linxian.videoinminecraft.video.tool.FrameBufferPoolWithQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 视频画面层："送入"模型下只负责纹理 + 视频消费者，
 * 音频完全交给 MC SoundEngine——本类只负责启动/停止
 * {@link AudioPlay.AudioSoundInstance}，不再碰任何 AL。
 */
public class VideoScreen {
    private final DynamicTexture dynamicTexture;
    public final ResourceLocation textureLocation;
    public final int videoWidth;
    public final int videoHeight;

    private final VideoConsumer videoConsumer;
    private final PlaybackClock clock;
    private final FrameBufferPoolWithQueue pool;

    /** 音频：送入 MC 的 SoundInstance（仅在有音轨时创建）。 */
    private AudioPlay.AudioSoundInstance soundInstance;
    private boolean audioStarted = false;
    private final boolean hasAudio;

    public VideoScreen(VideoDecoder videoDecoder) {
        VideoDecoder.VideoMeta meta = videoDecoder.getVIDEOMETA();
        this.videoWidth = meta.getWIDTH();
        this.videoHeight = meta.getHEIGHT();
        this.hasAudio = videoDecoder.getGrabber().hasAudio();
        this.dynamicTexture = new DynamicTexture(videoWidth, videoHeight, false);
        this.textureLocation = Minecraft.getInstance().getTextureManager().register("video_frame", dynamicTexture);

        this.pool = videoDecoder.getGrabber().getPool();
        this.clock = new PlaybackClock(this.hasAudio);
        this.videoConsumer = new VideoConsumer(pool, clock, dynamicTexture.getId(), videoWidth, videoHeight);
    }

    /**
     * 启动视频 PCM 送入 MC SoundEngine（幂等）。
     * 由 onSoundEngineReady 调用（此时 MC SoundEngine 已加载，SoundManager.play 才有效）。
     */
    public void startAudio() {
        if (audioStarted || !hasAudio) return;
        audioStarted = true;
        // ★ 预滚改后台线程：主线程绝不能 sleep——否则 L 键后渲染线程卡住 → 视频冻结。
        //   后台等音频槽攒够（attach 预缓存 pumpBuffers(4) 必须立即可取，否则 channel 0 buffer
        //   自杀 → 无声）；期间解码线程持续产出，readyQueue 趁机攒满。
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 2000;
            while (pool.getAudioReadyCount() < 6 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            // 回主线程创建并播放（SoundManager.play 需主线程）
            Minecraft.getInstance().tell(() -> {
                if (!audioStarted) return;                 // 已被 dispose 中止
                if (pool.getAudioReadyCount() < 6) {
                    VideoInMinecraft.LOGGER.warn("startAudio gave up: audio pipeline too slow, ready={}", pool.getAudioReadyCount());
                }
                this.soundInstance = new AudioPlay.AudioSoundInstance(this.pool, this.clock);
                Minecraft.getInstance().getSoundManager().play(this.soundInstance);
            });
        }, "video-audio-preroll").start();
    }

    /** 渲染线程每帧调用：只推进视频消费（GL 上传）。音频由 SoundEngine 线程自动拉取。 */
    public void updateVideoTexture() {
        videoConsumer.render();
    }

    /**
     * 由 {@code SoundEngineMixin}（reload TAIL）触发：MC SoundEngine 已就绪。
     * ★ 不在此启动音频：read() 阻塞等槽，若解码线程未 PlayVideo 会卡死 SoundEngine。
     *   音频启动统一由 L 键（PlayVideo 之后）调用 {@link #startAudio()}。
     */
    public void onSoundEngineReady() {
        // 仅标记；实际启动在 L 键 startAudio()
    }

    /** 关闭/停止时：停掉送入 MC 的音频实例。 */
    public void dispose() {
        if (soundInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(soundInstance);
            soundInstance = null;
        }
        audioStarted = false;
    }
}