package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * 厦门地铁风格的站台屏蔽门（1 格高、2px 厚的竖直板）。
 *
 * <h2>为什么长这样</h2>
 * <p>贴图来自玩家资源包 {@code 厦门地铁一号线车辆} 里改 MTR 屏蔽门用的那一套
 * （{@code psd_door_top_1} / {@code psd_glass_top_1} 及其 back 面）。那张贴图是一整块
 * 16×16 UV、中间大面积全透明（玻璃）的板面图，只有边框、上方的橙色告示条和下方的
 * 厦门 pavilion（会展中心/翔安风格屋顶）logo 是不透明的。
 *
 * <p>所以方块本体就是一块 <b>2px 厚、满高</b> 的板：几何形状和 MTR 自己的
 * {@code mtr:block/psd_glass_left_base} 完全一致（模型直接以它为 parent），
 * 碰撞箱也照着这块板给，不会占满一整格。
 *
 * <p>朝向：与 MTR 的 {@code psd_glass} 一致 —— 未旋转的模型板面法线朝 X 轴，
 * 所以 {@code facing=west} 时是原样，{@code facing=north} 转 90°（见 blockstate json）。
 */
public class XiamenPsdBlock extends HorizontalFacingBlock {

    /**
     * 板面的四条朝向各自的碰撞 / 外框，按 blockstate 里的 y 旋转推出来
     * （MTR 的 {@code psd_glass} 用的是 facing=west→0°、north→90°、east→180°、south→270°）：
     * <ul>
     *   <li>y=0：模型不动，板在 x∈[2,4]（西侧边缘）</li>
     *   <li>y=90：{@code x' = 16-z, z' = x} → 板落在 z∈[2,4]（北侧边缘）</li>
     *   <li>y=180：板落在 x∈[12,14]（东侧边缘）</li>
     *   <li>y=270：板落在 z∈[12,14]（南侧边缘）</li>
     * </ul>
     * 四条分开写是必要的：只按"板面沿 X 还是沿 Z"分两种，会让 east / south 这两向的
     * 外框整体错开 8px（一半格子），看起来像选不中。
     */
    private static final VoxelShape SHAPE_WEST = createCuboidShape(2.0D, 0.0D, 0.0D, 4.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_NORTH = createCuboidShape(0.0D, 0.0D, 2.0D, 16.0D, 16.0D, 4.0D);
    private static final VoxelShape SHAPE_EAST = createCuboidShape(12.0D, 0.0D, 0.0D, 14.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_SOUTH = createCuboidShape(0.0D, 0.0D, 12.0D, 16.0D, 16.0D, 14.0D);

    public XiamenPsdBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    /**
     * {@link HorizontalFacingBlock} 自己不往 StateManager 里加 FACING（只有 rotate/mirror），
     * 所以这里必须手动加上，否则 {@code with(FACING, …)} 会在构造期就抛
     * "property not registered"。
     */
    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 放置时板面正对玩家（和门 / 告示牌一致的手感）。 */
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeOf(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeOf(state);
    }

    private static VoxelShape shapeOf(BlockState state) {
        return switch (state.get(FACING)) {
            case NORTH -> SHAPE_NORTH;
            case EAST -> SHAPE_EAST;
            case SOUTH -> SHAPE_SOUTH;
            default -> SHAPE_WEST;
        };
    }
}
