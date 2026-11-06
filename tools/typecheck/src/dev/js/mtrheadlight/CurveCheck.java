package dev.js.mtrheadlight;

import mtr.data.Rail;
import mtr.data.Train;
import mtr.path.PathData;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline geometry harness for the per-car pose: straight + curved track, reversal, clamping.
 *
 * <p>No Minecraft, no MTR runtime - it compiles against the stubs in { tools/typecheck/stubs}.
 * From the repo root:
 * <pre>
 * J="C:/Program Files/Java/jdk-21.0.10/bin"
 * "/javac" -encoding UTF-8 -d /tmp/pc 197121find tools/typecheck/stubs -name "*.java") \n *     common/src/main/java/dev/js/mtrheadlight/TrainPose.java \n *     tools/typecheck/src/dev/js/mtrheadlight/CurveCheck.java
 * "/java" -cp /tmp/pc dev.js.mtrheadlight.CurveCheck
 * </pre>
 */
public final class CurveCheck {
    private static boolean failed;

    public static void main(final String[] args) {
        straight();
        curve();
        reverse();
        clamped();
        System.out.println(failed ? "CURVE_CHECK: FAIL" : "CURVE_CHECK: PASS");
        if (failed) {
            System.exit(1);
        }
    }

    /** Straight +X: every car heading must be +X, front to rear, and the tail -X. */
    private static void straight() {
        final Train train = train(2, 20, straightPath(3, 20.0D));
        train.setRailProgress(25.0D);
        final TrainPose p = TrainPose.of(train, false);
        check("straight front.x", 25.0D, p.front().x);
        check("straight rear.x", 0.0D, p.rear().x);
        check("straight fwd.x", 1.0D, p.forward().x);
        check("straight back.x", -1.0D, p.backward().x);
        check("straight car1 fwd.x", 1.0D, p.carForward(1).x);
        check("straight cars", 2, p.cars());
        // Degenerate: no cars / empty path must not throw.
        final Train empty = train(0, 20, new ArrayList<>());
        final TrainPose none = TrainPose.of(empty, false);
        check("empty front==ZERO", 1.0D, none.front() == Vec3d.ZERO ? 1.0D : 0.0D);
    }

    /**
     * Circle of radius R in the x/z plane (counter-clockwise), rails cut every ~10 blocks.
     * Heading at arc length s is (-sin(s/R), 0, cos(s/R)).
     */
    private static void curve() {
        final double radius = 40.0D;
        final double arc = 2.0D * Math.PI * radius;
        final List<PathData> path = circlePath(radius, 10.0D);
        final Train train = train(4, 20, path);
        final double s = arc * 0.5D;             // mid-circle: cars sit around the apex
        train.setRailProgress(s);

        final TrainPose p = TrainPose.of(train, false);
        final double spacing = 20.0D;
        for (int i = 0; i < p.cars(); i++) {
            // Car i's centre in travel order is s - (i + 0.5) * spacing ... but each car's own
            // reference point is s - i*spacing; the tangent is taken at the car's MIDPOINT of faces.
            final double mid = s - (i + 0.5D) * spacing;
            final double a = mid / radius;
            final double ex = -Math.sin(a);
            final double ez = Math.cos(a);
            final Vec3d d = p.carForward(i);
            check("curve car" + i + " dx", ex, d.x);
            check("curve car" + i + " dz", ez, d.z);
        }
        // The two ends of a long train on a curve must NOT be parallel.
        final double dot = p.forward().x * p.backward().x + p.forward().z * p.backward().z;
        final double expected = -Math.cos(3.0D * spacing / radius);
        check("curve end dot", expected, dot);
        System.out.printf("  curve end directions: head=(%.3f,%.3f) tail=(%.3f,%.3f)%n",
                p.forward().x, p.forward().z, p.backward().x, p.backward().z);
        // Boundaries must be the rail points (spacing apart along the arc).
        check("curve front on rail", 0.0D, distanceFromCentre(p.front(), radius));
        check("curve rear on rail", 0.0D, distanceFromCentre(p.rear(), radius));
    }

