package com.linxian.videoinminecraft.video.tool;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

public class FFmpegFrameGrabber implements AutoCloseable{
    private final int width;
    private final int height;
    private final double fps;

    private final AVFormatContext fmtCtx;

    private final AVCodecContext videoCodecCtx;
    private final AVCodecContext audioCodecCtx;

    private final SwsContext swsContext;
    private final int videoStreamIndex;
    private final int audioStreamIndex;
    private final boolean audio;

    // 双向 消费-生产 管道：解码线程 borrow→sws_scale→publish，渲染线程 acquire→GL上传→release
    private final FrameBufferPoolWithQueue pool;
    private final IntPointer rgbaLinesize; // 输出行距（像素单位）= width*4，常驻复用
    private final long rgbaCapacity;

    private long lastTimestampUs;

    private AVFrame frame;
    private AVPacket pkt;
    private AVPacket pendingPkt = null;// 因 EAGAIN 未发送成功的包（需保留）

    private enum State {
        READING,   // 正常读包
        DRAINING,  // 已发null包，正在取缓存帧
        FINISHED   // 彻底结束
    }
    private State state = State.READING;


    public FFmpegFrameGrabber(Path videoPath,int pixelFormat,AVDictionary option) throws IOException {
        //1,启动FFmpeg
        fmtCtx = new AVFormatContext(null);
        if (avformat_open_input(fmtCtx, videoPath.toString(), null, null) < 0) {
            throw new IOException("avformat_open_input failed");
        }
        if (avformat_find_stream_info(fmtCtx, (PointerPointer) null) < 0) {
            throw new IOException("avformat_find_stream_info failed");
        }
        //2，找流
        Integer videoStreamFinder = null;
        Integer audioStreamFinder = null;
        for (int i = 0  ; i < fmtCtx.nb_streams() ; i++){
            int streamType = fmtCtx.streams(i).codecpar().codec_type();
            if(streamType == AVMEDIA_TYPE_VIDEO && videoStreamFinder == null){
                videoStreamFinder = i;
            }else if (streamType == AVMEDIA_TYPE_AUDIO && audioStreamFinder == null){
                audioStreamFinder = i;
            }
        }
        if(videoStreamFinder == null){throw new IOException("No video stream found");}
        if(audioStreamFinder != null){
            audioStreamIndex = audioStreamFinder;
            audio = true;
        }else {
            audioStreamIndex = -1;
            audio = false;
        }
        videoStreamIndex = videoStreamFinder;

        AVStream videoStream = fmtCtx.streams(videoStreamIndex); //拿到流
        AVCodecParameters videoParameters = videoStream.codecpar(); //拿到流元信息（编码格式，视频长度 帧率 长宽等）
        AVCodec videoCodec = avcodec_find_decoder(videoParameters.codec_id()); //根据元信息获取解码器
        AVStream audioStream = null;
        AVCodecParameters audioParameters = null;
        AVCodec audioCodec = null;
        if (audioStreamIndex >= 0 ){
            audioStream = fmtCtx.streams(audioStreamIndex);
            audioParameters = audioStream.codecpar();
            audioCodec = avcodec_find_decoder(audioParameters.codec_id());
        }
        //3、打开解码器
        videoCodecCtx = avcodec_alloc_context3(videoCodec);
        if (videoCodecCtx == null || videoCodecCtx.isNull()) throw new IOException("avcodec_alloc_context3 failed");
        if (avcodec_parameters_to_context(videoCodecCtx,videoParameters) < 0) throw new IOException("avcodec_parameters_to_context failed");
        videoCodecCtx.thread_count(0); // 0 表示自动模式 其他即为线程数
        if (avcodec_open2(videoCodecCtx,videoCodec,option) < 0 ) throw new IOException("avcodec_open2 failed");
        width = videoCodecCtx.width();
        height = videoCodecCtx.height();
        fps = av_q2d(videoStream.avg_frame_rate());
        rgbaCapacity = (long) width * height * 4;

        swsContext = sws_getContext(
                        width,height,videoCodecCtx.pix_fmt(),
                        width,height,pixelFormat,
                        SWS_BILINEAR, null, null, (DoublePointer)null);
        if(swsContext == null || swsContext.isNull()) throw new IOException("sws_getContext Failed");
        if(audio) {
            audioCodecCtx = avcodec_alloc_context3(audioCodec);
            if (audioCodecCtx == null || audioCodecCtx.isNull()) throw new IOException("avcodec_alloc_context3 Failed");
            if (avcodec_parameters_to_context(audioCodecCtx, audioParameters) < 0)
                throw new IOException("avcodec_parameters_to_context Failed");
            audioCodecCtx.thread_count(0);
            if (avcodec_open2(audioCodecCtx, audioCodec, (PointerPointer) null) < 0)
                throw new IOException("avcodec_open2 failed");
        }else {audioCodecCtx = null;}

        // 预分配 3 槽（三缓冲流水线）+ 就绪队列容量 3（背压上限）
        pool = new FrameBufferPoolWithQueue(3, 3, width, height);
        rgbaLinesize = new IntPointer(new int[]{width * 4});

        frame = av_frame_alloc();
        pkt = av_packet_alloc();
    }
    /** 返回解码后的像素槽（已进入就绪队列，等待渲染线程 acquire）。无帧返回 null。 */
    public FrameBufferPoolWithQueue.BufferSlot grabImage() throws InterruptedException {
        if(state == State.FINISHED) return null;
        while(true){
            int recvRet = avcodec_receive_frame(videoCodecCtx,frame);
            if(recvRet == 0){
                // 借空闲槽（背压：无槽时阻塞等渲染线程归还）
                FrameBufferPoolWithQueue.BufferSlot slot = pool.borrow();
                // sws_scale 直写槽内存（常驻 BytePointer.pointerPtr，零 new）
                sws_scale(swsContext,
                        frame.data(),frame.linesize(),
                        0,height,
                        slot.pointerPtr,
                        rgbaLinesize
                        );
                lastTimestampUs = frame.best_effort_timestamp();
                av_frame_unref(frame);
                // 发布到就绪队列，渲染线程 acquire（队列满则背压阻塞）
                pool.publish(slot);
                return slot;
            }else if(recvRet == AVERROR_EOF){
                state = State.FINISHED;
                if (pendingPkt != null) {
                    av_packet_unref(pendingPkt);
                    pendingPkt = null;
                }
                return null;
            }else if (recvRet != AVERROR_EAGAIN()){
                state = State.FINISHED;
            }
            switch (state) {
                case DRAINING:
                    // 在冲刷模式下，receive 返回 EAGAIN 理论上不会发生。
                    // 如果发生了，继续循环尝试即可。
                    continue;
                case READING:
                    // 正常流程：处理 pending 包或读新包
                    break;
                case FINISHED:
                    // 前面的 if 已经拦截，这里不会执行
                    return null;
            }
            if(pendingPkt != null){
                int sendRet = avcodec_send_packet(videoCodecCtx,pendingPkt);
                if (sendRet == 0){
                    av_packet_unref(pendingPkt);
                    pendingPkt = null;
                    continue;
                }else if (sendRet == AVERROR_EAGAIN()){
                    continue;
                }else {
                    av_packet_unref(pendingPkt);
                    pendingPkt = null;
                    state = State.FINISHED;
                    return null;
                }
            }
            int readRet = av_read_frame(fmtCtx,pkt);
            if(readRet<0){
                int flushRet = avcodec_send_packet(videoCodecCtx,(AVPacket) null);
                if (flushRet <0 && flushRet != AVERROR_ERANGE()){
                    state = State.FINISHED;
                    return null;
                }
                state = State.DRAINING;
                continue;
            }
            if (pkt.stream_index() != videoStreamIndex){
                av_packet_unref(pkt);
                continue;
            }
            int sendRet = avcodec_send_packet(videoCodecCtx,pkt);
            if(sendRet == 0){
                av_packet_unref(pkt);
                continue;
            }else if(sendRet == AVERROR_EAGAIN()){
                pendingPkt = pkt;
                continue;
            } else {
                av_packet_unref(pkt);
                state = State.FINISHED;
                return null;
            }
        }
    }
    public FFmpegFrameGrabber(Path videoPath,int pixelFormat) throws IOException {
        this(videoPath,pixelFormat,(AVDictionary) null);
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public double getFps() { return fps; }
    /** 最近一次成功解码帧的时间戳（微秒）。 */
    public long getLastTimestampUs() { return lastTimestampUs; }
    /** 访问内部管道池（渲染线程用 acquire/release）。 */
    public FrameBufferPoolWithQueue getPool() { return pool; }

    public void reset() throws IOException {
        if (state == State.FINISHED) {
            state = State.READING;
        }
        if (pendingPkt != null) {
            av_packet_unref(pendingPkt); // 释放引用，但不释放外壳
            pendingPkt = null;
        }
        if (pkt != null) {
            av_packet_unref(pkt); // 清空内部数据，但不释放外壳
        }
        avcodec_flush_buffers(videoCodecCtx);
        if (audioCodecCtx != null) {
            avcodec_flush_buffers(audioCodecCtx);
        }
        int seekRet = av_seek_frame(fmtCtx, -1, 0, AVSEEK_FLAG_BACKWARD);
        if (seekRet < 0) {
            throw new IOException("Failed to seek to beginning of file: " + seekRet);
        }
        state = State.READING;
        if (frame != null) {
            av_frame_unref(frame);
        }
    }
    @Override
    public void close() {
        if (frame != null) { av_frame_free(frame); frame = null; }
        if (pkt != null)   { av_packet_free(pkt);   pkt = null; pendingPkt =null;}
        if (pendingPkt != null)   { av_packet_free(pendingPkt);   pkt = null; pendingPkt =null;}
        // 释放常驻指针包装（DirectByteBuffer 由 JVM Cleaner 管理，无需 av_free）
        if (rgbaLinesize != null) rgbaLinesize.deallocate();
        pool.close();
        if (swsContext != null && !swsContext.isNull()) { sws_freeContext(swsContext); }
        if (videoCodecCtx != null && !videoCodecCtx.isNull()) { avcodec_free_context(videoCodecCtx); }
        if (audioCodecCtx != null && !audioCodecCtx.isNull()) { avcodec_free_context(audioCodecCtx); }
        if (fmtCtx != null && !fmtCtx.isNull()) { avformat_close_input(fmtCtx); }

        state = null;
    }
}
