package dev.js.mtrheadlight.ldl;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import dev.lambdaurora.lambdynlights.api.behavior.LineLightBehavior;
import org.joml.Vector3d;

/**
 * LambDynamicLights 4.x glue for the <b>player-held</b> dynamic light — the only LDL code left in the
 * 1.20.1 module.
 *
 * <h2>Why a segment and not a point</h2>
 * LDL 4.x has no point behaviour: {@code DynamicLightBehavior} is the interface, and the API ships
 * {@code LineLightBehavior} (a segment) and {@code BeaconLightBehavior} (which takes a
 * {@code Level}). A degenerate "point" is therefore a very short segment: a vertical one from knee to
 * eye height at the player's position, which is exactly the shape a torch in hand has anyway. Its
 * bounding box ({@code LineLightBehavior#getBoundingBox}) covers the two blocks it spans, which is the
 * same path the train light lines used on this version before the LDL backend was removed.
 *
 * <h2>Off means detached</h2>
 * Same lesson as the deleted {@code Ldl4Backend}: a live behaviour parked at luminance 0 is not proof
 * that the ground went dark again, because LDL only re-lights what a behaviour reports through
 * {@code hasChanged()}. So a luminance of 0 calls {@code manager.remove(...)} instead of
 * {@code setLuminance(0)}, and the next item pick-up adds it back. The add/remove happens on the
 * transition only, never per tick.
 *
 * <h2>Never load this class without LDL</h2>
 * Every reference to an LDL type lives in here, and nothing but
 * {@link PlayerDynamicLight} calls it. That facade decides whether LDL is installed
 * ({@code lambdynlights}, the mod jar - not {@code lambdynlights_api}, which is jar-in-jar'd inside
 * it) and wraps every call in {@code try/catch Throwable}, so on a client without LDL this class is
 * never initialised and a missing API class can never reach a stack trace the player sees.
 */
public final class LdlPlayerLight {

    /** Handed over by LDL through {@code lambdynlights:initializer}; null until then. */
    private static DynamicLightBehaviorManager manager;

    /** The one behaviour we own. Created on the first lit tick, reused afterwards. */
    private static LineLightBehavior light;

    /** Whether {@link #light} is currently registered on {@link #manager}. */
    private static boolean attached;

    private static int lastLuminance;

    private LdlPlayerLight() {
    }

    /**
     * Called from the {@code lambdynlights:initializer} entrypoint. Runs on client init, so the
     * manager is in place before the first tick that might want a light.
     */
    static void install(final DynamicLightBehaviorManager behaviorManager) {
        manager = behaviorManager;
        attached = false;
        lastLuminance = 0;
    }

    /** True once LDL handed us a manager - the facade uses this to tell "installed" from "loaded". */
    static boolean ready() {
        return manager != null;
    }

    /**
     * Moves the held light into place, or detaches it when {@code luminance <= 0}.
     *
     * @param x         player X
     * @param y         player Y (feet)
     * @param z         player Z
     * @param luminance 0-15 block light level of whatever is in hand
     * @return the luminance actually published (0 when nothing is lit / no manager yet)
     */
    static int set(final double x, final double y, final double z, final int luminance) {
        final DynamicLightBehaviorManager m = manager;
        if (m == null) {
            return 0;
        }
        if (luminance <= 0) {
            if (attached && light != null) {
                try {
                    m.remove(light);
                } finally {
                    attached = false;
                    lastLuminance = 0;
                }
            }
            return 0;
        }
        if (light == null) {
            light = new LineLightBehavior(new Vector3d(x, y + 0.25D, z),
                    new Vector3d(x, y + 1.50D, z), luminance);
            lastLuminance = luminance;
        } else {
            // Knee -> eye height: the light comes out of the hand, not out of the feet.
            light.setStartPoint(x, y + 0.25D, z);
            light.setEndPoint(x, y + 1.50D, z);
            if (luminance != lastLuminance) {
                light.setLuminance(luminance);
                lastLuminance = luminance;
            }
        }
        if (!attached) {
            m.add(light);
            attached = true;
        }
        return luminance;
    }

    /** Drops the behaviour for good (world unload / master switch). */
    static void clear() {
        if (manager != null && attached && light != null) {
            try {
                manager.remove(light);
            } catch (RuntimeException ignored) {
                // A detach that fails is not worth a crash; the snapshot is dropped anyway.
            }
        }
        attached = false;
        lastLuminance = 0;
        light = null;
    }
}
