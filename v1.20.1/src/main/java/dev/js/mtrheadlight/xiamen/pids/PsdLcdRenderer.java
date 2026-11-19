// PSD-LCD 挂板的渲染器：整屏交给 Java2D 画成位图（PsdLcdPainter），再贴到面板上。
//
// 为什么改成「离屏位图 + 动态贴图」而不是逐图元往上堆四边形：这块屏的元素有一半是**素材**
// （M 标志 / 车头 / 车厢卡片），素材必须原样贴；另一半是文字，必须按实拍的字形墨迹定位。
// 这两件事 Java2D 一次就做完了，而且和离线预览（tools/psd-lcd/PsdLcdPreview.java）用的是
// **同一份绘制代码** —— 预览图长什么样，游戏里就是什么样。
//
// 每帧只做三件事：
//   1. 从 MTR 数据拼出 PsdLcdPainter.Content；
//   2. 内容变了才重画（时钟 / 倒计时是分钟级，一般一分钟一次）；
//   3. 把动态贴图贴成一个四边形，再叠广告层和滚动标语层。
//
// 坐标系（和方块模型一致）：
//   translate(anchor 最左一格的左边缘, 面板上沿, 朝外 0.504 格)
//   rotY(90 - readDir.asRotation())   readDir = facing.rotateYCounterclockwise()
//   rotZ(180)                         ← 必须是 Z 轴 180°（写成 Y 轴会让字上下颠倒）
//   scale(1 / 520)                    之后 1 单位 = 1 屏幕像素，+x 向右、+y 向下
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.client.RenderDistance;
import mtr.MTRClient;
import mtr.client.ClientData;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.ScheduleEntry;
import mtr.data.Station;
import mtr.data.TrainClient;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.render.MoreRenderLayers;
import net.minecraft.block.BlockState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/** 厦门地铁 PSD-LCD 挂板（{@code mtrlte:psd_lcd_xiamen}）的方块实体渲染器。 */
public class PsdLcdRenderer extends BlockEntityRendererMapper<PsdLcdBlockEntity> {

    /** 广告层深度（px，负 = 更靠近观察者）。 */
    private static final float AD_Z = -1.40F;
    /** 滚动标语层深度（px）。 */
    private static final float TEXT_Z = -2.00F;
    /** 编组卡片的张数（没有实时编组数据时的示意）。 */
    private static final int DEFAULT_CARS = 4;
    /** 内容检查的最小间隔（ms）—— 没必要每帧都拼一遍字符串。 */
    private static final long CHECK_MS = 200L;

    private boolean warned;
    /** 「拿不到实时编组、退回 4 节」这条 warning 只打一次（consistOf 每帧都在跑）。 */
    private static boolean carsWarned;
    private long lastSweep;

    public PsdLcdRenderer(final BlockEntityRendererFactory.Context context) {
        super(context.getRenderDispatcher());
    }

    // ------------------------------------------------------------------ 视距

    @Override
    public boolean isInRenderDistance(final PsdLcdBlockEntity entity, final net.minecraft.util.math.Vec3d cameraPos) {
        return RenderDistance.inRange(entity, cameraPos);
    }

    @Override
    public boolean rendersOutsideBoundingBox(final PsdLcdBlockEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 64;
    }

    // ------------------------------------------------------------------ 入口

    @Override
    public void render(final PsdLcdBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        try {
            renderSafe(entity, matrices, vertexConsumers);
        } catch (final Throwable t) {
            if (!this.warned) {
                this.warned = true;
                LteLog.LOGGER.error("[lte] psd lcd render failed", t);
            }
        }
    }

