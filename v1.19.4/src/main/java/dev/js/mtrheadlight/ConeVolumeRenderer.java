package dev.js.mtrheadlight;

import net.minecraft.client.util.math.MatrixStack;

import java.util.List;

/**
 * Non-1.20.1 fallback: the depth-aware cone needs a core-shader registration hook and a
 * framebuffer depth blit that only the 1.20.1 build carries. {@link #ready} always answers
 * false, so {@link ProtoBeamCone} keeps drawing the legacy additive shell cone on this version.
 */
final class ConeVolumeRenderer {
    private ConeVolumeRenderer() {
    }

    static void init() {
    }

    static boolean ready(final HeadlightConfig cfg) {
        return false;
    }

    static String status(final HeadlightConfig cfg) {
        return "unsupported";
    }

    /**
     * No-op on versions without the depth-aware cone: there is no statistics window to flush.
     * Present so {@code ProtoBeamCone}'s five-second block still links on this version.
     */
    static void logIfDue() {
    }

    static boolean render(final List<ConeVolumeBeam> beams, final MatrixStack stack,
                          final HeadlightConfig cfg) {
        return false;
    }
}
