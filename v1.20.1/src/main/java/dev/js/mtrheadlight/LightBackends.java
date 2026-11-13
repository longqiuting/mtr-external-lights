package dev.js.mtrheadlight;

import net.minecraft.util.math.Vec3d;

/**
 * Version-local selection of the light backend implementation.
 *
 * <h2>There is exactly one backend now: real block light</h2>
 * LambDynamicLights is <b>gone</b> from 1.20.1. It was never the right tool for a moving train
 * light: it lights a radius around a point, a car head light is a wedge that has to reach 16 blocks
 * and fall off along its length, and every LDL generation had a different (and partly broken) way of
 * re-baking a chunk when a source moved - which is why this module used to carry a fourth LDL
 * backend (deleted with this rework; the 1.18.2 / 1.19.x modules still have theirs) and a pile of notes about
 * which one deadlocked or left the ground lit after the beam went out.
 *
 * <p>The remaining light that lands on the world is {@link BeamBlockLight}: our own contribution to
 * the vanilla block-light nibble while a section is meshed, driven by {@code BeamRelight}. It has no
 * third-party dependency, no entrypoint, no reflection, and it produces exactly the shape the
 * {@code BeamBlockLight#level} maths describes (a widening wedge, brightest on the axis).
 *
 * <p>This class is kept (rather than deleting the interface) because {@link LightController} still
 * drives a per-end state machine through {@link LightBackend} - hide / update / remove on the
 * transition tick - and that bookkeeping is worth keeping even when the sink ignores it: it is what
 * guarantees a lamp that is switched off is not published again on the next tick.
 */
public final class LightBackends {
    private static final LightBackend BLOCK = new BlockBackend();

    private LightBackends() {
    }

    public static LightBackend backend() {
        return BLOCK;
    }

    /**
     * Always {@code true}: the dynamic-light switch now controls {@link BeamBlockLight}, which is
     * part of this mod. It used to be false without LambDynamicLights, and the config screen greyed
     * the row out - there is nothing to install any more.
     */
    public static boolean available() {
        return true;
    }

    /**
     * Sink for the per-end line registrations. It stores nothing: the real light is published as a
     * beam snapshot by {@link LightController} itself ({@link BeamBlockLight#publish}), because the
     * block-light path needs the wedge (start, end, radius, peak) and not a generic "luminance".
     */
    private static final class BlockBackend implements LightBackend {
        @Override
        public void updateLine(final String key, final Vec3d start, final Vec3d end,
                               final int luminance) {
            // The wedge is already published by LightController; nothing to register per key.
        }

        @Override
        public void hideLine(final String key) {
            // Nothing is registered, nothing to dim - the next publish simply omits the beam.
        }

        @Override
        public void removeLine(final String key) {
            // Nothing is registered, nothing to detach.
        }

        @Override
        public void clearAll() {
            // The snapshot is cleared by BeamBlockLight.clear() from LightController.clear().
        }

        /** No engine-side sources: the snapshot's own beam count is what the diagnostics report. */
        @Override
        public int liveSources() {
            return 0;
        }

        @Override
        public void tick() {
            // No engine to poll.
        }
    }
}
