package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.MtrHeadlightClient;
import dev.js.mtrheadlight.net.ClientHandshake;
import dev.js.mtrheadlight.server.ServerConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 碰撞子系统的入口点（{@code fabric.mod.json} 的 {@code client} 数组，MC 1.20.1 独有）。
 *
 * <p>三件事：
 * <ul>
 *   <li>{@link CollisionSystem#tick()} → {@code END_CLIENT_TICK}（tick 计数、诊断行、车顶动画）；</li>
 *   <li>{@link MobCollision#serverTick} → {@code END_SERVER_TICK}（其它生物在<b>整合服务器</b>上
 *       结算；dedicated server 上由 {@code server.ServerCollision} 接管，两边不会同时跑）；</li>
 *   <li>客户端的一侧装配：把活配置交给服务端 tick（单人的服务端解算要用玩家刚在配置界面里改的值），
 *       并注册 {@code mtrlte:hello} 握手（判断对端服务端是否权威）。</li>
 * </ul>
 *
 * <p>真正的玩家解算在 {@code MixinEntityMove} 里（{@code Entity#move}）。
 *
 * <p>单独一个入口类而不是塞进 {@code MtrHeadlightClient}：那个类在 {@code common} 里，被 1.18.2 /
 * 1.19.2 / 1.19.4 三个版本共用，而碰撞只做 1.20.1。
 */
public final class CollisionBootstrap implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 单人：服务端 tick 与客户端在同一个进程，配置以「活对象」为准（配置界面改的是它）。
        ServerConfig.install(MtrHeadlightClient::config);
        ClientHandshake.register();
        installImpulseMirror();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                CollisionSystem.tick();
            } catch (Throwable t) {
                // 诊断/计数出错不影响游戏。
            }
        });
        // 生物碰撞在整合服务器上跑（客户端改不动生物的位置与血量），见 MobCollision。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                MobCollision.serverTick(server);
            } catch (Throwable t) {
                // 生物碰撞出错不影响游戏。
            }
        });
    }

    /**
     * 撞飞冲量的<b>客户端镜像</b>：每 tick 的位置是客户端权威的，服务端 {@code ServerPlayerEntity}
     * 上的 {@code setVelocity} 不会发给玩家本人（只发给其他玩家），于是服务端算出的 5–10 格撞飞
     * 在画面上只剩「被车顶着走」那一两格。这里把同一组速度写进本进程的 {@code ClientPlayerEntity}。
     *
     * <p>写的是本进程自己的玩家（UUID 相同才算），所以多人（服务端是别人的）时什么都不做 ——
     * 那一路由服务端的速度包负责。从服务端线程调用，所以走 {@code client.execute} 回客户端线程。
     */
    private static void installImpulseMirror() {
        CollisionImpact.installImpulseMirror((id, x, y, z) -> {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || id == null) {
                return;
            }
            client.execute(() -> {
                try {
                    final PlayerEntity self = client.player;
                    if (self == null || !id.equals(self.getUuid())) {
                        return;
                    }
                    self.setVelocity(x, y, z);
                    self.velocityModified = true;
                    // 被撞飞不是摔下来：清掉落体距离，免得落地多一条「摔死了」盖掉我们的死亡文案。
                    self.fallDistance = 0.0F;
                } catch (Throwable ignored) {
                    // 镜像失败只影响手感。
                }
            });
        });
    }
}
