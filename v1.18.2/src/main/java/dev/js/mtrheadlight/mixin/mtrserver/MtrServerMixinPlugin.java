package dev.js.mtrheadlight.mixin.mtrserver;

import dev.js.mtrheadlight.mixin.HostProbe;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * {@code mtrlte.mtrserver.mixins.json} 的插件：只为「MTR 不在 / 版本不对」时静默跳过。
 *
 * <p>这组注入是<b>服务端需要的</b>（每 tick 把 {@code TrainServer} 登记进
 * {@code TrainTickCapture}），写在 {@code "mixins"} 数组里 —— 它同时作用于包含整合服务器的客户端
 * 与 dedicated server（单个存档里的「服务端」也是服务端，生物碰撞同样需要这张表）。
 *
 * <p>探测与 {@code MtrMixinPlugin} 同形：只查类资源，不 {@code Class.forName}，所以不会在其它
 * 模组的注入还没应用时就把 MTR 的类冻住。类不在 → 整个配置不应用；类在但方法签名变了 →
 * {@code defaultRequire: 0} 让注入静默失效（{@code ServerCollision} 会打一行
 * 「no MTR train was captured」的 warn）。
 */
public final class MtrServerMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.endsWith(".PidsScheduleScopeMixin")) {
            // 到站数据范围：目标类换了就静默跳过（defaultRequire: 0），退回 MTR 原行为。
            return HostProbe.mtrSavedRail();
        }
        return HostProbe.mtrServerTrain();
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass,
                         final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass,
                          final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
