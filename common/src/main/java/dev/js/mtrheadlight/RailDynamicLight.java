package dev.js.mtrheadlight;

import net.minecraft.util.math.BlockPos;

/**
 * Dynamic-light lookup used by the rail bake (1.20.1).
 *
 * <h2>Only one source now: our own beam</h2>
 * This used to ask a third-party dynamic-light engine for the light level at a rail sample, so a
 * held torch or another mod's light also brightened the rails, and take the max with our own beam
 * snapshot. That engine was removed from 1.20.1 with the light rework - the mod no longer owns any
 * dynamic light source there - so the reflective lookup, its {@code MethodHandle} cache and the two
 * bugs documented around it are gone with it.
 *
 * <p>What is left is {@link RailLightState#ownLevel}: the train beams this mod publishes through
 * {@link BeamBlockLight}, sampled at a rail vertex. That is what the rail bake needs, and nothing
 * else in the mod ever queried the engine.
 *
 * <p>{@code railLightGain} still applies - it amplifies the <em>train</em> beam only, because a beam
 * head is dimmer than a torch and shader packs curve the lightmap super-linearly.
 */
public final class RailDynamicLight {
    /** One-shot diagnostics: the bake runs on worker threads, so this must stay a plain flag. */
    private static volatile boolean announced;

    private RailDynamicLight() {
    }

    /** Dynamic light level (0-15) at a block position, or 0 when no beam reaches it. */
    public static int level(final BlockPos pos) {
        final double own = ownLevel(pos);
        if (own <= 0.0D) {
            return 0;
        }
        final int rounded = (int) Math.round(Math.min(15.0D, own));
        return rounded < 0 ? 0 : (rounded > 15 ? 15 : rounded);
    }

    /** Our own-beam level at {@code pos}, scaled by {@link HeadlightConfig#railLightGain}. */
    private static double ownLevel(final BlockPos pos) {
        double own;
        try {
            own = RailLightState.ownLevel(pos) * ownGain();
        } catch (Throwable t) {
            own = 0.0D;
        }
        RailDiag.observeOwn(own);
        if (!announced && own > 0.0D) {
            announced = true;
            // Proof the rail bake is seeing the beams at all; without it "rails stay dark" is
            // indistinguishable from "the beams were never published".
            MtrHeadlightClient.LOGGER.info("[lte] rail light: own beam reached the rails at {}", pos);
        }
        return own;
    }

    private static double ownGain() {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            return cfg == null ? 1.0D : cfg.railLightGain;
        } catch (Throwable t) {
            return 1.0D;
        }
    }
}
