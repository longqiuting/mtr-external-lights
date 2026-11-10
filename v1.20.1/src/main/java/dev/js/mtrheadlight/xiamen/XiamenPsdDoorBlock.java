package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * 厦门地铁风格的屏蔽门<b>门扇</b> —— MTR {@code mtr:block/BlockPSDDoor} +
 * {@code BlockPSDAPGDoorBase}（{@code extends BlockPSDAPGBase implements EntityBlockMapper}）的厦门版。
 *
 * <h2>属性（与 MTR 一一对应）</h2>
 * <ul>
 *   <li>{@code facing} —— {@code IBlock.FACING}；</li>
 *   <li>{@code half} —— {@code DoubleBlockHalf}，LOWER(y+0) / UPPER(y+1)；</li>
 *   <li>{@code side} —— {@code IBlock.SIDE}（过滤版，只有 LEFT / RIGHT）：对开的两扇；</li>
 *   <li>{@code end} —— {@code BlockPSDAPGDoorBase.END}：外侧挨着端部玻璃时换端部贴图；</li>
 *   <li>{@code unlocked} —— {@code BlockPSDAPGDoorBase.UNLOCKED}：右键切换的锁定状态；</li>
 *   <li>{@code open} —— 代替 BE 里的 {@code open:int(0..MAX_OPEN_VALUE=32)}：
 *       放 blockstate 里由原版存档 + 同步，{@link XiamenPsdDoorEntity} 只留客户端插值。</li>
 * </ul>
 *
 * <h2>开关</h2>
 * <p>MTR 里门是<b>列车</b>开的（{@code TrainServer}），玩家右键只切
 * {@code UNLOCKED}（{@code BlockPSDAPGDoorBase#onUse} 里是
 * {@code IBlock.checkHoldingBrush(..., () -> lockDoor(...))}，提示
 * {@code gui.mtr.psd_apg_door_locked} / {@code _unlocked}），并且 {@code tick()} 在
 * {@code UNLOCKED} 时把门强制关上。这个模组没有列车，所以保留同一套「锁定」语义，
 * 另外给两条开门的途径：
 * <ol>
 *   <li><b>红石</b> —— 通电开、断电关（相当于「车到了」的信号）；</li>
 *   <li><b>潜行 + 右键</b> —— 手动开 / 关（只在 {@code unlocked} 时有效，否则提示先解锁）。</li>
 * </ol>
 * 开门时下段 / 上段两格的 {@code getCollisionShape} 都返回空
 * （MTR 是读 BE 的 {@code isOpen()}，这里读 blockstate，效果一样）。
 */
public class XiamenPsdDoorBlock extends XiamenPsdBaseBlock implements BlockEntityProvider {

    /** 开着 = 门扇已经滑进旁边的玻璃后面，碰撞箱清空。 */
    public static final BooleanProperty OPEN = BooleanProperty.of("open");

    /** 靠端部玻璃的那一扇（MTR 的 {@code END}）—— 决定用不用 {@code psd_door_end_*} 那套贴图。 */
    public static final BooleanProperty END = BooleanProperty.of("end");

    /**
     * 上一次看到的红石电平。
     *
     * <p>MTR 那边开门是列车发的信号，不存在「玩家手动开」和「红石」抢方向盘的问题；
     * 这里两条途径并存，所以用一个锁存属性记住电平：只有<b>电平变化</b>时才去改
     * {@link #OPEN}，否则「潜行右键手动开」会立刻被 {@code neighborUpdate} 里
     * 「没电 → 关门」的那条规则弹回去。
     */
    public static final BooleanProperty POWERED = BooleanProperty.of("powered");

    public XiamenPsdDoorBlock(AbstractBlock.Settings settings) {
        // 门扇比玻璃厚一点（基准系 x∈[0,4]）：MTR 的 getOutlineShape 也是按 4px 给的；
        // 门叶本体画在 x∈[0,2]，滑开时藏进玻璃（x∈[2,4]）后面。
        super(settings, 0.0D, 4.0D);
        this.setDefaultState(this.getDefaultState()
                .with(SIDE, EnumSide.LEFT)
                .with(OPEN, false)
                .with(END, false)
                .with(UNLOCKED, false)
                .with(POWERED, false));
    }

    @Override
    public Kind kind() {
        return Kind.DOOR;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, SIDE, OPEN, END, UNLOCKED, POWERED);
    }

    // ------------------------------------------------------------------ 联动

    /**
     * 邻居变了就重算 {@code end}：MTR 在 {@code getStateForNeighborUpdate} 里读
     * {@code pos.offset(sideDirection.getOpposite())}（也就是说每扇门的<b>外侧</b>）
     * 是不是端部玻璃，这里判同一个位置、同一个条件。
     */
    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        final boolean end = isEndPanel(world.getBlockState(pos.offset(sideDirection(state).getOpposite())));
        return state.get(END) == end ? state : state.with(END, end);
    }

    private static boolean isEndPanel(BlockState state) {
        return state.getBlock() instanceof XiamenPsdEndBlock;
    }

    /** 右键：潜行 = 手动开 / 关（需已解锁），否则 = 锁定 / 解锁（MTR 的行为）。 */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand,
                              BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (player.isSneaking()) {
            if (!state.get(UNLOCKED)) {
                player.sendMessage(Text.translatable("message.mtrlte.psd_locked"), true);
                return ActionResult.SUCCESS;
            }
            final boolean open = !state.get(OPEN);
            setOpen(world, pos, state, open);
            world.playSound(null, pos,
                    open ? SoundEvents.BLOCK_IRON_DOOR_OPEN : SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                    SoundCategory.BLOCKS, 0.55F, 1.35F);
            return ActionResult.SUCCESS;
        }
        final boolean unlocked = !state.get(UNLOCKED);
        setUnlocked(world, pos, state, unlocked);
        player.sendMessage(Text.translatable(unlocked ? "message.mtrlte.psd_unlocked" : "message.mtrlte.psd_locked"),
                true);
        return ActionResult.SUCCESS;
    }

    /** 红石：电平<b>变化</b>时才开 / 关（见 {@link #POWERED}）。 */
    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos,
                               boolean notify) {
        if (world.isClient) {
            return;
        }
        final boolean powered = world.isReceivingRedstonePower(pos);
        if (powered != state.get(POWERED)) {
            updatePair(world, pos, state, POWERED, powered);
            updatePair(world, pos, state, OPEN, powered);
        }
    }

    private static void setUnlocked(World world, BlockPos pos, BlockState state, boolean unlocked) {
        updatePair(world, pos, state, UNLOCKED, unlocked);
    }

    /** 整组一起开 / 关：自己这一列的两格 + 对开那一列的两格。 */
    private static void setOpen(World world, BlockPos pos, BlockState state, boolean open) {
        updatePair(world, pos, state, OPEN, open);
    }

    private static void updatePair(World world, BlockPos pos, BlockState state, BooleanProperty property,
                                   boolean value) {
        forEachLeafHalf(world, pos, state, s -> {
            if (s.state().get(property) != value) {
                world.setBlockState(s.pos(), s.state().with(property, value), Block.NOTIFY_ALL);
            }
        });
    }

    private record Part(BlockPos pos, BlockState state) {
    }

    /** 遍历「两扇 × 上下两段」四个位置。 */
    private static void forEachLeafHalf(World world, BlockPos pos, BlockState state, java.util.function.Consumer<Part> action) {
        final BlockPos base = state.get(HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos;
        final Direction facing = state.get(FACING);
        final Direction left = facing.rotateYCounterclockwise();
        final BlockPos partnerBase = state.get(SIDE) == EnumSide.LEFT
                ? base.offset(left)
                : base.offset(left.getOpposite());
        for (BlockPos col : new BlockPos[]{base, partnerBase}) {
            for (int dy = 0; dy < 2; dy++) {
                final BlockPos p = col.up(dy);
                final BlockState s = world.getBlockState(p);
                if (s.getBlock() == state.getBlock() && s.get(FACING) == facing) {
                    action.accept(new Part(p, s));
                }
            }
        }
    }

    // ------------------------------------------------------------------ 形状

    /** 开门时下段 / 上段都没有碰撞（MTR 同款）。 */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN) ? VoxelShapes.empty() : super.getCollisionShape(state, world, pos, context);
    }

    // ------------------------------------------------------------------ BE

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new XiamenPsdDoorEntity(pos, state);
    }

    /** 只在客户端 tick：BE 里的浮点只是给渲染插值用的。 */
    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (!world.isClient) {
            return null;
        }
        return (BlockEntityTicker<T>) (w, p, s, be) -> {
            if (be instanceof XiamenPsdDoorEntity door) {
                door.tickAnimation();
            }
        };
    }
}
