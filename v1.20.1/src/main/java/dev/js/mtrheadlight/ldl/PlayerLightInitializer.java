package dev.js.mtrheadlight.ldl;

import dev.js.mtrheadlight.LteLog;
import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;

/**
 * LDL discovery entrypoint, 4.x line (MC 1.20.1).
 *
 * <h2>The contract, as javap prints it</h2>
 * <pre>
 *   public interface DynamicLightsInitializer {
 *     public static final String ENTRYPOINT_KEY = "lambdynlights:initializer";
 *     public default  void onInitializeDynamicLights(DynamicLightsContext context);  // calls the no-arg
 *     public abstract void onInitializeDynamicLights();
 *   }
 * </pre>
 * {@code lambdynamiclights-4.4.0+1.20.1.jar}, {@code LambDynLights#invokeInitializer(DynamicLightsInitializer)}
 * executes {@code INVOKEINTERFACE onInitializeDynamicLights(DynamicLightsContext)} — the <b>context</b>
 * overload — for every container registered under the key
 * {@code FabricLoader#getEntrypointContainers("lambdynlights:initializer", DynamicLightsInitializer.class)}.
 * {@code initializeApi()} asks for two keys: {@code lambdynlights:initializer} and the 2.x legacy
 * {@code dynamiclights}; we only declare the first one.
 *
 * <p>Because the no-arg method is <b>abstract</b>, a class that implements this interface must supply
 * it even though LDL never calls it on the entrypoint path. It is implemented here as a real
 * (idempotent) initialisation rather than an empty body: if any future LDL ever dispatches the no-arg
 * overload, the light still comes up — through {@link LdlPlayerLight#acquireReflectively()} instead of
 * the context, which is the same manager. An empty body here is exactly the kind of silent "entrypoint
 * ran, nothing happened" that is impossible to see in a log.
 *
 * <p>Fabric only invokes an entrypoint whose key a <em>loaded</em> mod declares, so on a client without
 * LambDynamicLights this class is never instantiated - which is why it is allowed to name LDL types.
 * No {@code depends} entry is added for {@code lambdynlights}: it is a genuinely optional dependency.
 */
public final class PlayerLightInitializer implements DynamicLightsInitializer {

    @Override
    public void onInitializeDynamicLights(final DynamicLightsContext context) {
        LdlPlayerLight.install(context.dynamicLightBehaviorManager());
        LteLog.LOGGER.info("[lte] ldl: entrypoint lambdynlights:initializer -> manager={}",
                LdlPlayerLight.ready() ? "ok" : "null");
        PlayerDynamicLight.onInitializerRan();
    }

    @Override
    public void onInitializeDynamicLights() {
        // Only reached if an LDL build dispatches the no-arg overload (or another mod calls it
        // directly). No context means no manager argument, so pull the same one off the mod instance.
        if (LdlPlayerLight.acquireReflectively()) {
            LteLog.LOGGER.info("[lte] ldl: entrypoint (no context) -> manager=ok via reflection");
            PlayerDynamicLight.onInitializerRan();
        }
    }
}
