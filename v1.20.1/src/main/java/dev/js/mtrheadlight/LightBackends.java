package dev.js.mtrheadlight;

/**
 * Version-local selection of the light backend implementation.
 *
 * <h2>LambDynamicLights is optional</h2>
 * The 1.20.1 backend ({@link Ldl4Backend}) is written against LDL's own API classes, so touching it
 * without the mod installed is a {@link NoClassDefFoundError}, not a missing method. That is why the
 * class is only named inside a {@code try} block here: with LDL absent (or too old to have the 4.x
 * behaviour API) the lookup fails, we fall back to a backend that does nothing, and every other
 * feature - the beam sheet, the cone, rail light, collision, rain - keeps working.
 *
 * <p>{@link #available()} is what the config screen asks before it draws the 动态光源 row: a true
 * answer means "there is an engine to switch", so the row is live; false greys it out with a
 * "需要安装 LambDynamicLights" tooltip instead of letting the player toggle something that cannot
 * happen.
 */
public final class LightBackends {
    private static LightBackend instance;
    /** Whether {@link #backend()} found a working LambDynamicLights; set once, on first resolve. */
    private static boolean ldlPresent;

    private LightBackends() {
    }

    public static LightBackend backend() {
        if (instance == null) {
            instance = create();
        }
        return instance;
    }

    /** True when the active backend is LambDynamicLights (i.e. the dynamic-light switch can do anything). */
    public static boolean available() {
        backend();
        return ldlPresent;
    }

    private static LightBackend create() {
        try {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("lambdynlights")) {
                final LightBackend ldl = new Ldl4Backend();
                ldlPresent = true;
                return ldl;
            }
        } catch (Throwable t) {
            // NoClassDefFoundError (not installed), NoSuchMethodError (LDL older than the 4.x
            // behaviour API): either way there is no engine to hand lights to.
            MtrHeadlightClient.LOGGER.warn(
                    "[lte] LambDynamicLights unavailable, dynamic lights disabled: {}",
                    String.valueOf(t));
        }
        ldlPresent = false;
        return new NoopBackend();
    }

    /**
     * Stand-in backend for a client without LambDynamicLights. Every call is a no-op, so the light
     * controller keeps its ordinary bookkeeping (per-train state, diagnostics) and simply has nothing
     * to hand the lights to.
     */
    private static final class NoopBackend implements LightBackend {
        @Override
        public void updateLine(final String key, final net.minecraft.util.math.Vec3d start,
                               final net.minecraft.util.math.Vec3d end, final int luminance) {
            // No engine: nothing to register.
        }

        @Override
        public void hideLine(final String key) {
            // No engine: nothing to dim.
        }

        @Override
        public void removeLine(final String key) {
            // No engine: nothing to detach.
        }

        @Override
        public void clearAll() {
            // Nothing is ever registered.
        }

        @Override
        public int liveSources() {
            return 0;
        }

        @Override
        public void tick() {
            // No diagnostics to print.
        }
    }
}
