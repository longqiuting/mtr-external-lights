// 方速屏蔽门的客户端挂载：只注册一个方块实体渲染器（14 个方块共用一个方块实体类型）。
package dev.js.mtrheadlight.xiamen.fangsu;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

/**
 * 客户端入口：给 {@code mtrlte:fangsu_psd_door} 挂 {@link FangsuPsdRenderer}。
 *
 * <p>不需要 {@code BlockRenderLayerMap}：贴图不经方块图集，模型在
 * {@code assets/mtrlte/blockstates/fangsu_psd_*.json} 里是「只有粒子」的空壳，
 * 几何由渲染器用 {@code RenderLayer.getEntityCutout(贴图)} 直绑绘制（和 eyecandy 同一个做法）。
 */
public final class FangsuPsdClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(FangsuPsdBlocks.entityType(),
                    context -> new FangsuPsdRenderer(context));
            LteLog.LOGGER.info("[lte] fangsu psd door renderer registered: {}",
                    FangsuPsdRenderer.class.getName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the fangsu psd door renderer", t);
        }
        // 玻璃板：另一个方块类 + 另一个方块实体类型（静态，只画 glass_* 那一组）。
        try {
            if (FangsuPsdBlocks.glassEntityType() != null) {
                BlockEntityRendererRegistry.register(FangsuPsdBlocks.glassEntityType(),
                        context -> new FangsuPsdGlassRenderer(context));
                LteLog.LOGGER.info("[lte] fangsu psd glass renderer registered: {}",
                        FangsuPsdGlassRenderer.class.getName());
            }
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the fangsu psd glass renderer", t);
        }
    }
}
