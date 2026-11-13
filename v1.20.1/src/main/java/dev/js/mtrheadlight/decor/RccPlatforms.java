// RCC 三种站台方块的注册点：独立创造页签 + 三个 BlockPlatform 子类。
package dev.js.mtrheadlight.decor;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 从 RCC（Reality City Construction，LGPL-3.0）移植的三种站台方块，<b>单独一个创造页签</b>。
 *
 * <table border="1">
 *   <caption>三个方块</caption>
 *   <tr><th>id</th><th>RCC 原 id</th><th>母模型</th><th>内凹</th></tr>
 *   <tr><td>{@code mtrlte:platform}</td><td>{@code tcwm:platform}</td>
 *       <td>{@code models/block/platform.json}</td><td>否</td></tr>
 *   <tr><td>{@code mtrlte:platform_indented}</td><td>{@code tcwm:platform_indented}</td>
 *       <td>{@code models/block/platform_indented.json}</td><td>是</td></tr>
 *   <tr><td>{@code mtrlte:platform_type_quartz}</td><td>{@code tcwm:platform_type_quartz}</td>
 *       <td>{@code models/block/platform_type_quartz.json}</td><td>否</td></tr>
 * </table>
 *
 * <h2>三种的差别只有「贴图 / 几何」</h2>
 * <p>方块类全是同一个 {@link RccPlatformBlock}，差别落在资源上：
 * <ul>
 *   <li>{@code platform}：混凝土本体（{@code block/white_terracotta} + {@code gray_wool} 栏杆），
 *       顶面叠 {@code mtrlte:block/platform_cn}（中国铁路那种白瓷砖 + 黄线站台边）；</li>
 *   <li>{@code platform_indented}：同样贴图，几何内凹（朝向侧挖掉 8 格进深），配 {@code indented=true} 碰撞；</li>
 *   <li>{@code platform_type_quartz}：母模型复用 {@code platform}，只把本体重贴图换成
 *       {@code block/quartz_block_bottom}（平滑石英）。</li>
 * </ul>
 * 每种都再挂两个子模型：{@code _psd}（顶面换成 {@code mtr:block/transparent}，让屏蔽门上沿盖住站台边）
 * 与 {@code _apg}（顶面换成 {@code mtr:block/platform_hk_2}）。<b>注意这是 RCC 原样的设定</b>：
 * {@code _psd} / {@code _apg} 同时把本体 {@code main} 换成 {@code mtrlte:block/marble}
 * —— 也就是说在站台上放屏蔽门，站台本体材质会变成大理石。这是上游的行为，移植时逐字保留。
 *
 * <h2>为什么要单独一个页签</h2>
 * <p>这三块不属于「厦门风格物件」：它们是通用站台方版（混凝土 / 石英 / 内凹），跟厦门屏蔽门、
 * 招牌、扶手不是一套东西。页签 {@code mtrlte:rcc_platform} 只放这三块，
 * 图标直接用 {@code mtrlte:platform} 的方块物品（不再单独造图标物品 —— 页签图标在 1.20.1 上
 * 必须由一个物品模型画出来，而这三种方块物品本身就是站台模型，正合适；见 {@code TabIcons} 的类注释）。
 *
 * <h2>贴图 / 模型的来源</h2>
 * <p>全部来自 RCC 1.0.3 的 {@code assets/tcwm/}，只做了两件事：{@code tcwm:} → {@code mtrlte:} 的命名空间改写，
 * 以及把用不到的子模型（{@code front} / {@code inner} 两个未被引用的贴图变量）删掉。
 * blockstate 里引用的 {@code mtr:block/platform_arrow_1..4} 是 MTR 自带的箭头模型
 * （RCC 也是这么引用的），不复制。许可与路径见仓库根目录 {@code NOTICE.md} 的第 3 条。
 *
 * <p>渲染层：{@code _psd} 顶面用的 {@code mtr:block/transparent} 与箭头贴图都有大量 alpha=0 像素，
 * 必须挂 cutout，见 {@link DecorClient}。
 */
public final class RccPlatforms {

