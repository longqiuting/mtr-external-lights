package dev.js.mtrheadlight.net;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 服务端的一半握手：收 {@code hello}，回 {@code ServerHello}。只在 {@code main} 入口注册。
 *
 * <p>服务端<b>不需要</b>知道客户端装没装：包只是单向下发「我在算，别自己扣血」。客户端没装 LTE 时
 * 根本不会发 hello，这里也就不会被触发；服务端没装 LTE 时客户端发出去的 hello 没人收，客户端在
 * 超时后回到纯客户端模式。
 *
 * <p>所有回调都包在 try/catch 里：一个坏包不能让服务端掉线。
 */
public final class ServerHandshake {

    private static boolean registered;

    private ServerHandshake() {
    }

    /** {@code main} 入口调用一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            ServerPlayNetworking.registerGlobalReceiver(LteChannel.ID,
                    (server, player, handler, buf, responseSender) -> {
                        final int version = LteChannel.readHelloVersion(buf);
                        if (version != LteChannel.VERSION) {
                            // 版本不同的客户端：不发应答，它那边会超时回到纯客户端模式。
                            return;
                        }
                        // 网络线程读包、主线程发应答。
                        server.execute(() -> {
                            try {
                                reply(server, player);
                            } catch (Throwable t) {
                                LteLog.LOGGER.debug("[lte] could not answer the handshake", t);
                            }
                        });
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] could not register the server handshake", t);
        }
    }

    /** 玩家进服时主动推一次（客户端不必先问；它没装 LTE 就跳过）。 */
    public static void announce(final MinecraftServer server, final ServerPlayerEntity player) {
        if (server == null || player == null) {
            return;
        }
        try {
            reply(server, player);
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not announce server authority", t);
        }
    }

    private static void reply(final MinecraftServer server, final ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return;
        }
        // 客户端没声明这个通道 = 没装 LTE（或版本不认）：不发，避免原版服务端刷
        // "Unknown custom packet payload"。
        if (!ServerPlayNetworking.canSend(player, LteChannel.ID)) {
            return;
        }
        final HeadlightConfig cfg = dev.js.mtrheadlight.server.ServerConfig.get();
        final boolean collision = cfg.trainCollision;
        final boolean damage = collision && cfg.trainCollisionDamage;
        final LteChannel.ServerHello hello = new LteChannel.ServerHello(
                LteChannel.VERSION,
                collision,
                damage,
                cfg.trainCollisionMobs,
                cfg.trainCollisionHighDamageMaxKmhClamped(),
                cfg.trainCollisionPushOnlyMaxKmhClamped());
        final PacketByteBuf buf = PacketByteBufs.create();
        hello.write(buf);
        ServerPlayNetworking.send(player, LteChannel.ID, buf);
        LteLog.LOGGER.debug("[lte] told {} who is authoritative (damage={})",
                player.getGameProfile().getName(), Boolean.valueOf(damage));
    }
}
