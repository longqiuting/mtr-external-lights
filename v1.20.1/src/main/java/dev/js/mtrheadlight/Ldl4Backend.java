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
 *
 * <h2>Off means detached, not dimmed</h2>
 * {@link #hideLine} still dims (it is the "briefly missing" path), but the auto switch
 * ({@link HeadlightSwitch}) does not use it: {@link LightController} <b>removes</b> a segment on the
 * tick it decides the train must be dark. LDL 4.x bakes a behaviour's light into the chunk meshes
 * it touches and only re-lights what a behaviour reports through {@code hasChanged()}; a live
 * behaviour left at luminance 0 is therefore not proof that the ground went dark again, whereas
 * {@code manager.remove(...)} goes through the engine's own detach path. The removal is driven by
 * the per-end state in {@code LightController.Tracked}, so it happens once per transition - never
 * once per tick.
 *
 * <h2>Counters</h2>
 * {@link #liveSources()} is what the 5-second {@code auto light: ... ldl=N} line reports, and
 * {@code ldl4: adds= removes= live=} is printed whenever any of those numbers moved, so a log where
 * {@code lit=off} appears next to a growing {@code adds} count is the signature of a light that is
 * still being re-registered.
 */
public final class Ldl4Backend implements LightBackend {
    private static DynamicLightBehaviorManager manager;

    private final Map<String, LineLightBehavior> lines = new HashMap<>();

    /** Segments handed to LDL / taken back; tick thread only (diagnostics). */
    private static int addCount;
    private static int removeCount;
    private static String lastDiag = "";
    private static long diagWindowStart = System.currentTimeMillis();

    static void setManager(final DynamicLightBehaviorManager behaviorManager) {
        manager = behaviorManager;
        MtrHeadlightClient.LOGGER.info("[lte] LambDynamicLights behaviour manager registered: {}",
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
                addCount++;
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
            removeCount++;
        }
    }

    @Override
    public void clearAll() {
        for (final Iterator<LineLightBehavior> it = lines.values().iterator(); it.hasNext(); ) {
            detach(it.next());
            it.remove();
        }
    }

    /** Segments with a non-zero luminance, i.e. the ones the engine is actually lighting with. */
    @Override
    public int liveSources() {
        int live = 0;
        for (final LineLightBehavior line : lines.values()) {
            if (line != null && line.getLuminance() > 0) {
                live++;
            }
        }
        return live;
    }

    @Override
    public void tick() {
        // Diagnostics only, and only when something changed: a five-second heartbeat on an idle
        // scene would otherwise own the log file (see the 0.1.1 incident in Ldl2Backend's notes).
        if (System.currentTimeMillis() - diagWindowStart < 5000L) {
            return;
        }
        diagWindowStart = System.currentTimeMillis();
        final String now = addCount + "/" + removeCount + "/" + lines.size() + "/" + liveSources();
        if (now.equals(lastDiag)) {
            return;
        }
        lastDiag = now;
        try {
            MtrHeadlightClient.LOGGER.info("[lte] ldl4: adds={} removes={} lines={} live={}",
                    Integer.valueOf(addCount), Integer.valueOf(removeCount),
                    Integer.valueOf(lines.size()), Integer.valueOf(liveSources()));
        } catch (Throwable ignored) {
            // Logging must never break the tick.
        }
    }

    private static void detach(final LineLightBehavior line) {
        if (manager == null) {
            return;
        }
        try {
            manager.remove(line);
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.debug("[lte] failed to detach light", e);
        }
    }
}
