package dev.js.mtrheadlight;

import net.minecraft.util.math.Vec3d;

/**
 * One car light as a world-space segment - the single implementation of "where does a lamp sit and
 * which way does its beam go".
 *
 * <p>Every consumer of the train ends goes through here ({@link LightController} for the
 * LambDynamicLights line, {@link RailRelightScheduler} for the baked rail light), so the dynamic
 * light and the baked light can never disagree about the endpoint or the heading. {@link BeamGlow}
 * uses the same {@code base} + {@code dir} pair from {@link TrainPose} and only replaces the height
 * (its sheet takes y from the ground under each sample), which is why only x/z are shared with it.
 *
 * <pre>
 * start = base + (dir.x, 0, dir.z) * offset          // lamp, pushed out of the car face
 * end   = start + dir * length                       // straight beam along THAT car's tangent
 * end.y = start.y - tipDrop                          // a slight droop reads as a cone
 * </pre>
 *
 * {@code dir} already contains the grade and is already oriented out of the correct end
 * ({@link TrainPose#forward()} / {@link TrainPose#backward()}, which fold in reversal and
 * {@code swapEnds}) - this class adds no heading logic of its own.
 */
final class EndLight {
    final Vec3d start;
    final Vec3d end;

    private EndLight(final Vec3d start, final Vec3d end) {
        this.start = start;
        this.end = end;
    }

    /**
     * @param base    rail point at the car face this lamp belongs to
     * @param dir     unit heading out of that face (never zero; callers check first)
     * @param offset  gap between the car face and the lamp, already clamped by the config
     * @param length  beam length in blocks
     * @param tipDrop how far the tip sits below the lamp, in blocks
     * @param height  {@link HeadlightConfig#heightOffset}: lamp height above the rail point
     */
    static EndLight of(final Vec3d base, final Vec3d dir, final double offset,
                       final double length, final double tipDrop, final double height) {
        final double y = base.y + height;
        final Vec3d start = new Vec3d(base.x + dir.x * offset, y, base.z + dir.z * offset);
        final Vec3d end = new Vec3d(start.x + dir.x * length, start.y - tipDrop,
                start.z + dir.z * length);
        return new EndLight(start, end);
    }
}
