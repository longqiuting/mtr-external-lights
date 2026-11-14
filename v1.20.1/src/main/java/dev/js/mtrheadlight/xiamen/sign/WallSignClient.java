// 贴墙标识的客户端接线：MTR 的站名渲染器 + cutout 渲染层（渲染器是 MTR 的，不是我们的）。
package dev.js.mtrheadlight.xiamen.sign;

import dev.js.mtrheadlight.LteLog;
import mtr.render.RenderStationNameTiled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

/**
 * 贴墙标识（{@code mtrlte:wall_sign_*}）的客户端注册。
 *
 * <h2>渲染器 = MTR 的 {@code RenderStationNameTiled}，我们一行都不画</h2>
 * <p>MTR 自己在 {@code MTRClient.init} 里给 {@code mtr:station_name_entrance} 挂的是
 * {@code new RenderStationNameTiled(dispatcher, true)}（javap 取证：四个
 * {@code RenderStationNameTiled} lambda 里只有出入口那个传 {@code iconst_1}）。那个 {@code true}
 * 是「按出入口款画」：
 * <ul>
 *   <li>沿 {@code facing.rotateYClockwise()} / {@code Counterclockwise} 数隔壁同款方块，
 *       把站名铺满整条（{@code getLength}）；</li>
 *   <li>读 {@code BlockStationNameEntrance.STYLE}：偶数 = 0.5 字号（矮板）、奇数 = 1.0（高板），
 *       并据此选字色（0/1 白、2/3 黑、4/5 白）；</li>
 *   <li>站名取 {@code RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)}，
 *       画的是 MTR 的 {@code ClientCache.getStationNameEntrance(...)} 动态贴图；</li>
 *   <li>落笔位置用方块实体里的 {@code yOffset / zOffset}（我们给的 0 / 0.00625，与 MTR 出入口款一致）。</li>
 * </ul>
 * 泛型是 {@code RenderStationNameTiled<T extends TileEntityStationNameBase>}，
 * 我们的 {@link WallSignBlockEntity} 正好是它的子类，所以能直接把 MTR 的渲染器实例注册到我们的类型上。
 *
 * <h2>渲染层：cutout</h2>
 * <p>MTR 那块板本身是不透明的 {@code dark_blue}，但我们在模型里叠的品牌 logo 是挖空的 PNG，
 * 走 solid 会把空白画成黑块 —— 所以这三个方块单独设 cutout（只影响我们自己的方块）。
 */
public final class WallSignClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(WallSignObjects.entityType(),
                    context -> new RenderStationNameTiled<WallSignBlockEntity>(context.getRenderDispatcher(), true));
            LteLog.LOGGER.info("[lte] wall sign renderer registered: {} (MTR's own)",
                    RenderStationNameTiled.class.getName());
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
