// 贴墙标识（厦门 / 角美 / LTE）的注册：3 个方块 + 3 个物品 + 1 个方块实体类型；
// 厦门、角美进「厦门风格物件」页签的<b>末尾</b>，LTE 那面进「LTE 工具」页签的第 2 位。
package dev.js.mtrheadlight.xiamen.sign;

import java.util.ArrayList;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.signal.LteCoreItems;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.block.Material;

/**
 * 贴墙标识（{@code mtrlte:wall_sign_*}）的注册入口（两端都要跑）。
 *
 * <h2>三个 id = 三张品牌 logo × 同一套 MTR「站名出入口」实现</h2>
 * <table border="1">
 *   <caption>id / 品牌 logo（行为见 {@link WallSignBlock}，渲染见 {@link WallSignClient}）</caption>
 *   <tr><th>id（{@code mtrlte:} 前缀省略）</th><th>品牌 logo（MTR 画进站名贴图的那张，换成这个）</th></tr>
 *   <tr><td>{@code wall_sign_xiamen}</td><td>{@code block/xiamen_logo}</td></tr>
 *   <tr><td>{@code wall_sign_jiaomei}</td><td>{@code block/jiaomei_logo}</td></tr>
 *   <tr><td>{@code wall_sign_lte}</td><td>{@code block/lte_logo}</td></tr>
 * </table>
 * <p>三块方块的形状、站名、刷子行为<b>完全一样</b>（都继承 MTR 的 {@code BlockStationNameEntrance}），
 * 差别只有模型里叠的那张 logo —— 所以是同一个 {@link WallSignBlock} 类实例化三次，
 * 也共用一个方块实体类型（{@link WallSignBlockEntity} 只注册一次）。
 *
 * <h2>方块设置：逐字段照 MTR 的 {@code mtr:station_name_entrance}</h2>
 * <p>javap {@code mtr.Blocks} 取证：{@code Settings.create().requiresTool().strength(2.0F).nonOpaque()}
 * —— 没有 {@code luminance}（那块板是普通贴图，不是自发光方块），这里也一样，
 * 免得把整块牌子点成一团白光（上一版就是 {@code luminance(15)}，看起来「不正常」）。
 *
 * <h2>页签顺序</h2>
 * <ul>
 *   <li><b>厦门风格物件</b>（{@link XiamenObjects#CORE_TAB_ID}）：厦门 / 角美两面牌子加到<b>末尾</b>，
 *       由 {@code LogoObjects.pinCoreTabOrder()} 统一重排（它同时决定前排的两个 logo 和四件站外标志）；</li>
 *   <li><b>LTE 工具</b>（{@link LteCoreItems#TOOLS_TAB_ID}）：{@code wall_sign_lte} 排<b>第 2 位</b>
 *       （LTE logo 方块之后、仪表板之前），见 {@link LteCoreItems#pinToolsTabOrder()}。</li>
 * </ul>
 */
public final class WallSignObjects {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 一个标识：id + 品牌 logo 贴图名（不含 {@code block/} 与扩展名）+ 方块 + 物品。 */
    public record Entry(String id, String logo, WallSignBlock block, Item item) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static BlockEntityType<WallSignBlockEntity> entityType;

    private static boolean registered;

    private WallSignObjects() {
    }

    /** 三个方块（客户端挂 cutout 渲染层用）。 */
    public static List<WallSignBlock> blocks() {
        final List<WallSignBlock> out = new ArrayList<>(ENTRIES.size());
        for (final Entry entry : ENTRIES) {
            out.add(entry.block());
        }
        return out;
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** 三个方块共用的方块实体类型（名字沿用 {@code mtrlte:wall_sign_xiamen}）。 */
    public static BlockEntityType<WallSignBlockEntity> entityType() {
        return entityType;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 顺序 = 注册顺序：厦门 → 角美 → LTE（页签里实际的先后由两个 pin 方法决定）。
        final Entry xiamen = add("wall_sign_xiamen", "xiamen_logo");
        final Entry jiaomei = add("wall_sign_jiaomei", "jiaomei_logo");
        final Entry lte = add("wall_sign_lte", "lte_logo");

        // 方块实体类型必须在三个方块之后：Fabric 的 builder 会把方块记进去（校验方块的 BE 类型）。
        try {
            entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, new Identifier(NAMESPACE, "wall_sign_xiamen"),
                    FabricBlockEntityTypeBuilder.create(WallSignBlockEntity::new,
                            xiamen.block(), jiaomei.block(), lte.block()).build());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] wall sign: could not register the block entity type", t);
        }

        // 厦门 / 角美两面牌子进「厦门风格物件」页签。这里只保证「有位」，
        // 最终位置由 LogoObjects.pinCoreTabOrder() 把它们挪到末尾。
        try {
            ItemGroupEvents.modifyEntriesEvent(XiamenObjects.CORE_TAB_ID)
                    .register(entries -> {
                        entries.add(xiamen.item());
                        entries.add(jiaomei.item());
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] wall sign: could not add the creative tab entries", t);
        }

        // LTE 那一面进「LTE 工具」页签；排第 2 位由 LteCoreItems.pinToolsTabOrder() 负责。
        try {
            ItemGroupEvents.modifyEntriesEvent(LteCoreItems.TOOLS_TAB_ID)
                    .register(entries -> entries.add(lte.item()));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] wall sign: could not add the LTE wall sign to the tools tab", t);
        }
        LteCoreItems.pinToolsTabOrder();

        for (final Entry entry : ENTRIES) {
            LteLog.LOGGER.info("[lte] wall sign: {} (logo={})", entry.id(), entry.logo());
        }
    }

    private static Entry add(final String id, final String logo) {
        final Identifier identifier = new Identifier(NAMESPACE, id);
        // 与 MTR 的 mtr:station_name_entrance 同一套设置（javap mtr.Blocks 取证），只多一张 logo 贴图。
        final WallSignBlock block = new WallSignBlock(AbstractBlock.Settings.of(Material.STONE)
                .requiresTool()
                .strength(2.0F)
                .nonOpaque(), logo);
        Registry.register(Registries.BLOCK, identifier, block);
        final Item item = Registry.register(Registries.ITEM, identifier,
                new BlockItem(block, new Item.Settings()));
        final Entry entry = new Entry(id, logo, block, item);
        ENTRIES.add(entry);
        return entry;
    }
}
