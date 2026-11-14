// 充电宝租借机的客户端部分：注册方块实体渲染器（原始模型）+ 投掷实体的渲染器。
package dev.js.mtrheadlight.xiamen.charger;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;

/**
 * 充电宝租借机的客户端入口。
 *
 * <ul>
 *   <li><b>方块</b>：注册 {@link ChargerRenderer}。几何来自
 *       {@code assets/mtrlte/rawmodels/charger_{top,bottom}.json}（Blockbench 原样导出的 54 / 6 个元素，
 *       上段大量元素绕 y 转 ±45°），由 BER 画；方块模型本身是 {@code mtrlte:block/empty}，
 *       没有渲染层要挂。</li>
 *   <li><b>充电宝</b>：{@link FlyingItemEntityRenderer}（就是雪球 / 鸡蛋那只渲染器）。
 *       实体实现了 {@code FlyingItemEntity}，渲染器自己从 {@code getStack()} 取物品画出来。</li>
 * </ul>
 */
public final class ChargerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(ChargerObjects.blockEntityType(), ChargerRenderer::new);
            LteLog.LOGGER.info("[lte] charger renderer registered: {} (rawmodels)",
                    ChargerRenderer.class.getSimpleName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the charger renderer", t);
        }

        try {
            EntityRendererRegistry.register(ChargerObjects.projectileType(), FlyingItemEntityRenderer::new);
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the power bank projectile renderer", t);
        }
    }
}
