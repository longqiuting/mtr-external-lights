package dev.js.mtrheadlight;

import mtr.data.Siding;
import net.minecraft.client.gui.screen.Screen;

/**
 * One siding's overrides: the same seven controls as {@link ConfigScreen}, but every edit writes
 * that siding's {@link HeadlightConfig.SidingSettings} entry instead of the global field.
 *
 * <p>Untouched controls keep following the global value and say so in their label; "恢复为跟随全局"
 * deletes the entry, which drops every override for this siding at once.
 *
 * <p>Which trains this affects: a train lights with the settings of the siding it was dispatched
 * from ({@code Train.sidingId}), which is the only siding relation MTR sends to the client.
 */
public final class SidingDetailScreen extends ParameterScreen {

    public SidingDetailScreen(final Screen parent, final Siding siding) {
        super(parent, siding);
    }
}
