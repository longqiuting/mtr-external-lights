// 26 个厦门风格 eyecandy 的注册点：方块 + 物品 + 空壳方块实体 + 独立创造页签 + 从几何推的碰撞箱。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawObjParser;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/**
 * 厦门风格物件包（用户自己的资源包 {@code 厦门风格物件包}）里那 26 个 eyecandy 的注册入口。
 *
 * <h2>素材</h2>
 * <p>原件在 {@code resourcepacks/厦门风格物件包/assets/mtrsteamloco/eyecandies/}：
 * 26 个 {@code .obj} + 24 张 {@code .png} + 一个 {@code first.json}（27 个 {@code .mtl} 不用）。
 * 全部复制到 {@code assets/mtrlte/eyecandies/}，命名空间 {@code mtrsteamloco} → {@code mtrlte}，
 * 路径不变 —— 于是 {@code first.json} 里的 {@code model} / {@code textureId} 只需要换命名空间。
 *
 * <h2>一个 key 一个方块</h2>
 * <p>id = {@code mtrlte:eyecandy_<key 小写>}（{@code key1} / {@code bike2} / {@code qiaodun20} …），
 * 中文名直接用 {@code first.json} 的 {@code name}（写进 {@code lang/zh_cn.json}），
 * 26 个按 {@code first.json} 的顺序进新页签 {@code mtrlte:xiamen_decor}（「厦门装饰物件」）。
 *
 * <h2>碰撞箱：从几何重推，不用素材里的 collisionShape</h2>
 * <p>{@code first.json} 里那两列 {@code shape} / {@code collisionShape} 是随手写的
 * （26 个物件里有 9 个的 {@code collisionShape} 是 {@code 0,0,0,0,0,0}，也就是完全没有碰撞；
 * 高度还统一写 16 或 24，跟真实几何对不上）。所以这里<b>一个都不用</b>，改成：
 * <ol>
 *   <li>读 {@code .obj} 的 {@code v} 行；</li>
 *   <li>按 {@code scale → rotation(XYZ 欧拉，度) → translation(格)} 变换（见 {@link RawObjParser#place}）；</li>
 *   <li>换算到模型空间 0..16，取逐轴 min/max；</li>
 *   <li>末尾的「抖动」：{@code -2.5..0} 这种亚像素负数抹成 0（否则草地上一格薄片会带一个 -0.03 的尾巴）；
 *       <b>大于 2.5 单位的负值保留</b> —— 那是真的伸出去一格的几何（4.875 格长的 BRT 栏杆
 *       {@code x ∈ [-38, 40]}、64 格宽的地铁线网图 {@code z ∈ [-24, 40]}、20 格高的桥墩
 *       {@code x ∈ [-50, 66]}），把它们裁到 0 会让碰撞箱与看得见的模型对不上；</li>
 *   <li>上限<b>不 clamp</b>：{@code qiaodun20} 就是 20 格高（320 单位），薄片就是 1 单位厚。</li>
 * </ol>
 * 碰撞盒与轮廓盒用同一个（{@link EyecandyBlock}）。
 *
 * <h2>为什么注册期就要读 OBJ</h2>
 * <p>碰撞箱是方块属性，服务端也得有 —— 所以这里在主线程（两端）读 26 个 obj 的 {@code v} 行算 AABB，
 * 不生成四边形（{@link RawObjParser#bounds} 的 {@code withFaces=false} 分支）。渲染用的完整网格
 * 只在客户端、第一次画到的时候才解析（{@link #mesh}）。
 *
 * <h2>由谁调用</h2>
 * <p>{@code LteServer} 在 {@code XiamenTicketGates.register()} 之后调 {@link #register()}；
 * 客户端在 {@code XiamenClient} 里调 {@link EyecandyClient#register()}。
 */
public final class EyecandyBlocks {

    /** 模组命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 创造页签 id：{@code mtrlte:xiamen_decor}（名字由 {@code itemGroup.mtrlte.xiamen_decor} 决定）。 */
    public static final Identifier TAB_ID = new Identifier(NAMESPACE, "xiamen_decor");

    /** 26 个方块共用的方块实体类型 id。 */
    public static final Identifier ENTITY_ID = new Identifier(NAMESPACE, "eyecandy");

    /** 抹零阈值（模型单位）：比 2.5 单位（≈2px）更小的负数当作浮点噪声。 */
    private static final double NUDGE_UNITS = 2.5D;

    /** 防御性下限：某一轴因为抹零退化成 0 时补到 1 单位（1px），避免出现零厚度碰撞盒。 */
    private static final double MIN_SIZE_UNITS = 1.0D;

    /** {@code first.json} 里的顺序 = 页签里的顺序。 */
    private static final List<EyecandyEntry> ENTRIES = new ArrayList<>();

    private static final Map<String, EyecandyBlock> BLOCKS = new LinkedHashMap<>();
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    private static final Map<String, VoxelShape> SHAPES = new LinkedHashMap<>();

    private static BlockEntityType<EyecandyBlockEntity> entityType;
    private static boolean registered;

    private EyecandyBlocks() {
    }

    /** 26 条描述（{@code register()} 之后才有内容）。 */
    public static List<EyecandyEntry> entries() {
        return ENTRIES;
    }

    /** 26 个方块（顺序同 {@link #entries()}）。 */
    public static List<EyecandyBlock> blocks() {
        return new ArrayList<>(BLOCKS.values());
    }

    /** 某个 key 的方块（没注册 / key 不存在 → {@code null}）。 */
    public static EyecandyBlock block(final String key) {
        return BLOCKS.get(key);
    }

    /** 某个 key 的方块物品（没注册 / key 不存在 → {@code null}）。 */
    public static Item item(final String key) {
        return ITEMS.get(key);
    }

    /** 方块实体类型（{@link EyecandyBlockEntity} 构造器要用，{@code register()} 之后非 null）。 */
    public static BlockEntityType<EyecandyBlockEntity> entityType() {
        return entityType;
    }

    /** 某个物件的碰撞 / 轮廓盒（方块空间；{@code register()} 之前调会现算）。 */
    public static VoxelShape shapeOf(final EyecandyEntry entry) {
        final VoxelShape cached = SHAPES.get(entry.key());
        if (cached != null) {
            return cached;
        }
        final double[] box = boxOf(entry);
        final VoxelShape shape = VoxelShapes.cuboid(new Box(
                box[0], box[1], box[2], box[3], box[4], box[5]));
        SHAPES.put(entry.key(), shape);
        return shape;
    }

    /**
     * 某个物件的碰撞盒，<b>方块空间</b>（{@code [minX, minY, minZ, maxX, maxY, maxZ]}，1 = 1 格）。
     *
     * <p>模型空间的 0..16 除以 16 得来，所以 {@code qiaodun20} 会是 {@code y ∈ [0, 20]}、
     * {@code key12}（线网图）会是 {@code z ∈ [-1.5, 2.5]} —— 数值超出 0..1 是<b>有意</b>的。
     */
    public static double[] boxOf(final EyecandyEntry entry) {
        final String text = modelText(entry);
        if (text == null) {
            return new double[] {0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D};
        }
        final double[] units = RawObjParser.bounds(text, entry.scale(), entry.rotation(),
                entry.translation(), entry.mirror());
        final double[] box = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            double min = units[axis] / 16.0D;
            double max = units[axis + 3] / 16.0D;
            // 亚像素负数抖到 0（“只把负值抖到 0”，但只抖噪声级别的；大负值是真实的伸出几何）。
            if (min < 0.0D && min > -NUDGE_UNITS / 16.0D) {
                min = 0.0D;
            }
            if (max < 0.0D) {
                max = 0.0D;
            }
            if (max - min < MIN_SIZE_UNITS / 16.0D) {
                max = min + MIN_SIZE_UNITS / 16.0D;
            }
            box[axis] = min;
            box[axis + 3] = max;
        }
        return box;
    }

    /**
     * 渲染用的完整网格（客户端；第一次调用解析 OBJ，之后走 {@link RawObjParser#get} 的缓存）。
     *
     * <p>每个 obj 只对应一条描述，所以按 {@code modelId} 缓存是安全的（26 个物件 26 个文件）。
     */
    public static RawMesh mesh(final EyecandyEntry entry) {
        return RawObjParser.get(entry.modelId(), entry.texture(), entry.flipV(),
                entry.scale(), entry.rotation(), entry.translation(), entry.mirror());
    }

    /** OBJ 文本（classpath），读不到返回 {@code null} 并警告一次。 */
    private static String modelText(final EyecandyEntry entry) {
        final String path = "/assets/" + entry.modelId().getNamespace() + "/" + entry.modelId().getPath();
        try (InputStream in = EyecandyBlocks.class.getResourceAsStream(path)) {
            if (in == null) {
                LteLog.LOGGER.warn("[lte] eyecandy {}: {} is missing (collision box falls back to a full block)",
                        entry.key(), path);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] eyecandy {}: could not read {} ({})", entry.key(), path, t.toString());
            return null;
        }
    }

    /** 方块 + 物品 + 方块实体类型 + 独立创造页签，{@code LteServer} 入口调一次。幂等。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ENTRIES.addAll(EyecandyEntry.loadAll());
        if (ENTRIES.isEmpty()) {
            LteLog.LOGGER.warn("[lte] eyecandy: {} is missing or empty, no decorations registered",
                    EyecandyEntry.DESCRIPTOR);
            return;
        }

        final Block[] blocks = new Block[ENTRIES.size()];
        for (int i = 0; i < ENTRIES.size(); i++) {
            final EyecandyEntry entry = ENTRIES.get(i);
            final EyecandyBlock block = new EyecandyBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.GRAY)
                    .strength(0.8F)
                    .sounds(BlockSoundGroup.STONE)
                    .nonOpaque(), entry, shapeOf(entry));
            Registry.register(Registries.BLOCK, entry.blockId(), block);
            // 方块与物品同一个 id（本模组厦门物件的一贯做法）。
            final Item item = Registry.register(Registries.ITEM, entry.blockId(),
                    new BlockItem(block, new Item.Settings()));
            blocks[i] = block;
            BLOCKS.put(entry.key(), block);
            ITEMS.put(entry.key(), item);
        }

        // 26 个方块共用一个方块实体类型（渲染器按方块实例上的 entry 挑网格）。
        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, ENTITY_ID,
                FabricBlockEntityTypeBuilder.create(EyecandyBlockEntity::new, blocks).build());

        final Item icon = ITEMS.get(ENTRIES.get(0).key());
        Registry.register(Registries.ITEM_GROUP, TAB_ID,
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.mtrlte.xiamen_decor"))
                        .icon(() -> new ItemStack(icon))
                        .build());

        // 顺序 = first.json 的顺序。
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, TAB_ID))
                .register(entries -> {
                    for (final EyecandyEntry entry : ENTRIES) {
                        final Item item = ITEMS.get(entry.key());
                        if (item != null) {
                            entries.add(item);
                        }
                    }
                });

        for (final EyecandyEntry entry : ENTRIES) {
            final double[] box = boxOf(entry);
            LteLog.LOGGER.info("[lte] eyecandy {} ({}) box {}x{}x{} at [{}, {}, {}]",
                    entry.blockId(), entry.displayName(),
                    round(box[3] - box[0]), round(box[4] - box[1]), round(box[5] - box[2]),
                    round(box[0]), round(box[1]), round(box[2]));
        }
        LteLog.LOGGER.info("[lte] eyecandy: {} decorations registered into {}",
                ENTRIES.size(), TAB_ID);
    }

    /** 页签（已注册时才有；调试用）。 */
    public static ItemGroup tab() {
        return Registries.ITEM_GROUP.get(TAB_ID);
    }

    private static double round(final double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }
}
