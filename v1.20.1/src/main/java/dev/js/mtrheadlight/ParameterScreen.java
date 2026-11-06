package dev.js.mtrheadlight;

import mtr.data.Siding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Locale;

/**
 * The six controls (master switch, render distance, head luminance, beam length, rail light, rail
 * glow) on one column - used <b>once</b> for the global settings ({@link ConfigScreen}) and
 * <b>again</b> for one siding's overrides ({@link SidingDetailScreen}). Subclassing instead of
 * copying is what guarantees the two screens can never drift apart in what they offer.
 *
 * <p>When {@code siding == null} every control writes the global field of {@link HeadlightConfig};
 * otherwise it writes that siding's {@link HeadlightConfig.SidingSettings} entry, creating it on
 * the first edit. The label carries a "（跟随全局）" suffix while a control has no override, so the
 * player can see what "恢复为跟随全局" would revert.
 *
 * <h2>Layout rules (why the numbers look like this)</h2>
 * <ul>
 *   <li>One column width for every row, centred on the screen, at least as wide as the widest
 *       label measured with the real font ({@link #textWidth}) - CJK glyphs are 9px, so a guess
 *       clips.</li>
 *   <li>{@link #ROWS} content rows sit centred in the free area between the status line
 *       ({@link #CONTENT_TOP}) and the footer + JSON hint; the block is re-centred after every
 *       widget change instead of leaving a hole.</li>
 *   <li>The row pitch shrinks first (26 → 16px) and the button height with it (20 → 14px), so
 *       seven rows still fit a 240px-tall window without touching the footer.</li>
 *   <li>Footer buttons share the column and are equal width - "立即重载光源" | "完成" on the global
 *       screen, one centred "返回" on the siding screen.</li>
 * </ul>
 */
