package dev.js.mtrheadlight;

import net.minecraft.client.util.math.MatrixStack;
import mtr.data.Siding;
import dev.js.mtrheadlight.clientgui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 分区列表：全局配置和一条侧线的详情都先到这里选分区，点进分区才是具体的行
 * （{@link ParameterScreen}）。
 *
 * <h2>三个屏幕的导航</h2>
 * <pre>
 *   主界面 ConfigScreen ──全局配置──▶ CategoryScreen(siding=null) ──光源/碰撞/…──▶ ParameterScreen(分区行)
 *        │                                   ▲   │ 返回分区                              │
 *        └──侧线配置──▶ SidingScreen ──一条侧线──▶ CategoryScreen(siding) ◀──────────────┘
 *                             │                        │ 返回
 *                             └──── 地图 SidingMapScreen ┘
 * </pre>
 * <ul>
 *   <li><b>返回</b>（分区列表底部 / ESC）= 回到 {@link #back}：全局流程是主界面，侧线流程是侧线列表。</li>
 *   <li><b>返回分区</b>（行页底部 / ESC）= 回到这里，同一个侧线 / 全局。</li>
 *   <li><b>返回主界面</b>（行页底部）= 回到 {@link #root}，也就是带「完成 / 取消」的主界面 -
 *       配置只有在那里才写盘，取消也在那里整体回滚。</li>
 * </ul>
 *
 * <h2>只列出有内容的分区</h2>
 * 侧线页按 {@link ConfigCategory#hasSidingRows} 过滤：粒子 / 伤害 / 其他全是全局值，
 * 不生成对应按钮，省得点进去看到一页「（跟随）」的只读行。
 *
 * <h2>布局</h2>
 * 竖直按钮列表，宽度和行页用同一条公式（最长标签 + 两个汉字，最窄 {@value #MIN_COLUMN}），
 * 不用硬编码坐标。窗口矮到放不下五个分区时按整行滚动（滚轮 / PageUp / PageDown），
 * 和 {@link ParameterScreen} 一样只滚整行，半行不会藏到底栏下面。
 */
public final class CategoryScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int ROW_H = 20;
    /** 20px 按钮 + 5px 间隙：和行页同一个节奏，窗口矮时 5 个分区也能滚。 */
    private static final int PITCH = 25;
    private static final int GAP = 8;
    private static final int MIN_COLUMN = 176;
    private static final int CONTENT_TOP = 58;
    private static final int FOOTER_H = 44;
    private static final int BOTTOM_PAD = 16;

    /** 回到这里：全局流程 = 主界面，侧线流程 = 侧线列表。 */
    private final Screen back;
    /** 主界面（{@link ConfigScreen}）：行页的「返回主界面」直接跳到它。 */
    private final Screen root;
    /** null = 全局配置，否则这条侧线的覆盖。 */
    private final Siding siding;

    private final List<ConfigCategory> shown = new ArrayList<>();
    private int colX;
    private int colW;
    private int listTop;
    private int visibleRows = 1;
    private int scrollRow;

    public CategoryScreen(final Screen back, final Screen root, final Siding siding) {
        super(siding == null
                ? tr("cat.title.global")
                : tr("cat.title.siding", Long.toString(siding.id), ParameterScreen.nameOf(siding)));
        this.back = back;
        this.root = root == null ? back : root;
        this.siding = siding;
    }

    static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    /** True for the global flow. */
    private boolean isGlobal() {
        return this.siding == null;
    }

    // ---------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------

    private int viewBottom() {
        return this.height - FOOTER_H - BOTTOM_PAD;
    }

    private int rowsPerPage() {
        return Math.max(1, (viewBottom() - CONTENT_TOP + (PITCH - ROW_H)) / PITCH);
    }

    @Override
    protected void init() {
        this.shown.clear();
        for (final ConfigCategory category : ConfigCategory.values()) {
            // 侧线页只保留有可覆盖行的分区（光源 / 碰撞）。
            if (this.siding == null || category.hasSidingRows) {
                this.shown.add(category);
            }
        }

        int need = 0;
        for (final ConfigCategory category : this.shown) {
            need = Math.max(need, this.textRenderer.getWidth(category.title().getString()));
        }
        final int charW = this.textRenderer.getWidth("测");
        final int maxCol = Math.max(MIN_COLUMN, this.width - 24);
        this.colW = Math.min(maxCol, Math.max(MIN_COLUMN, need + charW * 2 + 32));
        this.colX = this.width / 2 - this.colW / 2;

        this.visibleRows = rowsPerPage();
        final int maxScroll = Math.max(0, this.shown.size() - this.visibleRows);
        this.scrollRow = Math.max(0, Math.min(this.scrollRow, maxScroll));

        final int listH = Math.min(this.visibleRows, this.shown.size()) * PITCH - (PITCH - ROW_H);
        this.listTop = maxScroll == 0
                ? CONTENT_TOP + Math.max(0, (viewBottom() - CONTENT_TOP - listH) / 2)
                : CONTENT_TOP;

        final int last = Math.min(this.shown.size(), this.scrollRow + this.visibleRows);
        for (int i = this.scrollRow; i < last; i++) {
            final ConfigCategory category = this.shown.get(i);
            final int y = this.listTop + (i - this.scrollRow) * PITCH;
            addButton(this.colX, y, this.colW, ROW_H, category.title(), b -> open(category));
        }

        // 底部一个「返回」：和行页的两格底栏同一宽度公式，不会比上面的按钮更宽。
        final int y = this.height - FOOTER_H;
        addButton(this.width / 2 - this.colW / 2, y, this.colW, ROW_H, tr("btn.back"),
                b -> closeToBack());
    }

    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        return this.addDrawableChild(ButtonWidget.builder(label, action).dimensions(x, y, w, h).build());
    }

    private void open(final ConfigCategory category) {
        if (this.client != null) {
            this.client.setScreen(new ParameterScreen(this, this.root, this.siding, category));
        }
    }

    private void closeToBack() {
        if (this.client != null) {
            this.client.setScreen(this.back);
        }
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
        final Text hint = this.isGlobal()
                ? tr("cat.hint.global")
                : tr("cat.hint.siding", ParameterScreen.describe(this.siding));
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth(hint.getString(), this.width - 24)),
                this.width / 2, 38, 0xB0B0B0);

        if (this.shown.size() > this.visibleRows) {
            drawScrollBar(context);
        }
        context.drawCenteredTextWithShadow(this.textRenderer, tr("hint.author"),
                this.width / 2, this.height - 14, 0xB0B0B0);
    }

    private void drawScrollBar(final DrawContext context) {
        final int top = CONTENT_TOP;
        final int bottom = this.listTop + this.visibleRows * PITCH - (PITCH - ROW_H);
        final int x = this.colX + this.colW + 4;
        context.fill(x, top, x + 1, Math.max(top, bottom), 0x60FFFFFF);
        final int trackH = Math.max(1, bottom - top);
        final int thumbH = Math.max(12, trackH * this.visibleRows / this.shown.size());
        final int span = Math.max(1, this.shown.size() - this.visibleRows);
        final int thumbY = top + (trackH - thumbH) * this.scrollRow / span;
        context.fill(x - 1, thumbY, x + 2, thumbY + thumbH, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC = 返回上一层（逐层返回的那一层）。 */
    @Override
    public void close() {
        closeToBack();
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (this.shown.size() > this.visibleRows && amount != 0.0D) {
            this.scrollRow -= (int) Math.signum(amount);
            this.clearAndInit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN) {
            return scrollBy(Math.max(1, this.visibleRows - 1));
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
            return scrollBy(-Math.max(1, this.visibleRows - 1));
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean scrollBy(final int delta) {
        if (this.shown.size() <= this.visibleRows) {
            return false;
        }
        this.scrollRow += delta;
        this.clearAndInit();
        return true;
    }
}
