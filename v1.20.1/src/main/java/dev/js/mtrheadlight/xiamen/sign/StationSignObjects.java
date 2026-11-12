// 站外标志方块（厦门 / 角美 × 挂墙 / 杆上）的注册：4 个方块 + 4 个物品，进「厦门风格物件」页签。
package dev.js.mtrheadlight.xiamen.sign;

import java.util.ArrayList;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
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
 * 站外标志的注册入口（两端都要跑）。
 *
 * <h2>四个方块、两个模型、两张贴图</h2>
 * <table border="1">
 *   <caption>id / 款 / 品牌（模型与贴图见 {@link StationSignBlock} 的类注释）</caption>
 *   <tr><th>id（{@code mtrlte:} 前缀省略）</th><th>款</th><th>品牌</th></tr>
 *   <tr><td>{@code station_sign_wall_xiamen}</td><td>挂墙</td><td>厦门地铁</td></tr>
 *   <tr><td>{@code station_sign_pole_xiamen}</td><td>杆上</td><td>厦门地铁</td></tr>
 *   <tr><td>{@code station_sign_wall_jiaomei}</td><td>挂墙</td><td>角美地铁</td></tr>
 *   <tr><td>{@code station_sign_pole_jiaomei}</td><td>杆上</td><td>角美地铁</td></tr>
 * </table>
 *
 * <p>方块和物品用同一个 id（本模组的厦门物件都是这个规矩），没有方块实体、没有存档数据。
 * 由 {@code LteServer} 在 {@link XiamenObjects#register()} 之后调用，
 * 物品进「厦门风格物件」页签（页签本身是 {@link XiamenObjects} 建的）。
 *
 * <h2>属性</h2>
 * <ul>
 *   <li>{@code luminance(state -> 15)}：灯箱牌自发光，最亮一档；</li>
 *   <li>{@code nonOpaque()}：模型不是满方块（挂墙款只有一个窄牌面），不这样邻居朝我们的面会被
 *       按满方块剔除，缝隙里能看穿邻居（{@code dep_on_wall} 踩过同一个坑）；</li>
 *   <li>{@code requiresTool().hardness(2.0F)} + 进 {@code mineable/pickaxe} 标签：镐子敲；</li>
 *   <li>{@code sounds(BlockSoundGroup.METAL)}：铁牌子的声。</li>
 * </ul>
 */
public final class StationSignObjects {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 一个「品牌 × 款」的方块 + 物品。 */
    public record Entry(String id, boolean wallMounted, String texture, StationSignBlock block, Item item) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static boolean registered;

    private StationSignObjects() {
    }

    /** 四个方块（客户端挂 cutout 渲染层时用）。 */
    public static List<StationSignBlock> blocks() {
        final List<StationSignBlock> out = new ArrayList<>(ENTRIES.size());
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

        // 顺序 = 页签里从左到右：厦门挂墙 → 厦门杆上 → 角美挂墙 → 角美杆上。
        final Entry wallXiamen = add("station_sign_wall_xiamen", true, "xiamen");
        final Entry poleXiamen = add("station_sign_pole_xiamen", false, "xiamen");
        final Entry wallJiaomei = add("station_sign_wall_jiaomei", true, "jiaomei");
        final Entry poleJiaomei = add("station_sign_pole_jiaomei", false, "jiaomei");

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        entries.add(wallXiamen.item());
                        entries.add(poleXiamen.item());
                        entries.add(wallJiaomei.item());
                        entries.add(poleJiaomei.item());
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] station sign: could not add the creative tab entries", t);
        }

        for (final Entry entry : ENTRIES) {
            LteLog.LOGGER.info("[lte] station sign: {} (wall={}, texture={})",
                    entry.id(), entry.wallMounted(), entry.texture());
        }
    }

    private static Entry add(final String id, final boolean wallMounted, final String texture) {
        final Identifier identifier = new Identifier(NAMESPACE, id);
        final StationSignBlock block = new StationSignBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .requiresTool()
                .strength(2.0F)
                .sounds(BlockSoundGroup.METAL)
                // 灯箱牌：最亮一档。贴图是挖空的（alpha），所以还要 nonOpaque + cutout 渲染层。
                .luminance(state -> 15)
                .nonOpaque(), wallMounted);
        Registry.register(Registries.BLOCK, identifier, block);
        final Item item = Registry.register(Registries.ITEM, identifier,
                new BlockItem(block, new Item.Settings()));
        final Entry entry = new Entry(id, wallMounted, texture, block, item);
        ENTRIES.add(entry);
        return entry;
    }
}
