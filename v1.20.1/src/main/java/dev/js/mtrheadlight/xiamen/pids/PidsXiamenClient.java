// 厦门 PIDS 的客户端接线：方块实体渲染器 + 裁切渲染层 + 广告表重载监听。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

/**
 * 厦门 PIDS 的客户端注册（{@code fabric.mod.json} 的 {@code client} 入口）。
 *
 * <ul>
 *   <li><b>渲染器</b>：{@link PidsXiamenRenderer}，绑到唯一的方块实体类型
 *       {@code mtrlte:pids_xiamen}（地铁 / BRT 两块共用，kind 从方块实例上取）；</li>
 *   <li><b>渲染层</b>：{@code RenderLayer.getCutout()} —— 与 MTR 自己的 PIDS 一致。
 *       模型里 {@code metal.png} 是带 alpha 的，走 solid 会把透明像素画黑；</li>
 *   <li><b>广告表</b>：挂一个资源重载监听，换资源包 / F3+T 之后
 *       {@link PidsXiamenAds#invalidate()} 一下，下次渲染重新读
 *       {@code assets/mtrlte/pids_xiamen_ads.json}。</li>
 * </ul>
 *
 * <p>每一步各自 try/catch：某一步失败只跳过它自己（渲染器没注册 = 只有空屏，不该连累广告表）。
 */
public final class PidsXiamenClient implements ClientModInitializer {

    private static final Identifier RELOAD_ID = new Identifier(PidsXiamenObjects.NAMESPACE, "pids_xiamen_ads");

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(PidsXiamenObjects.entityType(), PidsXiamenRenderer::new);
            LteLog.LOGGER.info("[lte] xiamen pids renderer registered: {}", PidsXiamenRenderer.class.getName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the xiamen pids renderer", t);
        }

        try {
            BlockRenderLayerMap.INSTANCE.putBlock(PidsXiamenObjects.metro(), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(PidsXiamenObjects.brt(), RenderLayer.getCutout());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] could not set the cutout layer for the xiamen pids blocks", t);
        }

        try {
            ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new AdsListener());
        } catch (Throwable t) {
            // 没有 resource-loader API 也能用：广告表会在第一次渲染时懒加载，只是换包要重启。
            LteLog.LOGGER.warn("[lte] xiamen pids ads: reload listener unavailable", t);
        }

        // 刷子界面：服务端发「打开」，这里回到主线程 setScreen（网络线程碰 GUI 会崩）。
        try {
            ClientPlayNetworking.registerGlobalReceiver(PidsXiamenNet.ID, (client, handler, buf, sender) -> {
                try {
                    if (!PidsXiamenNet.isOpen(PidsXiamenNet.readKind(buf))) {
                        return;
                    }
                    final PidsXiamenNet.OpenPacket packet = PidsXiamenNet.readOpen(buf);
                    client.execute(() -> client.setScreen(
                            new PidsXiamenConfigScreen(packet.dataPos(), packet.partnerPos())));
                } catch (Throwable t) {
                    LteLog.LOGGER.warn("[lte] xiamen pids: bad open packet", t);
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not register the client receiver", t);
        }
    }

    /** 资源重载 → 丢掉广告表缓存。 */
    private static final class AdsListener implements SimpleResourceReloadListener<Void> {

        @Override
        public Identifier getFabricId() {
            return RELOAD_ID;
        }

        @Override
        public CompletableFuture<Void> load(final ResourceManager manager, final Profiler profiler,
                                            final Executor executor) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> apply(final Void data, final ResourceManager manager,
                                             final Profiler profiler, final Executor executor) {
            try {
                PidsXiamenAds.invalidate();
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] xiamen pids ads: could not invalidate the cache", t);
            }
            try {
                // 标语表也一样：换资源包 / F3+T 之后下次渲染重新读 pids_xiamen_ticker.json
                PidsXiamenTicker.invalidate();
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] xiamen pids ticker: could not invalidate the cache", t);
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
