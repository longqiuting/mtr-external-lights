// PSD-LCD 挂板的渲染器：整屏交给 Java2D 画成**一张**位图（PsdLcdPainter），再贴成一个四边形。
//
// 只有一个绘制层。以前这里还有「广告层」「滚动标语层」两个额外的 quad / MC 文字层，
// 结果屏幕上的字出现两遍（同位图里的字 + 叠在上面的文字层）、而且三个层各自的深度与
// 尺寸都对不上 —— 现在全部并进位图：文字 / 图标 / 标语 / 广告文字都画在同一个 BufferedImage
// 里，渲染器只发一个四边形。
//
// 四边形的四个顶点**就是**模型里 ScreenPlane 那块面的四个角，由
// PsdLcdLayout#windowQuad 算出（不做任何矩阵旋转）。所以：
//   · 窗口矩形 x 0…16、y 1.5…8.5、z = 1.6（屏幕面）——不多一个像素；
//   · 位图宽高比 = 窗口宽高比（16:7 / 段），内容不会被拉伸（见 tools/psd-lcd-fit-check.js）。
//
// 旧版错在把「屏幕面中线 y = 5px」当成了四边形原点，再 rotZ(180) 让画面往下长，
// 于是 7px 高的画面从 y=5px 一直挂到 y=-2px（吊板下面），看起来像「多画了一遍」。
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
import mtr.client.ClientData;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.ScheduleEntry;
import mtr.data.Station;
import mtr.data.TrainClient;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.render.MoreRenderLayers;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/** 厦门地铁 PSD-LCD 挂板（{@code mtrlte:psd_lcd_xiamen}）的方块实体渲染器。 */
public class PsdLcdRenderer extends BlockEntityRendererMapper<PsdLcdBlockEntity> {

    /** 编组卡片的张数（没有实时编组数据时的示意）。 */
    private static final int DEFAULT_CARS = 4;

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
        if (!(state.getBlock() instanceof PsdLcdBlock)) {
            return;
        }
        // 整条链只由最左一格画（每次渲染都会重新算，拆 / 接一格立刻生效）。
        final BlockPos anchor = PsdLcdBlock.anchorOf(world, pos);
        if (!anchor.equals(pos)) {
            return;
        }
        final Direction facing = state.get(PsdLcdBlock.FACING);
        final PsdLcdLayout.Face face = faceOf(facing);
        final int segments = PsdLcdBlock.lengthOf(world, anchor);

        final Content content = contentOf(entity, world, anchor, segments);
        final int bitmapW = PsdLcdLayout.bitmapWidth(segments);
        final int bitmapH = PsdLcdLayout.bitmapHeight();
        final String key = world.getRegistryKey().getValue() + "|" + anchor.asLong() + "|" + segments;
        final PsdLcdTextures.Entry texture = PsdLcdTextures.acquire(key, bitmapW, bitmapH);
        if (texture == null) {
            return;
        }
        // 内容变了才重画（时钟 / 倒计时是分钟级，一般一分钟一次）
        if (!content.hash().equals(texture.hash)) {
            final BufferedImage image = PsdLcdPainter.paint(content.content(), PsdLcdAssets.get(),
                    content.ad(), bitmapW, bitmapH);
            texture.hash = content.hash();
            PsdLcdTextures.upload(texture, image);
        }

        // 四边形顶点 = 模型里 ScreenPlane 的四个角（世界坐标，相对 anchor 那一格的最小角）。
        final float[][] quad = PsdLcdLayout.windowQuad(face, segments);
        final float[] normal = PsdLcdLayout.screenNormal(face);
        final Matrix4f matrix = matrices.peek().getPositionMatrix();
        final Matrix3f normalMatrix = matrices.peek().getNormalMatrix();

        // MTR 有时递过来的不是 Immediate（它自己的队列）：退回客户端的实体缓冲区并自己 flush。
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

