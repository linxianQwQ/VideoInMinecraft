package com.linxian.videoinminecraft.video.play;

import com.linxian.videoinminecraft.VideoInMinecraft;
import com.linxian.videoinminecraft.video.sync.PlaybackClock;
import com.linxian.videoinminecraft.video.tool.FrameBufferPoolWithQueue;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * 视频音频"送入"MC SoundEngine 的实现（零裸 AL）。
 *
 * <p>{@link AudioSoundInstance} 交给 {@code SoundManager.play()} 后，
 * MC 会走流式分支：attachBufferStream → pumpBuffers(4) 预缓存 →
 * 之后每 tick updateStream 补槽；每次 {@code read()} 从管道池取一槽
 * （flip 后 limit=有效字节），返回槽视图（零拷贝）。MC 在 pumpBuffers
 * 循环体内同步 alBufferData 上传完成后，由 {@code ChannelMixin} 调
 * {@link VideoAudioStream#release} 归还槽。
 */
public class AudioPlay {

    /** MC SoundEngine 拉取视频 PCM 的流。 */
    public static class VideoAudioStream implements AudioStream {
        private static final int SAMPLE_RATE = 44100;
        private static final int CHANNELS = 2;
        private static final int SAMPLE_BITS = 16;           // PCM_SIGNED 16bit

        private final FrameBufferPoolWithQueue pool;
        private final PlaybackClock clock;
        /**
         * 最近一次 read() 取出的槽的归还闭包。
         * ChannelMixin（pumpBuffers 内 releaseAlBuffer 之后）调用它归还该槽。
         */
        public volatile Runnable release;
        /** 停止标志（close 由 MC stop 调用）。 */
        private volatile boolean eof = false;
        /** 首个进入队列的音频帧 PTS（归一化≈0），作为 play 时的主时钟锚点。 */
        private volatile long firstQueuedPtsUs = -1;

        public VideoAudioStream(FrameBufferPoolWithQueue pool, PlaybackClock clock) {
            this.pool = pool;
            this.clock = clock;
        }

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false);
        }

        /**
         * 非阻塞取一槽：无槽返回 null（pumpBuffers 跳过本轮）；拿到槽 → 登记归还闭包并返回槽视图。
         * 绝不阻塞：SoundEngine 是单线程事件循环，阻塞会卡死 MC 所有声音。
         */
        @Override
        public ByteBuffer read(int size) throws IOException {
            if (eof) return null;
            // ★ 非阻塞：拿不到槽立即返回 null，绝不 sleep——SoundEngine 是单线程事件循环，
            //   阻塞会卡死 MC 所有声音。预缓存保证在 startAudio 前 readyQueue 已攒够槽，
            //   这里总能拿到；读空只是过渡（解码慢时 MC 跳过本轮）。
            FrameBufferPoolWithQueue.AudioBufferSlot slot = pool.tryAcquireAudioBuffer();
            if (slot == null) {
                return null;
            }
            // 记录首个进入队列的音频帧 PTS（预缓存 4 次都会走这里；只取第一次）
            if (firstQueuedPtsUs < 0) {
                firstQueuedPtsUs = slot.ptsUs;
            }
            final FrameBufferPoolWithQueue.AudioBufferSlot done = slot;  // effectively final
            release = () -> pool.releaseAudioBuffer(done);
            return done.audioBuffer;                         // 零拷贝：MC 紧随 alBufferData 同步上传
        }

        /**
         * OpenAL 真正开始播放（Channel.play()）时由 ChannelMixin 调用：
         * 以首队列帧 PTS 锚定音频主时钟。
         */
        public void notifyPlayStart() {
            if (firstQueuedPtsUs >= 0) {
                clock.onAudioPlayStart(firstQueuedPtsUs);
            }
        }

        /** 音频暂停（Channel.pause()）：冻结主时钟，否则恢复后 nowUs 远超视频 PTS 导致快进追赶。 */
        public void notifyPause() {
            clock.pause();
        }

        /** 音频恢复（Channel.unpause()）：继续推进主时钟。 */
        public void notifyResume() {
            clock.resume();
        }

        @Override
        public void close() throws IOException {
            this.eof = true;
            this.release = null;
        }
    }

    /** 播放视频 PCM 的自定义 SoundInstance：Sound.stream=true → MC 走流式拉取。 */
    public static class AudioSoundInstance extends AbstractSoundInstance {
        private final VideoAudioStream stream;
        private final Sound videoSound;

        public AudioSoundInstance(FrameBufferPoolWithQueue pool, PlaybackClock clock) {
            // ★ 用 intentionally_empty location：AbstractSoundInstance.resolve 对它特判返回非 null 的
            //   INTENTIONALLY_EMPTY_SOUND_EVENT，确保 SoundEngine.play 通过 null 检查进入流式分支。
            //   （不能用 SoundEvents.EMPTY——minecraft:empty 未注册时 resolve 返回 null 直接无声音）
            super(SoundManager.INTENTIONALLY_EMPTY_SOUND_LOCATION, SoundSource.RECORDS, RandomSource.create());
            VideoInMinecraft.LOGGER.info("AudioSoundInstance created (starting video stream playback)");
            this.stream = new VideoAudioStream(pool, clock); // 创建即持有唯一流实例，getStream 复用
            this.volume = 1.0F;
            this.videoSound = new Sound(
                    ResourceLocation.fromNamespaceAndPath(VideoInMinecraft.MOD_ID, "video_audio"),
                    ConstantFloat.of(1.0F),
                    ConstantFloat.of(1.0F),
                    1,
                    Sound.Type.FILE,
                    true,                                    // ★ stream=true：attachBufferStream 流式
                    false,
                    0
            );
        }

        public VideoAudioStream getStream() {
            return this.stream;
        }

        @Override
        public Sound getSound() {
            return this.videoSound;
        }

        @Override
        public boolean isLooping() {
            return false;
        }

        @Override
        public boolean isRelative() {
            return true;
        }

        @Override
        public double getX() { return 0; }

        @Override
        public double getY() { return 0; }

        @Override
        public double getZ() { return 0; }

        @Override
        public Attenuation getAttenuation() {
            return Attenuation.NONE;
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBufferLibrary, Sound sound, boolean looping) {
            VideoInMinecraft.LOGGER.info("AudioSoundInstance.getStream called → returning VideoAudioStream");
            // ★ 关键：返回我们自己的、复用的 AudioStream
            return CompletableFuture.completedFuture(this.stream);
        }
    }
}