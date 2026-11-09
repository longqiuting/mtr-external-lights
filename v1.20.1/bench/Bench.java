import dev.js.mtrheadlight.BeamBlockLight;

/**
 * Standalone micro-benchmark for BeamBlockLight.level() / signature().
 *
 * <p>Runs the shipping class outside Minecraft (it has no MC imports) so the per-vertex cost of the
 * injected light can be measured without a game session. Four cases, because the cost depends
 * entirely on how many beams are in range:
 *
 * <ol>
 *   <li>no beam at all: what every section in the world pays when the feature is off or the train
 *       is far away - one volatile read and a length check;</li>
 *   <li>one beam, position outside its AABB: the AABB reject, which is the cost of the ~90% of
 *       vertices that are near but not inside the wedge;</li>
 *   <li>one beam, position inside: the full cone/falloff maths;</li>
 *   <li>a whole 16x16x16 section: 2500 distinct positions, warmed up, which is the number that
 *       goes into the report's "what does one extra rebuild cost" arithmetic.</li>
 * </ol>
 */
public final class Bench {
    private static final int WARMUP = 500_000;
    private static final int ITERS = 5_000_000;

    /** A representative head beam: 16 blocks long, 12 block radius, level 15, at y=64. */
    private static final double[] BEAM = {0.0D, 64.0D, 0.0D, 16.0D, 63.8D, 0.0D, 12.0D, 15.0D};

    private static int sink;

    public static void main(final String[] args) {
        noBeam();
        BeamBlockLight.publish(BEAM);
        outsideAabb();
        insideWedge();
        sectionCost();
        signatureCost();
    }

    private static void noBeam() {
        BeamBlockLight.publish(null);
        for (int i = 0; i < WARMUP; i++) {
            sink += BeamBlockLight.level(i & 15, 64, i & 15);
        }
        final long start = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            sink += BeamBlockLight.level(i & 15, 64, i & 15);
        }
        report("level(), no beam published", System.nanoTime() - start, ITERS);
    }

    private static void outsideAabb() {
        for (int i = 0; i < WARMUP; i++) {
            sink += BeamBlockLight.level(200 + (i & 15), 64, 200 + (i & 7));
        }
        final long start = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            sink += BeamBlockLight.level(200 + (i & 15), 64, 200 + (i & 7));
        }
        report("level(), outside the beam AABB (reject)", System.nanoTime() - start, ITERS);
    }

    private static void insideWedge() {
        for (int i = 0; i < WARMUP; i++) {
            sink += BeamBlockLight.level(2 + (i & 11), 64, 2 + (i & 3));
        }
        final long start = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            sink += BeamBlockLight.level(2 + (i & 11), 64, 2 + (i & 3));
        }
        report("level(), inside the wedge (full maths)", System.nanoTime() - start, ITERS);
    }

    /**
     * One section rebuild's worth of lookups: 2500 distinct block positions. The vanilla
     * {@code BrightnessCache} only keeps 100 entries, so a real 16^3 terrain section asks about
     * this many times and the mixin's hook runs once per ask.
     */
    private static void sectionCost() {
        final int sections = 400;
        long sum = 0L;
        for (int w = 0; w < 50; w++) {
            for (int i = 0; i < 2500; i++) {
                sink += BeamBlockLight.level(i & 15, 60 + (i & 7), (i * 7) & 23);
            }
        }
        final long start = System.nanoTime();
        for (int s = 0; s < sections; s++) {
            for (int i = 0; i < 2500; i++) {
                sum += BeamBlockLight.level(i & 15, 60 + (i & 7), (i * 7) & 23);
            }
        }
        final long t = System.nanoTime() - start;
        System.out.printf("  one 16^3 section, 2500 distinct positions: %.4f ms  (%.1f ns/pos)%n",
                t / 1.0E6D / sections, t / (double) (sections * 2500));
        sink += (int) sum;
    }

    private static void signatureCost() {
        for (int i = 0; i < 2000; i++) {
            sink += BeamBlockLight.signature(i & 7, 4, i & 7);
        }
        final long start = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            sink += BeamBlockLight.signature(i & 7, 4, i & 7);
        }
        report("signature(), 64 samples per section", System.nanoTime() - start, 200_000);
    }

    private static void report(final String what, final long nanos, final int iters) {
        System.out.printf("%-46s %8.1f ns/op  (%d ops)%n", what, nanos / (double) iters, iters);
    }
}
