package dev.js.mtrheadlight;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /mtr_lte} on 1.18.2, with {@code /externallights} kept as an alias for one release.
 *
 * <p>Fabric API 0.77 for 1.18.2 has no client-command API, so this registers normal server commands
 * and opens the screen on the client thread. In singleplayer that means cheats must be enabled; the
 * Mod Menu entry and the key binding work regardless. Both roots are registered in the same event
 * body and built by {@link #openConfig}, so the alias can never fall out of sync with the primary
 * name.
 */
public final class Commands {
    /** Current name. */
    private static final String NAME = "mtr_lte";
    /** Pre-rename name, kept as an alias for one release. */
    private static final String LEGACY_NAME = "externallights";

    private Commands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(openConfig(CommandManager.literal(NAME)));
            dispatcher.register(openConfig(CommandManager.literal(LEGACY_NAME)));
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> openConfig(
            final LiteralArgumentBuilder<ServerCommandSource> root) {
        return root.executes(context -> {
            context.getSource().sendFeedback(Text.of("LTE：指令执行成功"), false);
            // Same entry point as the key binding - see MtrHeadlightClient#openConfigScreen.
            MtrHeadlightClient.openConfigScreen();
            return 1;
        });
    }
}
