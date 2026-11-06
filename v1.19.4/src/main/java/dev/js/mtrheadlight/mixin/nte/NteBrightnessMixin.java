package dev.js.mtrheadlight.mixin.nte;

import dev.js.mtrheadlight.RailLightInject;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feeds dynamic light into the light value NTE bakes into its rail vertices (mesh-building path).
 *
 * <p>NTE calls {@code World#getLightLevel(LightType, BlockPos)} twice per rail joint (sky and
 * block) and packs the result into the vertex light with {@code LightTexture.pack}. That method is
 * a default method on {@code BlockRenderView}, and stock Mixin 0.8.7 cannot inject into interface
 * default methods, so the call sites inside NTE's bake are redirected instead. That is also the
 * narrowest possible scope: only NTE's rail bake is affected, ordinary world/chunk lighting is
 * untouched.
 *
 * <p><b>How this is wired (see {@code HostProbe} / {@code NteMixinPlugin}):</b>
 * <ul>
 *   <li>{@code @Mixin(targets = "...")} - a <em>string</em>, so the class is never linked at
 *       compile time and a renamed/moved NTE class only loses the feature;</li>
 *   <li>{@code require = 0} - NTE may drop {@code rebuildBuffer} or change the call; the injection
 *       then silently does nothing instead of aborting the game at load;</li>
 *   <li>host probe by <em>resource lookup</em>, never {@code Class.forName} - loading NTE this early
 *       would freeze it before other mods' mixins apply;</li>
 *   <li>NTE ships <b>two</b> chunk implementations and picks one from
 *       {@code ClientConfig.getRailRenderLevel()}: level 3 (instanced, what an OpenGL 4.5+ client
 *       gets) uses {@code InstancedRailChunk}, everything else uses this one. Mixing only one of
 *       them is why {@code rail bake: calls=0} was possible while the rails were visibly rebuilt -
 *       see {@link NteInstancedBrightnessMixin}.</li>
 * </ul>
 */
@Mixin(targets = "cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk")
public abstract class NteBrightnessMixin {
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
