// 挂墙发车倒数器的方块实体：一个纯粹的存在性标记，不存任何数据。
package dev.js.mtrheadlight.xiamen.dep;

import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * 挂墙发车倒数器 {@code mtrlte:dep_on_wall} 的方块实体。
 *
 * <h2>它只存一样东西：客户端自己锁定的那条班次</h2>
 * <p>{@code depLock} 是<b>纯客户端渲染态</b>：{@link DepOnWallRenderer} 在渲染线程上写，服务端一次都不碰，
 * 也<b>不进 NBT</b> —— 拆下来再放回去，下一帧会重新按当前 {@code SCHEDULES_FOR_PLATFORM} 选一条锁上。
 * 之所以放在方块实体上而不是渲染器里的静态表：它的生命周期天然跟着这块牌子（区块卸载 /
 * 方块被拆，状态一起没），不需要任何失效清理。
 *
 * <p>除此之外它什么都不存：
 * <ul>
 *   <li>没有 NBT：拆下来再放回去不需要记住任何东西；</li>
 *   <li>没有 {@code markDirty()} / {@code syncData()}：服务端一次都不碰它；</li>
 *   <li>没有 ticker：倒数是由 {@link System#currentTimeMillis()} 直接算出来的，
 *       不靠每 tick 累减（server 卡 / 客户端掉帧都不会让时间走歪）。</li>
 * </ul>
 *
 * <p>存在它的唯一理由：方块实体渲染器（BER）只能挂在方块实体类型上，
 * 而 {@code BlockEntityRenderer} 拿不到「没有方块实体的方块」。
 *
 * <p>继承 MTR 的 {@link BlockEntityMapper}（而不是 vanilla {@code BlockEntity}）是为了和
 * 本模组其它 MTR 方块保持一致：它把 1.20.1 的 {@code toInitialChunkDataNbt} /
 * {@code readNbt} 名字差异包了一层，{@code writeCompoundTag} 留空即可。
 */
public class DepOnWallBlockEntity extends BlockEntityMapper {

    /**
     * 这块牌子当前锁定的班次；{@code null} = 还没锁（下一帧重新选）。
     *
     * <p>只在渲染线程上读写，不落盘、不参与网络同步。
     */
    private DepOnWallLock depLock;

    public DepOnWallBlockEntity(final BlockPos pos, final BlockState state) {
        super(DepOnWallObjects.entityType(), pos, state);
    }

    /** 当前锁定的班次（{@code null} = 没有）。 */
    DepOnWallLock depLock() {
        return depLock;
    }

    /** 换一把锁；传 {@code null} 就是放锁。 */
    void setDepLock(final DepOnWallLock lock) {
        this.depLock = lock;
    }
}
