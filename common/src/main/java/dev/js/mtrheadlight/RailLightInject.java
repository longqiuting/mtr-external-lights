package dev.js.mtrheadlight;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

/**
 * The single place where dynamic light is substituted for world light while a host mod bakes
 * its rail mesh.
 *
 * <p>The per-host mixins redirect the two {@code World#getLightLevel} calls inside
 * {@code MeshBuildingRailChunk#rebuildBuffer} (and the instanced twin) into this method, so the
 * version-specific mixin classes hold no logic at all. ANTE packs the two results with
 * {@code LightTexture.pack(block, sky)}
 * ({@code class_765.method_23687(method_8314(BLOCK, pos), method_8314(SKY, pos))}), so the return
 * value of this method <em>is</em> the lightmap component - we never build a packed int here and
 * never invent an attribute.
 *
 * <p>Block light is raised to the dynamic value (what LambDynamicLights does for vanilla chunks).
 * Sky light is passed through untouched, except under a shader pack with
 * {@link HeadlightConfig#railLightShaderBoost} on, where the raised block value is duplicated into
 * the sky slot - see that field for why a pack needs it.
 *
 * <p>The mixin is scoped to {@code rebuildBuffer}, so this method is only ever reached while rails
 * are being baked - no thread-local "inside the bake" flag is needed. That bake runs on the
 * chunk-build worker thread, which is why every external call below is guarded.
 */
public final class RailLightInject {
    private RailLightInject() {
    }

    public static int resolve(final World world, final LightType type, final BlockPos pos) {
        return resolve(world, type, pos, true);
    }

    /**
     * Same values as {@link #resolve}, but leaves {@link RailDiag}'s rail-bake counters alone.
     *
     * <p>For callers that hit this once per frame per object rather than once per mesh rebuild -
     * fangsu hands its turnout OBJ a fresh light value every frame. Counting those would move
     * {@code calls} forever and make the five-second diagnostic line print for the whole session.
     */
    public static int resolveQuiet(final World world, final LightType type, final BlockPos pos) {
        return resolve(world, type, pos, false);
    }

    private static int resolve(final World world, final LightType type, final BlockPos pos,
                               final boolean diag) {
        final int original = world.getLightLevel(type, pos);
        if (type == LightType.BLOCK) {
            if (diag) {
                RailDiag.call();
            }
            final int effective = blockLight(pos, original);
            if (diag && effective > original) {
                RailDiag.hit();
            }
            return effective;
        }
        return skyLight(world, pos, original, diag);
    }

    /**
     * The value ANTE bakes into the low (block) lightmap slot: world block light raised to the
     * dynamic light - our train beams <em>and</em> every LambDynamicLights source (a held torch, a
     * placed lamp, other mods' entities).
     */
    private static int blockLight(final BlockPos pos, final int original) {
        try {
            final int dynamic = RailDynamicLight.level(pos);
            return dynamic > original ? dynamic : original;
        } catch (Throwable t) {
            // A broken light engine must not fail the rail bake.
            return original;
        }
    }

    /**
     * The value for the high (sky) lightmap slot. Normally the world value, untouched.
     *
     * <p>With the shader boost on, the effective block light is duplicated into the sky slot so a
     * pack that derives its ambient/direct term from the sky component still sees the rail as lit.
     * The sky value is only ever raised to what the block slot already carries, never above it, and
     * never on a rail we did not light - so unlit rails and the rest of the world stay untouched.
     */
    private static int skyLight(final World world, final BlockPos pos, final int original,
                                final boolean diag) {
        if (!shaderBoost()) {
            return original;
        }
        try {
            final int block = blockLight(pos, world.getLightLevel(LightType.BLOCK, pos));
            if (block <= original) {
                return original;
            }
            if (diag) {
                RailDiag.boost();
            }
            return block;
        } catch (Throwable t) {
            return original;
        }
    }

    /**
     * True when the sky half should mirror the block half: opt-in config, pack actually rendering.
     *
     * <p>The block half of the same joint is queried again here instead of being memoised between
     * the two calls. ANTE evaluates the block argument first, but a shared memo would have to be
     * thread-safe (the bake runs on the chunk workers) and a stale hit would bake a wrong sky value;
     * one extra lookup per rail joint on a path that is rebuilt at most a few times per second is
     * the cheaper trade.
     */
    private static boolean shaderBoost() {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            return cfg != null && cfg.railLightShaderBoost && ShaderPackProbe.active();
        } catch (Throwable t) {
            return false;
        }
    }
}