public abstract class ParameterScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    /** Vanilla button height; only reduced when the window is too short to fit every row. */
    private static final int ROW_H = 20;
    /** Row pitch with a full-height button: 20px button + 6px gap. */
    private static final int ROW_PITCH_MAX = 26;
    /** Narrowest pitch that still leaves a 12px button, i.e. 7 rows in 96px (small GUI scale). */
    private static final int ROW_PITCH_MIN = 14;
    private static final int ROW_H_MIN = 12;
    /** Inner padding of a vanilla button, left + right, when sizing it to its label. */
    private static final int LABEL_PAD = 8;
    private static final int GAP = 8;

    /** master · distance · luminance · beam length · rail light · rail glow · last row. */
    private static final int ROWS = 7;

    /** Title (22) + status line (36) above; content starts below the status line. */
    private static final int CONTENT_TOP = 54;
    /** Footer button: height-44..height-24. */
    private static final int FOOTER_H = 44;
    /** Height the JSON hint needs below the last row (8px gap + 9px text). */
    private static final int HINT_H = 18;

    private static final int SLIDER_DISTANCE = 0;
    private static final int SLIDER_LUMINANCE = 1;
    private static final int SLIDER_LENGTH = 2;
    private static final double DIST_MIN = 16.0D;
    private static final double DIST_MAX = 512.0D;
    private static final double DIST_STEP = 8.0D;
    private static final double LUM_MIN = 0.0D;
    private static final double LUM_MAX = 15.0D;
    private static final double LENGTH_MIN = 0.0D;
    private static final double LENGTH_MAX = 32.0D;
    private static final double LENGTH_STEP = 0.5D;

    /** How long a slider drag may go unsaved before it is written to disk (ms). */
    private static final long SAVE_THROTTLE_MS = 400L;

    protected final Screen parent;
    /** null = global settings, otherwise this siding's overrides. */
    private final Siding siding;

    private long lastSaveMs;
    private boolean dirty;

    /** The "按侧线配置" row, so its disabled state can be explained on hover. */
    private ButtonWidget sidingsButton;
    /** Detail screen only: enabled as soon as one override exists (a slider drag does that too). */
    private ButtonWidget resetButton;
    /** Its rectangle, kept separately: {@code getX()/getWidth()} only exist from 1.20 on. */
    private int sidingsRowX;
    private int sidingsRowY;
    private int sidingsRowW;
    private int sidingsRowH;

    protected ParameterScreen(final Screen parent, final Siding siding) {
        super(siding == null
                ? tr("screen.mtr_headlight.config")
                : tr("detail.title", Long.toString(siding.id), nameOf(siding)));
        this.parent = parent;
        this.siding = siding;
    }

    // ---------------------------------------------------------------------------------------------
    // Config access
    // ---------------------------------------------------------------------------------------------

    /** Translation lookup; every literal is in assets/mtr_headlight/lang. */
    static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    private HeadlightConfig.Effective effective() {
        return MtrHeadlightClient.config().effectiveFor(this.siding == null ? 0L : this.siding.id);
    }

    private boolean hasOverrides() {
        if (this.siding == null) {
            return false;
        }
        final HeadlightConfig.SidingSettings o =
                MtrHeadlightClient.config().overrides(this.siding.id);
        return o != null && !o.isEmpty();
    }

    /** True while this control still inherits the global value (detail screen only). */
    private boolean follows(final int which) {
        if (this.siding == null) {
            return false;
        }
        final HeadlightConfig.SidingSettings o =
                MtrHeadlightClient.config().overrides(this.siding.id);
        if (o == null) {
            return true;
        }
        switch (which) {
            case SLIDER_DISTANCE:
                return o.maxDistance == null;
            case SLIDER_LUMINANCE:
                return o.headLuminance == null;
            default:
                return o.headLength == null;
        }
    }

    private double valueOf(final int which) {
        final HeadlightConfig.Effective eff = effective();
        switch (which) {
            case SLIDER_DISTANCE:
                return eff.maxDistance;
            case SLIDER_LUMINANCE:
                return eff.headLuminance;
            default:
                return eff.headLength;
        }
    }

    private void writeValue(final int which, final double value) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (this.siding == null) {
            switch (which) {
                case SLIDER_DISTANCE:
                    cfg.maxDistance = value;
                    break;
                case SLIDER_LUMINANCE:
                    cfg.headLuminance = (int) Math.round(value);
                    break;
                default:
                    cfg.headLength = value;
                    break;
            }
        } else {
            final HeadlightConfig.SidingSettings o = cfg.settingsFor(this.siding.id);
            switch (which) {
                case SLIDER_DISTANCE:
                    o.maxDistance = Double.valueOf(value);
                    break;
                case SLIDER_LUMINANCE:
                    o.headLuminance = Integer.valueOf((int) Math.round(value));
                    break;
                default:
                    o.headLength = Double.valueOf(value);
                    break;
            }
        }
        touch();
        // The first drag creates the override entry, which is exactly what enables "恢复为跟随全局".
        if (this.resetButton != null) {
            this.resetButton.active = true;
        }
    }

    private void setMaster(final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (this.siding == null) {
            cfg.enabled = on;
        } else {
            cfg.settingsFor(this.siding.id).enabled = Boolean.valueOf(on);
        }
        MtrHeadlightClient.config().save();
    }

    private void setRailLight(final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (this.siding == null) {
            cfg.railLight = on;
        } else {
            cfg.settingsFor(this.siding.id).railLight = Boolean.valueOf(on);
        }
        MtrHeadlightClient.config().save();
    }

    private void setRailGlow(final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (this.siding == null) {
            cfg.railGlow = on;
        } else {
            cfg.settingsFor(this.siding.id).railGlow = Boolean.valueOf(on);
        }
        MtrHeadlightClient.config().save();
    }

    private void resetSiding() {
        if (this.siding != null) {
            MtrHeadlightClient.config().clearSiding(this.siding.id);
            MtrHeadlightClient.config().save();
        }
    }

    /** Marks the config dirty and writes it out at most every {@link #SAVE_THROTTLE_MS}. */
    private void touch() {
        this.dirty = true;
        final long now = System.currentTimeMillis();
        if (now - this.lastSaveMs >= SAVE_THROTTLE_MS) {
            this.lastSaveMs = now;
            MtrHeadlightClient.config().save();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        final HeadlightConfig.Effective eff = effective();
        final int cx = this.width / 2;

        final Text master = masterLabel(eff);
        final Text railLight = railLightLabel(eff);
        final Text railGlow = railGlowLabel(eff);
        final Text lastRow = this.siding == null
                ? tr("btn.sidings")
                : tr("btn.resetSiding");

        // ---- horizontal ----
        // 320px is the comfortable default; grow it (up to the window) if a label needs more.
        final int maxCol = Math.max(200, this.width - 40);
        int needed = Math.max(textWidth(master), Math.max(textWidth(railLight), textWidth(railGlow)));
        needed = Math.max(needed, textWidth(lastRow));
        needed = Math.max(needed, textWidth(sliderLabel(SLIDER_DISTANCE, DIST_MAX, follows(SLIDER_DISTANCE))));
        needed = Math.max(needed, textWidth(sliderLabel(SLIDER_LUMINANCE, LUM_MAX, follows(SLIDER_LUMINANCE))));
        needed = Math.max(needed, textWidth(sliderLabel(SLIDER_LENGTH, LENGTH_MAX, follows(SLIDER_LENGTH))));
        final int colW = Math.min(maxCol, Math.max(320, needed + LABEL_PAD * 2));
        final int left = cx - colW / 2;

        // ---- vertical ----
        final int contentBottom = this.height - FOOTER_H - GAP - HINT_H;
        final int avail = Math.max(ROWS * ROW_H_MIN, contentBottom - CONTENT_TOP);
        final int pitch = Math.max(ROW_PITCH_MIN, Math.min(ROW_PITCH_MAX, avail / ROWS));
        int rowH = Math.min(ROW_H, pitch - 2);
        if (rowH < ROW_H_MIN) {
            rowH = ROW_H_MIN;
        }
        final int blockH = (ROWS - 1) * pitch + rowH;
        // Centre the block in the free area; on a short window free space is 0 and it just fills.
        int y = CONTENT_TOP + Math.max(0, (avail - blockH) / 2);

        addButton(left, y, colW, rowH, master, button -> {
            setMaster(!effective().enabled);
            reinit();
        });
        y += pitch;

        addDrawableChild(new NumberSlider(left, y, colW, rowH, SLIDER_DISTANCE,
                "opt.distance", DIST_MIN, DIST_MAX, DIST_STEP, valueOf(SLIDER_DISTANCE)));
        y += pitch;

        addDrawableChild(new NumberSlider(left, y, colW, rowH, SLIDER_LUMINANCE,
                "opt.headLuminance", LUM_MIN, LUM_MAX, 1.0D, valueOf(SLIDER_LUMINANCE)));
        y += pitch;

        addDrawableChild(new NumberSlider(left, y, colW, rowH, SLIDER_LENGTH,
                "opt.headLength", LENGTH_MIN, LENGTH_MAX, LENGTH_STEP, valueOf(SLIDER_LENGTH)));
        y += pitch;

        addButton(left, y, colW, rowH, railLight, button -> {
            setRailLight(!effective().railLight);
            reinit();
        });
        y += pitch;

        addButton(left, y, colW, rowH, railGlow, button -> {
            setRailGlow(!effective().railGlow);
            reinit();
        });
        y += pitch;

        if (this.siding == null) {
            final boolean inWorld = MinecraftClient.getInstance().world != null;
            this.sidingsRowX = left;
            this.sidingsRowY = y;
            this.sidingsRowW = colW;
            this.sidingsRowH = rowH;
            this.sidingsButton = addButton(left, y, colW, rowH, lastRow, button -> {
                if (this.client != null) {
                    this.client.setScreen(new SidingScreen(this));
                }
            });
            // Not in a save: MTR's siding list is empty and there is nothing to configure. Greyed
            // out rather than hidden, so the row still tells the player where it will appear.
            this.sidingsButton.active = inWorld;
            this.resetButton = null;
        } else {
            this.sidingsButton = null;
            this.resetButton = addButton(left, y, colW, rowH, lastRow, button -> {
                resetSiding();
                reinit();
            });
            this.resetButton.active = hasOverrides();
        }

        // ---- footer: equal-width buttons inside the same column ----
        final int footerY = this.height - FOOTER_H;
        if (this.siding == null) {
            final int cell = (colW - GAP) / 2;
            final int x = cx - (cell * 2 + GAP) / 2;
            addButton(x, footerY, cell, ROW_H, tr("btn.reload"), button -> LightController.reload());
            addButton(x + cell + GAP, footerY, cell, ROW_H, tr("btn.done"), button -> this.close());
        } else {
            addButton(cx - colW / 4, footerY, colW / 2, ROW_H, tr("btn.back"), button -> this.close());
        }
    }

    private int textWidth(final Text text) {
        return this.textRenderer.getWidth(text.getString());
    }

    /**
     * The single place either screen builds a button, so the tooltip contract is stated (and
     * enforced) once.
     *
     * <p><b>Never hand a null tooltip to a raw {@code new ButtonWidget(..., tooltip)} on 1.19.2 /
     * 1.18.2.</b> Their {@code ButtonWidget.renderTooltip()} calls
     * {@code tooltipSupplier.onTooltip(...)} without a null check, so the first mouse-over NPEs the
     * whole screen ("Rendering screen ... because the return value of ... is null"). That is why the
     * 1.19.2 and 1.18.2 builds pass {@code ButtonWidget.EMPTY} - a public, non-null supplier that
     * draws nothing.
     *
     * <p>1.20.1 and 1.19.4 need no such workaround: their raw constructor is protected (builder only)
     * and {@code ClickableWidget.applyTooltip()} starts with {@code if (this.tooltip == null) return;},
     * so a button with the builder default is safe to hover. Handing those a
     * {@code Tooltip.of(Text.empty())} would be non-null but would paint an empty tooltip box on every
     * hover, so "no tooltip" stays "never set one" there.
     *
     * <p>No button tooltip is used for this screen's own hint either - see
     * {@link #drawNeedWorldHint}: it is painted directly, because a vanilla <i>disabled</i> button
     * never shows a tooltip anyway and calling {@code onTooltip(...)} by hand would NPE on 1.19.2.
     */
    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        return this.addDrawableChild(ButtonWidget.builder(label, action).dimensions(x, y, w, h).build());
    }

    private void reinit() {
        this.clearAndInit();
    }

    // ---------------------------------------------------------------------------------------------
    // Labels
    // ---------------------------------------------------------------------------------------------

    private MutableText masterLabel(final HeadlightConfig.Effective eff) {
        final MutableText label = tr(eff.enabled ? "opt.master.on" : "opt.master.off");
        return followsSwitch(0) ? label.append(tr("hint.followGlobal")) : label;
    }

    private MutableText railLightLabel(final HeadlightConfig.Effective eff) {
        final MutableText label = tr(eff.railLight ? "opt.railLight.on" : "opt.railLight.off");
        return followsSwitch(1) ? label.append(tr("hint.followGlobal")) : label;
    }

    private MutableText railGlowLabel(final HeadlightConfig.Effective eff) {
        final MutableText label = tr(eff.railGlow ? "opt.railGlow.on" : "opt.railGlow.off");
        return followsSwitch(2) ? label.append(tr("hint.followGlobal")) : label;
    }

    /** 0 = master, 1 = rail light, 2 = rail glow. True while that switch has no override. */
    private boolean followsSwitch(final int which) {
        if (this.siding == null) {
            return false;
        }
        final HeadlightConfig.SidingSettings o =
                MtrHeadlightClient.config().overrides(this.siding.id);
        if (o == null) {
            return true;
        }
        switch (which) {
            case 0:
                return o.enabled == null;
            case 1:
                return o.railLight == null;
            default:
                return o.railGlow == null;
        }
    }

    private MutableText sliderLabel(final int which, final double value, final boolean follows) {
        final String text;
        switch (which) {
            case SLIDER_DISTANCE:
                text = String.format(Locale.ROOT, "%.0f", value);
                break;
            case SLIDER_LUMINANCE:
                text = String.format(Locale.ROOT, "%.0f", value);
                break;
            default:
                text = String.format(Locale.ROOT, "%.1f", value);
                break;
        }
        final MutableText label = tr(sliderKey(which), text);
        return follows ? label.append(tr("hint.followGlobal")) : label;
    }

    private static String sliderKey(final int which) {
        switch (which) {
            case SLIDER_DISTANCE:
                return "opt.distance";
            case SLIDER_LUMINANCE:
                return "opt.headLuminance";
            default:
                return "opt.headLength";
        }
    }

    /**
     * Siding as {@code #id 名称 · TYPE · 129 格 · 6 节}. Shared with {@link SidingScreen}'s list rows;
     * MTR does not send the siding-to-depot relation to the client, so type/length/car count are
     * what actually tells two identically named sidings apart.
     */
    static String describe(final Siding siding) {
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

    /** Same background as the game's own screens: dirt texture stretched + a dark gradient. */
    private void drawVanillaBackground(final DrawContext context) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        drawVanillaBackground(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 22, 0xFFFFFF);

        final HeadlightConfig.Effective eff = effective();
        final Text subtitle;
        final int subtitleColor;
        if (this.siding == null) {
            subtitle = tr(eff.enabled ? "screen.mtr_headlight.status.on"
                    : "screen.mtr_headlight.status.off");
            subtitleColor = eff.enabled ? 0x9DFF9D : 0xFF9D9D;
        } else {
            // id + name live in the title; this line carries the fields that tell sidings apart.
            subtitle = tr("detail.subtitle", stats(this.siding));
            subtitleColor = eff.enabled ? 0x9DFF9D : 0xFF9D9D;
        }
        context.drawCenteredTextWithShadow(this.textRenderer, subtitle, this.width / 2, 36, subtitleColor);

        // Bottom-anchored above the footer: the block is centred, so this line is never under a
        // button and never on top of the footer (HINT_H reserves exactly this space).
        context.drawCenteredTextWithShadow(this.textRenderer, tr("hint.json"),
                this.width / 2, this.height - FOOTER_H - 14, 0x909090);
        // height-14: below the footer button (which ends at height-24), never on top of it.
        context.drawCenteredTextWithShadow(this.textRenderer, tr("hint.author"),
                this.width / 2, this.height - 14, 0xB0B0B0);

        // A disabled button in vanilla never shows a tooltip, so say why right under the row.
        drawNeedWorldHint(context, mouseX, mouseY);
    }

    /**
     * The "需要进入存档" hint, painted by hand one line under the 按侧线配置 row.
     *
     * <p>Deliberately not a button tooltip: vanilla never shows a tooltip for a <i>disabled</i>
     * button, and on 1.19.2 calling {@code renderTooltip(...)} by hand would dereference the
     * button's (null) supplier and NPE. Everything this needs is null-checked here instead, so a
     * missing button or a row that was never laid out simply skips the hint.
     */
    private void drawNeedWorldHint(final DrawContext context, final int mouseX, final int mouseY) {
        final ButtonWidget button = this.sidingsButton;
        if (button == null || button.active || this.sidingsRowW <= 0 || this.sidingsRowH <= 0) {
            return;
        }
        if (mouseX < this.sidingsRowX || mouseX >= this.sidingsRowX + this.sidingsRowW
                || mouseY < this.sidingsRowY || mouseY >= this.sidingsRowY + this.sidingsRowH) {
            return;
        }
        context.drawCenteredTextWithShadow(this.textRenderer, tr("tip.needWorld"),
                this.width / 2, this.sidingsRowY + this.sidingsRowH + 2, 0xFFD37F);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Leaving the screen (back, ESC, another screen) is the last chance to flush a drag. */
    @Override
    public void removed() {
        if (this.dirty) {
            this.dirty = false;
            MtrHeadlightClient.config().save();
        }
        super.removed();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
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
        private final double min;
        private final double max;
        private final double step;
        private boolean ready;

        NumberSlider(final int x, final int y, final int w, final int h, final int which,
                     final String key, final double min, final double max, final double step,
                     final double current) {
            super(x, y, w, h, ParameterScreen.tr(key, formatFor(key, current)),
                    normalize(current, min, max));
            this.which = which;
            this.min = min;
            this.max = max;
            this.step = step;
            this.ready = true;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (!this.ready) {
                return;
            }
            setMessage(sliderLabel(this.which, actual(), follows(this.which)));
        }

        @Override
        protected void applyValue() {
            if (!this.ready) {
                return;
            }
            writeValue(this.which, actual());
        }

        private double actual() {
            return snap(this.min + (this.max - this.min) * this.value, this.min, this.max, this.step);
        }
    }

    private static String formatFor(final String key, final double value) {
        return "opt.headLength".equals(key)
                ? String.format(Locale.ROOT, "%.1f", value)
                : String.format(Locale.ROOT, "%.0f", value);
    }
}
