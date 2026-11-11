package dev.js.mtrheadlight;

import dev.js.mtrheadlight.clientgui.Widgets;

import net.minecraft.client.util.math.MatrixStack;
import dev.js.mtrheadlight.mtr.BuiltInTrains;
import net.minecraft.client.MinecraftClient;
import dev.js.mtrheadlight.clientgui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置主界面：<b>只有四个入口</b>，不再是一列滑条。
 *
 * <ol>
 *   <li><b>全局配置</b> -> {@link CategoryScreen}（全局），先选分区，分区里才是具体的行；</li>
 *   <li><b>侧线配置</b> -> {@link SidingScreen}（侧线地图 / 列表，行为不变），
 *       点一条侧线同样先进它自己的分区列表；</li>
 *   <li><b>隐藏原版列车</b> - 一个<b>即时生效</b>的开关：写 {@code disableBuiltInTrains} 之后立刻
 *       调 {@link BuiltInTrains#reloadNow()}（和 {@code /mtr_lte trains reload} 同一条路径），
 *       不需要先点「完成」才看见车厂面板的变化；</li>
 *   <li><b>重载</b> - 内置车注册表 + 光源缓存一起重建，结果打进聊天栏
 *       （按钮本身没有别的反馈渠道）。</li>
 * </ol>
 *
 * <h2>完成 / 取消 只在这一层</h2>
 * 打开时拍一份 {@link HeadlightConfig#toJson() 配置快照}。所有分区里的控件都直接改内存里的
 * 实时配置（世界里的光会立刻预览），但<b>不写盘</b>：
 * <ul>
 *   <li><b>完成</b> -> {@link ConfigWriteTarget#commit()}，写盘后回父界面（模组菜单）；</li>
 *   <li><b>取消 / ESC</b> -> {@link MtrHeadlightClient#restoreConfig(String)} 整份回滚，再回父界面 -
 *       分区页、侧线页、地图里改的一切都在这份快照之内。</li>
 * </ul>
 * 分区页和行页因此不再各自拍快照：它们只管「返回」，回滚只有一个出口，不会出现「点了三次返回，
 * 只撤销了最后一页」这种半撤销状态。
 *
 * <h2>布局</h2>
 * 竖直按钮列表，宽度按最长标签用真实字体量（CJK 9px），最窄 {@value #MIN_COLUMN}；不用任何
 * 硬编码坐标。四个入口在 240px 高的窗口里也放得下，所以这里不做滚动。
 */
public final class ConfigScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int ROW_H = 20;
    /** 20px 按钮 + 5px 间隙，和分区页 / 行页同一个节奏。 */
    private static final int PITCH = 25;
    private static final int GAP = 8;
    private static final int MIN_COLUMN = 176;
    private static final int CONTENT_TOP = 58;
    /** 底部两行：提示行 + 完成/取消。 */
    private static final int FOOTER_H = 44;
    private static final int BOTTOM_PAD = 14;

    private final Screen parent;
    /** 配置打开时的样子 - 取消整份交还。 */
    private String snapshot;
    /** 本次 init 是不是我们自己重建触发的（开关点击后重画按钮文字）。 */
    private boolean internalReinit;

    private int colX;
    private int colW;
    private int listTop;

    public ConfigScreen(final Screen parent) {
        super(tr("screen.mtrlte.config"));
        this.parent = parent;
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    // ---------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        if (this.snapshot == null && !this.internalReinit) {
            this.snapshot = MtrHeadlightClient.snapshotConfig();
        }
        this.internalReinit = false;

        final List<Text> labels = new ArrayList<>();
        labels.add(tr("btn.global"));
        labels.add(tr("btn.sidingMenu"));
        labels.add(builtInLabel());
        labels.add(tr("btn.reloadAll"));

        int need = 0;
        for (final Text label : labels) {
            need = Math.max(need, this.textRenderer.getWidth(label.getString()));
        }
        final int charW = this.textRenderer.getWidth("测");
        final int maxCol = Math.max(MIN_COLUMN, this.width - 24);
        this.colW = Math.min(maxCol, Math.max(MIN_COLUMN, need + charW * 2 + 28));
        this.colX = this.width / 2 - this.colW / 2;

        final int viewBottom = this.height - FOOTER_H - BOTTOM_PAD;
        final int listH = labels.size() * PITCH - (PITCH - ROW_H);
        this.listTop = CONTENT_TOP + Math.max(0, (viewBottom - CONTENT_TOP - listH) / 2);

        int y = this.listTop;
        // 1) 全局配置 -> 分区列表
        addButton(this.colX, y, this.colW, ROW_H, labels.get(0), b -> openGlobal());
        y += PITCH;
        // 2) 侧线配置 -> 现有侧线列表（世界外没有侧线数据，置灰 + 悬停说明）
        final boolean worldOk = MinecraftClient.getInstance().world != null;
        final ButtonWidget sidings = addButton(this.colX, y, this.colW, ROW_H, labels.get(1),
                b -> openSidings(), worldOk ? null : tr("tip.needWorld"));
        sidings.active = worldOk;
        y += PITCH;
        // 3) 隐藏原版列车：整行一个开关，点了立刻重建注册表
        addButton(this.colX, y, this.colW, ROW_H, labels.get(2), b -> toggleBuiltIn());
        y += PITCH;
        // 4) 重载：内置车 + 光源
        addButton(this.colX, y, this.colW, ROW_H, labels.get(3), b -> reloadAll());

        // 完成 / 取消
        final int cell = (this.colW - GAP) / 2;
        final int x = this.width / 2 - (cell * 2 + GAP) / 2;
        final int fy = this.height - FOOTER_H;
        addButton(x, fy, cell, ROW_H, tr("btn.done"), b -> commitAndClose());
        addButton(x + cell + GAP, fy, cell, ROW_H, tr("btn.cancel"), b -> cancelAndClose());
    }

    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        return this.addButton(x, y, w, h, label, action, null);
    }

    /**
     * 带悬停说明的按钮。1.19.2 的 tooltip 是 {@code ButtonWidget.TooltipSupplier}（建按钮时给），
     * 没有 1.19.4 的 {@code setTooltip(..)}，所以说明必须在这里一次性交进去。
     */
    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action,
                                   final Text tooltip) {
        final Widgets.Builder builder = Widgets.button(label, action).dimensions(x, y, w, h);
        if (tooltip != null) {
            builder.tooltip(tooltip);
        }
        return this.addDrawableChild(builder.build());
    }

    private MutableText builtInLabel() {
        final boolean on = MtrHeadlightClient.config().disableBuiltInTrains;
        return tr(on ? "opt.builtin.on" : "opt.builtin.off");
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void openGlobal() {
        if (this.client == null) {
            return;
        }
        // back = 这里，root = 这里：全局流程里「返回」和「返回主界面」是同一层。
        this.client.setScreen(new CategoryScreen(this, this, null));
    }

    private void openSidings() {
        if (this.client == null || MinecraftClient.getInstance().world == null) {
            return;
        }
        this.client.setScreen(new SidingScreen(this, this));
    }

    private void toggleBuiltIn() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.disableBuiltInTrains = !cfg.disableBuiltInTrains;
        // 即时生效：不等 tick 的冷却，也不等「完成」写盘。
        BuiltInTrains.reloadNow();
        reinit();
    }

    /** 「重载」：内置车型注册表 + 光源缓存，结果写进聊天栏（按钮没有别的反馈）。 */
    private void reloadAll() {
        final String trains = BuiltInTrains.reloadNow();
        LightController.reload();
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(trains), false);
        }
    }

    private void commitAndClose() {
        ConfigWriteTarget.global(MtrHeadlightClient::config).commit();
        closeToParent();
    }

    private void cancelAndClose() {
        MtrHeadlightClient.restoreConfig(this.snapshot);
        closeToParent();
    }

    private void closeToParent() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private void reinit() {
        this.internalReinit = true;
        this.clearAndInit();
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

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 22, 0xFFFFFF);

        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final MutableText status = tr("config.status",
                        onOff(cfg.enabled), onOff(cfg.dynamicLight), onOff(cfg.beamGlow))
                .append(Text.literal(cfg.disableBuiltInTrains
                        ? tr("main.builtinHidden").getString() : ""));
        context.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, 38,
                cfg.enabled ? 0x9DFF9D : 0xFF9D9D);

        context.drawCenteredTextWithShadow(this.textRenderer, tr("main.hint"),
                this.width / 2, this.height - FOOTER_H - 16, 0x909090);
        context.drawCenteredTextWithShadow(this.textRenderer, tr("hint.author"),
                this.width / 2, this.height - 14, 0xB0B0B0);
    }

    private Text onOff(final boolean on) {
        return tr(on ? "config.on" : "config.off");
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC = 取消：整份回滚，和「取消」按钮同一条路径。 */
    @Override
    public void close() {
        cancelAndClose();
    }
}
