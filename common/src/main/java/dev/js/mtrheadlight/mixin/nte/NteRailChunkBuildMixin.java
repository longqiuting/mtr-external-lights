package dev.js.mtrheadlight.mixin.nte;

import dev.js.mtrheadlight.RailChunkBuildBoost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NTE counterpart of {@code AnteRailChunkBuildMixin}: raises NTE's hard-coded "one rail chunk
 * rebuild per frame" gate.
 *
 * <p>Lives in {@code common} so every version build can ship it (NTE 0.3.3 uses the same class
 * names on 1.18.2 / 1.19.2 / 1.19.4), and a 1.20.1 client with NTE still gets the boost even though
 * that line usually runs ANTE.
 *
 * <p>NTE 0.3.3 {@code RailRenderDispatcher#drawRails(Level, BatchManager, Matrix4f)} has the same
 * loop as ANTE 1.1.x, only with different local-variable slots and without the replay-mod test:
 *
 * <pre>{@code
 *   304: aload        13
 *   306: getfield     RailChunkBase.isDirty:Z
 *   309: ifne         320
 *   312: aload        13
 *   314: getfield     RailChunkBase.bufferBuilt:Z
 *   317: ifne         335
 *   320: iload        7               // buffersRebuilt
 *   322: iconst_1                   // <-- the per-frame limit
 *   323: if_icmpge     332
 *   326: aload        13
 *   328: aload_1
 *   329: invokevirtual RailChunkBase.rebuildBuffer(class_1937)V
 *   332: iinc         7, 1
 * }</pre>
 *
 * <p>Same reasoning as the ANTE version: the existing branch is retargeted (so the queue-empty case
 * costs nothing), the increment below is a separate instruction, and the constant is anchored by a
 * {@code @Slice} between the {@code isDirty} read and the {@code rebuildBuffer} call rather than by
 * ordinal - NTE has two other {@code iconst_1} in the method (the isInstanced flag and the
 * render-level test).
 *
 * <p>Bytecode-verified against all three shipped NTE 0.3.3 jars (1.18.2 / 1.19.2 / 1.19.4 -
 * identical class, same offsets).
 *
 * <p>Everything is {@code require = 0}, so an unverified host only loses the boost. The boot line
 * {@code rail mixin: nte=...} reports whether it actually landed.
 */
@Mixin(targets = "cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher", remap = false)
public abstract class NteRailChunkBuildMixin {

    /** Rewrites the {@code buffersRebuilt < 1} limit to {@code railChunkBuildPerFrame}. */
    @ModifyConstant(
            method = "drawRails",
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lcn/zbx1425/mtrsteamloco/render/rail/RailChunkBase;isDirty:Z"
                    ),
                    to = @At(
                            value = "INVOKE",
                            target = "Lcn/zbx1425/mtrsteamloco/render/rail/RailChunkBase;"
                                    + "rebuildBuffer(Lnet/minecraft/class_1937;)V"
                    )
            ),
            constant = @Constant(intValue = 1, ordinal = 0),
            require = 0
    )
    private int mtrheadlight$raiseRebuildLimit(final int original) {
        RailChunkBuildBoost.call();
        return RailChunkBuildBoost.perFrame();
    }

    /** Diagnostic only: counts real rebuilds for the {@code railbuild:} line. */
    @Inject(
            method = "drawRails",
            at = @At(
                    value = "INVOKE",
                    target = "Lcn/zbx1425/mtrsteamloco/render/rail/RailChunkBase;"
                            + "rebuildBuffer(Lnet/minecraft/class_1937;)V"
            ),
            require = 0
    )
    private void mtrheadlight$countRebuild(final CallbackInfo ci) {
        RailChunkBuildBoost.rebuild();
    }
}
