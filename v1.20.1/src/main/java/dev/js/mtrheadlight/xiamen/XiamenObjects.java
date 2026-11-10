// Ported from Minecraft Transit Railway (MTR), MIT License.
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
 *   <caption>和 MTR 的对应关系（本模组已整体移植 MTR 的实现）</caption>
 *   <tr><th>本模组</th><th>MTR</th><th>格</th></tr>
 *   <tr><td>{@code xiamen_psd_door}</td><td>{@code BlockPSDDoor} + {@code BlockPSDAPGDoorBase}</td>
 *       <td>y+0 下段、y+1 上段（2 格高，左右两扇对开，有方块实体）</td></tr>
 *   <tr><td>{@code xiamen_psd_glass}</td><td>{@code BlockPSDGlass} + {@code BlockPSDAPGGlassBase}</td>
 *       <td>y+0 下段、y+1 上段</td></tr>
 *   <tr><td>{@code xiamen_psd_end}</td><td>{@code BlockPSDGlassEnd} + {@code BlockPSDAPGGlassEndBase}</td>
 *       <td>y+0 下段、y+1 上段（端头收口，带 {@code touching_left/right}）</td></tr>
 *   <tr><td>{@code xiamen_psd_top}</td><td>{@code BlockPSDTop}</td>
 *       <td>y+2 吊板（独立方块，<b>没有</b>物品形态，有方块实体）</td></tr>
 * </table>
 *
 * <p>放置走 {@link PsdAPGItem}（MTR 的 {@code ItemPSDAPGBase} 的移植）：门 2 宽、玻璃/端部 1 宽，
 * 都是 2 高 + 1 格吊板，只消耗 1 个物品。
 *
 * <h2>贴图</h2>
 * <ul>
 *   <li>门扇、吊板灯箱：厦门包里的 {@code psd_door_*} / {@code psd_top}（转存为
 *       {@code xiamen_psd_*}，见 docs/report-2026-11-10-xiamen-psd-mtr-port.md）；</li>
 *   <li>玻璃 / 端部 / 吊板包边：从 MTR 资源包原样搬来的 {@code psd_glass_*} / {@code psd_top*}，
 *       因为它们的 blockstate 用的是 MTR 那套 UV 分区，换图会错位。</li>
 * </ul>
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

    /** 厦门地铁标志方块：{@code mtrlte:xiamen_logo}。 */
    public static final Identifier LOGO_ID = new Identifier("mtrlte", "xiamen_logo");

    /** 门扇的 BlockEntity 类型：{@code mtrlte:xiamen_psd_door}。 */
    public static final Identifier PSD_DOOR_BE_ID = new Identifier("mtrlte", "xiamen_psd_door");

    /** 吊板的 BlockEntity 类型：{@code mtrlte:xiamen_psd_top}。 */
    public static final Identifier PSD_TOP_BE_ID = new Identifier("mtrlte", "xiamen_psd_top");

    /** 页签 id：{@code mtrlte:core} —— 名字由 {@code itemGroup.mtrlte.core} 决定。 */
    public static final Identifier CORE_TAB_ID = new Identifier("mtrlte", "core");

    private static XiamenPsdBlocks.Door psdDoor;
    private static XiamenPsdBlocks.Glass psdGlass;
    private static XiamenPsdBlocks.GlassEnd psdEnd;
    private static PsdTopBlock psdTop;
    private static XiamenLogoBlock logo;
    private static BlockEntityType<PsdDoorBlock.TileEntityPsdDoor> psdDoorEntityType;
    private static BlockEntityType<PsdTopBlock.TileEntityPsdTop> psdTopEntityType;
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
    public static PsdTopBlock psdTop() {
        return psdTop;
    }

    /** 厦门地铁标志（贴墙 2px 薄牌，四向 facing，自发光 10）。 */
    public static XiamenLogoBlock logo() {
        return logo;
    }

    public static BlockEntityType<PsdDoorBlock.TileEntityPsdDoor> psdDoorEntityType() {
        return psdDoorEntityType;
    }

    public static BlockEntityType<PsdTopBlock.TileEntityPsdTop> psdTopEntityType() {
        return psdTopEntityType;
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
        logo = registerLogo(LOGO_ID);

        psdDoorEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, PSD_DOOR_BE_ID,
                FabricBlockEntityTypeBuilder.<PsdDoorBlock.TileEntityPsdDoor>create(
                        (pos, state) -> new PsdDoorBlock.TileEntityPsdDoor(psdDoorEntityType(), pos, state),
                        psdDoor).build());
        psdTopEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, PSD_TOP_BE_ID,
                FabricBlockEntityTypeBuilder.<PsdTopBlock.TileEntityPsdTop>create(
                        (pos, state) -> new PsdTopBlock.TileEntityPsdTop(psdTopEntityType(), pos, state),
                        psdTop).build());

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
                    entries.add(logo);
                });

        LteLog.LOGGER.info("[lte] xiamen objects registered ({} / {} / {} / {} / {})",
                PSD_DOOR_ID, PSD_GLASS_ID, PSD_END_ID, PSD_TOP_ID, LOGO_ID);
    }

    private static XiamenPsdBlocks.Door registerDoor(Identifier id) {
        final XiamenPsdBlocks.Door block = new XiamenPsdBlocks.Door();
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new PsdAPGItem(block, PsdAPGItem.Kind.DOOR, new Item.Settings()));
        return block;
    }

    private static XiamenPsdBlocks.Glass registerGlass(Identifier id) {
        final XiamenPsdBlocks.Glass block = new XiamenPsdBlocks.Glass();
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new PsdAPGItem(block, PsdAPGItem.Kind.GLASS, new Item.Settings()));
        return block;
    }

    private static XiamenPsdBlocks.GlassEnd registerEnd(Identifier id) {
        final XiamenPsdBlocks.GlassEnd block = new XiamenPsdBlocks.GlassEnd();
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new PsdAPGItem(block, PsdAPGItem.Kind.END, new Item.Settings()));
        return block;
    }

    /**
     * 顶层吊板：<b>只注册方块，不注册物品</b>（MTR 的 {@code BlockPSDTop} 也不进创造页签）。
     * 它由 {@link PsdAPGItem} 跟着整列一起放下；没有掉落表（{@code dropsNothing()}），
     * 中键选取给的是玻璃物品。
     */
    private static PsdTopBlock registerTop(Identifier id) {
        final PsdTopBlock block = new PsdTopBlock();
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
