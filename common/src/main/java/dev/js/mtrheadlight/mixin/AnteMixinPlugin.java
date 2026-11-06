package dev.js.mtrheadlight.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for the ANTE rail mixins.
 *
 * <p>Deliberately empty except for {@link #shouldApplyMixin}: the config is {@code required: false}
 * and lists only ANTE-targeted mixins, so an ANTE-less client simply applies nothing. The plugin
 * adds the explicit class probe so the decision does not rely on mixin's own "target not found"
 * handling (which still logs a warning and can differ between mixin versions).
 */
public final class AnteMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        // Deliberately silent: logging from here would load MtrHeadlightClient (and whatever it
        // references) while mixin configs are still being prepared, which freezes those classes
        // before other mods' mixins can apply to them - e.g. Fabric's ScreenExtensions never lands
        // on TitleScreen and the game dies with a ClassCastException on the title screen.
        return HostProbe.ante();
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
