package dev.js.mtrheadlight.xiamen;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

/**
 * 厦门物件的客户端部分：挂渲染层 + 注册门扇的 BlockEntityRenderer。
 *
 * <p>贴图中间大块是 <b>alpha=0</b> 的（玻璃部分），默认的 solid 渲染层会把透明像素
 * 画成黑色，所以两块静止板都上 {@code CUTOUT}（和 MTR 自己的屏蔽门一致）。
 * 顶层吊板 {@code xiamen_psd_top} 是不透明的金属图，用默认 solid 层即可。
 *
 * <p>门扇没有静态模型 —— 它是 {@link XiamenPsdDoorBlock}（BlockEntityProvider），
 * 几何由 {@link XiamenPsdDoorRenderer} 按 {@code half} 分上下段、按 {@code open} 插值滑动，
 * 所以这里只要把渲染器和 BE 类型接上。
 */
public final class XiamenClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdGlass(), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdEnd(), RenderLayer.getCutout());
            // 标志的贴图外面一圈是 alpha=0（和 MTR 的 sign/logo.png 一样），
            // MTR 在 MTRClient 里也是给 Blocks.LOGO 挂 getCutout()。
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.logo(), RenderLayer.getCutout());
            BlockEntityRendererRegistry.register(XiamenObjects.psdDoorEntityType(), XiamenPsdDoorRenderer::new);
        } catch (Throwable t) {
            // 渲染层没挂上只是透明区域发黑 / 门扇不显示，不该让客户端起不来。
            LteLog.LOGGER.warn("[lte] could not set the xiamen block render layers", t);
        }
    }
}
