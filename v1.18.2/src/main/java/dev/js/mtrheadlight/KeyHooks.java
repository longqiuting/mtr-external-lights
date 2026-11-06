package dev.js.mtrheadlight;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

/**
 * Key binding that opens the config screen - the keyboard equivalent of {@code /externallights}.
 *
 * <p><b>Unbound by default.</b> The default key is {@code GLFW_KEY_UNKNOWN}, so the mod adds a row
 * to "选项 → 控制 → 按键绑定" and nothing else: no key is stolen from the player or from another mod,
 * and an unbound binding can never fire (GLFW key codes are non-negative, so {@code wasPressed()}
 * stays false). {@code while} rather than {@code if} because {@code wasPressed()} consumes one
 * queued press per call.
 *
 * <p>1.18.2 note: {@code KeyBinding} has no {@code InputUtil.Type} parameter here - the mouse-button
 * overload arrived in 1.19 - hence the three-argument constructor
 * {@code (translationKey, keyCode, category)}. The registration API is the same.
 *
 * <p>Nothing happens while another screen is open - the player is already in a UI, and stacking one
 * screen on top of an inventory or chat prompt is never what the key was meant to do.
 */
public final class KeyHooks {
    /** Translation keys; both are in assets/mtr_headlight/lang. */
    private static final String OPEN_CONFIG_KEY = "key.mtr_headlight.open_config";
    private static final String CATEGORY_KEY = "key.categories.mtr_headlight";

    private static KeyBinding openConfig;

    private KeyHooks() {
    }

    public static void init() {
        openConfig = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(OPEN_CONFIG_KEY, GLFW.GLFW_KEY_UNKNOWN, CATEGORY_KEY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfig.wasPressed()) {
                if (client.currentScreen == null) {
                    MtrHeadlightClient.openConfigScreen();
                }
            }
        });
    }
}
