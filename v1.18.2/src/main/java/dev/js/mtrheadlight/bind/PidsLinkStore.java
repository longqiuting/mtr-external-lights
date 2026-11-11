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
 * PIDS → 站台 的关联表（每个维度一份，落盘 {@code world/data/mtrlte_pids_links.dat}）。
 *
 * <h2>MTR 的 PIDS 里有什么</h2>
 * <p>{@code mtr.data.IPIDS.TileEntityPIDS}（所有 PIDS 的共同父类，MTR 的
 * {@code BlockPIDSBaseHorizontal} / {@code BlockPIDSBaseVertical}、天津地铁的
 * {@code ziyue.tjmetro.block.BlockPIDSTianjin}、JCM 的 {@code com.jsblock.block.JobanPIDSBase}
 * 全部继承它）只存：
 * <pre>{@code
 * public final String[] messages;            // NBT "message0".."messageN"
 * private final boolean[] hideArrival;       // NBT "hide_arrival0"..
 * private final Set<Long> platformIds;       // NBT "platform_ids"，long[]
 * private int displayPage;                   // NBT "display_page"
 * }</pre>
 * 同样没有「车站名」字段：PIDS 显示哪几路车，完全由 platformIds 决定。
 * {@code setData(String[], boolean[], Set<Long>, int)} 是 public 的，所以我们把
 * platformIds 换成「该节点所属站台」，PIDS 立刻按那个站台出到站信息（这条路径和 MTR 自己的
 * PIDS 配置界面服务端做的是同一件事，见 {@code PacketTrainDataGuiServer#receivePIDSMessageC2S}）。
 *
 * <p>这张表记的是「我们绑的那一下选了哪个节点 / 哪个站台」，用于 {@code /ltebind pids} 查询，
 * 与信号表、投影机表都是独立的文件。
 */
public final class PidsLinkStore extends PersistentState {

    private static final String ID = "mtrlte_pids_links";
    private static final String KEY_LINKS = "links";
    private static final String KEY_POS = "pos";
    private static final String KEY_NODE = "node";
    private static final String KEY_PLATFORM = "platform";
    private static final String KEY_NAME = "name";

    /** PIDS pos → 绑定的节点 + 站台。 */
    private final Map<BlockPos, Link> links = new LinkedHashMap<>();

    /** 一条绑定：源节点、站台 id、站台名。 */
    public record Link(long node, long platformId, String platformName) {
        public BlockPos nodePos() {
            return BlockPos.fromLong(node);
        }
    }

    public static PidsLinkStore get(final ServerWorld world) {
        return world.getPersistentStateManager()
                .getOrCreate(PidsLinkStore::new, PidsLinkStore::new, ID);
    }

    public PidsLinkStore() {
    }

    public PidsLinkStore(final NbtCompound nbt) {
        final NbtList list = nbt.getList(KEY_LINKS, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            final NbtCompound entry = list.getCompound(i);
            links.put(BlockPos.fromLong(entry.getLong(KEY_POS)),
                    new Link(entry.getLong(KEY_NODE), entry.getLong(KEY_PLATFORM),
                            entry.getString(KEY_NAME)));
        }
    }

    @Override
    public NbtCompound writeNbt(final NbtCompound nbt) {
        final NbtList list = new NbtList();
        for (final Map.Entry<BlockPos, Link> entry : links.entrySet()) {
            final NbtCompound one = new NbtCompound();
            one.putLong(KEY_POS, entry.getKey().asLong());
            one.putLong(KEY_NODE, entry.getValue().node());
            one.putLong(KEY_PLATFORM, entry.getValue().platformId());
            one.putString(KEY_NAME, entry.getValue().platformName());
            list.add(one);
        }
        nbt.put(KEY_LINKS, list);
        return nbt;
    }

    public Link at(final BlockPos pids) {
        return links.get(pids);
    }

    public Map<BlockPos, Link> links() {
        return links;
    }

    public void put(final BlockPos pids, final BlockPos node, final long platformId,
                    final String platformName) {
        links.put(pids.toImmutable(),
                new Link(node.toImmutable().asLong(), platformId, platformName == null ? "" : platformName));
        markDirty();
    }

    public boolean remove(final BlockPos pids) {
        if (links.remove(pids) != null) {
            markDirty();
            return true;
        }
        return false;
    }

    /**
     * 把「以 {@code pos} 为屏」和「以 {@code pos} 为源节点」的记录都删掉。
     *
     * <p>两条路都要：屏被拆时 {@code pos} 是<b>键</b>；节点被拆时 {@code pos} 是记录里的
     * {@code node}（值）—— 只按键删的话，拆掉节点再原地放回来，这块屏会「自动继承」原来的站台
     * （现象和第 43 行那段注释里说的旧 bug 一样）。
     *
     * <p>只删与 {@code pos} 直接相关的条目，同一张表里其它屏的记录一条不动。
     *
     * @return 删掉的记录（键 = 屏的数据格），调用方据此清 MTR 里的 {@code platformIds}
     */
    public Map<BlockPos, Link> removeInvolving(final BlockPos pos) {
        final Map<BlockPos, Link> out = new LinkedHashMap<>();
        if (pos == null) {
            return out;
        }
        links.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(pos) || entry.getValue().nodePos().equals(pos)) {
                out.put(entry.getKey(), entry.getValue());
                return true;
            }
            return false;
        });
        if (!out.isEmpty()) {
            markDirty();
        }
        return out;
    }
}
