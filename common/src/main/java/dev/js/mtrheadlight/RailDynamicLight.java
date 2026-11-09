package dev.js.mtrheadlight;

import net.minecraft.util.math.BlockPos;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Dynamic-light lookup used by the rail bake mixin (1.20.1).
 *
 * <p>Preferred source is LambDynamicLights' own engine ({@code LambDynLights#getDynamicLightLevel}),
 * so every LDL source - the player's torch, other mods, and our train beams - lands on the rails.
 * The call is resolved reflectively because LDL ships intermediary names and is only available at
 * run time; the handle is cached on first use. If anything fails the own-beam snapshot is used
 * instead, and if that is empty as well the method returns 0 (original world light is kept).
 *
 * <p>Verified against the jars actually installed here, both are checked before use:
 * <ul>
 *   <li>{@code lambdynamiclights-4.4.0+1.20.1.jar}: {@code public static LambDynLights get()} and
 *       {@code public double getDynamicLightLevel(BlockPos)} (the return value is 0-15, not 0-1 -
 *       its own {@code getLightmapWithDynamicLight(double, int)} multiplies it by 16 to get a
 *       texel).</li>
 *   <li>{@code lambdynamiclights-api-4.4.0+1.20.1.jar} contains no engine class at all, only the
 *       {@code api} package, so the API jar cannot be used for a level lookup.</li>
 * </ul>
 *
 * <p>Two bugs lived here and both made a held torch invisible to the rails:
 * <ol>
 *   <li>{@code MethodHandles.unreflect(instanceMethod).asType((BlockPos)double)} - {@code unreflect}
 *       yields a handle whose first parameter is the receiver, and {@code asType} cannot drop
 *       parameters, so it threw {@code WrongMethodTypeException} inside the resolve {@code try} and
 *       left the handle {@code null}. LDL was therefore never queried. Fixed with
 *       {@code bindTo(instance)}.</li>
 *   <li>A successful LDL lookup that returned 0 (no source near the rail) suppressed the own-beam
 *       fallback, since a present-but-zero answer is not {@code NaN}. The two are now maxed.</li>
 * </ol>
 */
public final class RailDynamicLight {
    private static final MethodType LEVEL_TYPE = MethodType.methodType(double.class, BlockPos.class);

    private static MethodHandle levelHandle;
    /** Guard for the one-time resolve; the handle is published before this flips to true. */
    private static volatile boolean resolved;
    /** One-shot diagnostics: the bake runs on worker threads, so this must stay a plain flag. */
    private static volatile boolean announced;
    private static volatile boolean announceWarned;

    private RailDynamicLight() {
    }

    /** Dynamic light level (0-15) at a block position, or 0 when unknown/off. */
    public static int level(final BlockPos pos) {
        final double dynamic = dynamicLevel(pos);
        if (dynamic <= 0.0D) {
            return 0;
        }
        final int rounded = (int) Math.round(Math.min(15.0D, dynamic));
        return rounded < 0 ? 0 : (rounded > 15 ? 15 : rounded);
    }

    /** Strongest of the two sources we know about; both are already on the 0-15 scale. */
    private static double dynamicLevel(final BlockPos pos) {
        final double lambda = lambdaLevel(pos);
        double own;
        try {
            own = RailLightState.ownLevel(pos) * ownGain();
        } catch (Throwable t) {
            own = 0.0D;
        }
        RailDiag.observeOwn(own);
        return lambda > own ? lambda : own;
    }

    /**
     * Amplifies the train beam only. A held torch is 15 in LDL while a beam head is 12 and decays with
     * distance, so on the rails the train usually lands around 6-9; shader packs curve the lightmap
     * super-linearly, which turns that into "barely visible". Player light sources stay untouched.
     */
    private static double ownGain() {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            return cfg == null ? 1.0D : cfg.railLightGain;
        } catch (Throwable t) {
            return 1.0D;
        }
    }

    /** LDL level, or NaN when LDL could not be queried (then the caller falls back). */
    private static double lambdaLevel(final BlockPos pos) {
        final MethodHandle handle = handle();
        if (handle == null) {
            return Double.NaN;
        }
        try {
            return (double) handle.invokeExact(pos);
        } catch (Throwable t) {
            // A disabled/uninitialised engine must never break the bake.
            return Double.NaN;
        }
    }

    private static MethodHandle handle() {
        if (resolved) {
            // levelHandle is written before the volatile `resolved` flag, so this read is safe.
            return levelHandle;
        }
        synchronized (RailDynamicLight.class) {
            if (resolved) {
                return levelHandle;
            }
            resolveHandle();
            resolved = true;
            return levelHandle;
        }
    }

    /** Resolves once; leaves {@link #levelHandle} null when LDL is absent or its API moved. */
    private static void resolveHandle() {
        try {
            final Class<?> api = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
            final Object instance = api.getMethod("get").invoke(null);
            if (instance == null) {
                warnOnce("LambDynamicLights is present but not initialised yet", null);
                return;
            }
            for (final Method method : api.getMethods()) {
                if (!"getDynamicLightLevel".equals(method.getName())
                        || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != BlockPos.class
                        || method.getReturnType() != double.class) {
                    continue;
                }
                // bindTo drops the receiver, then asType only has to convert the remaining
                // (BlockPos) -> double signature. Without bindTo, asType throws
                // WrongMethodTypeException because it cannot drop the receiver parameter.
                levelHandle = MethodHandles.lookup().unreflect(method)
                        .bindTo(instance)
                        .asType(LEVEL_TYPE);
                break;
            }
            if (levelHandle == null) {
                warnOnce("LambDynamicLights API mismatch: getDynamicLightLevel(BlockPos) not found",
                        null);
                return;
            }
            announceOnce();
        } catch (ClassNotFoundException e) {
            // LDL simply not installed: the own-beam snapshot is the only source.
            levelHandle = null;
        } catch (Throwable t) {
            levelHandle = null;
            warnOnce("LambDynamicLights lookup could not be resolved", t);
        }
    }

    private static void announceOnce() {
        if (announced) {
            return;
        }
        announced = true;
        try {
            MtrHeadlightClient.LOGGER.info(
                    "[lte] rail light: LambDynamicLights engine bound "
                            + "(held/placed light sources are baked into the rails)");
        } catch (Throwable t) {
            // Logging must never break the bake.
        }
    }

    private static void warnOnce(final String message, final Throwable cause) {
        if (announceWarned) {
            return;
        }
        announceWarned = true;
        try {
            if (cause == null) {
                MtrHeadlightClient.LOGGER.warn("[lte] rail light: {}", message);
            } else {
                MtrHeadlightClient.LOGGER.warn("[lte] rail light: {}", message, cause);
            }
        } catch (Throwable t) {
            // Logging must never break the bake.
        }
    }
}
