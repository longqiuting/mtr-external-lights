// 全局广告覆盖的选择页：第一行「不覆盖」，往下是广告表里的每一张（分类名 · 标语）。
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
 * 「全局广告」选择页 —— 从 {@link PidsXiamenSettingsScreen} 的第四行进来。
 *
 * <h2>列表是什么</h2>
 * <pre>
 *   第 0 行          不覆盖（= 各屏按自己勾的分类播）
 *   第 1..n 行       广告表里的每一张：分类名 · 标语（{@link PidsXiamenAds#allAds()}，
 *                    跨分类拍平、按图片去重，含默认厦门风景 / xiamen_ads / 天津包的 nbz）
 * </pre>
 * 当前选中的那一行前面标一个 {@code ▶}。点任意一行立刻生效并回到上一页 ——
 * 没有「完成 / 取消」，因为这一点改动本来就只有一行、改错了再点一次的成本更低。
 *
 * <h2>为什么按图片 id 记</h2>
 * 广告表来自资源包，顺序和条数随时会变；记图片 id 才不会在换包后指到另一张图上。
 * 保存见 {@link PidsXiamenSettings#setAdOverrideImage(String)}。
 *
 * <h2>布局</h2>
 * <p>和 {@link PidsXiamenAdsScreen} 同一套数值：标题 y = 10、列表从 y = 20 起、每条 20 高、
 * 间距 24、左右各留 20；放不下就分页（翻页只重排控件、不重开界面）。
 */
public final class PidsXiamenAdPickScreen extends Screen {

    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int LIST_TOP = 20;
    private static final int ROW_H = 20;
    private static final int PITCH = 24;
    private static final int SIDE = 20;
    private static final int PAGER_W = 56;

    /** 一行 = 一个可选目标；{@code image == null} 就是「不覆盖」。 */
    private record Row(Text label, String image) {
    }

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    private ButtonWidget prevPage;
    private ButtonWidget nextPage;

    private int page;
    private int rowsPerPage = 1;
    private int listW;

    /** 只有第一次 {@code init()} 会翻到「当前选中的那一页」；之后翻页完全由玩家控制。 */
    private boolean snapToSelection = true;

    public PidsXiamenAdPickScreen(final Screen parent) {
        super(tr("gui.mtrlte.pids_xiamen.ad_override_title"));
        this.parent = parent;
        buildRows();
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    // ------------------------------------------------------------------ 列表

    /**
     * 拍一份列表（打开界面时一次；翻页不重读，勾选状态由设置本身提供）。
     *
     * <p>标签用「分类名 · 标语」：同一个标语可能在不同分类里都出现，标上分类名才不会看花；
     * 标语是语言键、按当前语言解析（{@code Ad#text}）。
     */
    private void buildRows() {
        this.rows.clear();
        this.rows.add(new Row(tr("gui.mtrlte.pids_xiamen.ad_override_none"), null));
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
                        // 同一张图在两个分类里出现（资源包叠了同一张海报）：只列一次。
                        continue;
                    }
                    final MutableText label = name.copy().append(Text.literal(" · "));
                    label.append(ad.text() == null || ad.text().getString().isBlank()
                            ? Text.literal(image) : ad.text());
                    this.rows.add(new Row(label, image));
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not list the advertisements for the global override", t);
        }
    }

    /** 某张图在列表里的行号（-1 = 没有）。 */
    private int indexOf(final String image) {
        for (int i = 0; i < this.rows.size(); i++) {
            final String candidate = this.rows.get(i).image();
            if (candidate != null && candidate.equals(image)) {
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
        final int listBottom = pagerY - 16;
        this.rowsPerPage = Math.max(1, (listBottom - LIST_TOP) / PITCH);

        final int pages = Math.max(1, (this.rows.size() + this.rowsPerPage - 1) / this.rowsPerPage);
        // 第一次打开时直接翻到当前选中的那一页（否则选项在另一页，玩家会以为没选）。
        // 之后（翻页 / 改窗口大小）不再自动跳，免得玩家翻到第 2 页又被弹回去。
        if (this.snapToSelection) {
            this.snapToSelection = false;
            final String current = PidsXiamenSettings.adOverrideImage();
            if (pages > 1 && current != null) {
                final int index = indexOf(current);
                if (index >= 0) {
                    this.page = index / this.rowsPerPage;
                }
            }
        }
        this.page = Math.min(this.page, pages - 1);
        final String current = PidsXiamenSettings.adOverrideImage();

        final int from = this.page * this.rowsPerPage;
        final int to = Math.min(this.rows.size(), from + this.rowsPerPage);
        int y = LIST_TOP;
        for (int i = from; i < to; i++) {
            final Row row = this.rows.get(i);
            final boolean selected = row.image() == null
                    ? current == null
                    : row.image().equals(current);
            final Text label = selected
                    ? tr("gui.mtrlte.pids_xiamen.ad_override_row_selected", row.label())
                    : row.label();
            final int index = i;
            this.addDrawableChild(ButtonWidget.builder(label, b -> choose(this.rows.get(index)))
                    .dimensions(SIDE, y, this.listW, ROW_H).build());
            y += PITCH;
        }

        // 完成（= 保持当前选择，回上一页）
        this.addDrawableChild(ButtonWidget.builder(tr("gui.done"), b -> back())
                .dimensions(this.width / 2 - 72, doneY, 144, ROW_H).build());

        if (pages > 1) {
            this.prevPage = ButtonWidget.builder(tr("gui.mtrlte.pids_xiamen.page_prev"), b -> turnPage(-1))
                    .dimensions(SIDE, pagerY, PAGER_W, ROW_H).build();
            this.prevPage.active = this.page > 0;
            this.addDrawableChild(this.prevPage);
            this.nextPage = ButtonWidget.builder(tr("gui.mtrlte.pids_xiamen.page_next"), b -> turnPage(1))
                    .dimensions(this.width - SIDE - PAGER_W, pagerY, PAGER_W, ROW_H).build();
            this.nextPage.active = this.page < pages - 1;
            this.addDrawableChild(this.nextPage);
        }
    }

    /** 翻页：只重排控件（不清 {@link #page}）。 */
    private void turnPage(final int delta) {
        this.page = Math.max(0, this.page + delta);
        this.clearAndInit();
    }

    // ------------------------------------------------------------------ 选

    /** 选中一行立刻落盘，然后回上一页（上一页 {@code init()} 会把按钮文字刷新）。 */
    private void choose(final Row row) {
        PidsXiamenSettings.setAdOverrideImage(row.image());
        back();
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
        context.drawCenteredTextWithShadow(this.textRenderer, tr("gui.mtrlte.pids_xiamen.ad_override_hint"),
                this.width / 2, this.height - 14, 0x909090);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC = 放弃（不改设置），回上一页 —— 和「完成」在行为上一样，因为点一行就已经生效了。 */
    @Override
    public void close() {
        back();
    }
}
