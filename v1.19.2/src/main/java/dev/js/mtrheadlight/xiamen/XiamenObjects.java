// Xiamen style objects: creative tab + the PSD blocks of all three Xiamen lines (which subclass MTR's own block classes).
package dev.js.mtrheadlight.xiamen;

import java.util.EnumMap;
import java.util.Map;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.TabIcons;
// 1.19.2：没有 fabric-item-group-api-v1，页签走 FabricItemGroupBuilder（见类内注释）。
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.Material;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 厦门风格物件：创造模式页签 {@code mtrlte:core}（「厦门风格物件 / Xiamen Style Objects」）以及里面的方块。
 *
 * <h2>三套屏蔽门 = 三条线路 × 七个方块</h2>
 * <p>1 / 2 / 3 号线的屏蔽门<b>几何、属性、方块实体、放置逻辑完全一样</b>，唯一的差别是
 * 「门扇上段那张线路色贴图」。所以这里<b>每个方块类只写一份</b>
 * （{@link XiamenPsdBlocks.Door} 等），按 {@link XiamenLine} 分别实例化、分别注册，
 * 线路信息挂在方块实例上（{@link XiamenPsdBlocks.LineAware}），渲染器渲染时再按方块反查。
 *
 * <table border="1">
 *   <caption>{@link LineSet} 里的 7 个方块（每行 ×1）</caption>
 *   <tr><th>id（L2 为例）</th><th>MTR 父类</th><th>格</th></tr>
 *   <tr><td>{@code psd_door_xiamen_l2}</td><td>{@code BlockPSDAPGDoorBase}</td>
 *       <td>y+0 下段、y+1 上段（左右两扇对开，有方块实体）</td></tr>
 *   <tr><td>{@code psd_glass_xiamen_l2}</td><td>{@code BlockPSDAPGGlassBase}</td>
 *       <td>y+0 下段、y+1 上段</td></tr>
 *   <tr><td>{@code psd_end_xiamen_l2}</td><td>{@code BlockPSDAPGGlassEndBase}</td>
 *       <td>y+0 下段、y+1 上段（端头收口）</td></tr>
 *   <tr><td>{@code psd_top_xiamen_l2}</td><td>{@code BlockPSDTop}</td>
 *       <td>y+2 吊板（独立方块，<b>没有</b>物品形态，有方块实体）</td></tr>
 *   <tr><td>{@code apg_door_xiamen_l2}</td><td>{@code BlockAPGDoor}</td><td>半高，2 格高无吊板</td></tr>
 *   <tr><td>{@code apg_glass_xiamen_l2}</td><td>{@code BlockAPGGlass}</td><td>半高玻璃（线路图 BER）</td></tr>
 *   <tr><td>{@code apg_end_xiamen_l2}</td><td>{@code BlockAPGGlassEnd}</td><td>半高端部</td></tr>
 * </table>
 *
 * <p>继承 MTR 的方块类意味着：MTR 的列车逻辑（{@code Train#scanDoors} →
 * {@code TrainServer#openDoors}）用 {@code instanceof BlockPSDAPGBase} /
 * {@code instanceof BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase} 就能认出这些门，
 * 进站停稳后自动 {@code setOpen(...)}；吊板也自动吃到 MTR 的线路图 / 站名动态绘制。
 *
 * <h2>为什么方块实体类型只有 4 个</h2>
 * <p>{@code BlockEntityType} 本来就支持「一个类型 + 多个方块」，所以三条线路的门扇共用一个
 * {@code mtrlte:psd_door_xiamen} 类型（<b>老存档里的方块实体 id 不变</b>，不需要任何数据迁移）。
 * 渲染器因此也只需要注册一次，线路靠方块实例区分。
 */
public final class XiamenObjects {

    /** 模组命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 基础 id（后面按线路拼 {@code _l2} / {@code _l3}）。 */
    private static final String PSD_DOOR = "psd_door_xiamen";
    private static final String PSD_GLASS = "psd_glass_xiamen";
    private static final String PSD_END = "psd_end_xiamen";
    private static final String PSD_TOP = "psd_top_xiamen";
    private static final String APG_DOOR = "apg_door_xiamen";
    private static final String APG_GLASS = "apg_glass_xiamen";
    private static final String APG_END = "apg_end_xiamen";

    /** 厦门地铁标志方块：{@code mtrlte:xiamen_logo}（与屏蔽门无关，只有一块）。 */
    public static final Identifier LOGO_ID = new Identifier(NAMESPACE, "xiamen_logo");

    /**
     * 方块实体的 registry id —— 1 号线时的那 4 个，<b>三条线路共用</b>。
     *
     * <p>如果把 2/3 号线拆成新的 BE 类型，不但多 8 个 id，还会让 1 号线的老存档必须迁移；
     * 而 {@code BlockEntityType} 天生就是「一个类型对多块方块」，共有没有任何副作用。
     */
    public static final Identifier PSD_DOOR_BE_ID = new Identifier(NAMESPACE, PSD_DOOR);
    public static final Identifier PSD_TOP_BE_ID = new Identifier(NAMESPACE, PSD_TOP);
    public static final Identifier APG_DOOR_BE_ID = new Identifier(NAMESPACE, APG_DOOR);
    public static final Identifier APG_GLASS_BE_ID = new Identifier(NAMESPACE, APG_GLASS);

    /** 页签 id：{@code mtrlte:core} —— 名字由 {@code itemGroup.mtrlte.core} 决定。 */
    public static final Identifier CORE_TAB_ID = new Identifier(NAMESPACE, "core");

    /**
     * 一条线路的整套屏蔽门：7 个方块、6 个物品（吊板没有物品形态）、7 个 id。
     *
     * <p>字段在 {@link #register()} 里按线路逐个填，所以是 public 而非 final —— 注册完成后就只读。
     */
    public static final class LineSet {

        public final XiamenLine line;
        public final Identifier doorId;
        public final Identifier glassId;
        public final Identifier endId;
        public final Identifier topId;
        public final Identifier apgDoorId;
        public final Identifier apgGlassId;
        public final Identifier apgEndId;

        public XiamenPsdBlocks.Door door;
        public XiamenPsdBlocks.Glass glass;
        public XiamenPsdBlocks.GlassEnd end;
        public XiamenPsdBlocks.Top top;
        public XiamenPsdBlocks.ApgDoor apgDoor;
        public XiamenPsdBlocks.ApgGlass apgGlass;
        public XiamenPsdBlocks.ApgEnd apgEnd;

        public Item doorItem;
        public Item glassItem;
        public Item endItem;
        public Item apgDoorItem;
        public Item apgGlassItem;
        public Item apgEndItem;

        private LineSet(final XiamenLine line) {
            this.line = line;
            this.doorId = new Identifier(NAMESPACE, line.id(PSD_DOOR));
            this.glassId = new Identifier(NAMESPACE, line.id(PSD_GLASS));
            this.endId = new Identifier(NAMESPACE, line.id(PSD_END));
            this.topId = new Identifier(NAMESPACE, line.id(PSD_TOP));
            this.apgDoorId = new Identifier(NAMESPACE, line.id(APG_DOOR));
            this.apgGlassId = new Identifier(NAMESPACE, line.id(APG_GLASS));
            this.apgEndId = new Identifier(NAMESPACE, line.id(APG_END));
        }

        /** 7 个方块（注册顺序 = 建造顺序：门扇 → 玻璃 → 端部 → 吊板 → 半高三件）。 */
        public Block[] blocks() {
            return new Block[]{
                    this.door, this.glass, this.end, this.top,
                    this.apgDoor, this.apgGlass, this.apgEnd
            };
        }
    }

    private static final Map<XiamenLine, LineSet> SETS = new EnumMap<>(XiamenLine.class);

    static {
        for (final XiamenLine line : XiamenLine.values()) {
            SETS.put(line, new LineSet(line));
        }
    }

    private static XiamenLogoBlock logo;

    private static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenPSDDoor> psdDoorEntityType;
    private static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenPSDTop> psdTopEntityType;
    private static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenAPGDoor> apgDoorEntityType;
    private static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenAPGGlass> apgGlassEntityType;
    /** 1.19.4 的页签不是 registry 条目，自己留一份引用（1.20.1 用 Registry.ITEM_GROUP.get()）。 */
    private static ItemGroup coreTab;
    private static boolean registered;

    private XiamenObjects() {
    }

    /** 某条线路的那一套（{@code null} 当 1 号线处理，方便老调用点）。 */
    public static LineSet set(final XiamenLine line) {
        return SETS.get(line == null ? XiamenLine.L1 : line);
    }

    /** 方块属于哪条线路（不是本模组的方块 → 1 号线，即「不带后缀」）。 */
    public static XiamenLine lineOf(final Block block) {
        return block instanceof XiamenPsdBlocks.LineAware aware ? aware.line() : XiamenLine.L1;
    }

    /** 方块 → 贴图变体后缀（{@code null} = 不用后缀），渲染器每帧调一次。 */
    public static String textureVariantOf(final Block block) {
        return lineOf(block).textureVariant();
    }

    // ------------------------------------------------------------------
    // 1 号线兼容入口（老的调用点仍然按「不带后缀」这一套走）
    // ------------------------------------------------------------------

    public static XiamenPsdBlocks.Door psdDoor() {
        return set(XiamenLine.L1).door;
    }

    public static XiamenPsdBlocks.Glass psdGlass() {
        return set(XiamenLine.L1).glass;
    }

    /** 端部模块（门扇靠它决定 {@code end=true}）。 */
    public static XiamenPsdBlocks.GlassEnd psdEnd() {
        return set(XiamenLine.L1).end;
    }

    /** 顶层吊板（y+2）。 */
    public static XiamenPsdBlocks.Top psdTop() {
        return set(XiamenLine.L1).top;
    }

    /** 半高 APG 门扇方块（{@code mtrlte:apg_door_xiamen}）。 */
    public static XiamenPsdBlocks.ApgDoor apgDoor() {
        return set(XiamenLine.L1).apgDoor;
    }

    /** 半高 APG 玻璃板（{@code mtrlte:apg_glass_xiamen}）。 */
    public static XiamenPsdBlocks.ApgGlass apgGlass() {
        return set(XiamenLine.L1).apgGlass;
    }

    /** 半高 APG 端部（{@code mtrlte:apg_end_xiamen}）。 */
    public static XiamenPsdBlocks.ApgEnd apgEnd() {
        return set(XiamenLine.L1).apgEnd;
    }

    public static Item psdDoorItem() {
        return set(XiamenLine.L1).doorItem;
    }

    public static Item psdGlassItem() {
        return set(XiamenLine.L1).glassItem;
    }

    public static Item psdEndItem() {
        return set(XiamenLine.L1).endItem;
    }

    public static Item apgDoorItem() {
        return set(XiamenLine.L1).apgDoorItem;
    }

    public static Item apgGlassItem() {
        return set(XiamenLine.L1).apgGlassItem;
    }

    public static Item apgEndItem() {
        return set(XiamenLine.L1).apgEndItem;
    }

    /** 厦门地铁标志（贴墙 2px 薄牌，四向 facing，自发光 10）。 */
    public static XiamenLogoBlock logo() {
        return logo;
    }

    public static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenPSDDoor> psdDoorEntityType() {
        return psdDoorEntityType;
    }

    public static BlockEntityType<XiamenPsdBlocks.TileEntityXiamenPSDTop> psdTopEntityType() {
        return psdTopEntityType;
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

        for (final XiamenLine line : XiamenLine.values()) {
            register(line);
        }
        logo = registerLogo(LOGO_ID);

        // 一个 BE 类型对三条线路的同种方块（老存档的 BE id 不变）。
        psdDoorEntityType = Registry.register(Registry.BLOCK_ENTITY_TYPE, PSD_DOOR_BE_ID,
                FabricBlockEntityTypeBuilder.create(XiamenPsdBlocks.TileEntityXiamenPSDDoor::new,
                        blocksOf(s -> s.door)).build());
        psdTopEntityType = Registry.register(Registry.BLOCK_ENTITY_TYPE, PSD_TOP_BE_ID,
                FabricBlockEntityTypeBuilder.create(XiamenPsdBlocks.TileEntityXiamenPSDTop::new,
                        blocksOf(s -> s.top)).build());
        apgDoorEntityType = Registry.register(Registry.BLOCK_ENTITY_TYPE, APG_DOOR_BE_ID,
                FabricBlockEntityTypeBuilder.create(XiamenPsdBlocks.TileEntityXiamenAPGDoor::new,
                        blocksOf(s -> s.apgDoor)).build());
        apgGlassEntityType = Registry.register(Registry.BLOCK_ENTITY_TYPE, APG_GLASS_BE_ID,
                FabricBlockEntityTypeBuilder.create(XiamenPsdBlocks.TileEntityXiamenAPGGlass::new,
                        blocksOf(s -> s.apgGlass)).build());

        // 页签图标是一个只画图标的物品（见 TabIcons 的类注释），不是这一页里的门扇 / 玻璃。
        TabIcons.register();

        // 1.19.2 的页签：显示名取自「itemGroup.mtrlte.core」这个翻译键（builder 用 id 生成），
        // 追加物品用 appendItems(List<ItemStack>)；顺序 = 页签里从左到右的顺序：
        // 先标志，再 1/2/3 号线各一组（门扇 → 玻璃 → 端头），每组后面跟这一条线路的半高 APG。
        coreTab = FabricItemGroupBuilder.create(CORE_TAB_ID)
                .icon(() -> new ItemStack(TabIcons.core()))
                .appendItems(entries -> {
                    entries.add(new ItemStack(logo));
                    for (final XiamenLine line : XiamenLine.values()) {
                        final LineSet set = set(line);
                        entries.add(new ItemStack(set.door));
                        entries.add(new ItemStack(set.glass));
                        entries.add(new ItemStack(set.end));
                        entries.add(new ItemStack(set.apgDoor));
                        entries.add(new ItemStack(set.apgGlass));
                        entries.add(new ItemStack(set.apgEnd));
                    }
                })
                .build();

        for (final XiamenLine line : XiamenLine.values()) {
            final LineSet set = set(line);
            LteLog.LOGGER.info("[lte] xiamen {} psd: {} / {} / {} / {} | apg: {} / {} / {}",
                    line, set.doorId, set.glassId, set.endId, set.topId,
                    set.apgDoorId, set.apgGlassId, set.apgEndId);
        }
    }

    /** 三条线路的同种方块（给 BE 类型用）。 */
    private static Block[] blocksOf(final java.util.function.Function<LineSet, Block> pick) {
        final XiamenLine[] lines = XiamenLine.values();
        final Block[] blocks = new Block[lines.length];
        for (int i = 0; i < lines.length; i++) {
            blocks[i] = pick.apply(set(lines[i]));
        }
        return blocks;
    }

    /** 一条线路的 7 个方块 + 6 个物品。 */
    private static void register(final XiamenLine line) {
        final LineSet set = set(line);

        set.door = new XiamenPsdBlocks.Door(line);
        Registry.register(Registry.BLOCK, set.doorId, set.door);
        set.doorItem = Registry.register(Registry.ITEM, set.doorId,
                new PsdAPGItem(set.door, PsdAPGItem.Kind.DOOR, new Item.Settings()));

        set.glass = new XiamenPsdBlocks.Glass(line);
        Registry.register(Registry.BLOCK, set.glassId, set.glass);
        set.glassItem = Registry.register(Registry.ITEM, set.glassId,
                new PsdAPGItem(set.glass, PsdAPGItem.Kind.GLASS, new Item.Settings()));

        set.end = new XiamenPsdBlocks.GlassEnd(line);
        Registry.register(Registry.BLOCK, set.endId, set.end);
        set.endItem = Registry.register(Registry.ITEM, set.endId,
                new PsdAPGItem(set.end, PsdAPGItem.Kind.END, new Item.Settings()));

        // 吊板：只注册方块（MTR 的 BlockPSDTop 也不进创造页签）。它由 PsdAPGItem 跟着整列一起放下，
        // 没有掉落表，中键选取给的是玻璃物品。
        set.top = new XiamenPsdBlocks.Top(line);
        Registry.register(Registry.BLOCK, set.topId, set.top);

        set.apgDoor = new XiamenPsdBlocks.ApgDoor(line);
        Registry.register(Registry.BLOCK, set.apgDoorId, set.apgDoor);
        set.apgDoorItem = Registry.register(Registry.ITEM, set.apgDoorId,
                new PsdAPGItem(set.apgDoor, PsdAPGItem.Kind.APG_DOOR, new Item.Settings()));

        set.apgGlass = new XiamenPsdBlocks.ApgGlass(line);
        Registry.register(Registry.BLOCK, set.apgGlassId, set.apgGlass);
        set.apgGlassItem = Registry.register(Registry.ITEM, set.apgGlassId,
                new PsdAPGItem(set.apgGlass, PsdAPGItem.Kind.APG_GLASS, new Item.Settings()));

        set.apgEnd = new XiamenPsdBlocks.ApgEnd(line);
        Registry.register(Registry.BLOCK, set.apgEndId, set.apgEnd);
        set.apgEndItem = Registry.register(Registry.ITEM, set.apgEndId,
                new PsdAPGItem(set.apgEnd, PsdAPGItem.Kind.APG_END, new Item.Settings()));
    }

    /**
     * 厦门地铁标志：设置项<b>逐字照抄 MTR 的 {@code Blocks.LOGO}</b>
     * （{@code create().requiresTool().hardness(2.0f).luminance(s -> 10)}）——
     * 那块是整格立方体，所以这里不能加 {@code nonOpaque()}（会漏光）。
     */
    private static XiamenLogoBlock registerLogo(Identifier id) {
        // 1.19.4: AbstractBlock.Settings.create() 不存在（1.20 才加），用 Settings.of(Material)。
        // 后面 mapColor/hardness/luminance 全部显式给了，材质只影响未被覆盖的默认值。
        final XiamenLogoBlock block = new XiamenLogoBlock(AbstractBlock.Settings.of(Material.STONE)
                .mapColor(MapColor.IRON_GRAY)
                .requiresTool()
                .hardness(2.0F)
                .luminance(state -> 10));
        Registry.register(Registry.BLOCK, id, block);
        Registry.register(Registry.ITEM, id, new net.minecraft.item.BlockItem(block, new Item.Settings()));
        return block;
    }

    /** 页签在 registry 里的那一份（调试用）。 */
    public static ItemGroup tab() {
        return coreTab;
    }
}
