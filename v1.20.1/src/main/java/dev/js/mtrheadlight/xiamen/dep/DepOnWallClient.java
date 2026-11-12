// 挂墙发车倒数器的客户端接线：方块实体渲染器 + 渲染层。
package dev.js.mtrheadlight.xiamen.dep;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

/**
 * 挂墙发车倒数器的客户端注册（{@code fabric.mod.json} 的 {@code client} 入口）。
 *
 * <ul>
 *   <li><b>渲染器</b>：{@link DepOnWallRenderer}，绑到 {@code mtrlte:dep_on_wall} 的方块实体类型；</li>
 *   <li><b>渲染层</b>：{@code RenderLayer.getCutout()} —— 与厦门 PIDS / MTR 自己的 PIDS 一致。
 *       {@code dep_on_wall.png} 是 RGBA 的，留白部分 alpha = 0，走 solid 会被画成黑块。</li>
 * </ul>
 *
 * <p>每一步各自 try/catch：渲染器注册失败只该「屏上没有字」，不该连累渲染层（反之亦然）。
 */
public final class DepOnWallClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(DepOnWallObjects.entityType(), DepOnWallRenderer::new);
            LteLog.LOGGER.info("[lte] dep on wall renderer registered: {}",
                    DepOnWallRenderer.class.getName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the dep on wall renderer", t);
        }

        try {
            BlockRenderLayerMap.INSTANCE.putBlock(DepOnWallObjects.block(), RenderLayer.getCutout());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] could not set the cutout layer for the dep on wall block", t);
        }
    }
}
