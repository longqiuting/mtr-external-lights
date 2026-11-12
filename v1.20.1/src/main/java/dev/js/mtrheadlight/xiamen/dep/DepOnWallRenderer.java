// 挂墙发车倒数器的屏幕渲染器：自发光底图 + 一行七段数码管倒计时。
package dev.js.mtrheadlight.xiamen.dep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import dev.js.mtrheadlight.LteLog;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.ScheduleEntry;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 挂墙发车倒数器 {@code mtrlte:dep_on_wall} 的屏幕渲染器。
 *
 * <h2>它是「发车倒数器」，不是 PIDS</h2>
 * <p>贴在站台头端的墙上。屏幕上<b>没有站名、没有线路</b>，<b>永远只有一行七段数字</b>，
 * 四个状态循环：
 * <pre>
 *   ① 下一班还没到站            → 空屏（只剩那块暗红 LED 点阵底图）
 *   ② 到站了，还在停站          → 亮绿数字从「站台 dwell / 2」倒数到 0:00
 *   ③ 到点了车还没走（晚点）     → 同一位置变纯红，从 0:00 正计到 0:05
 *   ④ 红字 5 秒走完 / 车已开走  → 空屏，等下一班（下一班还没进站 → 回到 ①）
 * </pre>
 * 「0:00 之后」那一档是这块牌子的全部意义：列车到点还没走（上下客慢、有人挡门、
 * 前方信号被占）时，屏幕不挂着 0 不动，而是把「已经晚了几秒」正着数出来 ——
 * 站务隔着站台就知道该催了。
 *
 * <h2>数值链路：JCM 的 {@code jsblock.render.RenderDepartureTimer}，公式一字不改</h2>
 * <p><b>不读列车。</b>不碰 {@code ClientData.TRAINS}、不看 {@code elapsedDwellTicks}、
 * 不看 {@code path} —— 「遍历列车找停站进度」那套已经整段删掉，数值只有 JCM 这一条链路
 * （参数、顺序、公式都和字节码逐行对过）：
 * <pre>
 * 1. RailwayData.getClosePlatformId(ClientData.PLATFORMS, DATA_CACHE, pos, 5, 3, 3)
 *       → 离这块牌子最近的站台 id（0 = 附近没有站台 → 空屏）
 * 2. ClientData.SCHEDULES_FOR_PLATFORM.get(platformId)   // null = 没数据
 * 3. new ArrayList&lt;&gt;(entries) + Collections.sort         // 按 arrivalMillis（同刻再按 routeId）
 * 4. entry = 排序后第一条「还没走完」的班次            // 见下面「锁」
 * 5. arrivalMillis 还在未来 → 空屏（车还没到站，不是显示 0:00）
 * 6. 否则（JCM 的原式，一字不改）：
 *       已过秒 = (arrivalMillis - now) / 1000             // ≤ 0；先减再 /1000，向零截断
 *       dwellHalf = platform == null ? 0 : platform.getDwellTime() / 2   // 整数除法
 *       剩余   = |dwellHalf - |已过秒||
 * </pre>
 * <p>{@code dwell / 2} 是 <b>整数除法</b>，{@code Platform} 为 null 时 JCM 直接拿 0 当基准
 * （这里照抄，没有再垫一个「最少 1 秒」的下限）。{@code arrivalMillis} 是<b>到站</b>时刻 ——
 * JCM 的经验值 dwell/2 恰好就是 MTR 的实际停站时长（{@code Train#getTotalDwellTicks()} =
 * {@code dwellTime * 10} tick，而 20 tick = 1 秒），所以 0:00 落在发车点上。
 * <p>式子外面套了一层 {@code abs}，于是「到点之后」的数字会自己往回长（0 → 1 → 2 …），
 * 这个值本身就是「已经晚了几秒」，状态 ③ 直接用它，不需要另开一套计时。
 *
 * <h2>「锁一条」：唯一和 JCM 不一样的地方（也是「2 突然变 5」的修法）</h2>
 * <p>JCM 第 4 步是每帧现取 {@code list.get(0)}。MTR 这张表是服务端<b>周期性整表推送</b>的
 * （{@code updateSchedule} → {@code clearAndAddAll}），每次推送都可能重算 {@code arrivalMillis}、
 * 删掉刚发车的条目、或整个换掉集合 —— 于是倒数途中「第 1 条」会换人：绿字数到 2，
 * 推送把到站时刻往后挪了 3 秒，{@code |已过秒|} 变小，屏幕就<b>弹回 5</b> 接着数，
 * 下一次推送再弹一次。
 *
 * <p>所以「用哪一条」不再每帧重选，而是<b>锁在方块上</b>（{@link DepOnWallBlockEntity#depLock()}，
 * 类型 {@link DepOnWallLock}）：
 * <ul>
 *   <li>锁定键是 {@code arrivalMillis + routeId}（和 {@code ScheduleEntry#compareTo} 的排序键一致）；</li>
 *   <li>{@code dwellHalf} 一起冻在锁里 —— 站台数据被重推也不会换基准；</li>
 *   <li><b>一旦开始倒数就不换人</b>，服务端怎么推都只数这一条，直到它自己走完（绿 dwellHalf 秒 +
 *       红 5 秒）自动放锁；</li>
 *   <li>屏幕还空着（车没到站）时没有可见读数，这时才允许跟着新的第一条改锁 ——
 *       早到的车 / 新插入的班次照旧能在到站前抢到位置，且不产生任何数字跳变；</li>
 *   <li>选班时跳过已经过了显示窗口的条目，所以「刚放锁那条」不会被重新选回来。</li>
 * </ul>
 * <p>再加一道<b>单调钳制</b>（也在 {@link DepOnWallLock} 里）：绿段只减不增、红段只增不减、
 * 开过车不再回到空屏。{@code System.currentTimeMillis()} 在同一 tick 里被多次读取、或系统对时 /
 * 回档时倒退，读数是「停住」而不是往回跳。正常走时公式本来就单调，钳制不改任何数值。
 *
 * <p>只有<b>颜色 / 阶段</b>是我们的扩展：
 * <ul>
 *   <li>{@code |已过秒| < dwellHalf}（递减段）→ 绿 {@code #00E676}，{@code %02d:%02d}；</li>
 *   <li>{@code |已过秒| >= dwellHalf}（递增段，到点还没走）→ 红 {@code #FF0000} 正计；</li>
 *   <li>{@code 剩余 > } {@link DepOnWallLock#OVERTIME_SECONDS} → 黑屏，放锁等下一班
 *       （JCM 会一路数回 dwellHalf，我们只留红色的那 5 秒）。</li>
 * </ul>
 *
 * <h2>屏幕坐标系 / 变换链（改这里之前先读这个类 + {@link DepOnWallLayout}）</h2>
 * <pre>
 *   translate(方块中心)                                  // 0.5, 0.5, 0.5
 *   rotateY(-blockstate 的 y 角)                         // 和模型同一套旋转
 *   translate(0, 0, 屏幕中心 z - 0.5)                     // 屏幕正中，模型空间 z = 11.96/16
 *   multiply(right | down | into 三个基向量)              // 正交、行列式 +1 → 不含镜像
 *   scale(1 / 128)                                      // 96×64 LED 像素 → 0.75×0.5 格
 * </pre>
 * 数字层再单独 push 一层（所以翻转永远只作用于数字，底图一个像素都不动）：
 * <pre>
 *   translate(0, 0, TEXT_LAYER_Z)                        // 往观察者方向 1.5 px
 *   [scale(±1, ±1, 1)]                                  // 运行期方向开关，见 DepOnWallOrient
 * </pre>
 * 局部坐标：<b>+X = 屏幕向右、+Y = 屏幕向下、+Z = 扎进屏幕里</b>，原点 = 屏幕<b>正中</b>
 * （x ∈ [-48, +48]、y ∈ [-32, +32] px）。屏幕占的方块空间和模型元素 2 一模一样
 * （x 2..14、y 4..12、z 11.96/16）。
 *
 * <h2>读数方向：按基向量摆，不猜符号</h2>
 * <p>数码管是<b>几何</b>画出来的，不像 {@code TextRenderer} 那样自带「GUI 文字」的翻转处理：
 * 框架里 +X 往哪边走，字就往哪边读。前三次返工都是在 {@code rotateZ(180)} / {@code scale(-1)}
 * 上加减符号、再靠离线矩阵推演收尾，三次都没落地。这次不再对角度做加减，而是直接把三个
 * 基向量算出来：
 * <pre>
 *   out   = facing（屏幕法线，指向玩家）
 *   right = up × out，
 *   down  = -up，
 *   into  = -out
 * </pre>
 * {@code right × down · into = +1}，也就是说这一组基是<b>纯旋转</b>：不带镜像、不带反射，
 * 画出来的 {@code 00:19} 只能是正的或整体转过去的，不可能出现「每个字都反着写」。
 * 「玩家站在牌子正面时右手在哪边」也就从人脑推变成了叉乘 —— 正面朝北时
 * {@code right = up × north = -X = 西}，正是面朝南的玩家的右手边。
 * <p>万一实机读出来仍然不对，<b>不要改这里的基</b>：用游戏内开关 {@code /mtr_lte dep}
 * 现场翻（{@link DepOnWallOrient}：normal / mirror / flip / rotate 四档，绕屏幕原点缩放，
 * 屏幕面积一个像素都不动，改完下一帧生效并落盘）。
 * <p>顺带记一笔：{@code getEntityTranslucentEmissive} 是 {@code DISABLE_CULLING} 的
 * （1.20.1 的 {@code RenderLayer.method_42598} 里写着，也从字节码核过），
 * 所以四边形的绕向 / 镜像与否都不会让屏幕整块消失 —— 之前几版（镜与不镜）
 * 数字都看得见，问题只在左右。
 *
 * 绕 Y 用 <b>负角</b>（{@code NEGATIVE_Y}）：blockstate 的 {@code y:90} 对应
 * {@code (x,z) → (16-z, x)}，在 JOML 里就是 {@code Ry(-90)}（液体安检机那边验证过同一套换算）。
 *
 * <h2>为什么自己再贴一张底图</h2>
 * <p>模型的 north 面已经用 {@code dep_on_wall.png} 画了一遍那块暗红点阵，但方块模型走的是
 * 普通光照通道 —— 夜里那块屏会跟着环境变黑。这里用
 * {@link RenderLayer#getEntityTranslucentEmissive} 把同一块 UV 再画一次（前移 0.02/16 格
 * 避免 z-fighting），屏幕就永远是「亮着」的，和厦门 PIDS 一个做法。
 *
 * <h2>数字为什么是几何画的</h2>
 * <p>不走 {@code TextRenderer}、也不打字体包：{@link DepOnWallDigits} 按七段直接吐四边形，
 * 任意字号都锐利，颜色就是顶点色（绿 / 红切换不用换贴图）。它和底图共用一个自发光层，
 * 但用<b>另一张贴图</b>（{@link #LED_TEXTURE}，1 色纯白）—— 底图那张是暗红点阵，拿它当
 * 数字的颜色会整片发黑。
 *
 * <h2>渲染状态：一个全局 GL 状态都不碰</h2>
 * <p>{@code RenderLayer} 各自 start/end，矩阵 push/pop 全在 {@code try/finally} 里；
 * 结尾没有任何 {@code enableCull} / {@code depthMask} 之类的「复位」（那会把下一个 BER
 * 正在依赖的状态按默认值覆盖掉，厦门 PIDS 踩过这个坑）。
 */
public class DepOnWallRenderer extends BlockEntityRendererMapper<DepOnWallBlockEntity> {

    /** 屏幕底图（整张 {@code dep_on_wall.png}，只取 uv 3..6 × 0..2 那一块）。 */
    public static final Identifier TEXTURE =
            new Identifier(DepOnWallObjects.NAMESPACE, "textures/block/dep_on_wall.png");

    /**
     * 数码管用的纯白贴图（{@code dep_on_wall_led.png}，4×4 全白）。
     * <p>自发光层是<b>带贴图</b>的（{@code POSITION_COLOR_TEXTURE_...}），没法「不用贴图只上色」；
     * 所有顶点都取它的正中（uv 0.5, 0.5）= 纯白，于是画出来的颜色就是顶点色本身。
     */
    public static final Identifier LED_TEXTURE =
            new Identifier(DepOnWallObjects.NAMESPACE, "textures/block/dep_on_wall_led.png");

    /**
     * 找站台的搜索范围：{@code (搜索半径, 包围盒外扩x/z, 外扩y)} = {@code (5, 3, 3)}，
     * <b>和 JCM 的 {@code RenderDepartureTimer} 完全一致</b>。
     */
    private static final int SEARCH_RADIUS = 5;
    private static final int SEARCH_MARGIN = 3;

    // 00:00 之后红灯正计时多少秒：判据在 DepOnWallLock#OVERTIME_SECONDS 里（remain > 5 → 放锁），
    // 渲染器只负责画，不重复定义一份「5」。

    /**
     * 一帧里算出来的内容：已经格式化好的 {@code mm:ss} + 是不是红灯那一档。
     * <p>颜色只取决于 {@code overdue}，不按剩余秒数分档 —— 绿就是绿，红就是红。
     */
    private record Display(String text, boolean overdue) {
    }

    /** 渲染线程上只报一次错（否则每帧一条，日志会被刷爆）。 */
    private static boolean warned;

    public DepOnWallRenderer(final BlockEntityRendererFactory.Context context) {
        super(context.getRenderDispatcher());
    }

    @Override
    public void render(final DepOnWallBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        try {
            renderSafe(entity, matrices, vertexConsumers);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.error("[lte] dep on wall render failed", t);
            }
        }
    }

    private void renderSafe(final DepOnWallBlockEntity entity, final MatrixStack matrices,
                            final VertexConsumerProvider vertexConsumers) {
        final World world = entity.getWorld();
        if (world == null) {
            return;
        }
        final BlockPos pos = entity.getPos();
        final BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DepOnWallBlock)) {
            return;
        }
        final Direction facing = state.getOrEmpty(HorizontalFacingBlock.FACING).orElse(Direction.NORTH);

        final Display display = display(entity);

        matrices.push();
        try {
            applyScreenTransform(matrices, facing);

            // 只有数字：模型自己那块 LED 板就是底图，不再额外叠一层自发光面
            //（用户：没有复制一层显示屏在前面，不需要这个功能）。
            if (display != null) {
                matrices.push();
                try {
                    matrices.translate(0.0F, 0.0F, DepOnWallLayout.TEXT_LAYER_Z);
                    DepOnWallOrient.mode().apply(matrices);
                    drawDigits(matrices, vertexConsumers, display);
                } finally {
                    matrices.pop();
                }
            }
        } finally {
            matrices.pop();
        }
    }

    // ------------------------------------------------------------------ 变换 / 几何

    /**
     * 屏幕的变换（见类注释）。
     *
     * <p>顺序：方块中心 → 与模型同一套 Y 旋转 → 屏幕正中 → 三个基向量 → 1/128。
     * 方向开关不在这里：它只作用于数字层（见 {@link #renderSafe} 与 {@link DepOnWallOrient}）。
     * 这里的基向量是<b>算</b>出来的（{@link #screenBasis}），不是对 {@code rotateZ} 的角度做加减：
     * 算出来的矩阵是纯旋转，不可能偷偷带一个镜像进去。
     *
     * @param facing 方块正面朝向（= blockstate 的 {@code facing}，也就是屏幕法线方向）
     */
    private static void applyScreenTransform(final MatrixStack matrices, final Direction facing) {
        matrices.translate(0.5F, 0.5F, 0.5F);
        // 负角：blockstate 的 y 旋转是「从上往下看顺时针」，JOML 的正角是逆时针。
        matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(DepOnWallBlock.blockRotation(facing)));
        // 屏幕正中：x / y 已经在方块中心上，只有 z 要挪到「边框正面之前那一层」。
        matrices.translate(0.0F, 0.0F, DepOnWallLayout.SCREEN_CENTER_Z - 0.5F);
        // MatrixStack 在 1.20.1 只提供 multiply(Quaternionf)，所以基向量矩阵直接乘到当前
        // 这一层的 position / normal 矩阵上 —— 和 MatrixStack.multiply 内部做的是同一件事。
        final Matrix4f basis = screenBasis();
        final MatrixStack.Entry entry = matrices.peek();
        entry.getPositionMatrix().mul(basis);
        entry.getNormalMatrix().mul(new Matrix3f(basis));
        // 读数方向开关只作用于数字层（见 renderSafe）；底图与模型元素 2 永远一致。
        final float scale = 1.0F / DepOnWallLayout.SCREEN_SCALE_DIVISOR;
        matrices.scale(scale, scale, scale);
    }

    /**
     * 屏幕在<b>模型空间</b>里的朝外法线：模型里那块发光板（元素 2）的 north 面朝 {@code -Z}，
     * 也就是模型自己的「正面」。四个 blockstate 变体由矩阵链前面那次 {@code rotateY} 把它转到
     * 世界上，所以这个基向量与 {@code facing} 无关 —— 拿世界方向去算反而会把旋转算两遍
     * （朝向 east / west 时屏幕会整体转过 90°、飘出方块）。
     */
    private static final Vec3d MODEL_SCREEN_NORMAL = new Vec3d(0.0D, 0.0D, -1.0D);

    /**
     * 屏幕的三个基向量拼成的旋转矩阵：<b>列 0 = 屏幕 +X（玩家的右手）</b>、
     * 列 1 = 屏幕 +Y（向下）、列 2 = 屏幕 +Z（扎进屏幕里）。
     *
     * <p>{@code right = up × out}，{@code out} 是模型空间里的屏幕法线（{@link #MODEL_SCREEN_NORMAL}）。
     * {@code up × (0,0,-1) = (-1,0,0)}，也就是「玩家站在牌子正面时自己的右手边」——
     * 世界方向由前面那次 {@code rotateY} 带过去，四个朝向自动一起成立。
     *
     * <p>三个向量两两正交、单位长，且 {@code right × down · into = +1}，所以这是一个
     * <b>行列式 +1 的纯旋转</b>：它只会整体转动，不会把字形镜像成「每个字都反着写」。
     * 真机上方向不对时不要改这里 —— 用 {@link DepOnWallOrient} 的运行期开关。
     */
    private static Matrix4f screenBasis() {
        final Vec3d up = new Vec3d(0.0D, 1.0D, 0.0D);
        // 2026-11-12：按用户要求整体绕 Y 轴转 180° —— 等价于 out 取反（right 随之反号、down 不变），
        // 仍然是行列式 +1 的纯旋转，不会产生镜像。
        final Vec3d out = MODEL_SCREEN_NORMAL.multiply(-1.0D);
        final Vec3d right = up.crossProduct(out);
        final Vec3d down = up.multiply(-1.0D);
        final Vec3d into = out.multiply(-1.0D);
        // JOML 的 Matrix4f 构造函数是列主序：前四个数 = 第一列 = 局部 +X 的去向。
        return new Matrix4f(
                (float) right.x, (float) right.y, (float) right.z, 0.0F,
                (float) down.x, (float) down.y, (float) down.z, 0.0F,
                (float) into.x, (float) into.y, (float) into.z, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
    }

    /**
     * 画屏幕底图：整块 96×64 的四边形（<b>以原点为中心</b>，x ∈ [-48, 48]、y ∈ [-32, 32]），
     * UV 只取贴图里 uv 3..6 × 0..2 那一块（= 模型元素 2 的 north 面）。
     *
     * <p>顶点格式跟着 {@code getEntityTranslucentEmissive} 走：
     * {@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}，所以要比 MTR 的光束层多一个
     * {@code overlay} 元素（{@link OverlayTexture#DEFAULT_UV}），少了会直接抛
     * 「missing element」。
     */
    private static void drawScreen(final MatrixStack matrices, final VertexConsumerProvider vertexConsumers) {
        final VertexConsumer buffer = vertexConsumers.getBuffer(
                RenderLayer.getEntityTranslucentEmissive(TEXTURE));
        final MatrixStack.Entry entry = matrices.peek();
        final Matrix4f position = entry.getPositionMatrix();
        final Matrix3f normal = entry.getNormalMatrix();
        final float halfWidth = DepOnWallLayout.SCREEN_WIDTH * 0.5F;
        final float halfHeight = DepOnWallLayout.SCREEN_HEIGHT * 0.5F;
        // 左上 → 左下 → 右下 → 右上，法线朝 -Z（= 朝观察者）。
        vertex(buffer, position, normal, -halfWidth, -halfHeight,
                DepOnWallLayout.UV_LEFT, DepOnWallLayout.UV_TOP);
        vertex(buffer, position, normal, -halfWidth, halfHeight,
                DepOnWallLayout.UV_LEFT, DepOnWallLayout.UV_BOTTOM);
        vertex(buffer, position, normal, halfWidth, halfHeight,
                DepOnWallLayout.UV_RIGHT, DepOnWallLayout.UV_BOTTOM);
        vertex(buffer, position, normal, halfWidth, -halfHeight,
                DepOnWallLayout.UV_RIGHT, DepOnWallLayout.UV_TOP);
    }

    private static void vertex(final VertexConsumer buffer, final Matrix4f position, final Matrix3f normal,
                               final float x, final float y, final float u, final float v) {
        buffer.vertex(position, x, y, 0.0F)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(DepOnWallLayout.FULL_LIGHT)
                .normal(normal, 0.0F, 0.0F, -1.0F)
                .next();
    }

    // ------------------------------------------------------------------ 数字

    /**
     * 把这一帧的 {@code mm:ss} 画成七段数码管：整串在屏幕<b>正中</b>水平居中、竖直居中，
     * 字高取「塞得进可用宽高」的那个值（位数变了自动缩放，见 {@link DepOnWallLayout}）。
     */
    private static void drawDigits(final MatrixStack matrices,
                                   final VertexConsumerProvider vertexConsumers,
                                   final Display display) {
        final float unitWidth = DepOnWallDigits.width(display.text(), 1.0F);
        if (unitWidth <= 0.0F) {
            return;
        }
        final float height = Math.min(
                DepOnWallLayout.LED_MAX_HEIGHT,
                DepOnWallLayout.TEXT_MAX_WIDTH / unitWidth);
        final float width = unitWidth * height;
        // 原点在屏幕正中：左上角 = (-宽/2, -高/2)。
        final float left = -width * 0.5F;
        final float top = -height * 0.5F;

        final VertexConsumer buffer = vertexConsumers.getBuffer(
                RenderLayer.getEntityTranslucentEmissive(LED_TEXTURE));
        final MatrixStack.Entry entry = matrices.peek();
        DepOnWallDigits.draw(buffer, entry.getPositionMatrix(), entry.getNormalMatrix(),
                display.text(), left, top, height,
                display.overdue() ? DepOnWallLayout.COLOR_OVERDUE : DepOnWallLayout.COLOR_COUNTDOWN);
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 屏幕（底图 + 数字）刻意贴在边框正面之前 0.04/16 格，比方块的包围盒探出一点点；
     * 打开这一项，站在屏幕正前方、方块包围盒刚好出视锥时也不会整块不画。
     */
    @Override
    public boolean rendersOutsideBoundingBox(final DepOnWallBlockEntity entity) {
        return true;
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 算出这块牌子这一帧该显示什么；{@code null} = 空屏。
     *
     * <h2>数值公式：JCM 原式，一字不改</h2>
     * <pre>
     * platformId = RailwayData.getClosePlatformId(PLATFORMS, DATA_CACHE, pos, 5, 3, 3);
     * if (platformId == 0) return;                                  // 附近没站台 → 空屏
     * list = sorted(SCHEDULES_FOR_PLATFORM.get(platformId));        // 空 / 缺 = 没数据
     * sec  = (entry.arrivalMillis - now) / 1000;                     // 先减再 /1000，向零截断
     * dwellHalf = platform == null ? 0 : platform.getDwellTime() / 2; // 整数除法
     * remain = abs(dwellHalf - abs(sec));                            // ← 核心公式，两层 abs
     * </pre>
     *
     * <h2>取哪一条：「锁一条」而不是每帧取第 1 条</h2>
     * <p>JCM 是每帧现取 {@code list.get(0)}。MTR 的这张表是服务端<b>周期性整表推送</b>的，
     * 每推一次都可能改掉 {@code arrivalMillis}、删掉刚发车的条目、或换掉整个集合 ——
     * 于是同一块牌子的「第 1 条」会在倒数途中换人，实机看到的就是
     * <b>绿字数到 2 突然弹回 5 接着数</b>，下一推再弹一次。
     *
     * <p>所以「用哪一条」改成方块的<b>持久锁定</b>（{@link DepOnWallLock}，存在
     * {@link DepOnWallBlockEntity} 上）：
     * <ol>
     *   <li>选班：从排序后的列表里取<b>第一条还没走完</b>的（{@link DepOnWallLock#finished}）——
     *       已经过了「绿 dwellHalf + 红 5 秒」的老条目直接跳过，不会被重新选回来；</li>
     *   <li>当前锁定的班次<b>一旦开始倒数</b>（{@code sec <= 0} 过），就<b>不换</b>，
     *       服务端怎么推都只数这一条，直到它自己走完、自动放锁；</li>
     *   <li>屏幕还空着（车没到站）时没有可见读数，这时才允许换锁 —— 早到的车 / 新插入的班次
     *       照旧能在到站前抢到位置，而且不会有任何数字跳变。</li>
     * </ol>
     * <p>数值本身再走一道单调钳制（绿段只减不增、红段只增不减、开过车不回空屏），
     * {@code System.currentTimeMillis()} 在同一 tick 里被多次读取或系统对时倒退时，
     * 读数是「停住」而不是往回跳。正常走时它本来就单调，钳制一个数都不改。
     *
     * <p>只有「颜色 / 阶段」是我们的扩展：
     * <ul>
     *   <li>{@code |sec| < dwellHalf}（递减段）→ 绿，{@code %02d:%02d}；</li>
     *   <li>{@code |sec| >= dwellHalf}（递增段，到点还没走）→ 红，正计到
     *       {@link DepOnWallLock#OVERTIME_SECONDS} 秒就黑屏；</li>
     *   <li>红灯走完 → 放锁、空屏，等下一班。</li>
     * </ul>
     *
     * <p>{@code /mtr_lte dep debug on} 之后每块牌子每秒打一行 {@code [lte] dep: …}
     * （{@link DepOnWallDebug}），把这一帧的 {@code entries / firstArrival / sec / dwell /
     * lockArrival} 原样写出来 —— 再出「2 变 5」能直接看出是哪一环变了。
     */
    private static Display display(final DepOnWallBlockEntity entity) {
        final BlockPos pos = entity.getPos();
        final long now = System.currentTimeMillis();

        // 取证用的现场（不管走哪个分支都要能打出来）。
        long platformId = 0L;
        int entries = 0;
        ScheduleEntry head = null;
        Long sec = null;
        int dwellRaw = 0;
        int dwellHalf = 0;
        Integer remain = null;
        String phase = "blank";
        Long lockArrival = null;
        Display result = null;

        try {
            platformId = RailwayData.getClosePlatformId(
                    ClientData.PLATFORMS, ClientData.DATA_CACHE, pos,
                    SEARCH_RADIUS, SEARCH_MARGIN, SEARCH_MARGIN);
            if (platformId == 0L) {
                // 附近没站台：牌子旁边什么也没有，把锁也放掉。
                entity.setDepLock(null);
            } else {
                final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformId);
                // 整数除法，dwell 20 → 10。Platform 缺数据时 JCM 用 0，照抄。
                dwellRaw = platform == null ? 0 : platform.getDwellTime();
                dwellHalf = dwellRaw / 2;

                final List<ScheduleEntry> list = schedules(platformId);
                entries = list == null ? 0 : list.size();
                // 第一条「还没走完」的班次 = 这一帧服务端认为的下一班。
                head = firstLive(list, dwellHalf, now);

                DepOnWallLock lock = entity.depLock();
                if (lock != null && (!lock.samePlatform(platformId) || lock.finished(now))) {
                    // 牌子被挪到了别的站台旁边，或者锁着的那条早就走完了：放锁重选。
                    lock = null;
                }
                if (lock != null && !lock.started() && head != null && !lock.matches(head)) {
                    // 还没开始倒数（屏幕是空的）→ 换锁不会有可见跳变，跟着新的一条走。
                    lock = null;
                }
                if (lock == null && head != null) {
                    // 锁定：dwellHalf 一起冻在锁里，之后站台数据被重推也不动基准。
                    lock = DepOnWallLock.of(platformId, head, dwellHalf);
                }

                if (lock != null) {
                    lockArrival = Long.valueOf(lock.arrivalMillis());
                    sec = Long.valueOf((lock.arrivalMillis() - now) / 1000L);
                    final DepOnWallLock.Reading reading = lock.tick(now);
                    if (reading != null) {
                        remain = Integer.valueOf(reading.remain());
                        phase = reading.overdue() ? "red" : "green";
                        result = new Display(format(reading.remain()), reading.overdue());
                    }
                    if (lock.done()) {
                        // 红灯 5 秒数完了：放锁，下一帧重新选班（这条已经被 finished 过滤掉）。
                        lock = null;
                    }
                }
                entity.setDepLock(lock);
            }
        } catch (Throwable t) {
            // 数据没同步好（刚进服 / 站台被删）不该让整块方块不画。
            result = null;
        }

        if (DepOnWallDebug.enabled()) {
            DepOnWallDebug.log(pos, now, "[lte] dep: pos=(" + pos.getX() + "," + pos.getY() + ","
                    + pos.getZ() + ") platform=" + platformId
                    + " entries=" + entries
                    + " firstArrival=" + (head == null ? "none" : Long.toString(head.arrivalMillis))
                    + " now=" + now
                    + " sec=" + (sec == null ? "none" : sec.toString())
                    + " dwell=" + dwellRaw
                    + " dwellHalf=" + dwellHalf
                    + " remain=" + (remain == null ? "none" : remain.toString())
                    + " phase=" + phase
                    + " lockArrival=" + (lockArrival == null ? "none" : lockArrival.toString()));
        }
        return result;
    }

    /**
     * 这一帧的 {@code SCHEDULES_FOR_PLATFORM}（排序后的副本）；平台没有表 = {@code null}。
     *
     * <p>表是服务端<b>整表替换</b>推过来的（{@code clearAndAddAll}），所以这里每帧重新拿、
     * 重新拷一份再排序，绝不缓存 —— 缓存反而会让「换人了」这件事看不见。锁定与否由
     * {@link DepOnWallLock} 决定，不靠这份列表的稳定性。
     */
    private static List<ScheduleEntry> schedules(final long platformId) {
        final Set<ScheduleEntry> schedules = ClientData.SCHEDULES_FOR_PLATFORM.get(platformId);
        if (schedules == null) {
            return null;
        }
        final List<ScheduleEntry> list = new ArrayList<>(schedules);
        // JCM 先拷一份再排序（不改调用方那个集合）。
        Collections.sort(list);
        return list;
    }

    /**
     * 列表里第一条「还没走完」的班次；没有 = {@code null}（空屏）。
     *
     * <p>跳过已经过了显示窗口的条目，是为了让「刚放锁的那条」不会被重新选回来（否则放锁之后
     * 会立刻再锁定它、再立刻放锁，成了每帧一次的循环）。
     */
    private static ScheduleEntry firstLive(final List<ScheduleEntry> list, final int dwellHalf,
                                           final long now) {
        if (list == null) {
            return null;
        }
        for (final ScheduleEntry entry : list) {
            if (!DepOnWallLock.finished(entry.arrivalMillis, dwellHalf, now)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 秒 → {@code mm:ss}（不足两位补零）。
     * <p>分钟<b>不</b>折成小时：站台停站最多几分钟，{@code 12:05} 比 {@code 0:12:05} 好读；
     * 真要超过 99 分钟就让它自然变宽（{@code 120:00}），排版会自己缩。
     */
    private static String format(final long seconds) {
        final long s = Math.max(0L, seconds);
        return String.format("%02d:%02d", s / 60L, s % 60L);
    }
}
