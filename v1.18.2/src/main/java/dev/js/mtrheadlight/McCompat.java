package dev.js.mtrheadlight;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.ParticlesMode;

/** Minecraft API that differs between the supported versions, kept in one place. */
public final class McCompat {
    private McCompat() {
    }

    /**
     * The client's render distance in chunks, or 0 when it cannot be read.
     *
     * <p>1.18.2: {@code GameOptions#getViewDistance()} already returns the {@code int}; the
     * {@code SimpleOption<Integer>} wrapper (and its {@code getValue()}) only exists from 1.19.2 on.
     */
    public static int viewDistanceChunks() {
        try {
            return MinecraftClient.getInstance().options.getViewDistance();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The particle setting, or {@link ParticlesMode#ALL} when it cannot be read.
     *
     * <p>1.18.2 keeps it as a plain {@code public ParticlesMode particles} field on
     * {@code GameOptions}; the {@code getParticles()} accessor returning a {@code SimpleOption}
     * arrives in 1.19.2.
     */
    public static ParticlesMode particlesMode() {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            final ParticlesMode mode = client == null ? null : client.options.particles;
            return mode == null ? ParticlesMode.ALL : mode;
        } catch (Throwable t) {
            return ParticlesMode.ALL;
        }
    }
}
