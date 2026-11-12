// 挂墙发车倒数器：贴在站台头端的墙上，列车开门后倒计离站秒数。
package dev.js.mtrheadlight.xiamen.dep;

import java.util.List;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * 挂墙发车倒数器 {@code mtrlte:dep_on_wall}（模型 {@code mtrlte:block/dep_on_wall}）。
 *
 * <h2>它做什么</h2>
 * <p>钉在<b>站台头端</b>的墙上，是一块「发车倒数器」：平时屏幕空着（只剩那块暗红 LED
 * 点阵），列车进站<b>开门之后</b>才亮起一行<b>绿色七段数码管</b>，从剩余秒数倒数到
 * {@code 00:00}；到点还没走就变<b>红色</b>从 {@code 00:00} 正着数 5 秒，然后黑屏等下一班。
 * 屏幕上没有站名 —— 「下一班还有几分钟到」是 PIDS 的事。
 * 数据全部由客户端从 {@code ClientData} 里读（服务端不需要参与，方块实体也不落任何数据），
 * 见 {@link DepOnWallRenderer}。行为照 JCM 的「发车计时器」方块
 * （{@code jsblock:departure_timer}）：<b>找最近的站台 → 取该站台的班次 → 找正在停站的那一班</b>。
 *
 * <h2>朝向：背面贴墙、正面朝外</h2>
 * <p>模型自带正面：盒子只占 <b>z 12..16</b>（4/16 格厚），屏幕平面在 z = 12.3，
 * 朝 <b>north</b>（-Z）。换句话说「背面」在 z = 16 那一侧 —— 那是贴墙的一面。
 * 所以 {@code facing} 不仅仅是「正面朝哪」，它同时决定了<b>墙在哪一侧</b>：
 * 墙永远在 {@code facing.getOpposite()}，取 {@link #supportPos}。
 * 放置时取「你点的那一面」（{@link #getPlacementState}）：点墙的北面，牌子就朝北挂上去。
 * 四个水平方向都能放，blockstate 四个变体的 {@code y} 就是 {@link #blockRotation} 那一组值：
 * {@code north=0 / east=90 / south=180 / west=270}。
 *
 * <h2>贴墙：没有墙就放不下，墙没了就掉</h2>
 * <ul>
 *   <li>{@link #getPlacementState}：背后（{@code pos.offset(facing.getOpposite())}）没有
 *       完整实心面 → 返回 {@code null}，物品右键直接放不出来（不会出现「悬空贴在空气上」）；</li>
 *   <li>{@link #canPlaceAt}：{@code /setblock} / 活塞 / 结构方块这些绕过 {@code getPlacementState}
 *       的路径也挡一道；</li>
 *   <li>{@link #getStateForNeighborUpdate}：背后那一格被拆 / 被换掉时自己变空气
 *       （原版火把、按钮、告示牌都是这一套）。</li>
 * </ul>
 *
 * <h2>碰撞 / 轮廓</h2>
 * <p>按模型的真实包围盒 {@code x 1..15, y 3..13, z 12..16}（1/16 格）。模型会跟着
 * blockstate 绕 Y 转，所以盒子也按同一套轴交换
 * {@code (x, z) → (16 - z, x)} 提前算好四份（{@link #rotatedBox}），
 * 不共用同一个北向形状 —— 否则朝东 / 朝西放下时视觉和碰撞会差 90°。
 *
 * <h2>亮度</h2>
 * <p>方块本身不发光（{@code luminance} 保持 0）：屏幕的「亮」来自渲染器那层
 * {@code getEntityTranslucentEmissive} 自发光四边形，和厦门 PIDS 一样，不需要给方块打光。
 */
public class DepOnWallBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** 模型包围盒（1/16 格）：盒子 4/16 格厚，贴在方块的一侧，上下各留 3 格边。 */
    private static final double MIN_X = 1.0D;
    private static final double MAX_X = 15.0D;
    private static final double MIN_Y = 3.0D;
    private static final double MAX_Y = 13.0D;
    private static final double MIN_Z = 12.0D;
    private static final double MAX_Z = 16.0D;

    /** 四个朝向的形状（已经按 blockstate 的 {@code y} 转好，见 {@link #rotatedBox}）。 */
    private static final VoxelShape SHAPE_NORTH = rotatedBox(0);
    private static final VoxelShape SHAPE_EAST = rotatedBox(90);
    private static final VoxelShape SHAPE_SOUTH = rotatedBox(180);
    private static final VoxelShape SHAPE_WEST = rotatedBox(270);

    public DepOnWallBlock() {
        // nonOpaque()：模型不是满方块，不这样邻居朝我们的那一面会被按满方块剔除，
        // 缝隙里就能看穿邻居（厦门 PIDS 踩过同一个坑）。
        super(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .strength(1.2F)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque());
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    // ------------------------------------------------------------------ 形状

    /** 造一个「z = MIN_Z..MAX_Z」的轴对齐盒子（x/y/z 都是 1/16 格）。 */
    private static VoxelShape box(final double x1, final double x2, final double z1, final double z2) {
        return Block.createCuboidShape(x1, MIN_Y, z1, x2, MAX_Y, z2);
    }

    /**
     * 把模型包围盒按 blockstate 的 {@code y} 旋转转成世界坐标的盒子。
     *
     * <p>每 90° 就是 {@code (x, z) → (16 - z, x)}（与 blockstate {@code y} 同一套约定，
     * 也就是液体安检机那边验证过的映射）。朝北是唯一不做旋转的变体。
     */
    private static VoxelShape rotatedBox(final int yRotation) {
        switch (((yRotation % 360) + 360) % 360) {
            case 90:
                // (x, z) → (16 - z, x)：z 12..16 落到 x 0..4，x 1..15 落到 z 1..15。
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
     * {@code assets/mtrlte/blockstates/dep_on_wall.json} 里的四个变体一模一样，
     * 否则模型转了、碰撞箱没转（或者反过来）。
     */
    public static int blockRotation(final Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            // 只会是水平方向（FACING 就是 HORIZONTAL_FACING），UP/DOWN 在这里兜底成北。
            case NORTH, UP, DOWN -> 0;
        };
    }

    private static VoxelShape shapeFor(final Direction facing) {
        return switch (facing) {
            case EAST -> SHAPE_EAST;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case NORTH, UP, DOWN -> SHAPE_NORTH;
        };
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

    // ------------------------------------------------------------------ 放置 / 贴墙

    /** 背后那一格（贴墙的一面）的方块状态。 */
    public static BlockPos supportPos(final BlockPos pos, final Direction facing) {
        return pos.offset(facing.getOpposite());
    }

    /** 背后有没有一块完整的实心侧面朝着我们（{@code side} = 支撑方块朝向本方块的那一面）。 */
    private static boolean hasSupport(final BlockState state, final WorldView world, final BlockPos pos) {
        final Direction facing = state.get(FACING);
        final BlockPos support = supportPos(pos, facing);
        return world.getBlockState(support).isSideSolidFullSquare(world, support, facing);
    }

    /**
     * 正面背着墙，也就是 === 你点的那一面 ===。
     *
     * <p>两种来源，优先用「点的那一面」：
     * <ul>
     *   <li>点的是墙的水平侧面（绝大多数情况）：{@code facing = ctx.getSide()} ——
     *       点北面就朝北，和原版拉杆 / 按钮「贴在点的那一面上」完全一致；</li>
     *   <li>点的是顶面 / 底面（地上 / 天花板上）：退回
     *       {@code getHorizontalPlayerFacing().getOpposite()}（正面朝玩家）。
     *       这种放法背后多半没有墙，{@link #hasSupport} 会把这次放置拒掉。</li>
     * </ul>
     *
     * <p>两种来源都只会是水平方向（{@code FACING} 就是 {@code HORIZONTAL_FACING}），
     * 四个 blockstate 变体都能落到。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final Direction side = ctx.getSide();
        final Direction facing = side.getAxis().isHorizontal()
                ? side
                : ctx.getHorizontalPlayerFacing().getOpposite();
        final BlockState state = getDefaultState().with(FACING, facing);
        return hasSupport(state, ctx.getWorld(), ctx.getBlockPos()) ? state : null;
    }

    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        return hasSupport(state, world, pos);
    }

    /** 背后那格没了 → 自己变空气（走邻居更新，和原版火把一样；玩家直接挖本体照常掉物品）。 */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        if (direction == state.get(FACING).getOpposite() && !canPlaceAt(state, world, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        // 物品栏渲染 / 结构方块可能给不到真实 state，用默认态兜底（MTR 的方块也照传 null）。
        return new DepOnWallBlockEntity(pos, state == null ? getDefaultState() : state);
    }

    @Override
    public void appendTooltip(final ItemStack stack, final BlockView world, final List<Text> tooltip,
                              final TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.mtrlte.dep_on_wall"));
        // 读数方向的现场开关：写在物品提示里，玩家不用翻文档也能发现它（见 DepOnWallOrient）。
        tooltip.add(Text.translatable("tooltip.mtrlte.dep_on_wall.orient")
                .formatted(net.minecraft.util.Formatting.GRAY));
    }
}
