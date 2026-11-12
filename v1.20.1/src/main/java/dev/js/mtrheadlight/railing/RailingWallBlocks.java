// 墙上扶手的注册：11 个几何段 = 11 个独立方块 + 11 个方块物品（全部进 mtrlte:core 页签）。
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
 * 墙上扶手的注册点 —— <b>每个模型一个方块</b>。
 *
 * <p>素材 {@code 素材/墙上扶手/} 里的 11 个 {@code railing_stair_*_on_wall.json} 各注册成
 * {@code mtrlte:railing_wall_<段名>[_mirror]}，一个方块对应一个模型文件、一个物品。
 * 方块上只有 {@code facing}（扶手贴在本方的哪一侧，四向），<b>没有</b> {@code shape} / {@code mirror} 属性、
 * 也没有邻居推导或按状态切模型 —— 这一段该用哪个模型完全由玩家挑哪个物品决定
 * （这一点与 MSD 不同：MSD 是一个方块 + {@code type} 属性切 20 多个模型）。
 * 和 MSD 一样<b>任意位置可放</b>：不检查背后有没有墙，也不因为墙/邻居被拆而掉落。
 *
 * <p>物品按 {@link #VARIANTS} 的顺序进 {@code mtrlte:core}（「厦门风格物件 / Xiamen Style Objects」）页签。
 * 那个页签由 {@code XiamenObjects#register()} 建，所以 {@code LteServer} 里本类的调用排在它后面。
 */
public final class RailingWallBlocks {

    /** 物品栏页签 id（与 {@code XiamenObjects#CORE_TAB_ID} 相同）。 */
    private static final Identifier CORE_TAB_ID = new Identifier("mtrlte", "core");

    /**
     * 一个几何段：方块 id 后缀、模型文件（{@code assets/mtrlte/models/block/} 下的名字）、
     * 轮廓 / 碰撞用的<b>粗包围盒</b>（{@code {x1,y1,z1,x2,y2,z2}}，单位 1/16 格；模型空间约定：
     * 贴墙侧在 {@code x=0..3}，走向沿 {@code z}）。
     *
     * <p>{@link RailingWallBlock} 会再复制一份、把底面压到 {@code y=0}，<b>框选与碰撞都用这份</b>
     * （2026-11-12 起两种栏杆的碰撞高度统一成「地面 → 几何顶部」）。所以写盒子时只要把<b>看得见的几何</b>
     * 完整裹住即可，不用自己去补到地面。
     */
    private record Variant(String id, String model, double[][] boxes) {
    }

    /**
     * 注册顺序 = 创造页签里的排列顺序。语言键见 {@code lang/zh_cn.json} / {@code lang/en_us.json}。
     *
     * <p>包围盒照 MSD 的做法：<b>每种几何段只给一两条手写的粗盒子</b> —— 贴墙的薄片（x=0..3，通长 z=0..16）
     * 加该段的竖向范围，外转角再补一条垂直伸出墙面的臂。<b>不</b>按模型元素逐个取包围盒、<b>不</b>给 45° 斜杆
     * 做阶梯近似：斜段（start / stair）就是一整块 y=18..31 的薄片，和 MSD 把斜段也写成一条通长薄片一样粗。
     * 竖向可以超出 0..16（扶手装在上沿，斜段能顶到 y≈31）；水平方向以 0..16 为准，只有 45° 斜杆的杆尖会探出
     * 0.36 格，那几段就写到 {@code -0.5..16.5}，别把杆尖留在框选外。
     *
     * <p>唯一要求：<b>盒子必须把该模型的可见几何完整裹住</b>（{@code tools/check-railing-shapes.js} 会逐个
     * 模型元素核对，漏出去一个角就报错）。框选盒一定会有富余，但「有富余」只是难看得多，而「漏几何」会直接
     * 变成选不上的黑块。
     */
    private static final List<Variant> VARIANTS = List.of(
            // 平直段：两块端板 + 通长横杆 → 一条薄片。
            new Variant("railing_wall_flat", "railing_stair_flat_on_wall", boxes(
                    box(0, 18, 0, 3, 22, 16))),

            // 楼梯斜段起点（下端接平段）：薄片一直罩到斜杆最高点。
            // 45° 斜杆的端头会探出格子 0.36 格（模型 z=-0.36 / 16.36），盒子跟着放半格，别把杆尖漏在框选外。
            new Variant("railing_wall_start", "railing_stair_start_on_wall", boxes(
                    box(0, 18, -0.5, 3, 31, 16.5))),
            new Variant("railing_wall_start_mirror", "railing_stair_start_mirror_on_wall", boxes(
                    box(0, 18, -0.5, 3, 31, 16.5))),

            // 楼梯斜段中段：通体斜杆，下端压到 y=14。
            new Variant("railing_wall_stair", "railing_stair_on_wall", boxes(
                    box(0, 14, -0.5, 3, 31, 16.5))),
            new Variant("railing_wall_stair_mirror", "railing_stair_mirror_on_wall", boxes(
                    box(0, 14, -0.5, 3, 31, 16.5))),

            // 端部收口：上端平段横杆 + 向下收的斜杆，竖向 14..20（45° 杆角会冒到 20.005，顶面给到 20.5）。
            new Variant("railing_wall_end", "railing_stair_end_on_wall", boxes(
                    box(0, 14, -0.5, 3, 22, 16.5))),
            new Variant("railing_wall_end_mirror", "railing_stair_end_mirror_on_wall", boxes(
                    box(0, 14, -0.5, 3, 22, 16.5))),

            // 内转角：只有一小截 L 形短臂，落在方块的一个角上（3x3 格）。
            // 2026-11-12 用户换了新模型：几何下移到 y=19..20（旧模型是 20..21）。
            new Variant("railing_wall_corner", "railing_stair_corner_on_wall", boxes(
                    box(0, 19, 13, 3, 22, 16))),
            new Variant("railing_wall_corner_mirror", "railing_stair_corner_mirror_on_wall", boxes(
                    box(0, 19, 0, 3, 22, 3))),

            // 外转角（素材里的 corner_2）：沿墙薄片 + 垂直伸出墙面的臂 + 臂端立板，三条盒子拼成 L（同 MSD 的 type=5）。
            // 臂端立板（x=15..16, z=0..3, y=18..19）比臂本身更宽，单独一条，别让 z=0..1 那一格漏掉。
            new Variant("railing_wall_corner_2", "railing_stair_corner_2_on_wall", boxes(
                    box(0, 18, 0, 3, 22, 16),
                    box(1, 18, 1, 16, 22, 3),
                    box(15, 18, 0, 16, 19, 3))),
            new Variant("railing_wall_corner_2_mirror", "railing_stair_corner_2_mirror_on_wall", boxes(
                    box(0, 18, 0, 3, 22, 16),
                    box(1, 18, 13, 16, 22, 16))));

    private static final List<Item> ITEMS = new ArrayList<>();
    private static boolean registered;

    private RailingWallBlocks() {
    }

    /** 方块 + 方块物品 + 页签归类，{@code LteServer} 入口调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        for (final Variant variant : VARIANTS) {
            final Identifier id = new Identifier("mtrlte", variant.id());
            final RailingWallBlock block = Registry.register(Registries.BLOCK, id,
                    new RailingWallBlock(settings(), variant.boxes()));
            ITEMS.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        }

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, CORE_TAB_ID))
                .register(entries -> ITEMS.forEach(entries::add));

        LteLog.LOGGER.info("[lte] railing wall: {} variants ({} .. {})",
                VARIANTS.size(), VARIANTS.get(0).id(), VARIANTS.get(VARIANTS.size() - 1).id());
    }

    /** 金属扶手：不透明薄片（nonOpaque 让旁边的方块面不被剔除），不需要工具就能拆，掉落表只有它自己。 */
    private static AbstractBlock.Settings settings() {
        return AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .strength(1.5F)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque();
    }

    private static double[] box(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new double[]{x1, y1, z1, x2, y2, z2};
    }

    private static double[][] boxes(double[]... boxes) {
        return boxes;
    }
}
