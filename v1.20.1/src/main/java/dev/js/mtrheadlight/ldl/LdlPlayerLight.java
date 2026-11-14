package dev.js.mtrheadlight.ldl;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import net.minecraft.util.math.BlockPos;

/**
 * LambDynamicLights 4.x glue for the <b>player-held</b> dynamic light — the only LDL code left in the
 * 1.20.1 module.
 *
 * <h2>Which API actually moves light (read off the 4.4.0 jar, not guessed)</h2>
 * <pre>
 *   DynamicLightsInitializer  (dev.lambdaurora.lambdynlights.api, key "lambdynlights:initializer")
 *     -&gt; DynamicLightsContext#dynamicLightBehaviorManager()
 *     -&gt; DynamicLightBehaviorManager#add(DynamicLightBehavior) / remove(...)
 * </pre>
 * {@code LambDynLights#addLightSource} wraps the behaviour in a {@code DeferredDynamicLightSource}
 * and {@code DynamicLightingEngine} calls {@code behavior.lightAtPos(pos, 1.935483870967742)} for
 * every block whose 8³ cell is within one cell of the behaviour's registered cell. So a behaviour is
 * a real light source on its own: it does <b>not</b> go through LDL's entity pipeline, which is the
 * pipeline that has been flaky on this client (see {@code docs/ldl-1.19.4-ab.md}).
 *
 * <h2>Why our own behaviour and not {@code LineLightBehavior}</h2>
 * LDL 4.4 ships {@code LineLightBehavior}, and the previous version used a 1.25-block vertical
 * segment as a fake point light. That is wrong in a way that is easy to miss:
 * {@code LineLightBehavior#getBoundingBox()} is {@code floor(start) .. ceil(end)} of the two
 * endpoints only, so for a segment at one (x, z) the box is 1–2 blocks wide.
 * {@code DeferredDynamicLightSource} uses that box twice — to decide which cells the light is
 * registered in, and (together with the <em>previous</em> box) which chunk sections get rebuilt.
 * A box that never covers the ground around the player therefore never invalidates the sections
 * that need re-lighting: the light is "there" numerically but only the player's own column ever
 * gets rebuilt, which reads as "nothing lights up".
 *
 * <p>{@link Point} is a real point light instead: the box is the luminance radius in all six
 * directions (9³ cells worst case), and {@code lightAtPos} uses LDL's own falloff constant so the
 * result is the same shape as LDL's built-in sources.
 *
 * <h2>Off means detached</h2>
 * A behaviour parked at luminance 0 is not proof the ground went dark, because LDL only re-lights
 * what a behaviour reports through {@code hasChanged()}. So luminance 0 calls
 * {@code manager.remove(...)}; the add happens on the next item pick-up. Add/remove only on the
 * transition, never per tick.
 *
 * <h2>Never load this class without LDL</h2>
 * Every reference to an LDL type lives in here, and only {@link PlayerDynamicLight} calls it. That
 * facade checks the mod id first and wraps every call in {@code try/catch Throwable}, so on a client
 * without LDL this class is never initialised and a missing API class cannot reach a stack trace.
 */
public final class LdlPlayerLight {

    /**
     * The falloff LDL itself uses: {@code SpatialLookupDeferredEntry} calls
     * {@code lightAtPos(pos, 1.935483870967742)}, i.e. a level-15 light reaches ~7.75 blocks.
     */
    static final double FALLOFF = 1.935483870967742D;

    /** LDL's engine cell size ({@code CELL_SIZE = ceil(MAX_RADIUS)}); used to size the bounding box. */
    private static final int MAX_RADIUS = 8;

    /** Handed over by LDL through {@code lambdynlights:initializer}; null until then. */
    private static DynamicLightBehaviorManager manager;

    /** The one behaviour we own. Created on the first lit tick, reused afterwards. */
    private static Point light;

    /** Whether {@link #light} is currently registered on {@link #manager}. */
    private static boolean attached;

    private LdlPlayerLight() {
    }

    /**
     * Called from the {@code lambdynlights:initializer} entrypoint. Runs on client init, so the
     * manager is in place before the first tick that might want a light.
     */
    static void install(final DynamicLightBehaviorManager behaviorManager) {
        manager = behaviorManager;
        attached = false;
        light = null;
    }

