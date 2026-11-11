package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.xiamen.XiamenRouteScope;
import mtr.client.ClientCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 给<b>我们自己的</b>线路图纹理单独开一个缓存键，避免和 MTR 原版屏蔽门的图互相顶掉。
 *
 * <h2>为什么需要</h2>
 * <p>MTR 的纹理缓存（{@code ClientCache#getRouteMap / #getDirectionArrow}）按
 * <b>{@code platformId + 面板参数}</b> 做 key（javap 3.6.4.2 / 1.20.1）：</p>
 * <pre>
 *   getRouteMap:       ldc  "route_map_%s_%s_%s_%s_%s"        → String.format → getResource(key, …)
 *   getDirectionArrow: ldc  "direction_arrow_%s_%s_…_%s"      → String.format → getResource(key, …)
 * </pre>
 * <p>同一个站台上如果既有我们的吊板 / 玻璃、又有 MTR 自己那块 PSD 吊板，两者的平台 id 和几何参数
 * 完全一样 → 谁先生成，另一块就显示谁那张图。所以这里在<b>我们渲染</b>时给 key 加一个后缀
 * （{@link XiamenRouteScope#cacheKeySuffix()}），两张图各自缓存、互不干扰；MTR 那块拿到的还是
 * 原来的 key，行为和以前一字不差。</p>
 *
 * <p>只拦 {@code String.format}（JDK 方法，开发 / 生产字节码一致，注解处理器能当场校验目标），
 * 与 {@code RenderPsdDoorTextureMixin} 是同一个思路。注入丢失时 {@code defaultRequire: 0} +
 * {@code required: false} 兜底 —— 最坏情况和不加这个 mixin 一样，不会崩。</p>
 */
@Mixin(value = ClientCache.class, remap = false)
public abstract class RouteMapCacheKeyMixin {

    /** {@code route_map_…} / {@code direction_arrow_…} 的 key：我们的那张加 {@code _lte} 后缀。 */
    @Redirect(
            method = {"getRouteMap", "getDirectionArrow"},
            at = @At(value = "INVOKE", target = "Ljava/lang/String;format", remap = false),
            remap = false)
    private static String lte$scopedKey(final String format, final Object[] args) {
        final String key = String.format(format, args);
        final String suffix = XiamenRouteScope.cacheKeySuffix();
        return suffix.isEmpty() ? key : key + suffix;
    }
}
