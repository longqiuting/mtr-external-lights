// Xiamen client setup: render layers + block entity renderers (MTR's own renderers, no local copies).
package dev.js.mtrheadlight.xiamen;

import dev.js.mtrheadlight.LteLog;
import mtr.render.RenderAPGGlass;
import mtr.render.RenderPSDTop;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;

/**
 * 厦门物件的客户端部分：挂渲染层 + 注册两个方块实体的渲染器。
 *
 * <ul>
 *   <li>门扇：{@link RenderPsdDoor} —— <b>继承 MTR 的 {@code RenderPSDAPGDoor}</b>（type 0 = PSD），
 *       几何 / 盒子 UV / 贴图 id / 门灯 / 开合动画全部是 MTR 那一份代码，只有方块实体是我们自己的；</li>
 *   <li>吊板：<b>MTR 自己的 {@code RenderPSDTop}</b> —— 站名 / 线路图 / 色带 / 方向箭头
 *       就是 MTR 那套运行时动态绘制（数据源 {@code ClientData.PLATFORMS} +
 *       {@code ClientData.DATA_CACHE}，经 {@code TileEntityRouteBase#getPlatformId} 定位站台）。
 *       它写死了泛型参数 {@code BlockPSDTop.TileEntityPSDTop}，而我们的方块实体继承的是
 *       {@code TileEntityRouteBase}（为了能用自己注册的 BlockEntityType），所以注册时要做一次
 *       不检查的向下转型；运行期 {@code RenderRouteBase} 只按 {@code TileEntityRouteBase} 调
 *       {@code getPlatformId}，是安全的。</li>
 *   <li>半高 APG 玻璃：<b>MTR 自己的 {@code RenderAPGGlass}</b> —— 和吊板<b>同一个</b>
 *       {@code RenderRouteBase} 基类和同一份 {@code RouteMapGenerator} 数据，只是几何参数不同
 *       （{@code z=4/16, topPadding=8/16, bottomPadding=4/16, sidePadding=8/16}），
 *       并且只画在<b>上段</b>（下段 {@code getRenderType} 直接返回 {@code NONE}）。</li>
 * </ul>
 *
 * <p>渲染层与 MTR 自己的 {@code MTRClient} 一致：屏蔽门那一族（门 / 玻璃 / 端部 / 吊板）都是
 * {@code RenderLayer.getCutout()}。这些贴图中间是 alpha=0（玻璃），挂 solid 层会把透明像素画成黑色。
 *
 * <p><b>每一步各自 try/catch</b>：以前整段套一个 try，前面某个 {@code putBlock} 抛异常就把后面的
 * BER 注册一起吞掉，症状正是「门扇整片透明（渲染器根本没注册）」。现在每一步失败只会跳过它自己，
 * 并且都会在日志里留下一行。
 */
public final class XiamenClient implements ClientModInitializer {

    @Override
    @SuppressWarnings("unchecked")
    public void onInitializeClient() {
        // 1) 门扇渲染器 —— 最先注册，绝不因为别的东西抛异常而被跳过。
        try {
            BlockEntityRendererRegistry.register(XiamenObjects.psdDoorEntityType(), RenderPsdDoor::new);
            LteLog.LOGGER.info("[lte] xiamen door renderer registered: {}", RenderPsdDoor.class.getName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the xiamen door renderer", t);
        }

        // 2) 吊板渲染器 —— MTR 自己的 RenderPSDTop。
        try {
            BlockEntityRendererRegistry.register(XiamenObjects.psdTopEntityType(), context ->
                    (BlockEntityRenderer<XiamenPsdBlocks.TileEntityXiamenPSDTop>) (Object)
                            new RenderPSDTop(context.getRenderDispatcher()));
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the xiamen psd top renderer", t);
        }

        // 3) 半高 APG 门扇渲染器 —— 同样是 MTR 的 RenderPSDAPGDoor，只是 type = 2。
        try {
            BlockEntityRendererRegistry.register(XiamenObjects.apgDoorEntityType(), RenderPsdApgDoor::new);
            LteLog.LOGGER.info("[lte] xiamen apg door renderer registered: {}", RenderPsdApgDoor.class.getName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the xiamen apg door renderer", t);
        }

        // 4) 半高 APG 玻璃渲染器 —— <b>MTR 自己的 RenderAPGGlass</b>（线路图 + 色带 + 站名 + 箭头）。
        //    它写死泛型参数 BlockAPGGlass.TileEntityAPGGlass，而我们的方块实体继承的是
        //    TileEntityRouteBase（为了能用自己注册的 BlockEntityType），所以注册时做一次不检查的
        //    向下转型 —— 和上面 RenderPSDTop 同一个道理：RenderRouteBase 只会按
        //    TileEntityRouteBase 调 getPlatformId()，运行期是安全的。
        try {
            BlockEntityRendererRegistry.register(XiamenObjects.apgGlassEntityType(), context ->
                    (BlockEntityRenderer<XiamenPsdBlocks.TileEntityXiamenAPGGlass>) (Object)
                            new RenderAPGGlass(context.getRenderDispatcher()));
            LteLog.LOGGER.info("[lte] xiamen apg glass route map renderer registered: {}",
                    RenderAPGGlass.class.getName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the xiamen apg glass renderer", t);
        }

        // 5) 方块渲染层（照 MTR 的 MTRClient：PSD_DOOR / APG_GLASS / PSD_TOP 全部是 cutout）。
        put(XiamenObjects.psdDoor(), "psd door");
        put(XiamenObjects.psdGlass(), "psd glass");
        put(XiamenObjects.psdEnd(), "psd end");
        put(XiamenObjects.psdTop(), "psd top");
        put(XiamenObjects.apgDoor(), "apg door");
        put(XiamenObjects.apgGlass(), "apg glass");
        put(XiamenObjects.apgEnd(), "apg end");
        put(XiamenObjects.logo(), "logo");
    }

    private static void put(net.minecraft.block.Block block, String what) {
        try {
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] could not set the cutout layer for the xiamen {} block", what, t);
        }
    }
}
