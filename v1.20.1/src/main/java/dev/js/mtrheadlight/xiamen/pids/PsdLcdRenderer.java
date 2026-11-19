// PSD-LCD 挂板的渲染器：排版全部来自 PsdLcdLayout，这里只负责「把指令画进世界」。
package dev.js.mtrheadlight.xiamen.pids;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 厦门地铁 PSD-LCD 挂板（{@code mtrlte:psd_lcd_xiamen}）的方块实体渲染器。
 *
 * <h2>只做三件事</h2>
 * <ol>
 *   <li>拿到这一帧的内容（时钟 / 两班到站 / 当前站 / 线路 / 广告 / 标语），拼成
 *       {@link PsdLcdLayout.Content}；</li>
 *   <li>让 {@link PsdLcdLayout#plan} 排版 —— <b>一个坐标都不在本类里写</b>；</li>
 *   <li>把指令表画出去：色块走「白色贴图 + 顶点色」的自发光层，文字走
 *       {@link PidsXiamenText}（和厦门 PIDS 同一套，1px = 1px）。</li>
 * </ol>
 * 于是「游戏里的画面」和 `tools/preview-psd-lcd.js` 出的预览图是同一张指令表 ——
 * 预览那边只是把同样的指令交给 {@code Graphics2D}。
 *
 * <h2>坐标系</h2>
 * <p>屏幕平面宽 {@code 520 × 段数} px、高 160 px，原点左上、+x 向右（= 阅读方向）、
 * +y 向下、+z 扎进屏幕。世界变换：
 * <pre>
 *   translate(anchor 最左一格的左边缘, 面板上沿, 朝外 0.504 格)
 *   rotY(90 - readDir.asRotation())      readDir = facing.rotateYCounterclockwise()
 *   rotZ(180)                            ← 必须是 Z 轴 180°（写成 Y 轴会让字上下颠倒）
 *   scale(1 / 520)
 * </pre>
 * 这三步之后 +X = {@code readDir}、+Y = 世界向下、−Z = {@code facing}（朝外）。
 *
 * <h2>横向连接：只由 anchor 画一次</h2>
 * <p>{@link PsdLcdBlock#anchorOf} 找到链条最左一格，{@link PsdLcdBlock#lengthOf} 数出几格，
 * 宽度 = 520 × 格数。其它格直接 return —— 否则一整条会被画成好几段叠在一起。
 *
 * <h2>深度分层</h2>
 * <p>色块 / 描边 / 图标都走 {@link MoreRenderLayers#getLight}（beacon 层：贴图原色、不过光照贴图、
 * 写深度、不剔除），每一条指令的 z 依次往观察者方向挪 0.01px —— 总共不到 1.5px（≈3mm），
 * 视觉上就是一块平屏，但深度测试 / 上传排序（谁先谁后）都能稳定把「后画的」压在「先画的」上面。
 * 广告图在 −1.4px、文字在 −2.0px。
 */
public class PsdLcdRenderer extends BlockEntityRendererMapper<PsdLcdBlockEntity> {

    /**
     * 1×1 纯白贴图：色块用它当「画笔」—— 顶点色 × 白 = 想要的颜色，
     * 而 beacon 层不吃光照贴图，所以挂板永远是满亮的一块屏。
     */
    private static final Identifier WHITE_TEXTURE =
            new Identifier(PsdLcdObjects.NAMESPACE, "textures/block/white.png");

    /** 每条指令之间往观察者方向挪多少 px。 */
    private static final float Z_STEP = 0.01F;
    /** 广告图所在的深度（px）。 */
    private static final float AD_Z = -1.40F;
    /** 文字层深度（px）。 */
    private static final float TEXT_Z = -2.00F;
    /** 圆角圆弧每 1.5px 一段。 */
    private static final float ARC_STEP = 1.5F;

    /** 编组卡片的张数（没有实时编组数据时的固定示意）。 */
    private static final int DEFAULT_CARS = 4;

    private boolean warned;

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
        } catch (Throwable t) {
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
        final List<PsdLcdLayout.Op> ops = PsdLcdLayout.plan(content.content(), segments);

        matrices.push();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        try {
            applyTransform(matrices, facing);
            final VertexConsumer vc = vertexConsumers.getBuffer(MoreRenderLayers.getLight(WHITE_TEXTURE, false));
            final Matrix4f matrix = matrices.peek().getPositionMatrix();
            final Matrix3f normal = matrices.peek().getNormalMatrix();

            float z = 0.0F;
            for (final PsdLcdLayout.Op op : ops) {
                if (op instanceof PsdLcdLayout.Fill fill) {
                    fill(vc, matrix, normal, fill.x0(), fill.y0(), fill.x1(), fill.y1(), z, fill.color());
                    z -= Z_STEP;
                } else if (op instanceof PsdLcdLayout.Quad quad) {
                    quad(vc, matrix, normal, quad, z);
                    z -= Z_STEP;
                } else if (op instanceof PsdLcdLayout.RoundFill round) {
                    roundFill(vc, matrix, normal, round, z);
                    z -= Z_STEP;
                } else if (op instanceof PsdLcdLayout.RoundStroke stroke) {
                    roundStroke(vc, matrix, normal, stroke, z);
                    z -= Z_STEP;
                } else if (op instanceof PsdLcdLayout.Disc disc) {
                    disc(vc, matrix, normal, disc.cx(), disc.cy(), disc.radius(), z, disc.color());
                    z -= Z_STEP;
                } else if (op instanceof PsdLcdLayout.AdSlot slot) {
                    drawAd(matrices, vertexConsumers, content.ad(), slot);
                } else if (op instanceof PsdLcdLayout.Ticker ticker) {
                    final PidsXiamenLayout.Rect bar = new PidsXiamenLayout.Rect(
                            ticker.x0(), ticker.y0(), ticker.x1(), ticker.y1());
                    PidsXiamenTicker.draw(matrices, textRenderer, immediate, bar, ticker.sizeRatio(),
                            content.tickerTick(), content.caption());
                } else if (op instanceof PsdLcdLayout.Text line) {
                    drawText(matrices, textRenderer, immediate, line);
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

    /**
     * 屏幕的世界变换（见类注释）。{@code matrices} 进来时原点已经是 anchor 那一格的最小角。
     */
    private static void applyTransform(final MatrixStack matrices, final Direction facing) {
        // 阅读方向 = 朝外方向逆时针 90°；链条方向（rotateYClockwise）正好是它的反向，
        // 所以「沿链条走到头」拿到的是视觉最左一格（见 PsdLcdBlock#anchorOf）。
        final Direction readDir = facing.rotateYCounterclockwise();
        final float x = 0.5F - readDir.getOffsetX() * 0.5F + facing.getOffsetX() * PsdLcdLayout.ANCHOR_OUT;
        final float z = 0.5F - readDir.getOffsetZ() * 0.5F + facing.getOffsetZ() * PsdLcdLayout.ANCHOR_OUT;
        final float y = 0.5F + PsdLcdLayout.HEIGHT_BLOCKS * 0.5F;
        matrices.translate(x, y, z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0F - readDir.asRotation()));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180.0F));
        final float scale = 1.0F / PsdLcdLayout.SCREEN_SCALE_DIVISOR;
        matrices.scale(scale, scale, scale);
    }

    // ------------------------------------------------------------------ 图元

    private static void fill(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                             final float x0, final float y0, final float x1, final float y1,
                             final float z, final int argb) {
        emit(vc, m, n, x0, y0, x0, y1, x1, y1, x1, y0, z, argb);
    }

    private static void quad(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                             final PsdLcdLayout.Quad q, final float z) {
        emit(vc, m, n, q.x0(), q.y0(), q.x1(), q.y1(), q.x2(), q.y2(), q.x3(), q.y3(), z, q.color());
    }

    /** 一个四边形（顺时针写，uv 全取贴图中心的白色）。 */
    private static void emit(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                             final float ax, final float ay, final float bx, final float by,
                             final float cx, final float cy, final float dx, final float dy,
                             final float z, final int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        final int a = (argb >>> 24) & 0xFF;
        vertex(vc, m, n, ax, ay, z, r, g, b, a);
        vertex(vc, m, n, bx, by, z, r, g, b, a);
        vertex(vc, m, n, cx, cy, z, r, g, b, a);
        vertex(vc, m, n, dx, dy, z, r, g, b, a);
    }

    private static void vertex(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                               final float x, final float y, final float z,
                               final int r, final int g, final int b, final int a) {
        vc.vertex(m, x, y, z)
                .color(r, g, b, a)
                .texture(0.5F, 0.5F)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, 0.0F, 0.0F, -1.0F)
                .next();
    }

    private static void roundFill(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                                  final PsdLcdLayout.RoundFill r, final float z) {
        final float rad = Math.min(r.radius(), Math.min(r.x1() - r.x0(), r.y1() - r.y0()) * 0.5F);
        if (rad <= 0.05F) {
            fill(vc, m, n, r.x0(), r.y0(), r.x1(), r.y1(), z, r.color());
            return;
        }
        fill(vc, m, n, r.x0(), r.y0() + rad, r.x1(), r.y1() - rad, z, r.color());
        fill(vc, m, n, r.x0() + rad, r.y0(), r.x1() - rad, r.y1(), z, r.color());
        disc(vc, m, n, r.x0() + rad, r.y0() + rad, rad, z, r.color());
        disc(vc, m, n, r.x1() - rad, r.y0() + rad, rad, z, r.color());
        disc(vc, m, n, r.x0() + rad, r.y1() - rad, rad, z, r.color());
        disc(vc, m, n, r.x1() - rad, r.y1() - rad, rad, z, r.color());
    }

    private static void roundStroke(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                                    final PsdLcdLayout.RoundStroke s, final float z) {
        final float w = Math.min(s.x1() - s.x0(), s.y1() - s.y0()) * 0.5F;
        final float rad = Math.max(0.0F, Math.min(s.radius(), w - 0.01F));
        final float t = Math.max(0.25F, Math.min(s.thickness(), rad > 0.25F ? rad : 0.25F));
        if (rad <= 0.25F) {
            fill(vc, m, n, s.x0(), s.y0(), s.x1(), s.y0() + t, z, s.color());
            fill(vc, m, n, s.x0(), s.y1() - t, s.x1(), s.y1(), z, s.color());
            fill(vc, m, n, s.x0(), s.y0(), s.x0() + t, s.y1(), z, s.color());
            fill(vc, m, n, s.x1() - t, s.y0(), s.x1(), s.y1(), z, s.color());
            return;
        }
        // 四条直边
        fill(vc, m, n, s.x0() + rad, s.y0(), s.x1() - rad, s.y0() + t, z, s.color());
        fill(vc, m, n, s.x0() + rad, s.y1() - t, s.x1() - rad, s.y1(), z, s.color());
        fill(vc, m, n, s.x0(), s.y0() + rad, s.x0() + t, s.y1() - rad, z, s.color());
        fill(vc, m, n, s.x1() - t, s.y0() + rad, s.x1(), s.y1() - rad, z, s.color());
        // 四段圆角（从外半径画到内半径）
        arc(vc, m, n, s.x0() + rad, s.y0() + rad, rad, t, 180.0F, 270.0F, z, s.color());
        arc(vc, m, n, s.x1() - rad, s.y0() + rad, rad, t, 270.0F, 360.0F, z, s.color());
        arc(vc, m, n, s.x1() - rad, s.y1() - rad, rad, t, 0.0F, 90.0F, z, s.color());
        arc(vc, m, n, s.x0() + rad, s.y1() - rad, rad, t, 90.0F, 180.0F, z, s.color());
    }

    /** 圆角的一段圆环（屏幕坐标：y 向下，角度 0 = +x、90 = +y）。 */
    private static void arc(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                            final float cx, final float cy, final float radius, final float thickness,
                            final float fromDeg, final float toDeg, final float z, final int argb) {
        final int steps = Math.max(2, (int) Math.ceil(radius / ARC_STEP));
        for (int i = 0; i < steps; i++) {
            final double a0 = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / (double) steps);
            final double a1 = Math.toRadians(fromDeg + (toDeg - fromDeg) * (i + 1) / (double) steps);
            final float ox0 = cx + (float) Math.cos(a0) * radius;
            final float oy0 = cy + (float) Math.sin(a0) * radius;
            final float ox1 = cx + (float) Math.cos(a1) * radius;
            final float oy1 = cy + (float) Math.sin(a1) * radius;
            final float ix0 = cx + (float) Math.cos(a0) * (radius - thickness);
            final float iy0 = cy + (float) Math.sin(a0) * (radius - thickness);
            final float ix1 = cx + (float) Math.cos(a1) * (radius - thickness);
            final float iy1 = cy + (float) Math.sin(a1) * (radius - thickness);
            emit(vc, m, n, ox0, oy0, ox1, oy1, ix1, iy1, ix0, iy0, z, argb);
        }
    }

    /** 圆：凑成扇形四边形（QUADS 没有三角图元）。 */
    private static void disc(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                             final float cx, final float cy, final float radius,
                             final float z, final int argb) {
        if (radius <= 0.0F) {
            return;
        }
        final int steps = Math.max(6, (int) Math.ceil(radius * 2.0F / ARC_STEP));
        for (int i = 0; i < steps; i++) {
            final double a0 = Math.PI * 2.0D * i / steps;
            final double a1 = Math.PI * 2.0D * (i + 1) / steps;
            final double am = (a0 + a1) * 0.5D;
            emit(vc, m, n,
                    cx, cy,
                    cx + (float) Math.cos(a0) * radius, cy + (float) Math.sin(a0) * radius,
                    cx + (float) Math.cos(am) * radius * 1.02F, cy + (float) Math.sin(am) * radius * 1.02F,
                    cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius,
                    z, argb);
        }
    }

    // ------------------------------------------------------------------ 文字 / 广告

    private static void drawText(final MatrixStack matrices, final TextRenderer textRenderer,
                                 final VertexConsumerProvider.Immediate immediate,
                                 final PsdLcdLayout.Text line) {
        if (line.text() == null || line.text().isEmpty()) {
            return;
        }
        final PidsXiamenLayout.HAlign align = switch (line.align()) {
            case CENTER -> PidsXiamenLayout.HAlign.CENTER;
            case RIGHT -> PidsXiamenLayout.HAlign.RIGHT;
            default -> PidsXiamenLayout.HAlign.LEFT;
        };
        matrices.push();
        try {
            matrices.translate(0.0F, 0.0F, TEXT_Z);
            PidsXiamenText.drawRun(matrices, textRenderer, immediate,
                    new PidsXiamenText.Segment[]{new PidsXiamenText.Segment(
                            line.text(), Math.max(1.0F, line.size()), line.color())},
                    line.x(), line.yTop(), line.size(), align, line.maxWidth(),
                    Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
        } finally {
            matrices.pop();
        }
    }

    /** 广告图：铺满版式给的那块矩形（和厦门 PIDS 一样走 beacon 层，满亮）。 */
    private static void drawAd(final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                               final PidsXiamenAds.Ad ad, final PsdLcdLayout.AdSlot slot) {
        if (ad == null || ad.image() == null) {
            return;
        }
        matrices.push();
        try {
            matrices.translate(0.0F, 0.0F, AD_Z);
            final MatrixStack.Entry entry = matrices.peek();
            final Matrix4f m = entry.getPositionMatrix();
            final Matrix3f n = entry.getNormalMatrix();
            final VertexConsumer vc = vertexConsumers.getBuffer(MoreRenderLayers.getLight(ad.image(), false));
            final int white = 0xFFFFFFFF;
            uvQuad(vc, m, n, slot.x0(), slot.y0(), slot.x1(), slot.y1(), 0.0F, 1.0F, white);
        } finally {
            matrices.pop();
        }
    }

    /** 带整张 uv 的矩形（广告图）。 */
    private static void uvQuad(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                               final float x0, final float y0, final float x1, final float y1,
                               final float u0, final float v0, final int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        vertexUv(vc, m, n, x0, y0, u0, v0, r, g, b);
        vertexUv(vc, m, n, x0, y1, u0, 1.0F, r, g, b);
        vertexUv(vc, m, n, x1, y1, 1.0F, 1.0F, r, g, b);
        vertexUv(vc, m, n, x1, y0, 1.0F, v0, r, g, b);
    }

    private static void vertexUv(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                                 final float x, final float y, final float u, final float v,
                                 final int r, final int g, final int b) {
        vc.vertex(m, x, y, 0.0F)
                .color(r, g, b, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, 0.0F, 0.0F, -1.0F)
                .next();
    }

    // ------------------------------------------------------------------ 内容

    /** 一帧算一次的内容 + 广告 / 标语的两样附赠品。 */
    private record Content(PsdLcdLayout.Content content, PidsXiamenAds.Ad ad,
                           List<PidsXiamenTicker.Message> caption, long tickerTick) {
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
        try {
            final Station station = RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos);
            if (station != null) {
                final String[] parts = station.name.split("\\|", -1);
                stationZh = parts.length > 0 ? parts[0].trim() : "";
                stationEn = parts.length > 1 ? parts[1].trim() : "";
            }
        } catch (Throwable ignored) {
            // 车站表还没同步：站名留空，其余照画。
        }

        // ---- 编组（没有实时数据时的固定示意）
        final List<String> carLabels = new ArrayList<>(DEFAULT_CARS);
        for (int i = 1; i <= DEFAULT_CARS; i++) {
            carLabels.add(Text.translatable("gui.mtrlte.psd_lcd.car", i).getString());
        }

        // ---- 广告 + 标语
        final List<PidsXiamenAds.Ad> ads = PidsXiamenSettings.applyAdOverride(entity.getAds());
        final Chosen chosen = adAt(ads, entity.getAdvertisementIndex(), gameTick, tickerRect(segments));
        final PidsXiamenAds.Ad ad = chosen == null ? null : chosen.ad();
        final boolean hasAd = ad != null && ad.image() != null;
        final List<PidsXiamenTicker.Message> caption = hasAd && ad.text() != null
                && !ad.text().getString().isBlank()
                ? List.of(new PidsXiamenTicker.Message(ad.text().getString(), PidsXiamenLayout.COLOR_WHITE, 0.0F))
                : null;
        final long tickerTick = chosen == null ? gameTick : chosen.tick();

        final PsdLcdLayout.Content content = new PsdLcdLayout.Content(
                dateText, weekdayZh, weekdayEn, clock,
                Text.translatable("gui.mtrlte.pids.bound_for_label").getString(),
                Text.translatable("gui.mtrlte.pids.bound_for_sub").getString(),
                dest1[0], dest1[1], count1.number(),
                Text.translatable("gui.mtrlte.pids.next_train_label").getString(),
                Text.translatable("gui.mtrlte.pids.next_train_sub").getString(),
                dest2[0], dest2[1], count2.number(),
                unitZh, unitEn,
                lineZh, lineEn, lineColor,
                stationZh, stationEn, Text.translatable("gui.mtrlte.psd_lcd.this_station").getString(),
                DEFAULT_CARS, carLabels,
                Text.translatable("gui.mtrlte.psd_lcd.place_ad").getString(),
                "", hasAd);
        return new Content(content, ad, caption, tickerTick);
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

    /** 标语条的矩形（广告轮播的节拍要用它的宽度算「这条滚完要多久」）。 */
    private static PidsXiamenLayout.Rect tickerRect(final int segments) {
        final PsdLcdLayout.Box box = PsdLcdLayout.tickerBox(segments);
        return new PidsXiamenLayout.Rect(box.x0(), box.y0(), box.x1(), box.y1());
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
                    caption == null ? PidsXiamenTicker.messages() : caption, bar, 0.44F);
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
