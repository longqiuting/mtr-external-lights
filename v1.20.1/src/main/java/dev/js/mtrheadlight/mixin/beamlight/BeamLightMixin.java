package dev.js.mtrheadlight.mixin.beamlight;

import dev.js.mtrheadlight.BeamBlockLight;
import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.MtrHeadlightClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bakes the train head beam into <b>vanilla terrain vertices</b> as block light.
 *
 * <p>This is the whole injection: one {@code @Inject} at RETURN on
 * {@link WorldRenderer#getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)}. Verified
 * with {@code javap -c} on {@code minecraft-merged-1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10},
 * that 6-instruction static method is the only place the vanilla block mesher turns a block
 * position into a light value, and every path inside a section build reaches it:
 *
 * <pre>
 *   ChunkBuilder$BuiltChunk$RebuildTask#render(ChunkRendererRegion region, ...)
 *     -&gt; BlockModelRenderer#render(view, ...)
 *          -&gt; renderQuadsSmooth -&gt; AmbientOcclusionCalculator#apply
 *               -&gt; BrightnessCache#getInt                 -&gt; getLightmapCoordinates(view, state, pos)
 *          -&gt; renderQuadsFlat (both call sites)            -&gt; getLightmapCoordinates(view, state, pos)
 *     -&gt; FluidRenderer#render                              -&gt; getLightmapCoordinates(view, pos)
 *                                                               -&gt; delegates to the 3-arg overload
 * </pre>
 *
 * <p>The two-argument overload is exactly {@code getBlockState(pos)} followed by the three-argument
 * call (javap: 12 bytes, one interface call and one static call), so hooking the three-argument one
 * also covers water and lava surfaces without a second injection.
 *
 * <p><b>Why it cannot leak into entity rendering.</b> The hook only acts when the view is a
 * {@link ChunkRendererRegion}, which exists only inside a section build. Entities, particles and
 * the item-in-hand path pass a {@code ClientWorld} and fall straight through on one
 * {@code instanceof} - so the per-frame cost of this feature outside chunk builds is zero.
 *
 * <p><b>Why {@code max} and not "set".</b> The block nibble is raised only when the beam is
 * brighter than what the world already has there, so a torch, a lava lake or a lit tunnel keeps
 * its own light: a head light can never make a scene darker.
 *
 * <p>Version-specific on purpose: only the 1.20.1 source set builds it, because only this version
 * gets the mixin config that lists it. {@code require = 1} there means a mapping change fails the
 * launch loudly instead of leaving a silently dark feature.
 */
@Mixin(WorldRenderer.class)
public abstract class BeamLightMixin {

    /** One-shot file probe: proves the handler ran, independently of any static field identity. */
    private static boolean probed;

    @Inject(
            method = "getLightmapCoordinates(Lnet/minecraft/world/BlockRenderView;"
                    + "Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void mtrheadlight$beamBlockLight(final BlockRenderView view, final BlockState state,
                                                    final BlockPos pos,
                                                    final CallbackInfoReturnable<Integer> cir) {
        if (!probed) {
            probed = true;
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                "lte-beamligh-probe.txt"),
                        "first call view=" + view.getClass().getName()
                                + " pos=" + pos + " thread=" + Thread.currentThread().getName()
                                + " loader=" + BeamBlockLight.class.getClassLoader() + "\n");
            } catch (Throwable ignored) {
                // diagnostic only
            }
        }
        BeamBlockLight.noteCall();
        if (!(view instanceof ChunkRendererRegion)) {
            return;
        }
        BeamBlockLight.noteAlive();
        if (!BeamBlockLight.active()) {
            return;
        }
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (cfg == null || !cfg.beamBlockLight) {
            return;
        }
        try {
            final int original = cir.getReturnValueI();
            final int block = (original >> 4) & 0xF;
            if (block >= 15) {
                // Already at the top of the block-light range; nothing a beam could add.
                return;
            }
            final long start = System.nanoTime();
            final int beam = BeamBlockLight.level(pos.getX(), pos.getY(), pos.getZ());
            if (beam > block) {
                // Only the block nibble (bits 4-7) is rewritten; sky light (bits 20-23) is left
                // untouched, so a beam cannot fake daylight.
                cir.setReturnValue((original & ~0xF0) | (beam << 4));
                BeamBlockLight.countHit(System.nanoTime() - start);
            }
        } catch (Throwable t) {
            // A lighting bug must never break chunk meshing; the section just comes out vanilla.
        }
    }
}
