package dev.js.mtrheadlight.mixin.fangsu;

import dev.js.mtrheadlight.FangsuTurnoutLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Feeds dynamic light into 方速 (fangsu)'s self-rendered turnout OBJ.
 *
 * <p>fangsu draws the switch itself, outside ANTE's bake: the vanilla {@code light} int goes
 * {@code TurnoutBlockEntityRenderer#render -> BaseBlockEntityRender#render -> EyeCandyDrawCalls
 * #commit/#renderDirect -> vertex light}, and nothing in that chain ever asks
 * {@code World#getLightLevel}, so neither the train beams nor LambDynamicLights reach it. This
 * mixin rewrites that one int on its way into the base renderer.
 *
 * <h2>Injection point (verified with {@code javap -p -c}, fangsu 1.3.2 build20261102090023)</h2>
 * <pre>
 * public void render(com.fangsu.blockEntities.BaseObjBlockEntity, float,
 *                    net.minecraft.class_4587, net.minecraft.class_4597, int, int);
 *    0: aload_0
 *    1: aload_1                 // BaseObjBlockEntity  -&gt; slot 1, arg 0 of the call
 *    2: fload_2                 // partialTick         -&gt; slot 2, arg 1
 *    3: aload_3                 // MatrixStack         -&gt; slot 3, arg 2
 *    4: aload 4                 // VertexConsumerProvider -&gt; slot 4, arg 3
 *    6: iload 5                 // light               -&gt; slot 5, arg 4
 *    8: iload 6                 // overlay             -&gt; slot 6, arg 5
 *   10: invokespecial BaseBlockEntityRender.render:(Lcom/fangsu/blockEntities/BaseObjBlockEntity;F
 *                     Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V
 * </pre>
 * So the super call is the very first thing the method does and its 5th argument is the light.
 *
 * <h2>Why {@code @ModifyArgs} and not {@code @ModifyVariable} / {@code @Inject}</h2>
 * <ul>
 *   <li>{@code @ModifyVariable(index = 5)} only hands over the int - the block position, which is
 *       what the light lookup needs, is not reachable from it.</li>
 *   <li>{@code @Inject} with arguments cannot be used at all: mixin requires handler parameter
 *       types to be <em>identical</em> to the target's ({@code Injector.checkCoerce} only allows a
 *       cast when the parameter carries {@code @Coerce} and is a <em>subtype</em>), so capturing
 *       argument 0 would need {@code com.fangsu.blockEntities.BaseObjBlockEntity} in this file -
 *       exactly the compile-time dependency we refuse.</li>
 *   <li>{@code @ModifyArgs} is typed as {@code Args}, a mixin class, so both the block entity
 *       ({@code args.get(0)}, as {@code Object}) and the light ({@code args.get(4)}) can be read
 *       without naming any fangsu type, and only the light is written back.</li>
 * </ul>
 *
 * <p>The {@code target} descriptor is written in <b>intermediary</b> names ({@code class_4587},
 * {@code class_4597}) on purpose: fangsu is a production jar, so its own bytecode refers to those
 * names, and this mixin has no refmap entry for it (nothing to remap). The descriptor also pins the
 * exact call shape, so there is no "right index, wrong variable" hazard of the kind a bare
 * {@code index = 5} carries.
 *
 * <p>{@code require = 0}: if a fangsu update alters this call (renamed class, extra argument,
 * different owner) the injection is dropped and the switch simply keeps its ambient lightmap.
 */
@Mixin(targets = "com.fangsu.blocks.client.TurnoutBlockEntityRenderer")
public abstract class FangsuTurnoutRenderMixin {
    @ModifyArgs(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/fangsu/blockEntities/client/BaseBlockEntityRender;render("
                            + "Lcom/fangsu/blockEntities/BaseObjBlockEntity;F"
                            + "Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V"
            ),
            require = 0
    )
    private void mtrheadlight$liftTurnoutLight(final Args args) {
        try {
            final int light = args.get(4);
            final int lifted = FangsuTurnoutLight.lift(args.get(0), light);
            if (lifted != light) {
                args.set(4, lifted);
            }
        } catch (Throwable t) {
            // A light helper must never take a block entity render down; leave the args untouched.
        }
    }
}
