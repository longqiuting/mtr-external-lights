// 厦门 PIDS 的方块实体：沿用 MTR 的 IPIDS.TileEntityPIDS，只加「广告」这两个状态。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.Arrays;

import mtr.data.IPIDS;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

/**
 * 厦门 PIDS 的方块实体（地铁 / BRT 共用同一个 {@code BlockEntityType}）。
 *
 * <h2>为什么直接继承 {@code IPIDS.TileEntityPIDS}</h2>
 * <p>它已经把 PIDS 需要的全部数据与协议做完了：
 * <ul>
 *   <li>{@code messages[]} / {@code hideArrival[]} / {@code platformIds} / {@code displayPage}
 *       以及它们的 NBT 读写（{@code readCompoundTag} / {@code writeCompoundTag}）；</li>
 *   <li>{@code setData(...)} 里的 {@code markDirty()} + {@code syncData()} —— 本模组的
 *       {@code MtrTiles}（反射）与 {@code PidsStationResolver} 认的就是这个类名，
 *       所以 PIDS 连接器 / {@code /ltebind} 对这块屏<b>天然可用</b>，一行胶水都不用写。</li>
 * </ul>
 * 天津也是继承它（{@code TileEntityBlockPIDSTianjin extends IPIDS$TileEntityPIDS}）。
 *
 * <h2>广告状态（照天津的两个字段）</h2>
 * <ul>
 *   <li>{@link #getAdCategories()} —— 玩家在广告选择界面里<b>勾了哪些广告分类</b>
 *       （分类 id = 广告表顶层 key 的 {@code hashCode}，见 {@link PidsXiamenAds.Category#id()}）。
 *       空 = 没勾过 = 用默认分类 {@code mtrlte}；天津的 {@code categories} 就是这个语义，
 *       只是天津没勾就不显示广告，我们没勾就播默认那一套。</li>
 *   <li>{@link #getAdvertisementIndex()} —— 轮播时间线的<b>起点偏移</b>（落盘，跟着存档走）。</li>
 * </ul>
 * 轮播节奏是「当前这张广告的标语全部滚完 → 下一张」（学天津，见
 * {@code PidsXiamenRenderer#slideOf}），按世界时间无状态地算出来，不需要每 tick 同步。
 */
public class PidsXiamenBlockEntity extends IPIDS.TileEntityPIDS {

    private static final String ADVERTISEMENT_INDEX_TAG = "xiamen_advertisement_index";
    private static final String AD_CATEGORIES_TAG = "xiamen_ad_categories";

    /** 时间轮播的起播偏移（只影响从第几张开始转）。 */
    private int advertisementIndex;

    /** 勾选的广告分类 id；{@code null} / 空 = 默认分类。 */
    private long[] adCategories;

    /** 这块屏属于哪个版式（地铁 3 行 / BRT 2 行）。 */
    private final PidsXiamenBlock.Kind kind;

    public PidsXiamenBlockEntity(final BlockPos pos, final BlockState state) {
        super(PidsXiamenObjects.entityType(), pos, state);
        // 物品栏渲染会走 BlockItem → createBlockEntity(pos, null)（MTR 的
        // BlockPIDSBaseHorizontal 也照传），这里必须容忍 null，否则打开背包即崩。
        this.kind = state != null && state.getBlock() instanceof PidsXiamenBlock block
                ? block.kind() : PidsXiamenBlock.Kind.METRO;
    }

    @Override
    public int getMaxArrivals() {
        return PidsXiamenLayout.rowsOf(this.kind);
    }

    @Override
    public int getLinesPerArrival() {
        return PidsXiamenLayout.LINES_PER_ARRIVAL;
    }

    // ------------------------------------------------------------------ 广告

    public int getAdvertisementIndex() {
        return this.advertisementIndex;
    }

    public void setAdvertisementIndex(final int index) {
        this.advertisementIndex = Math.max(0, index);
        this.markDirty();
    }

    /** 勾选过的广告分类 id（可能为空 = 用默认分类）。 */
    public long[] getAdCategories() {
        final long[] value = this.adCategories;
        return value == null ? new long[0] : value.clone();
    }

    /** 这块屏此刻要播的广告（勾选的分类拼接；没勾就是默认分类）。 */
    public java.util.List<PidsXiamenAds.Ad> getAds() {
        return PidsXiamenAds.adsFor(this.adCategories);
    }

    /**
     * 勾选的分类；{@code null} / 空数组 = 回到默认分类。
     *
     * <p>顺序保留（玩家勾选的顺序 = 广告拼接顺序），重复的 id 去掉。
     */
    public void setAdCategories(final long[] ids) {
        if (ids == null || ids.length == 0) {
            this.adCategories = null;
        } else {
            final long[] clean = Arrays.stream(ids).distinct().toArray();
            this.adCategories = clean.length == 0 ? null : clean;
        }
        this.markDirty();
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void readCompoundTag(final NbtCompound nbt) {
        super.readCompoundTag(nbt);
        this.advertisementIndex = nbt.contains(ADVERTISEMENT_INDEX_TAG)
                ? nbt.getInt(ADVERTISEMENT_INDEX_TAG) : 0;
        this.adCategories = nbt.contains(AD_CATEGORIES_TAG)
                ? nbt.getLongArray(AD_CATEGORIES_TAG) : null;
    }

    @Override
    public void writeCompoundTag(final NbtCompound nbt) {
        super.writeCompoundTag(nbt);
        nbt.putInt(ADVERTISEMENT_INDEX_TAG, this.advertisementIndex);
        if (this.adCategories != null && this.adCategories.length > 0) {
            nbt.putLongArray(AD_CATEGORIES_TAG, this.adCategories);
        }
    }
}
