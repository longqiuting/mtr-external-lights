// Xiamen PSD blocks — 直接继承 MTR 的方块 / 方块实体（不做移植副本）。
package dev.js.mtrheadlight.xiamen;

import java.util.function.Consumer;

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
 * 厦门屏蔽门的四个方块 —— <b>全部继承 MTR 的方块类</b>，只换贴图与名字。
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
            return IBlock.checkHoldingItem(world, player, item -> {
                Direction facing = IBlock.getStatePropertySafe(state, FACING);
                if (item == Items.BRUSH) {
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
            }, null, Items.BRUSH, Items.BARRIER);
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
}
