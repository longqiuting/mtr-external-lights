package dev.lambdaurora.lambdynlights.api.behavior;

/** Stub mirroring the LambDynamicLights 4.4.0 behaviour manager. */
public interface DynamicLightBehaviorManager {
    void add(DynamicLightBehavior behavior);

    boolean remove(DynamicLightBehavior behavior);
}
