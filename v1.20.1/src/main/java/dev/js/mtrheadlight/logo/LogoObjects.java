// 标志方块的注册：LTE 自己的 logo（工具页） + 角美地铁 logo（厦门页）。
package dev.js.mtrheadlight.logo;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.signal.LteCoreItems;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 两个标志方块的注册入口（两端都要跑）。
 *
 * <table border="1">
 *   <caption>方块 / 贴图 / 页签</caption>
 *   <tr><th>id</th><th>贴图</th><th>进哪个页签</th></tr>
 *   <tr><td>{@code mtrlte:lte_logo}</td><td>{@code block/lte_logo}（LTE 标志）</td>
 *       <td>{@code mtrlte:tools}「LTE 工具」（{@link LteCoreItems#TOOLS_TAB_ID}）</td></tr>
 *   <tr><td>{@code mtrlte:jiaomei_logo}</td><td>{@code block/jiaomei_logo}（角美地铁标志）</td>
 *       <td>{@code mtrlte:core}「厦门风格物件」（{@link XiamenObjects#CORE_TAB_ID}）</td></tr>
 * </table>
 *
 * <p>方块和物品用同一个 id（本模组一贯的规矩）。两个方块除了贴图没有别的区别，
 * 所以同一个 {@link LogoBlock} 类实例化两次。
 *
 * <h2>属性：逐字照抄 MTR 的 {@code Blocks.LOGO}</h2>
 * <pre>{@code
 * AbstractBlock.Settings.create()
 *         .requiresTool()            // MTR: method_29292
 *         .hardness(2.0f)            // MTR: method_9632
 *         .luminance(state -> 10)    // MTR: method_9631
 * }</pre>
 * <p>另外加了 {@code mapColor(IRON_GRAY)}（MTR 没写，默认就是铁灰，写出来只为了和本模组
 * 其它方块一致），以及进 {@code mineable/pickaxe} 标签 ——
 * <b>{@code requiresTool()} 的方块不在镐子标签里就挖不掉任何一个掉落物</b>，
 * 所以这一条必须和数据包里的 {@code data/minecraft/tags/blocks/mineable/pickaxe.json}
 * 一起改（已经加了）。
 *
 * <p>由 {@code LteServer} 在 {@link XiamenObjects#register()} / {@link LteCoreItems#register()}
 * 之后调用 —— 两个页签都得先建好，往页签里追加条目才不会落空。
 */
public final class LogoObjects {

    /** 模组命名空间。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** LTE 自己的标志：{@code mtrlte:lte_logo}（「LTE 工具」页签）。 */
    public static final Identifier LTE_ID = new Identifier(NAMESPACE, "lte_logo");

    /** 角美地铁标志：{@code mtrlte:jiaomei_logo}（「厦门风格物件」页签）。 */
    public static final Identifier JIAOMEI_ID = new Identifier(NAMESPACE, "jiaomei_logo");

    private static LogoBlock lteLogo;
    private static LogoBlock jiaomeiLogo;
    private static Item lteItem;
    private static Item jiaomeiItem;
    private static boolean registered;

    private LogoObjects() {
    }

    public static LogoBlock lteLogo() {
        return lteLogo;
    }

    public static LogoBlock jiaomeiLogo() {
        return jiaomeiLogo;
    }

    public static Item lteItem() {
        return lteItem;
    }

    public static Item jiaomeiItem() {
        return jiaomeiItem;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        lteLogo = registerBlock(LTE_ID);
        jiaomeiLogo = registerBlock(JIAOMEI_ID);
        lteItem = registerItem(LTE_ID, lteLogo);
        jiaomeiItem = registerItem(JIAOMEI_ID, jiaomeiLogo);

        // LTE 标志进「LTE 工具」页签（排在连接器 / 编辑器那几件之后，logo 是装饰品）。
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, LteCoreItems.TOOLS_TAB_ID))
                    .register(entries -> entries.add(lteItem));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] lte logo: could not add the tools tab entry", t);
        }

        // 角美地铁标志进「厦门风格物件」页签（和厦门地铁标志同一页）。
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> entries.add(jiaomeiItem));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] jiaomei logo: could not add the core tab entry", t);
        }

        LteLog.LOGGER.info("[lte] logo blocks: {} / {}", LTE_ID, JIAOMEI_ID);
    }

    /** MTR {@code Blocks.LOGO} 的设置，只有贴图不同。 */
    private static LogoBlock registerBlock(final Identifier id) {
        final LogoBlock block = new LogoBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .requiresTool()
                .hardness(2.0F)
                .luminance(state -> 10));
        return Registry.register(Registries.BLOCK, id, block);
    }

    private static Item registerItem(final Identifier id, final LogoBlock block) {
        return Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
    }
}
