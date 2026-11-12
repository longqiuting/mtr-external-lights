// 厦门 PIDS 的屏幕渲染器：底图 + 只往里写文字（框/标志/徽章条都在底图里）。
package dev.js.mtrheadlight.xiamen.pids;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import mtr.render.MoreRenderLayers;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 厦门 PIDS 的屏幕渲染器（地铁 / BRT 共用，版式各一套）。
 *
 * <h2>这里只画两种东西</h2>
 * <ol>
 *   <li><b>一块底图</b>：{@code pids_xiamen_metro.png} / {@code pids_xiamen_brt.png}，512×235；
 *       框线、圆角、M 标志、右上角线路徽章条、底部标语框<b>全都已经画在底图里</b>，
 *       渲染器一个矩形都不画（上一版自己描框，结果和底图的框错位）。</li>
 *   <li><b>文字</b>：按 {@link PidsXiamenLayout} 里量好的框往写。
 *       走 {@code TextRenderer.draw}（1px=1px），不用 MTR 的 {@code drawStringWithFont}。</li>
 * </ol>
 * 广告图是第三样东西，但它本来就是「贴一张图」，见 {@link #drawAdvertisement}（和底图同一个
 * beacon 层 → 同一个满亮；换图时机是标语滚完，见 {@link #slideOf}）。
 * 屏幕本身按满亮度画（自发光层），<b>没有</b>外围光晕（上一版的 {@code drawGlow} 已删掉）。
 *
 * <h2>屏幕坐标系（改这里之前先读这段）</h2>
 * <p>底图是一次 {@code drawQuad} 画出来的 <b>512×235 px</b> 的矩形平面，局部坐标系是
 * 「GUI 风格」：<b>+X = 屏幕向右、+Y = 屏幕向下、+Z = 扎进屏幕里</b>；
 * 原点 = 屏幕<b>左上角</b>（= 底图左上角）。
 *
 * <h2>变换链（逐条对应 MTR 的 {@code RenderPIDS}，但换成我们的模型）</h2>
 * <pre>
 *   matrices.translate(anchor)                                       // 屏幕左上角，世界/方块坐标
 *   matrices.multiply(rotY(90 − facing.asRotation()))                // +X = facing（屏长方向）
 *   matrices.multiply(rotZ(180))                                     // ← 必须是 Z 轴 180°
 *   matrices.multiply(rotX(+SCREEN_TILT_X))                          // 绕屏长轴倾 22.5°，贴合斜面
 *   matrices.scale(1 / 261.1, …)                                     // 512×235 px → 1.96×0.90 格
 * </pre>
 * {@code rotZ(180)} 不能写 {@code rotY(180)}：后者只镜像左右，还会让 +Y 朝上（文字上下颠倒）。
 * 锚点不能用天津的 {@code (0.5,0.85,0.5)−0.48}（那是它自己那块平板的数），我们的是按
 * {@code pids_xiamen.json} 里 ±22.5° 斜面的几何算出来的（见 {@link PidsXiamenLayout#ANCHOR_OUT}）。
 *
 * <h2>两面都画</h2>
 * <p>模型是 V 形双面屏：第一面朝 {@code facing.rotateYClockwise()}，画在本格；第二面朝反方向，
 * 画在 partner 格。整块屏横跨两格，所以第二面的锚点用 partner 格中心算。
 * 只有「存数据的那一半」（{@code facing ∈ {SOUTH, WEST}}）才会走到这里，
 * 否则同一块屏会被画两遍（而且镜像）—— 见 {@link PidsXiamenBlock#canStoreData}。
 * 帧内重复绘制的计数见 {@link #countDraw}。
 *
 * <h2>渲染状态：一个全局 GL 状态都不碰</h2>
 * <p>{@code RenderLayer} / {@code TextRenderer} 各自 start/end，矩阵的 push/pop 都在
 * {@code try/finally} 里。上一版在收尾调 {@code enableCull()/depthMask(true)}「复位」，
 * 实际是把下一个 BER 正在依赖的状态按默认值覆盖掉（{@code RenderLayer} 里 cull 是靠继承的），
 * 于是后面那些四边形全被当背面剔掉 —— 看着就是「PIDS 周围的方块变透明」。
 *
 * <p>底图和广告走<b>同一个</b> {@link MoreRenderLayers#getLight}（beacon 层：贴图原色、
 * <b>不过光照贴图</b>、写深度）—— 两张图因此是同一个亮度基准，广告不会比底图暗一档
 * （天津的广告也是 {@code getLight(advertisement.image(), false)} + {@code FULL_LIGHT}）。
 * <ul>
 *   <li>底图在 z=0，广告在 −0.35px（更靠近观察者）：都写深度，{@code LEQUAL} 下广告赢，
 *       底图后画也盖不住它；0.35px ≈ 1.3mm，视觉上贴面；</li>
 *   <li>文字用 text 层（不写深度）再往前 0.7px，压在广告上不会被广告挡住。</li>
 * </ul>
 */
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

    private static boolean warned;
    private static boolean warnedDouble;
    private static long nextLogTick;
    private static long countedTick = -1L;

    /** 本 tick 每格被画了几次（= 两半都画了就会 > 1）。 */
    private static final Map<BlockPos, Integer> DRAWN_THIS_TICK = new HashMap<>();

    /** 已经打过「广告画在哪」诊断的版式（地铁 / BRT 各一次，别每帧刷日志）。 */
    private static final Set<PidsXiamenBlock.Kind> AD_LOGGED =
            java.util.EnumSet.noneOf(PidsXiamenBlock.Kind.class);

    /** 一行文字（渲染器内部的排版单元）。 */
    private record Line(PidsXiamenText.Segment[] segments, float height,
                        float x, PidsXiamenLayout.HAlign align, float maxWidth) {
    }

    public PidsXiamenRenderer(final BlockEntityRendererFactory.Context context) {
        super(context.getRenderDispatcher());
    }

    @Override
    public void render(final PidsXiamenBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        try {
            renderSafe(entity, matrices, vertexConsumers);
        } catch (Throwable t) {
            // 渲染线程上抛异常 = 整块屏不画（甚至整个 BER 链断掉），只记一次。
            if (!warned) {
                warned = true;
                LteLog.LOGGER.error("[lte] xiamen pids render failed", t);
            }
        }
    }

    // ------------------------------------------------------------------ 一面屏

    private void renderSafe(final PidsXiamenBlockEntity entity, final MatrixStack matrices,
                            final VertexConsumerProvider vertexConsumers) {
        final World world = entity.getWorld();
        if (world == null) {
            return;
        }
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
        if (client == null || client.textRenderer == null) {
            return;
        }

        // 文字用 Immediate：优先用调度器递进来的那个（一帧结束由它自己 flush）。
        final VertexConsumerProvider.Immediate textConsumers;
        final boolean flushText;
        if (vertexConsumers instanceof VertexConsumerProvider.Immediate immediate) {
            textConsumers = immediate;
            flushText = false;
        } else {
            textConsumers = client.getBufferBuilders().getEntityVertexConsumers();
            flushText = true;
        }

        final PidsXiamenBlock.Kind kind = block.kind();
        final boolean partner = world.getBlockState(pos.offset(facing)).getBlock() instanceof PidsXiamenBlock;

        // 到站 / 时钟 / 日期 / 广告：两面共用，一帧只算一次。
        final List<ScheduleEntry> schedules = schedulesFor(entity, pos);
        final long gameTick = (long) MTRClient.getGameTick();
        final List<PidsXiamenAds.Ad> ads = entity.getAds();
        final Screen screen = new Screen(kind, schedules, entity.getDisplayPage(),
                slideOf(client.textRenderer, kind, gameTick, entity.getAdvertisementIndex(), ads),
                gameTick, formatTime(world), formatIsoDate(world), formatBrtDate(world));

        countDraw(pos, kind, facing, partner);

        try {
            // 第一面：本格，屏幕朝 facing.rotateYClockwise()。
            renderFace(matrices, vertexConsumers, textConsumers, kind, client.textRenderer,
                    facing, 0, 0, screen);
            // 第二面：partner 格（pos.offset(facing)），屏幕朝反方向。
            if (partner) {
                renderFace(matrices, vertexConsumers, textConsumers, kind, client.textRenderer,
                        facing.getOpposite(), facing.getOffsetX(), facing.getOffsetZ(), screen);
            }
        } finally {
            if (flushText) {
                textConsumers.draw();
            }
        }
    }

    /** 画「一面」屏：底图 → 广告 → 时钟 → 到站 → 标语。 */
    private void renderFace(final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                            final VertexConsumerProvider.Immediate textConsumers,
                            final PidsXiamenBlock.Kind kind, final TextRenderer textRenderer,
                            final Direction screenFacing, final float cellDx, final float cellDz,
                            final Screen screen) {
        matrices.push();
        try {
            applyScreenTransform(matrices, screenFacing, cellDx, cellDz);

            // 1) 底图（框、标志、徽章条都在里面）
            step("base", () -> drawQuad(matrices,
                    vertexConsumers.getBuffer(MoreRenderLayers.getLight(kind.screenTexture(), false)),
                    0.0F, 0.0F, PidsXiamenLayout.SCREEN_WIDTH, PidsXiamenLayout.SCREEN_HEIGHT));

            // 文字层往观察者方向推一点，避免和底图共面
            matrices.translate(0.0F, 0.0F, PidsXiamenLayout.TEXT_LAYER_Z);

            // 2) 广告（在文字之前：它是张图，不该盖住字）。
            // 广告和底图走同一个 beacon 层 → 同一个亮度（满亮、不过光照贴图），
            // 只靠 -0.35px 的深度差压在底图上面（见类注释）。
            step("ad", () -> {
                final Slide slide = screen.slide();
                if (slide.ad() == null) {
                    return;
                }
                matrices.push();
                try {
                    matrices.translate(0.0F, 0.0F, PidsXiamenLayout.AD_LAYER_Z);
                    drawAdvertisement(matrices, vertexConsumers, kind, slide);
                } finally {
                    matrices.pop();
                }
            });

            // 3) 左上角的日期 / 时间
            step("clock", () -> renderClock(matrices, textRenderer, textConsumers, kind, screen));

            // 4) 到站行
            step("rows", () -> renderArrivals(matrices, textRenderer, textConsumers, kind, screen));

            // 5) 底部滚动标语：广告开着就用这张广告的 text（天津的语义），否则用资源包那套。
            step("ticker", () -> {
                final PidsXiamenLayout.Rect bar = tickerOf(kind);
                final float sizeRatio = tickerTextOf(kind).sizeRatio();
                final Slide slide = screen.slide();
                final List<PidsXiamenTicker.Message> caption = captionOf(slide.ad());
                // 有广告时，标语的相位从「这张广告开始播」的那一刻算起 —— 广告换图的时机
                // 就是这个相位把整套标语滚完的时刻（{@link PidsXiamenTicker#scrollSpanTicks}），
                // 所以标语的最后半个字出左边缘 = 下一张广告进来，和天津 nextSlide 一致。
                // 没广告时回落到连续的世界时间。
                final long tickerTick = slide.ad() == null ? screen.gameTick() : slide.tick();
                PidsXiamenTicker.draw(matrices, textRenderer, textConsumers, bar, sizeRatio,
                        tickerTick, caption);
            });
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
     * 画一块整张 uv 的矩形（+Y 向下的屏幕空间）。
     *
     * <p>底图和广告都走 {@link MoreRenderLayers#getLight}（= {@code RenderLayer.getBeaconBeam}，
     * 顶点格式 {@code POSITION_COLOR_TEXTURE_LIGHT_NORMAL}），所以这里多给的
     * {@code overlay} 元素会被 {@code BufferBuilder} 忽略；文字那条层仍然靠
     * {@link OverlayTexture#DEFAULT_UV} 兜底。
     *
     * <p>这一层<b>不剔除</b>，所以顺时针逆时针都看得见；仍然按「左上→左下→右下→右上」发顶点，
     * 使法线朝 −Z（= 朝观察者）。
     */
    private static void drawQuad(final MatrixStack matrices, final VertexConsumer buffer,
                                 final float x, final float y, final float width, final float height) {
        final MatrixStack.Entry entry = matrices.peek();
        final Matrix4f position = entry.getPositionMatrix();
        final Matrix3f normal = entry.getNormalMatrix();
        vertex(buffer, position, normal, x, y, 0.0F, 0.0F);                       // 左上
        vertex(buffer, position, normal, x, y + height, 0.0F, 1.0F);              // 左下
        vertex(buffer, position, normal, x + width, y + height, 1.0F, 1.0F);      // 右下
        vertex(buffer, position, normal, x + width, y, 1.0F, 0.0F);               // 右上
    }

    private static void vertex(final VertexConsumer buffer, final Matrix4f position, final Matrix3f normal,
                               final float x, final float y, final float u, final float v) {
        buffer.vertex(position, x, y, 0.0F)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(PidsXiamenLayout.FULL_LIGHT)
                .normal(normal, 0.0F, 0.0F, -1.0F)
                .next();
    }

    // ------------------------------------------------------------------ 日期 / 时间

    /** 左上角：日期 + 时间，写进 M 标志右边那块空白（不额外加背景）。 */
    private void renderClock(final MatrixStack matrices, final TextRenderer textRenderer,
                             final VertexConsumerProvider.Immediate immediate,
                             final PidsXiamenBlock.Kind kind, final Screen screen) {
        final PidsXiamenLayout.Clock clock = clockOf(kind);
        final PidsXiamenLayout.Rect card = clock.card();
        final PidsXiamenLayout.TextStyle date = clock.date();
        final PidsXiamenLayout.TextStyle time = clock.time();
        drawFlow(matrices, textRenderer, immediate, card, clock.padRatio(), clock.gapRatio(),
                line(date, card, kind == PidsXiamenBlock.Kind.BRT ? screen.dateBrt() : screen.dateIso()),
                line(time, card, screen.clock()));
    }

    // ------------------------------------------------------------------ 到站

    /** 到站行：地铁三行、BRT 两行，版式由 {@link PidsXiamenLayout} 定。 */
    private void renderArrivals(final MatrixStack matrices, final TextRenderer textRenderer,
                                final VertexConsumerProvider.Immediate immediate,
                                final PidsXiamenBlock.Kind kind, final Screen screen) {
        if (kind == PidsXiamenBlock.Kind.BRT) {
            renderBrtRows(matrices, textRenderer, immediate, screen);
        } else {
            renderMetroRows(matrices, textRenderer, immediate, screen);
        }
    }

    /** 地铁：左列「开往 / Bound for」，右列「终点站 / Destination / X 分钟」。 */
    private void renderMetroRows(final MatrixStack matrices, final TextRenderer textRenderer,
                                 final VertexConsumerProvider.Immediate immediate, final Screen screen) {
        final PidsXiamenLayout.Row[] rows = PidsXiamenLayout.METRO.rows();
        for (int i = 0; i < rows.length; i++) {
            final PidsXiamenLayout.Row row = rows[i];
            final PidsXiamenLayout.Rect card = row.card();
            final ScheduleEntry entry = entryAt(screen, i);
            final Route route = entry == null ? null : ClientData.DATA_CACHE.routeIdMap.get(entry.routeId);

            // 左列：开往 / 下一班开往 / 第三班开往（+ 英文小字）
            drawFlow(matrices, textRenderer, immediate, card, row.padRatio(), row.gapRatio(),
                    line(row.label(), card, Text.translatable(labelKey(i)).getString()),
                    line(row.labelSub(), card, Text.translatable(labelSubKey(i)).getString()));

            if (entry == null || route == null) {
                continue;
            }

            final String[] destination = destinations(route, entry);
            final Countdown countdown = countdown(entry);

            // 右列：终点站（大）/ 英文（小）/ 倒计时（大数字 + 小单位）
            drawFlow(matrices, textRenderer, immediate, card, row.padRatio(), row.gapRatio(),
                    line(row.dest(), card, destination[0]),
                    line(row.destSub(), card, destination[1]),
                    countLine(row.count(), row.unit(), card, countdown));
        }
    }

    /** BRT：第一行「开往XXXX站」，第二行「线路号（线路色）约X 分钟」。 */
    private void renderBrtRows(final MatrixStack matrices, final TextRenderer textRenderer,
                               final VertexConsumerProvider.Immediate immediate, final Screen screen) {
        final PidsXiamenLayout.BrtRow[] rows = PidsXiamenLayout.BRT.rows();
        for (int i = 0; i < rows.length; i++) {
            final PidsXiamenLayout.BrtRow row = rows[i];
            final PidsXiamenLayout.Rect card = row.card();
            final ScheduleEntry entry = entryAt(screen, i);
            final Route route = entry == null ? null : ClientData.DATA_CACHE.routeIdMap.get(entry.routeId);
            if (entry == null || route == null) {
                continue;
            }

            final String destination = destinations(route, entry)[0];
            final String routeName = firstPart(route.name);
            final Countdown countdown = countdown(entry);

            drawFlow(matrices, textRenderer, immediate, card, row.padRatio(), row.gapRatio(),
                    line(row.dest(), card, Text.translatable(
                            "gui.mtrlte.pids.bound_for_station", destination).getString()),
                    routeLine(row, card, route, routeName, countdown));
        }
    }

    /**
     * BRT 第二行：<b>线路号（线路色）</b> + 「约」小字 + 大数字 + 「分钟」小字（底边对齐）。
     *
     * <p>线路号不带引号（旧版用的 {@code route_quoted} 键已经不用了），颜色取 MTR 里这条线路的
     * {@code route.color}（厦门 1 号线是绿的、2 号线橙的……），取不到才退回白色 ——
     * 整屏文字全白，只有这一处跟线路色走。
     */
    private Line routeLine(final PidsXiamenLayout.BrtRow row, final PidsXiamenLayout.Rect card,
                           final Route route, final String routeName, final Countdown countdown) {
        final PidsXiamenLayout.TextStyle routeStyle = row.route();
        final PidsXiamenLayout.TextStyle about = row.about();
        final PidsXiamenLayout.TextStyle count = row.count();
        final PidsXiamenLayout.TextStyle unit = row.unit();

        // 即将进站 / 已到站：没有数字、也没有单位，所以「约」也一起省掉 ——
        // 否则屏上会读成「1号线 约 即将进站」。这一档只写「线路号 即将进站」，
        // 状态用倒计时那一档字号（地铁版同样把状态写在大数字的位置上）。
        if (countdown.unit().isEmpty()) {
            final float routeSize = size(routeStyle, card);
            final float stateSize = size(count, card);
            final PidsXiamenText.Segment[] state = {
                    new PidsXiamenText.Segment(routeName, routeSize, routeColor(route)),
                    new PidsXiamenText.Segment(" ", routeSize, PidsXiamenLayout.COLOR_WHITE),
                    new PidsXiamenText.Segment(countdown.number(), stateSize, count.color())
            };
            return new Line(state, Math.max(routeSize, stateSize), card.x(routeStyle.xRatio()),
                    PidsXiamenLayout.HAlign.LEFT, card.w() * 0.88F);
        }

        final float height = Math.max(Math.max(size(routeStyle, card), size(count, card)), size(unit, card));
        final PidsXiamenText.Segment[] segments = {
                new PidsXiamenText.Segment(routeName, size(routeStyle, card), routeColor(route)),
                // 「约」前面留一个空格：线路号和「约」之间要断开（「1号线 约 3 分钟」）
                new PidsXiamenText.Segment(" " + Text.translatable("gui.mtrlte.pids.about").getString(),
                        size(about, card), about.color()),
                new PidsXiamenText.Segment(pad(countdown.number()), size(count, card), count.color()),
                new PidsXiamenText.Segment(padUnit(countdown.unit()), size(unit, card), unit.color())
        };
        // 前三段按同一基线左对齐；整体宽度超了就等比缩（drawRun 内做）
        return new Line(segments, height, card.x(routeStyle.xRatio()),
                PidsXiamenLayout.HAlign.LEFT, card.w() * 0.88F);
    }

    /** 线路色（ARGB）。MTR 的 {@code route.color} 是 {@code 0xRRGGBB}；没设过（0）= 白色。 */
    private static int routeColor(final Route route) {
        if (route == null || (route.color & 0xFFFFFF) == 0) {
            return PidsXiamenLayout.COLOR_WHITE;
        }
        return 0xFF000000 | (route.color & 0xFFFFFF);
    }

    /** 地铁的倒计时行：大数字（白）+ 小单位，底边对齐。 */
    private Line countLine(final PidsXiamenLayout.TextStyle count, final PidsXiamenLayout.TextStyle unit,
                           final PidsXiamenLayout.Rect card, final Countdown countdown) {
        final float countSize = size(count, card);
        final float unitSize = size(unit, card);
        final PidsXiamenText.Segment[] segments = {
                new PidsXiamenText.Segment(countdown.number(), countSize, count.color()),
                new PidsXiamenText.Segment(padUnit(countdown.unit()), unitSize, unit.color())
        };
        return new Line(segments, Math.max(countSize, unitSize),
                card.x(count.xRatio()), PidsXiamenLayout.HAlign.LEFT, card.w() * 0.55F);
    }

    // ------------------------------------------------------------------ 广告

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
     * <p>天津的 {@code RenderPIDSTianjin.renderAdvertisement} 是先把当前广告的
     * {@code text} 交给 {@code ScrollingText} 去滚，等 {@code scrollText(...)} 返回
     * {@code true}（整条滚完、出了左边缘）才调 {@code nextSlide(...)} 换下一张；
     * 换图的同时 {@code ScrollingText.changeImage(...)} 把 {@code ticksOffset} 重置，
     * 新标语从右边缘重新进来。
     *
     * <p>这里把同一套时序算成一条<b>无状态</b>的时间线（渲染线程不写任何字段，
     * 两面屏 / 多块屏 / 存档重进都对得上）：
     * <pre>
     *   第 i 张的时长 dwell[i] = 这张广告的标语「全部滚完」的 tick 数
     *   总周期 total = Σ dwell[i]
     *   t = gameTick mod total  →  落在哪一段就是第几张，段内偏移就是标语相位
     * </pre>
     * 一张广告 = 段长恒为 total = 不轮播；≥2 张才转，且每张的停留时间按它自己那句标语的
     * 长度自动变（长句多停、短句少停）—— 这就是「标语全滚完再切下一张」。
     *
     * <p>{@code adIndex}（方块实体里存的「起播偏移」）不是加在下标上，而是<b>旋转时间线的起点</b>：
     * 否则「这张图的时长」和「这张图自己的标语」就对不上，标语会被切在半路。
     *
     * <p>广告关掉 / 没有广告 / 标语表是空的时候整段不画（只留底图的空白广告区）。
     */
    private static Slide slideOf(final TextRenderer textRenderer, final PidsXiamenBlock.Kind kind,
                                 final long gameTick, final int adIndex,
                                 final List<PidsXiamenAds.Ad> ads) {
        if (!PidsXiamenSettings.advertisements()) {
            return Slide.NONE;
        }
        final int count = ads == null ? 0 : ads.size();
        if (count <= 0) {
            return Slide.NONE;
        }
        final PidsXiamenLayout.Rect bar = tickerOf(kind);
        final float sizeRatio = tickerTextOf(kind).sizeRatio();

        // 时间线按「旋转后的顺序」铺：第 0 段 = ads[adIndex mod count]。
        final PidsXiamenAds.Ad[] order = new PidsXiamenAds.Ad[count];
        final int[] dwell = new int[count];
        long total = 0L;
        for (int slot = 0; slot < count; slot++) {
            final int index = Math.floorMod(adIndex + slot, count);
            order[slot] = ads.get(index);
            dwell[slot] = dwellTicks(textRenderer, order[slot], bar, sizeRatio);
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

    /**
     * 一张广告该停多少 tick：它自己的标语滚完的时间。
     *
     * <p>这张广告没有 {@code text} 时，标语用的是资源包那套（{@link PidsXiamenTicker#messages()}），
     * 时长就按那一整套滚完来算 —— 两边用的是同一份列表，所以「换图」仍然等于「标语滚完」。
     * 兜底（标语表空 / 条太窄）用 {@link PidsXiamenLayout#AD_ADVANCE_TICKS}。
     */
    private static int dwellTicks(final TextRenderer textRenderer, final PidsXiamenAds.Ad ad,
                                  final PidsXiamenLayout.Rect bar, final float sizeRatio) {
        if (ad == null) {
            return PidsXiamenLayout.AD_ADVANCE_TICKS;
        }
        final List<PidsXiamenTicker.Message> caption = captionOf(ad);
        final int ticks = PidsXiamenTicker.scrollSpanTicks(textRenderer,
                caption == null ? PidsXiamenTicker.messages() : caption, bar, sizeRatio);
        return ticks > 0 ? ticks : PidsXiamenLayout.AD_ADVANCE_TICKS;
    }

    /**
     * 广告：<b>铺满版式给的矩形，四边贴齐</b>（{@link PidsXiamenLayout#AD_KEEP_ASPECT} 为假时
     * 直接拉伸；为真时按原图比例缩进去，居中留白 —— 默认是假，也就是「贴边」）。
     *
     * <p>贴图走 {@link MoreRenderLayers#getLight}——<b>和底图同一层</b>：beacon 层按贴图原色画、
     * 不过光照贴图，所以广告和底图一样满亮（用户报的「广告不够亮」就是之前用
     * {@code getEntityCutoutNoCull} 吃了光照贴图那一档）。天津的广告也是这条层。
     *
     * <p>哪一张、播到哪儿由 {@link #slideOf} 定（标语滚完才换）。
     */
    private static void drawAdvertisement(final MatrixStack matrices,
                                          final VertexConsumerProvider vertexConsumers,
                                          final PidsXiamenBlock.Kind kind, final Slide slide) {
        final PidsXiamenAds.Ad ad = slide.ad();
        if (ad == null || ad.image() == null) {
            return;
        }

        // 宽高全部由版式推出来（参考图要求「可拉伸」：框多大就铺多大，不写死尺寸）
        final PidsXiamenLayout.Rect box = adOf(kind);
        float width = box.w();
        float height = box.h();
        if (PidsXiamenLayout.AD_KEEP_ASPECT) {
            final float boxAspect = width / height;
            final float imageAspect = PidsXiamenAds.aspectRatio();
            if (imageAspect > 0.0F) {
                if (imageAspect > boxAspect) {
                    height = width / imageAspect;
                } else {
                    width = height * imageAspect;
                }
            }
        }
        drawQuad(matrices,
                vertexConsumers.getBuffer(MoreRenderLayers.getLight(ad.image(), false)),
                box.x0() + (box.w() - width) * 0.5F,
                box.y0() + (box.h() - height) * 0.5F,
                width, height);

        // 每种屏第一次画广告时打一行：广告矩形、实际画多大、第几张、贴图是否找得到。
        // 「广告消失」这类问题光看屏分不出「没画」还是「被底图盖住」，有这行就能排除前者。
        if (AD_LOGGED.add(kind)) {
            LteLog.LOGGER.info("[lte] pids ad: kind={} box=({},{}..{},{}) index={}/{} tick={} tex={}",
                    kind, box.x0(), box.y0(), box.x1(), box.y1(),
                    slide.index(), slide.count(), slide.tick(), ad.image());
        }
    }

    /**
     * 这一帧的广告标语（天津语义：广告自带的 {@code text} 就是屏下那条滚动文字）。
     *
     * @return {@code null} = 广告没画 / 这张没有文字 → 回落到 {@link PidsXiamenTicker} 的资源包列表
     */
    private static List<PidsXiamenTicker.Message> captionOf(final PidsXiamenAds.Ad ad) {
        if (ad == null || ad.text() == null) {
            return null;
        }
        final String text = ad.text().getString();
        if (text == null || text.isBlank()) {
            return null;
        }
        return List.of(new PidsXiamenTicker.Message(text, PidsXiamenLayout.COLOR_WHITE, 0.0F));
    }

    // ------------------------------------------------------------------ 排版工具

    /** 把一行 {@link Line} 摆进框里：整体垂直居中，行间留 {@code gapRatio × 框高}。 */
    private void drawFlow(final MatrixStack matrices, final TextRenderer textRenderer,
                          final VertexConsumerProvider.Immediate immediate,
                          final PidsXiamenLayout.Rect card, final float padRatio, final float gapRatio,
                          final Line... lines) {
        if (lines.length == 0) {
            return;
        }
        final float gap = card.h() * gapRatio;
        float total = gap * (lines.length - 1);
        for (final Line line : lines) {
            total += line.height();
        }
        float y = card.y0() + (card.h() - total) * 0.5F;
        // 框太矮时从内边距开始，宁可压一点也别把字挤出框
        y = Math.max(y, card.y0() + card.h() * padRatio);
        for (final Line line : lines) {
            if (hasText(line)) {
                PidsXiamenText.drawRun(matrices, textRenderer, immediate, line.segments(),
                        line.x(), y, line.height(), line.align(), line.maxWidth(),
                        Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
            }
            y += line.height() + gap;
        }
    }

    private static boolean hasText(final Line line) {
        for (final PidsXiamenText.Segment segment : line.segments()) {
            if (segment.text() != null && !segment.text().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 单段一行。 */
    private static Line line(final PidsXiamenLayout.TextStyle style, final PidsXiamenLayout.Rect card,
                             final String text) {
        final float size = size(style, card);
        return new Line(new PidsXiamenText.Segment[]{new PidsXiamenText.Segment(text, size, style.color())},
                size, card.x(style.xRatio()), style.align(), card.w() * style.maxWidthRatio());
    }

    /** 字号（px）= 框高 × sizeRatio。 */
    private static float size(final PidsXiamenLayout.TextStyle style, final PidsXiamenLayout.Rect card) {
        return Math.max(3.0F, card.h() * style.sizeRatio());
    }

    /** 混排行里给「数字」前面留一个空格（参考图里「约 3」是分开的）。 */
    private static String pad(final String text) {
        return text == null || text.isEmpty() ? "" : " " + text;
    }

    /** 单位前面留一个空格（「3 分钟」）；单位为空（即将进站 / 已到站）就不留。 */
    private static String padUnit(final String unit) {
        return unit == null || unit.isEmpty() ? "" : " " + unit;
    }

    // ------------------------------------------------------------------ 版式选择

    private static PidsXiamenLayout.Clock clockOf(final PidsXiamenBlock.Kind kind) {
        return kind == PidsXiamenBlock.Kind.BRT ? PidsXiamenLayout.BRT.clock() : PidsXiamenLayout.METRO.clock();
    }

    private static PidsXiamenLayout.Rect adOf(final PidsXiamenBlock.Kind kind) {
        return kind == PidsXiamenBlock.Kind.BRT ? PidsXiamenLayout.BRT.ad() : PidsXiamenLayout.METRO.ad();
    }

    private static PidsXiamenLayout.Rect tickerOf(final PidsXiamenBlock.Kind kind) {
        return kind == PidsXiamenBlock.Kind.BRT
                ? PidsXiamenLayout.BRT.ticker() : PidsXiamenLayout.METRO.ticker();
    }

    private static PidsXiamenLayout.TextStyle tickerTextOf(final PidsXiamenBlock.Kind kind) {
        return kind == PidsXiamenBlock.Kind.BRT
                ? PidsXiamenLayout.BRT.tickerText() : PidsXiamenLayout.METRO.tickerText();
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

    // ------------------------------------------------------------------ 数据

    /** 一帧算一次的东西，两面共用。 */
    private record Screen(PidsXiamenBlock.Kind kind, List<ScheduleEntry> schedules, int displayPage,
                          Slide slide, long gameTick, String clock, String dateIso, String dateBrt) {
    }

    /** 倒计时：「12 / 分钟」、「即将进站」或者「已到站」。 */
    private record Countdown(String number, String unit) {
    }

    /**
     * 倒计时。
     *
     * <p>{@link PidsXiamenSettings#arriving()} 打开时（默认）：一分钟以内写「即将进站」，
     * 已经过了到点时间写「已到站」—— 厦门地铁屏上就是这个说法，不会出现「0 秒」这种读着像坏了的数字。
     * 关掉时回到 MTR 式的「X 秒 / X 分钟」。
     */
    private static Countdown countdown(final ScheduleEntry entry) {
        final long remainingSeconds = (entry.arrivalMillis - System.currentTimeMillis()) / 1000L;
        if (PidsXiamenSettings.arriving() && remainingSeconds <= ARRIVING_SECONDS) {
            final String state = Text.translatable(remainingSeconds < 0L
                    ? "gui.mtrlte.pids.arrived" : "gui.mtrlte.pids.arriving").getString();
            return new Countdown(state, "");
        }
        final boolean minutes = remainingSeconds > 60L;
        return new Countdown(
                String.valueOf(minutes ? remainingSeconds / 60L : Math.max(0L, remainingSeconds)),
                Text.translatable(minutes ? "gui.mtrlte.pids.minute" : "gui.mtrlte.pids.second").getString());
    }

    /** 第 {@code row} 行对应哪条到站（考虑翻页）。 */
    private static ScheduleEntry entryAt(final Screen screen, final int row) {
        final int index = row + screen.displayPage() * PidsXiamenLayout.rowsOf(screen.kind());
        return index >= 0 && index < screen.schedules().size() ? screen.schedules().get(index) : null;
    }

    /** 终点站：MTR 给的是「中文|英文」，拆成两行。 */
    private static String[] destinations(final Route route, final ScheduleEntry entry) {
        try {
            final String[] parts = ClientData.DATA_CACHE
                    .getFormattedRouteDestination(route, entry.currentStationIndex, "").split("\\|", -1);
            final String first = firstPart(parts.length > 0 ? parts[0] : "");
            final String second = parts.length > 1 ? firstPart(parts[1]) : "";
            return new String[]{first, second};
        } catch (Throwable t) {
            return new String[]{"", ""};
        }
    }

    /** 「中文|英文」→ 前半；顺带去掉 MTR 可能塞进来的 {@code |} 与样式符号。 */
    private static String firstPart(final String raw) {
        if (raw == null) {
            return "";
        }
        final int bar = raw.indexOf('|');
        final String text = bar < 0 ? raw : raw.substring(0, bar);
        return text.replace("|", "").trim();
    }

    private static Station resolveStation(final BlockPos pos) {
        try {
            return PidsStationResolver.resolve(ClientData.STATIONS, ClientData.DATA_CACHE, pos).station();
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<ScheduleEntry> schedulesFor(final PidsXiamenBlockEntity entity, final BlockPos pos) {
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
    private static List<ScheduleEntry> getSchedules(final PidsXiamenBlockEntity entity, final Station station,
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
    private static String formatTime(final World world) {
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
    private static String formatIsoDate(final World world) {
        return dateNow(world).toString();
    }

    /**
     * 屏幕上那一天的日期。
     *
     * <p>{@link PidsXiamenSettings#localTime()} 打开时取<b>现实日期</b>；关掉时回落到世界天数
     * （第 0 天 = 2024-01-01 星期一），和钟点出自同一套时间。
     */
    private static LocalDate dateNow(final World world) {
        return PidsXiamenSettings.localTime() ? LocalDate.now() : worldDate(world);
    }

    private static LocalDate worldDate(final World world) {
        final long day = Math.max(0L, world.getTime() / 24000L);
        return EPOCH.plusDays(day);
    }

    /** BRT 的「MM月DD日星期X」。 */
    private static String formatBrtDate(final World world) {
        final LocalDate date = dateNow(world);
        final String weekday = Text.translatable("gui.mtrlte.pids.weekday." + date.getDayOfWeek().getValue()).getString();
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

    /** 单个步骤的隔离：某一步抛异常只让这一步不画，不连累同一面屏的其它步骤。 */
    private static void step(final String what, final Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.error("[lte] xiamen pids: step '{}' failed", what, t);
            }
        }
    }

    // ------------------------------------------------------------------ 杂项

    @Override
    public boolean rendersOutsideBoundingBox(final PidsXiamenBlockEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 64;
    }

    /** 方块 facing（与天津用同一个属性：{@code HorizontalFacingBlock.FACING}）。 */
    private static final class IBlockFacing {

        private static final Property<Direction> FACING = HorizontalFacingBlock.FACING;

        private IBlockFacing() {
        }

        static Direction of(final World world, final BlockPos pos) {
            try {
                return mtr.block.IBlock.getStatePropertySafe(world, pos, FACING);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
