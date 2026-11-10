package dev.js.mtrheadlight.server;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.net.ServerAuthority;
import dev.js.mtrheadlight.net.ServerHandshake;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * {@code main} 入口点 —— <b>服务端安全</b>，不引用任何客户端类。
 *
 * <h2>它负责什么</h2>
 * <ol>
 *   <li>读本侧配置（dedicated server 的 {@code config/mtrlte.json}）；</li>
 *   <li>标记「本进程里有 LTE 的服务端」→ 客户端的撞击伤害自动让位（{@link ServerAuthority}）；</li>
 *   <li>挂 {@code END_SERVER_TICK} → {@link ServerCollision#tick}
 *       （dedicated server 与单人整合服务器都走这里）；</li>
 *   <li>握手：玩家进服时告诉装了 LTE 的客户端「撞击由我结算」。</li>
 * </ol>
 *
 * <h2>纯客户端模式</h2>
 * 这个入口在客户端进程里也会被 Fabric 加载（{@code environment="*"} + 整合服务器需要它），
 * 所以客户端进单人游戏时服务端权威立刻成立，客户端那边的 {@code CollisionSystem} 只负责推出 /
 * 车顶 / 表现，扣血只有一份。连别人的服务器时本类的服务端 tick 不存在，客户端靠握手判断。
 *
 * <h2>失败模式</h2>
 * 每一个 MTR / 事件注册调用都包在 try/catch 里：MTR 没装、版本不匹配、数据未就绪都只是让
 * {@link ServerCollision} 什么都不做（它会自己再挡一层），绝不让服务端起不来。
 */
public final class LteServer implements ModInitializer {

    /** 本侧配置。dedicated server 上就是文件里的那一份。 */
    private static volatile HeadlightConfig config = new HeadlightConfig();

    public static HeadlightConfig config() {
        return config;
    }

    @Override
    public void onInitialize() {
        try {
            config = HeadlightConfig.load();
            ServerConfig.install(LteServer::config);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] server config unavailable - using defaults", t);
        }
        // 「本进程是 dedicated server」—— 客户端的撞击伤害自动让位（没有客户端，无所谓）。
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.SERVER) {
            ServerAuthority.markDedicated();
        }

        try {
            ServerHandshake.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] handshake unavailable", t);
        }

        // LTE 核心物品 + 创造页签（物品注册必须在两端都跑，所以放在 main）。
        try {
            dev.js.mtrheadlight.signal.LteCoreItems.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] connector items unavailable", t);
        }

        // /ltebind：投影机 / PIDS 绑定表的查询（服务端，读 PersistentState）。
        try {
            dev.js.mtrheadlight.bind.LteBindCommand.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] /ltebind unavailable", t);
        }

        // 厦门风格物件页签 + 屏蔽门方块（同样是两端都需要的 registry 条目）。
        try {
            dev.js.mtrheadlight.xiamen.XiamenObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen objects unavailable", t);
        }

        try {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                try {
                    // 进服：把玩家所在维度的两张表都同步过去（渲染要它）。
                    final net.minecraft.server.network.ServerPlayerEntity player = handler.player;
                    final net.minecraft.server.world.ServerWorld world = player.getServerWorld();
                    final dev.js.mtrheadlight.signal.SignalLinkStore store =
                            dev.js.mtrheadlight.signal.SignalLinkStore.get(world);
                    dev.js.mtrheadlight.signal.SignalLinkNet.sendAll(player,
                            dev.js.mtrheadlight.signal.SignalLinkNet.dimensionOf(world),
                            store.links(), store.lampLinks());
                } catch (Throwable ignored) {
                    // 同步失败只是绑定的信号灯不亮，不影响进服。
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the signal-link sync", t);
        }

        try {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                try {
                    ServerHandshake.announce(server, handler.player);
                } catch (Throwable ignored) {
                    // 握手只是优化，失败不影响游戏。
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the join event", t);
        }

        try {
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                try {
                    ServerCollision.tick(server);
                } catch (Throwable t) {
                    // 服务端碰撞永远不许影响 tick（MTR 数据异常、类缺失都在这里被吞掉）。
                    ServerCollision.warnOnce(t);
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] server collision unavailable", t);
        }

        LteLog.LOGGER.info("[lte] server initialised "
                        + "(collision={} damage={} mobs={})",
                Boolean.valueOf(config.trainCollision),
                Boolean.valueOf(config.trainCollisionDamage),
                Boolean.valueOf(config.trainCollisionMobs));
    }
}