    /** Reversal = "the same train driven the other way": ends swap, headings negate. */
    private static void reverse() {
        final double radius = 40.0D;
        final Train train = train(3, 20, circlePath(radius, 10.0D));
        train.setRailProgress(2.0D * Math.PI * radius * 0.5D);
        final TrainPose fwd = TrainPose.of(train, false);
        final TrainPose rev = TrainPose.of(train, true);
        check("reverse front==old rear x", fwd.rear().x, rev.front().x);
        check("reverse front==old rear z", fwd.rear().z, rev.front().z);
        check("reverse rear==old front x", fwd.front().x, rev.rear().x);
        // Flipping the direction flag is exactly what swapEnds does: same pose would be produced
        // by of(train, !swap) - check the tail now leaves along the old nose car's reversed tangent.
        // Reversed: the old nose is now the tail, and the tail light there still points the way the
        // old head light did (out of the back of the train), so the direction is unchanged.
        check("reverse tail dir x", fwd.forward().x, rev.backward().x);
        check("reverse tail dir z", fwd.forward().z, rev.backward().z);
        check("reverse head dir x", fwd.backward().x, rev.forward().x);
        check("reverse head dir z", fwd.backward().z, rev.forward().z);
    }

    /** Progress near both ends of the path: one-sided samples, no ZERO / NaN. */
    private static void clamped() {
        final Train atStart = train(2, 20, straightPath(3, 20.0D));
        atStart.setRailProgress(0.0D);
        final TrainPose p0 = TrainPose.of(atStart, false);
        check("clamp start fwd.x", 1.0D, p0.forward().x);
        check("clamp start back.x", -1.0D, p0.backward().x);
        check("clamp start front.x", 0.0D, p0.front().x);

        final Train beyond = train(2, 20, straightPath(3, 20.0D));
        beyond.setRailProgress(500.0D);
        final TrainPose p1 = TrainPose.of(beyond, false);
        check("clamp end front.x", 60.0D, p1.front().x);
        check("clamp end fwd.x", 1.0D, p1.forward().x);
        check("clamp end rear.x", 60.0D, p1.rear().x);
        check("clamp end back.x", -1.0D, p1.backward().x);

        // A 3-car train whose tail hangs off the path start: the stacked car inherits a heading.
        final Train shortPath = train(3, 20, straightPath(1, 10.0D));
        shortPath.setRailProgress(5.0D);
        final TrainPose p2 = TrainPose.of(shortPath, false);
        check("short fwd.x", 1.0D, p2.forward().x);
        check("short back.x", -1.0D, p2.backward().x);
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Train train(final int cars, final int spacing, final List<PathData> path) {
        final Train train = new Train();
        train.trainCars = cars;
        train.spacing = spacing;
        train.path = path;
        return train;
    }

    private static List<PathData> straightPath(final int rails, final double length) {
        final List<PathData> path = new ArrayList<>();
        for (int i = 0; i < rails; i++) {
            final double x0 = i * length;
            path.add(new PathData(railAt((double) i * length, length, progress -> new Vec3d(x0 + progress, 64.0D, 0.0D))));
        }
        return path;
    }

    private static List<PathData> circlePath(final double radius, final double railLength) {
        final double arc = 2.0D * Math.PI * radius;
        final int rails = (int) Math.ceil(arc / railLength);
        final double step = arc / rails;
        final List<PathData> path = new ArrayList<>();
        for (int i = 0; i < rails; i++) {
            final double s0 = i * step;
            path.add(new PathData(railAt(s0, step, progress -> {
                final double a = (s0 + progress) / radius;
                return new Vec3d(radius * Math.cos(a), 64.0D, radius * Math.sin(a));
            })));
        }
        return path;
    }

    private interface Position {
        Vec3d at(double progress);
    }

    private static Rail railAt(final double s0, final double length, final Position fn) {
        return new Rail() {
            @Override
            public Vec3d getPosition(final double progress) {
                return fn.at(progress);
            }

            @Override
            public double getLength() {
                return length;
            }
        };
    }

    private static double distanceFromCentre(final Vec3d p, final double radius) {
        return Math.abs(Math.sqrt(p.x * p.x + p.z * p.z) - radius);
    }

    private static void check(final String name, final double expected, final double actual) {
        final boolean ok = Math.abs(expected - actual) < 1.0E-3D;
        if (!ok) {
            failed = true;
        }
        System.out.printf("%-26s expected=%8.4f actual=%8.4f %s%n", name, expected, actual,
                ok ? "ok" : "MISMATCH");
    }
}
