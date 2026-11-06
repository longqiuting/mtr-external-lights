package dev.js.mtrheadlight.mixin.ante;

import dev.js.mtrheadlight.RailLightInject;
import fabric.cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feeds dynamic light into the light value ANTE bakes into its rail vertices.
 *
 * <p>ANTE calls {@code World#getLightLevel(LightType, BlockPos)} twice per rail joint (sky and
 * block) and packs the result into the vertex light. That method is a default method on
 * {@code BlockRenderView}, and stock Mixin 0.8.7 cannot inject into interface default methods, so
 * the call sites inside ANTE's bake are redirected instead. That is also the narrowest possible
 * scope: only ANTE's rail bake is affected, ordinary world/chunk lighting is untouched.
 *
 * <p>Applied only when ANTE is installed; see {@code AnteMixinPlugin}.
 */
@Mixin(MeshBuildingRailChunk.class)
public abstract class AnteBrightnessMixin {
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
