// 贴墙标识（厦门 / 角美 / LTE）的注册：3 个方块 + 3 个物品；厦门、角美进「厦门风格物件」页签，
// LTE 那面进「LTE 工具」页签（它是本模组的牌子，不是厦门风格物件）。
package dev.js.mtrheadlight.xiamen.sign;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * 贴墙标识（{@code mtrlte:wall_sign_*}）的注册入口（两端都要跑）。
 *
 * <h2>三个方块 = 一份几何 × 三张 logo</h2>
 * <table border="1">
 *   <caption>id / 贴图（几何见 {@link WallSignBlock} 的类注释）</caption>
 *   <tr><th>id（{@code mtrlte:} 前缀省略）</th><th>贴图</th></tr>
 *   <tr><td>{@code wall_sign_xiamen}</td><td>{@code block/xiamen_logo}</td></tr>
 *   <tr><td>{@code wall_sign_jiaomei}</td><td>{@code block/jiaomei_logo}</td></tr>
 *   <tr><td>{@code wall_sign_lte}</td><td>{@code block/lte_logo}</td></tr>
 * </table>
 * <p>三张 logo 都是<b>挖空</b>的正方形贴图（logo 以外 alpha=0），方块实体模型里垫了一层
 * {@code mtrlte:block/metal} 灯箱底板，所以客户端必须挂 cutout 渲染层（见 {@code XiamenClient}），
 * 否则挖空处会被画成黑块。<b>不新增贴图</b>：三张图原样复用 logo 方块那一套。
 *
 * <h2>属性</h2>
 * <ul>
 *   <li>{@code luminance(state -> 15)}：和站外标志（{@link StationSignObjects}）一样按灯箱牌算 ——
 *       模型里那块底板就是 MTR 站名出入口的「Light」面，白底 + logo，夜里整块牌子发白光；</li>
 *   <li>{@code nonOpaque()}：模型是一块 1/16 厚的板，不这样邻居朝我们的面会被按满方块剔除；</li>
 *   <li>{@code requiresTool().hardness(2.0F)} + 进 {@code mineable/pickaxe} 标签：镐子敲；</li>
 *   <li>{@code sounds(BlockSoundGroup.METAL)}：铁牌子的声；</li>
 *   <li>没有方块实体、没有存档数据 —— 三个方块只差贴图。</li>
 * </ul>
 *
 * <p>由 {@code LteServer} 在 {@code LogoObjects.register()} 之后调用。最后的前排顺序由
 * {@code LogoObjects.pinCoreTabOrder()} 统一重排（两个 logo → 这两个贴墙标识 → 四件站外标志），
 * 所以这里不靠注册顺序定位置。<b>LTE 那一面不进厦门页</b>：
 * 它进「LTE 工具」页签（{@link dev.js.mtrheadlight.signal.LteCoreItems#TOOLS_TAB_ID}），
 * 排在那五件工具物品后面 —— 它是本模组自己的牌子，不属于厦门风格物件。
 */
public final class WallSignObjects {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 一个标识：id + logo 贴图名（不含 {@code mtrlte:block/} 前缀）。 */
    public record Entry(String id, String texture, WallSignBlock block, Item item) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static boolean registered;

    private WallSignObjects() {
    }

    /** 三个方块（客户端挂 cutout 渲染层时用）。 */
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

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 顺序 = 页签里从左到右：厦门 → 角美 → LTE。
        final Entry xiamen = add("wall_sign_xiamen", "xiamen_logo");
        final Entry jiaomei = add("wall_sign_jiaomei", "jiaomei_logo");
        final Entry lte = add("wall_sign_lte", "lte_logo");

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        entries.add(xiamen.item());
                        entries.add(jiaomei.item());
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] wall sign: could not add the creative tab entries", t);
        }

        // LTE 那一面进「LTE 工具」页签（排在工具物品后面）：它是本模组自己的牌子，
        // 不该混在「厦门风格物件」里。页签由 LteCoreItems 建，register 顺序在它之后。
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, LteCoreItems.TOOLS_TAB_ID))
                    .register(entries -> entries.add(lte.item()));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] wall sign: could not add the LTE wall sign to the tools tab", t);
        }

        for (final Entry entry : ENTRIES) {
            LteLog.LOGGER.info("[lte] wall sign: {} (texture={})", entry.id(), entry.texture());
        }
    }

    private static Entry add(final String id, final String texture) {
        final Identifier identifier = new Identifier(NAMESPACE, id);
        final WallSignBlock block = new WallSignBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .requiresTool()
                .strength(2.0F)
                .sounds(BlockSoundGroup.METAL)
                // 灯箱牌：最亮一档（与站外标志一致）。贴图挖空 → 还要 nonOpaque + cutout 渲染层。
                .luminance(state -> 15)
                .nonOpaque());
        Registry.register(Registries.BLOCK, identifier, block);
        final Item item = Registry.register(Registries.ITEM, identifier,
                new BlockItem(block, new Item.Settings()));
        final Entry entry = new Entry(id, texture, block, item);
        ENTRIES.add(entry);
        return entry;
    }
}
