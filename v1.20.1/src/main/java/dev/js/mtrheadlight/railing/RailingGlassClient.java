// 玻璃扶手的客户端部分：把 11 个玻璃栏杆方块挂到 translucent 渲染层。
package dev.js.mtrheadlight.railing;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

/**
 * 玻璃扶手 {@code mtrlte:railing_glass_*} 的渲染层。
 *
 * <p>贴图 {@code assets/mtrlte/textures/block/railing_stair_glass.png}（1024×1024，RGBA）
 * 的玻璃部分带半透明像素，挂 {@code solid} 会被画成不透明白块，所以和 MSD 的玻璃栏杆、
 * 以及本模组的 {@code liquid_scanner} / 屏蔽门那一族一样改走渲染层：
 * <b>玻璃栏杆用 {@link RenderLayer#getTranslucent()}</b>（MSD 的 {@code RAILING_STAIR_GLASS*}
 * 也是 {@code getTranslucent}；半透明渐变的玻璃柱用 cutout 会变成硬边）。
 *
 * <p>和 {@code SecurityClient} / {@code XiamenClient} 同样的前提：方块由 {@code main} 入口
 * （{@link RailingGlassBlocks#register()}）注册，Fabric 先跑 main 再跑 client，所以这里拿得到实例。
 * 整段包在 try 里 —— 渲染层没挂上只是玻璃难看，不影响方块本身能用。
 */
public final class RailingGlassClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            for (final RailingWallBlock block : RailingGlassBlocks.blocks()) {
                BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getTranslucent());
            }
        } catch (Throwable ignored) {
            // Fabric API 版本不对就静默失效：玻璃会按 solid 画（不透明），方块照常能放。
        }
    }
}
