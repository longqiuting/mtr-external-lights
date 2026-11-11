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

    /** 门扇（y+0 / y+1 两段，左右两扇对开）。 */
    public static final class Door extends BlockPSDAPGDoorBase {

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityXiamenPSDDoor(pos, state);
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.psdDoorItem());
        }
    }

    /** 门扇方块实体 —— MTR 的 {@code setOpen} / {@code getOpen} 就在这里，列车调的就是它。 */
    public static final class TileEntityXiamenPSDDoor extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase {

        public TileEntityXiamenPSDDoor(BlockPos pos, BlockState state) {
            super(XiamenObjects.psdDoorEntityType(), pos, state);
        }
    }

    /** 玻璃板（一条链里按 side=left/middle/right/single 自动切换模型）。 */
    public static final class Glass extends BlockPSDAPGGlassBase {

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.psdGlassItem());
        }
    }

    /** 端部收口（{@code touching_left} / {@code touching_right}）。 */
    public static final class GlassEnd extends BlockPSDAPGGlassEndBase {

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.psdEndItem());
        }
    }

    /**
     * 吊板（y+2）。继承 MTR 的 {@code BlockPSDTop}，只重写三处必须换成本模组方块的地方：
     * 方块实体（要有自己的注册类型）、{@code getStateForNeighborUpdate}（MTR 的静态
     * {@code getActualState} 会把方块换回 MTR 自己的 {@code Blocks.PSD_TOP}）、
     * {@code onUse}（MTR 的 {@code setState} 用 {@code instanceof BlockPSDDoor} 判下层方块）。
     */
    public static final class Top extends BlockPSDTop {

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityXiamenPSDTop(pos, state);
        }

        /** MTR 里吊板中键选取给玻璃物品。 */
        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.psdGlassItem());
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
                    : XiamenObjects.psdTop().getDefaultState();
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
    public static final class ApgDoor extends BlockAPGDoor {

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityXiamenAPGDoor(pos, state);
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.apgDoorItem());
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
    public static final class ApgGlass extends BlockAPGGlass {

        @Override
        public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityXiamenAPGGlass(pos, state);
        }

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.apgGlassItem());
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
    public static final class ApgEnd extends BlockAPGGlassEnd {

        @Override
        public Item asItem() {
            return itemOrAir(XiamenObjects.apgEndItem());
        }
    }
}
