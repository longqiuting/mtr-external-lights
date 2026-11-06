package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * A glowing band drawn along the rails ahead of each train - our own geometry, on top of the rail
 * bed, independent of the baked rail light.
 *
 * <p><b>Off by default</b> ({@code railGlow = false}). The baked light ({@link RailRelightScheduler}
 * + the ANTE mixins) is the normal path; this is the fallback for a shader pack that renders the
 * baked light too dark, and the user can also switch it on for its own sake. Nothing enables it
 * automatically - an active pack does not turn it on, and it never touches the relight scheduler
 * (the two are completely independent: switching the band on must not change how often a chunk is
 * rebuilt, and a rebuild does not touch the band).
 *
 * <p>Geometry is a quad strip sampled along the train's own path ({@link TrainPose#samplePath}) at
 * {@code railGlowStep} blocks, {@code railGlowWidth} wide, lifted {@code railGlowLift} above the
 * rail surface, fading out over {@code railGlowAhead} blocks in front of the nose. Additive
 * blending at {@code railGlowAlpha} (0.12 default) so it reads as light on the rail, not as a
 * plastic ribbon.
 *
 * <h2>Coordinate space (the trap)</h2>
 * {@code WorldRenderContext.matrixStack()} already carries the camera translation, so the vertices
 * must be <b>camera-relative</b> ({@code world - camera.getPos()}). Feeding world coordinates here
 * puts the band at the world origin, which is why this is spelled out twice.
 *
 * <p>Version differences (buffer draw call, matrix accessor, shader supplier) live in the
 * version-local {@link GlowCompat}; the geometry here is shared by all four versions.
 */
public final class RailGlow {
    /** Upper bound on segments per train; a longer band would only be re-sampled detail. */
    private static final int MAX_SAMPLES = 96;
    /** Below this the band is clipped by the rail model itself. */
    private static final double MIN_LIFT = 0.06D;
    /** Floats per vertex: x y z r g b a. */
    private static final int VERTEX_STRIDE = 7;

    /** Reused vertex scratch, grown on demand - the band runs every frame while enabled. */
    private static float[] vertices = new float[0];
    private static int vertexCount;

    private RailGlow() {
    }

