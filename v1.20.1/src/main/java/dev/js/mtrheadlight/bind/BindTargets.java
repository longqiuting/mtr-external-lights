package dev.js.mtrheadlight.bind;

import dev.js.mtrheadlight.signal.SignalLinkNet;
import mtr.block.BlockNode;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

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
 * <h2>为什么看邻居</h2>
 * MTR 的 PIDS 都是 2 格：横版沿 facing 方向成对，竖版的上下两半（{@code HALF} 属性）各有一个
 * 方块实体。玩家可能点到任意一半，而配置界面 / 显示真正读的是「另一半」或「上一半」
 * （{@code BlockPIDSBaseVertical#onUse} 里 {@code isUpper ? pos : pos.above()}）。
 * 这里把「同一方块类的 6 邻居」都收进来，一次写全，两边不会不同步。
 */
public final class BindTargets {

    /** 找节点时允许的搜索半径（与 {@code SignalLinkItem} 一致：节点只有 1px 高，点歪是常态）。 */
    private static final int NODE_SEARCH = 3;

    private static final Direction[] NEIGHBOURS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST,
            Direction.WEST, Direction.UP, Direction.DOWN
    };

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
     * 这一格（或同一台 PIDS 的另一半）上的所有 PIDS 方块实体。
     *
     * @return 空的 = 这一下没点到 PIDS
     */
    public static List<MtrTiles.PidsTile> pidsAt(final World world, final BlockPos pos) {
        final Block block = world.getBlockState(pos).getBlock();
        final List<MtrTiles.PidsTile> out = new ArrayList<>(2);
        addPids(world, pos, block, out);
        for (final Direction direction : NEIGHBOURS) {
            addPids(world, pos.offset(direction), block, out);
        }
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    private static void addPids(final World world, final BlockPos pos, final Block block,
                                final List<MtrTiles.PidsTile> out) {
        if (world.getBlockState(pos).getBlock() != block) {
            return;
        }
        final BlockEntity entity = world.getBlockEntity(pos);
        if (entity == null || !MtrTiles.isPids(entity)) {
            return;
        }
        for (final MtrTiles.PidsTile existing : out) {
            if (existing.pos().equals(pos)) {
                return;
            }
        }
        out.add(new MtrTiles.PidsTile(entity, pos.toImmutable()));
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
