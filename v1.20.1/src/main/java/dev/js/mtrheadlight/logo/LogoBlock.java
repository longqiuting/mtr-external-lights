// 标志方块（LTE / 角美地铁）：一块满方块，四面贴同一张 logo。
package dev.js.mtrheadlight.logo;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;

/**
 * 标志方块 {@code mtrlte:lte_logo} / {@code mtrlte:jiaomei_logo}。
 *
 * <h2>和 MTR 原版 {@code mtr:logo}、本模组 {@code mtrlte:xiamen_logo} 同构</h2>
 * <p>三者是同一个东西：<b>一个满方块 + 四面一张 logo 覆盖层</b>，不朝向、不贴墙、不受邻居影响。
 * MTR 的 {@code Blocks.LOGO} 就是
 * {@code AbstractBlock.Settings.create().requiresTool().hardness(2.0f).luminance(state -> 10)}
 * 的一个裸 {@code Block}，这里逐字照抄（见 {@link LogoObjects#register()}）。
 *
 * <h2>模型（{@code assets/mtrlte/models/block/*_logo.json}）</h2>
 * <ul>
 *   <li><b>底板</b>：{@code 0..16} 的整格立方体，六面 {@code cullface}，贴
 *       {@code mtr:block/metal}；</li>
 *   <li><b>标志层</b>：{@code -0.05..16.05} 外扩 0.05 的覆盖层，只有四个侧面、
 *       {@code "shade": false}，贴各自的 logo 图（{@code mtrlte:block/lte_logo} /
 *       {@code mtrlte:block/jiaomei_logo}）。外扩是为了不和底板 z-fighting。</li>
 * </ul>
 * <p>贴图是<b>挖空</b>的（logo 以外 alpha = 0），所以客户端要挂
 * {@code RenderLayer.getCutout()}（见 {@link LogoClient}），否则空白处会被画成黑色。
 * 另外两张图都由源 logo 居中缩放而成，都配了 {@code .mcmeta}（{@code blur: false, clamp: true}）
 * —— 不然 mipmap 会在 logo 边缘糊出一圈半透明毛边。
 *
 * <h2>为什么不干脆复用 {@code XiamenLogoBlock}</h2>
 * <p>行为完全一样（都只是一个没有覆写的 {@code Block}），分开写只是为了让「LTE 自己的标志」
 * 和「厦门地铁的标志」在注册点 / 注释里各自有名字，也免得改一处注释把另一处说岔。
 */
public class LogoBlock extends Block {

    public LogoBlock(final AbstractBlock.Settings settings) {
        super(settings);
    }
}