    /** 模组命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 创造页签 id：{@code mtrlte:rcc_platform}。 */
    public static final Identifier TAB_ID = new Identifier(NAMESPACE, "rcc_platform");

    /** {@code mtrlte:platform}（RCC {@code tcwm:platform}）。 */
    public static final Identifier PLATFORM_ID = new Identifier(NAMESPACE, "platform");

    /** {@code mtrlte:platform_indented}（RCC {@code tcwm:platform_indented}）。 */
    public static final Identifier INDENTED_ID = new Identifier(NAMESPACE, "platform_indented");

    /** {@code mtrlte:platform_type_quartz}（RCC {@code tcwm:platform_type_quartz}）。 */
    public static final Identifier QUARTZ_ID = new Identifier(NAMESPACE, "platform_type_quartz");

    private static RccPlatformBlock platform;
    private static RccPlatformBlock indented;
    private static RccPlatformBlock quartz;
    private static Item platformItem;
    private static Item indentedItem;
    private static Item quartzItem;
    private static boolean registered;

    private RccPlatforms() {
    }

    public static RccPlatformBlock platform() {
        return platform;
    }

    public static RccPlatformBlock indented() {
        return indented;
    }

    public static RccPlatformBlock quartz() {
        return quartz;
    }

    /** 三块合起来，给 {@link DecorClient} 挂渲染层用（没注册就是空数组）。 */
    public static Block[] all() {
        if (platform == null || indented == null || quartz == null) {
            return new Block[0];
        }
        return new Block[]{platform, indented, quartz};
    }

    /** 方块 + 方块物品 + 独立创造页签，{@code LteServer} 入口调一次。幂等。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        platform = registerBlock(PLATFORM_ID, false, MapColor.STONE_GRAY);
        indented = registerBlock(INDENTED_ID, true, MapColor.STONE_GRAY);
        quartz = registerBlock(QUARTZ_ID, false, MapColor.OFF_WHITE);

        platformItem = registerItem(PLATFORM_ID, platform);
        indentedItem = registerItem(INDENTED_ID, indented);
        quartzItem = registerItem(QUARTZ_ID, quartz);

        Registry.register(Registries.ITEM_GROUP, TAB_ID,
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.mtrlte.rcc_platform"))
                        .icon(() -> new ItemStack(platformItem))
                        .build());

        // 页签里的顺序：平齐 → 内凹 → 石英（与素材目录的枚举顺序一致）。
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, TAB_ID))
                .register(entries -> {
                    entries.add(platformItem);
                    entries.add(indentedItem);
                    entries.add(quartzItem);
                });

        LteLog.LOGGER.info("[lte] rcc platforms: {} / {} / {} (tab {})",
                PLATFORM_ID, INDENTED_ID, QUARTZ_ID, TAB_ID);
    }

    /**
     * 方块本体。{@code settings} 与 MTR 的 {@code mtr:platform} 同源
     * （{@code create().requiresTool().strength(2.0f, 6.0f)}），另补 {@code mapColor} 与石头音效 ——
     * MTR 那边没写这两项，但本方的材质是石材，写了更贴（镐子挖掘、掉落见
     * {@code data/minecraft/tags/blocks/mineable/pickaxe.json} 与 {@code data/mtrlte/loot_tables/blocks/*.json}）。
     */
    private static RccPlatformBlock registerBlock(final Identifier id, final boolean isIndented,
                                                  final MapColor mapColor) {
        final RccPlatformBlock block = Registry.register(Registries.BLOCK, id,
                new RccPlatformBlock(AbstractBlock.Settings.create()
                        .mapColor(mapColor)
                        .requiresTool()
                        .strength(2.0F, 6.0F)
                        .sounds(BlockSoundGroup.STONE), isIndented));
        return block;
    }

    private static Item registerItem(final Identifier id, final Block block) {
        return Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
    }

    /** 页签（已注册时才有；给别处引用时用得到）。 */
    public static ItemGroup tab() {
        return Registries.ITEM_GROUP.get(TAB_ID);
    }
}
