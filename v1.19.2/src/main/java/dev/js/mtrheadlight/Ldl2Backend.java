package dev.js.mtrheadlight;

import dev.lambdaurora.lambdynlights.DynamicLightSource;
import dev.lambdaurora.lambdynlights.LambDynLights;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Light backend for LambDynamicLights 2.x (MC 1.18-1.19).
 *
 * <p>2.x has no line-light concept: the API only accepts {@link DynamicLightSource} points. A segment
 * is therefore approximated by {@link #POINTS_PER_LINE} evenly spaced point lights, which is what the
 * light engine accumulates per block anyway.
 */
public final class Ldl2Backend implements LightBackend {
    private static final int POINTS_PER_LINE = 8;

    private final Map<String, PointLight[]> lines = new HashMap<>();

    @Override
    public void updateLine(final String key, final Vec3d start, final Vec3d end, final int luminance) {
        PointLight[] points = lines.get(key);
        if (points == null) {
            points = new PointLight[POINTS_PER_LINE];
            for (int i = 0; i < points.length; i++) {
                final PointLight light = new PointLight();
                points[i] = light;
                attach(light);
            }
            lines.put(key, points);
        }
        for (int i = 0; i < points.length; i++) {
            final double t = points.length == 1 ? 0.0D : (double) i / (double) (points.length - 1);
            points[i].set(
                    start.x + (end.x - start.x) * t,
                    start.y + (end.y - start.y) * t,
                    start.z + (end.z - start.z) * t,
                    luminance
            );
        }
    }

    @Override
    public void hideLine(final String key) {
        final PointLight[] points = lines.get(key);
        if (points != null) {
            for (final PointLight light : points) {
                light.luminance = 0;
            }
        }
    }

    @Override
    public void removeLine(final String key) {
        final PointLight[] points = lines.remove(key);
        if (points != null) {
            for (final PointLight light : points) {
                detach(light);
            }
        }
    }

    @Override
    public void clearAll() {
        for (final Iterator<PointLight[]> it = lines.values().iterator(); it.hasNext(); ) {
            for (final PointLight light : it.next()) {
                detach(light);
            }
            it.remove();
        }
    }

    private static void attach(final PointLight light) {
        try {
            LambDynLights.get().addLightSource(light);
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.debug("[mtr-headlight] could not add light source", e);
        }
    }

    private static void detach(final PointLight light) {
        try {
            LambDynLights.get().removeLightSource(light);
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.debug("[mtr-headlight] could not remove light source", e);
        }
    }

    /** A free-floating point light; LDL 2.x accumulates these per block. */
    private static final class PointLight implements DynamicLightSource {
        double x;
        double y;
        double z;
        volatile int luminance;

        void set(final double x, final double y, final double z, final int luminance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.luminance = luminance;
        }

        @Override
        public double getDynamicLightX() {
            return x;
        }

        @Override
        public double getDynamicLightY() {
            return y;
        }

        @Override
        public double getDynamicLightZ() {
            return z;
        }

        @Override
        public World getDynamicLightWorld() {
            return MinecraftClient.getInstance().world;
        }

        @Override
        public void resetDynamicLight() {
        }

        @Override
        public int getLuminance() {
            return luminance;
        }

        @Override
        public void dynamicLightTick() {
        }

        @Override
        public boolean shouldUpdateDynamicLight() {
            return true;
        }

        @Override
        public boolean lambdynlights$updateDynamicLight(final WorldRenderer renderer) {
            return true;
        }

        @Override
        public void lambdynlights$scheduleTrackedChunksRebuild(final WorldRenderer renderer) {
        }
    }
}
