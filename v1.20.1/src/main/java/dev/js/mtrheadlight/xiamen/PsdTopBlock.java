// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import java.util.function.Consumer;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * MTR {@code mtr.block.BlockPSDTop} + {@code TileEntityPSDTop} 的移植 —— 屏蔽门顶上那一格「吊板」。
 *
 * <h2>它不是一块面板，是「横梁 + 转角包边」</h2>
 * <p>外观完全由从 MTR 资源包原样搬来的 blockstate / model 决定（{@code psd_top} 的 40 条 multipart）：
 * 每格左右两个转角各画一块带圆弧底的包边，端头（{@code air_left / air_right = true}）换成封板，
 * {@code persistent != none} 时整体上移到 7.5px 处（贴墙那一档）。正面那条站名 / 线路图灯箱
 * 在 MTR 里是动态贴图（要 MTR 的线路数据），本模组用厦门贴图静态画一条（{@link RenderPsdTop}）。
 *
 * <h2>状态</h2>
 * <ul>
 *   <li>{@link #AIR_LEFT} / {@link #AIR_RIGHT}、{@link #SIDE_EXTENDED}、{@code facing}：
 *       放下方那一格（门 / 玻璃 / 端部）现算（{@link #getActualState}）；</li>
 *   <li>{@link #PERSISTENT}：拿屏障右键切换 NONE ↔ ARROW / ROUTE / BLANK，并沿同种方块传递一格；</li>
 *   <li>{@link #ARROW_DIRECTION}：拿刷子右键 +1 并传递。</li>
 * </ul>
 */
public class PsdTopBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** MTR 的 {@code PERSISTENT_OFFSET}（px，选择框用）。 */
    private static final float PERSISTENT_OFFSET = 7.5F;

    /** MTR 的 {@code PERSISTENT_OFFSET_SMALL}（格）。 */
    public static final float PERSISTENT_OFFSET_SMALL = 0.46875F;

    public static final BooleanProperty AIR_LEFT = BooleanProperty.of("air_left");
    public static final BooleanProperty AIR_RIGHT = BooleanProperty.of("air_right");
    /** MTR 里叫 {@code propagate_property}（原版就是这个名字，别改）。 */
    public static final IntProperty ARROW_DIRECTION = IntProperty.of("propagate_property", 0, 3);
    public static final EnumProperty<Persistent> PERSISTENT = EnumProperty.of("persistent", Persistent.class);

    /** MTR 的 {@code EnumPersistent}。 */
    public enum Persistent implements StringIdentifiable {
        NONE("none"),
        ARROW("arrow"),
        ROUTE("route"),
        BLANK("blank");

        private final String name;

        Persistent(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }
    }

    public PsdTopBlock() {
        // 设置与 MTR 的 BlockPSDTop 构造器完全一致
        super(AbstractBlock.Settings.create()
                .nonOpaque()
                .strength(2.0F)
                .dropsNothing());
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        return PsdProps.checkHoldingItem(world, player, item -> {
            Direction facing = PsdProps.prop(state, FACING);
            if (item == Items.BRUSH) {
                world.setBlockState(pos, state.cycle(ARROW_DIRECTION));
                this.propagate(world, pos, facing.rotateYClockwise(), ARROW_DIRECTION, 1);
                this.propagate(world, pos, facing.rotateYCounterclockwise(), ARROW_DIRECTION, 1);
            } else {
                boolean shouldBePersistent = PsdProps.prop(state, PERSISTENT) == Persistent.NONE;
                this.setState(world, pos, shouldBePersistent);
                this.propagate(world, pos, facing.rotateYClockwise(),
                        offsetPos -> this.setState(world, offsetPos, shouldBePersistent), 1);
                this.propagate(world, pos, facing.rotateYCounterclockwise(),
                        offsetPos -> this.setState(world, offsetPos, shouldBePersistent), 1);
            }
        }, null, Items.BRUSH, Items.BARRIER);
    }

    /** MTR 的 {@code setState}：按下方那一格决定 PERSISTENT 的取值。 */
    private void setState(World world, BlockPos pos, boolean shouldBePersistent) {
        Block blockBelow = world.getBlockState(pos.down()).getBlock();
        if (blockBelow instanceof PsdDoorBlock || blockBelow instanceof PsdGlassBlock
                || blockBelow instanceof PsdGlassEndBlock) {
            if (shouldBePersistent) {
                Persistent persistent = blockBelow instanceof PsdDoorBlock ? Persistent.ARROW
                        : blockBelow instanceof PsdGlassBlock ? Persistent.ROUTE
                        : Persistent.BLANK;
                world.setBlockState(pos, world.getBlockState(pos).with(PERSISTENT, persistent));
            } else {
                world.setBlockState(pos, world.getBlockState(pos).with(PERSISTENT, Persistent.NONE));
            }
        }
    }

    /** MTR 的 {@code propagate}：沿 direction 找到最近的一格同种方块，改它再继续。 */
    public <T extends Comparable<T>> void propagate(World world, BlockPos pos, Direction direction,
                                                    Property<T> property, int maxBlocksAway) {
        T value = PsdProps.prop(world.getBlockState(pos), property);
        this.propagate(world, pos, direction,
                offsetPos -> world.setBlockState(offsetPos, world.getBlockState(offsetPos).with(property, value)),
                maxBlocksAway);
    }

    public void propagate(World world, BlockPos pos, Direction direction, Consumer<BlockPos> callback, int maxBlocksAway) {
        for (int i = 1; i <= maxBlocksAway; i++) {
            BlockPos offsetPos = pos.offset(direction, i);
            if (this != world.getBlockState(offsetPos).getBlock()) {
                continue;
            }
            callback.accept(offsetPos);
            this.propagate(world, offsetPos, direction, callback, maxBlocksAway);
            return;
        }
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state;
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state;
    }

    /** MTR 的 {@code method_8389}：吊板没有物品形态，中键选取给玻璃。 */
    @Override
    public net.minecraft.item.Item asItem() {
        return XiamenObjects.psdGlass() == null ? Items.AIR : XiamenObjects.psdGlass().asItem();
    }

    @Override
    public ItemStack getPickStack(BlockView world, BlockPos pos, BlockState state) {
        return new ItemStack(this.asItem());
    }

    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        BlockPos belowPos = pos.down();
        BlockState belowState = world.getBlockState(belowPos);
        if (belowState.getBlock() instanceof PsdAPGBaseBlock) {
            belowState.getBlock().onBreak(world, belowPos, belowState, player);
            world.setBlockState(belowPos, Blocks.AIR.getDefaultState());
        }
        super.onBreak(world, pos, state, player);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN && PsdProps.prop(state, PERSISTENT) == Persistent.NONE
                && !(neighborState.getBlock() instanceof PsdAPGBaseBlock)) {
            return Blocks.AIR.getDefaultState();
        }
        return getActualState(world, pos);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        VoxelShape baseShape = PsdProps.shapeByDirection(0.0D,
                PsdProps.prop(state, PERSISTENT) == Persistent.NONE ? 0.0D : PERSISTENT_OFFSET,
                0.0D, 16.0D, 16.0D, 6.0D, PsdProps.prop(state, FACING));
        boolean airLeft = PsdProps.prop(state, AIR_LEFT);
        boolean airRight = PsdProps.prop(state, AIR_RIGHT);
        if (airLeft || airRight) {
            return PsdGlassEndBlock.getEndOutlineShape(baseShape, state, 16, 6, airLeft, airRight);
        }
        return baseShape;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, PsdProps.SIDE_EXTENDED, AIR_LEFT, AIR_RIGHT, ARROW_DIRECTION, PERSISTENT);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new TileEntityPsdTop(XiamenObjects.psdTopEntityType(), pos, state);
    }

    /** MTR 的 {@code getActualState}：从下方那一格推出 facing / side / air_left / air_right。 */
    public static BlockState getActualState(BlockView world, BlockPos pos) {
        Direction facing = null;
        PsdProps.Side side = null;
        boolean airLeft = false;
        boolean airRight = false;

        BlockState stateBelow = world.getBlockState(pos.down());
        Block blockBelow = stateBelow.getBlock();
        if (blockBelow instanceof PsdGlassBlock || blockBelow instanceof PsdDoorBlock) {
            side = blockBelow instanceof PsdDoorBlock
                    ? PsdProps.prop(stateBelow, PsdProps.SIDE)
                    : PsdProps.prop(stateBelow, PsdProps.SIDE_EXTENDED);
            facing = PsdProps.prop(stateBelow, FACING);
            if (blockBelow instanceof PsdGlassEndBlock) {
                if (PsdProps.prop(stateBelow, PsdGlassEndBlock.TOUCHING_LEFT) == PsdGlassEndBlock.GlassEndSide.AIR) {
                    airLeft = true;
                }
                if (PsdProps.prop(stateBelow, PsdGlassEndBlock.TOUCHING_RIGHT) == PsdGlassEndBlock.GlassEndSide.AIR) {
                    airRight = true;
                }
            }
        }

        BlockState oldState = world.getBlockState(pos);
        BlockState base = oldState.getBlock() instanceof PsdTopBlock
                ? oldState
                : XiamenObjects.psdTop().getDefaultState();
        BlockState newState = base.with(AIR_LEFT, airLeft).with(AIR_RIGHT, airRight);
        if (facing != null) {
            newState = newState.with(FACING, facing);
        }
        if (side != null) {
            newState = newState.with(PsdProps.SIDE_EXTENDED, side);
        }
        return newState;
    }

    /** MTR 的 {@code TileEntityPSDTop}（那边还带线路信息缓存，这里只留方块实体本身给渲染用）。 */
    public static class TileEntityPsdTop extends BlockEntity {

        public TileEntityPsdTop(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }
}
