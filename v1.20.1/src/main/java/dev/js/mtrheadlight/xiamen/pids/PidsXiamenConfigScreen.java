// 刷子打开的「厦门 PIDS」界面：站台筛选 + 显示页码 + 广告序号。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.HashSet;
import java.util.Set;

import dev.js.mtrheadlight.LteLog;
import mtr.client.IDrawing;
import mtr.mappings.ScreenMapper;
import mtr.packet.PacketTrainDataGuiClient;
import mtr.screen.PIDSConfigScreen;
import mtr.screen.WidgetBetterCheckbox;
import mtr.screen.WidgetBetterTextField;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 拿刷子右键厦门 PIDS 打开的界面。
 *
 * <h2>为什么不用 MTR 原版的 {@code PIDSConfigScreen}</h2>
 * 那个界面是「每行一条自定义文字 + 隐藏到站」，厦门的版式是固定的三行 / 两行标签（见
 * {@link PidsXiamenLayout}），没有地方放那几条文字；玩家真正要改的只有三件事：
 * <ol>
 *   <li><b>这块屏听哪个站台</b> —— 默认「自动检测附近站台」（{@code platformIds} 留空），
 *       也可以在列表里自己挑（复用 MTR 的 {@code DashboardListSelectorScreen}）；</li>
 *   <li><b>显示第几页</b> —— 到站多于一屏时从第几条开始；</li>
 *   <li><b>播哪些广告</b> —— 天津那颗「播放广告」按钮的对应物：打开
 *       {@link PidsXiamenAdsScreen} 勾广告分类（广告表里的顶层 key），
 *       勾完写进方块实体的 {@code xiamen_ad_categories}；不勾 = 默认分类 {@code mtrlte}。</li>
 * </ol>
 * 页面上不再有「自定义文字」：那块区域已经被版式占满（左列标签 + 右列终点站 / 倒计时）。
 *
 * <h2>数据怎么回去</h2>
 * <ul>
 *   <li>站台 + 页码：{@link PacketTrainDataGuiClient#sendPIDSConfigC2S}（MTR 自己的包，
 *       服务端 {@code receivePIDSMessageC2S} 写回方块实体并同步）—— 关界面时发一次，和 MTR 一样；</li>
 *   <li>广告分类：{@link PidsXiamenNet#categories}，点「完成」立刻发（要马上看到换图）。</li>
 * </ul>
 *
 * <h2>为什么界面是服务端叫开的</h2>
 * {@code platformIds} / {@code displayPage} 是<b>服务端</b>数据。刷子按下时服务端先
 * {@code syncData()} 两半，再发 {@link PidsXiamenNet#openScreen}；这里构造时读到的就是刚同步的值。
 * 直接在客户端开界面会读到旧值，然后关界面时把旧值写回去 —— 一按刷子就把绑定冲掉。
 */
public final class PidsXiamenConfigScreen extends ScreenMapper {

    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int ROW_H = 20;
    /** 最宽 360，窄窗口时留 24px 边。 */
    private static final int MAX_COLUMN = 360;
    private static final int FOOTER_H = 34;

    private final BlockPos dataPos;
    private final BlockPos partnerPos;

    /** 界面自己的一份拷贝：MTR 的 {@code getPlatformIds()} 返回的是实体里的活集合，不能直接改。 */
    private final Set<Long> platformIds = new HashSet<>();
    /** 打开时的那一份：关界面时只有真的改过才发回去（数据还没同步到客户端时不要凭空清空）。 */
    private final Set<Long> initialPlatforms = new HashSet<>();
    private int displayPage;
    private int initialPage;
    /** 勾选的广告分类（打开时拍一份，只用来显示「已选 N 个」）。 */
    private long[] adCategories = new long[0];
    /** 这块屏实际会播几张广告（勾选的分类拼接后的条数）。 */
    private int adCount;

    private WidgetBetterCheckbox selectAll;
    private ButtonWidget filterButton;
    private ButtonWidget adsButton;
    private WidgetBetterTextField pageInput;
    /** 这个位置附近有没有车站（没有的话「选择站台」按钮置灰）。 */
    private boolean hasStation;

    private int colX;
    private int colW;
    /** 各控件实际的 y（render() 里按这个画标签，不再用「反推行高」那套魔法数）。 */
    private int platformY;
    private int filterY;
    private int noStationY;
    private int pageY;
    private int adsY;

    public PidsXiamenConfigScreen(final BlockPos dataPos, final BlockPos partnerPos) {
        super(tr("gui.mtrlte.pids_xiamen.config_title"));
        this.dataPos = dataPos;
        this.partnerPos = partnerPos;
        readEntity();
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    /** 从（刚同步过的）方块实体里取初值；拿不到就用默认值。 */
    private void readEntity() {
        try {
            final var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.world == null
                    || !(client.world.getBlockEntity(this.dataPos) instanceof PidsXiamenBlockEntity pids)) {
                return;
            }
            final Set<Long> live = pids.getPlatformIds();
            if (live != null) {
                this.platformIds.addAll(live);
                this.initialPlatforms.addAll(live);
            }
            this.displayPage = Math.max(0, pids.getDisplayPage());
            this.initialPage = this.displayPage;
            readAds(pids);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not read the block entity for the screen", t);
        }
    }

    // ------------------------------------------------------------------ 布局

    /**
     * 「自动检测附近站台」那个勾选框：<b>只建一次</b>。
     *
     * <p>MTR 的 {@code getPlatformFilterButton} 会把这个实例交给列表选择屏，选完回来时
     * {@code setChecked(...)} 写的还是它；如果每次 {@code init()} 都新建一个，回调写的是旧实例，
     * 勾选状态就和屏幕上的对不上。
     */
    private WidgetBetterCheckbox selectAll() {
        if (this.selectAll == null) {
            this.selectAll = new WidgetBetterCheckbox(0, 0, 0, ROW_H,
                    tr("gui.mtr.automatically_detect_nearby_platform"), checked -> {
                        if (checked) {
                            this.platformIds.clear();
                        }
                    });
        }
        return this.selectAll;
    }

    /** 这个位置有没有车站（MTR 的站台表按车站取；站外就选不了）。 */
    private boolean hasStation() {
        try {
            return mtr.data.RailwayData.getStation(mtr.client.ClientData.STATIONS,
                    mtr.client.ClientData.DATA_CACHE, this.dataPos) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void init() {
        // 从站台列表回来会再跑一次 init()：先把输入框里已经打好的页码收回来，别丢。
        if (this.pageInput != null) {
            this.displayPage = parsePage(this.pageInput.getText(), this.displayPage);
        }
        // 从「选择广告」回来也走 init()：把最新的勾选 / 张数读回来再画按钮文字。
        refreshAds();
        this.colW = Math.min(MAX_COLUMN, this.width - 48);
        this.colX = this.width / 2 - this.colW / 2;

        // 每一行：标签 12px + 控件 20px + 8px 余量 = 30。标签单独占一行，
        // 不再「上一行的控件 + 下一行的标签」挤在同一段 y 上（用户报的「按钮与文字重叠」）。
        int y = 66;

        // 1) 自动检测附近站台（勾上 = 清空筛选，和 MTR 的语义一致）
        this.platformY = y;
        final WidgetBetterCheckbox box = selectAll();
        IDrawing.setPositionAndWidth(box, this.colX, y, this.colW);
        box.setChecked(this.platformIds.isEmpty());
        this.addDrawableChild(box);
        y += 30;

        // 2) 选择站台…（复用 MTR 的列表选择器；关掉时把勾选状态同步回来）
        this.hasStation = hasStation();
        this.filterY = y;
        this.filterButton = PIDSConfigScreen.getPlatformFilterButton(
                this.dataPos, this.selectAll, this.platformIds, this);
        if (this.filterButton != null) {
            IDrawing.setPositionAndWidth(this.filterButton, this.colX, y, this.colW);
            this.filterButton.setMessage(tr("gui.mtrlte.pids_xiamen.pick_platforms"));
            // 附近没有车站时列表必然是空的：置灰，别让玩家点了没反应。
            this.filterButton.active = this.hasStation;
            this.addDrawableChild(this.filterButton);
        }
        y += 30;

        // 没有车站：红字单独占一行，画在按钮下面（以前画在按钮正中间，直接叠字）。
        this.noStationY = y - 4;
        if (!this.hasStation) {
            y += 16;
        }

        // 3) 显示页码（标签在左边，输入框靠右，垂直居中对齐）
        this.pageY = y;
        this.pageInput = new WidgetBetterTextField(
                WidgetBetterTextField.TextFieldFilter.POSITIVE_INTEGER, "1", 3);
        this.pageInput.setText(String.valueOf(this.displayPage + 1));
        IDrawing.setPositionAndWidth(this.pageInput, this.colX + 96, y, 56);
        this.addDrawableChild(this.pageInput);
        y += 30;

        // 4) 广告：打开「选择广告」；勾选状态直接写进按钮文字（省掉一行会和上面重叠的标签）
        this.adsY = y;
        this.adsButton = ButtonWidget.builder(adsLabel(), b -> openAds())
                .dimensions(this.colX, y, this.colW, ROW_H).build();
        this.addDrawableChild(this.adsButton);
        y += 30;

        // 底部：完成（close() 里统一发配置，别在这里再发一次）
        final int fy = this.height - FOOTER_H;
        this.addDrawableChild(ButtonWidget.builder(tr("gui.done"), b -> close())
                .dimensions(this.colX, fy, this.colW, ROW_H).build());
    }

    /** 广告按钮上的文字：勾了几个分类、这块屏一共会播几张。 */
    private MutableText adsLabel() {
        return tr("gui.mtrlte.pids_xiamen.pick_ads_count", this.adCategories.length, this.adCount);
    }

    // ------------------------------------------------------------------ 广告

    /** 广告分类 + 实际张数（只刷新这两样，不动站台 / 页码的初值快照）。 */
    private void readAds(final PidsXiamenBlockEntity pids) {
        try {
            this.adCategories = pids.getAdCategories();
            this.adCount = PidsXiamenAds.adsFor(this.adCategories).size();
        } catch (Throwable t) {
            this.adCategories = new long[0];
            this.adCount = 0;
        }
    }

    /** 从客户端那份方块实体重读广告状态；读不到就维持上一次的值（不清零）。 */
    private void refreshAds() {
        try {
            final var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client != null && client.world != null
                    && client.world.getBlockEntity(this.dataPos) instanceof PidsXiamenBlockEntity pids) {
                readAds(pids);
                if (this.adsButton != null) {
                    this.adsButton.setMessage(adsLabel());
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] xiamen pids: could not refresh the advertisement state", t);
        }
    }

    /**
     * 打开「选择广告」（{@link PidsXiamenAdsScreen}）。
     *
     * <p>打开前重新读一次分类：在广告界面里点过「完成」再回来时，实体已经被同步过，
     * 这里得跟着刷新，否则那行「已选 N 个」还是旧数字。
     */
    private void openAds() {
        if (this.client == null) {
            return;
        }
        try {
            final var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.world != null
                    && client.world.getBlockEntity(this.dataPos) instanceof PidsXiamenBlockEntity pids) {
                readAds(pids);
            }
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] xiamen pids: could not refresh the advertisement state", t);
        }
        this.client.setScreen(new PidsXiamenAdsScreen(this.dataPos, this));
    }

    // ------------------------------------------------------------------ 存

    private static String readMessage(final PidsXiamenBlockEntity pids, final int index) {
        try {
            final String text = pids.getMessage(index);
            return text == null ? "" : text;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean readHideArrival(final PidsXiamenBlockEntity pids, final int index) {
        try {
            return pids.getHideArrival(index);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 输入框里的「第几页」（1 起）→ 内部页码（0 起）；打不出来就保留 {@code fallback}。 */
    private static int parsePage(final String text, final int fallback) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()) - 1);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 站台 + 页码 + （原样带回）文字 / 隐藏到站：一条 MTR 的 PIDS 配置包。 */
    private void sendToServer() {
        try {
            if (this.selectAll != null && this.selectAll.isChecked()) {
                this.platformIds.clear();
            }
            final int page = this.pageInput == null
                    ? this.displayPage : parsePage(this.pageInput.getText(), this.displayPage);
            final String[] messages;
            final boolean[] hideArrival;
            final var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.world != null
                    && client.world.getBlockEntity(this.dataPos) instanceof PidsXiamenBlockEntity pids) {
                final int maxArrivals = Math.max(0, pids.getMaxArrivals());
                final int lines = Math.max(1, pids.getLinesPerArrival());
                messages = new String[maxArrivals * lines];
                for (int i = 0; i < messages.length; i++) {
                    // getMessage 读的是实体里那个定长数组：长度对不上时别让界面开不起来。
                    messages[i] = readMessage(pids, i);
                }
                hideArrival = new boolean[maxArrivals];
                for (int i = 0; i < maxArrivals; i++) {
                    hideArrival[i] = readHideArrival(pids, i);
                }
            } else {
                messages = new String[0];
                hideArrival = new boolean[0];
            }
            PacketTrainDataGuiClient.sendPIDSConfigC2S(this.dataPos, this.partnerPos,
                    messages, hideArrival, this.platformIds, page);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not send the config", t);
        }
    }

    // ------------------------------------------------------------------ 画

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        // 标签按 init() 记下的控件位置画，一律在控件<b>上方</b> 12px；
        // 不再「用固定行高反推」，也就不会出现「下一行的标签压在上一行的控件上」。
        context.drawTextWithShadow(this.textRenderer,
                tr("gui.mtrlte.pids_xiamen.platforms", this.platformIds.size()),
                this.colX, this.platformY - 12, 0xB0B0B0);
        if (!this.hasStation) {
            context.drawTextWithShadow(this.textRenderer,
                    tr("gui.mtrlte.pids_xiamen.no_station"), this.colX, this.noStationY, 0xFF9D9D);
        }
        context.drawTextWithShadow(this.textRenderer, tr("gui.mtrlte.pids_xiamen.display_page"),
                this.colX, this.pageY + 6, 0xFFFFFF);
        // 广告的「已选 N 个分类 / 共 M 张」写在按钮文字里（见 adsLabel），这里不再另画一行。

        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 24, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                tr("gui.mtrlte.pids_xiamen.hint"), this.width / 2, 40, 0x909090);
        context.drawCenteredTextWithShadow(this.textRenderer, tr("hint.author"),
                this.width / 2, this.height - 14, 0xB0B0B0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /**
     * ESC / 完成 都走这里：<b>只有真的改过</b>才把站台 / 页码发回去，然后回上一屏。
     *
     * <p>「改过才发」不是省流量：界面构造时读的是客户端那份方块实体，方块实体还没同步过来时是空的 ——
     * 无条件发一条空配置等于一按刷子就把绑定清成「自动检测」。
     */
    @Override
    public void close() {
        final int page = this.pageInput == null
                ? this.displayPage : parsePage(this.pageInput.getText(), this.displayPage);
        final boolean changed = page != this.initialPage
                || !this.platformIds.equals(this.initialPlatforms);
        if (changed) {
            sendToServer();
        }
        if (this.client != null) {
            this.client.setScreen(null);
        }
    }
}
