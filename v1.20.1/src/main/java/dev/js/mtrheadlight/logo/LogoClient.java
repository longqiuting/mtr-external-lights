// 标志方块的客户端接线：两张挖空的 logo 贴图要挂 cutout 渲染层。
package dev.js.mtrheadlight.logo;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.block.Block;
import net.minecraft.client.render.RenderLayer;

/**
 * 标志方块的渲染层（{@code mtrlte:lte_logo} / {@code mtrlte:jiaomei_logo}）。
 *
 * <p>和 MTR 在 {@code MTRClient} 里给 {@code Blocks.LOGO} 挂 {@code getCutout()} 是同一个理由：
 * logo 贴图外圈 alpha = 0、方块本身又是不透明的满方块，走 solid 层会把空白处画成黑块。
 *
 * <p>逐个 try/catch：一个挂了不影响另一个（渲染层挂了只是那一块标志发黑，
 * 不该让整个客户端初始化失败）。
 */
public final class LogoClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        put(LogoObjects.lteLogo(), "lte logo");
        put(LogoObjects.jiaomeiLogo(), "jiaomei logo");
    }

    private static void put(final Block block, final String what) {
        if (block == null) {
            return;
        }
        try {
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] could not set the cutout layer for the {} block", what, t);
        }
    }
}
