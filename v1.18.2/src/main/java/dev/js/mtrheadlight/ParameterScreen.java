package dev.js.mtrheadlight;

import mtr.data.Siding;
import net.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.systems.RenderSystem;
import static net.minecraft.client.gui.DrawableHelper.*;
import net.minecraft.client.util.math.MatrixStack;
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
 * One list of rows, drawn in the same two cells on every line - used once for the global settings
 * ({@link ConfigScreen}) and again for one siding's overrides ({@link SidingDetailScreen}).
 *
 * <h2>Two cells + the ↺ column, always the same width</h2>
 * <ul>
 *   <li><b>Left cell</b> ({@link #colX}, {@link #leftW}): a switch, a colour swatch, or the
 *       parameter name.</li>
 *   <li><b>Right cell</b> ({@link #rightX()}, {@link #rightW}): the slider whose row has no
 *       switch, or the colour swatch.</li>
 *   <li><b>↺ column</b> ({@link #resetX()}, {@link #RESET_W}): one square button at the end of
 *       every parameter row. It is taken out of the block's existing width, so the two cells - and
 *       with them the sliders - are {@code RESET_W + GAP} narrower than they were without it. The
 *       column never overlaps the right cell: {@code rightX() + rightW + GAP == resetX()}.</li>
 *   <li>Sliders are created with exactly the right cell's x and width - there is no centred,
 *       narrower or wider variant any more, which is what made the slider edges and the block
 *       edges disagree.</li>
 * </ul>
 *
 * <h2>The two meanings of a reset</h2>
 * <ul>
 *   <li><b>↺ on a row</b> ({@link ConfigWriteTarget#resetValue(int)} and friends): on the global
 *       screen it writes that one field back to {@link HeadlightConfig#defaults()}; on a siding it
 *       <em>deletes that one override</em>, so the row follows the global value again (its label
 *       gains （跟随）). Neither touches any other row.</li>
 *   <li><b>全部恢复默认</b> (footer, behind {@link ResetConfirmScreen}):
 *       {@link ConfigWriteTarget#resetAll()} - the global screen restores every global field
 *       <em>and</em> clears every siding override; the detail screen deletes just this siding's
 *       overrides.</li>
 * </ul>
 *
 * <h2>Row order (top to bottom)</h2>
 * <ol>
 *   <li>总开关 | 渲染距离</li>
 *   <li>动态光源 | 车灯亮度</li>
 *   <li>光带 | 照射长度</li>
 *   <li>车头颜色</li>
 *   <li>车尾颜色</li>
 *   <li>光束距离 / 车头长度 / 车尾长度 / 光束宽度 / 车头高度 / 车尾高度 - every beam row sits
 *       <b>above</b> the per-siding entry, so the parameter block ends with the beam settings and
 *       the entry ("按侧线配置…" / "恢复为跟随全局") is the last thing in it.</li>
 *   <li>按侧线配置… (global screen) or 恢复为跟随全局 (detail screen)</li>
 *   <li>立即重载光源 (global screen only)</li>
 * </ol>
 *
 * <h2>Scrolling</h2>
 * All rows are one list, not three pages: twelve rows do not fit a 240px-tall window, so the list
 * scrolls by whole rows (mouse wheel, ↑/↓, PageUp/PageDown). A thin bar on the right of the block
 * shows the position; the status line says "滚轮翻看" while there is more to see. Whole rows only -
 * a half-visible row would put a slider under the footer where clicks no longer reach it.
 *
 * <h2>Cancel / save</h2>
 * Opening the screen takes a {@link HeadlightConfig#toJson() snapshot} of the live config. Every
 * control writes the live config immediately (so the light in the world previews the change) but
 * <b>nothing is written to disk until 完成 / 保存并返回</b>. 取消 / 返回 restore the snapshot and
 * close, so the file keeps exactly what it held before the screen opened - see
 * {@link MtrHeadlightClient#restoreConfig(String)}.
 *
 * <h2>Where the writes go</h2>
 * Every control goes through {@link ConfigWriteTarget}: {@code global(...)} for this screen,
 * {@code siding(id, ...)} for {@link SidingDetailScreen}. The siding target has no code path that
 * can assign a global field, and a null siding is rejected outright instead of silently degrading
 * to "edit the globals".
 */
public abstract class ParameterScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    /** Vanilla button height. */
    private static final int ROW_H = 20;
    /** Row pitch: 20px control + 5px gap (25px keeps 5 rows on a 240px-tall window). */
    private static final int PITCH = 25;
    /** Gap between the two cells, and between the footer buttons. */
    private static final int GAP = 8;
    /** Narrowest column the screen will draw (the window can still force it narrower). */
    private static final int MIN_COLUMN = 176;
    /** Narrowest cell, so a short label cannot squeeze a slider to nothing. */
    private static final int MIN_CELL = 84;
    /** Side of the colour square painted inside a swatch button. */
    private static final int SWATCH_SIDE = 12;
    /** Width of the per-row ↺ button: a square of the row height, so it reads as an icon. */
    private static final int RESET_W = 20;

    /** Title (22) + status line (36) above; content starts below the status line. */
    private static final int CONTENT_TOP = 54;
    /**
     * Footer band: 全部恢复默认 on its own row at {@code height-70..height-50}, 完成/取消 at
     * {@code height-44..height-24}. Two rows instead of three 73px-wide cells - 全部恢复默认 is six
     * CJK glyphs and would be squeezed (or cut) in a third of the block on a narrow window.
     */
    private static final int FOOTER_H = 70;
    /** Gap between the 全部恢复默认 row and the 完成/取消 row (20 + 6 + 20 = 46 = FOOTER_H - 24). */
    private static final int FOOTER_ROW_GAP = 6;
    /** Space below the list: the JSON hint line sits at height-84, the author line at height-14. */
    private static final int LIST_BOTTOM_PAD = 20;

    private static final int ROW_TOGGLE = 0;
    private static final int ROW_VALUE = 1;
    private static final int ROW_COLOR = 2;
    private static final int ROW_ACTION = 3;

    /** The layout line is logged once per session, not once per screen open. */
    private static boolean layoutLogged;

    /** Ranges. The beam values are deliberately separate from the dynamic-light ones. */
    private static final double DIST_MIN = 16.0D;
    private static final double DIST_MAX = 512.0D;
    private static final double DIST_STEP = 8.0D;
    private static final double LUM_MIN = 0.0D;
    private static final double LUM_MAX = 15.0D;
    private static final double LENGTH_MIN = 0.0D;
    private static final double LENGTH_MAX = 32.0D;
    private static final double LENGTH_STEP = 0.5D;
    private static final double BEAM_DIST_MIN = 16.0D;
    private static final double BEAM_DIST_MAX = 384.0D;
    private static final double BEAM_DIST_STEP = 8.0D;
    private static final double BEAM_LEN_MIN = 0.0D;
    private static final double BEAM_LEN_MAX = 48.0D;
    private static final double BEAM_TAIL_MAX = 24.0D;
    private static final double BEAM_LEN_STEP = 1.0D;
    private static final double BEAM_WIDTH_MIN = 1.0D;
    private static final double BEAM_WIDTH_MAX = 8.0D;
    private static final double BEAM_WIDTH_STEP = 0.5D;
    private static final double BEAM_HEIGHT_MIN = 0.0D;
    private static final double BEAM_HEIGHT_MAX = 4.0D;
    private static final double BEAM_HEIGHT_STEP = 0.05D;

    protected final Screen parent;
    /** null = global settings, otherwise this siding's overrides. */
    private final Siding siding;
    /** The single object every control writes through; never null, never a global writer here. */
    private final ConfigWriteTarget target;

    /** Config as it was when this screen opened - 取消 / 返回 hands it back. */
    private String snapshot;
    /** True while a child screen that may save (the siding list) is open. */
    private boolean awaitingChildReturn;
    /** Set for the one {@code init()} caused by our own rebuild, so the snapshot is not refreshed. */
    private boolean internalReinit;

    // ---- geometry, recomputed in init() ----
    private int colX;
    private int colW;
    private int leftW;
    private int rightW;
    private int listTop;
    private int scrollRow;
    private int visibleRows = 1;

    /** Rows of the current screen, in draw order. */
    private final List<Row> rows = new ArrayList<>();
    /** Text painted inside the left cell (no widget) for colour and slider-only rows. */
    private final List<Label> labels = new ArrayList<>();
    /** Squares painted inside the swatch buttons of the visible colour rows. */
    private final List<Swatch> swatches = new ArrayList<>();

    protected ParameterScreen(final Screen parent, final Siding siding) {
        super(siding == null
                ? tr("screen.mtr_headlight.config")
                : tr("detail.title", Long.toString(siding.id), nameOf(siding)));
        this.parent = parent;
        this.siding = siding;
        // Never degrade to the global target: a detail screen without a siding is a bug in the
        // caller, and silently editing the globals is exactly the symptom this class exists to
        // make impossible.
        this.target = siding == null
                ? ConfigWriteTarget.global(MtrHeadlightClient::config)
                : ConfigWriteTarget.siding(MtrHeadlightClient::config, siding.id);
    }

    /** True for {@link ConfigScreen}. */
    protected final boolean isGlobal() {
        return this.siding == null;
    }

    /** Translation lookup; every literal is in assets/mtr_headlight/lang. */
    static MutableText tr(final String key, final Object... args) {
        return new net.minecraft.text.TranslatableText(key, args);
    }

    // ---------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------

    private int rightX() {
        return this.colX + this.leftW + GAP;
    }

    /**
     * x of the per-row ↺ button: flush with the block's right edge, {@link #RESET_W} wide. The right
     * cell ends at {@code resetX() - GAP}, so the two can never overlap however narrow the window is.
     */
    private int resetX() {
        return this.colX + this.colW - RESET_W;
    }

    private int viewBottom() {
        return this.height - FOOTER_H - LIST_BOTTOM_PAD;
    }

    /** How many whole rows fit between the status line and the footer. */
    private int rowsPerPage() {
        return Math.max(1, (viewBottom() - CONTENT_TOP + (PITCH - ROW_H)) / PITCH);
    }

    @Override
    protected void init() {
        if (this.awaitingChildReturn) {
            // Back from the siding list, which may have saved a detail page: what is on disk now
            // is the new "before this screen" state, so 取消 must not undo it.
            this.awaitingChildReturn = false;
            this.snapshot = MtrHeadlightClient.snapshotConfig();
        } else if (this.snapshot == null && !this.internalReinit) {
            this.snapshot = MtrHeadlightClient.snapshotConfig();
        }
        this.internalReinit = false;

        this.labels.clear();
        this.swatches.clear();
        buildRows();

        // ---- horizontal: two equal cells plus the ↺ column, inside the widest thing either cell ----
        // The block keeps the width it had without the new column; RESET_W + GAP (28px) comes out of
        // the two cells, so each cell - and with it the slider - is 14px narrower and the ↺ button
        // never sits on top of a slider.
        final int cellNeed = Math.max(MIN_CELL, measureCellNeed());
        final int maxCol = Math.max(MIN_COLUMN, this.width - 24);
        this.colW = Math.min(maxCol, Math.max(MIN_COLUMN, cellNeed * 2 + GAP));
        final int cellsW = this.colW - RESET_W - GAP;
        this.leftW = (cellsW - GAP) / 2;
        this.rightW = cellsW - GAP - this.leftW;
        this.colX = this.width / 2 - this.colW / 2;

        // ---- vertical: whole rows, centred while everything fits ----
        this.visibleRows = rowsPerPage();
        final int maxScroll = Math.max(0, this.rows.size() - this.visibleRows);
        if (this.scrollRow > maxScroll) {
            this.scrollRow = maxScroll;
        }
        if (this.scrollRow < 0) {
            this.scrollRow = 0;
        }
        final int listH = Math.min(this.visibleRows, this.rows.size()) * PITCH - (PITCH - ROW_H);
        this.listTop = maxScroll == 0
                ? CONTENT_TOP + Math.max(0, (viewBottom() - CONTENT_TOP - listH) / 2)
                : CONTENT_TOP;

        final int last = Math.min(this.rows.size(), this.scrollRow + this.visibleRows);
        for (int i = this.scrollRow; i < last; i++) {
            buildRow(this.rows.get(i), i - this.scrollRow);
        }

        buildFooter();
        logLayoutOnce();
    }

    /**
     * One INFO line per session with the numbers the layout actually used - the thing to quote when
     * someone reports that a control does not line up with its cell.
     */
    private void logLayoutOnce() {
        if (layoutLogged) {
            return;
        }
        layoutLogged = true;
        MtrHeadlightClient.LOGGER.info(
                "[mtr-headlight] config layout {}x{} ({}): column x={} w={}, left cell x={} w={},"
                        + " right cell x={} w={}, reset x={} w={}, rows={} visible={}",
                Integer.valueOf(this.width), Integer.valueOf(this.height),
                isGlobal() ? "global" : "siding", Integer.valueOf(this.colX),
                Integer.valueOf(this.colW), Integer.valueOf(this.colX),
                Integer.valueOf(this.leftW), Integer.valueOf(rightX()),
                Integer.valueOf(this.rightW), Integer.valueOf(resetX()),
                Integer.valueOf(RESET_W), Integer.valueOf(this.rows.size()),
                Integer.valueOf(this.visibleRows));
    }

    /** y of a visible row, given its index inside the visible window. */
    private int rowY(final int visibleIndex) {
        return this.listTop + visibleIndex * PITCH;
    }

    /**
     * Width the wider cell needs: switch labels, colour / parameter labels and the slider text, all
     * measured with the real font (CJK glyphs are 9px, so a guess clips).
     */
    private int measureCellNeed() {
        int need = swatchNeed();
        for (final Row row : this.rows) {
            switch (row.kind) {
                case ROW_TOGGLE:
                    need = Math.max(need, textWidth(toggleLabel(row.toggle)) + 14);
                    need = Math.max(need, textWidth(sliderLabel(row.value, maxOf(row.value))) + 14);
                    break;
                case ROW_VALUE:
                    need = Math.max(need, textWidth(valueLabel(row.value)) + 14);
                    need = Math.max(need, textWidth(Text.of(
                            formatValue(row.value, maxOf(row.value)))) + 14);
                    break;
                case ROW_COLOR:
                    need = Math.max(need, textWidth(colorLabel(row.head)) + 14);
                    break;
                default:
                    need = Math.max(need, textWidth(row.label) + 14);
                    break;
            }
        }
        return need;
    }

    /** Space a colour swatch button needs: the square, a gap, and the {@code #RRGGBB} text. */
    private int swatchNeed() {
        return SWATCH_SIDE + 6 + textWidth(Text.of("#FFFFFF")) + 10;
    }

    private int textWidth(final Text text) {
        return this.textRenderer.getWidth(text.getString());
    }

    // ---------------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------------

    private void buildRows() {
        this.rows.clear();
        this.rows.add(Row.toggle(ConfigWriteTarget.MASTER, ConfigWriteTarget.DISTANCE));
        this.rows.add(Row.toggle(ConfigWriteTarget.DYNAMIC_LIGHT, ConfigWriteTarget.LUMINANCE));
        this.rows.add(Row.toggle(ConfigWriteTarget.LIGHT_BAND, ConfigWriteTarget.HEAD_LENGTH));
        this.rows.add(Row.color(true));
        this.rows.add(Row.color(false));
        this.rows.add(Row.value(ConfigWriteTarget.BEAM_DISTANCE));
        this.rows.add(Row.value(ConfigWriteTarget.BEAM_HEAD_LENGTH));
        this.rows.add(Row.value(ConfigWriteTarget.BEAM_TAIL_LENGTH));
        this.rows.add(Row.value(ConfigWriteTarget.BEAM_WIDTH));
        this.rows.add(Row.value(ConfigWriteTarget.BEAM_HEAD_HEIGHT));
        this.rows.add(Row.value(ConfigWriteTarget.BEAM_TAIL_HEIGHT));
        if (isGlobal()) {
            // The entry is the last control of the parameter area - every beam row is above it.
            this.rows.add(Row.worldAction(tr("btn.sidings"), this::openSidings));
            this.rows.add(Row.action(tr("btn.reload"), LightController::reload));
        } else {
            this.rows.add(Row.action(tr("btn.resetSiding"), () -> {
                this.target.reset();
                reinit();
            }));
        }
    }

    private void buildRow(final Row row, final int visibleIndex) {
        final int y = rowY(visibleIndex);
        switch (row.kind) {
            case ROW_TOGGLE: {
                addButton(this.colX, y, this.leftW, ROW_H, toggleLabel(row.toggle), button -> {
                    this.target.writeToggle(row.toggle, !this.target.readToggle(row.toggle));
                    // A switch that now differs from the global value starts being an override;
                    // getting back to "follow" is the explicit 恢复为跟随全局 row.
                    reinit();
                });
                addDrawableChild(new NumberSlider(rightX(), y, this.rightW, ROW_H, row.value,
                        true, this.target.readValue(row.value)));
                addResetButton(row, y);
                break;
            }
            case ROW_VALUE: {
                this.labels.add(new Label(this.colX, y, this.leftW, ROW_H,
                        () -> valueLabel(row.value)));
                addDrawableChild(new NumberSlider(rightX(), y, this.rightW, ROW_H, row.value,
                        false, this.target.readValue(row.value)));
                addResetButton(row, y);
                break;
            }
            case ROW_COLOR: {
                this.labels.add(new Label(this.colX, y, this.leftW, ROW_H,
                        () -> colorLabel(row.head)));
                addButton(rightX(), y, this.rightW, ROW_H, Text.of(""),
                        button -> openPicker(row.head));
                this.swatches.add(new Swatch(rightX(), y, this.rightW, ROW_H,
                        this.target.readColor(row.head)));
                addResetButton(row, y);
                break;
            }
            default: {
                final boolean worldOk = MinecraftClient.getInstance().world != null;
                final Text label = row.needsWorld && !worldOk
                        ? row.label.copy().append(tr("hint.needWorldShort"))
                        : row.label;
                final ButtonWidget button = addButton(this.colX, y, this.colW, ROW_H, label,
                        b -> row.action.run());
                if (row.needsWorld) {
                    // MTR's siding list is empty outside a save, so the row says so instead of
                    // opening an empty screen.
                    button.active = worldOk;
                }
                break;
            }
        }
    }

    /**
     * The square ↺ at the end of a parameter row. What "default" means depends on the target - see
     * {@link ConfigWriteTarget#resetValue(int)}: a global field goes back to
     * {@link HeadlightConfig#defaults()}, a siding override is deleted so the row follows the global
     * value again. The row is rebuilt afterwards, because the label (（跟随）) and the slider position
     * both change.
     */
    private void addResetButton(final Row row, final int y) {
        addButton(resetX(), y, RESET_W, ROW_H, tr("btn.resetOne"), button -> {
            switch (row.kind) {
                case ROW_TOGGLE:
                    this.target.resetToggle(row.toggle);
                    break;
                case ROW_VALUE:
                    this.target.resetValue(row.value);
                    break;
                case ROW_COLOR:
                    this.target.resetColor(row.head);
                    break;
                default:
                    return;
            }
            reinit();
        });
    }

    private void buildFooter() {
        // 全部恢复默认 gets a full-width row of its own: six CJK glyphs do not survive being
        // squeezed into a third of the block on a narrow window.
        final int resetY = this.height - FOOTER_H;
        addButton(this.colX, resetY, this.colW, ROW_H, tr("btn.resetAll"),
                button -> openResetAllConfirm());

        final int y = resetY + ROW_H + FOOTER_ROW_GAP;
        final int cell = (this.colW - GAP) / 2;
        final int x = this.width / 2 - (cell * 2 + GAP) / 2;
        if (isGlobal()) {
            addButton(x, y, cell, ROW_H, tr("btn.done"), button -> commitAndClose());
            addButton(x + cell + GAP, y, cell, ROW_H, tr("btn.cancel"),
                    button -> cancelAndClose());
        } else {
            addButton(x, y, cell, ROW_H, tr("btn.saveBack"), button -> commitAndClose());
            addButton(x + cell + GAP, y, cell, ROW_H, tr("btn.back"),
                    button -> cancelAndClose());
        }
    }

    /**
     * 全部恢复默认: confirm first (it drops data the player may not be able to see, e.g. the siding
     * overrides while the 按侧线配置 list is closed). The confirmation screen calls
     * {@link ConfigWriteTarget#resetAll()} and hands control back, which re-runs {@code init()} and
     * redraws every row with the restored values.
     */
    private void openResetAllConfirm() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new ResetConfirmScreen(this, tr("reset.confirm.title"),
                tr(isGlobal() ? "reset.confirm.global" : "reset.confirm.siding"),
                () -> {
                    this.target.resetAll();
                    reinit();
                }));
    }

    /** 完成 / 保存并返回: write the config to disk, then leave. */
    private void commitAndClose() {
        this.target.commit();
        closeToParent();
    }

    /** 取消 / 返回: drop every edit made while this screen was open, then leave. */
    private void cancelAndClose() {
        MtrHeadlightClient.restoreConfig(this.snapshot);
        closeToParent();
    }

    private void closeToParent() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private void openSidings() {
        if (this.client == null) {
            return;
        }
        if (MinecraftClient.getInstance().world == null) {
            return;
        }
        this.awaitingChildReturn = true;
        this.client.setScreen(new SidingScreen(this));
    }

    /** Opens the swatch grid; 完成 applies, 取消 / ESC leaves the colour untouched. */
    private void openPicker(final boolean head) {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new ColorPickerScreen(this, colorLabel(head).getString(),
                this.target.readColor(head), rgb -> this.target.writeColor(head, rgb)));
    }

    private void reinit() {
        this.internalReinit = true;
        this.init(this.client, this.width, this.height);
    }

    // ---------------------------------------------------------------------------------------------
    // Labels
    // ---------------------------------------------------------------------------------------------

    private MutableText toggleLabel(final int which) {
        switch (which) {
            case ConfigWriteTarget.MASTER:
                return follow(this.target.readToggle(which)
                        ? tr("opt.master.on") : tr("opt.master.off"), which);
            case ConfigWriteTarget.DYNAMIC_LIGHT:
                return follow(this.target.readToggle(which)
                        ? tr("opt.dynamicLight.on") : tr("opt.dynamicLight.off"), which);
            default:
                return follow(this.target.readToggle(which)
                        ? tr("opt.band.on") : tr("opt.band.off"), which);
        }
    }

    private MutableText follow(final MutableText label, final int which) {
        return this.target.followsToggle(which) ? label.append(tr("hint.followGlobal")) : label;
    }

    /** Name of a slider-only row, with the follow-global suffix while it is inherited. */
    private MutableText valueLabel(final int which) {
        final MutableText label = tr(valueKey(which));
        return this.target.followsValue(which) ? label.append(tr("hint.followGlobal")) : label;
    }

    /** {@code 车头颜色} - the swatch button next to it carries the hex, so the label stays short. */
    private MutableText colorLabel(final boolean head) {
        final MutableText label = tr(head ? "opt.beamHeadColor" : "opt.beamTailColor");
        return this.target.followsColor(head) ? label.append(tr("hint.followGlobal")) : label;
    }

    private MutableText sliderLabel(final int which, final double value) {
        return tr(valueKey(which), formatValue(which, value));
    }

    private static String valueKey(final int which) {
        switch (which) {
            case ConfigWriteTarget.DISTANCE:
                return "opt.distance";
            case ConfigWriteTarget.LUMINANCE:
                return "opt.headLuminance";
            case ConfigWriteTarget.HEAD_LENGTH:
                return "opt.headLength";
            case ConfigWriteTarget.BEAM_DISTANCE:
                return "opt.beamDistance";
            case ConfigWriteTarget.BEAM_HEAD_LENGTH:
                return "opt.beamHeadLength";
            case ConfigWriteTarget.BEAM_TAIL_LENGTH:
                return "opt.beamTailLength";
            case ConfigWriteTarget.BEAM_WIDTH:
                return "opt.beamWidth";
            case ConfigWriteTarget.BEAM_HEAD_HEIGHT:
                return "opt.beamHeadHeight";
            default:
                return "opt.beamTailHeight";
        }
    }

    private static double maxOf(final int which) {
        switch (which) {
            case ConfigWriteTarget.DISTANCE:
                return DIST_MAX;
            case ConfigWriteTarget.LUMINANCE:
                return LUM_MAX;
            case ConfigWriteTarget.HEAD_LENGTH:
                return LENGTH_MAX;
            case ConfigWriteTarget.BEAM_DISTANCE:
                return BEAM_DIST_MAX;
            case ConfigWriteTarget.BEAM_HEAD_LENGTH:
                return BEAM_LEN_MAX;
            case ConfigWriteTarget.BEAM_TAIL_LENGTH:
                return BEAM_TAIL_MAX;
            case ConfigWriteTarget.BEAM_WIDTH:
                return BEAM_WIDTH_MAX;
            default:
                return BEAM_HEIGHT_MAX;
        }
    }

    private static String formatValue(final int which, final double value) {
        switch (which) {
            case ConfigWriteTarget.DISTANCE:
            case ConfigWriteTarget.LUMINANCE:
            case ConfigWriteTarget.BEAM_DISTANCE:
                return String.format(Locale.ROOT, "%.0f", value);
            case ConfigWriteTarget.BEAM_HEAD_HEIGHT:
            case ConfigWriteTarget.BEAM_TAIL_HEIGHT:
                return String.format(Locale.ROOT, "%.2f", value);
            default:
                return String.format(Locale.ROOT, "%.1f", value);
        }
    }

    private static double minOf(final int which) {
        switch (which) {
            case ConfigWriteTarget.DISTANCE:
                return DIST_MIN;
            case ConfigWriteTarget.LUMINANCE:
                return LUM_MIN;
            case ConfigWriteTarget.HEAD_LENGTH:
                return LENGTH_MIN;
            case ConfigWriteTarget.BEAM_DISTANCE:
                return BEAM_DIST_MIN;
            case ConfigWriteTarget.BEAM_HEAD_LENGTH:
            case ConfigWriteTarget.BEAM_TAIL_LENGTH:
                return BEAM_LEN_MIN;
            case ConfigWriteTarget.BEAM_WIDTH:
                return BEAM_WIDTH_MIN;
            default:
                return BEAM_HEIGHT_MIN;
        }
    }

    private static double stepOf(final int which) {
        switch (which) {
            case ConfigWriteTarget.DISTANCE:
                return DIST_STEP;
            case ConfigWriteTarget.LUMINANCE:
                return 1.0D;
            case ConfigWriteTarget.HEAD_LENGTH:
                return LENGTH_STEP;
            case ConfigWriteTarget.BEAM_DISTANCE:
                return BEAM_DIST_STEP;
            case ConfigWriteTarget.BEAM_HEAD_LENGTH:
            case ConfigWriteTarget.BEAM_TAIL_LENGTH:
                return BEAM_LEN_STEP;
            case ConfigWriteTarget.BEAM_WIDTH:
                return BEAM_WIDTH_STEP;
            default:
                return BEAM_HEIGHT_STEP;
        }
    }

    /**
     * Same tooltip contract as the other screens: these buttons carry no tooltip. Safe on 1.20.1
     * ({@code ClickableWidget.applyTooltip()} null-checks the field); the 1.19.2/1.18.2 builds must
     * pass {@code ButtonWidget.EMPTY} instead - never a null supplier.
     */
    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        // 1.19.2 / 1.18.2 renderTooltip() dereferences the supplier unconditionally: a null
        // supplier (or builder default) NPEs on every hover, so EMPTY is passed explicitly.
        return this.addDrawableChild(new ButtonWidget(x, y, w, h, label, action, ButtonWidget.EMPTY));
    }

    // ---------------------------------------------------------------------------------------------
    // Siding helpers (shared with SidingScreen)
    // ---------------------------------------------------------------------------------------------

    /**
     * Siding as {@code #id 名称 · TYPE · 129 格 · 6 节}. MTR does not send the siding-to-depot
     * relation to the client, so type/length/car count are what tells two identically named
     * sidings apart.
     */
    static String describe(final Siding siding) {
        if (siding == null) {
            return "?";
        }
        final StringBuilder out = new StringBuilder();
        try {
            out.append('#').append(siding.id).append(' ').append(nameOf(siding));
        } catch (RuntimeException ignored) {
            return "?";
        }
        final String stats = stats(siding);
        if (!stats.isEmpty()) {
            out.append("  ·  ").append(stats);
        }
        return out.toString();
    }

    /** {@code TYPE · 129 格 · 6 节}; the part of {@link #describe(Siding)} that is not the name. */
    static String stats(final Siding siding) {
        final StringBuilder out = new StringBuilder();
        try {
            out.append(siding.transportMode == null ? "?" : siding.transportMode.name())
                    .append("  ·  ").append(Math.round(siding.railLength)).append(" 格");
            final int cars = SidingInfo.cars(siding);
            if (cars > 0) {
                out.append("  ·  ").append(cars).append(" 节");
            }
        } catch (RuntimeException ignored) {
            // Keep whatever was written before the missing field.
        }
        return out.toString();
    }

    static String nameOf(final Siding siding) {
        try {
            final String name = siding.name;
            return name == null || name.isBlank() ? tr("sidings.unnamed").getString() : name;
        } catch (RuntimeException e) {
            return tr("sidings.unnamed").getString();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------------------------------

    private void drawVanillaBackground(final MatrixStack matrices) {
        RenderSystem.setShaderTexture(0, OPTIONS_BACKGROUND);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        drawTexture(matrices, 0, 0, 0.0F, 0.0F, this.width, this.height, 32, 32);
        fillGradient(matrices, 0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    /** 1.19 / 1.18 have no Text overload of drawCenteredText: centre from the real font width. */
    private void drawCentered(final MatrixStack matrices, final Text text, final int cx,
                              final int y, final int color) {
        final String s = text.getString();
        this.textRenderer.drawWithShadow(matrices, s,
                (float) (cx - this.textRenderer.getWidth(s) / 2), (float) y, color);
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        drawVanillaBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);

        drawCentered(matrices, this.title, this.width / 2, 22, 0xFFFFFF);
        final boolean anyOn = this.target.readToggle(ConfigWriteTarget.MASTER);
        final MutableText subtitle = tr("config.status",
                        onOff(ConfigWriteTarget.MASTER), onOff(ConfigWriteTarget.DYNAMIC_LIGHT),
                        onOff(ConfigWriteTarget.LIGHT_BAND))
                .append(scrollHint());
        drawCentered(matrices, subtitle, this.width / 2, 36, anyOn ? 0x9DFF9D : 0xFF9D9D);

        drawLabels(matrices);
        drawSwatches(matrices);
        drawScrollBar(matrices);

        drawCentered(matrices, tr("hint.json"), this.width / 2, this.height - FOOTER_H - 14, 0x909090);
        drawCentered(matrices, tr("hint.author"), this.width / 2, this.height - 14, 0xB0B0B0);
    }

    private Text onOff(final int which) {
        return tr(this.target.readToggle(which) ? "config.on" : "config.off");
    }

    private Text scrollHint() {
        if (this.rows.size() <= this.visibleRows) {
            return Text.of("");
        }
        return tr("config.scrollHint", Integer.valueOf(this.scrollRow + 1),
                Integer.valueOf(this.rows.size()));
    }

    private void drawLabels(final MatrixStack matrices) {
        for (final Label label : this.labels) {
            drawCentered(matrices, label.text.get(), label.x + label.w / 2,
                    label.y + (label.h - 8) / 2, 0xFFFFFF);
        }
    }

    private void drawSwatches(final MatrixStack matrices) {
        for (final Swatch swatch : this.swatches) {
            final int side = Math.max(8, Math.min(SWATCH_SIDE, swatch.h - 6));
            final int x = swatch.x + 6;
            final int y = swatch.y + (swatch.h - side) / 2;
            // Border first, so a near-black colour still reads as a square on the button.
            fill(matrices, x - 1, y - 1, x + side + 1, y + side + 1, 0xFF000000);
            fill(matrices, x, y, x + side, y + side, 0xFF000000 | (swatch.rgb & 0xFFFFFF));
            final int textRight = swatch.x + swatch.w - 6;
            drawCentered(matrices, Text.of(HeadlightConfig.formatColor(swatch.rgb)),
                    (x + side + textRight) / 2, swatch.y + (swatch.h - 8) / 2, 0xFFE0E0E0);
        }
    }

    /** Thin position bar on the right edge of the block; nothing is drawn when it all fits. */
    private void drawScrollBar(final MatrixStack matrices) {
        if (this.rows.size() <= this.visibleRows) {
            return;
        }
        final int top = CONTENT_TOP;
        final int bottom = this.listTop + this.visibleRows * PITCH - (PITCH - ROW_H);
        final int x = this.colX + this.colW + 4;
        fill(matrices, x, top, x + 1, Math.max(top, bottom), 0x60FFFFFF);
        final int trackH = Math.max(1, bottom - top);
        final int thumbH = Math.max(12, trackH * this.visibleRows / this.rows.size());
        final int span = Math.max(1, this.rows.size() - this.visibleRows);
        final int thumbY = top + (trackH - thumbH) * this.scrollRow / span;
        fill(matrices, x - 1, thumbY, x + 2, thumbY + thumbH, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC = 取消 / 返回: edits made here are rolled back. */
    @Override
    public void close() {
        cancelAndClose();
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (this.rows.size() > this.visibleRows && amount != 0.0D) {
            final int before = this.scrollRow;
            this.scrollRow -= (int) Math.signum(amount);
            reinit();
            if (this.scrollRow != before) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        // Only page keys: ↑/↓ belong to a focused slider (its own fine adjustment).
        final int page = Math.max(1, this.visibleRows - 1);
        switch (keyCode) {
            case GLFW.GLFW_KEY_PAGE_DOWN:
                return scrollBy(page);
            case GLFW.GLFW_KEY_PAGE_UP:
                return scrollBy(-page);
            default:
                return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    private boolean scrollBy(final int delta) {
        if (this.rows.size() <= this.visibleRows) {
            return false;
        }
        this.scrollRow += delta;
        reinit();
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Small value types
    // ---------------------------------------------------------------------------------------------

    /** One line of the list. */
    private static final class Row {
        final int kind;
        final int toggle;
        final int value;
        final boolean head;
        /** True for the per-siding entry: disabled (and annotated) outside a save. */
        final boolean needsWorld;
        final Text label;
        final Runnable action;

        private Row(final int kind, final int toggle, final int value, final boolean head,
                    final boolean needsWorld, final Text label, final Runnable action) {
            this.kind = kind;
            this.toggle = toggle;
            this.value = value;
            this.head = head;
            this.needsWorld = needsWorld;
            this.label = label;
            this.action = action;
        }

        static Row toggle(final int toggle, final int value) {
            return new Row(ROW_TOGGLE, toggle, value, false, false, Text.of(""), null);
        }

        static Row value(final int value) {
            return new Row(ROW_VALUE, -1, value, false, false, Text.of(""), null);
        }

        static Row color(final boolean head) {
            return new Row(ROW_COLOR, -1, -1, head, false, Text.of(""), null);
        }

        static Row action(final Text label, final Runnable action) {
            return new Row(ROW_ACTION, -1, -1, false, false, label, action);
        }

        static Row worldAction(final Text label, final Runnable action) {
            return new Row(ROW_ACTION, -1, -1, false, true, label, action);
        }
    }

    /** Text painted inside a cell (rows without a widget in the left cell). */
    private static final class Label {
        final int x;
        final int y;
        final int w;
        final int h;
        /** Resolved while drawing, so the 跟随 suffix follows a live edit without a rebuild. */
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

    /** Colour square + hex painted inside a swatch button. */
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

    /**
     * A slider that writes one numeric parameter of the current target (global or siding).
     *
     * <p>{@code ready} guards {@link #updateMessage()}: whether or not a version's
     * {@code SliderWidget} constructor calls it, the fields read here are only valid after this
     * class' own constructor body has run.
     */
    private final class NumberSlider extends SliderWidget {
        private final int which;
        /** True on a row whose left cell is the switch: the slider must carry the name. */
        private final boolean withName;
        private final double min;
        private final double max;
        private final double step;
        private boolean ready;

        NumberSlider(final int x, final int y, final int w, final int h, final int which,
                     final boolean withName, final double current) {
            super(x, y, w, h, Text.of(""), normalize(current, minOf(which), maxOf(which)));
            this.which = which;
            this.withName = withName;
            this.min = minOf(which);
            this.max = maxOf(which);
            this.step = stepOf(which);
            this.ready = true;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (!this.ready) {
                return;
            }
            setMessage(this.withName
                    ? sliderLabel(this.which, actual())
                    : Text.of(formatValue(this.which, actual())));
        }

        @Override
        protected void applyValue() {
            if (!this.ready) {
                return;
            }
            target.writeValue(this.which, actual());
        }

        private double actual() {
            return snap(this.min + (this.max - this.min) * this.value, this.min, this.max, this.step);
        }
    }
}
