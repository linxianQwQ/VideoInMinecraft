package com.linxian.videoinminecraft;

import net.minecraft.client.KeyMapping;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

public class Register {
    public static final String KEY_CATEGORY = "key.category.videoplayer";
    public static final String KEY_START = "key.videoplayer.start";
    public static final String KEY_STOP = "key.videoplayer.stop";
    public static KeyMapping startKey;
    public static KeyMapping stopKey;
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, VideoInMinecraft.MOD_ID);

}
