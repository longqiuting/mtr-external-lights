package dev.js.mtrheadlight.signal;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端权威的「信号灯方块 → 轨道节点」绑定表（每个维度一份，落盘在
 * {@code world/data/mtrlte_signal_links.dat}）。
 *
 * <h2>为什么不写进 MTR 的 {@code RailwayData}</h2>
 * {@code RailwayData} 是 {@code PersistentStateMapper}，存档格式是 msgpack
 * （{@code KEY_RAW_MESSAGE_PACK}），它的 {@code KEY_*} 全是给 stations/platforms/rails/
 * signalBlocks 用的私有常量，没有留给第三方挂数据的口子。硬塞进去要么 mixin 它的
 * {@code writeNbt/load}，要么自己造 msgpack 字段 —— 都比「自己一份 PersistentState」更容易跟着
 * MTR 更新一起坏。绑定表和 MTR 的轨道数据没有外键，只是「信号灯 pos → 节点 pos」，独立存即可。
 *
 * <p>校验：读取时不做（节点可能在被绑定时合法、之后被拆），真正的校验在
 * {@link SignalLinkItem} 绑定那一刻，以及客户端渲染时（节点不存在 → MTR 自己算不出 aspect，
 * 信号灯就保持灭灯，不会崩）。
 */
public final class SignalLinkStore extends PersistentState {

    private static final String ID = "mtrlte_signal_links";
    private static final String KEY_LINKS = "links";
    private static final String KEY_SIGNAL = "signal";
    private static final String KEY_NODE = "node";

    /** 信号灯 pos -> 节点 pos。 */
    private final Map<BlockPos, BlockPos> links = new HashMap<>();

    public static SignalLinkStore get(final ServerWorld world) {
        return world.getPersistentStateManager()
                .getOrCreate(SignalLinkStore::new, SignalLinkStore::new, ID);
    }

    public SignalLinkStore() {
    }

    public SignalLinkStore(final NbtCompound nbt) {
        final NbtList list = nbt.getList(KEY_LINKS, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            final NbtCompound entry = list.getCompound(i);
            links.put(BlockPos.fromLong(entry.getLong(KEY_SIGNAL)),
                    BlockPos.fromLong(entry.getLong(KEY_NODE)));
        }
    }

    @Override
    public NbtCompound writeNbt(final NbtCompound nbt) {
        final NbtList list = new NbtList();
        for (final Map.Entry<BlockPos, BlockPos> entry : links.entrySet()) {
            final NbtCompound one = new NbtCompound();
            one.putLong(KEY_SIGNAL, entry.getKey().asLong());
            one.putLong(KEY_NODE, entry.getValue().asLong());
            list.add(one);
        }
        nbt.put(KEY_LINKS, list);
        return nbt;
    }

    public Map<BlockPos, BlockPos> links() {
        return links;
    }

    public BlockPos nodeAt(final BlockPos signal) {
        return links.get(signal);
    }

    public void put(final BlockPos signal, final BlockPos node) {
        links.put(signal.toImmutable(), node.toImmutable());
        markDirty();
    }

    public BlockPos remove(final BlockPos signal) {
        final BlockPos old = links.remove(signal);
        if (old != null) {
            markDirty();
        }
        return old;
    }
}
