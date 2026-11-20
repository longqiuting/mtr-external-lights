// 时钟的渲染器：整个钟面都是代码画的（白图 × 顶点色，吃世界光照）。
package dev.js.mtrheadlight.clock;

import net.minecraft.block.BlockState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 时钟方块实体的渲染器 —— 圆盘钟面、三根指针、中心轴、12 个刻度 / 数字，全部用
 * {@link #LIT_LAYER}（{@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}）在 {@link MatrixStack}
 * 的局部坐标里画出来，颜色写在顶点色上。
 *
 * <h2>为什么用「白图 × 顶点色」而不是 debug quads</h2>
 * <p>以前这一层是 {@code RenderLayer.getDebugQuads()}（{@code POSITION_COLOR}）：<b>没有贴图采样、
 * 也没有 lightmap 元素</b>，写进去的 RGB 就是屏幕上看到的样子 —— 也就是说钟面<b>恒满亮</b>，
 * 夜里和隔壁普通方块并排放就像自己会发光。用户实机对比 MTR 原版吊板后要求钟面按普通受光方块做。
 *
 * <p>现在：{@link #LIT_LAYER} = {@code RenderLayer.getEntityCutoutNoCull(白图)}，顶点格式
 * {@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}（<b>带 lightmap / overlay / normal</b>），
 * 颜色 = 顶点色（各部件自己的颜色）× 16×16 全白贴图 × 世界光照。
 * 用 NoCull 变体是因为钟面几何的绕序是「朝 -FACING」，而它画在 {@code -FACING} 那一侧、玩家从
 * 正面看 —— 开剔除会把整个钟面删掉；同理法线仍写 +FACING，方向性明暗才和方块正面一致。
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
 * θ 从 12 点方向<b>顺时针</b>量。depth 常量把「盘面 / 刻度 / 指针 / 中心轴」分层，
 * 避免同一平面上打架。
 *
 * <h2>盘面贴底，法线仍朝 +FACING</h2>
 * <p>{@code FACING} 的语义不变：它仍然是<b>钟面朝向</b>的那一面（放置时玩家点的那一面，朝玩家 / 朝外）。
 * 但几何<b>不贴在那一面上</b>：整个钟面都画在 {@code -FACING} 那一侧，也就是<b>远离玩家、紧贴着
 * 钟背后那格方块</b>的表层（局部坐标 0.016 起，见 {@link #BACK_PLANE}）—— 之前贴在 0.970，
 * 看起来像从方块里朝玩家浮出来一块，而不是「贴墙的钟」。
 *
 * <p>只有沿法线的偏移翻了，面内的基（{@code right} / {@code faceUp}）和法线本身都没动，所以：
 * 12 点照旧朝上、三根针照旧顺时针、数字照旧正着念 —— 从玩家那一侧看，画面和以前一模一样，
 * 只是离得远了 0.95 格。分层顺序要跟着翻：在 {@code -FACING} 侧，「离玩家更近」= depth 更小，
 * 所以盘面最深、轴点最靠玩家（{@link #DISC_DEPTH} … {@link #HUB_DEPTH}），否则盘面会挡在针前面。
 *
 * <h2>远处的钟不许消失</h2>
 * <p>{@link #getRenderDistance()} 返回 {@value #RENDER_DISTANCE}（1.20.1 的
 * {@code BlockEntityRenderer} 本来就有这个方法，NTE 的装饰物件就是这么干的），
 * {@link #rendersOutsideBoundingBox} 返回 {@code true}（钟面最大可以画到 54 格半径，
 * 远超方块的包围盒）。少了这两个，站台大钟走远一点就整块消失。
 *
 * <h2>亮度（含昼夜）</h2>
 * <p>默认**完全交给 lightmap**：顶点写的是方块自己那一格的 {@code WorldRenderer.getLightmapCoordinates(world, pos)}，
 * 也就是原版给普通方块用的那一对坐标。夜里 lightmap 纹理本身会把天空光那一半按环境暗度压暗，
 * 所以钟面跟着变暗、被灯照着又亮回来，和隔壁方块一致。
 *
 * <p>{@link ClockConfig.GlowMode} 是<b>用户显式勾选</b>的「钟面自己亮」（默认 {@code OFF}）：
 * 命中当前昼夜时 `lightFor` 才写满值，那是可切换的开关，不是默认行为。
 *
 * <p><b>不能再自己乘一遍亮度系数</b>（老实现是 {@code 0.35 + 0.65 × max(...)}）：lightmap 已经在
 * 着色器里乘过一次，再乘就是压暗两次，钟面会比隔壁方块明显更黑。
 */
public final class ClockRenderer implements BlockEntityRenderer<ClockBlockEntity> {

    /** 远处不消失：384 格（比默认的 64 格远得多，站台两头都看得到）。 */
    public static final int RENDER_DISTANCE = 384;

    /**
     * 纯色几何的载体贴图：{@code mtrlte:textures/block/white.png}，16×16、每像素不透明纯白。
     * entity 层按资源路径直绑（{@code Sampler0} 读原图），<b>不经过方块图集</b>，
     * 所以不需要任何模型 json 引用它。
     */
    private static final Identifier SOLID_TEXTURE = new Identifier("mtrlte", "textures/block/white.png");

    /**
     * 受光层：{@code entity_cutout_no_cull} 程序，顶点格式
     * {@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}（<b>有 lightmap 元素</b>），写深度。
     *
     * <p>为什么是 NoCull：钟面几何的绕序算出来朝向 {@code -FACING}，而钟面画在 {@code -FACING}
     * 那一侧、玩家从 {@code +FACING} 正面看 —— 一开剔除整个钟面就没了。
     */
    private static final RenderLayer LIT_LAYER = RenderLayer.getEntityCutoutNoCull(SOLID_TEXTURE);

    /**
     * 最里面那一层的深度：盘面就贴在这里 —— 沿 {@code -FACING} 走 0.484 格，即局部坐标 0.016，
     * 紧贴钟背后那格方块的表层（负号就是「朝 -FACING」，见类注释「盘面贴底」）。
     */
    private static final double BACK_PLANE = -0.484D;
    /** 钟面盘心的深度（最深的一层）。 */
    private static final double DISC_DEPTH = BACK_PLANE;
    /** 刻度的深度：压在盘面上，比盘面更靠玩家一点点。 */
    private static final double TICK_DEPTH = BACK_PLANE + 0.002D;
    /**
     * 时针 / 分针 / 秒针 / 中心轴的深度。
     *
     * <p>注意方向：在 {@code -FACING} 侧，「离玩家更近」= depth <b>更大</b>（更靠近 0），
     * 所以三针必须<b>比盘面深</b>（{@code BACK_PLANE + 正值}），否则会被盘面挡住。
     * 越靠前越不会被压住：时针 → 分针 → 秒针 → 轴点。
     */
    private static final double HOUR_DEPTH = BACK_PLANE + 0.008D;
    private static final double MINUTE_DEPTH = BACK_PLANE + 0.011D;
    private static final double SECOND_DEPTH = BACK_PLANE + 0.014D;
    private static final double HUB_DEPTH = BACK_PLANE + 0.020D;

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
        // 全局配置开着时拿到的是全局那份（客户端镜像，见 ClockGlobalCache），否则是这块钟自己的。
        final ClockConfig cfg = entity.effectiveConfig();
        final Direction facing = state.get(ClockBlock.FACING);

        final double seconds = entity.displaySeconds(tickDelta);
        // 钟面半径 / 刻度半径是配置里的独立字段（不再由最长针长度推算）。
        final double faceRadius = cfg.faceRadius;
        final double tickRadius = cfg.tickRadius;
        final int segments = segmentsFor(Math.max(faceRadius, tickRadius));

        final Frame frame = new Frame(facing);
        // 光照：默认吃**这个方块位置**的真实光照（和隔壁普通方块同一套 lightmap；
        // 钟块挂在天花板 / 墙上也照样取它自己那一格）。配置里的发光模式命中当前昼夜时
        // 才写满值 —— 那是用户显式勾选的「钟面自己亮」。
        final int lightCoord = lightFor(world, entity.getPos(), cfg, light);
        frame.light = lightCoord;
        frame.normals = matrices.peek().getNormalMatrix();
        // 颜色不再自己乘亮度系数：明暗全部交给 lightmap，否则夜里会被压暗两次。
        final int face = opaque(cfg.faceColor);
        final int tick = opaque(cfg.tickColor);
        final int hourHand = opaque(cfg.hourColor);
        final int minuteHand = opaque(cfg.minuteColor);
        final int secondHand = opaque(cfg.secondColor);
        final int hub = opaque(cfg.hubColor);

        final VertexConsumer vc = vertexConsumers.getBuffer(LIT_LAYER);
        final Matrix4f matrix = matrices.peek().getPositionMatrix();

        // 顶点色就是最终颜色：位置光 / 实体受击闪红这些状态会通过 shader 的 ColorModulator
        // 影响输出，先归一到白色。（不再改 cull：这一层自己是 NoCull，强行改全局状态会波及同帧其它层。）
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            // ① 钟面（+ 刻度 / 数字）。刻度没有独立的显隐开关：它跟着钟面一起开关，
            //    只想要「没有刻度的干净盘面」就把刻度样式设成「无」。
            if (cfg.showFace) {
                frame.depth = DISC_DEPTH;
                this.disc(vc, matrix, frame, faceRadius, face, segments);
            }

            // ② 刻度 / 数字（在钟面之上、指针之下）—— 有自己的开关，可以只留圆底板
            if (cfg.showTicks) {
                frame.depth = TICK_DEPTH;
                this.ticks(vc, matrix, frame, cfg, tick, faceRadius, tickRadius, matrices, vertexConsumers);
            }

            // ③ 三根指针（各自可以单独关掉；长度与钟面半径无关，针可以伸到盘外）
            if (cfg.showHour) {
                frame.depth = HOUR_DEPTH;
                this.hand(vc, matrix, frame, ClockTime.hourFraction(seconds),
                        cfg.hourLength, Math.max(0.012D, faceRadius * 0.075D), hourHand);
            }
            if (cfg.showMinute) {
                frame.depth = MINUTE_DEPTH;
                this.hand(vc, matrix, frame, ClockTime.minuteFraction(seconds),
                        cfg.minuteLength, Math.max(0.010D, faceRadius * 0.055D), minuteHand);
            }
            if (cfg.showSecond) {
                frame.depth = SECOND_DEPTH;
                this.hand(vc, matrix, frame, ClockTime.secondFraction(seconds),
                        cfg.secondLength, Math.max(0.006D, faceRadius * 0.028D), secondHand);
            }

            // ④ 中心轴盖住三根针的根部
            if (cfg.showHub) {
                frame.depth = HUB_DEPTH;
                this.disc(vc, matrix, frame, Math.min(faceRadius, faceRadius * 0.04D + 0.03D), hub,
                        Math.max(16, segments / 3));
            }
        } finally {
            // 只回滚自己改过的那一项（颜色调节器 ColorModulator = 中性白）；
            // cull / 深度一概不碰：同帧其它层（例如不写 cull 阶段的 beacon 层）靠继承的状态工作。
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
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

    /**
     * 12 个刻度：每 30° 一个，**摆在内圈 {@code tickRadius} 上**（另一个独立字段，跟钟面半径
     * 无关）；刻度本身的粗细 / 长度按钟面半径 {@code faceRadius} 缩放（盘大刻度就该大一点）。
     */
    private void ticks(final VertexConsumer vc, final Matrix4f matrix, final Frame frame,
                       final ClockConfig cfg, final int color, final double faceRadius,
                       final double tickRadius, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers) {
        if (cfg.tickStyle == ClockConfig.TickStyle.NONE) {
            return;
        }
        final double radius = Math.max(0.05D, faceRadius);
        final double ring = tickRadius;
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
                frame.light);
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
     * 这一帧写进顶点的 lightmap 坐标。
     *
     * <p><b>默认</b>取方块自己那一格的真实光照 {@link World#getLightmapCoordinates(BlockPos)}：
     * 它就是原版给普通方块算的那一对坐标（天空光 + 方块光打包成 16×16 的采样点），
     * lightmap 纹理本身已经按当帧的环境暗度压过天空光那一半，所以夜里会自然变暗、
     * 被灯照着又会亮回来 —— 和隔壁方块完全一致。
     *
     * <p>只有配置里的 {@link ClockConfig.GlowMode} 命中当前昼夜时才写满值
     * （{@link LightmapTextureManager#MAX_LIGHT_COORDINATE}）：那是用户显式勾选的
     * 「钟面自己亮」（默认 {@code OFF}），不是渲染器的默认行为。
     *
     * <p>{@code world == null}（理论不该发生）时退回 BER 传进来的 {@code fallbackLight}。
     */
    public static int lightFor(final World world, final BlockPos pos, final ClockConfig cfg,
                               final int fallbackLight) {
        if (world == null) {
            return fallbackLight;
        }
        final boolean daylight = isDaylight(world);
        if (cfg.glow == ClockConfig.GlowMode.ALWAYS
                || (cfg.glow == ClockConfig.GlowMode.DAY && daylight)
                || (cfg.glow == ClockConfig.GlowMode.NIGHT && !daylight)) {
            return LightmapTextureManager.MAX_LIGHT_COORDINATE;
        }
        return WorldRenderer.getLightmapCoordinates(world, pos);
    }

    // 注：夜里变暗这一层现在由 lightmap 纹理本身完成（见 lightFor），不再手写亮度系数。

    /**
     * 现在算不算「白天」—— {@link ClockConfig.GlowMode#DAY} / {@code NIGHT} 的分界。
     *
     * <p>阈值和 {@code World#isDay()} 一致（{@code ambientDarkness < 4}）；固定时间维度按白天算
     * （那里本来就没有「夜里」这回事）。
     */
    public static boolean isDaylight(final World world) {
        if (world == null) {
            return true;
        }
        if (world.getDimension().hasFixedTime()) {
            return true;
        }
        return world.getAmbientDarkness() < 4;
    }

    /** 0xRRGGBB → 不透明 ARGB（顶点色要写 alpha，配置里的颜色只有 RGB）。 */
    public static int opaque(final int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
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
        /** 这一帧写进每个顶点的 lightmap 坐标（见 {@link ClockRenderer#lightFor}）。 */
        private int light;
        /** 法线矩阵（写 {@code .normal()} 用）。 */
        private Matrix3f normals;

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
                    // 白图（16×16 全白）：UV 随便取一点都等价，颜色全在顶点色上。
                    .texture(0.0F, 0.0F)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(this.light)
                    // 法线写 +FACING：方向性明暗（minecraft_mix_light）才和方块正面一致。
                    .normal(this.normals,
                            (float) this.normal.x, (float) this.normal.y, (float) this.normal.z)
                    .next();
        }
    }
}
