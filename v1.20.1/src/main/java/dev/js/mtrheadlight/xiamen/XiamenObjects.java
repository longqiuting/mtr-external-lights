package dev.js.mtrheadlight.xiamen;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.TabIcons;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
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
 * 厦门风格物件：创造模式页签 {@code mtrlte:core}（「厦门风格物件 / Xiamen Style Objects」）
 * 以及里面的方块。
 *
 * <h2>三种方块，两套实现</h2>
 * <ul>
 *   <li><b>门扇</b> {@code xiamen_psd_door} → {@link XiamenPsdDoorBlock}：
 *       <b>可开合的双扇门</b>，方块 + BlockEntity + BlockEntityRenderer，
 *       照 MTR 的 {@code BlockPSDAPGDoorBase} / {@code RenderPSDAPGDoor} 与天津地铁的
 *       {@code BlockPSDDoorTianjin} / {@code RenderTianjinPSDDoor} 的做法重写
 *       （详见那两个类的注释，以及 {@link XiamenPsdDoorRenderer} 里的上下分段贴图）。</li>
 *   <li><b>玻璃板 / 端部</b> {@code xiamen_psd_glass} / {@code xiamen_psd_end} →
 *       {@link XiamenPsdBlock}：静止的 2px 薄板，模型直接 {@code "parent": "mtr:block/psd_glass_left_base"}，
 *       只覆盖 front/back/bottom/top 四个面贴图，几何完全继承 MTR。</li>
 * </ul>
 *
 * <p>贴图从玩家资源包 {@code 厦门地铁一号线车辆} 的
 * {@code assets/mtr/textures/block/} 拷成本模组自己的
 * {@code assets/mtrlte/textures/block/}，而不是运行时去引用 {@code mtr:} 路径 ——
 * 否则只有开着那个资源包的世界才看得到厦门样式。拷贝来源：
 * <ul>
 *   <li>{@code psd_door_top_1.png} → {@code xiamen_psd_door_top.png}（门扇上半，正面）</li>
 *   <li>{@code psd_door_top_back_1.png} → {@code xiamen_psd_door_top_back.png}</li>
 *   <li>{@code psd_door_bottom_1.png} → {@code xiamen_psd_door_bottom.png}（门扇下半，正面）</li>
 *   <li>{@code psd_door_bottom_back_1.png} → {@code xiamen_psd_door_bottom_back.png}</li>
 *   <li>{@code psd_door_top_1.png} → {@code xiamen_psd_door.png}（旧静态模型 / 物品图标，保留）</li>
 *   <li>{@code psd_glass_top_1.png} → {@code xiamen_psd_glass.png}</li>
 *   <li>{@code psd_glass_top_back_1.png} → {@code xiamen_psd_glass_back.png}</li>
 *   <li>{@code psd_door_end_top_1.png} → {@code xiamen_psd_end.png}（端部）</li>
 *   <li>{@code psd_door_end_top_back_1.png} → {@code xiamen_psd_end_back.png}（端部背面）</li>
 * </ul>
 *
 * <p>注册放在 {@code main} 入口（两端都要有方块），半透明渲染层 + 门扇渲染器在
 * {@link XiamenClient}（仅客户端）里挂。
 */
public final class XiamenObjects {

    /** 屏蔽门门扇：{@code mtrlte:xiamen_psd_door}。 */
    public static final Identifier PSD_DOOR_ID = new Identifier("mtrlte", "xiamen_psd_door");

    /** 屏蔽门玻璃板：{@code mtrlte:xiamen_psd_glass}。 */
    public static final Identifier PSD_GLASS_ID = new Identifier("mtrlte", "xiamen_psd_glass");

    /** 屏蔽门端部（end）模块：{@code mtrlte:xiamen_psd_end}。 */
    public static final Identifier PSD_END_ID = new Identifier("mtrlte", "xiamen_psd_end");

