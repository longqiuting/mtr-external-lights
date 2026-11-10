// Xiamen PSD placement item — same rules as MTR's ItemPSDAPGBase.
package dev.js.mtrheadlight.xiamen;

import java.util.List;

import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.IBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * MTR {@code mtr.item.ItemPSDAPGBase} 的等价物 —— 屏蔽门的一整套放置逻辑。
 *
 * <ul>
 *   <li>门：宽 <b>2</b> 格 × 高 2 格，再在 y+2 补一格吊板（= 6 格）；</li>
 *   <li>玻璃 / 端部：宽 <b>1</b> 格 × 高 2 格 + 吊板（= 3 格）；</li>
 *   <li>整组做可替换检查，任何一格不行就整组不放；</li>
 *   <li>放成功后<b>只扣 1 个</b>物品（和 MTR 一样）。</li>
 * </ul>
 *
 * <p>方块状态属性现在全部来自 MTR：{@code IBlock#SIDE}（门，left/right）、
 * {@code IBlock#SIDE_EXTENDED}（玻璃 / 吊板，left/middle/right/single）、
 * {@code IBlock#HALF}（上下段）。名字和取值与资源包里的 blockstate JSON 一致。
 */
public class PsdAPGItem extends BlockItem {

    /**
     * 六个变体：全高 PSD 的「门 / 玻璃 / 端部」+ 半高 APG 的「门 / 玻璃 / 端部」
     * （MTR 的 {@code EnumPSDAPGItem} × {@code EnumPSDAPGType}）。
     *
     * <p>{@code topPanel} 就是 MTR {@code EnumPSDAPGType#isPSD} 的等价物：{@code true} 时整列是
     * 「2 高 + 1 格吊板」（3 格），{@code false} 时只有 2 格、<b>没有吊板</b>（MTR 的 APG）。
     */
    public enum Kind {
        /** 全高 PSD 门扇：2 宽 × 2 高 + 吊板。 */
        DOOR("psd_apg_door", true, true),
        /** 全高 PSD 玻璃：1 宽 × 2 高 + 吊板。 */
        GLASS("psd_apg_glass", false, true),
        /** 全高 PSD 端部：1 宽 × 2 高 + 吊板。 */
        END("psd_apg_glass_end", false, true),
        /** 半高 APG 门扇：2 宽 × 2 高，无吊板。 */
        APG_DOOR("apg_door", true, false),
        /** 半高 APG 玻璃：1 宽 × 2 高，无吊板。 */
        APG_GLASS("apg_glass", false, false),
        /** 半高 APG 端部：1 宽 × 2 高，无吊板。 */
        APG_END("apg_glass_end", false, false);

        private final String name;
        private final boolean door;
        private final boolean topPanel;

        Kind(String name, boolean door, boolean topPanel) {
            this.name = name;
            this.door = door;
            this.topPanel = topPanel;
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
        // MTR 的判据：blocksNotReplaceable 的高度是 isPSD ? 3 : 2（APG 只占 2 格）。
        int checkHeight = this.kind.topPanel ? 3 : 2;
        Block baseBlock = this.getBlock();
        if (blocksNotReplaceable(context, horizontalBlocks, checkHeight, baseBlock)) {
            return ActionResult.FAIL;
        }
        World world = context.getWorld();
        Direction playerFacing = context.getHorizontalPlayerFacing();
        BlockPos pos = context.getBlockPos().offset(context.getSide());

        for (int x = 0; x < horizontalBlocks; x++) {
            BlockPos newPos = pos.offset(playerFacing.rotateYClockwise(), x);
            for (int y = 0; y < 2; y++) {
                BlockState state = baseBlock.getDefaultState()
                        .with(Properties.HORIZONTAL_FACING, playerFacing)
                        .with(IBlock.HALF, y == 1 ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
                if (this.kind.door) {
                    world.setBlockState(newPos.up(y), state
                            .with(IBlock.SIDE, x == 0 ? IBlock.EnumSide.LEFT : IBlock.EnumSide.RIGHT)
                            .with(BlockPSDAPGDoorBase.UNLOCKED, true));
                } else {
                    world.setBlockState(newPos.up(y),
                            state.with(IBlock.SIDE_EXTENDED, IBlock.EnumSide.SINGLE));
                }
            }
            // 吊板：按刚放下的那一格现算 facing / side / air_left / air_right。
            // 半高 APG 没有吊板 —— 这一步在那边整个跳过（MTR 的 ItemPSDAPGBase 也一样）。
            if (this.kind.topPanel) {
                world.setBlockState(newPos.up(2), XiamenPsdBlocks.Top.actualState(world, newPos.up(2)));
            }
        }

        context.getStack().decrement(1);
        return ActionResult.SUCCESS;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable(this.kind.translationKey()).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.mtrlte.texture_source").formatted(Formatting.DARK_GRAY));
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
