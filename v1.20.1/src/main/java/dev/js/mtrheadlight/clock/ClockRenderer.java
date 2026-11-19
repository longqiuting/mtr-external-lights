// 时钟的渲染器：**整个钟面都是代码画的**（POSITION_COLOR，不吃贴图、不吃光照贴图）。
package dev.js.mtrheadlight.clock;

import net.minecraft.block.BlockState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * 时钟方块实体的渲染器 —— 圆盘钟面、三根指针、中心轴、12 个刻度 / 数字，全部用
 * {@link RenderLayer#getDebugQuads()}（{@code POSITION_COLOR}）在 {@link MatrixStack} 的局部
 * 坐标里画出来。
 *
 * <h2>为什么用 debug quads</h2>
 * <p>这一层是纯顶点色：<b>没有贴图采样、也不吃光照贴图</b>，写进去的 RGB 就是屏幕上看到的样子。
 * 所以「发光 = 颜色不衰减」只要改乘不乘亮度系数就行，不需要额外的渲染层、也不需要贴图。
 * quads 层只有 QUADS 一种图元，圆盘用「中心 + 弧上两点 + 弧中点」的扇形四边形凑（见
 * {@link #disc}）。
 *
 * <h2>局部空间</h2>
 * <p>方块实体渲染时矩阵已经平移到了方块最小角，所以本类里的坐标是「0..1 的方块内空间」，
 * 钟心固定 {@code (0.5, 0.5, 0.5)}。以 {@code FACING} 为 +Z 建一组基：
 * <pre>
 *   up    = 水平朝向 ? (0,1,0) : (0,0,1)      // 上下朝向时世界 up 与法线重合，换 (0,0,1)
 *   right = up × n                            // 从正面看过去的「右」
 *   faceUp= n × right                         // = 投影到钟面里的 up
 * </pre>
 * 钟面上任意一点 = 钟心 + right·a + faceUp·b + n·depth，其中 {@code a = r·sinθ, b = r·cosθ}，
 * θ 从 12 点方向<b>顺时针</b>量。三个 depth 常量把「盘面 / 刻度 / 指针 / 中心轴」分层，
 * 避免同一平面上打架。
 *
 * <h2>远处的钟不许消失</h2>
 * <p>{@link #getRenderDistance()} 返回 {@value #RENDER_DISTANCE}（1.20.1 的
 * {@code BlockEntityRenderer} 本来就有这个方法，NTE 的装饰物件就是这么干的），
 * {@link #rendersOutsideBoundingBox} 返回 {@code true}（钟面最大可以画到 54 格半径，
 * 远超方块的包围盒）。少了这两个，站台大钟走远一点就整块消失。
 *
 * <h2>亮度</h2>
 * <p>不发光时按 {@code max(方块光, 天空光 × 白天系数)} 映射到 0.35–1.0 乘到颜色上 ——
 * 夜里钟面变暗、被灯照着又亮回来。{@link ClockConfig.GlowMode} 命中当前昼夜时系数固定 1.0
 * （「看起来亮」，但不往世界里打方块光）。
 */
public final class ClockRenderer implements BlockEntityRenderer<ClockBlockEntity> {

    /** 远处不消失：384 格（比默认的 64 格远得多，站台两头都看得到）。 */
    public static final int RENDER_DISTANCE = 384;

    /** 钟面盘心离方块中心多远（沿法线，格）。方块面在 0.5，这里退进 0.03 免得和邻格的面对穿。 */
    private static final double DISC_DEPTH = 0.470D;
    /** 刻度的深度。 */
    private static final double TICK_DEPTH = 0.472D;
    /** 时针 / 分针 / 秒针 / 中心轴的深度（越靠前越不会被压住）。 */
    private static final double HOUR_DEPTH = 0.478D;
    private static final double MINUTE_DEPTH = 0.481D;
    private static final double SECOND_DEPTH = 0.484D;
    private static final double HUB_DEPTH = 0.490D;

    private static final double TWO_PI = Math.PI * 2.0D;

    private final TextRenderer textRenderer;

    public ClockRenderer(final BlockEntityRendererFactory.Context context) {
        this.textRenderer = context.getTextRenderer();
    }

    @Override
    public boolean rendersOutsideBoundingBox(final ClockBlockEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return RENDER_DISTANCE;
    }

    @Override
    public void render(final ClockBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof ClockBlock)) {
            return;
        }
        final ClockConfig cfg = entity.config();
        final Direction facing = state.get(ClockBlock.FACING);

        final double seconds = entity.displaySeconds(tickDelta);
        final double maxLength = Math.max(cfg.hourLength, Math.max(cfg.minuteLength, cfg.secondLength));
        final double radius = maxLength * 1.08D;
        final int segments = segmentsFor(radius);

        final Frame frame = new Frame(facing);
        final float bright = brightness(world, entity.getPos(), cfg);
        final int face = shade(cfg.faceColor, bright);
        final int tick = shade(cfg.tickColor, bright);
        final int hourHand = shade(cfg.hourColor, bright);
        final int minuteHand = shade(cfg.minuteColor, bright);
        final int secondHand = shade(cfg.secondColor, bright);
        final int hub = shade(cfg.hubColor, bright);

        final VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getDebugQuads());
        final Matrix4f matrix = matrices.peek().getPositionMatrix();

        // 顶点色就是最终颜色：位置光 / 实体受击闪红这些状态会通过 shader 的 ColorModulator
        // 影响输出，先归一到白色。
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        try {
            // ① 钟面
            frame.depth = DISC_DEPTH;
            this.disc(vc, matrix, frame, radius, face, segments);

            // ② 刻度 / 数字（在钟面之上、指针之下）
            frame.depth = TICK_DEPTH;
            this.ticks(vc, matrix, frame, cfg, tick, radius, matrices, vertexConsumers);

            // ③ 三根指针
            frame.depth = HOUR_DEPTH;
            this.hand(vc, matrix, frame, ClockTime.hourFraction(seconds),
                    cfg.hourLength, Math.max(0.012D, radius * 0.075D), hourHand);
            frame.depth = MINUTE_DEPTH;
            this.hand(vc, matrix, frame, ClockTime.minuteFraction(seconds),
                    cfg.minuteLength, Math.max(0.010D, radius * 0.055D), minuteHand);
            frame.depth = SECOND_DEPTH;
            this.hand(vc, matrix, frame, ClockTime.secondFraction(seconds),
                    cfg.secondLength, Math.max(0.006D, radius * 0.028D), secondHand);

            // ④ 中心轴盖住三根针的根部
            frame.depth = HUB_DEPTH;
            this.disc(vc, matrix, frame, Math.min(radius, radius * 0.04D + 0.03D), hub,
                    Math.max(16, segments / 3));
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
        }
    }

    // ------------------------------------------------------------------ 几何

    /**
     * 圆盘：每段一个四边形 {@code (中心, 弧起点, 弧中点, 弧终点)}。
     *
     * <p>QUADS 图元不能直接画三角扇，所以用「弧中点稍微顶出去」的四点四边形近似扇形；
     * 段数越多越接近圆（见 {@link #segmentsFor}）。
     */
    private void disc(final VertexConsumer vc, final Matrix4f matrix, final Frame frame,
                      final double radius, final int color, final int segments) {
        if (radius <= 0.0D) {
            return;
        }
        for (int i = 0; i < segments; i++) {
            final double t0 = TWO_PI * i / segments;
            final double t1 = TWO_PI * (i + 1) / segments;
            final double tm = (t0 + t1) * 0.5D;
            frame.vertex(vc, matrix, 0.0D, 0.0D, color);
            frame.vertex(vc, matrix, Math.sin(t0) * radius, Math.cos(t0) * radius, color);
            frame.vertex(vc, matrix, Math.sin(tm) * radius, Math.cos(tm) * radius, color);
            frame.vertex(vc, matrix, Math.sin(t1) * radius, Math.cos(t1) * radius, color);
        }
    }

    /** 一根指针：从钟心（带一点点尾巴）沿角度画一个梯形四边形。 */
    private void hand(final VertexConsumer vc, final Matrix4f matrix, final Frame frame,
                      final double fraction, final double length, final double width, final int color) {
        final double theta = fraction * TWO_PI;
        final double dx = Math.sin(theta);
        final double dy = Math.cos(theta);
        // 面内的垂直方向（右手边旋转 90°）
        final double px = Math.cos(theta);
        final double py = -Math.sin(theta);
        final double baseHalf = width * 0.5D;
        final double tipHalf = width * 0.16D;
        final double tail = length * 0.10D;

        frame.vertex(vc, matrix, -dx * tail + px * baseHalf, -dy * tail + py * baseHalf, color);
        frame.vertex(vc, matrix, dx * length + px * tipHalf, dy * length + py * tipHalf, color);
        frame.vertex(vc, matrix, dx * length - px * tipHalf, dy * length - py * tipHalf, color);
        frame.vertex(vc, matrix, -dx * tail - px * baseHalf, -dy * tail - py * baseHalf, color);
    }

    /** 12 个刻度：每 30° 一个，样式按配置。 */
    private void ticks(final VertexConsumer vc, final Matrix4f matrix, final Frame frame,
                       final ClockConfig cfg, final int color, final double radius,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers) {
        if (cfg.tickStyle == ClockConfig.TickStyle.NONE) {
            return;
        }
        final double ring = radius * 0.92D;
        for (int i = 0; i < 12; i++) {
            final double theta = TWO_PI * i / 12.0D;
            final double dx = Math.sin(theta);
            final double dy = Math.cos(theta);
            final double px = Math.cos(theta);
            final double py = -Math.sin(theta);
            // 12 点方向画大一点（i == 0），和真钟一样
            final double weight = i == 0 ? 1.35D : 1.0D;

            switch (cfg.tickStyle) {
                case BAR -> {
                    final double inner = ring - radius * 0.085D * weight;
                    final double outer = ring + radius * 0.045D * weight;
                    final double half = Math.max(0.004D, radius * 0.014D * weight);
                    frame.vertex(vc, matrix, dx * inner + px * half, dy * inner + py * half, color);
                    frame.vertex(vc, matrix, dx * outer + px * half, dy * outer + py * half, color);
                    frame.vertex(vc, matrix, dx * outer - px * half, dy * outer - py * half, color);
                    frame.vertex(vc, matrix, dx * inner - px * half, dy * inner - py * half, color);
                }
                case DOT -> {
                    // 小圆点：用四个点拼成菱形，段数少也不难看
                    final double r = Math.max(0.006D, radius * 0.026D * weight);
                    final double cx = dx * ring;
                    final double cy = dy * ring;
                    frame.vertex(vc, matrix, cx, cy, color);
                    frame.vertex(vc, matrix, cx + px * r, cy + py * r, color);
                    frame.vertex(vc, matrix, cx + dx * r, cy + dy * r, color);
                    frame.vertex(vc, matrix, cx - px * r, cy - py * r, color);
                }
                case SQUARE -> {
                    final double h = Math.max(0.006D, radius * 0.028D * weight);
                    final double cx = dx * ring;
                    final double cy = dy * ring;
                    frame.vertex(vc, matrix, cx + px * h + dx * h, cy + py * h + dy * h, color);
                    frame.vertex(vc, matrix, cx - px * h + dx * h, cy - py * h + dy * h, color);
                    frame.vertex(vc, matrix, cx - px * h - dx * h, cy - py * h - dy * h, color);
                    frame.vertex(vc, matrix, cx + px * h - dx * h, cy + py * h - dy * h, color);
                }
                case TRIANGLE -> {
                    final double apex = ring + radius * 0.05D * weight;
                    final double base = ring - radius * 0.045D * weight;
                    final double half = Math.max(0.006D, radius * 0.026D * weight);
                    // 三角形塞进 QUADS：第四个点与第一个重合，驱动侧拆出来的第二个三角形退化，
                    // 画出来就是一个实心三角。
                    frame.vertex(vc, matrix, dx * apex, dy * apex, color);
                    frame.vertex(vc, matrix, dx * base + px * half, dy * base + py * half, color);
                    frame.vertex(vc, matrix, dx * base - px * half, dy * base - py * half, color);
                    frame.vertex(vc, matrix, dx * apex, dy * apex, color);
                }
                case ARABIC, ROMAN -> {
                    final String label = cfg.tickStyle == ClockConfig.TickStyle.ARABIC
                            ? Integer.toString(i == 0 ? 12 : i)
                            : ROMAN[i == 0 ? 11 : i - 1];
                    this.numeral(matrices, vertexConsumers, frame, dx * ring, dy * ring, radius,
                            label, color);
                }
                default -> {
                }
            }
        }
    }

    /** 罗马数字 1–12（下标 0 对应 1）。 */
    private static final String[] ROMAN = {
            "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII",
    };

    /**
     * 一个数字：把它「贴」在钟面平面上、正面朝外、水平居中。
     *
     * <p>文字渲染器在 XY 平面里画（x 向右、y 向下、z 朝观察者），所以这里直接拼一个 4×4：
     * 三列分别是 {@code right}（文字 +x）、{@code -faceUp}（文字 +y 向下）、{@code n}（文字 +z
     * 朝外），再把原点搬到数字的位置、整体缩到 {@code 0.115R} 高。
     */
    private void numeral(final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                         final Frame frame, final double a, final double b, final double radius,
                         final String label, final int color) {
        final float scale = (float) (radius * 0.0145D);
        if (scale <= 0.0F) {
            return;
        }
        final Vec3d at = frame.point(a, b);
        final Matrix4f rot = new Matrix4f(
                (float) frame.right.x, (float) frame.right.y, (float) frame.right.z, 0.0F,
                (float) -frame.up.x, (float) -frame.up.y, (float) -frame.up.z, 0.0F,
                (float) frame.normal.x, (float) frame.normal.y, (float) frame.normal.z, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        final Matrix4f local = new Matrix4f()
                .translation((float) at.x, (float) at.y, (float) at.z)
                .mul(rot)
                .scale(scale);
        final Matrix4f finalMatrix = new Matrix4f(matrices.peek().getPositionMatrix()).mul(local);

        final int width = this.textRenderer.getWidth(label);
        this.textRenderer.draw(label, -width / 2.0F, -4.0F, color | 0xFF000000, false, finalMatrix,
                vertexConsumers, TextRenderer.TextLayerType.NORMAL, 0,
                LightmapTextureManager.MAX_LIGHT_COORDINATE);
    }

    /**
     * 分段数按半径自适应：{@code R ≤ 4 → 48 段}、{@code R ≥ 16 → 192 段}，中间线性插值。
     * 半径是按「格」算的，默认的小挂钟（R ≈ 0.36）在 48 段下已经看不出棱角。
     */
    public static int segmentsFor(final double radius) {
        final double t = MathHelper.clamp((radius - 4.0D) / 12.0D, 0.0D, 1.0D);
        return (int) Math.round(48.0D + 144.0D * t);
    }

    // ------------------------------------------------------------------ 亮度

    /**
     * 不发光时乘到颜色上的亮度系数（0.35–1.0）。
     *
     * <p>{@code max(方块光, 天空光 × 白天系数)}：白天户外 sky=15 → 1.0，夜里户外只有 0.3 倍
     * 天空光（≈0.545），插火把就亮回来。{@code glow} 命中当前昼夜时直接 1.0。
     */
    public static float brightness(final World world, final BlockPos pos, final ClockConfig cfg) {
        if (world == null) {
            return 1.0F;
        }
        final boolean day = world.isDay();
        if (cfg.glow == ClockConfig.GlowMode.ALWAYS
                || (cfg.glow == ClockConfig.GlowMode.DAY && day)
                || (cfg.glow == ClockConfig.GlowMode.NIGHT && !day)) {
            return 1.0F;
        }
        final int sky = world.getLightLevel(LightType.SKY, pos);
        final int block = world.getLightLevel(LightType.BLOCK, pos);
        final double level = Math.max(block, sky * (day ? 1.0D : 0.3D)) / 15.0D;
        final double clamped = MathHelper.clamp(level, 0.0D, 1.0D);
        return (float) (0.35D + 0.65D * clamped);
    }

    /** 按亮度系数压暗一个 0xRRGGBB（不透明）。 */
    public static int shade(final int rgb, final float factor) {
        final int r = MathHelper.clamp((int) (((rgb >> 16) & 0xFF) * factor), 0, 255);
        final int g = MathHelper.clamp((int) (((rgb >> 8) & 0xFF) * factor), 0, 255);
        final int b = MathHelper.clamp((int) ((rgb & 0xFF) * factor), 0, 255);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ 帧

    /**
     * 一组钟面基向量 + 当前绘制深度。所有顶点都只写「面内坐标 (a, b)」，
     * 由 {@link #point} 换到方块局部空间（钟心为原点 (0.5,0.5,0.5)）。
     */
    private static final class Frame {

        /** 钟面法线（= {@code FACING} 的向量，指向钟面朝外的那一侧）。 */
        private final Vec3d normal;
        /** 从正面看过去的「右」。 */
        private final Vec3d right;
        /** 钟面内的「上」。 */
        private final Vec3d up;
        /** 当前这一层的深度（沿法线离钟心多远）。 */
        private double depth;

        Frame(final Direction facing) {
            this.normal = Vec3d.of(facing.getVector());
            final Vec3d worldUp = facing.getAxis().isHorizontal()
                    ? new Vec3d(0.0D, 1.0D, 0.0D)
                    : new Vec3d(0.0D, 0.0D, 1.0D);
            this.right = worldUp.crossProduct(this.normal).normalize();
            this.up = this.normal.crossProduct(this.right).normalize();
        }

        /** 面内坐标 (a, b) + 当前深度 → 方块局部空间里的点。 */
        Vec3d point(final double a, final double b) {
            return new Vec3d(
                    0.5D + this.right.x * a + this.up.x * b + this.normal.x * this.depth,
                    0.5D + this.right.y * a + this.up.y * b + this.normal.y * this.depth,
                    0.5D + this.right.z * a + this.up.z * b + this.normal.z * this.depth);
        }

        void vertex(final VertexConsumer vc, final Matrix4f matrix, final double a, final double b,
                    final int argb) {
            final float x = (float) (0.5D + this.right.x * a + this.up.x * b + this.normal.x * this.depth);
            final float y = (float) (0.5D + this.right.y * a + this.up.y * b + this.normal.y * this.depth);
            final float z = (float) (0.5D + this.right.z * a + this.up.z * b + this.normal.z * this.depth);
            vc.vertex(matrix, x, y, z)
                    .color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
                    .next();
        }
    }
}
