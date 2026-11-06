package dev.js.mtrheadlight;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Colour editor for one beam colour: a swatch grid <b>and</b> a hex field, so a colour that is not
 * in the palette can be typed in directly.
 *
 * <ul>
 *   <li>{@value #COLS} × 5 preset swatches on a dark panel; the current colour is ringed in white,
 *       the hovered one in grey.</li>
 *   <li>The preview bar at the top paints the colour being edited with its {@code #RRGGBB} inside.</li>
 *   <li>Below the grid: a text field pre-filled with the current hex. Typing {@code #RRGGBB} or
 *       {@code RRGGBB} updates the preview live; <b>Enter</b> applies it. An invalid value is
 *       reported in red and is never applied - the previous colour stays selected.</li>
 *   <li>完成 applies, 取消 / ESC returns without touching the config.</li>
 * </ul>
 */
public final class ColorPickerScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int COLS = 8;
    private static final int ROWS = 5;
    /** Preferred swatch size; shrinks on a narrow window so the grid never leaves the screen. */
    private static final int CELL = 16;
    private static final int CELL_GAP = 4;
    /** Padding of the dark panel around the grid. */
    private static final int PAD = 8;
    private static final int BTN_H = 20;
    private static final int GAP = 8;
    /** Height of the preview bar. */
    private static final int PREVIEW_H = 12;
    /** Height of the hex input row. */
    private static final int HEX_H = 18;

    private static final int FRAME = 0xFF000000;
    private static final int FRAME_IDLE = 0xFF3A3A3A;
    private static final int FRAME_HOVER = 0xFFC8C8C8;
    private static final int FRAME_SELECTED = 0xFFFFFFFF;

    /**
     * {cols × rows} presets, row by row. Row 0: warm whites down to the default tail red; row 1:
     * reds / pinks; row 2: amber / green; row 3: cyan / blue / violet; row 4: neutral greys.
     */
    private static final int[] PALETTE = {
            0xFFF3D6, 0xFFF8E7, 0xFFEFC4, 0xFFE2A8, 0xFFD08A, 0xFFBB66, 0xFF9E4A, 0xFF6A5A,
            0xFF8A80, 0xFF5252, 0xE53935, 0xC62828, 0x8E1B1B, 0xF06292, 0xFF4081, 0xD81B60,
            0xFFB300, 0xFFD54F, 0xFFF176, 0xDCE775, 0x9CFFA8, 0x4CD964, 0x2E9E4F, 0x1B7F3B,
            0xE8F4FF, 0x9FD8FF, 0x4FC3F7, 0x29B6F6, 0x2196F3, 0x1565C0, 0x7C4DFF, 0xB388FF,
            0xFFFFFF, 0xD0D0D0, 0x9E9E9E, 0x6D6D6D, 0x424242, 0x212121, 0x8D6E63, 0x5D4037,
    };

    /** Title (20) + hint (34) own the top; the panel starts below them. */
    private static final int CONTENT_TOP = 48;

    private final Screen parentScreen;
    private final IntConsumer onDone;
    private int selected;

    /** Grid geometry, recomputed in init() so the screen survives a resize. */
    private int cell = CELL;
    private int gridX;
    private int gridY;
    private int gridW;
    private int gridH;
    private int previewY;
    private int hexY;
    private int buttonY;

    /** Typed hex input; pre-filled with the current colour. */
    private TextFieldWidget hexField;
    /** Set when the last Enter / 完成 saw something that is not {@code #RRGGBB}. */
    private boolean hexInvalid;

    ColorPickerScreen(final Screen parentScreen, final String label, final int initialRgb,
                      final IntConsumer onDone) {
        super(ParameterScreen.tr("color.title", label));
        this.parentScreen = parentScreen;
        this.onDone = onDone;
        this.selected = initialRgb & 0xFFFFFF;
    }

    @Override
    protected void init() {
        // Shrink first on narrow / short windows (the grid must stay inside the screen), then centre.
        final int byWidth = (this.width - 2 * (PAD + 12) - (COLS - 1) * CELL_GAP) / COLS;
        // 160 = title/hint area + preview bar + panel padding + hex row + button row + margins.
        final int byHeight = (this.height - 160 - (ROWS - 1) * CELL_GAP) / ROWS;
        this.cell = Math.max(10, Math.min(CELL, Math.min(byWidth, byHeight)));
        this.gridW = COLS * this.cell + (COLS - 1) * CELL_GAP;
        this.gridH = ROWS * this.cell + (ROWS - 1) * CELL_GAP;
        this.gridX = (this.width - this.gridW) / 2;

        final int blockH = PREVIEW_H + 10 + 2 * PAD + this.gridH + 8 + HEX_H + 14 + BTN_H;
        final int top = Math.max(CONTENT_TOP, (this.height - blockH) / 2);
        this.previewY = top;
        this.gridY = this.previewY + PREVIEW_H + 10 + PAD;
        this.hexY = this.gridY + this.gridH + PAD + 8;
        this.buttonY = this.hexY + HEX_H + 14;

        // ---- hex field: same width as the grid, label in front of it ----
        final int labelW = this.textRenderer.getWidth(ParameterScreen.tr("color.hex").getString()) + 8;
        this.hexField = new TextFieldWidget(this.textRenderer, this.gridX + labelW, this.hexY,
                this.gridW - labelW, HEX_H, ParameterScreen.tr("color.hex"));
        this.hexField.setMaxLength(7);
        this.hexField.setTextPredicate(text -> text.matches("#?[0-9a-fA-F]{0,6}"));
        this.hexField.setText(HeadlightConfig.formatColor(this.selected));
        this.hexField.setChangedListener(text -> {
            final Integer rgb = parseHex(text);
            if (rgb != null) {
                // Live preview while the typed value is valid; nothing is applied on its own.
                this.selected = rgb.intValue();
                this.hexInvalid = false;
            }
        });
        addDrawableChild(this.hexField);

        final int bw = Math.max(64, Math.min(96, (this.gridW - GAP) / 2));
        final int bx = (this.width - (bw * 2 + GAP)) / 2;
        addButton(bx, this.buttonY, bw, BTN_H, ParameterScreen.tr("btn.done"), button -> complete());
        addButton(bx + bw + GAP, this.buttonY, bw, BTN_H, ParameterScreen.tr("btn.cancel"),
                button -> this.close());
    }

    /**
     * Applies what is in the hex field. {@code null} when the text is not {@code #RRGGBB} - the
     * caller then reports it in red instead of applying anything.
     */
    private boolean applyHex() {
        final Integer rgb = this.hexField == null ? null : parseHex(this.hexField.getText());
        if (rgb == null) {
            this.hexInvalid = true;
            return false;
        }
        this.selected = rgb.intValue();
        this.hexInvalid = false;
        return true;
    }

    /** {@code #RRGGBB} / {@code RRGGBB} (case-insensitive, {@code #} optional) or null. */
    static Integer parseHex(final String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(s, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void complete() {
        // A typed-but-invalid value blocks 完成: applying the last valid colour behind the player's
        // back would look like the text was accepted.
        if (!applyHex()) {
            return;
        }
        if (this.onDone != null) {
            this.onDone.accept(this.selected & 0xFFFFFF);
        }
        this.close();
    }

    /** Swatch under the cursor, or -1. */
    private int colorAt(final double mouseX, final double mouseY) {
        for (int i = 0; i < PALETTE.length; i++) {
            final int x = this.gridX + (i % COLS) * (this.cell + CELL_GAP);
            final int y = this.gridY + (i / COLS) * (this.cell + CELL_GAP);
            if (mouseX >= x && mouseX < x + this.cell && mouseY >= y && mouseY < y + this.cell) {
                return PALETTE[i] & 0xFFFFFF;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button == 0) {
            final int hit = colorAt(mouseX, mouseY);
            if (hit >= 0) {
                this.selected = hit;
                this.hexInvalid = false;
                if (this.hexField != null) {
                    // setText fires the changed listener, which re-selects the same value.
                    this.hexField.setText(HeadlightConfig.formatColor(hit));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (this.hexField != null && this.hexField.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            if (applyHex() && this.hexField != null) {
                this.hexField.setText(HeadlightConfig.formatColor(this.selected));
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Centre a text; the 1.18/1.19 build gets the {@code MatrixStack} body from the generator. */
    private void drawCentered(final DrawContext context, final Text text, final int cx,
                              final int y, final int color) {
        context.drawCenteredTextWithShadow(this.textRenderer, text, cx, y, color);
    }

    /** Perceived brightness, so the hex text stays readable on top of its own colour. */
    private static int luminance(final int rgb) {
        return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

        drawCentered(context, this.title, this.width / 2, 20, 0xFFFFFF);
        drawCentered(context, ParameterScreen.tr("color.hint"), this.width / 2, 34, 0xB0B0B0);

        final int rgb = this.selected & 0xFFFFFF;

        // ---- preview bar: the colour being edited, with the hex it will be stored as ----
        context.fill(this.gridX - 1, this.previewY - 1, this.gridX + this.gridW + 1,
                this.previewY + PREVIEW_H + 1, FRAME);
        context.fill(this.gridX, this.previewY, this.gridX + this.gridW, this.previewY + PREVIEW_H,
                0xFF000000 | rgb);
        drawCentered(context, Text.literal(HeadlightConfig.formatColor(rgb)),
                this.gridX + this.gridW / 2, this.previewY + 2,
                luminance(rgb) > 140 ? 0xFF101010 : 0xFFFFFFFF);

        // ---- dark panel behind the grid, 1px light border, like MTR's selector ----
        final int px = this.gridX - PAD;
        final int py = this.gridY - PAD;
        final int pr = this.gridX + this.gridW + PAD;
        final int pb = this.gridY + this.gridH + PAD;
        context.fill(px, py, pr, pb, 0xC0101010);
        context.fill(px, py, pr, py + 1, 0xFF5A5A5A);
        context.fill(px, pb - 1, pr, pb, 0xFF5A5A5A);
        context.fill(px, py, px + 1, pb, 0xFF5A5A5A);
        context.fill(pr - 1, py, pr, pb, 0xFF5A5A5A);

        // ---- swatches: black base frame (2px), then white/grey/idle ring, then the colour ----
        for (int i = 0; i < PALETTE.length; i++) {
            final int swatch = PALETTE[i] & 0xFFFFFF;
            final int x = this.gridX + (i % COLS) * (this.cell + CELL_GAP);
            final int y = this.gridY + (i / COLS) * (this.cell + CELL_GAP);
            final boolean isSelected = swatch == rgb;
            final boolean hovered = mouseX >= x && mouseX < x + this.cell
                    && mouseY >= y && mouseY < y + this.cell;
            final int ring = isSelected ? FRAME_SELECTED : (hovered ? FRAME_HOVER : FRAME_IDLE);
            context.fill(x - 2, y - 2, x + this.cell + 2, y + this.cell + 2, FRAME);
            context.fill(x - 1, y - 1, x + this.cell + 1, y + this.cell + 1, ring);
            context.fill(x, y, x + this.cell, y + this.cell, 0xFF000000 | swatch);
        }

        // ---- hex row label + validation message ----
        context.drawTextWithShadow(this.textRenderer, ParameterScreen.tr("color.hex"),
                this.gridX, this.hexY + (HEX_H - 8) / 2, 0xFFFFFF);
        if (this.hexInvalid) {
            drawCentered(context, ParameterScreen.tr("color.hexInvalid"),
                    this.gridX + this.gridW / 2, this.hexY + HEX_H + 2, 0xFF5555);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC and 取消 both land here: the colour is only written by {@link #complete()}. */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parentScreen);
        }
    }

    /**
     * Same tooltip contract as {@code ParameterScreen#addButton}: these buttons carry no tooltip.
     * That is safe on 1.20.1 / 1.19.4 ({@code ClickableWidget.applyTooltip()} null-checks the
     * field), while the 1.19.2/1.18.2 builds must pass {@code ButtonWidget.EMPTY} instead of a null
     * supplier - their {@code renderTooltip()} dereferences it on every hover (NPE).
     */
    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        return this.addDrawableChild(ButtonWidget.builder(label, action).dimensions(x, y, w, h).build());
    }
}
