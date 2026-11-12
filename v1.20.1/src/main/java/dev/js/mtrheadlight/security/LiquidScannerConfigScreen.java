package dev.js.mtrheadlight.security;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionUtil;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 液体安检机的配置界面：<b>勾上的液体刷机器时直接没收，没勾的一律放行</b>。
 *
 * <h2>名单从哪来</h2>
 * 列出 {@code Registries.POTION} 里<b>所有</b>药水类型（原版三十来种 + 药水模组加的那些），
 * 按界面语言里显示的名字排序。勾选状态按药水 id 存 —— 水瓶 / 喷溅 / 滞留共用一个 id，
 * 勾一次三种容器都算，符合「哪些液体」的直觉。
 *
 * <h2>「没配过」的默认值</h2>
 * 服务端下发 {@code configured=false}（这台机器从没保存过）时，这里按
 * {@link LiquidScannerBlock#isHarmful} 同一套规则<b>预选</b>「有负面效果」的那些：
 * 玩家一打开界面看到的就是机器现在的实际行为，不会出现「打开界面全是空的，一保存机器就什么都放行」。
 * 界面的「默认」按钮也是这一份。
 *
 * <h2>保存</h2>
 * 只有「保存」会把名单发回服务端（{@link SecurityClient#sendSave}），服务端校验距离 / 方块 / id
 * 之后写进方块实体并落盘，动作栏回一条「已保存 N 种」。取消 / ESC 什么都不发，
 * 机器保持原来的名单。
 *
 * <h2>布局</h2>
 * 固定宽 320（窗口窄时按 {@code width-40} 收），竖直滚动的清单 + 搜索框 + 四个按钮。
 * 清单是自己画的（每行 16px，一个方框 + 颜色点 + 名字），因为行数随模组药水数量变化，
 * 用原生 {@code ElementListWidget} 反而要额外包一层 entry。滚动只在这个清单里生效。
 */
public final class LiquidScannerConfigScreen extends Screen {

    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    /** 每行高度。 */
    private static final int ROW_H = 16;
    /** 滚动条宽度（只是视觉，滚轮才是主要输入）。 */
    private static final int BAR_W = 4;

    private final BlockPos pos;
    /** 全部药水 id（按显示名排序一次，之后只过滤不重排）。 */
    private final List<Identifier> all = new ArrayList<>();
    /** 过滤后的可见列表。 */
    private final List<Identifier> view = new ArrayList<>();
    /** 当前勾选（要没收的）。 */
    private final Set<Identifier> selected = new LinkedHashSet<>();

    private TextFieldWidget search;
    private int scroll;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listTop;
    private int listW;
    private int listH;

    /**
     * @param pos        是哪台机器（保存时回传）
     * @param configured 服务端说这台机器配过没有；没配过就按有害效果预选
     * @param ids        服务端下发的现有名单（{@code configured=false} 时为空）
     */
    public LiquidScannerConfigScreen(final BlockPos pos, final boolean configured,
                                     final Set<Identifier> ids) {
        super(Text.translatable("screen.mtrlte.liquid_scanner"));
        this.pos = pos;
        this.all.addAll(sortedPotions());
        this.selected.addAll(configured ? ids : defaults());
        this.refresh();
    }

    // ------------------------------------------------------------------ 名单

    /** 注册表里所有药水，按界面语言里的名字排序（同名的按 id 兜底）。 */
    private static List<Identifier> sortedPotions() {
        final List<Identifier> out = new ArrayList<>(Registries.POTION.getIds());
        out.sort(Comparator.<Identifier, String>comparing(id -> nameOf(id).getString(),
                String.CASE_INSENSITIVE_ORDER).thenComparing(Identifier::toString));
        return out;
    }

    /** 「有任意一个负面效果就没收」—— 与方块里那条启发式一字不差。 */
    public static Set<Identifier> defaults() {
        final Set<Identifier> out = new LinkedHashSet<>();
        for (final Identifier id : Registries.POTION.getIds()) {
            final Potion potion = Registries.POTION.get(id);
            if (potion == null) {
                continue;
            }
            for (final StatusEffectInstance effect : potion.getEffects()) {
                if (effect.getEffectType().getCategory() == StatusEffectCategory.HARMFUL) {
                    out.add(id);
                    break;
                }
            }
        }
        return out;
    }

    /** 药水显示名：有翻译就用翻译（{@code 迅捷药水}），没有就退回 id 的 path。 */
    private static Text nameOf(final Identifier id) {
        final Potion potion = Registries.POTION.get(id);
        if (potion != null) {
            final String key = potion.finishTranslationKey("item.minecraft.potion.effect.");
            if (key != null && I18n.hasTranslation(key)) {
                return Text.translatable(key);
            }
        }
        return Text.literal(id.getPath());
    }

    /** 按搜索框内容重建 {@link #view}。 */
    private void refresh() {
        final String q = this.search == null ? ""
                : this.search.getText().trim().toLowerCase(Locale.ROOT);
        this.view.clear();
        for (final Identifier id : this.all) {
            if (q.isEmpty()
                    || id.toString().toLowerCase(Locale.ROOT).contains(q)
                    || nameOf(id).getString().toLowerCase(Locale.ROOT).contains(q)) {
                this.view.add(id);
            }
        }
        this.scroll = Math.max(0, Math.min(this.scroll, this.maxScroll()));
    }

    private int maxScroll() {
        return Math.max(0, this.view.size() * ROW_H - this.listH);
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void init() {
        this.panelW = Math.min(320, this.width - 40);
        this.panelH = Math.min(224, this.height - 40);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;

        this.search = new TextFieldWidget(this.textRenderer, this.panelX + 10, this.panelY + 26,
                this.panelW - 20, 18, Text.translatable("screen.mtrlte.liquid_scanner.search"));
        this.search.setMaxLength(48);
        this.search.setChangedListener(text -> this.refresh());
        this.addDrawableChild(this.search);

        this.listX = this.panelX + 10;
        this.listTop = this.panelY + 50;
        this.listW = this.panelW - 20;
        // 底部留 48px：一行提示文字 + 一排按钮，清单不能压到它们身上。
        this.listH = this.panelH - 50 - 48;

        final int by = this.panelY + this.panelH - 26;
        final int gap = 6;
        final int bw = (this.listW - gap * 3) / 4;
        addButton(this.listX, by, bw, 20, "screen.mtrlte.liquid_scanner.defaultButton",
                b -> {
                    this.selected.clear();
                    this.selected.addAll(defaults());
                });
        addButton(this.listX + (bw + gap), by, bw, 20, "screen.mtrlte.liquid_scanner.clearButton",
                b -> this.selected.clear());
        addButton(this.listX + (bw + gap) * 2, by, bw, 20, "btn.done", b -> this.saveAndClose());
        addButton(this.listX + (bw + gap) * 3, by, bw, 20, "btn.cancel", b -> this.close());

        this.refresh();
    }

    private void addButton(final int x, final int y, final int w, final int h, final String key,
                           final ButtonWidget.PressAction action) {
        this.addDrawableChild(ButtonWidget.builder(Text.translatable(key), action)
                .dimensions(x, y, w, h).build());
    }

    private void saveAndClose() {
        SecurityClient.sendSave(this.pos, this.selected);
        this.close();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY,
                       final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        context.fill(this.panelX, this.panelY, this.panelX + this.panelW,
                this.panelY + this.panelH, 0xE0141414);
        context.drawBorder(this.panelX, this.panelY, this.panelW, this.panelH, 0xFF6E6E6E);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, this.panelY + 8, 0xFFFFFF);

        super.render(context, mouseX, mouseY, delta);

        // 清单（自己滚、自己画）
        context.enableScissor(this.listX, this.listTop, this.listX + this.listW,
                this.listTop + this.listH);
        int y = this.listTop - this.scroll;
        for (final Identifier id : this.view) {
            if (y + ROW_H > this.listTop && y < this.listTop + this.listH) {
                this.drawRow(context, id, y, mouseX, mouseY);
            }
            y += ROW_H;
        }
        context.disableScissor();

        if (this.view.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("screen.mtrlte.liquid_scanner.empty"),
                    this.width / 2, this.listTop + 8, 0x909090);
        }
        this.drawScrollBar(context);

        // 底部提示：勾选语义 + 当前勾了几个
        final Text hint = Text.translatable("screen.mtrlte.liquid_scanner.hint", this.selected.size());
        context.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2,
                this.panelY + this.panelH - 42, 0xB0B0B0);
    }

    private void drawRow(final DrawContext context, final Identifier id, final int y,
                         final int mouseX, final int mouseY) {
        final boolean hovered = mouseX >= this.listX && mouseX < this.listX + this.listW
                && mouseY >= y && mouseY < y + ROW_H;
        if (hovered) {
            context.fill(this.listX, y, this.listX + this.listW, y + ROW_H, 0x30FFFFFF);
        }
        final boolean on = this.selected.contains(id);

        // 勾选框：外框 + 底色 +（勾上）绿色芯
        final int bx = this.listX + 3;
        final int by = y + 3;
        context.fill(bx, by, bx + 10, by + 10, 0xFF1A1A1A);
        context.drawBorder(bx, by, 10, 10, 0xFF9A9A9A);
        if (on) {
            context.fill(bx + 2, by + 2, bx + 8, by + 8, 0xFF38C24E);
            context.drawBorder(bx + 2, by + 2, 6, 6, 0xFFD8FFDD);
        }

        context.drawTextWithShadow(this.textRenderer, nameOf(id), bx + 16, y + 4,
                on ? 0xFFFFFF : 0xBFBFBF);

        // 右侧：药水颜色点 + id（颜色点让「喷溅 / 滞留」这类同名的也能一眼认出）
        final Potion potion = Registries.POTION.get(id);
        if (potion != null) {
            final int dotX = this.listX + this.listW - 14;
            context.fill(dotX, y + 5, dotX + 8, y + 13,
                    0xFF000000 | (PotionUtil.getColor(potion) & 0xFFFFFF));
            context.drawBorder(dotX, y + 5, 8, 8, 0x60FFFFFF);
        }
    }

    private void drawScrollBar(final DrawContext context) {
        final int max = this.maxScroll();
        if (max <= 0) {
            return;
        }
        final int x = this.listX + this.listW - BAR_W;
        context.fill(x, this.listTop, x + BAR_W, this.listTop + this.listH, 0x40FFFFFF);
        final int barH = Math.max(16, this.listH * this.listH / (this.view.size() * ROW_H));
        final int barY = this.listTop + (this.listH - barH) * this.scroll / max;
        context.fill(x, barY, x + BAR_W, barY + barH, 0xFFBFBFBF);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && mouseX >= this.listX && mouseX < this.listX + this.listW
                && mouseY >= this.listTop && mouseY < this.listTop + this.listH) {
            final int index = (int) ((mouseY - this.listTop + this.scroll) / ROW_H);
            if (index >= 0 && index < this.view.size()) {
                final Identifier id = this.view.get(index);
                if (!this.selected.remove(id)) {
                    this.selected.add(id);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        final int max = this.maxScroll();
        if (max > 0) {
            this.scroll = (int) Math.max(0, Math.min(max, this.scroll - amount * ROW_H * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
