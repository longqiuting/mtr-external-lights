// 时钟的客户端部分：注册渲染器 + 收「开界面」包 + 把配置发回去。
package dev.js.mtrheadlight.clock;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.util.math.BlockPos;

/**
 * 时钟的客户端入口，两件事：
 *
 * <ol>
 *   <li><b>渲染器</b>：给 {@code mtrlte:clock} 挂 {@link ClockRenderer}（钟面全靠它画）；</li>
 *   <li><b>配置界面</b>：收服务端 {@code mtrlte:clock_cfg} 的 OPEN 包开
 *       {@link ClockConfigScreen}，界面里点「完成」/「试听」时把配置发回去。</li>
 * </ol>
 *
 * <p>由 {@code XiamenClient} 调 {@link #register()}（与其它方块渲染器的入口一致）。
 * 每一步各自 try/catch：渲染器没挂上只是钟面看不见，界面通道没通只是刷子右键不弹窗，
 * 都不影响别的方块。
 */
public final class ClockClient {

    private static boolean registered;

    private ClockClient() {
    }

    /** 客户端初始化时调一次（{@code XiamenClient#onInitializeClient}）。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        try {
            if (ClockObjects.entityType() != null) {
                BlockEntityRendererRegistry.register(ClockObjects.entityType(), ClockRenderer::new);
                LteLog.LOGGER.info("[lte] clock renderer registered ({}-block render distance)",
                        Integer.valueOf(ClockRenderer.RENDER_DISTANCE));
            }
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the clock renderer", t);
        }

        try {
            ClientPlayNetworking.registerGlobalReceiver(ClockNet.ID, (client, handler, buf, sender) -> {
                final BlockPos pos;
                final ClockConfig cfg;
                try {
                    if (buf.readByte() != ClockNet.KIND_OPEN) {
                        return; // 只认 OPEN。
                    }
                    pos = buf.readBlockPos();
                    cfg = ClockConfig.readBuf(buf);
                } catch (Throwable bad) {
                    return; // 坏包：不开界面，也不报错。
                }
                // 网络回调在 Netty 线程，开界面必须回到客户端主线程。
                client.execute(() -> client.setScreen(new ClockConfigScreen(pos, cfg)));
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] clock config channel (client side) unavailable", t);
        }
    }

    /** 把整份配置发回服务端（「完成」/「试听」按钮调，本来就在主线程）。 */
    public static void sendSave(final BlockPos pos, final ClockConfig cfg) {
        try {
            if (!ClientPlayNetworking.canSend(ClockNet.ID)) {
                return;
            }
            ClientPlayNetworking.send(ClockNet.ID, ClockNet.savePacket(pos, cfg));
        } catch (Throwable ignored) {
            // 发不出去就当没保存，界面照常关闭。
        }
    }

    /** 让服务端在这块钟的位置播一下报时音（试听）。 */
    public static void sendPreview(final BlockPos pos) {
        try {
            if (!ClientPlayNetworking.canSend(ClockNet.ID)) {
                return;
            }
            ClientPlayNetworking.send(ClockNet.ID, ClockNet.previewPacket(pos));
        } catch (Throwable ignored) {
            // 试听失败无所谓。
        }
    }
}
