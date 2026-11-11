package dev.js.mtrheadlight.signal;

import mtr.block.BlockNode;
import mtr.client.ClientData;
import mtr.data.Rail;
import mtr.path.PathData;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 让一台<b>已绑定</b>的方速交通灯按 MTR 的乘务信号显示。<b>只在客户端调用</b>
 * （{@code FangsuTrafficLightRenderMixin} 在 {@code BlockEntityTrafficLight#whenRendering} 的
 * HEAD 每帧调一次）。
 *
 * <h2>为什么必须由我们写 {@code lightState}</h2>
 * <p>方速 1.3.2 的 {@code whenRendering()} 只有 {@code "program"} 档会调 {@code drive()}：
 * <pre>
 * 123: if ("program".equals(this.dispType)) this.drive();
 * 132: if (!alwaysOn &amp;&amp; (on == null || !on)) continue;   // on = lightState.get(label)
 * </pre>
 * {@code dispType="mtr"} 只是「别跑积木程序」，没有任何代码填 {@code lightState} —— 三个灯珠
 * 全灭。全 jar 里写 {@code lightState} 的只有 {@code whenLoading()}（全部置 false）、
 * {@code drive()}（积木程序的 {@code TYPE_SET_LIGHT}）和 {@code applyDisplayStates()}（倒计时数字）。
 * 也就是说 mtr 档在方速里<b>是空实现</b>，红/黄/绿永远不会亮。这里补上驱动。
 *
 * <h2>写什么</h2>
 * <p>默认模型 {@code fangsu:traffic_light/basic_traffic_light.json} 的 {@code subModels} 是
 * {@code {"main":"main","red":"r","yellow":"y","green":"g"}}，{@code lightState} 的 key 就是
 * {@code main/red/yellow/green}（{@code whenLoading} 已经为每个非 main 的 key 置了 false）。
 * 少数自制模型没有 yellow 灯，所以按 key 是否存在决定 aspect 的解释（见 {@link #applyAspect}）。
 *
 * <h2>aspect 从哪来</h2>
 * <p>逐字复用 MTR {@code RenderSignalBase#getOccupiedAspect}（3.6.4.2，{@code aspects} 个档位）：
 * 从节点出发沿 {@code rail.facingStart.similarFacing(angle)} 的方向走，
 * {@code ClientData.SIGNAL_BLOCKS.isOccupied(product)} 或 {@code OCCUPIED_RAILS.get(product)}
 * 为真就返回当前跳数（1 = 第一节被占，2 = 第二节被占），走得通但没占用 → 0，一节轨道都没有 → -1。
 * 方速自己那份拷贝（{@code com.fangsu.scripting.MinecraftClientUtil#getOccupiedAspect}，
 * 全 jar 无调用点、且起点写死成传进来的坐标）算的是同一件事，这里不反射它 —— 直接调 MTR 的
 * 公开 {@code ClientData}，编译期类型安全。
 *
 * <p>颜色映射照抄 MTR {@code RenderSignalLight3Aspect}：aspect 1 → 红、2 → 黄、
 * 0（默认）→ 绿、-1 → 全灭。角度用 MTR 的 {@code facing.asRotation() + 90}，和
 * {@code RenderSignalBase#render} 完全一致，所以「灯朝向和轨道对不上就不亮」的行为也一样。
 *
 * <p>职责边界：本类只在<b>有绑定</b>时动手。没绑定的方速交通灯一个字段都不碰，玩家在配置界面
 * 里选的「程序控制」照旧。任何异常都吞掉（渲染线程里的辅助代码不能让灯消失，更不能崩客户端）。
 */
public final class FangsuSignalSync {

    private static final String KEY_DISP_TYPE = "dispType";
    private static final String MODE_MTR = "mtr";
    private static final String KEY_RED = "red";
    private static final String KEY_YELLOW = "yellow";
    private static final String KEY_GREEN = "green";

    /** 和 MTR 的三灯信号灯同档位（{@code RenderSignalLight3Aspect}）。 */
    private static final int ASPECTS = 3;

    /** 类 -> 反射字段；方速只有一种交通灯方块实体，实际就一条。 */
    private static final Map<Class<?>, Refs> REFS = new java.util.concurrent.ConcurrentHashMap<>();

    private FangsuSignalSync() {
    }

    /**
     * 每帧（方速那个 {@code fangsu-rendering-async} 线程）调用一次：把绑定的节点算成 aspect 写进
     * {@code lightState}，并把 {@code dispType} 钉在 {@code "mtr"}。
     *
     * <p>钉字段这一步不是多余的：方速的 {@code dispType} <b>不参与 NBT 往返</b>
     * （{@code readNbt} 只灌 {@code extraConfigs}，从不赋给字段），所以不每帧写，重进存档之后
     * 字段会回落成 {@code "program"}，{@code drive()} 就会用空程序把我们的 {@code lightState}
     * 覆盖回去。
     */
    public static void onRender(final Object self) {
        try {
            if (!(self instanceof BlockEntity be)) {
                return;
            }
            final World world = be.getWorld();
            if (world == null || !world.isClient) {
                return;
            }
            final BlockPos lamp = be.getPos();

            final Direction ownFacing = facingOf(be.getCachedState(), Direction.NORTH);
            BlockPos node = SignalLinks.nodeFor(world, lamp);
            Direction facing = ownFacing;
            if (node == null) {
                // 灯 → 灯：抄源灯的那个节点（源灯自己没绑节点就按 MTR 的扫描找他脚边那颗）。
                final BlockPos source = SignalLinks.resolveSource(world, lamp);
                if (source == null) {
                    return;
                }
                facing = facingOf(world.getBlockState(source), ownFacing);
                node = SignalLinks.nodeFor(world, source);
                if (node == null) {
                    node = scanNode(world, source, facing);
                }
            }
            if (node == null) {
                return;
            }

            final Refs refs = refs(be.getClass());
            if (refs.dispType == null || refs.lightState == null) {
                return;
            }
            if (!MODE_MTR.equals(refs.dispType.get(be))) {
                refs.dispType.set(be, MODE_MTR);
            }
            if (!(refs.lightState.get(be) instanceof Map<?, ?> map)) {
                return;
            }
            applyAspect(map, aspect(node, facing.asRotation() + 90.0F));
        } catch (Throwable ignored) {
            // 渲染辅助代码：静默退回方速自己的行为。
        }
    }

    // ------------------------------------------------------------------ 灯色

    @SuppressWarnings("unchecked")
    private static void applyAspect(final Map<?, ?> raw, final int aspect) {
        final Map<String, Boolean> state = (Map<String, Boolean>) raw;
        // 只改这个模型真有的灯珠（whenLoading 给每个非 main 的 subModel 都置过 false）。
        final boolean hasYellow = state.containsKey(KEY_YELLOW);
        final boolean green = aspect == 0;
        final boolean red = aspect >= 1 && (aspect == 1 || !hasYellow);
        final boolean yellow = aspect >= 2 && hasYellow;
        if (state.containsKey(KEY_RED)) {
            state.put(KEY_RED, red);
        }
        if (hasYellow) {
            state.put(KEY_YELLOW, yellow);
        }
        if (state.containsKey(KEY_GREEN)) {
            state.put(KEY_GREEN, green);
        }
    }

    /**
     * MTR {@code RenderSignalBase#getOccupiedAspect(BlockPos, float)} 的逐字副本
     * （3.6.4.2 javap：{@code aspects} 是渲染器构造参数，这里固定 3）。
     *
     * @return 1..N = 第 N 节被占；0 = 有轨道但没占；-1 = 这个角度上一节轨道都没有（不亮）。
     */
    private static int aspect(final BlockPos node, final float angle) {
        Map<BlockPos, Float> scan = new HashMap<>(4);
        scan.put(node, angle);
        int occupiedAspect = -1;
        for (int j = 1; j < ASPECTS; j++) {
            final Map<BlockPos, Float> next = new HashMap<>(4);
            for (final Map.Entry<BlockPos, Float> check : scan.entrySet()) {
                final Map<BlockPos, Rail> rails = ClientData.RAILS.get(check.getKey());
                if (rails == null) {
                    continue;
                }
                for (final Map.Entry<BlockPos, Rail> railEntry : rails.entrySet()) {
                    final Rail rail = railEntry.getValue();
                    if (rail == null || !rail.facingStart.similarFacing(check.getValue())) {
                        continue;
                    }
                    final java.util.UUID product = PathData.getRailProduct(check.getKey(), railEntry.getKey());
                    if (ClientData.SIGNAL_BLOCKS.isOccupied(product)) {
                        return j;
                    }
                    final Boolean occupied = ClientData.OCCUPIED_RAILS.get(product);
                    if (occupied != null && occupied) {
                        return j;
                    }
                    next.put(railEntry.getKey(), rail.facingEnd.getOpposite().angleDegrees);
                    occupiedAspect = 0;
                }
            }
            scan = next;
            if (scan.isEmpty()) {
                break;
            }
        }
        return occupiedAspect;
    }

    // ------------------------------------------------------------------ 工具

    private static Direction facingOf(final BlockState state, final Direction fallback) {
        try {
            if (state != null && state.contains(Properties.HORIZONTAL_FACING)) {
                return state.get(Properties.HORIZONTAL_FACING);
            }
        } catch (Throwable ignored) {
            // 状态不可读 → fallback
        }
        return fallback;
    }

    /**
     * MTR {@code RenderSignalBase#getNodePos} 的逐字副本（源灯自己没绑节点时用）：
     * {@code int[] d = {0,1,-1,2,-2,3,-3,4,-4}}，先 {@code facing.rotateYClockwise()} 扫 x，
     * 再 {@code facing} 扫 z，{@code up(-5..0)}。
     */
    private static BlockPos scanNode(final World world, final BlockPos pos, final Direction facing) {
        final int[] d = {0, 1, -1, 2, -2, 3, -3, 4, -4};
        for (final int z : d) {
            for (final int x : d) {
                for (int up = -5; up <= 0; up++) {
                    final BlockPos p = pos.up(up)
                            .offset(facing.rotateYClockwise(), x)
                            .offset(facing, z);
                    if (BlockNode.class.isInstance(world.getBlockState(p).getBlock())) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** 反射出 {@code dispType} / {@code lightState}（都在 {@code BlockEntityTrafficLight}）。 */
    private static Refs refs(final Class<?> type) {
        Refs cached = REFS.get(type);
        if (cached != null) {
            return cached;
        }
        try {
            final Field mode = type.getDeclaredField(KEY_DISP_TYPE);
            mode.setAccessible(true);
            final Field state = type.getDeclaredField("lightState");
            state.setAccessible(true);
            cached = new Refs(mode, state);
            REFS.put(type, cached);
            return cached;
        } catch (Throwable t) {
            // 方速换了字段名/结构 → 本功能静默关闭（灯保持方速自己的行为）。
            REFS.put(type, Refs.NONE);
            return Refs.NONE;
        }
    }

    private static final class Refs {
        static final Refs NONE = new Refs(null, null);
        final Field dispType;
        final Field lightState;

        Refs(final Field dispType, final Field lightState) {
            this.dispType = dispType;
            this.lightState = lightState;
        }
    }
}
