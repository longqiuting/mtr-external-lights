package dev.js.mtrheadlight;

import com.mojang.blaze3d.systems.RenderSystem;
import static net.minecraft.client.gui.DrawableHelper.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The "are you sure?" step in front of 全部恢复默认.
 *
 * <p>{@link ParameterScreen} opens this instead of resetting straight away, because 全部恢复默认 is
 * the one action on the screen that throws away work the player cannot see any more (the siding
 * overrides are deleted, and the rows that showed them are scrolled out of view). Two buttons only:
 * 确认恢复 applies the reset and returns to the parameter screen, 取消 / ESC returns untouched.
 *
 * <p>It is a separate screen rather than a modal inside {@link ParameterScreen} so the parameter
 * screen's own {@code init()} - and with it the layout and the row list - is rebuilt when we hand
 * control back: after a reset every label and slider has to show the new values.
 *
 * <p>Nothing is written to disk here either. The reset edits the live config exactly like a slider
 * move does, so 取消 / 返回 on the parameter screen still rolls it back through the snapshot it took
 * when it opened.
 */
final class ResetConfirmScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");
    private static final int BTN_W = 150;
    private static final int BTN_H = 20;
    private static final int GAP = 8;

    private final Screen parent;
    private final Text message;
    private final Runnable onConfirm;

    ResetConfirmScreen(final Screen parent, final Text title, final Text message,
                       final Runnable onConfirm) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        final int y = this.height / 2 + 16;
        final int left = this.width / 2 - (BTN_W * 2 + GAP) / 2;
        // 1.19.2 / 1.18.2 dereference the tooltip supplier on hover, so EMPTY is explicit.
        this.addDrawableChild(new ButtonWidget(left, y, BTN_W, BTN_H,
                ParameterScreen.tr("btn.resetConfirm"), button -> {
                    this.onConfirm.run();
                    close();
                }, ButtonWidget.EMPTY));
        this.addDrawableChild(new ButtonWidget(left + BTN_W + GAP, y, BTN_W, BTN_H,
                ParameterScreen.tr("btn.resetAbort"), button -> close(), ButtonWidget.EMPTY));
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        RenderSystem.setShaderTexture(0, OPTIONS_BACKGROUND);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        drawTexture(matrices, 0, 0, 0.0F, 0.0F, this.width, this.height, 32, 32);
        fillGradient(matrices, 0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(matrices, mouseX, mouseY, delta);

        drawCentered(matrices, this.title, this.width / 2, this.height / 2 - 44, 0xFFFFFF);

        // Wrapped from the real font, so a longer translation cannot run off the window.
        final List<OrderedText> lines = this.textRenderer.wrapLines(this.message,
                Math.max(120, Math.min(this.width - 60, 340)));
        int y = this.height / 2 - 22;
        for (final OrderedText line : lines) {
            drawCentered(matrices, line, this.width / 2, y, 0xE0E0E0);
            y += 12;
        }
    }

    /** Centred already-wrapped text; the MatrixStack API has no Text variant to reuse. */
    private void drawCentered(final MatrixStack matrices, final OrderedText text, final int cx,
                              final int y, final int color) {
        this.textRenderer.drawWithShadow(matrices, text,
                (float) (cx - this.textRenderer.getWidth(text) / 2), (float) y, color);
    }

    private void drawCentered(final MatrixStack matrices, final Text text, final int cx,
                              final int y, final int color) {
        this.drawCentered(matrices, text.asOrderedText(), cx, y, color);
    }

    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        return this.addDrawableChild(new ButtonWidget(x, y, w, h, label, action, ButtonWidget.EMPTY));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC = 取消: back to the parameter screen with nothing changed. */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
