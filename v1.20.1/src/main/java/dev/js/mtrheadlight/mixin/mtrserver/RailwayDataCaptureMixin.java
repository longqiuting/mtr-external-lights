package dev.js.mtrheadlight.mixin.mtrserver;

import dev.js.mtrheadlight.server.MtrServerData;
import mtr.data.RailwayData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每 tick 每维度一次：把 {@code RailwayData} 登记给 {@link MtrServerData}（服务端道床用它取轨道表）。
 *
 * <pre>
 * MinecraftServer#tick  ->  world.mtrRailwayData.simulateTrains()   // HEAD: capture(this)
 * </pre>
 *
 * <p>只写 {@code @Inject}、{@code remap = false}（MTR 未 remap，方法名就是源码名），
 * {@code require = 0}（配置里的 {@code defaultRequire}）：MTR 换版本 / 方法改名时静默失效 ——
 * 服务端道床不生效，列车碰撞与玩家道床完全不受影响。
 *
 * <p>放在 {@code "mixins"} 数组（两端都应用）是刻意的：单人里整合服务器同样需要这张表，
 * 否则掉落物在下界/高架轨道上一样会掉下去。
 */
@Mixin(value = RailwayData.class, remap = false)
public abstract class RailwayDataCaptureMixin {

    @Inject(method = "simulateTrains", at = @At("HEAD"), remap = false)
    private void mtrheadlight$captureRails(final CallbackInfo ci) {
        try {
            MtrServerData.capture((RailwayData) (Object) this);
        } catch (Throwable ignored) {
            // 登记失败只是道床实体碰撞不生效，绝不能让 MTR 的 tick 崩掉。
        }
    }
}
