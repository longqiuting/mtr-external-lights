package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.mixin.HostProbe;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for the MTR train-registry mixin.
 *
 * <p>Same shape as {@code AnteMixinPlugin}: the config is {@code required: false} and the plugin only
 * answers {@link #shouldApplyMixin} from an explicit class-resource probe, so an MTR fork without the
 * class (or a resource pack that renamed the package) simply applies nothing.
 *
 * <p>Silent on purpose - no logging from here. Logging during mixin config preparation would load
 * this mod's own classes (and whatever they reference) before every other mod's mixins get a chance
 * to apply to them (see the note in {@code AnteMixinPlugin}). The runtime counterpart
 * ({@code BuiltInTrains}) reports the one line that matters: what it kept and what it removed.
 */
public final class MtrMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.endsWith(".RenderPIDSStationMixin")) {
            // 站外投影机顶渲染：只要渲染器在就应用（与列车注册表无关）。
            return HostProbe.mtrPidsRenderer();
        }
        return HostProbe.mtrTrainRegistry();
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
