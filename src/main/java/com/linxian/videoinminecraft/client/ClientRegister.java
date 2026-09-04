package com.linxian.videoinminecraft.client;

import com.linxian.videoinminecraft.VideoInMinecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import static com.linxian.videoinminecraft.Register.*;
import static com.linxian.videoinminecraft.Register.KEY_CATEGORY;
import static com.linxian.videoinminecraft.Register.KEY_STOP;
import static com.linxian.videoinminecraft.Register.startKey;
import static com.linxian.videoinminecraft.Register.stopKey;

public class ClientRegister {
    @SubscribeEvent
    public void registerKeyBindings(RegisterKeyMappingsEvent event) {
        startKey = new KeyMapping(KEY_START, GLFW.GLFW_KEY_L, KEY_CATEGORY);
        stopKey = new KeyMapping(KEY_STOP, GLFW.GLFW_KEY_I, KEY_CATEGORY);
        event.register(startKey);
        event.register(stopKey);
    }

}
