package dev.js.mtrheadlight.ldl;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entrypoint that drives {@link PlayerDynamicLight} once per tick.
 *
 * <p>It is a separate entrypoint (rather than a line inside the shared
 * {@code MtrHeadlightClient}) because that class lives in {@code common/} and is compiled for the
 * 1.18.2 / 1.19.x modules too, where the 1.20.1-only {@code ldl} package does not exist. Registering
 * the tick here keeps the shared code free of version-specific names.
 *
 * <p>Nothing in this class touches LambDynamicLights: without it, {@link PlayerDynamicLight} answers
 * {@code false} and the tick is a single boolean check.
 *
 * <p>The first log line below is deliberately unconditional: it is the proof that the <b>client</b>
 * entrypoint of this jar ran at all, which is the one thing a report about "no light" can never
 * establish on its own. The {@code [lte] ldl: ready|missing ...} line that follows it belongs to
 * {@link PlayerDynamicLight} and says whether LambDynamicLights handed over a manager.
 */
public final class PlayerLightClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Resolve the branch once at startup so the log says which path this client is on, even if
        // the player never picks up a torch in this session.
        final boolean available = PlayerDynamicLight.available();
        LteLog.LOGGER.info("[lte] ldl: tick-handler registered (lambdynlights={} available={})",
                PlayerDynamicLight.modPresent(), available);
        ClientTickEvents.END_CLIENT_TICK.register(PlayerDynamicLight::tick);
        // Detach on shutdown: LDL keeps the behaviour in its manager otherwise, and a manager that
        // outlives the client is the kind of thing that leaves a lit block in a fresh world.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PlayerDynamicLight.clear());
    }
}
