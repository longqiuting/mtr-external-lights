// 时钟的客户端部分：注册渲染器 + 收「开界面」/「配置变更」/「全局快照」包 + 把配置发回去。
package dev.js.mtrheadlight.clock;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 时钟的客户端入口，四件事：
 *
 * <ol>
 *   <li><b>渲染器</b>：给 {@code mtrlte:clock} 挂 {@link ClockRenderer}（钟面全靠它画）；</li>
 *   <li><b>配置界面</b>：收服务端 {@code mtrlte:clock_cfg} 的 OPEN 包开
 *       {@link ClockConfigScreen}，界面里点「完成」/「试听」时把配置发回去；</li>
 *   <li><b>配置变更</b>：收同一个通道的 {@code KIND_SYNC}，把新配置写进<b>客户端这一份</b>方块实体
 *       —— 这一步不做，界面改完点完成，钟的样子一点不会变（渲染器读的就是客户端这份）；</li>
 *   <li><b>全局配置</b>：收 {@code mtrlte:clock_global} 的快照写进 {@link ClockGlobalCache}；
 *       断线时清掉。</li>
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
                final byte kind;
                final BlockPos pos;
                final ClockConfig cfg;
                try {
                    kind = buf.readByte();
                    pos = buf.readBlockPos();
                    cfg = kind == ClockNet.KIND_OPEN || kind == ClockNet.KIND_SYNC
                            ? ClockConfig.readBuf(buf) : null;
                } catch (Throwable bad) {
                    return; // 坏包：什么也不做。
                }
                // 网络回调在 Netty 线程，开界面 / 改方块实体都必须回到客户端主线程。
                client.execute(() -> {
                    if (kind == ClockNet.KIND_OPEN) {
                        client.setScreen(new ClockConfigScreen(pos, cfg));
                    } else if (kind == ClockNet.KIND_SYNC) {
                        applySynced(client, pos, cfg);
                    }
                });
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] clock config channel (client side) unavailable", t);
        }

        try {
            ClientPlayNetworking.registerGlobalReceiver(ClockGlobalNet.ID, (client, handler, buf, sender) -> {
                final ClockGlobalConfig cfg;
                try {
                    if (buf.readByte() != ClockGlobalNet.KIND_SNAPSHOT) {
                        return; // 只认快照。
                    }
                    cfg = ClockGlobalConfig.readBuf(buf);
                } catch (Throwable bad) {
                    return;
                }
                client.execute(() -> ClockGlobalCache.set(cfg));
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] clock global channel (client side) unavailable", t);
        }

        try {
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClockGlobalCache.clear());
        } catch (Throwable ignored) {
            // 清缓存失败最多是「上一台的全局配置还留着」，下次收到快照就覆盖。
        }
    }

    /**
     * 服务端推来的新配置 → 客户端这一份方块实体。
     *
     * <p>找不到方块实体（区块还没到 / 刚好被拆）就什么也不做：区块数据里本来就带着配置，
     * 下一帧渲染出来的仍然是存档里的样子。
     */
    private static void applySynced(final MinecraftClient client, final BlockPos pos,
                                    final ClockConfig cfg) {
        final World world = client.world;
        if (world == null || pos == null || cfg == null) {
            return;
        }
        if (world.getBlockEntity(pos) instanceof ClockBlockEntity entity) {
            entity.applyClientConfig(cfg);
            LteLog.LOGGER.debug("[lte] clock cfg synced at {}: face=#{}", pos,
                    Integer.toHexString(cfg.faceColor));
        }
    }

    // ------------------------------------------------------------------ 发

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

    /** 全局配置「完成」：整份 + 启用开关发回服务端（服务端校验权限 ≥ 2）。 */
    public static void sendGlobalSave(final ClockGlobalConfig cfg) {
        try {
            if (!ClientPlayNetworking.canSend(ClockGlobalNet.ID)) {
                return;
            }
            ClientPlayNetworking.send(ClockGlobalNet.ID, ClockGlobalNet.savePacket(cfg));
        } catch (Throwable ignored) {
            // 发不出去就当没保存。
        }
    }

    /** 全局配置「试听」：让服务端在我这个位置播一下（全局没有方块可站）。 */
    public static void sendGlobalPreview() {
        try {
            if (!ClientPlayNetworking.canSend(ClockGlobalNet.ID)) {
                return;
            }
            ClientPlayNetworking.send(ClockGlobalNet.ID, ClockGlobalNet.previewPacket());
        } catch (Throwable ignored) {
            // 试听失败无所谓。
        }
    }

    /** 打开「全局模式」的配置界面（仪表板「其他 → 时钟全局设置」用）。 */
    public static void openGlobalScreen(final MinecraftClient client) {
        if (client == null) {
            return;
        }
        final ClockGlobalConfig cfg = ClockGlobalCache.get();
        client.setScreen(new ClockConfigScreen(null, cfg));
    }
}
