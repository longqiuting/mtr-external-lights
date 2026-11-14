// 方速屏蔽门的注册点：14 个门方块 + 1 块玻璃板 + 3 个方块实体类型 + 创造页签条目 + OBJ 网格缓存。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawObjParser;
import dev.js.mtrheadlight.server.RegistrySelfCheck;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 方速（fangsu）屏蔽门 —— <b>5 款旋转端门 + 2 款厦门滑动门，各拆左 / 右</b>，共 14 个门方块，
 * 另加 1 块「厦门高」固定玻璃板。
 *
 * <h2>15 个方块 = 14 个门 + 1 块玻璃板（= 一整格）</h2>
 * <table border="1">
 *   <caption>方块 id 与来源</caption>
 *   <tr><th>id</th><th>来源（{@code assets/fangsu/}）</th><th>类型 / 开关方式</th></tr>
 *   <tr><td>{@code fangsu_psd_brtduanmen_left/_right}</td><td>{@code duanmen/brtduanmen.json}</td>
 *       <td>端门（旋转）· <b>驾驶钥匙手动</b></td></tr>
 *   <tr><td>{@code fangsu_psd_mtrduanmen_left/_right}</td><td>{@code duanmen/mtrduanmen.json}</td>
 *       <td>端门 · 驾驶钥匙</td></tr>
 *   <tr><td>{@code fangsu_psd_drlduanmen_left/_right}</td><td>{@code duanmen/drlduanmen.json}</td>
 *       <td>端门 · 驾驶钥匙</td></tr>
 *   <tr><td>{@code fangsu_psd_shlowduanmen_left/_right}</td><td>{@code duanmen/shlowduanmen.json}</td>
 *       <td>端门 · 驾驶钥匙</td></tr>
 *   <tr><td>{@code fangsu_psd_shhighduanmen_left/_right}</td><td>{@code duanmen/shhighduanmen.json}</td>
 *       <td>端门 · 驾驶钥匙</td></tr>
 *   <tr><td>{@code fangsu_psd_screendoor_low_left/_right}</td><td>{@code screendoor/xiamen/low.json}</td>
 *       <td>滑动门 · <b>MTR 列车自动</b></td></tr>
 *   <tr><td>{@code fangsu_psd_screendoor_high_left/_right}</td><td>{@code screendoor/xiamen/high.json}</td>
 *       <td>滑动门 · MTR 列车自动</td></tr>
 *   <tr><td>{@code fangsu_psd_screendoor_high_glass}</td>
 *       <td>{@code screendoor/xiamen/high.json} 的 {@code glass.left/right}</td>
 *       <td><b>固定玻璃板，一个方块 = 一整格</b>（格内左右两个 0.5 格半片同时画；
 *       {@link FangsuPsdGlassBlock}，左右各 4 种形态、刷子切换）</td></tr>
 * </table>
 *
 * <h2>端门与滑动门是两个方块类（2026-11-13 拆分）</h2>
 * <ul>
 *   <li><b>滑动门</b> {@link FangsuPsdDoorBlock}：<b>继承</b> MTR 的 {@code BlockPSDAPGDoorBase} ——
 *       列车停稳自动开、发车自动关。这个继承是<b>唯一</b>的办法：MTR 的
 *       {@code TrainServer#openDoors} 拿它自己的类做 {@code instanceof} 找门；</li>
 *   <li><b>端门</b> {@link FangsuPsdEndDoorBlock}：普通方块 + {@code BlockEntityProvider}，
 *       <b>玩家手持 MTR 驾驶钥匙右键开关</b>，与列车无关（用户要求）。</li>
 * </ul>
 * <p>所以方块实体类型也分两个：{@code mtrlte:fangsu_psd_door}（滑动门，方块实体是 MTR 的
 * {@code TileEntityPSDAPGDoorBase} 子类）与 {@code mtrlte:fangsu_psd_end_door}（端门，自己的方块实体）。
 * 玻璃板是第三个（{@code mtrlte:fangsu_psd_glass}，只挂 BER、不存数据）。
 *
 * <h2>不依赖方速</h2>
 * <p>几何（{@code .obj}）、材质（{@code .mtl}）、贴图（{@code .png}）与描述（{@code .json}）全部
 * 复制进了本模组的 {@code assets/mtrlte/fangsu_psd/} 与 {@code assets/mtrlte/textures/fangsu_psd/}，
 * 命名空间 {@code fangsu:} 改写成 {@code mtrlte:fangsu_psd/}；方块、方块实体、渲染器都是我们自己的。
 */
public final class FangsuPsdBlocks {

    /** 命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 滑动门的方块实体类型 id（4 个滑动门方块共用）。 */
    public static final Identifier ENTITY_ID = new Identifier(NAMESPACE, "fangsu_psd_door");

    /** 端门的方块实体类型 id（10 个端门方块共用；和滑动门分开，列车不会再认它）。 */
    public static final Identifier END_DOOR_ENTITY_ID = new Identifier(NAMESPACE, "fangsu_psd_end_door");

    /** 玻璃板的方块实体类型 id（只挂 BER，不存数据）。 */
    public static final Identifier GLASS_ENTITY_ID = new Identifier(NAMESPACE, "fangsu_psd_glass");

    /** 全部门方块（顺序 = 描述文件的顺序 = 页签里的顺序）。 */
    private static final List<FangsuPsdDef> DEFS = new ArrayList<>();

    /** 玻璃板（{@code glass.left/right} 两半同属一格，各 4 种形态、不可开合）；排在门后面。 */
    private static final List<FangsuPsdGlassDef> GLASS_DEFS = new ArrayList<>();

    /** 方块 id → 方块（门，两种方块类的公共父类 {@link Block}）。 */
    private static final Map<Identifier, Block> BLOCKS = new LinkedHashMap<>();

    /** 方块 id → 方块（玻璃板）。 */
    private static final Map<Identifier, FangsuPsdGlassBlock> GLASS_BLOCKS = new LinkedHashMap<>();

    /** 方块 id → 物品（门 + 玻璃板统一放一张表）。 */
    private static final Map<Identifier, Item> ITEMS = new LinkedHashMap<>();

    /** OBJ 网格缓存：变体 id → ({@code o} 组名 → 该组的若干网格（按材质分开，各自一张贴图））。 */
    private static final Map<String, Map<String, List<RawMesh>>> MESHES = new ConcurrentHashMap<>();

    private static BlockEntityType<FangsuPsdDoorBlock.FangsuPsdDoorEntity> entityType;

    private static BlockEntityType<FangsuPsdEndDoorBlock.FangsuPsdEndDoorEntity> endDoorEntityType;

    private static BlockEntityType<FangsuPsdGlassBlock.FangsuPsdGlassEntity> glassEntityType;

    private static boolean registered;

    private FangsuPsdBlocks() {
    }

    /** 滑动门的方块实体类型。 */
    public static BlockEntityType<FangsuPsdDoorBlock.FangsuPsdDoorEntity> entityType() {
        return entityType;
    }

    /** 端门的方块实体类型。 */
    public static BlockEntityType<FangsuPsdEndDoorBlock.FangsuPsdEndDoorEntity> endDoorEntityType() {
        return endDoorEntityType;
    }

    /** 玻璃板的方块实体类型（客户端挂 BER 用）。 */
    public static BlockEntityType<FangsuPsdGlassBlock.FangsuPsdGlassEntity> glassEntityType() {
        return glassEntityType;
    }

    /** 全部门变体（页签 / 日志用）。 */
    public static List<FangsuPsdDef> defs() {
        return DEFS;
    }

    /** 玻璃板变体（页签 / 日志用）。 */
    public static List<FangsuPsdGlassDef> glassDefs() {
        return GLASS_DEFS;
    }

    /** 某个门变体的物品（注册前 / 读不到时是 {@code null}）。 */
    public static Item item(final FangsuPsdDef def) {
        return ITEMS.get(new Identifier(NAMESPACE, def.id()));
    }

    /** 某块玻璃板的物品。 */
    public static Item glassItem(final FangsuPsdGlassDef def) {
        return ITEMS.get(new Identifier(NAMESPACE, def.id()));
    }

    /** 方块 + 物品 + 方块实体 + 页签条目。{@code LteServer} 在 eyecandy 之后调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        DEFS.clear();
        DEFS.addAll(FangsuPsdDef.load());

        final List<Block> autoBlocks = new ArrayList<>(4);
        final List<Block> manualBlocks = new ArrayList<>(10);
        for (final FangsuPsdDef def : DEFS) {
            // 每个变体单独包一层：注册就在创建的同一条语句里，某个变体坏掉只跳过它自己。
            try {
                final Identifier id = new Identifier(NAMESPACE, def.id());
                final Block block;
                final Item item;
                // 端门 = 手动（驾驶钥匙）；滑动门 = 自动（MTR 列车）。两个方块类，见类注释。
                if (def.kind() == FangsuPsdDef.Kind.END_DOOR) {
                    final FangsuPsdEndDoorBlock endDoor = new FangsuPsdEndDoorBlock(def);
                    Registry.register(Registries.BLOCK, id, endDoor);
                    item = registerItem(id, endDoor);
                    endDoor.setItem(item);
                    manualBlocks.add(endDoor);
                    block = endDoor;
                } else {
                    final FangsuPsdDoorBlock autoDoor = new FangsuPsdDoorBlock(def);
                    Registry.register(Registries.BLOCK, id, autoDoor);
                    item = registerItem(id, autoDoor);
                    autoDoor.setItem(item);
                    autoBlocks.add(autoDoor);
                    block = autoDoor;
                }
                BLOCKS.put(id, block);
                ITEMS.put(id, item);
            } catch (Throwable t) {
                LteLog.LOGGER.error("[lte] fangsu psd door {} failed to register - skipping it", def.id(), t);
                RegistrySelfCheck.purgePending("fangsu psd " + def.id());
            }
        }

        GLASS_DEFS.clear();
        GLASS_DEFS.addAll(FangsuPsdGlassDef.load());
        final List<Block> glassBlocks = new ArrayList<>(GLASS_DEFS.size());
        for (final FangsuPsdGlassDef def : GLASS_DEFS) {
            try {
                final Identifier id = new Identifier(NAMESPACE, def.id());
                final FangsuPsdGlassBlock block = new FangsuPsdGlassBlock(def);
                Registry.register(Registries.BLOCK, id, block);
                final Item item = registerItem(id, block);
                block.setItem(item);
                GLASS_BLOCKS.put(id, block);
                ITEMS.put(id, item);
                glassBlocks.add(block);
            } catch (Throwable t) {
                LteLog.LOGGER.error("[lte] fangsu psd glass {} failed to register - skipping it", def.id(), t);
                RegistrySelfCheck.purgePending("fangsu psd glass " + def.id());
            }
        }

        if (autoBlocks.isEmpty() && manualBlocks.isEmpty()) {
            // 描述文件全都读不到（jar 资源被裁过）：没有方块可挂，方块实体类型 / 页签都不用建了。
            LteLog.LOGGER.warn("[lte] fangsu psd doors: no variant could be loaded, nothing registered");
            return;
        }

        if (!autoBlocks.isEmpty()) {
            entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, ENTITY_ID,
                    FabricBlockEntityTypeBuilder.create(FangsuPsdDoorBlock.FangsuPsdDoorEntity::new,
                            autoBlocks.toArray(new Block[0])).build());
        }
        if (!manualBlocks.isEmpty()) {
            endDoorEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, END_DOOR_ENTITY_ID,
                    FabricBlockEntityTypeBuilder.create(FangsuPsdEndDoorBlock.FangsuPsdEndDoorEntity::new,
                            manualBlocks.toArray(new Block[0])).build());
        }
        if (!glassBlocks.isEmpty()) {
            glassEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, GLASS_ENTITY_ID,
                    FabricBlockEntityTypeBuilder.create(FangsuPsdGlassBlock.FangsuPsdGlassEntity::new,
                            glassBlocks.toArray(new Block[0])).build());
        }

        // 页签：进「厦门风格物件」（mtrlte:core，由 XiamenObjects 建），排在 eyecandy 之后 ——
        // 这一页本来就是「厦门风格」的大杂烩（屏蔽门 / 闸机 / 栏杆 / 装饰件）。
        // 顺序 = 门扇那一组 → 玻璃板（厦门高，一个方块 = 一整格）。
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                .register(entries -> {
                    for (final FangsuPsdDef def : DEFS) {
                        final Item item = item(def);
                        if (item != null) {
                            entries.add(item);
                        }
                    }
                    for (final FangsuPsdGlassDef def : GLASS_DEFS) {
                        final Item item = glassItem(def);
                        if (item != null) {
                            entries.add(item);
                        }
                    }
                });

        LteLog.LOGGER.info("[lte] fangsu psd doors: {} auto + {} manual doors + {} glass panels registered ({} / {} / {})",
                autoBlocks.size(), manualBlocks.size(), GLASS_DEFS.size(),
                ENTITY_ID, END_DOOR_ENTITY_ID, GLASS_ENTITY_ID);
    }

    /** 注册一个 {@code BlockItem}（方块已经注册完，这里只做物品那一半）。 */
    private static Item registerItem(final Identifier id, final Block block) {
        return Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
    }

    /**
     * 某个门变体的网格：{@code o} 组名 → 网格列表。
     *
     * <p>一个组可能跨多个材质（{@code brtduanmen} 的门扇是 dkg / ltg / blk 三层），所以每个组是
     * 一个<b>列表</b>，每份网格各带自己的贴图，渲染时同一组矩阵依次画完。
     *
     * <p>贴图名以 {@code ltg.png} 结尾的材质（灯带）把四边形标成 {@code fullBright} —— 素材里
     * OBJ 没有自发光信息，这里按文件名补上，门上的灯带才会亮。
     */
    public static Map<String, List<RawMesh>> meshes(final FangsuPsdDef def) {
        return meshes(def.model(), def.flipV(), def.id());
    }

    /** 玻璃板的网格（同一个 {@code .obj}，所以缓存键是变体 id，不是模型 id）。 */
    public static Map<String, List<RawMesh>> meshes(final FangsuPsdGlassDef def) {
        return meshes(def.model(), def.flipV(), def.id());
    }

    private static Map<String, List<RawMesh>> meshes(final Identifier model, final boolean flipV,
                                                     final String cacheKey) {
        return MESHES.computeIfAbsent(cacheKey, id -> {
            final Map<String, Map<Identifier, List<RawMesh.Quad>>> byGroup = new LinkedHashMap<>();
            for (final RawMesh mesh : RawObjParser.materials(model, flipV,
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
}
