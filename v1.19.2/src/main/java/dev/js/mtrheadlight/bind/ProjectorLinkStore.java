package dev.js.mtrheadlight.bind;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 投影机 → 车站 的关联表（每个维度一份，落盘 {@code world/data/mtrlte_projector_links.dat}）。
 *
 * <h2>它和 MTR 自己的数据是什么关系</h2>
 * <p>MTR 的投影机（{@code mtr.block.BlockArrivalProjectorBase}）里<b>根本没有 stationName</b>：
 * 它的 {@code TileEntityArrivalProjectorBase} 只有两个字段 ——
 * <pre>{@code
 * private final Set<Long> platformIds;   // NBT "platform_ids"，long[]
 * private int displayPage;               // NBT "display_page"
 * }</pre>
 * 「这台投影机属于哪个车站」在 MTR 里是<b>由 platformIds 反推</b>的（站台 → 车站走
 * {@code DataCache.platformIdToStation}）。所以「绑定到车站」这个动作是：
 * 把该车站名下所有站台的 id 写进 {@code setData(platformIds, displayPage)}。
 * 这一步是真的写进了 MTR 的方块实体，投影出来的到站信息随之改属该车站 —— 不需要 mixin
 * 去顶渲染。
 *
 * <p>那这张表还留着干什么？两个用途：
 * <ul>
 *   <li>MTR 只记 platformIds，「当初绑的是哪个车站」不可逆（两个车站共用站台时会混淆），
 *       所以我们自己记一份 pos → stationId，用于 {@code /ltebind projector} 查询；</li>
 *   <li>车站范围被改小、站台被删之后，platformIds 会变空，这张表能说明「原来绑的是这儿」。</li>
 * </ul>
 *
 * <p>与信号表（{@link dev.js.mtrheadlight.signal.SignalLinkStore}）完全独立：不同 id、不同文件、
 * 不同 PersistentState 类，互不影响。
 */
public final class ProjectorLinkStore extends PersistentState {

    private static final String ID = "mtrlte_projector_links";
    private static final String KEY_LINKS = "links";
    private static final String KEY_POS = "pos";
    private static final String KEY_STATION = "station";
    private static final String KEY_NAME = "name";
    private static final String KEY_PLATFORMS = "platforms";

    /** 投影机 pos → 绑定的车站。 */
    private final Map<BlockPos, Link> links = new LinkedHashMap<>();

    /** 一条绑定：车站 id、车站名、绑定时写进 MTR 的站台数量。 */
    public record Link(long stationId, String stationName, int platformCount) {
    }

    public static ProjectorLinkStore get(final ServerWorld world) {
        return world.getPersistentStateManager()
                .getOrCreate(ProjectorLinkStore::new, ProjectorLinkStore::new, ID);
    }

    public ProjectorLinkStore() {
    }

    public ProjectorLinkStore(final NbtCompound nbt) {
        final NbtList list = nbt.getList(KEY_LINKS, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            final NbtCompound entry = list.getCompound(i);
            final BlockPos pos = BlockPos.fromLong(entry.getLong(KEY_POS));
            links.put(pos, new Link(entry.getLong(KEY_STATION),
                    entry.getString(KEY_NAME), entry.getInt(KEY_PLATFORMS)));
        }
    }

    @Override
    public NbtCompound writeNbt(final NbtCompound nbt) {
        final NbtList list = new NbtList();
        for (final Map.Entry<BlockPos, Link> entry : links.entrySet()) {
            final NbtCompound one = new NbtCompound();
            one.putLong(KEY_POS, entry.getKey().asLong());
            one.putLong(KEY_STATION, entry.getValue().stationId());
            one.putString(KEY_NAME, entry.getValue().stationName());
            one.putInt(KEY_PLATFORMS, entry.getValue().platformCount());
            list.add(one);
        }
        nbt.put(KEY_LINKS, list);
        return nbt;
    }

    public Link at(final BlockPos projector) {
        return links.get(projector);
    }

    public Map<BlockPos, Link> links() {
        return links;
    }

    public void put(final BlockPos projector, final long stationId, final String stationName,
                    final int platformCount) {
        links.put(projector.toImmutable(),
                new Link(stationId, stationName == null ? "" : stationName, platformCount));
        markDirty();
    }

    public boolean remove(final BlockPos projector) {
        if (links.remove(projector) != null) {
            markDirty();
            return true;
        }
        return false;
    }

    /**
     * 删掉这一格投影机的记录（投影机只有自己一头，没有「另一端」）。
     *
     * <p>留着的话，拆掉投影机再原地放一台新的，{@code /ltebind projector} 会报出上一台绑的车站
     * ——「新投影机自动继承了旧绑定」就是这么来的。
     *
     * @return 是否删掉了东西
     */
    public boolean removeInvolving(final BlockPos pos) {
        return remove(pos);
    }
}
