package dev.js.mtrheadlight.mixin.ante;

import dev.js.mtrheadlight.RailLightInject;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same redirect as {@link AnteBrightnessMixin}, but for ANTE's instanced rail path.
 *
 * <p>ANTE ships two chunk implementations ({@code MeshBuildingRailChunk} and
 * {@code InstancedRailChunk}); both bake {@code World#getLightLevel} into their vertices, and which
 * one is used at runtime depends on the render mode. Targeting only one of them left the rail
 * lighting silently inert on the instanced path.
 *
 * <p>String target, for the same reason as {@link AnteBrightnessMixin}: the class is only present
 * at runtime, and {@code javap} on ANTE 1.1.0+1.19.4 confirms both implementations declare
 * {@code rebuildBuffer(Level)} and make the same two {@code getLightLevel} calls.
 */
@Mixin(targets = "fabric.cn.zbx1425.mtrsteamloco.render.rail.InstancedRailChunk")
public abstract class AnteInstancedBrightnessMixin {
    @Redirect(
            method = "rebuildBuffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getLightLevel(Lnet/minecraft/world/LightType;Lnet/minecraft/util/math/BlockPos;)I"
            ),
            require = 0
    )
    private int mtrheadlight$bakeDynamicLight(final World world, final LightType type,
                                              final BlockPos pos) {
        return RailLightInject.resolve(world, type, pos);
    }
}
