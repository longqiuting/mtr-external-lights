// 两套 eyecandy 素材（厦门风格物件包 26 件 + 南普陀 34 件）的注册点：
// 方块 + 物品 + 空壳方块实体 + 页签 + 碰撞箱（AABB / 转角 L 形 / 桥墩只有轮廓）。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawObjParser;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
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
import net.minecraft.util.shape.VoxelShape;

/**
 * eyecandy（{@code .obj} 装饰物件）的注册入口。<b>两套素材 = 两个页签</b>：
 *
 * <table border="1">
 *   <caption>两套素材</caption>
 *   <tr><th>素材包</th><th>描述文件</th><th>件数</th><th>页签</th></tr>
 *   <tr><td>{@code resourcepacks/厦门风格物件包}</td><td>{@code assets/mtrlte/eyecandies/first.json}</td>
 *       <td>26</td><td>{@code mtrlte:core}「厦门风格物件」（与屏蔽门 / 闸机 / 栏杆同一页）</td></tr>
 *   <tr><td>{@code resourcepacks/厦门南普陀相关装饰物件}</td>
 *       <td>{@code assets/mtrlte/eyecandies/nanputuo.json}</td><td>34</td>
 *       <td>{@code mtrlte:nanputuo_decor}「南普陀装饰物件」（单独一页）</td></tr>
 * </table>
 *
 * <p>原来那个 {@code mtrlte:xiamen_decor}「厦门装饰物件」页签<b>已经不注册了</b>（用户要求并进
 * 「厦门风格物件」，别再多出一个只放这 26 件的页）。来源是 {@link EyecandyEntry.Pack}。
 *
 * <h2>素材</h2>
 * <p>{@code .obj} 和 {@code .png} 全部复制到本模组的 {@code assets/mtrlte/eyecandies/} 与
 * {@code assets/mtrlte/textures/eyecandies/}（两包的 key / 文件名不冲突，所以共用一个目录），
 * {@code first.json} / {@code nanputuo.json} 原样保留素材包的格式，命名空间在解析时统一改成
 * {@code mtrlte}。
 *
 * <h2>一个 key 一个方块</h2>
 * <p>id = {@code mtrlte:eyecandy_<key 小写>}（{@code key1} / {@code bike2} / {@code qiaodun20} /
 * {@code sign_stand_1} …），中文名直接用描述文件的 {@code name}（写进 {@code lang/zh_cn.json}）。
 * 60 个方块共用一个方块实体类型 {@code mtrlte:eyecandy}（渲染器按方块实例上的描述挑网格）。
 *
 * <h2>碰撞箱：三条规则</h2>
 * <ol>
 *   <li><b>普通物件 → 一个 AABB</b>：读 {@code .obj} 的 {@code v} 行，按
 *       {@code scale → rotation(XYZ 欧拉，度) → translation(格)} 变换（{@link RawObjParser#place}），
 *       换算到模型空间 0..16 取逐轴 min/max（{@link #boxOf}）。素材里那两列
 *       {@code shape} / {@code collisionShape} <b>一个都不用</b>（26 件里有 9 件的
 *       {@code collisionShape} 是 {@code 0,0,0,0,0,0}，高度还统一写成 16 / 24，跟真实几何对不上）。
 *       末尾的「抖动」：{@code -2.5..0} 这种亚像素负数抹成 0；<b>大于 2.5 单位的负值保留</b> ——
 *       那是真伸出去一格的几何（4.875 格长的 BRT 栏杆、64 格宽的线网图、20 格高的桥墩）。</li>
 *   <li><b>转角栏杆 → L 形</b>（{@link #CORNER_KEYS}：{@code key13} 适配 MSD 的转角栏杆、
 *       {@code key14} 厦门 BRT 栏杆转角）：这两件<b>不是一整块</b>，直接套 AABB 会把拐角里那块
 *       空地也挡住（而且 BRT 拐角模型本身 4.5 格宽，AABB 连邻居格都算进去了）。做法是从几何
 *       推出方块内 {@code 16 × 16} 的占位格（{@link RawObjParser#footprint}），闭运算补掉 1 单位
 *       的缝，再按行合并成一组盒子（{@link #cornerBoxes}）—— 结果就是两条栏杆的 L，
 *       走廊里既不会有一堵看不见的墙，也不会从栏杆里穿过去。</li>
 *   <li><b>桥墩 → 只有轮廓</b>（{@link #NO_COLLISION_KEYS}：{@code qiaodun20}）：碰撞盒是空集，
 *       轮廓是一个 1×1×1 的整格框（选中时能看见一格的范围，方便对齐），模型本身按
 *       {@code first.json} 的 {@code "translation": [0, 2, 0]} 从放置点<b>往下挂</b>：墩顶落在放置格
 *       的顶面 = 上方桥面的底面，玩家贴着桥面底部放就是一条完整的墩。</li>
 * </ol>
 *
 * <h2>没有朝向</h2>
 * <p>方块只有一种状态（blockstate 单变体 {@code ""}）：没有 {@code ROTATION}、没有刷子旋转、
 * 也没有「四向各一份碰撞盒」。模型烘在 {@code .obj} 里，放下去是什么样子就一直是什么样子
 * （用户原话：「刷子旋转也没什么用，玩家放置的时候自然就转向了」）。
 *
 * <h2>为什么注册期就要读 OBJ</h2>
 * <p>碰撞箱是方块属性，服务端也得有 —— 所以这里在主线程（两端）读 60 个 obj 的顶点算盒子，
 * 只有两件转角栏杆需要三角形（算占位格），其余走 {@link RawObjParser#bounds} 的
 * {@code withFaces=false} 分支。渲染用的完整网格只在客户端、第一次画到的时候才解析（{@link #mesh}）。
 *
 * <h2>由谁调用</h2>
 * <p>{@code LteServer} 在 {@code XiamenTicketGates.register()} 之后调 {@link #register()}；
 * 客户端在 {@code XiamenClient} 里调 {@link EyecandyClient#register()}。
 */
