package dev.js.mtrheadlight;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

/**
 * {@code /externallights} - client command.
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
    private Commands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("externallights")
                        .executes(context -> {
                            context.getSource().sendFeedback(
                                    Text.literal("MTR外部光源：指令执行成功"));
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
                                }))));
    }

    /**
     * Flips {@link HeadlightConfig#ldlRegisterSources} in the live config, saves it and clears our
     * lines so the change is visible on the very next tick instead of at the next relog.
     */
    private static int setRegistration(final net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                                       final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.ldlRegisterSources = on;
        try {
            cfg.save();
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] could not save the config", t);
        }
        // clearAll() detaches every point we own, so "off" leaves LDL's set containing only its own
        // sources (player torch, entities, block entities); "on" rebuilds them on the next tick.
        LightController.reload();
        source.sendFeedback(Text.literal(on
                ? "MTR外部光源：已重新注册我们的动态光源（A/B 对照结束）"
                : "MTR外部光源：已注销我们的全部动态光源（A/B 对照开始：现在只有 LDL 自己的光源）"));
        return 1;
    }
}
