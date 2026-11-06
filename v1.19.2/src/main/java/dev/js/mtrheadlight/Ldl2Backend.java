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
 * <h2>Why the sources have to mark chunks dirty themselves</h2>
 *
 * <p>In LDL 2.x a dynamic light is baked into a chunk mesh: {@code CommonWorldRendererMixin} injects
 * into {@code WorldRenderer.getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)} and adds
 * {@code LambDynLights.getLightmapWithDynamicLight(pos, lightmap)} there. Consequence - <b>the block
 * light is written into the mesh at build time</b>. Moving a light source changes nothing on screen
 * until the affected chunk sections are rebuilt, and LDL does not rebuild them for us: its only pass
 * over the sources ({@code LambDynLights.updateAll}, called from {@code WorldRenderEvents.START} every
 * 50 ms) does nothing but
 *
 * <pre>
 * for (DynamicLightSource s : dynamicLightSources)
 *     if (s.lambdynlights$updateDynamicLight(renderer)) ++lastUpdateCount;
 * </pre>
 *
 * <p>i.e. <b>{@code lambdynlights$updateDynamicLight} is expected to do the rebuild scheduling and to
 * return whether anything changed</b>. LDL's own entity mixin shows the contract: it compares the
 * position with the last position, and on a change calls {@code LambDynLights.scheduleChunkRebuild(
 * renderer, pos)} for every touched chunk section, then keeps the set so that
 * {@code lambdynlights$scheduleTrackedChunksRebuild} can clean up on removal.
 *
 * <p>Our implementation used to return {@code true} from that method without scheduling anything, and
 * left {@code lambdynlights$scheduleTrackedChunksRebuild} empty. Nothing ever marked a section dirty,
 * so the light never appeared - until the player changed a block, which makes vanilla rebuild the
 * surrounding sections; the light showed up for a frame and then vanished again, because the very
 * next rebuild pass had no reason to happen. That is the exact reported symptom.
 *
 * <p>Fix: every point keeps its own last position / luminance and its own set of lit chunk sections.
 * When it moves (or its luminance changes) the <i>symmetric difference</i> is scheduled: sections
 * the light entered need a rebuild to receive it, sections it left need one to lose it. That keeps
 * the borders correct - a missed border is a bright/dark seam exactly on the section line.
 *
 * <p><b>Why the difference alone is not enough (the reported banding).</b> The sections that stay
 * lit are exactly the ones nobody marks dirty, yet their meshes still hold the light from the last
 * build. A train that keeps sliding therefore leaves a frozen patch behind and only refreshes it
 * when a point crosses a border - i.e. the light visibly jumps in ~16 block steps and looks cut off
 * along a section edge. So the current set is also re-baked, on a movement / time cadence
 * ({@link #REFRESH_MOVE}, {@link #REFRESH_MIN_MS}) and under a pass-wide budget
 * ({@link #REFRESH_PER_PASS}) shared by every point and every train.
 */
public final class Ldl2Backend implements LightBackend {
    private static final int POINTS_PER_LINE = 8;
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

    /** Number of point sources currently registered with LDL; diagnostics only. */
    private static volatile int sourceCount;
    /** World the sources were attached in, so a stale source can be ignored after a world switch. */
    private static World boundWorld;

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

    // --- diagnostics (render thread only) -------------------------------------------------------
    private static long diagWindowStart = System.currentTimeMillis();
    private static int diagMoved;
    private static int diagRelight;

    @Override
    public void updateLine(final String key, final Vec3d start, final Vec3d end, final int luminance) {
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
        boundWorld = null;
    }

    private static void attach(final PointLight light) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            boundWorld = client.world;
        }
        try {
            LambDynLights.get().addLightSource(light);
            light.registered = true;
            sourceCount++;
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.debug("[mtr-headlight] could not add light source", t);
        }
    }

    private static void detach(final PointLight light) {
        try {
            LambDynLights.get().removeLightSource(light);
            if (light.registered) {
                light.registered = false;
                sourceCount--;
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

    /**
     * One line every five seconds, and only when one of the numbers moved: proves on a real client
     * that positions change ({@code moved}) and that section rebuilds are actually requested
     * ({@code relight}). Silent while nothing happens.
     */
    private static void diagSample() {
        final long now = System.currentTimeMillis();
        if (now - diagWindowStart < 5000L) {
            return;
        }
        diagWindowStart = now;
        final int moved = diagMoved;
        final int relight = diagRelight;
        diagMoved = 0;
        diagRelight = 0;
        RailDiag.ldl2(sourceCount, moved, relight);
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

        void set(final double x, final double y, final double z, final int luminance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.luminance = luminance;
        }

        @Override
        public double getDynamicLightX() {
            return x;
        }

        @Override
        public double getDynamicLightY() {
            return y;
        }

        @Override
        public double getDynamicLightZ() {
            return z;
        }

        @Override
        public World getDynamicLightWorld() {
            return boundWorld != null ? boundWorld : MinecraftClient.getInstance().world;
        }

        @Override
        public void resetDynamicLight() {
            lastLuminance = 0;
        }

        @Override
        public int getLuminance() {
            return luminance;
        }

        @Override
        public void dynamicLightTick() {
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
            diagSample();
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
            final int luminanceNow = luminance;
            final double nx = x;
            final double ny = y;
            final double nz = z;
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
            if (Math.abs(x) > 3.0E7D || Math.abs(z) > 3.0E7D || Math.abs(y) > 3.0E7D) {
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
