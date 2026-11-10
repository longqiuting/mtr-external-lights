package dev.js.mtrheadlight.xiamen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 厦门屏蔽门的公共底座 —— 对应 MTR 的 {@code mtr:block/BlockPSDAPGBase}
 * （{@code extends BlockDirectionalDoubleBlockBase implements IBlock}）。
 *
 * <h2>竖向三层：{@code HALF} 是两段，顶层是独立方块（javap 实证）</h2>
 * <p>{@code IBlock} 的静态初始化里第一句就是：
 * <pre>
 * getstatic net/minecraft/class_2741.field_12533   // = Properties.DOUBLE_BLOCK_HALF
 * putstatic mtr/block/IBlock.HALF
 * </pre>
 * 也就是 {@code IBlock.HALF} 就是原版的 {@code Properties.DOUBLE_BLOCK_HALF}，值域只有
 * {@code DoubleBlockHalf.LOWER / UPPER}（{@code field_12607} / {@code field_12609}）。
 * 所以屏蔽门的「三层」在 MTR 里是这么拼的：
 * <ul>
 *   <li>y+0 —— 下段，{@code half=lower}；</li>
 *   <li>y+1 —— 上段，{@code half=upper}；</li>
 *   <li>y+2 —— <b>另一个方块</b> {@code BlockPSDTop}（吊板），不带 HALF。</li>
 * </ul>
 * 证据：{@code ItemPSDAPGBase.useOnBlock} 的 {@code dh} 循环只有 {@code dh &lt; 2} 两次
 * （写进去的是 {@code LOWER}/{@code UPPER}），循环外再单独
 * {@code world.setBlockState(pos.up(2), BlockPSDTop.getActualState(world, pos.up(2)))}。
 *
 * <p><b>不是 {@code ITripleBlock}</b>：{@code mtr.block.ITripleBlock} 里只有一个
 * {@code ODD}(BooleanProperty) 和两个 helper 方法（{@code updateShape} / {@code playerWillDestroy}），
 * 没有 LOWER/MIDDLE/UPPER 常量；jar 里引用了 {@code ITripleBlock} 的类只有
 * {@code BlockLiftDoorOdd} / {@code BlockLiftPanelBase}（电梯），屏蔽门一族一个都没引用。
 * 三层高度这里是靠「两段 HALF + 一个独立的吊板方块」实现的，效果和「三层」一样，
 * 但和 MTR 的类结构对得上。
 *
 * <h2>横向：SIDE / SIDE_EXTENDED（javap 实证）</h2>
 * <p>{@code IBlock} 里有<b>两个</b>同名 {@code side} 属性：
 * <pre>
 * SIDE_EXTENDED = EnumProperty.of("side", EnumSide.class);            // 四个值全开
 * SIDE          = EnumProperty.of("side", EnumSide.class, predicate); // 过滤掉 MIDDLE / SINGLE
 * </pre>
 * {@code IBlock.EnumSide} 的四个常量是 {@code LEFT / RIGHT / MIDDLE / SINGLE}。
 * 门用 {@code SIDE}（只有 LEFT/RIGHT，对开两扇），玻璃用 {@code SIDE_EXTENDED}
 * （LEFT=一段玻璃的第一块、RIGHT=最后一块、MIDDLE=中间、SINGLE=孤零零一块）。
 *
 * <h2>联动</h2>
 * <p>MTR 靠 {@code getStateForNeighborUpdate} 返回空气自毁；这里不在邻居更新里删方块，
 * 改成：{@link XiamenPsdItem} 一次放满整列，{@link #breakStructure} 拆任意一格带走整列。
 */
public abstract class XiamenPsdBaseBlock extends HorizontalFacingBlock {

    /** 上下两段 —— {@code Properties.DOUBLE_BLOCK_HALF}（MTR 的 {@code IBlock.HALF} 就是它）。 */
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    /** 一整列的高度：下段 + 上段 + 吊板。 */
    public static final int COLUMN_HEIGHT = 3;

    /** MTR 的 {@code IBlock.EnumSide}：LEFT / RIGHT / MIDDLE / SINGLE。 */
    public enum EnumSide implements StringIdentifiable {
        LEFT("left"),
        RIGHT("right"),
        MIDDLE("middle"),
        SINGLE("single");

        private final String name;

        EnumSide(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    /** MTR 的 {@code BlockPSDAPGGlassEndBase.EnumPSDAPGGlassEndSide}：AIR / DOOR / NONE。 */
    public enum EndSide implements StringIdentifiable {
        AIR("air"),
        DOOR("door"),
        NONE("none");

        private final String name;

        EndSide(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    /** MTR 的 {@code IBlock.SIDE}：同名 "side"，但只允许 LEFT / RIGHT（门扇用）。 */
    public static final EnumProperty<EnumSide> SIDE =
            EnumProperty.of("side", EnumSide.class, side -> side == EnumSide.LEFT || side == EnumSide.RIGHT);

    /** MTR 的 {@code IBlock.SIDE_EXTENDED}：完整的 LEFT / RIGHT / MIDDLE / SINGLE（玻璃用）。 */
    public static final EnumProperty<EnumSide> SIDE_EXTENDED = EnumProperty.of("side", EnumSide.class);

    /** MTR 的 {@code BlockPSDAPGGlassEndBase.TOUCHING_LEFT / TOUCHING_RIGHT}。 */
    public static final EnumProperty<EndSide> TOUCHING_LEFT = EnumProperty.of("touching_left", EndSide.class);
    public static final EnumProperty<EndSide> TOUCHING_RIGHT = EnumProperty.of("touching_right", EndSide.class);

    /** MTR 的 {@code BlockPSDAPGDoorBase.UNLOCKED}：右键切换的锁定状态。 */
    public static final BooleanProperty UNLOCKED = BooleanProperty.of("unlocked");

    /** 三类变体 —— 对应 MTR 的 {@code EnumPSDAPGItem}（PSD_APG_DOOR / GLASS / GLASS_END）。 */
    public enum Kind implements StringIdentifiable {
        DOOR("door"),
        GLASS("glass"),
        END("end");

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    private final VoxelShape[] outlineShapes;
    private final VoxelShape[] collisionShapes;

    /**
     * @param x0 基准系（facing=west）里板子的最小 x（px）
     * @param x1 最大 x
     */
    protected XiamenPsdBaseBlock(AbstractBlock.Settings settings, double x0, double x1) {
        super(settings);
        this.outlineShapes = boxes(x0, 0.0D, 0.0D, x1, 16.0D, 16.0D);
        this.collisionShapes = this.outlineShapes;
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(HALF, DoubleBlockHalf.LOWER));
    }

    /** 这一格属于哪一类变体。 */
    public abstract Kind kind();

    /** {@link HorizontalFacingBlock} 自己不注册 FACING，必须手动加。 */
    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return this.outlineShapes[facingIndex(state.get(FACING))];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return this.collisionShapes[facingIndex(state.get(FACING))];
    }

    /** 拆任意一格（下段 / 上段 / 吊板）都把整列带走，门还要带走对开的那一扇。 */
    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            breakStructure(world, pos, state);
        }
        super.onBreak(world, pos, state, player);
    }

    /**
     * 清掉整列（门：两列）。
     *
     * <p>用 {@code world.setBlockState(AIR, NOTIFY_ALL)} 而不是 {@code world.removeBlock}：
     * 后者会再走一遍 {@code onBreak} + 掉落表，拆一格会掉一堆物品；前者不掉落，
     * 于是「拆任意一格」只会掉玩家真正打掉那一格的一份（和原版床 / 门一样）。
     */
    public static void breakStructure(World world, BlockPos pos, BlockState state) {
        final Block block = state.getBlock();
        final Direction facing = state.get(FACING);
        final BlockPos base;
        final boolean door;
        final EnumSide side;
        if (block instanceof XiamenPsdTopBlock) {
            base = pos.down(COLUMN_HEIGHT - 1);
            final BlockState below = world.getBlockState(base);
            if (!(below.getBlock() instanceof XiamenPsdBaseBlock) || below.get(FACING) != facing) {
                return;
            }
            door = below.getBlock() instanceof XiamenPsdDoorBlock;
            side = door ? below.get(XiamenPsdDoorBlock.SIDE) : null;
        } else if (block instanceof XiamenPsdBaseBlock) {
            door = block instanceof XiamenPsdDoorBlock;
            side = door ? state.get(XiamenPsdDoorBlock.SIDE) : null;
            base = state.get(HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos;
        } else {
            return;
        }

        final List<BlockPos> columns = new ArrayList<>(2);
        columns.add(base);
        if (door && side != null) {
            final Direction left = facing.rotateYCounterclockwise();
            columns.add(side == EnumSide.LEFT ? base.offset(left) : base.offset(left.getOpposite()));
        }

        for (int c = 0; c < columns.size(); c++) {
            final BlockPos col = columns.get(c);
            if (c > 0) {
                // 只有「对开的那一扇」跟着拆：同朝向 + side 不同，别误伤隔壁另一组门。
                final BlockState partner = world.getBlockState(col);
                if (!(partner.getBlock() instanceof XiamenPsdDoorBlock)
                        || partner.get(FACING) != facing
                        || partner.get(XiamenPsdDoorBlock.SIDE) == side) {
                    continue;
                }
            }
            for (int dy = 0; dy < COLUMN_HEIGHT; dy++) {
                final BlockPos p = col.up(dy);
                if (p.equals(pos)) {
                    continue;
                }
                final BlockState s = world.getBlockState(p);
                if (!isPart(s) || s.get(FACING) != facing) {
                    continue;
                }
                world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    /** 这一格是不是这套屏蔽门的一部分（下段 / 上段 / 吊板）。 */
    public static boolean isPart(BlockState state) {
        return state.getBlock() instanceof XiamenPsdBaseBlock || state.getBlock() instanceof XiamenPsdTopBlock;
    }

    /** 「左扇 / 右扇」的外侧方向 —— MTR 的 {@code IBlock.getSideDirection}。 */
    public static Direction sideDirection(BlockState state) {
        final Direction facing = state.get(FACING);
        return state.get(XiamenPsdDoorBlock.SIDE) == EnumSide.LEFT
                ? facing.rotateYCounterclockwise()
                : facing.rotateYClockwise();
    }

    /** facing → 四向形状 / blockstate y 旋转下标（west 0°、north 90°、east 180°、south 270°）。 */
    public static int facingIndex(Direction facing) {
        return switch (facing) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 3;
            default -> 0;
        };
    }

    /**
     * 按 blockstate 的 y 旋转把基准系（facing=west）的盒子换算成四向形状：
     * 0° 原样、90° {@code (x,z)→(16-z,x)}、180° {@code (16-x,16-z)}、270° {@code (z,16-x)}。
     */
    private static VoxelShape[] boxes(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new VoxelShape[]{
                createCuboidShape(x0, y0, z0, x1, y1, z1),
                createCuboidShape(16.0D - z1, y0, x0, 16.0D - z0, y1, x1),
                createCuboidShape(16.0D - x1, y0, 16.0D - z1, 16.0D - x0, y1, 16.0D - z0),
                createCuboidShape(z0, y0, 16.0D - x1, z1, y1, 16.0D - x0)
        };
    }
}
