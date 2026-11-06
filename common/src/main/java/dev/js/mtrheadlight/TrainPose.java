package dev.js.mtrheadlight;

import mtr.data.Train;
import mtr.path.PathData;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only pose sampling for MTR trains - <b>per car, not per train</b>.
 *
 * <p>This mirrors what MTR itself does in {@code mtr.data.Train#simulateTrain}:
 *
 * <pre>
 * Vec3d[] positions = new Vec3d[trainCars + 1];
 * for (int i = 0; i &lt;= trainCars; i++)
 *     positions[i] = getRoutePosition(reversed ? trainCars - i : i, spacing);
 * </pre>
 *
 * and {@code mtr.data.Train#getRoutePosition(int, int)}:
 *
 * <pre>
 * double progress = max(railProgress - carIndex * spacing - modelZOffset, 0);
 * int i = getIndex(progress, false);
 * double from = i == 0 ? 0 : distances.get(i - 1);
 * return path.get(i).rail.getPosition(progress - from).add(0, transportMode.railOffset, 0);
 * </pre>
 *
 * <p>We do NOT mixin into MTR: everything used here is public API
 * ({@code path}, {@code spacing}, {@code trainCars}, {@code getRailProgress()},
 * {@code isReversed()}, {@code Rail#getPosition(double)}, {@code Rail#getLength()}), and the
 * cumulative {@code distances} list is recomputed from the rails' lengths (which is exactly how
 * MTR builds it). Only {@code getModelZOffset()} (a per-model cosmetic offset of a fraction of a
 * block) is skipped.
 *
 * <h2>Why each car gets its own heading</h2>
 * The old pose carried a single {@code forward()} for the whole train - the chord from the nose
 * boundary to the second boundary. On a curve that vector describes car 0 only, and every other
 * car (the tail chipped in particular) was oriented as if the train were still straight: the nose
 * had already turned while the last car was still on the straight, so the tail light pointed off
 * the rails.
 *
 * <p>Each car now samples its <b>own</b> rail tangent:
 *
 * <pre>
 * mid_i   = (p_i + p_{i+1}) / 2                         // this car's centre, in path blocks
 * dir_i   = normalize(P(clamp(mid_i + eps)) - P(clamp(mid_i - eps)))   // eps = 0.5 block
 * tailDir = -dir_last                                  // the tail faces backwards along ITS car
 * </pre>
 *
 * The midpoint is deliberate: a car body is a chord of the curve, and the rail tangent at the
 * midpoint is the chord's direction to second order, so the beam leaves the car roughly along the
 * car's own axis instead of being rotated by half the chord angle (which is what sampling at the
 * car's reference point would do).
 *
 * <h2>Ends, reversal, {@code swapEnds}</h2>
 * Index 0 is always the <b>leading</b> boundary in travel order and {@code cars} the trailing one,
 * so the head light belongs to car 0 and the tail light to car {@code cars - 1}. Reversal only
 * changes which MTR car index each travel-order index maps to (exactly like MTR's own
 * {@code reversed ? cars - i : i}), and flipping travel direction is also exactly what the
 * {@code swapEnds} config option means. Callers therefore collapse both into a single boolean:
 *
 * <pre>
 * reversed = departureReversed(train) ^ swapEnds;
 * </pre>
 *
 * and there is no second, duplicated swap branch anywhere.
 *
 * <h2>Sampling budget</h2>
 * One pose = {@code cars + 1} boundary samples + {@code 2 * cars} tangent samples, i.e.
 * {@code 3 * cars + 1} {@code Rail#getPosition} calls (31 for a 10-car train, versus 11 before).
 * {@link #ofShared} keeps that at <b>one build per train per frame</b> even though both consumers
 * (the dynamic-light tick and the beam renderer) ask for the pose.
 */
public final class TrainPose {
    /**
     * Half the window used to read a car's tangent, in blocks. Small enough that the samples stay
     * inside one rail on any normal curve, large enough that {@code getPosition} rounding does not
     * dominate the difference.
     */
    public static final double TANGENT_EPS = 0.5D;

    /** Empty path / no cars: one boundary at the origin, no headings. */
    private static final TrainPose EMPTY = new TrainPose(new Vec3d[]{Vec3d.ZERO}, new Vec3d[0]);

    /**
     * boundaries[0] is the leading face of the first car in travel order, boundaries[cars] the
     * trailing face of the last one; boundaries[i] and boundaries[i+1] are the two ends of car i.
     */
    public final Vec3d[] boundaries;

    /** Unit tangent of each car in travel direction (contains the grade); carDir.length == cars. */
    private final Vec3d[] carDir;

    private TrainPose(final Vec3d[] boundaries, final Vec3d[] carDir) {
        this.boundaries = boundaries;
        this.carDir = carDir;
    }

    public static TrainPose of(final Train train) {
        return of(train, train.isReversed());
    }

    /**
     * @param reversed whether the train is running against the path direction. Callers pass a value
     *                 derived from the change in {@code getRailProgress()}, because MTR's
     *                 {@code isReversed()} does not flip at every terminus turnaround. The
     *                 {@code swapEnds} config option folds into this flag (XOR).
     */
    public static TrainPose of(final Train train, final boolean reversed) {
        final int cars = Math.max(0, train.trainCars);
        if (cars == 0 || train.path.isEmpty()) {
            return EMPTY;
        }
        final double spacing = Math.max(0.0D, (double) train.spacing);
        final double progress = train.getRailProgress();
        final double total = pathLength(train);

        final Vec3d[] boundaries = new Vec3d[cars + 1];
        final double[] at = new double[cars + 1];
        for (int i = 0; i <= cars; i++) {
            // MTR maps travel-order index i onto car index (reversed ? cars - i : i) and puts that
            // car's reference point at railProgress - carIndex * spacing; same arithmetic, so the
            // faces land exactly where MTR draws the cars.
            final double p = clamp(progress - (reversed ? (double) (cars - i) : (double) i) * spacing,
                    total);
            at[i] = p;
            boundaries[i] = positionAt(train, p);
        }

        final Vec3d[] dir = new Vec3d[cars];
        for (int i = 0; i < cars; i++) {
            final double mid = (at[i] + at[i + 1]) * 0.5D;
            Vec3d d = tangent(train, mid, total, reversed);
            if (d == Vec3d.ZERO) {
                // Degenerate rail (zero length, or both samples clamped onto the same spot): fall
                // back to the car's own chord, which needs no extra sampling.
                d = chord(boundaries[i], boundaries[i + 1]);
            }
            if (d == Vec3d.ZERO && i > 0) {
                // A car stacked on top of the previous one (start of a too-short path) inherits its
                // neighbour's heading rather than snapping to zero.
                d = dir[i - 1];
            }
            dir[i] = d;
        }
        return new TrainPose(boundaries, dir);
    }

    /**
     * Same as {@link #of(Train, boolean)} but reuses the previous result while nothing it depends
     * on has changed: same train object, same {@code railProgress}, same direction flag, same car
     * count/spacing, same path list instance. Rail progress only moves on a client tick, so the
     * light tick and the beam render of one frame share one pose instead of sampling twice.
     */
    public static TrainPose ofShared(final Train train, final boolean reversed) {
        final Integer key = Integer.valueOf(System.identityHashCode(train));
        final CachedPose cached = CACHE.get(key);
        if (cached != null && cached.matches(train, reversed)) {
            return cached.pose;
        }
        final TrainPose pose = of(train, reversed);
        CACHE.put(key, new CachedPose(train, reversed, pose));
        return pose;
    }

    /** Drops every cached pose (world change / manual reload). */
    public static void clearCache() {
        CACHE.clear();
    }

    /** Number of cars, i.e. the length of the per-car heading array. */
    public int cars() {
        return carDir.length;
    }

    /** Leading boundary of the train in travel order (the nose). */
    public Vec3d front() {
        return boundaries[0];
    }

    /** Trailing boundary of the train in travel order (the tail face). */
    public Vec3d rear() {
        return boundaries[boundaries.length - 1];
    }

    /**
     * Unit vector out of the nose, along the <b>first car's</b> own tangent. {@link Vec3d#ZERO}
     * when the path is empty or the first car has no usable heading.
     */
    public Vec3d forward() {
        return carDir.length == 0 ? Vec3d.ZERO : carDir[0];
    }

    /**
     * Unit vector out of the tail, along the <b>last car's</b> own tangent, negated. Not
     * necessarily the exact opposite of {@link #forward()}: on a curve the two ends of the train
     * genuinely point in different directions.
     */
    public Vec3d backward() {
        return carDir.length == 0 ? Vec3d.ZERO : carDir[carDir.length - 1].multiply(-1.0D);
    }

    /** Leading face of travel-order car {@code car}. */
    public Vec3d carStart(final int car) {
        return boundaries[clampIndex(car, boundaries.length - 1)];
    }

    /** Trailing face of travel-order car {@code car}. */
    public Vec3d carEnd(final int car) {
        return boundaries[clampIndex(car + 1, boundaries.length - 1)];
    }

    /** Unit tangent of travel-order car {@code car}, in travel direction. */
    public Vec3d carForward(final int car) {
        return carDir.length == 0 ? Vec3d.ZERO : carDir[clampIndex(car, carDir.length - 1)];
    }

    private static int clampIndex(final int value, final int last) {
        return value < 0 ? 0 : Math.min(value, last);
    }

    /**
     * Unit tangent of the rail at {@code progress}, pointing along travel. Sampled at
     * {@code progress -/+} {@value #TANGENT_EPS} blocks and clamped onto {@code [0, total]}, so a
     * car hanging off either end of the path gets a one-sided (still valid) difference.
     */
    private static Vec3d tangent(final Train train, final double progress, final double total,
                                 final boolean reversed) {
        final double low = clamp(progress - TANGENT_EPS, total);
        final double high = clamp(progress + TANGENT_EPS, total);
        if (high - low < 1.0E-6D) {
            return Vec3d.ZERO;
        }
        final Vec3d d = positionAt(train, high).subtract(positionAt(train, low));
        final double len = d.length();
        if (len < 1.0E-6D) {
            return Vec3d.ZERO;
        }
        final Vec3d unit = d.multiply(1.0D / len);
        // Increasing progress is travel direction unless the train runs against the path.
        return reversed ? unit.multiply(-1.0D) : unit;
    }

    /** Unit vector from a car's trailing face towards its leading face. */
    private static Vec3d chord(final Vec3d lead, final Vec3d trail) {
        final Vec3d d = lead.subtract(trail);
        final double len = d.length();
        return len < 1.0E-6D ? Vec3d.ZERO : d.multiply(1.0D / len);
    }

    private static double clamp(final double progress, final double total) {
        if (progress < 0.0D) {
            return 0.0D;
        }
        return progress > total ? total : progress;
    }

    private static Vec3d positionAt(final Train train, final double rawProgress) {
        final List<PathData> path = train.path;
        final int size = path.size();
        if (size == 0) {
            return Vec3d.ZERO;
        }
        final double progress = Math.max(rawProgress, 0.0D);

        double before = 0.0D;
        double length = 0.0D;
        int index = size - 1;
        for (int i = 0; i < size; i++) {
            length = path.get(i).rail.getLength();
            // The last rail also answers "progress at or past the end of the path": without that,
            // an exact-total progress fell through to before == total and returned the START of the
            // last rail (one rail short of where the train really is).
            if (progress < before + length || i == size - 1) {
                index = i;
                break;
            }
            before += length;
        }

        double t = progress - before;
        if (t < 0.0D) {
            t = 0.0D;
        } else if (t > length) {
            t = length;
        }

        final int railOffset = train.transportMode == null ? 0 : train.transportMode.railOffset;
        // MTR 的 jar 未 remap，getPosition 的返回类型在编译期是 intermediary 名(class_243)，
        // 运行期就是 Vec3d —— 经 Object 过渡转换，不引入反射。
        return ((Vec3d) (Object) path.get(index).rail.getPosition(t)).add(0.0D, railOffset, 0.0D);
    }

    /** Total length of the train's path in blocks. */
    public static double pathLength(final Train train) {
        double total = 0.0D;
        for (final PathData data : train.path) {
            total += data.rail.getLength();
        }
        return total;
    }

    /**
     * Position along the train's path at an arbitrary progress value (blocks from the path start).
     * Returns {@link Vec3d#ZERO} for an empty path. Kept as a public sampling helper (used by the
     * offline pose harness); the light and beam geometry only reads {@link #front()} /
     * {@link #rear()} / the per-car headings.
     */
    public static Vec3d samplePath(final Train train, final double progress) {
        return positionAt(train, progress);
    }

    /** The rail segment the train currently sits on (null when the path is empty). */
    public static mtr.data.Rail railAt(final Train train, final double rawProgress) {
        final List<PathData> path = train.path;
        if (path.isEmpty()) {
            return null;
        }
        final double progress = Math.max(rawProgress, 0.0D);
        double before = 0.0D;
        for (int i = 0; i < path.size(); i++) {
            final double length = path.get(i).rail.getLength();
            if (progress < before + length) {
                return path.get(i).rail;
            }
            before += length;
        }
        return path.get(path.size() - 1).rail;
    }

    /** One frame's pose per train; LRU-bounded so a stream of re-synced clients cannot grow it. */
    private static final int CACHE_SIZE = 64;
    private static final Map<Integer, CachedPose> CACHE =
            new LinkedHashMap<Integer, CachedPose>(32, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<Integer, CachedPose> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    private static final class CachedPose {
        private final Train train;
        private final double progress;
        private final boolean reversed;
        private final int cars;
        private final int spacing;
        private final List<PathData> path;
        private final TrainPose pose;

        CachedPose(final Train train, final boolean reversed, final TrainPose pose) {
            this.train = train;
            this.progress = train.getRailProgress();
            this.reversed = reversed;
            this.cars = train.trainCars;
            this.spacing = train.spacing;
            this.path = train.path;
            this.pose = pose;
        }

        boolean matches(final Train other, final boolean direction) {
            // Identity of the path list matters: MTR replaces it wholesale on a re-route, and the
            // progress can be identical across that swap.
            return other == train && direction == reversed && other.trainCars == cars
                    && other.spacing == spacing && other.path == path
                    && other.getRailProgress() == progress;
        }
    }
}
