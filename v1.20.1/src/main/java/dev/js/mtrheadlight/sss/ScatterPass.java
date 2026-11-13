package dev.js.mtrheadlight.sss;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.MtrHeadlightClient;
import dev.js.mtrheadlight.ShaderPackProbe;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * <b>Full-screen screen-space scattering for the train lamps</b> - the "light in the air" that a
 * billboard flare cannot give.
 *
 * <h2>Where the pass sits</h2>
 * {@code WorldRenderEvents.LAST}: every framebuffer write of the frame is finished (terrain,
 * entities, particles, weather, clouds) but the world has not been torn down, so the main
 * framebuffer is still bound and its depth attachment still describes the frame we are about to read.
 * The GUI and the hand come later, which is what makes this a scene-wide effect rather than an
 * overlay.
 *
 * <h2>The model</h2>
 * Each lamp is treated as a small glowing volume (a sphere of radius {@code scatterRadius}) that only
 * scatters the light it is aimed at, and the pass integrates the part of that volume the view ray
 * actually crosses, up to the first opaque surface:
 *
 * <pre>
 *   per pixel:   p(t) = originNear + t*rd,  t in [0, sceneDepth],  rd from inverse(proj*view)
 *   per lamp:    sphere |p - L| &lt; R  -&gt; chord [t0, t1] of the ray inside it, cut at sceneDepth
 *                tau  = (chord / R) * cone * window * phase * transmittance * intensity
 *   total:       colour = 1 - exp(-sum tau * gain)     (per channel, additive over the scene)
 * </pre>
 *
 * <ul>
 *   <li><b>chord</b>: how much of the ray is inside the lamp's volume. It peaks for a ray through the
 *       centre and goes to zero at the silhouette, so the glow has a soft, physically-shaped edge
 *       instead of a hard disc - and it is exactly the "how much air does this pixel look through"
 *       quantity, which is why the result reads as volume, not as a decal.</li>
 *   <li><b>cone</b>: the lamp's own heading gates the volume ({@code smoothstep} between an inner and
 *       an outer cosine), so a head light throws a wedge of fog and a lamp pointing away from the
 *       pixel's ray does not light it. Air right next to the lamp still glows (the halo).</li>
 *   <li><b>window</b>: fades the last part of the beam length, so the beam's end cannot pop.</li>
 *   <li><b>phase</b>: forward scattering (Henyey-Greenstein, normalised to 1 at g=0). Looking into a
 *       head light is much brighter than seeing it from the side - the cue that makes a light feel
 *       like it is shining <em>at you</em> through the air.</li>
 *   <li><b>transmittance</b>: {@code exp(-k * distance from the lamp)}, i.e. the medium between the
 *       lamp and the scattering point absorbs; combined with the final {@code 1 - exp(-sum)} this is
 *       the same two-sided exponential a single-scattering integral produces.</li>
 *   <li><b>falloff</b>: {@code 1 / (1 + d^2 / 9)} - the lamp lights the medium with ~1/d^2 irradiance,
 *       so the air at the lamp is far brighter than the air three blocks away. Without it the effect
 *       is a uniformly bright ball, which reads as a decal instead of fog.</li>
 *   <li><b>sceneDepth</b>: the ray stops at the first surface, so a wall, platform or car body cuts
 *       the glow exactly where it cuts the view - the reason this is worth doing on the GPU at all.</li>
 * </ul>
 *
 * <h2>Why the colour attachment is never sampled</h2>
 * The pass writes into the main framebuffer's colour attachment with additive blending, so it cannot
 * read that attachment (that would be a read/write feedback loop on the same texture) and therefore
 * cannot do a "scene-aware" roll-off. It does not need to: every channel saturates at 1 independently
 * through {@code 1 - exp(-x)}, which self-limits a bright lamp against a dark scene and cannot produce
 * the blown-out white block a plain {@code scene + light} would.
 *
 * <h2>Cost</h2>
 * One full-screen quad, one depth blit, and at most {@value ScatterLights#MAX} lamps in the fragment
 * loop. No texture fetch inside the lamp loop, no per-lamp draw. Zero lamps means the method returns
 * before touching GL - no depth copy, no framebuffer, no program bind. A shader pack (Iris/OptiFine)
 * owns the pipeline and the depth buffer, so the pass is skipped there with one log line.
 */
public final class ScatterPass {

    /** Longest view ray integrated, blocks. Also the cut for a sky pixel (depth == 1). */
    static final float MAX_RAY = 96.0F;
    /** Extinction of the medium between the lamp and the scattering point, per block. */
    private static final float EXTINCTION = 0.075F;
    /** Henyey-Greenstein anisotropy: forward-peaked, but not a laser. */
    private static final float PHASE_G = 0.55F;
    /** Inner / outer cosine of the lamp's scattering cone (~20 deg / ~57 deg). */
    private static final float COS_INNER = 0.94F;
    private static final float COS_OUTER = 0.55F;
    /** HG can spike hard head-on; cap it so a lamp staring at the camera does not bloom the screen. */
    private static final float PHASE_MAX = 4.0F;

    private static final long LOG_INTERVAL_MS = 5000L;

    private static final float[] LIGHT_POS = new float[ScatterLights.MAX * 4];
    private static final float[] LIGHT_DIR = new float[ScatterLights.MAX * 4];
    private static final float[] LIGHT_COLOR = new float[ScatterLights.MAX * 4];
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();

    private static boolean packWarned;
    private static boolean depthWarned;
    private static boolean startupLogged;
    private static String viewSource;
    private static long windowStart = System.currentTimeMillis();
    private static int frames;
    private static int lastLights;
    private static int maxLights;
    private static double msSum;
    private static int msCount;

    private ScatterPass() {
    }

    /**
     * Compiles the shader as early as possible (the client tick runs on the render thread, and the
     * title screen has a live GL context): a shader that does not compile is then visible in the log
     * before the player is in a world, and the cost is paid once.
     */
    public static void tick(final MinecraftClient client) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null || !cfg.enabled || !cfg.scatterEnabled) {
                return;
            }
            if (client == null || client.getWindow() == null) {
                return;
            }
            if (ScatterProgram.ensure(client) && !startupLogged) {
                startupLogged = true;
                // Same shape as the per-window line, so one grep over a log answers "did the shader
                // ever compile" even on a run that never entered a world (this is the evidence the
                // title-screen probe produces).
                MtrHeadlightClient.LOGGER.info("[lte] scatter: lights=0/{} program=ok depth=none "
                        + "frames=0 candidates=0 (shader compiled; the pass runs once a world with "
                        + "lit lamps is drawn)", Integer.valueOf(ScatterLights.MAX));
            }
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] scatter: shader probe failed", t);
        }
    }

    /** The pass itself. Never throws: a broken pass must not take the frame down. */
    public static void render(final WorldRenderContext context) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null || !cfg.enabled || !cfg.scatterEnabled) {
                return;
            }
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) {
                return;
            }
            if (shaderPackActive()) {
                if (!packWarned) {
                    packWarned = true;
                    MtrHeadlightClient.LOGGER.info(
                            "[lte] scatter: shader pack active - the screen-space scattering pass "
                                    + "stays out of the pipeline (the pack owns the framebuffer "
                                    + "and the depth buffer)");
                }
                return;
            }
            if (!ScatterProgram.ensure(client)) {
                return;
            }

            final int lights = ScatterLights.collect(client, cfg, LIGHT_POS, LIGHT_DIR, LIGHT_COLOR);
            if (lights == 0) {
                // Zero lamps: no depth copy, no framebuffer, nothing bound. This is the common case
                // on a map without trains and it has to be free.
                lastLights = 0;
                logIfDue(cfg);
                return;
            }

            final Framebuffer framebuffer = client.getFramebuffer();
            if (framebuffer == null) {
                return;
            }
            final long started = System.nanoTime();
            if (!DepthCopy.update(framebuffer)) {
                if (!depthWarned) {
                    depthWarned = true;
                    MtrHeadlightClient.LOGGER.warn("[lte] scatter: the scene depth could not be "
                            + "copied this frame - the pass stays off (it never draws against a "
                            + "stale or empty depth buffer)");
                }
                return;
            }

            /*
             * Depth reconstruction matrix: inverse(projection * view). Everything stays
             * camera-relative - the lamp positions are already relative to the camera - so no large
             * world coordinates ever reach a float in the shader.
             */
            final Matrix4f view = viewMatrix(context, client);
            VIEW_PROJ.set(RenderSystem.getProjectionMatrix()).mul(view);
            INVERSE.set(VIEW_PROJ).invert();

            ScatterProgram.uniforms(INVERSE, gain(cfg), EXTINCTION, MAX_RAY, PHASE_G,
                    COS_INNER, COS_OUTER, lights, LIGHT_POS, LIGHT_DIR, LIGHT_COLOR);

            draw();
            lastLights = lights;
            maxLights = Math.max(maxLights, lights);
            frames++;
            msSum += (System.nanoTime() - started) / 1.0E6D;
            msCount++;
            logIfDue(cfg);
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.error("[lte] scatter: pass failed", t);
        }
    }

    /** One full-screen quad in NDC. The shader builds its own rays; no geometry is involved. */
    private static void draw() {
        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        buffer.vertex(-1.0F, -1.0F, 0.0F).next();
        buffer.vertex(-1.0F, 1.0F, 0.0F).next();
        buffer.vertex(1.0F, 1.0F, 0.0F).next();
        buffer.vertex(1.0F, -1.0F, 0.0F).next();
        final BufferBuilder.BuiltBuffer built = buffer.endNullable();
        if (built == null) {
            return;
        }

        RenderSystem.setShader(ScatterProgram::program);
        RenderSystem.setShaderTexture(0, DepthCopy.texture());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        // The glow is already a function of the scene depth, so the depth buffer itself must neither
        // reject the quad nor be written by it.
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            BufferRenderer.drawWithGlobalProgram(built);
        } finally {
            /*
             * Restore every piece of global state we touched. Leaving our program bound, our depth
             * texture on unit 0, or the depth mask off is exactly what made particles disappear in
             * the earlier beam-cone work: the next thing drawn (particles, weather, the hand) keeps
             * our sampler and our depth rules and silently renders nothing.
             */
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            RenderSystem.setShaderTexture(0, 0);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** Scatter strength, clamped, so a hand-edited json cannot turn the screen into a white card. */
    private static float gain(final HeadlightConfig cfg) {
        final double strength = cfg.scatterStrengthClamped();
        return (float) (strength * 1.6D);
    }

    /**
     * The view matrix the depth buffer was produced with.
     *
     * <p>Three sources, most authoritative first: the context's matrix stack (Fabric documents it as
     * camera-transformed at {@code LAST}), {@code RenderSystem.getModelViewMatrix()} (what vanilla
     * applied when it drew the world), and a matrix rebuilt from the camera's pitch/yaw.
     *
     * <p>Each candidate is checked for being <b>rigid</b> - rotation plus translation, no scale,
     * bottom row {@code (0,0,0,1)}. A degenerate candidate (an identity left behind by a mod that
     * reset the stack, a matrix with the camera applied twice) is skipped, because a wrong view
     * matrix never fails loudly: it silently reconstructs the frame in the wrong place and the glow
     * misses the lamp. The fallback is a plain Rx(pitch) * Ry(yaw + 180), which is what vanilla
     * builds; it differs from the real matrix only by view bobbing (a few centimetres).
     */
    private static Matrix4f viewMatrix(final WorldRenderContext context,
                                       final MinecraftClient client) {
        Matrix4f candidate = null;
        try {
            if (context != null && context.matrixStack() != null) {
                candidate = context.matrixStack().peek().getPositionMatrix();
            }
        } catch (Throwable ignored) {
            candidate = null;
        }
        if (rigid(candidate)) {
            noteViewSource("context");
            return candidate;
        }
        final Matrix4f renderSystem = RenderSystem.getModelViewMatrix();
        if (rigid(renderSystem)) {
            noteViewSource("renderSystem");
            return renderSystem;
        }
        noteViewSource("camera");
        final net.minecraft.client.render.Camera camera = client.gameRenderer.getCamera();
        return new Matrix4f()
                .rotateX((float) Math.toRadians(camera.getPitch()))
                .rotateY((float) Math.toRadians(camera.getYaw() + 180.0F));
    }

    /** Rotation + translation only: |det(R)| = 1, bottom row (0,0,0,1). */
    private static boolean rigid(final Matrix4f m) {
        if (m == null) {
            return false;
        }
        if (Math.abs(m.m30()) > 1.0E-3F || Math.abs(m.m31()) > 1.0E-3F
                || Math.abs(m.m32()) > 1.0E-3F || Math.abs(m.m33() - 1.0F) > 1.0E-3F) {
            return false;
        }
        final float det = m.m00() * (m.m11() * m.m22() - m.m12() * m.m21())
                - m.m01() * (m.m10() * m.m22() - m.m12() * m.m20())
                + m.m02() * (m.m10() * m.m21() - m.m11() * m.m20());
        return Math.abs(Math.abs(det) - 1.0F) < 0.05F;
    }

    /** One line per session: which source the reconstruction matrix came from. */
    private static void noteViewSource(final String source) {
        if (viewSource != null) {
            return;
        }
        viewSource = source;
        MtrHeadlightClient.LOGGER.info("[lte] scatter: depth reconstruction uses "
                + "inverse(ProjMat * {}-derived view)", source);
    }

    private static boolean shaderPackActive() {
        try {
            return ShaderPackProbe.active();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code [lte] scatter: lights=… program=ok …} every 5 s. {@code lights=0} is a real answer (the
     * pass is alive and the world has nothing lit); a silent log would be indistinguishable from a
     * pass that never runs.
     */
    private static void logIfDue(final HeadlightConfig cfg) {
        final long now = System.currentTimeMillis();
        if (now - windowStart < LOG_INTERVAL_MS) {
            return;
        }
        final double avg = msCount == 0 ? 0.0D : msSum / msCount;
        MtrHeadlightClient.LOGGER.info(String.format(Locale.ROOT,
                "[lte] scatter: lights=%d/%d program=%s candidates=%d frames=%d maxLights=%d "
                        + "avgMs=%.2f strength=%.2f radius=%.1f",
                Integer.valueOf(lastLights), Integer.valueOf(ScatterLights.MAX),
                ScatterProgram.status(), Integer.valueOf(ScatterLights.candidates()),
                Integer.valueOf(frames), Integer.valueOf(maxLights), Double.valueOf(avg),
                Double.valueOf(cfg.scatterStrengthClamped()),
                Double.valueOf(cfg.scatterRadiusClamped())));
        windowStart = now;
        frames = 0;
        maxLights = 0;
        msSum = 0.0D;
        msCount = 0;
    }

    /** Status for {@code /mtr_lte scatter status}. */
    public static String status(final HeadlightConfig cfg) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("scatter ").append(cfg.scatterEnabled ? "on" : "off");
        sb.append(" program=").append(ScatterProgram.status());
        sb.append(" strength=").append(String.format(Locale.ROOT, "%.2f",
                Double.valueOf(cfg.scatterStrengthClamped())));
        sb.append(" radius=").append(String.format(Locale.ROOT, "%.1f",
                Double.valueOf(cfg.scatterRadiusClamped())));
        sb.append(" lights=").append(lastLights).append('/').append(ScatterLights.MAX);
        sb.append(" depth=").append(DepthCopy.texture() == -1 ? "none"
                : DepthCopy.width() + "x" + DepthCopy.height());
        if (ScatterProgram.failure() != null) {
            sb.append(" failure=").append(ScatterProgram.failure());
        }
        if (shaderPackActive()) {
            sb.append(" (shader pack active: pass skipped)");
        }
        return sb.toString();
    }

    /** Releases the program and the depth target; called when the client stops. */
    public static void shutdown() {
        DepthCopy.release();
        ScatterProgram.close();
    }
}