    /** True once LDL handed us a manager — the facade uses this to tell "installed" from "loaded". */
    static boolean ready() {
        return manager != null;
    }

    /**
     * Forgets the attachment without touching LDL.
     *
     * <p>LDL calls {@code LambDynLights#onChangeWorld()} from its {@code MinecraftClient#setWorld}
     * mixin, and that method drops <b>every</b> entry of its light source set - ours included, with no
     * callback to tell us. {@code DynamicLightBehaviorManager} has no "is it still registered?"
     * query, so the only way to notice is to reset on a world change and let the next tick add a
     * fresh behaviour. Without this, the light dies for good the first time the player leaves a world
     * (the {@code attached} flag keeps saying "already added" while LDL holds nothing).
     */
    static void resetAttachment() {
        attached = false;
        light = null;
    }

    /** True while a light is actually registered on LDL (i.e. the ground should be lit). */
    static boolean attached() {
        return attached;
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
        final int level = Math.max(0, Math.min(15, luminance));
        if (level <= 0) {
            if (attached && light != null) {
                try {
                    m.remove(light);
                } finally {
                    attached = false;
                }
            }
            return 0;
        }
        // Chest height: the light comes out of the hand, not out of the feet.
        final double ly = y + 1.0D;
        if (light == null) {
            light = new Point(x, ly, z, level);
        } else {
            light.move(x, ly, z, level);
        }
        if (!attached) {
            m.add(light);
            attached = true;
        }
        return level;
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
        light = null;
    }

    /**
     * A spherical light at the player's chest. Implements the public API interface directly instead
     * of abusing a line segment, so the bounding box — and therefore the set of chunk sections LDL
     * re-lights — covers the whole falloff radius.
     */
    static final class Point implements DynamicLightBehavior {

        private double x;
        private double y;
        private double z;
        private int luminance;

        /** Last values {@link #hasChanged()} reported, so a still player costs nothing per tick. */
        private double seenX;
        private double seenY;
        private double seenZ;
        private int seenLuminance = -1;
        private boolean first = true;

        Point(final double x, final double y, final double z, final int luminance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.luminance = luminance;
        }

        void move(final double nx, final double ny, final double nz, final int nl) {
            this.x = nx;
            this.y = ny;
            this.z = nz;
            this.luminance = nl;
        }

        @Override
        public double lightAtPos(final BlockPos pos, final double falloff) {
            final double dx = (pos.getX() + 0.5D) - x;
            final double dy = (pos.getY() + 0.5D) - y;
            final double dz = (pos.getZ() + 0.5D) - z;
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            final double level = luminance - distance * falloff;
            return level < 0.0D ? 0.0D : Math.min(level, 15.0D);
        }

        @Override
        public DynamicLightBehavior.BoundingBox getBoundingBox() {
            // Round out to whole blocks and then add the falloff radius, so every cell (and every
            // chunk section) the light can reach is registered and gets invalidated on the move.
            final int radius = radius();
            final int bx = (int) Math.floor(x);
            final int by = (int) Math.floor(y);
            final int bz = (int) Math.floor(z);
            return new DynamicLightBehavior.BoundingBox(bx - radius, by - radius, bz - radius,
                    bx + radius, by + radius, bz + radius);
        }

        @Override
        public boolean hasChanged() {
            if (first) {
                first = false;
                snapshot();
                return true;
            }
            // A twentieth of a block is far below what the light engine can show, and skipping it
            // keeps a walking player from rebuilding the same section every frame.
            if (Math.abs(x - seenX) < 0.05D && Math.abs(y - seenY) < 0.05D
                    && Math.abs(z - seenZ) < 0.05D && luminance == seenLuminance) {
                return false;
            }
            snapshot();
            return true;
        }

        private void snapshot() {
            seenX = x;
            seenY = y;
            seenZ = z;
            seenLuminance = luminance;
        }

        private int radius() {
            final int r = (int) Math.ceil(luminance / FALLOFF);
            return Math.max(1, Math.min(r, MAX_RADIUS));
        }
    }
}
