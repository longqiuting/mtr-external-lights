// 饮料售卖机的客户端部分：注册方块实体渲染器（三份原始模型）。
package dev.js.mtrheadlight.xiamen.drinks;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

/**
 * 饮料售卖机的客户端入口。
 *
 * <p>只做一件事：给 {@link DrinksMachineObjects#blockEntityType()} 挂上
 * {@link DrinksMachineRenderer}。几何来自
 * {@code assets/mtrlte/rawmodels/drinks_machine_{top,bottom_closed,bottom_opened}.json}
 * （Blockbench 原样导出：43 / 36 / 36 个元素，大量 22.5° 斜玻璃层 + 绕 y 的旋转），
 * 原版 blockstate 模型表达不了，所以走 BER；方块模型本身是 {@code mtrlte:block/empty}，
 * 没有渲染层要挂（渲染器自己取 {@code getEntityCutout}）。
 */
public final class DrinksMachineClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            BlockEntityRendererRegistry.register(DrinksMachineObjects.blockEntityType(), DrinksMachineRenderer::new);
            LteLog.LOGGER.info("[lte] drinks machine renderer registered: {} (rawmodels)",
                    DrinksMachineRenderer.class.getSimpleName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the drinks machine renderer", t);
        }
    }
}
