package dev.js.mtrheadlight.mixin.ante;

import dev.js.mtrheadlight.RailLightInject;
import fabric.cn.zbx1425.mtrsteamloco.render.rail.InstancedRailChunk;
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
 */
@Mixin(InstancedRailChunk.class)
public abstract class AnteInstancedBrightnessMixin {
    @Redirect(
            method = "rebuildBuffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getLightLevel(Lnet/minecraft/world/LightType;Lnet/minecraft/util/math/BlockPos;)I"
            )
    )
    private int mtrheadlight$bakeDynamicLight(final World world, final LightType type,
                                              final BlockPos pos) {
        return RailLightInject.resolve(world, type, pos);
    }
}
