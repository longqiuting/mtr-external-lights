package dev.js.mtrheadlight;

import dev.lambdaurora.lambdynlights.DynamicLightSource;
import dev.lambdaurora.lambdynlights.LambDynLights;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Light backend for LambDynamicLights 2.x (MC 1.18-1.19).
 *
 * <p>2.x has no line-light concept: the API only accepts {@link DynamicLightSource} points. A segment
 * is therefore approximated by {@link #POINTS_PER_LINE} evenly spaced point lights, which is what the
 * light engine accumulates per block anyway.
 *
 * <h2>Contract with LDL 2.x - and the three ways to break the whole engine</h2>
 *
 * <p>In LDL 2.x a dynamic light is baked into a chunk mesh: {@code CommonWorldRendererMixin} injects
 * into {@code WorldRenderer.getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)} and adds
 * {@code LambDynLights.getLightmapWithDynamicLight(pos, lightmap)} there. Consequence - <b>the block
 * light is written into the mesh at build time</b>. Moving a light source changes nothing on screen
 * until the affected chunk sections are rebuilt, which is why every point keeps its own last position
 * / luminance and its own set of lit sections (see {@code update} / {@code refresh}).
 *
 * <p>Because everything above this class is "just a light", it is easy to forget that <b>we are
 * guests inside somebody else's engine</b> - and that engine is shared with the player's torch, every
 * block entity and every other mod. Three rules follow, and all three were broken in 0.1.1 on 1.19.4:
 *
 * <ol>
 *   <li><b>Never call back into {@code LambDynLights} while it is calling us.</b>
 *       {@code updateAll} iterates the source set while holding {@code lightSourcesLock.readLock()};
 *       every public query ({@code getDynamicLightLevel}, {@code getLightSourcesCount},
 *       {@code containsLightSource}) takes that same read lock again, and
 *       {@code ReentrantReadWriteLock} makes a re-entrant read fail to arrive as soon as a writer
 *       (our {@code addLightSource}/{@code removeLightSource} on the tick thread) is queued - while
 *       the reader we already hold is exactly what the writer waits for. That is a permanent
 *       deadlock of the render thread inside {@code updateAll}: from then on LDL updates nothing and
 *       no chunk can be rebuilt with dynamic light, <b>including the player's torch</b>. All
 *       diagnostics therefore run from {@link #tick()} (client tick thread, holding no LDL lock),
 *       never from a callback.</li>
 *   <li><b>Never publish a value LDL cannot handle.</b> {@code getLuminance()} is multiplied into
 *       the lightmap level and {@code NaN} is not filtered by {@code MathHelper.clamp} (NaN compares
 *       false both ways), so one bad source can poison every position query; a value above 15
 *       overflows the lightmap's block-light field. Coordinates must be finite and inside the world
 *       border. Everything handed out here is clamped to 0..15 and to a finite, in-range position.</li>
 *   <li><b>Never let an exception escape a callback.</b> LDL calls our getters from
 *       {@code maxDynamicLightLevel} with no try/finally around its read lock, and calls
 *       {@code lambdynlights$updateDynamicLight} from a loop with no try/catch at all. One throw
 *       either leaks the lock (deadlock, see 1) or aborts the whole update pass.</li>
 * </ol>
 */
public final class Ldl2Backend implements LightBackend {
    private static final int POINTS_PER_LINE = 8;
    /** LDL's own ceiling: above 15 a level overflows the lightmap's block-light field. */
    private static final int MAX_LUMINANCE = 15;
    /** Vanilla world border is 3e7; anything beyond is a broken pose, not a light. */
    private static final double COORD_LIMIT = 3.0E7D;
    /**
     * {@code LambDynLights.MAX_RADIUS} (7.75) in all three 2.x jars we ship against
     * (2.1.0+1.17 / 2.2.0+1.19.2 / 2.3.1+1.19.4, verified with javap -constants). Beyond this a
     * source contributes nothing, so only sections closer than this need a rebuild.
     *
     * <p>{@code LambDynLights.maxDynamicLightLevel} is the authority: it measures the distance from
     * the <em>block centre</em> ({@code blockX + 0.5 - lightX}) and rejects anything with
     * {@code r^2 > 60.0625} (= 7.75^2), otherwise returns {@code (1 - r / 7.75) * luminance}. A
     * point therefore reaches at most one section beyond its own in each axis, which is why the
     * marked set is bounded at 8 sections rather than 27.
     */
    private static final double LIGHT_RADIUS = 7.75D;
    /** A light that moved less than this is treated as still - no rebuild, no work at all. */
    private static final double MOVE_EPSILON = 0.05D;
    /**
     * How far a point must travel before the sections it <em>already occupies</em> are re-baked.
     *
     * <p>Rebuilding only on set changes is not enough. While a point stays inside the same
     * 8-section neighbourhood its section meshes keep the light exactly where it was when they were
     * last built, so the lit patch is frozen and then jumps a whole section - on screen that reads
     * as the light being cut off along a section border (the reported banding). Re-baking once the
     * point has moved half a block keeps the error under what the eye reads as a seam at every
     * speed: a 25 blocks/s train travels 1.25 blocks per 50 ms update pass.
     */
    private static final double REFRESH_MOVE = 0.5D;
    /** Minimum time between two re-bakes of the same point, so a slow creep cannot spin. */
    private static final long REFRESH_MIN_MS = 80L;
    /**
     * Section rebuilds handed to LDL per 50 ms update pass, all sources together, counting each
     * section once. A beam's 8 point lights overlap heavily and cover ~6 sections, so this is what
     * one nearby light needs for a full refresh per pass; a second nearby light shares the budget
     * instead of doubling the work.
     */
    private static final int REFRESH_PER_PASS = 8;
    /** Only lights this close to the camera are worth re-baking; farther sections are not visible. */
    private static final double REFRESH_RANGE = 32.0D;
    /** Distinct sections one pass may touch; matches {@link #REFRESH_PER_PASS} with slack. */
    private static final int PASS_MEMORY = 16;
    /**
     * A point with a 7.75 block radius straddles at most two chunk sections per axis (it can only
     * be near one of the two boundaries), so 2*2*2 = 8 sections is the hard upper bound. The arrays
     * are fixed size: the per-frame path allocates nothing.
     */
    private static final int MAX_SECTIONS = 8;

    private final Map<String, PointLight[]> lines = new HashMap<>();

    /** TEMP DIAG: the live line map, so the static probe can reach it. */
    private static volatile Map<String, PointLight[]> probeLines;

    Ldl2Backend() {
        probeLines = lines;
    }

    /** Number of point sources currently registered with LDL; diagnostics only. */
    private static volatile int sourceCount;
    /** How many sources we handed to LDL / took back; diagnostics only (a leak shows up here). */
    private static volatile int addCount;
    private static volatile int removeCount;

    // --- scheduling state (render thread only: updateAll runs from WorldRenderEvents.START) ------
    /** Start of the current 50 ms update pass, and the rebuild slots left in it. */
    private static long passStartMs = Long.MIN_VALUE;
    private static int passSlots;
    /** Sections already re-baked in this pass, so overlapping point lights do not re-mark them. */
    private static final long[] passDone = new long[PASS_MEMORY];
    private static int passDoneCount;
    /** Camera position, sampled once per pass: only sections near it are worth re-baking. */
    private static Vec3d cameraPos;
    private static long cameraMs = Long.MIN_VALUE;

    // --- diagnostics (tick thread only) ---------------------------------------------------------
    private static long diagWindowStart = System.currentTimeMillis();
    private static int diagMoved;
    private static int diagRelight;
    /** Tick counter for the once-every-two-seconds reconcile and the five-second diag line. */
    private static long diagTicks;
    private static boolean mismatchLogged;
    /** Last emitted lines, so the five-second probe does not own the log file. */
    private static String lastRegistryLine = "";
    private static String lastProbeLine = "";

    // --- per-line entry points ------------------------------------------------------------------

    @Override
    public void updateLine(final String key, final Vec3d start, final Vec3d end, final int luminance) {
        if (!registrationEnabled()) {
            // A/B switch off - or the config says so: nothing of ours may sit in LDL's set, or the
            // comparison would be meaningless. Lines are rebuilt by the next tick after it flips.
            removeLine(key);
            return;
        }
        PointLight[] points = lines.get(key);
        if (points == null) {
            points = new PointLight[POINTS_PER_LINE];
            for (int i = 0; i < points.length; i++) {
                final PointLight light = new PointLight();
                points[i] = light;
                attach(light);
            }
            lines.put(key, points);
        }
        for (int i = 0; i < points.length; i++) {
            final double t = points.length == 1 ? 0.0D : (double) i / (double) (points.length - 1);
            points[i].set(
                    start.x + (end.x - start.x) * t,
                    start.y + (end.y - start.y) * t,
                    start.z + (end.z - start.z) * t,
                    luminance
            );
        }
    }

    @Override
    public void hideLine(final String key) {
        final PointLight[] points = lines.get(key);
        if (points != null) {
            // luminance 0 is a real change: the next update pass rebuilds the sections the light
            // used to sit in, which is what makes the beam go away immediately.
            for (final PointLight light : points) {
                light.luminance = 0;
            }
        }
    }

    @Override
    public void removeLine(final String key) {
        final PointLight[] points = lines.remove(key);
        if (points != null) {
            for (final PointLight light : points) {
                detach(light);
            }
        }
    }

    @Override
    public void clearAll() {
        for (final Iterator<PointLight[]> it = lines.values().iterator(); it.hasNext(); ) {
            for (final PointLight light : it.next()) {
                detach(light);
            }
            it.remove();
        }
        PROBE.clear();
    }

    // --- registration ----------------------------------------------------------------------------

    /** Whether our sources may be handed to LDL at all (the A/B switch, default on). */
    private static boolean registrationEnabled() {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            return cfg == null || cfg.ldlRegisterSources;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void attach(final PointLight light) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            final World world = client == null ? null : client.world;
            if (world == null) {
                // No world: LDL would NPE on our getDynamicLightWorld(), and a light from a previous
                // world must never be reused. The line is re-attached on the next tick.
                return;
            }
            light.world = world;
            final LambDynLights engine = LambDynLights.get();
            if (engine == null) {
                return;
            }
            engine.addLightSource(light);
            // addLightSource() validates silently (world.isClient(), mode enabled, "already in the
            // set") and returns void, so ask it back. containsLightSource takes LDL's read lock,
            // which is fine here: this runs on the tick thread and we hold no lock of our own.
            if (engine.containsLightSource(light)) {
                if (!light.registered) {
                    light.registered = true;
                    sourceCount++;
                    addCount++;
                }
                PROBE.add(light);
                if (!initLogged) {
                    initLogged = true;
                    logInit();
                }
            }
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.debug("[mtr-headlight] could not add light source", t);
        }
    }

    // --- TEMP DIAG ------------------------------------------------------------------------------
    /** Sources currently handed to LDL; the probe line picks one of them. */
    private static final java.util.List<PointLight> PROBE =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean initLogged;

    /** One line at the first successful registration: which backend, which LDL, did it take it. */
    private static void logInit() {
        try {
            String ldlVersion = "unknown";
            try {
                ldlVersion = net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getModContainer("lambdynlights")
                        .map(container -> container.getMetadata().getVersion().getFriendlyString())
                        .orElse("not-installed");
            } catch (Throwable ignored) {
                // FabricLoader unavailable: keep "unknown".
            }
            final LambDynLights engine = LambDynLights.get();
            MtrHeadlightClient.LOGGER.info(
                    "ldl2 init: backend={} api={} ldl={} engine={} addLightSource=ok"
                            + " engineSources={}",
                    Ldl2Backend.class.getSimpleName(),
                    DynamicLightSource.class.getName(), ldlVersion,
                    engine == null ? "null" : engine.getClass().getName(),
                    engine == null ? -1 : engine.getLightSourcesCount());
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] ldl2 init probe failed", t);
        }
    }

    /**
     * Once per client tick, on the tick thread. Deliberately the <em>only</em> place that talks to
     * LDL for diagnostics: we hold no LDL lock here, so a queued writer can never turn this into
     * the read-lock deadlock described in the class comment.
     */
    @Override
    public void tick() {
        try {
            diagTicks++;
            if (diagTicks % 100L == 0L) {
                reconcile();
            }
            if (System.currentTimeMillis() - diagWindowStart >= 5000L) {
                diagSample();
            }
        } catch (Throwable t) {
            // Diagnostics must never break the tick.
            MtrHeadlightClient.LOGGER.debug("[mtr-headlight] ldl2 diag failed", t);
        }
    }

    /**
     * LDL drops <b>every</b> source on {@code MinecraftClient.setWorld} (its own
     * {@code clearLightSources()}) and can also drop ours through {@code removeLightSources(...)}.
     * When that happens our map still thinks the sources are live, so nothing would ever re-register
     * them and our lights would silently never come back for the rest of the session. Comparing our
     * own count against the engine's catches it - and rebuilding is cheap.
     */
    private void reconcile() {
        if (sourceCount <= 0) {
            mismatchLogged = false;
            return;
        }
        final LambDynLights engine = LambDynLights.get();
        if (engine == null) {
            return;
        }
        final int total = engine.getLightSourcesCount();
        if (total < sourceCount) {
            if (!mismatchLogged) {
                mismatchLogged = true;
                MtrHeadlightClient.LOGGER.info(
                        "ldl2: LDL dropped our sources (ours={} engine={}) - re-registering",
                        sourceCount, total);
            }
            clearAll();
        } else {
            mismatchLogged = false;
        }
    }

    /**
     * One line every five seconds: our bookkeeping next to LDL's own.
     *
     * <p>{@code ldlOthers} is the number that matters for this investigation - LDL's set minus ours.
     * If it stays at 0 while the player is holding a torch, the torch source is not in the engine at
     * all; if it is 1+ and the torch is still dark, our sources are not the ones being dropped.
     *
     * <p>{@code bake} is the lightmap LDL would write for a block at one of our lit sources, built
     * from the same public call the chunk mixin uses. {@code brightened=true} means LDL's maths and
     * lookup are fine and anything still dark is downstream of LDL (chunk rebuild / lightmap
     * texture), which no amount of fiddling with our sources can fix.
     */
    private static void diagSample() {
        diagWindowStart = System.currentTimeMillis();
        final int moved = diagMoved;
        final int relight = diagRelight;
        diagMoved = 0;
        diagRelight = 0;

        final LambDynLights engine = LambDynLights.get();
        final int total = engine == null ? -1 : engine.getLightSourcesCount();
        final int updated = engine == null ? -1 : engine.getLastUpdateCount();
        final int others = total < 0 ? -1 : total - sourceCount;
        RailDiag.ldl2(sourceCount, moved, relight);
        // Only when something actually moved: a five-second heartbeat would otherwise own the log
        // file again (the 0.1.1 build produced a 14 MB log in 90 seconds).
        final String registry = sourceCount + "/" + total + "/" + others + "/"
                + addCount + "/" + removeCount + "/" + registrationEnabled();
        if (!registry.equals(lastRegistryLine)) {
            lastRegistryLine = registry;
            try {
                MtrHeadlightClient.LOGGER.info(
                        "[mtr-headlight] ldl2 registry: ours={} ldlTotal={} ldlOthers={}"
                                + " adds={} removes={} updates={} suspended={}",
                        sourceCount, total, others, addCount, removeCount, updated,
                        !registrationEnabled());
            } catch (Throwable ignored) {
                // Logging must never break the tick.
            }
        }
        ldlProbe();
    }

    /**
     * TEMP DIAG: what LDL itself answers at one of our own sources. Separates "LDL never took the
     * source" from "LDL has it and computes a level, but the mesh never shows it".
     */
    private static void ldlProbe() {
        try {
            PointLight sample = null;
            for (final PointLight point : PROBE) {
                if (point.getLuminance() > 0) {
                    sample = point;
                    break;
                }
            }
            if (sample == null) {
                MtrHeadlightClient.LOGGER.info("ldlprobe: no lit source (registered={})",
                        sourceCount);
                return;
            }
            final BlockPos pos = new BlockPos((int) Math.floor(sample.getDynamicLightX()),
                    (int) Math.floor(sample.getDynamicLightY()),
                    (int) Math.floor(sample.getDynamicLightZ()));
            final LambDynLights engine = LambDynLights.get();
            // 0xF000F0 = sky 15, block 0 - the brightest possible vanilla lightmap, so any change is
            // LDL's own doing and not a comparison artefact.
            final int vanilla = 0xF000F0;
            final int baked = engine.getLightmapWithDynamicLight(pos, vanilla);
            final String probe = pos.toShortString() + "|" + sample.getLuminance() + "|" + baked;
            if (probe.equals(lastProbeLine)) {
                return;
            }
            lastProbeLine = probe;
            MtrHeadlightClient.LOGGER.info(
                    "ldlprobe: at={} ownLum={} ldlLevel={} ldlSources={} ldlUpdates={}"
                            + " bake=0x{} in=0x{} brightened={}",
                    pos.toShortString(), sample.getLuminance(), engine.getDynamicLightLevel(pos),
                    engine.getLightSourcesCount(), engine.getLastUpdateCount(),
                    Integer.toHexString(baked), Integer.toHexString(vanilla), baked != vanilla);
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] ldl probe failed", t);
        }
    }

    private static void detach(final PointLight light) {
        try {
            final LambDynLights engine = LambDynLights.get();
            if (engine != null) {
                engine.removeLightSource(light);
            }
            PROBE.remove(light);
            light.world = null;
            if (light.registered) {
                light.registered = false;
                sourceCount--;
                removeCount++;
            }
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.debug("[mtr-headlight] could not remove light source", t);
        }
    }

    /** Asks LDL to rebuild one chunk section; the single funnel for every mark we make. */
    private static void scheduleRebuild(final WorldRenderer renderer, final long sectionPos) {
        try {
            LambDynLights.scheduleChunkRebuild(renderer, sectionPos);
            diagRelight++;
        } catch (Throwable t) {
            // A missing/renamed LDL helper must not break the tick; the light just stays stale.
            MtrHeadlightClient.LOGGER.debug("[mtr-headlight] could not schedule a chunk rebuild", t);
        }
    }

    /**
     * Claims a section rebuild for this pass.
     *
     * <p>{@code LambDynLights.updateAll} runs on the render thread every 50 ms. Two things are
     * enforced here: a section is rebuilt at most once per pass (the 8 point lights of one beam
     * overlap, and re-marking an already dirty section is free but re-marking one we just claimed
     * would waste the budget on nothing), and at most {@link #REFRESH_PER_PASS} distinct sections
     * are rebuilt per pass across every train, which bounds the mesh work.
     *
     * @return true when the caller should schedule this section now.
     */
    private static boolean claimSection(final long sectionPos, final long nowMs) {
        if (passStartMs == Long.MIN_VALUE || nowMs - passStartMs >= 40L) {
            passStartMs = nowMs;
            passSlots = REFRESH_PER_PASS;
            passDoneCount = 0;
        }
        if (PointLight.contains(passDone, passDoneCount, sectionPos)) {
            return false;
        }
        if (passSlots <= 0) {
            return false;
        }
        passSlots--;
        if (passDoneCount < passDone.length) {
            passDone[passDoneCount++] = sectionPos;
        }
        return true;
    }

    /** Camera position, sampled at most once per update pass; null when the client is not ready. */
    private static Vec3d camera() {
        final long nowMs = System.currentTimeMillis();
        if (cameraMs != Long.MIN_VALUE && nowMs - cameraMs < 40L) {
            return cameraPos;
        }
        cameraMs = nowMs;
        try {
            cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        } catch (Throwable t) {
            cameraPos = null;
        }
        return cameraPos;
    }

    /** A free-floating point light; LDL 2.x accumulates these per block. */
    private static final class PointLight implements DynamicLightSource {
        // Written by the client tick thread, read by LDL's render-thread pass.
        volatile double x;
        volatile double y;
        volatile double z;
        volatile int luminance;
        /** Whether LDL currently holds this source; keeps the diagnostic count honest. */
        volatile boolean registered;
        /** World this light was last attached in. */
        volatile World world;

        // Render-thread state: where the light was when the chunks were last rebuilt.
        private double prevX;
        private double prevY;
        private double prevZ;
        private int lastLuminance;
        /** When this point last re-baked the sections it occupies; 0 = never. */
        private long lastRefreshMs;
        /** Rotating start index into the section set, so the budget does not always hit the same ones. */
        private int refreshCursor;
        private long[] lit = new long[MAX_SECTIONS];
        private long[] next = new long[MAX_SECTIONS];
        private int litCount;

        /**
         * Publishes a new position / level. Anything LDL cannot survive is dropped rather than
         * stored: a NaN or an out-of-world coordinate is turned into "off" and the last good
         * position is kept, so the point neither jumps to 0,0,0 nor poisons the engine's max().
         */
        void set(final double x, final double y, final double z, final int luminance) {
            if (!isFinite(x) || !isFinite(y) || !isFinite(z)
                    || Math.abs(x) > COORD_LIMIT || Math.abs(y) > COORD_LIMIT
                    || Math.abs(z) > COORD_LIMIT) {
                this.luminance = 0;
                return;
            }
            this.x = x;
            this.y = y;
            this.z = z;
            this.luminance = clampLuminance(luminance);
        }

        private static int clampLuminance(final int value) {
            return value < 0 ? 0 : (value > MAX_LUMINANCE ? MAX_LUMINANCE : value);
        }

        @Override
        public double getDynamicLightX() {
            try {
                return isFinite(x) ? x : 0.0D;
            } catch (Throwable t) {
                return 0.0D;
            }
        }

        @Override
        public double getDynamicLightY() {
            try {
                return isFinite(y) ? y : 0.0D;
            } catch (Throwable t) {
                return 0.0D;
            }
        }

        @Override
        public double getDynamicLightZ() {
            try {
                return isFinite(z) ? z : 0.0D;
            } catch (Throwable t) {
                return 0.0D;
            }
        }

        @Override
        public World getDynamicLightWorld() {
            try {
                // Always the live client world. LDL clears its source set on setWorld(), so a world
                // captured at attach time goes stale the moment the player changes dimension and
                // then containsLightSource() answers false for us forever.
                final MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.world != null) {
                    return client.world;
                }
                return world;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean isDynamicLightEnabled() {
            // Never calls LDL: the interface default is
            // LambDynLights.get().containsLightSource(this), i.e. a read-lock acquisition from a
            // call site we do not control.
            return registered && luminance > 0;
        }

        @Override
        public void setDynamicLightEnabled(final boolean enabled) {
            // Our attach/detach own the lifecycle. The default implementation calls
            // addLightSource/removeLightSource behind our back (write lock + a rebuild pass), which
            // desyncs sourceCount and can drop a source we still need.
        }

        @Override
        public void resetDynamicLight() {
            try {
                lastLuminance = 0;
            } catch (Throwable ignored) {
                // Nothing to do - see the class comment on never throwing at LDL.
            }
        }

        @Override
        public int getLuminance() {
            try {
                return clampLuminance(luminance);
            } catch (Throwable t) {
                return 0;
            }
        }

        @Override
        public void dynamicLightTick() {
            // Nothing: we push positions from the tick thread instead.
        }

        /**
         * Not a hook LDL calls: in 2.x {@code LambDynLights.updateAll} only calls
         * {@code lambdynlights$updateDynamicLight}, and {@code shouldUpdateDynamicLight} is a helper
         * of LDL's own entity mixins (kept for interface completeness, and the only thing LDL reads
         * it for is its own delay bookkeeping). LDL's pass is already gated on the dynamic-lights
         * mode being enabled, so the cheap movement check in {@code update} is the real throttle.
         */
        @Override
        public boolean shouldUpdateDynamicLight() {
            return true;
        }

        @Override
        public boolean lambdynlights$updateDynamicLight(final WorldRenderer renderer) {
            // Runs inside LDL's updateAll, under its read lock. Nothing in here may call back into
            // LambDynLights (see rule 1 in the class comment) and nothing may throw (rule 3).
            try {
                return update(renderer);
            } catch (Throwable t) {
                // Never let one bad frame kill the render loop.
                return false;
            }
        }

        private boolean update(final WorldRenderer renderer) {
            if (renderer == null) {
                return false;
            }
            final int luminanceNow = getLuminance();
            final double nx = getDynamicLightX();
            final double ny = getDynamicLightY();
            final double nz = getDynamicLightZ();
            final double dx = nx - prevX;
            final double dy = ny - prevY;
            final double dz = nz - prevZ;
            final double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            final boolean luminanceChanged = luminanceNow != lastLuminance;
            if (moved <= MOVE_EPSILON && !luminanceChanged) {
                // Still, and no brightness change: nothing to rebuild, nothing to do.
                return false;
            }
            prevX = nx;
            prevY = ny;
            prevZ = nz;
            lastLuminance = luminanceNow;

            final int count = collectSections(nx, ny, nz, luminanceNow, next);

            // Sections the light entered: rebuild so the mesh picks the light up.
            for (int i = 0; i < count; i++) {
                if (!contains(lit, litCount, next[i])) {
                    scheduleRebuild(renderer, next[i]);
                }
            }
            // Sections the light left: rebuild so the baked light is dropped again.
            for (int i = 0; i < litCount; i++) {
                if (!contains(next, count, lit[i])) {
                    scheduleRebuild(renderer, lit[i]);
                }
            }

            // Sections that stay in the set: their mesh still holds the light from the last build,
            // so without this the patch only moves on border crossings (one visible jump per
            // section). Throttled - see REFRESH_MOVE / REFRESH_PER_PASS.
            refresh(renderer, nx, ny, nz, count, luminanceChanged, moved);

            final long[] swap = lit;
            lit = next;
            next = swap;
            litCount = count;
            diagMoved++;
            return true;
        }

        /**
         * Re-bakes the sections this point occupies, so the lit patch follows the light instead of
         * jumping a whole section at a time.
         *
         * <p>Gated three ways: the point has to have moved {@link #REFRESH_MOVE} (or changed
         * brightness), at most one re-bake per {@link #REFRESH_MIN_MS} per point, and the pass-wide
         * {@link #claimSection} budget is shared with every other point and train. Lights beyond
         * {@link #REFRESH_RANGE} from the camera are skipped - their sections are not on screen and
         * would only eat the budget of the ones that are.
         */
        private void refresh(final WorldRenderer renderer, final double nx, final double ny,
                            final double nz, final int count, final boolean luminanceChanged,
                            final double moved) {
            if (count == 0 || (!luminanceChanged && moved < REFRESH_MOVE)) {
                return;
            }
            final long nowMs = System.currentTimeMillis();
            if (lastRefreshMs != 0L && nowMs - lastRefreshMs < REFRESH_MIN_MS) {
                return;
            }
            final Vec3d cam = camera();
            if (cam != null) {
                final double ex = nx - cam.x;
                final double ey = ny - cam.y;
                final double ez = nz - cam.z;
                if (ex * ex + ey * ey + ez * ez > REFRESH_RANGE * REFRESH_RANGE) {
                    return;
                }
            }
            lastRefreshMs = nowMs;
            // Start at a rotating offset: when several beams compete for the pass budget, a fixed
            // order would always re-bake the same sections and starve the rest of the set.
            final int start = refreshCursor++ % count;
            for (int k = 0; k < count; k++) {
                final long sectionPos = next[(start + k) % count];
                if (claimSection(sectionPos, nowMs)) {
                    scheduleRebuild(renderer, sectionPos);
                }
            }
        }

        /**
         * Every chunk section the light can reach: {@code floor(x-R)..floor(x+R)} per axis, shifted
         * to section coordinates. {@code >>} is a floor division for negatives too, so no extra
         * rounding is needed. 0 when the light is off or the position is not a number.
         */
        private static int collectSections(final double x, final double y, final double z,
                                           final int luminance, final long[] out) {
            if (luminance <= 0 || !isFinite(x) || !isFinite(y) || !isFinite(z)) {
                return 0;
            }
            if (Math.abs(x) > COORD_LIMIT || Math.abs(z) > COORD_LIMIT
                    || Math.abs(y) > COORD_LIMIT) {
                return 0;
            }
            final int minX = floor(x - LIGHT_RADIUS) >> 4;
            final int maxX = floor(x + LIGHT_RADIUS) >> 4;
            final int minY = floor(y - LIGHT_RADIUS) >> 4;
            final int maxY = floor(y + LIGHT_RADIUS) >> 4;
            final int minZ = floor(z - LIGHT_RADIUS) >> 4;
            final int maxZ = floor(z + LIGHT_RADIUS) >> 4;
            int n = 0;
            for (int sx = minX; sx <= maxX; sx++) {
                for (int sy = minY; sy <= maxY; sy++) {
                    for (int sz = minZ; sz <= maxZ; sz++) {
                        if (n >= out.length) {
                            return n;
                        }
                        out[n++] = BlockPos.asLong(sx, sy, sz);
                    }
                }
            }
            return n;
        }

        private static boolean isFinite(final double value) {
            return !Double.isNaN(value) && !Double.isInfinite(value);
        }

        private static int floor(final double value) {
            final int i = (int) value;
            return value < (double) i ? i - 1 : i;
        }

        private static boolean contains(final long[] values, final int count, final long value) {
            for (int i = 0; i < count; i++) {
                if (values[i] == value) {
                    return true;
                }
            }
            return false;
        }

        /**
         * LDL calls this when the source is removed (and on {@code clearLightSources}), expecting the
         * chunks it used to light to be rebuilt - otherwise the light stays baked into the mesh where
         * the train used to be.
         */
        @Override
        public void lambdynlights$scheduleTrackedChunksRebuild(final WorldRenderer renderer) {
            try {
                if (renderer == null || litCount == 0) {
                    return;
                }
                final World world = getDynamicLightWorld();
                final MinecraftClient client = MinecraftClient.getInstance();
                if (world == null || client.world != world) {
                    // Different world: those sections are gone, their meshes with them.
                    return;
                }
                for (int i = 0; i < litCount; i++) {
                    scheduleRebuild(renderer, lit[i]);
                }
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.debug("[mtr-headlight] could not clear a light", t);
            }
        }
    }
}
