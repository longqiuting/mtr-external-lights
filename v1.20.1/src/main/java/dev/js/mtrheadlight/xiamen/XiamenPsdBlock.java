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
 * <p>端部（{@code xiamen_psd_end}）用的是 MTR 的 <b>end</b> 几何
 * {@code mtr:block/psd_glass_end_left_base}：一样 2px 厚、满高，但<b>只占半格长</b>
 * （z∈[8,16]），因为端头模块本来就是与旁边的门扇/玻璃拼成一整段的收口板。
 * 所以这个类把「四向形状」做成构造参数，两种板共用一个类：
 * <ul>
 *   <li>{@link #XiamenPsdBlock(AbstractBlock.Settings)} —— 满格长（门扇 / 玻璃）</li>
 *   <li>{@link #endPanel(AbstractBlock.Settings)} —— 半格长（端部）</li>
 * </ul>
 *
 * <p>朝向：与 MTR 的 {@code psd_glass} 一致 —— 未旋转的模型板面法线朝 X 轴，
 * 所以 {@code facing=west} 时是原样，{@code facing=north} 转 90°（见 blockstate json）。
 */
public class XiamenPsdBlock extends HorizontalFacingBlock {

    /**
     * 满格长板（{@code mtr:block/psd_glass_left_base}，x∈[2,4]、z 满格）的四向碰撞 / 外框，
     * 按 blockstate 里的 y 旋转推出来
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
    private static final VoxelShape[] BOARD = {
            createCuboidShape(2.0D, 0.0D, 0.0D, 4.0D, 16.0D, 16.0D),      // west  (y=0)
            createCuboidShape(0.0D, 0.0D, 2.0D, 16.0D, 16.0D, 4.0D),      // north (y=90)
            createCuboidShape(12.0D, 0.0D, 0.0D, 14.0D, 16.0D, 16.0D),    // east  (y=180)
            createCuboidShape(0.0D, 0.0D, 12.0D, 16.0D, 16.0D, 14.0D)     // south (y=270)
    };

    /**
     * 半格长端部板（{@code mtr:block/psd_glass_end_left_base}，x∈[4,6]、z∈[8,16]）的四向形状。
     * 同样按 y 旋转推：
     * <ul>
     *   <li>y=0（west）：x∈[4,6]、z∈[8,16]</li>
     *   <li>y=90（north）：{@code x' = 16-z, z' = x} → x∈[0,8]、z∈[4,6]</li>
     *   <li>y=180（east）：x∈[10,12]、z∈[0,8]</li>
     *   <li>y=270（south）：x∈[8,16]、z∈[10,12]</li>
     * </ul>
     */
    private static final VoxelShape[] END = {
            createCuboidShape(4.0D, 0.0D, 8.0D, 6.0D, 16.0D, 16.0D),      // west  (y=0)
            createCuboidShape(0.0D, 0.0D, 4.0D, 8.0D, 16.0D, 6.0D),       // north (y=90)
            createCuboidShape(10.0D, 0.0D, 0.0D, 12.0D, 16.0D, 8.0D),     // east  (y=180)
            createCuboidShape(8.0D, 0.0D, 10.0D, 16.0D, 16.0D, 12.0D)     // south (y=270)
    };

    /** 四条朝向各自的形状，下标 = {@link #shapeIndex(Direction)}。 */
    private final VoxelShape[] shapes;

    /** 满格长的门扇 / 玻璃板。 */
    public XiamenPsdBlock(AbstractBlock.Settings settings) {
        this(settings, BOARD);
    }

    protected XiamenPsdBlock(AbstractBlock.Settings settings, VoxelShape[] shapes) {
        super(settings);
        this.shapes = shapes;
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    /** 半格长的端部模块（几何 = MTR 的 {@code psd_glass_end_left_base}）。 */
    public static XiamenPsdBlock endPanel(AbstractBlock.Settings settings) {
        return new XiamenPsdBlock(settings, END);
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

    private VoxelShape shapeOf(BlockState state) {
        return shapes[shapeIndex(state.get(FACING))];
    }

    /** {@code BOARD} / {@code END} 里的下标：west 原样、north +90°、east +180°、south +270°。 */
    private static int shapeIndex(Direction facing) {
        return switch (facing) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 3;
            default -> 0;
        };
    }
}
