package dev.js.mtrheadlight;

import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import dev.js.mtrheadlight.clientgui.Widgets;

import net.minecraft.client.util.math.MatrixStack;
import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import dev.js.mtrheadlight.clientgui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 光束编辑的第 3 层：<b>一束光束</b>。
 *
 * <p>沿用配置行页的两格 + ↺ 列体系（{@link ParameterScreen}）：左格是参数名 / 整行按钮，
 * 右格是滑块或色块，最右边一个 {@value #RESET_W}px 的 ↺。列宽还是
 * {@code max(两格最小宽, 最长标签 + 两个字)}，没有任何硬编码坐标。
 *
 * <h2>字段与范围</h2>
 * <pre>
 *   类型      车头 / 车尾        点一下换另一档（整行一个按钮）
 *   颜色      #RRGGBB           复用 {@link ColorPickerScreen}（色块网格 + 手输色号）
 *   位置 XYZ   -16 .. 16         步长 0.05，模型空间（格）
 *   旋转 XYZ   -180 .. 180       步长 1，度（偏航 / 俯仰 / 横滚）
 *   长度       1 .. 64           步长 0.5，格
 *   宽度       0.5 .. 8          步长 0.1，格（光锥直径）
 *   透明度     0.02 .. 0.5       步长 0.02（渲染层的光束峰值 alpha）
 * </pre>
 *
 * <h2>↺ 恢复的是「自动识别值」</h2>
 * 每一行末尾的 ↺ 只重置这一行的字段，取的是 {@link BeamSession#base(int) 该束的自动识别底稿} -
 * 自动识别出的位置 / 长度 / 宽度 / 透明度，或该类型的默认色（自动识别没有颜色）。玩家新增的束没有
 * 自动识别对应项，↺ 回到它被创建时的数值快照。<b>任何情况下都不会归零。</b>
 *
 * <p>位置和旋转是一个向量：数据层里 {@code pos} / {@code rot} 只能整组继承（半继承的向量没有意义），
 * 所以 ↺ 其中任一轴会把三个轴一起恢复成底稿。底部提示行写明了这一点。
 *
 * <h2>即时生效</h2>
 * 拖动滑块 / 取色后立刻 {@link BeamSession#save()}（写运行时层，世界里当场预览），这里不落盘；
 * 落盘只发生在光束列表页的「导出 json」。
 */
final class BeamEditScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int ROW_H = 20;
    private static final int PITCH = 25;
    private static final int GAP = 8;
    private static final int MIN_COLUMN = 176;
    private static final int MIN_CELL = 84;
    private static final int SWATCH_SIDE = 12;
    private static final int RESET_W = 20;
    private static final int TITLE_Y = 22;
    private static final int HINT_Y = 36;
    private static final int CONTENT_TOP = 54;
    /** 底栏两行：整束 ↺，返回光束列表。 */
    private static final int FOOTER_H = 70;
    private static final int FOOTER_ROW_GAP = 6;
    private static final int LIST_BOTTOM_PAD = 20;

    // --- ranges -------------------------------------------------------------------------------
    private static final double POS_MIN = -16.0D;
    private static final double POS_MAX = 16.0D;
    private static final double POS_STEP = 0.05D;
    private static final double ROT_MIN = -180.0D;
    private static final double ROT_MAX = 180.0D;
    private static final double ROT_STEP = 1.0D;
    private static final double LEN_MIN = 1.0D;
    private static final double LEN_MAX = 64.0D;
    private static final double LEN_STEP = 0.5D;
    private static final double WID_MIN = 0.5D;
    private static final double WID_MAX = 8.0D;
    private static final double WID_STEP = 0.1D;
    private static final double ALP_MIN = 0.02D;
    private static final double ALP_MAX = 0.5D;
    private static final double ALP_STEP = 0.02D;

    private static final int KIND_SLIDER = 0;
    private static final int KIND_COLOR = 1;
    private static final int KIND_CYCLE = 2;

    private final Screen parent;
    private final Screen root;
    private final BeamSession session;
    /** 这一页编的是 {@link BeamSession#work} 的第几束。 */
    private final int index;

    private final List<Row> rows = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Swatch> swatches = new ArrayList<>();

    private int colX;
    private int colW;
    private int leftW;
    private int rightW;
    private int listTop;
    private int visibleRows = 1;
    private int scrollRow;

    BeamEditScreen(final Screen parent, final Screen root, final BeamSession session, final int index) {
        super(tr("beam.title.edit", Integer.valueOf(index + 1),
                session == null ? "?" : session.vehicleId));
        this.parent = parent;
        this.root = root == null ? parent : root;
        this.session = session;
        this.index = index;
    }

    static MutableText tr(final String key, final Object... args) {
        return new TranslatableText(key, args);
    }

    // ---------------------------------------------------------------------------------------------
    // Rows table
    // ---------------------------------------------------------------------------------------------

    private void buildRows() {
        this.rows.clear();
        this.rows.add(Row.cycle(BeamSession.Field.TYPE));
        this.rows.add(Row.color());
        this.rows.add(Row.slider(BeamSession.Field.POS_X, "beam.axis.posX", POS_MIN, POS_MAX, POS_STEP, "%.2f"));
        this.rows.add(Row.slider(BeamSession.Field.POS_Y, "beam.axis.posY", POS_MIN, POS_MAX, POS_STEP, "%.2f"));
        this.rows.add(Row.slider(BeamSession.Field.POS_Z, "beam.axis.posZ", POS_MIN, POS_MAX, POS_STEP, "%.2f"));
        this.rows.add(Row.slider(BeamSession.Field.ROT_X, "beam.axis.rotX", ROT_MIN, ROT_MAX, ROT_STEP, "%.0f"));
        this.rows.add(Row.slider(BeamSession.Field.ROT_Y, "beam.axis.rotY", ROT_MIN, ROT_MAX, ROT_STEP, "%.0f"));
        this.rows.add(Row.slider(BeamSession.Field.ROT_Z, "beam.axis.rotZ", ROT_MIN, ROT_MAX, ROT_STEP, "%.0f"));
        this.rows.add(Row.slider(BeamSession.Field.LENGTH, "beam.axis.length", LEN_MIN, LEN_MAX, LEN_STEP, "%.1f"));
        this.rows.add(Row.slider(BeamSession.Field.WIDTH, "beam.axis.width", WID_MIN, WID_MAX, WID_STEP, "%.2f"));
        this.rows.add(Row.slider(BeamSession.Field.ALPHA, "beam.axis.alpha", ALP_MIN, ALP_MAX, ALP_STEP, "%.2f"));
    }

    // ---------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------

    private int rightX() {
        return this.colX + this.leftW + GAP;
    }

    private int resetX() {
        return this.colX + this.colW - RESET_W;
    }

    private int viewBottom() {
        return this.height - FOOTER_H - LIST_BOTTOM_PAD;
    }

    private int textWidth(final Text text) {
        return this.textRenderer.getWidth(text.getString());
    }

    @Override
    protected void init() {
        this.labels.clear();
        this.swatches.clear();
        buildRows();

        int cellNeed = Math.max(MIN_CELL, this.textRenderer.getWidth("#FFFFFF") + SWATCH_SIDE + 16);
        for (final Row row : this.rows) {
            if (row.kind == KIND_CYCLE) {
                cellNeed = Math.max(cellNeed, textWidth(typeLabel()) + 14);
            } else if (row.kind == KIND_COLOR) {
                cellNeed = Math.max(cellNeed, textWidth(tr("beam.axis.color")) + 14);
            } else {
                cellNeed = Math.max(cellNeed, textWidth(tr(row.labelKey)) + 14);
                cellNeed = Math.max(cellNeed, textWidth(new LiteralText(format(row, value(row.field)))) + 14);
            }
        }
        final int maxCol = Math.max(MIN_COLUMN, this.width - 24);
        this.colW = Math.min(maxCol, Math.max(MIN_COLUMN, cellNeed * 2 + GAP + RESET_W + GAP));
        final int cellsW = this.colW - RESET_W - GAP;
        this.leftW = (cellsW - GAP) / 2;
        this.rightW = cellsW - GAP - this.leftW;
        this.colX = this.width / 2 - this.colW / 2;

        this.visibleRows = Math.max(1, (viewBottom() - CONTENT_TOP + (PITCH - ROW_H)) / PITCH);
        final int maxScroll = Math.max(0, this.rows.size() - this.visibleRows);
        this.scrollRow = Math.max(0, Math.min(this.scrollRow, maxScroll));
        final int listH = Math.min(this.visibleRows, this.rows.size()) * PITCH - (PITCH - ROW_H);
        this.listTop = maxScroll == 0
                ? CONTENT_TOP + Math.max(0, (viewBottom() - CONTENT_TOP - listH) / 2)
                : CONTENT_TOP;

        final int last = Math.min(this.rows.size(), this.scrollRow + this.visibleRows);
        for (int i = this.scrollRow; i < last; i++) {
            buildRow(this.rows.get(i), this.listTop + (i - this.scrollRow) * PITCH);
        }
        buildFooter();
    }

    private void buildRow(final Row row, final int y) {
        switch (row.kind) {
            case KIND_CYCLE:
                addButton(this.colX, y, this.colW - RESET_W - GAP, ROW_H, typeLabel(), b -> cycleType());
                addReset(row.field, y);
                break;
            case KIND_COLOR:
                this.labels.add(new Label(this.colX, y, this.leftW, ROW_H, () -> tr("beam.axis.color")));
                addButton(this.rightX(), y, this.rightW, ROW_H, LiteralText.EMPTY, b -> openPicker());
                this.swatches.add(new Swatch(this.rightX(), y, this.rightW, ROW_H, color()));
                addReset(row.field, y);
                break;
            default:
                this.labels.add(new Label(this.colX, y, this.leftW, ROW_H, () -> tr(row.labelKey)));
                addDrawableChild(new FieldSlider(this.rightX(), y, this.rightW, ROW_H, row));
                addReset(row.field, y);
                break;
        }
    }

    /** 行末的 ↺：只重置这一行（位置 / 旋转是整组向量，见类注释）。 */
    private void addReset(final BeamSession.Field field, final int y) {
        addButton(resetX(), y, RESET_W, ROW_H, tr("btn.resetOne"), b -> {
            this.session.resetField(this.index, field);
            reinit();
        });
    }

    private void buildFooter() {
        final int top = this.height - FOOTER_H;
        final int cell2 = (this.colW - GAP) / 2;
        final int x2 = this.width / 2 - (cell2 * 2 + GAP) / 2;
        addButton(x2, top, cell2, ROW_H, tr("btn.beamResetBeam"), b -> {
            this.session.resetAll(this.index);
            reinit();
        });
        addButton(x2 + cell2 + GAP, top, cell2, ROW_H, tr("btn.backBeams"), b -> close());
    }

    // ---------------------------------------------------------------------------------------------
    // Values
    // ---------------------------------------------------------------------------------------------

    private Beam beam() {
        return this.session == null ? null : this.session.beam(this.index);
    }

    private int color() {
        final Beam beam = beam();
        return beam == null || beam.color == null
                ? BeamSession.defaultColor("head") : beam.color.intValue() & 0xFFFFFF;
    }

    private double value(final BeamSession.Field field) {
        final Beam beam = beam();
        if (beam == null) {
            return 0.0D;
        }
        switch (field) {
            case POS_X:
                return beam.pos == null ? 0.0D : beam.pos[0];
            case POS_Y:
                return beam.pos == null ? 0.0D : beam.pos[1];
            case POS_Z:
                return beam.pos == null ? 0.0D : beam.pos[2];
            case ROT_X:
                return beam.rot == null ? 0.0D : beam.rot[0];
            case ROT_Y:
                return beam.rot == null ? 0.0D : beam.rot[1];
            case ROT_Z:
                return beam.rot == null ? 0.0D : beam.rot[2];
            case LENGTH:
                return beam.length == null ? BeamSession.defaultLength(BeamSession.typeOf(beam))
                        : beam.length.doubleValue();
            case WIDTH:
                return beam.width == null ? BeamSession.defaultWidth() : beam.width.doubleValue();
            case ALPHA:
                return beam.alpha == null ? BeamSession.defaultAlpha() : beam.alpha.doubleValue();
            default:
                return 0.0D;
        }
    }

    private void write(final BeamSession.Field field, final double raw) {
        final Beam beam = beam();
        if (beam == null) {
            return;
        }
        final double v = raw;
        switch (field) {
            case POS_X:
            case POS_Y:
            case POS_Z:
                if (beam.pos == null) {
                    beam.pos = new double[]{0.0D, 0.0D, 0.0D};
                }
                beam.pos[axis(field, BeamSession.Field.POS_X)] = v;
                break;
            case ROT_X:
            case ROT_Y:
            case ROT_Z:
                if (beam.rot == null) {
                    beam.rot = new double[]{0.0D, 0.0D, 0.0D};
                }
                beam.rot[axis(field, BeamSession.Field.ROT_X)] = v;
                break;
            case LENGTH:
                beam.length = Double.valueOf(v);
                break;
            case WIDTH:
                beam.width = Double.valueOf(v);
                break;
            case ALPHA:
                beam.alpha = Double.valueOf(v);
                break;
            default:
                return;
        }
        this.session.save();
    }

    /** POS_X/-Y/-Z 与 ROT_X/-Y/-Z 的枚举顺序就是 0/1/2。 */
    private static int axis(final BeamSession.Field field, final BeamSession.Field first) {
        return field.ordinal() - first.ordinal();
    }

    private String format(final Row row, final double value) {
        return String.format(Locale.ROOT, row.pattern, Double.valueOf(value));
    }

    private MutableText typeLabel() {
        final Beam beam = beam();
        return tr("beam.field.type", BeamSession.typeName(BeamSession.typeOf(beam)));
    }

    private void cycleType() {
        final Beam beam = beam();
        if (beam == null) {
            return;
        }
        // 颜色保持不动：玩家把头灯改成尾灯时往往就是要那个颜色，换色有它自己的色块。
        beam.type = BeamSession.isHead(beam) ? "tail" : "head";
        this.session.save();
        reinit();
    }

    private void openPicker() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new ColorPickerScreen(this, tr("beam.axis.color").getString(), color(),
                rgb -> {
                    final Beam beam = beam();
                    if (beam != null) {
                        beam.color = Integer.valueOf(rgb & 0xFFFFFF);
                        this.session.save();
                    }
                }));
    }

    private void reinit() {
        this.init(this.client, this.width, this.height); // 1.18.2 没有 clearAndInit()：init 自己会 clearChildren
    }

    // ---------------------------------------------------------------------------------------------
    // Render / input
    // ---------------------------------------------------------------------------------------------

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        final DrawContext context = new DrawContext(matrices);
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(matrices, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
        final Beam beam = beam();
        final String status = beam == null ? "?" : this.textRenderer.trimToWidth(
                BeamSession.typeName(BeamSession.typeOf(beam)) + " · " + BeamSession.hex(beam.color)
                        + String.format(Locale.ROOT, " · 长 %.1f 宽 %.2f 透明 %.2f",
                        Double.valueOf(beam.length == null ? 0.0D : beam.length.doubleValue()),
                        Double.valueOf(beam.width == null ? 0.0D : beam.width.doubleValue()),
                        Double.valueOf(beam.alpha == null ? 0.0D : beam.alpha.doubleValue())),
                Math.max(80, this.width - 32));
        context.drawCenteredTextWithShadow(this.textRenderer, new LiteralText(status),
                this.width / 2, HINT_Y, 0xB0B0B0);

        for (final Label label : this.labels) {
            context.drawCenteredTextWithShadow(this.textRenderer, label.text.get(),
                    label.x + label.w / 2, label.y + (label.h - 8) / 2, 0xFFFFFF);
        }
        for (final Swatch swatch : this.swatches) {
            final int side = Math.max(8, Math.min(SWATCH_SIDE, swatch.h - 6));
            final int x = swatch.x + 6;
            final int y = swatch.y + (swatch.h - side) / 2;
            context.fill(x - 1, y - 1, x + side + 1, y + side + 1, 0xFF000000);
            context.fill(x, y, x + side, y + side, 0xFF000000 | (swatch.rgb & 0xFFFFFF));
            final int textRight = swatch.x + swatch.w - 6;
            context.drawCenteredTextWithShadow(this.textRenderer,
                    new LiteralText(HeadlightConfig.formatColor(swatch.rgb)),
                    (x + side + textRight) / 2, swatch.y + (swatch.h - 8) / 2, 0xFFE0E0E0);
        }

        if (this.rows.size() > this.visibleRows) {
            drawScrollBar(context);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("config.scrollHint", Integer.valueOf(this.scrollRow + 1),
                            Integer.valueOf(this.rows.size())),
                    this.width / 2, this.height - FOOTER_H - 14, 0x909090);
        }
        context.drawCenteredTextWithShadow(this.textRenderer,
                new LiteralText(this.textRenderer.trimToWidth(tr("beam.hint.vector").getString(),
                        Math.max(80, this.width - 32))),
                this.width / 2, this.height - 14, 0x909090);
    }

    private void drawScrollBar(final DrawContext context) {
        final int top = CONTENT_TOP;
        final int bottom = this.listTop + this.visibleRows * PITCH - (PITCH - ROW_H);
        final int x = this.colX + this.colW + 4;
        context.fill(x, top, x + 1, Math.max(top, bottom), 0x60FFFFFF);
        final int trackH = Math.max(1, bottom - top);
        final int thumbH = Math.max(12, trackH * this.visibleRows / this.rows.size());
        final int span = Math.max(1, this.rows.size() - this.visibleRows);
        final int thumbY = top + (trackH - thumbH) * this.scrollRow / span;
        context.fill(x - 1, thumbY, x + 2, thumbY + thumbH, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC / 返回：回光束列表（逐层返回的第 3 层，不会一次跳回光源分区）。 */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (this.rows.size() > this.visibleRows && amount != 0.0D) {
            this.scrollRow -= (int) Math.signum(amount);
            reinit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        final int page = Math.max(1, this.visibleRows - 1);
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            return scrollBy(page);
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            return scrollBy(-page);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean scrollBy(final int delta) {
        if (this.rows.size() <= this.visibleRows) {
            return false;
        }
        this.scrollRow += delta;
        reinit();
        return true;
    }

    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        return this.addDrawableChild(Widgets.button(label, action).dimensions(x, y, w, h).build());
    }

    // ---------------------------------------------------------------------------------------------
    // Small value types
    // ---------------------------------------------------------------------------------------------

    /** One line: a slider, the colour swatch, or the type cycle. Every kind carries the ↺ field. */
    private static final class Row {
        final int kind;
        final BeamSession.Field field;
        final String labelKey;
        final double min;
        final double max;
        final double step;
        final String pattern;

        private Row(final int kind, final BeamSession.Field field, final String labelKey,
                    final double min, final double max, final double step, final String pattern) {
            this.kind = kind;
            this.field = field;
            this.labelKey = labelKey;
            this.min = min;
            this.max = max;
            this.step = step;
            this.pattern = pattern;
        }

        static Row slider(final BeamSession.Field field, final String labelKey,
                          final double min, final double max, final double step,
                          final String pattern) {
            return new Row(KIND_SLIDER, field, labelKey, min, max, step, pattern);
        }

        static Row color() {
            return new Row(KIND_COLOR, BeamSession.Field.COLOR, "beam.axis.color",
                    0.0D, 0.0D, 0.0D, "%.0f");
        }

        static Row cycle(final BeamSession.Field field) {
            return new Row(KIND_CYCLE, field, "beam.field.type", 0.0D, 0.0D, 0.0D, "%.0f");
        }
    }

    private static final class Label {
        final int x;
        final int y;
        final int w;
        final int h;
        final java.util.function.Supplier<Text> text;

        Label(final int x, final int y, final int w, final int h,
              final java.util.function.Supplier<Text> text) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
        }
    }

    private static final class Swatch {
        final int x;
        final int y;
        final int w;
        final int h;
        final int rgb;

        Swatch(final int x, final int y, final int w, final int h, final int rgb) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.rgb = rgb;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Slider
    // ---------------------------------------------------------------------------------------------

    private static double normalize(final double value, final double min, final double max) {
        final double span = max - min;
        if (span <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (value - min) / span));
    }

    private static double snap(final double value, final double min, final double max, final double step) {
        final double clamped = Math.max(min, Math.min(max, value));
        if (step <= 0.0D) {
            return clamped;
        }
        final double stepped = min + Math.round((clamped - min) / step) * step;
        return Math.max(min, Math.min(max, stepped));
    }

    /** 写一个字段的滑块；值来自 {@link #value}, 写回走 {@link #write}（立刻 put 运行时层）。 */
    private final class FieldSlider extends SliderWidget {
        private final Row row;
        private boolean ready;

        FieldSlider(final int x, final int y, final int w, final int h, final Row row) {
            super(x, y, w, h, LiteralText.EMPTY,
                    normalize(BeamEditScreen.this.value(row.field), row.min, row.max));
            this.row = row;
            this.ready = true;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (!this.ready) {
                return;
            }
            setMessage(new LiteralText(String.format(Locale.ROOT, this.row.pattern,
                    Double.valueOf(actual()))));
        }

        @Override
        protected void applyValue() {
            if (!this.ready) {
                return;
            }
            BeamEditScreen.this.write(this.row.field, actual());
        }

        private double actual() {
            return snap(this.row.min + (this.row.max - this.row.min) * this.value,
                    this.row.min, this.row.max, this.row.step);
        }
    }
}
