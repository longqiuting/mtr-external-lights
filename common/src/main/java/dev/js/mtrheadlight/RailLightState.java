package dev.js.mtrheadlight;

import net.minecraft.util.math.BlockPos;

/**
 * Last known train beams, used only as a fallback when LambDynamicLights cannot be queried.
 *
 * <p>Published from the client thread a few times per second (see {@link RailRelightScheduler}); the bake
 * thread reads the immutable array snapshot, so no locks are needed. Layout is seven doubles per
 * segment: {@code x1 y1 z1 x2 y2 z2 luminance}.
 */
public final class RailLightState {
    private static final double[] NONE = new double[0];
    private static volatile double[] segments = NONE;

    private RailLightState() {
    }

    /** Publishes a new immutable snapshot; the caller must not mutate the array afterwards. */
    public static void publish(final double[] newSegments) {
        segments = newSegments == null ? NONE : newSegments;
    }

    public static void clear() {
        segments = NONE;
    }

    /**
     * Rough own-beam luminance at a block position: distance to the segment, linear falloff.
     * Returns 0 when no beam reaches the position.
     */
    public static double ownLevel(final BlockPos pos) {
        final double[] all = segments;
        if (all.length == 0) {
            return 0.0D;
        }
        final double px = pos.getX() + 0.5D;
        final double py = pos.getY() + 0.5D;
        final double pz = pos.getZ() + 0.5D;
        double best = 0.0D;
        for (int i = 0; i + 6 < all.length; i += 7) {
            final double ax = all[i];
            final double ay = all[i + 1];
            final double az = all[i + 2];
            final double bx = all[i + 3];
            final double by = all[i + 4];
            final double bz = all[i + 5];
            final double abx = bx - ax;
            final double aby = by - ay;
            final double abz = bz - az;
            final double lenSq = abx * abx + aby * aby + abz * abz;
            double t = 0.0D;
            if (lenSq > 1.0E-6D) {
                t = ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / lenSq;
                t = t < 0.0D ? 0.0D : (t > 1.0D ? 1.0D : t);
            }
            final double dx = px - (ax + abx * t);
            final double dy = py - (ay + aby * t);
            final double dz = pz - (az + abz * t);
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            final double level = all[i + 6] - distance * 1.5D;
            if (level > best) {
                best = level;
            }
        }
        return best;
    }
}
