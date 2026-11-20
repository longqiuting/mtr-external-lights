// 厦门 PIDS 的屏幕渲染器：整屏交给 Java2D 画成一张动态贴图（PidsXiamenPainter），本类只负责
// 「取数据 → 拼内容 → 上传贴图 → 发四边形」。
//
// ## 为什么改成函数绘制
// 老路子是底图里**烘好文字**（pids_xiamen_metro.png 自带「开往 / 终点站 / 分钟」），
// 换一句话就要重导 PNG，而且中文字走资源包字体 —— 缺字时整行直接不显示。
// 现在底图只是**纯版式底板**（只有色块 / 描边框，见用户给的两张新图），
// 文字 / 数字 / 广告 / 编组卡片全部由 PidsXiamenPainter 画进位图，
// 沿用 PSD-LCD 吊板那一套：内容哈希去重 + 动态贴图 + F3+T 失效重建 + 30 秒 TTL 回收
// （{@link PsdLcdTextures}，两个屏幕共用同一个缓存）。
//
// ## 一帧画什么
// <ol>
//   <li><b>整屏四边形</b>：512×235 的动态贴图（底图 + 标志 + 日期时间 + 到站行 + 编组 + 广告）。
//       内容没变就不重画、不上传。</li>
//   <li><b>滚动标语四边形</b>：标语条「框内」那一小块**透明底**小贴图，每 tick 只在滚过一个像素时
//       重画 / 重传。速度走 {@link PidsXiamenTicker#pxPerTick(float)}（全模组唯一一份换算），
//       所以字大的屏滚得也快。</li>
// </ol>
// 广告图**烘进位图**（不再是单独一层 quad）：它本来就是一张 PNG（{@link PidsXiamenAds.Ad#image()}），
// 由 {@link PsdLcdAssets#ad(Identifier)} 读成 BufferedImage 再画进去 —— 于是屏上只有一个绘制层，
// 不会再出现「广告 / 文字两层各自深度不一样」的老问题。
//
// ## 屏幕坐标系（改这里之前先读这段）
// 底图就是 512×235 px 的矩形平面，局部坐标「GUI 风格」：+X = 屏幕向右、+Y = 屏幕向下、
// +Z = 扎进屏幕里；原点 = 屏幕左上角。变换链见 {@link #applyScreenTransform}。
//
// ## 两面都画
// 模型是 V 形双面屏：第一面朝 {@code facing.rotateYClockwise()}，画在本格；第二面朝反方向，
// 画在 partner 格。只有「存数据的那一半」（{@code facing ∈ {SOUTH, WEST}}）才会走到这里，
// 否则同一块屏会被画两遍（而且镜像）—— 见 {@link PidsXiamenBlock#canStoreData}。
//
// ## 渲染状态：一个全局 GL 状态都不碰
// 矩阵 push/pop 都在 try/finally 里；不调 enableCull / depthMask 之类的「复位」
// （上一版那样做会把下一个 BER 正在依赖的状态按默认值覆盖掉，看着就是「PIDS 周围的方块变透明」）。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.bind.PidsStationResolver;
import mtr.MTRClient;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.ScheduleEntry;
import mtr.data.Station;
import mtr.data.TrainClient;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.render.MoreRenderLayers;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/** 厦门 PIDS（地铁 {@code mtrlte:pids_xiamen_metro} / BRT {@code mtrlte:pids_xiamen_brt}）的渲染器。 */
public class PidsXiamenRenderer extends BlockEntityRendererMapper<PidsXiamenBlockEntity> {

