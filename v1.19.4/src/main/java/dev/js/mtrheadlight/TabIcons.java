package dev.js.mtrheadlight;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 两个创造页签的图标物品：{@code mtrlte:tab_core} 与 {@code mtrlte:tab_tools}。
 *
 * <h2>为什么要有「物品」</h2>
 * <p>1.20.1 里 {@code ItemGroup.Builder#icon} 收的是 {@code Supplier<ItemStack>}，而页签上那 16×16
 * 的小图是<b>由物品模型画出来的</b>（{@code ItemRenderer} 取 {@code Item} 的模型 id）。也就是说：
 * 想让页签显示一张我们自己画的贴图，就必须有一个「模型指向这张贴图的物品」——
 * 1.20.1 没有 1.20.5 之后的 {@code item_model} 组件，也没有「无物品的页签贴图」这条路。
 * 直接 {@code new ItemStack(Items.PAPER)} 只能画成纸，改不到（除非覆盖原版 paper 的模型，那会影响
 * 全世界的纸）。
 *
 * <p>所以这里注册两个<b>纯图标物品</b>：无功能、不进任何创造页签（{@link ItemGroupEvents} 从不
 * add 它们，创造模式里翻不到，只能在搜索不到的孤儿位置用 {@code /give} 拿到），存在的唯一意义就是
 * 让页签有一个可以指向的模型：
 * <ul>
 *   <li>{@code item/tab_core.json} → {@code mtrlte:item/tab_core}</li>
 *   <li>{@code item/tab_tools.json} → {@code mtrlte:item/tab_tools}</li>
 * </ul>
 * 两张贴图都是 16×16 PNG，透明背景：厦门地铁 M 标（{@code mtr/textures/block/sign/logo.png}，
 * 600×600）与 LTE 标识（{@code C:\Users\AD\Desktop\标准封面\LTE.png}，2048×2048），
 * 都按内容框裁到 16×16（保比例、居中、NEAREST 采样、alpha 不动），转换脚本见
 * {@code temp/Scale.java}。
 *
 * <p>{@link #register()} 幂等，两个页签的注册类（{@code signal.LteCoreItems} /
 * {@code xiamen.XiamenObjects}）各自先调一次，所以入口里的调用顺序无所谓。
 */
public final class TabIcons {

    /** 图标物品 id：{@code mtrlte:tab_core}（「厦门风格物件」页签）。 */
    public static final Identifier CORE_ID = new Identifier("mtrlte", "tab_core");

    /** 图标物品 id：{@code mtrlte:tab_tools}（「LTE 工具」页签）。 */
    public static final Identifier TOOLS_ID = new Identifier("mtrlte", "tab_tools");

    private static Item core;
    private static Item tools;
    private static boolean registered;

    private TabIcons() {
    }

    /** 注册两个图标物品（{@code main} 入口，两端都要有）。幂等。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        core = Registry.register(Registries.ITEM, CORE_ID, new Item(new Item.Settings()));
        tools = Registry.register(Registries.ITEM, TOOLS_ID, new Item(new Item.Settings()));
    }

    /** 「厦门风格物件」页签的图标物品（没注册就先注册，页签 icon supplier 随时可调）。 */
    public static Item core() {
        register();
        return core;
    }

    /** 「LTE 工具」页签的图标物品。 */
    public static Item tools() {
        register();
        return tools;
    }
}
