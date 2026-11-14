// 方速端门：普通方块 + 方块实体，玩家手持 MTR 驾驶钥匙右键开 / 关（不接列车）。
package dev.js.mtrheadlight.xiamen.fangsu;

import net.minecraft.block.Material;
import mtr.block.IBlock;
import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
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
import net.minecraft.world.WorldAccess;

/**
 * 方速<b>端门</b>（{@code fangsu_psd_<brt|mtr|drl|shlow|shhigh>duanmen_left / _right}，5 款 × 左右 = 10 块）：
 * <b>玩家手持 MTR 驾驶钥匙右键开 / 关</b>，<b>与列车完全无关</b>。
 *
 * <h2>为什么不再继承 MTR 的 {@code BlockPSDAPGDoorBase}</h2>
 * <p>上一版端门和滑动门共用 {@code FangsuPsdDoorBlock}（继承 MTR 的 PSD 门基类），好处是列车进站会自动开、
 * 发车自动关。但端门在站台上是「人工复位」的门 —— 用户要求：<b>手持驾驶钥匙右键开关，不需要和列车联动</b>。
 * 而 MTR 的自动开门是拿<b>它自己的类</b>做 {@code instanceof} 找出来的（javap 3.6.4.2）：
 * <pre>
 *   TrainServer#openDoors:  block instanceof BlockPSDAPGDoorBase → be instanceof TileEntityPSDAPGDoorBase
 *                           → state.unlocked → be.setOpen(doorValue * 64) → scheduleBlockTick(dwell)
 * </pre>
 * 只要还是那个类的子类，端门就会被列车打开；只要方块实体还是 {@code TileEntityPSDAPGDoorBase}，
 * 就不能自由决定「谁来开关」。所以这里换成<b>普通方块 + {@code BlockEntityProvider}</b>
 * （方块实体是 {@link FangsuPsdEndDoorEntity}），列车那条链子从根上断开。
 *
 * <h2>保留了什么（和上一版逐条对齐）</h2>
 * <ul>
 *   <li><b>几何 / 材质</b>：blockstate 还是「只有粒子」的空壳，画面全交给 BER
 *       （{@link FangsuPsdEndDoorRenderer} → {@link FangsuPsdDraw}），和滑动门同一套；</li>
 *   <li><b>两格高</b>：{@code HALF}（{@link Properties#DOUBLE_BLOCK_HALF}）上下成对，放置时自动补上半段
 *       （{@link #onPlaced}）、拆一半另一半跟着消失（{@link #getStateForNeighborUpdate} /
 *       {@link #onBreak}）—— 抄的是 MTR {@code BlockDirectionalDoubleBlockBase} 的行为；</li>
 *   <li><b>碰撞切换</b>：关着挡人、开着是空集（{@link #getCollisionShape}）；轮廓永远是关门的形状，
 *       开着也点得到；{@code getCullingShape} 空集（薄门不挡邻居的面）；</li>
 *   <li><b>动画</b>：7 tick 开 / 关到底，插值在 {@link FangsuPsdProgress}（和滑动门同一个）；
 *       门扇绕 {@code doorPos} 转 ±90°，参数来自 {@link FangsuPsdDef.Motion}。</li>
 * </ul>
 *
 * <h2>开合状态存在哪：blockstate 的 {@link #OPEN}</h2>
 * <p>不存方块实体、也不发自定义包 —— {@code OPEN} 是方块状态属性，改它就只能走
 * {@code setBlockState}，而 {@code setBlockState} 本身就会把状态同步给所有客户端。
 * 客户端渲染时把 {@code OPEN} 当目标值，用 {@link FangsuPsdProgress} 平滑推过去，
 * 所以「状态是即时的、动画是渐进的」。
 *
 * <h2>右键</h2>
 * <table border="1">
 *   <caption>交互</caption>
 *   <tr><th>手上拿的</th><th>结果</th></tr>
 *   <tr><td>MTR 驾驶钥匙（{@code mtr:driver_key}）</td><td>开 / 关（上下两段一起，7 tick 动画）</td></tr>
 *   <tr><td>别的任何东西（含 MTR 刷子）</td><td>不响应（{@link ActionResult#PASS}，让原版继续处理放置 / 使用）</td></tr>
 * </table>
 */
public class FangsuPsdEndDoorBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** 开着吗。两段（上 / 下）各存一份，改的时候一起改。 */
    public static final BooleanProperty OPEN = BooleanProperty.of("open");

    /** 上 / 下两段（索引与 {@link DoubleBlockHalf} 的对应见 {@link #halfIndex}）。 */
    private static final int HALVES = 2;

    /** 水平四向的 4 个角度。 */
    private static final int FACINGS = 4;

    private final FangsuPsdDef def;

    /** 碰撞盒：{@code [half][绕 Y 的 90° 步数]}（步数的取法见 {@link FangsuPsdDoorBlock#shapeQuarterTurns}）。 */
    private final VoxelShape[][] collision;

    /** 轮廓盒（选中 / 高亮）：永远用关门的形状，开门后也还能点到这块门。 */
    private final VoxelShape[][] outline;

    private Item item;

    public FangsuPsdEndDoorBlock(final FangsuPsdDef def) {
        // 金属端门：硬度和声音按铁门那一档（MTR 的门也是金属感），薄片所以 nonOpaque。
        super(AbstractBlock.Settings.of(Material.METAL)
                .mapColor(MapColor.IRON_GRAY)
                .strength(2.0F)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque());
        this.def = def;
        this.collision = new VoxelShape[HALVES][FACINGS];
        this.outline = new VoxelShape[HALVES][FACINGS];
        for (int half = 0; half < HALVES; half++) {
            for (int rotation = 0; rotation < FACINGS; rotation++) {
                final VoxelShape shape = FangsuPsdDoorBlock.buildShape(def.closeBoxes(), half, rotation * 90);
                this.collision[half][rotation] = shape;
                this.outline[half][rotation] = shape;
            }
        }
        setDefaultState(getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .with(OPEN, false));
    }

    /** 这个方块对应的描述（渲染器 / 注册表都从这里取）。 */
    public FangsuPsdDef def() {
        return this.def;
    }

    /** 物品（注册时回填；没有物品形态时是空气）。 */
    public void setItem(final Item item) {
        this.item = item;
    }

    @Override
    public Item asItem() {
        return this.item == null ? Items.AIR : this.item;
    }

    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new FangsuPsdEndDoorEntity(pos, state);
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, Properties.DOUBLE_BLOCK_HALF, OPEN);
    }

    // ------------------------------------------------------------------ 放置 / 上下段

    /**
     * 放置：{@code FACING = 玩家水平朝向}（{@link FangsuPsdPlacement}，和滑动门 / 玻璃板同一个约定），
     * 下半段落在光标格；<b>上面一格不可替换就不给放</b>（整扇门要两格）。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        if (!IBlock.isReplaceable(ctx, Direction.UP, 2)) {
            return null;
        }
        return getDefaultState()
                .with(FACING, FangsuPsdPlacement.facingOf(ctx))
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .with(OPEN, false);
    }

    /** 放下之后补上半段（MTR {@code BlockDirectionalDoubleBlockBase#onPlaced} 的做法）。 */
    @Override
    public void onPlaced(final World world, final BlockPos pos, final BlockState state,
                         final LivingEntity placer, final ItemStack stack) {
        if (world.isClient) {
            return;
        }
        world.setBlockState(pos.up(), state.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                Block.NOTIFY_ALL);
        world.updateNeighborsAlways(pos, Blocks.AIR);
        state.updateNeighbors(world, pos, Block.NOTIFY_ALL);
    }

    /**
     * 上下段必须成对：上段的下邻居 / 下段的上邻居不是同一种方块 → 变空气。
     *
     * <p>上一版（继承 MTR）还额外删掉了父类「门线方向上的邻居不是自己就自毁」那条判断 ——
     * 那是给 MTR 整列放置写的，方速的门是一扇一扇摆的（左右两扇本来就是两个不同 id 的方块）。
     * 这个类从一开始就没有那条判断，所以不存在那个坑。
     */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        final boolean upper = state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        if ((upper ? direction == Direction.DOWN : direction == Direction.UP) && !neighborState.isOf(this)) {
            return Blocks.AIR.getDefaultState();
        }
        return state;
    }

    /**
     * 拆上段时把下段一起处理掉。
     *
     * <p>MTR 的 {@code BlockDirectionalDoubleBlockBase} 只在创造模式清掉另一半
     * （{@code IBlock.onBreakCreative}：非创造什么都不做），生存里拆上半扇会剩一根悬空的下半扇，
     * 下一条邻居更新把它变成空气时<b>不掉东西</b> —— 一整扇门就这么没了。门是贵重方块，不能这样。
     *
     * <p>所以：拆上段时，若玩家是生存，就用 {@code World#breakBlock(down, true, player)} 把下段
     * 「正常拆掉」（掉落表 {@code half=lower} 的条件在这里成立，掉一次物品）；创造模式照 MTR 的做法
     * 直接设空气（不掉东西）。
     */
    @Override
    public void onBreak(final World world, final BlockPos pos, final BlockState state,
                        final PlayerEntity player) {
        if (!world.isClient && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            final BlockPos down = pos.down();
            final BlockState lower = world.getBlockState(down);
            if (lower.isOf(this) && lower.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                if (player == null || player.isCreative()) {
                    IBlock.onBreakCreative(world, player, down);
                } else {
                    world.breakBlock(down, true, player);
                }
            }
        }
        super.onBreak(world, pos, state, player);
    }

    // ------------------------------------------------------------------ 碰撞

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return state.get(OPEN) ? VoxelShapes.empty() : this.collision[halfIndex(state)][FangsuPsdDoorBlock.shapeQuarterTurns(state)];
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return this.outline[halfIndex(state)][FangsuPsdDoorBlock.shapeQuarterTurns(state)];
    }

    /**
     * 不让邻居朝我们的面被剔除。
     *
     * <p>默认 {@code getCullingShape} 是整格立方体 —— 门只有 2~3 单位厚，邻居的贴面会被判成
     * 「看不见」而整面不画，墙上出现一个方块大小的洞。空集 = 一个面都不剔。
     */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    private static int halfIndex(final BlockState state) {
        return state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? 0 : 1;
    }

    /** 开着吗（给渲染器 / 调试用）。 */
    public static boolean isOpen(final BlockState state) {
        return state.contains(OPEN) && state.get(OPEN);
    }

    // ------------------------------------------------------------------ 右键：驾驶钥匙

    /**
     * 手持 MTR 驾驶钥匙右键 = 开 / 关；<b>其他情况一律不响应</b>（{@link ActionResult#PASS}）。
     *
     * <p>两段一起写：上下各有一个方块实体，只写一半会出现「下半扇开了上半扇没开」。
     * 状态改完 {@code setBlockState} 自己会同步给客户端，客户端渲染器再把进度平滑推过去
     * （{@link FangsuPsdEndDoorEntity#progress}）。
     */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (!FangsuPsdInteract.isDriverKey(player.getStackInHand(hand))) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            // 客户端先回 SUCCESS（手摆一下），真正的改动在服务端那一遍做。
            return ActionResult.SUCCESS;
        }
        final boolean open = !isOpen(state);
        setColumnOpen(world, pos, state, open);
        world.playSound(null, pos,
                open ? SoundEvents.BLOCK_IRON_DOOR_OPEN : SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                SoundCategory.BLOCKS, 1.0F, 1.0F);
        return ActionResult.CONSUME;
    }

    /** 这一列（上下两段）一起设开合。 */
    private static void setColumnOpen(final World world, final BlockPos pos, final BlockState state,
                                      final boolean open) {
        world.setBlockState(pos, state.with(OPEN, open), Block.NOTIFY_ALL);
        final BlockPos other = state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                ? pos.up() : pos.down();
        final BlockState otherState = world.getBlockState(other);
        if (otherState.isOf(state.getBlock())) {
            world.setBlockState(other, otherState.with(OPEN, open), Block.NOTIFY_ALL);
        }
    }

    // ------------------------------------------------------------------ 方块实体

    /**
     * 端门的方块实体：<b>不存任何数据</b>（开合在 blockstate 的 {@link #OPEN} 里），
     * 唯一的作用是给 {@link FangsuPsdEndDoorRenderer} 一个挂 BER 的位置 + 客户端插值。
     *
     * <p>{@code state} 允许为 {@code null}（{@code BlockItem} 在物品栏 / 手持渲染时会传 null，
     * 兜底成空气，PIDS 那边踩过一次 NPE）。
     */
    public static class FangsuPsdEndDoorEntity extends BlockEntityMapper {

        /** 客户端插值（按时间推进，7 tick 走完全程，见 {@link FangsuPsdProgress}）。 */
        private final FangsuPsdProgress animation = new FangsuPsdProgress();

        public FangsuPsdEndDoorEntity(final BlockPos pos, final BlockState state) {
            super(FangsuPsdBlocks.endDoorEntityType(), pos,
                    state == null ? Blocks.AIR.getDefaultState() : state);
        }

        /**
         * 当前开合进度 0..1（客户端每帧调一次）。
         *
         * <p>目标值直接从方块状态拿：{@code OPEN ? 1 : 0}。方块状态是同步过来的，
         * 所以别的玩家开门这边也会动。
         *
         * @param state     这个位置的当前状态（调用方刚从 {@code world.getBlockState} 读到的）
         * @param ticks     {@code world.getTime()}
         * @param tickDelta 当前帧的 partialTick
         */
        public float progress(final BlockState state, final long ticks, final float tickDelta) {
            return this.animation.update(isOpen(state) ? 1.0F : 0.0F, ticks, tickDelta);
        }

        @Override
        public void writeCompoundTag(final net.minecraft.nbt.NbtCompound nbt) {
            // 什么都不存：开合状态在 blockstate 里，拆掉再放回来就是关着的门。
        }
    }
}
