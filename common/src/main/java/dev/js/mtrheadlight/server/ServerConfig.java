package dev.js.mtrheadlight.server;

import dev.js.mtrheadlight.HeadlightConfig;

import java.util.function.Supplier;

/**
 * 服务端 tick 读哪一份配置。
 *
 * <p>两端的配置来源不同，而服务端代码<b>不能</b>直接引用客户端类：
 * <ul>
 *   <li>dedicated server：{@link LteServer#onInitialize()} 读 {@code config/mtrlte.json} 得到自己
 *       的一份，进程里再没有别人改它；</li>
 *   <li>单人 / 局域网主机：客户端初始化后由 {@code CollisionBootstrap} 调 {@link #install} 把
 *       {@code MtrHeadlightClient::config} 装进来 —— 玩家在配置界面里改的数值立刻就是服务端
 *       解算用的数值（配置界面改的是活对象，不是文件）。</li>
 * </ul>
 *
 * <p>默认值是 dedicated server 自己加载的那份；客户端<b>只在客户端</b>覆盖它，所以 dedicated
 * server 永远走不到任何客户端类（这里连类名都没有）。
 */
public final class ServerConfig {

    private static volatile Supplier<HeadlightConfig> supplier;
    private static final Supplier<HeadlightConfig> FALLBACK = HeadlightConfig::defaults;

    private ServerConfig() {
    }

    /** dedicated server 用自己加载的那一份。 */
    public static void install(final Supplier<HeadlightConfig> provider) {
        if (provider != null) {
            supplier = provider;
        }
    }

    public static HeadlightConfig get() {
        final Supplier<HeadlightConfig> s = supplier;
        final HeadlightConfig cfg = s == null ? null : s.get();
        return cfg == null ? FALLBACK.get() : cfg;
    }
}
