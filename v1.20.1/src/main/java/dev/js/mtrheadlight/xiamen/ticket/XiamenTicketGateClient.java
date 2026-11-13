// 闸机的客户端部分：只挂渲染层（贴图里有大片 alpha=0，必须 cutout）。
package dev.js.mtrheadlight.xiamen.ticket;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

/**
 * 闸机方块在客户端的渲染层：{@code cutout}。
 *
 * <h2>为什么必须挂 cutout</h2>
 * <p>{@code assets/mtrlte/textures/block/ticket_barrier.png}（与 {@code brt_barrier.png}）是
 * 4 通道贴图，透明像素约占一半（玻璃 / 镂空 / 未使用的区域 alpha = 0）。方块模型默认走
 * {@code solid} 层，那条管线<b>不丢弃</b> alpha=0 的像素，会把这些地方画成纯黑；
 * 挂到 {@code cutout} 上之后 alpha &lt; 0.1 的像素直接裁掉，剩下的还是硬边（不混合），
 * 不会出现排序问题。这也是本模组屏蔽门那一族的做法（见 {@code XiamenClient}）。
 *
 * <p>几何全部来自 blockstate + 原版模型（没有方块实体、没有 BER）：模型由
 * {@code tools/gen-ticket-gate-models.js} 生成，所以这里除了渲染层没有别的事可做。
 */
public final class XiamenTicketGateClient {

    private XiamenTicketGateClient() {
    }

    /** 给四个闸机方块挂 cutout 层；每个方块单独 try/catch，一个失败不影响其它。 */
    public static void registerRenderLayers() {
        for (final XiamenTicketGates.Gate gate : XiamenTicketGates.gates()) {
            try {
                BlockRenderLayerMap.INSTANCE.putBlock(gate.block(), RenderLayer.getCutout());
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] could not set the cutout layer for the ticket gate {}", gate.id, t);
            }
        }
        LteLog.LOGGER.info("[lte] ticket gate render layers: cutout x{}", XiamenTicketGates.gates().size());
    }
}
