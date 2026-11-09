package dev.js.mtrheadlight;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Locale;

/**
 * 1.20.1 depth-aware beam cone: the geometry is only a coverage proxy, the light is a
 * per-fragment ray-march through the cone that tests every sample against a copy of the
 * scene's depth buffer.
 *
 * <h2>How the depth gets here</h2>
 * The main framebuffer's depth attachment ({@link Framebuffer#getDepthAttachment()}) is
 * blitted once per frame into a texture this class owns
 * ({@link GlStateManager#_glBlitFrameBuffer}, {@code GL_DEPTH_BUFFER_BIT}, same internal
 * format - queried, not assumed). The copy matters: the source texture is still attached to
 * the framebuffer we are drawing into, and sampling it there is a feedback loop. The copy is
 * skipped entirely when no beam is in range, so an empty track costs nothing.
 *
 * <h2>Shader</h2>
 * {@code assets/mtrlte/shaders/core/lte_cone_volume.json} (+ {@code .vsh} / {@code .fsh}),
 * registered as the core shader {@code mtrlte:lte_cone_volume} through
 * {@link CoreShaderRegistrationCallback}. It is a <b>separate</b> program from
 * {@code GlowCompat}'s shared {@code position_color}: the shared one has no sampler and is
 * used by the beam sheets, which must not change.
 *
 * <p>Uniforms (all set per beam, {@code vertexColor} carries colour + alpha budget):
 * <ul>
 *   <li>{@code Sampler0} - the depth copy;</li>
 *   <li>{@code BeamApex} (vec4.xyz) - cone apex, camera-relative;</li>
 *   <li>{@code BeamAxis} (vec4.xyz) - unit axis;</li>
 *   <li>{@code BeamParams} (vec4) - x length, y tan(half angle), z steps, w density.</li>
 * </ul>
 *
 * <h2>Shaders pack / Iris</h2>
 * A pack owns the depth buffer and the shader pipeline, so this path is switched off whenever
 * {@link ShaderPackProbe#active()} is true: {@link #ready} then answers {@code false} and
 * {@link ProtoBeamCone} keeps drawing the legacy additive shells. That is the whole
 * degradation - no Iris interop, no crash surface. Every GL failure (blit rejected, shader
 * did not compile, uniform missing) also latches {@link #failed}, which is the same fallback.
 */
final class ConeVolumeRenderer {
    /** Registered core shader id; the json/vsh/fsh live under {@code assets/mtrlte/shaders/core/}. */
    private static final Identifier SHADER_ID = new Identifier("mtrlte", "lte_cone_volume");
    /** Longest beam distance from the camera that is drawn at all, blocks. */
    static final double MAX_DISTANCE = 64.0D;
    /** Hard cap on beams per frame (nearest first, probe included). */
    static final int MAX_BEAMS = 8;
    /** Proxy silhouette resolution - it only has to cover the cone's screen footprint. */
    private static final int SEGMENTS = 12;
    /** Resolution of the small depth copy used for the statistics line. */
    private static final int COARSE = 48;
    private static final float DENSITY = 0.6F;
    private static final int STRIDE = 7;
    private static final long LOG_INTERVAL_MS = 5000L;

    private static ShaderProgram program;
    private static boolean failed;
    private static boolean registered;
    private static String failure;

    private static int depthTex = -1;
    private static int depthFbo = -1;
    private static int coarseTex = -1;
    private static int coarseFbo = -1;
    private static int texW = -1;
    private static int texH = -1;
    private static int srcDepthTex = -1;

    private static final float[] coarse = new float[COARSE * COARSE];
    private static final FloatBuffer coarseBuf = BufferUtils.createFloatBuffer(COARSE * COARSE);

    private static float[] verts = new float[0];
    private static int vertCount;
    private static final Vector4f U_APEX = new Vector4f();
    private static final Vector4f U_AXIS = new Vector4f();
    private static final Vector4f U_PARAMS = new Vector4f();

    private static long windowStart = System.currentTimeMillis();
    private static int frames;
    private static int beamsDrawn;
    private static int occSamples;
    private static int allSamples;
    private static double msSum;
    private static int msCount;
    private static double msLast;
    private static int stepsSeen;
    private static int skipped;

    private ConeVolumeRenderer() {
    }

