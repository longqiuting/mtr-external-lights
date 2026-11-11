package dev.js.mtrheadlight;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side hook for {@code lte_vehicle_lights.json}.
 *
 * <p>Reads {@code config/lte_lights/**} and {@code <world>/datapacks/**} at server start and again
 * after every datapack reload, so a server that runs collision / light authority uses the same
 * lamp positions the clients see. On a dedicated server the client's resource packs are not
 * mounted (see {@link VehicleLightPacks}), which is why these two folders are the server's copy of
 * the format: exporting from the client produces exactly the file to drop there.
 *
 * <p>In a single-player world this entry point still runs, but it must not clobber the client
 * layer that shares the same static store - the client already loaded the packs and, being the
 * same process, its view <em>is</em> the server's view.
 */
public final class VehicleLightsJsonServer implements ModInitializer {

    @Override
    public void onInitialize() {
        try {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> reload(server));
            ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
                if (success) {
                    reload(server);
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] lights json: server hooks unavailable", t);
        }
    }

    private static void reload(final net.minecraft.server.MinecraftServer server) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            // Integrated server: the client layer in this JVM already holds pack + config data.
            return;
        }
        try {
            VehicleLightPacks.reloadServer(server);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] lights json: server scan failed", t);
        }
    }
}
