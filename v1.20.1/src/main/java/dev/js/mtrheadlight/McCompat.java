package dev.js.mtrheadlight;

import net.minecraft.client.MinecraftClient;

/** Minecraft API that differs between the supported versions, kept in one place. */
public final class McCompat {
    private McCompat() {
    }

    /** The client's render distance in chunks, or 0 when it cannot be read. */
    public static int viewDistanceChunks() {
        try {
            return MinecraftClient.getInstance().options.getViewDistance().getValue();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
