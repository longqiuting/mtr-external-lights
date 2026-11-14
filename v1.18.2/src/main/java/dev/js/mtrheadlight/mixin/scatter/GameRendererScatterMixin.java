package dev.js.mtrheadlight.mixin.scatter;

import dev.js.mtrheadlight.MtrHeadlightClient;
import dev.js.mtrheadlight.sss.ScatterPass;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>光影下散射的第二个时机</b>：{@code GameRenderer#renderWorld}({@code float,long,MatrixStack})
 * <b>返回之后</b>调 {@link ScatterPass#renderDeferred()}。
 *
 * <h2>1.18.2 与 1.20.1 的差别</h2>
 * 1.20.1 里这个方法叫 {@code renderLevel}，1.18.2 里叫 {@code renderWorld}（intermediary
 * {@code method_32742}，yarn 1.18.2+build.4）。<b>描述符是一样的</b>
 * ({@code (FJLnet/minecraft/client/util/math/MatrixStack;)V})，所以这里的 {@code method =} 字符串
 * 一个字都不用改 —— 只有 remap 出来的名字不同，那是 refmap（{@code lte.refmap.json}）的事。
 *
 * <h2>为什么不能只在 {@code WorldRenderEvents.LAST}</h2>
 * {@code LAST} 落在 Iris 的 world 渲染<b>之内</b>：画在那里的颜色随后会被光影自己的 composite 覆盖，
 * 所以开光影时那一条 pass 等于没画（2.0.0 及以前干脆整条 {@code return}，日志
 * {@code reason=shader-pack}）。RTE 这类模组的做法是等到 {@code renderWorld} 返回后再画 —— 那时光影
 * 已经合成完，主帧缓冲就是屏幕，写进去的就是玩家看到的那一帧。深度仍然必须在 {@code LAST} 采
 * （那是深度缓冲还完整描述这一帧的唯一时机），颜色合成在这里做。
 *
 * <h2>priority = 1500</h2>
 * Iris 自己也在 {@code renderWorld} 的尾部注入（{@code endLevelRendering} / 最终 pass）。Mixin 里
 * <b>数值大的 priority 后应用</b>，排在默认的 1000 之后才落在 Iris 的合成<b>之后</b>；如果不幸顺序反了，
 * 我们写的颜色会被它的 composite 盖掉，画面「没什么变化」，但不会崩、也不会画错别的。
 *
 * <h2>require = 0</h2>
 * 这个注入是<b>锦上添花</b>：方法名/描述符对不上时不注入就好（无光影的那条路完全不受影响），绝不
 * 因为一次映名失败让游戏起不来。没插上的证据在 5 秒的那行日志里：{@code deferred=false} 而
 * {@code depth=stale(unconsumed) defer=not-consumed}。真正的工作体自己 catch 一切异常 —— 这里只有
 * 一次静态调用，世界渲染的这条返回路径上不允许有任何异常冒出去。
 */
@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class GameRendererScatterMixin {

    @Inject(
            method = "renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
            at = @At("RETURN"),
            require = 0)
    private void lte$scatterAfterWorld(final float tickDelta, final long limitTime,
                                       final MatrixStack matrices, final CallbackInfo ci) {
        try {
            ScatterPass.renderDeferred();
        } catch (Throwable t) {
            // ScatterPass 内部已经兜住一切；这一层只保证「连兜底都炸了」也不会把世界渲染带下去。
            MtrHeadlightClient.LOGGER.warn("[lte] scatter: deferred hook failed", t);
        }
    }
}
