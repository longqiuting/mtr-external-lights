package dev.js.mtrheadlight;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /mtr_lte} - client command that opens the config screen.
 *
 * <p>{@code /externallights} is registered as a second root with the same body: the mod shipped
 * under that name before the rename to LTE, and keeping it for one release means existing muscle
 * memory and older guides keep working. Both roots are built by {@link #openConfig}, so the two can
 * never drift apart.
 */
public final class Commands {
    /** Current name. */
    private static final String NAME = "mtr_lte";
    /** Pre-rename name, kept as an alias for one release. */
    private static final String LEGACY_NAME = "externallights";

    private Commands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(openConfig(ClientCommandManager.literal(NAME)));
            dispatcher.register(openConfig(ClientCommandManager.literal(LEGACY_NAME)));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> openConfig(
            final LiteralArgumentBuilder<FabricClientCommandSource> root) {
        return root.executes(context -> {
            context.getSource().sendFeedback(Text.literal("LTE：指令执行成功"));
            // Same entry point as the key binding - see MtrHeadlightClient#openConfigScreen.
            MtrHeadlightClient.openConfigScreen();
            return 1;
        });
    }
}
