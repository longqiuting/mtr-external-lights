package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * 一次放置一整组屏蔽门 —— MTR {@code mtr:item/ItemPSDAPGBase} 的厦门版。
 *
 * <h2>MTR 的放置逻辑（javap {@code ItemPSDAPGBase.useOnBlock} / {@code method_7884}）</h2>
 * <pre>
 * int width  = item.isDoor ? (type.isOdd ? 3 : 2) : 1;   // 门两扇对开（宽 2），玻璃 / 端部宽 1
 * int height = type.isPSD  ? 3 : 2;                      // PSD 三格高
 * if (blocksNotReplaceable(ctx, width, height, block)) return FAIL;
 * BlockPos start = ctx.getBlockPos().offset(ctx.getSideHit());
 * for (int i = 0; i &lt; width; i++) {                      // ← 宽是外层循环
 *     BlockPos pos = start.offset(facing.rotateYCounterclockwise(), i);
 *     for (int dh = 0; dh &lt; 2; dh++) {                   // ← 每列只有 2 格：LOWER / UPPER
 *         BlockState s = state.with(FACING, facing).with(HALF, dh == 1 ? UPPER : LOWER);
 *         if (isDoor) s = s.with(SIDE, i == 0 ? LEFT : RIGHT);
 *         else        s = s.with(SIDE_EXTENDED, SINGLE);
 *         world.setBlockState(pos.up(dh), s);
 *     }
 *     if (isPSD) world.setBlockState(pos.up(2), BlockPSDTop.getActualState(world, pos.up(2)));   // ← 吊板 y+2
 * }
 * stack.decrement(1);                                    // ← 整组只消耗 1 个
 * </pre>
 *
 * <p>所以一次放置是 <b>2 宽 × 3 高 = 6 格</b>（门）/ <b>1 宽 × 3 高 = 3 格</b>（玻璃、端部），
 * 顺序是 <b>宽在外、高在内</b>，每列先下段再上段、最后补吊板。
 *
 * <p>放完还要补一遍「新方块自己收不到 neighbor update」的属性：
 * 玻璃的 {@code side}（LEFT/MIDDLE/RIGHT/SINGLE）和端部的
 * {@code touching_left/touching_right}（AIR/DOOR/NONE）。门扇的 {@code end} 在放置时就算好，
 * 相邻的旧方块会由 {@code getStateForNeighborUpdate} 自动修正。
 */
public class XiamenPsdItem extends BlockItem {

    public XiamenPsdItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        final Block block = this.getBlock();
        if (!(block instanceof XiamenPsdBaseBlock part)) {
            return super.useOnBlock(context);
        }
        final World world = context.getWorld();
        final BlockPos base = context.getBlockPos().offset(context.getSide());
        final Direction facing = context.getHorizontalPlayerFacing().getOpposite();
        final Direction left = facing.rotateYCounterclockwise();
        final boolean door = part.kind() == XiamenPsdBaseBlock.Kind.DOOR;
        final int width = door ? 2 : 1;

        if (!canPlace(world, base, left, width)) {
            return ActionResult.FAIL;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        final XiamenPsdTopBlock top = XiamenObjects.psdTop();
        for (int i = 0; i < width; i++) {
            final BlockPos col = base.offset(left, i);
            for (int dy = 0; dy < 2; dy++) {
                BlockState state = part.getDefaultState()
                        .with(XiamenPsdBaseBlock.FACING, facing)
                        .with(XiamenPsdBaseBlock.HALF, dy == 1 ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
                if (door) {
                    state = state.with(XiamenPsdDoorBlock.SIDE,
                            i == 0 ? XiamenPsdBaseBlock.EnumSide.LEFT : XiamenPsdBaseBlock.EnumSide.RIGHT);
                    // 外侧 = sideDirection 的反向：左扇在 facing 顺时针一侧，右扇在逆时针一侧
                    final Direction out = i == 0 ? facing.rotateYClockwise() : left;
                    state = state.with(XiamenPsdDoorBlock.END, hasEndOutside(world, col, out));
                }
                world.setBlockState(col.up(dy), state, Block.NOTIFY_ALL);
            }
            // 吊板：整列的第 3 格（y+2）
            world.setBlockState(col.up(2), top.getDefaultState().with(XiamenPsdTopBlock.FACING, facing),
                    Block.NOTIFY_ALL);
        }

        // 新放的玻璃 / 端部自己要补一遍邻居相关的属性（它们自己没收到 neighbor update）
        for (int i = 0; i < width; i++) {
            final BlockPos col = base.offset(left, i);
            for (int dy = 0; dy < 2; dy++) {
                refreshLinks(world, col.up(dy));
            }
        }

        final PlayerEntity player = context.getPlayer();
        if (player == null || !player.getAbilities().creativeMode) {
            context.getStack().decrement(1);
        }
        world.playSound(null, base, part.getDefaultState().getSoundGroup().getPlaceSound(),
                SoundCategory.BLOCKS, 0.9F, 1.0F);
        return ActionResult.SUCCESS;
    }

    /** 玻璃的 {@code side} / 端部的 {@code touching_left|right} 重算一次。 */
    public static void refreshLinks(World world, BlockPos pos) {
        final BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof XiamenPsdGlassBlock) {
            final XiamenPsdBaseBlock.EnumSide side =
                    XiamenPsdGlassBlock.computeSide(world, pos, state.get(XiamenPsdBaseBlock.FACING));
            if (state.get(XiamenPsdBaseBlock.SIDE_EXTENDED) != side) {
                world.setBlockState(pos, state.with(XiamenPsdBaseBlock.SIDE_EXTENDED, side), Block.NOTIFY_ALL);
            }
        } else if (state.getBlock() instanceof XiamenPsdEndBlock) {
            final Direction facing = state.get(XiamenPsdBaseBlock.FACING);
            final XiamenPsdBaseBlock.EndSide l =
                    XiamenPsdEndBlock.sideEnd(world, pos.offset(facing.rotateYCounterclockwise()));
            final XiamenPsdBaseBlock.EndSide r =
                    XiamenPsdEndBlock.sideEnd(world, pos.offset(facing.rotateYClockwise()));
            if (state.get(XiamenPsdBaseBlock.TOUCHING_LEFT) != l || state.get(XiamenPsdBaseBlock.TOUCHING_RIGHT) != r) {
                world.setBlockState(pos, state.with(XiamenPsdBaseBlock.TOUCHING_LEFT, l)
                        .with(XiamenPsdBaseBlock.TOUCHING_RIGHT, r), Block.NOTIFY_ALL);
            }
        }
    }

    /** 整组（宽 × 3 高）都得能吃下去，不然一格都别放 —— 和 MTR 的 {@code blocksNotReplaceable} 一致。 */
    private static boolean canPlace(World world, BlockPos base, Direction left, int width) {
        if (base.getY() < world.getBottomY()
                || base.getY() + XiamenPsdBaseBlock.COLUMN_HEIGHT - 1 > world.getTopY() - 1) {
            return false;
        }
        for (int i = 0; i < width; i++) {
            final BlockPos col = base.offset(left, i);
            for (int dy = 0; dy < XiamenPsdBaseBlock.COLUMN_HEIGHT; dy++) {
                if (!world.getBlockState(col.up(dy)).isReplaceable()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 门扇外侧那一格是不是端部玻璃（MTR：{@code instanceof BlockPSDAPGGlassEndBase}）。 */
    private static boolean hasEndOutside(World world, BlockPos col, Direction out) {
        return world.getBlockState(col.offset(out)).getBlock() instanceof XiamenPsdEndBlock;
    }
}
