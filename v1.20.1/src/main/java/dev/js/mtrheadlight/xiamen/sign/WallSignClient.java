// 贴墙标识的客户端接线：我们自己的站名渲染器 + cutout 渲染层。
package dev.js.mtrheadlight.xiamen.sign;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

/**
 * 贴墙标识（{@code mtrlte:wall_sign_*}）的客户端注册。
 *
 * <h2>渲染器 = 我们自己的 {@link WallSignRenderer}</h2>
 * <p>上一版直接挂 MTR 的 {@code RenderStationNameTiled(dispatcher, true)}，它把 MTR 原版的 logo
 * （{@code mtr:textures/block/sign/logo.png}，红底白 M）烤进运行时生成的牌面贴图里 —— 资源包换不掉，
 * 于是三种品牌牌子全挂着 MTR 的 logo。现在换成 {@link WallSignRenderer}：站名用它自己的白字贴图
 * （{@code getStationName}），只占牌面左边那条；品牌 logo 走模型里的 quad
 * （{@code models/block/station_name_entrance_{tall,short}.json}，贴图 {@code mtrlte:block/*_logo}）。
 *
 * <h2>渲染层：cutout</h2>
 * <p>牌面底图是实心色（{@code mtrlte:block/station_name_background}），但模型里叠的品牌 logo 是挖空的 PNG，
 * 走 solid 会把空白画成黑块 —— 所以这三个方块单独设 cutout（只影响我们自己的方块）。
 * 站名那条走 {@code INTERIOR}（= {@code getEntityCutout}），和 cutout 一致。
 */
public final class WallSignClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(WallSignObjects.entityType(),
                    context -> new WallSignRenderer(context.getRenderDispatcher()));
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
