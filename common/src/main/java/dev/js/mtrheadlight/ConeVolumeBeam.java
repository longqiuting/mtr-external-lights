package dev.js.mtrheadlight;

import net.minecraft.util.math.Vec3d;

/**
 * One beam handed from {@link ProtoBeamCone} to the version-local cone renderer.
 *
 * <p>Deliberately plain data: <b>how</b> a beam is rasterised is a per-version decision (a
 * depth-aware raymarched volume on 1.20.1, the legacy additive shell stack everywhere else), so
 * the shared code only decides <i>where</i> a beam is and how bright it is.
 */
public final class ConeVolumeBeam {
    /** World-space apex (the lamp). */
    public final Vec3d apex;
    /** Unit axis, apex -> tip. */
    public final Vec3d axis;
    /** Beam length in blocks. */
    public final double length;
    /** Radius at the tip, blocks. */
    public final double radius;
    /** {@code #RRGGBB}. */
    public final int rgb;
    /** Alpha budget of the beam (see {@code HeadlightConfig#beamConeAlpha}). */
    public final float alpha;
    /**
     * Ray-march samples per fragment for this beam (4–16), resolved per train from
     * {@link HeadlightConfig.Effective#beamConeSteps}. The renderer's uniform is per beam, so two
     * trains on different sidings can march at different quality in the same frame.
     */
    public final int steps;

    public ConeVolumeBeam(final Vec3d apex, final Vec3d axis, final double length,
                          final double radius, final int rgb, final float alpha,
                          final int steps) {
        this.apex = apex;
        this.axis = axis.normalize();
        this.length = length;
        this.radius = radius;
        this.rgb = rgb;
        this.alpha = alpha;
        this.steps = steps;
    }

    /** Half angle of the cone in radians. */
    public double halfAngle() {
        return Math.atan2(radius, length);
    }
}
