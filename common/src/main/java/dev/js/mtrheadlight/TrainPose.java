package dev.js.mtrheadlight;

import mtr.data.Train;
import mtr.path.PathData;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Read-only pose sampling for MTR trains.
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
 * {@code isReversed()}, {@code Rail#getPosition(double)}, {@code Rail#getLength()}),
 * and the cumulative {@code distances} list is recomputed from the rails' lengths
 * (which is exactly how MTR builds it). Only {@code getModelZOffset()} (a per-model
 * cosmetic offset of a fraction of a block) is skipped.
 */
public final class TrainPose {
    /** boundaries[0] is the leading face of car 0, boundaries[trainCars] the trailing face of the last car. */
    public final Vec3d[] boundaries;

    private TrainPose(final Vec3d[] boundaries) {
        this.boundaries = boundaries;
    }

    public static TrainPose of(final Train train) {
        return of(train, train.isReversed());
    }

    /**
     * @param reversed whether the train is running against the path direction. Callers pass a value
     *                 derived from the change in {@code getRailProgress()}, because MTR's
     *                 {@code isReversed()} does not flip at every terminus turnaround.
     */
    public static TrainPose of(final Train train, final boolean reversed) {
        final int cars = train.trainCars;
        final Vec3d[] out = new Vec3d[cars + 1];
        for (int i = 0; i <= cars; i++) {
            out[i] = routePosition(train, reversed ? cars - i : i);
        }
        return new TrainPose(out);
    }

    public Vec3d front() {
        return boundaries[0];
    }

    public Vec3d rear() {
        return boundaries[boundaries.length - 1];
    }

    /** Unit vector pointing out of the front of the train. */
    public Vec3d forward() {
        final Vec3d front = boundaries[0];
        final Vec3d next = boundaries.length > 1 ? boundaries[1] : boundaries[0];
        final Vec3d d = front.subtract(next);
        final double len = d.length();
        return len < 1.0E-4D ? Vec3d.ZERO : d.multiply(1.0D / len);
    }

    /** Unit vector pointing out of the back of the train (opposite of {@link #forward()}). */
    public Vec3d backward() {
        return forward().multiply(-1.0D);
    }

    /** Position along the train's path at an arbitrary progress value. */
    private static Vec3d routePosition(final Train train, final int carIndex) {
        return positionAt(train, train.getRailProgress() - (double) carIndex * train.spacing);
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
            if (progress < before + length) {
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
     * Returns {@link Vec3d#ZERO} for an empty path. Used by {@link RailGlow}.
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
}
