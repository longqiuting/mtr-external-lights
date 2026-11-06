package dev.js.mtrheadlight;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Detects whether a third-party shader pack (Iris or OptiFine) is currently taking over the
 * pipeline.
 *
 * <p>Both mods are optional, so everything here is reflective and every failure path means "no
 * pack". The answer is cached for a second: packs can be toggled at run time (that is a single
 * keybind in Iris), and a per-tick reflective call would be wasteful for a boolean that changes
 * once in a session.
 *
 * <p>What the mod does with it: ANTE has two rail render paths and, when a pack is active, it
 * drops to the fallback one (own shader program replaced by the pack's program). See
 * {@code docs/rail-light-plan.md} for the reasoning behind {@code railLightUnderShaders}.
 */
public final class ShaderPackProbe {
    private static final long REFRESH_MILLIS = 1000L;

    private static boolean resolved;
    private static Method irisInUse;
    private static Object irisApi;
    private static Method optifineInUse;
    private static Field optifineActiveProgram;

    // Read from the chunk-build workers as well as the client thread: the value is a boolean that
    // changes once per session, so volatile (not a lock) is enough to avoid a torn/timed-out read.
    private static volatile boolean cached;
    private static volatile long cachedAt;
    private static boolean announced;
    private static boolean lastAnnounced;

    private ShaderPackProbe() {
    }

    /** True when Iris or OptiFine currently renders with a shader pack. */
    public static boolean active() {
        final long now = System.currentTimeMillis();
        if (now - cachedAt < REFRESH_MILLIS) {
            return cached;
        }
        cachedAt = now;
        cached = query();
        return cached;
    }

    /**
     * Logs one line when the pack state changes (never per frame, never per tick): a pack that
     * renders the baked light much darker than expected is impossible to tell apart from "the
     * feature does nothing", and this line at least says which of the two is happening.
     */
    public static void announce(final HeadlightConfig cfg) {
        final boolean inUse = active();
        if (announced && inUse == lastAnnounced) {
            return;
        }
        announced = true;
        lastAnnounced = inUse;
        try {
            if (inUse) {
                MtrHeadlightClient.LOGGER.info(
                        "[mtr-headlight] shader pack active: rail light goes into the vertex lightmap "
                                + "and the pack decides whether to show it "
                                + "(railLightUnderShaders={}, railLightShaderBoost={})",
                        cfg != null && cfg.railLightUnderShaders,
                        cfg != null && cfg.railLightShaderBoost);
            } else {
                MtrHeadlightClient.LOGGER.info(
                        "[mtr-headlight] shader pack inactive: rail light uses ANTE's own shader path");
            }
        } catch (Throwable t) {
            // Logging must never break the tick.
        }
    }

    private static boolean query() {
        if (!resolved) {
            resolved = true;
            resolve();
        }
        if (irisInUse != null && irisApi != null) {
            try {
                return Boolean.TRUE.equals(irisInUse.invoke(irisApi));
            } catch (Throwable t) {
                // Never both: fall through to the OptiFine paths, then to "no pack".
            }
        }
        if (optifineInUse != null) {
            try {
                return Boolean.TRUE.equals(optifineInUse.invoke(null));
            } catch (Throwable t) {
                return false;
            }
        }
        if (optifineActiveProgram != null) {
            try {
                return (Integer) optifineActiveProgram.get(null) != 0;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static void resolve() {
        try {
            final Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            final Object instance = api.getMethod("getInstance").invoke(null);
            final Method inUse = api.getMethod("isShaderPackInUse");
            if (instance != null && inUse.getReturnType() == boolean.class) {
                irisApi = instance;
                irisInUse = inUse;
                return;
            }
        } catch (Throwable t) {
            irisApi = null;
            irisInUse = null;
        }
        try {
            final Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
            final Method inUse = shaders.getMethod("isShaderPackInUse");
            if (inUse.getReturnType() == boolean.class) {
                optifineInUse = inUse;
                return;
            }
        } catch (Throwable t) {
            optifineInUse = null;
        }
        // Same field ANTE itself reads when OptiFine is the shader mod.
        try {
            final Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
            final Field field = shaders.getDeclaredField("activeProgramID");
            field.setAccessible(true);
            optifineActiveProgram = field;
        } catch (Throwable t) {
            optifineActiveProgram = null;
        }
    }
}
