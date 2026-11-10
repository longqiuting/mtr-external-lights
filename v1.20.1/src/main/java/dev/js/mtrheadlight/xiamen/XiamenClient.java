// Xiamen client setup: render layers + block entity renderers (MTR's for the PSD top).
package dev.js.mtrheadlight.xiamen;

import dev.js.mtrheadlight.LteLog;
import mtr.render.RenderPSDTop;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;

/**
 * 厦门物件的客户端部分：挂渲染层 + 注册两个方块实体的渲染器。
 *
 * <ul>
 *   <li>门扇：{@link RenderPsdDoor}（MTR 的几何 + 厦门贴图；开度来自 MTR 的方块实体）；</li>
 *   <li>吊板：<b>MTR 自己的 {@code RenderPSDTop}</b> —— 站名 / 线路图 / 色带 / 方向箭头
 *       就是 MTR 那套运行时动态绘制（数据源 {@code ClientData.PLATFORMS} +
 *       {@code ClientData.DATA_CACHE}，经 {@code TileEntityRouteBase#getPlatformId} 定位站台）。
 *       它写死了泛型参数 {@code BlockPSDTop.TileEntityPSDTop}，而我们的方块实体继承的是
 *       {@code TileEntityRouteBase}（为了能用自己注册的 BlockEntityType），所以注册时要做一次
 *       不检查的向下转型；运行期 {@code RenderRouteBase} 只按 {@code TileEntityRouteBase} 调
 *       {@code getPlatformId}，是安全的。</li>
 * </ul>
 *
 * <p>玻璃 / 端部的贴图中间是 <b>alpha=0</b> 的（玻璃部分），默认 solid 层会把透明像素画成黑色，
 * 所以上 {@code CUTOUT}（和 MTR 自己在 {@code MTRClient} 里给屏蔽门挂的层一致）。
 */
public final class XiamenClient implements ClientModInitializer {

    @Override
    @SuppressWarnings("unchecked")
    public void onInitializeClient() {
        try {
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdGlass(), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdEnd(), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdTop(), RenderLayer.getCutout());
            // 标志的贴图外面一圈是 alpha=0（和 MTR 的 sign/logo.png 一样），
            // MTR 在 MTRClient 里也是给 Blocks.LOGO 挂 getCutout()。
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.logo(), RenderLayer.getCutout());

            BlockEntityRendererRegistry.register(XiamenObjects.psdDoorEntityType(), RenderPsdDoor::new);
            BlockEntityRendererRegistry.register(XiamenObjects.psdTopEntityType(), context ->
                    (BlockEntityRenderer<XiamenPsdBlocks.TileEntityXiamenPSDTop>) (Object)
                            new RenderPSDTop(context.getRenderDispatcher()));
        } catch (Throwable t) {
            // 渲染层没挂上只是透明区域发黑 / 门扇不显示，不该让客户端起不来。
            LteLog.LOGGER.warn("[lte] could not set the xiamen block render layers", t);
        }
    }
}
