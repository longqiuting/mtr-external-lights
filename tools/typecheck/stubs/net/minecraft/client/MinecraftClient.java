package net.minecraft.client;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

/** Stub mirroring net.minecraft.client.MinecraftClient (getInstance, world, player). */
public class MinecraftClient {
    public ClientWorld world;
    public ClientPlayerEntity player;

    public static MinecraftClient getInstance() {
        return new MinecraftClient();
    }
}
