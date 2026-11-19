// 时钟配置界面：开关在上、滑块在下；颜色走 ColorPickerScreen；音效 id 可搜索；每行一个 ↺。
package dev.js.mtrheadlight.clock;

import dev.js.mtrheadlight.ColorPickerScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * 时钟（{@code mtrlte:clock}）的配置界面，刷子右键打开。
 *
 * <h2>两种模式</h2>
 * <ul>
 *   <li><b>单块模式</b>（{@code pos != null}）：改这一块钟自己的配置，底部「完成」走
 *       {@link ClockNet#savePacket}；</li>
 *   <li><b>全局模式</b>（{@code pos == null}，由仪表板「其他 → 时钟全局设置」进来）：<b>界面完全一样</b>，
 *       只是多一行「全局启用」，并且「完成」直接把整份配置写成<b>全局配置</b>（盖住所有时钟，
 *       见 {@link ClockGlobalNet}）。取消什么都不发。</li>
 * </ul>
 *
 * <h2>布局（用户点名：开关在上、滑块在下）</h2>
 * <pre>
 *   第 0–2 行：时间源 / 发光 / 刻度样式 / 整点报时 / 报时模式 / 红石控制   ← 开关与选项
 *   第 3–5 行：钟面 / 时针 / 分针 / 秒针 / 中心轴 的显隐                  ← 开关
 *   第 6–10 行：时针 / 分针 / 秒针长度、钟面半径、刻度半径                ← 滑块
 *   第 11 行 ：时间偏移（分钟） + 「应用」 + 当前显示 HH:MM
 *   第 12–14 行：六个颜色（钟面 / 时针 / 分针 / 秒针 / 中心轴 / 刻度）
 *   第 15 行 ：音效搜索 + 试听
 *   第 16–17 行：音效列表（可滚动、点一下就是选中）
 *   底部    ：全部复原 / 完成 / 取消
 * </pre>
 * 全局模式下所有行整体下移一行，第 0 行是「全局启用：开 / 关」。
 *
 * <h2>滚动（18 行 + 一行全局 = 19 行，小窗口放不下）</h2>
 * <p>行高先按窗口高度自动算（{@value #MIN_ROW_H}–{@value #MAX_ROW_H}px），算到下限仍放不下时
 * <b>整行滚动</b>：滚轮在面板内滚动、一次一行，右侧画一条细滚动条，底部三个按钮固定在面板
 * 底部不跟着滚。滚动只改 {@link #row(int)} 里的行偏移，重建控件时把输入框里没提交的字留住
 * （{@link #offsetText} / {@code soundText}），所以滚一下不会把正在敲的音效 id 弄丢。
 * 视口外的行不加控件 —— 看不见的东西点不到，也不会画出面板。
 *
 * <p><b>底部栏必须永远看得见</b>（2026-11-19 修）：内容区 = 屏幕高 − 顶部标题 − 底部栏，
 * 滚动范围按内容实际高度算；底部三颗按钮是 {@link #footerButtons}，在
 * {@link #drawFooter} 里<b>于滚动裁剪之外</b>再画一遍。以前它们只出现在
 * {@code super.render()} 那一遍里，而那一遍被 {@code bodyTop…bodyBottom} 的 scissor 包着 ——
 * 按钮整颗落在裁剪区外，于是「加了一堆行之后底部按钮消失、存不了」。
 *
 * <h2>每行一个 ↺（复原）</h2>
 * <p>每一行最右边都有一个方形 ↺（{@code 18px}，与 {@code ParameterScreen} 的复位列同一个做法）：
 * 点一下只把这<b>一行</b>的字段写回 {@link #defaults 出厂值}（{@code new ClockConfig()} 的字段初值）。
 * 一行放两个参数时（例如「时间源 | 发光」）两个一起恢复 —— 和 {@code ParameterScreen} 对
 * 「一行两个参数」的定义一致。
 * 底部「全部复原」把整份配置一次写回出厂值（全局模式下连「启用」也回到关）。
 * 复原只改界面里的副本，和普通修改一样要按「完成」才发给服务端。
 *
 * <h2>音效列表</h2>
 * <p>来源是 {@code Registries.SOUND_EVENT}（原版 + 所有模组的音效，客户端已同步），
 * 搜索框按 id 过滤。默认 {@code minecraft:block.bell.use}。点列表里的一行就是选中它；
 * 在搜索框里直接敲一个<b>注册表里存在</b>的 id 再点完成也算（{@link #saveAndClose} 会校验）。
 *
 * <h2>颜色</h2>
 * <p>复用 {@link ColorPickerScreen}：点颜色按钮 → 选色板 / 直接输 {@code #RRGGBB} → 完成后
 * 回调写回本界面里的配置副本（还没发给服务端，等服务端收到「完成」才算数）。
 */
public final class ClockConfigScreen extends Screen {

    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    /** 面板最大宽度 / 一行的最高高度。 */
    private static final int MAX_PANEL_W = 380;
    private static final int MAX_ROW_H = 22;
    private static final int MIN_ROW_H = 16;
    /** 单块模式的固定行数（见类注释的布局表）。 */
    private static final int ROWS = 18;
    /** 面板顶部标题条高度（标题 + 一点留白）。 */
    private static final int HEADER_H = 26;
    /** 面板底部固定区：一行「报时音效：xxx」提示 + 三个按钮。 */
    private static final int FOOTER_H = 38;
    private static final int PAD = 8;
    /** 每行末尾 ↺ 的宽度（和行高一个量级的方形按钮）。 */
    private static final int RESET_W = 18;
    /** ↺ 和内容之间的缝。 */
    private static final int GAP = 4;

    /** 这一块钟的坐标；{@code null} = 全局模式（改所有钟）。 */
    private final BlockPos pos;
    /** 全局模式：改的是全局配置，完成时发 {@link ClockGlobalNet}。 */
    private final boolean global;
    /** 配置副本（界面里改的就是它）。 */
    private final ClockConfig cfg;
    /** 全局模式的「全局启用」开关（单块模式不用）。 */
    private boolean enabled;
    /** 出厂值：每一行 ↺ / 全部复原 取的默认值。 */
    private final ClockConfig defaults = new ClockConfig();
    /** 全部音效 id（排一次序，之后只过滤）。 */
    private final List<Identifier> allSounds = new ArrayList<>();
    /** 过滤后的可见音效。 */
    private final List<Identifier> viewSounds = new ArrayList<>();
    /** 颜色按钮左边的色块（render 里按 {@link IntSupplier} 取当前值）。 */
    private final List<Swatch> swatches = new ArrayList<>();
    /**
     * 底部那条<b>固定</b>的按钮栏（全部复原 / 完成 / 取消）。
     *
     * <p>它们和其余控件一样是 {@code children}（这样点击 / 键盘焦点照旧），但
     * {@link #render} 里要<b>在滚动裁剪之外再画一遍</b> —— 见那里的注释：{@code bodyBottom}
     * 之下就是裁剪边界，底部栏落在裁剪区域外，被 scissor 整块切掉，于是「看不见也点不到」。
     */
    private final List<ButtonWidget> footerButtons = new ArrayList<>();

    private TextFieldWidget offsetField;
    private TextFieldWidget clockField;
    private TextFieldWidget soundField;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    /** 滚动的可见区域（行在里面才建控件 / 才画）；底部三个按钮在 {@link #bodyBottom} 之下，固定。 */
    private int bodyTop;
    private int bodyBottom;
    /** 已滚过的行数（整行滚动，0 = 最上面）。 */
    private int scrollRows;
    /** 两个输入框里「还没提交」的文字：滚动重建控件时留住它们。 */
    private String offsetText;
    private String soundText;
    /** 两个参数格各自的宽度（↺ 列已经从总宽里扣掉）。 */
    private int colW;
    /** 一行内容（不含 ↺ 列）的宽度。 */
    private int contentW;
    private int rowH;
    /** 全局模式比单块模式多一行（「全局启用」），其余行的 y 整体下移。 */
    private int rowOffset;
    private int rowCount;
    /** 音效列表滚动量（像素）。 */
    private int soundScroll;

    private ButtonWidget sourceButton;
    private ButtonWidget glowButton;
    private ButtonWidget tickButton;
    private ButtonWidget chimeButton;
    private ButtonWidget chimeModeButton;
    private ButtonWidget redstoneButton;
    private ButtonWidget enabledButton;

    /** 单块模式：改这一块钟自己的配置。 */
    public ClockConfigScreen(final BlockPos pos, final ClockConfig config) {
        super(Text.translatable("screen.mtrlte.clock"));
        this.pos = pos;
        this.global = pos == null;
        this.cfg = config == null ? new ClockConfig() : config.copy();
        this.cfg.clamp();
        this.enabled = false;
        this.allSounds.addAll(Registries.SOUND_EVENT.getIds());
        this.allSounds.sort(Comparator.comparing(Identifier::toString));
        this.refreshSounds();
    }

    /** 全局模式：改所有时钟的那一份（{@code pos == null}）。 */
    public ClockConfigScreen(final BlockPos pos, final ClockGlobalConfig config) {
        super(Text.translatable(pos == null
                ? "screen.mtrlte.clock.globalTitle" : "screen.mtrlte.clock"));
        this.pos = pos;
        this.global = pos == null;
        final ClockGlobalConfig source = config == null ? new ClockGlobalConfig() : config;
        this.cfg = source.config == null ? new ClockConfig() : source.config.copy();
        this.cfg.clamp();
        this.enabled = source.enabled;
        this.allSounds.addAll(Registries.SOUND_EVENT.getIds());
        this.allSounds.sort(Comparator.comparing(Identifier::toString));
        this.refreshSounds();
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void init() {
        // 重建（滚动 / 复原）时把输入框里还没提交的字留住：旧控件这会儿已经从 children 里摘掉了，
        // 但对象还在字段上，读它的文本正好。
        if (this.offsetField != null) {
            this.offsetText = this.offsetField.getText();
        }
        if (this.soundField != null) {
            this.soundText = this.soundField.getText();
        }
        if (this.offsetText == null) {
            this.offsetText = formatOffset(this.cfg.offsetMinutes);
        }
        if (this.soundText == null) {
            this.soundText = this.cfg.chimeSound;
        }

        this.rowOffset = this.global ? 1 : 0;
        this.rowCount = ROWS + this.rowOffset;

        this.panelW = Math.min(MAX_PANEL_W, this.width - 16);
        // 内容区高度 = 屏幕高 − 顶部标题 − 底部固定栏（行高按这个可用高度反算，
        // 算到 MIN_ROW_H 仍放不下就整行滚动；面板本身最多占满屏幕）。
        final int available = Math.max(0, this.height - 4 - HEADER_H - FOOTER_H);
        this.rowH = clamp(available / Math.max(1, this.rowCount), MIN_ROW_H, MAX_ROW_H);
        // 面板至少放得下「一行 + 标题 + 底部栏」；内容再多就靠滚动，绝不让底部栏被顶出去。
        final int minPanelH = HEADER_H + FOOTER_H + this.rowH;
        this.panelH = Math.min(this.height - 4,
                Math.max(minPanelH, HEADER_H + this.rowCount * this.rowH + FOOTER_H));
        this.panelY = Math.max(2, (this.height - this.panelH) / 2);
        this.panelX = (this.width - this.panelW) / 2;
        this.bodyTop = this.panelY + HEADER_H;
        this.bodyBottom = this.panelY + this.panelH - FOOTER_H;
        this.scrollRows = clamp(this.scrollRows, 0, this.maxScrollRows());
        // ↺ 列从总宽里扣：内容永远不会压在 ↺ 上（rightX() + colW + GAP == resetX()）。
        this.contentW = this.panelW - 2 * PAD - RESET_W - GAP;
        this.colW = (this.contentW - GAP) / 2;

        this.swatches.clear();
        final int left = this.panelX + PAD;
        final int right = left + this.colW + GAP;
        final int wh = this.rowH - 4;

        // ---- 全局模式最上面一行：全局启用（整行一个开关，带自己的 ↺） ----
        // row(-1) 就是「第 0 个视觉行」（row(i) 会把 i 加上 rowOffset），不和「时间源 / 发光」撞。
        if (this.global) {
            this.enabledButton = cycle(left, row(-1), this.contentW, wh,
                    () -> Text.translatable("screen.mtrlte.clock.global.enabled", onOff(this.enabled)),
                    () -> this.enabled = !this.enabled);
            this.resetButton(row(-1), wh, () -> this.enabled = false);
        }

        // ---- 第 0 行：时间源 / 发光 -------------------------------------
        this.sourceButton = cycle(left, row(0), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.source",
                        Text.translatable(this.cfg.timeSource.labelKey())),
                () -> this.cfg.timeSource = next(ClockConfig.TimeSource.values(), this.cfg.timeSource));
        this.glowButton = cycle(right, row(0), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.glow",
                        Text.translatable(this.cfg.glow.labelKey())),
                () -> this.cfg.glow = next(ClockConfig.GlowMode.values(), this.cfg.glow));
        this.resetButton(row(0), wh, () -> {
            this.cfg.timeSource = this.defaults.timeSource;
            this.cfg.glow = this.defaults.glow;
        });

        // ---- 第 1 行：刻度样式 / 整点报时 -------------------------------
        this.tickButton = cycle(left, row(1), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.tick",
                        Text.translatable(this.cfg.tickStyle.labelKey())),
                () -> this.cfg.tickStyle = next(ClockConfig.TickStyle.values(), this.cfg.tickStyle));
        this.chimeButton = cycle(right, row(1), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.chime", onOff(this.cfg.chime)),
                () -> this.cfg.chime = !this.cfg.chime);
        this.resetButton(row(1), wh, () -> {
            this.cfg.tickStyle = this.defaults.tickStyle;
            this.cfg.chime = this.defaults.chime;
        });

        // ---- 第 2 行：报时模式 / 红石控制 -------------------------------
        this.chimeModeButton = cycle(left, row(2), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.chimeMode",
                        Text.translatable(this.cfg.chimeMode.labelKey())),
                () -> this.cfg.chimeMode = next(ClockConfig.ChimeMode.values(), this.cfg.chimeMode));
        this.redstoneButton = cycle(right, row(2), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.redstone", onOff(this.cfg.redstone)),
                () -> this.cfg.redstone = !this.cfg.redstone);
        this.resetButton(row(2), wh, () -> {
            this.cfg.chimeMode = this.defaults.chimeMode;
            this.cfg.redstone = this.defaults.redstone;
        });

        // ---- 第 3–5 行：五个显隐开关（开关行，全部在滑块之前） -------------
        // 钟面 / 时针、分针 / 秒针、中心轴（中心轴独占一行，右半栏留空）。
        this.cycle(left, row(3), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.showFace", onOff(this.cfg.showFace)),
                () -> this.cfg.showFace = !this.cfg.showFace);
        this.cycle(right, row(3), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.showHour", onOff(this.cfg.showHour)),
                () -> this.cfg.showHour = !this.cfg.showHour);
        this.resetButton(row(3), wh, () -> {
            this.cfg.showFace = this.defaults.showFace;
            this.cfg.showHour = this.defaults.showHour;
        });
        this.cycle(left, row(4), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.showMinute", onOff(this.cfg.showMinute)),
                () -> this.cfg.showMinute = !this.cfg.showMinute);
        this.cycle(right, row(4), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.showSecond", onOff(this.cfg.showSecond)),
                () -> this.cfg.showSecond = !this.cfg.showSecond);
        this.resetButton(row(4), wh, () -> {
            this.cfg.showMinute = this.defaults.showMinute;
            this.cfg.showSecond = this.defaults.showSecond;
        });
        this.cycle(left, row(5), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.showHub", onOff(this.cfg.showHub)),
                () -> this.cfg.showHub = !this.cfg.showHub);
        this.cycle(right, row(5), this.colW, wh,
                () -> Text.translatable("screen.mtrlte.clock.row.showTicks", onOff(this.cfg.showTicks)),
                () -> this.cfg.showTicks = !this.cfg.showTicks);
        this.resetButton(row(5), wh, () -> {
            this.cfg.showHub = this.defaults.showHub;
            this.cfg.showTicks = this.defaults.showTicks;
        });

        // ---- 第 6–10 行：三根指针的长度 + 钟面半径 + 刻度半径（滑块，右边各一个 ↺） ----
        this.lengthSlider(row(6), wh, "screen.mtrlte.clock.row.hourLen", this.cfg.hourLength,
                0.20F, v -> this.cfg.hourLength = v, () -> this.cfg.hourLength = this.defaults.hourLength);
        this.lengthSlider(row(7), wh, "screen.mtrlte.clock.row.minuteLen", this.cfg.minuteLength,
                0.28F, v -> this.cfg.minuteLength = v, () -> this.cfg.minuteLength = this.defaults.minuteLength);
        this.lengthSlider(row(8), wh, "screen.mtrlte.clock.row.secondLen", this.cfg.secondLength,
                0.33F, v -> this.cfg.secondLength = v, () -> this.cfg.secondLength = this.defaults.secondLength);
        this.lengthSlider(row(9), wh, "screen.mtrlte.clock.row.faceRadius", this.cfg.faceRadius,
                ClockConfig.DEFAULT_FACE_RADIUS, v -> this.cfg.faceRadius = v,
                () -> this.cfg.faceRadius = this.defaults.faceRadius);
        this.lengthSlider(row(10), wh, "screen.mtrlte.clock.row.tickRadius", this.cfg.tickRadius,
                ClockConfig.DEFAULT_TICK_RADIUS, v -> this.cfg.tickRadius = v,
                () -> this.cfg.tickRadius = this.defaults.tickRadius);

        // ---- 第 11 行：偏移（分钟） + 应用 / 当前显示 HH:MM ---------------
        final int applyW = 44;
        final int clockW = Math.max(60, this.contentW / 3);
        final int offsetW = this.contentW - clockW - applyW - 2 * GAP;
        if (this.rowVisible(row(11), wh)) {
            this.offsetField = new TextFieldWidget(this.textRenderer, left, row(11), offsetW, wh,
                    Text.translatable("screen.mtrlte.clock.offset"));
            this.offsetField.setMaxLength(10);
            this.offsetField.setTextPredicate(t -> t.matches("-?[0-9]{0,6}(\\.[0-9]{0,3})?"));
            this.offsetField.setText(this.offsetText);
            this.offsetField.setChangedListener(text -> {
                this.offsetText = text;
                final Double v = parseDouble(text);
                if (v != null) {
                    this.cfg.offsetMinutes = v.doubleValue();
                }
            });
            this.addDrawableChild(this.offsetField);

            this.addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mtrlte.clock.apply"),
                            b -> this.applyClockText())
                    .dimensions(left + offsetW + GAP, row(11), applyW, wh)
                    .build());

            this.clockField = new TextFieldWidget(this.textRenderer, left + offsetW + applyW + 2 * GAP,
                    row(11), clockW, wh, Text.translatable("screen.mtrlte.clock.clockField"));
            this.clockField.setMaxLength(5);
            this.clockField.setTextPredicate(t -> t.matches("[0-9]{0,2}:?[0-9]{0,2}"));
            this.clockField.setPlaceholder(Text.translatable("screen.mtrlte.clock.clockHint"));
            this.addDrawableChild(this.clockField);
        } else {
            this.offsetField = null;
            this.clockField = null;
        }
        this.resetButton(row(11), wh, () -> {
            this.cfg.offsetMinutes = this.defaults.offsetMinutes;
            this.offsetText = formatOffset(this.defaults.offsetMinutes);
            if (this.offsetField != null) {
                this.offsetField.setText(this.offsetText);
            }
        });

        // ---- 第 12–14 行：六个颜色（左半栏 / 右半栏各一个） ----------------
        colorCell(left, row(12), wh, "screen.mtrlte.clock.color.face",
                () -> this.cfg.faceColor, v -> this.cfg.faceColor = v);
        colorCell(right, row(12), wh, "screen.mtrlte.clock.color.hour",
                () -> this.cfg.hourColor, v -> this.cfg.hourColor = v);
        this.resetButton(row(12), wh, () -> {
            this.cfg.faceColor = this.defaults.faceColor;
            this.cfg.hourColor = this.defaults.hourColor;
        });
        colorCell(left, row(13), wh, "screen.mtrlte.clock.color.minute",
                () -> this.cfg.minuteColor, v -> this.cfg.minuteColor = v);
        colorCell(right, row(13), wh, "screen.mtrlte.clock.color.second",
                () -> this.cfg.secondColor, v -> this.cfg.secondColor = v);
        this.resetButton(row(13), wh, () -> {
            this.cfg.minuteColor = this.defaults.minuteColor;
            this.cfg.secondColor = this.defaults.secondColor;
        });
        colorCell(left, row(14), wh, "screen.mtrlte.clock.color.hub",
                () -> this.cfg.hubColor, v -> this.cfg.hubColor = v);
        colorCell(right, row(14), wh, "screen.mtrlte.clock.color.tick",
                () -> this.cfg.tickColor, v -> this.cfg.tickColor = v);
        this.resetButton(row(14), wh, () -> {
            this.cfg.hubColor = this.defaults.hubColor;
            this.cfg.tickColor = this.defaults.tickColor;
        });

        // ---- 第 15 行：音效搜索 + 试听 ---------------------------------
        final int previewW = 44;
        if (this.rowVisible(row(15), wh)) {
            this.soundField = new TextFieldWidget(this.textRenderer, left, row(15),
                    this.contentW - previewW - GAP, wh,
                    Text.translatable("screen.mtrlte.clock.sound.search"));
            this.soundField.setMaxLength(64);
            this.soundField.setText(this.soundText);
            this.soundField.setChangedListener(text -> {
                this.soundText = text;
                this.soundScroll = 0;
                this.refreshSounds();
            });
            this.addDrawableChild(this.soundField);
            this.addDrawableChild(ButtonWidget.builder(Text.translatable("screen.mtrlte.clock.preview"),
                            b -> this.preview())
                    .dimensions(left + this.contentW - previewW, row(15), previewW, wh)
                    .build());
        } else {
            this.soundField = null;
        }
        this.resetButton(row(15), wh, () -> {
            this.cfg.chimeSound = this.defaults.chimeSound;
            this.soundText = this.defaults.chimeSound;
            if (this.soundField != null) {
                this.soundField.setText(this.soundText);
            }
        });

        // ---- 底部：全部复原 / 完成 / 取消 -------------------------------
        // 这三颗钉在面板底部（bodyBottom 之下），是**固定栏**：不随内容滚动。
        // 注意 render() 会在滚动裁剪之外把它们再画一遍 —— 少了那一步就是「按钮被裁掉、点不到」。
        this.footerButtons.clear();
        final int by = this.panelY + this.panelH - 24;
        final int right0 = this.panelX + this.panelW - PAD;
        final int bw = Math.max(56, Math.min(80, (this.contentW - 100) / 2));
        final ButtonWidget done = ButtonWidget.builder(Text.translatable("btn.done"), b -> this.saveAndClose())
                .dimensions(right0 - bw, by, bw, 20).build();
        final ButtonWidget cancel = ButtonWidget.builder(Text.translatable("btn.cancel"), b -> this.close())
                .dimensions(right0 - 2 * bw - GAP, by, bw, 20).build();
        final ButtonWidget resetAll = ButtonWidget.builder(Text.translatable("btn.resetAll"), b -> this.resetAll())
                .dimensions(this.panelX + PAD, by, Math.max(70, this.contentW - 2 * bw - 3 * GAP), 20)
                .build();
        this.addDrawableChild(done);
        this.addDrawableChild(cancel);
        this.addDrawableChild(resetAll);
        this.footerButtons.add(resetAll);
        this.footerButtons.add(cancel);
        this.footerButtons.add(done);

        this.refreshSounds();
    }

    /** 第 {@code index} 行的 y（全局模式下整体下移一行，「全局启用」是第 0 行；再减去滚过的行）。 */
    private int row(final int index) {
        return this.bodyTop + (index + this.rowOffset - this.scrollRows) * this.rowH;
    }

    /** 面板里一次能放下几整行（滚动时用来夹 {@link #scrollRows}）。 */
    private int visibleRows() {
        return Math.max(1, (this.bodyBottom - this.bodyTop) / Math.max(1, this.rowH));
    }

    /** 最多能滚几行；0 = 这一屏放得下，不滚。 */
    private int maxScrollRows() {
        return Math.max(0, this.rowCount - this.visibleRows());
    }

    /**
     * 这一行现在在视口里吗？不在就不建控件 —— 看不见的东西不该能点到，也不该画到面板外。
     * （滚动是整行的，所以「在不在」只有两种答案，不会出现半行。）
     */
    private boolean rowVisible(final int y, final int h) {
        return y >= this.bodyTop && y + h <= this.bodyBottom;
    }

    /** 每行末尾 ↺ 的 x：贴着面板右边缘。 */
    private int resetX() {
        return this.panelX + this.panelW - PAD - RESET_W;
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 一行的 ↺：点一下把这行的字段写回出厂值，然后重建控件（滑块位置 / 按钮文字都要跟着变）。 */
    private void resetButton(final int y, final int h, final Runnable reset) {
        if (!this.rowVisible(y, h)) {
            return;
        }
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("btn.resetOne"), b -> {
            reset.run();
            this.requestRebuild();
        }).dimensions(resetX(), y, RESET_W, h).build());
    }

    /**
     * 重建控件（滑块位置、按钮上的当前值都要跟着配置走）。
     *
     * <p><b>不在点击回调里直接 {@code clearAndInit()} </b>：这个按钮是在
     * {@code Screen#mouseClicked} 遍历 {@code children()} 的过程中被调到的，原地 clear 会动到正在遍历
     * 的那个列表。丢给任务队列，这一帧画完再重建 —— 玩家看到的差别只有「一帧之后滑块才到位」。
     */
    private void requestRebuild() {
        if (this.client == null) {
            return;
        }
        this.client.send(() -> {
            if (this.client != null && this.client.currentScreen == this) {
                this.clearAndInit();
            }
        });
    }

    /**
     * 循环按钮：点一下换下一个值，并把按钮文字（含当前值）刷掉。
     *
     * <p>文字用一个 {@code Supplier} 现拼 —— 值改完之后不用记「哪个按钮对应哪个字段」，
     * 反正每次都按当前配置重新生成。
     *
     * @return 建好的按钮；整行滚出视口时返回 {@code null}（没建控件）
     */
    private ButtonWidget cycle(final int x, final int y, final int w, final int h,
                               final java.util.function.Supplier<Text> label, final Runnable advance) {
        if (!this.rowVisible(y, h)) {
            return null;
        }
        final ButtonWidget[] self = new ButtonWidget[1];
        final ButtonWidget button = ButtonWidget.builder(label.get(), b -> {
            advance.run();
            self[0].setMessage(label.get());
        }).dimensions(x, y, w, h).build();
        self[0] = button;
        this.addDrawableChild(button);
        return button;
    }

    /**
     * 长度 / 半径滑块 + 它自己的 ↺（滑块占满内容宽，↺ 在复位列）。
     *
     * @param fallback 只用来在数值坏掉时给标签显示一个合理值（不影响真正写回的值）
     */
    private void lengthSlider(final int y, final int h, final String labelKey, final float initial,
                              final float fallback, final FloatSetter setter, final Runnable reset) {
        if (!this.rowVisible(y, h)) {
            return;
        }
        this.addDrawableChild(new LengthSlider(this.panelX + PAD, y, this.contentW, h, labelKey,
                initial, fallback, setter));
        this.resetButton(y, h, reset);
    }

    /** 一个颜色格子：左边 10px 色块 + 右边按钮，点按钮开取色界面。 */
    private void colorCell(final int x, final int y, final int h, final String key,
                           final IntSupplier current, final IntConsumer setter) {
        if (!this.rowVisible(y, h)) {
            return;
        }
        final int swatchW = 14;
        this.swatches.add(new Swatch(x, y + (h - 10) / 2, current));
        this.addDrawableChild(ButtonWidget.builder(Text.translatable(key),
                        b -> this.openColor(key, current.getAsInt(), setter))
                .dimensions(x + swatchW, y, this.colW - swatchW, h)
                .build());
    }

    /** 打开取色界面，完成后写回配置副本（色块下一次 render 就是新颜色）。 */
    private void openColor(final String key, final int rgb, final IntConsumer setter) {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new ColorPickerScreen(this, Text.translatable(key).getString(), rgb,
                setter::accept));
    }

    /** 全部复原：整份配置写回出厂值（全局模式连「启用」一起回到关）。 */
    private void resetAll() {
        this.cfg.timeSource = this.defaults.timeSource;
        this.cfg.glow = this.defaults.glow;
        this.cfg.hourLength = this.defaults.hourLength;
        this.cfg.minuteLength = this.defaults.minuteLength;
        this.cfg.secondLength = this.defaults.secondLength;
        this.cfg.faceRadius = this.defaults.faceRadius;
        this.cfg.tickRadius = this.defaults.tickRadius;
        this.cfg.showFace = this.defaults.showFace;
        this.cfg.showHour = this.defaults.showHour;
        this.cfg.showMinute = this.defaults.showMinute;
        this.cfg.showSecond = this.defaults.showSecond;
        this.cfg.showHub = this.defaults.showHub;
        this.cfg.faceColor = this.defaults.faceColor;
        this.cfg.hourColor = this.defaults.hourColor;
        this.cfg.minuteColor = this.defaults.minuteColor;
        this.cfg.secondColor = this.defaults.secondColor;
        this.cfg.hubColor = this.defaults.hubColor;
        this.cfg.tickColor = this.defaults.tickColor;
        this.cfg.chime = this.defaults.chime;
        this.cfg.chimeSound = this.defaults.chimeSound;
        this.cfg.chimeMode = this.defaults.chimeMode;
        this.cfg.tickStyle = this.defaults.tickStyle;
        this.cfg.redstone = this.defaults.redstone;
        this.cfg.offsetMinutes = this.defaults.offsetMinutes;
        this.offsetText = formatOffset(this.defaults.offsetMinutes);
        this.soundText = this.defaults.chimeSound;
        // 顺手把输入框也刷掉：重建时 init() 会从控件读文本，只改字段的话会被旧文本盖回去。
        if (this.offsetField != null) {
            this.offsetField.setText(this.offsetText);
        }
        if (this.soundField != null) {
            this.soundField.setText(this.soundText);
        }
        this.enabled = false;
        this.requestRebuild();
    }

    // ------------------------------------------------------------------ 校准

    /** 「当前显示 HH:MM」：把钟面拨到这一刻，反推出偏移。 */
    private void applyClockText() {
        final String text = this.clockField == null ? "" : this.clockField.getText().trim();
        final String[] parts = text.split(":");
        if (parts.length != 2) {
            return;
        }
        final Integer h = parseInt(parts[0]);
        final Integer m = parseInt(parts[1]);
        if (h == null || m == null || h.intValue() < 0 || h.intValue() > 23
                || m.intValue() < 0 || m.intValue() > 59) {
            return;
        }
        final double target = h.intValue() * 3600.0D + m.intValue() * 60.0D;
        final double now = ClockTime.secondsOfDay(this.client == null ? null : this.client.world,
                this.cfg.timeSource, 0.0D);
        double delta = ClockTime.wrap(target - now);
        if (delta > ClockTime.DAY_SECONDS / 2.0D) {
            delta -= ClockTime.DAY_SECONDS;
        }
        this.cfg.offsetMinutes = delta / 60.0D;
        this.cfg.clamp();
        if (this.offsetField != null) {
            this.offsetField.setText(formatOffset(this.cfg.offsetMinutes));
        }
    }

    private static String formatOffset(final double minutes) {
        return String.format(Locale.ROOT, "%.1f", minutes);
    }

    private static Double parseDouble(final String text) {
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(final String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 音效

    private void refreshSounds() {
        final String q = this.soundField == null ? ""
                : this.soundField.getText().trim().toLowerCase(Locale.ROOT);
        this.viewSounds.clear();
        for (final Identifier id : this.allSounds) {
            if (q.isEmpty() || id.toString().toLowerCase(Locale.ROOT).contains(q)) {
                this.viewSounds.add(id);
            }
        }
        this.soundScroll = Math.max(0, Math.min(this.soundScroll, this.maxSoundScroll()));
    }

    private int soundRowH() {
        return Math.max(10, this.rowH / 2);
    }

    private int soundListH() {
        // 比两行行高少 12px：给底部「报时音效：xxx」那一行留出位置，别让文字压到列表上。
        return this.rowH * 2 - 12;
    }

    private int maxSoundScroll() {
        return Math.max(0, this.viewSounds.size() * this.soundRowH() - this.soundListH());
    }

    /** 搜索框里直接敲的 id 也认（必须在注册表里），否则保留当前选中的那个。 */
    private void acceptTypedSound() {
        if (this.soundField == null) {
            return;
        }
        final Identifier id = Identifier.tryParse(this.soundField.getText().trim());
        if (id != null && Registries.SOUND_EVENT.containsId(id)) {
            this.cfg.chimeSound = id.toString();
        }
    }

    // ------------------------------------------------------------------ 保存 / 试听

    private void saveAndClose() {
        this.acceptTypedSound();
        this.cfg.clamp();
        if (this.global) {
            // 全局：整份 + 启用开关一起发（服务端校验权限 ≥ 2）。
            ClockClient.sendGlobalSave(new ClockGlobalConfig(this.cfg, this.enabled));
        } else if (this.pos != null) {
            ClockClient.sendSave(this.pos, this.cfg);
        }
        this.close();
    }

    /** 「试听」：先落一次配置再放音 —— 不然听到的还是改之前的那个音效。 */
    private void preview() {
        this.acceptTypedSound();
        this.cfg.clamp();
        if (this.global) {
            // 全局没有方块可站：服务端在玩家自己的位置播。
            ClockClient.sendGlobalSave(new ClockGlobalConfig(this.cfg, this.enabled));
            ClockClient.sendGlobalPreview();
        } else {
            ClockClient.sendSave(this.pos, this.cfg);
            ClockClient.sendPreview(this.pos);
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        context.fill(this.panelX, this.panelY, this.panelX + this.panelW,
                this.panelY + this.panelH, 0xE0141414);
        context.drawBorder(this.panelX, this.panelY, this.panelW, this.panelH, 0xFF6E6E6E);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2,
                this.panelY + 8, 0xFFFFFF);

        // 行内容整体裁在 body 里：滚出去的控件既画不出面板，也不会压到标题 / 底部按钮上。
        context.enableScissor(this.panelX + 1, this.bodyTop, this.panelX + this.panelW - 1,
                Math.max(this.bodyTop, this.bodyBottom));
        super.render(context, mouseX, mouseY, delta);

        // 色块（按钮左边那一小格，颜色实时跟着配置走）
        for (final Swatch s : this.swatches) {
            if (s.y + 10 < this.bodyTop || s.y > this.bodyBottom) {
                continue;
            }
            context.fill(s.x, s.y, s.x + 10, s.y + 10, 0xFF000000 | (s.color.getAsInt() & 0xFFFFFF));
            context.drawBorder(s.x, s.y, 10, 10, 0xFF000000);
        }

        this.drawSoundList(context, mouseX, mouseY);
        context.disableScissor();

        this.drawScrollBar(context);
        this.drawFooter(context, mouseX, mouseY, delta);
    }

    /**
     * 底部固定栏：先铺一条不透明的底（把可能漏出来的行内容盖住），再画「报时音效」提示行，
     * 最后把三颗按钮<b>在裁剪区域之外重画一遍</b>。
     *
     * <p>为什么必须重画：{@code super.render()} 那一遍是在 {@code bodyTop…bodyBottom} 的
     * scissor 里跑的，而底部栏整颗都在 {@code bodyBottom} 之下 —— 于是「完成 / 取消」被
     * 整块裁掉，屏幕上只剩一条空面板，按不到也就存不了。这一遍没有 scissor，按钮才真的可见。
     * （两遍不会叠色：被裁掉的那一遍一点像素都没落。）
     */
    private void drawFooter(final DrawContext context, final int mouseX, final int mouseY,
                            final float delta) {
        final int top = this.panelY + this.panelH - FOOTER_H;
        final int bottom = this.panelY + this.panelH;
        context.fill(this.panelX + 1, top, this.panelX + this.panelW - 1, bottom - 1, 0xF0141414);
        // 底部提示：当前选中的报时音效
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("screen.mtrlte.clock.sound.current", this.cfg.chimeSound),
                this.width / 2, top + 4, 0xB0B0B0);
        for (final ButtonWidget button : this.footerButtons) {
            button.render(context, mouseX, mouseY, delta);
        }
    }

    /** 右上角那条细滚动条（只有放不下时才画）：长度 = 视口占比，位置 = 滚到哪儿了。 */
    private void drawScrollBar(final DrawContext context) {
        final int max = this.maxScrollRows();
        if (max <= 0) {
            return;
        }
        final int x = this.panelX + this.panelW - PAD + 3;
        final int top = this.bodyTop;
        final int h = this.bodyBottom - this.bodyTop;
        if (h <= 0) {
            return;
        }
        final int total = this.rowCount;
        final int barH = Math.max(12, h * this.visibleRows() / total);
        final int barY = top + (h - barH) * this.scrollRows / max;
        context.fill(x, top, x + 2, top + h, 0x40FFFFFF);
        context.fill(x, barY, x + 2, barY + barH, 0xFFBFBFBF);
    }

    /** 音效列表的顶 y（第 16 行）—— 只在视口里才画。 */
    private int soundListTop() {
        return row(16);
    }

    /** 音效列表的实际高度：不超过两行，也不许戳出视口。 */
    private int soundListHeight() {
        return Math.max(0, Math.min(this.soundListH(), this.bodyBottom - this.soundListTop()));
    }

    private void drawSoundList(final DrawContext context, final int mouseX, final int mouseY) {
        final int x = this.panelX + PAD;
        final int y = this.soundListTop();
        final int w = this.contentW;
        final int h = this.soundListHeight();
        final int rh = this.soundRowH();
        if (h <= 0 || y >= this.bodyBottom) {
            return;
        }

        context.fill(x, y, x + w, y + h, 0x60000000);
        context.drawBorder(x, y, w, h, 0xFF3A3A3A);

        context.enableScissor(x, y, x + w, y + h);
        int ry = y - this.soundScroll;
        for (final Identifier id : this.viewSounds) {
            if (ry + rh > y && ry < y + h) {
                final boolean hovered = mouseX >= x && mouseX < x + w
                        && mouseY >= Math.max(y, ry) && mouseY < Math.min(y + h, ry + rh);
                final boolean selected = id.toString().equals(this.cfg.chimeSound);
                if (hovered) {
                    context.fill(x, ry, x + w, ry + rh, 0x30FFFFFF);
                }
                if (selected) {
                    context.fill(x, ry, x + 2, ry + rh, 0xFF38C24E);
                }
                context.drawTextWithShadow(this.textRenderer, Text.literal(id.toString()),
                        x + 6, ry + (rh - 8) / 2, selected ? 0xFFFFFF : 0xBFBFBF);
            }
            ry += rh;
        }
        context.disableScissor();

        if (this.viewSounds.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("screen.mtrlte.clock.sound.empty"), x + w / 2,
                    y + h / 2 - 4, 0x909090);
        }
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        final int x = this.panelX + PAD;
        final int y = this.soundListTop();
        final int w = this.contentW;
        final int h = this.soundListHeight();
        if (h > 0 && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
            final int index = (int) ((mouseY - y + this.soundScroll) / this.soundRowH());
            if (index >= 0 && index < this.viewSounds.size()) {
                this.cfg.chimeSound = this.viewSounds.get(index).toString();
                this.soundText = this.cfg.chimeSound;
                if (this.soundField != null) {
                    this.soundField.setText(this.cfg.chimeSound);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        final int max = this.maxSoundScroll();
        final int x = this.panelX + PAD;
        final int y = this.soundListTop();
        if (max > 0 && mouseX >= x && mouseX < x + this.contentW
                && mouseY >= y && mouseY < y + this.soundListHeight()) {
            this.soundScroll = (int) Math.max(0, Math.min(max,
                    this.soundScroll - amount * this.soundRowH() * 3));
            return true;
        }
        // 面板放不下 18/19 行时：在面板里滚轮 = 整行滚动（先试面板，再考虑列表）。
        if (this.maxScrollRows() > 0 && mouseX >= this.panelX && mouseX < this.panelX + this.panelW
                && mouseY >= this.bodyTop && mouseY < this.bodyBottom) {
            final int before = this.scrollRows;
            final int step = amount > 0 ? 1 : -1;
            this.scrollRows = clamp(this.scrollRows + step, 0, this.maxScrollRows());
            if (this.scrollRows != before) {
                this.requestRebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ 小工具

    private static Text onOff(final boolean value) {
        return Text.translatable(value ? "screen.mtrlte.clock.on" : "screen.mtrlte.clock.off");
    }

    private static <E> E next(final E[] values, final E current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    /** 一个色块的「位置 + 当前颜色」。 */
    private static final class Swatch {
        final int x;
        final int y;
        final IntSupplier color;

        Swatch(final int x, final int y, final IntSupplier color) {
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }

    /** 长度 / 半径滑块：**对数**映射（0.1 → 50），落值按 0.1 取整（与需求里的步进一致）。 */
    private static final class LengthSlider extends SliderWidget {

        private final String labelKey;
        private final float fallback;
        private final FloatSetter setter;

        LengthSlider(final int x, final int y, final int w, final int h, final String labelKey,
                     final float initial, final float fallback, final FloatSetter setter) {
            super(x, y, w, h,
                    Text.translatable(labelKey, String.format(Locale.ROOT, "%.1f",
                            ClockConfig.snapLength(initial, fallback))),
                    toSlider(ClockConfig.snapLength(initial, fallback)));
            this.labelKey = labelKey;
            this.fallback = fallback;
            this.setter = setter;
        }

        static double toSlider(final float length) {
            return Math.log(length / ClockConfig.MIN_LENGTH)
                    / Math.log(ClockConfig.MAX_LENGTH / ClockConfig.MIN_LENGTH);
        }

        float length() {
            return ClockConfig.snapLength(
                    (float) (ClockConfig.MIN_LENGTH * Math.pow(
                            ClockConfig.MAX_LENGTH / ClockConfig.MIN_LENGTH, this.value)), this.fallback);
        }

        @Override
        protected void updateMessage() {
            if (this.labelKey == null) {
                return;
            }
            this.setMessage(Text.translatable(this.labelKey,
                    String.format(Locale.ROOT, "%.1f", this.length())));
        }

        @Override
        protected void applyValue() {
            if (this.setter != null) {
                this.setter.set(this.length());
            }
        }
    }

    /** 避免 {@code java.util.function.Consumer<Float>} 的装箱。 */
    private interface FloatSetter {
        void set(float value);
    }
}