    /** 日期 / 时间的世界起点：MC 第 0 天 = 2024-01-01（星期一），于是「星期」可以从天数直接算。 */
    private static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);

    /**
     * 「即将进站」的阈值（秒）：一分钟以内就不再写「X 秒」，直接写「即将进站」。
     * 关掉 {@link PidsXiamenSettings#arriving()} 时回到旧的「X 秒 / X 分钟」。
     */
    private static final long ARRIVING_SECONDS = 60L;

    /** 诊断日志间隔（tick）。 */
    private static final long LOG_INTERVAL_TICKS = 200L;

    /**
     * 滚动标语四边形相对整屏再往观察者方向推多少（px）。
     * 0.35px ≈ 1.3mm：标语贴面，不会像上一版那样「浮」在屏幕外面。
     */
    private static final float TICKER_LIFT_PX = 0.35F;

    /** 没有实时编组时的示意节数（和 PSD-LCD 吊板一致）。 */
    private static final int DEFAULT_CARS = 4;

    /** 单位：中文写「分钟 / 秒」，英文写「min / s」—— 两条**同时**画在屏上（设计稿就是这样）。 */
    private static final String UNIT_MIN_ZH = "分钟";
    private static final String UNIT_SEC_ZH = "秒";
    private static final String UNIT_MIN_EN = "min";
    private static final String UNIT_SEC_EN = "s";

    private static boolean warned;
    private static boolean warnedDouble;
    private static boolean carsWarned;
    private static long nextLogTick;
    private static long countedTick = -1L;

    /** 本 tick 每格被画了几次（= 两半都画了就会 > 1）。 */
    private static final Map<BlockPos, Integer> DRAWN_THIS_TICK = new HashMap<>();

    /** 已经打过「广告画在哪」诊断的版式（地铁 / BRT 各一次，别每帧刷日志）。 */
    private static final Set<PidsXiamenBlock.Kind> AD_LOGGED =
            java.util.EnumSet.noneOf(PidsXiamenBlock.Kind.class);

    /**
     * 标语时间表缓存（键 = 版式 + 字高 + 全部文案）。
     * <p>为什么要缓存：一张广告停多久 = 它自己那条标语滚完的时间，这段算的是 Java2D 的
     * 墨迹宽度（每条约 4 次字形轮廓），一帧算 3 张广告 × 2 面屏就有点贵了。
     */
    private static final Map<String, PidsXiamenPainter.TickerPlan> PLANS =
            new LinkedHashMap<String, PidsXiamenPainter.TickerPlan>(8, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<String, PidsXiamenPainter.TickerPlan> eldest) {
                    return size() > 12;
                }
            };

    private long lastSweep;

    public PidsXiamenRenderer(final BlockEntityRendererFactory.Context context) {
        super(context.getRenderDispatcher());
    }

    @Override
    public boolean isInRenderDistance(final PidsXiamenBlockEntity entity,
                                      final net.minecraft.util.math.Vec3d cameraPos) {
        return dev.js.mtrheadlight.client.RenderDistance.inRange(entity, cameraPos);
    }

    @Override
    public boolean rendersOutsideBoundingBox(final PidsXiamenBlockEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 64;
    }

    @Override
    public void render(final PidsXiamenBlockEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        try {
            renderSafe(entity, matrices, vertexConsumers);
        } catch (final Throwable t) {
            // 渲染线程上抛异常 = 整块屏不画（甚至整个 BER 链断掉），只记一次。
            if (!warned) {
                warned = true;
                LteLog.LOGGER.error("[lte] xiamen pids render failed", t);
            }
        }
    }

    // ------------------------------------------------------------------ 入口

    private void renderSafe(final PidsXiamenBlockEntity entity, final MatrixStack matrices,
                            final VertexConsumerProvider vertexConsumers) {
        final World world = entity.getWorld();
        if (world == null) {
            return;
        }
        this.sweep();
        final BlockPos pos = entity.getPos();
        final BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof PidsXiamenBlock block)) {
            return;
        }
        if (!PidsXiamenBlock.canStoreData(world, pos)) {
            return;
        }
        final Direction facing = IBlockFacing.of(world, pos);
        if (facing == null) {
            return;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        // MTR 有时递过来的不是 Immediate（它自己的队列）：退回客户端的实体缓冲区并自己 flush。
        final VertexConsumerProvider.Immediate immediate;
        final boolean flush;
        if (vertexConsumers instanceof VertexConsumerProvider.Immediate direct) {
            immediate = direct;
            flush = false;
        } else {
            immediate = client.getBufferBuilders().getEntityVertexConsumers();
            flush = true;
        }

        final PidsXiamenBlock.Kind kind = block.kind();
        final boolean partner = world.getBlockState(pos.offset(facing)).getBlock()
                instanceof PidsXiamenBlock;

        // ---- 数据（两面共用，一帧只算一次）
        final List<ScheduleEntry> schedules = schedulesFor(entity, pos);
        final long gameTick = (long) MTRClient.getGameTick();
        // 广告表先问全局覆盖：全局配置 → 其他 →「厦门 PIDS」里选了某一张，就所有屏都只播它
        final List<PidsXiamenAds.Ad> ads = PidsXiamenSettings.applyAdOverride(entity.getAds());
        final Slide slide = slideOf(kind, gameTick, entity.getAdvertisementIndex(), ads);
        final Station station = resolveStation(pos);

        final PidsXiamenPainter.Content content = contentOf(kind, entity, world, schedules, station);
        final BufferedImage adImage = slide.ad() == null ? null : PsdLcdAssets.ad(slide.ad().image());
        final String hash = hashOf(content, adImage, slide);

        // ---- 整屏贴图
        final String key = world.getRegistryKey().getValue() + "|" + pos.asLong();
        final PsdLcdTextures.Entry texture = PsdLcdTextures.acquire(key,
                Math.round(PidsXiamenPainter.SCREEN_W), Math.round(PidsXiamenPainter.SCREEN_H));
        if (texture == null) {
            return;
        }
        if (!hash.equals(texture.hash)) {
            final BufferedImage image = PidsXiamenPainter.paint(content, PidsXiamenAssets.get(),
                    adImage, painterKind(kind), Math.round(PidsXiamenPainter.SCREEN_W),
                    Math.round(PidsXiamenPainter.SCREEN_H));
            texture.hash = hash;
            PsdLcdTextures.upload(texture, image);
            if (AD_LOGGED.add(kind)) {
                final float[] box = PidsXiamenPainter.adRect(painterKind(kind));
                LteLog.LOGGER.info("[lte] pids ad: kind={} box=({},{}..{},{}) index={}/{} tick={}",
                        kind, box[0], box[1], box[2], box[3], slide.index(), slide.count(),
                        slide.tick());
            }
        }

        countDraw(pos, kind, facing, partner);

        // ---- 滚动标语（两面共用一张小贴图）
        final List<PidsXiamenTicker.Message> caption = captionOf(slide.ad());
        final PidsXiamenPainter.TickerPlan plan = planFor(kind, caption);
        final long tickerTick = slide.ad() == null ? gameTick : slide.tick();
        final PsdLcdTextures.Entry strip = tickerStrip(key, kind, plan, tickerTick);
        final Identifier stripId = strip == null ? null : strip.id;

        // ---- 两面
        matrices.push();
        try {
            renderFace(matrices, immediate, kind, facing, 0.0F, 0.0F, texture.id, stripId);
            if (partner) {
                renderFace(matrices, immediate, kind, facing.getOpposite(),
                        facing.getOffsetX(), facing.getOffsetZ(), texture.id, stripId);
            }
        } finally {
            matrices.pop();
            if (flush) {
                immediate.draw();
            }
        }
    }

    /** 版式选择：方块类型 → 画家类型（两个枚举各管各的：一个带 MC 贴图，一个纯 AWT）。 */
    private static PidsXiamenPainter.Kind painterKind(final PidsXiamenBlock.Kind kind) {
        return kind == PidsXiamenBlock.Kind.BRT
                ? PidsXiamenPainter.Kind.BRT : PidsXiamenPainter.Kind.METRO;
    }

    // ------------------------------------------------------------------ 一面屏

    /** 画「一面」屏：整屏四边形 + 标语小四边形。 */
    private static void renderFace(final MatrixStack matrices,
                                   final VertexConsumerProvider.Immediate immediate,
                                   final PidsXiamenBlock.Kind kind, final Direction screenFacing,
                                   final float cellDx, final float cellDz,
                                   final Identifier screenTexture, final Identifier stripId) {
        matrices.push();
        try {
            applyScreenTransform(matrices, screenFacing, cellDx, cellDz);
            final Matrix4f m = matrices.peek().getPositionMatrix();
            final Matrix3f n = matrices.peek().getNormalMatrix();

            // 1) 整屏（底图 + 文字 + 编组 + 广告全在这一张里）
            quad(immediate.getBuffer(MoreRenderLayers.getLight(screenTexture, false)), m, n,
                    0.0F, 0.0F, PidsXiamenPainter.SCREEN_W, PidsXiamenPainter.SCREEN_H, 0.0F);

            // 2) 滚动标语：压在屏面上的一小块**半透明**贴图（字之外全透明）
            if (stripId != null) {
                final float[] inner = PidsXiamenPainter.tickerInner(painterKind(kind));
                quad(immediate.getBuffer(MoreRenderLayers.getLight(stripId, true)), m, n,
                        inner[0], inner[1], inner[2], inner[3], -TICKER_LIFT_PX);
            }
        } finally {
            matrices.pop();
        }
    }

    /**
     * 屏幕的变换（见类注释）。
     *
     * @param screenFacing 这一面屏幕的 <b>facing</b>（不是法线！）：屏长方向 = 它，
     *                     屏幕朝外方向 = {@code screenFacing.rotateYClockwise()}
     * @param cellDx       这一面屏所在格相对本格的偏移（x）
     * @param cellDz       这一面屏所在格相对本格的偏移（z）
     */
    private static void applyScreenTransform(final MatrixStack matrices, final Direction screenFacing,
                                             final float cellDx, final float cellDz) {
        final Direction outward = screenFacing.rotateYClockwise();
        matrices.translate(
                0.5F + cellDx
                        + outward.getOffsetX() * PidsXiamenLayout.ANCHOR_OUT
                        - screenFacing.getOffsetX() * PidsXiamenLayout.ANCHOR_ALONG,
                PidsXiamenLayout.ANCHOR_Y,
                0.5F + cellDz
                        + outward.getOffsetZ() * PidsXiamenLayout.ANCHOR_OUT
                        - screenFacing.getOffsetZ() * PidsXiamenLayout.ANCHOR_ALONG);
        // +X = 阅读方向（屏长），+Y = 向下，+Z = 扎进屏幕。
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0F - screenFacing.asRotation()));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180.0F));
        // 绕屏长轴倾斜，贴合模型上 ±22.5° 的那块斜面（两面同号，因为 +X 也跟着反了）。
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(PidsXiamenLayout.SCREEN_TILT_X));
        final float inverse = 1.0F / PidsXiamenLayout.SCREEN_SCALE_DIVISOR;
        matrices.scale(inverse, inverse, inverse);
    }

    /**
     * 一块整张 uv 的矩形（屏幕坐标系，+Y 向下，{@code x1/y1} 为右下角）。
     *
     * <p>顶点顺序「左上→左下→右下→右上」使法线朝 −Z（= 朝观察者）；这一层不剔除，
     * 所以两面都看得见。亮度取 {@link PidsXiamenLayout#FULL_LIGHT}（自发光屏幕）。
     */
    private static void quad(final VertexConsumer buffer, final Matrix4f m, final Matrix3f n,
                             final float x0, final float y0, final float x1, final float y1,
                             final float z) {
        buffer.vertex(m, x0, y0, z).color(255, 255, 255, 255).texture(0.0F, 0.0F)
                .overlay(OverlayTexture.DEFAULT_UV).light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, 0.0F, 0.0F, -1.0F).next();
        buffer.vertex(m, x0, y1, z).color(255, 255, 255, 255).texture(0.0F, 1.0F)
                .overlay(OverlayTexture.DEFAULT_UV).light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, 0.0F, 0.0F, -1.0F).next();
        buffer.vertex(m, x1, y1, z).color(255, 255, 255, 255).texture(1.0F, 1.0F)
                .overlay(OverlayTexture.DEFAULT_UV).light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, 0.0F, 0.0F, -1.0F).next();
        buffer.vertex(m, x1, y0, z).color(255, 255, 255, 255).texture(1.0F, 0.0F)
                .overlay(OverlayTexture.DEFAULT_UV).light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, 0.0F, 0.0F, -1.0F).next();
    }

    private void sweep() {
        final long now = System.currentTimeMillis();
        if (now - this.lastSweep > 5_000L) {
            this.lastSweep = now;
            PsdLcdTextures.sweep();
        }
    }

    // ------------------------------------------------------------------ 内容

    /**
     * 这一帧的整屏内容（全部解析成字符串 —— 排版不认识 MTR）。
     *
     * <p>拿不到数据时<b>不是黑屏</b>：标签照画，终点站位置写「暂无列车信息」
     * （{@link PidsXiamenPainter.Content#placeholder()}），编组条退回
     * {@value #DEFAULT_CARS} 节 —— 屏看起来还是块屏，只是没车。
     */
    private static PidsXiamenPainter.Content contentOf(final PidsXiamenBlock.Kind kind,
                                                       final PidsXiamenBlockEntity entity,
                                                       final World world,
                                                       final List<ScheduleEntry> schedules,
                                                       final Station station) {
        final int page = Math.max(0, entity.getDisplayPage());
        final List<PidsXiamenPainter.Row> rows = new ArrayList<>();
        if (kind == PidsXiamenBlock.Kind.BRT) {
            for (int i = 0; i < PidsXiamenLayout.BRT.rows().length; i++) {
                rows.add(brtRow(entryAt(schedules, page * PidsXiamenLayout.BRT.rows().length + i)));
            }
        } else {
            for (int i = 0; i < PidsXiamenLayout.METRO.rows().length; i++) {
                rows.add(metroRow(i,
                        entryAt(schedules, page * PidsXiamenLayout.METRO.rows().length + i)));
            }
        }

        // 编组：只有地铁底图有那条青色带（BRT 底图没有 → 传空表，画家不画）
        List<String> cars = Collections.emptyList();
        if (kind == PidsXiamenBlock.Kind.METRO) {
            final long routeId = schedules.isEmpty() ? 0L : schedules.get(0).routeId;
            final int count = consistOf(station, routeId);
            cars = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                cars.add(String.valueOf(i));
            }
        }

        return new PidsXiamenPainter.Content(
                kind == PidsXiamenBlock.Kind.BRT ? brtDate(world) : formatIsoDate(world),
                formatTime(world), rows, cars,
                Text.translatable("gui.mtrlte.pids.no_data").getString());
    }

    /** 地铁一行：左列「开往 / Bound for」，右列「终点站 / Destination / X 分钟」。 */
    private static PidsXiamenPainter.Row metroRow(final int index, final ScheduleEntry entry) {
        final String label = Text.translatable(labelKey(index)).getString();
        final String labelSub = Text.translatable(labelSubKey(index)).getString();
        final Route route = entry == null ? null
                : ClientData.DATA_CACHE.routeIdMap.get(entry.routeId);
        if (entry == null || route == null) {
            return PidsXiamenPainter.Row.empty(label, labelSub);
        }
        final String[] destination = destinations(route, entry);
        final Countdown countdown = countdown(entry);
        final String[] unit = units(countdown);
        return new PidsXiamenPainter.Row(label, labelSub, destination[0], destination[1],
                "", PidsXiamenLayout.COLOR_WHITE, "",
                countdown.number(), unit[0], unit[1]);
    }

    /** BRT 一行：「开往XXX站」+「线路号（线路色）约 X 分钟」。 */
    private static PidsXiamenPainter.Row brtRow(final ScheduleEntry entry) {
        final Route route = entry == null ? null
                : ClientData.DATA_CACHE.routeIdMap.get(entry.routeId);
        if (entry == null || route == null) {
            return PidsXiamenPainter.Row.empty("", "");
        }
        final String destination = destinations(route, entry)[0];
        final Countdown countdown = countdown(entry);
        final String[] unit = units(countdown);
        return new PidsXiamenPainter.Row("", "",
                Text.translatable("gui.mtrlte.pids.bound_for_station", destination).getString(),
                "", firstPart(route.name), routeColor(route),
                // 「即将进站 / 已到站」这一档没有数字也没有单位 → 「约」也一起省掉
                unit[0].isEmpty() ? "" : Text.translatable("gui.mtrlte.pids.about").getString(),
                countdown.number(), unit[0], unit[1]);
    }

    /**
     * 倒计时单位：中文「分钟 / 秒」+ 英文「min / s」<b>两条都给</b>。
     *
     * <p>为什么两个都给：设计稿上「分钟」和「min」是上下两行小字，中英文读者一眼都能读；
     * 而且切换语言时两边都在，不会出现「英文模式只剩数字」。
     */
    private static String[] units(final Countdown countdown) {
        if (countdown == null || countdown.unit() == null || countdown.unit().isEmpty()) {
            return new String[]{"", ""};
        }
        final String minute = Text.translatable("gui.mtrlte.pids.minute").getString();
        if (minute != null && minute.equals(countdown.unit())) {
            return new String[]{UNIT_MIN_ZH, UNIT_MIN_EN};
        }
        return new String[]{UNIT_SEC_ZH, UNIT_SEC_EN};
    }

    /** 内容哈希：变了才重画整屏（时钟 / 倒计时是分钟级，一般一分钟一次）。 */
    private static String hashOf(final PidsXiamenPainter.Content content,
                                 final BufferedImage ad, final Slide slide) {
        final StringBuilder out = new StringBuilder(128);
        out.append(content.date()).append('|').append(content.clock()).append('|')
                .append(content.placeholder()).append('|').append(content.carNumbers()).append('|');
        for (final PidsXiamenPainter.Row row : content.rows()) {
            out.append(row.label()).append(',').append(row.labelSub()).append(',')
                    .append(row.dest()).append(',').append(row.destSub()).append(',')
                    .append(row.route()).append(',').append(row.routeColor()).append(',')
                    .append(row.about()).append(',').append(row.number()).append(',')
                    .append(row.unitZh()).append(',').append(row.unitEn()).append(';');
        }
        out.append('|').append(ad == null ? "noad" : "ad")
                .append(':').append(slide.index()).append('/').append(slide.count());
        return out.toString();
    }

    // ------------------------------------------------------------------ 广告轮播

    /**
     * 这一帧播第几张广告、播到第几个 tick（两面共用一份）。
     *
     * @param ad    {@code null} = 这一帧不画广告（开关关掉 / 一张都没有）
     * @param index 这张广告在 {@code ads} 里的下标（诊断日志用）
     * @param count 这一块屏一共几张（诊断日志用）
     * @param tick  这张广告已经播了多少 tick —— 标语的相位从这里起算
     */
    private record Slide(PidsXiamenAds.Ad ad, int index, int count, long tick) {

        /** 不画广告。 */
        private static final Slide NONE = new Slide(null, -1, 0, 0L);
    }

    /**
     * 广告轮播的节拍：<b>每张广告的时长 = 它自己那条标语滚完的时间</b>（学天津）。
     *
     * <pre>
     *   第 i 张的时长 dwell[i] = 这张广告的标语「全部滚完」的 tick 数
     *   总周期 total = Σ dwell[i]
     *   t = gameTick mod total  →  落在哪一段就是第几张，段内偏移就是标语相位
     * </pre>
     * 一张广告 = 段长恒为 total = 不轮播；≥2 张才转，且每张的停留时间按它自己那句标语的
     * 长度自动变（长句多停、短句少停）——「标语全滚完再切下一张」，不会滚到一半突然换图。
     *
     * <p>标语的时长和滚速来自同一份时间表（{@link #planFor}），所以「换图」和「滚完」是同一刻。
     */
    private static Slide slideOf(final PidsXiamenBlock.Kind kind, final long gameTick,
                                 final int adIndex, final List<PidsXiamenAds.Ad> ads) {
        if (!PidsXiamenSettings.advertisements()) {
            return Slide.NONE;
        }
        final int count = ads == null ? 0 : ads.size();
        if (count <= 0) {
            return Slide.NONE;
        }
        final PidsXiamenAds.Ad[] order = new PidsXiamenAds.Ad[count];
        final int[] dwell = new int[count];
        long total = 0L;
        for (int slot = 0; slot < count; slot++) {
            final int index = Math.floorMod(adIndex + slot, count);
            order[slot] = ads.get(index);
            dwell[slot] = dwellTicks(kind, order[slot]);
            total += dwell[slot];
        }
        if (total <= 0L) {
            return Slide.NONE;
        }
        long t = Math.floorMod(gameTick, total);
        for (int slot = 0; slot < count; slot++) {
            if (t < dwell[slot]) {
                final PidsXiamenAds.Ad ad = order[slot];
                if (ad == null || ad.image() == null) {
                    return Slide.NONE;
                }
                return new Slide(ad, Math.floorMod(adIndex + slot, count), count, t);
            }
            t -= dwell[slot];
        }
        return Slide.NONE;
    }

    /** 一张广告该停多少 tick：它自己的标语滚完的时间（兜底见 {@link PidsXiamenLayout#AD_ADVANCE_TICKS}）。 */
    private static int dwellTicks(final PidsXiamenBlock.Kind kind, final PidsXiamenAds.Ad ad) {
        if (ad == null) {
            return PidsXiamenLayout.AD_ADVANCE_TICKS;
        }
        final int ticks = PidsXiamenPainter.tickerSpanTicks(planFor(kind, captionOf(ad)));
        return ticks > 0 ? ticks : PidsXiamenLayout.AD_ADVANCE_TICKS;
    }

    /**
     * 这一帧的广告标语（天津语义：广告自带的 {@code text} 就是屏下那条滚动文字）。
     *
     * @return {@code null} = 广告没画 / 这张没有文字 → 回落到 {@link PidsXiamenTicker} 的资源包列表
     */
    static List<PidsXiamenTicker.Message> captionOf(final PidsXiamenAds.Ad ad) {
        if (ad == null || ad.text() == null) {
            return null;
        }
        final String text = ad.text().getString();
        if (text == null || text.isBlank()) {
            return null;
        }
        return List.of(new PidsXiamenTicker.Message(text, PidsXiamenLayout.COLOR_WHITE, 0.0F));
    }

    // ------------------------------------------------------------------ 标语

    /** 这一帧的标语时间表（{@code null} = 没有标语 / 条太窄）。 */
    private static PidsXiamenPainter.TickerPlan planFor(final PidsXiamenBlock.Kind kind,
                                                         final List<PidsXiamenTicker.Message> caption) {
        final PidsXiamenPainter.Assets assets = PidsXiamenAssets.get();
        final List<PidsXiamenTicker.Message> source = caption == null || caption.isEmpty()
                ? PidsXiamenTicker.messages() : caption;
        if (source.isEmpty()) {
            return null;
        }
        final float fontPx = PidsXiamenPainter.tickerFontPx(painterKind(kind));
        final float[] inner = PidsXiamenPainter.tickerInner(painterKind(kind));
        final float innerWidth = inner[2] - inner[0];
        final String key = kind + "|" + fontPx + "|" + innerWidth + "|" + source;
        synchronized (PLANS) {
            final PidsXiamenPainter.TickerPlan cachedPlan = PLANS.get(key);
            if (cachedPlan != null) {
                return cachedPlan;
            }
        }
        final List<PidsXiamenPainter.TickerMessage> messages = new ArrayList<>(source.size());
        for (final PidsXiamenTicker.Message message : source) {
            messages.add(new PidsXiamenPainter.TickerMessage(message.text(), message.color()));
        }
        // 速度只有一份：PidsXiamenTicker#pxPerTick(fontPx)（PSD-LCD 吊板用的是同一个函数）
        final PidsXiamenPainter.TickerPlan plan = PidsXiamenPainter.tickerPlan(messages,
                assets.cjk, fontPx, innerWidth, PidsXiamenTicker.pxPerTick(fontPx),
                PidsXiamenTicker.gap());
        synchronized (PLANS) {
            PLANS.put(key, plan);
        }
        return plan;
    }

    /**
     * 标语小贴图：只在「滚过一个像素」或「文案换了」时重画 / 重传。
     *
     * @return {@code null} = 这一帧没标语（空档 / 表是空的 / 拿不到贴图）
     */
    private static PsdLcdTextures.Entry tickerStrip(final String key, final PidsXiamenBlock.Kind kind,
                                                     final PidsXiamenPainter.TickerPlan plan,
                                                     final long tick) {
        if (plan == null) {
            return null;
        }
        final float[] inner = PidsXiamenPainter.tickerInner(painterKind(kind));
        final int width = Math.round(inner[2] - inner[0]);
        final int height = Math.round(inner[3] - inner[1]);
        final PsdLcdTextures.Entry strip = PsdLcdTextures.acquire(key + "|ticker", width, height);
        if (strip == null) {
            return null;
        }
        // 文案换了（换广告 / 换资源包）：必须重画，否则相位刚好重合时会继续显示上一句
        final String signature = plan.messages().toString();
        if (!signature.equals(strip.hash)) {
            strip.hash = signature;
            strip.lastPhasePx = Integer.MIN_VALUE;
        }
        final int phase = PidsXiamenPainter.tickerPhaseKey(plan, tick);
        if (phase != strip.lastPhasePx) {
            strip.lastPhasePx = phase;
            PsdLcdTextures.upload(strip,
                    PidsXiamenPainter.paintTickerStrip(plan, tick, width, height));
        }
        return strip;
    }

    // ------------------------------------------------------------------ 编组

    /**
     * 编组节数：MTR 里「这一站 / 这条线」正在跑的那列车有多少节。
     *
     * <p>三条入口（和 PSD-LCD 吊板同一套算法）：
     * <ol>
     *   <li>停在这个站、并且跑这条线的那列车；</li>
     *   <li>停在这个站的任意一列车（跨线车 / 折返车）；</li>
     *   <li>这条线路上任意一列在跑的车。</li>
     * </ol>
     * 三条都落空（单机存档还没发车、或者客户端还没同步列车）时退回 {@value #DEFAULT_CARS} 节，
     * 并且<b>只 warning 一次</b>（这条路径每 200ms 走一次，不能刷屏）。
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
            LteLog.LOGGER.warn("[lte] xiamen pids: no live train at this platform, "
                    + "falling back to {} cars for the consist strip", DEFAULT_CARS);
        }
        return DEFAULT_CARS;
    }

    /** 两个 Station 是不是同一个（按 id；任一为 null 就是 false）。 */
    private static boolean sameStation(final Station a, final Station b) {
        return a != null && b != null && a.id == b.id;
    }

    /** 线路色（ARGB）。MTR 的 {@code route.color} 是 {@code 0xRRGGBB}；没设过（0）= 白色。 */
    private static int routeColor(final Route route) {
        if (route == null || (route.color & 0xFFFFFF) == 0) {
            return PidsXiamenLayout.COLOR_WHITE;
        }
        return 0xFF000000 | (route.color & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ 标签

    private static String labelKey(final int row) {
        return switch (row) {
            case 0 -> "gui.mtrlte.pids.bound_for_label";
            case 1 -> "gui.mtrlte.pids.next_train_label";
            default -> "gui.mtrlte.pids.third_train_label";
        };
    }

    private static String labelSubKey(final int row) {
        return switch (row) {
            case 0 -> "gui.mtrlte.pids.bound_for_sub";
            case 1 -> "gui.mtrlte.pids.next_train_sub";
            default -> "gui.mtrlte.pids.third_train_sub";
        };
    }

    // ------------------------------------------------------------------ 数据（PSD-LCD 吊板复用）

    /** 倒计时（PSD-LCD 挂板复用，所以是包内可见）。 */
    record Countdown(String number, String unit) {
    }

    /**
     * 倒计时。
     *
     * <p>{@link PidsXiamenSettings#arriving()} 打开时（默认）：一分钟以内写「即将进站」，
     * 已经过了到点时间写「已到站」—— 厦门地铁屏上就是这个说法，不会出现「0 秒」这种读着像坏了的数字。
     * 关掉时回到 MTR 式的「X 秒 / X 分钟」。
     */
    static Countdown countdown(final ScheduleEntry entry) {
        final long remainingSeconds = (entry.arrivalMillis - System.currentTimeMillis()) / 1000L;
        if (PidsXiamenSettings.arriving() && remainingSeconds <= ARRIVING_SECONDS) {
            final String state = Text.translatable(remainingSeconds < 0L
                    ? "gui.mtrlte.pids.arrived" : "gui.mtrlte.pids.arriving").getString();
            return new Countdown(state, "");
        }
        final boolean minutes = remainingSeconds > 60L;
        return new Countdown(
                String.valueOf(minutes ? remainingSeconds / 60L : Math.max(0L, remainingSeconds)),
                Text.translatable(minutes ? "gui.mtrlte.pids.minute" : "gui.mtrlte.pids.second")
                        .getString());
    }

    /** 第 {@code index} 条到站（{@code null} = 没有）。 */
    private static ScheduleEntry entryAt(final List<ScheduleEntry> schedules, final int index) {
        return index >= 0 && index < schedules.size() ? schedules.get(index) : null;
    }

    /** 终点站：MTR 给的是「中文|英文」，拆成两行。 */
    static String[] destinations(final Route route, final ScheduleEntry entry) {
        try {
            final String[] parts = ClientData.DATA_CACHE
                    .getFormattedRouteDestination(route, entry.currentStationIndex, "")
                    .split("\\|", -1);
            final String first = firstPart(parts.length > 0 ? parts[0] : "");
            final String second = parts.length > 1 ? firstPart(parts[1]) : "";
            return new String[]{first, second};
        } catch (final Throwable t) {
            return new String[]{"", ""};
        }
    }

    /** 「中文|英文」→ 前半；顺带去掉 MTR 可能塞进来的 {@code |} 与样式符号。 */
    static String firstPart(final String raw) {
        if (raw == null) {
            return "";
        }
        final int bar = raw.indexOf('|');
        final String text = bar < 0 ? raw : raw.substring(0, bar);
        return text.replace("|", "").trim();
    }

    static Station resolveStation(final BlockPos pos) {
        try {
            return PidsStationResolver.resolve(ClientData.STATIONS, ClientData.DATA_CACHE, pos)
                    .station();
        } catch (final Throwable t) {
            return null;
        }
    }

    static List<ScheduleEntry> schedulesFor(final PidsXiamenBlockEntity entity, final BlockPos pos) {
        final Station station = resolveStation(pos);
        if (station == null) {
            return Collections.emptyList();
        }
        return getSchedules(entity, station, pos, new HashMap<>());
    }

    /**
     * 到站列表 —— 与天津的 {@code getSchedules} 同一套算法（也是 MTR {@code RenderPIDS} 的算法）：
     * <pre>
     * 1. 按方块位置找车站（PidsStationResolver，支持站外绑定）
     * 2. 车站 → 站台表（requestStationIdToPlatforms 异步，第一帧可能是空的）
     * 3. 方块实体自己的 platformIds 非空就用它，否则用「离本格最近的站台」
     * 4. 遍历这些站台的 ClientData.SCHEDULES_FOR_PLATFORM，丢掉终点站已过的条目，排序
     * </pre>
     */
    static List<ScheduleEntry> getSchedules(final PidsXiamenBlockEntity entity, final Station station,
                                            final BlockPos pos, final Map<Long, String> platformIdToName) {
        final Map<Long, Platform> stationIdToPlatforms =
                ClientData.DATA_CACHE.requestStationIdToPlatforms(station.id);
        if (stationIdToPlatforms == null || stationIdToPlatforms.isEmpty()) {
            return Collections.emptyList();
        }

        final Set<Long> configured = entity.getPlatformIds();
        final Set<Long> targets;
        if (configured == null || configured.isEmpty()) {
            final long close = RailwayData.getClosePlatformId(
                    new HashSet<>(stationIdToPlatforms.values()), ClientData.DATA_CACHE, pos);
            targets = close == 0L ? Collections.emptySet() : Collections.singleton(close);
        } else {
            targets = configured;
        }

        final Set<ScheduleEntry> entries = new HashSet<>();
        for (final Platform platform : stationIdToPlatforms.values()) {
            if (!targets.contains(platform.id)) {
                continue;
            }
            final Set<ScheduleEntry> platformEntries = ClientData.SCHEDULES_FOR_PLATFORM.get(platform.id);
            if (platformEntries == null) {
                continue;
            }
            for (final ScheduleEntry entry : platformEntries) {
                final Route route = ClientData.DATA_CACHE.routeIdMap.get(entry.routeId);
                if (route != null && entry.currentStationIndex < route.platformIds.size() - 1) {
                    entries.add(entry);
                    platformIdToName.put(platform.id, platform.name);
                }
            }
        }
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        final List<ScheduleEntry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * 时钟。
     *
     * <p>{@link PidsXiamenSettings#localTime()} 打开时（默认）= <b>现实时间</b> {@code HH:mm}
     * （用户要的就是「现在几点」，和日期同一套时间源）；关掉时回到 MTR 的世界钟点：
     * 一天 24000 tick 映射到 24 小时（天津的 {@code getFormattedTime}）。
     */
    static String formatTime(final World world) {
        if (PidsXiamenSettings.localTime()) {
            final java.time.LocalTime now = java.time.LocalTime.now();
            return String.format("%02d:%02d", now.getHour(), now.getMinute());
        }
        final long modulo = world.getTimeOfDay() % 24000L;
        final long scaled = modulo * 36L / 10L;
        final long hour = (scaled / 3600L + 6L) % 24L;
        final long minute = scaled / 60L % 60L;
        return String.format("%02d:%02d", hour, minute);
    }

    /** 日期：地铁版是 {@code YYYY-MM-DD}。 */
    static String formatIsoDate(final World world) {
        return dateNow(world).toString();
    }

    /**
     * 屏幕上那一天的日期。
     *
     * <p>{@link PidsXiamenSettings#localTime()} 打开时取<b>现实日期</b>；关掉时回落到世界天数
     * （第 0 天 = 2024-01-01 星期一），和钟点出自同一套时间。
     */
    static LocalDate dateNow(final World world) {
        return PidsXiamenSettings.localTime() ? LocalDate.now() : worldDate(world);
    }

    private static LocalDate worldDate(final World world) {
        final long day = Math.max(0L, world.getTime() / 24000L);
        return EPOCH.plusDays(day);
    }

    /** BRT 的「MM月DD日星期X」。 */
    private static String brtDate(final World world) {
        final LocalDate date = dateNow(world);
        final String weekday = Text.translatable("gui.mtrlte.pids.weekday."
                + date.getDayOfWeek().getValue()).getString();
        return Text.translatable("gui.mtrlte.pids.date_line",
                date.getMonthValue(), date.getDayOfMonth(), weekday).getString();
    }

    // ------------------------------------------------------------------ 诊断

    /**
     * 诊断：每格每帧被画几次。
     *
     * <p>用户能直接看出「同一块屏是不是被两半各画了一遍」（{@code count=2} 就是有 bug），
     * 以及这块屏画了几面（{@code faces}）。
     */
    private static void countDraw(final BlockPos pos, final PidsXiamenBlock.Kind kind,
                                  final Direction facing, final boolean partner) {
        final long tick = (long) MTRClient.getGameTick();
        if (tick != countedTick) {
            countedTick = tick;
            DRAWN_THIS_TICK.clear();
        }
        final int count = DRAWN_THIS_TICK.merge(pos, 1, Integer::sum);
        if (count > 1 && !warnedDouble) {
            warnedDouble = true;
            LteLog.LOGGER.warn("[lte] pids draw: pos={} 同一帧被画了 {} 次（两半都在渲染？）", pos, count);
        }
        if (tick >= nextLogTick) {
            nextLogTick = tick + LOG_INTERVAL_TICKS;
            LteLog.LOGGER.info("[lte] pids draw halves=1 faces={} kind={} pos={} facing={} partner={} rows={}",
                    partner ? 2 : 1, kind, pos, facing, partner, PidsXiamenLayout.rowsOf(kind));
        }
    }

    /** 方块 facing（与天津用同一个属性：{@code HorizontalFacingBlock.FACING}）。 */
    private static final class IBlockFacing {

        private static final Property<Direction> FACING = HorizontalFacingBlock.FACING;

        private IBlockFacing() {
        }

        static Direction of(final World world, final BlockPos pos) {
            try {
                return mtr.block.IBlock.getStatePropertySafe(world, pos, FACING);
            } catch (final Throwable t) {
                return null;
            }
        }
    }
}
