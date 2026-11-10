// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * MTR {@code mtr.block.BlockDirectionalDoubleBlockBase} 的移植 —— 有 facing、上下两段的方块底座。
 *
 * <p>行为逐条照抄：
 * <ul>
 *   <li>{@code getStateForNeighborUpdate}：上段看下面、下段看上面，对面没了就变空气（自毁）；</li>
 *   <li>{@code onPlaced}：放下段时把上段一起补上；</li>
 *   <li>{@code getPlacementState}：上面一格可替换才允许放；</li>
 *   <li>{@code onBreak}：打掉上段时顺手清掉下段（创造模式）；</li>
 *   <li>{@code rotate} / {@code mirror}：原样返回（MTR 也不跟着转）。</li>
 * </ul>
 */
public abstract class PsdDoubleBlock extends HorizontalFacingBlock {

    protected PsdDoubleBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        boolean isTop = PsdProps.prop(state, PsdProps.HALF) == DoubleBlockHalf.UPPER;
        if ((isTop && direction == Direction.DOWN || !isTop && direction == Direction.UP)
                && !neighborState.isOf(this)) {
            return Blocks.AIR.getDefaultState();
        }
        return state;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        if (!world.isClient) {
            Direction facing = PsdProps.prop(state, FACING);
            world.setBlockState(pos.up(),
                    this.getAdditionalState(pos, facing)
                            .with(FACING, facing)
                            .with(PsdProps.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
            world.updateNeighborsAlways(pos, this);
            state.updateNeighbors(world, pos, Block.NOTIFY_ALL);
        }
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction facing = ctx.getHorizontalPlayerFacing();
        if (!PsdProps.isReplaceable(ctx, Direction.UP, 2)) {
            return null;
        }
        return this.getAdditionalState(ctx.getBlockPos(), facing)
                .with(FACING, facing)
                .with(PsdProps.HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (PsdProps.prop(state, PsdProps.HALF) == DoubleBlockHalf.UPPER) {
            PsdProps.onBreakCreative(world, player, pos.down());
        }
        super.onBreak(world, pos, state, player);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state;
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state;
    }

    /** 子类可以在这里补上自己的属性（MTR 的同名钩子）。 */
    protected BlockState getAdditionalState(BlockPos pos, Direction facing) {
        return this.getDefaultState();
    }
}
