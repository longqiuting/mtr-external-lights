package dev.js.mtrheadlight.bind;

import dev.js.mtrheadlight.signal.SignalLinkNet;
import mtr.block.BlockNode;
import mtr.block.IBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「玩家右键的这一下，点到了哪个 MTR 物件」的判定，两个连接器共用。
 *
 * <h2>类名证据（反编译 MTR-fabric-1.20.1-3.6.4.2）</h2>
 * <table border="1">
 *   <caption>投影机与 PIDS 的真实类型</caption>
 *   <tr><th>物件</th><th>方块类</th><th>方块实体（真正存数据的地方）</th></tr>
 *   <tr><td>投影机</td>
 *       <td>{@code mtr.block.BlockArrivalProjectorBase}
 *           （子类 {@code BlockArrivalProjector1Small/Medium/Large}）</td>
 *       <td>{@code BlockArrivalProjectorBase$TileEntityArrivalProjectorBase}
 *           —— {@code Set<Long> platformIds} + {@code int displayPage}</td></tr>
 *   <tr><td>PIDS（MTR 横版）</td>
 *       <td>{@code mtr.block.BlockPIDSBaseHorizontal}（{@code BlockPIDS1/2/3}）</td>
 *       <td rowspan="3">{@code mtr.data.IPIDS$TileEntityPIDS}
 *           —— {@code String[] messages} + {@code boolean[] hideArrival} +
 *           {@code Set<Long> platformIds} + {@code int displayPage}</td></tr>
 *   <tr><td>PIDS（MTR 竖版）</td>
 *       <td>{@code mtr.block.BlockPIDSBaseVertical}（{@code BlockPIDS4}）</td></tr>
 *   <tr><td>PIDS（天津地铁 / JCM）</td>
 *       <td>{@code ziyue.tjmetro.block.BlockPIDSTianjin} /
 *           {@code com.jsblock.block.JobanPIDSBase}（都 extends
 *           {@code BlockPIDSBaseHorizontal}）</td></tr>
 * </table>
 *
 * <p>所以判定不看方块类名（第三方 PIDS 的类名不可枚举），只看<b>方块实体是不是
 * {@code mtr.data.IPIDS$TileEntityPIDS} 家族</b>（按运行时类名判定，原因见 {@link MtrTiles} 的类注释）。
 * 天津地铁的 {@code TileEntityBlockPIDSTianjin} 与 JCM 的 {@code TileEntityBlockJobanPIDS}
 * 都继承它，一条判定同时覆盖三家。
 *
 * <h2>只看「同一台屏」，不看邻居</h2>
 * <p>PIDS 都是多格方块：MTR 的上下两半（{@code IBlock.HALF}）、天津地铁按 {@code FACING}
 * 沿墙成对（{@code getBlockPosWithData} 给出数据那一格）。玩家可能点到任意一半，而显示真正读的是
 * <b>数据那一格</b>（{@code RenderPIDS} 只在 {@code UPPER} 渲染，天津的渲染器也直接
 * {@code return} 掉非数据格）。
 *
 * <p>但「同方块类」不等于「同一台屏」：两块独立 PIDS 挨着摆 / 上下叠时方块类完全一样。所以这里
 * <b>不</b>做 6 邻居扫描，只收「数据格 + 同一台屏的另一半（同方块且 HALF 相反）」，见
 * {@link #pidsAt} 与 {@link #dataPos}。绑定只写这一台屏，新放的屏永远是「没绑过」。
 */
public final class BindTargets {

    /** 找节点时允许的搜索半径（与 {@code SignalLinkItem} 一致：节点只有 1px 高，点歪是常态）。 */
    private static final int NODE_SEARCH = 3;

    private BindTargets() {
    }

    /** 这一格是不是 MTR 投影机（三个尺寸的方块实体都是同一个内部类）。 */
    public static boolean isProjector(final World world, final BlockPos pos) {
        return MtrTiles.isProjector(MtrTiles.tileAt(world, pos));
    }

    /**
     * 诊断用：这一格「被我们当成了什么」。日志里逐次打印，绑定失败时不用猜。
     *
     * @return {@code "projector"} / {@code "node"}（{@code mtr.block.BlockNode}）/ {@code "other"}
     */
    public static String kind(final World world, final BlockPos pos) {
        if (isProjector(world, pos)) {
            return "projector";
        }
        try {
            if (BlockNode.class.isInstance(world.getBlockState(pos).getBlock())) {
                return "node";
            }
        } catch (Throwable ignored) {
            // 读不到方块状态就当 other
        }
        return "other";
    }

    /** 投影机的方块实体；没有就 {@code null}。 */
    public static Object projectorAt(final World world, final BlockPos pos) {
        final Object tile = MtrTiles.tileAt(world, pos);
        return MtrTiles.isProjector(tile) ? tile : null;
    }

    /**
     * 这一格「属于哪一台 PIDS」以及它的数据格 —— 一台屏最多两格方块实体。
     *
     * <h2>为什么不能再按「同方块类的 6 邻居」收</h2>
     * <p>旧实现把点击格 + 6 个同方块类邻居全当成同一台屏写进去。MTR 的 PIDS 是「上下两半」，
     * 但 <b>两块独立的 PIDS 挨着摆 / 上下叠</b>时方块类完全相同，于是「绑一块」等于「绑一片」：
     * 玩家在已绑定的屏旁边新放一块，下一次绑定时新屏也被写进同一个站台，看起来就是
     * 「绑定一次之后，新放的屏自己就被绑到那个站台了」（写入端扩散，读的那一侧并没有任何缓存）。
     *
     * <p>现在只认<b>同一台屏</b>：
     * <ol>
     *   <li>数据格用方块自己给的答案 —— 天津地铁 / JCM 实现了
     *       {@code getBlockPosWithData(BlockView, BlockPos)}（JCM 的
     *       {@code JobanPIDSBase} 同形），反射问它；</li>
     *   <li>没有那个方法的（MTR 原版）按 {@code IBlock.HALF} 归一：{@code LOWER} 的数据格是上面那格
     *       （{@code RenderPIDS} 只在 {@code UPPER} 渲染，MTR 自己的 PIDS 界面也写 {@code pos.above()}）；</li>
     *   <li>另一格只在「同方块 + HALF 相反」时才是同一台屏的另一半，其它邻居一概不碰。</li>
     * </ol>
     *
     * @return 空的 = 这一下没点到 PIDS
     */
    public static List<MtrTiles.PidsTile> pidsAt(final World world, final BlockPos pos) {
        final List<MtrTiles.PidsTile> out = new ArrayList<>(2);
        final BlockPos data = dataPos(world, pos);
        if (!addPids(world, data, out)) {
            return Collections.emptyList();
        }
        // 另一格只可能是「数据格往另一半的方向那一格」：数据格是上半 → 下半在下面；下半 → 上半在上面。
        // 这样「两块独立的屏上下叠着摆」不会被当成一台（那两块的上/下半在几何上正好交叉）。
        final Direction towardsOther = halfOf(world, data) == DoubleBlockHalf.UPPER
                ? Direction.DOWN : Direction.UP;
        final BlockPos other = data.offset(towardsOther);
        if (world.getBlockState(other).getBlock() == world.getBlockState(data).getBlock()
                && isOtherHalf(world, data, other)) {
            addPids(world, other, out);
        }
        return out;
    }

    /** 这一格的 {@code HALF}；没有这个属性（单格屏）返回 {@code null}。 */
    private static DoubleBlockHalf halfOf(final World world, final BlockPos pos) {
        try {
            final BlockState state = world.getBlockState(pos);
            return state.contains(IBlock.HALF)
                    ? IBlock.getStatePropertySafe(state, IBlock.HALF) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 这一格背后的「数据格」：天津 / JCM 由方块自己的 {@code getBlockPosWithData} 决定，
     * MTR 原版按 {@code HALF} 把下半归到上半。都不适用就返回 {@code pos} 本身。
     */
    public static BlockPos dataPos(final World world, final BlockPos pos) {
        final BlockPos reflected = reflectedDataPos(world, pos);
        if (reflected != null) {
            return reflected;
        }
        try {
            final BlockState state = world.getBlockState(pos);
            if (state.contains(IBlock.HALF)
                    && IBlock.getStatePropertySafe(state, IBlock.HALF) == DoubleBlockHalf.LOWER) {
                final BlockPos above = pos.up();
                final BlockState upper = world.getBlockState(above);
                if (upper.getBlock() == state.getBlock()
                        && IBlock.getStatePropertySafe(upper, IBlock.HALF) == DoubleBlockHalf.UPPER) {
                    return above;
                }
            }
        } catch (Throwable ignored) {
            // 没有 HALF 属性 / 读方块状态失败：就当数据在这一格。
        }
        return pos;
    }

    /** 天津地铁 / JCM 的方块自己告诉我们数据在哪一格（{@code getBlockPosWithData(BlockView, BlockPos)}）。 */
    private static BlockPos reflectedDataPos(final World world, final BlockPos pos) {
        try {
            final Block block = world.getBlockState(pos).getBlock();
            final Method method = block.getClass()
                    .getMethod("getBlockPosWithData", BlockView.class, BlockPos.class);
            final Object value = method.invoke(block, world, pos);
            return value instanceof BlockPos found ? found.toImmutable() : null;
        } catch (Throwable ignored) {
            // 没有这个方法（MTR 原版），或者签名不同：交给 HALF 归一。
            return null;
        }
    }

    /** {@code a} / {@code b} 是不是同一台双格方块的上下两半。 */
    private static boolean isOtherHalf(final World world, final BlockPos a, final BlockPos b) {
        try {
            final BlockState first = world.getBlockState(a);
            final BlockState second = world.getBlockState(b);
            if (!first.contains(IBlock.HALF) || !second.contains(IBlock.HALF)) {
                // 单格屏（天津 pids_tianjin_single 之类）：没有「另一半」这回事。
                return false;
            }
            return IBlock.getStatePropertySafe(first, IBlock.HALF)
                    != IBlock.getStatePropertySafe(second, IBlock.HALF);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean addPids(final World world, final BlockPos pos,
                                   final List<MtrTiles.PidsTile> out) {
        final BlockEntity entity = world.getBlockEntity(pos);
        if (entity == null || !MtrTiles.isPids(entity)) {
            return false;
        }
        for (final MtrTiles.PidsTile existing : out) {
            if (existing.pos().equals(pos)) {
                return true;
            }
        }
        out.add(new MtrTiles.PidsTile(entity, pos.toImmutable()));
        return true;
    }

    /**
     * 目标方块本身就是「接了轨道的节点」就用它；否则在点击位置附近找最近的一个。
     *
     * <p>和 {@code SignalLinkItem#findNode} 同一套判定：{@code BlockNode} 的碰撞箱只有 1 像素高，
     * 弯道上的轨道还是画出来的、不是方块，玩家点到的往往是轨道下方的地面。
     */
    public static BlockPos findNode(final World world, final BlockPos clicked, final Vec3d hitPos) {
        if (BlockNode.class.isInstance(world.getBlockState(clicked).getBlock())
                && SignalLinkNet.hasRail(world, clicked)) {
            return clicked;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        final BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -NODE_SEARCH; dx <= NODE_SEARCH; dx++) {
            for (int dy = -NODE_SEARCH; dy <= NODE_SEARCH; dy++) {
                for (int dz = -NODE_SEARCH; dz <= NODE_SEARCH; dz++) {
                    cursor.set(clicked.getX() + dx, clicked.getY() + dy, clicked.getZ() + dz);
                    if (!BlockNode.class.isInstance(world.getBlockState(cursor).getBlock())) {
                        continue;
                    }
                    if (!SignalLinkNet.hasRail(world, cursor)) {
                        continue;
                    }
                    final double distance = cursor.getSquaredDistance(hitPos.x, hitPos.y, hitPos.z);
                    if (distance < bestDist) {
                        bestDist = distance;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return best;
    }
}
