package dev.js.mtrheadlight;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

/**
 * Dynamic light for 方速 (fangsu)'s self-rendered turnout OBJ.
 *
 * <p>fangsu does not bake its turnout through ANTE: {@code TurnoutBlockEntityRenderer#render}
 * forwards the vanilla {@code light} int to {@code BaseBlockEntityRender#render}, which passes it to
 * {@code EyeCandyDrawCalls#commit}/{@code #renderDirect} and from there to the light of every vertex
 * in the model. Nothing in that chain calls {@code World#getLightLevel}, so the redirect that lights
 * up ANTE rails never runs for a switch. {@code FangsuTurnoutRenderMixin} rewrites that int on the
 * way into the base renderer and calls {@link #lift(Object, int)} to decide the new value.
 *
 * <p>The whole switch model carries a single light value (no per-vertex lighting), so one lookup at
 * the block position is all there is to do - and because fangsu re-emits its vertices every frame
 * ({@code renderDirect} writes into a fresh {@code BufferSource}, {@code commit} hands the light to
 * the draw scheduler per frame), the value is live: no relight / dirty-marking is needed, unlike the
 * ANTE rail bake.
 *
 * <p>Everything here is best-effort. The mixin carries {@code require = 0}, its config is
 * {@code required: false} behind a class probe, and this method swallows {@link Throwable} and
 * returns the value it was given, so a fangsu update that moves the injection point degrades to
 * "turnout stays on ambient light" instead of a crash.
 */
public final class FangsuTurnoutLight {
    private FangsuTurnoutLight() {
    }

    /**
     * Returns the packed lightmap fangsu should use for the block entity being rendered - the given
     * value with its block (and, for the shader boost, sky) component raised to the dynamic light.
     * Returns {@code packed} unchanged when nothing should change or anything goes wrong.
     *
     * @param blockEntity the block entity, taken from the render call's first argument (typed
     *                    {@code Object} so no {@code com.fangsu.*} type is needed to name it)
     * @param packed      the already packed {@code light} int fangsu is about to use
     */
    public static int lift(final Object blockEntity, final int packed) {
        try {
            if (!(blockEntity instanceof BlockEntity be)) {
                return packed;
            }
            if (!enabled()) {
                return packed;
            }
            final World world = be.getWorld();
            if (world == null) {
                return packed;
            }
            final BlockPos pos = be.getPos();
            if (pos == null) {
                return packed;
            }

            final int incomingBlock = packed & 0xFFFF;
            final int incomingSky = (packed >>> 16) & 0xFFFF;
            // Sanity gate: a packed lightmap is two 0-15 components. If fangsu ever hands us
            // something else at this position (an overlay, a colour), leave that value alone.
            if (incomingBlock > 15 || incomingSky > 15) {
                return packed;
            }

            // Same lookup the ANTE rail bake uses: our train beams plus every LambDynamicLights
            // source, maxed with the world value. The "quiet" variant keeps RailDiag's rail-bake
            // counters meaningful - this runs per frame per switch, not per mesh rebuild.
            final int block = Math.max(incomingBlock,
                    sanitize(RailLightInject.resolveQuiet(world, LightType.BLOCK, pos)));
            final int sky = Math.max(incomingSky,
                    sanitize(RailLightInject.resolveQuiet(world, LightType.SKY, pos)));
            if (block == incomingBlock && sky == incomingSky) {
                return packed;
            }
            return LightmapTextureManager.pack(block, sky);
        } catch (Throwable t) {
            return packed;
        }
    }

    /** Honours the existing 「轨道烘焙光照」 switch - the switch model belongs to the same feature. */
    private static boolean enabled() {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            return cfg != null && cfg.enabled && cfg.railLight;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int sanitize(final int level) {
        return MathHelper.clamp(level, 0, 15);
    }
}
