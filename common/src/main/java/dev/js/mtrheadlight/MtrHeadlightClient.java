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
