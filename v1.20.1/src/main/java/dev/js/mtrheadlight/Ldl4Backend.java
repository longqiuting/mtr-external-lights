package dev.js.mtrheadlight;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import dev.lambdaurora.lambdynlights.api.behavior.LineLightBehavior;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Light backend for LambDynamicLights 4.x (MC 1.20.1): one {@link LineLightBehavior} per segment,
 * registered on the behaviour manager LDL hands over in the {@code lambdynlights:initializer} entrypoint.
 */
public final class Ldl4Backend implements LightBackend {
    private static DynamicLightBehaviorManager manager;

    private final Map<String, LineLightBehavior> lines = new HashMap<>();

    static void setManager(final DynamicLightBehaviorManager behaviorManager) {
        manager = behaviorManager;
        MtrHeadlightClient.LOGGER.info("[mtr-headlight] LambDynamicLights behaviour manager registered: {}",
                behaviorManager != null);
    }

    @Override
    public void updateLine(final String key, final Vec3d start, final Vec3d end, final int luminance) {
        LineLightBehavior line = lines.get(key);
        if (line == null) {
            line = new LineLightBehavior(new Vector3d(), new Vector3d(), 0);
            lines.put(key, line);
            if (manager != null) {
                manager.add(line);
            }
        }
        line.setStartPoint(start.x, start.y, start.z);
        line.setEndPoint(end.x, end.y, end.z);
        line.setLuminance(luminance);
    }

    @Override
    public void hideLine(final String key) {
        final LineLightBehavior line = lines.get(key);
        if (line != null) {
            line.setLuminance(0);
        }
    }

    @Override
    public void removeLine(final String key) {
        final LineLightBehavior line = lines.remove(key);
        if (line != null) {
            detach(line);
        }
    }

    @Override
    public void clearAll() {
        for (final Iterator<LineLightBehavior> it = lines.values().iterator(); it.hasNext(); ) {
            detach(it.next());
            it.remove();
        }
    }

    private static void detach(final LineLightBehavior line) {
        if (manager == null) {
            return;
        }
        try {
            manager.remove(line);
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.debug("[mtr-headlight] failed to detach light", e);
        }
    }
}
