package dev.js.mtrheadlight;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /mtr_lte} - client command.
 *
 * <p>{@code /externallights} is registered as a second root with the same body: the mod shipped
 * under that name before the rename to LTE. Both roots are built by {@link #build}, so the alias
 * gets every subcommand too and the two can never drift apart.
 *
 * <ul>
 *   <li>no argument: opens the config screen (same entry point as the key binding);</li>
 *   <li>{@code ldlreg on|off}: the A/B switch for LambDynamicLights registration (1.19.4). Off
 *       unregisters every source we own, which is how "is it us?" is answered in one step without
 *       editing JSON or restarting the game;</li>
 *   <li>{@code ldl}: prints our source count next to LDL's.</li>
 * </ul>
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
            dispatcher.register(build(ClientCommandManager.literal(NAME)));
            dispatcher.register(build(ClientCommandManager.literal(LEGACY_NAME)));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(
            final LiteralArgumentBuilder<FabricClientCommandSource> root) {
        return root
                .executes(context -> {
                    context.getSource().sendFeedback(
                            Text.literal("LTE：指令执行成功"));
                    // Same entry point as the key binding - see MtrHeadlightClient.
                    MtrHeadlightClient.openConfigScreen();
                    return 1;
                })
                .then(ClientCommandManager.literal("ldlreg")
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> setRegistration(context.getSource(), true)))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> setRegistration(context.getSource(), false))))
                .then(ClientCommandManager.literal("ldl")
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(
                                    "LDL 注册：" + (MtrHeadlightClient.config().ldlRegisterSources
                                            ? "开（我们的光源已交给 LDL）"
                                            : "关（A/B 对照：我们的光源未注册）")));
                            return 1;
                        }));
    }

    /**
     * Flips {@link HeadlightConfig#ldlRegisterSources} in the live config, saves it and clears our
     * lines so the change is visible on the very next tick instead of at the next relog.
     */
    private static int setRegistration(final FabricClientCommandSource source,
                                       final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.ldlRegisterSources = on;
        try {
            cfg.save();
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] could not save the config", t);
        }
        // clearAll() detaches every point we own, so "off" leaves LDL's set containing only its own
        // sources (player torch, entities, block entities); "on" rebuilds them on the next tick.
        LightController.reload();
        source.sendFeedback(Text.literal(on
                ? "LTE：已重新注册我们的动态光源（A/B 对照结束）"
                : "LTE：已注销我们的全部动态光源（A/B 对照开始：现在只有 LDL 自己的光源）"));
        return 1;
    }
}
