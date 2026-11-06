package dev.js.mtrheadlight;

import mtr.data.Rail;
import mtr.data.Train;
import mtr.path.PathData;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline harness: builds a synthetic 3-car train on a straight +X track and checks
 * that {@link TrainPose} puts the front at the highest path progress and each following
 * car one {@code spacing} behind, with the forward vector pointing along +X.
 *
 * Run with tools/typecheck/run.sh (or .ps1). Not part of the shipped mod.
 */
public final class PoseCheck {
    public static void main(final String[] args) {
        final Train train = new Train();
        train.trainCars = 2;
        train.spacing = 20;
        train.path = straightTrack(3, 20.0D, 64.0D);
        train.setRailProgress(25.0D);

        final TrainPose pose = TrainPose.of(train);
        System.out.println("boundaries:");
        for (int i = 0; i < pose.boundaries.length; i++) {
            System.out.printf("  [%d] %s%n", i, fmt(pose.boundaries[i]));
        }
        System.out.println("forward = " + fmt(pose.forward()));
        System.out.println("backward = " + fmt(pose.backward()));

        boolean ok = true;
        ok &= expect("front.x", 25.0D, pose.front().x);
        ok &= expect("front y", 64.0D, pose.front().y);
        ok &= expect("car1.x", 5.0D, pose.boundaries[1].x);
        ok &= expect("rear.x", 0.0D, pose.rear().x);
        ok &= expect("forward.x", 1.0D, pose.forward().x);
        ok &= expect("forward.z", 0.0D, pose.forward().z);
        ok &= expect("backward.x", -1.0D, pose.backward().x);

        System.out.println(ok ? "POSE_CHECK: PASS" : "POSE_CHECK: FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static List<PathData> straightTrack(final int rails, final double railLength, final double y) {
        final List<PathData> path = new ArrayList<>();
        for (int i = 0; i < rails; i++) {
            final double x0 = i * railLength;
            path.add(new PathData(new Rail() {
                @Override
                public Vec3d getPosition(final double progress) {
                    return new Vec3d(x0 + progress, y, 0.0D);
                }

                @Override
                public double getLength() {
                    return railLength;
                }
            }));
        }
        return path;
    }

    private static boolean expect(final String name, final double expected, final double actual) {
        final boolean ok = Math.abs(expected - actual) < 1.0E-6D;
        System.out.printf("%-12s expected=%.3f actual=%.3f %s%n", name, expected, actual, ok ? "ok" : "MISMATCH");
        return ok;
    }

    private static String fmt(final Vec3d v) {
        return String.format("(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }
}
