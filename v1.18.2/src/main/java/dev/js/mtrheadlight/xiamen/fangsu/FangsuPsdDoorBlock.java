// 方速屏蔽门方块 —— 继承 MTR 的 BlockPSDAPGDoorBase，只把「几何 / 碰撞 / 右键」换成方速那一套。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.ArrayList;
import java.util.List;

import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase;
import mtr.block.IBlock;
import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
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
 * 一个方速<b>滑动</b>屏蔽门变体（{@code screendoor_low/high_left|right}，4 块），
 * <b>继承 MTR 的 {@code BlockPSDAPGDoorBase}</b> —— 列车停稳自动开、发车自动关。
 *
 * <p><b>端门不在这里</b>：端门要「玩家手持驾驶钥匙手动开关、不接列车」，而（见下）MTR 的自动开门
 * 是拿它自己的类做 {@code instanceof} 找出来的，继承就等于接上了列车。所以 5 款端门 × 左右 = 10 块
 * 换成了 {@link FangsuPsdEndDoorBlock}（普通方块 + 方块实体）。两个类共用 {@link FangsuPsdDraw} 画几何。
 *
 * <h2>为什么必须继承（= 自动开关门的来源）</h2>
 * <p>MTR 的列车在停稳开门时走
 * {@code TrainServer#openDoors(World, Block, BlockPos, int)}，它的字节码（3.6.4.2，javap）是：
 * <pre>
 *   0: aload_2 instanceof mtr/block/BlockPSDAPGDoorBase        // 方块类型
 *  47: aload 8 instanceof mtr/block/BlockPSDAPGDoorBase        // 同一列 y-1 / y / y+1 三格
 *  57: aload 9 instanceof ...$TileEntityPSDAPGDoorBase         // 方块实体类型
 *  65: getstatic BlockPSDAPGDoorBase.UNLOCKED                  // 且未锁定
 *  88..95: doorValue * 64 clamp 0..32 → istore 10              // 开合目标值 0..32
 * 105: TileEntityPSDAPGDoorBase.setOpen(int)                   // 直接写进方块实体
 * 129: Utilities.scheduleBlockTick(world, pos, block, dwell)   // 到点自动关门
 * </pre>
 * 也就是说「开门信号」是 MTR 拿<b>它自己的类</b>做 {@code instanceof} 找出来的 ——
 * 抄一份自己的类永远不会被认出来，只有继承才会。继承顺带拿到：
 * <ul>
 *   <li>放置：{@code BlockDirectionalDoubleBlockBase#onPlaced} 自动补上 y+1 那一段；</li>
 *   <li>关门：{@code tick} 在列车到点后的 {@code scheduleBlockTick} 里把 {@code open} 写回 0；</li>
 *   <li>锁定：刷子右键切 {@code UNLOCKED}（锁定后列车不再开这扇门）；</li>
 *   <li>同步：方块实体是 {@code BlockEntityClientSerializableMapper}，{@code setOpen} → {@code syncData}
 *       → 原版 {@code BlockEntityUpdateS2CPacket}（每个玩家都能看到动画）。</li>
 * </ul>
 *
 * <h2>我们换掉的三处</h2>
 * <ol>
 *   <li><b>碰撞</b>：描述里的 {@code shape.close}（1/16 格，可超过 16 —— 3 格高的门就是 y 到 48），
 *       按四向 + 上下段预烘成 8 个 {@link VoxelShape}。关门时挡人、开门时变空集
 *       （父类也是这个思路，只是它用的是 MTR 自己的固定几何）。</li>
 *   <li><b>外观</b>：blockstate 是「只有粒子」的空模型，几何全交给 {@link FangsuPsdRenderer} 画 OBJ。</li>
 *   <li><b>右键</b>：刷子 = 手动开 / 关（搭建和测试用），潜行 + 刷子 = 锁定（父类的原语义）。
 *       自动逻辑优先：列车在站时它自己每 tick 会写 {@code setOpen}，手动结果会被立刻覆盖。</li>
 * </ol>
 */
public class FangsuPsdDoorBlock extends BlockPSDAPGDoorBase {

    /** 上 / 下两段（索引与 {@link DoubleBlockHalf} 的 LOWER/TOP 对应关系见 {@link #halfIndex}）。 */
    private static final int HALVES = 2;

    /** 水平四向的 4 个角度（{@code north=0 / east=90 / south=180 / west=270}）。 */
    private static final int FACINGS = 4;

    private final FangsuPsdDef def;

    /** 碰撞盒：{@code [half][绕 Y 的 90° 步数]}（步数的取法见 {@link #shapeQuarterTurns}）。 */
    private final VoxelShape[][] collision;

    /** 轮廓盒（选中 / 高亮）：永远用关门的形状，开门后也还能点到这块门。 */
    private final VoxelShape[][] outline;

    private Item item;

    public FangsuPsdDoorBlock(final FangsuPsdDef def) {
        this.def = def;
        // 默认态就是「解锁」：BooleanProperty 的候选顺序是 true,false，父类的 appendProperties
        // 加了 UNLOCKED，所以 getDefaultState() 已经是 unlocked=true —— 这里显式写一次，防止
        // 哪天父类改了顺序导致列车不开门。
        setDefaultState(getDefaultState().with(UNLOCKED, true));

        this.collision = new VoxelShape[HALVES][FACINGS];
        this.outline = new VoxelShape[HALVES][FACINGS];
        for (int half = 0; half < HALVES; half++) {
            for (int rotation = 0; rotation < FACINGS; rotation++) {
                final VoxelShape shape = buildShape(def.closeBoxes(), half, rotation * 90);
                this.collision[half][rotation] = shape;
                this.outline[half][rotation] = shape;
            }
        }
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
    public BlockEntityMapper createBlockEntity(final BlockPos pos, final BlockState state) {
        return new FangsuPsdDoorEntity(pos, state);
    }

    // ------------------------------------------------------------------ 放置 / 朝向

    /**
     * 放置：<b>和 MTR 原版屏蔽门逐字节一致</b> —— 直接用 {@code getHorizontalPlayerFacing()}，不取反。
     *
     * <h2>MTR 的写法（{@code libs/MTR-fabric-1.20.1-3.6.4.2-named.jar}，javap -c）</h2>
     * <p>{@code BlockPSDAPGDoorBase} 没有自己的 {@code getPlacementState}，用的是
     * {@code BlockDirectionalDoubleBlockBase#getPlacementState}：
     * <pre>
     *   0: aload_1
     *   1: invokevirtual  ItemPlacementContext.getHorizontalPlayerFacing   // ← 没有 getOpposite
     *   4: astore_2
     *   5: aload_1 / getstatic Direction.UP / iconst_2
     *  10: invokestatic   IBlock.isReplaceable(ctx, UP, 2)
     *  16: aload_0 / getBlockPos() / aload_2
     *  22: invokevirtual  getAdditionalState(pos, facing)                   // = getDefaultState()
     *  25: getstatic      FACING / aload_2 / BlockState.with(FACING, facing)
     *  35: getstatic      HALF   / LOWER / .with(HALF, LOWER)
     * </pre>
     * 也就是说 {@code FACING == 玩家看的那个方向}，含义是<b>门扇贴的那条边</b>（几何在那一侧贴边）：
     * <pre>
     *   IBlock.getVoxelShapeByDirection(0, 0, 0, 16, h, 4, FACING)        // 门扇 = 4 单位厚
     *   IBlock$1 静态块：NORTH→1, EAST→2, SOUTH→3, WEST→4
     *   case 1 (NORTH)：原样                    → z 0..4    （贴北边）
     *   case 2 (EAST) ：(16-z2, x1, 16-z1, x2)  → x 12..16  （贴东边）
     *   case 3 (SOUTH)：(16-z2, x1, 16-z1, x2)  → z 12..16  （贴南边）
     *   case 4 (WEST) ：(x1, 16-z2, x2, 16-z1)  → x 0..4    （贴西边）
     * </pre>
     * 所以「玩家朝北放门」→ {@code FACING=NORTH} → 门扇落在<b>北侧那条边</b>，也就是玩家对面那条边、
     * 正面朝北（背离玩家）。原来这里写 {@code getOpposite()} 会让门扇翻到玩家这一侧的内侧 ——
     * 玩家反馈的「模型和碰撞箱都翻到内侧来了」就是这一处。
     *
     * <p>上半段由父类的 {@code onPlaced} 按同一个 {@code FACING} 补，这里只改下半段的朝向。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext context) {
        final BlockState state = super.getPlacementState(context);
        return state == null ? null : state.with(FACING, FangsuPsdPlacement.facingOf(context));
    }

    /**
     * 邻居更新：<b>只保留父类「上下段必须成对」那一条</b>，去掉另一条自毁判断。
     *
     * <p>父类（javap，3.6.4.2）是：
     * <pre>
     *   if (direction == IBlock.getSideDirection(state)) {        // 门线方向上的邻居
     *       if (!neighborState.isOf(this)) return Blocks.AIR;     // ← 不是同一种方块就自毁
     *   }
     *   ... super(...) → 上下段配对检查
     * </pre>
     * 那条判断是给 MTR 自己的整列放置写的（{@code PsdAPGItem} 会按邻居把 {@code SIDE} 算成
     * left / middle / right / single，整列都是<b>同一个方块</b>）。方速的门是玩家一扇一扇摆的，
     * 左右两扇本来就是<b>两个不同 id 的方块</b>（{@code ..._left} / {@code ..._right}），
     * 只要旁边摆的不是同一个 id 就会被判成「该消失」，所以这里必须去掉 —— 否则门放下后
     * 一有邻居变化就变成空气。
     */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        final boolean upper = IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER;
        if ((upper ? direction == Direction.DOWN : direction == Direction.UP) && !neighborState.isOf(this)) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
        return state;
    }

    // ------------------------------------------------------------------ 碰撞

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        if (isOpen(world, pos)) {
            return VoxelShapes.empty();
        }
        return this.collision[halfIndex(state)][shapeQuarterTurns(state)];
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return this.outline[halfIndex(state)][shapeQuarterTurns(state)];
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

    /** 门开着吗（读方块实体 —— 父类也是这么判的）。 */
    private static boolean isOpen(final BlockView world, final BlockPos pos) {
        final BlockEntity entity = world.getBlockEntity(pos);
        return entity instanceof TileEntityPSDAPGDoorBase door && door.isOpen();
    }

    private static int halfIndex(final BlockState state) {
        return IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.LOWER ? 0 : 1;
    }

    /**
     * 碰撞盒要转几个 90°（作用在 {@link #buildShape} 的 {@code (x,z) → (16-z, x)} 上，也就是每格 −90°）。
     *
     * <h2>为什么 north 是 2 而不是 0（= 比模型多转 180°）</h2>
     * <p>方速自己的<b>模型</b>和<b>碰撞盒</b>并不是同一个朝向 —— 差一个 180°：
     * <ul>
     *   <li>模型：{@code BaseBlockEntityRender#render} 里是
     *       {@code candyPose.rotateY(-toRadians(facing.getHorizontal()) + PI)}（注意那个 {@code + PI}，
     *       所以 north 时是 0°）；</li>
     *   <li>碰撞盒：{@code BlockEntityDuanmen#setShape} / {@code BlockEntityScreendoor#setShape} 里是
     *       {@code toRadians(-facing.getHorizontal())}（<b>没有</b> {@code + PI}，所以 north 时是 −180°）。</li>
     * </ul>
     * 也就是说同一个 {@code FACING} 下，碰撞盒必须比模型再多转 180° 才和画出来的门扇重合。
     * 反证（素材本身就是两组数）：{@code doorPos.x} 的右扇是 −0.4375（铰链在西），可
     * {@code shape.open} 里右扇展开后落在 x 14..16（东）；模型的门扇贴 z 0..2（北），
     * {@code shape.close} 却写 15..16（南）—— x 和 z 同时反号，正好是绕 Y 的 180°。
     *
     * <p>照模型转（north=0）就会出现用户反馈的现象：门扇画在方块一侧、碰撞盒在另一侧，
     * 看着「模型镜像了」。
     *
     * <p>{@link #buildShape} 里的每一步是 {@code (x,z) → (16-z, x)}，即 −90°；下表的数字就是步数。
     */
    static int shapeQuarterTurns(final BlockState state) {
        final Direction facing = IBlock.getStatePropertySafe(state, FACING);
        return switch (facing) {
            case EAST -> 3;
            case SOUTH -> 0;
            case WEST -> 1;
            default -> 2;   // NORTH
        };
    }

    /**
     * 把描述里的盒子烘成某一段、某个朝向的 {@link VoxelShape}。
     *
     * <p>三件事：<b>按 90° 转 xz</b>（{@code (x,z) → (16-z, x)}，与 {@code EyecandyBlock} /
     * {@code dep_on_wall} 同一套约定，所以四个方向都是精确的轴对齐盒子）；<b>裁到本格</b>
     * （x/z 只保留 0..16，伸到邻居格的部分不归本方块管）；<b>取本段那一层 y</b>
     * （下段 0..16，上段 16..32 再下移 16）。
     */
    static VoxelShape buildShape(final List<double[]> boxes, final int half, final int yRotation) {
        final double lowY = half == 0 ? 0.0D : 16.0D;
        final double highY = lowY + 16.0D;
        final List<VoxelShape> parts = new ArrayList<>(boxes.size());
        for (final double[] box : boxes) {
            double x1 = box[0];
            double y1 = box[1];
            double z1 = box[2];
            double x2 = box[3];
            double y2 = box[4];
            double z2 = box[5];
            for (int i = 0; i < (((yRotation % 360) + 360) % 360) / 90; i++) {
                final double nx1 = 16.0D - z2;
                final double nx2 = 16.0D - z1;
                z1 = x1;
                z2 = x2;
                x1 = nx1;
                x2 = nx2;
            }
            x1 = Math.max(0.0D, x1);
            x2 = Math.min(16.0D, x2);
            z1 = Math.max(0.0D, z1);
            z2 = Math.min(16.0D, z2);
            final double cy1 = Math.max(y1, lowY);
            final double cy2 = Math.min(y2, highY);
            if (x2 - x1 < 0.01D || z2 - z1 < 0.01D || cy2 - cy1 < 0.01D) {
                continue;
            }
            parts.add(VoxelShapes.cuboid(x1 / 16.0D, (cy1 - lowY) / 16.0D, z1 / 16.0D,
                    x2 / 16.0D, (cy2 - lowY) / 16.0D, z2 / 16.0D));
        }
        return parts.isEmpty() ? VoxelShapes.empty()
                : VoxelShapes.union(VoxelShapes.empty(), parts.toArray(new VoxelShape[0]));
    }

    // ------------------------------------------------------------------ 右键

    /**
     * 刷子右键 = 手动开 / 关；<b>潜行</b> + 刷子 = 锁定 / 解锁（父类的原语义，锁定后列车不开）。
     *
     * <p>手动开关把上下两段一起写（两段各有自己的方块实体，不一起写会出现「下半扇开了上半扇没开」）。
     * 列车在站时 MTR 每个 tick 都会自己 {@code setOpen(doorValue*64)}，所以<b>自动逻辑优先</b>。
     */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (!FangsuPsdInteract.isBrush(player.getStackInHand(hand))) {
            return super.onUse(state, world, pos, player, hand, hit);
        }
        if (player.isSneaking()) {
            return super.onUse(state, world, pos, player, hand, hit);
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        final int target = isOpen(world, pos) ? 0 : MAX_OPEN_VALUE;
        setColumnOpen(world, pos, state, target);
        return ActionResult.CONSUME;
    }

    /** 这一列（上下两段）一起设开合值。 */
    private static void setColumnOpen(final World world, final BlockPos pos, final BlockState state,
                                      final int value) {
        setOpenAt(world, pos, value);
        final BlockPos other = IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.LOWER
                ? pos.up() : pos.down();
        setOpenAt(world, other, value);
    }

    private static void setOpenAt(final World world, final BlockPos pos, final int value) {
        if (world.getBlockEntity(pos) instanceof TileEntityPSDAPGDoorBase door) {
            door.setOpen(value);
        }
    }

    // ------------------------------------------------------------------ 方块实体

    /**
     * 门扇方块实体：MTR 的 {@code TileEntityPSDAPGDoorBase}（{@code open} 0..32 + 同步 + NBT）
     * 再加一份<b>客户端插值</b>。
     *
     * <h2>为什么不直接用父类的 {@code getOpen(float)}</h2>
     * <p>父类那份是「每调用一次推进 0.95」—— 与帧率挂钩：60 fps 约 0.56 s 走完，144 fps 只剩 0.23 s
     * （看起来就是瞬开）。这里改成<b>按时间推进</b>，插值本身在 {@link FangsuPsdProgress}
     * （端门用的是同一份，两种门手感一致）。
     */
    public static class FangsuPsdDoorEntity extends TileEntityPSDAPGDoorBase {

        /**
         * 全行程耗时（tick）。<b>调速度就改 {@link FangsuPsdProgress#MOVE_TICKS}</b>。
         *
         * <p>MTR 自己那套是按帧推进（60 fps ≈ 0.57 s），方速是 500 ms。原来这里写 15 tick（0.75 s），
         * 用户反馈「太慢」；7 tick = 0.35 s，90° 摆过去的手感和真实端门更接近。
         * 改动会影响全部变体（端门 + 滑动门），因为插值器是共用的。
         */
        public static final float MOVE_TICKS = FangsuPsdProgress.MOVE_TICKS;

        /** 客户端插值（按时间推进，见 {@link FangsuPsdProgress}）。 */
        private final FangsuPsdProgress animation = new FangsuPsdProgress();

        public FangsuPsdDoorEntity(final BlockPos pos, final BlockState state) {
            super(FangsuPsdBlocks.entityType(), pos, state);
        }

        /**
         * 当前开合进度 0..1（客户端每帧调一次）。
         *
         * @param ticks      {@code world.getTime()}
         * @param tickDelta  当前帧的 partialTick
         */
        public float progress(final long ticks, final float tickDelta) {
            // MTR 3.2.2 没有 getOpenValue()：getOpen(tickDelta) 返回同一刻度的平滑值。
            return this.animation.update(this.getOpen(1.0F) / (float) MAX_OPEN_VALUE, ticks, tickDelta);
        }
    }
}
