// 挂顶发车倒数器的方块实体：同样只是一个「挂锁」的存在性标记，不存任何数据。
package dev.js.mtrheadlight.xiamen.dep;

import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * 挂顶发车倒数器 {@code mtrlte:dep_on_roof} 的方块实体。
 *
 * <p>和挂墙版的 {@link DepOnWallBlockEntity} 一模一样：只放一把
 * {@link DepOnWallLock}（纯客户端渲染态，不进 NBT、不参与网络同步），
 * 存在的唯一理由同样是「{@code BlockEntityRenderer} 只能挂在方块实体类型上」。
 * 读数链路在 {@link DepCountdown}，两边共用。
 *
 * <p>继承 MTR 的 {@link BlockEntityMapper}（而不是 vanilla {@code BlockEntity}）是为了和
 * 本模组其它 MTR 方块保持一致：它把 1.20.1 的 {@code toInitialChunkDataNbt} /
 * {@code readNbt} 名字差异包了一层，{@code writeCompoundTag} 留空即可。
 */
public class DepOnRoofBlockEntity extends BlockEntityMapper implements DepCountdown.Holder {

    /** 这块牌子当前锁定的班次；{@code null} = 还没锁（下一帧重新选）。 */
    private DepOnWallLock depLock;

    public DepOnRoofBlockEntity(final BlockPos pos, final BlockState state) {
        super(DepOnRoofObjects.entityType(), pos, state);
    }

    @Override
    public DepOnWallLock depLock() {
        return depLock;
    }

    @Override
    public void setDepLock(final DepOnWallLock lock) {
        this.depLock = lock;
    }

    /**
     * 挂顶版专用的搜索范围（{@link DepCountdown#ROOF_SEARCH}，{@code 7 / 4 / 5}）。
     *
     * <p>吊在天花板上时方块比站台面高好几格，用挂墙版的 {@code 5 / 3 / 3} 经常<b>整个站台都落在
     * 垂直余量之外</b> —— 屏幕永远是空的。所以这里把半径、x/z 外扩各放宽一点、垂直多给 2 格。
     */
    @Override
    public DepCountdown.Search search() {
        return DepCountdown.ROOF_SEARCH;
    }
}
