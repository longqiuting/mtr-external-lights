// 厦门 PIDS 的挂杆：只能放在 PIDS（或另一根挂杆）上面，facing 自动跟下面那格一致。
package dev.js.mtrheadlight.xiamen.pids;

import mtr.block.BlockPoleCheckBase;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * 厦门 PIDS 的挂杆方块 {@code mtrlte:pids_xiamen_pole}（模型
 * {@code mtrlte:block/pids_xiamen_pole}：一根 x/z 都居中的 1×1 竖杆，通到方块顶面）。
 *
 * <h2>为什么直接继承 MTR 的 {@code BlockPoleCheckBase}</h2>
 * <p>它已经把「只能放在某个方块上面」这件事做完了 —— 字节码证据
 * （{@code mtr/block/BlockPoleCheckBase}）：
 * <pre>
 *   getPlacementState(ctx):
 *       stateBelow = world.getBlockState(pos.down());
 *       return isBlock(stateBelow.getBlock()) ? placeWithState(stateBelow) : null;   // null = 放不下
 *   placeWithState(stateBelow):
 *       defaultState.with(FACING, getStatePropertySafe(stateBelow, FACING));          // facing 跟着下面
 *   appendTooltip: tooltip.mtr.pole_placement + getTooltipBlockText()
 * </pre>
 * MTR 自己的 {@code pids_pole} 就是这么写的（{@code isBlock} 允许
 * {@code BlockPIDSBaseHorizontal} 和它自己，所以能一段段往上叠）。
 *
 * <h2>这里额外加的两条</h2>
 * <ul>
 *   <li>{@link #canPlaceAt}：{@code getPlacementState} 只挡住「物品右键」这条路，
 *       {@code /setblock} 之类还会绕过，所以再补一个（原来的 {@code BlockPIDSBaseHorizontal}
 *       靠 {@code isReplaceable} 挡，也是同样的道理）；</li>
 *   <li>{@link #getStateForNeighborUpdate}：下面那格 PIDS 被拆掉时挂杆一起掉，
 *       不然会浮在空中。</li>
 * </ul>
 */
public class PidsXiamenPoleBlock extends BlockPoleCheckBase {

    /** 竖杆的**碰撞**轮廓：x/z 都是 7.5~8.5（模型里就是居中那一根），通高。 */
    private static final VoxelShape COLLISION = Block.createCuboidShape(7.5, 0.0, 7.5, 8.5, 16.0, 8.5);

    /**
     * 竖杆的**框选**轮廓：比碰撞大一圈（6~10 而不是 7.5~8.5）。
     *
     * <p>杆只有 1/16 格粗，正对着才好点中，是这类模组的通病。这里只放大框选
     * （射线检测走 {@code getOutlineShape}），碰撞仍按真实细杆，不影响走过去。</p>
     */
    private static final VoxelShape OUTLINE = Block.createCuboidShape(6.0, 0.0, 6.0, 10.0, 16.0, 10.0);

    public PidsXiamenPoleBlock(final AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        // 杆是居中的，旋转不改变形状（放大后的框选也一样）。
        return OUTLINE;
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return COLLISION;
    }

    /** 下面那格必须是我们自己的 PIDS（或者另一根挂杆，允许往上叠）。 */
    @Override
    protected boolean isBlock(final Block block) {
        return block instanceof PidsXiamenBlock || block instanceof PidsXiamenPoleBlock;
    }

    @Override
    protected Text getTooltipBlockText() {
        return Text.translatable("block.mtrlte.pids_xiamen_pole");
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(HorizontalFacingBlock.FACING);
    }

    /** 下面不是 PIDS / 挂杆 → 不许放（{@code /setblock} 也会被挡）。 */
    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        return isBlock(world.getBlockState(pos.down()).getBlock());
    }

    /** 下面的 PIDS 被拆掉 → 挂杆跟着掉（不然浮空）。 */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        if (direction == Direction.DOWN && !isBlock(neighborState.getBlock())) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }
}
