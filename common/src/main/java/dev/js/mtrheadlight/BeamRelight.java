package dev.js.mtrheadlight;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides <b>which sections have to be remeshed</b> so a moving head beam stays baked into the
 * terrain, and how often.
 *
 * <p>This is the expensive half of {@link BeamBlockLight}: the mixin only changes the light value
 * of a vertex that is already being meshed, so the light can never move unless a section is rebuilt.
 * A section is 16x16x16 blocks and a rebuild is a full re-mesh of it (model sampling, ambient
 * occlusion, vertex bake, GPU buffer re-upload) - the same work as a chunk streaming in.
 *
 * <h2>Why this cannot just mark everything in range</h2>
 *
 * <p>With {@code beamBlockLightRadius = 12} and a 16 block beam, one head light touches a
 * 40 x 8 x 24 block box: about 13 sections, of which 5-7 are actually inside the wedge. A train at
 * 20 blocks/s = 1 block/tick crosses a section in 0.8 s, so "rebuild whatever the beam touches"
 * comes to 60-120 section rebuilds per second for one train. That is the same order as streaming
 * an entire 12 chunk render distance, and it is why the feature ships off.
 *
 * <h2>The four gates, in the order they cost</h2>
 *
 * <ol>
 *   <li><b>movement</b> - a sweep only runs when a beam endpoint moved at least
 *       {@link #MOVE_BLOCKS} (1) blocks since the last one, or when {@link #EVERY_TICKS} (10)
 *       ticks have passed. A train standing at a platform costs zero sweeps, zero signatures and
 *       zero rebuilds.</li>
 *   <li><b>signature</b> - each candidate section is fingerprinted with a 64 sample grid
 *       ({@link BeamBlockLight#signature}); a section whose beam contribution is unchanged is
 *       skipped. This is the rail relight scheduler's idea ({@code RailRelightScheduler}) applied
 *       to whole sections, and it is what turns "the 13 sections in range" into "the 2 or 3 whose
 *       brightness actually moved".</li>
 *   <li><b>per-section cooldown</b> - {@link #COOLDOWN_MS} between two marks of the same section,
 *       so a section is not re-queued while its rebuild is still sitting in the chunk builder.</li>
 *   <li><b>per-second quota</b> - {@link #MAX_PER_SEC} marks per second across every beam, the hard
 *       ceiling on how much of the chunk build throughput this feature may take. When it binds, the
 *       remaining sections simply stay as they are and are picked up by a later sweep, so the light
 *       is late rather than the client stuttering.</li>
 * </ol>
 *
 * <p><b>Clearing the trail.</b> Every section whose signature was non-zero is remembered. A later
 * sweep includes those sections even when they are out of range, so the section the beam has just
 * left is rebuilt <em>without</em> it - otherwise a lit corridor would stay lit forever behind the
 * train.
 *
 * <p>The diagnostics line is {@code beam light: sections=K verts=V ms=M}; {@code verts} and
 * {@code ms} come from the mixin and are per 5 s window, {@code sections} is the number of
 * rebuilds requested in that window.
 */
public final class BeamRelight {
    /** Minimum beam movement, in blocks, that makes a sweep worthwhile. */
    private static final double MOVE_BLOCKS = 1.0D;
    /** Upper bound on the sweep period: catches sub-block drift and a config change. */
    private static final int EVERY_TICKS = 10;
    /** Two marks of the same section closer together than this are merged. */
    private static final long COOLDOWN_MS = 200L;
    /** Hard ceiling on rebuild requests per second, all beams together. */
    private static final int MAX_PER_SEC = 40;
    /** Refuse a sweep that would have to look at more sections than this (radius set too high). */
    private static final int MAX_CANDIDATES = 96;
    /** Diagnostics window. */
    private static final int LOG_TICKS = 100;
    /** Sections remembered for trail clearing; bounded so a long flight cannot grow it forever. */
    private static final int MAX_CACHED = 256;

    /** Named in the log when the vanilla hook is unavailable; null means "installed and running". */
    private static String skipReason;

    private static boolean installed;
    private static String announcedSkip;
    /** Tick counter of this class; drives the sweep period and the diagnostics window. */
    private static long ticks;

    private static long lastSweepTick = Long.MIN_VALUE;
    private static long lastVersion = Long.MIN_VALUE;
    /** Beam start points at the last sweep, for the 1 block movement gate. */
    private static double[] lastStarts = new double[0];

    /** Section key -> signature that is currently baked into that section. */
    private static final Map<Long, Integer> baked = new HashMap<>();
    /** Section key -> wall clock of the last rebuild request for it. */
    private static final Map<Long, Long> lastMarkMs = new HashMap<>();

    /* Diagnostics, per 5 s window. */
    private static long windowStartTick = Long.MIN_VALUE;
    private static long windowStartMs;
    private static int windowSections;
    private static int windowThrottled;
    private static int windowSweeps;
    private static long quotaUsed;
    private static long quotaWindowStartMs;

    private BeamRelight() {
    }

    /** True when the feature can run at all on this client. */
    public static boolean available() {
        return installed;
    }

    /**
     * Installs the sweeper, or records why it cannot run.
     *
     * <p>Sodium and Iris both replace the terrain mesher, so the vanilla {@code WorldRenderer} hook
     * would never be called and this feature would do nothing but burn CPU. Rather than guess, the
     * check is a hard skip with a log line: {@code sodium} is what the loader reports, which covers
     * every Sodium fork that keeps the id.
     */
    public static void install() {
        final String sodium = modPresent("sodium") ? "sodium" : (modPresent("embeddium") ? "embeddium" : null);
        if (sodium != null) {
            skipReason = sodium + " present (replaces the vanilla section mesher)";
            installed = false;
            return;
        }
        installed = true;
    }

    private static boolean modPresent(final String id) {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(id);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Client tick, after {@link LightController#tick()} has published this tick's beams. */
    public static void tick(final HeadlightConfig cfg) {
        final long tick = ++ticks;
        final boolean on = cfg != null && cfg.beamBlockLight && cfg.enabled;
        if (!on) {
            if (!baked.isEmpty()) {
                // Switch turned off: hand every lit section back once so the light goes out.
                flushAll();
            }
            lastStarts = new double[0];
            lastVersion = Long.MIN_VALUE;
            return;
        }
        if (skipReason != null) {
            announceSkip();
            return;
        }
        if (!installed || !BeamBlockLight.active()) {
            if (!baked.isEmpty()) {
                flushAll();
            }
            return;
        }
        if (!BeamBlockLight.alive() || !BeamBlockLight.regionSeen()) {
            // Two very different failures, and the log has to say which:
            //   alive=false      -> the injected method never ran at all (mixin not applied);
            //   regionSeen=false -> it ran, but never with a ChunkRendererRegion, i.e. some other
            //                       mod replaced the vanilla section mesher without changing its id.
            if (announcedSkip == null) {
                announcedSkip = "hook";
                MtrHeadlightClient.LOGGER.warn(
                        "[lte] beam light: vanilla lightmap hook unusable "
                                + "(called={} regionSeen={}) - the beam stays a drawn sheet only",
                        Long.valueOf(BeamBlockLight.calls()),
                        Boolean.valueOf(BeamBlockLight.regionSeen()));
            }
            return;
        }

        maybeLog(tick);

        final long version = BeamBlockLight.version();
        final boolean moved = version != lastVersion;
        final int sinceSweep = lastSweepTick == Long.MIN_VALUE ? Integer.MAX_VALUE : (int) (tick - lastSweepTick);
        if (!moved) {
            if (sinceSweep < EVERY_TICKS) {
                return;
            }
        } else if (sinceSweep < EVERY_TICKS && maxMove() < MOVE_BLOCKS) {
            // Sub-block drift: the snapshot moved but no lamp travelled a full block. Wait for
            // either a real block of travel or the periodic sweep - a rebuild for 0.05 blocks of
            // motion is invisible and costs a whole section.
            return;
        }

        lastVersion = version;
        lastSweepTick = tick;
        lastStarts = starts();
        windowSweeps++;
        if (windowStartTick == Long.MIN_VALUE) {
            windowStartTick = tick;
            windowStartMs = System.currentTimeMillis();
        }

        final WorldRenderer renderer = renderer();
        if (renderer == null) {
            return;
        }
        sweep(renderer, cfg);
    }

    /** Collects the candidate sections, checks their signatures and marks the changed ones. */
    private static void sweep(final WorldRenderer renderer, final HeadlightConfig cfg) {
        final Set<Long> candidates = new HashSet<>(baked.keySet());
        if (!addRange(candidates, cfg.beamBlockLightRadius)) {
            windowThrottled++;
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        if (candidates.size() > MAX_CANDIDATES) {
            // Radius (or train count) put more sections in range than this budget can serve in one
            // sweep; skip it rather than queue a burst that stalls the chunk builder for a second.
            windowThrottled++;
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Long key : candidates) {
            final int sx = ChunkSectionPos.unpackX(key);
            final int sy = ChunkSectionPos.unpackY(key);
            final int sz = ChunkSectionPos.unpackZ(key);
            final int signature = BeamBlockLight.signature(sx, sy, sz);
            final Integer previous = baked.get(key);
            if (signature == 0) {
                if (previous == null) {
                    continue;
                }
                // The beam has left this section: rebuild it once without the light.
                if (mayMark(key, now)) {
                    mark(renderer, sx, sy, sz);
                    baked.remove(key);
                } else {
                    windowThrottled++;
                }
                continue;
            }
            if (previous != null && previous.intValue() == signature) {
                continue;
            }
            if (!mayMark(key, now)) {
                windowThrottled++;
                continue;
            }
            mark(renderer, sx, sy, sz);
            baked.put(key, Integer.valueOf(signature));
        }
    }

    /** Adds every section the beams can reach; false when the set grew past the hard cap. */
    private static boolean addRange(final Set<Long> out, final int radius) {
        final double[] all = beamsSnapshot();
        for (int i = 0; i + BeamBlockLight.STRIDE <= all.length; i += BeamBlockLight.STRIDE) {
            final double sx = all[i];
            final double sy = all[i + 1];
            final double sz = all[i + 2];
            final double ex = all[i + 3];
            final double ey = all[i + 4];
            final double ez = all[i + 5];
            final double r = all[i + 6];
            final int minX = ChunkSectionPos.getSectionCoord((int) Math.floor(min(sx, ex) - r));
            final int maxX = ChunkSectionPos.getSectionCoord((int) Math.floor(max(sx, ex) + r));
            final int minZ = ChunkSectionPos.getSectionCoord((int) Math.floor(min(sz, ez) - r));
            final int maxZ = ChunkSectionPos.getSectionCoord((int) Math.floor(max(sz, ez) + r));
            // The light slab is 4 blocks tall around the beam, plus one section of slack for the
            // ground under a raised track (a viaduct's ballast is 1-2 blocks below the rail).
            final int minY = ChunkSectionPos.getSectionCoord((int) Math.floor(min(sy, ey) - 4.0D - 12.0D));
            final int maxY = ChunkSectionPos.getSectionCoord((int) Math.floor(max(sy, ey) + 4.0D + 4.0D));
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        out.add(Long.valueOf(ChunkSectionPos.asLong(x, y, z)));
                        if (out.size() > MAX_CANDIDATES) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static double[] beamsSnapshot() {
        // BeamBlockLight owns the array; a copy keeps this class from depending on its internals.
        return BeamBlockLight.snapshot();
    }

    /** Gates 3 and 4: per-section cooldown, then the shared per-second quota. */
    private static boolean mayMark(final Long key, final long now) {
        final Long last = lastMarkMs.get(key);
        if (last != null && now - last.longValue() < COOLDOWN_MS) {
            return false;
        }
        if (now - quotaWindowStartMs >= 1000L) {
            quotaWindowStartMs = now;
            quotaUsed = 0L;
        }
        if (quotaUsed >= MAX_PER_SEC) {
            return false;
        }
        quotaUsed++;
        if (lastMarkMs.size() > MAX_CACHED) {
            lastMarkMs.clear();
        }
        lastMarkMs.put(key, Long.valueOf(now));
        return true;
    }

    private static void mark(final WorldRenderer renderer, final int sx, final int sy, final int sz) {
        try {
            // Section coords -> the 6 block coords scheduleBlockRenders takes; it expands by one
            // block itself (javap: arg-1 .. arg+1), so the section is exactly covered.
            renderer.scheduleBlockRenders(sx << 4, sy << 4, sz << 4,
                    (sx << 4) + 15, (sy << 4) + 15, (sz << 4) + 15);
            windowSections++;
        } catch (Throwable t) {
            // Scheduling is best effort; a failure must not stop the tick loop.
        }
    }

    /** Rebuilds every remembered section once (feature switched off / world left). */
    private static void flushAll() {
        final WorldRenderer renderer = renderer();
        if (renderer != null) {
            for (final Long key : baked.keySet()) {
                try {
                    final int sx = ChunkSectionPos.unpackX(key);
                    final int sy = ChunkSectionPos.unpackY(key);
                    final int sz = ChunkSectionPos.unpackZ(key);
                    renderer.scheduleBlockRenders(sx << 4, sy << 4, sz << 4,
                            (sx << 4) + 15, (sy << 4) + 15, (sz << 4) + 15);
                } catch (Throwable t) {
                    break;
                }
            }
        }
        baked.clear();
        lastMarkMs.clear();
    }

    /** Prints the window line now (test harness / manual check) and starts a fresh window. */
    public static void reportNow() {
        if (windowStartTick == Long.MIN_VALUE) {
            return;
        }
        windowStartTick = ticks - LOG_TICKS;
        maybeLog(ticks);
        windowStartTick = ticks;
    }

    private static void maybeLog(final long tick) {
        if (windowStartTick == Long.MIN_VALUE) {
            return;
        }
        if (tick - windowStartTick < LOG_TICKS) {
            return;
        }
        final long[] stats = BeamBlockLight.drainStats();
        final double ms = stats[1] / 1.0E6D;
        MtrHeadlightClient.LOGGER.info(
                "[lte] beam light: sections={} verts={} ms={} sweeps={} cand={} throttled={} beams={}",
                Integer.valueOf(windowSections), Long.valueOf(stats[0]),
                String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(ms)),
                Integer.valueOf(windowSweeps), Integer.valueOf(baked.size()),
                Integer.valueOf(windowThrottled), Integer.valueOf(BeamBlockLight.count()));
        windowSections = 0;
        windowSweeps = 0;
        windowThrottled = 0;
        windowStartTick = tick;
        windowStartMs = System.currentTimeMillis();
    }

    private static void announceSkip() {
        if (announcedSkip == null) {
            announcedSkip = skipReason;
            MtrHeadlightClient.LOGGER.warn(
                    "[lte] beam light: skipped, {} - the beam stays a drawn sheet only",
                    skipReason);
        }
    }

    private static WorldRenderer renderer() {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            return client == null ? null : client.worldRenderer;
        } catch (Throwable t) {
            return null;
        }
    }

    private static double[] starts() {
        final double[] all = beamsSnapshot();
        final double[] out = new double[(all.length / BeamBlockLight.STRIDE) * 3];
        int o = 0;
        for (int i = 0; i + BeamBlockLight.STRIDE <= all.length; i += BeamBlockLight.STRIDE) {
            out[o++] = all[i];
            out[o++] = all[i + 1];
            out[o++] = all[i + 2];
        }
        return out;
    }

    /** Largest distance any beam's lamp moved since the last sweep, in blocks. */
    private static double maxMove() {
        final double[] now = starts();
        if (now.length != lastStarts.length) {
            return Double.MAX_VALUE;
        }
        double best = 0.0D;
        for (int i = 0; i + 2 < now.length; i += 3) {
            final double dx = now[i] - lastStarts[i];
            final double dy = now[i + 1] - lastStarts[i + 1];
            final double dz = now[i + 2] - lastStarts[i + 2];
            final double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > best) {
                best = d;
            }
        }
        return best;
    }

    private static double min(final double a, final double b) {
        return a < b ? a : b;
    }

    private static double max(final double a, final double b) {
        return a > b ? a : b;
    }
}
