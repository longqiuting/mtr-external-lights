// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

/**
 * 厦门物件的客户端部分：挂渲染层 + 注册两个方块实体的渲染器。
 *
 * <p>玻璃 / 端部的贴图中间是 <b>alpha=0</b> 的（玻璃部分），默认 solid 层会把透明像素画成黑色，
 * 所以上 {@code CUTOUT}（和 MTR 自己在 {@code MTRClient} 里给屏蔽门挂的层一致）。
 * 吊板的灯箱面也用 cutout；门扇由 {@link RenderPsdDoor} 自己按 cutout-no-cull 取 buffer。
 */
public final class XiamenClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdGlass(), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdEnd(), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.psdTop(), RenderLayer.getCutout());
            // 标志的贴图外面一圈是 alpha=0（和 MTR 的 sign/logo.png 一样），
            // MTR 在 MTRClient 里也是给 Blocks.LOGO 挂 getCutout()。
            BlockRenderLayerMap.INSTANCE.putBlock(XiamenObjects.logo(), RenderLayer.getCutout());

            BlockEntityRendererRegistry.register(XiamenObjects.psdDoorEntityType(), RenderPsdDoor::new);
            BlockEntityRendererRegistry.register(XiamenObjects.psdTopEntityType(), RenderPsdTop::new);
        } catch (Throwable t) {
            // 渲染层没挂上只是透明区域发黑 / 门扇不显示，不该让客户端起不来。
            LteLog.LOGGER.warn("[lte] could not set the xiamen block render layers", t);
        }
    }
}
