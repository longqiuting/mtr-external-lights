// 装饰方块（大理石地板 / 大理石站台）的注册点。
package dev.js.mtrheadlight.decor;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
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
 * 装饰方块：{@code mtrlte:marble_floor}（大理石地板）与 {@code mtrlte:marble_platform}（大理石站台）。
 *
 * <h2>两个方块、两种做法</h2>
 * <ul>
 *   <li><b>大理石地板</b>：就是一个整格方块 {@link Block}。没有属性、没有方块实体、没有邻居推导，
 *       模型 {@code marble_floor.json} 顶面用 {@code floor_hangzhou_floor}、四个侧面用
 *       {@code floor_hangzhou}（素材给的两张贴图各司其职）。</li>
 *   <li><b>大理石站台</b>：{@link MarblePlatformBlock} <b>继承 MTR 的 {@code mtr.block.BlockPlatform}</b>，
 *       于是四向朝向、{@code door_type}、{@code side}（站台箭头）以及「列车认出这是站台」全部由 MTR 提供。
 *       把屏蔽门放在它上面，{@code side} 被父类算成 1..4，blockstate 就叠上对应的箭头模型
 *       —— 这就是「屏蔽门放上去显示箭头」的实现方式。</li>
 * </ul>
 *
 * <h2>贴图 / 模型命名</h2>
 * <pre>
 *   textures/block/floor_hangzhou.png        站台侧面、地板侧面（素材包里这张是 1744×1744，
 *                                            真正的大理石只在左上 872×872 那一块，模型的 uv 只用 0..8）
 *   textures/block/floor_hangzhou_floor.png  地板顶面（整张 872×872 都是料）
 *   textures/block/warning.png               站台顶面上那条黄色警戒带（素材包缺这张，见下）
 *   textures/block/platform_arrow.png        箭头（照抄 MTR 的 mtr:block/platform_arrow）
 * </pre>
 * <p><b>warning.png 是补出来的</b>：素材 {@code platform.json} 里那条警戒带引用了名为 {@code warning}
 * 的贴图，但素材目录里没有这张图（MTR 自己也只在整块顶面贴图 {@code platform_hk_1} 里画黄线，
 * 没有单独一张）。模型的这张脸 uv 是 {@code [16,3,0,0]}，也就是只取贴图<b>最上面 3/16 那一条</b>，
 * 所以按那个比例画了一张 16×16 的安全黄 + 45° 黑斜纹，落在这条 3 像素高的带子上就是一串黑色短斜杠。
 *
 * <h2>进哪个创造页签</h2>
 * <p>两个物品都进 {@code mtrlte:core}（「厦门风格物件」），排在墙上扶手 / 玻璃扶手之后。页签由
 * {@link XiamenObjects#register()} 建，所以 {@code LteServer} 里本类的调用排在它后面
 * （页签不存在时 {@code modifyEntriesEvent} 会抛异常，这里也包了一层 try/catch）。
 */
public final class DecorBlocks {

    /** 模组命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 方块 / 物品共用的 id：{@code mtrlte:marble_floor}。 */
    public static final Identifier MARBLE_FLOOR_ID = new Identifier(NAMESPACE, "marble_floor");

    /** 方块 / 物品共用的 id：{@code mtrlte:marble_platform}。 */
    public static final Identifier MARBLE_PLATFORM_ID = new Identifier(NAMESPACE, "marble_platform");

    private static Block marbleFloor;
    private static Item marbleFloorItem;
    private static MarblePlatformBlock marblePlatform;
    private static Item marblePlatformItem;
    private static boolean registered;

    private DecorBlocks() {
    }

    public static Block marbleFloor() {
        return marbleFloor;
    }

    public static Item marbleFloorItem() {
        return marbleFloorItem;
    }

    public static MarblePlatformBlock marblePlatform() {
        return marblePlatform;
    }

    public static Item marblePlatformItem() {
        return marblePlatformItem;
    }

    /** 方块 + 方块物品 + 创造页签条目，{@code LteServer} 入口调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 大理石地板：整格、石头音效、要镐（data/minecraft/tags/blocks/mineable/pickaxe.json 里有它）。
        marbleFloor = Registry.register(Registries.BLOCK, MARBLE_FLOOR_ID,
                new Block(stoneSettings()));
        marbleFloorItem = Registry.register(Registries.ITEM, MARBLE_FLOOR_ID,
                new BlockItem(marbleFloor, new Item.Settings()));

        // 大理石站台：属性 / 朝向 / 门型 / 箭头全部继承 MTR 的 BlockPlatform，这里只给方块属性。
        // 设置项与 MTR 的 mtr:platform 逐字一致（create().requiresTool().hardness(2.0f)），
        // 只多一个 mapColor / sounds —— MTR 那边没写，本方的材质是灰白石材，写了更贴。
        marblePlatform = Registry.register(Registries.BLOCK, MARBLE_PLATFORM_ID,
                new MarblePlatformBlock(AbstractBlock.Settings.create()
                        .mapColor(MapColor.STONE_GRAY)
                        .requiresTool()
                        .strength(2.0F, 6.0F)
                        .sounds(BlockSoundGroup.STONE)));
        marblePlatformItem = Registry.register(Registries.ITEM, MARBLE_PLATFORM_ID,
                new BlockItem(marblePlatform, new Item.Settings()));

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP,
                            XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        entries.add(marbleFloorItem);
                        entries.add(marblePlatformItem);
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] marble blocks could not be added to the core tab", t);
        }

        LteLog.LOGGER.info("[lte] decor: {} / {}", MARBLE_FLOOR_ID, MARBLE_PLATFORM_ID);
    }

    /**
     * 石质方块的通用属性：整格、要工具、镐子能挖。
     *
     * <p>{@code requiresTool()} 意味着「没有正确工具就不掉落」——所以这两个方块必须出现在
     * {@code mineable/pickaxe} 标签里（{@code data/minecraft/tags/blocks/mineable/pickaxe.json}），
     * 掉落表 {@code data/mtrlte/loot_tables/blocks/*.json} 只写「炸不掉就掉自己」。
     */
    private static AbstractBlock.Settings stoneSettings() {
        return AbstractBlock.Settings.create()
                .mapColor(MapColor.STONE_GRAY)
                .requiresTool()
                .strength(1.5F, 6.0F)
                .sounds(BlockSoundGroup.STONE);
    }
}
