// 标志方块的注册：LTE 自己的 logo（工具页） + 角美地铁 logo（厦门页）。
package dev.js.mtrheadlight.logo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.signal.LteCoreItems;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
 * <p>「厦门风格物件」页签的<b>最前排</b>顺序由 {@link #pinCoreTabOrder()} 统一重排：
 * 厦门 logo → 角美 logo → 厦门贴墙标识 → 角美贴墙标识 → LTE 贴墙标识 → 四件站外标志，
 * 不依赖「谁先注册谁在前」。
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

    /**
     * 「厦门风格物件」页签最前面那几件，<b>按用户点名的顺序</b>：
     * 厦门 logo → 角美 logo → 厦门贴墙标识 → 角美贴墙标识 → LTE 贴墙标识 →
     * 四件站外标志（挂墙 / 杆上 × 厦门 / 角美）。
     *
     * <p>三个贴墙标识紧跟在两个 logo 块<b>后面</b>（用户：「贴墙标识要放到两个 logo 块的后面」）——
     * 它们是同一族的「牌子」，排在一起才好找；四件站外标志<sub>（挂墙 / 杆上的出口牌）</sub>
     * 不属于这一族，排在它们之后。{@code wall_sign_lte} 同时也是「LTE 工具」页签的第 2 位
     * （{@link LteCoreItems#pinToolsTabOrder()}），两处都在，存档里是同一个方块。
     */
    private static final List<Identifier> CORE_TAB_FRONT = List.of(
            XiamenObjects.LOGO_ID,
            JIAOMEI_ID,
            new Identifier(NAMESPACE, "wall_sign_xiamen"),
            new Identifier(NAMESPACE, "wall_sign_jiaomei"),
            new Identifier(NAMESPACE, "wall_sign_lte"),
            new Identifier(NAMESPACE, "station_sign_wall_xiamen"),
            new Identifier(NAMESPACE, "station_sign_pole_xiamen"),
            new Identifier(NAMESPACE, "station_sign_wall_jiaomei"),
            new Identifier(NAMESPACE, "station_sign_pole_jiaomei"));

    /**
     * 「厦门风格物件」页签<b>末尾</b>那几件：留空。
     *
     * <p>上一版把厦门 / 角美两个贴墙标识放在这里（页签最末），用户反馈「放错位置了，要放到两个 logo 块的后面」，
     * 于是它们被挪进了 {@link #CORE_TAB_FRONT}。列表留着（{@link #pinCoreTabOrder()} 仍然会按它重排），
     * 以后有要压到最末的东西往这里加就行。
     */
    private static final List<Identifier> CORE_TAB_TAIL = List.of();


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

        // LTE 标志进「LTE 工具」页签，排在最前面（用户要求：装饰性 logo 先看到）。
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, LteCoreItems.TOOLS_TAB_ID))
                    .register(entries -> {
                        final List<ItemStack> stacks = entries.getDisplayStacks();
                        stacks.removeIf(s -> s.isOf(lteItem));
                        stacks.add(0, new ItemStack(lteItem));
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] lte logo: could not add the tools tab entry", t);
        }

        // 厦门 / 角美两个标志进「厦门风格物件」页签的**最前面**，厦门在前、角美紧跟其后。
        // （最终的「logo → 贴墙标识 → 站外标志」顺序由 pinCoreTabOrder() 统一重排，这里只保证先有位。）
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        final Item xiamenItem = XiamenObjects.logo().asItem();
                        final List<ItemStack> stacks = entries.getDisplayStacks();
                        stacks.removeIf(s -> s.isOf(xiamenItem) || s.isOf(jiaomeiItem));
                        stacks.add(0, new ItemStack(jiaomeiItem));
                        stacks.add(0, new ItemStack(xiamenItem));
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] jiaomei logo: could not add the core tab entry", t);
        }

        LteLog.LOGGER.info("[lte] logo blocks: {} / {}", LTE_ID, JIAOMEI_ID);
    }

    /**
     * 把「厦门风格物件」页签重排成用户要的样子：
     * <b>最前排</b> {@link #CORE_TAB_FRONT}（两个 logo → 四件站外标志），
     * <b>末尾</b> {@link #CORE_TAB_TAIL}（厦门 / 角美两个贴墙标识）。
     *
     * <p>做法：拿 {@code entries.getDisplayStacks()}（页签正在拼的可变列表）→ 把我们关心的那几件
     * 按列表顺序摘出来 → 原样（是同一个 {@link ItemStack} 实例，NBT 都不丢）
     * 一次性插回下标 0 / 追加到末尾。其余条目保持原有相对顺序，只被「往下推」。
     *
     * <h2>为什么单独一个方法、由 {@code LteServer} 在站外标志之后调</h2>
     * <p>{@code ItemGroupEvents.modifyEntriesEvent} 的回调<b>按注册顺序</b>执行，回调里才看得见
     * 前面回调已经加进去的条目。两个 logo 在 {@link #register()} 里就进页签了，贴墙标识
     * （{@code WallSignObjects}）紧接着进，站外标志（{@code StationSignObjects}）最后进 ——
     * 所以重排必须在<b>它们都注册完</b>之后注册，才不会「重排时那几件还没进列表」。
     * 之后注册的模块（闸机 / 安检 / eyecandy / 方速 …）只会往回加，插到末尾，
     * 不会跑回前排，所以再晚就没有影响。
     */
    public static void pinCoreTabOrder() {
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        final List<ItemStack> stacks = entries.getDisplayStacks();
                        final List<ItemStack> front = new ArrayList<>(CORE_TAB_FRONT.size());
                        for (final Identifier id : CORE_TAB_FRONT) {
                            remember(stacks, id, front);
                        }
                        if (!front.isEmpty()) {
                            stacks.addAll(0, front);
                        }
                        // 末尾：两个贴墙标识（厦门组之后）。
                        final List<ItemStack> tail = new ArrayList<>(CORE_TAB_TAIL.size());
                        for (final Identifier id : CORE_TAB_TAIL) {
                            remember(stacks, id, tail);
                        }
                        if (!tail.isEmpty()) {
                            stacks.addAll(tail);
                        }
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] core tab order: could not pin the front of the xiamen tab", t);
        }
    }

    /** 把 {@code id} 那一件从 {@code stacks} 里摘出来放进 {@code out}（同一个实例，NBT 不丢）。 */
    private static void remember(final List<ItemStack> stacks, final Identifier id, final List<ItemStack> out) {
        final Item item = Registries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            // 方块没注册（被别的模块挡了 / 版本差异）：跳过，不影响其余排序。
            return;
        }
        for (final Iterator<ItemStack> it = stacks.iterator(); it.hasNext();) {
            final ItemStack stack = it.next();
            if (stack.isOf(item)) {
                out.add(stack);
                it.remove();
            }
        }
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
