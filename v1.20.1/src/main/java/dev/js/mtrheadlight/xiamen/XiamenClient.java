package dev.js.mtrheadlight.xiamen;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

/**
 * 厦门物件的客户端部分（只挂渲染层，不注册任何 registry 条目）。
 *
 * <p>贴图中间大块是 <b>alpha=0</b> 的（玻璃部分，{@code psd_glass_top_1} 有 66.5% 的像素
 * 全透明），默认的 solid 渲染层会把透明像素画成黑色，所以两块板都上
 * {@code CUTOUT}：完全透明→剔除，不透明→照常画。用 CUTOUT 而不是 TRANSLUCENT 是为了
 * 和 MTR 自己的屏蔽门一致（同一套贴图在 MTR 里就是 cutout 材质），也避免半透明排序问题。
 */
public final class XiamenClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdDoor(), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdGlass(), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdEnd(), RenderLayer.getCutout());
        } catch (Throwable t) {
            // 渲染层没挂上只是透明区域发黑，不该让客户端起不来。
            LteLog.LOGGER.warn("[lte] could not set the xiamen block render layers", t);
        }
    }
}
