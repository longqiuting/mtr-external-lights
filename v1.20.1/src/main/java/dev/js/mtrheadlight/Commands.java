package dev.js.mtrheadlight;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** /externallights - client command that opens the config screen. */
public final class Commands {
    private Commands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("externallights").executes(context -> {
                    context.getSource().sendFeedback(Text.literal("MTR外部光源：指令执行成功"));
                    MtrHeadlightClient.openConfigScreen();
                    return 1;
                })));
    }
}
