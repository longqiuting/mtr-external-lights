package dev.js.mtrheadlight.signal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信号连接器：客户端持有的「信号灯方块 → 轨道节点」绑定表。
 *
 * <p>键是维度 id + 信号灯方块坐标，值是节点方块坐标（MTR 的 {@code BlockNode} 位置，
 * 也就是 {@code ClientData.RAILS} 的 key）。服务端权威的那一份在
 * {@code dev.js.mtrheadlight.signal.SignalLinkStore}，这里只是同步过来的镜像。
 *
 * <p>唯一的读取者是 {@code RenderSignalBaseMixin}：MTR 原本在渲染时从信号灯方块位置
 * 向前/向侧扫描找 {@code BlockNode}，绑定的信号灯直接用这里的节点位置顶掉那次扫描。
 */
public final class SignalLinks {

    /** 维度 id -> (信号灯 pos -> 节点 pos)。 */
    private static final Map<String, Map<BlockPos, BlockPos>> CLIENT = new ConcurrentHashMap<>();

    private SignalLinks() {
    }

    /** 整个换掉（服务端全量同步时用）。 */
    public static void replace(final String dimension, final Map<BlockPos, BlockPos> links) {
        if (links == null || links.isEmpty()) {
            CLIENT.remove(dimension);
        } else {
            CLIENT.put(dimension, new HashMap<>(links));
        }
    }

    public static void clear() {
        CLIENT.clear();
    }

    public static void put(final String dimension, final BlockPos signal, final BlockPos node) {
        CLIENT.computeIfAbsent(dimension, k -> new HashMap<>())
                .put(signal.toImmutable(), node.toImmutable());
    }

    public static void remove(final String dimension, final BlockPos signal) {
        final Map<BlockPos, BlockPos> map = CLIENT.get(dimension);
        if (map != null) {
            map.remove(signal);
        }
    }

    /**
     * @return 绑定到该信号灯的节点坐标；没有绑定（或者维度拿不到）返回 {@code null}，
     *         调用方应当退回 MTR 自己的位置扫描。
     */
    public static BlockPos nodeFor(final BlockView view, final BlockPos signal) {
        if (!(view instanceof World)) {
            return null;
        }
        final Map<BlockPos, BlockPos> map = CLIENT.get(((World) view).getRegistryKey().getValue().toString());
        if (map == null || map.isEmpty()) {
            return null;
        }
        return map.get(signal);
    }
}
