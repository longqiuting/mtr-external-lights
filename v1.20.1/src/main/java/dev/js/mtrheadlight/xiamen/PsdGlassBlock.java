// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * MTR {@code mtr.block.BlockPSDAPGGlassBase} 的移植。
 *
 * <p>左右邻居都是同一种玻璃时把 {@code side} 算成 MIDDLE / LEFT / RIGHT / SINGLE，
 * 拿刷子右键可以手动重算（{@link #connectGlass}，逐行照抄 MTR）。
 */
public abstract class PsdGlassBlock extends PsdAPGBaseBlock {

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        return PsdProps.checkHoldingBrush(world, player, () -> {
            for (int y = -1; y <= 1; y++) {
                BlockState scanState = world.getBlockState(pos.up(y));
                if (!state.isOf(scanState.getBlock())) {
                    continue;
                }
                connectGlass(world, pos.up(y), scanState);
            }
        });
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, PsdProps.HALF, PsdProps.SIDE_EXTENDED);
    }

    /** MTR 的 {@code connectGlass}：按左右邻居改写三格的 side。 */
    private void connectGlass(World world, BlockPos pos, BlockState state) {
        Direction facing = PsdProps.prop(state, FACING);
        BlockPos leftPos = pos.offset(facing.rotateYCounterclockwise());
        BlockState leftState = world.getBlockState(leftPos);
        boolean leftValid = state.isOf(leftState.getBlock());
        if (leftValid) {
            PsdProps.Side side = PsdProps.prop(leftState, PsdProps.SIDE_EXTENDED);
            PsdProps.Side newLeftSide = side == PsdProps.Side.RIGHT ? PsdProps.Side.MIDDLE
                    : side == PsdProps.Side.SINGLE ? PsdProps.Side.LEFT : side;
            world.setBlockState(leftPos, leftState.with(PsdProps.SIDE_EXTENDED, newLeftSide));
        }

        BlockPos rightPos = pos.offset(facing.rotateYClockwise());
        BlockState rightState = world.getBlockState(rightPos);
        boolean rightValid = state.isOf(rightState.getBlock());
        if (rightValid) {
            PsdProps.Side side = PsdProps.prop(rightState, PsdProps.SIDE_EXTENDED);
            PsdProps.Side newRightSide = side == PsdProps.Side.LEFT ? PsdProps.Side.MIDDLE
                    : side == PsdProps.Side.SINGLE ? PsdProps.Side.RIGHT : side;
            world.setBlockState(rightPos, rightState.with(PsdProps.SIDE_EXTENDED, newRightSide));
        }

        PsdProps.Side newSide = leftValid && rightValid ? PsdProps.Side.MIDDLE
                : leftValid ? PsdProps.Side.RIGHT
                : rightValid ? PsdProps.Side.LEFT
                : PsdProps.Side.SINGLE;
        world.setBlockState(pos, state.with(PsdProps.SIDE_EXTENDED, newSide));
    }
}
