package com.linxian.videoinminecraft.video;

import com.linxian.videoinminecraft.VideoInMinecraft;
import com.linxian.videoinminecraft.video.play.AudioPlayer;
import com.linxian.videoinminecraft.video.play.Clock;
import com.linxian.videoinminecraft.video.play.ImagePlayer;
import com.linxian.videoinminecraft.video.play.VideoDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

public class VideoPlayer {
    public static class VideoPlayerState{
        public enum State{
            /** 空闲，未加载解码器 */
            FREE(true),
            /** 空闲，解码器已加载 */
            READY(false),
            /** 播放中 */
            PLAYING(false),
            /** 暂停中 */
            PAUSING(false),
            /** 跳转中 */
            SEEKING(true),
            /** 关闭 */
            CLOSED(true);
            private final boolean FROZEN;
            State(boolean FROZEN){
                this.FROZEN = FROZEN;
            }
            public boolean isFROZEN(){return this.FROZEN;}
        }
        public State state = State.FREE;
        public boolean isClosed(){return this.state == State.CLOSED;}
        public boolean isPausing(){return this.state == State.PAUSING;}
        public boolean isPlaying(){return this.state == State.PLAYING;}
    }
    private VideoDecoder videoDecoder;
    private final DynamicTexture dynamicTexture;
    private final int bindDecoderID;
    private final String registerName;
    public final ResourceLocation textureLocation;

    public final int width;
    public final int height;


    private Clock clock;
    private AudioPlayer audioPlayer;
    private ImagePlayer imagePlayer;

    private VideoPlayerState stateContainer;

    public VideoPlayer(String videoName){
        VideoDecoder.CreateDecoderResult result = VideoDecoder.createVideoDecode(videoName);
        if (result.isSuccess()){
            this.videoDecoder = result.videoDecoder();
        }else {
            throw new RuntimeException(result.resultReason().getReason());
        }
        this.bindDecoderID = result.decoderID();
        this.width = this.videoDecoder.getVIDEOMETA().getWIDTH();
        this.height = this.videoDecoder.getVIDEOMETA().getHEIGHT();
        this.dynamicTexture =new DynamicTexture(this.width,this.height,false);
        this.registerName = "videoplayer" + String.valueOf(bindDecoderID);
        this.textureLocation = Minecraft.getInstance().getTextureManager().register(this.registerName,this.dynamicTexture);
        this.stateContainer = new VideoPlayerState();
        this.stateContainer.state = VideoPlayerState.State.READY;
        this.clock = new Clock(this.stateContainer);
        this.imagePlayer = new ImagePlayer(this.videoDecoder.getPool(), this.clock,this.dynamicTexture.getId(),this.width,this.height);
        this.audioPlayer = new AudioPlayer(this.videoDecoder.getPool(), this.clock);
    }
    public void startDecoder(){
        this.videoDecoder.StartDecodeThread();
    }

    public void playAudio(){
        this.audioPlayer.upload();
    }

    public void playImage(){
        this.imagePlayer.render();

    }

    public void seek(long targetSecond){
        this.videoDecoder.seek(targetSecond);
    }


    public void dispose(){
        this.audioPlayer.dispose();
    }

    public void close() {
        VideoInMinecraft.LOGGER.debug("CLOSE-try:{}", this.stateContainer.state);
        if (this.stateContainer.state == VideoPlayerState.State.CLOSED) return;
        if (!this.videoDecoder.isDestroyed()) {
            this.audioPlayer.dispose();
            this.videoDecoder.destroy();
        }
        this.videoDecoder = null;
        this.audioPlayer = null;
        this.imagePlayer = null;
        this.clock = null;
        Minecraft.getInstance().getTextureManager().release(this.textureLocation);
        this.stateContainer.state = VideoPlayerState.State.CLOSED;
    }
}
