// 方速屏蔽门的客户端挂载：三个方块实体类型各挂一个渲染器（绘制实现是共用的）。
package dev.js.mtrheadlight.xiamen.fangsu;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

/**
 * 客户端入口：给三套方块实体挂渲染器。
 *
 * <ul>
 *   <li>{@code mtrlte:fangsu_psd_door} → {@link FangsuPsdRenderer}（滑动门，MTR 方块实体）；</li>
 *   <li>{@code mtrlte:fangsu_psd_end_door} → {@link FangsuPsdEndDoorRenderer}（端门，驾驶钥匙开关）；</li>
 *   <li>{@code mtrlte:fangsu_psd_glass} → {@link FangsuPsdGlassRenderer}（玻璃板，4 种形态）。</li>
 * </ul>
 *
 * <p>三个渲染器的几何绘制都是 {@link FangsuPsdDraw}，差别只有「进度从哪来」。
 *
 * <p>不需要 {@code BlockRenderLayerMap}：贴图不经方块图集，模型在
 * {@code assets/mtrlte/blockstates/fangsu_psd_*.json} 里是「只有粒子」的空壳，
 * 几何由渲染器用 {@code RenderLayer.getEntityCutout(贴图)} 直绑绘制（和 eyecandy 同一个做法）。
 */
public final class FangsuPsdClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        if (FangsuPsdBlocks.entityType() != null) {
            try {
                BlockEntityRendererRegistry.register(FangsuPsdBlocks.entityType(),
                        context -> new FangsuPsdRenderer(context));
                LteLog.LOGGER.info("[lte] fangsu psd door renderer registered: {}",
                        FangsuPsdRenderer.class.getName());
            } catch (Throwable t) {
                LteLog.LOGGER.error("[lte] could not register the fangsu psd door renderer", t);
            }
        } else {
            LteLog.LOGGER.warn("[lte] fangsu psd sliding doors were not registered, no renderer");
        }
        // 端门：另一个方块类 + 另一个方块实体类型（驾驶钥匙手动开关，不接列车）。
        if (FangsuPsdBlocks.endDoorEntityType() != null) {
            try {
                BlockEntityRendererRegistry.register(FangsuPsdBlocks.endDoorEntityType(),
                        context -> new FangsuPsdEndDoorRenderer(context));
                LteLog.LOGGER.info("[lte] fangsu psd end door renderer registered: {}",
                        FangsuPsdEndDoorRenderer.class.getName());
            } catch (Throwable t) {
                LteLog.LOGGER.error("[lte] could not register the fangsu psd end door renderer", t);
            }
        } else {
            LteLog.LOGGER.warn("[lte] fangsu psd end doors were not registered, no renderer");
        }
        // 玻璃板：第三个方块类 + 第三个方块实体类型（静态，只画当前形态那一组）。
        if (FangsuPsdBlocks.glassEntityType() != null) {
            try {
                BlockEntityRendererRegistry.register(FangsuPsdBlocks.glassEntityType(),
                        context -> new FangsuPsdGlassRenderer(context));
                LteLog.LOGGER.info("[lte] fangsu psd glass renderer registered: {}",
                        FangsuPsdGlassRenderer.class.getName());
            } catch (Throwable t) {
                LteLog.LOGGER.error("[lte] could not register the fangsu psd glass renderer", t);
            }
        } else {
            LteLog.LOGGER.warn("[lte] fangsu psd glass panels were not registered, no renderer");
        }
    }
}
