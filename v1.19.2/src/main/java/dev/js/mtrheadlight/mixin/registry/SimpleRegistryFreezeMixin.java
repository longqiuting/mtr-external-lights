// 注册表冻结前的最后一道自检：把「建了但没注册」的方块点名（并清掉残留引用）。
package dev.js.mtrheadlight.mixin.registry;

import dev.js.mtrheadlight.server.RegistrySelfCheck;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.SimpleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code SimpleRegistry#freeze()} 的 HEAD 钩子 —— 方块注册表自检的最后一个可用位置。
 *
 * <h2>为什么必须是这里</h2>
 * <p>原版在冻结（1.19.2：数据包加载末期）时校验「待绑定」表
 * （{@code SimpleRegistry.unfrozenValueToEntry}），表里但凡还剩一个
 * 「建了但没注册」的方块就直接抛
 * {@code Some intrusive holders were not added to registry: [Reference{null=Block{minecraft:air}} × N]}，
 * 而此时崩溃信息里全是 {@code minecraft:air}，看不出是谁。
 *
 * <p>这个时刻<b>所有模组的注册都已完成</b>，所以不论漏网方块是哪个模组（自己 / metropolis /
 * jsblock / londonunderground / shmetro / mishanguc …）造的，都能在这里被点名；
 * 同时也解释了为什么放在我们自己的 {@code onInitialize} 里不够 —— 那次可能比闯祸的模组还早。
 *
 * <h2>安全性</h2>
 * <p>钩子只做两件事：读私有表、把<b>没有 registry key</b> 的条目删掉并 WARN。被删的实例除了这张表
 * 之外没有任何引用（不在注册表里、也不在任何字段里），删掉不会影响任何正常方块；
 * 任何异常都被 {@link RegistrySelfCheck#onFreeze} 吞掉，绝不让 {@code freeze()} 跟着失败。
 *
 * <p>1.19.2 的签名是 {@code public Registry<T> freeze()}（1.19.4 也是），所以返回值类型用
 * {@link Registry} —— 与 1.19.4 那份逐字一致。
 *
 * <p>配置里 {@code required=false} + {@code defaultRequire=0}：万一哪天换了原版版本、
 * {@code freeze} 这个名字对不上，整个 mixin 静默不生效，而不是把游戏弄崩。
 */
@Mixin(SimpleRegistry.class)
public abstract class SimpleRegistryFreezeMixin {

    @Inject(method = "freeze", at = @At("HEAD"))
    private void lte$checkLeakedBlocksBeforeFreeze(final CallbackInfoReturnable<Registry<?>> cir) {
        RegistrySelfCheck.onFreeze((Registry<?>) (Object) this);
    }
}
