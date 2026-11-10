package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 厦门地铁风格的屏蔽门<b>门扇</b>（可开合，双扇对开）。
 *
 * <h2>这套做法是照着 MTR / 天津地铁抄的</h2>
 * <p>MTR 3.x 的屏蔽门（{@code mtr:block/BlockPSDAPGDoorBase}）不是「一块会换模型的板」，
 * 而是：
 * <ol>
 *   <li>一个方块 = <b>一扇门叶</b>（1 格宽、1 格高），blockstate 里带
 *       {@code facing / side(LEFT/RIGHT) / half(LOWER/UPPER) / end / unlocked}；</li>
 *   <li>真正「开着多少」存在 <b>BlockEntity</b> 里（{@code TileEntityPSDAPGDoorBase.open:int}，
 *       {@code MAX_OPEN_VALUE = 32}），客户端另有一个 {@code openClient:float} 做插值；</li>
 *   <li>门叶的滑动、贴图、门灯全部由 {@code mtr:render/RenderPSDAPGDoor}（BlockEntityRenderer）
 *       画：{@code matrices.translate(open * (side ? -1 : 1), 0, 0)} 把整块板沿墙面推出去，
 *       上下两段用不同贴图（{@code psd_door_top_left_1.png} / {@code psd_door_bottom_left_1.png}）；</li>
 *   <li>碰撞箱跟着开合走：{@code getCollisionShape()} 在 {@code isOpen()} 时直接返回
 *       {@code VoxelShapes.empty()}，关着才返回那块 16×16×4 的板；</li>
 *   <li>成对关系靠相邻方块自己判定（{@code SIDE}），放置时一次放<b>两扇</b>
 *       （{@code ItemPSDAPGBase#useOn} 里 {@code horizontalBlocks = 2, height = 2}）。</li>
 * </ol>
 * 天津地铁（{@code ziyue.tjmetro.block.BlockPSDDoorTianjin}）完全就是
 * {@code extends BlockPSDAPGDoorBase} + 自己的 {@code RenderTianjinPSDDoor}，
 * 也是同一套：BE 记 open、渲染器按 {@code entity.getOpen(...)} 平移贴图，
 * 因此我们这里也用「方块 + BlockEntity + BlockEntityRenderer」而不是静态模型。
 *
 * <h2>和 MTR 的两点差异（有意为之）</h2>
 * <ul>
 *   <li>MTR 一扇门是 <b>2 格高</b>（{@code HALF} 上下两半各一个方块）；厦门这套的玻璃板 / 端部
 *       本来就是 1 格高，为了不把玩家已建好的站台墙整体抬高，这里保持 <b>1 格高</b>，
 *       贴图在同一个方块内上下分段：下半 8px 用 bottom 贴图、上半 8px 用 top 贴图。</li>
 *   <li>开关状态放在 <b>blockstate</b>（{@code open=true/false}）而不是 BE 的 int，
 *       这样原版就负责同步/存档，不需要自己发包；BE 只保留客户端插值用的浮点进度。
 *       碰撞箱读的也是这个 blockstate。</li>
 * </ul>
 *
 * <p>方块 id 仍是 {@code mtrlte:xiamen_psd_door}（旧存档里的单块门叶读出来就是
 * {@code side=left, open=false} 的默认值，继续能用）。
 */
public class XiamenPsdDoorBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** 门叶在门洞里的位置：左扇往左滑、右扇往右滑，于是「对开」。 */
    public enum Side implements StringIdentifiable {
        LEFT("left"),
        RIGHT("right");

        private final String name;

        Side(String name) {
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

    public static final EnumProperty<Side> SIDE = EnumProperty.of("side", Side.class);

    /** 开着 = 门叶已经滑出去，碰撞箱清空。 */
    public static final BooleanProperty OPEN = BooleanProperty.of("open");

    /**
     * 关门时的碰撞 / 外框：MTR 的 {@code BlockPSDAPGBase#getOutlineShape} 用的是
     * {@code getVoxelShapeByDirection(0, 0, 0, 16, 16, 4, facing)} —— 从方块边缘起 4px 厚，
     * 比 2px 的门板视觉厚一点，免得贴着墙走的时候卡进板里。照抄。
     */
    private static final VoxelShape[] CLOSED = {
            createCuboidShape(0.0D, 0.0D, 0.0D, 4.0D, 16.0D, 16.0D),      // facing=west  (y=0)
            createCuboidShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 4.0D),      // facing=north (y=90)
            createCuboidShape(12.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),    // facing=east  (y=180)
            createCuboidShape(0.0D, 0.0D, 12.0D, 16.0D, 16.0D, 16.0D)     // facing=south (y=270)
    };

    public XiamenPsdDoorBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(SIDE, Side.LEFT)
                .with(OPEN, false));
    }

    /** {@link HorizontalFacingBlock} 自己不加 FACING，得手动加，否则构造期就抛。 */
    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIDE, OPEN);
    }

    /**
     * 「左扇」的方向 —— 站在站台侧看门时，左手边。
     *
     * <p>贴图的正面朝 +X（见 {@link XiamenPsdDoorRenderer}：MTR 的 {@code psd_glass_left_base}
     * 把 {@code #front} 贴在 x=4 那个面，门叶按同样的朝向放在 x∈[0,2]），所以 facing=west 时
     * 观众站在东侧往西看，左手边是 +Z = south = {@code facing.rotateYCounterclockwise()}。
     * 渲染器里左扇沿局部 +Z 滑，两处必须一致。
     */
    public static Direction leftDirection(Direction facing) {
        return facing.rotateYCounterclockwise();
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        final Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
        BlockState state = this.getDefaultState().with(FACING, facing).with(OPEN, false);
        // 左扇在「我 + left」的位置；那边已经有门叶 → 我是右扇。
        if (isDoor(ctx.getWorld().getBlockState(ctx.getBlockPos().offset(leftDirection(facing))), facing)) {
            state = state.with(SIDE, Side.RIGHT);
        }
        return state;
    }

    /**
     * 和 MTR 的物品一样：一次放置生成<b>两扇</b>（左扇 + 右扇），否则玩家永远只能放出一扇
     * 「单开门」，双扇对开就成不了默认行为。省下的那一块不收物品 —— MTR 也是这么做的。
     */
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        if (world.isClient) {
            return;
        }
        final Direction facing = state.get(FACING);
        if (state.get(SIDE) == Side.LEFT) {
            final BlockPos partnerPos = pos.offset(leftDirection(facing).getOpposite());
            if (world.isAir(partnerPos) && isDoor(world.getBlockState(pos.offset(leftDirection(facing))), facing) == false) {
                world.setBlockState(partnerPos, this.getDefaultState()
                        .with(FACING, facing)
                        .with(SIDE, Side.RIGHT)
                        .with(OPEN, state.get(OPEN)), Block.NOTIFY_ALL);
            }
        }
        refreshSides(world, pos);
    }

    /** 拆掉一扇就把另一半也拆掉，别留半扇在墙里。 */
    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            final Direction left = leftDirection(state.get(FACING));
            for (Direction d : new Direction[]{left, left.getOpposite()}) {
                final BlockPos otherPos = pos.offset(d);
                final BlockState other = world.getBlockState(otherPos);
                if (isDoor(other, state.get(FACING)) && other.get(SIDE) != state.get(SIDE)) {
                    world.removeBlock(otherPos, false);
                    break;
                }
            }
        }
        super.onBreak(world, pos, state, player);
    }

    /** 右键开关整对门（两扇 + 上下如果堆了同款门）。 */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            final boolean open = !state.get(OPEN);
            applyOpen(world, pos, state, open);
            world.playSound(null, pos,
                    open ? SoundEvents.BLOCK_IRON_DOOR_OPEN : SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                    SoundCategory.BLOCKS, 0.55F, 1.35F);
        }
        return ActionResult.success(world.isClient);
    }

    /** 红石：通电就开、断电就关（手动右键仍然可用，两者冲突时以最后一次信号为准）。 */
    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world.isClient) {
            return;
        }
        final boolean powered = world.isReceivingRedstonePower(pos);
        if (powered != state.get(OPEN)) {
            applyOpen(world, pos, state, powered);
        }
    }

    /**
     * 整对门一起开/关：自己 + 左右沿墙各一格（MTR 的门也是一对对来的），
     * 顺带把上下堆起来的同款门也带上（有人会叠两块做 2 格高的门洞）。
     */
    private void applyOpen(World world, BlockPos pos, BlockState state, boolean open) {
        final Direction left = leftDirection(state.get(FACING));
        for (Direction d : new Direction[]{left, null, left.getOpposite(), Direction.UP, Direction.DOWN}) {
            final BlockPos p = d == null ? pos : pos.offset(d);
            final BlockState s = world.getBlockState(p);
            if (s.getBlock() == this && s.get(FACING) == state.get(FACING) && s.get(OPEN) != open) {
                world.setBlockState(p, s.with(OPEN, open), Block.NOTIFY_ALL);
            }
        }
    }

    /** 放置后修正这条门两侧的 side（先放右扇再放左扇时，右扇要变成左扇）。 */
    private void refreshSides(World world, BlockPos pos) {
        final BlockState here = world.getBlockState(pos);
        if (here.getBlock() != this) {
            return;
        }
        final Direction left = leftDirection(here.get(FACING));
        for (Direction d : new Direction[]{null, left, left.getOpposite()}) {
            final BlockPos p = d == null ? pos : pos.offset(d);
            final BlockState s = world.getBlockState(p);
            if (s.getBlock() != this || s.get(FACING) != here.get(FACING)) {
                continue;
            }
            final boolean right = isDoor(world.getBlockState(p.offset(left)), here.get(FACING));
            final Side side = right ? Side.RIGHT : Side.LEFT;
            if (s.get(SIDE) != side) {
                world.setBlockState(p, s.with(SIDE, side), Block.NOTIFY_ALL);
            }
        }
    }

    private boolean isDoor(BlockState state, Direction facing) {
        return state.getBlock() == this && state.get(FACING) == facing;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return CLOSED[shapeIndex(state.get(FACING))];
    }

    /** 开门后完全没有碰撞（MTR 的 {@code BlockPSDAPGDoorBase#getCollisionShape} 同样返回 empty）。 */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN) ? VoxelShapes.empty() : CLOSED[shapeIndex(state.get(FACING))];
    }

    private static int shapeIndex(Direction facing) {
        return switch (facing) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 3;
            default -> 0;
        };
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new XiamenPsdDoorEntity(pos, state);
    }

    /** 只在客户端 tick：BE 里那个浮点只是给渲染插值用的。 */
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
