package dev.js.mtrheadlight.ldl;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;

/**
 * LDL discovery entrypoint, 4.x line (MC 1.20.1).
 *
 * <p>The key is {@link DynamicLightsInitializer#ENTRYPOINT_KEY}, whose constant value in
 * lambdynamiclights-api-4.4.0+1.20.1 is literally {@code "lambdynlights:initializer"} - the same
 * string {@code LambDynLights#initializeApi()} passes to
 * {@code FabricLoader#getEntrypointContainers(String, Class)}. Fabric keys entrypoints by the exact
 * string a mod declares, so {@code fabric.mod.json} must repeat it verbatim under
 * {@code entrypoints} (it does).
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
        // Proof of life: this prints "[lte] ldl: ready mod=lambdynlights ...", so a report without
        // that line means the entrypoint never ran (mod not installed, or key/class mismatch).
        PlayerDynamicLight.onInitializerRan();
    }

    @Override
    public void onInitializeDynamicLights() {
        // Abstract in the API; LDL 4.x always calls the DynamicLightsContext overload.
    }
}