    /** 门扇的 BlockEntity 类型：{@code mtrlte:xiamen_psd_door}。 */
    public static final Identifier PSD_DOOR_BE_ID = new Identifier("mtrlte", "xiamen_psd_door");

    /** 页签 id：{@code mtrlte:core} —— 名字由 {@code itemGroup.mtrlte.core} 决定。 */
    public static final Identifier CORE_TAB_ID = new Identifier("mtrlte", "core");

    private static XiamenPsdDoorBlock psdDoor;
    private static XiamenPsdBlock psdGlass;
    private static XiamenPsdBlock psdEnd;
    private static BlockEntityType<XiamenPsdDoorEntity> psdDoorEntityType;
    private static boolean registered;

    private XiamenObjects() {
    }

    public static XiamenPsdDoorBlock psdDoor() {
        return psdDoor;
    }

    public static XiamenPsdBlock psdGlass() {
        return psdGlass;
    }

    /** 端部模块（半格长、2px 厚，几何 = MTR 的 {@code psd_glass_end_left_base}）。 */
    public static XiamenPsdBlock psdEnd() {
        return psdEnd;
    }

    public static BlockEntityType<XiamenPsdDoorEntity> psdDoorEntityType() {
        return psdDoorEntityType;
    }

    /** 方块 + 方块物品 + BlockEntity + 创造页签，{@code main} 入口调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        psdDoor = registerDoor(PSD_DOOR_ID);
        psdGlass = registerBlock(PSD_GLASS_ID, false);
        psdEnd = registerBlock(PSD_END_ID, true);
        psdDoorEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, PSD_DOOR_BE_ID,
                FabricBlockEntityTypeBuilder.create(XiamenPsdDoorEntity::new, psdDoor).build());

        // 页签图标是一个只画图标的物品（见 TabIcons 的类注释），不是这一页里的门扇 / 玻璃。
        TabIcons.register();

        Registry.register(Registries.ITEM_GROUP, CORE_TAB_ID,
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.mtrlte.core"))
                        .icon(() -> new ItemStack(TabIcons.core()))
                        .build());

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, CORE_TAB_ID))
                .register(entries -> {
                    entries.add(psdDoor);
                    entries.add(psdGlass);
                    entries.add(psdEnd);
                });

        LteLog.LOGGER.info("[lte] xiamen objects registered ({} / {} / {})",
                PSD_DOOR_ID, PSD_GLASS_ID, PSD_END_ID);
    }

    /** 门扇：带 BlockEntity 的可动方块，properties 有 facing / side / open。 */
    private static XiamenPsdDoorBlock registerDoor(Identifier id) {
        final XiamenPsdDoorBlock block = new XiamenPsdDoorBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.LIGHT_GRAY)
                .strength(0.5F, 1.0F)
                .sounds(BlockSoundGroup.GLASS)
                .nonOpaque());
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        return block;
    }

    /**
     * 一块薄板：硬度跟玻璃一个量级，声音用玻璃，{@code nonOpaque()} 让它不挡光
     * （否则整格不透光、贴图透明的地方会看起来发黑）。
     *
     * @param endPanel {@code true} 用端部那套半格长的碰撞箱
     */
    private static XiamenPsdBlock registerBlock(Identifier id, boolean endPanel) {
        final AbstractBlock.Settings settings = AbstractBlock.Settings.create()
                .mapColor(MapColor.LIGHT_GRAY)
                .strength(0.5F, 1.0F)
                .sounds(BlockSoundGroup.GLASS)
                .nonOpaque();
        final XiamenPsdBlock block = endPanel
                ? XiamenPsdBlock.endPanel(settings)
                : new XiamenPsdBlock(settings);
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        return block;
    }

    /** 页签在 registry 里的那一份（调试用）。 */
    public static ItemGroup tab() {
        return Registries.ITEM_GROUP.get(CORE_TAB_ID);
    }
}
