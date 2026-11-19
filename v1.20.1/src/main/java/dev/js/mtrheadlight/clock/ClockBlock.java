// 时钟方块本体：任意位置可放、空碰撞箱、薄板轮廓、刷子开配置、红石充能状态。
package dev.js.mtrheadlight.clock;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 时钟方块 {@code mtrlte:clock} —— <b>不放模型、不放贴图</b>，钟面完全由
 * {@link ClockRenderer} 用代码画出来。
 *
 * <h2>放置</h2>
 * <p>{@code FACING} 用 {@link Properties#FACING}（<b>6 向</b>，含上下），放置时取
 * {@link ItemPlacementContext#getSide()} —— 就是玩家点的那一面，所以钟面正对玩家、贴在背后那格
 * 的表面上。与贴墙标识 / 挂墙倒数器一样，{@link #canPlaceAt} <b>恒真</b>：
 * 不需要支撑、邻居被拆也不消失（{@code getStateForNeighborUpdate} 沿用父类的「状态不变」）。
 *
 * <h2>碰撞箱 / 轮廓</h2>
 * <p>碰撞箱是空的（人可以直接走过去），只留一块 <b>10×10 px、1 px 厚</b> 的轮廓薄板贴在
 * {@code FACING} 那一面上 —— 不然玩家没有东西可以瞄准，右键也点不到。
 *
 * <h2>红石</h2>
 * <p>{@code POWERED} 由 {@link #neighborUpdate} 跟着周围红石更新；打开
 * {@code redstone} 配置后，未充能时 {@link ClockBlockEntity} 会停摆且不报时。
 * 方块本身不认识「停摆」，它只负责把 {@code POWERED} 写对。
 *
 * <h2>刷子</h2>
 * <p>手持刷子右键打开配置界面。判定必须用 {@code mtr.Items.BRUSH.get()} —— 原版
 * {@code Items.BRUSH} 是另一件物品，MTR 的刷子才是玩家手里那个。
 */
public final class ClockBlock extends Block implements BlockEntityProvider {

    /** 钟面朝向（6 向：钟面法线方向 = 这一面朝外）。 */
    public static final DirectionProperty FACING = Properties.FACING;
    /** 被红石充能（只在 {@code redstone} 配置打开时有意义）。 */
    public static final BooleanProperty POWERED = Properties.POWERED;

    /** 轮廓薄板：10 px 见方（3..13），厚 1 px，贴在 {@code FACING} 那一面。 */
    private static final VoxelShape SHAPE_NORTH = Block.createCuboidShape(3.0D, 3.0D, 0.0D, 13.0D, 13.0D, 1.0D);
    private static final VoxelShape SHAPE_SOUTH = Block.createCuboidShape(3.0D, 3.0D, 15.0D, 13.0D, 13.0D, 16.0D);
    private static final VoxelShape SHAPE_WEST = Block.createCuboidShape(0.0D, 3.0D, 3.0D, 1.0D, 13.0D, 13.0D);
    private static final VoxelShape SHAPE_EAST = Block.createCuboidShape(15.0D, 3.0D, 3.0D, 16.0D, 13.0D, 13.0D);
    private static final VoxelShape SHAPE_DOWN = Block.createCuboidShape(3.0D, 0.0D, 3.0D, 13.0D, 1.0D, 13.0D);
    private static final VoxelShape SHAPE_UP = Block.createCuboidShape(3.0D, 15.0D, 3.0D, 13.0D, 16.0D, 13.0D);

    public ClockBlock() {
        super(AbstractBlock.Settings.create()
                .mapColor(MapColor.OFF_WHITE)
                .strength(0.6F)
                .sounds(BlockSoundGroup.WOOD)
                .nonOpaque()
                // 没有模型、没有贴图，透明区域不会被画出来；nonOpaque 让相邻面的剔除按「不是满方块」算。
                .dynamicBounds());
        setDefaultState(getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(POWERED, Boolean.FALSE));
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    /** 轮廓：贴在 {@code FACING} 那一面的薄板。 */
    public static VoxelShape shapeFor(final Direction facing) {
        switch (facing) {
            case SOUTH:
                return SHAPE_SOUTH;
            case WEST:
                return SHAPE_WEST;
            case EAST:
                return SHAPE_EAST;
            case UP:
                return SHAPE_UP;
            case DOWN:
                return SHAPE_DOWN;
            case NORTH:
            default:
                return SHAPE_NORTH;
        }
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return shapeFor(state.get(FACING));
    }

    /** 碰撞箱是空的：时钟不挡人。 */
    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return VoxelShapes.empty();
    }

    /** 任意位置可放：不要求支撑，邻居被拆也不掉。 */
    @Override
    public boolean canPlaceAt(final BlockState state, final net.minecraft.world.WorldView world,
                              final BlockPos pos) {
        return true;
    }

    /** 放置：钟面朝向 = 玩家点的那一面（正对玩家），并顺手读一次红石。 */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final Direction facing = ctx.getSide();
        final BlockPos pos = ctx.getBlockPos();
        final boolean powered = ctx.getWorld().isReceivingRedstonePower(pos);
        return getDefaultState().with(FACING, facing).with(POWERED, Boolean.valueOf(powered));
    }

    /** 红石变化：只改 {@code POWERED}，不动别的属性、也不检查支撑。 */
    @Override
    public void neighborUpdate(final BlockState state, final World world, final BlockPos pos,
                               final Block block, final BlockPos sourcePos, final boolean notify) {
        if (world.isClient) {
            return;
        }
        final boolean powered = world.isReceivingRedstonePower(pos);
        if (powered != state.get(POWERED).booleanValue()) {
            world.setBlockState(pos, state.with(POWERED, Boolean.valueOf(powered)), Block.NOTIFY_ALL);
        }
    }

    /** 放下时如果那一刻就有红石信号，也要认（{@code getPlacementState} 已经读过了，这里只兜底）。 */
    @Override
    public void onBlockAdded(final BlockState state, final World world, final BlockPos pos,
                             final BlockState oldState, final boolean notify) {
        if (!world.isClient && world.isReceivingRedstonePower(pos) != state.get(POWERED).booleanValue()) {
            world.setBlockState(pos, state.with(POWERED, Boolean.valueOf(!state.get(POWERED).booleanValue())),
                    Block.NOTIFY_ALL);
        }
    }

    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new ClockBlockEntity(pos, state);
    }

    /** 方块实体每 tick 都要跑（客户端走指针、服务端报时）。 */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final World world, final BlockState state,
                                                                 final BlockEntityType<T> type) {
        if (ClockObjects.entityType() != null && type != ClockObjects.entityType()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<ClockBlockEntity>) ClockBlockEntity::tick;
    }

    /** 刷子右键 → 服务端下发配置 → 客户端开界面（见 {@link ClockNet}）。 */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        final ItemStack held = player.getStackInHand(hand);
        if (!held.isOf(mtr.Items.BRUSH.get())) {
            return ActionResult.PASS;
        }
        if (world.isClient || !(player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer)) {
            return ActionResult.SUCCESS;
        }
        ClockNet.sendOpen(serverPlayer, pos,
                world.getBlockEntity(pos) instanceof ClockBlockEntity entity ? entity : null);
        return ActionResult.SUCCESS;
    }
}
