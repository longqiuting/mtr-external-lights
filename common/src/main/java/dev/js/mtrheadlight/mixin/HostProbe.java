package dev.js.mtrheadlight.mixin;

/**
 * Detects which rail host mod (if any) is installed.
 *
 * <p>ANTE and NTE expose the same classes under different packages, and both are optional. Every
 * check is a class-presence probe wrapped in {@code Throwable} so a half-loaded or missing host can
 * never take the client down; the result is cached because it cannot change while the game runs.
 */
public final class HostProbe {
    /** ANTE (1.20.1 line). */
    private static final String ANTE_CHUNK =
            "fabric.cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk";
    /** NTE (1.18.2/1.19.x line). */
    private static final String NTE_CHUNK =
            "cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk";

    private static final boolean ANTE = present(ANTE_CHUNK);
    private static final boolean NTE = present(NTE_CHUNK);

    private HostProbe() {
    }

    public static boolean ante() {
        return ANTE;
    }

    public static boolean nte() {
        return NTE;
    }

    private static boolean present(final String className) {
        try {
            // Resource lookup, NOT Class.forName: loading a host class this early would freeze it
            // before every other mod's mixins get a chance to apply, which can break unrelated mods
            // (EntityCulling's block-entity interface injection is exactly such a victim).
            final String path = className.replace('.', '/') + ".class";
            final ClassLoader own = HostProbe.class.getClassLoader();
            if (own != null && own.getResource(path) != null) {
                return true;
            }
            final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            return ctx != null && ctx.getResource(path) != null;
        } catch (Throwable t) {
            // Missing host, or a loader that cannot see it: treat as absent.
            return false;
        }
    }
}
