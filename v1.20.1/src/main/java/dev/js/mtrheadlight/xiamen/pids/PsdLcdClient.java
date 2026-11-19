// PSD-LCD 挂板的客户端接线：只有一个方块实体渲染器 + 渲染层。
package dev.js.mtrheadlight.xiamen.pids;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

/**
 * 厦门地铁 PSD-LCD 挂板的客户端注册（{@code fabric.mod.json} 的 {@code client} 入口）。
 *
 * <ul>
 *   <li><b>渲染器</b>：{@link PsdLcdRenderer}，绑到 {@code mtrlte:psd_lcd_xiamen}；</li>
 *   <li><b>渲染层</b>：{@code RenderLayer.getCutout()} —— 模型里的 {@code metal.png} 带 alpha，
 *       走 solid 会把透明像素画黑（和厦门 PIDS 一样）。</li>
 * </ul>
 *
 * <p>刷子界面<b>不用</b>在这里再注册一遍：它走的是 {@link PidsXiamenNet} 的同一条通道、
 * 同一个「打开配置界面」的接收器（见 {@link PidsXiamenClient}）。
 */
public final class PsdLcdClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(PsdLcdObjects.entityType(), PsdLcdRenderer::new);
            LteLog.LOGGER.info("[lte] psd lcd renderer registered: {}", PsdLcdRenderer.class.getName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the psd lcd renderer", t);
        }

        try {
            BlockRenderLayerMap.INSTANCE.putBlock(PsdLcdObjects.block(), RenderLayer.getCutout());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] could not set the cutout layer for the psd lcd block", t);
        }
    }
}
