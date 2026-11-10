package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;

/**
 * 玻璃板 —— MTR 的 {@code mtr:block/BlockPSDGlass}（{@code extends BlockPSDAPGGlassBase}）。
 *
 * <p>两格高（{@code half=lower} 在 y+0、{@code half=upper} 在 y+1），顶上 y+2 是吊板。
 * 属性只有 {@code side}（{@link XiamenPsdBaseBlock#SIDE_EXTENDED}，LEFT / RIGHT / MIDDLE / SINGLE）：
 * 它描述「这一块是一段玻璃里的第几块」，MTR 在 {@code BlockPSDAPGGlassBase#connectGlass} 里
 * 靠邻居互相修正（RIGHT→MIDDLE、SINGLE→LEFT …），收敛成 LEFT … MIDDLE … RIGHT / SINGLE 的链。
 * 这里改成每块玻璃自己按左右邻居算一遍（结果相同，但不用去改邻居的方块状态）。
 *
 * <p>贴图：下段 {@code xiamen_psd_glass_bottom}、上段 {@code xiamen_psd_glass}
 * （对应 MTR 的 {@code psd_glass_bottom_1} / {@code psd_glass_top_1}，见
 * {@code psd_glass_bottom_left_1.json} 与 {@code psd_glass_top_left_1.json} 这两个 model）。
 */
public class XiamenPsdGlassBlock extends XiamenPsdBaseBlock {

    public XiamenPsdGlassBlock(AbstractBlock.Settings settings) {
        // 基准系（facing=west）里板子在 x∈[2,4]：MTR 的 psd_glass_left_base 就是 from [2,0,0] to [4,16,16]
        super(settings, 2.0D, 4.0D);
        this.setDefaultState(this.getDefaultState().with(SIDE_EXTENDED, EnumSide.SINGLE));
    }

    @Override
    public Kind kind() {
        return Kind.GLASS;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, SIDE_EXTENDED);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        final EnumSide side = computeSide(world, pos, state.get(FACING));
        return state.get(SIDE_EXTENDED) == side ? state : state.with(SIDE_EXTENDED, side);
    }

    /**
     * 沿墙方向（facing 的顺 / 逆时针）看邻居，算出这一段玻璃里的位置：
     * 两头都是玻璃 → MIDDLE；只有「前一块」→ RIGHT；只有「后一块」→ LEFT；都没有 → SINGLE。
     */
    public static EnumSide computeSide(net.minecraft.world.BlockView world, BlockPos pos, Direction facing) {
        final boolean prev = world.getBlockState(pos.offset(facing.rotateYClockwise())).getBlock()
                instanceof XiamenPsdGlassBlock;
        final boolean next = world.getBlockState(pos.offset(facing.rotateYCounterclockwise())).getBlock()
                instanceof XiamenPsdGlassBlock;
        if (prev && next) {
            return EnumSide.MIDDLE;
        }
        if (prev) {
            return EnumSide.RIGHT;
        }
        return next ? EnumSide.LEFT : EnumSide.SINGLE;
    }
}
