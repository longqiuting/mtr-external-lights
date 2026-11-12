// 广告选择界面：版式与行为照天津地铁的 CategorySelectorScreen（勾分类 → 完成），外加「导入资源包」入口。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.js.mtrheadlight.LteLog;
import mtr.client.IDrawing;
import mtr.mappings.ScreenMapper;
import mtr.screen.WidgetBetterCheckbox;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 「选择广告」界面 —— <b>逐项照天津地铁的 {@code CategorySelectorScreen}</b>。
 *
 * <h2>天津长什么样，这里就长什么样</h2>
 * <pre>
 *   标题「选择广告」              居中，y = 10
 *   分类勾选框                     x = 20、从 y = 20 起、每条 20 高、间距 24、宽 = 窗口宽 − 40
 *   完成                         144 × 20，水平居中、y = 窗口高 − 40
 * </pre>
 * 天津的 {@code allCategories} 是
 * {@code BlockPIDSTianjin.CATEGORIES}（资源重载时从<b>整个包栈</b>里的
 * {@code pids_tianjin_ads.json} 合并出来的分类表），勾完点「完成」把勾中的分类 id 发给服务端。
 * 这里的三处对应关系：
 * <ul>
 *   <li>分类表 → {@link PidsXiamenAds#categories()}（同样是 {@code getAllResources} 合并）；</li>
 *   <li>勾选状态 → 方块实体的 {@code xiamen_ad_categories}（{@link PidsXiamenBlockEntity#setAdCategories(long[])}）；</li>
 *   <li>「完成」→ {@link PidsXiamenNet#categories}（C2S），服务端写回并 {@code syncData()}。</li>
 * </ul>
 *
 * <h2>多出来的两样东西（天津没有）</h2>
 * <ol>
 *   <li><b>导入资源包</b>：广告本来就住在资源包里（{@code assets/<ns>/pids_xiamen_ads.json} +
 *       广告图），所以这里直接开原版的资源包界面（{@code PackScreen}）——
 *       它自带「打开包文件夹」和拖入 zip 的导入。装好、重开本界面就能看到新的分类
 *       （{@code init()} 每次都会重读分类表，包栈一变广告表也立即失效）。
 *       天津靠玩家自己去「选项 → 资源包」，等于把入口藏起来了。</li>
 *   <li><b>分页</b>：分类多到一屏放不下时出现「上一页 / 下一页」（天津直接溢出到按钮上）。</li>
 * </ol>
 *
 * <h2>为什么只有一个按钮</h2>
 * 天津只有一个「完成」，关界面（ESC）等于放弃本次勾选。这里也一样：ESC 不写。
 */
public final class PidsXiamenAdsScreen extends ScreenMapper {

    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    /** 天津的数值：列表从 y = 20 起、每条 20 高、间距 24、左右各留 20。 */
    private static final int LIST_TOP = 20;
    private static final int ROW_H = 20;
    private static final int PITCH = 24;
    private static final int SIDE = 20;
    /** 「完成」按钮：天津的 144 × 20。 */
    private static final int BUTTON_W = 144;
    private static final int PAGER_W = 56;

    private final BlockPos dataPos;
    private final ScreenMapper parent;

    /** 分类表（打开界面时拍一份：勾选过程中资源包不会换）。 */
    private final List<PidsXiamenAds.Category> categories = new ArrayList<>();
    /** 勾选状态（分类 id，保持勾选顺序）。 */
    private final Set<Long> selected = new LinkedHashSet<>();

    private final List<WidgetBetterCheckbox> boxes = new ArrayList<>();
    private ButtonWidget prevPage;
    private ButtonWidget nextPage;

    private int page;
    private int rowsPerPage = 1;
    private int listW;

    public PidsXiamenAdsScreen(final BlockPos dataPos, final ScreenMapper parent) {
        super(tr("gui.mtrlte.pids_xiamen.select_ads"));
        this.dataPos = dataPos;
        this.parent = parent;
        readCategories();
        readSelection();
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    // ------------------------------------------------------------------ 读

    private void readCategories() {
        this.categories.clear();
        try {
            this.categories.addAll(PidsXiamenAds.categories());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not read the advertisement categories", t);
        }
    }

    /** 方块实体里存的那一份；读不到 = 空（= 默认分类）。 */
    private void readSelection() {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null) {
                return;
            }
            if (client.world.getBlockEntity(this.dataPos) instanceof PidsXiamenBlockEntity pids) {
                for (final long id : pids.getAdCategories()) {
                    this.selected.add(Long.valueOf(id));
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not read the selected categories", t);
        }
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void init() {
        this.boxes.clear();
        // 从资源包界面回来 / 窗口改大小时都重读一次：装完新包这里就能看到新分类。
        readCategories();
        this.listW = Math.max(80, this.width - SIDE * 2);

        // 底部三行：完成（天津的位置）、导入资源包、分页（需要时才有）
        final int doneY = this.height - 40;
        final int importY = doneY - 28;
        final int listBottom = importY - 16;
        this.rowsPerPage = Math.max(1, (listBottom - LIST_TOP) / PITCH);

        final int pages = Math.max(1, (this.categories.size() + this.rowsPerPage - 1) / this.rowsPerPage);
        this.page = Math.min(this.page, pages - 1);

        final int from = this.page * this.rowsPerPage;
        final int to = Math.min(this.categories.size(), from + this.rowsPerPage);
        int y = LIST_TOP;
        for (int i = from; i < to; i++) {
            final PidsXiamenAds.Category category = this.categories.get(i);
            final WidgetBetterCheckbox box = new WidgetBetterCheckbox(0, 0, this.listW, ROW_H,
                    category.name() == null ? Text.literal(category.key()) : category.name(),
                    checked -> {
                        if (checked) {
                            this.selected.add(Long.valueOf(category.id()));
                        } else {
                            this.selected.remove(Long.valueOf(category.id()));
                        }
                    });
            IDrawing.setPositionAndWidth(box, SIDE, y, this.listW);
            box.setChecked(this.selected.contains(Long.valueOf(category.id())));
            try {
                box.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                        tr("gui.mtrlte.pids_xiamen.ad_category_tip",
                                category.description() == null ? Text.empty() : category.description(),
                                category.ads().size())));
            } catch (Throwable ignored) {
                // 老版本没有 setTooltip 也不影响勾选。
            }
            this.boxes.add(box);
            this.addDrawableChild(box);
            y += PITCH;
        }

        // 导入资源包（原版 PackScreen：自带「打开包文件夹」+ 拖入 zip）
        this.addDrawableChild(ButtonWidget.builder(tr("gui.mtrlte.pids_xiamen.import_pack"),
                        b -> openPackScreen())
                .dimensions(this.width / 2 - BUTTON_W / 2, importY, BUTTON_W, ROW_H).build());

        // 完成（天津的位置与尺寸）
        this.addDrawableChild(ButtonWidget.builder(tr("gui.done"), b -> saveAndClose())
                .dimensions(this.width / 2 - BUTTON_W / 2, doneY, BUTTON_W, ROW_H).build());

        // 分页（只有在放不下时才出现）
        if (pages > 1) {
            this.prevPage = ButtonWidget.builder(tr("gui.mtrlte.pids_xiamen.page_prev"), b -> turnPage(-1))
                    .dimensions(SIDE, importY, PAGER_W, ROW_H).build();
            this.prevPage.active = this.page > 0;
            this.addDrawableChild(this.prevPage);
            this.nextPage = ButtonWidget.builder(tr("gui.mtrlte.pids_xiamen.page_next"), b -> turnPage(1))
                    .dimensions(this.width - SIDE - PAGER_W, importY, PAGER_W, ROW_H).build();
            this.nextPage.active = this.page < pages - 1;
            this.addDrawableChild(this.nextPage);
        }
    }

    /** 翻页：只重排控件，不重开界面（勾选状态在 {@link #selected} 里）。 */
    private void turnPage(final int delta) {
        this.page = Math.max(0, this.page + delta);
        this.clearAndInit();
    }

    /**
     * 开原版资源包界面。
     *
     * <p><b>1.20.1 的坑</b>：{@code PackScreen.close()} 只做两件事 ——
     * {@code organizer.apply()}（把包栈落盘并回调构造时传进去的 applier）和关掉目录监听，
     * <b>它自己不切屏</b>。所以「完成」/ ESC 之后停在哪，完全由那个 applier 决定：
     * 我们原来的 applier 只刷新了资源包，结果界面永远退不出去（用户报的「完成按钮失效、ESC 也没用」）。
     * 现在在 applier 里把玩家送回本界面（{@code setScreen} 会重新跑 {@code init()}，
     * 于是分类表按新包重读一遍）。
     */
    private void openPackScreen() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        final PidsXiamenAdsScreen self = this;
        try {
            client.setScreen(new PackScreen(client.getResourcePackManager(),
                    manager -> {
                        try {
                            client.options.refreshResourcePacks(manager);
                        } catch (Throwable t) {
                            LteLog.LOGGER.warn("[lte] xiamen pids: could not apply the resource packs", t);
                        }
                        // 包栈变了：广告表 / 标语表立即失效，回到本界面时读到的是新包里的内容。
                        PidsXiamenAds.invalidate();
                        PidsXiamenTicker.invalidate();
                        client.setScreen(self);
                    },
                    client.getResourcePackDir(),
                    Text.translatable("resourcePack.title")));
        } catch (Throwable t) {
            // 打不开就只提示一句：界面不能因为一个按钮把玩家卡住。
            LteLog.LOGGER.warn("[lte] xiamen pids: could not open the resource pack screen", t);
            if (client.player != null) {
                client.player.sendMessage(tr("gui.mtrlte.pids_xiamen.import_pack_failed"), false);
            }
        }
    }

    // ------------------------------------------------------------------ 存

    /** 勾中的分类 id（空 = 默认分类）。 */
    private long[] selectedIds() {
        final long[] ids = new long[this.selected.size()];
        int i = 0;
        for (final Long id : this.selected) {
            ids[i++] = id.longValue();
        }
        return ids;
    }

    /** 先改客户端这份（屏上立刻变），再发给服务端写进方块实体。 */
    private void apply() {
        final long[] ids = selectedIds();
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.world != null
                    && client.world.getBlockEntity(this.dataPos) instanceof PidsXiamenBlockEntity pids) {
                pids.setAdCategories(ids);
            }
            if (ClientPlayNetworking.canSend(PidsXiamenNet.ID)) {
                ClientPlayNetworking.send(PidsXiamenNet.ID, PidsXiamenNet.categories(this.dataPos, ids));
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not send the advertisement categories", t);
        }
    }

    // ------------------------------------------------------------------ 画

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        // 标题：天津写在 y = 10（列表从 20 起，正好在下面）
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);

        if (this.categories.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlte.pids_xiamen.no_ad_categories"),
                    this.width / 2, this.height / 2 - 20, 0xFF9D9D);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("gui.mtrlte.pids_xiamen.no_ad_categories_hint"),
                    this.width / 2, this.height / 2 - 6, 0x909090);
        }

        super.render(context, mouseX, mouseY, delta);

        // 说明行：勾几个分类、一共几张、没勾等于什么
        final int adCount = countAds();
        context.drawCenteredTextWithShadow(this.textRenderer,
                tr("gui.mtrlte.pids_xiamen.ad_selected", this.selected.size(), adCount),
                this.width / 2, this.height - 80, this.selected.isEmpty() ? 0x909090 : 0xB0B0B0);
        context.drawCenteredTextWithShadow(this.textRenderer, tr("gui.mtrlte.pids_xiamen.ads_source"),
                this.width / 2, this.height - 14, 0x707070);
    }

    /** 当前勾选会产生几张广告（空选 = 默认分类那一套）。 */
    private int countAds() {
        try {
            return PidsXiamenAds.adsFor(selectedIds()).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 「完成」：勾选写回去，然后回上一屏。 */
    private void saveAndClose() {
        apply();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC = 放弃（天津的 CategorySelectorScreen 也不存）。「完成」走 {@link #saveAndClose()}。 */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
