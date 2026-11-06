package dev.js.mtrheadlight;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;

/**
 * LambDynamicLights discovery entrypoint ({@code lambdynlights:initializer}).
 * LDL hands us its behaviour manager here; we never touch LDL internals beyond this API.
 */
public final class MtrHeadlightDynamicLights implements DynamicLightsInitializer {
    @Override
    public void onInitializeDynamicLights(final DynamicLightsContext context) {
        LightController.setManager(context.dynamicLightBehaviorManager());
    }

    @Override
    public void onInitializeDynamicLights() {
        // Called only by loaders that invoke the no-arg variant; LDL 4.x uses the
        // context overload above (verified in LambDynLights#invokeInitializer bytecode).
    }
}
