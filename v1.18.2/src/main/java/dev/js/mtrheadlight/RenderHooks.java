package dev.js.mtrheadlight;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Version-local render hooks.
 *
 * <p>Registers the self-drawn light-beam sheets ({@code beamGlow} on by default), the key
 * binding, and the throttled relight scheduler. The mixins already sit inside NTE's mesh build, so
 * the baked rail light works on its own - the scheduler is a no-op when neither ANTE nor NTE is
 * installed.
 */
public final class RenderHooks {
    private RenderHooks() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            try {
                BeamGlow.render(context, false);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] beam glow failed", t);
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            try {
                BeamGlow.render(context, true);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] beam glow (late pass) failed", t);
            }
        });

        try {
            KeyHooks.init();
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] could not register the key binding", t);
        }

        try {
            RailRelightScheduler.install();
        } catch (Throwable t) {
            // Host missing or too old: rail baking stays off, the rest keeps working.
        }
    }
}
