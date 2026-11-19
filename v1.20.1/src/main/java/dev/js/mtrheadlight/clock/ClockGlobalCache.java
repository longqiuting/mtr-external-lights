// 客户端镜像：全局时钟配置的只读缓存（渲染器每帧读它）。
package dev.js.mtrheadlight.clock;

/**
 * 客户端这一侧的全局配置镜像。
 *
 * <h2>为什么单独一个类、而且不 import 任何客户端类</h2>
 * <p>{@link ClockBlockEntity#effectiveConfig()} 两端都要调，而它要读「全局配置」——
 * 服务端读 {@link ClockGlobalState}，客户端读这里。如果把这个静态字段塞进客户端类，
 * 服务端加载方块实体时就会去加载客户端类。所以这里只放一个
 * {@code volatile ClockGlobalConfig}，谁都能安全加载。
 *
 * <h2>什么时候更新</h2>
 * <ul>
 *   <li>进服（服务端推一份快照）；</li>
 *   <li>管理员在仪表板改了全局配置（服务端 {@link ClockGlobalNet#applySave} 之后广播）；</li>
 *   <li>断线时清掉（{@link #clear()}），换服务器不会带着上一台的设置。</li>
 * </ul>
 *
 * <p>写入都在客户端主线程（网络回调里 {@code client.execute} 之后），读在渲染线程 ——
 * {@code volatile} 保证看到的是完整对象（要么旧要么新，不会半份）。
 */
public final class ClockGlobalCache {

    /** 还没收到快照时 = null，按「全局关闭」处理。 */
    private static volatile ClockGlobalConfig current;

    private ClockGlobalCache() {
    }

    /** 当前镜像的副本（没有就是「关闭 + 默认」）。 */
    public static ClockGlobalConfig get() {
        final ClockGlobalConfig cfg = current;
        return cfg == null ? new ClockGlobalConfig() : cfg.copy();
    }

    /** 全局开关是否打开（没收到快照 = 关）。 */
    public static boolean enabled() {
        final ClockGlobalConfig cfg = current;
        return cfg != null && cfg.enabled;
    }

    /** 收到服务端快照。 */
    public static void set(final ClockGlobalConfig cfg) {
        current = cfg == null ? null : cfg.copy();
    }

    /** 断线 / 换服：清空，别把上一台的全局配置带到下一台。 */
    public static void clear() {
        current = null;
    }

    /** 时钟该用的配置：全局开着 → 全局那份，否则 → 方块自己的。 */
    public static ClockConfig effectiveOr(final ClockConfig own) {
        final ClockGlobalConfig cfg = current;
        if (cfg == null || !cfg.enabled) {
            return own;
        }
        return cfg.config.copy();
    }
}
