// LTE 仪表板 → 全局配置 → 其他 →「全局广告集」：一套有序图片，开启后覆盖所有厦门屏。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.List;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 「全局广告集」页（入口：LTE 仪表板 → 全局配置 → 其他 →「全局广告集」）。
 *
 * <h2>这一页改的是什么</h2>
 * <p>{@link PidsAdSet}：一个开关 + 一组<b>有序</b>图片 id。开关打开且至少有一张图时，
 * {@link PidsXiamenSettings#applyAdOverride(List)} 会把<b>所有</b>厦门 PIDS 与 PSD-LCD
 * 的广告表整份换成这一套（按顺序轮播），每块屏自己的广告分类、以及老的「全局广告：单张」
 * 都不再参与；关掉就回到原行为。
 *
 * <h2>存在哪 / 怎么生效</h2>
 * <p>权威值在服务端（{@link PidsAdSetState}），这一页每次点击都整份发
 * {@link PidsAdSetNet#savePacket}（C2S，权限 ≥ 2）；本机先乐观写
 * {@link PidsAdSetCache} 让界面立刻有反应，服务端广播回来的快照是最终值
 * （被拒绝时会推一份快照把界面改回去）。
 *
 * <h2>排版（沿用既有习惯：开关在上、列表在下）</h2>
 * <pre>
 *   y=10   标题
 *   y=22   说明一行
 *   y=34   开关（整行）
 *   y=58   「添加 / 移除广告…」（整行，点开 {@link PidsXiamenAdSetPickScreen}）
 *   y=86…  当前这一套，一行一张：序号 + 标语，行尾一个 ✕ 移除；放不下分页
 *   底栏   清空 / 返回
 * </pre>
 */
public final class PidsXiamenAdSetScreen extends Screen {

    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int SIDE = 20;
    private static final int ROW_H = 20;
    private static final int PITCH = 24;
    /** 列表起点：开关（34…54）/ 添加（58…78）/ 张数（80）之后。 */
    private static final int LIST_TOP = 92;
    private static final int REMOVE_W = 24;
    private static final int PAGER_W = 56;

    private final Screen parent;

    private int page;
    private int rowsPerPage = 1;
    private int listW;

    public PidsXiamenAdSetScreen(final Screen parent) {
        super(tr("screen.mtrlte.pids_ad_set"));
        this.parent = parent;
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void init() {
        this.listW = Math.max(120, this.width - SIDE * 2);
        final int footerY = this.height - 34;
        final int pagerY = footerY - 28;
        final int listBottom = pagerY - 8;
        this.rowsPerPage = Math.max(1, (listBottom - LIST_TOP) / PITCH);

        final PidsAdSet set = PidsAdSetCache.get();
        final List<String> images = set.images();
        final int pages = Math.max(1, (images.size() + this.rowsPerPage - 1) / this.rowsPerPage);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        // 开关
        this.addDrawableChild(ButtonWidget.builder(toggleLabel(set), b -> {
            final PidsAdSet next = PidsAdSetCache.get();
            next.setEnabled(!next.enabled());
            PidsAdSetCache.save(next);
            reinit();
        }).dimensions(SIDE, 34, this.listW, ROW_H).build());

        // 添加 / 移除（点开另一页，回来时这一页会重排）
        this.addDrawableChild(ButtonWidget.builder(tr("btn.pids_ad_set.add"), b -> openPicker())
                .dimensions(SIDE, 58, this.listW, ROW_H).build());

        // 列表：一行一张（文字在 render 里画，行尾只有一个 ✕ 按钮，避免「点一下没反应」的假按钮）
        final int from = this.page * this.rowsPerPage;
        final int to = Math.min(images.size(), from + this.rowsPerPage);
        int y = LIST_TOP;
        for (int i = from; i < to; i++) {
            final String image = images.get(i);
            this.addDrawableChild(ButtonWidget.builder(tr("btn.pids_ad_set.remove"), b -> {
                final PidsAdSet next = PidsAdSetCache.get();
                next.remove(image);
                PidsAdSetCache.save(next);
                reinit();
            }).dimensions(this.width - SIDE - REMOVE_W, y, REMOVE_W, ROW_H).build());
            y += PITCH;
        }

        // 分页
        if (pages > 1) {
            final ButtonWidget prev = ButtonWidget.builder(tr("gui.mtrlte.pids_xiamen.page_prev"), b -> turnPage(-1))
                    .dimensions(SIDE, pagerY, PAGER_W, ROW_H).build();
            prev.active = this.page > 0;
            this.addDrawableChild(prev);
            final ButtonWidget next = ButtonWidget.builder(tr("gui.mtrlte.pids_xiamen.page_next"), b -> turnPage(1))
                    .dimensions(this.width - SIDE - PAGER_W, pagerY, PAGER_W, ROW_H).build();
            next.active = this.page < pages - 1;
            this.addDrawableChild(next);
        }

        // 底栏：清空 / 返回
        final int half = (this.listW - 8) / 2;
        final ButtonWidget clear = ButtonWidget.builder(tr("btn.pids_ad_set.clear"), b -> {
            final PidsAdSet next = PidsAdSetCache.get();
            next.clear();
            PidsAdSetCache.save(next);
            reinit();
        }).dimensions(SIDE, footerY, half, ROW_H).build();
        clear.active = !images.isEmpty();
        this.addDrawableChild(clear);
        this.addDrawableChild(ButtonWidget.builder(tr("btn.back"), b -> back())
                .dimensions(SIDE + half + 8, footerY, half, ROW_H).build());
    }

    /** 开关那一行：开（N 张）/ 开但空 / 关。 */
    private static Text toggleLabel(final PidsAdSet set) {
        if (!set.enabled()) {
            return tr("opt.pids_ad_set.off");
        }
        return set.size() == 0 ? tr("opt.pids_ad_set.empty") : tr("opt.pids_ad_set.on", set.size());
    }

    /** 一行：{@code 1. 标语}；图已经不在资源包里就标出来（玩家才知道要重选）。 */
    private static Text rowLabel(final int index, final String image) {
        final PidsXiamenAds.Ad ad = PidsXiamenAds.adWithImage(image);
        final Text caption;
        if (ad == null) {
            caption = tr("gui.mtrlte.pids_ad_set.missing", image);
        } else if (ad.text() == null || ad.text().getString().isBlank()) {
            caption = Text.literal(image);
        } else {
            caption = ad.text();
        }
        return tr("gui.mtrlte.pids_ad_set.row", index + 1, caption);
    }

    private void turnPage(final int delta) {
        this.page = Math.max(0, this.page + delta);
        this.clearAndInit();
    }

    private void openPicker() {
        if (this.client != null) {
            try {
                this.client.setScreen(new PidsXiamenAdSetPickScreen(this));
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] xiamen pids ad set: could not open the picker", t);
            }
        }
    }

    private void reinit() {
        this.clearAndInit();
    }

    private void back() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    // ------------------------------------------------------------------ 画

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

        final PidsAdSet set = PidsAdSetCache.get();
        final String count = tr("gui.mtrlte.pids_ad_set.count", set.size()).getString();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                tr("gui.mtrlte.pids_ad_set.hint"), this.width / 2, 22, 0xB0B0B0);
        if (set.size() == 0) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlte.pids_ad_set.empty"), this.width / 2, LIST_TOP + 6, 0x909090);
        } else {
            context.drawTextWithShadow(this.textRenderer, Text.literal(count),
                    SIDE, LIST_TOP - 12, 0xA0A0A0);
            // 每行的文字（按钮只有行尾那个 ✕，所以这里自己画）
            final int from = this.page * this.rowsPerPage;
            final int to = Math.min(set.size(), from + this.rowsPerPage);
            final int maxW = this.listW - REMOVE_W - 12;
            for (int i = from; i < to; i++) {
                final String text = this.textRenderer.trimToWidth(
                        rowLabel(i, set.images().get(i)).getString(), maxW);
                context.drawTextWithShadow(this.textRenderer, Text.literal(text),
                        SIDE + 4, LIST_TOP + (i - from) * PITCH + 6, 0xFFFFFF);
            }
        }
        context.drawCenteredTextWithShadow(this.textRenderer, tr("hint.author"),
                this.width / 2, this.height - 14, 0xB0B0B0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        back();
    }
}
