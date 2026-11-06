package dev.js.mtrheadlight;

import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

/**
 * /externallights on 1.18.2.
 *
 * <p>Fabric API 0.77 for 1.18.2 has no client-command API, so this registers a normal server command
 * and opens the screen on the client thread. In singleplayer that means cheats must be enabled; the
 * Mod Menu entry and the key binding work regardless.
 */
public final class Commands {
    private Commands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) ->
                dispatcher.register(CommandManager.literal("externallights").executes(context -> {
                    context.getSource().sendFeedback(Text.of("MTR外部光源：指令执行成功"), false);
                    // Same entry point as the key binding - see MtrHeadlightClient#openConfigScreen.
                    MtrHeadlightClient.openConfigScreen();
                    return 1;
                })));
    }
}
