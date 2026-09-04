package com.linxian.videoinminecraft.video.play;

import com.linxian.videoinminecraft.VideoInMinecraft;
import com.linxian.videoinminecraft.video.VideoPlayer;

/**时钟类，用于视频播放的音画同步*/
public class Clock {
    private final VideoPlayer.VideoPlayerState stateContainer;
    public Clock(VideoPlayer.VideoPlayerState stateContainer){
        this.stateContainer = stateContainer;
    }
    public boolean isClosed(){return this.stateContainer.state == VideoPlayer.VideoPlayerState.State.CLOSED;}

    private volatile long startTime = -1;

    private long seekPtsUs = -1;

    private long pauseTimeNano = -1;

    public void setStartTime(long startTime){
        this.startTime = startTime;
    }

    public void seekToPtsUs(long targetPtsUs){
        this.seekPtsUs = targetPtsUs;
    }

    //声音回调同步接口
    public void setAudioTimeCallBack(long audioTimeCallBack){
        this.startTime = System.nanoTime()/1_000 - audioTimeCallBack;
    }

    public void start(){
        if (this.stateContainer.state.isFROZEN()) return;
        this.stateContainer.state = VideoPlayer.VideoPlayerState.State.PLAYING;
        this.startTime = System.nanoTime()/1_000;
    }

    public void pause(){
        if (this.stateContainer.state.isFROZEN()) return;
        this.stateContainer.state = VideoPlayer.VideoPlayerState.State.PAUSING;
        this.pauseTimeNano = System.nanoTime();
        VideoInMinecraft.LOGGER.debug("pauseTime:{}",this.pauseTimeNano);
    }

    public void resume(){
        if (this.stateContainer.state.isFROZEN()) return;
        this.stateContainer.state = VideoPlayer.VideoPlayerState.State.PLAYING;
        if (pauseTimeNano < 0) return;
        this.startTime = this.startTime + ((System.nanoTime() - this.pauseTimeNano) / 1_000);
        VideoInMinecraft.LOGGER.debug("start:{}",this.startTime);
        this.pauseTimeNano = -1;
    }

    public void reset(){
        if (this.stateContainer.state.isFROZEN()) return;
        this.stateContainer.state = VideoPlayer.VideoPlayerState.State.PAUSING;
        this.startTime = -1;
        this.pauseTimeNano = -1;
    }
    //============================================//
    public boolean switchImage(long videoPtsUs){
        if (this.stateContainer.state.isFROZEN()) return false;
        if (this.stateContainer.isPausing()) return false;
        //5_000为可能的上传时间（内存->显存）
        return (System.nanoTime()/1_000 - this.startTime) + 5_000 > videoPtsUs;
    }
}
