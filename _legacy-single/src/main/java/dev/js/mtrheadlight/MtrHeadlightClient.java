package dev.js.mtrheadlight;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MtrHeadlightClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mtr-headlight");
    private static HeadlightConfig config = new HeadlightConfig();

    public static HeadlightConfig config() {
        return config;
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
        // /externallights - client-side command that opens the config screen.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("externallights").executes(context -> {
                    final MinecraftClient client = MinecraftClient.getInstance();
                    context.getSource().sendFeedback(Text.literal("MTR-headlights模组：指令执行成功"));
                    client.send(() -> client.setScreen(new ConfigScreen(client.currentScreen)));
                    return 1;
                })));
        LOGGER.info("[mtr-headlight] client initialised (head={} tail={} len={}/{})",
                config.headLuminance, config.tailLuminance, config.headLength, config.tailLength);
    }
}
