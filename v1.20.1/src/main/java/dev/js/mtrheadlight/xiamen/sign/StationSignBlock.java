// 站外标志方块（厦门地铁 / 角美地铁 × 挂墙 / 杆上）：模型是 Blockbench 导出的 16 格高双面灯箱牌。
package dev.js.mtrheadlight.xiamen.sign;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * 站外标志（进站口那种立在地面 / 挑在墙上的「XX 地铁」灯箱牌），方块 {@code mtrlte:station_sign_*}。
 *
 * <h2>四个方块 = 两个模型 × 两张贴图</h2>
 * <table border="1">
 *   <caption>方块 id / 模型 / 贴图</caption>
 *   <tr><th>id</th><th>模型</th><th>贴图</th></tr>
 *   <tr><td>{@code station_sign_wall_xiamen}</td><td>{@code block/station_sign_wall}</td>
 *       <td rowspan="2">{@code block/station_sign_xiamen}</td></tr>
 *   <tr><td>{@code station_sign_pole_xiamen}</td><td>{@code block/station_sign_pole}</td></tr>
 *   <tr><td>{@code station_sign_wall_jiaomei}</td><td>{@code block/station_sign_wall_jiaomei}</td>
 *       <td rowspan="2">{@code block/station_sign_jiaomei}</td></tr>
 *   <tr><td>{@code station_sign_pole_jiaomei}</td><td>{@code block/station_sign_pole_jiaomei}</td></tr>
 * </table>
 * <p>几何只有两份（{@code sign_on_wall.json} / {@code sign_on_pole.json}，来自 Blockbench）；
 * 角美那两份（{@code *_jiaomei.json}）只写 {@code parent} + {@code textures}，
 * 几何完全继承厦门那份，所以「两模型两贴图」就能凑出四个方块。
 *
 * <h2>模型空间</h2>
 * <ul>
 *   <li><b>pole</b>（杆上 / 立地款）：牌面是一张 14×14 的双面板，占
 *       {@code x 0..16, y 0..16, z 5.4..10.6}；两面分别朝 <b>north / south</b>（±Z），
 *       中间是穿过牌面的十字抱杆。整格高，自己站得住，不需要支撑。</li>
 *   <li><b>wall</b>（挂墙款）：同一块牌子绕 Y 转了 90°，牌面朝 <b>east / west</b>（±X）。
 *       2026-11-12 换的新模型（{@code sign_on_wall.json}，27 个元素）把整套几何沿 +Z 挪了 2/16：
 *       支架（2×3.2×2 那一小块）从格子外的 {@code z -2..0} 挪进<b>本格</b>{@code z 0..2}，
 *       牌身 {@code z 8.4..11.6}、侧翼 {@code z 3..17}，最前面的顶尖到 {@code z=18}（探出格子 2/16）。
 *       墙仍在<b>模型空间的 north（-Z）一侧</b>，所以这一款必须贴着完整的墙面放，墙没了就掉
 *       （和原版告示牌 / 按钮同一个规矩）。</li>
 * </ul>
 *
 * <h2>{@code facing} 的含义两款不同（都有文档，别混）</h2>
 * <ul>
 *   <li>pole：{@code facing} = <b>牌面朝向</b>，放置时朝玩家
 *       （{@code getHorizontalPlayerFacing().getOpposite()}）；</li>
 *   <li>wall：{@code facing} = <b>墙在哪一侧</b>（{@code facing} 那一格必须是实心方块）。
 *       挂墙牌是双面的，正面没有意义，有意义的是「钉在哪面墙上」——
 *       点墙的南面挂上去，{@code facing} 就是 SOUTH。</li>
 * </ul>
 * <p>两款的 blockstate 旋转约定完全一致：{@code north=0 / east=90 / south=180 / west=270}
 * （{@link #blockRotation}，与 {@code dep_on_wall} / {@code liquid_scanner} 用的同一套，
 * 也和 {@link #rotatedBox} 里的 {@code (x, z) → (16 - z, x)} 对应）。
 *
 * <h2>亮度 15</h2>
 * <p>灯箱牌本来就是自发光：{@code luminance(state -> 15)}，夜里整块牌子发白光
 * （注册在 {@link StationSignObjects}）。贴图有大量 alpha=0 的空白，客户端必须挂
 * {@code RenderLayer.getCutout()}，否则空白处会被画成黑色（见 {@code XiamenClient}）。
 */
public class StationSignBlock extends HorizontalFacingBlock {

    /** 模型包围盒（1/16 格，只取本格内的部分：挂墙款的支架伸出格子那 2 格外的不算）。 */
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;

    /** {@code true} = 挂墙款（要支撑、会掉）；{@code false} = 杆上款（自立）。 */
    private final boolean wallMounted;

    /** 四个朝向的形状（{@code facing} → 已按 blockstate 的 {@code y} 转好）。 */
    private final VoxelShape[] shapes = new VoxelShape[4];

    /**
     * @param settings   方块设置（亮度 / 硬度这些，见 {@link StationSignObjects}）
     * @param wallMounted 挂墙款为 {@code true}，杆上款为 {@code false}
     */
    public StationSignBlock(final AbstractBlock.Settings settings, final boolean wallMounted) {
        super(settings);
        this.wallMounted = wallMounted;
        if (wallMounted) {
            // 牌面 5.2 格宽（x 5.4..10.6）、整格高；沿 z 从支架（0..2）一直到侧翼（17）。
            // 只取本格内的部分：新模型最前端到 z=18，探出的 2/16 不进形状（撞到邻居格里更糟）。
            this.minX = 5.4D;
            this.maxX = 10.6D;
            this.minZ = 0.0D;
            this.maxZ = 16.0D;
        } else {
            // 牌面 14 格宽（x 0..16）、5 格厚（z 5.4..10.6）。
            this.minX = 0.0D;
            this.maxX = 16.0D;
            this.minZ = 5.4D;
            this.maxZ = 10.6D;
        }
        this.shapes[0] = rotatedBox(0);
        this.shapes[1] = rotatedBox(90);
        this.shapes[2] = rotatedBox(180);
        this.shapes[3] = rotatedBox(270);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    public boolean isWallMounted() {
        return this.wallMounted;
    }

    // ------------------------------------------------------------------ 形状

    /** 轴对齐盒子（y 恒为整格 0..16）。 */
    private VoxelShape box(final double x1, final double x2, final double z1, final double z2) {
        return Block.createCuboidShape(x1, 0.0D, z1, x2, 16.0D, z2);
    }

    /**
     * 把模型包围盒按 blockstate 的 {@code y} 旋转成世界坐标的盒子。
     * 每 90° 都是 {@code (x, z) → (16 - z, x)}（与 blockstate {@code y} 同一套约定）。
     */
    private VoxelShape rotatedBox(final int yRotation) {
        switch (((yRotation % 360) + 360) % 360) {
            case 90:
                return box(16.0D - this.maxZ, 16.0D - this.minZ, this.minX, this.maxX);
            case 180:
                return box(16.0D - this.maxX, 16.0D - this.minX, 16.0D - this.maxZ, 16.0D - this.minZ);
            case 270:
                return box(this.minZ, this.maxZ, 16.0D - this.maxX, 16.0D - this.minX);
            case 0:
            default:
                return box(this.minX, this.maxX, this.minZ, this.maxZ);
        }
    }

    /**
     * facing → blockstate {@code y}。必须与
     * {@code assets/mtrlte/blockstates/station_sign_*.json} 里的四个变体一模一样。
     */
    public static int blockRotation(final Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    private VoxelShape shapeFor(final Direction facing) {
        return this.shapes[blockRotation(facing) / 90];
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        return shapeFor(state.get(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return shapeFor(state.get(FACING));
    }

    // ------------------------------------------------------------------ 放置 / 支撑

    /** 挂墙款：{@code facing} 那一格就是墙。 */
    private static BlockPos supportPos(final BlockPos pos, final Direction facing) {
        return pos.offset(facing);
    }

    private static boolean hasSupport(final BlockState state, final WorldView world, final BlockPos pos) {
        final Direction facing = state.get(FACING);
        final BlockPos support = supportPos(pos, facing);
        // 墙朝向我们的那一面：墙在 NORTH 侧时，贴着我们的是它朝南的那一面。
        return world.getBlockState(support).isSideSolidFullSquare(world, support, facing.getOpposite());
    }

    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        if (!this.wallMounted) {
            // 杆上款：牌面朝玩家，哪都能放。
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        }
        final Direction side = ctx.getSide();
        // 点墙的某一侧 → 新方块在墙的外面，墙就在新方块的 side.getOpposite() 那一侧。
        final Direction wall = side.getAxis().isHorizontal()
                ? side.getOpposite()
                : ctx.getHorizontalPlayerFacing();
        final BlockState state = getDefaultState().with(FACING, wall);
        return hasSupport(state, ctx.getWorld(), ctx.getBlockPos()) ? state : null;
    }

    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        return !this.wallMounted || hasSupport(state, world, pos);
    }

    /** 墙没了 → 自己变空气（走邻居更新，和原版告示牌一样；玩家直接挖本体照常掉物品）。 */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        if (this.wallMounted && direction == state.get(FACING) && !canPlaceAt(state, world, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }
}
