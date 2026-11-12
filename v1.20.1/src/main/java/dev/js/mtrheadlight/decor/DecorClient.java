// 大理石地板 / 站台的客户端部分：把站台挂到 cutout 渲染层（否则贴图里的 alpha=0 会画成黑块）。
package dev.js.mtrheadlight.decor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.block.Block;
import net.minecraft.client.render.RenderLayer;

/**
 * 装饰方块 {@code mtrlte:marble_platform} / {@code mtrlte:marble_floor} 的渲染层。
 *
 * <h2>为什么必须有这一层（「站台贴图变灰」的真正原因）</h2>
 * <p>本模组的大理石站台 = MTR 的 {@code BlockPlatform} 子类，模型是「大理石本体 + 一块 16×16 的箭头薄片」
 * （{@code marble_platform} + {@code marble_platform_arrow_1..4}，后者挂在 {@code y=16.1}）。
 * 箭头贴图 {@code mtrlte:block/platform_arrow}（256×128，取自 MTR 的 {@code mtr:block/platform_arrow}）
 * 里<b>只有箭头本身是不透明的</b>：26663 个像素 alpha=0，5111 个是深灰底，只有 162 个纯白。
 * 同样地，站台模型自己那条黄色警戒带用的 {@code mtrlte:block/warning}（16×16）也是 160 个 alpha=0 +
 * 96 个黄色。
 *
 * <p>Minecraft 1.20.1 的方块模型默认走 {@code RenderLayer.getSolid()}，那一层<b>不做 alpha 测试</b>：
 * 贴图里 alpha=0 的像素会连同它的颜色（黑色）一起写进缓冲区。于是
 * <pre>
 *   屏蔽门放上去 → 父类把 side 算成 1..4 → blockstate 叠上箭头模型
 *   → 那张整格 16×16 的箭头顶板连同它 80% 的透明的部分一起被画成「一块深灰板」盖住大理石
 * </pre>
 * 这就是实机截图里「屏蔽门那一条站台整块变成深灰、上面还压着白/绿箭头」的形态
 * —— <b>不是</b>站台被换成了 MTR 的 {@code platform_psd} 模型（blockstate 里从头到尾没引用过任何
 * {@code mtr:} 模型，见 {@code tools/check-platform-models.js} 的枚举表），而是同一块方块的箭头层把
 * 透明像素画成了不透明的黑/深灰板。
 *
 * <h2>为什么是 cutout 而不是 translucent</h2>
 * <p>MTR 自己给 {@code Blocks.PLATFORM} 挂的就是 {@code getCutout()}
 * （{@code mtr.MTRClient#onInitializeClient} 里 {@code RegistryClient.registerBlockRenderType(RenderLayer.getCutout(), Blocks.PLATFORM.get())}）。
 * cutout 做的是「alpha < 0.1 的片段直接丢弃、其余完全不透明」，正好是箭头贴图要的语义：
 * 箭头之外的像素消失、箭头本身是纯色。translucent 会引入排序问题，且大理石本体并不需要半透明。
 *
 * <p>大理石本体那张 {@code floor_hangzhou}（1744×1744）被模型用到的 {@code uv 0..8} 区域（左上 872×872）
 * 是<b>完全不透明</b>的（实测 alpha=0 像素 0 个），所以挂 cutout 不会让站台本体变透明；地板同理。
 *
 * <p>前提与 {@code SecurityClient} / {@code RailingGlassClient} 相同：方块由 {@code main} 入口
 * （{@link DecorBlocks#register()}）注册，Fabric 先跑 {@code main} 再跑 {@code client}，这里拿得到实例。
 * 整段包在 try 里 —— 渲染层没挂上只是贴图难看（透明处变黑），不影响方块本身能用。
 */
public final class DecorClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            putCutout(DecorBlocks.marblePlatform());
            putCutout(DecorBlocks.marbleFloor());
        } catch (Throwable ignored) {
            // Fabric API 版本不对时静默失效：贴图透明处会被画成黑色，方块照常能放。
        }
    }

    private static void putCutout(final Block block) {
        if (block != null) {
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout());
        }
    }
}
