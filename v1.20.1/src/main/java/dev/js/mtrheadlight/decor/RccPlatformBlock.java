// RCC 三种站台方块的方块类 —— 一个空子类，只用构造参数区分「平齐 / 内凹」。
package dev.js.mtrheadlight.decor;

import mtr.block.BlockPlatform;
import net.minecraft.block.AbstractBlock;

/**
 * RCC 站台方块（{@code mtrlte:platform} / {@code platform_indented} / {@code platform_type_quartz}）
 * —— <b>就是 MTR 的站台方块换了模型和贴图</b>。
 *
 * <h2>为什么是「继承」而不是「照着写一个」</h2>
 * <p>与 {@link MarblePlatformBlock} 的路子完全一样：{@code facing}（四向）、{@code door_type}
 * （{@code none} / {@code psd} / {@code apg}）、{@code side}（0..4，站台面箭头）三个属性都由
 * {@code mtr.block.BlockPlatform#getActualState} 从<b>世界邻居</b>推出来：
 * <pre>
 *   pos.up()                            上方是屏蔽门？→ door_type = psd / apg，facing 换成门的朝向
 *   pos.up().offset(facing.rotateY*)    上方左右邻格是不是门 → side = 1 / 2 / 3 / 4
 * </pre>
 * 继承之后连带吃到两件事，都不需要额外代码：
 * <ul>
 *   <li>{@code mtr.data.Train#scanDoors} 用 {@code block instanceof BlockPlatform} 认出站台，
 *       进站自动停靠开门；</li>
 *   <li>本模组的 {@code BlockPlatformDoorTypeMixin} 打在父类 {@code getActualState} 上，
 *       子类不重写它，于是「全高 PSD 也能把站台切成 {@code door_type=psd}」照样生效。</li>
 * </ul>
 *
 * <h2>{@code indented} 那一格参数决定了什么</h2>
 * <p>父类构造器的第二个参数只影响 {@code getOutlineShape} 与自家模型的几何搭配：
 * <ul>
 *   <li>{@code false}（平齐）：整格碰撞，对应素材 {@code platform.json} —— 站台边退缝 0.2 格，
 *       但碰撞仍是整格，与 MTR 的 {@code mtr:platform} 一致；</li>
 *   <li>{@code true}（内凹）：碰撞切成「贴着轨道侧的凹槽 + 顶板」两块，对应素材
 *       {@code platform_indented.json} 里那条 {@code from:[0,0,0.2]} 之前挖掉的 8 格进深。</li>
 * </ul>
 * 模型和碰撞必须成对：把内凹模型配平齐碰撞会让人悬在凹槽上，反之会踩空。
 *
 * <h2>模型 / 箭头在哪</h2>
 * <p>方块本体见 {@code assets/mtrlte/models/block/platform*.json}（源自 RCC 的
 * {@code tcwm:block/platform*}，只改了命名空间）；箭头模型 <b>直接用 MTR 的</b>
 * {@code mtr:block/platform_arrow_1..4}（RCC 的 blockstate 也是这么引用的），
 * 所以「屏蔽门放上去之后站台上出现箭头」这件事，方块代码里一行都没有 ——
 * 全靠父类算出来的 {@code side} 去挑模型。
 */
public class RccPlatformBlock extends BlockPlatform {

    /**
     * @param settings 方块属性（见 {@link RccPlatforms}）
     * @param indented {@code true} = 内凹站台（碰撞挖掉朝向侧一条）；{@code false} = 平齐站台
     */
    public RccPlatformBlock(final AbstractBlock.Settings settings, final boolean indented) {
        super(settings, indented);
    }
}
