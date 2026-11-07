package dev.js.mtrheadlight.mixin;

import dev.js.mtrheadlight.RailMixinFlags;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for the NTE rail mixins.
 *
 * <p>Same shape as {@link AnteMixinPlugin}: the config is {@code required: false} and lists only
 * NTE-targeted mixins, and the class probe decides whether they are applied at all.
 */
public final class NteMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return HostProbe.nte();
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
        // Flag only, never a log line: this runs while a host class is being transformed.
        if (mixinClassName.endsWith("NteRailChunkBuildMixin")) {
            RailMixinFlags.noteNte(MixinScan.containsBoostCall(targetClass));
        } else if (mixinClassName.endsWith("AnteRailChunkBuildMixin")) {
            RailMixinFlags.noteAnte(MixinScan.containsBoostCall(targetClass));
        }
    }
}