public final class EyecandyBlocks {

    /** 模组命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 南普陀页签 id：{@code mtrlte:nanputuo_decor}（名字由 {@code itemGroup.mtrlte.nanputuo_decor} 决定）。 */
    public static final Identifier NANPUTUO_TAB_ID = new Identifier(NAMESPACE, "nanputuo_decor");

    /** 60 个方块共用的方块实体类型 id。 */
    public static final Identifier ENTITY_ID = new Identifier(NAMESPACE, "eyecandy");

    /**
     * 转角栏杆（要 L 形碰撞箱的 key）：{@code key13} 适配 MSD 的转角栏杆、{@code key14} 厦门 BRT 栏杆转角。
     * <p>两件的几何都是「两条互相垂直的栏杆」，套整格 AABB 会把拐角里的空地挡住。
     */
    private static final Set<String> CORNER_KEYS = new LinkedHashSet<>(List.of("key13", "key14"));

    /**
     * 只要轮廓、不要碰撞的 key：{@code qiaodun20}（20 格高的桥墩）。
     * <p>轮廓 = 1×1×1 整格框（对齐用），碰撞 = 空集（一格方框不该挡人，真正的墩身是 20 格高的几何）。
     */
    private static final Set<String> NO_COLLISION_KEYS = new LinkedHashSet<>(List.of("qiaodun20"));

    /** 抹零阈值（模型单位）：比 2.5 单位（≈2px）更小的负数当作浮点噪声。 */
    private static final double NUDGE_UNITS = 2.5D;

    /** 防御性下限：某一轴因为抹零退化成 0 时补到 1 单位（1px），避免出现零厚度碰撞盒。 */
    private static final double MIN_SIZE_UNITS = 1.0D;

    /** 占位格合并时允许补掉的缝隙（单位）：栏杆上的立柱、扶手之间有 1~2 单位的空档。 */
    private static final int GAP_UNITS = 2;

    /** 占位格的闭运算次数（把 1 单位的采样缝补实，转角才不会变成漏勺）。 */
    private static final int CLOSE_PASSES = 3;

    /** 描述文件里的顺序 = 页签里的顺序（两包依次：厦门 26 件、南普陀 34 件）。 */
    private static final List<EyecandyEntry> ENTRIES = new ArrayList<>();

    private static final Map<String, EyecandyBlock> BLOCKS = new LinkedHashMap<>();
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();

    /** OBJ 文本缓存（注册期一个物件要读两遍：算碰撞盒 + 打日志；60 个 obj 合计几 MB，别读两次）。 */
    private static final Map<String, String> MODEL_TEXTS = new LinkedHashMap<>();

    private static BlockEntityType<EyecandyBlockEntity> entityType;
    private static boolean registered;

    private EyecandyBlocks() {
    }

    /** 全部描述（{@code register()} 之后才有内容）。 */
    public static List<EyecandyEntry> entries() {
        return ENTRIES;
    }

