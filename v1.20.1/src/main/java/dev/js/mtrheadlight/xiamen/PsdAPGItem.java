// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * MTR {@code mtr.item.ItemPSDAPGBase} 的移植 —— 屏蔽门的一整套放置逻辑。
 *
 * <ul>
 *   <li>门：宽 <b>2</b> 格 × 高 2 格，再在 y+2 补一格吊板（= 6 格）；</li>
 *   <li>玻璃 / 端部：宽 <b>1</b> 格 × 高 2 格 + 吊板（= 3 格）；</li>
 *   <li>整组做可替换检查（{@link #blocksNotReplaceable}：上下各多探一格黑名单，防止叠放），
 *       任何一格不行就整组不放；</li>
 *   <li>放成功后<b>只扣 1 个</b>物品（和 MTR 一样）。</li>
 * </ul>
 */
public class PsdAPGItem extends BlockItem {

    /** 三个变体：门 / 玻璃 / 端部（MTR 的 {@code EnumPSDAPGItem}）。 */
    public enum Kind {
        DOOR("psd_apg_door", true),
        GLASS("psd_apg_glass", false),
        END("psd_apg_glass_end", false);

        private final String name;
        private final boolean door;

        Kind(String name, boolean door) {
            this.name = name;
            this.door = door;
        }

        public String translationKey() {
            return "tooltip.mtrlte." + this.name;
        }
    }

    private final Kind kind;

    public PsdAPGItem(Block block, Kind kind, Settings settings) {
        super(block, settings);
        this.kind = kind;
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        int horizontalBlocks = this.kind.door ? 2 : 1;
        Block baseBlock = this.getBlock();
        if (blocksNotReplaceable(context, horizontalBlocks, 3, baseBlock)) {
            return ActionResult.FAIL;
        }
        World world = context.getWorld();
        Direction playerFacing = context.getHorizontalPlayerFacing();
        BlockPos pos = context.getBlockPos().offset(context.getSide());

        for (int x = 0; x < horizontalBlocks; x++) {
            BlockPos newPos = pos.offset(playerFacing.rotateYClockwise(), x);
            for (int y = 0; y < 2; y++) {
                BlockState state = baseBlock.getDefaultState()
                        .with(HorizontalFacingBlock.FACING, playerFacing)
                        .with(PsdProps.HALF, y == 1 ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
                if (this.kind.door) {
                    world.setBlockState(newPos.up(y),
                            state.with(PsdProps.SIDE, x == 0 ? PsdProps.Side.LEFT : PsdProps.Side.RIGHT));
                } else {
                    world.setBlockState(newPos.up(y),
                            state.with(PsdProps.SIDE_EXTENDED, PsdProps.Side.SINGLE));
                }
            }
            // 吊板：按刚放下的那一格现算 facing / side / air_left / air_right
            world.setBlockState(newPos.up(2), PsdTopBlock.getActualState(world, newPos.up(2)));
        }

        context.getStack().decrement(1);
        return ActionResult.SUCCESS;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable(this.kind.translationKey()).formatted(Formatting.GRAY));
    }

    /**
     * MTR 的 {@code blocksNotReplaceable}：整组每一格都必须可替换；另外在黑名单方块的
     * 正下方 / 正上方各多探一格，避免把吊板叠在自己身上。
     */
    public static boolean blocksNotReplaceable(ItemUsageContext context, int width, int height, Block blacklistBlock) {
        Direction facing = context.getHorizontalPlayerFacing();
        World world = context.getWorld();
        BlockPos startingPos = context.getBlockPos().offset(context.getSide());
        for (int x = 0; x < width; x++) {
            BlockPos offsetPos = startingPos.offset(facing.rotateYClockwise(), x);
            if (blacklistBlock != null) {
                boolean blacklistedBelow = world.getBlockState(offsetPos.down()).isOf(blacklistBlock);
                boolean blacklistedAbove = world.getBlockState(offsetPos.up(height)).isOf(blacklistBlock);
                if (blacklistedBelow || blacklistedAbove) {
                    return true;
                }
            }
            for (int y = 0; y < height; y++) {
                if (world.getBlockState(offsetPos.up(y)).isReplaceable()) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }
}
