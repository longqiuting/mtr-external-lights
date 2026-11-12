// LTE 仪表板 →「厦门 PIDS」：三个全局开关（本地时间 / 即将进站 / 广告）。
package dev.js.mtrheadlight.xiamen.pids;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 厦门 PIDS 的<b>全局</b>开关页（入口：LTE 仪表板 → 全局配置 → 其他 →「厦门 PIDS」）。
 *
 * <p>三个开关都是「对所有厦门 PIDS 同时生效」的显示选项，值存在
 * {@link PidsXiamenSettings}（{@code config/lte_xiamen_pids.json}）：
 * <table border="1">
 *   <caption>开关</caption>
 *   <tr><th>开关</th><th>开</th><th>关</th></tr>
 *   <tr><td>本地时间</td><td>时钟 / 日期 = 现实时间</td><td>时钟 / 日期 = 世界时间（第 0 天 = 2024-01-01）</td></tr>
 *   <tr><td>即将进站</td><td>到站前一分钟内写「即将进站」</td><td>继续写「X 秒 / X 分钟」</td></tr>
 *   <tr><td>广告</td><td>显示广告图（默认一张静态；勾了多张才轮播）</td><td>只留空白底图</td></tr>
 * </table>
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
        // 三行开关 + 一行说明：垂直居中
        final int rows = 3;
        final int block = rows * PITCH - (PITCH - ROW_H);
        this.listTop = Math.max(58, (this.height - block) / 2 - 10);

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

        // 返回
        final int fy = this.height - 34;
        this.addDrawableChild(ButtonWidget.builder(tr("btn.back"), b -> back())
                .dimensions(this.colX, fy, this.colW, ROW_H).build());
    }

    /** 一行开关：标签每次 init 时按当前值重取（{@code label} 是个 supplier）。 */
    private void addButton(final int y, final java.util.function.Supplier<Text> label,
                           final ButtonWidget.PressAction action) {
        this.addDrawableChild(ButtonWidget.builder(label.get(), action)
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
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

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
