// 闸机的客户端部分：注册方块实体渲染器（几何是运行时画的，没有方块模型要挂渲染层）。
package dev.js.mtrheadlight.xiamen.ticket;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

/**
 * 闸机方块的客户端接线：只注册 {@link XiamenTicketGateRenderer}。
 *
 * <h2>为什么这里没有 {@code BlockRenderLayerMap}</h2>
 * <p>闸机已经没有方块模型了（blockstate 全部指向 {@code mtrlte:block/empty}），
 * 也就不存在「这张图该走 solid 还是 cutout」的问题 —— 渲染层由渲染器自己取
 * {@code RenderLayer.getEntityCutoutNoCull(texture)}（贴图直绑，不进方块图集，
 * alpha 裁掉、背面不剔除）。渲染层挂在方块上对 BER 没有作用，所以上一版的
 * {@code putBlock(gate, RenderLayer.getCutout())} 一并去掉了。
 *
 * <p>失败只记一行日志、不抛出去：渲染器没注册的症状是「闸机整块透明」，日志里能直接看到。
 */
public final class XiamenTicketGateClient {

    private XiamenTicketGateClient() {
    }

    /** 注册闸机的 BER；由 {@code XiamenClient} 在客户端初始化时调一次。 */
    public static void register() {
        try {
            BlockEntityRendererRegistry.register(XiamenTicketGates.entityType(), XiamenTicketGateRenderer::new);
            LteLog.LOGGER.info("[lte] ticket gate renderer registered: {} ({} blocks, rawmodels)",
                    XiamenTicketGateRenderer.class.getSimpleName(), XiamenTicketGates.gates().size());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the ticket gate renderer", t);
        }
    }
}