    /** 全部方块（顺序同 {@link #entries()}）。 */
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

    /** 某个 key 是不是「转角栏杆」（L 形碰撞箱）。 */
    public static boolean isCorner(final String key) {
        return CORNER_KEYS.contains(key);
    }

    /** 某个 key 是不是「只有轮廓没有碰撞」（桥墩）。 */
    public static boolean hasNoCollision(final String key) {
        return NO_COLLISION_KEYS.contains(key);
    }

    /**
     * 某个物件的碰撞盒，<b>方块空间</b>（{@code [minX, minY, minZ, maxX, maxY, maxZ]}，1 = 1 格）。
     *
     * <p>这是「普通物件」那一条规则算出来的整块 AABB —— 转角栏杆与桥墩用另两条规则（见类注释），
     * 这里只当参考值（日志、调试命令、{@code tools/verify-eyecandies.js} 用）。
     * {@code qiaodun20} 会是 {@code y ∈ [-19, 1]}（从放置点往下挂 20 格，墩顶贴放置格顶面），
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
            // 亚像素负数抖到 0（只抖噪声级别的；大负值是真实的伸出几何 —— 桥墩就是整段挂在 y<0）。
            if (min < 0.0D && min > -NUDGE_UNITS / 16.0D) {
                min = 0.0D;
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
     * 某个物件真正用的盒子列表（模型空间 0..16，{@code [x1, y1, z1, x2, y2, z2]}），按类注释的三条规则：
     * 普通物件 1 个盒子、转角栏杆一组 L 形盒子、桥墩 1 个整格框。
     */
    public static List<double[]> boxesOf(final EyecandyEntry entry) {
        final List<double[]> out = new ArrayList<>();
        if (hasNoCollision(entry.key())) {
            // 1×1×1 的框选线（与朝向无关：整格转不转都一样）。
            out.add(new double[] {0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D});
            return out;
        }
        if (isCorner(entry.key())) {
            final String text = modelText(entry);
            if (text != null) {
                final int[] grid = RawObjParser.footprint(text, entry.scale(), entry.rotation(),
                        entry.translation(), entry.mirror());
                final double[] box = boxOf(entry);
                final List<double[]> corner = cornerBoxes(grid, Math.max(0.0D, box[1] * 16.0D),
                        Math.max(1.0D, box[4] * 16.0D));
                if (!corner.isEmpty()) {
                    out.addAll(corner);
                    return out;
                }
            }
            LteLog.LOGGER.warn("[lte] eyecandy {}: corner footprint is empty, falling back to an AABB",
                    entry.key());
        }
        final double[] box = boxOf(entry);
        out.add(new double[] {box[0] * 16.0D, box[1] * 16.0D, box[2] * 16.0D,
                box[3] * 16.0D, box[4] * 16.0D, box[5] * 16.0D});
        return out;
    }

    /** 某个物件的轮廓形状（{@code north} 那一份；{@code register()} 之后才有）。 */
    public static VoxelShape shapeOf(final EyecandyEntry entry) {
        final EyecandyBlock block = BLOCKS.get(entry.key());
        if (block != null) {
            return block.shape();
        }
        return EyecandyBlock.shapeOf(boxesOf(entry));
    }

    /**
     * 渲染用的完整网格（客户端；第一次调用解析 OBJ，之后走 {@link RawObjParser#get} 的缓存）。
     *
     * <p>每个 obj 只对应一条描述（两包之间 obj 文件名也不重复），所以按 {@code modelId} 缓存是安全的。
     */
    public static RawMesh mesh(final EyecandyEntry entry) {
        return RawObjParser.get(entry.modelId(), entry.texture(), entry.flipV(),
                entry.scale(), entry.rotation(), entry.translation(), entry.mirror());
    }

    // ------------------------------------------------------------------ 转角栏杆：占位格 → 盒子

