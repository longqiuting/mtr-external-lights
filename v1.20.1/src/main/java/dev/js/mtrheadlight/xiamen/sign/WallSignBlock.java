// 贴墙标识方块（厦门 / 角美 / LTE）：一块薄灯箱板钉在墙上，几何照 MTR 站名出入口那块 Light 面。
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
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * 贴墙标识方块 {@code mtrlte:wall_sign_xiamen} / {@code wall_sign_jiaomei} / {@code wall_sign_lte}。
 *
 * <h2>和站外标志（{@link StationSignBlock}）不是同一个东西</h2>
 * <p>站外标志是立地 / 挑在墙上那种 {@code 16} 格高的双面大灯箱（进站口招牌）；
 * 这里是<b>一块薄板直接钉在墙面上的标识</b>：占一格宽、14/16 格高、1/16 格厚，正面是一张 logo。
 * 三个方块几何完全一样，只有贴图不同（见 {@link WallSignObjects}）。
 *
 * <h2>几何来源：MTR 的「站名出入口」模型</h2>
 * <p>{@code mtr:block/station_name_entrance_short} / {@code _tall}（MTR jar 里那两个 json）
 * 的元素只有一块 {@code __comment: "Light"} 的<b>整面反光板</b>：
 * {@code from [0,4,0.05] → to [16,12,0.05]}（short，16×8）和 {@code [0,0,0.05] → [16,16,0.05]}（tall，16×16），
 * 而且<b>零厚度</b>（from.z == to.z）、只有 north（{@code cullface}）/ south 两个面 —— 站名文字是 BER 另外画上去的。
 * <p>本模组的 {@code block/wall_sign.json} 就是照这块板做的，两处改动：
 * <ol>
 *   <li><b>给它补上厚度</b>：{@code z 0 → 0.95}（1/16 格减去 0.05 的 logo 层），
 *       这样它是一块真板子，不是一张纸；碰撞箱 / 框选就按这 0.95/16 算（见 {@link #shapeFor}）；</li>
 *   <li>高度取 short 与 tall 之间：{@code y 1 → 15}（14 格高，上下各留 1 格边距），
 *       正面留 2 格边距给 logo（12×12 见方，正方形贴图不拉伸）。</li>
 * </ol>
 * <p>贴图<b>全部是本模组自己的</b>（{@code mtrlte:block/{xiamen,jiaomei,lte}_logo} + {@code mtrlte:block/metal}），
 * 没有一处 {@code mtr:} 引用。
 *
 * <h2>{@code facing} = 墙在哪一侧</h2>
 * <p>和 {@link StationSignBlock} 的挂墙款同一个规矩（也和原版告示牌 / 按钮一致）：
 * 点在墙的哪一面就 {@code facing} 那一侧，{@code facing} 那一格必须是实心方块，否则放不下；
 * 墙没了自己变空气（顺手把物品掉出来）。牌面朝外，所以「正面」由墙决定，不需要单独一个属性。
 * <p>blockstate 旋转约定：{@code north=0 / east=90 / south=180 / west=270}（{@link #blockRotation}），
 * 模型空间里墙在 <b>north（-Z）</b> 一侧，与 {@code station_sign_wall} / {@code dep_on_wall} 同一套。
 */
public class WallSignBlock extends HorizontalFacingBlock {

    /** 模型在 x 上的范围（1/16 格）：整格宽。 */
    private static final double MIN_X = 0.0D;
    private static final double MAX_X = 16.0D;

    /** 模型在 y 上的范围：14 格高，上下各 1 格边距（与 {@code block/wall_sign.json} 一致）。 */
    private static final double MIN_Y = 1.0D;
    private static final double MAX_Y = 15.0D;

    /** 模型在 z 上的范围：0（贴墙）→ 0.95（板厚），logo 层再往外的 0.05 不算进形状。 */
    private static final double MIN_Z = 0.0D;
    private static final double MAX_Z = 0.95D;

    /** 四个朝向的形状：{@code facing} → 已按 blockstate 的 {@code y} 转好。 */
    private final VoxelShape[] shapes = new VoxelShape[4];

    public WallSignBlock(final AbstractBlock.Settings settings) {
        super(settings);
        this.shapes[0] = rotatedBox(0);
        this.shapes[1] = rotatedBox(90);
        this.shapes[2] = rotatedBox(180);
        this.shapes[3] = rotatedBox(270);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    // ------------------------------------------------------------------ 形状

    private static VoxelShape box(final double x1, final double x2, final double z1, final double z2) {
        return Block.createCuboidShape(x1, MIN_Y, z1, x2, MAX_Y, z2);
    }

    /**
     * 把模型包围盒按 blockstate 的 {@code y} 旋转成世界坐标的盒子。
     * 每 90° 都是 {@code (x, z) → (16 - z, x)}（与 blockstate {@code y} 同一套约定）。
     */
    private static VoxelShape rotatedBox(final int yRotation) {
        switch (((yRotation % 360) + 360) % 360) {
            case 90:
                return box(16.0D - MAX_Z, 16.0D - MIN_Z, MIN_X, MAX_X);
            case 180:
                return box(16.0D - MAX_X, 16.0D - MIN_X, 16.0D - MAX_Z, 16.0D - MIN_Z);
            case 270:
                return box(MIN_Z, MAX_Z, 16.0D - MAX_X, 16.0D - MIN_X);
            case 0:
            default:
                return box(MIN_X, MAX_X, MIN_Z, MAX_Z);
        }
    }

    /**
     * facing → blockstate {@code y}。必须与
     * {@code assets/mtrlte/blockstates/wall_sign_*.json} 里的四个变体一模一样。
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
        final Direction side = ctx.getSide();
        // 点墙的某一侧 → 新方块在墙的外面，墙就在新方块的 side.getOpposite() 那一侧。
        // 点天花板 / 地面这种竖直面放不了：改按玩家朝向挂到身后的墙上（和原版告示牌一样）。
        final Direction wall = side.getAxis().isHorizontal()
                ? side.getOpposite()
                : ctx.getHorizontalPlayerFacing();
        final BlockState state = getDefaultState().with(FACING, wall);
        return hasSupport(state, ctx.getWorld(), ctx.getBlockPos()) ? state : null;
    }

    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        return hasSupport(state, world, pos);
    }

    /** 墙没了 → 掉物品 + 自己变空气（比站外标志多一步掉物品：薄板更容易被连带拆掉）。 */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        if (direction == state.get(FACING) && !canPlaceAt(state, world, pos)) {
            if (world instanceof World realWorld) {
                dropStacks(state, realWorld, pos);
            }
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }
}
