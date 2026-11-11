package dev.js.mtrheadlight.bind;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * LTE 仪表板（{@code mtrlte:lte_dashboard}）：拿在手上右键，打开 LTE 的配置界面。
 *
 * <h2>只有客户端</h2>
 * 配置界面是一个 {@code Screen}，服务端没有它的概念，所以：
 * <ul>
 *   <li>服务端那一次 {@code use} 直接 {@code PASS} —— 不发包、不产生任何服务端状态；</li>
 *   <li>客户端走和 {@code /mtr_lte} 完全相同的入口
 *       {@code MtrHeadlightClient#openConfigScreen()}（键位、指令、物品三条路都进同一个界面）；</li>
 *   <li>调用是<b>反射</b>的：{@code MtrHeadlightClient} 只存在于客户端，dedicated server 上没有这个类，
 *       直接写 {@code MtrHeadlightClient.openConfigScreen()} 会让这个物品类在服务端首次触碰常量池时
 *       就有找不到类的风险。反射 + 捕获 Throwable，服务端上「点一下什么都没发生」而不是崩。</li>
 * </ul>
 *
 * <p>物品本身两端都注册（{@code LteCoreItems}，{@code main} 入口）：只在客户端注册的物品，
 * 在服务器上会变成「未知物品」，联机时会掉。
 */
public final class LteDashboardItem extends Item {

    public LteDashboardItem(final Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(final World world, final PlayerEntity user, final Hand hand) {
        final ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) {
            // 服务端不处理：界面是纯客户端的东西。
            return TypedActionResult.pass(stack);
        }
        open();
        return TypedActionResult.success(stack);
    }

    /**
     * 打开配置界面（客户端）。反射同名入口，见类注释。
     *
     * @return {@code false} = 这一侧没有客户端类（dedicated server）或调用失败
     */
    public static boolean open() {
        try {
            Class.forName("dev.js.mtrheadlight.MtrHeadlightClient")
                    .getMethod("openConfigScreen")
                    .invoke(null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void appendTooltip(final ItemStack stack, final World world,
                              final List<Text> tooltip, final TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.mtrlte.dashboard.hint").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.mtrlte.dashboard.client_only").formatted(Formatting.DARK_GRAY));
    }
}
