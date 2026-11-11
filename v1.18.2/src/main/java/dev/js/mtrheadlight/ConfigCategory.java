package dev.js.mtrheadlight;

import net.minecraft.text.TranslatableText;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * 配置界面的分区。主界面（{@link ConfigScreen}）只有四个入口；「全局配置」和一条侧线的详情都先
 * 进 {@link CategoryScreen} 选分区，分区里才是具体的行（{@link ParameterScreen}）。
 *
 * <h2>为什么分区</h2>
 * 侧线隔离之前，全部 19+ 行挤在一列里：光源、碰撞、伤害、水雾、内置车混在一起，一屏放不下，
 * 玩家要翻三轮滚轮才找得到「伤害指数 k」。分区把「找一条配置」变成「先想它属于哪一类」。
 *
 * <h2>哪些分区带侧线覆盖</h2>
 * 除「其他」外每个分区都有可按侧线覆盖的字段（{@link HeadlightConfig.SidingSettings}）：
 * <ul>
 *   <li>{@link #LIGHT} - 总开关 / 动态光源 / 光带 / 光柱开关 / 光束几何 / 颜色，全部可覆盖；
 *       车灯模式只在这一分区里出现，但它是全局的（没有侧线覆盖）；</li>
 *   <li>{@link #COLLISION} - 只有车头 / 车尾碰撞延长可覆盖，其余（车体开关、道床、车顶）是全局的；</li>
 *   <li>{@link #PARTICLE} - 水雾开关 / 起始速度 / 挡雨可覆盖，粒子设置与诊断是全局的；</li>
 *   <li>{@link #DAMAGE} - 伤害开关 / 伤害起点速度 / 伤害上限可覆盖，分档与公式系数是全局的；
 *       <b>关掉伤害只是不扣血，推开与撞飞照旧</b>；</li>
 *   <li>{@link #OTHER} - 全部只有全局值，侧线页不生成这个分区。</li>
 * </ul>
 * 这就是「侧线页只显示可覆盖的项」：分区列表在侧线模式下按 {@link #hasSidingRows()} 过滤，
 * 行表按 {@link #globalOnly} 过滤，两层都不过滤的话点到全局限定的行只会得到一次空操作。
 */
enum ConfigCategory {
    /** 光源：车灯模式、开关、光带、光柱、颜色、光束长度 / 宽度 / 高度、亮度、渲染距离。 */
    LIGHT("cat.light", true),
    /** 碰撞：车体碰撞、碰撞高度、道床、端部延长、车顶掉落。 */
    COLLISION("cat.collision", true),
    /** 粒子：雨天水雾及其起始速度、挡雨。 */
    PARTICLE("cat.particle", true),
    /** 伤害：伤害总开关、速度分档、伤害公式系数、冲量、生物是否生效。 */
    DAMAGE("cat.damage", true),
    /** 其他：内置车型屏蔽、重载。（车灯模式已移到「光源」。） */
    OTHER("cat.other", false);

    /** Translation key of the category name, shown on the button and in the detail title. */
    final String titleKey;
    /** True when at least one row of this category can be overridden per siding. */
    final boolean hasSidingRows;

    ConfigCategory(final String titleKey, final boolean hasSidingRows) {
        this.titleKey = titleKey;
        this.hasSidingRows = hasSidingRows;
    }

    /** Localized name; every literal lives in assets/mtrlte/lang. */
    MutableText title() {
        return new TranslatableText(this.titleKey);
    }
}