    /**
     * Draws the band. Called from both the early and the late world-render event; exactly one of
     * the two actually draws, so a frame never gets two overlapping bands.
     *
     * @param latePass true for the {@code AFTER_TRANSLUCENT} call (the one a shader pack still
     *                 composites), false for {@code AFTER_ENTITIES}
     */
    public static void render(final WorldRenderContext context, final boolean latePass) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        // railGlowAnySiding(): the global switch may be off while one siding overrides the band on.
        if (cfg == null || !cfg.enabled || !cfg.railGlowAnySiding() || cfg.railGlowAlpha <= 0.0D) {
            return;
        }
        final boolean shaders;
        try {
            shaders = ShaderPackProbe.active();
        } catch (Throwable t) {
            return;
        }
        if (shaders != latePass) {
            return;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.gameRenderer == null) {
            return;
        }
        final MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            // Never draw without the world matrix: that would land the band at the origin.
            return;
        }
        final Vec3d shift = client.gameRenderer.getCamera().getPos();
        final Vec3d eye = client.player.getPos();

        final double step = Math.max(cfg.railGlowStep, 0.5D);
        final double lift = Math.max(MIN_LIFT, cfg.railGlowLift);
        final double halfWidth = Math.max(0.1D, cfg.railGlowWidth) * 0.5D;

        vertexCount = 0;
        for (final TrainClient train : snapshotTrains()) {
            // The band follows the same per-siding switches as the beams: a siding whose glow is
            // off (or whose light is off, or whose distance is shorter) gets no band.
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train == null ? 0L : train.sidingId);
            if (!eff.enabled || !eff.railGlow) {
                continue;
            }
            if (!visible(cfg, train, eye)) {
                continue;
            }
            final TrainPose pose = TrainPose.of(train);
            if (pose.front().distanceTo(eye) > eff.maxDistance
                    && pose.rear().distanceTo(eye) > eff.maxDistance) {
                continue;
            }
            final double progress = train.getRailProgress();
            final double total = TrainPose.pathLength(train);
            if (total <= 0.0D) {
                continue;
            }
            // Under a pack only the part in front of the nose is drawn: the body of the train
            // hides the rest, and a band under the cars is what looked like a plastic strip.
            final double from = shaders
                    ? progress
                    : Math.max(progress - (double) train.trainCars * (double) train.spacing, 0.0D);
            final double to = Math.min(progress + cfg.railGlowAhead, total);
            if (to <= from) {
                continue;
            }
            final int samples = (int) Math.min(MAX_SAMPLES,
                    Math.floor((to - from) / step) + 1.0D);
            if (samples < 2) {
                continue;
            }
            for (int i = 0; i < samples - 1; i++) {
                final double t0 = from + (to - from) * ((double) i / (double) (samples - 1));
                final double t1 = from + (to - from) * ((double) (i + 1) / (double) (samples - 1));
                final Vec3d p0 = TrainPose.samplePath(train, t0);
                final Vec3d p1 = TrainPose.samplePath(train, t1);
                if (p0 == Vec3d.ZERO || p1 == Vec3d.ZERO) {
                    continue;
                }
                final Vec3d dir = p1.subtract(p0);
                if (dir.lengthSquared() < 1.0E-6D) {
                    continue;
                }
                final Vec3d unit = dir.normalize();
                Vec3d side = unit.crossProduct(new Vec3d(0.0D, 1.0D, 0.0D));
                if (side.lengthSquared() < 1.0E-8D) {
                    side = unit.crossProduct(new Vec3d(1.0D, 0.0D, 0.0D));
                }
                if (side.lengthSquared() < 1.0E-12D) {
                    continue;
                }
                side = side.normalize().multiply(halfWidth);

                final float alpha0 = alphaAt(cfg, t0, progress, to, shaders);
                final float alpha1 = alphaAt(cfg, t1, progress, to, shaders);
                if (alpha0 <= 0.001F && alpha1 <= 0.001F) {
                    continue;
                }

                // Camera-relative: the matrix stack already contains the camera translation.
                final Vec3d left0 = p0.add(side).add(0.0D, lift, 0.0D).subtract(shift);
                final Vec3d right0 = p0.subtract(side).add(0.0D, lift, 0.0D).subtract(shift);
                final Vec3d left1 = p1.add(side).add(0.0D, lift, 0.0D).subtract(shift);
                final Vec3d right1 = p1.subtract(side).add(0.0D, lift, 0.0D).subtract(shift);

                quad(left0, right0, right1, left1, alpha0, alpha1);
            }
        }

        if (vertexCount > 0) {
            GlowCompat.draw(vertices, vertexCount, matrices);
            vertexCount = 0;
        }
    }

    /** Writes one quad (4 vertices, additive warm white) into the shared scratch. */
    private static void quad(final Vec3d a0, final Vec3d b0, final Vec3d b1, final Vec3d a1,
                             final float alpha0, final float alpha1) {
        final int needed = vertexCount + 4 * VERTEX_STRIDE;
        if (needed > vertices.length) {
            final float[] grown = new float[Math.max(needed, vertices.length * 2 + 64)];
            System.arraycopy(vertices, 0, grown, 0, vertexCount);
            vertices = grown;
        }
        vertexCount = put(a0, alpha0, vertices, vertexCount);
        vertexCount = put(b0, alpha0, vertices, vertexCount);
        vertexCount = put(b1, alpha1, vertices, vertexCount);
        vertexCount = put(a1, alpha1, vertices, vertexCount);
    }

    private static int put(final Vec3d p, final float alpha, final float[] out, final int index) {
        int i = index;
        out[i++] = (float) p.x;
        out[i++] = (float) p.y;
        out[i++] = (float) p.z;
        out[i++] = 1.0F;
        out[i++] = 0.97F;
        out[i++] = 0.88F;
        out[i++] = alpha;
        return i;
    }

    /**
     * Alpha along the band: full at the nose and fading to zero at the far end, so the band has no
     * visible edge. Behind the nose (never drawn under a pack) it is a faint constant.
     */
    private static float alphaAt(final HeadlightConfig cfg, final double along, final double progress,
                                 final double end, final boolean aheadOnly) {
        final double ahead = along - progress;
        if (ahead >= 0.0D) {
            final double span = Math.max(end - progress, 0.5D);
            final double fade = 1.0D - Math.min(ahead / span, 1.0D);
            final double shaped = aheadOnly ? fade * fade : 0.35D + 0.65D * fade;
            return (float) (cfg.railGlowAlpha * shaped);
        }
        return aheadOnly ? 0.0F : (float) (cfg.railGlowAlpha * 0.25D);
    }

    private static boolean visible(final HeadlightConfig cfg, final TrainClient train, final Vec3d eye) {
        if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
            return false;
        }
        // The per-siding switch (incl. the migrated muted-sidings list) is applied by the caller,
        // which already holds the resolved values for this train's siding.
        return !cfg.onlyTrains || train.transportMode == TransportMode.TRAIN;
    }

    private static List<TrainClient> snapshotTrains() {
        try {
            return new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
