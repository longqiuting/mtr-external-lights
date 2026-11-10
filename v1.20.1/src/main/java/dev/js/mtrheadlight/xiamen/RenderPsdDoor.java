// Xiamen door renderer — 直接复用 MTR 的 RenderPSDAPGDoor（几何 / 盒子 UV / 贴图 id / 门灯 / 动画全部同一份代码）。
package dev.js.mtrheadlight.xiamen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.js.mtrheadlight.LteLog;
import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.IBlock;
import mtr.render.RenderPSDAPGDoor;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 厦门门扇的渲染器 —— <b>不自己画</b>，直接继承 MTR 的 {@code mtr.render.RenderPSDAPGDoor}。
 *
 * <h2>为什么改成继承（以前是自己照抄几何，结果门扇透明）</h2>
 * <p>MTR 的门扇渲染器本身就是 {@code BlockEntityRenderer<TileEntityPSDAPGDoorBase>}，构造器是
 * public 的 {@code RenderPSDAPGDoor(BlockEntityRenderDispatcher dispatcher, int type)}；而我们的方块实体
 * 本来就继承 {@code BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase}、方块继承
 * {@code BlockPSDAPGDoorBase}（列车开门用的 {@code instanceof} 判据要求如此）。
 * 所以只要在注册 BER 时把 {@code context.getRenderDispatcher()} 交给父类，MTR 就会<b>用同一份代码</b>
 * 画我们的门：
 * <ul>
 *   <li>贴图 id 由 MTR 自己 {@code String.format} 出来（见 {@link #FMT_DOOR}），前缀必然是
 *       {@code mtr:} —— 资源包栈里 {@code assets/mtr/textures/block/psd_door_*_1.png} 被谁接管，
 *       我们的门就和旁边的 MTR 门一起变；</li>
 *   <li>盒子 UV（{@code ModelSingleCube(36, 18, 0, 0, 0, 16, 16, 2)}）、{@code rotateX(180)} 的
 *       朝向翻转、{@code open} 平移、门灯 {@code QueuedRenderLayer.LIGHT}/{@code EXTERIOR}、
 *       「暂停使用」牌，全部与 MTR 逐字节一致；</li>
 *   <li>渲染层是 MTR 的 {@code MoreRenderLayers.getExterior(id)} = {@code RenderLayer.getEntityCutout(id)}
 *       （带背面剔除，不是 {@code NoCull}），走 {@code RenderTrains.scheduleRender} 的批处理 ——
 *       和 MTR 自己的门同一个通道，所以深度/透明度排序也一致。</li>
 * </ul>
 * 以前那份「照抄几何」的版本里，正面/背面是两个独立 quad、层用 {@code entityCutoutNoCull}、
 * 还自己算了 UV 与正反配对，任何一处差一点都会表现成「门扇透明/只有玻璃」。现在这些差异<b>按定义不存在</b>。
 *
 * <h2>门扇贴图是「玻璃 + 橙色横幅」这件事本身不是 bug</h2>
 * <p>厦门包与南北组包里的 {@code psd_door_top_left_1.png}（576×288 = 36×18 单位 ×16）经像素统计：
 * 整张 45% 像素 alpha=0，门板<b>中部整片是 alpha=0</b>（玻璃），只有顶部橙色警示横幅
 * （排队候车 / 灯闪铃响）与底部橙色腰线、白色开启箭头是不透明的。MTR 原版那张（72×36）
 * 更极端：79% alpha=0，只剩一圈黑框。也就是说「能看见玻璃后面的东西」是资源包的设计，
 * 真正要保证的是「橙色横幅 + 腰线 + 箭头必须画出来」—— 这正是 MTR 渲染器负责的部分。
 *
 * <h2>贴图自检</h2>
 * <p>{@link #logTable()} 在第一帧把 MTR 可能请求的 15 个 id 全部打印出来，并附上
 * <b>实际命中的资源包名</b>（{@code ResourceManager#getAllResources} 的栈顶），
 * 日志行形如 {@code [lte] psd door tex: mtr:textures/block/psd_door_top_left_1.png <- 南北组 …}。
 * {@link #logRequested} 再把「这一帧真实用到的 id」逐条打印一次（去重）。出问题时直接看 latest.log，
 * 不用再猜是哪张图。
 */
public class RenderPsdDoor extends RenderPSDAPGDoor<XiamenPsdBlocks.TileEntityXiamenPSDDoor> {

    /** MTR 的 door type：0 = PSD（{@code psd_door_*_1.png}），1 = PSD 二代（{@code _2}），2 = APG，3/4 = 电梯门。 */
    private static final int TYPE = 0;

    // ---- 与 MTR RenderPSDAPGDoor#render 里逐字相同的四个格式串（type=0 → 第三个占位符是 "1"） ----
    /** 普通门扇：{@code psd_door_<top|bottom>_<left|right>_<1|2>.png}。 */
    private static final String FMT_DOOR = "mtr:textures/block/psd_door_%s_%s_%s.png";
    /** 端部门扇主扇：{@code psd_door_end_<half>_<side>_1_<1|2>.png}。 */
    private static final String FMT_END_MAIN = "mtr:textures/block/psd_door_end_%s_%s_1_%s.png";
    /** 端部门扇副扇：{@code psd_door_end_<half>_<side>_2_<1|2>.png}。 */
    private static final String FMT_END_SUB = "mtr:textures/block/psd_door_end_%s_%s_2_%s.png";
    /** 门灯：{@code light_<on|off>.png}。 */
    private static final String FMT_LIGHT = "mtr:textures/block/light_%s.png";
    /** 「暂停使用」牌。 */
    private static final String ID_SIGN = "mtr:textures/block/sign/door_not_in_use.png";

    /** MTR 侧 type 字段的字符串形态（type 0 → "1"）。 */
    private static final String TYPE_TAG = TYPE == 1 ? "2" : "1";

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean TABLE_LOGGED = new AtomicBoolean();

    public RenderPsdDoor(BlockEntityRendererFactory.Context context) {
        // 关键的一行：把 MTR 自己的渲染器叫来画我们的方块实体。
        super(context.getRenderDispatcher(), TYPE);
    }

    @Override
    public void render(XiamenPsdBlocks.TileEntityXiamenPSDDoor entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        if (TABLE_LOGGED.compareAndSet(false, true)) {
            logTable();
        }
        World world = entity.getWorld();
        if (world != null) {
            logRequested(world.getBlockState(entity.getPos()));
        }
        super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay);
    }

    // ------------------------------------------------------------------
    // 贴图 id：全部集中在这里构造 / 打印，别处不再出现 new Identifier(...)
    // ------------------------------------------------------------------

    /** MTR 的 type 0 会请求的全部 15 个 id（顺序就是下面的打印顺序）。 */
    private static List<String> allIds() {
        final List<String> ids = new ArrayList<>(16);
        for (String half : new String[]{"top", "bottom"}) {
            for (String side : new String[]{"left", "right"}) {
                ids.add(String.format(FMT_DOOR, half, side, TYPE_TAG));
                ids.add(String.format(FMT_END_MAIN, half, side, TYPE_TAG));
                ids.add(String.format(FMT_END_SUB, half, side, TYPE_TAG));
            }
        }
        ids.add(String.format(FMT_LIGHT, "on"));
        ids.add(String.format(FMT_LIGHT, "off"));
        ids.add(ID_SIGN);
        return ids;
    }

    /** 这一帧真实要用的 id（和 MTR 内部 {@code String.format} 的参数顺序完全相同）。 */
    private static List<String> requestedFor(BlockState state) {
        final String half = IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.HALF) == DoubleBlockHalf.UPPER
                ? "top" : "bottom";
        final String side = IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.SIDE) == IBlock.EnumSide.RIGHT
                ? "right" : "left";
        final List<String> ids = new ArrayList<>(4);
        if (IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.END)) {
            ids.add(String.format(FMT_END_SUB, half, side, TYPE_TAG));
            ids.add(String.format(FMT_END_MAIN, half, side, TYPE_TAG));
        } else {
            ids.add(String.format(FMT_DOOR, half, side, TYPE_TAG));
        }
        ids.add(String.format(FMT_LIGHT, "on"));
        ids.add(String.format(FMT_LIGHT, "off"));
        ids.add(ID_SIGN);
        return ids;
    }

    /** 第一帧：把 15 个 id 与「栈顶资源包」一起打出来。 */
    private static void logTable() {
        try {
            for (String raw : allIds()) {
                final Identifier id = new Identifier(raw);
                LteLog.LOGGER.info("[lte] psd door tex: {} <- {}", id, sourceOf(id));
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] psd door texture table failed", t);
        }
    }

    /** 每帧：把这一帧真正请求的 id 打一次（同一个 id 只打一次）。 */
    private static void logRequested(BlockState state) {
        try {
            for (String raw : requestedFor(state)) {
                if (SEEN.add(raw)) {
                    final Identifier id = new Identifier(raw);
                    LteLog.LOGGER.info("[lte] psd door tex: {} <- {}", id, sourceOf(id));
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] psd door texture probe failed", t);
        }
    }

    /** 栈顶资源包名（= 真正生效的那张图从哪来）。 */
    private static String sourceOf(Identifier id) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return "?";
            }
            final List<Resource> all = client.getResourceManager().getAllResources(id);
            if (all.isEmpty()) {
                return "MISSING";
            }
            final Resource top = all.get(0);
            final String packs = top.getResourcePackName();
            return all.size() > 1 ? packs + " (+" + (all.size() - 1) + " lower)" : packs;
        } catch (Throwable t) {
            return "lookup failed: " + t.getClass().getSimpleName();
        }
    }
}
