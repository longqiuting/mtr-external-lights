package dev.js.mtrheadlight;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Client hook for {@code assets/<namespace>/lte_vehicle_lights.json}.
 *
 * <p>The scan is a reload listener rather than an init-time call, so F3+T (or a pack being added
 * or removed) re-reads the layouts instead of leaving the old ones cached - which is what
 * {@code VehicleLightOverrides.reload()} exists for. The load stage is empty on purpose: the json
 * is a few kilobytes and its only consumer is the renderer, so doing it in {@code apply} on the
 * game thread keeps {@link VehicleLightOverrides} free of cross-thread publication.
 */
public final class VehicleLightsJsonClient implements ClientModInitializer {

    private static final Identifier ID = new Identifier("mtrlte", "vehicle_lights_json");

    @Override
    public void onInitializeClient() {
        try {
            ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                    .registerReloadListener(new Listener());
        } catch (Throwable t) {
            // A missing resource-loader API must not take the client down: the editor simply
            // starts empty and export still works.
            LteLog.LOGGER.warn("[lte] lights json: reload listener unavailable", t);
        }
        try {
            // Belt and braces: the reload listener normally fires during the client's first
            // resource load, but if it does not (older loader, a pack added mid-session) this
            // makes sure the config folder is read and the diagnostic line is printed once.
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                try {
                    // Only when no reload has ever populated the store - otherwise this would
                    // replace the pack stack with the (poorer) config-folder-only view.
                    if (!VehicleLightOverrides.loaded()) {
                        VehicleLightPacks.apply(VehicleLightPacks.configSources());
                    }
                } catch (Throwable t) {
                    LteLog.LOGGER.warn("[lte] lights json: startup scan failed", t);
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] lights json: no client lifecycle hook", t);
        }
    }

    private static final class Listener implements SimpleResourceReloadListener<Void> {

        @Override
        public Identifier getFabricId() {
            return ID;
        }

        @Override
        public CompletableFuture<Void> load(final ResourceManager manager, final Profiler profiler,
                                            final Executor executor) {
            // Everything happens in apply(): a broken pack must never fail the reload future.
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> apply(final Void data, final ResourceManager manager,
                                             final Profiler profiler, final Executor executor) {
            try {
                VehicleLightPacks.reloadClient(manager);
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] lights json: client scan failed", t);
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
