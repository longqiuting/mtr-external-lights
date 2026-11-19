// 全局广告集的「添加 / 移除」页：列出广告表里的每一张，点一下加入、再点一下移除。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.ArrayList;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 全局广告集的成员选择页 —— 从 {@link PidsXiamenAdSetScreen} 的「添加 / 移除广告…」进来。
 *
 * <h2>为什么是「点一下切换」而不是「选一张就回去」</h2>
 * <p>这一页要回答的是「这一套里有哪几张」，不是「用哪一张」。所以点一行 = 加入 / 移除，
 * 页面不关；行首的 {@code ✔} 表示已在这一套里。顺序 = <b>加入顺序</b>（先加入的排在前面），
 * 想调整顺序就在上一页把某张 ✕ 掉再重新加入。
 *
 * <h2>列表是什么</h2>
 * <p>广告表跨分类拍平、按图片去重（{@link PidsXiamenAds#allAds()}）：资源包带的每一张图
 * 都会出现，和「单张全局覆盖」的选择页用的是同一份数据。
 *
 * <h2>写入</h2>
 * <p>每次点击都是「拷一份 {@link PidsAdSetCache} → 改 → {@link PidsAdSetCache#save}」，
 * 服务端广播回来的快照是最终值（做法同上一页）。
 */
public final class PidsXiamenAdSetPickScreen extends Screen {

    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int LIST_TOP = 34;
    private static final int ROW_H = 20;
    private static final int PITCH = 24;
    private static final int SIDE = 20;
    private static final int PAGER_W = 56;

    /** 一行 = 一张可选的广告。 */
    private record Row(String image, Text label) {
    }

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    private int page;
    private int rowsPerPage = 1;
    private int listW;

    public PidsXiamenAdSetPickScreen(final Screen parent) {
        super(tr("screen.mtrlte.pids_ad_set.pick"));
        this.parent = parent;
        buildRows();
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    /** 一张图在「分类名 · 标语」下的可读名字。 */
    private void buildRows() {
        this.rows.clear();
        try {
            for (final PidsXiamenAds.Category category : PidsXiamenAds.categories()) {
                final Text name = category.name() == null
                        ? Text.literal(category.key()) : category.name();
                for (final PidsXiamenAds.Ad ad : category.ads()) {
                    if (ad.image() == null) {
                        continue;
                    }
                    final String image = ad.image().toString();
                    if (indexOf(image) >= 0) {
                        continue; // 同一张图挂在两个分类下：只列一次
                    }
                    final MutableText label = name.copy().append(Text.literal(" · "));
                    label.append(ad.text() == null || ad.text().getString().isBlank()
                            ? Text.literal(image) : ad.text());
                    this.rows.add(new Row(image, label));
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids ad set: could not list the advertisements", t);
        }
    }

    private int indexOf(final String image) {
        for (int i = 0; i < this.rows.size(); i++) {
            if (image.equals(this.rows.get(i).image())) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void init() {
        this.listW = Math.max(80, this.width - SIDE * 2);
        final int doneY = this.height - 40;
        final int pagerY = doneY - 28;
        final int listBottom = pagerY - 12;
        this.rowsPerPage = Math.max(1, (listBottom - LIST_TOP) / PITCH);

        final int pages = Math.max(1, (this.rows.size() + this.rowsPerPage - 1) / this.rowsPerPage);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        final int from = this.page * this.rowsPerPage;
        final int to = Math.min(this.rows.size(), from + this.rowsPerPage);
        int y = LIST_TOP;
        for (int i = from; i < to; i++) {
            final Row row = this.rows.get(i);
            final boolean selected = PidsAdSetCache.get().contains(row.image());
            final Text label = selected
                    ? tr("gui.mtrlte.pids_ad_set.pick_selected", row.label())
                    : row.label();
            this.addDrawableChild(ButtonWidget.builder(label, b -> toggle(row.image()))
                    .dimensions(SIDE, y, this.listW, ROW_H).build());
            y += PITCH;
        }

        this.addDrawableChild(ButtonWidget.builder(tr("gui.done"), b -> back())
                .dimensions(this.width / 2 - 72, doneY, 144, ROW_H).build());

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
    }

    private void turnPage(final int delta) {
        this.page = Math.max(0, this.page + delta);
        this.clearAndInit();
    }

    /** 加入 / 移除，然后重排（行首的 ✔ 和标题上的张数立刻更新）。 */
    private void toggle(final String image) {
        final PidsAdSet next = PidsAdSetCache.get();
        if (next.contains(image)) {
            next.remove(image);
        } else {
            next.add(image);
        }
        PidsAdSetCache.save(next);
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

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                tr("gui.mtrlte.pids_ad_set.count", PidsAdSetCache.get().size()),
                this.width / 2, 22, 0xB0B0B0);
        context.drawCenteredTextWithShadow(this.textRenderer, tr("gui.mtrlte.pids_ad_set.pick_hint"),
                this.width / 2, this.height - 14, 0x909090);
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
