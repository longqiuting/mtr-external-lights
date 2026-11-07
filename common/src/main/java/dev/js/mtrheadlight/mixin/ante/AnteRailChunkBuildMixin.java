package dev.js.mtrheadlight.mixin.ante;

import dev.js.mtrheadlight.RailChunkBuildBoost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raises ANTE's hard-coded "one rail chunk rebuild per frame" gate.
 *
 * <p>Lives in {@code common} on purpose: ANTE ships for 1.17.1 ... 1.20.1 and, on Fabric, always
 * under the {@code fabric.cn.zbx1425.mtrsteamloco} package (see the jar's {@code fabric/} tree;
 * the {@code forge/} copy is the Forge build of the same classes). It is therefore the same target
 * on every Minecraft version we build, and a 1.19.4 client with ANTE needs this mixin just as much
 * as a 1.20.1 one. Only the mixin <em>config</em> is version-specific.
 *
 * <p>Target method - identical bytecode in ANTE 1.1.0+1.20.1 and ANTE 1.1.0+1.19.4:
 *
 * <pre>{@code
 *   public void drawRails(Level world, DrawScheduler scheduler, Matrix4f view)
 *   ...
 *   384: getfield     RailChunkBase.isDirty:Z
 *   387: ifne         398
 *   390: getfield     RailChunkBase.bufferBuilt:Z
 *   395: ifne         419
 *   398: invokestatic MTRClient.isReplayMod()Z
 *   401: ifne         410
 *   404: iload        10              // buffersRebuilt
 *   406: iconst_1                    // <-- the per-frame limit
 *   407: if_icmpge     416
 *   410: aload        16
 *   412: aload_1
 *   413: invokevirtual RailChunkBase.rebuildBuffer(class_1937)V
 *   416: iinc         10, 1
 * }</pre>
 *
 * <p>Why {@code @ModifyConstant} on that {@code iconst_1} and not something else:
 *
 * <ul>
 *   <li>It changes the <em>existing</em> branch, so the host's own loop, its nearest-camera-first
 *       ordering and its replay-mode bypass stay untouched. Nothing here calls
 *       {@code rebuildBuffer} itself, which is what makes "queue empty = zero extra work" true by
 *       construction: with nothing dirty the loop never reaches the comparison.</li>
 *   <li>The constant is only the limit - the increment {@code iinc 10, 1} below it is a separate
 *       instruction, so raising it cannot corrupt the counter.</li>
 *   <li>There are three {@code iconst_1} in {@code drawRails} (isInstanced flag, the
 *       replay flag path and this one), so the injection is anchored with a {@code @Slice} between
 *       the {@code isDirty} read and the {@code rebuildBuffer} call instead of counting ordinals.
 *       Inside that slice there is exactly one int constant, verified with {@code javap -c} on both
 *       ANTE 1.1.0 jars.</li>
 * </ul>
 *
 * <p>Wired as a string target with {@code require = 0} (see {@code HostProbe} /
 * {@code AnteMixinPlugin}): if ANTE renames the method or the slice anchors stop matching, the
 * injections are dropped with a mixin warning and the client keeps the stock 1-per-frame behaviour.
 * The boot line {@code rail mixin: ante=...} reports that case explicitly instead of staying silent.
 *
 * <p>{@code remap = false} is required and correct: the ANTE jar is not remapped (that is why the
 * build uses {@code compileOnly} instead of {@code modCompileOnly}), so {@code drawRails},
 * {@code RailChunkBase#rebuildBuffer} and the intermediary {@code class_1937} in the descriptors
 * are already the exact runtime names.
 */
@Mixin(targets = "fabric.cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher", remap = false)
public abstract class AnteRailChunkBuildMixin {

    /**
     * Rewrites the {@code buffersRebuilt < 1} limit to {@code railChunkBuildPerFrame}.
     *
     * <p>The slice runs from the {@code isDirty} field read to the {@code rebuildBuffer} call, which
     * contains exactly one int constant ({@code 1}); {@code ordinal = 0} therefore means "the
     * limit", not "the first iconst_1 in the method".
     */
    @ModifyConstant(
            method = "drawRails",
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lfabric/cn/zbx1425/mtrsteamloco/render/rail/RailChunkBase;isDirty:Z"
                    ),
                    to = @At(
                            value = "INVOKE",
                            target = "Lfabric/cn/zbx1425/mtrsteamloco/render/rail/RailChunkBase;"
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

    /**
     * Diagnostic only: counts the rebuilds that actually happen, so the {@code railbuild:} line can
     * report {@code chunksPerSec} - the number that proves the queue drains faster than stock.
     *
     * <p>Injects <em>before</em> the call, so it also counts the (single) rebuild the replay path
     * performs. Never alters behaviour; a missing match only loses the count.
     */
    @Inject(
            method = "drawRails",
            at = @At(
                    value = "INVOKE",
                    target = "Lfabric/cn/zbx1425/mtrsteamloco/render/rail/RailChunkBase;"
                            + "rebuildBuffer(Lnet/minecraft/class_1937;)V"
            ),
            require = 0
    )
    private void mtrheadlight$countRebuild(final CallbackInfo ci) {
        RailChunkBuildBoost.rebuild();
    }
}
