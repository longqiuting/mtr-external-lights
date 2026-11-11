package dev.js.mtrheadlight.mixin.mtrserver;

import dev.js.mtrheadlight.collision.TrainTickCapture;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.TrainServer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * 每 tick 每列车的唯一公开入口：{@code TrainServer#simulateTrain(...)} 的 TAIL（MC 1.20.1 / MTR 3.6.4）。
 *
 * <p>MTR 在主线程的 {@code Siding#simulateTrain} 里对每条侧线的每一列车调一次这个方法；在这里把
 * 列车登记进 {@link TrainTickCapture}，{@code MobCollision} 才有几何可用（MTR 没有公开的列车列表）。
 *
 * <pre>
 * Siding#simulateTrain(...)  ->  TrainServer#simulateTrain(World, float, Depot, DataCache, ...)
 *                                    ^ TAIL: TrainTickCapture.capture(world, this)
 * </pre>
 *
 * <p>只写 {@code @Inject}、{@code remap = false}（MTR 的 jar 未 remap，方法名就是源码名），并且
 * 用<b>方法名</b>而不是描述符：{@code TrainServer} 只有一个 {@code simulateTrain}，描述符里那一串
 * intermediary 类型名很难手写正确。注入失败（MTR 换版本）时 {@code require = 0} 让它静默失效 ——
 * 生物碰撞不生效，但玩家的那条路（{@link dev.js.mtrheadlight.collision.CollisionSystem}）完全不受影响。
 */
@Mixin(value = TrainServer.class, remap = false)
public abstract class TrainSimulateCaptureMixin {

    @Inject(method = "simulateTrain", at = @At("TAIL"), remap = false)
    private void mtrheadlight$capture(final World world, final float ticks, final Depot depot,
                                      final DataCache dataCache, final List<?> trainPositions,
                                      final Map<?, ?> map1, final Map<?, ?> map2,
                                      final Map<?, ?> map3,
                                      final CallbackInfoReturnable<Boolean> cir) {
        try {
            TrainTickCapture.capture(world, (TrainServer) (Object) this);
        } catch (Throwable ignored) {
            // 登记失败只是生物碰撞不生效，绝不能让 MTR 的 tick 崩掉。
        }
    }
}
