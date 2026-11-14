// 挂顶发车倒数器的屏幕渲染器：把同一行七段数码管画到那块斜着吊的牌子上。
package dev.js.mtrheadlight.xiamen.dep;

import net.minecraft.util.math.Matrix3f;
import net.minecraft.util.math.Matrix4f;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelJson;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import dev.js.mtrheadlight.clientgui.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 挂顶发车倒数器 {@code mtrlte:dep_on_roof} 的屏幕渲染器。
 *
 * <h2>它画什么</h2>
 * <p>和挂墙版<b>一模一样的读数</b>：一块 96×64 的七段数字，四个状态循环
 * （空屏 → 绿色倒数 → 红色正计 5 秒 → 空屏）。数值链路在 {@link DepCountdown}，
 * 字形与排版在 {@link DepOnWallDigits} / {@link DepOnWallLayout}，两边共用；
 * 本类只负责<b>把那张屏摆到模型的斜板上</b>。
 *
 * <h2>屏幕在哪（从 {@code models/block/dep_on_roof.json} 量出来）</h2>
 * <p>模型里那块零厚度的发光板，<b>偏转前</b>是：
 * <pre>
 *   from [2, 1, 9]  to [14, 9, 9]      ← 12 × 8 格 = 96 × 64 px（1 px = 1/128 格，和挂墙版同比例）
 *   元素 rotation y = -45，origin (8, 5, 7.15)
 * </pre>
 * <p>也就是说牌面朝 {@code +Z}（模型自己的正面），但是整块牌子被转了 -45°，于是：
 * <ul>
 *   <li>屏幕正中从 {@code (8, 5, 9)} 转到 <b>{@code (9.5487, 5, 8.6987)}</b>（1/16 格）；</li>
 *   <li>屏幕法线从 {@code (0,0,1)} 转到 <b>{@code (0.7071, 0, 0.7071)}</b> —— 东南。</li>
 * </ul>
 * <p>这两个数不是手抄的：{@link #SCREEN_CENTER_X} / {@link #SCREEN_CENTER_Z} /
 * {@link #SCREEN_NORMAL} 全部由 {@link DepOnRoofBlock#MODEL_YAW_DEGREES} 在类初始化时算出来
 * （{@link #PLATE_Z} 那张板本身在 {@code z=9}，屏幕再往观察者方向挪 0.04 格到 {@code z=9.34}，
 * 好让牌框（正面 {@code z=9.3}）挡不住数字，和挂墙版同一个处理）。
 *
 * <h2>变换链（和挂墙版逐行对应）</h2>
 * <pre>
 *   translate(方块中心)                                     // 0.5, 0.5, 0.5
 *   rotateY(-blockstate 的 y 角)                            // 和模型同一套旋转
 *   translate(屏幕正中 - 方块中心)                            // 模型空间里的那一点
 *   multiply(right | down | into 三个基向量)                 // 正交、行列式 +1 → 不含镜像
 *   translate(0, 0, TEXT_LAYER_Z)                          // 数字层往观察者方向 1.5 px
 *   scale(1 / 128)                                         // 96×64 px → 0.75 × 0.5 格
 * </pre>
 *
 * <h2>读数方向：按基向量算，不猜符号</h2>
 * <p>{@code out = 屏幕法线（东南）、right = up × out、down = -up、into = -out}。
 * {@code right × down · into = +1}，所以这是一个<b>纯旋转</b>：数字只可能整体转过去，
 * 不可能「每个字都反着写」。牌面朝东南时 {@code right = (0.7071, 0, -0.7071)}（东北），
 * 正是站在东南面朝西北的玩家的右手边 —— 和挂墙版用的是同一个推导，只是法线不同。
 *
 * <p><b>这里不接 {@code /mtr_lte dep} 的方向开关</b>（{@link DepOnWallOrient}）：那个开关是绕着
 * 「原点在屏幕正中、+X = 玩家右手」这套基做的，而它默认的 {@code mirror} 是给挂墙版那套
 * 「法线取反 + 再镜像」的写法配平的（见 {@link DepOnWallRenderer#screenBasis()}）。
 * 这一版直接按真实法线建基，再套一个镜像反而会真的镜像。实机若发现读数是反的，
 * 改 {@link #screenBasis()} 里 {@code right} 的符号（一行）即可，不用重配。
 *
 * <h2>渲染状态</h2>
 * <p>{@code RenderLayer} 自己 start/end，矩阵 push/pop 全在 {@code try/finally} 里；
 * 结尾没有任何 {@code enableCull} / {@code depthMask} 之类的「复位」（那会把下一个 BER
 * 正在依赖的状态按默认值覆盖掉，厦门 PIDS 踩过这个坑）。
 */
public class DepOnRoofRenderer extends BlockEntityRendererMapper<DepOnRoofBlockEntity> {

    /**
     * 屏幕正中（模型空间 1/16 格，<b>偏转前</b>的牌面坐标）。
     * <p>x / y 就是那块发光板的中点（{@code (2+14)/2}、{@code (1+9)/2}）；
     * z 是牌框正面（{@code 9.3}）之前 0.04 格 —— 板本身在 {@code z=9}，缩在框里 0.3 格，
     * 不往前提就会被牌框削掉、正面看还会 z-fighting（挂墙版踩过同一个坑）。
     */
    private static final double PLATE_X = 8.0D;
    private static final double PLATE_Y = 5.0D;
    private static final double PLATE_Z = 9.34D;

    /** 屏幕正中（模型空间 1/16 格 → 格），已按模型自带的 -45° 偏转到牌子真正的正面。 */
    static final float SCREEN_CENTER_X;
    static final float SCREEN_CENTER_Y;
    static final float SCREEN_CENTER_Z;

    /**
     * 屏幕法线（模型空间）：模型自己的正面 {@code +Z} 转过同一个 -45° = 东南。
     * <p>不随 {@code facing} 变化 —— blockstate 的旋转已经在前面的矩阵链里把它带过去了，
     * 这里再乘一次就会转两遍（挂墙版把这条写进了注释，这里照办）。
     */
    static final Vec3d SCREEN_NORMAL;

    static {
        final double rad = Math.toRadians(DepOnRoofBlock.MODEL_YAW_DEGREES);
        final double cos = Math.cos(rad);
        final double sin = Math.sin(rad);
        final double dx = PLATE_X - DepOnRoofBlock.MODEL_PIVOT_X;
        final double dz = PLATE_Z - DepOnRoofBlock.MODEL_PIVOT_Z;
        SCREEN_CENTER_X = (float) ((DepOnRoofBlock.MODEL_PIVOT_X + dx * cos - dz * sin) / 16.0D);
        SCREEN_CENTER_Y = (float) (PLATE_Y / 16.0D);
        SCREEN_CENTER_Z = (float) ((DepOnRoofBlock.MODEL_PIVOT_Z + dx * sin + dz * cos) / 16.0D);
        // (0, 0, 1) 转过同一个角：x' = -sin，z' = cos。
        SCREEN_NORMAL = new Vec3d(-sin, 0.0D, cos);
    }

    /** 渲染线程上只报一次错（否则每帧一条，日志会被刷爆）。 */
    private static boolean warned;

    /**
     * 牌身 + 挂杆（{@code assets/mtrlte/rawmodels/dep_on_roof.json}）。
     * <p>就是 {@code 素材/挂墙发车倒数器/dep_on_roof.json} 那份 16 个元素的 Blockbench 导出，
     * 由 {@code tools/gen-dep-rawmodels.js} 原样拷进 jar（只改贴图名）。<b>挂杆那十节</b>
     * 的 {@code 154 / -87.5 / -73.5 …} 是「拧绳子」的手工角度，原版方块模型一个都存不下，
     * 所以整份模型改走 {@link RawModelJson}。
     */
    private static final Identifier MODEL = RawModelJson.id("dep_on_roof");

    /**
     * 素材朝向 → 本模组代码朝向的补偿角（度，模型空间，绕方块竖轴，正 = 俯视逆时针）。
     *
     * <p>两套约定的符号是反的：Blockbench 导出的元素 rotation、以及原版
     * {@code BakedQuadFactory#rotateVertex} 用的 {@code Quaternionf.rotationAxis(angle, axis)}
     * 都是<b>数学正角</b>；而 {@code DepOnRoofBlock#yawedAabb} 与
     * {@link #applyScreenTransform} 用的是 blockstate 那套<b>俯视顺时针</b>
     * （{@code x' = x·cos − z·sin}）。素材的牌框 135° 在代码这套里本该是 45°，
     * 差这 90° 正好就是「碰撞箱 / 数字都对、模型偏 90°」。
     * <p>数值由 {@code node tools/verify-dep-orient.js} 核对：转完之后牌面法线 = 数字层的
     * {@link #SCREEN_NORMAL}，牌框包围盒 = {@code DepOnRoofBlock} 的中段碰撞箱。
     */
    static final float MODEL_YAW_FIX_DEGREES = 90.0F;

    public DepOnRoofRenderer(final BlockEntityRendererFactory.Context context) {
        super(context.getRenderDispatcher());
    }

    @Override
    public void render(final DepOnRoofBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        try {
            renderSafe(entity, matrices, vertexConsumers, light, overlay);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.error("[lte] dep on roof render failed", t);
            }
        }
    }

    private void renderSafe(final DepOnRoofBlockEntity entity, final MatrixStack matrices,
                            final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        final World world = entity.getWorld();
        if (world == null) {
            return;
        }
        final BlockPos pos = entity.getPos();
        final BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DepOnRoofBlock)) {
            return;
        }
        final Direction facing = state.getOrEmpty(HorizontalFacingBlock.FACING).orElse(Direction.NORTH);

        // 牌身 + 挂杆：空屏时同样要画（这两件从来不看读数）。
        drawModel(matrices, vertexConsumers, facing, light, overlay);

        // 数据链路在 DepCountdown（挂墙 / 挂顶共用），这里只把结果显示到屏幕上。
        final DepCountdown.Display display = DepCountdown.display(entity, pos);
        if (display == null) {
            return;
        }

        matrices.push();
        try {
            applyScreenTransform(matrices, facing);

            // 数字层再单独 push 一层：方向开关 / 图层错位只作用于数字，底图一个像素都不动。
            matrices.push();
            try {
                matrices.translate(0.0F, 0.0F, DepOnWallLayout.TEXT_LAYER_Z);
                DepOnWallRenderer.drawDigits(matrices, vertexConsumers, display);
            } finally {
                matrices.pop();
            }
        } finally {
            matrices.pop();
        }
    }

    // ------------------------------------------------------------------ 牌身（rawmodel）

    /**
     * 画牌身本体（含挂杆）：{@code rawmodels/dep_on_roof.json} 整份。
     *
     * <h2>两步旋转</h2>
     * <ol>
     *   <li><b>+90°（模型空间，绕方块竖轴）</b>：把素材里那种「数学正角」的朝向
     *       （Blockbench / 原版元素 rotation 都是 {@code Quaternionf.rotationAxis(angle)}
     *       的正角，从上往下看是<b>逆时针</b>）拧到本模组这套代码一直在用的
     *       「俯视顺时针」上（{@code DepOnRoofBlock#yawedAabb} /
     *       {@link #applyScreenTransform} 里写的都是 {@code x' = x·cos − z·sin}）。
     *       两套约定差正好 90°：素材的牌框是 135°，折回代码这套就是 45°；
     *       牌面从「西南」转到「东南」，<b>正好落在数字层和牌框碰撞箱假定的那一面</b>。
     *       少了这一步，就是用户看到的「碰撞箱和数字都对，模型偏了 90°」。
     *       工具核对：{@code node tools/verify-dep-orient.js}（牌面 / 牌框 / 挂杆逐项对）。</li>
     *   <li><b>blockstate 的 {@code y}</b>：和挂墙版同一个表达式，四个朝向都走
     *       {@link DepOnRoofBlock#blockRotation(Direction)}。</li>
     * </ol>
     *
     * <p>挂杆那十节的三轴旋转由 {@link RawModelJson} 逐顶点算（XYZ 欧拉序、绕各自 origin），
     * 所以是原作者拧的那根绳子，而不是被吸附到 ±22.5 之后的「另一根杆」。
     */
    private static void drawModel(final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                                  final Direction facing, final int light, final int overlay) {
        final RawMesh mesh = RawModelJson.get(MODEL);
        if (mesh == null) {
            return;
        }
        final VertexConsumer consumer = vertexConsumers.getBuffer(RawModelRenderer.cutoutLayer(mesh));
        // 矩阵链（先写的先作用在顶点上）：
        //   绕方块中心转 blockstate 的 y  →  绕模型 pivot 转 +90°（补偿角）  →  画。
        // 顺序不能反：补偿角必须绕模型自己的 pivot（Blockbench 里那 16 个元素的 origin），
        // 绕方块中心会把这块斜吊的牌子整体挪走 0.85/16 格。
        final double pivotX = DepOnRoofBlock.MODEL_PIVOT_X / 16.0D;
        final double pivotZ = DepOnRoofBlock.MODEL_PIVOT_Z / 16.0D;
        matrices.push();
        try {
            matrices.translate(0.5D, 0.0D, 0.5D);
            matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(DepOnRoofBlock.blockRotation(facing)));
            matrices.translate(-0.5D, 0.0D, -0.5D);
            matrices.translate(pivotX, 0.0D, pivotZ);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MODEL_YAW_FIX_DEGREES));
            matrices.translate(-pivotX, 0.0D, -pivotZ);
            // RawModelRenderer 自己把 0..16 的模型坐标除以 16。
            RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
        } finally {
            matrices.pop();
        }
    }

    // ------------------------------------------------------------------ 变换

    /**
     * 屏幕的变换（见类注释）。
     *
     * @param facing 方块正面朝向（= blockstate 的 {@code facing}；牌面法线是它顺时针 135°，
     *               见 {@link DepOnRoofBlock#facingForNormal}）
     */
    private static void applyScreenTransform(final MatrixStack matrices, final Direction facing) {
        matrices.translate(0.5F, 0.5F, 0.5F);
        // 负角：blockstate 的 y 旋转是「从上往下看顺时针」，JOML 的正角是逆时针。
        matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(DepOnRoofBlock.blockRotation(facing)));
        // 屏幕正中在模型空间里的位置，换算成「相对方块中心」的偏移。
        matrices.translate(SCREEN_CENTER_X - 0.5F, SCREEN_CENTER_Y - 0.5F, SCREEN_CENTER_Z - 0.5F);
        // MatrixStack 在 1.20.1 只提供 multiply(Quaternionf)，所以基向量矩阵直接乘到当前
        // 这一层的 position / normal 矩阵上 —— 和 MatrixStack.multiply 内部做的是同一件事。
        final Matrix4f basis = screenBasis();
        final MatrixStack.Entry entry = matrices.peek();
        entry.getPositionMatrix().multiply(basis);
        entry.getNormalMatrix().multiply(new Matrix3f(basis));
        final float scale = 1.0F / DepOnWallLayout.SCREEN_SCALE_DIVISOR;
        matrices.scale(scale, scale, scale);
    }

    /**
     * 屏幕的三个基向量拼成的旋转矩阵：<b>列 0 = 屏幕 +X（玩家的右手）</b>、
     * 列 1 = 屏幕 +Y（向下）、列 2 = 屏幕 +Z（扎进屏幕里）。
     *
     * <p>{@code right = up × out}，{@code out} = {@link #SCREEN_NORMAL}（东南）。
     * 三个向量两两正交、单位长，且 {@code right × down · into = +1}，所以这是一个
     * <b>行列式 +1 的纯旋转</b>：它只会整体转动，不会把字形镜像成「每个字都反着写」。
     */
    private static Matrix4f screenBasis() {
        final Vec3d up = new Vec3d(0.0D, 1.0D, 0.0D);
        final Vec3d out = SCREEN_NORMAL;
        final Vec3d right = up.crossProduct(out);
        final Vec3d down = up.multiply(-1.0D);
        final Vec3d into = out.multiply(-1.0D);
        // JOML 的 Matrix4f 构造函数是列主序：前四个数 = 第一列 = 局部 +X 的去向。
        return dev.js.mtrheadlight.compat.Matrices.fromColumns(
                (double) right.x, (double) right.y, (double) right.z,
                (double) down.x, (double) down.y, (double) down.z,
                (double) into.x, (double) into.y, (double) into.z);
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 屏幕刻意贴在牌框正面之前 0.04 格（又因为牌子是斜的，比包围盒的某一角探出一点点）；
     * 打开这一项，站在屏幕正前方、方块包围盒刚好出视锥时也不会整块不画。
     */
    @Override
    public boolean rendersOutsideBoundingBox(final DepOnRoofBlockEntity entity) {
        return true;
    }
}
