package dev.js.mtrheadlight.security;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

import java.util.List;

/**
 * 液体安检机的物品形态 —— 只为了在物品栏里给一条操作提示。
 *
 * <p>这个方块的两种右键（药水 = 检测、刷子 = 开配置）在物品上完全看不出来，
 * 不写提示的话玩家只会把它当装饰。方块本身的行为全在 {@link LiquidScannerBlock}，这里不参与逻辑。
 *
 * <p>只多 import 了 {@code TooltipContext}（客户端也没有别的调用者），
 * {@code appendTooltip} 是 {@link BlockItem} 自带的接口，不需要 mixin。
 */
public final class LiquidScannerItem extends BlockItem {

    public LiquidScannerItem(final LiquidScannerBlock block, final Settings settings) {
        super(block, settings);
    }

    @Override
    public void appendTooltip(final ItemStack stack, final World world, final List<Text> tooltip,
                              final TooltipContext context) {
        super.appendTooltip(stack, world, tooltip, context);
        tooltip.add(Text.translatable("tooltip.mtrlte.liquid_scanner").formatted(Formatting.GRAY));
    }
}
