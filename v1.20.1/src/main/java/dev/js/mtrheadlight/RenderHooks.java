package dev.js.mtrheadlight;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Version-local hook for 1.20.1: the world-render callbacks (self-drawn light beams) and the key
 * binding, plus the throttled rail relight scheduler.
 */
public final class RenderHooks {
    private RenderHooks() {
    }

    public static void init() {
        FangsuCompat.announce();

        // The beam sheets are our own geometry and completely independent of the relight
        // scheduler: they draw themselves and never queue a chunk. The early/late pair exists so
        // exactly one pass draws, depending on whether a shader pack composites after translucent.
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            try {
                BeamGlow.render(context, false);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[mtr-headlight] beam glow failed", t);
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            try {
                BeamGlow.render(context, true);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[mtr-headlight] beam glow (late pass) failed", t);
            }
        });

        try {
            KeyHooks.init();
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] could not register the key binding", t);
        }

        // Rail lighting itself is baked into ANTE's rail models by the mixins; the scheduler only
        // decides when a section has to be rebuilt. It is a no-op when ANTE is missing.
        try {
            RailRelightScheduler.install();
        } catch (Throwable t) {
            // ANTE/NTE missing or too old: rail baking stays off, the rest keeps working.
        }
    }
}
