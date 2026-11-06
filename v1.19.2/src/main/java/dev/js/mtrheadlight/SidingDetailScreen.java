package dev.js.mtrheadlight;

import mtr.data.Siding;
import net.minecraft.client.gui.screen.Screen;

import java.util.Objects;

/**
 * One siding's overrides: the same rows as {@link ConfigScreen}, but every edit writes that siding's
 * {@link HeadlightConfig.SidingSettings} entry instead of the global field - through
 * {@link ConfigWriteTarget#siding(HeadlightConfig, long)}, which has no code path that can assign a
 * global field.
 *
 * <p>Untouched rows keep following the global value and say so in their label; 恢复为跟随全局
 * deletes the entry, which drops every override for this siding at once.
 *
 * <p>底部: 保存并返回 writes the config and goes back to the siding list, 返回 rolls this page's
 * edits back (the config snapshot taken when the page opened) and goes back to the list as well.
 *
 * <p>Which trains this affects: a train lights with the settings of the siding it was dispatched
 * from ({@code Train.sidingId}), which is the only siding relation MTR sends to the client.
 */
public final class SidingDetailScreen extends ParameterScreen {

    public SidingDetailScreen(final Screen parent, final Siding siding) {
        // Never null: ParameterScreen treats a null siding as the global screen, and a detail page
        // that silently became the global editor is exactly the "按侧线改配置，全局跟着变" bug.
        super(parent, Objects.requireNonNull(siding, "SidingDetailScreen needs a siding"));
    }
}
