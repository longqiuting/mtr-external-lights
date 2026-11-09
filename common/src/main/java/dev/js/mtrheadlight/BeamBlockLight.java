package dev.js.mtrheadlight;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The train head beams as <b>real block light sources</b>, for the vanilla chunk mesher.
 *
 * <p>Minecraft bakes light into the chunk mesh: a vertex carries a packed {@code skyLight<<20 |
 * blockLight<<4} lightmap coordinate and nothing re-evaluates it at draw time. So the only way a
 * moving light can brighten a block is to raise the block nibble while that section is being
 * rebuilt - which is what {@code BeamLightMixin} does, on the single code path every vanilla
 * terrain vertex passes through.
 *
 * <h2>The one injection point</h2>
 *
 * <p>Verified with {@code javap -c} on {@code minecraft-merged-1.20.1-yarn+build.10}:
 * <ul>
 *   <li>{@code WorldRenderer.getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)}
 *       (6 instructions) is the <em>only</em> place the vanilla block mesher turns a position into
 *       a light value: {@code BlockModelRenderer$BrightnessCache#getInt} calls it on every AO
 *       corner, {@code BlockModelRenderer#render} and {@code #renderQuadsFlat} call it for the
 *       flat path, and {@code FluidRenderer} reaches it through the two-argument overload, which
 *       is a pure delegate ({@code getBlockState(pos)} then the three-argument call). One
 *       {@code @Inject} at RETURN therefore covers every block and every fluid face of a section
 *       build, and nothing else, as long as the {@code BlockRenderView} is a
 *       {@code ChunkRendererRegion}.</li>
 * </ul>
 *
 * <h2>Shape of the light</h2>
 *
 * <p>Deliberately not a sphere: a car head light is a horizontal wedge that hugs the ground and
 * widens with distance, so the model is
 * <pre>
 *   t       = distance along the beam's horizontal axis, normalised to [0,1]
 *   lateral = width/2 + (radius - width/2) * t          cone half-width at t
 *   radial  = 1 - perpendicular / lateral               full on the axis, 0 at the rim
 *   vert    = 1 - |dy| / 4                              a 4 block tall slab, faded at both edges
 *   axial   = 1 - 0.45 t                                dips towards the tip
 *   level   = peak * radial^2 * vert * axial            rounded, clamped to 0..15
 * </pre>
 * and the result is applied as {@code max(blockLight, level)} - a beam may never <em>darken</em> a
 * block that a real light already lights.
 *
 * <h2>Why the sky nibble is left alone</h2>
 *
 * <p>Block light only shows up where sky light is weaker: the lightmap texture combines the two,
 * and in full daylight {@code skyLight=15} already saturates it. A beam is therefore visible on
 * blocks at night, under a bridge, in a tunnel or in rain - which is exactly where a head light
 * matters, and it is the reason the acceptance test for this feature has to be run at night.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #publish} runs on the client thread once per tick and swaps an immutable array; the
 * chunk-build workers read the volatile reference. No locks, no mutation after publish.
 */
public final class BeamBlockLight {
    /** Doubles per beam: {@code sx sy sz ex ey ez radius peak}. */
    public static final int STRIDE = 8;
    /** Upper bound on beams in one snapshot; a train's two beams already need two entries. */
    public static final int MAX_BEAMS = 16;

    private static final double[] NONE = new double[0];
    private static volatile double[] beams = NONE;
    /**
     * TEST ONLY ({@code -Dlte.beamtest=1}, see {@code BeamLightTest}): a fixed beam that replaces
     * whatever the trains published. Same shape, same maths, same sweep - only the light source is
     * faked, so an A/B screenshot can be taken at a wall without waiting for a train to pass.
     * {@code null} in normal play.
     */
    private static volatile double[] override;

    /** TEST ONLY: installs (or with {@code null} removes) a fixed beam. */
    public static void setTestBeam(final double[] beam) {
        override = beam;
        publish(null);
    }

    /**
     * Bumped on every {@link #publish}. {@link BeamRelight} compares it with the value the current
     * section signatures were computed from, so an unchanged snapshot costs no sampling at all.
     */
    private static volatile long version;

    /** Vertices whose block light this feature raised (the {@code verts=} of the diagnostic line). */
    private static final AtomicLong HITS = new AtomicLong();
    /** Nanoseconds spent in the actual lighting maths, excluding the AABB reject. */
    private static final AtomicLong NANOS = new AtomicLong();
    /** Every invocation of the injected method, whatever the view type - proves the mixin is live. */
    private static volatile long calls;
    /** Set the first time the hook saw a {@code ChunkRendererRegion} (a real section build). */
    private static volatile boolean regionSeen;

    private BeamBlockLight() {
    }

    /** Publishes a new immutable snapshot; the caller must not touch the array afterwards. */
    public static void publish(final double[] snapshot) {
        final double[] next = override != null ? override : (snapshot == null ? NONE : snapshot);
        if (same(beams, next)) {
            // A train standing at a platform publishes the identical beam every tick. Bumping the
            // version there would make BeamRelight sweep 64 section signatures twenty times a
            // second for nothing, so the version is the "has anything actually moved" signal.
            return;
        }
        beams = next;
        version++;
    }

    private static boolean same(final double[] a, final double[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /** Set the first time the vanilla hook runs; proves the mixin matched, not just that it loaded. */
    private static volatile boolean alive;

    /**
     * Called on every invocation of the injected method, before any filter, so the diagnostics can
     * tell "the mixin never ran" (mapping/ordering problem) from "it ran but never saw a
     * {@code ChunkRendererRegion}" (a different mesher is in charge).
     */
    public static void noteCall() {
        alive = true;
        calls++;
    }

    public static void noteAlive() {
        alive = true;
        regionSeen = true;
    }

    public static boolean alive() {
        return alive;
    }

    /** True when at least one invocation carried a {@code ChunkRendererRegion}, i.e. a section build. */
    public static boolean regionSeen() {
        return regionSeen;
    }

    /** Total invocations of the injected method since launch (racy on purpose: a diagnostic only). */
    public static long calls() {
        return calls;
    }

    public static void clear() {
        publish(null);
    }

    /** True when there is at least one beam to bake, i.e. the hook has any work at all. */
    public static boolean active() {
        return beams.length != 0;
    }

    /** Number of beams in the current snapshot ({@code length / STRIDE}). */
    public static int count() {
        return beams.length / STRIDE;
    }

    /**
     * The current immutable snapshot. Not a copy: callers must only read it, and must not hold on
     * to it across beats - the array is replaced, never mutated.
     */
    public static double[] snapshot() {
        return beams;
    }

    public static long version() {
        return version;
    }

    /** Block light level (0-15) contributed by the beams at {@code pos}; 0 when none reaches it. */
    public static int level(final int bx, final int by, final int bz) {
        final double[] all = beams;
        if (all.length == 0) {
            return 0;
        }
        final double px = bx + 0.5D;
        final double py = by + 0.5D;
        final double pz = bz + 0.5D;
        double best = 0.0D;
        for (int i = 0; i + STRIDE <= all.length; i += STRIDE) {
            final double sx = all[i];
            final double sy = all[i + 1];
            final double sz = all[i + 2];
            final double ex = all[i + 3];
            final double ey = all[i + 4];
            final double ez = all[i + 5];
            final double radius = all[i + 6];
            final double peak = all[i + 7];

            /*
             * Cheap reject first: the beam is inside the box spanned by its two ends grown by the
             * radius, so a block outside that box can be skipped with three compares. This runs on
             * every vertex of every rebuilt section, including the 90% that are nowhere near a
             * train, so it is the only cost that matters when no beam is in range.
             */
            if (px < min(sx, ex) - radius || px > max(sx, ex) + radius
                    || pz < min(sz, ez) - radius || pz > max(sz, ez) + radius
                    || py < min(sy, ey) - VERTICAL || py > max(sy, ey) + VERTICAL) {
                continue;
            }

            final double hx = ex - sx;
            final double hz = ez - sz;
            final double length = Math.sqrt(hx * hx + hz * hz);
            if (length < 1.0E-4D) {
                continue;
            }
            final double ux = hx / length;
            final double uz = hz / length;
            final double rx = px - sx;
            final double rz = pz - sz;
            double t = (rx * ux + rz * uz) / length;
            t = t < 0.0D ? 0.0D : (t > 1.0D ? 1.0D : t);

            // Perpendicular distance to the beam's horizontal axis (the light is a wedge, so the
            // vertical offset is handled separately and does not shrink the cone).
            final double perp = Math.abs(rx * uz - rz * ux);
            final double lateral = 1.5D + (radius - 1.5D) * t;
            if (perp >= lateral) {
                continue;
            }
            final double radial = 1.0D - perp / lateral;
            final double dy = py - (sy + (ey - sy) * t);
            final double vert = 1.0D - Math.abs(dy) / VERTICAL;
            if (vert <= 0.0D) {
                continue;
            }
            final double value = peak * radial * radial * vert * (1.0D - 0.45D * t);
            if (value > best) {
                best = value;
            }
        }
        if (best <= 0.0D) {
            return 0;
        }
        final int rounded = (int) Math.round(best);
        return rounded < 0 ? 0 : (rounded > 15 ? 15 : rounded);
    }

    /** Half-height of the lit slab, in blocks, above and below the beam's own height. */
    private static final double VERTICAL = 4.0D;

    /**
     * Coarse "how bright is this section" fingerprint, used by {@link BeamRelight} to rebuild a
     * section only when the baked light would actually differ.
     *
     * <p>64 samples (a 4x4x4 grid at offsets 2/6/10/14 inside the section) against 27 for a 3x3x3
     * grid: the beam is only ~1.5 blocks wide at the lamp, so a 3-point-per-axis grid can walk
     * straight past it and report "nothing here" for a section the beam is visibly lighting. The
     * signature is the sum of the 64 levels, so a beam that moves within the section changes it
     * even when its peak does not.
     */
    public static int signature(final int sectionX, final int sectionY, final int sectionZ) {
        int sum = 0;
        final int ox = sectionX << 4;
        final int oy = sectionY << 4;
        final int oz = sectionZ << 4;
        for (int x = 2; x < 16; x += 4) {
            for (int y = 2; y < 16; y += 4) {
                for (int z = 2; z < 16; z += 4) {
                    sum += level(ox + x, oy + y, oz + z);
                }
            }
        }
        return sum;
    }

    /** Records one raised vertex. Called from the chunk-build workers, hence the atomics. */
    public static void countHit(final long nanos) {
        HITS.incrementAndGet();
        if (nanos > 0L) {
            NANOS.addAndGet(nanos);
        }
    }

    /** Reads and clears the counters: {@code [verts, nanos]}. */
    public static long[] drainStats() {
        return new long[]{HITS.getAndSet(0L), NANOS.getAndSet(0L)};
    }

    private static double min(final double a, final double b) {
        return a < b ? a : b;
    }

    private static double max(final double a, final double b) {
        return a > b ? a : b;
    }
}