        matrices.push();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        try {
            final VertexConsumer vc = immediate.getBuffer(MoreRenderLayers.getLight(texture.id, false));
            vertex(vc, matrix, normalMatrix, quad[0][0], quad[0][1], quad[0][2], 0.0F, 0.0F, normal);
            vertex(vc, matrix, normalMatrix, quad[1][0], quad[1][1], quad[1][2], 0.0F, 1.0F, normal);
            vertex(vc, matrix, normalMatrix, quad[2][0], quad[2][1], quad[2][2], 1.0F, 1.0F, normal);
            vertex(vc, matrix, normalMatrix, quad[3][0], quad[3][1], quad[3][2], 1.0F, 0.0F, normal);
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            matrices.pop();
            if (flush) {
                immediate.draw();
            }
        }
    }

    /** 方块状态的 {@code FACING} → 面板几何用的 {@link PsdLcdLayout.Face}。 */
    private static PsdLcdLayout.Face faceOf(final Direction facing) {
        if (facing == null) {
            return PsdLcdLayout.Face.NORTH;
        }
        return switch (facing) {
            case SOUTH -> PsdLcdLayout.Face.SOUTH;
            case EAST -> PsdLcdLayout.Face.EAST;
            case WEST -> PsdLcdLayout.Face.WEST;
            default -> PsdLcdLayout.Face.NORTH;
        };
    }

    private void sweep() {
        final long now = System.currentTimeMillis();
        if (now - this.lastSweep > 5_000L) {
            this.lastSweep = now;
            PsdLcdTextures.sweep();
        }
    }

    // ------------------------------------------------------------------ 顶点

    private static void vertex(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                               final float x, final float y, final float z,
                               final float u, final float v, final float[] normal) {
        vc.vertex(m, x, y, z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, normal[0], normal[1], normal[2])
                .next();
    }

    // ------------------------------------------------------------------ 内容

    /** 一帧的内容 + 用来判断「要不要重画位图」的哈希。 */
    private record Content(PsdLcdPainter.Content content, BufferedImage ad, String hash) {
    }

    private static Content contentOf(final PsdLcdBlockEntity entity, final World world, final BlockPos pos,
                                     final int segments) {
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

        // ---- 标语条的文字：**画进位图**（静态），不再走 MC 文字层。
        // 广告区画的是同一张广告（有图就铺图，没图就洋红占位块 + 「广告位」）。
        final PidsXiamenAds.Ad ad = adOf(entity);
        final BufferedImage adImage = ad == null ? null : PsdLcdAssets.ad(ad.image());
        final String ticker = ad == null || ad.text() == null ? "" : ad.text().getString();

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
                Text.translatable("gui.mtrlte.psd_lcd.consist_note").getString(),
                ticker,
                Text.translatable("gui.mtrlte.psd_lcd.place_ad").getString());

        // 哈希：只有这些变了才需要重画位图（广告图换了也要重画，所以把图片 id 也带上）
        final String adKey = ad == null || ad.image() == null ? "-" : ad.image().toString();
        return new Content(content, adImage, content + "|" + segments + "|" + adKey);
    }

    /**
     * 这块屏当前要显示的广告（和厦门 PIDS 同一套：全局覆盖优先，其次这块屏自己勾的分类，
     * 再按 {@code advertisementIndex} 轮播）。广告关掉 / 表是空的 → {@code null}。
     */
    private static PidsXiamenAds.Ad adOf(final PsdLcdBlockEntity entity) {
        try {
            if (!PidsXiamenSettings.advertisements()) {
                return null;
            }
            final List<PidsXiamenAds.Ad> ads = PidsXiamenSettings.applyAdOverride(entity.getAds());
            if (ads == null || ads.isEmpty()) {
                return null;
            }
            return ads.get(Math.floorMod(entity.getAdvertisementIndex(), ads.size()));
        } catch (final Throwable ignored) {
            return null;
        }
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
}
