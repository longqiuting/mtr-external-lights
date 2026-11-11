package dev.js.mtrheadlight.signal;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 服务端权威的信号连接表（每个维度一份，落盘在 {@code world/data/mtrlte_signal_links.dat}）。
 *
 * <h2>两张表</h2>
 * <ul>
 *   <li>{@code links}：信号灯 pos → 轨道节点 pos —— 灯显示「这个节点出去的那一段」；</li>
 *   <li>{@code lampLinks}：信号灯 pos → 源信号灯 pos —— 后点的灯抄前点的灯的显示状态。</li>
 * </ul>
 *
 * <p>对同一盏灯两张表<b>互斥</b>：{@link #put} 会清掉它的 lamp 源，{@link #putLamp} 会清掉它的
 * 节点绑定。一盏灯只有一个「显示什么」的答案，留两条就变成谁先谁赢的隐式规则，调起来没人能看懂。
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
    private static final String KEY_LAMP_LINKS = "lamp_links";
    private static final String KEY_LAMP = "lamp";
    private static final String KEY_SOURCE = "source";

    /**
     * 灯链最长跳数。正常玩法里 2～3 跳就到头了；这个数字只是「数据坏了也不会死循环」的上限，
     * 客户端 {@code SignalLinks} 用的是同一个值。
     */
    public static final int MAX_HOPS = 64;

    /** 信号灯 pos -> 节点 pos。 */
    private final Map<BlockPos, BlockPos> links = new HashMap<>();

    /** 信号灯 pos -> 源信号灯 pos（后者显示前者的状态）。 */
    private final Map<BlockPos, BlockPos> lampLinks = new HashMap<>();

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
        final NbtList lamps = nbt.getList(KEY_LAMP_LINKS, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < lamps.size(); i++) {
            final NbtCompound entry = lamps.getCompound(i);
            lampLinks.put(BlockPos.fromLong(entry.getLong(KEY_LAMP)),
                    BlockPos.fromLong(entry.getLong(KEY_SOURCE)));
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

        final NbtList lamps = new NbtList();
        for (final Map.Entry<BlockPos, BlockPos> entry : lampLinks.entrySet()) {
            final NbtCompound one = new NbtCompound();
            one.putLong(KEY_LAMP, entry.getKey().asLong());
            one.putLong(KEY_SOURCE, entry.getValue().asLong());
            lamps.add(one);
        }
        nbt.put(KEY_LAMP_LINKS, lamps);
        return nbt;
    }

    // ---------------------------------------------------------------- 读

    public Map<BlockPos, BlockPos> links() {
        return links;
    }

    public Map<BlockPos, BlockPos> lampLinks() {
        return lampLinks;
    }

    public BlockPos nodeAt(final BlockPos signal) {
        return links.get(signal);
    }

    /** @return 直接源信号灯；没有（或者这盏灯不是 lamp 链接）返回 {@code null}。 */
    public BlockPos lampSourceAt(final BlockPos lamp) {
        return lampLinks.get(lamp);
    }

    public boolean hasAny(final BlockPos signal) {
        return links.containsKey(signal) || lampLinks.containsKey(signal);
    }

    // ---------------------------------------------------------------- 写

    /** 灯 → 节点。同时清掉这盏灯的 lamp 源（互斥，见类注释）。 */
    public void put(final BlockPos signal, final BlockPos node) {
        links.put(signal.toImmutable(), node.toImmutable());
        lampLinks.remove(signal);
        markDirty();
    }

    /**
     * 灯 → 灯：{@code lamp} 抄 {@code source} 的显示状态。
     *
     * @return {@code false} = 自指或会成环（{@code source} 顺着链已经指回 {@code lamp}），调用方
     *         应当拒绝这次绑定。成环检查在服务端做，客户端那边只是镜像，坏数据（比如旧存档手改）
     *         由 {@code SignalLinks#resolveSource} 的访问集兜底。
     */
    public boolean putLamp(final BlockPos lamp, final BlockPos source) {
        if (lamp.equals(source)) {
            return false;
        }
        if (reaches(source, lamp)) {
            return false;
        }
        lampLinks.put(lamp.toImmutable(), source.toImmutable());
        links.remove(lamp);
        markDirty();
        return true;
    }

    /** @return 这张表里有没有删掉东西（节点绑定或 lamp 绑定任一条就算）。 */
    public boolean remove(final BlockPos signal) {
        final BlockPos oldNode = links.remove(signal);
        final BlockPos oldLamp = lampLinks.remove(signal);
        if (oldNode != null || oldLamp != null) {
            markDirty();
            return true;
        }
        return false;
    }

    /**
     * 一次「解绑 / 方块被拆」涉及到的记录 —— 广播要给客户端逐条发，所以把键都带出来。
     *
     * <p>{@link #signals} 里的是要发 {@code REMOVE}（节点绑定没了）的灯，{@link #lamps} 里的是要发
     * {@code LAMP_REMOVE}（灯 → 灯 没了）的灯。同一盏灯可能两张都在（两条记录都删了）。
     */
    public static final class Removed {

        /** 要发 {@code REMOVE} 的灯（节点绑定被删）。 */
        public final Set<BlockPos> signals = new LinkedHashSet<>();

        /** 要发 {@code LAMP_REMOVE} 的灯（灯 → 源灯 被删）。 */
        public final Set<BlockPos> lamps = new LinkedHashSet<>();

        public boolean any() {
            return !signals.isEmpty() || !lamps.isEmpty();
        }

        public int count() {
            return signals.size() + lamps.size();
        }
    }

    /**
     * 把「{@code pos} 自己的绑定」和「以 {@code pos} 为另一端的所有绑定」删掉（两张表都清）。
     *
     * <h2>为什么必须按「两端」删</h2>
     * <p>这张表是 {@code 灯 pos → 节点 pos}：拆掉<b>灯</b>时键就是它自己，{@link #remove} 够用；
     * 但拆掉<b>节点</b>时它是<b>值</b>，键是别人的灯 —— 只按键删就一个都删不掉。留着这条记录，
     * 玩家在原地再放一个节点，那盏灯会「自动继承」回原来的绑定（拆了再放回来绑定还在）。
     *
     * <p>「灯 → 灯」同理：源灯被拆时它是别人 {@code lampLinks} 里的<b>值</b>，不删就是一条永远指向
     * 空气的记录，重新放下同位置的一盏灯就又「继承」了。
     *
     * <p><b>只删与 {@code pos} 直接相关的条目</b>：同一张表里其它灯、其它节点的记录一条都不动
     * （同车 / 同站 / 相邻灯都不受影响）。
     *
     * @return 删掉了什么（调用方据此逐条广播）
     */
    public Removed removeInvolving(final BlockPos pos) {
        final Removed out = new Removed();
        if (pos == null) {
            return out;
        }
        links.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(pos) || entry.getValue().equals(pos)) {
                out.signals.add(entry.getKey());
                return true;
            }
            return false;
        });
        lampLinks.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(pos) || entry.getValue().equals(pos)) {
                out.lamps.add(entry.getKey());
                return true;
            }
            return false;
        });
        if (out.any()) {
            markDirty();
        }
        return out;
    }

    /** {@code from} 顺着 lamp 链向上找，途中是否经过 {@code target}。 */
    private boolean reaches(final BlockPos from, final BlockPos target) {
        BlockPos cursor = from;
        for (int hop = 0; hop < MAX_HOPS && cursor != null; hop++) {
            if (cursor.equals(target)) {
                return true;
            }
            cursor = lampLinks.get(cursor);
        }
        return false;
    }
}
