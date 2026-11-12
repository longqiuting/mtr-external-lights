// 玻璃扶手的注册：11 个几何段 = 11 个独立方块 + 11 个方块物品（全部进 mtrlte:core 页签）。
package dev.js.mtrheadlight.railing;

import java.util.ArrayList;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * 玻璃扶手 {@code mtrlte:railing_glass_<段名>} —— 与 {@link RailingWallBlocks} 同一套做法：
 * <b>一个模型 = 一个方块</b>，方块上只有 {@code facing}，没有 {@code type} 属性、没有邻居推导、
 * 没有按状态切模型。素材（{@code 素材/玻璃扶手/railing_stair_glass_*_hangzhou.json}）里的 11 个几何段
 * 各注册成一个方块 + 一个物品，用哪个模型完全由玩家挑哪个物品决定
 * （MSD 新版是「一个方块 + {@code type} 属性切十几个模型」，这里<b>不要</b>那套）。
 *
 * <h2>与墙上扶手的关系</h2>
 * <p>几何约定完全一样：<b>贴墙侧在 {@code x=0..3}、走向沿 {@code z}</b>，所以直接复用
 * {@link RailingWallBlock} —— 同一个 {@code facing} 语义（{@code facing} = 扶手贴在哪一侧，
 * {@code west→y=0 / north→y=90 / east→y=180 / south→y=270}）、同样的
 * 「形状从 y=0 拉到几何顶部，框选与碰撞共用这一份」策略。
 *
 * <p>差别只在三处：
 * <ol>
 *   <li><b>高度</b>：墙上扶手画在 {@code y=18..31}（墙顶之上），玻璃扶手是落地玻璃栏杆，
 *       从 {@code y=0} 一直画到 {@code y=17.5}，斜段的手扶管再翘到 {@code y≈29}，
 *       端部 / 斜段的玻璃还会画到 {@code y=-5}（顺着楼梯扎进下一格）。包围盒的 {@code y1} 一律取
 *       {@code 0} —— {@code y<0} 的部分落在下方方块里，做成碰撞会把人卡在楼梯里。
 *       <b>碰撞顶面不取几何高度，而是对齐墙上扶手</b>（见下面「碰撞高度」）。</li>
 *   <li><b>材质</b>：玻璃 + 金属管，声音走 {@link BlockSoundGroup#GLASS}、强度 0.6，
 *       {@code nonOpaque}；贴图带 alpha，渲染层由 {@link RailingGlassClient} 挂
 *       {@code translucent}（MSD 的玻璃栏杆也是 translucent）。</li>
 *   <li><b>不遮挡 / 不窒息 / 不刷怪</b>：照原版玻璃，视线与生成都按「空」处理。</li>
 * </ol>
 *
 * <h2>包围盒怎么来的</h2>
 * <p>与墙上扶手一样是<b>手写的粗盒子</b>（不是逐元素取包围盒）：每段一条贴墙薄片
 * {@code x=0.75..2.25}（玻璃板 + 上下立柱 + 手扶管的实际厚度），外转角再补一条垂直伸出墙面的臂。
 * <b>注意斜段里的 45° 元素</b>：素材 {@code from/to} 是旋转前的坐标（例如斜段的
 * {@code z=-6.2..19.31}），真实包围盒要按 {@code rotation} 算过才知道 —— 实际是
 * {@code z=0..17.06 / y=12..29.06}，并没有真的伸出格子 6 格。这里的盒子按<b>旋转后</b>的
 * 这里的盒子按<b>旋转后</b>的 AABB 写，{@code tools/check-railing-shapes.js} 也按旋转后的坐标核对。
 *
 * <h2>碰撞高度：对齐墙上扶手，跳不过去</h2>
 * <p>{@code y1} 一律 {@code 0}（由 {@link RailingWallBlock#stretchToFloor} 压平），<b>{@code y2} 不取
 * 玻璃几何的顶（平直段只有 {@code 17.5} ≈ 1.09 格），而是照抄墙上扶手同一段的高度</b>：
 * <pre>
 *   平直段 / 外转角   20      （与 railing_wall_flat、railing_wall_corner_2 同高）
 *   端部收口          20.5    （与 railing_wall_end 同高）
 *   内转角            21      （与 railing_wall_corner 同高）
 *   斜段起点 / 斜段    31      （与 railing_wall_start / railing_wall_stair 同高）
 * </pre>
 * 原因：玩家跳跃顶点是 {@code 1.2522} 格（初速 0.42、重力 0.08、阻力 0.98 逐 tick 累加），
 * 玻璃平直段的 {@code 17.5/16 = 1.094} 格<b>一跳就过去了</b>；{@code 20/16 = 1.25} 格正好压在顶点下方，
 * 只有顶点那一个 tick 脚底能高过 0.0022 格，走不过 3/16 厚的薄片 —— 实机就是「跳不过去」。
 * 代价是玻璃上方多出 {@code 2.5/16} 格看不见的挡头（平直段），与墙上扶手的手感一致，
 * 两种栏杆现在挡人高度完全相同；玻璃斜段的几何顶 29.06 仍在 31 以内，不会漏几何。
 */
public final class RailingGlassBlocks {

    /** 物品栏页签 id（与 {@code XiamenObjects#CORE_TAB_ID} 相同）。 */
    private static final Identifier CORE_TAB_ID = new Identifier("mtrlte", "core");

    /**
     * 一个几何段：方块 id 后缀、模型文件（{@code assets/mtrlte/models/block/} 下的名字，
     * = 素材文件名去掉 {@code _hangzhou}）、轮廓 / 碰撞用的粗包围盒
     * （{@code {x1,y1,z1,x2,y2,z2}}，单位 1/16 格；模型空间：贴墙侧在 {@code x=0..3}，走向沿 {@code z}）。
     */
    private record Variant(String id, String model, double[][] boxes) {
    }

    /**
     * 注册顺序 = 创造页签里的排列顺序（与墙上扶手同序：平直 → 楼梯起点 → 斜段 → 端部 → 内转角 → 外转角）。
     * 语言键见 {@code lang/zh_cn.json} / {@code lang/en_us.json}。
     *
     * <p>盒子一律从 {@code y=0} 起（几何画到 {@code y<0} 的那几段不往下伸，理由见类注释）；
     * <b>{@code y2} 取墙上扶手同一段的高度</b>，不取玻璃自己的几何顶（见类注释「碰撞高度」）。
     */
    private static final List<Variant> VARIANTS = List.of(
            // 平直段：两块立柱（z=0..0.5 / 15.5..16）+ 玻璃板 + 顶上手扶管（y=16..17.5）。
            // 碰撞顶面 20 = 墙上扶手平直段的高度（跳不过去，见类注释）。
            new Variant("railing_glass_flat", "railing_stair_glass_flat", boxes(
                    box(0.75, 0, 0, 2.25, 22, 16))),

            // 楼梯起点：平地立柱 + 手扶管，外加一整段 45° 上坡玻璃（旋转后 y=16..29.06, z=0..13.06）。
            new Variant("railing_glass_start", "railing_stair_glass_start", boxes(
                    box(0.75, 0, 0, 2.25, 31, 16))),
            new Variant("railing_glass_start_mirror", "railing_stair_glass_start_mirror", boxes(
                    box(0.75, 0, 0, 2.25, 31, 16))),

            // 楼梯斜段：通体 45° 玻璃 + 手扶管，旋转后最高的手扶管到 y≈29.06，沿走向探出 1.06/16。
            new Variant("railing_glass_stair", "railing_stair_glass", boxes(
                    box(0.75, 0, 0, 2.25, 31, 17.1))),
            new Variant("railing_glass_stair_mirror", "railing_stair_glass_mirror", boxes(
                    box(0.75, 0, -1.1, 2.25, 31, 16))),

            // 端部收口：上端平段 + 向下收的 45° 玻璃（旋转后 y=12..17.5, z=11.56..17.06）。
            new Variant("railing_glass_end", "railing_stair_glass_end", boxes(
                    box(0.75, 0, 0, 2.25, 22, 17.1))),
            new Variant("railing_glass_end_mirror", "railing_stair_glass_end_mirror", boxes(
                    box(0.75, 0, -1.1, 2.25, 22, 16))),

            // 内转角：一小截 L 形短臂，落在方块的一个角上（z=13.75..16）。
            new Variant("railing_glass_corner", "railing_stair_glass_corner", boxes(
                    box(0, 0, 13.75, 2.25, 22, 16))),
            new Variant("railing_glass_corner_mirror", "railing_stair_glass_corner_mirror", boxes(
                    box(0, 0, 0, 2.25, 22, 2.25))),

            // 外转角（素材里的 corner_2）：沿墙薄片 + 垂直伸出墙面的臂，两条盒子拼成 L（与墙上扶手同构）。
            new Variant("railing_glass_corner_2", "railing_stair_glass_corner_2", boxes(
                    box(0.75, 0, 0.75, 2.25, 22, 16),
                    box(0.75, 0, 0.75, 16, 22, 2.25))),
            new Variant("railing_glass_corner_2_mirror", "railing_stair_glass_corner_2_mirror", boxes(
                    box(0.75, 0, 0, 2.25, 22, 15.25),
                    box(0.75, 0, 13.75, 16, 22, 15.25))));

    private static final List<Item> ITEMS = new ArrayList<>();
    private static final List<RailingWallBlock> BLOCKS = new ArrayList<>();
    private static boolean registered;

    private RailingGlassBlocks() {
    }

    /** 方块 + 方块物品 + 页签归类，{@code LteServer} 入口调一次（排在 {@code RailingWallBlocks} 之后）。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        for (final Variant variant : VARIANTS) {
            final Identifier id = new Identifier("mtrlte", variant.id());
            final RailingWallBlock block = Registry.register(Registries.BLOCK, id,
                    new RailingWallBlock(settings(), variant.boxes()));
            BLOCKS.add(block);
            ITEMS.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        }

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, CORE_TAB_ID))
                .register(entries -> ITEMS.forEach(entries::add));

        LteLog.LOGGER.info("[lte] railing glass: {} variants ({} .. {})",
                VARIANTS.size(), VARIANTS.get(0).id(), VARIANTS.get(VARIANTS.size() - 1).id());
    }

    /** 客户端渲染层要用的方块列表（{@link RailingGlassClient} 挂 translucent）。 */
    static List<RailingWallBlock> blocks() {
        return List.copyOf(BLOCKS);
    }

    /**
     * 玻璃栏杆：照原版玻璃不挡光、不窒息、不刷怪、不遮视线，只是形状不是满块。
     * <p>强度给 0.6（玻璃 0.3 / 铁 1.5 之间，玻璃 + 金属管），声音走玻璃。
     */
    private static AbstractBlock.Settings settings() {
        return AbstractBlock.Settings.create()
                .mapColor(MapColor.CLEAR)
                .strength(0.6F)
                .sounds(BlockSoundGroup.GLASS)
                .nonOpaque()
                .allowsSpawning((state, world, pos, type) -> false)
                .solidBlock((state, world, pos) -> false)
                .suffocates((state, world, pos) -> false)
                .blockVision((state, world, pos) -> false);
    }

    private static double[] box(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new double[]{x1, y1, z1, x2, y2, z2};
    }

    private static double[][] boxes(double[]... boxes) {
        return boxes;
    }
}
