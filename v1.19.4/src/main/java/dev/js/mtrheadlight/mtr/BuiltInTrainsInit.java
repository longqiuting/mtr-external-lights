package dev.js.mtrheadlight.mtr;

import dev.js.mtrheadlight.MtrHeadlightClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 1.20.1 专属的第二个 client entrypoint：内置车型过滤的 tick 钩子。
 *
 * <p>单独一个 entrypoint 而不是塞进 {@code MtrHeadlightClient}（它在 {@code common} 里，四个版本
 * 共用）：{@link BuiltInTrains} 直接引用 {@code mtr.client.TrainClientRegistry}，只有 1.20.1 的
 * {@code build.gradle} 把 MTR 3.6.4.2 放进编译期 classpath。放在版本专属的 source set 里，
 * 其它三个版本既不会编译它、也不会注册它。
 *
 * <p>先于 / 后于主 initializer 都无所谓：两者都只在启动时注册回调，真正的配置读取发生在
 * 第一个 tick 之前（{@code MtrHeadlightClient#onInitializeClient}）。
 */
public final class BuiltInTrainsInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                BuiltInTrains.tick(MtrHeadlightClient.config(), client);
            } catch (Throwable t) {
                // 注册表操作失败最多是"内置车没被隐藏"，绝不能让客户端崩。
                MtrHeadlightClient.LOGGER.warn("[lte] built-in train tick failed", t);
            }
        });
    }
}
