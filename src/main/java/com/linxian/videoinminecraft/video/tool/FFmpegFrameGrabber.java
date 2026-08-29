package com.linxian.videoinminecraft.video.tool;

import com.linxian.videoinminecraft.VideoInMinecraft;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * JavaCPP 直控 FFmpeg 的解码器。
 *
 * <p>驱动模型：非阻塞单接口推进（标准 pull 状态机）。
 * <ul>
 *   <li>{@link #grab()} 每次调用对称地推进视频一步 + 音频一步：要么 receive 出一帧并发布，要么喂一个包。</li>
 *   <li>读包受就绪队列背压（队列满即不产不读）；交叉流包投递到各自的<b>有界 packet 队列</b>（容量 5，满时丢旧保新）。</li>
 *   <li>每条流的生命周期由 {@link CodecState} 表达；容器层由 {@link ContainerState} 表达，二者替代零散的 boolean。</li>
 * </ul>
 */
public class FFmpegFrameGrabber implements AutoCloseable {

    /** 单个解码流的生命周期状态。 */
    public enum CodecState {
        /** 初始就绪，可接收包 / 可出帧。 */
        READY,
        /** 上次 receive 返回 EAGAIN，需要喂包。 */
        NEED_PUSH_PACKET,
        /** 刚产出一帧，需要继续 receive 取帧。 */
        NEED_PULL_FRAME,
        /** 容器已读完、已 send null，正在排空缓冲帧。 */
        FLUSHING,
        /** receive 返回 AVERROR_EOF，该流彻底结束。 */
        FLUSHED,
        /** 喂包真错误。 */
        ERROR,
        /** 无此流（无音频）。 */
        DISABLE
    }

    /** 容器解复用层状态。 */
    public enum ContainerState {
        /** 容器仍可读包。 */
        OPEN,
        /** 容器已读到底（av_read_frame 返回负值）。 */
        END
    }

    private final int width;
    private final int height;
    private final double fps;

    private final AVFormatContext fmtCtx;
    private final AVCodecContext videoCodecCtx;
    private final AVCodecContext audioCodecCtx;
    private final SwsContext swsContext;
    private final SwrContext swrContext;
    private final int videoStreamIndex;
    private final int audioStreamIndex;
    private final boolean audio;

    private final FrameBufferPoolWithQueue pool;
    private final IntPointer rgbaLinesize;

    private long lastVideoTimestampUs;
    private long lastAudioTimestampUs;

    /** 首帧 PTS 基准：将视频/音频各自的 PTS 归一到 0 起点，消除不同流 start_time 导致的音画错位。 */
    private long firstVideoPtsUs = -1;
    private long firstAudioPtsUs = -1;

    private final AVFrame imageFrame;
    private final AVFrame audioFrame;

    private final AVPacket pkt;              // av_read_frame 目标（瞬时使用）

    /** 视频流 / 音频流 / 容器 三者的状态（无零散 boolean）。 */
    private CodecState imageCodecState = CodecState.READY;
    private CodecState audioCodecState = CodecState.DISABLE;
    private ContainerState containerState = ContainerState.OPEN;

    /** 交叉投递的包队列：按 duration 水位（微秒）背压，避免读穿容器。 */
    private final AVPacketQueue audioPendingPackets = new AVPacketQueue(300_000,600_000);
    private final AVPacketQueue imagePendingPackets = new AVPacketQueue(300_000,1_500_000);
    public class AVPacketQueue{

        private final long Low_Water;
        private final long High_Water;
        private volatile AtomicLong nowSize = new AtomicLong(0);
        private final BlockingQueue<AVPacket> pool = new LinkedBlockingQueue<>();
        private final BlockingQueue<AVPacket> readyQueue = new LinkedBlockingQueue<>();
        private final BlockingQueue<Long> readyDuration = new LinkedBlockingQueue<>();
        /**单位：us**/
        public AVPacketQueue(int Low_Water,int High_Water){
            this.High_Water = High_Water;
            this.Low_Water = Low_Water;
        }
        /**单位：us**/
        public long size(){
            return this.nowSize.get();
        }
        public void release(AVPacket packet){
            this.pool.offer(packet);
        }
        public AVPacket getFreePkt(){
            AVPacket packet = this.pool.poll();
            if (packet == null){
                return av_packet_alloc();
            }
            return packet;
        }
        public AVPacket peek(){
            return this.readyQueue.peek();
        }
        public AVPacket poll(){
            AVPacket packet = this.readyQueue.poll();
            if (packet != null) {
                Long d = this.readyDuration.poll();
                assert d != null;
                long duration = d;
                this.nowSize.addAndGet(-duration);
            }
            return packet;
        }
        public boolean offer(AVPacket packet){
            if (nowSize.get()>=this.High_Water) return false;
            this.readyQueue.offer(packet);
            long duration = av_rescale_q(packet.duration(),fmtCtx.streams(packet.stream_index()).time_base(),av_make_q(1,1000000));
            this.readyDuration.offer(duration);
            this.nowSize.addAndGet(duration);
            return true;
        }
        public boolean isHungry(){
            return this.nowSize.get() < this.Low_Water;
        }
        public void freeAll(){
            for (AVPacket packets:this.readyQueue){
                av_packet_free(packets);
            }
            for (AVPacket packets:this.pool){
                av_packet_free(packets);
            }
        }
    }

    private static final int AV_SUCCESS = 0;

    public CodecState getImageCodecState() { return imageCodecState; }
    public CodecState getAudioCodecState() { return audioCodecState; }

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

            swrContext = swresample.swr_alloc();
            if (swrContext == null || swrContext.isNull()) throw new IOException("swr_alloc Failed");

            int inChannels;
            try {
                inChannels = audioCodecCtx.ch_layout().nb_channels();
            } catch (Throwable ignored) {
                inChannels = 0;                       // 旧 FFmpeg 无 ch_layout：保持默认回退
            }
            int inSampleRate = audioCodecCtx.sample_rate();
            int inSampleFmt = audioCodecCtx.sample_fmt();
            if (inChannels <= 0) inChannels = 2;

            boolean ichOk = av_opt_set(swrContext, "ichl", inChannels + "c", 0) >= 0;
            boolean ochOk = av_opt_set(swrContext, "ochl", "2c", 0) >= 0;
            boolean isrOk = av_opt_set_int(swrContext, "isr", inSampleRate, 0) >= 0;
            boolean isfOk = av_opt_set_sample_fmt(swrContext, "isf", inSampleFmt, 0) >= 0;
            boolean osrOk = av_opt_set_int(swrContext, "osr", 44100, 0) >= 0;
            boolean osfOk = av_opt_set_sample_fmt(swrContext, "osf", AV_SAMPLE_FMT_S16, 0) >= 0;

            if (swresample.swr_init(swrContext) < 0) {
                throw new IOException("swr_init Failed (ich=" + ichOk + " isr=" + isrOk + " isf=" + isfOk
                        + " och=" + ochOk + " osr=" + osrOk + " osf=" + osfOk + ")");
            }
            audioCodecState = CodecState.READY;
        } else {
            audioCodecCtx = null;
            swrContext = null;
            audioCodecState = CodecState.DISABLE;
        }

        pool = new FrameBufferPoolWithQueue(3, 10, videoCodecCtx, audioCodecCtx);
        rgbaLinesize = new IntPointer(new int[]{width * 4});

        imageFrame = av_frame_alloc();
        pkt = av_packet_alloc();
        audioFrame = audio ? av_frame_alloc() : null;
    }

    // ==================== grab 单接口推进（视频一步 + 音频一步，对称） ====================

    /**
     * 非阻塞推进一次：视频一步 + 音频一步。
     *
     * <p>FFmpeg 返回值速查：<br>
     * {@code av_read_frame}：0 成功读包；AVERROR_EOF 读完；其它 <0 解封装异常。<br>
     * {@code avcodec_send_packet}：0 成功；AVERROR_EAGAIN 解码器满（先 receive）；AVERROR_EOF 已冲刷拒绝输入。<br>
     * {@code avcodec_receive_frame}：0 成功；AVERROR_EAGAIN 暂无帧（需 send）；AVERROR_EOF 冲刷完毕无更多帧。
     *
     * @return true 表示本次有实际推进（产帧 / 喂包 / 状态变化）；false 表示无推进（让出）。
     */
    public boolean grab() {
        boolean advanced = false;
        advanced |= videoStep();
        advanced |= audioStep();
        advanced |= DemuxAndDistributionPacket();
        advanced |= feedVideoPacket();
        advanced |= feedAudioPacket();
        return advanced;
    }

    private boolean videoStep() {
        if (imageCodecState == CodecState.FLUSHED || imageCodecState == CodecState.ERROR) return false;
        if (pool.isImageFreeEmpty()) return false;

        int videoPullFrameRet = avcodec_receive_frame(videoCodecCtx, imageFrame);
        if (videoPullFrameRet == AV_SUCCESS) {
            if (imageCodecState != CodecState.FLUSHING) {
                imageCodecState = CodecState.NEED_PULL_FRAME;
            }
            FrameBufferPoolWithQueue.ImageBufferSlot slot = pool.tryBorrowImageBuffer();
            if (slot == null) { av_frame_unref(imageFrame); return false; }
            sws_scale(swsContext, imageFrame.data(), imageFrame.linesize(), 0, height, slot.imagePointerPtr, rgbaLinesize);
            long videoRawPtsUs = av_rescale_q(
                    imageFrame.best_effort_timestamp(),
                    fmtCtx.streams(videoStreamIndex).time_base(),
                    av_make_q(1, 1000000));
            if (firstVideoPtsUs < 0) firstVideoPtsUs = videoRawPtsUs;
            slot.ptsUs = videoRawPtsUs - firstVideoPtsUs;   // 归一化到 0 起点
            lastVideoTimestampUs = slot.ptsUs;
            av_frame_unref(imageFrame);
            try {
                pool.publishImageBuffer(slot);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return true;
        } else if (videoPullFrameRet == AVERROR_EOF()) {
            if (containerState == ContainerState.END && imageCodecState == CodecState.FLUSHING) {
                imageCodecState = CodecState.FLUSHED;   // flush 排空完毕才算真结束
                VideoInMinecraft.LOGGER.info("[Grabber] video stream finished");
                return true;
            }
            return false;
        }

        // EAGAIN 或瞬时错误：喂一个包（FLUSHING 期间保持，等待 drain 剩余帧，防止状态被覆盖）
        if (imageCodecState != CodecState.FLUSHING) {
            imageCodecState = CodecState.NEED_PUSH_PACKET;
        }
        return false;
    }

    private boolean audioStep() {
        if (audioCodecState == CodecState.DISABLE || audioCodecState == CodecState.FLUSHED
                || audioCodecState == CodecState.ERROR) return false;
        if (pool.isAudioReadyFull()) return false;

        int ret = avcodec_receive_frame(audioCodecCtx, audioFrame);
        if (ret == AV_SUCCESS) {
            // 容器已 END、正在 flush 排空时保持 FLUSHING（与 videoStep 同理，防覆盖导致卡死）。
            if (audioCodecState != CodecState.FLUSHING) {
                audioCodecState = CodecState.NEED_PULL_FRAME;
            }
            FrameBufferPoolWithQueue.AudioBufferSlot slot = pool.tryBorrowAudioBuffer();
            if (slot == null) { av_frame_unref(audioFrame); return false; }
            int writtenSamples = swresample.swr_convert(swrContext, slot.audioPointerPtr,
                    16384, audioFrame.data(), audioFrame.nb_samples());
            long audioRawPtsUs = av_rescale_q(
                    audioFrame.best_effort_timestamp(),
                    fmtCtx.streams(audioStreamIndex).time_base(),
                    av_make_q(1, 1000000));
            if (firstAudioPtsUs < 0) firstAudioPtsUs = audioRawPtsUs;
            slot.ptsUs = audioRawPtsUs - firstAudioPtsUs;   // 归一化到 0 起点
            lastAudioTimestampUs = slot.ptsUs;
            av_frame_unref(audioFrame);
            if (writtenSamples <= 0) {
                pool.releaseAudioBuffer(slot);
                return false;
            }
            int writtenBytes = writtenSamples * 2 * 2;
            slot.audioBuffer.position(writtenBytes).limit(slot.capacity);
            try {
                pool.publishAudioBuffer(slot);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return true;
        } else if (ret == AVERROR_EOF()) {
            if (containerState == ContainerState.END && audioCodecState == CodecState.FLUSHING) {
                audioCodecState = CodecState.FLUSHED;   // flush 排空完毕才算真结束
                VideoInMinecraft.LOGGER.info("[Grabber] audio stream finished");
                return true;
            }
            return false;
        }
        // EAGAIN 或瞬时错误：喂一个包（FLUSHING 期间保持，等待 drain 剩余帧，防止状态被覆盖）
        if (audioCodecState != CodecState.FLUSHING) {
            audioCodecState = CodecState.NEED_PUSH_PACKET;
        }
        return false;
    }
    /**@return {@code true}成功读取并分发对应包 <br> {@code false} 无读取分包**/
    private boolean DemuxAndDistributionPacket(){
        if (this.containerState != ContainerState.END) {
            if (this.audioPendingPackets.isHungry() || this.imagePendingPackets.isHungry()) {
                int readRet = av_read_frame(fmtCtx, pkt);
                if (readRet == AV_SUCCESS) {
                    int streamIndex = this.pkt.stream_index();
                    if (streamIndex == this.videoStreamIndex) {
                        offerDroppingOldest(imagePendingPackets, pkt);
                        return true;
                    } else if (streamIndex == this.audioStreamIndex) {
                        offerDroppingOldest(audioPendingPackets, pkt);
                        return true;
                    } else {
                        av_packet_unref(this.pkt);
                        return false;
                    }
                } else if (readRet == AVERROR_EOF()) {
                    this.containerState = ContainerState.END;
                    av_packet_unref(this.pkt);
                    return false;
                }
            }
        }
        return false;
    }
    private boolean feedVideoPacket() {
        if (this.imageCodecState != CodecState.NEED_PUSH_PACKET) return false;

        AVPacket queued = imagePendingPackets.peek();
        if (queued != null) {                        // 队头有挂起视频包：peek 发送，成功才移除
            int sr = avcodec_send_packet(videoCodecCtx, queued);
            if (sr == AV_SUCCESS) {
                imagePendingPackets.poll();
                imagePendingPackets.release(queued);
                return true;
            } else if (sr == AVERROR_EAGAIN()) {
                return false;                        // 解码器满：保留队头，下次重发
            }
            imagePendingPackets.poll();              // 真错误：移除并丢弃
            imagePendingPackets.release(queued);
            imageCodecState = CodecState.ERROR;
            return true;
        }

        // 队列空且容器读完：send null 一次进入 drain 模式（仅此处发送，避免重复）
        if (this.containerState == ContainerState.END) {
            avcodec_send_packet(videoCodecCtx, (AVPacket) null);
            this.imageCodecState = CodecState.FLUSHING;
            return true;
        }
        return false;                                // 队列空、容器未读完：等 Demux 再投包
    }

    private boolean feedAudioPacket() {
        if (this.audioCodecState != CodecState.NEED_PUSH_PACKET) return false;

        AVPacket queued = audioPendingPackets.peek();
        if (queued != null) {                        // 队头有挂起音频包：peek 发送，成功才移除
            int sr = avcodec_send_packet(audioCodecCtx, queued);
            if (sr == AV_SUCCESS) {
                audioPendingPackets.poll();
                audioPendingPackets.release(queued);
                return true;
            } else if (sr == AVERROR_EAGAIN()) {
                return false;                        // 解码器满：保留队头，下次重发
            }
            audioPendingPackets.poll();              // 真错误：移除并丢弃
            audioPendingPackets.release(queued);
            audioCodecState = CodecState.ERROR;
            return true;
        }

        // 队列空且容器读完：send null 一次进入 drain 模式（用对 audioCodecCtx）
        if (this.containerState == ContainerState.END) {
            avcodec_send_packet(audioCodecCtx, (AVPacket) null);
            this.audioCodecState = CodecState.FLUSHING;
            return true;
        }
        return false;                                // 队列空、容器未读完：等 Demux 再投包
    }
    /**
     * 把读到的包（src，所有权在 src 内）投递进队列；队列满时丢弃队头最旧的包，保证新包不被丢。
     * 通过 move_ref 转移所有权，src 被清空。
     */
    private void offerDroppingOldest(AVPacketQueue queue, AVPacket src) {
        AVPacket copy = queue.getFreePkt();
        av_packet_move_ref(copy, src);               // 转移，src 清空
        if (queue.offer(copy)) {
            return;
        }
        AVPacket old = queue.poll();                 // 满：丢队头最旧包
        if (old != null) {
            av_packet_free(old);
        }
        queue.offer(copy);                           // 此时必成功
    }

    // ==================== 访问器 ====================

    public FFmpegFrameGrabber(Path videoPath, int pixelFormat) throws IOException {
        this(videoPath, pixelFormat, (AVDictionary) null);
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public double getFps() { return fps; }
    public long getLastVideoTimestampUs() { return lastVideoTimestampUs; }
    public long getLastAudioTimestampUs() { return lastAudioTimestampUs; }
    public boolean hasAudio() { return audio; }
    public boolean isVideoEof() { return imageCodecState == CodecState.FLUSHED; }
    public boolean isAudioEof() { return audioCodecState == CodecState.FLUSHED; }

    /** 双流是否都已结束（无音频流时只看视频）。驱动循环据此退出。 */
    public boolean isFinished() {
        boolean videoDone = imageCodecState == CodecState.FLUSHED;
        boolean audioDone = !audio || audioCodecState == CodecState.FLUSHED;
        return videoDone && audioDone;
    }

    public FrameBufferPoolWithQueue getPool() { return pool; }

    @Override
    public void close() {
        if (imageFrame != null) av_frame_free(imageFrame);
        if (pkt != null) av_packet_free(pkt);
        if (audioFrame != null) av_frame_free(audioFrame);
        audioPendingPackets.freeAll();
        imagePendingPackets.freeAll();
        if (rgbaLinesize != null) rgbaLinesize.deallocate();
        pool.close();
        if (swsContext != null && !swsContext.isNull()) sws_freeContext(swsContext);
        if (swrContext != null && !swrContext.isNull()) swresample.swr_free(swrContext);
        if (videoCodecCtx != null && !videoCodecCtx.isNull()) avcodec_free_context(videoCodecCtx);
        if (audioCodecCtx != null && !audioCodecCtx.isNull()) avcodec_free_context(audioCodecCtx);
        if (fmtCtx != null && !fmtCtx.isNull()) avformat_close_input(fmtCtx);
    }
}