package dev.lambdaurora.lambdynlights.api;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import dev.lambdaurora.lambdynlights.api.entity.EntityLightSourceManager;
import dev.lambdaurora.lambdynlights.api.item.ItemLightSourceManager;

/** Stub mirroring dev.lambdaurora.lambdynlights.api.DynamicLightsContext. */
public interface DynamicLightsContext {
    ItemLightSourceManager itemLightSourceManager();

    EntityLightSourceManager entityLightSourceManager();

    DynamicLightBehaviorManager dynamicLightBehaviorManager();
}
