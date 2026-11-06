package dev.js.mtrheadlight.mixin.nte;

import dev.js.mtrheadlight.RailLightInject;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same redirect as {@link NteBrightnessMixin}, for NTE's instanced rail path.
 *
 * <p>Which of the two chunks NTE builds rails with is decided per frame by
 * {@code RailRenderDispatcher#drawRails}:
 *
 * <pre>
 * isInstanced = ClientConfig.getRailRenderLevel() == 3;
 * </pre>
 *
 * and {@code getRailRenderLevel()} returns 3 only when render optimisation is on, {@code
 * enableRail3D} is set and {@code ShadersModHandler.canInstance()} is true - i.e. on any client with
 * an OpenGL 4.5+ context, which is the normal case. The instanced chunk therefore owns the visible
 * geometry on a modern GPU, while the mesh-building one is only used on the fallback path.
 *
 * <p>Mixining just {@code MeshBuildingRailChunk} produced exactly that signature: the host was
 * rebuilding rail chunks (our relight requests reached {@code registerLightUpdate}) but
 * {@code rail bake: calls=0} - no {@code getLightLevel} call was ever intercepted, because the
 * method we had wrapped was never executed.
 *
 * <p>{@code InstancedRailChunk#rebuildBuffer(class_1937)} in all three 0.3.3 jars
 * (1.18.2 / 1.19.2 / 1.19.4) contains the same two calls
 * ({@code class_1937.method_8314(class_1944, class_2338)}), so one wrapper covers all of them.
 */
@Mixin(targets = "cn.zbx1425.mtrsteamloco.render.rail.InstancedRailChunk")
public abstract class NteInstancedBrightnessMixin {
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