    /** Registers the core shader. Called once from {@link ProtoBeamCone#announce()}. */
    static void init() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            CoreShaderRegistrationCallback.EVENT.register(context -> {
                try {
                    context.register(SHADER_ID, VertexFormats.POSITION_COLOR,
                            loaded -> program = loaded);
                } catch (Throwable t) {
                    fail("register", t);
                }
            });
        } catch (Throwable t) {
            fail("callback", t);
        }
    }

    /**
     * True when the volume path is usable this frame: the shader loaded, no shader pack owns the
     * pipeline, the config allows occlusion testing and nothing failed earlier.
     */
    static boolean ready(final HeadlightConfig cfg) {
        if (failed || program == null) {
            return false;
        }
        // 渲染路径开关：false（默认）= 真实光束一律走壳层锥，raymarch 连深度图都不复制。
        if (cfg != null && !cfg.beamConeVolume) {
            return false;
        }
        // 全局关掉遮挡、但某条侧线把它打开时，深度图仍然要复制好 —— 逐车的遮挡判定在
        // ProtoBeamCone 里（关掉的那条侧线走壳层锥）。
        if (cfg != null && !cfg.beamConeOcclusion && !cfg.beamConeOcclusionAnySiding()) {
            return false;
        }
        try {
            return !ShaderPackProbe.active();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Why the volume path is not in use, for the log line. */
    static String status(final HeadlightConfig cfg) {
        if (cfg != null && !cfg.beamConeVolume) {
            // 默认：路径开关关着，和 GL 状态无关。不再往下报 shader-missing / shaderpack，
            // 否则会被读成"raymarch 坏了"，而事实是"没让它跑"。
            return "off(shell)";
        }
        if (failed) {
            return "failed(" + failure + ")";
        }
        if (program == null) {
            return "shader-missing";
        }
        if (cfg != null && !cfg.beamConeOcclusion && !cfg.beamConeOcclusionAnySiding()) {
            return "occlusion-off";
        }
        try {
            if (ShaderPackProbe.active()) {
                return "shaderpack";
            }
        } catch (Throwable t) {
            return "probe-error";
        }
        return "active";
    }

    private static void fail(final String where, final Throwable t) {
        failed = true;
        failure = where + ":" + t.getClass().getSimpleName();
        try {
            MtrHeadlightClient.LOGGER.warn("[lte] cone volume {} failed - falling back to the "
                    + "shell cone", where, t);
        } catch (Throwable ignored) {
            // logging must never break rendering
        }
    }

    private static void failGl(final String where) {
        failed = true;
        failure = where + ":gl" + GL11.glGetError();
        try {
            MtrHeadlightClient.LOGGER.warn("[lte] cone volume {} rejected by GL ({}) - falling "
                    + "back to the shell cone", where, failure);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Draws every beam of this frame. Returns false when it decided to do nothing (empty list or
     * a latched failure) - the caller then falls back to the shell path for this frame.
     */
    static boolean render(final List<ConeVolumeBeam> beams, final MatrixStack stack,
                          final HeadlightConfig cfg) {
        if (failed || program == null || beams == null || beams.isEmpty()) {
            return !failed;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getFramebuffer() == null) {
            return true;
        }
        // Nearest N win: the beam list arrives in train iteration order, so capping on the way in
        // would keep whichever trains happened to be enumerated first instead of the ones on
        // screen. Sorting here also drops anything that slipped past the distance gate.
        if (beams.size() > MAX_BEAMS) {
            final Vec3d camera = client.gameRenderer.getCamera().getPos();
            beams.sort((a, b) -> Double.compare(a.apex.squaredDistanceTo(camera),
                    b.apex.squaredDistanceTo(camera)));
            while (beams.size() > MAX_BEAMS) {
                beams.remove(beams.size() - 1);
            }
        }
        if (beams.isEmpty()) {
            return true;
        }
        final Framebuffer fb = client.getFramebuffer();
        final boolean statsFrame = System.currentTimeMillis() - windowStart >= LOG_INTERVAL_MS;
        final long t0 = System.nanoTime();

        ensureTargets(fb);
        if (failed) {
            return false;
        }
        copyDepth(fb);
        if (failed) {
            return false;
        }
        if (statsFrame) {
            captureCoarse(fb);
        }

        // 采样数是逐束的（侧线可以各自覆盖），诊断行报的是这一帧最后一束用到的值。
        if (statsFrame) {
            for (final ConeVolumeBeam beam : beams) {
                countOcclusion(beam);
            }
        }

        try {
            RenderSystem.setShader(() -> program);
            RenderSystem.setShaderTexture(0, depthTex);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            try {
                for (final ConeVolumeBeam beam : beams) {
                    drawBeam(beam, stack, beam.steps);
                    stepsSeen = beam.steps;
                    beamsDrawn++;
                }
            } finally {
                RenderSystem.depthMask(true);
                RenderSystem.defaultBlendFunc();
                RenderSystem.enableCull();
                // 关键：把全局 shader 与纹理单元还回去。不还的话，之后渲染的东西（粒子、
                // 天气、字幕）会继续用我们这个带深度采样的程序 / 绑在 0 号单元的深度纹理，
                // 表现就是"粒子生成了但一个也看不见"。
                RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);
                RenderSystem.setShaderTexture(0, 0);
            }
        } catch (Throwable t) {
            fail("draw", t);
            return false;
        }

        if (statsFrame) {
            // One explicit sync per log window: makes the reported ms include the GPU work
            // instead of only the CPU-side mesh build. Once every five seconds is invisible.
            try {
                GL11.glFinish();
            } catch (Throwable ignored) {
            }
        }
        msLast = (System.nanoTime() - t0) / 1.0E6D;
        msSum += msLast;
        msCount++;
        frames++;
        logIfDue();
        return true;
    }

    /**
     * Emits the statistics line when the window elapsed. Called from {@link #render} and from
     * {@link ProtoBeamCone}'s own five-second block, so the line still appears in frames where no
     * beam was in range (it then reports zeroes - silence would be indistinguishable from "the
     * feature is dead").
     */
    static void logIfDue() {
        if (System.currentTimeMillis() - windowStart < LOG_INTERVAL_MS) {
            return;
        }
        logWindow();
    }

    /** Statistics line - proof that the depth test ran and what it cost. */
    private static void logWindow() {
        final long now = System.currentTimeMillis();
        final double avg = msCount == 0 ? 0.0D : msSum / msCount;
        MtrHeadlightClient.LOGGER.info(String.format(Locale.ROOT,
                "[lte] cone: steps=%d occluded=%d/%d (%.0f%%) ms=%.2f avgMs=%.2f frames=%d beams=%d"
                        + " skipped=%d depthTex=%d",
                stepsSeen, occSamples, allSamples,
                allSamples == 0 ? 0.0D : 100.0D * occSamples / allSamples,
                msLast, avg, frames, beamsDrawn, skipped, depthTex));
        windowStart = now;
        frames = 0;
        beamsDrawn = 0;
        occSamples = 0;
        allSamples = 0;
        msSum = 0.0D;
        msCount = 0;
        skipped = 0;
    }

    // -----------------------------------------------------------------------------------------
    // depth copy
    // -----------------------------------------------------------------------------------------

    /** (Re)creates the depth / coarse targets when the window, the framebuffer or its depth texture changed. */
    private static void ensureTargets(final Framebuffer fb) {
        final int src = fb.getDepthAttachment();
        if (depthTex != -1 && texW == fb.textureWidth && texH == fb.textureHeight && srcDepthTex == src) {
            return;
        }
        release();
        try {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(src);
            final int internal = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                    GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (internal == 0) {
                failGl("format");
                return;
            }
            final boolean packed = internal == GL30.GL_DEPTH24_STENCIL8
                    || internal == GL30.GL_DEPTH32F_STENCIL8;
            final int format = packed ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
            final int type = internal == GL30.GL_DEPTH32F_STENCIL8
                    ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                    : (packed ? GL30.GL_UNSIGNED_INT_24_8 : GL11.GL_FLOAT);

            depthTex = newDepthTexture(fb.textureWidth, fb.textureHeight, internal, format, type);
            depthFbo = newDepthFbo(depthTex);
            coarseTex = newDepthTexture(COARSE, COARSE, internal, format, type);
            coarseFbo = newDepthFbo(coarseTex);
            if (depthTex == -1 || coarseTex == -1 || depthFbo == -1 || coarseFbo == -1) {
                failGl("targets");
                release();
                return;
            }
            texW = fb.textureWidth;
            texH = fb.textureHeight;
            srcDepthTex = src;
            MtrHeadlightClient.LOGGER.info(
                    "[lte] cone: depth copy {}x{} internal=0x{} fbo={}", texW, texH,
                    Integer.toHexString(internal), depthFbo);
        } catch (Throwable t) {
            fail("targets", t);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
        }
    }

    private static int newDepthTexture(final int w, final int h, final int internal,
                                       final int format, final int type) {
        final int[] ids = new int[1];
        GlStateManager._genTextures(ids);
        GlStateManager._bindTexture(ids[0]);
        drainErrors();
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null);
        // A depth texture with the default mipmap min filter is *incomplete* and samples as 0,
        // which would make everything look occluded - set the filters explicitly.
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            return -1;
        }
        return ids[0];
    }

    private static int newDepthFbo(final int texture) {
        if (texture == -1) {
            return -1;
        }
        final int fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, texture, 0);
        // Depth only: without these the framebuffer is incomplete and blits into it are rejected.
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        if (GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            return -1;
        }
        return fbo;
    }

    private static void copyDepth(final Framebuffer fb) {
        try {
            drainErrors();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFbo);
            GlStateManager._glBlitFrameBuffer(0, 0, fb.textureWidth, fb.textureHeight,
                    0, 0, fb.textureWidth, fb.textureHeight,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            final int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
                failGl("blit");
                return;
            }
        } catch (Throwable t) {
            fail("blit", t);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
        }
    }

    /** Small depth copy for the {@code occluded=} statistic, read back once per log window. */
    private static void captureCoarse(final Framebuffer fb) {
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, coarseFbo);
            GlStateManager._glBlitFrameBuffer(0, 0, fb.textureWidth, fb.textureHeight,
                    0, 0, COARSE, COARSE, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, coarseFbo);
            coarseBuf.clear();
            GL11.glReadPixels(0, 0, COARSE, COARSE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, coarseBuf);
            coarseBuf.rewind();
            coarseBuf.get(coarse);
        } catch (Throwable t) {
            fail("coarse", t);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
        }
    }

    /** Clears any pending GL error so a check right after our own call cannot see someone else's. */
    private static void drainErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // drain
        }
    }

    private static void release() {
        try {
            if (depthFbo != -1) {
                GlStateManager._glDeleteFramebuffers(depthFbo);
            }
            if (coarseFbo != -1) {
                GlStateManager._glDeleteFramebuffers(coarseFbo);
            }
            if (depthTex != -1) {
                GlStateManager._deleteTexture(depthTex);
            }
            if (coarseTex != -1) {
                GlStateManager._deleteTexture(coarseTex);
            }
        } catch (Throwable ignored) {
            // On a lost context there is nothing to release.
        }
        depthTex = -1;
        depthFbo = -1;
        coarseTex = -1;
        coarseFbo = -1;
        texW = -1;
        texH = -1;
        srcDepthTex = -1;
    }

    // -----------------------------------------------------------------------------------------
    // drawing
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the coverage proxy (cone side + base cap, {@link #SEGMENTS} steps) and issues one
     * draw for one beam. The proxy never carries the light itself - it only has to cover the
     * silhouette so that the fragment shader runs once per covered pixel.
     */
    private static void drawBeam(final ConeVolumeBeam beam, final MatrixStack stack, final int steps) {
        if (beam.length <= 0.1D || beam.radius <= 0.01D || beam.alpha <= 1.0E-4F) {
            return;
        }
        final Vec3d axis = beam.axis;
        Vec3d up = Math.abs(axis.y) > 0.99D ? new Vec3d(1.0D, 0.0D, 0.0D) : new Vec3d(0.0D, 1.0D, 0.0D);
        final Vec3d right = axis.crossProduct(up).normalize();
        up = right.crossProduct(axis).normalize();

        final Vec3d camera = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        final double ax = beam.apex.x - camera.x;
        final double ay = beam.apex.y - camera.y;
        final double az = beam.apex.z - camera.z;
        final double tipX = ax + axis.x * beam.length;
        final double tipY = ay + axis.y * beam.length;
        final double tipZ = az + axis.z * beam.length;

        final float red = ((beam.rgb >> 16) & 0xFF) / 255.0F;
        final float green = ((beam.rgb >> 8) & 0xFF) / 255.0F;
        final float blue = (beam.rgb & 0xFF) / 255.0F;
        final float alpha = beam.alpha;

        vertCount = 0;
        final double twoPi = Math.PI * 2.0D;
        final double r = beam.radius;
        for (int s = 0; s < SEGMENTS; s++) {
            final double a0 = twoPi * s / SEGMENTS;
            final double a1 = twoPi * (s + 1) / SEGMENTS;
            final double x0 = right.x * Math.cos(a0) * r + up.x * Math.sin(a0) * r;
            final double y0 = right.y * Math.cos(a0) * r + up.y * Math.sin(a0) * r;
            final double z0 = right.z * Math.cos(a0) * r + up.z * Math.sin(a0) * r;
            final double x1 = right.x * Math.cos(a1) * r + up.x * Math.sin(a1) * r;
            final double y1 = right.y * Math.cos(a1) * r + up.y * Math.sin(a1) * r;
            final double z1 = right.z * Math.cos(a1) * r + up.z * Math.sin(a1) * r;
            // side: apex -> ring
            put(ax, ay, az, red, green, blue, alpha);
            put(tipX + x0, tipY + y0, tipZ + z0, red, green, blue, alpha);
            put(tipX + x1, tipY + y1, tipZ + z1, red, green, blue, alpha);
            // base cap: closes the cone so a view into the open end still rasterises
            put(tipX, tipY, tipZ, red, green, blue, alpha);
            put(tipX + x1, tipY + y1, tipZ + z1, red, green, blue, alpha);
            put(tipX + x0, tipY + y0, tipZ + z0, red, green, blue, alpha);
        }
        if (vertCount == 0) {
            return;
        }

        final GlUniform apexU = program.getUniform("BeamApex");
        final GlUniform axisU = program.getUniform("BeamAxis");
        final GlUniform paramsU = program.getUniform("BeamParams");
        if (apexU == null || axisU == null || paramsU == null) {
            fail("uniform", new IllegalStateException("beam uniforms missing"));
            return;
        }
        apexU.set(U_APEX.set((float) ax, (float) ay, (float) az, 0.0F));
        axisU.set(U_AXIS.set((float) axis.x, (float) axis.y, (float) axis.z, 0.0F));
        paramsU.set(U_PARAMS.set((float) beam.length, (float) (r / beam.length),
                (float) steps, DENSITY));

        final Matrix4f matrix = stack.peek().getPositionMatrix();
        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        for (int i = 0; i + STRIDE - 1 < vertCount; i += STRIDE) {
            buffer.vertex(matrix, verts[i], verts[i + 1], verts[i + 2])
                    .color(verts[i + 3], verts[i + 4], verts[i + 5], verts[i + 6])
                    .next();
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static void put(final double x, final double y, final double z,
                            final float red, final float green, final float blue, final float alpha) {
        final int needed = vertCount + STRIDE;
        if (needed > verts.length) {
            final float[] grown = new float[Math.max(needed, verts.length * 2 + 256)];
            System.arraycopy(verts, 0, grown, 0, vertCount);
            verts = grown;
        }
        int i = vertCount;
        verts[i++] = (float) x;
        verts[i++] = (float) y;
        verts[i++] = (float) z;
        verts[i++] = red;
        verts[i++] = green;
        verts[i++] = blue;
        verts[i++] = alpha;
        vertCount = i;
    }

    // -----------------------------------------------------------------------------------------
    // statistics
    // -----------------------------------------------------------------------------------------

    /**
     * Counts, for one beam, how many samples along its axis the depth buffer says are behind
     * geometry. Uses the same projection and the same comparison as the shader, only against the
     * coarse copy - so the number in the log really is "the depth test found occluders", and not
     * a bookkeeping counter.
     */
    static void countOcclusion(final ConeVolumeBeam beam) {
        if (coarseTex == -1) {
            skipped++;
            return;
        }
        final Matrix4f mv = RenderSystem.getModelViewMatrix();
        final Matrix4f proj = RenderSystem.getProjectionMatrix();
        final float nearP = nearPlane(proj);
        final Vector4f v = new Vector4f();
        final Vec3d camera = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        for (int i = 0; i < 6; i++) {
            final double u = 0.12D + 0.15D * i;
            final Vec3d p = beam.apex.add(beam.axis.multiply(beam.length * u));
            v.set((float) (p.x - camera.x), (float) (p.y - camera.y), (float) (p.z - camera.z), 1.0F);
            mv.transform(v);
            proj.transform(v);
            if (v.w <= 0.0F) {
                continue;
            }
            final float nx = v.x / v.w;
            final float ny = v.y / v.w;
            final float pz = (v.z / v.w) * 0.5F + 0.5F;
            final int cx = MathHelper.floor((nx * 0.5F + 0.5F) * COARSE);
            final int cy = MathHelper.floor((ny * 0.5F + 0.5F) * COARSE);
            if (cx < 0 || cx >= COARSE || cy < 0 || cy >= COARSE) {
                continue;
            }
            final float scene = coarse[cy * COARSE + cx];
            final float t = (float) Math.max(0.5D, beam.length * u);
            final float bias = 0.75F * nearP / Math.max(t * t, 0.25F);
            allSamples++;
            if (scene < pz - bias) {
                occSamples++;
            }
        }
    }

    private static float nearPlane(final Matrix4f proj) {
        // JOML: m22 / m32 are the two entries of the standard perspective matrix, near = m32/(m22-1).
        final float a = proj.m22();
        final float b = proj.m32();
        if (a == 0.0F || !(b / (a - 1.0F) > 0.0F) || b / (a - 1.0F) > 1.0F) {
            return 0.05F;
        }
        return b / (a - 1.0F);
    }
}
