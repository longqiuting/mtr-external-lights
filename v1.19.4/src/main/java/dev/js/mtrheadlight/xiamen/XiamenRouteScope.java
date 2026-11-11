// 「现在画的是不是我们自己的屏蔽门」——线路条带 / 方向面板接管的唯一开关。
package dev.js.mtrheadlight.xiamen;

/**
 * <b>作用域</b>：MTR 的线路图纹理是全局按 {@code platformId + 参数} 缓存的，
 * {@code RouteMapGenerator.generateRouteMap} 又是静态方法 —— 所以「换掉线路图」这件事默认会
 * 连带把 <b>MTR 原版屏蔽门</b>也换掉（v3 的 bug）。
 *
 * <h2>怎么做到“只作用于我们的方块”</h2>
 * <p>MTR 的 {@code RenderRouteBase#render} 是 {@code final} 改不了，但它每帧调用两个
 * <b>protected、非 final</b> 的钩子，而且顺序固定（javap 3.6.4.2 / 1.20.1，
 * {@code RenderRouteBase#render(T, float, MatrixStack, VertexConsumerProvider, int, int)}）：</p>
 * <pre>
 *    89-105  platformId == 0 ? return          ← 早退，getRenderType 还没被调用
 *   121-175  getTextureNumber ×2 / getShadingColor / getRenderType(BlockView, BlockPos, BlockState)
 *   482      ClientData.DATA_CACHE.getRouteMap(...)   ← 线路条带位图在这条调用链上被“下单”
 *   445/377  getDirectionArrow(...) / getStationName(...)
 *   561      renderAdditional(StoredMatrixTransformations, platformId, ...)
 * </pre>
 * <p>于是我们的渲染器子类（{@link XiamenPsdTopRenderer} / {@link XiamenApgGlassRenderer}）
 * 在 {@code getRenderType} 里 {@link #push(boolean) push(true)}、在 {@code renderAdditional} 里
 * {@link #pop() pop()}，把「这一段渲染是我们的方块」压在 ThreadLocal 上；172 与 561 之间没有任何
 * 提前 return（逐条核对过上面的字节码），所以栈一定是配平的。</p>
 *
 * <p>MTR 自己注册的 {@code RenderPSDTop / RenderAPGGlass} 实例不会走这两个覆盖方法 →
 * 线程里永远是“不是我们的” → {@link dev.js.mtrheadlight.mixin.mtr.RouteMapArtMixin} 一行都不改
 * MTR 的图，原版屏蔽门回到它本来的样子。</p>
 *
 * <h2>为什么要走 mixin 把标记带进工作线程</h2>
 * <p>{@code ClientCache#getResource} 是 {@code CompletableFuture.supplyAsync(supplier)} ——
 * 位图其实是在<b>线程池</b>上画的，ThreadLocal 传不过去。所以
 * {@link dev.js.mtrheadlight.mixin.mtr.RouteAsyncScopeMixin} 在 {@code supplyAsync} 那一刻
 * （仍在渲染线程）把标记读出来，需要时给 supplier 包一层「进池前压栈、跑完出栈」。
 * 两条 mixin + 两个渲染器覆盖方法合起来才是完整的“只作用于我们的方块”：</p>
 * <pre>
 *   render(渲染线程) → getRenderType(push) → getRouteMap → getResource
 *                                                          ↳ supplyAsync 包装（读标记）
 *   线程池 → 包装体 push → generateRouteMap / generateDirectionArrow → pop
 *   render(渲染线程) → renderAdditional(pop)
 * </pre>
 *
 * <h2>为什么还要按线程存</h2>
 * <p>渲染线程和工作线程各自有独立的栈，两边都不会串：包体里压的栈只活在那一个生成任务里，
 * 不会让同一时刻渲染线程上 MTR 自己的面板被误判。</p>
 */
public final class XiamenRouteScope {

    /** {当前压栈深度, 其中“我们的方块”的层数}。 */
    private static final ThreadLocal<int[]> STATE = ThreadLocal.withInitial(() -> new int[2]);

    private XiamenRouteScope() {
    }

    /** 进入一次线路图渲染。{@code ours = true} 表示这次画的是本模组的屏蔽门方块。 */
    public static void push(final boolean ours) {
        final int[] state = STATE.get();
        state[0]++;
        if (ours) {
            state[1]++;
        }
    }

    /** 退出一次线路图渲染（与 {@link #push(boolean)} 严格配对；多调也不会把计数器压成负数）。 */
    public static void pop() {
        final int[] state = STATE.get();
        if (state[0] > 0) {
            state[0]--;
        }
        if (state[1] > state[0]) {
            state[1] = state[0];
        }
        if (state[0] == 0) {
            state[1] = 0;
        }
    }

    /**
     * 当前这次渲染是不是<b>本模组的</b>屏蔽门 / 吊板 / 玻璃。
     *
     * <p>返回 {@code false} 时所有接管入口（{@code XiamenRouteStrip#paint / directionArrow /
     * colorStrip}）都直接返回 {@code null}，MTR 照它原来的样子画。</p>
     */
    public static boolean active() {
        return STATE.get()[1] > 0;
    }

    /** 调试用：当前栈深。 */
    public static int depth() {
        return STATE.get()[0];
    }

    /** 给纹理缓存键用的后缀（我们的图和 MTR 的图必须分开缓存，否则同一个站台会互相顶掉）。 */
    public static String cacheKeySuffix() {
        return active() ? "_lte" : "";
    }
}
