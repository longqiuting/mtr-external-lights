package dev.js.mtrheadlight.net;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.MtrHeadlightClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

/**
 * 客户端的一半握手。只在 <b>client</b> 入口注册（dedicated server 上不存在这个类）。
 *
 * <h2>时序</h2>
 * <pre>
 * JOIN           -> 发 hello（服务端通道表可能还没到，所以前 {@link #RETRY_TICKS} tick 里重试）
 * 收到应答        -> ServerAuthority.acceptRemote(...) + 与本地配置比对，不一致打一行 warn
 * DISCONNECT     -> ServerAuthority.clearRemote()
 * </pre>
 *
 * <p>服务端没装 LTE / 老版本：没有人回包，{@code ServerAuthority} 一直是 false，客户端维持原来的
 * 「本地只做受击表现」行为 —— 纯客户端模式不需要任何改动。
 */
public final class ClientHandshake {

    /** JOIN 之后最多重试这么多 tick（0.25 s）。服务端的通道声明在登录阶段就到，通常第 1 次就发出去了。 */
    private static final int RETRY_TICKS = 5;

    private static boolean registered;

    /** 还需要再试几次 / 还没收到应答。 */
    private static int retries;
    private static boolean answered;

    private ClientHandshake() {
    }

    /** client 入口调用一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            ClientPlayNetworking.registerGlobalReceiver(LteChannel.ID,
                    (client, handler, buf, responseSender) -> {
                        final LteChannel.ServerHello hello = LteChannel.ServerHello.read(buf);
                        client.execute(() -> accept(hello));
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] could not register the client handshake receiver", t);
        }
        try {
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                answered = false;
                retries = RETRY_TICKS;
                sendHello();
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the join event", t);
        }
        try {
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                    ServerAuthority.clearRemote());
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the disconnect event", t);
        }
        try {
            // 通道表晚到的兜底：JOIN 那一 tick 还没声明就再试几次。
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (answered || retries <= 0) {
                    return;
                }
                retries--;
                sendHello();
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the client tick", t);
        }
    }

    private static void sendHello() {
        try {
            if (ClientPlayNetworking.canSend(LteChannel.ID)) {
                final PacketByteBuf buf = PacketByteBufs.create();
                LteChannel.writeHello(buf);
                ClientPlayNetworking.send(LteChannel.ID, buf);
            }
        } catch (Throwable t) {
            // 服务端不认这个通道 / 不在游戏里：保持纯客户端模式即可。
        }
    }

    private static void accept(final LteChannel.ServerHello hello) {
        if (hello == null) {
            return;
        }
        answered = true;
        retries = 0;
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final boolean before = ServerAuthority.authoritative();
        ServerAuthority.acceptRemote(hello);
        if (!before && ServerAuthority.authoritative()) {
            LteLog.LOGGER.info("[lte] server-side collision is authoritative "
                            + "(collision={} damage={} mobs={} high={}km/h pushOnly={}km/h) - "
                            + "this client will no longer apply collision damage itself",
                    Boolean.valueOf(hello.trainCollision),
                    Boolean.valueOf(hello.damage),
                    Boolean.valueOf(hello.mobs),
                    Integer.valueOf(hello.highDamageMaxKmh),
                    Integer.valueOf(hello.pushOnlyMaxKmh));
        }
        ServerAuthority.warnIfMismatch(cfg.trainCollisionDamage,
                cfg.trainCollisionHighDamageMaxKmhClamped());
    }
}
