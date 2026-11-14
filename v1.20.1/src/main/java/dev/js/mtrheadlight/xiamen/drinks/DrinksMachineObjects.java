// 饮料售卖机的注册：两块方块 + 两个方块物品 + 一个方块实体类型 + 创造页签条目。
package dev.js.mtrheadlight.xiamen.drinks;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * 饮料售卖机的注册入口（两端都要跑）。
 *
 * <table border="1">
 *   <caption>id 一览</caption>
 *   <tr><th>id</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code mtrlte:drinks_machine_top}</td><td>方块 + 方块物品</td>
 *       <td>上半（货道）：微光 {@value DrinksMachineBlock#TOP_LUMINANCE}。模型
 *       {@code rawmodels/drinks_machine_top.json}（BER 画）</td></tr>
 *   <tr><td>{@code mtrlte:drinks_machine_bottom}</td><td>方块 + 方块物品</td>
 *       <td>下半（取货口，有 {@code open}）：微光 {@value DrinksMachineBlock#BOTTOM_LUMINANCE}。模型
 *       {@code drinks_machine_bottom_closed.json} / {@code _opened.json}</td></tr>
 *   <tr><td>{@code mtrlte:drinks_machine}</td><td>方块实体类型</td>
 *       <td>两块<b>共用</b>一个类型（各自是不同位置的实例）</td></tr>
 * </table>
 *
 * <p>两块<b>不是</b>「一个 id + half」：需求要两个 id / 两件物品，但行为是<b>联动</b>
 * （放一块自动补另一块、拆任一块拆整台，见 {@link DrinksMachineBlock}）。
 *
 * <p>亮度用 {@code Settings#luminance} 声明，属于<b>方块光照</b>：上下两块<b>全部</b>发微光
 * （上半 10、下半 6），与 blockstate / BER 无关。
 *
 * <p>掉落：{@code data/mtrlte/loot_tables/blocks/drinks_machine_{top,bottom}.json} 各自掉自己；
 * 另一半是被邻居更新收走的（{@code setBlockState}，不经过战利品表），所以恒为 1 个。
 *
 * <p>由 {@code LteServer} 在充电宝机之后调用；条目进「厦门风格物件」页签
 * （{@link XiamenObjects#CORE_TAB_ID}，车站设备都在这一页）。
 */
public final class DrinksMachineObjects {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 上半（货道）。 */
    public static final Identifier TOP_ID = new Identifier(NAMESPACE, "drinks_machine_top");

    /** 下半（取货口 + 投币）。 */
    public static final Identifier BOTTOM_ID = new Identifier(NAMESPACE, "drinks_machine_bottom");

    /** 方块实体类型（两块共用）。 */
    public static final Identifier BE_ID = new Identifier(NAMESPACE, "drinks_machine");

    private static DrinksMachineBlock top;
    private static DrinksMachineBlock bottom;
    private static Item topItem;
    private static Item bottomItem;
    private static BlockEntityType<DrinksMachineBlockEntity> blockEntityType;
    private static boolean registered;

    private DrinksMachineObjects() {
    }

    public static DrinksMachineBlock top() {
        return top;
    }

    public static DrinksMachineBlock bottom() {
        return bottom;
    }

    public static BlockEntityType<DrinksMachineBlockEntity> blockEntityType() {
        return blockEntityType;
    }

    /** 方块 + 物品 + 方块实体 + 创造页签条目。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        top = Registry.register(Registries.BLOCK, TOP_ID,
                new DrinksMachineBlock(settings(DrinksMachineBlock.TOP_LUMINANCE), true,
                        "tooltip.mtrlte.drinks_machine_top"));
        topItem = Registry.register(Registries.ITEM, TOP_ID, new BlockItem(top, new Item.Settings()));

        bottom = Registry.register(Registries.BLOCK, BOTTOM_ID,
                new DrinksMachineBlock(settings(DrinksMachineBlock.BOTTOM_LUMINANCE), false,
                        "tooltip.mtrlte.drinks_machine_bottom"));
        bottomItem = Registry.register(Registries.ITEM, BOTTOM_ID, new BlockItem(bottom, new Item.Settings()));

        // 一个方块实体类型对两块方块（各自位置的实例，预设存在下段那个身上）。
        blockEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, BE_ID,
                FabricBlockEntityTypeBuilder.create(DrinksMachineBlockEntity::new, top, bottom).build());

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        entries.add(bottomItem);
                        entries.add(topItem);
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] drinks machine: could not add the creative tab entries", t);
        }

        LteLog.LOGGER.info("[lte] drinks machine: {} / {} / {} (luminance {}/{}, price ${})",
                TOP_ID, BOTTOM_ID, BE_ID, DrinksMachineBlock.TOP_LUMINANCE,
                DrinksMachineBlock.BOTTOM_LUMINANCE, DrinksMachineBlock.PRICE);
    }

    /**
     * 两块各一份设置（不能共用一个实例，亮度不一样）。
     *
     * @param luminance 方块光照等级（{@link DrinksMachineBlock#TOP_LUMINANCE} /
     *                  {@link DrinksMachineBlock#BOTTOM_LUMINANCE}）
     */
    private static AbstractBlock.Settings settings(final int luminance) {
        return AbstractBlock.Settings.create()
                .mapColor(MapColor.RED)
                .requiresTool()
                .strength(2.0F)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque()
                // 方块光照（不是渲染发光）：整块恒定等级，与 blockstate / BER 无关。
                .luminance(state -> luminance);
    }
}
