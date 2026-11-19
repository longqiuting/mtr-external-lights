// 厦门 PIDS 的挂杆：任意位置可放（不要求下面有方块），facing 面朝玩家。
package dev.js.mtrheadlight.xiamen.pids;

import mtr.block.BlockPoleCheckBase;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldView;

/**
 * 厦门 PIDS 的挂杆方块 {@code mtrlte:pids_xiamen_pole}（模型
 * {@code mtrlte:block/pids_xiamen_pole}：一根 x/z 都居中的 1×1 竖杆，通到方块顶面）。
 *
 * <h2>为什么继承了 MTR 的 {@code BlockPoleCheckBase}，又把它的两条规则都改掉</h2>
 * <p>父类原本只做「上面那格必须是本模组的 PIDS 或另一根挂杆」：
 * <pre>
 *   getPlacementState(ctx):
 *       stateBelow = world.getBlockState(pos.down());
 *       return isBlock(stateBelow.getBlock()) ? placeWithState(stateBelow) : null;   // null = 放不下
 *   placeWithState(stateBelow):
 *       defaultState.with(FACING, getStatePropertySafe(stateBelow, FACING));          // facing 跟着下面
 * </pre>
 * 需求变了：挂杆要<b>任意位置可放</b>（吊在半空、贴天花板、站在地上都行），朝向不再跟下面那格，
 * 而是<b>面朝玩家</b>。所以这里覆写：
 * <ul>
 *   <li>{@link #getPlacementState} —— {@code facing = getHorizontalPlayerFacing().getOpposite()}
 *       （朝北放 → {@code facing=SOUTH}，杆的正面朝玩家），位置就是 {@code ctx.getBlockPos()}
 *       那一格（原版按 {@code pos.offset(side)} 算好的、你点的那面方块的相邻格）；</li>
 *   <li>{@link #canPlaceAt} —— <b>恒真</b>：不需要支撑，{@code /setblock} 之类也放得下；</li>
 *   <li>父类的 {@code getStateForNeighborUpdate}（下面没了就掉）不再需要 —— 这里<b>没有重写它</b>，
 *       杆不会因为邻居被拆而消失（{@code canPlaceAt} 恒真也会让它失效）。</li>
 * </ul>
 * 留下继承只是为了「物品 tooltip 那套」还能用（{@link #getTooltipBlockText}）。
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

    /**
     * 物品 tooltip：父类那条 {@code tooltip.mtr.pole_placement}（「只能放在 X 上面」）<b>已经不成立</b>
     * —— 这块现在任意位置可放，所以换成本模组的一句实话，不让人以为还得找 PIDS 顶着。
     */
    @Override
    public void appendTooltip(final net.minecraft.item.ItemStack stack,
                              final net.minecraft.world.BlockView world,
                              final java.util.List<Text> tooltip,
                              final net.minecraft.client.item.TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.mtrlte.pids_xiamen_pole")
                .formatted(net.minecraft.util.Formatting.GRAY));
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(HorizontalFacingBlock.FACING);
    }

    /**
     * 放置：位置 = 你点的那面方块的相邻格（原版 {@code ctx.getBlockPos()}，等价 {@code pos.offset(side)}），
     * 朝向 = <b>面朝玩家</b>（{@code getHorizontalPlayerFacing().getOpposite()}）。
     *
     * <p>父类的实现要求「下面那格是 PIDS / 挂杆」并抄它的 {@code facing} —— 这里整条换掉。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        return getDefaultState().with(HorizontalFacingBlock.FACING,
                ctx.getHorizontalPlayerFacing().getOpposite());
    }

    /** 任意位置可放：不要求下面有 PIDS / 挂杆，半空里也放得下。 */
    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        return true;
    }
}
