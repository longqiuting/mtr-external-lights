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
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 厦门风格物件：创造模式页签 {@code mtrlte:core}（「厦门风格物件 / Xiamen Style Objects」）
 * 以及里面的方块。
 *
 * <h2>屏蔽门 = 四个方块 id，一条 3 格高的列</h2>
 * <table border="1">
 *   <caption>和 MTR 的对应关系</caption>
 *   <tr><th>本模组</th><th>MTR</th><th>格</th></tr>
 *   <tr><td>{@code xiamen_psd_door}</td><td>{@code BlockPSDDoor} + {@code BlockPSDAPGDoorBase}</td>
 *       <td>y+0 下段、y+1 上段（2 格高，左右两扇对开）</td></tr>
 *   <tr><td>{@code xiamen_psd_glass}</td><td>{@code BlockPSDGlass} + {@code BlockPSDAPGGlassBase}</td>
 *       <td>y+0 下段、y+1 上段</td></tr>
 *   <tr><td>{@code xiamen_psd_end}</td><td>{@code BlockPSDGlassEnd} + {@code BlockPSDAPGGlassEndBase}</td>
 *       <td>y+0 下段、y+1 上段（端头收口，带 {@code EnumPSDAPGGlassEndSide}）</td></tr>
 *   <tr><td>{@code xiamen_psd_top}</td><td>{@code BlockPSDTop}</td>
 *       <td>y+2 吊板（独立方块，<b>没有</b>物品形态）</td></tr>
 * </table>
 *
 * <p>放置一律走 {@link XiamenPsdItem}：一次放满整列（门 2 宽 × 3 高 = 6 格，玻璃 / 端部 1 宽 × 3 高 = 3 格），
 * 只消耗 1 个物品；拆任意一格带走整列（见 {@link XiamenPsdBaseBlock#breakStructure}）。
 *
 * <h2>贴图来源</h2>
 * <p>从玩家资源包「厦门地铁一号线车辆」的 {@code assets/mtr/textures/block/} 拷进本模组，避免依赖资源包：
 * <ul>
 *   <li>{@code psd_door_top_1.png} → {@code xiamen_psd_door_top.png}（上段，带警示条）</li>
 *   <li>{@code psd_door_bottom_1.png} → {@code xiamen_psd_door_bottom.png}（下段）</li>
 *   <li>{@code psd_glass_top_1.png} → {@code xiamen_psd_glass.png}（玻璃上段）</li>
 *   <li>{@code psd_glass_bottom_1.png} → {@code xiamen_psd_glass_bottom.png}（玻璃下段）</li>
 *   <li>{@code psd_door_end_top_1.png} → {@code xiamen_psd_end.png}（端部 / 端部门）</li>
 *   <li>{@code psd_top.png} 是纯白占位图，吊板贴图按同一色系自己生成（{@code xiamen_psd_top.png}）</li>
 * </ul>
 *
 * <p>注册放在 {@code main} 入口（两端都要有方块），渲染层 + 门扇 BER 在 {@link XiamenClient} 里挂。
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

    /** 页签 id：{@code mtrlte:core} —— 名字由 {@code itemGroup.mtrlte.core} 决定。 */
    public static final Identifier CORE_TAB_ID = new Identifier("mtrlte", "core");

    private static XiamenPsdDoorBlock psdDoor;
    private static XiamenPsdGlassBlock psdGlass;
    private static XiamenPsdEndBlock psdEnd;
    private static XiamenPsdTopBlock psdTop;
    private static XiamenLogoBlock logo;
    private static BlockEntityType<XiamenPsdDoorEntity> psdDoorEntityType;
    private static boolean registered;

    private XiamenObjects() {
    }

    public static XiamenPsdDoorBlock psdDoor() {
        return psdDoor;
    }

    public static XiamenPsdGlassBlock psdGlass() {
        return psdGlass;
    }

    /** 端部模块（{@link XiamenPsdBaseBlock.Kind#END}，门扇靠它决定 {@code end=true}）。 */
    public static XiamenPsdEndBlock psdEnd() {
        return psdEnd;
    }

    /** 顶层吊板（y+2）。 */
    public static XiamenPsdTopBlock psdTop() {
        return psdTop;
    }

    /** 厦门地铁标志（贴墙 2px 薄牌，四向 facing，自发光 10）。 */
    public static XiamenLogoBlock logo() {
        return logo;
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
        psdGlass = registerGlass(PSD_GLASS_ID);
        psdEnd = registerEnd(PSD_END_ID);
        psdTop = registerTop(PSD_TOP_ID);
        logo = registerLogo(LOGO_ID);
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
                    entries.add(logo);
                });

        LteLog.LOGGER.info("[lte] xiamen objects registered ({} / {} / {} / {} / {})",
                PSD_DOOR_ID, PSD_GLASS_ID, PSD_END_ID, PSD_TOP_ID, LOGO_ID);
    }

    /** 屏蔽门的通用方块属性：薄板不该挡光，声音跟玻璃一个量级。 */
    private static AbstractBlock.Settings psdSettings() {
        return AbstractBlock.Settings.create()
                .mapColor(MapColor.LIGHT_GRAY)
                .strength(0.5F, 1.0F)
                .sounds(BlockSoundGroup.GLASS)
                .nonOpaque();
    }

    /** 门扇：带 BlockEntity 的可动方块，properties 有 facing / half / side / open / end。 */
    private static XiamenPsdDoorBlock registerDoor(Identifier id) {
        final XiamenPsdDoorBlock block = new XiamenPsdDoorBlock(psdSettings());
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new XiamenPsdItem(block, new Item.Settings()));
        return block;
    }

    /** 玻璃 / 端部：静止薄板，一次放一整列（1 宽 × 3 高）。 */
    private static XiamenPsdGlassBlock registerGlass(Identifier id) {
        final XiamenPsdGlassBlock block = new XiamenPsdGlassBlock(psdSettings());
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new XiamenPsdItem(block, new Item.Settings()));
        return block;
    }

    /** 端部收口：元素和玻璃一样，属性换成 {@code touching_left/right}。 */
    private static XiamenPsdEndBlock registerEnd(Identifier id) {
        final XiamenPsdEndBlock block = new XiamenPsdEndBlock(psdSettings());
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new XiamenPsdItem(block, new Item.Settings()));
        return block;
    }

    /**
     * 顶层吊板：<b>只注册方块，不注册物品</b>。
     * 它永远由 {@link XiamenPsdItem} 跟着整列一起放下（MTR 的 {@code BlockPSDTop} 也不进创造页签），
     * 所以没有 {@code BlockItem}、也没有掉落表；打掉它时 {@link XiamenPsdTopBlock#onBreak}
     * 会手动把下面那一格的物品掉出来。
     */
    private static XiamenPsdTopBlock registerTop(Identifier id) {
        final XiamenPsdTopBlock block = new XiamenPsdTopBlock(psdSettings());
        Registry.register(Registries.BLOCK, id, block);
        return block;
    }

    /**
     * 厦门地铁标志：设置项<b>照抄 MTR 的 {@code Blocks.LOGO}</b>
     * （{@code create().requiresTool().hardness(2.0f).luminance(s -> 10)}），
     * 额外加 {@code nonOpaque()}（薄板不该挡光、不该整格挡脸）和金属音。
     */
    private static XiamenLogoBlock registerLogo(Identifier id) {
        final XiamenLogoBlock block = new XiamenLogoBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .requiresTool()
                .hardness(2.0F)
                .luminance(state -> 10)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque());
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new net.minecraft.item.BlockItem(block, new Item.Settings()));
        return block;
    }

    /** 页签在 registry 里的那一份（调试用）。 */
    public static ItemGroup tab() {
        return Registries.ITEM_GROUP.get(CORE_TAB_ID);
    }
}
