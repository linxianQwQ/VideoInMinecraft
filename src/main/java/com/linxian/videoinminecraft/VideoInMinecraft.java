package com.linxian.videoinminecraft;


import com.linxian.videoinminecraft.client.Client;
import com.linxian.videoinminecraft.client.ClientRegister;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(VideoInMinecraft.MOD_ID)
public class VideoInMinecraft {
    public static final String MOD_ID = "video_in_minecraft";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final Client client = new Client();
    public static final ClientRegister clientRegister = new ClientRegister();
    public VideoInMinecraft(IEventBus modEventBus, ModContainer modContainer){
        NeoForge.EVENT_BUS.register(client);
        modEventBus.register(clientRegister);
    }

}
