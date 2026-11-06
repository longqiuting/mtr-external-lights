package dev.js.mtrheadlight.mixin.nte;

import dev.js.mtrheadlight.RailLightInject;
import cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feeds dynamic light into the light value NTE bakes into its rail vertices.
 *
 * <p>NTE calls {@code World#getLightLevel(LightType, BlockPos)} twice per rail joint (sky and
 * block) and packs the result into the vertex light. That method is a default method on
 * {@code BlockRenderView}, and stock Mixin 0.8.7 cannot inject into interface default methods, so
 * the call sites inside NTE's bake are redirected instead. That is also the narrowest possible
 * scope: only NTE's rail bake is affected, ordinary world/chunk lighting is untouched.
 *
 * <p>The mixin is only applied when NTE is installed (see {@code NteMixinPlugin}); without it the
 * config is simply skipped.
 */
@Mixin(MeshBuildingRailChunk.class)
public abstract class NteBrightnessMixin {
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
