package com.linxian.videoinminecraft.video.mixin;

import com.linxian.videoinminecraft.video.play.AudioPlay;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@link Channel#pumpBuffers} 循环体内、每次 {@code SoundBuffer#releaseAlBuffer()}
 * （内部同步 alBufferData 上传完成）之后，归还该次 read() 取出的槽。
 */
@Mixin(Channel.class)
public class ChannelMixin {
    @Shadow private AudioStream stream;

    @Inject(
            method = "pumpBuffers",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/audio/SoundBuffer;releaseAlBuffer()Ljava/util/OptionalInt;",
                    shift = At.Shift.AFTER
            )
    )
    private void videoInMinecraft$releaseBuffer(CallbackInfo ci) {
        if (this.stream instanceof AudioPlay.VideoAudioStream vas) {
            Runnable release = vas.release;
            if (release != null) {
                release.run();
            }
        }
    }

    /**
     * OpenAL 真正开始播放（play() → alSourcePlay）时锚定音频主时钟。
     * 注意不能用 read 锚定：attachBufferStream 会 pumpBuffers(4) 预缓存，尚未播放。
     */
    @Inject(method = "play", at = @At("HEAD"))
    private void videoInMinecraft$playStart(CallbackInfo ci) {
        if (this.stream instanceof AudioPlay.VideoAudioStream vas) {
            vas.notifyPlayStart();
        }
    }

    /** 音频暂停（游戏菜单暂停等）：冻结主时钟，避免恢复后快进追赶。 */
    @Inject(method = "pause", at = @At("HEAD"))
    private void videoInMinecraft$pauseStart(CallbackInfo ci) {
        if (this.stream instanceof AudioPlay.VideoAudioStream vas) {
            vas.notifyPause();
        }
    }

    /** 音频恢复：继续推进主时钟。 */
    @Inject(method = "unpause", at = @At("HEAD"))
    private void videoInMinecraft$resumeStart(CallbackInfo ci) {
        if (this.stream instanceof AudioPlay.VideoAudioStream vas) {
            vas.notifyResume();
        }
    }
}
