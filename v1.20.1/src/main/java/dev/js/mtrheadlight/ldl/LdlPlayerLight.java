package dev.js.mtrheadlight.ldl;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import net.minecraft.util.math.BlockPos;

/**
 * LambDynamicLights 4.x glue for the <b>player-held</b> dynamic light — the only LDL code left in the
 * 1.20.1 module.
 *
 * <h2>Which API actually moves light (read off the 4.4.0 jar with javap, not guessed)</h2>
 * <pre>
 *   DynamicLightsInitializer  (dev.lambdaurora.lambdynlights.api, key "lambdynlights:initializer")
 *     -&gt; DynamicLightsContext#dynamicLightBehaviorManager()
 *     -&gt; DynamicLightBehaviorManager#add(DynamicLightBehavior) / remove(...)
 * </pre>
 * Traced through {@code lambdynamiclights-4.4.0+1.20.1.jar}:
 * <ul>
 *   <li>{@code LambDynLights#initializeApi()} calls
 *       {@code invokeInitializers("lambdynlights:initializer")}, which does
 *       {@code FabricLoader#getEntrypointContainers("lambdynlights:initializer", DynamicLightsInitializer.class)}
 *       and then {@code initializer.onInitializeDynamicLights(DynamicLightsContext)} — the
 *       <em>context</em> overload, whose API default just forwards to the no-arg one.</li>
 *   <li>{@code dynamicLightBehaviorManager()} returns the internal
 *       {@code DynamicLightBehaviorSources}, whose {@code add} wraps the behaviour in a
 *       {@code DeferredDynamicLightSource} and calls {@code LambDynLights#addLightSource}.</li>
 *   <li>{@code LambDynLights#onEndLevelTick} then calls
 *       {@code DynamicLightingEngine#computeSpatialLookup(dynamicLightSources)} every world tick, and
 *       {@code DeferredDynamicLightSource#splitIntoDynamicLightEntries} registers one
 *       {@code SpatialLookupDeferredEntry} per <b>8-block cell</b> of
 *       {@link DynamicLightBehavior#getBoundingBox()}; the engine afterwards reads the 27 neighbouring
 *       cells around a block and calls {@code lightAtPos(pos, 1.935483870967742)}.</li>
 *   <li>Chunk rebuilds are gated by {@code getDynamicLightChunksToRebuild(force)}: the box itself plus
 *       the <em>previous</em> box, and — when {@code force} is false — only if
 *       {@link DynamicLightBehavior#hasChanged()} says so.</li>
 * </ul>
 * So a behaviour really is a first-class light source; it does not go through LDL's entity pipeline
 * (the pipeline documented as flaky on this client in {@code docs/ldl-1.19.4-ab.md}).
 *
 * <h2>Two ways in, one sink</h2>
 * The documented way to get a manager is the entrypoint ({@link PlayerLightInitializer}), and that is
 * the primary path. It is not the only one: {@link #acquireReflectively()} can also pull the very same
 * manager out of {@code LambDynLights.get().dynamicLightBehaviorManager()} (both members are public in
 * 4.4.0 — verified with {@code javap -p}). The reflection path exists because "the entrypoint key did
 * not fire" is invisible from inside a mod: Fabric only hands a namespaced key to a mod that declared
 * it, LDL only asks once at init, and a client that ran the two in the wrong order silently ends up
 * with a null manager forever. With the fallback, a light still appears, and
 * {@link #origin()} says which door it came through ({@code via=entrypoint|reflection}).
 *
 * <h2>Why our own behaviour and not {@code LineLightBehavior}</h2>
 * {@code LineLightBehavior#getBoundingBox()} is {@code floor(start) .. ceil(end)} of the two endpoints
 * only, so a 1.25-block vertical segment registers 1-2 cells and the sections around the player are
 * never invalidated — the light is "there" numerically and nothing lights up. {@link Point} is a real
 * point light: the box is the luminance radius in all six directions and {@code lightAtPos} uses LDL's
 * own falloff constant, so the result has the same shape as LDL's built-in sources.
 *
 * <h2>Off means detached</h2>
 * A behaviour parked at luminance 0 is not proof the ground went dark, because LDL only re-lights what
 * a behaviour reports through {@code hasChanged()}. So luminance 0 calls {@code manager.remove(...)};
 * the add happens again on the next light item. Add/remove only on the transition, never per tick.
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

    /** Re-verify LDL still holds our source this often (ticks) instead of every tick. */
    private static final int VERIFY_TICKS = 20;

    /** Handed over by LDL (entrypoint, or reflection); null until then. */
    private static DynamicLightBehaviorManager manager;

    /** The one behaviour we own. Created on the first lit tick, reused afterwards. */
    private static Point light;

    /** Whether {@link #light} is currently registered on {@link #manager}. */
    private static boolean attached;

    /** Which door the manager came through: {@code entrypoint}, {@code reflection} or {@code none}. */
    private static String origin = "none";

    /** Tick counter for {@link #VERIFY_TICKS}. */
    private static int verifyTicks;

    private LdlPlayerLight() {
    }

    /**
     * Called from the {@code lambdynlights:initializer} entrypoint. Runs on client init, so the
     * manager is in place before the first tick that might want a light.
     */
    static void install(final DynamicLightBehaviorManager behaviorManager) {
        if (behaviorManager == null) {
            return;
        }
        manager = behaviorManager;
        origin = "entrypoint";
        attached = false;
        light = null;
    }

    /**
     * Last-resort acquisition of the same manager without the entrypoint:
     * {@code LambDynLights.get().dynamicLightBehaviorManager()}.
     *
     * <p>Costs nothing when the entrypoint already worked (first line returns). Everything is looked up
     * by name inside {@code try/catch Throwable}, so an LDL that renamed either member simply leaves
     * the feature off instead of throwing.
     *
     * @return true when a manager is available after this call
     */
    static boolean acquireReflectively() {
        if (manager != null) {
            return true;
        }
        try {
            final Class<?> modClass = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
            final Object mod = modClass.getMethod("get").invoke(null);
            if (mod == null) {
                // LDL's client initialiser has not run yet; try again on a later tick.
                return false;
            }
            final Object m = modClass.getMethod("dynamicLightBehaviorManager").invoke(mod);
            if (m instanceof DynamicLightBehaviorManager behaviorManager) {
                manager = behaviorManager;
                origin = "reflection";
                attached = false;
                light = null;
                return true;
            }
        } catch (Throwable ignored) {
            // Not 4.x, renamed member, or no LDL: stay dark and quiet.
        }
        return false;
    }

    /** True once we hold a manager — the facade uses this to tell "installed" from "loaded". */
    static boolean ready() {
        return manager != null;
    }

    /** {@code entrypoint} / {@code reflection} / {@code none}, for the log line. */
    static String origin() {
        return origin;
    }

    /**
     * One-line view of LDL's own state, for the 5-second diagnostic:
     * {@code mode=FANCY ldlSources=3}. Reflective on purpose (impl classes, not API).
     *
     * <p>{@code mode=OFF} is the single most common reason a correctly registered behaviour produces
     * no light at all: {@code DynamicLightingEngine#getDynamicLightLevel} returns 0 before it even looks
     * at the spatial lookup, and {@code onEndLevelTick} skips {@code computeSpatialLookup} entirely.
     */
    static String status() {
        try {
            final Class<?> modClass = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
            final Object mod = modClass.getMethod("get").invoke(null);
            if (mod == null) {
                return "mode=booting ldlSources=0";
            }
            final Object cfg = modClass.getField("config").get(mod);
            final Object mode = cfg.getClass().getMethod("getDynamicLightsMode").invoke(cfg);
            final boolean enabled = (Boolean) mode.getClass().getMethod("isEnabled").invoke(mode);
            final String name = String.valueOf(mode.getClass().getMethod("getName").invoke(mode));
            return "mode=" + name + (enabled ? "" : "(off)")
                    + " ldlSources=" + sourcesCount();
        } catch (Throwable t) {
            return "mode=? ldlSources=?";
        }
    }

    /**
     * How many light sources LDL holds right now (its own entity/item sources plus our behaviour),
     * or -1 when the count cannot be read. Used to notice that LDL dropped everything behind our back
     * ({@code onChangeWorld} empties the set without telling anyone).
     */
    static int sourcesCount() {
        try {
            final Class<?> modClass = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
            final Object mod = modClass.getMethod("get").invoke(null);
            if (mod == null) {
                return -1;
            }
            return (Integer) modClass.getMethod("getLightSourcesCount").invoke(mod);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Forgets the attachment without touching LDL.
     *
     * <p>LDL calls {@code LambDynLights#onChangeWorld()} from its {@code MinecraftClient#setWorld}
     * mixin, and that method drops <b>every</b> entry of its light source set - ours included, with no
     * callback to tell us. {@code DynamicLightBehaviorManager} has no "is it still registered?" query,
     * so the only way to notice is to reset on a world change and let the next tick add a fresh
     * behaviour. Without this, the light dies for good the first time the player leaves a world (the
     * {@code attached} flag keeps saying "already added" while LDL holds nothing).
     */
    static void resetAttachment() {
        attached = false;
        verifyTicks = 0;
        // The behaviour instance survives: LDL's setWorld only empties its own source set, and re-adding
        // the same object is what makes the forced first rebuild do the right thing.
    }

    /** True while a light is registered on LDL (i.e. the ground should be lit). */
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
        if (manager == null && !acquireReflectively()) {
            return 0;
        }
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
                    // The instance is kept on purpose: LDL wraps whatever behaviour it is handed, and a
                    // behaviour that is removed and later re-added keeps its own hasChanged() history,
                    // which is what makes the first rebuild after a re-add a forced one instead of a
                    // "nothing changed" tick.
                }
            }
            return 0;
        }
        // Chest height: the light comes out of the hand, not out of the feet.
        final double ly = y + 1.0D;
        if (light == null) {
            light = new Point(x, ly, z, level);
            attached = false;
        } else {
            light.move(x, ly, z, level);
        }
        if (attached && ++verifyTicks >= VERIFY_TICKS) {
            verifyTicks = 0;
            // LDL empties its source set on a world change without a callback. If the set is empty
            // while we believe we are registered, our behaviour is gone - re-add it.
            if (sourcesCount() == 0) {
                attached = false;
            }
        }
        if (!attached) {
            m.add(light);
            attached = true;
            verifyTicks = 0;
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
        verifyTicks = 0;
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

        int luminance() {
            return luminance;
        }

        /**
         * Lamp level at a block centre, in LDL's own units: {@code level - distance * falloff}, below
         * zero snapped to zero and capped at 15. At zero distance this is exactly the held item's
         * luminance (torch 14, lantern 15), which is what makes the hand-held block look like the block
         * it is.
         */
        @Override
        public double lightAtPos(final BlockPos pos, final double falloff) {
            return levelAt((pos.getX() + 0.5D) - x, (pos.getY() + 0.5D) - y,
                    (pos.getZ() + 0.5D) - z, luminance, falloff);
        }

        /** Falloff maths, extracted so it can be exercised without a world. */
        static double levelAt(final double dx, final double dy, final double dz, final int luminance,
                              final double falloff) {
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
