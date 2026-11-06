package dev.js.mtrheadlight;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Version-local hook for 1.20.1: the world-render callbacks (self-drawn rail band) and the key
 * binding, plus the throttled rail relight scheduler.
 */
public final class RenderHooks {
    private RenderHooks() {
    }

    public static void init() {
        FangsuCompat.announce();

        // The self-drawn band is opt-in (railGlow=false by default) and completely independent of
        // the relight scheduler: it draws its own geometry and never queues a chunk.
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            try {
                RailGlow.render(context, false);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[mtr-headlight] rail glow failed", t);
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            try {
                RailGlow.render(context, true);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[mtr-headlight] rail glow (late pass) failed", t);
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
