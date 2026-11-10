// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

/**
 * 屏蔽门公共部分 —— MTR {@code mtr.block.IBlock} 的等价物（只搬屏蔽门用到的那几项）。
 *
 * <p>属性名与取值必须和 MTR 一致（{@code side=left|right|middle|single}、{@code half=lower|upper}、
 * {@code end}、{@code unlocked}），因为 blockstate / model 的 JSON 是从 MTR 资源包里原样搬过来的。
 */
public final class PsdProps {

    /** MTR 的 {@code IBlock.HALF}：就是原版的 DOUBLE_BLOCK_HALF。 */
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    /** 门扇专用：只有 LEFT / RIGHT（对开两扇）。 */
    public static final EnumProperty<Side> SIDE =
            EnumProperty.of("side", Side.class, side -> side == Side.LEFT || side == Side.RIGHT);

    /** 玻璃 / 吊板用：LEFT / RIGHT / MIDDLE / SINGLE（一条玻璃链的两端与中间）。 */
    public static final EnumProperty<Side> SIDE_EXTENDED = EnumProperty.of("side", Side.class);

    /** 门 / 玻璃链的这一侧是不是端头（旁边是端部收口）。 */
    public static final BooleanProperty END = BooleanProperty.of("end");

    /** 是否解锁（MTR 语义：解锁的门会被强制关上）。 */
    public static final BooleanProperty UNLOCKED = BooleanProperty.of("unlocked");

    /** MTR 的 {@code IBlock.EnumSide}。 */
    public enum Side implements StringIdentifiable {
        LEFT("left"),
        RIGHT("right"),
        MIDDLE("middle"),
        SINGLE("single");

        private final String name;

        Side(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }
    }

    private PsdProps() {
    }

    /** MTR 的 {@code IBlock.getVoxelShapeByDirection}：基准系是 facing=west（plate 在 x 小的一侧）。 */
    public static VoxelShape shapeByDirection(double x1, double y1, double z1,
                                             double x2, double y2, double z2, Direction facing) {
        return switch (facing) {
            case NORTH -> Block.createCuboidShape(x1, y1, z1, x2, y2, z2);
            case EAST -> Block.createCuboidShape(16.0D - z2, y1, x1, 16.0D - z1, y2, x2);
            case SOUTH -> Block.createCuboidShape(16.0D - x2, y1, 16.0D - z2, 16.0D - x1, y2, 16.0D - z1);
            case WEST -> Block.createCuboidShape(z1, y1, 16.0D - x2, z2, y2, 16.0D - x1);
            default -> VoxelShapes.empty();
        };
    }

    /** MTR 的 {@code IBlock.getStatePropertySafe}：方块没有这个属性时给默认值，不抛异常。 */
    @SuppressWarnings("unchecked")
    public static <T extends Comparable<T>> T prop(BlockState state, Property<T> property) {
        T fallback = (T) property.getValues().iterator().next();
        try {
            return state.contains(property) ? state.get(property) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** MTR 的 {@code IBlock.getSideDirection}：这一扇门「另一半」所在的方向。 */
    public static Direction sideDirection(BlockState state) {
        Direction facing = prop(state, HorizontalFacingBlock.FACING);
        return prop(state, SIDE) == Side.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
    }

    /**
     * MTR 的 {@code IBlock.onBreakCreative}：创造模式玩家打掉上段 / 右扇时，把真正那一格也清成空气，
     * 免得留下一半的方块。生存模式不处理（照 MTR）。
     */
    public static void onBreakCreative(World world, PlayerEntity player, BlockPos pos) {
        if (!world.isClient && (player == null || player.isCreative())) {
            BlockState state = world.getBlockState(pos);
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 35);
            world.syncWorldEvent(player, 2001, pos, Block.getRawIdFromState(state));
        }
    }

    /** MTR 的 {@code IBlock.checkHoldingBrush}：拿着刷子才执行回调。 */
    public static ActionResult checkHoldingBrush(World world, PlayerEntity player, Runnable callbackBrush) {
        return checkHoldingBrush(world, player, callbackBrush, null);
    }

    public static ActionResult checkHoldingBrush(World world, PlayerEntity player,
                                                 Runnable callbackBrush, Runnable callbackNoBrush) {
        return checkHoldingItem(world, player, item -> callbackBrush.run(), callbackNoBrush,
                net.minecraft.item.Items.BRUSH);
    }

    /** MTR 的 {@code IBlock.checkHoldingItem}：返回值与 MTR 完全一致。 */
    public static ActionResult checkHoldingItem(World world, PlayerEntity player, Consumer<Item> callbackItem,
                                                Runnable callbackNoItem, Item... items) {
        Item holdingItem = null;
        for (Item item : items) {
            if (player.isHolding(item)) {
                holdingItem = item;
                break;
            }
        }
        if (holdingItem != null) {
            if (!world.isClient) {
                callbackItem.accept(holdingItem);
            }
            return ActionResult.SUCCESS;
        }
        if (callbackNoItem == null) {
            return ActionResult.FAIL;
        }
        if (!world.isClient) {
            callbackNoItem.run();
            return ActionResult.CONSUME;
        }
        return ActionResult.SUCCESS;
    }

    /** MTR 的 {@code IBlock.isReplaceable}：沿 direction 连着 totalLength 格都能替换才行。 */
    public static boolean isReplaceable(net.minecraft.item.ItemPlacementContext ctx, Direction direction, int totalLength) {
        for (int i = 0; i < totalLength; i++) {
            if (!ctx.getWorld().getBlockState(ctx.getBlockPos().offset(direction, i)).isReplaceable()) {
                return false;
            }
        }
        return true;
    }

    /** 判断某个格子下方是不是「自己这一类」（放置检查 / 吊板判定用）。 */
    public static boolean isPsdBelow(World world, BlockPos pos) {
        return world.getBlockState(pos.down()).getBlock() instanceof PsdAPGBaseBlock;
    }
}
