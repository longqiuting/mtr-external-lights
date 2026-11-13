// Xiamen PSD blocks — 直接继承 MTR 的方块 / 方块实体（不做移植副本）。
package dev.js.mtrheadlight.xiamen;

import java.util.function.Consumer;

import mtr.block.BlockAPGDoor;
import mtr.block.BlockAPGGlass;
import mtr.block.BlockAPGGlassEnd;
import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.BlockPSDAPGBase;
import mtr.block.BlockPSDAPGGlassBase;
import mtr.block.BlockPSDAPGGlassEndBase;
import mtr.block.BlockPSDTop;
import mtr.block.IBlock;
import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * 厦门屏蔽门的两套方块 —— <b>全部继承 MTR 的方块类</b>，只换贴图与名字。
 *
 * <p>全高 PSD：{@code Door / Glass / GlassEnd / Top}（2 高 + 吊板）；
 * 半高 APG：{@code ApgDoor / ApgGlass / ApgEnd}（2 高、无吊板，父类分别是 MTR 的
 * {@code BlockAPGDoor / BlockAPGGlass / BlockAPGGlassEnd}）。
 *
 * <h2>为什么必须继承（这是列车能自动开门的前提）</h2>
 * <p>MTR 的 {@code mtr.data.Train} 在扫描列车周围的方块时用的是
 * {@code instanceof mtr.block.BlockPSDAPGBase}，再交给
 * {@code TrainServer#openDoors(World, Block, BlockPos, int)}；后者又有两条硬判据：
 * <pre>
 *   1: aload_2
 *   1: instanceof  mtr/block/BlockPSDAPGDoorBase          // 方块必须是它的子类
 *  47: aload 8
 *  49: instanceof  mtr/block/BlockPSDAPGDoorBase
 *  55: aload 9
 *  57: instanceof  mtr/block/BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase   // 方块实体同理
 *  65: getstatic     BlockPSDAPGDoorBase.UNLOCKED        // 且 blockstate 的 unlocked 必须为 true
 * 105: invokevirtual TileEntityPSDAPGDoorBase.setOpen:(I)V  // 最后直接调 MTR 方块实体的 setOpen
 * </pre>
 * 所以「抄一份自己的类」永远不会被认出来：字节码里比较的是 MTR 自己的类型。
 * 天津地铁就是这么做的（{@code BlockPSDDoorTianjin extends BlockPSDAPGDoorBase}，
 * {@code TileEntityPSDDoorTianjin extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase}）。
 *
 * <h2>继承顺带拿到的东西</h2>
 * <ul>
 *   <li>{@code unlocked} 默认值 = {@code true}：原版 {@code StateManager#getDefaultState()} 返回
 *       {@code states.get(0)}，而 {@code BooleanProperty} 的候选顺序是 {@code true, false}
 *       （{@code ImmutableSet.of(true, false)}），所以 MTR 的屏蔽门「放下就是解锁的」——列车才会开；</li>
 *   <li>定时 tick 自动关门（{@code BlockPSDAPGDoorBase#tick}，由 {@code Utilities.scheduleBlockTick} 安排）、
 *       刷子锁定 / 解锁、上下段联动、碰撞随开合消失、{@code END} / {@code SIDE} 邻接推导；</li>
 *   <li>吊板 {@code BlockPSDTop}：{@code persistent} / {@code air_left} / {@code air_right} /
 *       {@code propagate_property} 全套逻辑，配合 MTR 的 {@code RenderPSDTop} 画线路图 / 站名。</li>
 * </ul>
 */
public final class XiamenPsdBlocks {

    private XiamenPsdBlocks() {
    }

    private static Item itemOrAir(Item item) {
        return item == null ? Items.AIR : item;
    }

    /**
     * 「这个方块属于哪条线路」—— 7 个方块类都实现它。
     *
     * <p>渲染器拿不到 BER 的注册信息（一个 {@code BlockEntityType} 覆盖五条线路的门），所以
     * 贴图变体只能从<b>方块实例</b>反查：{@code entity.getCachedState().getBlock() instanceof LineAware}。
     * 这样三项成本全为零：不做 map 查找、不额外分配、多条线路共用一个方块类。
     */
    public interface LineAware {

        /** 本方块所属线路。 */
        XiamenLine line();
    }

    /**
     * 把 MTR 屏蔽门的「临时态」关掉 —— 这是「门放下去就全透明」的根因所在。
     *
     * <p>MTR 3.2.x（1.18.2 / 1.19.2 / 1.19.4）的 {@code BlockPSDAPGDoorBase} 有一个
     * {@code TEMP = BooleanProperty.of("temp")} 属性并把它 add 进了 {@code appendProperties}
     * （javap 依据：{@code appendProperties} 里 add 了 END/FACING/HALF/SIDE/UNLOCKED/TEMP 六个）。
     * {@code BooleanProperty} 的候选是 {@code ImmutableSet.of(true, false)}，
     * {@code StateManager#getDefaultState()} 取 {@code states.get(0)}，所以<b>默认 TEMP=true</b>。
     * 而 {@code mtr.render.RenderPSDAPGDoor#render} 的头几行是：
     * <pre>
     *   0: getWorld()  … ifnull → return
     *  18: getstatic BlockPSDAPGDoorBase.TEMP
     *  25: IBlock.getStatePropertySafe(world, pos, TEMP)
     *  34: ifeq 38            // TEMP == false 才继续
     *  37: return             // TEMP == true → 直接不画
     * </pre>
     * 我们的 blockstate 是「只给粒子、无几何」的 {@code *_particle_only}，于是放下第一帧：
     * 模型空转 + BER 早退 = 整扇门全透明；列车开过一次门（{@code setOpen} 会把 TEMP 写成 false）
     * 或刷子动一下（状态刷新）之后才恢复。
     *
     * <p>修法就是让<b>默认态</b>直接是 TEMP=false，BER 从第一帧起就出画面。
     *
     * <p>为什么要 {@code contains} 判一下：MTR 3.6.x（1.20.1）里 {@code TEMP} 字段还在，
     * 但 {@code appendProperties} <b>没有</b>注册它（javap：只 add 了 END/FACING/HALF/SIDE/UNLOCKED
     * 五个），{@code RenderPSDAPGDoor} 也不再读它。对不含该属性的 state 调
     * {@code with(TEMP, ...)} 会抛 {@code IllegalArgumentException}，所以这里「有就改、没有就跳过」，
     * 四个版本可以共用同一份源码。
     */
    private static BlockState withoutTemp(final BlockState state) {
        return state.contains(BlockPSDAPGDoorBase.TEMP)
                ? state.with(BlockPSDAPGDoorBase.TEMP, false)
                : state;
    }

    /** 门扇（y+0 / y+1 两段，左右两扇对开）。 */
    public static final class Door extends BlockPSDAPGDoorBase implements LineAware {

        private final XiamenLine line;

        public Door(final XiamenLine line) {
            this.line = line;
            // 默认态就是 TEMP=false：放下的第一帧 RenderPSDAPGDoor 就出画面，不再「全透明」。
            setDefaultState(withoutTemp(getDefaultState()));
        }

        @Override
        public XiamenLine line() {
            return this.line;
        }

        /**
         * 放置状态再兜一层。MTR 的 {@code BlockDirectionalDoubleBlockBase#getPlacementState}
         * 是用 {@code getAdditionalState(pos, facing)} 搭的，而它（javap）
         * {@code invokevirtual getDefaultState()} —— 所以上面改了默认态其实就够；
         * 这里显式再改一次，防止哪天默认态被别处（{@code getAdditionalState} 重写）绕开。
         */
        @Override
        public BlockState getPlacementState(final ItemPlacementContext context) {
            final BlockState state = super.getPlacementState(context);
            return state == null ? null : withoutTemp(state);
        }

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityXiamenPSDDoor(pos, state);
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.set(this.line).doorItem);
        }
    }

    /** 门扇方块实体 —— MTR 的 {@code setOpen} / {@code getOpen} 就在这里，列车调的就是它。 */
    public static final class TileEntityXiamenPSDDoor extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase {

        public TileEntityXiamenPSDDoor(BlockPos pos, BlockState state) {
            super(XiamenObjects.psdDoorEntityType(), pos, state);
        }
    }

    /** 玻璃板（一条链里按 side=left/middle/right/single 自动切换模型）。 */
    public static final class Glass extends BlockPSDAPGGlassBase implements LineAware {

        private final XiamenLine line;

        public Glass(final XiamenLine line) {
            this.line = line;
        }

        @Override
        public XiamenLine line() {
            return this.line;
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.set(this.line).glassItem);
        }
    }

    /** 端部收口（{@code touching_left} / {@code touching_right}）。 */
    public static final class GlassEnd extends BlockPSDAPGGlassEndBase implements LineAware {

        private final XiamenLine line;

        public GlassEnd(final XiamenLine line) {
            this.line = line;
        }

        @Override
        public XiamenLine line() {
            return this.line;
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.set(this.line).endItem);
        }
    }

    /**
     * 吊板（y+2）。继承 MTR 的 {@code BlockPSDTop}，只重写三处必须换成本模组方块的地方：
     * 方块实体（要有自己的注册类型）、{@code getStateForNeighborUpdate}（MTR 的静态
     * {@code getActualState} 会把方块换回 MTR 自己的 {@code Blocks.PSD_TOP}）、
     * {@code onUse}（MTR 的 {@code setState} 用 {@code instanceof BlockPSDDoor} 判下层方块）。
     */
    public static final class Top extends BlockPSDTop implements LineAware {

        private final XiamenLine line;

        public Top(final XiamenLine line) {
            this.line = line;
        }

        @Override
        public XiamenLine line() {
            return this.line;
        }

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityXiamenPSDTop(pos, state);
        }

        /** MTR 里吊板中键选取给玻璃物品。 */
        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.set(this.line).glassItem);
        }

        @Override
        public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                    WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            if (direction == Direction.DOWN
                    && IBlock.getStatePropertySafe(state, PERSISTENT) == EnumPersistent.NONE
                    && !(neighborState.getBlock() instanceof BlockPSDAPGBase)) {
                return Blocks.AIR.getDefaultState();
            }
            return actualState(world, pos);
        }

        @Override
        public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 Hand hand, BlockHitResult hit) {
            // 两个物品，语义和 MTR 的 BlockPSDTop#use 完全一致（javap 依据：
            //   getstatic mtr/Items.BRUSH … / getstatic net/minecraft/class_1802.field_8868
            //   后者在 1.20.1 里就是 Items.SHEARS）：
            //   - **MTR 的刷子**（mtr:brush，mtr.Items.BRUSH，不是原版 minecraft:brush）
            //     → cycle ARROW_DIRECTION，并沿 facing 左右各传播一格；
            //   - **原版剪刀 Items.SHEARS** → 切换 PERSISTENT：
            //     NONE → 按下方方块给 ARROW(门扇)/ROUTE(玻璃)/BLANK(端部)，
            //     再剪一次 → NONE。之前这里误写成 Items.BARRIER，所以剪刀右键什么都切不了。
            final net.minecraft.item.Item brush = mtr.Items.BRUSH.get();
            return IBlock.checkHoldingItem(world, player, item -> {
                Direction facing = IBlock.getStatePropertySafe(state, FACING);
                if (item == brush) {
                    world.setBlockState(pos, state.cycle(ARROW_DIRECTION));
                    this.propagate(world, pos, facing.rotateYClockwise(), ARROW_DIRECTION, 1);
                    this.propagate(world, pos, facing.rotateYCounterclockwise(), ARROW_DIRECTION, 1);
                } else {
                    boolean shouldBePersistent = IBlock.getStatePropertySafe(state, PERSISTENT) == EnumPersistent.NONE;
                    this.setState(world, pos, shouldBePersistent);
                    this.propagate(world, pos, facing.rotateYClockwise(),
                            offsetPos -> this.setState(world, offsetPos, shouldBePersistent), 1);
                    this.propagate(world, pos, facing.rotateYCounterclockwise(),
                            offsetPos -> this.setState(world, offsetPos, shouldBePersistent), 1);
                }
            }, null, brush, Items.SHEARS);
        }

        /** MTR {@code setState} 的等价物，判据换成 MTR 的三个基类。 */
        private void setState(World world, BlockPos pos, boolean persistent) {
            Block blockBelow = world.getBlockState(pos.down()).getBlock();
            if (!(blockBelow instanceof BlockPSDAPGBase)) {
                return;
            }
            EnumPersistent value;
            if (!persistent) {
                value = EnumPersistent.NONE;
            } else if (blockBelow instanceof BlockPSDAPGDoorBase) {
                value = EnumPersistent.ARROW;
            } else if (blockBelow instanceof BlockPSDAPGGlassEndBase) {
                value = EnumPersistent.BLANK;
            } else {
                value = EnumPersistent.ROUTE;
            }
            world.setBlockState(pos, world.getBlockState(pos).with(PERSISTENT, value));
        }

        /**
         * MTR {@code BlockPSDTop#getActualState} 的等价物 —— 唯一区别是「默认态」取本模组吊板的
         * 默认态（MTR 那个写死 {@code Blocks.PSD_TOP}，会把方块偷偷换回去）。
         */
        public static BlockState actualState(BlockView world, BlockPos pos) {
            Direction facing = null;
            IBlock.EnumSide side = null;
            boolean airLeft = false;
            boolean airRight = false;

            BlockState stateBelow = world.getBlockState(pos.down());
            Block blockBelow = stateBelow.getBlock();
            if (blockBelow instanceof BlockPSDAPGBase) {
                side = blockBelow instanceof BlockPSDAPGDoorBase
                        ? IBlock.getStatePropertySafe(stateBelow, IBlock.SIDE)
                        : IBlock.getStatePropertySafe(stateBelow, IBlock.SIDE_EXTENDED);
                if (blockBelow instanceof BlockPSDAPGGlassEndBase) {
                    if (IBlock.getStatePropertySafe(stateBelow,
                            BlockPSDAPGGlassEndBase.TOUCHING_LEFT) == BlockPSDAPGGlassEndBase.EnumPSDAPGGlassEndSide.AIR) {
                        airLeft = true;
                    }
                    if (IBlock.getStatePropertySafe(stateBelow,
                            BlockPSDAPGGlassEndBase.TOUCHING_RIGHT) == BlockPSDAPGGlassEndBase.EnumPSDAPGGlassEndSide.AIR) {
                        airRight = true;
                    }
                }
                facing = IBlock.getStatePropertySafe(stateBelow, FACING);
            }

            BlockState oldState = world.getBlockState(pos);
            BlockState base = oldState.getBlock() instanceof Top
                    ? oldState
                    : XiamenObjects.set(XiamenObjects.lineOf(blockBelow)).top.getDefaultState();
            BlockState newState = base.with(AIR_LEFT, airLeft).with(AIR_RIGHT, airRight);
            if (facing != null) {
                newState = newState.with(FACING, facing);
            }
            if (side != null) {
                newState = newState.with(SIDE_EXTENDED, side);
            }
            return newState;
        }
    }

    /**
     * 吊板方块实体 —— 继承 MTR 的 {@code TileEntityRouteBase}。
     *
     * <p><b>动态绘制全靠这里</b>：MTR 的 {@code RenderPSDTop}（→ {@code RenderRouteBase}）每帧调
     * {@code getPlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE)} 把方块位置换算成站台 id，
     * 再用站台 / 车站 / 线路数据现画站名、线路图、色带和方向箭头（JS 脚本包也走这条数据链）。
     */
    public static final class TileEntityXiamenPSDTop extends BlockPSDTop.TileEntityRouteBase {

        public TileEntityXiamenPSDTop(BlockPos pos, BlockState state) {
            super(XiamenObjects.psdTopEntityType(), pos, state);
        }
    }

    // ------------------------------------------------------------------
    // 独立吊板（psd_top_xiamen_standalone）—— 从「屏蔽门结构的一格」拆出来的单独方块。
    //
    // 和上面 Top 的关系：
    //   同一套状态（facing / side_extended / air_left / air_right / arrow_direction /
    //   persistent）、同一个父类 BlockPSDTop、同一个渲染器（XiamenPsdTopRenderer →
    //   MTR RenderPSDTop → XiamenRouteStrip / XiamenDirectionPanel），所以画出来的线路图
    //   一个字都不用改。
    //
    // 唯一要改的是「它是独立方块」这件事，共四处：
    //   1. 放置：不靠下方方块，FACING 由玩家朝向给，其余属性直接定成“一块完整的吊板”
    //      （side=single、两侧都不缺、persistent=route）；
    //   2. 邻居更新：父类会在下方不是 BlockPSDAPGBase 时把方块变成空气（javap 依据：
    //      getStateForNeighborUpdate 里 DOWN + PERSISTENT==NONE + !(instanceof BlockPSDAPGBase)
    //      → Blocks.AIR），独立吊板必须原地不动；
    //   3. 右键：父类的剪刀走 setState（按下方门扇 / 玻璃 / 端头挑 ARROW / ROUTE / BLANK），
    //      没有下方方块时它直接 return，等于剪刀没反应。这里改成和刷子一样 cycle
    //      ARROW_DIRECTION —— 剪刀右键 = 四向形态循环；
    //   4. 破坏：父类的 onBreak 会把下面那块屏蔽门一起敲掉（MTR 的“整列一起拆”），
    //      独立吊板下面什么都没有，不能跟着执行。
    // ------------------------------------------------------------------

    /**
     * 独立吊板 {@code mtrlte:psd_top_xiamen_standalone}。
     *
     * <p>默认态就是「一整块、有线路图」：{@code persistent = route}、{@code side = single}、
     * {@code air_left / air_right = false}。
     * 为什么必须逐项显式写：{@code BooleanProperty} 的候选顺序是 {@code true, false}，
     * {@code StateManager#getDefaultState()} 取 {@code states.get(0)} —— 也就是
     * <b>AIR_LEFT / AIR_RIGHT 的默认值是 true</b>（和 TEMP 同一个坑），不写就会出现“两端缺角”的吊板。
     *
     * <p>贴图用 1 号线那一套（{@link XiamenLine#L1}，不带后缀）：吊板本体只是一副金属支架，
     * 真正随线路变的是渲染器画上去的线路图。
     */
    public static final class StandaloneTop extends BlockPSDTop implements LineAware {

        public StandaloneTop() {
            setDefaultState(asStandalone(getDefaultState()));
        }

        @Override
        public XiamenLine line() {
            return XiamenLine.L1;
        }

        /** 一块“完整”的吊板：整段、两侧都有实体、线路图（route）。 */
        private static BlockState asStandalone(final BlockState base) {
            return base.with(PERSISTENT, EnumPersistent.ROUTE)
                    .with(SIDE_EXTENDED, IBlock.EnumSide.SINGLE)
                    .with(AIR_LEFT, false)
                    .with(AIR_RIGHT, false);
        }

        @Override
        public BlockState getPlacementState(final ItemPlacementContext context) {
            // 父类（HorizontalFacingBlock）只给 FACING，其余属性保持我们的默认态。
            final BlockState state = super.getPlacementState(context);
            return state == null ? null : asStandalone(state);
        }

        /**
         * 独立吊板不需要支撑：邻居怎么变都不动它。
         *
         * <p>父类实现（javap）：{@code DOWN} 且 {@code persistent == none} 且下方不是
         * {@code BlockPSDAPGBase} → 返回 {@code Blocks.AIR}；其余情况走
         * {@code getActualState(world, pos)} 按下方门扇重新推 facing / side / air。
         * 两条对我们都不成立，所以直接原样返回。
         */
        @Override
        public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                    WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            return state;
        }

        /**
         * 剪刀 / 刷子右键 = 四向形态循环（{@code ARROW_DIRECTION} 0→1→2→3→0）。
         *
         * <p>父类有两个工具：MTR 刷子 cycle {@code ARROW_DIRECTION}，原版剪刀切 {@code PERSISTENT}
         * （按下方方块给 ARROW / ROUTE / BLANK）。独立吊板下面没有门，{@code PERSISTENT} 恒为
         * {@code route}，所以这里两把工具都做成 cycle —— 用户手上有哪把都能切，切出来的形态与
         * 屏蔽门吊板刷出来的完全一样（{@link XiamenRouteStrip} 的镜像规则认的就是这个值）。
         */
        @Override
        public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 Hand hand, BlockHitResult hit) {
            final net.minecraft.item.Item brush = mtr.Items.BRUSH.get();
            return IBlock.checkHoldingItem(world, player, item ->
                            world.setBlockState(pos, world.getBlockState(pos).cycle(ARROW_DIRECTION)),
                    null, brush, Items.SHEARS);
        }

        /**
         * 装饰件不挡路。
         *
         * <p>父类没有写碰撞形状 → 整格实心：单独挂在半空 / 站台顶上时会把通道堵死
         * （屏蔽门吊板本来只出现在门框 y+2，看不出这个副作用）。轮廓形状仍然继承父类
         * （{@code persistent != none} 时是贴着方块顶端的一条），所以选中 / 高亮框还是对的。
         */
        @Override
        public net.minecraft.util.shape.VoxelShape getCollisionShape(BlockState state, BlockView world,
                                                                     BlockPos pos,
                                                                     net.minecraft.block.ShapeContext context) {
            return net.minecraft.util.shape.VoxelShapes.empty();
        }

        /** 父类的 onBreak 会把下方屏蔽门一起敲掉；独立吊板只拆自己（{@code Block#onBreak} 是空实现）。 */
        @Override
        public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
            // 故意不调 super：见上。
        }

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityStandaloneTop(pos, state);
        }

        /** 有物品形态（创造页签里就是它），中键选取也回到它自己。 */
        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.standaloneTopItem());
        }
    }

    /** 独立吊板的方块实体 —— 与屏蔽门吊板同一个基类，所以站台定位 / 线路图数据完全一致。 */
    public static final class TileEntityStandaloneTop extends BlockPSDTop.TileEntityRouteBase {

        public TileEntityStandaloneTop(BlockPos pos, BlockState state) {
            super(XiamenObjects.standaloneTopEntityType(), pos, state);
        }
    }

    // ------------------------------------------------------------------
    // 半高 APG 屏蔽门（全集）—— 父类与全高 PSD 完全相同，差别只有两处：
    //   1. 放置时不放吊板（{@code BlockPSDTop}），所以整列只有 y+0 / y+1 两格；
    //   2. 门扇渲染器用 MTR 的 type 2（{@code apg_door_*} 贴图）。
    // 这正是 MTR 自己 {@code BlockAPGDoor / BlockAPGGlass / BlockAPGGlassEnd} 的形状
    // （javap：三者都只是 {@code BlockPSDAPG*Base} 的薄壳 + 自己的 asItem / 方块实体）。
    // ------------------------------------------------------------------

    /**
     * APG 门扇（y+0 / y+1，左右两扇对开）。
     *
     * <p>MTR 的 {@code EnumPSDAPGType.APG} 是 {@code (isPSD=false, isOdd=false, isLift=false)}：
     * 宽度 {@code isOdd ? 3 : 2} → <b>2 格</b>，高度检查 {@code isPSD ? 3 : 2} → <b>2 格</b>，
     * 而且<b>不放吊板</b>（{@code ItemPSDAPGBase#useOnBlock} 里那段 {@code if (isPSD)} 才会
     * {@code setBlockState(pos.up(2), BlockPSDTop.getActualState(...))}）。
     *
     * <p>父类是 MTR 自己的 {@code BlockAPGDoor}（它只是 {@code BlockPSDAPGDoorBase} 的薄壳），
     * 这样 {@code BlockPSDAPGBase#isAPG()} 才为 {@code true} —— MTR 用它决定上段的<b>选中框</b>
     * 高度（{@code 9/16} 而不是整格）。列车开门的判据仍然是继承链上的
     * {@code BlockPSDAPGDoorBase}，一条都没变。
     */
    public static final class ApgDoor extends BlockAPGDoor implements LineAware {

        private final XiamenLine line;

        public ApgDoor(final XiamenLine line) {
            this.line = line;
            // 同 Door：APG 门扇也吃 RenderPSDAPGDoor 的 TEMP 早退，默认态先关掉。
            setDefaultState(withoutTemp(getDefaultState()));
        }

        @Override
        public XiamenLine line() {
            return this.line;
        }

        @Override
        public BlockState getPlacementState(final ItemPlacementContext context) {
            final BlockState state = super.getPlacementState(context);
            return state == null ? null : withoutTemp(state);
        }

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityXiamenAPGDoor(pos, state);
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.set(this.line).apgDoorItem);
        }
    }

    /** APG 门扇方块实体 —— 列车开门调的 {@code setOpen} 就在 MTR 的父类里。 */
    public static final class TileEntityXiamenAPGDoor extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase {

        public TileEntityXiamenAPGDoor(BlockPos pos, BlockState state) {
            super(XiamenObjects.apgDoorEntityType(), pos, state);
        }
    }

    /**
     * APG 玻璃板（{@code side_extended} = left / middle / right / single，JSON 模型）。
     *
     * <p><b>父类必须是 MTR 的 {@code BlockAPGGlass}</b>（不是 {@code BlockPSDAPGGlassBase}）——
     * 玻璃上的线路图<b>不是方块模型画的</b>，而是 MTR 的方块实体渲染器
     * {@code mtr.render.RenderAPGGlass}（{@code extends RenderRouteBase<BlockAPGGlass.TileEntityAPGGlass>}）
     * 每帧现画的；而这个 BER 只挂在 {@code BlockEntityTypes.APG_GLASS_TILE_ENTITY} 上。要吃到它，
     * 方块必须
     * <ol>
     *   <li>{@code extends BlockAPGGlass}（它 {@code implements EntityBlockMapper}、并且多一个
     *       {@code ARROW_DIRECTION} 属性 —— {@code RenderRouteBase.render} 无条件读这个属性来定箭头朝向，
     *       刷子右键切换也靠它）；</li>
     *   <li>有自己的方块实体，且父类为 {@code BlockPSDTop.TileEntityRouteBase}
     *       （线路图的数据入口 {@code getPlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE)} 就在那里）。</li>
     * </ol>
     *
     * <p>以前这里继承的是 {@code BlockPSDAPGGlassBase}：那个基类<b>根本没有方块实体</b>
     * （{@code javap}：只有一个 {@code use} 和 {@code connectGlass}），所以玻璃是纯静态模型，
     * 线路图自然一格都没有。顺带一提 {@code BlockPSDAPGBase#isAPG()} 是用
     * {@code instanceof BlockAPGGlass} 判的，继承基类还会让上段的选中框高度错成整格。
     */
    public static final class ApgGlass extends BlockAPGGlass implements LineAware {

        private final XiamenLine line;

        public ApgGlass(final XiamenLine line) {
            this.line = line;
        }

        @Override
        public XiamenLine line() {
            return this.line;
        }

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityXiamenAPGGlass(pos, state);
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.set(this.line).apgGlassItem);
        }
    }

    /**
     * APG 玻璃的方块实体 —— 父类是 MTR 的 {@code TileEntityRouteBase}，与吊板（{@code TileEntityPSDTop}）
     * 同一个基类、同一套站台定位逻辑，所以玻璃和吊板画的是<b>同一份</b>线路图数据。
     */
    public static final class TileEntityXiamenAPGGlass extends BlockPSDTop.TileEntityRouteBase {

        public TileEntityXiamenAPGGlass(BlockPos pos, BlockState state) {
            super(XiamenObjects.apgGlassEntityType(), pos, state);
        }
    }

    /**
     * APG 端部收口（{@code touching_left} / {@code touching_right}）。
     *
     * <p>父类是 MTR 的 {@code BlockAPGGlassEnd}（{@code BlockPSDAPGGlassEndBase} 的薄壳 + 自己的
     * asItem），同样是为了让 {@code isAPG()} 为 {@code true}（端部选中框高度）。
     */
    public static final class ApgEnd extends BlockAPGGlassEnd implements LineAware {

        private final XiamenLine line;

        public ApgEnd(final XiamenLine line) {
            this.line = line;
        }

        @Override
        public XiamenLine line() {
            return this.line;
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.set(this.line).apgEndItem);
        }
    }
}
