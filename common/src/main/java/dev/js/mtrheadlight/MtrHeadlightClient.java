package dev.js.mtrheadlight;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MtrHeadlightClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mtr-headlight");
    private static HeadlightConfig config = new HeadlightConfig();

    public static HeadlightConfig config() {
        return config;
    }

    /**
     * JSON snapshot of the live config (config screen undo). See {@link HeadlightConfig#toJson()}.
     */
    public static String snapshotConfig() {
        return config.toJson();
    }

    /**
     * Replaces the live config with a snapshot - the config screen's 取消 / 返回.
     *
     * <p>The instance is swapped rather than mutated field by field: every consumer calls
     * {@link #config()} again on each tick/frame (there is no long-lived {@code HeadlightConfig}
     * field anywhere), so the swap is picked up immediately and there is no chance of a stale
     * copy being written back. Nothing is saved here - the file on disk keeps whatever it held
     * before the screen opened.
     */
    public static void restoreConfig(final String json) {
        if (json != null) {
            config = HeadlightConfig.fromJson(json);
        }
    }

    /**
     * Opens the config screen on the client thread. The single entry point used by the
     * {@code /externallights} command and by the key binding, so the two can never drift apart.
     *
     * <p>{@code client.send} is required: both callers can run off the render thread (a command is
     * executed on the main thread, a key press is polled from the tick), and creating a screen is
     * only safe on the client thread.
     */
    public static void openConfigScreen() {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            client.send(() -> client.setScreen(new ConfigScreen(client.currentScreen)));
        } catch (Throwable t) {
            LOGGER.warn("[mtr-headlight] could not open the config screen", t);
        }
    }

    @Override
    public void onInitializeClient() {
        config = HeadlightConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                // Departure latch first: the light controller and the beam renderer both ask it
                // whether a train has left its depot.
                DepartureState.tick();
                LightController.tick();
            } catch (Throwable t) {
                // Never let a lighting bug take the client down.
                LOGGER.error("[mtr-headlight] tick failed", t);
            }
        });
        try {
            Commands.register();
        } catch (Throwable t) {
            // Command API differs between versions; the Mod Menu entry still works without it.
            LOGGER.warn("[mtr-headlight] could not register /externallights", t);
        }
        try {
            RenderHooks.init();
        } catch (Throwable t) {
            LOGGER.warn("[mtr-headlight] could not register the rail render hooks", t);
        }
        LOGGER.info("[mtr-headlight] client initialised (head={} tail={} len={}/{})",
                config.headLuminance, config.tailLuminance, config.headLength, config.tailLength);
    }
}