    /**
     * 占位格 → 轴对齐盒子列表（模型空间 0..16）。
     *
     * <p>三步：
     * <ol>
     *   <li><b>闭运算</b>（{@link #CLOSE_PASSES} 遍）：某格空着、但左右都有东西（或上下都有东西）
     *       → 填上。栏杆上的立柱 / 扶手之间有 1 单位的采样缝，不补的话碰撞箱会变成带孔的一排格子；</li>
     *   <li><b>逐行合并成 run</b>：一行里连续的实心格算一段，缝隙 ≤ {@link #GAP_UNITS} 单位就接上
     *       （立柱之间的空档对玩家来说过不去，连成一条更接近「栏杆」这个事实）；</li>
     *   <li><b>纵向合并</b>：相邻几行的 run 完全一样 → 并成一个盒子（16 行最多压到几个盒子）。</li>
     * </ol>
     *
     * @param grid   {@link RawObjParser#footprint} 的 16×16 占位格
     * @param yMin   盒子在 y 方向的起点（模型单位）
     * @param yMax   盒子在 y 方向的终点（模型单位）
     */
    static List<double[]> cornerBoxes(final int[] grid, final double yMin, final double yMax) {
        final int size = RawObjParser.FOOTPRINT_SIZE;
        int[] filled = grid.clone();
        for (int pass = 0; pass < CLOSE_PASSES; pass++) {
            final int[] next = filled.clone();
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    if (filled[z * size + x] != 0) {
                        continue;
                    }
                    final boolean horizontal = at(filled, x - 1, z) && at(filled, x + 1, z);
                    final boolean vertical = at(filled, x, z - 1) && at(filled, x, z + 1);
                    if (horizontal || vertical) {
                        next[z * size + x] = 1;
                    }
                }
            }
            filled = next;
        }

        // 逐行 run（[x1, x2)，单位 = 格）
        final List<List<int[]>> rows = new ArrayList<>(size);
        for (int z = 0; z < size; z++) {
            final List<int[]> runs = new ArrayList<>();
            int x = 0;
            while (x < size) {
                if (filled[z * size + x] == 0) {
                    x++;
                    continue;
                }
                int end = x;
                int scan = x;
                while (scan < size) {
                    if (filled[z * size + scan] != 0) {
                        end = scan;
                        scan++;
                        continue;
                    }
                    int look = scan;
                    while (look < size && look - scan < GAP_UNITS && filled[z * size + look] == 0) {
                        look++;
                    }
                    if (look < size && filled[z * size + look] != 0) {
                        scan = look;
                        continue;
                    }
                    break;
                }
                runs.add(new int[] {x, end + 1});
                x = end + 1;
            }
            rows.add(runs);
        }

        // 纵向合并
        final List<double[]> out = new ArrayList<>();
        int z0 = 0;
        while (z0 < size) {
            if (rows.get(z0).isEmpty()) {
                z0++;
                continue;
            }
            int z1 = z0;
            while (z1 + 1 < size && sameRuns(rows.get(z1 + 1), rows.get(z0))) {
                z1++;
            }
            for (final int[] run : rows.get(z0)) {
                out.add(new double[] {run[0], yMin, z0, run[1], yMax, z1 + 1});
            }
            z0 = z1 + 1;
        }
        return out;
    }

    private static boolean at(final int[] grid, final int x, final int z) {
        final int size = RawObjParser.FOOTPRINT_SIZE;
        return x >= 0 && z >= 0 && x < size && z < size && grid[z * size + x] != 0;
    }

    private static boolean sameRuns(final List<int[]> a, final List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) {
                return false;
            }
        }
        return true;
    }

    /** OBJ 文本（classpath），读不到返回 {@code null} 并警告一次。 */
    private static String modelText(final EyecandyEntry entry) {
        final String cached = MODEL_TEXTS.get(entry.key());
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        final String path = "/assets/" + entry.modelId().getNamespace() + "/" + entry.modelId().getPath();
        try (InputStream in = EyecandyBlocks.class.getResourceAsStream(path)) {
            if (in == null) {
                MODEL_TEXTS.put(entry.key(), "");
                LteLog.LOGGER.warn("[lte] eyecandy {}: {} is missing (collision box falls back to a full block)",
                        entry.key(), path);
                return null;
            }
            final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            MODEL_TEXTS.put(entry.key(), text);
            return text;
        } catch (Throwable t) {
            MODEL_TEXTS.put(entry.key(), "");
            LteLog.LOGGER.warn("[lte] eyecandy {}: could not read {} ({})", entry.key(), path, t.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 方块 + 物品 + 方块实体类型 + 两个页签的条目，{@code LteServer} 入口调一次。幂等。
     *
     * <p>页签：厦门那 26 件追加进 {@code mtrlte:core}（注册顺序在别的模块之后，所以排在页签末尾），
     * 南普陀 34 件进自己新开的 {@code mtrlte:nanputuo_decor}。
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        for (final EyecandyEntry.Pack pack : EyecandyEntry.Pack.values()) {
            final List<EyecandyEntry> loaded = EyecandyEntry.loadAll(pack);
            if (loaded.isEmpty()) {
                LteLog.LOGGER.warn("[lte] eyecandy: {} is missing or empty, no {} decorations registered",
                        pack.descriptor(), pack.name().toLowerCase(java.util.Locale.ROOT));
                continue;
            }
            ENTRIES.addAll(loaded);
        }
        if (ENTRIES.isEmpty()) {
            return;
        }

        final Block[] blocks = new Block[ENTRIES.size()];
        for (int i = 0; i < ENTRIES.size(); i++) {
            final EyecandyEntry entry = ENTRIES.get(i);
            final List<double[]> boxes = boxesOf(entry);
            final EyecandyBlock block = new EyecandyBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.GRAY)
                    .strength(0.8F)
                    .sounds(BlockSoundGroup.STONE)
                    .nonOpaque(), entry, boxes, !hasNoCollision(entry.key()));
            Registry.register(Registries.BLOCK, entry.blockId(), block);
            // 方块与物品同一个 id（本模组厦门物件的一贯做法）。
            final Item item = Registry.register(Registries.ITEM, entry.blockId(),
                    new BlockItem(block, new Item.Settings()));
            blocks[i] = block;
            BLOCKS.put(entry.key(), block);
            ITEMS.put(entry.key(), item);
        }

        // 60 个方块共用一个方块实体类型（渲染器按方块实例上的 entry 挑网格）。
        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, ENTITY_ID,
                FabricBlockEntityTypeBuilder.create(EyecandyBlockEntity::new, blocks).build());

        // 页签 1：厦门 26 件追加进「厦门风格物件」（mtrlte:core，别的模块已经建好了这一页）。
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                .register(entries -> addAll(entries, EyecandyEntry.Pack.XIAMEN));

        // 页签 2：南普陀 34 件单开一页。
        final List<EyecandyEntry> nanputuo = entriesOf(EyecandyEntry.Pack.NANPUTUO);
        if (!nanputuo.isEmpty()) {
            Registry.register(Registries.ITEM_GROUP, NANPUTUO_TAB_ID,
                    FabricItemGroup.builder()
                            .displayName(Text.translatable("itemGroup.mtrlte.nanputuo_decor"))
                            .icon(() -> new ItemStack(ITEMS.get(nanputuo.get(0).key())))
                            .build());
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, NANPUTUO_TAB_ID))
                    .register(entries -> addAll(entries, EyecandyEntry.Pack.NANPUTUO));
        }

        for (final EyecandyEntry entry : ENTRIES) {
            final List<double[]> boxes = boxesOf(entry);
            final double[] box = boxOf(entry);
            LteLog.LOGGER.info("[lte] eyecandy {} ({}) {} box(es), aabb {}x{}x{} at [{}, {}, {}]{}",
                    entry.blockId(), entry.displayName(), boxes.size(),
                    round(box[3] - box[0]), round(box[4] - box[1]), round(box[5] - box[2]),
                    round(box[0]), round(box[1]), round(box[2]),
                    hasNoCollision(entry.key()) ? " (outline only)" : isCorner(entry.key()) ? " (corner)" : "");
        }
        LteLog.LOGGER.info("[lte] eyecandy: {} decorations registered ({} into {}, {} into {})",
                ENTRIES.size(), entriesOf(EyecandyEntry.Pack.XIAMEN).size(), XiamenObjects.CORE_TAB_ID,
                nanputuo.size(), NANPUTUO_TAB_ID);
    }

    /** 某个页签（已注册时才有；调试用）。 */
    public static ItemGroup tab(final Identifier id) {
        return Registries.ITEM_GROUP.get(id);
    }

    /** 某一包的条目（按描述文件顺序）。 */
    private static List<EyecandyEntry> entriesOf(final EyecandyEntry.Pack pack) {
        final List<EyecandyEntry> out = new ArrayList<>();
        for (final EyecandyEntry entry : ENTRIES) {
            if (entry.pack() == pack) {
                out.add(entry);
            }
        }
        return out;
    }

    /** 把某一包的物品按描述文件顺序追加进一个页签。 */
    private static void addAll(final ItemGroup.Entries entries, final EyecandyEntry.Pack pack) {
        for (final EyecandyEntry entry : ENTRIES) {
            if (entry.pack() != pack) {
                continue;
            }
            final Item item = ITEMS.get(entry.key());
            if (item != null) {
                entries.add(item);
            }
        }
    }

    private static double round(final double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }
}
