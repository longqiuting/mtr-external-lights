package dev.js.mtrheadlight.sss;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.js.mtrheadlight.MtrHeadlightClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * <b>A private copy of the scene colour.</b>
 *
 * <p>The surface term of the scattering pass has to multiply the light it adds by the <em>albedo of
 * what is already on screen</em> - that is the whole difference between "a glow floating in front of
 * the ground" and "the ground is lit". Reading the main framebuffer's colour attachment while
 * drawing into it is a feedback loop, so the colour is blitted once per frame into a texture this
 * class owns, exactly like {@link DepthCopy} does for depth. Two different attachments, two
 * different textures, neither read and written in the same draw - the same "no read/write of the
 * same attachment" rule the depth copy follows.
 *
 * <h2>Format</h2>
 * Fixed {@code GL_RGBA8}: the main attachment is RGBA8 on 1.20.1, so the copy is byte-for-byte what
 * vanilla put there and {@code scene + light} starts from the exact pixel the player would have seen
 * with the pass off. Filters are {@code NEAREST}/{@code CLAMP} for the same reason as the depth
 * copy: the shader samples the copy at the same window position it writes, so any filtering would
 * blur the albedo away from the geometry it belongs to.
 *
 * <h2>The coverage probe</h2>
 * {@code /mtr_lte scatter status} wants to know how many pixels the surface term actually touched.
 * The shader writes that as the output alpha while the probe is armed; this class then blits the
 * frame down to {@value #PROBE_W}x{@value #PROBE_H} with {@code GL_LINEAR} - which averages alpha
 * over each block - and reads back 20 KB instead of a full-resolution 8 MB frame. The sum of the
 * averaged alphas times the block area is the lit-pixel estimate. It runs <b>only</b> on the frame
 * after the command asked for it; no per-frame cost, no readback in the normal path.
 *
 * <p>Everything is allocated lazily from a frame that has lamps, so an empty world creates nothing.
 */
final class ColorCopy {

    /** Downsample target of the coverage probe: 96x54 is 20 KB of readback. */
    static final int PROBE_W = 96;
    static final int PROBE_H = 54;

    private static int texture = -1;
    private static int fbo = -1;
    private static int width = -1;
    private static int height = -1;
    private static int sourceTexture = -1;
    private static boolean failed;

    private static int probeTexture = -1;
    private static int probeFbo = -1;

    private ColorCopy() {
    }

    static int texture() {
        return texture;
    }

    static int width() {
        return width;
    }

    static int height() {
        return height;
    }

    /**
     * Makes sure the copy target matches the framebuffer, then blits this frame's colour into it.
     *
     * @return {@code false} when the copy failed - with the volume term gone there is nothing left
     *         that can be drawn without an albedo, so the caller must skip the pass this frame
     */
    static boolean update(final Framebuffer source) {
        if (source == null || failed) {
            return false;
        }
        if (!ensureTargets(source)) {
            failed = true;
            MtrHeadlightClient.LOGGER.warn("[lte] scatter: the scene colour could not be copied - "
                    + "the pass stays off this session (there is no albedo to light, and the volume "
                    + "term no longer exists)");
            return false;
        }
        return blit(source);
    }

    private static boolean ensureTargets(final Framebuffer source) {
        final int src = source.getColorAttachment();
        if (src == 0) {
            // No colour texture (some drivers hand out a renderbuffer): nothing to blit from.
            return false;
        }
        if (texture != -1 && width == source.textureWidth && height == source.textureHeight
                && sourceTexture == src) {
            return true;
        }
        release();
        try {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            final int tex = newColorTexture(source.textureWidth, source.textureHeight);
            if (tex == -1) {
                return false;
            }
            final int target = newColorFbo(tex);
            if (target == -1) {
                GlStateManager._deleteTexture(tex);
                return false;
            }
            texture = tex;
            fbo = target;
            width = source.textureWidth;
            height = source.textureHeight;
            sourceTexture = src;
            MtrHeadlightClient.LOGGER.info("[lte] scatter: colour copy {}x{} RGBA8 fbo={}",
                    Integer.valueOf(width), Integer.valueOf(height), Integer.valueOf(fbo));
            return true;
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] scatter: colour target failed", t);
            release();
            return false;
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, source.fbo);
        }
    }

    private static int newColorTexture(final int w, final int h) {
        final int[] ids = new int[1];
        GlStateManager._genTextures(ids);
        GlStateManager._bindTexture(ids[0]);
        drainErrors();
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            GlStateManager._deleteTexture(ids[0]);
            return -1;
        }
        return ids[0];
    }

    private static int newColorFbo(final int tex) {
        final int id = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, tex, 0);
        if (GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GlStateManager._glDeleteFramebuffers(id);
            return -1;
        }
        return id;
    }

    private static boolean blit(final Framebuffer source) {
        try {
            drainErrors();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.fbo);
            // The main framebuffer's read buffer is COLOR_ATTACHMENT0 in practice; set it so a
            // previous depth-only pass that asked for READ_BUFFER_NONE cannot break our blit.
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GlStateManager._glBlitFrameBuffer(0, 0, source.textureWidth, source.textureHeight,
                    0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            return GL11.glGetError() == GL11.GL_NO_ERROR;
        } catch (Throwable t) {
            return false;
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, source.fbo);
        }
    }

    /**
     * Counts the pixels whose output alpha says "the surface term contributed here".
     *
     * <p>Only call this on the frame right after the shader ran with the coverage probe armed: it
     * reads the alpha channel of the main colour attachment, which is only meaningful on that frame.
     *
     * @return the estimate, or {@code -1} when the readback is unavailable
     */
    static int probeLitPixels(final Framebuffer source) {
        if (source == null || failed || !ensureProbe()) {
            return -1;
        }
        ByteBuffer pixels = null;
        try {
            drainErrors();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.fbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, probeFbo);
            // GL_LINEAR averages the block, so the alpha of a probe texel is the lit *fraction*.
            GlStateManager._glBlitFrameBuffer(0, 0, source.textureWidth, source.textureHeight,
                    0, 0, PROBE_W, PROBE_H, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                return -1;
            }
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, probeFbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            pixels = MemoryUtil.memAlloc(PROBE_W * PROBE_H * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, PROBE_W, PROBE_H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                return -1;
            }
            long lit = 0L;
            for (int i = 3; i < PROBE_W * PROBE_H * 4; i += 4) {
                lit += pixels.get(i) & 0xFF;
            }
            final double perTexel = (double) source.textureWidth * source.textureHeight
                    / (double) (PROBE_W * PROBE_H);
            final double estimate = lit / 255.0D * perTexel;
            return (int) Math.round(estimate);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
            }
            try {
                // PACK_ALIGNMENT is global state: leave it exactly as we found it.
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, source.fbo);
            } catch (Throwable ignored) {
                // Lost context: nothing to restore.
            }
        }
    }

    private static boolean ensureProbe() {
        if (probeFbo != -1) {
            return true;
        }
        try {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            final int tex = newColorTexture(PROBE_W, PROBE_H);
            if (tex == -1) {
                return false;
            }
            final int target = newColorFbo(tex);
            if (target == -1) {
                GlStateManager._deleteTexture(tex);
                return false;
            }
            probeTexture = tex;
            probeFbo = target;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Clears any pending GL error so a check right after our own call cannot see someone else's. */
    private static void drainErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // drain
        }
    }

    static void release() {
        try {
            if (fbo != -1) {
                GlStateManager._glDeleteFramebuffers(fbo);
            }
            if (texture != -1) {
                GlStateManager._deleteTexture(texture);
            }
            if (probeFbo != -1) {
                GlStateManager._glDeleteFramebuffers(probeFbo);
            }
            if (probeTexture != -1) {
                GlStateManager._deleteTexture(probeTexture);
            }
        } catch (Throwable ignored) {
            // Lost context: nothing to release.
        }
        fbo = -1;
        texture = -1;
        width = -1;
        height = -1;
        sourceTexture = -1;
        probeFbo = -1;
        probeTexture = -1;
        failed = false;
    }
}
