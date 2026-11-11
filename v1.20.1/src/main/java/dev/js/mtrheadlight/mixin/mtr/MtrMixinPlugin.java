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
        if (mixinClassName.endsWith(".RenderPsdDoorTextureMixin")) {
            // 门扇贴图命名空间切换：只要 MTR 的门扇渲染器在就应用（与列车注册表无关）。
            return HostProbe.mtrPsdDoorRenderer();
        }
        if (mixinClassName.endsWith(".RouteMapArtMixin")) {
            // 线路条带重绘：只要 MTR 的线路图生成器在就应用（与列车注册表无关）。
            return HostProbe.mtrRouteMap();
        }
        if (mixinClassName.endsWith(".BlockPlatformDoorTypeMixin")) {
            // 站台门型识别：站台方块是双端共享的类，所以用普通方块探测（dedicated server 上也要生效）。
            return HostProbe.mtrBlockPlatform();
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
