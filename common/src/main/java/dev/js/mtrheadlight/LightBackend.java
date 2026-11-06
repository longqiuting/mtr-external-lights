package dev.js.mtrheadlight;

import net.minecraft.util.math.Vec3d;

/**
 * Version-specific bridge into the dynamic light engine.
 *
 * <p>LambDynamicLights changed its API completely between the 2.x line (MC 1.18-1.19) and the 4.x
 * line (MC 1.20.1+): 4.x has {@code LineLightBehavior} registered on a behaviour manager, while 2.x
 * only accepts {@code DynamicLightSource} points. Everything above this interface (train lookup,
 * culling, lifecycle, config, GUI) is shared.
 */
public interface LightBackend {
    /** Add or move one light segment. Called every tick for every visible end. */
    void updateLine(String key, Vec3d start, Vec3d end, int luminance);

    /** Switch a segment off without unregistering it (used while a train is briefly missing). */
    void hideLine(String key);

    /** Unregister a segment for good. */
    void removeLine(String key);

    /** Drop everything (world change, master switch off, manual reload). */
    void clearAll();
}
