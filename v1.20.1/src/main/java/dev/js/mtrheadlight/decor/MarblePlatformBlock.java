// 大理石站台 —— 直接继承 MTR 的站台方块，四个朝向 / 门型 / 箭头全部沿用 MTR 那一套。
package dev.js.mtrheadlight.decor;

import mtr.block.BlockPlatform;
import net.minecraft.block.AbstractBlock;

/**
 * 大理石站台 {@code mtrlte:marble_platform} —— <b>就是 MTR 的站台方块换了个材质</b>。
 *
 * <h2>为什么是「继承」而不是「照着写一个」</h2>
 * <p>{@code mtr.block.BlockPlatform} 上的三个属性 —— {@code facing}（四向）、{@code door_type}
 * （{@code none} / {@code psd} / {@code apg}）、{@code side}（0..4，站台面上的箭头）——
 * 不是渲染层的装饰，而是由方块自己的 {@code getActualState} 从<b>世界邻居</b>推出来的：
 * <pre>
 *   pos.up()                     上方是屏蔽门？→ door_type = psd / apg，facing 换成门的朝向
 *   pos.up().offset(facing.rotateY*)   上方左右邻格是不是门 → side = 1 / 2 / 3 / 4
 * </pre>
 * 这几行逻辑还牵着别处：
 * <ul>
 *   <li>{@code mtr.data.Train#scanDoors} 用 {@code block instanceof BlockPlatform} 判断「这一格是站台」，
 *       是就开门、并把车停在站台边。继承之后本方的站台天然被列车认出来，<b>不需要额外的 mixin</b>。</li>
 *   <li>本模组的 {@code BlockPlatformDoorTypeMixin} 是打在 {@code BlockPlatform#getActualState} 上的，
 *       子类不重写这个方法，所以子类也吃到那条「全高 PSD 也能把站台切成 {@code door_type=psd}」的修补。</li>
 * </ul>
 * <p>照着 MTR 抄一份方块类反而会掉进这两个坑（{@code instanceof} 不中、mixin 打不到），所以这里只写一个
 * 空子类：<b>属性、放置朝向、邻居更新、碰撞形状全部来自父类</b>。
 *
 * <h2>父类做了哪些事（这里一行都不用重写）</h2>
 * <ul>
 *   <li>{@code appendProperties}：注册 {@code facing} / {@code door_type} / {@code side} 三个属性；</li>
 *   <li>{@code getPlacementState}：{@code facing} = 玩家水平朝向（和 MTR 站台完全一致）；</li>
 *   <li>{@code getStateForNeighborUpdate}：每次邻居变动都重算 {@code getActualState}，
 *       所以把屏蔽门放上去 / 拆掉，站台的 {@code door_type} 与箭头 {@code side} 立刻跟着变；</li>
 *   <li>{@code getShape}：非内凹站台（{@code isIndented=false}，我们传的就是 false）用整格碰撞，
 *       与 MTR 的 {@code mtr:platform} 一样 —— 模型那条 0.2 格的退缝也照样站得住。</li>
 * </ul>
 *
 * <h2>模型 / 箭头在哪</h2>
 * <p>方块本体：{@code assets/mtrlte/models/block/marble_platform.json}（素材 {@code platform.json}）。
 * 箭头：{@code marble_platform_arrow_1..4}，是 MTR {@code mtr:block/platform_arrow_*} 的同一张图、
 * 同一块 16.1 高度的薄板，由 {@code blockstates/marble_platform.json} 按 {@code side} 叠上去——
 * 也就是说「屏蔽门放上去之后站台上出现箭头」这件事，<b>方块代码里一行都没有</b>，
 * 全靠父类算出来的 {@code side} 去挑模型。
 */
public class MarblePlatformBlock extends BlockPlatform {

    /**
     * @param settings 方块属性（见 {@link DecorBlocks}）
     */
    public MarblePlatformBlock(final AbstractBlock.Settings settings) {
        // isIndented=false：不内凹。内凹站台会切掉朝向侧 6 格深的一条、把碰撞拆成「凹槽 + 顶板」两块，
        // 那是 MTR 给「站台边留缝隙」的另一种形态，本方的素材没有那段几何。
        super(settings, false);
    }
}
