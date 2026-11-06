package dev.js.mtrheadlight;

import net.minecraft.client.gui.screen.Screen;

/**
 * Global settings: the master switch, its two children (动态光源 / 光带) and the parameters every
 * train uses unless its siding overrides them (render distance, head luminance, beam length, the
 * beam shape, the two beam colours).
 *
 * <p>Everything is one scrollable list of rows, each row the same two cells wide - see
 * {@link ParameterScreen} for the layout and the cancel/save contract. The per-siding entry
 * ("按侧线配置…") is the last row of the parameter block, below every beam setting.
 *
 * <p>底部: 完成 writes the config and returns to its parent (the mod menu), 取消 rolls every edit
 * made here back and returns to the parent as well.
 *
 * <p>Tuning knobs that are not per-train parameters (relight cadence, offsets, rail light under
 * shaders) stay in the JSON, which the hint line points at.
 */
public final class ConfigScreen extends ParameterScreen {

    public ConfigScreen(final Screen parent) {
        super(parent, null);
    }
}
