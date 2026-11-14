// 贴墙标识的客户端接线：方块实体渲染器 + 渲染层。
package dev.js.mtrheadlight.xiamen.sign;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

/**
 * 贴墙标识（{@code mtrlte:wall_sign_*}）的客户端注册。
 *
 * <ul>
 *   <li><b>渲染器</b>：三个方块共用一个方块实体类型（{@link WallSignObjects#entityType()}），
 *       所以只注册一次 {@link WallSignRenderer}；</li>
 *   <li><b>渲染层</b>：{@code cutout}。牌子本体（{@code station_name_entrance_tall}）是零厚度的板，
 *       品牌 logo 那张是挖空的 PNG，走 solid 会把空白画成黑块 —— 和站外标志 / 站牌同一个原因。</li>
 * </ul>
 *
 * <p>每一步各自 try/catch：注册失败只该「牌子上没有字」，不该连累渲染层，
 * 也不该让整个客户端崩。由 {@code fabric.mod.json} 的 {@code client} 入口拉起。
 */
public final class WallSignClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(WallSignObjects.entityType(), WallSignRenderer::new);
            LteLog.LOGGER.info("[lte] wall sign renderer registered: {}", WallSignRenderer.class.getName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the wall sign renderer", t);
        }

        for (final WallSignObjects.Entry entry : WallSignObjects.entries()) {
            try {
                BlockRenderLayerMap.INSTANCE.putBlock(entry.block(), RenderLayer.getCutout());
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] could not set the cutout layer for the wall sign {}", entry.id(), t);
            }
        }
    }
}
