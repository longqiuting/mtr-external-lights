package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 顶层吊板 —— MTR {@code mtr:block/BlockPSDTop} 的厦门版，y+2 那一格。
 *
 * <h2>为什么它是独立的一个方块</h2>
 * <p>javap {@code ItemPSDAPGBase#useOnBlock} 的循环只跑 {@code dh = 0..1}（两格，
 * 对应 {@code HALF} 的 LOWER / UPPER），顶上那一格是循环结束后另写的一句：
 * <pre>
 * if (type.isPSD) world.setBlockState(pos.up(2), BlockPSDTop.getActualState(world, pos.up(2)));
 * </pre>
 * 也就是说吊板不属于 HALF 那套上下段，MTR 用一个独立的方块（带自己的
 * {@code PERSISTENT} / {@code AIR_LEFT} / {@code AIR_RIGHT} / {@code ARROW_DIRECTION}
 * 属性和 {@code TileEntityRouteBase}）来表示它。这里保留「独立方块」这件事，
 * 但把那些站牌 / 线路图的属性去掉 —— 厦门这套只用静态吊板。
 *
 * <p>形状：基准系（facing=west）x∈[0,6]，比板子（x∈[2,4]）厚，前后各凸出 2px，
 * 看起来是一根压在门顶上的梁（MTR 的 {@code psd_top_left_base} 也是 {@code from [0,1,8] to [6,16,16]}，
 * 一样是 6px 深）。
 */
public class XiamenPsdTopBlock extends HorizontalFacingBlock {

    private static final VoxelShape[] SHAPES = {
            createCuboidShape(0.0D, 0.0D, 0.0D, 6.0D, 16.0D, 16.0D),        // west  (y=0)
            createCuboidShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 6.0D),        // north (y=90)
            createCuboidShape(10.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),      // east  (y=180)
            createCuboidShape(0.0D, 0.0D, 10.0D, 16.0D, 16.0D, 16.0D)       // south (y=270)
    };

    public XiamenPsdTopBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[XiamenPsdBaseBlock.facingIndex(state.get(FACING))];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[XiamenPsdBaseBlock.facingIndex(state.get(FACING))];
    }

    /**
     * 吊板没有物品形态（不在创造页签里，也没有 {@code BlockItem}），所以打掉它的时候
     * 手动把下面那一段的物品掉出来 —— 否则从顶上拆屏蔽门的玩家会白丢一格。
     */
    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            final boolean creative = player != null && player.getAbilities().creativeMode;
            final BlockState below = world.getBlockState(pos.down());
            if (!creative && below.getBlock() instanceof XiamenPsdBaseBlock) {
                final Item item = below.getBlock().asItem();
                if (item != Items.AIR) {
                    Block.dropStack(world, pos, new ItemStack(item));
                }
            }
            XiamenPsdBaseBlock.breakStructure(world, pos, state);
        }
        super.onBreak(world, pos, state, player);
    }
}
