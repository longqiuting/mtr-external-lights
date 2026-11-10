// Xiamen style objects: creative tab + the PSD blocks (which subclass MTR's own block classes).
package dev.js.mtrheadlight.xiamen;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.TabIcons;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 厦门风格物件：创造模式页签 {@code mtrlte:core}（「厦门风格物件 / Xiamen Style Objects」）以及里面的方块。
 *
 * <h2>屏蔽门 = 四个方块 id，一条 3 格高的列</h2>
 * <table border="1">
 *   <caption>每个方块都是 MTR 方块类的子类（见 {@link XiamenPsdBlocks}）</caption>
 *   <tr><th>本模组</th><th>MTR 父类</th><th>格</th></tr>
 *   <tr><td>{@code xiamen_psd_door}</td><td>{@code BlockPSDAPGDoorBase}</td>
 *       <td>y+0 下段、y+1 上段（左右两扇对开，有方块实体）</td></tr>
 *   <tr><td>{@code xiamen_psd_glass}</td><td>{@code BlockPSDAPGGlassBase}</td>
 *       <td>y+0 下段、y+1 上段</td></tr>
 *   <tr><td>{@code xiamen_psd_end}</td><td>{@code BlockPSDAPGGlassEndBase}</td>
 *       <td>y+0 下段、y+1 上段（端头收口）</td></tr>
 *   <tr><td>{@code xiamen_psd_top}</td><td>{@code BlockPSDTop}</td>
 *       <td>y+2 吊板（独立方块，<b>没有</b>物品形态，有方块实体）</td></tr>
 * </table>
 *
 * <p>继承 MTR 的方块类意味着：MTR 的列车逻辑（{@code Train#scanDoors} →
 * {@code TrainServer#openDoors}）用 {@code instanceof BlockPSDAPGBase} /
 * {@code instanceof BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase} 就能认出这些门，
 * 进站停稳后自动 {@code setOpen(...)}；吊板也自动吃到 MTR 的线路图 / 站名动态绘制。
 *
 * <p>放置走 {@link PsdAPGItem}（MTR 的 {@code ItemPSDAPGBase} 的等价物）：门 2 宽、玻璃/端部 1 宽，
 * 都是 2 高 + 1 格吊板，只消耗 1 个物品。
 */
public final class XiamenObjects {

    /** 门扇：{@code mtrlte:xiamen_psd_door}（y+0 / y+1，左右对开）。 */
    public static final Identifier PSD_DOOR_ID = new Identifier("mtrlte", "xiamen_psd_door");

    /** 玻璃板：{@code mtrlte:xiamen_psd_glass}（y+0 / y+1）。 */
    public static final Identifier PSD_GLASS_ID = new Identifier("mtrlte", "xiamen_psd_glass");

    /** 端部收口：{@code mtrlte:xiamen_psd_end}（y+0 / y+1）。 */
    public static final Identifier PSD_END_ID = new Identifier("mtrlte", "xiamen_psd_end");

    /** 吊板：{@code mtrlte:xiamen_psd_top}（y+2，无物品形态，随整列一起放 / 拆）。 */
    public static final Identifier PSD_TOP_ID = new Identifier("mtrlte", "xiamen_psd_top");

    /** 半高 APG 门扇：{@code mtrlte:xiamen_apg_door}（y+0 / y+1，无吊板）。 */
    public static final Identifier APG_DOOR_ID = new Identifier("mtrlte", "xiamen_apg_door");

    /** 半高 APG 玻璃：{@code mtrlte:xiamen_apg_glass}。 */
    public static final Identifier APG_GLASS_ID = new Identifier("mtrlte", "xiamen_apg_glass");

    /** 半高 APG 端部：{@code mtrlte:xiamen_apg_end}。 */
    public static final Identifier APG_END_ID = new Identifier("mtrlte", "xiamen_apg_end");

    /** 厦门地铁标志方块：{@code mtrlte:xiamen_logo}。 */
    public static final Identifier LOGO_ID = new Identifier("mtrlte", "xiamen_logo");

    /** 门扇的 BlockEntity 类型：{@code mtrlte:xiamen_psd_door}。 */
    public static final Identifier PSD_DOOR_BE_ID = new Identifier("mtrlte", "xiamen_psd_door");

    /** 吊板的 BlockEntity 类型：{@code mtrlte:xiamen_psd_top}。 */
    public static final Identifier PSD_TOP_BE_ID = new Identifier("mtrlte", "xiamen_psd_top");

    /** APG 门扇的 BlockEntity 类型：{@code mtrlte:xiamen_apg_door}。 */
    public static final Identifier APG_DOOR_BE_ID = new Identifier("mtrlte", "xiamen_apg_door");

    /**
     * APG 玻璃的 BlockEntity 类型：{@code mtrlte:xiamen_apg_glass}。
     *
     * <p>这个方块实体是<b>线路图的入口</b>：MTR 的 {@code RenderAPGGlass} 从它取
     * {@code getPlatformId(...)}，再拿站台 id 去 {@code ClientData.DATA_CACHE} 里生成
     * 线路图 / 站名 / 色带纹理。没有它，玻璃就是一块死的静态模型。
     */
    public static final Identifier APG_GLASS_BE_ID = new Identifier("mtrlte", "xiamen_apg_glass");

    /** 页签 id：{@code mtrlte:core} —— 名字由 {@code itemGroup.mtrlte.core} 决定。 */
    public static final Identifier CORE_TAB_ID = new Identifier("mtrlte", "core");

    private static XiamenPsdBlocks.Door psdDoor;
    private static XiamenPsdBlocks.Glass psdGlass;
    private static XiamenPsdBlocks.GlassEnd psdEnd;
    private static XiamenPsdBlocks.Top psdTop;
    private static XiamenPsdBlocks.ApgDoor apgDoor;
    private static XiamenPsdBlocks.ApgGlass apgGlass;
    private static XiamenPsdBlocks.ApgEnd apgEnd;
    private static XiamenLogoBlock logo;

    private static Item psdDoorItem;
    private static Item psdGlassItem;
    private static Item psdEndItem;
    private static Item apgDoorItem;
    private static Item apgGlassItem;
    private static Item apgEndItem;
    private static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenPSDDoor> psdDoorEntityType;
    private static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenPSDTop> psdTopEntityType;
    private static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenAPGDoor> apgDoorEntityType;
    private static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenAPGGlass> apgGlassEntityType;
    private static boolean registered;

    private XiamenObjects() {
    }

    public static XiamenPsdBlocks.Door psdDoor() {
        return psdDoor;
    }

    public static XiamenPsdBlocks.Glass psdGlass() {
        return psdGlass;
    }

    /** 端部模块（门扇靠它决定 {@code end=true}）。 */
    public static XiamenPsdBlocks.GlassEnd psdEnd() {
        return psdEnd;
    }

    /** 顶层吊板（y+2）。 */
    public static XiamenPsdBlocks.Top psdTop() {
        return psdTop;
    }

    /** 厦门地铁标志（贴墙 2px 薄牌，四向 facing，自发光 10）。 */
    public static XiamenLogoBlock logo() {
        return logo;
    }

    public static Item psdDoorItem() {
        return psdDoorItem;
    }

    public static Item psdGlassItem() {
        return psdGlassItem;
    }

    public static Item psdEndItem() {
        return psdEndItem;
    }

    public static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenPSDDoor> psdDoorEntityType() {
        return psdDoorEntityType;
    }

    public static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenPSDTop> psdTopEntityType() {
        return psdTopEntityType;
    }

    /** 半高 APG 门扇方块（{@code mtrlte:xiamen_apg_door}）。 */
    public static XiamenPsdBlocks.ApgDoor apgDoor() {
        return apgDoor;
    }

    /** 半高 APG 玻璃板（{@code mtrlte:xiamen_apg_glass}）。 */
    public static XiamenPsdBlocks.ApgGlass apgGlass() {
        return apgGlass;
    }

    /** 半高 APG 端部（{@code mtrlte:xiamen_apg_end}）。 */
    public static XiamenPsdBlocks.ApgEnd apgEnd() {
        return apgEnd;
    }

    public static Item apgDoorItem() {
        return apgDoorItem;
    }

    public static Item apgGlassItem() {
        return apgGlassItem;
    }

    public static Item apgEndItem() {
        return apgEndItem;
    }

    public static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenAPGDoor> apgDoorEntityType() {
        return apgDoorEntityType;
    }

    /** APG 玻璃的 BlockEntity 类型（线路图 BER 按它注册）。 */
    public static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenAPGGlass> apgGlassEntityType() {
        return apgGlassEntityType;
    }

    /** 方块 + 方块物品 + BlockEntity + 创造页签，{@code main} 入口调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        psdDoor = registerDoor(PSD_DOOR_ID);
        psdGlass = registerGlass(PSD_GLASS_ID);
        psdEnd = registerEnd(PSD_END_ID);
        psdTop = registerTop(PSD_TOP_ID);
        apgDoor = registerApgDoor(APG_DOOR_ID);
        apgGlass = registerApgGlass(APG_GLASS_ID);
        apgEnd = registerApgEnd(APG_END_ID);
        logo = registerLogo(LOGO_ID);

        psdDoorEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, PSD_DOOR_BE_ID,
                FabricBlockEntityTypeBuilder.<XiamenPsdBlocks.TileEntityXiamenPSDDoor>create(
                        XiamenPsdBlocks.TileEntityXiamenPSDDoor::new,
                        psdDoor).build());
        psdTopEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, PSD_TOP_BE_ID,
                FabricBlockEntityTypeBuilder.<XiamenPsdBlocks.TileEntityXiamenPSDTop>create(
                        XiamenPsdBlocks.TileEntityXiamenPSDTop::new,
                        psdTop).build());
        apgDoorEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, APG_DOOR_BE_ID,
                FabricBlockEntityTypeBuilder.<XiamenPsdBlocks.TileEntityXiamenAPGDoor>create(
                        XiamenPsdBlocks.TileEntityXiamenAPGDoor::new,
                        apgDoor).build());
        apgGlassEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, APG_GLASS_BE_ID,
                FabricBlockEntityTypeBuilder.<XiamenPsdBlocks.TileEntityXiamenAPGGlass>create(
                        XiamenPsdBlocks.TileEntityXiamenAPGGlass::new,
                        apgGlass).build());

        // 页签图标是一个只画图标的物品（见 TabIcons 的类注释），不是这一页里的门扇 / 玻璃。
        TabIcons.register();

        Registry.register(Registries.ITEM_GROUP, CORE_TAB_ID,
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.mtrlte.core"))
                        .icon(() -> new ItemStack(TabIcons.core()))
                        .build());

        // 顺序 = 页签里从左到右的顺序：{@code entries.add} 是「追加到末尾」，所以先加的就是第一个。
        // 厦门地铁标志放第一位（MTR 自己的 logo 也排在自己页签的最前面），剩下三件屏蔽门按
        // 建造顺序（门扇 → 玻璃 → 端头）跟在后面。
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, CORE_TAB_ID))
                .register(entries -> {
                    entries.add(logo);
                    entries.add(psdDoor);
                    entries.add(psdGlass);
                    entries.add(psdEnd);
                    entries.add(apgDoor);
                    entries.add(apgGlass);
                    entries.add(apgEnd);
                });

        LteLog.LOGGER.info("[lte] xiamen objects registered ({} / {} / {} / {} / {}) + apg ({} / {} / {})",
                PSD_DOOR_ID, PSD_GLASS_ID, PSD_END_ID, PSD_TOP_ID, LOGO_ID,
                APG_DOOR_ID, APG_GLASS_ID, APG_END_ID);
    }

    private static XiamenPsdBlocks.Door registerDoor(Identifier id) {
        final XiamenPsdBlocks.Door block = new XiamenPsdBlocks.Door();
        Registry.register(Registries.BLOCK, id, block);
        psdDoorItem = Registry.register(Registries.ITEM, id,
                new PsdAPGItem(block, PsdAPGItem.Kind.DOOR, new Item.Settings()));
        return block;
    }

    private static XiamenPsdBlocks.Glass registerGlass(Identifier id) {
        final XiamenPsdBlocks.Glass block = new XiamenPsdBlocks.Glass();
        Registry.register(Registries.BLOCK, id, block);
        psdGlassItem = Registry.register(Registries.ITEM, id,
                new PsdAPGItem(block, PsdAPGItem.Kind.GLASS, new Item.Settings()));
        return block;
    }

    private static XiamenPsdBlocks.GlassEnd registerEnd(Identifier id) {
        final XiamenPsdBlocks.GlassEnd block = new XiamenPsdBlocks.GlassEnd();
        Registry.register(Registries.BLOCK, id, block);
        psdEndItem = Registry.register(Registries.ITEM, id,
                new PsdAPGItem(block, PsdAPGItem.Kind.END, new Item.Settings()));
        return block;
    }

    private static XiamenPsdBlocks.ApgDoor registerApgDoor(Identifier id) {
        final XiamenPsdBlocks.ApgDoor block = new XiamenPsdBlocks.ApgDoor();
        Registry.register(Registries.BLOCK, id, block);
        apgDoorItem = Registry.register(Registries.ITEM, id,
                new PsdAPGItem(block, PsdAPGItem.Kind.APG_DOOR, new Item.Settings()));
        return block;
    }

    private static XiamenPsdBlocks.ApgGlass registerApgGlass(Identifier id) {
        final XiamenPsdBlocks.ApgGlass block = new XiamenPsdBlocks.ApgGlass();
        Registry.register(Registries.BLOCK, id, block);
        apgGlassItem = Registry.register(Registries.ITEM, id,
                new PsdAPGItem(block, PsdAPGItem.Kind.APG_GLASS, new Item.Settings()));
        return block;
    }

    private static XiamenPsdBlocks.ApgEnd registerApgEnd(Identifier id) {
        final XiamenPsdBlocks.ApgEnd block = new XiamenPsdBlocks.ApgEnd();
        Registry.register(Registries.BLOCK, id, block);
        apgEndItem = Registry.register(Registries.ITEM, id,
                new PsdAPGItem(block, PsdAPGItem.Kind.APG_END, new Item.Settings()));
        return block;
    }

    /**
     * 顶层吊板：<b>只注册方块，不注册物品</b>（MTR 的 {@code BlockPSDTop} 也不进创造页签）。
     * 它由 {@link PsdAPGItem} 跟着整列一起放下；没有掉落表（{@code dropsNothing()}），
     * 中键选取给的是玻璃物品。
     */
    private static XiamenPsdBlocks.Top registerTop(Identifier id) {
        final XiamenPsdBlocks.Top block = new XiamenPsdBlocks.Top();
        Registry.register(Registries.BLOCK, id, block);
        return block;
    }

    /**
     * 厦门地铁标志：设置项<b>逐字照抄 MTR 的 {@code Blocks.LOGO}</b>
     * （{@code create().requiresTool().hardness(2.0f).luminance(s -> 10)}）——
     * 那块是整格立方体，所以这里不能加 {@code nonOpaque()}（会漏光）。
     */
    private static XiamenLogoBlock registerLogo(Identifier id) {
        final XiamenLogoBlock block = new XiamenLogoBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .requiresTool()
                .hardness(2.0F)
                .luminance(state -> 10));
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new net.minecraft.item.BlockItem(block, new Item.Settings()));
        return block;
    }

    /** 页签在 registry 里的那一份（调试用）。 */
    public static ItemGroup tab() {
        return Registries.ITEM_GROUP.get(CORE_TAB_ID);
    }
}
