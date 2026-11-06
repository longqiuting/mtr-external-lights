package dev.lambdaurora.lambdynlights.api;

/** Stub mirroring the LambDynamicLights 4.4.0 initializer entrypoint. */
public interface DynamicLightsInitializer {
    String ENTRYPOINT_KEY = "lambdynlights:initializer";

    default void onInitializeDynamicLights(DynamicLightsContext context) {
    }

    void onInitializeDynamicLights();
}
