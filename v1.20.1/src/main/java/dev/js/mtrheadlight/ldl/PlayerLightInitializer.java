package dev.js.mtrheadlight.ldl;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;

/**
 * LDL discovery entrypoint ({@link DynamicLightsInitializer#ENTRYPOINT_KEY}, i.e.
 * {@code lambdynlights:initializer}), 4.x line (MC 1.20.1).
 *
 * <p>Fabric only invokes an entrypoint whose key a <em>loaded</em> mod declares, so on a client
 * without LambDynamicLights this class is never instantiated - which is exactly why it is the only
 * place allowed to name LDL types besides {@link LdlPlayerLight}. No {@code depends} entry is added
 * for {@code lambdynlights}: it is a genuinely optional dependency.
 */
public final class PlayerLightInitializer implements DynamicLightsInitializer {

    @Override
    public void onInitializeDynamicLights(final DynamicLightsContext context) {
        LdlPlayerLight.install(context.dynamicLightBehaviorManager());
        PlayerDynamicLight.reportInstalled();
    }

    @Override
    public void onInitializeDynamicLights() {
        // Only loaders that call the no-arg variant land here; LDL 4.x uses the context overload.
    }
}
