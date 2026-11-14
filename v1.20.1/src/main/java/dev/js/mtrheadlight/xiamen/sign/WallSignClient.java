// 贴墙标识的客户端接线：MTR 的站名渲染器（包一层品牌标记） + cutout 渲染层。
package dev.js.mtrheadlight.xiamen.sign;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

/**
 * 贴墙标识（{@code mtrlte:wall_sign_*}）的客户端注册。
 *
 * <h2>渲染器 = MTR 的 {@link mtr.render.RenderStationNameTiled}，包一层品牌标记</h2>
 * <p>{@link WallSignRenderer} 继承 MTR 的渲染器、构造参数一字不差（{@code showLogo = true}），
 * {@code render} 里只压/弹 {@link WallSignLogos} 的作用域，不画任何东西。所以放置、朝向、字幕、
 * 刷子切形态、相邻拼字全按 MTR 走。
 *
 * <p>logo 那张图：MTR 把它<b>烤进生成的站名贴图</b>（{@code mtr:textures/block/sign/logo.png}，
 * 命名空间写死在 {@code RouteMapGenerator.drawResource} 里），模型里没有 logo 元素。
 * {@code mixin.mtr.StationNameEntranceLogoMixin} 在作用域生效时把取图换成
 * {@code mtrlte:textures/block/<brand>_logo.png} —— 只换 logo，画布 / 落点 / 字号 / 缓存键都还是 MTR 的。
 *
 * <h2>渲染层：cutout</h2>
 * <p>MTR 那块板本身是实心 {@code mtr:block/dark_blue}，但站名贴图和我们的品牌 logo 都是挖空的 PNG，
 * 走 solid 会把空白画成黑块 —— 所以这三个方块单独设 cutout（只影响我们自己的方块）。
 */
public final class WallSignClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(WallSignObjects.entityType(),
                    context -> new WallSignRenderer(context.getRenderDispatcher()));
            LteLog.LOGGER.info("[lte] wall sign renderer registered: {} (MTR's RenderStationNameTiled)",
                    WallSignRenderer.class.getName());
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