    private void renderSafe(final PsdLcdBlockEntity entity, final MatrixStack matrices,
                            final VertexConsumerProvider vertexConsumers) {
        final World world = entity.getWorld();
        if (world == null) {
            return;
        }
        this.sweep();
        final BlockPos pos = entity.getPos();
        final BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof PsdLcdBlock block)) {
            return;
        }
        // 整条链只由最左一格画（每次渲染都会重新算，拆 / 接一格立刻生效）。
        final BlockPos anchor = PsdLcdBlock.anchorOf(world, pos);
        if (!anchor.equals(pos)) {
            return;
        }
        final Direction facing = state.get(PsdLcdBlock.FACING);
        final int segments = PsdLcdBlock.lengthOf(world, anchor);
        final TextRenderer textRenderer = net.minecraft.client.MinecraftClient.getInstance() == null
                ? null : net.minecraft.client.MinecraftClient.getInstance().textRenderer;
        if (textRenderer == null) {
            return;
        }

        final VertexConsumerProvider.Immediate immediate;
        final boolean flush;
        if (vertexConsumers instanceof VertexConsumerProvider.Immediate direct) {
            immediate = direct;
            flush = false;
        } else {
            immediate = net.minecraft.client.MinecraftClient.getInstance()
                    .getBufferBuilders().getEntityVertexConsumers();
            flush = true;
        }

        final Content content = contentOf(entity, world, anchor, segments);
        final int bitmapW = PsdLcdLayout.bitmapWidth(segments);
        final int bitmapH = PsdLcdLayout.bitmapHeight();
        final String key = world.getRegistryKey().getValue() + "|" + anchor.asLong() + "|" + segments;
        final PsdLcdTextures.Entry texture = PsdLcdTextures.acquire(key, bitmapW, bitmapH);
        if (texture == null) {
            return;
        }
        // 内容变了才重画（hash 里带上广告/标语状态，换广告时整屏也会跟着变）
        if (!content.hash().equals(texture.hash)) {
            final BufferedImage image = PsdLcdPainter.paint(content.content(), PsdLcdAssets.get(),
                    bitmapW, bitmapH);
            texture.hash = content.hash();
            PsdLcdTextures.upload(texture, image);
        }

        matrices.push();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        try {
            applyTransform(matrices, facing);
            final Matrix4f matrix = matrices.peek().getPositionMatrix();
            final Matrix3f normal = matrices.peek().getNormalMatrix();

            // 整屏
            final VertexConsumer screen = vertexConsumers.getBuffer(MoreRenderLayers.getLight(texture.id, false));
            texturedQuad(screen, matrix, normal, PsdLcdLayout.widthPx(segments), PsdLcdLayout.HEIGHT_PX, 0.0F);

            // 广告：叠在整屏前面（整屏那张图的广告位是空的，见 PsdLcdPainter#paintAd）
            if (content.ad() != null && content.ad().image() != null) {
                final float[] ad = PsdLcdPainter.adBox(PsdLcdLayout.widthPx(segments), PsdLcdLayout.HEIGHT_PX);
                matrices.push();
                try {
                    matrices.translate(0.0F, 0.0F, AD_Z);
                    final VertexConsumer vc = vertexConsumers.getBuffer(
                            MoreRenderLayers.getLight(content.ad().image(), false));
                    uvQuad(vc, matrices.peek().getPositionMatrix(), matrices.peek().getNormalMatrix(),
                            ad[0], ad[1], ad[2], ad[3]);
                } finally {
                    matrices.pop();
                }
            }

            // 滚动标语：MC 文字层（滚动是连续的，放进位图就得每帧重传贴图）
            if (content.caption() != null) {
                final float[] bar = PsdLcdPainter.tickerBox(PsdLcdLayout.widthPx(segments),
                        PsdLcdLayout.HEIGHT_PX);
                matrices.push();
                try {
                    matrices.translate(0.0F, 0.0F, TEXT_Z);
                    PidsXiamenTicker.draw(matrices, textRenderer, immediate,
                            new PidsXiamenLayout.Rect(bar[0], bar[1], bar[2], bar[3]),
                            content.tickerRatio(), content.tickerTick(), content.caption());
                } finally {
                    matrices.pop();
                }
            }
        } finally {
            matrices.pop();
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            if (flush) {
                immediate.draw();
            }
        }
    }

    private void sweep() {
        final long now = System.currentTimeMillis();
        if (now - this.lastSweep > 5_000L) {
            this.lastSweep = now;
            PsdLcdTextures.sweep();
        }
    }

    /** 屏幕的世界变换（见类注释）。{@code matrices} 进来时原点已经是 anchor 那一格的最小角。 */
    private static void applyTransform(final MatrixStack matrices, final Direction facing) {
        final Direction readDir = facing.rotateYCounterclockwise();
        final float x = 0.5F - readDir.getOffsetX() * 0.5F + facing.getOffsetX() * PsdLcdLayout.ANCHOR_OUT;
        final float z = 0.5F - readDir.getOffsetZ() * 0.5F + facing.getOffsetZ() * PsdLcdLayout.ANCHOR_OUT;
        // 面板中线在方块底面以上 5px（1.5 … 8.5 的中点），不再是「方块正中」——
        // 见 PsdLcdLayout 里从参考模组 / MTR 顶板面板量到的数。
        final float y = PsdLcdLayout.CENTER_Y_BLOCKS;
        matrices.translate(x, y, z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0F - readDir.asRotation()));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180.0F));
        final float scale = 1.0F / PsdLcdLayout.SCREEN_SCALE_DIVISOR;
        matrices.scale(scale, scale, scale);
    }

    // ------------------------------------------------------------------ 四边形

    /** 整张 uv 铺满一个矩形（整屏）。 */
    private static void texturedQuad(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                                     final float w, final float h, final float z) {
        vertexUv(vc, m, n, 0.0F, 0.0F, 0.0F, 0.0F, z);
        vertexUv(vc, m, n, 0.0F, h, 0.0F, 1.0F, z);
        vertexUv(vc, m, n, w, h, 1.0F, 1.0F, z);
        vertexUv(vc, m, n, w, 0.0F, 1.0F, 0.0F, z);
    }

    private static void uvQuad(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                               final float x0, final float y0, final float x1, final float y1) {
        vertexUv(vc, m, n, x0, y0, 0.0F, 0.0F, 0.0F);
        vertexUv(vc, m, n, x0, y1, 0.0F, 1.0F, 0.0F);
        vertexUv(vc, m, n, x1, y1, 1.0F, 1.0F, 0.0F);
        vertexUv(vc, m, n, x1, y0, 1.0F, 0.0F, 0.0F);
    }

    private static void vertexUv(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                                 final float x, final float y, final float u, final float v, final float z) {
        vc.vertex(m, x, y, z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, 0.0F, 0.0F, -1.0F)
                .next();
    }

    // ------------------------------------------------------------------ 内容

    /** 一帧的内容 + 广告 / 标语这两样附赠品 + 用来判断「要不要重画」的哈希。 */
    private record Content(PsdLcdPainter.Content content, PidsXiamenAds.Ad ad,
                           List<PidsXiamenTicker.Message> caption, long tickerTick,
                           float tickerRatio, String hash) {
    }

    private static Content contentOf(final PsdLcdBlockEntity entity, final World world, final BlockPos pos,
                                     final int segments) {
        final long gameTick = (long) MTRClient.getGameTick();
        final List<ScheduleEntry> schedules = PidsXiamenRenderer.schedulesFor(entity, pos);
        final int page = Math.max(0, entity.getDisplayPage());

        // ---- 时钟 / 日期
        final LocalDate date = PidsXiamenRenderer.dateNow(world);
        final String dateText = date.toString();
        final String weekdayZh = "星期" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1);
        final String weekdayEn = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        final String clock = PidsXiamenRenderer.formatTime(world);

        // ---- 两班到站
        final ScheduleEntry first = entryAt(schedules, page * 2);
        final ScheduleEntry second = entryAt(schedules, page * 2 + 1);
        final Route route = first == null ? null
                : ClientData.DATA_CACHE.routeIdMap.get(first.routeId);
        final Route route2 = second == null ? null
                : ClientData.DATA_CACHE.routeIdMap.get(second.routeId);

        final String[] dest1 = first == null || route == null
                ? new String[]{"", ""} : PidsXiamenRenderer.destinations(route, first);
        final String[] dest2 = second == null || route2 == null
                ? new String[]{"", ""} : PidsXiamenRenderer.destinations(route2, second);

        final PidsXiamenRenderer.Countdown count1 = first == null
                ? new PidsXiamenRenderer.Countdown("", "") : PidsXiamenRenderer.countdown(first);
        final PidsXiamenRenderer.Countdown count2 = second == null
                ? new PidsXiamenRenderer.Countdown("", "") : PidsXiamenRenderer.countdown(second);
        // 单位：分钟 / min（数字为空时单位也留空，屏上不会出现孤零零的「分钟」）
        final String unitZh = count1.number().isEmpty() ? "" : unitOf(count1.unit(), true);
        final String unitEn = count1.number().isEmpty() ? "" : unitOf(count1.unit(), false);

        // ---- 线路徽标（取第一班的线路色）
        final String lineZh;
        final String lineEn;
        final int lineColor;
        if (route == null) {
            lineZh = "";
            lineEn = "";
            lineColor = 0xFF2B36C8;
        } else {
            final String raw = PidsXiamenRenderer.firstPart(route.name);
            lineZh = raw;
            final String digits = raw.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                lineEn = Text.translatable("gui.mtrlte.psd_lcd.line_en", digits).getString();
            } else {
                final int bar = route.name.indexOf('|');
                lineEn = bar < 0 ? "" : route.name.substring(bar + 1).trim();
            }
            lineColor = (route.color & 0xFFFFFF) == 0 ? 0xFF2B36C8 : (0xFF000000 | (route.color & 0xFFFFFF));
        }

        // ---- 当前站
        String stationZh = "";
        String stationEn = "";
        Station station = null;
        try {
            station = RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos);
            if (station != null) {
                final String[] parts = station.name.split("\\|", -1);
                stationZh = parts.length > 0 ? parts[0].trim() : "";
                stationEn = parts.length > 1 ? parts[1].trim() : "";
            }
        } catch (final Throwable ignored) {
            // 车站表还没同步：站名留空，其余照画。
        }
        // 没有站名数据时退回示意里的占位字「当前站 / This station」——
        // 这样空站也不会在那一格留一片空白（有数据时显示真实站名，和实拍一致）。
        if (stationZh.isEmpty()) {
            stationZh = Text.translatable("gui.mtrlte.psd_lcd.this_station_zh").getString();
        }
        if (stationEn.isEmpty()) {
            stationEn = Text.translatable("gui.mtrlte.psd_lcd.this_station_en").getString();
        }

        // ---- 编组：按 MTR 里「这一站 / 这条线」正在跑的那列车读实际节数（拿不到才退回 4 节）
        final int cars = consistOf(station, first == null ? 0L : first.routeId);
        final List<String> carNumbers = new java.util.ArrayList<>(cars);
        for (int i = 1; i <= cars; i++) {
            carNumbers.add(String.valueOf(i));
        }

        // ---- 广告 + 标语
        final List<PidsXiamenAds.Ad> ads = PidsXiamenSettings.applyAdOverride(entity.getAds());
        final float[] bar = PsdLcdPainter.tickerBox(PsdLcdLayout.widthPx(segments), PsdLcdLayout.HEIGHT_PX);
        final PidsXiamenLayout.Rect rect = new PidsXiamenLayout.Rect(bar[0], bar[1], bar[2], bar[3]);
        final Chosen chosen = adAt(ads, entity.getAdvertisementIndex(), gameTick, rect);
        final PidsXiamenAds.Ad ad = chosen == null ? null : chosen.ad();
        final boolean hasAd = ad != null && ad.image() != null;
        final List<PidsXiamenTicker.Message> caption = hasAd && ad.text() != null
                && !ad.text().getString().isBlank()
                ? List.of(new PidsXiamenTicker.Message(ad.text().getString(), PidsXiamenLayout.COLOR_WHITE, 0.0F))
                : null;
        final long tickerTick = chosen == null ? gameTick : chosen.tick();
        final float tickerRatio = PsdLcdPainter.TICK_SIZE_RATIO;

        final PsdLcdPainter.Content content = new PsdLcdPainter.Content(
                dateText, weekdayZh, weekdayEn, clock,
                Text.translatable("gui.mtrlte.pids.bound_for_label").getString(),
                Text.translatable("gui.mtrlte.pids.bound_for_sub").getString(),
                dest1[0], dest1[1], count1.number(),
                Text.translatable("gui.mtrlte.psd_lcd.next_train_label").getString(),
                Text.translatable("gui.mtrlte.pids.next_train_sub").getString(),
                dest2[0], dest2[1], count2.number(),
                unitZh, unitEn,
                lineZh, lineEn, lineColor,
                stationZh, stationEn,
                carNumbers,
                Text.translatable("gui.mtrlte.psd_lcd.place_ad").getString(),
                // 标语文字只给离线预览用；游戏里由 MC 文字层滚动，位图里不画
                "",
                hasAd);

        // 哈希：只有这些变了才需要重画位图
        final String hash = content + "|" + segments + "|" + (hasAd ? ad.image() : "noad");
        return new Content(content, ad, caption, tickerTick, tickerRatio, hash);
    }

    /**
     * 这一站 / 这条线上正在跑的那列车的<b>实际编组节数</b>（MTR 的 {@code Train#trainCars}）。
     *
     * <p>找法（越靠前越优先）：
     * <ol>
     *   <li>司机室里正停在这个站、并且跑的是第一班那条线路的车 → 用它；</li>
     *   <li>停在这个站的任意一列车（跨线车 / 折返车）；</li>
     *   <li>这条线路上任意一列在跑的车。</li>
     * </ol>
     * 三条都落空（单机存档还没发车、或者客户端还没同步列车）时退回
     * {@link #DEFAULT_CARS} 节，并且<b>只 warning 一次</b>（这条路径每 200ms 走一次，不能刷屏）。
     */
    private static int consistOf(final Station station, final long routeId) {
        try {
            int atStationAny = 0;
            int onRouteAny = 0;
            for (final TrainClient train : ClientData.TRAINS) {
                if (train == null || train.trainCars <= 0) {
                    continue;
                }
                final Route route = train.getThisRoute();
                final boolean onRoute = routeId > 0L && route != null && route.id == routeId;
                if (onRoute && train.trainCars > onRouteAny) {
                    onRouteAny = train.trainCars;
                }
                if (!sameStation(train.getThisStation(), station)) {
                    continue;
                }
                if (onRoute) {
                    return train.trainCars;
                }
                atStationAny = Math.max(atStationAny, train.trainCars);
            }
            if (atStationAny > 0) {
                return atStationAny;
            }
            if (onRouteAny > 0) {
                return onRouteAny;
            }
        } catch (final Throwable t) {
            // 列车表正在被服务端包改写：这一帧按「拿不到」处理，下一帧再试。
        }
        if (!carsWarned) {
            carsWarned = true;
            LteLog.LOGGER.warn("[lte] psd lcd: no live train at this platform, "
                    + "falling back to {} cars for the consist strip", DEFAULT_CARS);
        }
        return DEFAULT_CARS;
    }

    /** 两个 Station 是不是同一个（按 id；任一为 null 就是 false）。 */
    private static boolean sameStation(final Station a, final Station b) {
        return a != null && b != null && a.id == b.id;
    }

    /** 倒计时单位：中文写「分钟 / 秒」，英文写「min / s」。 */
    private static String unitOf(final String zhUnit, final boolean chinese) {
        if (zhUnit == null || zhUnit.isEmpty()) {
            return "";
        }
        final String minute = Text.translatable("gui.mtrlte.pids.minute").getString();
        final String second = Text.translatable("gui.mtrlte.pids.second").getString();
        if (zhUnit.equals(minute)) {
            return chinese ? zhUnit : Text.translatable("gui.mtrlte.psd_lcd.min_en").getString();
        }
        if (zhUnit.equals(second)) {
            return chinese ? zhUnit : Text.translatable("gui.mtrlte.psd_lcd.sec_en").getString();
        }
        return chinese ? zhUnit : "";
    }

    private static ScheduleEntry entryAt(final List<ScheduleEntry> schedules, final int index) {
        return index >= 0 && index < schedules.size() ? schedules.get(index) : null;
    }

    /** 这一帧该播第几张广告、播到第几个 tick（时序与厦门 PIDS 一致：标语滚完才换）。 */
    private record Chosen(PidsXiamenAds.Ad ad, long tick) {
    }

    private static Chosen adAt(final List<PidsXiamenAds.Ad> ads, final int adIndex,
                               final long gameTick, final PidsXiamenLayout.Rect bar) {
        if (!PidsXiamenSettings.advertisements() || ads == null || ads.isEmpty()) {
            return null;
        }
        final TextRenderer textRenderer = net.minecraft.client.MinecraftClient.getInstance() == null
                ? null : net.minecraft.client.MinecraftClient.getInstance().textRenderer;
        if (textRenderer == null) {
            return null;
        }
        final int count = ads.size();
        final PidsXiamenAds.Ad[] order = new PidsXiamenAds.Ad[count];
        final int[] dwell = new int[count];
        long total = 0L;
        for (int slot = 0; slot < count; slot++) {
            final int index = Math.floorMod(adIndex + slot, count);
            order[slot] = ads.get(index);
            final PidsXiamenAds.Ad ad = order[slot];
            final List<PidsXiamenTicker.Message> caption = ad == null || ad.text() == null
                    || ad.text().getString().isBlank() ? null
                    : List.of(new PidsXiamenTicker.Message(ad.text().getString(),
                            PidsXiamenLayout.COLOR_WHITE, 0.0F));
            final int ticks = PidsXiamenTicker.scrollSpanTicks(textRenderer,
                    caption == null ? PidsXiamenTicker.messages() : caption, bar, PsdLcdPainter.TICK_SIZE_RATIO);
            dwell[slot] = ticks > 0 ? ticks : PidsXiamenLayout.AD_ADVANCE_TICKS;
            total += dwell[slot];
        }
        if (total <= 0L) {
            return null;
        }
        long t = Math.floorMod(gameTick, total);
        for (int slot = 0; slot < count; slot++) {
            if (t < dwell[slot]) {
                final PidsXiamenAds.Ad ad = order[slot];
                if (ad == null || ad.image() == null) {
                    return null;
                }
                return new Chosen(ad, t);
            }
            t -= dwell[slot];
        }
        return null;
    }
}
