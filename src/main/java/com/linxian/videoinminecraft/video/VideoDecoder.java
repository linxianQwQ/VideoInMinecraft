package com.linxian.videoinminecraft.video;

import com.linxian.videoinminecraft.VideoInMinecraft;
import com.linxian.videoinminecraft.video.tool.FFmpegFrameGrabber;
import net.neoforged.fml.loading.FMLLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;

public class VideoDecoder {
    public enum CreateDecoderResultReason {
        SUCCESS("Success!"),
        FULL("Already exist too many decoder!"),
        FILE_NOT_EXIST("The Video File doesn't exist!"),;

        private final String reason;
        CreateDecoderResultReason(String reason) { this.reason = reason; }
        public String getReason() { return reason; }
    }

    public record CreateDecoderResult(boolean isSuccess, CreateDecoderResultReason resultReason, VideoDecoder videoDecoder) {
        @Override
        public VideoDecoder videoDecoder() {
            if (isSuccess) return videoDecoder;
            throw new IllegalStateException("The Video Decoder created unsuccessfully!");
        }
    }

    private static final int MAX_VIDEO_DECODER = 2;
    private static final AtomicInteger ACTIVE_VIDEO_DECODER = new AtomicInteger(0);
    private static final Path VIDEO_ROOT = FMLLoader.getGamePath().resolve("config").resolve(VideoInMinecraft.MOD_ID);

    public static boolean isFull() { return ACTIVE_VIDEO_DECODER.get() == MAX_VIDEO_DECODER; }

    public static CreateDecoderResult createVideoDecode(String videoName) {
        if (isFull()) return new CreateDecoderResult(false, CreateDecoderResultReason.FULL, null);
        if (!Files.exists(VIDEO_ROOT)) {
            try {
                Files.createDirectories(VIDEO_ROOT);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        Path videoPath = VIDEO_ROOT.resolve(videoName);
        if (!Files.exists(videoPath))
            return new CreateDecoderResult(false, CreateDecoderResultReason.FILE_NOT_EXIST, null);

        FFmpegFrameGrabber grabber;
        try {
            // JavaCPP 直控 FFmpeg：构造即打开解码器，sws_scale 直写外部 direct buffer（align=1 紧凑 RGBA）
            grabber = new FFmpegFrameGrabber(videoPath, AV_PIX_FMT_RGBA);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        VideoDecoder videoDecoder = new VideoDecoder(grabber, videoPath);
        CreateDecoderResult result = new CreateDecoderResult(true, CreateDecoderResultReason.SUCCESS, videoDecoder);

        ACTIVE_VIDEO_DECODER.incrementAndGet();
        LIVE_DECODERS.put(videoDecoder, Boolean.TRUE);
        return result;
    }

    private static final ConcurrentHashMap<VideoDecoder, Boolean> LIVE_DECODERS = new ConcurrentHashMap<>(2);

    public static void StopAll() {
        List<VideoDecoder> snapshot = new ArrayList<>(LIVE_DECODERS.keySet());
        for (VideoDecoder decoder : snapshot) {
            try {
                decoder.destroy();
            } catch (Exception e) {
                VideoInMinecraft.LOGGER.error("destroy decoder failed", e);
            }
        }
        LIVE_DECODERS.clear();
    }

    public enum State {
        READY,
        PLAYING,
        PAUSE,
        DESTROYED
    }

    private volatile State current = State.READY;

    public State get() { return current; }
    public boolean isDestroyed() { return current == State.DESTROYED; }
    public boolean isPause() { return current == State.PAUSE; }
    public boolean isPlaying() { return current == State.PLAYING; }
    public boolean isReady() { return current == State.READY; }
    public boolean isSafeForGetData() {
        return current == State.READY || current == State.PLAYING || current == State.PAUSE;
    }

    private synchronized void State_READY() { if (current == State.PLAYING) current = State.READY; }
    private synchronized void State_PLAYING() { if (current == State.READY || current == State.PAUSE) current = State.PLAYING; }
    private synchronized void State_PAUSE() { if (current == State.PLAYING) current = State.PAUSE; }
    private synchronized void State_DESTORY() { current = State.DESTROYED; }

    private final Path VIDEO_PATH;
    private final FFmpegFrameGrabber grabber;
    private ExecutorService grabExecutor;      // 单线程驱动循环（交替推进视频+音频）

    /** 携带视频元信息。 */
    public static class VideoMeta {
        private final int WIDTH;
        private final int HEIGHT;
        private final double FPS;
        private final long TOTAL_TIME;

        public VideoMeta(int width, int height, double fps, long totalTime) {
            this.WIDTH = width;
            this.HEIGHT = height;
            this.FPS = fps;
            this.TOTAL_TIME = totalTime;
        }
        public int getHEIGHT() { return HEIGHT; }
        public int getWIDTH() { return WIDTH; }
        public double getFPS() { return FPS; }
        public long getTOTAL_TIME() { return TOTAL_TIME; }
    }

    public VideoMeta getVIDEOMETA() {
        return new VideoMeta(grabber.getWidth(), grabber.getHeight(), grabber.getFps(), -1);
    }

    private VideoDecoder(FFmpegFrameGrabber grabber, Path videoPath) {
        this.VIDEO_PATH = videoPath;
        this.grabber = grabber;
        this.grabExecutor = Executors.newSingleThreadExecutor();
    }
    public synchronized void destroy() {
        this.State_DESTORY();
        LIVE_DECODERS.remove(this);
        if (this.grabber != null) this.grabber.close();
        this.grabExecutor.shutdownNow();
        ACTIVE_VIDEO_DECODER.decrementAndGet();
    }

    public long playStartSystemNano;
    private long currentTimeUs;

    /** 启动单线程驱动循环：交替推进视频与音频解码（非阻塞单次），满槽/暂停时 sleep 让出。 */
    public synchronized void PlayVideo() {
        if (this.isReady()) {
            playStartSystemNano = System.nanoTime();
            this.grabExecutor.submit(() -> {
                while (!isDestroyed()) {
                    if (isPause()) {
                        try { Thread.sleep(30L); } catch (InterruptedException e) { break; }
                        continue;
                    }
                    try {
                        boolean videoDone = this.grabber.isVideoEof();
                        boolean audioDone = !this.grabber.hasAudio() || this.grabber.isAudioEof();
                        if (videoDone && audioDone) break; // 双流结束

                        boolean didWork = false;
                        // 视频推进一步（非阻塞；readyQueue 满/EOF 时立即返回）
                        if (!videoDone) {
                            var slot = this.grabber.grabImage();
                            if (slot != null) {
                                didWork = true;
                                currentTimeUs = this.grabber.getLastVideoTimestampUs();
                            }
                        }
                        // 音频推进一步（非阻塞；audioReadyQueue 满/无包/EOF 时立即返回）
                        if (this.grabber.hasAudio() && !audioDone) {
                            var slot = this.grabber.grabAudio();
                            if (slot != null) didWork = true;
                        }
                        if (!didWork) {
                            // 本次无产出（队列满/需等待投递包）：短暂让出，等消费者释放槽
                            Thread.sleep(1L);
                        }
                    } catch (Exception e) {
                        VideoInMinecraft.LOGGER.warn("decode frame error", e);
                        break;
                    }
                }
            });
            this.State_PLAYING();
        }
    }

    public synchronized void Pause() {
        this.State_PAUSE();
    }

    /** 供渲染线程/外部访问管道池（acquire/release）。 */
    public FFmpegFrameGrabber getGrabber() {
        return grabber;
    }
}