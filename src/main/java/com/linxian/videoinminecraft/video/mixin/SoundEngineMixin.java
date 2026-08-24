package com.linxian.videoinminecraft.video.mixin;

import com.linxian.videoinminecraft.VideoInMinecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public class SoundEngineMixin {
    @Inject(method = "reload", at = @At("TAIL"))
    private void videoInMinecraft$afterReload(CallbackInfo ci) {
        VideoInMinecraft.client.soundEngineReady = true;
        if (VideoInMinecraft.client.videoScreen != null) {
            VideoInMinecraft.client.videoScreen.onSoundEngineReady();
        }
    }
}
