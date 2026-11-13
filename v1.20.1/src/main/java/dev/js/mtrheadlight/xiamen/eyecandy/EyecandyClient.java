// eyecandy 的客户端接线：只注册方块实体渲染器（几何运行时画，没有方块模型要挂渲染层）。
package dev.js.mtrheadlight.xiamen.eyecandy;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

/**
 * 26 个 eyecandy 的客户端接线：只注册 {@link EyecandyRenderer}。
 *
 * <h2>为什么没有 {@code BlockRenderLayerMap}</h2>
 * <p>方块模型是 {@code mtrlte:block/empty}（{@code elements: []}），世界里看得见的东西全由 BER 画，
 * 渲染层是渲染器自己取的 {@code RenderLayer.getEntityCutout(texture)}（贴图直绑、不进方块图集）。
 * 给方块挂 {@code putBlock(...cutout)} 对 BER 没有作用，所以这里不挂。
 *
 * <p>由 {@code XiamenClient} 在客户端初始化时调一次。失败只记一行 error、不抛出去
 * （症状是「物件整块透明」，日志里能直接看到）。
 */
public final class EyecandyClient {

    private EyecandyClient() {
    }

    /** 注册 eyecandy 的 BER。 */
    public static void register() {
        try {
            BlockEntityRendererRegistry.register(EyecandyBlocks.entityType(), EyecandyRenderer::new);
            LteLog.LOGGER.info("[lte] eyecandy renderer registered: {} ({} blocks, obj)",
                    EyecandyRenderer.class.getSimpleName(), EyecandyBlocks.blocks().size());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the eyecandy renderer", t);
        }
    }
}
