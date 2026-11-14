// 贴墙标识（厦门 / 角美 / LTE）的注册：3 个方块 + 3 个物品 + 1 个方块实体类型；
// 厦门、角美进「厦门风格物件」页签，LTE 那面进「LTE 工具」页签（它是本模组的牌子，不是厦门风格物件）。
package dev.js.mtrheadlight.xiamen.sign;

import java.util.ArrayList;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.signal.LteCoreItems;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * 贴墙标识（{@code mtrlte:wall_sign_*}）的注册入口（两端都要跑）。
 *
 * <h2>三个 id = 三张品牌 logo × 同一套「站名出入口」行为</h2>
 * <table border="1">
 *   <caption>id / 品牌 logo（几何与行为见 {@link WallSignBlock}、渲染见 {@link WallSignRenderer}）</caption>
 *   <tr><th>id（{@code mtrlte:} 前缀省略）</th><th>品牌 logo（画在牌子左下角）</th></tr>
 *   <tr><td>{@code wall_sign_xiamen}</td><td>{@code block/xiamen_logo}</td></tr>
 *   <tr><td>{@code wall_sign_jiaomei}</td><td>{@code block/jiaomei_logo}</td></tr>
 *   <tr><td>{@code wall_sign_lte}</td><td>{@code block/lte_logo}</td></tr>
 * </table>
 * <p>三块方块的形状、站名、连接、刷子行为<b>完全一样</b>（都是 MTR 4.0.5 的
 * {@code station_name_entrance_tall} 板，站名按位置现取），差别只有那张 logo ——
 * 所以是同一个 {@link WallSignBlock} 类实例化三次，也只共用一个方块实体类型
 * （{@link WallSignRenderer} 只注册一次）。
 *
 * <h2>属性</h2>
 * <ul>
 *   <li>{@code luminance(state -> 15)}：站名出入口那块板本来就是自发光面，整块牌子发白光；</li>
 *   <li>{@code nonOpaque()}：零厚度的板，不这样邻居朝我们的面会被按满方块剔除；</li>
 *   <li>{@code requiresTool().hardness(2.0F)} + 进 {@code mineable/pickaxe} 标签：镐子敲；</li>
 *   <li>{@code sounds(BlockSoundGroup.METAL)}：铁牌子的声。</li>
 * </ul>
 *
 * <p>由 {@code LteServer} 在 {@code LogoObjects.register()} 之后调用。厦门 / 角美两件的前排顺序由
 * {@code LogoObjects.pinCoreTabOrder()} 统一重排（两个 logo → 这两个贴墙标识 → 四件站外标志）。
 * <b>LTE 那一面不进厦门页</b>：它进「LTE 工具」页签（{@link LteCoreItems#TOOLS_TAB_ID}），
 * 并且<b>排在页签最后</b>（LTE logo → 五件工具物品 → 贴墙标识），见 {@link #pinToolsTabOrder()}。
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

    /** 三个方块（客户端挂 cutout 渲染层 / 注册渲染器时用）。 */
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

    /** 三个方块共用的方块实体类型（{@code mtrlte:wall_sign_xiamen} 这个名字，见 {@link #register()}）。 */
    public static BlockEntityType<WallSignBlockEntity> entityType() {
        return entityType;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 顺序 = 页签里从左到右：厦门 → 角美 → LTE。
        final Entry xiamen = add("wall_sign_xiamen", "xiamen_logo");
        final Entry jiaomei = add("wall_sign_jiaomei", "jiaomei_logo");
        final Entry lte = add("wall_sign_lte", "lte_logo");

        // 方块实体类型必须在三个方块之后：Fabric 的 builder 会把方块记进去（校验方块的 BE 类型）。
        // 一个类型服务三个方块 —— 它们的行为一模一样，渲染器也只认方块状态，不需要分家。
        try {
            entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, new Identifier(NAMESPACE, "wall_sign_xiamen"),
                    FabricBlockEntityTypeBuilder.create(WallSignBlockEntity::new,
                            xiamen.block(), jiaomei.block(), lte.block()).build());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] wall sign: could not register the block entity type", t);
        }

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        entries.add(xiamen.item());
                        entries.add(jiaomei.item());
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] wall sign: could not add the creative tab entries", t);
        }

        // LTE 那一面进「LTE 工具」页签：它是本模组自己的牌子，不该混在「厦门风格物件」里。
        // 页签由 LteCoreItems 建，register 顺序在它之后。
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, LteCoreItems.TOOLS_TAB_ID))
                    .register(entries -> entries.add(lte.item()));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] wall sign: could not add the LTE wall sign to the tools tab", t);
        }
        pinToolsTabOrder();

        for (final Entry entry : ENTRIES) {
            LteLog.LOGGER.info("[lte] wall sign: {} (logo={})", entry.id(), entry.logo());
        }
    }

    /**
     * 把 {@code wall_sign_lte} 摆到「LTE 工具」页签的<b>最后</b>：LTE logo 方块 → 五件工具物品 → 贴墙标识。
     *
     * <p>为什么不靠「谁后注册谁在后」：页签条目的回调按注册顺序跑，只要哪天有人在
     * {@link #register()} 之后又往工具页加东西（或者 LteCoreItems 里调了顺序），
     * 贴墙标识就会飘到中间去。这里跟 {@code LogoObjects.pinCoreTabOrder()} 一个做法 ——
     * 拿 {@code entries.getDisplayStacks()}（页签正在拼的可变列表）→ 把这一件摘出来 →
     * 追加到末尾，其余条目一个都不动。
     */
    public static void pinToolsTabOrder() {
        final Entry lte = entry("wall_sign_lte");
        if (lte == null) {
            return;
        }
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, LteCoreItems.TOOLS_TAB_ID))
                    .register(entries -> {
                        final List<ItemStack> stacks = entries.getDisplayStacks();
                        ItemStack self = null;
                        for (final ItemStack stack : stacks) {
                            if (stack.isOf(lte.item())) {
                                self = stack;
                                break;
                            }
                        }
                        if (self == null) {
                            // 没进过列表（正常情况下不会）：照原样补一件到最后。
                            self = new ItemStack(lte.item());
                        }
                        stacks.removeIf(stack -> stack.isOf(lte.item()));
                        stacks.add(self);
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] wall sign: could not pin the LTE wall sign to the end of the tools tab", t);
        }
    }

    private static Entry entry(final String id) {
        for (final Entry entry : ENTRIES) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private static Entry add(final String id, final String logo) {
        final Identifier identifier = new Identifier(NAMESPACE, id);
        final WallSignBlock block = new WallSignBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .requiresTool()
                .strength(2.0F)
                .sounds(BlockSoundGroup.METAL)
                // 灯箱牌：最亮一档（与站外标志一致）。板是零厚度的，必须 nonOpaque + cutout 渲染层。
                .luminance(state -> 15)
                .nonOpaque(), new Identifier(NAMESPACE, "textures/block/" + logo + ".png"));
        Registry.register(Registries.BLOCK, identifier, block);
        final Item item = Registry.register(Registries.ITEM, identifier,
                new BlockItem(block, new Item.Settings()));
        final Entry entry = new Entry(id, logo, block, item);
        ENTRIES.add(entry);
        return entry;
    }
}
