package dev.js.mtrheadlight.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Post-transform verification helper: did our {@code @ModifyConstant} really rewrite the host's
 * per-frame gate?
 *
 * <p>Mixin treats a mixin with {@code require = 0} as "applied" even when <em>every</em> injector
 * missed its target, so {@code postApply} firing says nothing about whether the boost is live. This
 * scans the already-transformed {@link ClassNode} for the call the handler injects
 * ({@code RailChunkBuildBoost.perFrame()}) - if it is there, the {@code iconst_1} in
 * {@code drawRails} was replaced and the boost is active in this instance.
 *
 * <p>Imports only ASM, which the mixin subsystem has already loaded at this point, so calling this
 * from {@code postApply} cannot prematurely load a game class.
 */
public final class MixinScan {
    private static final String BOOST_OWNER = "dev/js/mtrheadlight/RailChunkBuildBoost";

    private MixinScan() {
    }

    /** True when the transformed class contains a call to {@code RailChunkBuildBoost.perFrame()}. */
    public static boolean containsBoostCall(final ClassNode node) {
        if (node == null || node.methods == null) {
            return false;
        }
        try {
            for (final MethodNode method : node.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
                     insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                        continue;
                    }
                    final MethodInsnNode call = (MethodInsnNode) insn;
                    if (BOOST_OWNER.equals(call.owner) && "perFrame".equals(call.name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }
}
