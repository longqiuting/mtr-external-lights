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
 * <p>{@code LambDynLights.updateAll} calls nothing but {@code lambdynlights$updateDynamicLight}; LDL's
 * own entity source (mixin {@code lightsource.EntityMixin}) does all of the rebuild scheduling inside
 * that method and keeps an {@code LongOpenHashSet trackedLitChunkPos} "so that the sections can be
 * cleaned up on removal" (its own comment). {@code LambDynLights.removeLightSource} then calls
 * {@code lambdynlights$scheduleTrackedChunksRebuild} on the source it just dropped (bytecode:
 * {@code 47: iterator.remove / 53-66: client.worldRenderer / 69: invokeinterface
 * lambdynlights$scheduleTrackedChunksRebuild}) - that is the whole removal contract, and it is why a
 * source must remember which sections it baked.
 *
 * <h2>Removal is the one path where a missed rebuild is permanent</h2>
 *
 * <p>A moving light gets another chance 50 ms later. A <b>removed</b> light does not: once the source
 * is out of LDL's set, nothing will ever mark its sections again, so the last bake stays in the mesh
 * for as long as the player keeps that chunk loaded - "the train is gone but the track is still
 * lit". {@code detach} therefore does not rely on LDL's callback alone:
 *
 * <ul>
 *   <li>the callback is only invoked while {@code client.worldRenderer != null}, and only for a
 *       source LDL still holds - both can be false at exactly the wrong moment;</li>
 *   <li>our own {@code lit} set can name the <em>previous</em> position (a point that was moved and
 *       removed between two update passes) or be empty (a point removed before its first pass).</li>
 * </ul>
 *
 * so {@link #markLight} marks the <b>union</b> of the tracked set and the sections around the live
 * position, unconditionally, outside the per-pass rebuild budget. See {@link #markLight}.
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
    /**
     * Distinct sections one removal may hand to LDL. A beam is {@link #POINTS_PER_LINE} points spread
     * over up to {@code headLength} blocks; consecutive points overlap heavily, so the union of
     * "tracked set + position set" for a whole line is far below the worst case
     * {@code POINTS_PER_LINE * MAX_SECTIONS}. The extra room is slack, not an expectation.
     */
    private static final int CLEAN_MAX = (POINTS_PER_LINE + 4) * MAX_SECTIONS;
    /**
     * Default-on cleanup diagnostic, one line every five seconds:
     * {@code ldl2 cleanup: removed=N dirtySections=M pending=P}. Switch it off with
     * {@code -Dmtrheadlight.ldl2diag=false} (JVM argument, e.g. in the launcher's JVM args).
     */
    private static final boolean CLEANUP_DIAG =
            !"false".equalsIgnoreCase(System.getProperty("mtrheadlight.ldl2diag", "true"));

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
    /** Cleanup bookkeeping for the five-second {@code ldl2 cleanup} line (tick thread only). */
    private static int cleanRemoved;
    private static int cleanSections;

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
        if (points == null) {
            return;
        }
        // Only the transition matters: this is called every tick for a light that is already off
        // (out of range, depot, siding switched off), and marking sections on every one of those
        // calls would be a rebuild storm for a light that has nothing left to clear.
        PointLight[] wasLit = null;
        int lit = 0;
        for (final PointLight light : points) {
            if (light.luminance > 0) {
                if (wasLit == null) {
                    wasLit = new PointLight[points.length];
                }
                wasLit[lit++] = light;
            }
            light.luminance = 0;
        }
        if (lit == 0) {
            return;
        }
        // Clear the bake on the tick that decides "this must not shine", not on LDL's next pass
        // (up to 50 ms later, and that pass would repeat exactly this work anyway). Without this the
        // beam is dark in the light engine but still baked into the meshes it was last drawn into.
        final int marked = markLight(null, wasLit, lit, worldOf(points));
        cleanSections += marked;
    }

    @Override
    public void removeLine(final String key) {
        final PointLight[] points = lines.remove(key);
        if (points != null) {
            detachAll(points);
        }
    }

    @Override
    public void clearAll() {
        for (final Iterator<PointLight[]> it = lines.values().iterator(); it.hasNext(); ) {
            detachAll(it.next());
            it.remove();
        }
        PROBE.clear();
    }

    /**
     * Drops a line's sources and then clears the sections they were baked into.
     *
     * <p>Order matters: the sources have to be out of LDL's set <b>before</b> the sections are
     * marked, otherwise a rebuild that lands in between bakes them again.
     */
    private static void detachAll(final PointLight[] points) {
        final World world = worldOf(points);
        int removed = 0;
        for (final PointLight light : points) {
            if (detach(light)) {
                removed++;
            }
        }
        // Union mark over the whole line: the 8 points of one beam overlap, so marking per point
        // would hand LDL the same section half a dozen times.
        final int marked = markLight(null, points, points.length, world);
        cleanRemoved += removed;
        cleanSections += marked;
        for (final PointLight light : points) {
            light.clearTracked();
            light.world = null;
        }
    }

    /** World the line was attached in, or null when it never got that far. */
    private static World worldOf(final PointLight[] points) {
        for (final PointLight light : points) {
            if (light != null && light.world != null) {
                return light.world;
            }
        }
        return null;
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
            MtrHeadlightClient.LOGGER.debug("[lte] could not add light source", t);
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
            MtrHeadlightClient.LOGGER.warn("[lte] ldl2 init probe failed", t);
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
                cleanupSample();
            }
        } catch (Throwable t) {
            // Diagnostics must never break the tick.
            MtrHeadlightClient.LOGGER.debug("[lte] ldl2 diag failed", t);
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
                        "[lte] ldl2 registry: ours={} ldlTotal={} ldlOthers={}"
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
     * One line every five seconds (default on, {@code -Dmtrheadlight.ldl2diag=false} to switch it
     * off): {@code ldl2 cleanup: removed=N dirtySections=M pending=P}.
     *
     * <ul>
     *   <li>{@code removed} - point sources taken out of LDL's set in this window (one train line =
     *       {@link #POINTS_PER_LINE} points, head and tail = two lines);</li>
     *   <li>{@code dirtySections} - sections actually handed to LDL for a rebuild by the removal /
     *       dim paths, deduplicated per line. This is the number that has to be non-zero for a
     *       departing train: zero here on every departure is the residue bug itself;</li>
     *   <li>{@code pending} - point sources still registered with LDL, i.e. the lights that would
     *       still have to be cleaned up if they went away right now. It should fall to 0 when no
     *       train is near and stay there; a number that only grows is a leak.</li>
     * </ul>
     *
     * <p>Never calls into {@code LambDynLights} - it runs on the tick thread and only reads our own
     * counters plus the counter we maintain ourselves.
     */
    private static void cleanupSample() {
        final int removed = cleanRemoved;
        final int dirty = cleanSections;
        cleanRemoved = 0;
        cleanSections = 0;
        if (!CLEANUP_DIAG) {
            return;
        }
        try {
            MtrHeadlightClient.LOGGER.info("ldl2 cleanup: removed={} dirtySections={} pending={}",
                    removed, dirty, sourceCount);
        } catch (Throwable ignored) {
            // Logging must never break the tick.
        }
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
            MtrHeadlightClient.LOGGER.warn("[lte] ldl probe failed", t);
        }
    }

    /** @return true when LDL actually held the source (so {@code removed=} stays honest). */
    private static boolean detach(final PointLight light) {
        try {
            final World world = light.world;
            final boolean wasRegistered = light.registered;
            final LambDynLights engine = LambDynLights.get();
            if (engine != null) {
                // LDL drops the source and then calls lambdynlights$scheduleTrackedChunksRebuild on
                // it (bytecode-verified in 2.1.0/2.2.0/2.3.1), which is where the tracked set gets
                // marked. The explicit union mark in detachAll() is what still covers us when that
                // callback is skipped - no world renderer yet, or a source LDL no longer holds.
                engine.removeLightSource(light);
            }
            PROBE.remove(light);
            if (light.registered) {
                light.registered = false;
                sourceCount--;
                removeCount++;
            }
            return wasRegistered;
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.debug("[lte] could not remove light source", t);
            return false;
        }
    }

    /** Asks LDL to rebuild one chunk section; the single funnel for every mark we make. */
    private static boolean scheduleRebuild(final WorldRenderer renderer, final long sectionPos) {
        try {
            LambDynLights.scheduleChunkRebuild(renderer, sectionPos);
            diagRelight++;
            return true;
        } catch (Throwable t) {
            // A missing/renamed LDL helper must not break the tick; the light just stays stale.
            MtrHeadlightClient.LOGGER.debug("[lte] could not schedule a chunk rebuild", t);
            return false;
        }
    }

    /**
     * Marks every section the given points can be baked into and returns how many were scheduled.
     *
     * <p>This is the <b>removal</b> mark, used when a line is dropped, when a line is dimmed to zero,
     * and from LDL's own {@code lambdynlights$scheduleTrackedChunksRebuild} callback. The set is the
     * union of two sources, because either one alone has a hole:
     *
     * <ul>
     *   <li>each point's tracked {@code lit} set - the sections the last update pass really baked it
     *       into. That set is render-thread state and is at most one pass (50 ms) old;</li>
     *   <li>the sections around its <b>live position</b>, {@code floor(p +/- 7.75) >> 4} per axis -
     *       which covers a point that was moved and removed between two passes (its tracked set
     *       still names the previous spot) and a point whose tracked set is empty because it was
     *       removed before it ever got an update pass.</li>
     * </ul>
     *
     * <p>Duplicates are dropped, so the overlapping points of one beam do not hand LDL the same
     * section repeatedly. Deliberately <b>outside</b> the {@link #claimSection} budget: a budgeted
     * mark can be dropped in a busy pass, and a dropped mark on a removal is a patch of world that
     * stays lit until something unrelated rebuilds that chunk.
     *
     * @param renderer renderer to schedule on, or null to resolve the client's own
     * @param world    world the points were attached in; a different current world means the old
     *                 meshes are gone and nothing has to be cleared
     */
    private static int markLight(final WorldRenderer renderer, final PointLight[] points,
                                 final int count, final World world) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null) {
                return 0;
            }
            if (world != null && client.world != world) {
                return 0;
            }
            final WorldRenderer target = renderer != null ? renderer : client.worldRenderer;
            if (target == null) {
                return 0;
            }
            final long[] sections = new long[CLEAN_MAX];
            int n = 0;
            for (int i = 0; i < count; i++) {
                final PointLight light = points[i];
                if (light == null) {
                    continue;
                }
                n = light.appendTracked(sections, n);
                n = PointLight.appendSectionsAt(light.getDynamicLightX(), light.getDynamicLightY(),
                        light.getDynamicLightZ(), sections, n);
            }
            int marked = 0;
            for (int i = 0; i < n; i++) {
                if (scheduleRebuild(target, sections[i])) {
                    marked++;
                }
            }
            return marked;
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.debug("[lte] could not mark light sections dirty", t);
            return 0;
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
        /**
         * Sections this point was baked into when the chunks were last (re)built. Read from the tick
         * thread by {@link #markLight} during a removal, written by the render thread - hence
         * volatile, with {@code lit} written before {@code litCount} so a reader that sees the count
         * also sees the array it belongs to.
         */
        private volatile long[] lit = new long[MAX_SECTIONS];
        private volatile int litCount;
        private long[] next = new long[MAX_SECTIONS];

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

        /**
         * Appends the sections this point was last baked into (deduplicated) to {@code out}.
         *
         * @return the new element count
         */
        int appendTracked(final long[] out, final int from) {
            final long[] source = lit;
            final int count = Math.min(litCount, source.length);
            int k = from;
            for (int i = 0; i < count && k < out.length; i++) {
                if (!contains(out, k, source[i])) {
                    out[k++] = source[i];
                }
            }
            return k;
        }

        /** Forgets the tracked set; called once the sections have been marked dirty. */
        void clearTracked() {
            litCount = 0;
        }

        /**
         * Every section around one position that a light of {@link #LIGHT_RADIUS} can reach,
         * {@code floor(p-R)..floor(p+R)} per axis in section coordinates, appended to {@code out}
         * without duplicates. {@code >>} is a floor division for negatives too, so no extra rounding
         * is needed. Nothing is appended for a non-finite or out-of-world position.
         *
         * @return the new element count
         */
        static int appendSectionsAt(final double x, final double y, final double z,
                                    final long[] out, final int from) {
            if (!isFinite(x) || !isFinite(y) || !isFinite(z)
                    || Math.abs(x) > COORD_LIMIT || Math.abs(y) > COORD_LIMIT
                    || Math.abs(z) > COORD_LIMIT) {
                return from;
            }
            final int minX = floor(x - LIGHT_RADIUS) >> 4;
            final int maxX = floor(x + LIGHT_RADIUS) >> 4;
            final int minY = floor(y - LIGHT_RADIUS) >> 4;
            final int maxY = floor(y + LIGHT_RADIUS) >> 4;
            final int minZ = floor(z - LIGHT_RADIUS) >> 4;
            final int maxZ = floor(z + LIGHT_RADIUS) >> 4;
            int k = from;
            for (int sx = minX; sx <= maxX; sx++) {
                for (int sy = minY; sy <= maxY; sy++) {
                    for (int sz = minZ; sz <= maxZ; sz++) {
                        if (k >= out.length) {
                            return k;
                        }
                        final long section = BlockPos.asLong(sx, sy, sz);
                        if (!contains(out, k, section)) {
                            out[k++] = section;
                        }
                    }
                }
            }
            return k;
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
         * LDL calls this when the source is removed (and once per update pass before it stores the
         * new tracked set), expecting the sections it was baked into to be rebuilt - otherwise the
         * light stays baked into the mesh where the train used to be.
         *
         * <p>Runs on whichever thread removed the source (the client tick thread for us), under
         * LDL's write lock, so it must not call back into {@code LambDynLights} and must not throw.
         * The set is the union of the tracked set and the live position - see {@link #markLight}.
         */
        @Override
        public void lambdynlights$scheduleTrackedChunksRebuild(final WorldRenderer renderer) {
            try {
                markLight(renderer, new PointLight[]{this}, 1, world);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.debug("[lte] could not clear a light", t);
            }
        }
    }
}
