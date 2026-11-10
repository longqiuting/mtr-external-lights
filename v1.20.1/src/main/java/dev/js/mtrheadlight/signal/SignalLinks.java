package dev.js.mtrheadlight.signal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信号连接器：客户端持有的两张镜像表。
 *
 * <ul>
 *   <li>{@link #NODES}：维度 id + 信号灯方块坐标 → 节点方块坐标（MTR 的 {@code BlockNode} 位置，
 *       也就是 {@code ClientData.RAILS} 的 key）；</li>
 *   <li>{@link #LAMPS}：维度 id + 信号灯方块坐标 → <b>源</b>信号灯方块坐标 —— 这盏灯抄那盏灯的显示状态。</li>
 * </ul>
 *
 * <p>服务端权威的那一份在 {@code dev.js.mtrheadlight.signal.SignalLinkStore}，这里只是同步过来的镜像。
 *
 * <p>唯一的读取者是 {@code RenderSignalBaseMixin}：MTR 原本在渲染时从信号灯方块位置向前/向侧扫描
 * 找 {@code BlockNode}，绑定的信号灯直接用这里的节点位置顶掉那次扫描；lamp 链接则在算出 aspect
 * 之后换成源灯的角度再算一遍（等价于「拿源灯的那一份 aspect」）。
 */
public final class SignalLinks {

    /** 维度 id -> (信号灯 pos -> 节点 pos)。 */
    private static final Map<String, Map<BlockPos, BlockPos>> NODES = new ConcurrentHashMap<>();

    /** 维度 id -> (信号灯 pos -> 源信号灯 pos)。 */
    private static final Map<String, Map<BlockPos, BlockPos>> LAMPS = new ConcurrentHashMap<>();

    private SignalLinks() {
    }

    // ---------------------------------------------------------------- 节点表

    /** 整个换掉（服务端全量同步时用）。 */
    public static void replace(final String dimension, final Map<BlockPos, BlockPos> links) {
        if (links == null || links.isEmpty()) {
            NODES.remove(dimension);
        } else {
            NODES.put(dimension, new HashMap<>(links));
        }
    }

    public static void put(final String dimension, final BlockPos signal, final BlockPos node) {
        NODES.computeIfAbsent(dimension, k -> new HashMap<>())
                .put(signal.toImmutable(), node.toImmutable());
    }

    public static void remove(final String dimension, final BlockPos signal) {
        final Map<BlockPos, BlockPos> map = NODES.get(dimension);
        if (map != null) {
            map.remove(signal);
        }
    }

    /**
     * @return 绑定到该信号灯的节点坐标；没有绑定（或者维度拿不到）返回 {@code null}，
     *         调用方应当退回 MTR 自己的位置扫描。
     */
    public static BlockPos nodeFor(final BlockView view, final BlockPos signal) {
        final Map<BlockPos, BlockPos> map = NODES.get(dimensionOf(view));
        if (map == null || map.isEmpty()) {
            return null;
        }
        return map.get(signal);
    }

    // ---------------------------------------------------------------- 灯 → 灯

    public static void replaceLamps(final String dimension, final Map<BlockPos, BlockPos> links) {
        if (links == null || links.isEmpty()) {
            LAMPS.remove(dimension);
        } else {
            LAMPS.put(dimension, new HashMap<>(links));
        }
    }

    public static void putLamp(final String dimension, final BlockPos lamp, final BlockPos source) {
        LAMPS.computeIfAbsent(dimension, k -> new HashMap<>())
                .put(lamp.toImmutable(), source.toImmutable());
    }

    public static void removeLamp(final String dimension, final BlockPos lamp) {
        final Map<BlockPos, BlockPos> map = LAMPS.get(dimension);
        if (map != null) {
            map.remove(lamp);
        }
    }

    /**
     * 顺着「灯 → 灯」链走到<b>终点</b>：终点那盏灯不再指向别人，它的 aspect 就是要显示的东西。
     *
     * <p>循环保护两层：
     * <ol>
     *   <li>服务端 {@code SignalLinkStore#putLamp} 建链时就拒绝成环，正常玩法进不到这里；</li>
     *   <li>这里再用一个访问集 + {@link SignalLinkStore#MAX_HOPS} 兜底 —— 旧存档被手改、
     *       两个客户端版本不一致、包丢了半条，都只会让这盏灯退回自己的行为，不会递归爆栈。</li>
     * </ol>
     *
     * @return 终点源灯；没有 lamp 链接、链太长、或者检测到环 → {@code null}（退回这盏灯自己的行为）。
     */
    public static BlockPos resolveSource(final BlockView view, final BlockPos lamp) {
        final Map<BlockPos, BlockPos> map = LAMPS.get(dimensionOf(view));
        if (map == null || map.isEmpty()) {
            return null;
        }
        BlockPos cursor = map.get(lamp);
        if (cursor == null) {
            return null;
        }
        final Set<BlockPos> seen = new HashSet<>(8);
        seen.add(lamp);
        for (int hop = 0; hop < SignalLinkStore.MAX_HOPS; hop++) {
            if (!seen.add(cursor)) {
                // 环（A→B→A）：整条链作废，谁也别显示谁。
                return null;
            }
            final BlockPos next = map.get(cursor);
            if (next == null) {
                return cursor;
            }
            cursor = next;
        }
        return null;
    }

    public static void clear() {
        NODES.clear();
        LAMPS.clear();
    }

    private static String dimensionOf(final BlockView view) {
        return view instanceof World ? ((World) view).getRegistryKey().getValue().toString() : "";
    }
}
