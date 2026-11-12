package dev.js.mtrheadlight.mixin.mtr;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import dev.js.mtrheadlight.xiamen.XiamenRouteScope;
import mtr.client.ClientCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把「这张图是我们的方块要的」这个<b>线程标记</b>带进 MTR 的<b>异步</b>贴图生成任务。
 *
 * <h2>为什么必须有这一条</h2>
 * <p>MTR 的 {@code ClientCache#getResource(key, supplier, defaultColour)} 不是当场画图，而是：</p>
 * <pre>
 *   getResource:
 *    173: invokestatic  RouteMapGenerator.setConstants()
 *    177: invokestatic  java/util/concurrent/CompletableFuture.supplyAsync(Supplier)   ← 丢到线程池
 *    185: invokedynamic …:accept:(ClientCache, String, DefaultRenderingColor, MinecraftClient)
 *    190: invokevirtual CompletableFuture.thenAccept(Consumer)                        ← 画完再注册纹理
 * </pre>
 * <p>{@code generateRouteMap} 是<b>在 ForkJoinPool 的工作线程上</b>执行的，而
 * {@link XiamenRouteScope} 的标记是在渲染线程里压的 —— 直接读的话工作线程上永远是 0，
 * 结果就是「我们的吊板也回到 MTR 原版样式」。所以这里在 {@code supplyAsync} 那一刻
 * （<b>仍在渲染线程</b>，{@code getRouteMap → getResource} 是同一次调用）把当前标记<b>读出来</b>，
 * 需要时把 supplier 包一层：在工作线程上先压栈、跑完出栈。</p>
 *
 * <p>{@code supplyAsync} 是 JDK 静态方法（开发 / 生产字节码一致），所以注解处理器在编译期就能
 * 校验目标存在；注入丢失时由 {@code defaultRequire: 0} + {@code required: false} 兜底
 * （最坏情况：我们的吊板显示 MTR 原版线路图，不会崩）。</p>
 */
@Mixin(value = ClientCache.class, remap = false)
public abstract class RouteAsyncScopeMixin {

    /** 渲染线程：读一次标记；工作线程：包一层，让 {@code XiamenRouteScope.active()} 仍然成立。 */
    @Redirect(
            method = "getResource",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync",
                    remap = false),
            remap = false)
    private static <U> CompletableFuture<U> lte$carryScope(final Supplier<U> supplier) {
        if (!XiamenRouteScope.active()) {
            return CompletableFuture.supplyAsync(supplier);
        }
        // v7：方向值也必须带过去 —— 条带的镜像（含终点三角）由它决定，而位图是在这个工作线程上画的。
        final int arrow = XiamenRouteScope.arrowDirection();
        return CompletableFuture.supplyAsync(() -> {
            XiamenRouteScope.push(true);
            XiamenRouteScope.arrow(arrow);
            try {
                return supplier.get();
            } finally {
                XiamenRouteScope.pop();
            }
        });
    }
}
