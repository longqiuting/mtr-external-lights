package dev.js.mtrheadlight;

import net.minecraft.client.gui.screen.Screen;

/**
 * Global settings: the master switch plus the parameters every train uses unless its siding
 * overrides them (render distance, head luminance, beam length, rail light, rail glow).
 *
 * <p>Everything lives on the screen now - a player had to edit {@code config/mtr-headlight.json}
 * for a distance change before, which is not a setting. The two things this screen deliberately
 * does <em>not</em> do are the tuning knobs that are not per-train parameters (relight cadence,
 * offsets, glow width/alpha, the shader boost): those stay in the JSON, which the hint line points
 * at.
 *
 * <p>The seventh row opens {@link SidingScreen}. It is disabled (and says so on hover) while no
 * world is loaded: MTR's siding list is empty on the title screen, so there is nothing behind it.
 *
 * <p>All seven controls, the layout and the save behaviour come from {@link ParameterScreen} - the
 * same code the per-siding detail screen uses, so both screens always offer the same set.
 */
public final class ConfigScreen extends ParameterScreen {

    public ConfigScreen(final Screen parent) {
        super(parent, null);
    }
}
