// 饮料售卖机的注册：两个方块 + **一个**方块物品 + 一个方块实体类型 + 创造页签条目。
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
 * <h2>两种机型：单数（原来那台）/ 双数</h2>
 * <table border="1">
 *   <caption>id 一览</caption>
 *   <tr><th>id</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code mtrlte:drinks_machine_top}</td><td><b>只有方块</b></td>
 *       <td>单数上半（货道）：微光 {@value DrinksMachineBlock#TOP_LUMINANCE}。模型
 *       {@code rawmodels/drinks_machine_top.json}（BER 画）</td></tr>
 *   <tr><td>{@code mtrlte:drinks_machine_bottom}</td><td>方块 + 单数<b>唯一</b>的方块物品</td>
 *       <td>单数下半（取货口，有 {@code open}）：微光 {@value DrinksMachineBlock#BOTTOM_LUMINANCE}。模型
 *       {@code drinks_machine_bottom_closed.json} / {@code _opened.json}</td></tr>
 *   <tr><td>{@code mtrlte:drinks_machine_double_top}</td><td><b>只有方块</b></td>
 *       <td>双数上半：和单数<b>同一套模型</b>，只是几何 / 碰撞箱整体往玩家左手侧（{@code facing}
 *       顺时针）偏 0.5 格</td></tr>
 *   <tr><td>{@code mtrlte:drinks_machine_double_bottom}</td><td>方块 + 双数唯一的方块物品</td>
 *       <td>双数下半：同一个偏移、同一份 item 贴图（{@code mtrlte:item/drinks_machine}）</td></tr>
 *   <tr><td>{@code mtrlte:drinks_machine}</td><td>方块实体类型</td>
 *       <td>四块方块<b>共用</b>一个类型（各自是不同位置的实例）</td></tr>
 * </table>
 *
 * <h2>为什么一台机器只有一个物品</h2>
 * <p>以前两个 id 各发一个 BlockItem，物品栏里就是<b>两个半台</b>（上半一个、下半一个），
 * 看着像「机器被拆成两半」。现在每台机器只发<b>一个</b>物品，挂在<b>下半</b>
 * （{@code mtrlte:drinks_machine_bottom} / {@code mtrlte:drinks_machine_double_bottom}）：
 * <ul>
 *   <li>放它 → {@link DrinksMachineBlock#onPlaced} 自动把同一机型的上半补上（放一块 = 放一台）；</li>
 *   <li>拆任一半 → 掉落的都是<b>同一个</b>物品（上半的战利品表也指向它），恒为 1 个；</li>
 *   <li>图标是<b>整台机器</b>：{@code textures/item/drinks_machine.png}（128×128，
 *       由 {@code tools/gen-drinks-machine-item.js} 把上下两份 rawmodel 拼起来正交渲染），
 *       物品模型是 {@code item/generated}；双数<b>共用同一张贴图</b>。上半<b>没有</b> item 模型 ——
 *       它根本没有物品。</li>
 * </ul>
 *
 * <p>亮度用 {@code Settings#luminance} 声明，属于<b>方块光照</b>：上下两块<b>全部</b>发微光
 * （上半 10、下半 6），与 blockstate / BER 无关。
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

    /** 双数的上半（几何整体往玩家左手侧偏 0.5 格）。 */
    public static final Identifier DOUBLE_TOP_ID = new Identifier(NAMESPACE, "drinks_machine_double_top");

    /** 双数的下半（物品挂在这一块上，贴图与单数共用）。 */
    public static final Identifier DOUBLE_BOTTOM_ID = new Identifier(NAMESPACE, "drinks_machine_double_bottom");

    /** 方块实体类型（两块共用；双数那两块也挂在这个类型上）。 */
    public static final Identifier BE_ID = new Identifier(NAMESPACE, "drinks_machine");

    private static DrinksMachineBlock top;
    private static DrinksMachineBlock bottom;
    private static DrinksMachineBlock doubleTop;
    private static DrinksMachineBlock doubleBottom;
    private static Item item;
    private static Item doubleItem;
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

    /** 双数的上半（{@code drinks_machine_double_top}）。 */
    public static DrinksMachineBlock doubleTop() {
        return doubleTop;
    }

    /** 双数的下半（{@code drinks_machine_double_bottom}）。 */
    public static DrinksMachineBlock doubleBottom() {
        return doubleBottom;
    }

    /** 某个机型的上半。 */
    public static DrinksMachineBlock top(final DrinksMachineBlock.Variant variant) {
        return variant == DrinksMachineBlock.Variant.DOUBLE ? doubleTop : top;
    }

    /** 某个机型的下半。 */
    public static DrinksMachineBlock bottom(final DrinksMachineBlock.Variant variant) {
        return variant == DrinksMachineBlock.Variant.DOUBLE ? doubleBottom : bottom;
    }

    /**
     * 单数整台机器<b>唯一</b>的方块物品（挂在它的下半那个 id 上）。{@code register()} 之后非 null。
     */
    public static Item item() {
        return item;
    }

    /** 双数整台机器唯一的方块物品（挂在下半 {@code drinks_machine_double_bottom} 上）。 */
    public static Item doubleItem() {
        return doubleItem;
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
                        "tooltip.mtrlte.drinks_machine_top", DrinksMachineBlock.Variant.SINGLE));
        // 上半**不发物品**：整台机器只有一个物品（下半那个），放下它 onPlaced 自动补上半。

        bottom = Registry.register(Registries.BLOCK, BOTTOM_ID,
                new DrinksMachineBlock(settings(DrinksMachineBlock.BOTTOM_LUMINANCE), false,
                        "tooltip.mtrlte.drinks_machine_bottom", DrinksMachineBlock.Variant.SINGLE));
        item = Registry.register(Registries.ITEM, BOTTOM_ID, new BlockItem(bottom, new Item.Settings()));

        // 双数：同一套模型 / 贴图 / 交互，几何与碰撞箱整体往玩家左手侧（facing 顺时针）偏 0.5 格。
        doubleTop = Registry.register(Registries.BLOCK, DOUBLE_TOP_ID,
                new DrinksMachineBlock(settings(DrinksMachineBlock.TOP_LUMINANCE), true,
                        "tooltip.mtrlte.drinks_machine_top", DrinksMachineBlock.Variant.DOUBLE));
        doubleBottom = Registry.register(Registries.BLOCK, DOUBLE_BOTTOM_ID,
                new DrinksMachineBlock(settings(DrinksMachineBlock.BOTTOM_LUMINANCE), false,
                        "tooltip.mtrlte.drinks_machine_bottom", DrinksMachineBlock.Variant.DOUBLE));
        doubleItem = Registry.register(Registries.ITEM, DOUBLE_BOTTOM_ID,
                new BlockItem(doubleBottom, new Item.Settings()));

        // 一个方块实体类型对四块方块（两台机器上下各两块；预设存在下段那个身上）。
        blockEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, BE_ID,
                FabricBlockEntityTypeBuilder.create(DrinksMachineBlockEntity::new,
                        top, bottom, doubleTop, doubleBottom).build());

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        entries.add(item);
                        entries.add(doubleItem);
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] drinks machine: could not add the creative tab entries", t);
        }

        LteLog.LOGGER.info("[lte] drinks machine: single {} / {} (item {}), double {} / {} (item {}), "
                        + "luminance {}/{}, price ${}",
                TOP_ID, BOTTOM_ID, BOTTOM_ID, DOUBLE_TOP_ID, DOUBLE_BOTTOM_ID, DOUBLE_BOTTOM_ID,
                DrinksMachineBlock.TOP_LUMINANCE, DrinksMachineBlock.BOTTOM_LUMINANCE,
                DrinksMachineBlock.PRICE);
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
