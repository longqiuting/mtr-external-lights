package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;

/**
 * 端部玻璃 —— MTR 的 {@code mtr:block/BlockPSDGlassEnd}
 * （{@code extends BlockPSDAPGGlassEndBase extends BlockPSDAPGGlassBase}）。
 *
 * <p>两块 {@code TOUCHING_LEFT} / {@code TOUCHING_RIGHT}（值域就是 MTR 的
 * {@code EnumPSDAPGGlassEndSide}：{@code AIR} / {@code DOOR} / {@code NONE}）标记
 * 「沿墙左右两边挨着什么」：
 * <ul>
 *   <li>{@code DOOR} —— 挨着门扇（{@code BlockPSDAPGDoorBase}）；</li>
 *   <li>{@code NONE} —— 挨着别的屏蔽门板（玻璃 / 端部，{@code BlockPSDAPGBase}）；</li>
 *   <li>{@code AIR} —— 什么都没有。</li>
 * </ul>
 * MTR 在 {@code getSideEnd(world, pos, direction)} 里就是这么判的（先
 * {@code instanceof BlockPSDAPGDoorBase} → DOOR，再 {@code instanceof BlockPSDAPGBase} → NONE，
 * 否则 AIR），然后在 {@code getOutlineShape} 里把 {@code AIR} 的一侧用
 * {@code getEndOutlineShape} 补一块 4px 的转角。本模组照抄了属性与判定，
 * <b>没有</b>补那块转角（纯 1~4px 的美术细节，且 MTR 的算法是往形状里 combine，
 * 抄错会挡住走路）。
 *
 * <p>端部同样是两格高，顶上 y+2 是吊板；门扇靠它决定 {@code end=true}（见 {@link XiamenPsdDoorBlock}）。
 */
public class XiamenPsdEndBlock extends XiamenPsdBaseBlock {

    public XiamenPsdEndBlock(AbstractBlock.Settings settings) {
        super(settings, 2.0D, 4.0D);
        this.setDefaultState(this.getDefaultState()
                .with(TOUCHING_LEFT, EndSide.AIR)
                .with(TOUCHING_RIGHT, EndSide.AIR));
    }

    @Override
    public Kind kind() {
        return Kind.END;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, TOUCHING_LEFT, TOUCHING_RIGHT);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        final Direction facing = state.get(FACING);
        final EndSide left = sideEnd(world, pos.offset(facing.rotateYCounterclockwise()));
        final EndSide right = sideEnd(world, pos.offset(facing.rotateYClockwise()));
        if (state.get(TOUCHING_LEFT) == left && state.get(TOUCHING_RIGHT) == right) {
            return state;
        }
        return state.with(TOUCHING_LEFT, left).with(TOUCHING_RIGHT, right);
    }

    /** MTR 的 {@code getSideEnd(pos, direction)}。 */
    public static EndSide sideEnd(net.minecraft.world.BlockView world, BlockPos pos) {
        final Block block = world.getBlockState(pos).getBlock();
        if (block instanceof XiamenPsdDoorBlock) {
            return EndSide.DOOR;
        }
        return block instanceof XiamenPsdBaseBlock ? EndSide.NONE : EndSide.AIR;
    }
}
