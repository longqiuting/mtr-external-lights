// 方速屏蔽门的注册点：14 个方块 + 14 个物品 + 1 个方块实体类型 + 创造页签条目 + OBJ 网格缓存。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawObjParser;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 方速（fangsu）屏蔽门 —— <b>5 款旋转端门 + 2 款厦门滑动门，各拆左 / 右</b>，共 14 个方块。
 *
 * <h2>16 个方块 = 14 个门扇 + 2 块玻璃板</h2>
 * <table border="1">
 *   <caption>方块 id 与来源</caption>
 *   <tr><th>id</th><th>来源（{@code assets/fangsu/}）</th><th>类型</th></tr>
 *   <tr><td>{@code fangsu_psd_brtduanmen_left/_right}</td><td>{@code duanmen/brtduanmen.json}</td><td>端门（旋转）</td></tr>
 *   <tr><td>{@code fangsu_psd_mtrduanmen_left/_right}</td><td>{@code duanmen/mtrduanmen.json}</td><td>端门（旋转）</td></tr>
 *   <tr><td>{@code fangsu_psd_drlduanmen_left/_right}</td><td>{@code duanmen/drlduanmen.json}</td><td>端门（旋转）</td></tr>
 *   <tr><td>{@code fangsu_psd_shlowduanmen_left/_right}</td><td>{@code duanmen/shlowduanmen.json}</td><td>端门（旋转）</td></tr>
 *   <tr><td>{@code fangsu_psd_shhighduanmen_left/_right}</td><td>{@code duanmen/shhighduanmen.json}</td><td>端门（旋转）</td></tr>
 *   <tr><td>{@code fangsu_psd_screendoor_low_left/_right}</td><td>{@code screendoor/xiamen/low.json}</td><td>滑动门</td></tr>
 *   <tr><td>{@code fangsu_psd_screendoor_high_left/_right}</td><td>{@code screendoor/xiamen/high.json}</td><td>滑动门</td></tr>
 *   <tr><td>{@code fangsu_psd_screendoor_high_glass_left/_right}</td>
 *       <td>{@code screendoor/xiamen/high.json} 的 {@code glass.left/right}（{@code id=="glass"}）</td>
 *       <td><b>固定玻璃板</b>（静态，{@link FangsuPsdGlassBlock}）</td></tr>
 * </table>
 * <p>最后两块是后补的：滑动门当初只搬了会滑的门扇，描述里 {@code glass} 那一套（不动的玻璃板）
 * 漏掉了 —— 门扇一开，后面本该有玻璃的地方是空的。它们<b>不继承</b> MTR 的门类、
 * 用自己的方块实体类型 {@link #GLASS_ENTITY_ID}，原因见 {@link FangsuPsdGlassBlock} 的类注释。
 *
 * <h2>不依赖方速</h2>
 * <p>几何（{@code .obj}）、材质（{@code .mtl}）、贴图（{@code .png}）与描述（{@code .json}）全部
 * 复制进了本模组的 {@code assets/mtrlte/fangsu_psd/} 与 {@code assets/mtrlte/textures/fangsu_psd/}，
 * 命名空间 {@code fangsu:} 改写成 {@code mtrlte:fangsu_psd/}；方块、方块实体、渲染器都是我们自己的，
 * 只是<b>基类</b>用 MTR 的 {@code BlockPSDAPGDoorBase}（自动开关门信号的前提，见
 * {@link FangsuPsdDoorBlock} 的类注释）。
 *
 * <h2>14 个方块共用一个方块实体类型</h2>
 * <p>{@code BlockEntityType} 天生支持「一个类型 + 多个方块」，所以 {@code mtrlte:fangsu_psd_door}
 * 一个类型覆盖全部 14 个方块，渲染器只注册一次，具体是哪个变体从方块实例反查
 * （{@code state.getBlock() instanceof FangsuPsdDoorBlock → def()}）。
 */
public final class FangsuPsdBlocks {

    /** 命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 方块实体类型 id（14 个方块共用）。 */
    public static final Identifier ENTITY_ID = new Identifier(NAMESPACE, "fangsu_psd_door");

    /** 玻璃板的方块实体类型 id（只挂 BER，不存数据）。 */
    public static final Identifier GLASS_ENTITY_ID = new Identifier(NAMESPACE, "fangsu_psd_glass");

    /** 全部变体（顺序 = 描述文件的顺序 = 页签里的顺序）。 */
    private static final List<FangsuPsdDef> DEFS = new ArrayList<>();

    /** 玻璃板的变体（{@code glass.left/right}，静态、不可开合）；排在上面那些门扇之后。 */
    private static final List<FangsuPsdDef> GLASS_DEFS = new ArrayList<>();

    /** 方块 id → 方块。 */
    private static final Map<Identifier, FangsuPsdDoorBlock> BLOCKS = new LinkedHashMap<>();

    /** 方块 id → 玻璃板方块。 */
    private static final Map<Identifier, FangsuPsdGlassBlock> GLASS_BLOCKS = new LinkedHashMap<>();

    /** 方块 → 物品。 */
    private static final Map<FangsuPsdDoorBlock, Item> ITEMS = new LinkedHashMap<>();

    /** 玻璃板 → 物品。 */
    private static final Map<FangsuPsdGlassBlock, Item> GLASS_ITEMS = new LinkedHashMap<>();

    /** OBJ 网格缓存：变体 id → ({@code o} 组名 → 该组的若干网格（按材质分开，各自一张贴图））。 */
    private static final Map<String, Map<String, List<RawMesh>>> MESHES = new ConcurrentHashMap<>();

    private static BlockEntityType<FangsuPsdDoorBlock.FangsuPsdDoorEntity> entityType;

    /** 玻璃板的方块实体类型（只挂 BER，不存数据）。 */
    private static BlockEntityType<FangsuPsdGlassBlock.FangsuPsdGlassEntity> glassEntityType;

    private static boolean registered;

    private FangsuPsdBlocks() {
    }

    public static BlockEntityType<FangsuPsdDoorBlock.FangsuPsdDoorEntity> entityType() {
        return entityType;
    }

    /** 玻璃板的方块实体类型（客户端挂 BER 用）。 */
    public static BlockEntityType<FangsuPsdGlassBlock.FangsuPsdGlassEntity> glassEntityType() {
        return glassEntityType;
    }

    public static List<FangsuPsdDef> defs() {
        return DEFS;
    }

    /** 玻璃板变体（页签 / 日志用）。 */
    public static List<FangsuPsdDef> glassDefs() {
        return GLASS_DEFS;
    }

    public static FangsuPsdDoorBlock block(final FangsuPsdDef def) {
        return BLOCKS.get(new Identifier(NAMESPACE, def.id()));
    }

    public static FangsuPsdGlassBlock glassBlock(final FangsuPsdDef def) {
        return GLASS_BLOCKS.get(new Identifier(NAMESPACE, def.id()));
    }

    public static Item item(final FangsuPsdDef def) {
        final FangsuPsdDoorBlock block = block(def);
        return block == null ? null : ITEMS.get(block);
    }

    public static Item glassItem(final FangsuPsdDef def) {
        final FangsuPsdGlassBlock block = glassBlock(def);
        return block == null ? null : GLASS_ITEMS.get(block);
    }

    /** 方块 + 物品 + 方块实体 + 页签条目。{@code LteServer} 在 eyecandy 之后调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        DEFS.clear();
        DEFS.addAll(FangsuPsdDef.load());

        final List<FangsuPsdDoorBlock> blocks = new ArrayList<>(DEFS.size());
        for (final FangsuPsdDef def : DEFS) {
            final Identifier id = new Identifier(NAMESPACE, def.id());
            final FangsuPsdDoorBlock block = new FangsuPsdDoorBlock(def);
            Registry.register(Registries.BLOCK, id, block);
            final Item item = Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
            block.setItem(item);
            BLOCKS.put(id, block);
            ITEMS.put(block, item);
            blocks.add(block);
        }

        // 玻璃板：静态那块（不继承 BlockPSDAPGDoorBase，列车不该把它当门扇开），方块类不同、
        // 方块实体类型也不同（空的 BE，只为挂 BER）。
        GLASS_DEFS.clear();
        GLASS_DEFS.addAll(FangsuPsdDef.loadGlass());

        final List<FangsuPsdGlassBlock> glassBlocks = new ArrayList<>(GLASS_DEFS.size());
        for (final FangsuPsdDef def : GLASS_DEFS) {
            final Identifier id = new Identifier(NAMESPACE, def.id());
            final FangsuPsdGlassBlock block = new FangsuPsdGlassBlock(def);
            Registry.register(Registries.BLOCK, id, block);
            final Item item = Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
            block.setItem(item);
            GLASS_BLOCKS.put(id, block);
            GLASS_ITEMS.put(block, item);
            glassBlocks.add(block);
        }

        if (blocks.isEmpty()) {
            // 描述文件全都读不到（jar 资源被裁过）：没有方块可挂，方块实体类型 / 页签都不用建了。
            LteLog.LOGGER.warn("[lte] fangsu psd doors: no variant could be loaded, nothing registered");
            return;
        }

        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, ENTITY_ID,
                FabricBlockEntityTypeBuilder.create(FangsuPsdDoorBlock.FangsuPsdDoorEntity::new,
                        blocks.toArray(new FangsuPsdDoorBlock[0])).build());

        if (!glassBlocks.isEmpty()) {
            glassEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, GLASS_ENTITY_ID,
                    FabricBlockEntityTypeBuilder.create(FangsuPsdGlassBlock.FangsuPsdGlassEntity::new,
                            glassBlocks.toArray(new FangsuPsdGlassBlock[0])).build());
        }

        // 页签：进「厦门风格物件」（mtrlte:core，由 XiamenObjects 建），排在 eyecandy 之后 ——
        // 这一页本来就是「厦门风格」的大杂烩（屏蔽门 / 闸机 / 栏杆 / 装饰件）。
        // 顺序 = 门扇那一组 → 玻璃板（左右各一块）。
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                .register(entries -> {
                    for (final FangsuPsdDef def : DEFS) {
                        final Item item = item(def);
                        if (item != null) {
                            entries.add(item);
                        }
                    }
                    for (final FangsuPsdDef def : GLASS_DEFS) {
                        final Item item = glassItem(def);
                        if (item != null) {
                            entries.add(item);
                        }
                    }
                });

        LteLog.LOGGER.info("[lte] fangsu psd doors: {} blocks + {} glass panels registered ({} / {})",
                DEFS.size(), GLASS_DEFS.size(), ENTITY_ID, GLASS_ENTITY_ID);
    }

    /**
     * 某个变体的网格：{@code o} 组名 → 网格列表。
     *
     * <p>一个组可能跨多个材质（{@code brtduanmen} 的门扇是 dkg / ltg / blk 三层），所以每个组是
     * 一个<b>列表</b>，每份网格各带自己的贴图，渲染时同一组矩阵依次画完。
     *
     * <p>贴图名以 {@code ltg.png} 结尾的材质（灯带）把四边形标成 {@code fullBright} —— 素材里
     * OBJ 没有自发光信息，这里按文件名补上，门上的灯带才会亮。
     */
    public static Map<String, List<RawMesh>> meshes(final FangsuPsdDef def) {
        return MESHES.computeIfAbsent(def.id(), id -> {
            final Map<String, Map<Identifier, List<RawMesh.Quad>>> byGroup = new LinkedHashMap<>();
            for (final RawMesh mesh : RawObjParser.materials(def.model(), def.flipV(),
                    null, null, null, null).values()) {
                final boolean bright = mesh.texture().getPath().endsWith("ltg.png");
                for (final RawMesh.Quad quad : mesh.quads()) {
                    final RawMesh.Quad stored = bright && !quad.fullBright()
                            ? new RawMesh.Quad(quad.pos(), quad.uv(), quad.normal(), quad.shade(), true,
                                    quad.group())
                            : quad;
                    byGroup.computeIfAbsent(quad.group() == null ? "" : quad.group(),
                                    key -> new LinkedHashMap<>())
                            .computeIfAbsent(mesh.texture(), key -> new ArrayList<>())
                            .add(stored);
                }
            }
            final Map<String, List<RawMesh>> out = new LinkedHashMap<>();
            byGroup.forEach((group, byTexture) -> {
                final List<RawMesh> list = new ArrayList<>(byTexture.size());
                byTexture.forEach((texture, quads) ->
                        list.add(new RawMesh(texture, quads.toArray(new RawMesh.Quad[0]))));
                out.put(group, list);
            });
            return out;
        });
    }

    /** 方块设置：MTR 的 {@code BlockPSDAPGDoorBase()} 是无参构造（设置写在基类里），这里不再重复。 */
}
