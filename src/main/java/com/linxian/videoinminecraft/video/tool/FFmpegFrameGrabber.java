

package com.linxian.videoinminecraft.video.tool;

import com.linxian.videoinminecraft.VideoInMinecraft;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * JavaCPP 直控 FFmpeg 的解码器。
 *
 * 驱动模型：非阻塞单次推进。{@link #grabImage()} / {@link #grabAudio()} 每次调用只推进
 * "一步"（tryBorrow 确保有槽才 receive，无槽直接让出；EOF 用 isVideoEof/isAudioEof 标记，
 * 不靠返回值通知）。fmtCtx 只由 grabImage 独占读包（单生产者），读到音频包投递给
 * audioPendingPackets，grabAudio 只消费该队列。
 */
public class FFmpegFrameGrabber implements AutoCloseable {
    private static final int OUT_CHANNELS = 2;
    private static final int OUT_SAMPLE_BYTES = 2;
    private static final int OUT_BYTES_PER_SAMPLE = OUT_CHANNELS * OUT_SAMPLE_BYTES; // =4

    private final int width;
    private final int height;
    private final double fps;

    private final AVFormatContext fmtCtx;
    private final AVCodecContext videoCodecCtx;
    private final AVCodecContext audioCodecCtx;
    private final SwsContext swsContext;
    private final SwrContext swrContext;
    /** swr_free 用：持有双指针槽（av_opt 路由不使用，仅兼容旧路径）。 */
    private PointerPointer<SwrContext> swrContextPointerPointer;
    /** 输出声道布局：仅 av_opt 路由的备用（不再供 opts2 使用）。 */
    private final AVChannelLayout outLayout = new AVChannelLayout();
    private final int videoStreamIndex;
    private final int audioStreamIndex;
    private final boolean audio;

    // 管道池（外部消费者经 getPool 取帧）
    private final FrameBufferPoolWithQueue pool;
    private final IntPointer rgbaLinesize;
    private final long rgbaCapacity;

    private long lastVideoTimestampUs;
    private long lastAudioTimestampUs;

    private final AVFrame frame;
    private final AVPacket pkt;
    private AVPacket pendingPkt = null;      // EAGAIN 未发送成功的视频包（必须保留）
    private volatile boolean videoEof = false;

    private final AVFrame audioFrame;
    private final AVPacket audioPkt;
    private AVPacket pendingAudioPkt = null; // EAGAIN 未发送成功的音频包（必须保留）
    private volatile boolean audioEof = false;
    private boolean audioFlushed = false;
    /** fmtCtx 读流到达末尾（grabImage 置位，grabAudio 据此 flush 收尾）。 */
    private volatile boolean fmtEof = false;
    /** grabImage 投递的音频包队列（ref 拷贝，grabAudio 消费）。 */
    private final BlockingQueue<AVPacket> audioPendingPackets = new LinkedBlockingQueue<>();

    public FFmpegFrameGrabber(Path videoPath, int pixelFormat, AVDictionary option) throws IOException {
        fmtCtx = new AVFormatContext(null);
        if (avformat_open_input(fmtCtx, videoPath.toString(), null, null) < 0)
            throw new IOException("avformat_open_input failed");
        if (avformat_find_stream_info(fmtCtx, (PointerPointer) null) < 0)
            throw new IOException("avformat_find_stream_info failed");

        Integer videoStreamFinder = null;
        Integer audioStreamFinder = null;
        for (int i = 0; i < fmtCtx.nb_streams(); i++) {
            int t = fmtCtx.streams(i).codecpar().codec_type();
            if (t == AVMEDIA_TYPE_VIDEO && videoStreamFinder == null) videoStreamFinder = i;
            else if (t == AVMEDIA_TYPE_AUDIO && audioStreamFinder == null) audioStreamFinder = i;
        }
        if (videoStreamFinder == null) throw new IOException("No video stream found");
        videoStreamIndex = videoStreamFinder;
        if (audioStreamFinder != null) { audioStreamIndex = audioStreamFinder; audio = true; }
        else { audioStreamIndex = -1; audio = false; }

        AVStream videoStream = fmtCtx.streams(videoStreamIndex);
        AVCodecParameters videoParameters = videoStream.codecpar();
        AVCodec videoCodec = avcodec_find_decoder(videoParameters.codec_id());
        videoCodecCtx = avcodec_alloc_context3(videoCodec);
        if (videoCodecCtx == null || videoCodecCtx.isNull()) throw new IOException("video ctx alloc failed");
        if (avcodec_parameters_to_context(videoCodecCtx, videoParameters) < 0) throw new IOException("video params failed");
        videoCodecCtx.thread_count(0);
        if (avcodec_open2(videoCodecCtx, videoCodec, option) < 0) throw new IOException("video open failed");
        width = videoCodecCtx.width();
        height = videoCodecCtx.height();
        fps = av_q2d(videoStream.avg_frame_rate());
        rgbaCapacity = (long) width * height * 4;

        swsContext = sws_getContext(width, height, videoCodecCtx.pix_fmt(), width, height, pixelFormat, SWS_BILINEAR, null, null, (DoublePointer) null);
        if (swsContext == null || swsContext.isNull()) throw new IOException("sws_getContext Failed");

        if (audio) {
            AVStream audioStream = fmtCtx.streams(audioStreamIndex);
            AVCodecParameters audioParameters = audioStream.codecpar();
            AVCodec audioCodec = avcodec_find_decoder(audioParameters.codec_id());
            audioCodecCtx = avcodec_alloc_context3(audioCodec);
            if (audioCodecCtx == null || audioCodecCtx.isNull()) throw new IOException("audio ctx alloc failed");
            if (avcodec_parameters_to_context(audioCodecCtx, audioParameters) < 0) throw new IOException("audio params failed");
            audioCodecCtx.thread_count(0);
            if (avcodec_open2(audioCodecCtx, audioCodec, (PointerPointer) null) < 0) throw new IOException("audio open failed");

            // ★ 绕开 swr_alloc_set_opts2 + AVChannelLayout 结构体传参：
            //   JavaCPP 对 AVCodecContext 内嵌 ch_layout 的绑定桥接有系统性 ABI bug
            //   （7.1-1.5.11 与 8.0.1-1.5.13 两条版本线均在 opts2 内崩溃，读 Java 堆地址当表索引）。
            //   改用 swr_alloc + av_opt_set_*：纯字符串/标量不碰结构体，稳。
            //   ★ 官方 Resampler 文档：FFmpeg 7/8 的声道布局选项叫 ichl/ochl（in_chlayout/out_chlayout），
            //     不是旧版的 ich/och（已被移除）！语法为 ffmpeg-utils "Channel Layout Syntax"——
            //     用字符串 "Nc"（如 "2c"）让 C 侧 av_channel_layout_from_string 解析，
            //     字符串传参不经过 JavaCPP 结构体桥接，绕开 ABI bug。
            //   isr/isf/osr/osf 仍是标量（文档 2461-2473 行），用 int/format 直接设。
            swrContext = swresample.swr_alloc();
            if (swrContext == null || swrContext.isNull()) throw new IOException("swr_alloc Failed");

            int inChannels = 0;
            try { inChannels = audioCodecCtx.ch_layout().nb_channels(); } catch (Throwable ignored) {}
            int inSampleRate = audioCodecCtx.sample_rate();
            int inSampleFmt = audioCodecCtx.sample_fmt();
            if (inChannels <= 0) inChannels = 2; // 兜底

            boolean ichOk, isrOk, isfOk, ochOk, osrOk, osfOk;
            // CHANNEL_LAYOUT 类型选项：字符串 "Nc"（N=声道数）→ C 侧 av_channel_layout_from_string 解析
            ichOk = av_opt_set(swrContext, "ichl", inChannels + "c", 0) >= 0;
            ochOk = av_opt_set(swrContext, "ochl", "2c", 0) >= 0;
            // 其余为标量：int/format 直接设
            isrOk = av_opt_set_int(swrContext, "isr", inSampleRate, 0) >= 0;
            isfOk = av_opt_set_sample_fmt(swrContext, "isf", inSampleFmt, 0) >= 0;
            osrOk = av_opt_set_int(swrContext, "osr", 44100, 0) >= 0;
            osfOk = av_opt_set_sample_fmt(swrContext, "osf", AV_SAMPLE_FMT_S16, 0) >= 0;

            if (swresample.swr_init(swrContext) < 0) {
                throw new IOException("swr_init Failed (ich=" + ichOk + " isr=" + isrOk + " isf=" + isfOk
                        + " och=" + ochOk + " osr=" + osrOk + " osf=" + osfOk + ")");
            }
            VideoInMinecraft.LOGGER.info("swr init OK (inCh=" + inChannels + " inRate=" + inSampleRate
                    + " ich=" + ichOk + " isr=" + isrOk + " isf=" + isfOk
                    + " och=" + ochOk + " osr=" + osrOk + " osf=" + osfOk + ")");
            swrContextPointerPointer = null; // opts2 路由不再使用
        } else {
            audioCodecCtx = null;
            swrContext = null;
            swrContextPointerPointer = null;
        }

        // 固定槽数：视频3，音频10
        pool = new FrameBufferPoolWithQueue(3, 10, videoCodecCtx, audioCodecCtx);
        rgbaLinesize = new IntPointer(new int[]{width * 4});

        frame = av_frame_alloc();
        pkt = av_packet_alloc();
        audioFrame = audio ? av_frame_alloc() : null;
        audioPkt = audio ? av_packet_alloc() : null;
    }

    // ==================== 视频：非阻塞单次推进 ====================
    public FrameBufferPoolWithQueue.ImageBufferSlot grabImage() throws InterruptedException {
        if (videoEof) return null;
        // 第一步：就绪队列满 → 直接让出（不强行推进）
        if (pool.isImageReadyFull()) return null;

        // 尝试取帧
        int recvRet = avcodec_receive_frame(videoCodecCtx, frame);
        if (recvRet == 0) {
            // 确保有槽（刚检查过 ready 未满，这里 tryBorrow 必定拿到空闲槽；兜底 null 则等下一轮）
            FrameBufferPoolWithQueue.ImageBufferSlot slot = pool.tryBorrowImageBuffer();
            if (slot == null) { av_frame_unref(frame); return null; }
            sws_scale(swsContext, frame.data(), frame.linesize(), 0, height, slot.imagePointerPtr, rgbaLinesize);
            // 流时间基 tick → 微秒：best_effort_timestamp 单位是 stream.time_base，不是 us
            // ★ 必须写进 slot（VideoConsumer 靠 slot.ptsUs 做 PTS 同步），不只存 lastVideoTimestampUs
            slot.ptsUs = av_rescale_q(
                    frame.best_effort_timestamp(),
                    fmtCtx.streams(videoStreamIndex).time_base(),
                    av_make_q(1, 1000000));
            lastVideoTimestampUs = slot.ptsUs;
            av_frame_unref(frame);
            pool.publishImageBuffer(slot);
            return slot;
        } else if (recvRet == AVERROR_EOF) {
            videoEof = true;
            if (pendingPkt != null) { av_packet_unref(pendingPkt); pendingPkt = null; }
            return null;
        } else if (recvRet != AVERROR_EAGAIN()) {
            videoEof = true;
            return null;
        }

        // EAGAIN：需要喂包
        if (pendingPkt != null) {
            int sendRet = avcodec_send_packet(videoCodecCtx, pendingPkt);
            if (sendRet == 0) { av_packet_unref(pendingPkt); pendingPkt = null; }
            else if (sendRet != AVERROR_EAGAIN()) { av_packet_unref(pendingPkt); pendingPkt = null; videoEof = true; }
            return null;
        }

        // fmtCtx 独占读一个包（单生产者）
        int readRet = av_read_frame(fmtCtx, pkt);
        if (readRet < 0) {
            if (audio) fmtEof = true;
            avcodec_send_packet(videoCodecCtx, (AVPacket) null); // flush 视频
            return null;
        }
        if (pkt.stream_index() != videoStreamIndex) {
            if (audio && pkt.stream_index() == audioStreamIndex) {
                AVPacket audioCopy = av_packet_alloc();
                av_packet_ref(audioCopy, pkt);
                audioPendingPackets.offer(audioCopy);
            }
            av_packet_unref(pkt);
            return null;
        }
        int sendRet = avcodec_send_packet(videoCodecCtx, pkt);
        if (sendRet == 0) av_packet_unref(pkt);
        else if (sendRet == AVERROR_EAGAIN()) pendingPkt = pkt; // 保留重发
        else { av_packet_unref(pkt); videoEof = true; }
        return null;
    }

    // ==================== 音频：非阻塞单次推进 ====================
    public FrameBufferPoolWithQueue.AudioBufferSlot grabAudio() throws InterruptedException {
        if (!audio || audioEof) return null;
        // 第一步：就绪队列满 → 直接让出
        if (pool.isAudioReadyFull()) return null;

        int recvRet = avcodec_receive_frame(audioCodecCtx, audioFrame);
        if (recvRet == 0) {
            int outSamples = (int) swresample.swr_get_out_samples(swrContext, audioFrame.nb_samples());
            int needBytes = outSamples * OUT_BYTES_PER_SAMPLE;
            FrameBufferPoolWithQueue.AudioBufferSlot slot = pool.tryBorrowAudioBuffer(needBytes);
            if (slot == null) { av_frame_unref(audioFrame); return null; }
            int writtenSamples = swresample.swr_convert(swrContext, slot.audioPointerPtr,
                    slot.capacity / OUT_BYTES_PER_SAMPLE, audioFrame.data(), audioFrame.nb_samples());
            slot.length = writtenSamples * OUT_BYTES_PER_SAMPLE;
            // 流时间基 tick → 微秒
            slot.ptsUs = av_rescale_q(
                    audioFrame.best_effort_timestamp(),
                    fmtCtx.streams(audioStreamIndex).time_base(),
                    av_make_q(1, 1000000));
            lastAudioTimestampUs = slot.ptsUs;
            av_frame_unref(audioFrame);
            pool.publishAudioBuffer(slot);
            return slot;
        } else if (recvRet == AVERROR_EOF) {
            audioEof = true;
            return null;
        } else if (recvRet != AVERROR_EAGAIN()) {
            audioEof = true;
            return null;
        }

        // EAGAIN：需要喂包
        if (pendingAudioPkt != null) {
            int sendRet = avcodec_send_packet(audioCodecCtx, pendingAudioPkt);
            if (sendRet == 0) { av_packet_unref(pendingAudioPkt); pendingAudioPkt = null; }
            else if (sendRet != AVERROR_EAGAIN()) { av_packet_unref(pendingAudioPkt); pendingAudioPkt = null; audioEof = true; }
            return null;
        }

        // 消费投递队列（不读 fmtCtx）
        AVPacket packet = audioPendingPackets.poll();
        if (packet == null) {
            if (fmtEof && !audioFlushed) { // fmt 读完且队列空 → flush 收尾
                avcodec_send_packet(audioCodecCtx, (AVPacket) null);
                audioFlushed = true;
            }
            return null;
        }
        int sendRet = avcodec_send_packet(audioCodecCtx, packet);
        if (sendRet == 0) av_packet_unref(packet);
        else if (sendRet == AVERROR_EAGAIN()) pendingAudioPkt = packet;
        else { av_packet_unref(packet); audioEof = true; }
        return null;
    }

    // ==================== 访问器 ====================
    public FFmpegFrameGrabber(Path videoPath, int pixelFormat) throws IOException { this(videoPath, pixelFormat, (AVDictionary) null); }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public double getFps() { return fps; }
    public long getLastVideoTimestampUs() { return lastVideoTimestampUs; }
    public long getLastAudioTimestampUs() { return lastAudioTimestampUs; }
    public boolean hasAudio() { return audio; }
    public boolean isVideoEof() { return videoEof; }
    public boolean isAudioEof() { return audioEof; }
    public FrameBufferPoolWithQueue getPool() { return pool; }

    @Override
    public void close() {
        if (frame != null) av_frame_free(frame);
        if (pkt != null) av_packet_free(pkt);
        if (pendingPkt != null) av_packet_free(pendingPkt);
        if (audioFrame != null) av_frame_free(audioFrame);
        if (audioPkt != null) av_packet_free(audioPkt);
        if (pendingAudioPkt != null) av_packet_free(pendingAudioPkt);
        while (!audioPendingPackets.isEmpty()) {
            AVPacket p = audioPendingPackets.poll();
            if (p != null) av_packet_free(p);
        }
        if (rgbaLinesize != null) rgbaLinesize.deallocate();
        pool.close();
        if (swsContext != null && !swsContext.isNull()) sws_freeContext(swsContext);
        if (swrContextPointerPointer != null) swresample.swr_free(swrContextPointerPointer);
        else if (swrContext != null && !swrContext.isNull()) swresample.swr_free(swrContext);
        if (outLayout != null) outLayout.deallocate();
        if (videoCodecCtx != null && !videoCodecCtx.isNull()) avcodec_free_context(videoCodecCtx);
        if (audioCodecCtx != null && !audioCodecCtx.isNull()) avcodec_free_context(audioCodecCtx);
        if (fmtCtx != null && !fmtCtx.isNull()) avformat_close_input(fmtCtx);
    }
}