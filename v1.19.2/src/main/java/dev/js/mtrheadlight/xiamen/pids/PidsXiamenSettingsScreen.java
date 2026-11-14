// LTE 仪表板 →「厦门 PIDS」：四个全局开关（本地时间 / 即将进站 / 广告 / 全局广告覆盖）。
package dev.js.mtrheadlight.xiamen.pids;

import net.minecraft.client.util.math.MatrixStack;
import dev.js.mtrheadlight.clientgui.Widgets;
import dev.js.mtrheadlight.clientgui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 厦门 PIDS 的<b>全局</b>开关页（入口：LTE 仪表板 → 全局配置 → 其他 →「厦门 PIDS」）。
 *
 * <p>这些开关都是「对所有厦门 PIDS 同时生效」的显示选项，值存在
 * {@link PidsXiamenSettings}（{@code config/lte_xiamen_pids.json}）：
 * <table border="1">
 *   <caption>开关</caption>
 *   <tr><th>开关</th><th>开</th><th>关</th></tr>
 *   <tr><td>本地时间</td><td>时钟 / 日期 = 现实时间</td><td>时钟 / 日期 = 世界时间（第 0 天 = 2024-01-01）</td></tr>
 *   <tr><td>即将进站</td><td>到站前一分钟内写「即将进站」</td><td>继续写「X 秒 / X 分钟」</td></tr>
 *   <tr><td>广告</td><td>显示广告图（默认一张静态；勾了多张才轮播）</td><td>只留空白底图</td></tr>
 *   <tr><td>全局广告</td><td>所有屏都只播选中的那一张（不轮播）</td><td>各屏按自己勾的分类播</td></tr>
 * </table>
 *
 * <h2>第四行怎么用</h2>
 * <p>它是「广告」那一行<b>正下方</b>的一整行按钮（同一列、同一高度，见 {@link #PITCH}）：
 * 点开 {@link PidsXiamenAdPickScreen}，列表第一行是「不覆盖」，往下是广告表里的每一张。
 * 选完自动回到本页、按钮文字跟着变。
 * 排布按「行数 × 行距」整体居中（{@link #init()}），所以行数和文字说明不会撞在一起 ——
 * 之前踩过的坑就是把新控件硬编码在某个 y 上，结果压在标题 / 说明行上。
 *
 * <p>点一下立刻写内存 + 落盘（这个页面不进「完成 / 取消」的整份回滚：它改的是显示偏好，
 * 不是世界数据）。返回时回调用方（现在是「全局配置 → 其他」那一页）。
 */
public final class PidsXiamenSettingsScreen extends Screen {

    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int ROW_H = 20;
    private static final int PITCH = 25;
    private static final int GAP = 8;
    private static final int MAX_COLUMN = 320;

    /** 四行开关（本地时间 / 即将进站 / 广告 / 全局广告覆盖）。 */
    private static final int ROWS = 4;

    private final Screen parent;

    private int colX;
    private int colW;
    private int listTop;

    public PidsXiamenSettingsScreen(final Screen parent) {
        super(tr("screen.mtrlte.pids_xiamen"));
        this.parent = parent;
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    @Override
    protected void init() {
        this.colW = Math.min(MAX_COLUMN, this.width - 48);
        this.colX = this.width / 2 - this.colW / 2;
        // 四行开关 + 一行说明：按行数整体垂直居中，且不越过底部「返回」。
        final int block = ROWS * PITCH - (PITCH - ROW_H);
        final int centered = (this.height - block) / 2 - 10;
        final int maxTop = this.height - 34 - GAP - block;
        this.listTop = Math.max(58, Math.min(centered, maxTop));

        int y = this.listTop;
        addButton(y, () -> tr(PidsXiamenSettings.localTime() ? "opt.xpids.localTime.on" : "opt.xpids.localTime.off"),
                b -> {
                    PidsXiamenSettings.setLocalTime(!PidsXiamenSettings.localTime());
                    reinit();
                });
        y += PITCH;
        addButton(y, () -> tr(PidsXiamenSettings.arriving() ? "opt.xpids.arriving.on" : "opt.xpids.arriving.off"),
                b -> {
                    PidsXiamenSettings.setArriving(!PidsXiamenSettings.arriving());
                    reinit();
                });
        y += PITCH;
        addButton(y, () -> tr(PidsXiamenSettings.advertisements()
                        ? "opt.xpids.ads.on" : "opt.xpids.ads.off"),
                b -> {
                    PidsXiamenSettings.setAdvertisements(!PidsXiamenSettings.advertisements());
                    reinit();
                });
        y += PITCH;
        // 「广告」正下方：全局广告覆盖（点开新的一页选图，选完回到这里）。
        addButton(y, PidsXiamenSettingsScreen::adOverrideLabel, b -> openAdPicker());

        // 返回
        final int fy = this.height - 34;
        this.addDrawableChild(Widgets.button(tr("btn.back"), b -> back())
                .dimensions(this.colX, fy, this.colW, ROW_H).build());
    }

    /**
     * 全局广告那一行的文字：没选 = 「不覆盖」；选了 = 那张广告的标语（没有标语就印图片名）。
     */
    private static Text adOverrideLabel() {
        final String image = PidsXiamenSettings.adOverrideImage();
        if (image == null) {
            return tr("opt.xpids.adOverride.none");
        }
        final PidsXiamenAds.Ad ad = PidsXiamenSettings.adOverride();
        if (ad == null) {
            // 选了但资源包里已经没有这张图：照样把 id 显示出来，玩家才知道要重选。
            return tr("opt.xpids.adOverride.missing", image);
        }
        final Text caption = ad.text() == null || ad.text().getString().isBlank()
                ? Text.literal(image) : ad.text();
        return tr("opt.xpids.adOverride.on", caption);
    }

    private void openAdPicker() {
        if (this.client != null) {
            this.client.setScreen(new PidsXiamenAdPickScreen(this));
        }
    }

    /** 一行开关：标签每次 init 时按当前值重取（{@code label} 是个 supplier）。 */
    private void addButton(final int y, final java.util.function.Supplier<Text> label,
                           final ButtonWidget.PressAction action) {
        this.addDrawableChild(Widgets.button(label.get(), action)
                .dimensions(this.colX, y, this.colW, ROW_H).build());
    }

    private void reinit() {
        this.clearAndInit();
    }

    private void back() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        final DrawContext context = new DrawContext(matrices);
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(matrices, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 22, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, tr("gui.mtrlte.pids_xiamen.global_hint"),
                this.width / 2, 38, 0xB0B0B0);
        context.drawCenteredTextWithShadow(this.textRenderer, tr("hint.author"),
                this.width / 2, this.height - 14, 0xB0B0B0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC = 返回（和「返回」按钮同一条路）。 */
    @Override
    public void close() {
        back();
    }
}
