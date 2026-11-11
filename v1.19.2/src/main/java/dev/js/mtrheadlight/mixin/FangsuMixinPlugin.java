package dev.js.mtrheadlight.mixin;

import dev.js.mtrheadlight.MtrHeadlightClient;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gate for {@code mtrlte.fangsu.mixins.json}.
 *
 * <p>方速 (fangsu) is an optional, fast-moving addon we deliberately do not compile against, so the
 * decision to apply these mixins is a pure class-presence probe:
 *
 * <ul>
 *   <li>the config is {@code "required": false}, so a client without fangsu simply applies
 *       nothing;</li>
 *   <li>{@link #shouldApplyMixin} probes the <em>target class of that very mixin</em> with
 *       {@code Class.forName(name, false, loader)} inside a {@code catch (Throwable)} - the flag
 *       {@code false} keeps static initialisers from running, so the probe cannot start Minecraft
 *       code or fail in a way that matters;</li>
 *   <li>every injection in those mixins carries {@code require = 0}, so even a present-but-changed
 *       fangsu only loses the injections, it does not fail the launch.</li>
 * </ul>
 *
 * <p>Silence when fangsu is absent: no log line is emitted for a skipped mixin (that would put two
 * "skipped" warnings in every unrelated log). One INFO line per applied mixin is emitted instead,
 * which is what a support log needs to tell "hooked" from "silently absent".
 */
public final class FangsuMixinPlugin implements IMixinConfigPlugin {
    /** fangsu's turnout renderer - the class the whole feature hangs off. */
    private static final String TURNOUT_RENDERER = "com.fangsu.blocks.client.TurnoutBlockEntityRenderer";

    /** Probing is answer-stable for the process, and mixin may ask once per mixin class. */
    private static Boolean turnoutsPresent;

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        // Silent on purpose. Logging here loads MtrHeadlightClient (and its referenced classes) while
        // mixin configs are still being prepared; classes loaded that early never receive later
        // mixins, which broke Fabric's ScreenExtensions on TitleScreen for other mods.
        return loadable(targetClassName);
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

    /** True when fangsu's turnout renderer is on the class path (multi-layer rails are a bonus). */
    public static boolean turnoutsPresent() {
        Boolean cached = turnoutsPresent;
        if (cached == null) {
            cached = loadable(TURNOUT_RENDERER);
            turnoutsPresent = cached;
        }
        return cached;
    }

    /**
     * {@code Class.forName} with initialisation off, tried on our own loader first and on the
     * context loader second - Fabric hands each mod its own class loader, and which one can see
     * another mod's classes has changed between loader versions.
     */
    private static boolean loadable(final String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        // Resource lookup instead of Class.forName: loading a class at mixin-config time freezes it
        // before every other mod's mixins can apply, which breaks unrelated mods (EntityCulling's
        // block-entity interface injection is exactly such a victim).
        try {
            final String path = className.replace('.', '/') + ".class";
            final ClassLoader own = FangsuMixinPlugin.class.getClassLoader();
            if (own != null && own.getResource(path) != null) {
                return true;
            }
            final ClassLoader context = Thread.currentThread().getContextClassLoader();
            return context != null && context.getResource(path) != null;
        } catch (Throwable ignored) {
            // Neither loader can see it: treat as absent.
        }
        return false;
    }

    private static String simpleName(final String className) {
        if (className == null) {
            return "?";
        }
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
