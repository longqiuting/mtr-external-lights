package dev.lambdaurora.lambdynlights.api.behavior;

import net.minecraft.util.math.BlockPos;

import java.util.stream.Stream;

/** Stub mirroring the LambDynamicLights 4.4.0 API interface. */
public interface DynamicLightBehavior {
    double lightAtPos(BlockPos pos, double factor);

    BoundingBox getBoundingBox();

    boolean hasChanged();

    default boolean isRemoved() {
        return false;
    }

    record BoundingBox(int startX, int startY, int startZ, int endX, int endY, int endZ) {
    }

    static Stream<Object> none() {
        return Stream.empty();
    }
}
