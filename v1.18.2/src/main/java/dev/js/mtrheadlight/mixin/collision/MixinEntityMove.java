package dev.js.mtrheadlight.mixin.collision;

import dev.js.mtrheadlight.collision.CollisionSystem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把碰撞解算挂进原版 {@code Entity#move}（MC 1.20.1）。
 *
 * <pre>
 * move(MovementType, Vec3d):
 *   HEAD              -&gt; CollisionSystem.beforeMove   (记下移动前的 y)
 *   HEAD @ModifyVar   -&gt; CollisionSystem.clampMovement (竖直位移截到车顶，防高速下落穿模)
 *   TAIL              -&gt; CollisionSystem.afterMove    (方块碰撞算完之后：推出 / 车顶 / 道床)
 * </pre>
 *
 * <p>{@code Entity#move} 是 {@code ClientPlayerEntity#move} 的 {@code super} 调用点，所以 TAIL
 * 一定发生在客户端发移动包之前 —— 服务器收到的是修正后的位置。处理器内部第一件事就是
 * {@code entity == client.player}，其它实体（以及整合服务器线程）直接返回，开销是一次比较。
 *
 * <p>三个注入方法都在 {@code mtrlte.collision.mixins.json} 里以
 * {@code defaultRequire: 0} 注册：万一某个映射变了，宁可这条注入静默失效（诊断行会显示
 * {@code cars=0}）也不让客户端崩在启动阶段。
 */
@Mixin(Entity.class)
public abstract class MixinEntityMove {

    @Inject(method = "move", at = @At("HEAD"))
    private void mtrheadlight$beforeMove(final MovementType type, final Vec3d movement,
                                         final CallbackInfo ci) {
        CollisionSystem.beforeMove((Entity) (Object) this);
    }

    @ModifyVariable(method = "move", at = @At("HEAD"), index = 2, argsOnly = true)
    private Vec3d mtrheadlight$clampMovement(final Vec3d movement) {
        return CollisionSystem.clampMovement((Entity) (Object) this, movement);
    }

    @Inject(method = "move", at = @At("TAIL"))
    private void mtrheadlight$afterMove(final MovementType type, final Vec3d movement,
                                        final CallbackInfo ci) {
        CollisionSystem.afterMove((Entity) (Object) this);
    }
}
