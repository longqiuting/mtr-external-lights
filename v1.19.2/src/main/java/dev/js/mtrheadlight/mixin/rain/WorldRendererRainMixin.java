package dev.js.mtrheadlight.mixin.rain;

import dev.js.mtrheadlight.RainHush;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 1.20.1 的雨<b>整段跳掉</b>。
 *
 * <p>目标 {@code WorldRenderer#renderWeather(LightmapTextureManager, float, double, double, double)}，
 * intermediary {@code method_22714}。它由 {@code WorldRenderer#render} 在两处调用（Fabulous 走
 * {@code RenderPhase.WEATHER_TARGET} 的 framebuffer，普通画质直接画）——注释在 HEAD 上，两条路都
 * 一次命中，不存在「只关了一半」。
 *
 * <p>为什么不逐列剔除：原版这个方法一进去就 {@code Tessellator} + {@code BufferBuilder}，逐列
 * 查高度图并写顶点；没有任何「雨滴对象」可以单独跳过。要「不画」只能整段 cancel，这也正是最省的
 * 做法（连 GL 状态切换都不做）。
 *
 * <p>{@code tickRainSplashing(Camera)}（intermediary {@code method_22713}）是另一处：地面水花的
 * 粒子发射器，由 {@code WorldRenderer#tick} 调。它不画雨丝，但不关的话会看到「天上没雨、地上一直
 * 冒圈」，所以同一个开关下也注掉，独立受 {@code rainHushSplash} 控制。
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererRainMixin {

    @Inject(
            method = "renderWeather(Lnet/minecraft/client/render/LightmapTextureManager;FDDD)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void lte$skipRain(final LightmapTextureManager lightmap, final float delta,
                              final double camX, final double camY, final double camZ,
                              final CallbackInfo ci) {
        if (RainHush.shouldSuppress()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "tickRainSplashing(Lnet/minecraft/client/render/Camera;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void lte$skipRainSplash(final Camera camera, final CallbackInfo ci) {
        if (RainHush.shouldSuppressSplash()) {
            ci.cancel();
        }
    }
}
