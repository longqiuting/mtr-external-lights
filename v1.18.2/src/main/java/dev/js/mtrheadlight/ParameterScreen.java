package dev.js.mtrheadlight;

import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import dev.js.mtrheadlight.clientgui.Widgets;

import net.minecraft.client.util.math.MatrixStack;
import dev.js.mtrheadlight.mtr.BuiltInTrains;
import mtr.data.Siding;
import net.minecraft.client.MinecraftClient;
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
 * 一个分区里的具体配置行 - 全局配置和一条侧线的覆盖都复用它，只是
 * {@link ConfigWriteTarget} 不同（{@link #category} 决定显示哪些行）。
 *
 * <h2>两层过滤，谁都不会漏</h2>
 * <ul>
 *   <li><b>分区</b>：{@link #buildRows()} 按 {@link ConfigCategory} 只生成这一类行，
 *       所以「光源」页不会有伤害系数，「伤害」页不会有颜色；</li>
 *   <li><b>全局 / 侧线</b>：只有 {@link HeadlightConfig.SidingSettings} 里存在的字段才在侧线页生成。
 *       车体开关、道床、车顶、水雾、伤害公式、内置车这些<b>只有全局值</b>的行在侧线页根本不创建，
 *       不会再出现「点一下全局限定的行，写进一个没人读的字段」（更早的版本还会抛 globalOnly）。</li>
 * </ul>
 *
 * <h2>两格 + ↺ 列，宽度始终一致</h2>
 * <ul>
 *   <li><b>左格</b>（{@link #colX}, {@link #leftW}）：开关、色块或参数名；</li>
 *   <li><b>右格</b>（{@link #rightX()}, {@link #rightW}）：滑块，或色块；</li>
 *   <li><b>↺ 列</b>（{@link #resetX()}, {@link #RESET_W}）：每行末尾一个方形按钮。
 *       它从整块宽度里扣，所以两格（连同滑块）比没有它时窄 {@code RESET_W + GAP}，
 *       并且 {@code rightX() + rightW + GAP == resetX()}，不会压在滑块上；</li>
 *   <li>滑块用的就是右格的 x / 宽度 - 没有居中、变窄、加宽的第二套坐标。</li>
 * </ul>
 *
 * <h2>↺ 的两种含义</h2>
 * <ul>
 *   <li><b>行内 ↺</b>（{@link ConfigWriteTarget#resetValue(int)} 等）：全局页把这一个字段写回
 *       {@link HeadlightConfig#defaults()}；侧线页只删掉这一项覆盖，这一行重新跟随全局
 *       （标签出现（跟随））。一行有两个参数（开关 + 滑块）时两个一起恢复。</li>
 *   <li><b>全部恢复默认</b>（底栏，经 {@link ResetConfirmScreen}）：{@link ConfigWriteTarget#resetAll()} -
 *       全局页恢复每一个全局字段<b>并清空全部侧线覆盖</b>；侧线页只删这条侧线的覆盖。</li>
 * </ul>
 *
 * <h2>滚动</h2>
 * 一个分区仍然可能超过一屏（伤害 16 行），所以整行滚动：滚轮、PageUp / PageDown。右侧一条细
 * 位置条，状态行显示「滚轮翻看 n/m」。只滚整行 - 半行会滑到底栏下面，滑块就点不到了。
 *
 * <h2>保存 / 取消</h2>
 * 这一页<b>不写盘、也不拍快照</b>：每个控件直接改内存里的实时配置（世界里的光立刻预览）。
 * 写盘和整份回滚都只在主界面 {@link ConfigScreen} 的「完成 / 取消」发生，见那里的说明。
 * 底部因此是「返回分区 / 返回主界面」，ESC 回到分区列表（逐层返回）。
 */
public final class ParameterScreen extends Screen {
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
     * Footer band: 全部恢复默认 on its own row at {@code height-70..height-50}, 返回分区 / 返回主界面 at
     * {@code height-44..height-24}.
     */
    private static final int FOOTER_H = 70;
    /** Gap between the 全部恢复默认 row and the 返回 row. */
    private static final int FOOTER_ROW_GAP = 6;
    /** Space below the list: the JSON hint line sits at height-84, the author line at height-14. */
    private static final int LIST_BOTTOM_PAD = 20;

    private static final int ROW_TOGGLE = 0;
    private static final int ROW_VALUE = 1;
    private static final int ROW_COLOR = 2;
    private static final int ROW_ACTION = 3;
    /**
     * A switch with <b>no slider</b> next to it: the button spans both cells, the ↺ button stays at
     * the end. Used for the features that have no per-train number to pair with (光柱 / 车体碰撞 /
     * 道床 / 内置车型屏蔽 …).
     */
    private static final int ROW_TOGGLE_WIDE = 4;
    /** 三态值（车灯模式 自动 / 常亮 / 关闭）：整行一个按钮，点一下换下一档。 */
    private static final int ROW_CYCLE = 5;

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
    /** 碰撞端部延长（格）：与 {@link HeadlightConfig#collisionHeadExtraClamped()} 同一个 0–4 区间。 */
    private static final double END_EXTRA_MIN = 0.0D;
    private static final double END_EXTRA_MAX = 4.0D;
    private static final double END_EXTRA_STEP = 0.1D;
    /** 车体碰撞高度（格）：与 {@link HeadlightConfig#trainCollisionHeightClamped()} 同一个 1–8 区间。 */
    private static final double COL_HEIGHT_MIN = 1.0D;
    private static final double COL_HEIGHT_MAX = 8.0D;
    private static final double COL_HEIGHT_STEP = 0.1D;
    /** 车顶掉落（载人）速度阈值 km/h；弯道半径 格。 */
    private static final double ROOF_SPEED_MIN = 0.0D;
    private static final double ROOF_SPEED_MAX = 300.0D;
    private static final double ROOF_SPEED_STEP = 5.0D;
    private static final double ROOF_RADIUS_MIN = 10.0D;
    private static final double ROOF_RADIUS_MAX = 1000.0D;
    private static final double ROOF_RADIUS_STEP = 10.0D;
    /** 水雾起始速度（km/h）：与 {@link HeadlightConfig#rainSprayMinSpeedKmh} 同一个 0–200 区间。 */
    private static final double SPRAY_SPEED_MIN = 0.0D;
    private static final double SPRAY_SPEED_MAX = 200.0D;
    private static final double SPRAY_SPEED_STEP = 5.0D;
    /** 列车碰撞的速度分档（km/h）：四个阈值共用 0–400、步长 1。 */
    private static final double TIER_MIN = 0.0D;
    private static final double TIER_MAX = 400.0D;
    private static final double TIER_STEP = 1.0D;
    /** 指数伤害：基准 hp/s、系数 k、速度尺度 km/h、飞出倍率。 */
    private static final double RATE_MIN = 0.0D;
    private static final double RATE_MAX = 10.0D;
    private static final double RATE_STEP = 0.05D;
    private static final double EXP_K_MIN = 0.0D;
    private static final double EXP_K_MAX = 8.0D;
    private static final double EXP_K_STEP = 0.1D;
    private static final double RATE_SCALE_MIN = 10.0D;
    private static final double RATE_SCALE_MAX = 400.0D;
    private static final double RATE_SCALE_STEP = 5.0D;
    private static final double FLY_SCALE_MIN = 1.0D;
    private static final double FLY_SCALE_MAX = 10.0D;
    private static final double FLY_SCALE_STEP = 0.5D;
    /** 冲量（格/tick）：水平 0–12、竖直 0–6，步长 0.05。 */
    private static final double IMPULSE_MIN = 0.0D;
    private static final double IMPULSE_MAX = 12.0D;
    private static final double IMPULSE_V_MAX = 6.0D;
    private static final double IMPULSE_STEP = 0.05D;
    /** 冲量结算冷却（tick，1–200）。 */
    private static final double COOLDOWN_MIN = 1.0D;
    private static final double COOLDOWN_MAX = 200.0D;
    private static final double COOLDOWN_STEP = 1.0D;
    /** 撞飞距离基准值（格，1–80）：d(v) = base·exp(k(v−v0)/50)，滑块就是「50 km/h 时飞多远」。 */
    private static final double KNOCKBACK_MIN = 1.0D;
    private static final double KNOCKBACK_MAX = 80.0D;
    private static final double KNOCKBACK_STEP = 0.5D;
    /** 指数系数 k（0–3，默认 1.0）：k=0 退回固定距离。 */
    private static final double KNOCKBACK_K_MIN = 0.0D;
    private static final double KNOCKBACK_K_MAX = 3.0D;
    private static final double KNOCKBACK_K_STEP = 0.1D;
    /** 距离上限（格，1–200，默认 80）：指数曲线的天花板。 */
    private static final double KNOCKBACK_CAP_MIN = 1.0D;
    private static final double KNOCKBACK_CAP_MAX = 200.0D;
    private static final double KNOCKBACK_CAP_STEP = 1.0D;
    /** 一次接触的累计伤害上限（hp，0–100；0 = 只受实体最大生命限制）。 */
    private static final double DMG_CAP_MIN = 0.0D;
    private static final double DMG_CAP_MAX = 100.0D;
    private static final double DMG_CAP_STEP = 0.5D;

    /** 分区列表（返回分区 / ESC 都回这里）。 */
    private final Screen parent;
    /** 主界面 {@link ConfigScreen}（返回主界面）。 */
    private final Screen root;
    /** null = global settings, otherwise this siding's overrides. */
    private final Siding siding;
    /** 这一页显示哪个分区的行。 */
    private final ConfigCategory category;
    /** The single object every control writes through; never null, never a global writer here. */
    private final ConfigWriteTarget target;

    // ---- geometry, recomputed in init() ----
    private int colX;
    private int colW;
    private int leftW;
    private int rightW;
    private int listTop;
    private int scrollRow;
    private int visibleRows = 1;

    /** Rows of the current category, in draw order. */
    private final List<Row> rows = new ArrayList<>();
    /** Text painted inside the left cell (no widget) for colour and slider-only rows. */
    private final List<Label> labels = new ArrayList<>();
    /** Squares painted inside the swatch buttons of the visible colour rows. */
    private final List<Swatch> swatches = new ArrayList<>();

    public ParameterScreen(final Screen parent, final Screen root, final Siding siding,
                           final ConfigCategory category) {
        super(isGlobalTitle(siding, category));
        this.parent = parent;
        this.root = root == null ? parent : root;
        this.siding = siding;
        this.category = category == null ? ConfigCategory.LIGHT : category;
        // Never degrade to the global target: a detail screen without a siding is a bug in the
        // caller, and silently editing the globals is exactly the symptom this class exists to
        // make impossible.
        this.target = siding == null
                ? ConfigWriteTarget.global(MtrHeadlightClient::config)
                : ConfigWriteTarget.siding(MtrHeadlightClient::config, siding.id);
    }

    private static MutableText isGlobalTitle(final Siding siding, final ConfigCategory category) {
        final MutableText name = (category == null ? ConfigCategory.LIGHT : category).title();
        return siding == null
                ? tr("screen.mtrlte.section", name)
                : tr("detail.section", Long.toString(siding.id), nameOf(siding), name);
    }

    /** True for the global flow. */
    private boolean isGlobal() {
        return this.siding == null;
    }

    /** Translation lookup; every literal is in assets/mtrlte/lang. */
    static MutableText tr(final String key, final Object... args) {
        return new TranslatableText(key, args);
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
        this.labels.clear();
        this.swatches.clear();
        buildRows();

        // ---- horizontal: two equal cells plus the ↺ column, inside the widest thing either cell ----
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
                "[lte] config layout {}x{} ({} / {}): column x={} w={}, left cell x={} w={},"
                        + " right cell x={} w={}, reset x={} w={}, rows={} visible={}",
                Integer.valueOf(this.width), Integer.valueOf(this.height),
                isGlobal() ? "global" : "siding", this.category.name(),
                Integer.valueOf(this.colX), Integer.valueOf(this.colW), Integer.valueOf(this.colX),
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
                case ROW_TOGGLE_WIDE:
                case ROW_CYCLE:
                    // 整行宽的一个按钮：只需要装下按钮文字本身。
                    need = Math.max(need, textWidth(row.kind == ROW_CYCLE
                            ? modeLabel() : toggleLabel(row.toggle)) + 14);
                    break;
                case ROW_VALUE:
                    need = Math.max(need, textWidth(valueLabel(row.value)) + 14);
                    need = Math.max(need, textWidth(new LiteralText(
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
        return SWATCH_SIDE + 6 + textWidth(new LiteralText("#FFFFFF")) + 10;
    }

    private int textWidth(final Text text) {
        return this.textRenderer.getWidth(text.getString());
    }

    // ---------------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------------

    /**
     * The row table of THIS category only. Side-by-side comparison of the five partitions:
     * <pre>
     *   光源  总开关|渲染距离 · 车灯模式(自动/常亮/关闭，全局页) · 光带|照射长度 · 光柱 ·
     *         动态光源|车灯亮度 · 车头/车尾颜色 · 光束距离/车头长度/车尾长度/光束宽度/
     *         车头高度/车尾高度 · 光束编辑
     *   碰撞  车体碰撞 · 碰撞高度 · 道床碰撞 · 车头延长 · 车尾延长 · 车顶掉落速度 · 车顶弯道半径
     *   粒子  雨天水雾|水雾起始速度 · 挡雨
     *   伤害  伤害开关 · 创造模式也受伤 · 伤害起点速度 · 伤害上限 · 4 个速度档 · 4 个伤害系数 · 撞飞距离 · 2 个竖直初速 · 冲量冷却 · 生物受伤
     *   其他  内置车型屏蔽 · 重载内置车型 · 立即重载光源
     * </pre>
     * 「车灯模式」放在光源分区第二行（用户要求：它决定灯什么时候亮，属于光源，不属于其他），
     * 并且只在全局页出现 —— 它没有侧线覆盖。三个「灯」开关连在一起：
     * <b>光带 → 光柱 → 动态光源</b>（同一件事的三层：贴地光带 / 体积光锥 / 真实方块光照），
     * 动态光源那行右边仍然是车灯亮度滑块，改开关立刻能用滑块看到效果。
     * {@code isGlobal()} 为假时只留下侧线真能覆盖的行：光源全部（除车灯模式）、碰撞的两个端部延长、
     * 粒子的三项，以及伤害的开关 / 起点速度 / 上限。
     */
    private void buildRows() {
        this.rows.clear();
        switch (this.category) {
            case LIGHT:
                this.rows.add(Row.toggle(ConfigWriteTarget.MASTER, ConfigWriteTarget.DISTANCE));
                // 车灯模式（自动 / 常亮 / 关闭）从「其他」搬到这里：它决定的就是灯什么时候亮，
                // 属于光源。它没有侧线覆盖，所以只在全局页出现（侧线页多一行不可点的行没有意义）。
                if (isGlobal()) {
                    this.rows.add(Row.mode());
                }
                this.rows.add(Row.toggle(ConfigWriteTarget.LIGHT_BAND, ConfigWriteTarget.HEAD_LENGTH));
                // 光柱是光带之外的另一件事（体积光锥 vs 贴地光带）：整行一个开关。它可按侧线覆盖，
                // 所以侧线页也有这一行（alpha / 采样数 / 遮挡只在 JSON 里，见 SidingSettings）。
                this.rows.add(Row.toggleWide(ConfigWriteTarget.BEAM_CONE));
                // 动态光源 + 车灯亮度：和光带 / 光柱三个开关并排，紧跟在它们后面。
                // 这三个是同一件事的三个层次（贴地光带 → 体积光锥 → 真实方块光照），
                // 以前它在光源页最底下（要滚到底才看见），也在「其他」页出现过，现在归位到这里。
                this.rows.add(Row.toggle(ConfigWriteTarget.DYNAMIC_LIGHT, ConfigWriteTarget.LUMINANCE));
                this.rows.add(Row.color(true));
                this.rows.add(Row.color(false));
                this.rows.add(Row.value(ConfigWriteTarget.BEAM_DISTANCE));
                this.rows.add(Row.value(ConfigWriteTarget.BEAM_HEAD_LENGTH));
                this.rows.add(Row.value(ConfigWriteTarget.BEAM_TAIL_LENGTH));
                this.rows.add(Row.value(ConfigWriteTarget.BEAM_WIDTH));
                this.rows.add(Row.value(ConfigWriteTarget.BEAM_HEAD_HEIGHT));
                this.rows.add(Row.value(ConfigWriteTarget.BEAM_TAIL_HEIGHT));
                // 光束编辑：逐车型、逐束的几何 / 颜色 / 长度，入口放在光源分区最底部。
                this.rows.add(Row.worldAction(tr("btn.beamEditor"), this::openBeamEditor));
                break;
            case COLLISION:
                // 车体 / 道床 / 车顶是整车的几何设定，不是按车厢量出来的参数，所以只有全局值。
                if (isGlobal()) {
                    this.rows.add(Row.toggleWide(ConfigWriteTarget.TRAIN_COLLISION));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_HEIGHT));
                    this.rows.add(Row.toggleWide(ConfigWriteTarget.TRACKBED));
                }
                // 端部延长按行驶方向：头车前端 / 尾车后端。侧线页的 ↺ 是「清除这一项覆盖」。
                this.rows.add(Row.value(ConfigWriteTarget.COLLISION_HEAD_EXTRA));
                this.rows.add(Row.value(ConfigWriteTarget.COLLISION_TAIL_EXTRA));
                if (isGlobal()) {
                    this.rows.add(Row.value(ConfigWriteTarget.ROOF_DROP_SPEED));
                    this.rows.add(Row.value(ConfigWriteTarget.ROOF_DROP_RADIUS));
                }
                break;
            case PARTICLE:
                // 雨天水雾 + 它的起始速度（开关和阈值同一行，改一个立刻能看出另一个的效果）；
                // 挡雨整行一个开关。三项都可按侧线覆盖，所以全局页和侧线页用同一张行表。
                this.rows.add(Row.toggle(ConfigWriteTarget.RAIN_SPRAY,
                        ConfigWriteTarget.RAIN_SPRAY_MIN_SPEED));
                this.rows.add(Row.toggleWide(ConfigWriteTarget.RAIN_HUSH));
                break;
            case DAMAGE:
                // 伤害开关 / 伤害起点速度 / 本次接触的伤害上限可按侧线覆盖；
                // 分档与公式系数（低/高速档、k、尺度、冲量、冷却、生物）仍然只有全局值。
                // 注意语义：关掉伤害只是不扣血，推开与撞飞照旧（见 CollisionImpact.tick）。
                this.rows.add(Row.toggleWide(ConfigWriteTarget.TRAIN_DAMAGE));
                // 创造模式也受伤：测试 / 特殊玩法的开关，全局值（侧线页不生成）。
                if (isGlobal()) {
                    this.rows.add(Row.toggleWide(ConfigWriteTarget.DAMAGE_IN_CREATIVE));
                }
                this.rows.add(Row.value(isGlobal()
                        ? ConfigWriteTarget.COLLISION_PUSH_MAX
                        : ConfigWriteTarget.DAMAGE_MIN_SPEED));
                this.rows.add(Row.value(ConfigWriteTarget.DAMAGE_MAX));
                if (isGlobal()) {
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_LOW_MAX));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_HIGH_MAX));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_LAUNCH_MAX));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_BASE_RATE));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_EXP_K));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_RATE_SCALE));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_FLY_SCALE));
                    // 撞飞距离：基准值 + 指数系数 + 上限三行一组（1–80 格 / 0–3 / 1–200）。
                    // 水平初速由三者算出的 d(v) 反推，所以这里改的就是玩家看到的「被撞飞多远」；
                    // 原来那两行「水平初速」已经并进这一组。
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_KNOCKBACK));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_KNOCKBACK_K));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_KNOCKBACK_MAX));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_LAUNCH_V));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_FLY_V));
                    this.rows.add(Row.value(ConfigWriteTarget.COLLISION_COOLDOWN));
                    this.rows.add(Row.toggleWide(ConfigWriteTarget.TRAIN_MOBS));
                }
                break;
            default:
                if (isGlobal()) {
                    this.rows.add(Row.toggleWide(ConfigWriteTarget.BUILTIN_TRAINS));
                    this.rows.add(Row.action(tr("btn.reloadTrains"), this::reloadTrains));
                    this.rows.add(Row.action(tr("btn.reload"), LightController::reload));
                }
                break;
        }
    }

    private void buildRow(final Row row, final int visibleIndex) {
        final int y = rowY(visibleIndex);
        switch (row.kind) {
            case ROW_TOGGLE: {
                // 没有 LambDynamicLights 就没有动态光源可开关：这一行置灰 + 悬停说明，其余功能照常。
                // 1.19.2 的 tooltip 只能建按钮时给（没有 1.19.4 的 setTooltip），所以先算好。
                final boolean ldlMissing = row.toggle == ConfigWriteTarget.DYNAMIC_LIGHT
                        && !LightBackends.available();
                final ButtonWidget button = addButton(this.colX, y, this.leftW, ROW_H,
                        toggleLabel(row.toggle), b -> {
                            this.target.writeToggle(row.toggle,
                                    !this.target.readToggle(row.toggle));
                            // 内置车型屏蔽要立刻生效：写盘之前先把注册表重建一遍（与 /mtr_lte
                            // trains reload 同一条路径），否则要等 tick 的冷却才看见变化。
                            if (row.toggle == ConfigWriteTarget.BUILTIN_TRAINS) {
                                BuiltInTrains.reloadNow();
                            }
                            // A switch that now differs from the global value starts being an
                            // override; getting back to "follow" is the explicit 恢复为跟随全局 row.
                            reinit();
                        }, ldlMissing ? tr("hint.needsLdl") : null);
                if (ldlMissing) {
                    button.active = false;
                }
                addDrawableChild(new NumberSlider(rightX(), y, this.rightW, ROW_H, row.value,
                        true, this.target.readValue(row.value)));
                addResetButton(row, y);
                break;
            }
            case ROW_TOGGLE_WIDE: {
                // 两边格合成一个按钮：这一行没有数值可配，强塞一个无关滑块只会误导。
                final boolean ldlMissing = row.toggle == ConfigWriteTarget.DYNAMIC_LIGHT
                        && !LightBackends.available();
                final Text wideTip = ldlMissing
                        ? tr("hint.needsLdl")
                        : row.toggle == ConfigWriteTarget.DAMAGE_IN_CREATIVE
                                ? tr("hint.damageInCreative") : null;
                final ButtonWidget button = addButton(this.colX, y,
                        this.colW - RESET_W - GAP, ROW_H, toggleLabel(row.toggle), b -> {
                            this.target.writeToggle(row.toggle,
                                    !this.target.readToggle(row.toggle));
                            if (row.toggle == ConfigWriteTarget.BUILTIN_TRAINS) {
                                BuiltInTrains.reloadNow();
                            }
                            reinit();
                        }, wideTip);
                if (ldlMissing) {
                    button.active = false;
                }
                addResetButton(row, y);
                break;
            }
            case ROW_CYCLE: {
                // 车灯模式：整行一个按钮，点一下切下一档（自动 -> 常亮 -> 关闭）。
                addButton(this.colX, y, this.colW - RESET_W - GAP, ROW_H, modeLabel(), b -> {
                    final int next = ((int) Math.round(
                            this.target.readValue(ConfigWriteTarget.HEADLIGHT_MODE)) + 1) % 3;
                    this.target.writeValue(ConfigWriteTarget.HEADLIGHT_MODE, next);
                    reinit();
                });
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
                addButton(rightX(), y, this.rightW, ROW_H, LiteralText.EMPTY,
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
     *
     * <p><b>A row can carry two parameters</b> (a switch on the left, a slider on the right). Its ↺
     * resets <b>both</b>: resetting only the switch made the button look dead whenever the switch was
     * already at its default while the number had been changed.
     */
    private void addResetButton(final Row row, final int y) {
        addButton(resetX(), y, RESET_W, ROW_H, tr("btn.resetOne"), button -> {
            switch (row.kind) {
                case ROW_TOGGLE:
                case ROW_TOGGLE_WIDE:
                    this.target.resetToggle(row.toggle);
                    if (row.value >= 0) {
                        this.target.resetValue(row.value);
                    }
                    if (row.toggle == ConfigWriteTarget.BUILTIN_TRAINS) {
                        // ↺ 也是「开关变了」：和点按钮一样立刻重建，否则要等 tick 的冷却。
                        BuiltInTrains.reloadNow();
                    }
                    break;
                case ROW_CYCLE:
                    this.target.resetValue(ConfigWriteTarget.HEADLIGHT_MODE);
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

    /**
     * 全部恢复默认 + 返回分区 / 返回主界面。这一页不保存也不回滚：写盘和整份回滚都在主界面，
     * 所以两个出口都只是「回上一层 / 回最上层」。
     */
    private void buildFooter() {
        final int resetY = this.height - FOOTER_H;
        addButton(this.colX, resetY, this.colW, ROW_H, tr("btn.resetAll"),
                button -> openResetAllConfirm());

        final int y = resetY + ROW_H + FOOTER_ROW_GAP;
        final int cell = (this.colW - GAP) / 2;
        final int x = this.width / 2 - (cell * 2 + GAP) / 2;
        addButton(x, y, cell, ROW_H, tr("btn.backSection"), button -> closeToParent());
        addButton(x + cell + GAP, y, cell, ROW_H, tr("btn.backMain"), button -> closeToRoot());
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

    /** 返回分区: back to this target's category list. */
    private void closeToParent() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    /** 返回主界面: straight to {@link ConfigScreen}, where 完成 writes and 取消 rolls back. */
    private void closeToRoot() {
        if (this.client != null) {
            this.client.setScreen(this.root);
        }
    }

    /**
     * 光束编辑入口：先按车型选（{@link BeamVehicleScreen}），再进光束列表（{@link BeamListScreen}）
     * 和单束编辑（{@link BeamEditScreen}）。侧线页进来时把这条侧线当前使用的车型当预选项带过去
     * - 侧线配置的全部意义就是「这条线用的那几辆车」。
     */
    private void openBeamEditor() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BeamVehicleScreen(this, this.root, BeamSession.presetOf(this.siding)));
    }

    /**
     * 「重载内置车型」: the button form of {@code /mtr_lte trains reload}. It goes through the same
     * {@link BuiltInTrains#reloadNow()} the command uses, so the two cannot report different numbers,
     * and the result is echoed into chat because a button gives no other feedback.
     */
    private void reloadTrains() {
        final String result = BuiltInTrains.reloadNow();
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(new LiteralText(result), false);
        }
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
        this.init(this.client, this.width, this.height); // 1.18.2 没有 clearAndInit()：init 自己会 clearChildren
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
            case ConfigWriteTarget.BEAM_CONE:
                return follow(this.target.readToggle(which)
                        ? tr("opt.cone.on") : tr("opt.cone.off"), which);
            case ConfigWriteTarget.RAIN_SPRAY:
                return follow(this.target.readToggle(which)
                        ? tr("opt.rainSpray.on") : tr("opt.rainSpray.off"), which);
            case ConfigWriteTarget.BUILTIN_TRAINS:
                return follow(this.target.readToggle(which)
                        ? tr("opt.builtin.on") : tr("opt.builtin.off"), which);
            case ConfigWriteTarget.TRAIN_COLLISION:
                return follow(this.target.readToggle(which)
                        ? tr("opt.trainCollision.on") : tr("opt.trainCollision.off"), which);
            case ConfigWriteTarget.TRACKBED:
                return follow(this.target.readToggle(which)
                        ? tr("opt.trackbed.on") : tr("opt.trackbed.off"), which);
            case ConfigWriteTarget.TRAIN_DAMAGE:
                return follow(this.target.readToggle(which)
                        ? tr("opt.trainDamage.on") : tr("opt.trainDamage.off"), which);
            case ConfigWriteTarget.TRAIN_MOBS:
                return follow(this.target.readToggle(which)
                        ? tr("opt.trainMobs.on") : tr("opt.trainMobs.off"), which);
            case ConfigWriteTarget.DAMAGE_IN_CREATIVE:
                return tr(this.target.readToggle(which)
                        ? "opt.damageInCreative.on" : "opt.damageInCreative.off");
            case ConfigWriteTarget.RAIN_HUSH:
                return follow(this.target.readToggle(which)
                        ? tr("opt.rainHush.on") : tr("opt.rainHush.off"), which);
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

    /** 车灯模式：自动 / 常亮 / 关闭 - the label re-reads the live value, so a click only needs a rebuild. */
    private MutableText modeLabel() {
        final int index = (int) Math.round(this.target.readValue(ConfigWriteTarget.HEADLIGHT_MODE));
        return tr("opt.mode", tr(modeKey(index)));
    }

    private static String modeKey(final int index) {
        switch (index) {
            case 1:
                return "opt.mode.always";
            case 2:
                return "opt.mode.off";
            default:
                return "opt.mode.auto";
        }
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
            case ConfigWriteTarget.COLLISION_HEAD_EXTRA:
                return "opt.collisionHeadExtra";
            case ConfigWriteTarget.COLLISION_TAIL_EXTRA:
                return "opt.collisionTailExtra";
            case ConfigWriteTarget.COLLISION_HEIGHT:
                return "opt.collisionHeight";
            case ConfigWriteTarget.ROOF_DROP_SPEED:
                return "opt.roofDropSpeed";
            case ConfigWriteTarget.ROOF_DROP_RADIUS:
                return "opt.roofDropRadius";
            case ConfigWriteTarget.RAIN_SPRAY_MIN_SPEED:
                return "opt.rainSprayMinSpeed";
            case ConfigWriteTarget.DAMAGE_MIN_SPEED:
                // 和全局的「推开车速上限」是同一个概念（伤害起点 v0），标签复用。
                return "opt.collisionPushMax";
            case ConfigWriteTarget.DAMAGE_MAX:
                return "opt.damageMax";
            case ConfigWriteTarget.COLLISION_KNOCKBACK:
                return "opt.knockbackDistance";
            case ConfigWriteTarget.COLLISION_KNOCKBACK_K:
                return "opt.knockbackExpK";
            case ConfigWriteTarget.COLLISION_KNOCKBACK_MAX:
                return "opt.knockbackMax";
            case ConfigWriteTarget.COLLISION_PUSH_MAX:
                return "opt.collisionPushMax";
            case ConfigWriteTarget.COLLISION_LOW_MAX:
                return "opt.collisionLowMax";
            case ConfigWriteTarget.COLLISION_HIGH_MAX:
                return "opt.collisionHighMax";
            case ConfigWriteTarget.COLLISION_LAUNCH_MAX:
                return "opt.collisionLaunchMax";
            case ConfigWriteTarget.COLLISION_BASE_RATE:
                return "opt.collisionBaseRate";
            case ConfigWriteTarget.COLLISION_EXP_K:
                return "opt.collisionExpK";
            case ConfigWriteTarget.COLLISION_RATE_SCALE:
                return "opt.collisionRateScale";
            case ConfigWriteTarget.COLLISION_FLY_SCALE:
                return "opt.collisionFlyScale";
            case ConfigWriteTarget.COLLISION_LAUNCH_H:
                return "opt.collisionLaunchH";
            case ConfigWriteTarget.COLLISION_LAUNCH_V:
                return "opt.collisionLaunchV";
            case ConfigWriteTarget.COLLISION_FLY_H:
                return "opt.collisionFlyH";
            case ConfigWriteTarget.COLLISION_FLY_V:
                return "opt.collisionFlyV";
            case ConfigWriteTarget.COLLISION_COOLDOWN:
                return "opt.collisionCooldown";
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
            case ConfigWriteTarget.COLLISION_HEAD_EXTRA:
            case ConfigWriteTarget.COLLISION_TAIL_EXTRA:
                return END_EXTRA_MAX;
            case ConfigWriteTarget.COLLISION_HEIGHT:
                return COL_HEIGHT_MAX;
            case ConfigWriteTarget.ROOF_DROP_SPEED:
                return ROOF_SPEED_MAX;
            case ConfigWriteTarget.ROOF_DROP_RADIUS:
                return ROOF_RADIUS_MAX;
            case ConfigWriteTarget.RAIN_SPRAY_MIN_SPEED:
                return SPRAY_SPEED_MAX;
            case ConfigWriteTarget.DAMAGE_MIN_SPEED:
                return TIER_MAX;
            case ConfigWriteTarget.DAMAGE_MAX:
                return DMG_CAP_MAX;
            case ConfigWriteTarget.COLLISION_PUSH_MAX:
            case ConfigWriteTarget.COLLISION_LOW_MAX:
            case ConfigWriteTarget.COLLISION_HIGH_MAX:
            case ConfigWriteTarget.COLLISION_LAUNCH_MAX:
                return TIER_MAX;
            case ConfigWriteTarget.COLLISION_BASE_RATE:
                return RATE_MAX;
            case ConfigWriteTarget.COLLISION_EXP_K:
                return EXP_K_MAX;
            case ConfigWriteTarget.COLLISION_RATE_SCALE:
                return RATE_SCALE_MAX;
            case ConfigWriteTarget.COLLISION_FLY_SCALE:
                return FLY_SCALE_MAX;
            case ConfigWriteTarget.COLLISION_LAUNCH_H:
            case ConfigWriteTarget.COLLISION_FLY_H:
                return IMPULSE_MAX;
            case ConfigWriteTarget.COLLISION_LAUNCH_V:
            case ConfigWriteTarget.COLLISION_FLY_V:
                return IMPULSE_V_MAX;
            case ConfigWriteTarget.COLLISION_KNOCKBACK:
                return KNOCKBACK_MAX;
            case ConfigWriteTarget.COLLISION_KNOCKBACK_K:
                return KNOCKBACK_K_MAX;
            case ConfigWriteTarget.COLLISION_KNOCKBACK_MAX:
                return KNOCKBACK_CAP_MAX;
            case ConfigWriteTarget.COLLISION_COOLDOWN:
                return COOLDOWN_MAX;
            default:
                return BEAM_HEIGHT_MAX;
        }
    }

    private static String formatValue(final int which, final double value) {
        switch (which) {
            case ConfigWriteTarget.DISTANCE:
            case ConfigWriteTarget.LUMINANCE:
            case ConfigWriteTarget.BEAM_DISTANCE:
            case ConfigWriteTarget.RAIN_SPRAY_MIN_SPEED:
            case ConfigWriteTarget.ROOF_DROP_SPEED:
            case ConfigWriteTarget.DAMAGE_MIN_SPEED:
                return String.format(Locale.ROOT, "%.0f", value);
            case ConfigWriteTarget.BEAM_HEAD_HEIGHT:
            case ConfigWriteTarget.BEAM_TAIL_HEIGHT:
                return String.format(Locale.ROOT, "%.2f", value);
            case ConfigWriteTarget.COLLISION_HEIGHT:
            case ConfigWriteTarget.ROOF_DROP_RADIUS:
                return String.format(Locale.ROOT, "%.1f", value);
            case ConfigWriteTarget.COLLISION_PUSH_MAX:
            case ConfigWriteTarget.COLLISION_LOW_MAX:
            case ConfigWriteTarget.COLLISION_HIGH_MAX:
            case ConfigWriteTarget.COLLISION_LAUNCH_MAX:
            case ConfigWriteTarget.COLLISION_RATE_SCALE:
            case ConfigWriteTarget.COLLISION_COOLDOWN:
                return String.format(Locale.ROOT, "%.0f", value);
            case ConfigWriteTarget.COLLISION_EXP_K:
            case ConfigWriteTarget.COLLISION_FLY_SCALE:
            case ConfigWriteTarget.COLLISION_KNOCKBACK_K:
                return String.format(Locale.ROOT, "%.1f", value);
            case ConfigWriteTarget.COLLISION_KNOCKBACK_MAX:
                return String.format(Locale.ROOT, "%.0f", value);
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
            case ConfigWriteTarget.COLLISION_HEAD_EXTRA:
            case ConfigWriteTarget.COLLISION_TAIL_EXTRA:
                return END_EXTRA_MIN;
            case ConfigWriteTarget.COLLISION_HEIGHT:
                return COL_HEIGHT_MIN;
            case ConfigWriteTarget.ROOF_DROP_SPEED:
                return ROOF_SPEED_MIN;
            case ConfigWriteTarget.ROOF_DROP_RADIUS:
                return ROOF_RADIUS_MIN;
            case ConfigWriteTarget.RAIN_SPRAY_MIN_SPEED:
                return SPRAY_SPEED_MIN;
            case ConfigWriteTarget.DAMAGE_MIN_SPEED:
                return TIER_MIN;
            case ConfigWriteTarget.DAMAGE_MAX:
                return DMG_CAP_MIN;
            case ConfigWriteTarget.COLLISION_PUSH_MAX:
            case ConfigWriteTarget.COLLISION_LOW_MAX:
            case ConfigWriteTarget.COLLISION_HIGH_MAX:
            case ConfigWriteTarget.COLLISION_LAUNCH_MAX:
                return TIER_MIN;
            case ConfigWriteTarget.COLLISION_BASE_RATE:
                return RATE_MIN;
            case ConfigWriteTarget.COLLISION_EXP_K:
                return EXP_K_MIN;
            case ConfigWriteTarget.COLLISION_RATE_SCALE:
                return RATE_SCALE_MIN;
            case ConfigWriteTarget.COLLISION_FLY_SCALE:
                return FLY_SCALE_MIN;
            case ConfigWriteTarget.COLLISION_LAUNCH_H:
            case ConfigWriteTarget.COLLISION_LAUNCH_V:
            case ConfigWriteTarget.COLLISION_FLY_H:
            case ConfigWriteTarget.COLLISION_FLY_V:
                return IMPULSE_MIN;
            case ConfigWriteTarget.COLLISION_KNOCKBACK:
                return KNOCKBACK_MIN;
            case ConfigWriteTarget.COLLISION_KNOCKBACK_K:
                return KNOCKBACK_K_MIN;
            case ConfigWriteTarget.COLLISION_KNOCKBACK_MAX:
                return KNOCKBACK_CAP_MIN;
            case ConfigWriteTarget.COLLISION_COOLDOWN:
                return COOLDOWN_MIN;
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
            case ConfigWriteTarget.COLLISION_HEAD_EXTRA:
            case ConfigWriteTarget.COLLISION_TAIL_EXTRA:
                return END_EXTRA_STEP;
            case ConfigWriteTarget.COLLISION_HEIGHT:
                return COL_HEIGHT_STEP;
            case ConfigWriteTarget.ROOF_DROP_SPEED:
                return ROOF_SPEED_STEP;
            case ConfigWriteTarget.ROOF_DROP_RADIUS:
                return ROOF_RADIUS_STEP;
            case ConfigWriteTarget.RAIN_SPRAY_MIN_SPEED:
                return SPRAY_SPEED_STEP;
            case ConfigWriteTarget.DAMAGE_MIN_SPEED:
                return TIER_STEP;
            case ConfigWriteTarget.DAMAGE_MAX:
                return DMG_CAP_STEP;
            case ConfigWriteTarget.COLLISION_PUSH_MAX:
            case ConfigWriteTarget.COLLISION_LOW_MAX:
            case ConfigWriteTarget.COLLISION_HIGH_MAX:
            case ConfigWriteTarget.COLLISION_LAUNCH_MAX:
                return TIER_STEP;
            case ConfigWriteTarget.COLLISION_BASE_RATE:
                return RATE_STEP;
            case ConfigWriteTarget.COLLISION_EXP_K:
                return EXP_K_STEP;
            case ConfigWriteTarget.COLLISION_RATE_SCALE:
                return RATE_SCALE_STEP;
            case ConfigWriteTarget.COLLISION_FLY_SCALE:
                return FLY_SCALE_STEP;
            case ConfigWriteTarget.COLLISION_LAUNCH_H:
            case ConfigWriteTarget.COLLISION_LAUNCH_V:
            case ConfigWriteTarget.COLLISION_FLY_H:
            case ConfigWriteTarget.COLLISION_FLY_V:
                return IMPULSE_STEP;
            case ConfigWriteTarget.COLLISION_KNOCKBACK:
                return KNOCKBACK_STEP;
            case ConfigWriteTarget.COLLISION_KNOCKBACK_K:
                return KNOCKBACK_K_STEP;
            case ConfigWriteTarget.COLLISION_KNOCKBACK_MAX:
                return KNOCKBACK_CAP_STEP;
            case ConfigWriteTarget.COLLISION_COOLDOWN:
                return COOLDOWN_STEP;
            default:
                return BEAM_HEIGHT_STEP;
        }
    }

    /**
     * Same tooltip contract as the other screens: 1.19.2 has no
     * {@code ClickableWidget#setTooltip}, so the hint has to be handed over when the button is
     * built ({@code ButtonWidget.TooltipSupplier}); {@code null} means "no tooltip".
     */
    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        return this.addButton(x, y, w, h, label, action, null);
    }

    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action,
                                   final Text tooltip) {
        final Widgets.Builder builder = Widgets.button(label, action).dimensions(x, y, w, h);
        if (tooltip != null) {
            builder.tooltip(tooltip);
        }
        return this.addDrawableChild(builder.build());
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

    private void drawVanillaBackground(final DrawContext context) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    private void drawCentered(final DrawContext context, final Text text, final int cx,
                              final int y, final int color) {
        context.drawCenteredTextWithShadow(this.textRenderer, text, cx, y, color);
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        final DrawContext context = new DrawContext(matrices);
        drawVanillaBackground(context);
        super.render(matrices, mouseX, mouseY, delta);

        drawCentered(context, this.title, this.width / 2, 22, 0xFFFFFF);
        final boolean anyOn = this.target.readToggle(ConfigWriteTarget.MASTER);
        final MutableText subtitle = tr("config.status",
                        onOff(ConfigWriteTarget.MASTER), onOff(ConfigWriteTarget.DYNAMIC_LIGHT),
                        onOff(ConfigWriteTarget.LIGHT_BAND))
                .append(scrollHint());
        drawCentered(context, subtitle, this.width / 2, 36, anyOn ? 0x9DFF9D : 0xFF9D9D);

        drawLabels(context);
        drawSwatches(context);
        drawScrollBar(context);

        drawCentered(context, tr("hint.json"), this.width / 2, this.height - FOOTER_H - 14, 0x909090);
        drawCentered(context, tr("hint.author"), this.width / 2, this.height - 14, 0xB0B0B0);
    }

    private Text onOff(final int which) {
        return tr(this.target.readToggle(which) ? "config.on" : "config.off");
    }

    private Text scrollHint() {
        if (this.rows.size() <= this.visibleRows) {
            return LiteralText.EMPTY;
        }
        return tr("config.scrollHint", Integer.valueOf(this.scrollRow + 1),
                Integer.valueOf(this.rows.size()));
    }

    private void drawLabels(final DrawContext context) {
        for (final Label label : this.labels) {
            drawCentered(context, label.text.get(), label.x + label.w / 2,
                    label.y + (label.h - 8) / 2, 0xFFFFFF);
        }
    }

    private void drawSwatches(final DrawContext context) {
        for (final Swatch swatch : this.swatches) {
            final int side = Math.max(8, Math.min(SWATCH_SIDE, swatch.h - 6));
            final int x = swatch.x + 6;
            final int y = swatch.y + (swatch.h - side) / 2;
            // Border first, so a near-black colour still reads as a square on the button.
            context.fill(x - 1, y - 1, x + side + 1, y + side + 1, 0xFF000000);
            context.fill(x, y, x + side, y + side, 0xFF000000 | (swatch.rgb & 0xFFFFFF));
            final int textRight = swatch.x + swatch.w - 6;
            drawCentered(context, new LiteralText(HeadlightConfig.formatColor(swatch.rgb)),
                    (x + side + textRight) / 2, swatch.y + (swatch.h - 8) / 2, 0xFFE0E0E0);
        }
    }

    /** Thin position bar on the right edge of the block; nothing is drawn when it all fits. */
    private void drawScrollBar(final DrawContext context) {
        if (this.rows.size() <= this.visibleRows) {
            return;
        }
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

    /** ESC = 返回分区: one level up, never closes the whole flow. */
    @Override
    public void close() {
        closeToParent();
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
            return new Row(ROW_TOGGLE, toggle, value, false, false, LiteralText.EMPTY, null);
        }

        /** 只有开关、右边没有滑块的一行（光柱 / 车体碰撞 / 道床 / 内置车型屏蔽 …）。 */
        static Row toggleWide(final int toggle) {
            return new Row(ROW_TOGGLE_WIDE, toggle, -1, false, false, LiteralText.EMPTY, null);
        }

        static Row value(final int value) {
            return new Row(ROW_VALUE, -1, value, false, false, LiteralText.EMPTY, null);
        }

        static Row color(final boolean head) {
            return new Row(ROW_COLOR, -1, -1, head, false, LiteralText.EMPTY, null);
        }

        static Row action(final Text label, final Runnable action) {
            return new Row(ROW_ACTION, -1, -1, false, false, label, action);
        }

        static Row worldAction(final Text label, final Runnable action) {
            return new Row(ROW_ACTION, -1, -1, false, true, label, action);
        }

        /** 三态值一行（车灯模式），按钮整行宽。 */
        static Row mode() {
            return new Row(ROW_CYCLE, -1, ConfigWriteTarget.HEADLIGHT_MODE, false, false,
                    LiteralText.EMPTY, null);
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
            super(x, y, w, h, LiteralText.EMPTY, normalize(current, minOf(which), maxOf(which)));
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
                    : new LiteralText(formatValue(this.which, actual())));
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
