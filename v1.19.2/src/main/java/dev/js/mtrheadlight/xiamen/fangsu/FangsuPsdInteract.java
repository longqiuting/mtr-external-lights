// 方速屏蔽门这套方块「用哪个工具右键」的判定：MTR 刷子 / MTR 驾驶钥匙。
package dev.js.mtrheadlight.xiamen.fangsu;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * 两个 MTR 工具的判定（{@code mtr.Items.BRUSH} / {@code mtr.Items.DRIVER_KEY}，javap 3.6.4.2 确认）。
 *
 * <p>为什么不直接写 {@code stack.isOf(mtr.Items.BRUSH.get())}：MTR 没装 / 版本不对时
 * {@code .get()} 会抛 NPE，右键就整片失效。这里把异常吃掉，判定退化成「都不是」，
 * 门仍然能放、能开（只是少了那个工具）。
 */
final class FangsuPsdInteract {

    private FangsuPsdInteract() {
    }

    /** MTR 刷子（{@code mtr:brush}）：玻璃板循环形态、滑动门手动开关。 */
    static boolean isBrush(final ItemStack stack) {
        return matches(stack, safe("brush"));
    }

    /**
     * MTR 驾驶钥匙（{@code mtr:driver_key}）：<b>端门</b>的开 / 关。
     *
     * <p>来源：{@code javap -p mtr.Items} →
     * {@code public static final mtr.RegistryObject<Item> DRIVER_KEY;}；物品 id 由 MTR 的
     * {@code en_us.json} 里 {@code "item.mtr.driver_key": "Driver Key"} 反推为 {@code mtr:driver_key}。
     */
    static boolean isDriverKey(final ItemStack stack) {
        return matches(stack, safe("driver_key"));
    }

    private static Item safe(final String which) {
        try {
            return which.equals("brush") ? mtr.Items.BRUSH.get() : mtr.Items.DRIVER_KEY.get();
        } catch (Throwable t) {
            return Items.AIR;
        }
    }

    private static boolean matches(final ItemStack stack, final Item wanted) {
        return wanted != null && wanted != Items.AIR && stack != null && !stack.isEmpty()
                && stack.getItem() == wanted;
    }
}
