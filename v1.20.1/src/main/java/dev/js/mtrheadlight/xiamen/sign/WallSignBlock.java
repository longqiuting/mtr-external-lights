// 贴墙标识方块（厦门 / 角美 / LTE）：MTR 4.0.5 站名出入口塔式模型 + 自动连接 + 站名渲染 + 刷子切换。
package dev.js.mtrheadlight.xiamen.sign;

import dev.js.mtrheadlight.xiamen.XiamenObjects;
import mtr.block.IBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * 贴墙标识方块 {@code mtrlte:wall_sign_xiamen} / {@code wall_sign_jiaomei} / {@code wall_sign_lte}。
 *
 * <h2>几何与行为全部照 MTR 4.0.5 的「站名出入口」（{@code mtr:station_name_entrance}）</h2>
 * <p>模型是 {@code assets/mtr/models/block/station_name_entrance_tall.json}（4.0.5）原样搬过来的：
 * <b>一块零厚度的发光板</b> {@code from [0,0,0.05] → to [16,16,0.05]}，只有 {@code north}
 * （{@code cullface}，朝墙那一面）/ {@code south}（朝外）两个面，贴图 {@code mtr:block/dark_blue}。
 * 也就是说<b>方块模型本身只是一层「灯」</b>，站名是渲染器（{@link WallSignRenderer}）另外画上去的
 * —— 和 MTR 自己一模一样：它的方块模型就是这个 json，字由 BER 画。
 * <p>{@code station_name_entrance_short} 是同一块板压到 {@code y 4..12}（8 格高），
 * 两个 json 都拷进 {@code assets/mtrlte/models/block/}，贴图路径改写成 {@code mtrlte:block/dark_blue}。
 *
 * <h2>三个属性（与 MTR 的 {@code BlockStationNameEntrance} 对齐）</h2>
 * <table border="1">
 *   <caption>属性 / 取值 / 谁在用</caption>
 *   <tr><th>属性</th><th>取值</th><th>做什么</th></tr>
 *   <tr><td>{@code facing}</td><td>4 个水平方向</td>
 *       <td><b>墙在哪一侧</b>（和 {@link StationSignBlock} 的挂墙款同一个约定，也和 MTR 的
 *       {@code FACING} 一致：{@code pos.offset(facing)} 那格必须是实心方块）。牌面朝
 *       {@code facing.getOpposite()}。</td></tr>
 *   <tr><td>{@code style}</td><td>0 = 矮（short）/ 1 = 高（tall）</td>
 *       <td>MTR 里这个属性叫 {@code propagate_property}（{@code IntProperty.of("propagate_property", 0, 1)}，
 *       javap 核对过 3.6.4.2 与 4.0.5 都一样）。这里用更说得通的名字 {@code style}，
 *       <b>语义完全相同</b>：0 选 {@code station_name_entrance_short} 模型、1 选 tall。</td></tr>
 *   <tr><td>{@code link}</td><td>0..63 的位掩码</td>
 *       <td>本方块和相邻的「同一块的另一格」连上了没有，见下面的「自动连接」。</td></tr>
 * </table>
 *
 * <h2>自动连接：{@code link} 位掩码（不是四个布尔属性）</h2>
 * <p>位 = {@code 1 << direction.getId()}，也就是 Minecraft 的 {@code Direction} 序列
 * {@code DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5}：
 * <pre>
 *   DOWN  =  1      UP    =  2      NORTH =  4
 *   SOUTH =  8      WEST  = 16      EAST  = 32
 * </pre>
 * 一格最多 6 个邻居 → 值域 {@code 0..63}。判「连上」的条件三条全满足（{@link #connectsTo}）：
 * 同一个方块、<b>同一个 {@code facing}</b>（贴在同一面墙上）、<b>同一个 {@code style}</b>
 * （高度一样，连起来才是一条连续的带子）。
 * <p>为什么是位掩码而不是 6 个 {@code BooleanProperty}：这 6 位在模型里<b>不参与选变体</b>
 * （零厚度的板，两格并排在视觉上本来就无缝，MTR 的塔式模型也没有拼接件），
 * 唯一的消费者是渲染器 —— 它要拿这 6 位算出「这条牌子横跨几格、竖跨几格、
 * 由哪一格负责画字」。一个 {@code IntProperty} 比 6 个 boolean 少 6 倍的属性组合，
 * 也不会让 {@code blockstates} 变成 2×4×64 条。
 * <p>位的维护在 {@link #getStateForNeighborUpdate}（邻居变了自己整张掩码重算，
 * 6 次 {@code getBlockState}，比按位打补丁更难写错）。
 *
 * <h2>刷子切换（{@code mtr.Items.BRUSH}）</h2>
 * <p>{@link #onUse} 走 MTR 的 {@code IBlock.checkHoldingBrush(world, player, runnable)}：
 * 它自带「只在服务端跑 lambda」的守卫（javap 核对过 {@code checkHoldingItem} 里的
 * {@code world.isClient} 分支），所以客户端不会自己去改方块。
 * <p>取值循环：{@code style} <b>0 → 1 → 0</b>（矮 → 高 → 矮）。
 * 和 MTR 一样，改完<b>向左右两侧沿墙传一遍</b>（{@code facing.rotateYClockwise()} /
 * {@code rotateYCounterclockwise()}，{@link #propagateStyle}），一条牌子整排一起换形态；
 * 上下不传 —— 竖着叠的那几格形态由各自决定，连接位会按 style 相等自动断开 / 接上。
 *
 * <h2>站名</h2>
 * <p>方块本身不存站名：{@link WallSignBlockEntity} 是纯标记方块实体，
 * 渲染器每帧用 {@code RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)}
 * 按位置现取（与 MTR 的 {@code RenderStationNameBase} 同一条链路，见 {@link WallSignRenderer}）。
 *
 * <h2>形状：能选中、不能撞</h2>
 * <p>{@link #getOutlineShape} 给一块薄板（{@code z 0..1/16}，y 按 style 取 4..12 或 0..16），
 * {@link #getCollisionShape} 返回 {@link VoxelShapes#empty()} —— MTR 的站名出入口也是
 * {@code VoxelShapes.empty()}（javap 核对过），牌面薄、贴在墙上，不该挡人。
 */
public class WallSignBlock extends HorizontalFacingBlock implements IBlock {

    /** 形态：0 = 矮（short 模型），1 = 高（tall 模型）。MTR 里叫 {@code propagate_property}。 */
    public static final IntProperty STYLE = IntProperty.of("style", 0, 1);

    /** 连接位掩码（见类注释；{@code 1 << Direction#getId()}）。 */
    public static final IntProperty LINK = IntProperty.of("link", 0, 63);

    /** 模型里那块板所在的 z（1/16 格 → 0.05/16 = 0.003125；取 1/16 是为了有个能选中的厚度）。 */
    private static final double PLATE_MIN_Z = 0.0D;
    private static final double PLATE_MAX_Z = 1.0D;

    /** 矮形态的上下边（1/16 格，与 {@code station_name_entrance_short.json} 一致）。 */
    private static final double SHORT_MIN_Y = 4.0D;
    private static final double SHORT_MAX_Y = 12.0D;

    /** 这个方块的品牌 logo（渲染器画在牌子左下角的小方块）；三个 id 各一张。 */
    private final Identifier logo;

    /** 四个朝向的形状：矮 / 高各一套。 */
    private final VoxelShape[] shortShapes = new VoxelShape[4];
    private final VoxelShape[] tallShapes = new VoxelShape[4];

    /**
     * @param settings 方块设置（亮度 / 硬度这些，见 {@link WallSignObjects}）
     * @param logo     品牌 logo 的<b>贴图 id</b>（{@code mtrlte:textures/block/xiamen_logo.png} 这种
     *                 带 {@code textures/} 的完整路径 —— {@code RenderLayer} 要的就是这个形态）
     */
    public WallSignBlock(final AbstractBlock.Settings settings, final Identifier logo) {
        super(settings);
        this.logo = logo == null ? new Identifier(XiamenObjects.NAMESPACE, "textures/block/xiamen_logo.png") : logo;
        for (int i = 0; i < 4; i++) {
            this.shortShapes[i] = rotatedBox(i * 90, SHORT_MIN_Y, SHORT_MAX_Y);
            this.tallShapes[i] = rotatedBox(i * 90, 0.0D, 16.0D);
        }
        setDefaultState(getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(STYLE, 0)
                .with(LINK, 0));
    }

    /** 品牌 logo 贴图（渲染器用）。 */
    public Identifier logo() {
        return this.logo;
    }

    // ------------------------------------------------------------------ 形状

    private static VoxelShape box(final double x1, final double x2, final double y1, final double y2,
                                  final double z1, final double z2) {
        return Block.createCuboidShape(x1, y1, z1, x2, y2, z2);
    }

    /**
     * 把模型包围盒按 blockstate 的 {@code y} 旋转成世界坐标的盒子。
     * 每 90° 都是 {@code (x, z) → (16 - z, x)}（与 blockstate {@code y} 同一套约定，
     * 与 {@code station_sign_*} / {@code dep_on_wall} 一致）。
     */
    private static VoxelShape rotatedBox(final int yRotation, final double minY, final double maxY) {
        switch (((yRotation % 360) + 360) % 360) {
            case 90:
                return box(16.0D - PLATE_MAX_Z, 16.0D - PLATE_MIN_Z, minY, maxY, 0.0D, 16.0D);
            case 180:
                return box(0.0D, 16.0D, minY, maxY, 16.0D - PLATE_MAX_Z, 16.0D - PLATE_MIN_Z);
            case 270:
                return box(PLATE_MIN_Z, PLATE_MAX_Z, minY, maxY, 0.0D, 16.0D);
            case 0:
            default:
                return box(0.0D, 16.0D, minY, maxY, PLATE_MIN_Z, PLATE_MAX_Z);
        }
    }

    /**
     * facing → blockstate {@code y}。必须与
     * {@code assets/mtrlte/blockstates/wall_sign_*.json} 里的八个变体一模一样。
     */
    public static int blockRotation(final Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    private VoxelShape shapeFor(final BlockState state) {
        final int index = blockRotation(state.get(FACING)) / 90;
        return state.get(STYLE) == 0 ? this.shortShapes[index] : this.tallShapes[index];
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, STYLE, LINK);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        return shapeFor(state);
    }

    /** 薄的牌面不挡人（MTR 的站名出入口同样返回空）。 */
    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return VoxelShapes.empty();
    }

    // ------------------------------------------------------------------ 连接位掩码

    /** 一个方向对应的位（{@code 1 << direction.getId()}）。 */
    public static int bit(final Direction direction) {
        return 1 << direction.getId();
    }

    /** 这一格在 {@code direction} 那一侧连上了没有。 */
    public static boolean isLinked(final BlockState state, final Direction direction) {
        return (state.get(LINK) & bit(direction)) != 0;
    }

    /**
     * 相邻的那一格算不算「连上」：同一个方块 + 同一面墙（{@code facing}）+ 同一形态（{@code style}）。
     *
     * @param world 读世界（客户端也能调：{@link BlockView} 就够）
     * @param pos   被检查的那一格（已经是邻居的位置）
     * @param self  本格的方块状态
     */
    public static boolean connectsTo(final BlockView world, final BlockPos pos, final BlockState self) {
        final BlockState other = world.getBlockState(pos);
        return other.getBlock() == self.getBlock()
                && other.get(FACING) == self.get(FACING)
                && other.get(STYLE).equals(self.get(STYLE));
    }

    /** 整张掩码重算（6 次 {@code getBlockState}）。 */
    public static int computeLink(final BlockView world, final BlockPos pos, final BlockState state) {
        int mask = 0;
        for (final Direction direction : Direction.values()) {
            if (connectsTo(world, pos.offset(direction), state)) {
                mask |= bit(direction);
            }
        }
        return mask;
    }

    /** {@code from} 那一格朝 {@code direction} 是不是已经和邻居连上（两边都置位才算）。 */
    private static boolean linkedTo(final BlockView world, final BlockPos from, final BlockState fromState,
                                    final Direction direction) {
        if (!isLinked(fromState, direction)) {
            return false;
        }
        final BlockPos target = from.offset(direction);
        return connectsTo(world, target, fromState) && isLinked(world.getBlockState(target), direction.getOpposite());
    }

    /**
     * 这一格所在的「一条牌子」有多大、由哪一格负责画字。
     *
     * @param width   横跨几格（沿墙，从最左到最右）
     * @param height  竖跨几格（下 → 上）
     * @param anchor  这一格是不是<b>左下角</b>那一格（只有它画，不然一条牌子上会重复出现好几遍站名）
     */
    public record Run(int width, int height, boolean anchor) {
    }

    /**
     * 按 {@code link} 位掩码把整条牌子的尺寸算出来。渲染器只在这一格是 anchor 时落笔，
     * 落笔时用 {@code width} / {@code height} 把牌面铺满整条。
     */
    public static Run runOf(final BlockView world, final BlockPos pos, final BlockState state) {
        final Direction facing = state.get(FACING);
        // 沿墙往左 = facing.rotateYCounterclockwise()、往右 = facing.rotateYClockwise()
        // （facing=north 时右 = east = 站在牌子正面的人的右手边）。
        final Direction left = facing.rotateYCounterclockwise();
        final Direction right = facing.rotateYClockwise();

        int leftCount = 0;
        BlockPos cursor = pos;
        BlockState current = state;
        for (int guard = 0; guard < 64; guard++) {
            if (!linkedTo(world, cursor, current, left)) {
                break;
            }
            cursor = cursor.offset(left);
            current = world.getBlockState(cursor);
            leftCount++;
        }

        int rightCount = 0;
        cursor = pos;
        current = state;
        for (int guard = 0; guard < 64; guard++) {
            if (!linkedTo(world, cursor, current, right)) {
                break;
            }
            cursor = cursor.offset(right);
            current = world.getBlockState(cursor);
            rightCount++;
        }

        int downCount = 0;
        cursor = pos;
        current = state;
        for (int guard = 0; guard < 64; guard++) {
            if (!linkedTo(world, cursor, current, Direction.DOWN)) {
                break;
            }
            cursor = cursor.offset(Direction.DOWN);
            current = world.getBlockState(cursor);
            downCount++;
        }

        int upCount = 0;
        cursor = pos;
        current = state;
        for (int guard = 0; guard < 64; guard++) {
            if (!linkedTo(world, cursor, current, Direction.UP)) {
                break;
            }
            cursor = cursor.offset(Direction.UP);
            current = world.getBlockState(cursor);
            upCount++;
        }

        return new Run(1 + leftCount + rightCount, 1 + downCount + upCount, leftCount == 0 && downCount == 0);
    }

    // ------------------------------------------------------------------ 放置 / 支撑

    private static boolean hasSupport(final BlockState state, final WorldView world, final BlockPos pos) {
        final Direction facing = state.get(FACING);
        final BlockPos support = pos.offset(facing);
        // 墙朝向我们的那一面：墙在 NORTH 侧时，贴着我们的是它朝南的那一面。
        return world.getBlockState(support).isSideSolidFullSquare(world, support, facing.getOpposite());
    }

    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final Direction side = ctx.getSide();
        // 点墙的某一侧 → 新方块在墙的外面，墙就在新方块的 side.getOpposite() 那一侧
        //（与 MTR 的 BlockStationNameEntrance 同一个式子）。
        // 点天花板 / 地面这种竖直面放不了：改按玩家朝向挂到身后的墙上（和原版告示牌一样）。
        final Direction wall = side.getAxis().isHorizontal()
                ? side.getOpposite()
                : ctx.getHorizontalPlayerFacing();
        final BlockState state = getDefaultState().with(FACING, wall);
        if (!hasSupport(state, ctx.getWorld(), ctx.getBlockPos())) {
            return null;
        }
        // 放下去的那一刻就把连接位算好：自己的 getStateForNeighborUpdate 不会被自己触发。
        return state.with(LINK, computeLink(ctx.getWorld(), ctx.getBlockPos(), state));
    }

    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        return hasSupport(state, world, pos);
    }

    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        // 1) 墙没了 → 掉物品 + 自己变空气（薄板很容易被连带拆掉）。
        if (direction == state.get(FACING) && !canPlaceAt(state, world, pos)) {
            if (world instanceof World realWorld) {
                dropStacks(state, realWorld, pos);
            }
            return Blocks.AIR.getDefaultState();
        }
        // 2) 任何一侧的邻居变了都重算整张连接掩码（上下左右前后六个方向统一处理）。
        final int link = computeLink(world, pos, state);
        return link == state.get(LINK) ? state : state.with(LINK, link);
    }

    // ------------------------------------------------------------------ 刷子

    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        return IBlock.checkHoldingBrush(world, player, () -> {
            final int style = (state.get(STYLE) + 1) % 2;                 // 0 → 1 → 0
            final BlockState next = state.with(STYLE, style);
            // STYLE 变了，连接位也要跟着变（style 不等的两格不再算连上）：先落状态再重算掩码。
            final int link = computeLink(world, pos, next);
            world.setBlockState(pos, next.with(LINK, link), Block.NOTIFY_ALL);
            // 和 MTR 一样沿墙往左右各传一遍（同一排一起换形态）。
            final Direction facing = state.get(FACING);
            propagateStyle(world, pos, facing, facing.rotateYClockwise(), style);
            propagateStyle(world, pos, facing, facing.rotateYCounterclockwise(), style);
        });
    }

    /**
     * 沿墙把新形态传给连着的邻居（一条牌子整排一起换），遇到断口 / 换墙 / 换形态就停。
     * 只改 {@code style}，{@code link} 交给各自的 {@link #getStateForNeighborUpdate} 重算。
     */
    private void propagateStyle(final World world, final BlockPos pos, final Direction facing,
                                final Direction direction, final int style) {
        BlockPos cursor = pos;
        for (int guard = 0; guard < 64; guard++) {
            final BlockPos next = cursor.offset(direction);
            final BlockState neighbor = world.getBlockState(next);
            if (neighbor.getBlock() != this || neighbor.get(FACING) != facing) {
                return;
            }
            if (neighbor.get(STYLE) != style) {
                world.setBlockState(next, neighbor.with(STYLE, style), Block.NOTIFY_ALL);
            }
            cursor = next;
        }
    }
}
