package dev.js.mtrheadlight.sss;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.js.mtrheadlight.MtrHeadlightClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * <b>A private copy of the scene depth.</b>
 *
 * <p>The pass draws into the main framebuffer while it samples depth. Sampling the framebuffer's own
 * depth attachment there is a feedback loop (the texture is attached to the target being written),
 * so the depth is blitted once per frame into a texture this class owns. Only the <b>depth</b>
 * attachment is copied - the colour attachment is never read, copied or sampled by anything here,
 * which is the "no read/write of the same attachment" rule this pass is built around.
 *
 * <h2>Format</h2>
 * The destination format is queried from the source texture
 * ({@code glGetTexLevelParameteri(GL_TEXTURE_INTERNAL_FORMAT)}), never assumed: a depth blit between
 * mismatched formats is rejected by the driver (and the old code measured
 * {@code GL_DEPTH_COMPONENT16 = 0x1902} on this machine, not the 24-bit format one would guess).
 * Filters are set explicitly - a depth texture with the default mipmap min filter is <em>incomplete</em>
 * and samples as {@code 0.0}, which would read as "the whole world is one block in front of the
 * camera".
 *
 * <p>Everything is allocated lazily: {@link #follow} is only called from a frame that actually has
 * lamps, so an empty track creates no texture and no framebuffer.
 */
final class DepthCopy {

    private static int texture = -1;
    private static int fbo = -1;
    private static int width = -1;
    private static int height = -1;
    private static int sourceTexture = -1;

    private DepthCopy() {
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
     * Makes sure the copy target matches the framebuffer, then blits this frame's depth into it.
     *
     * @return {@code false} when the copy failed - the caller must then draw nothing this frame
     */
    static boolean update(final Framebuffer source) {
        if (source == null) {
            return false;
        }
        if (!ensureTargets(source)) {
            return false;
        }
        return blit(source);
    }

    /** (Re)creates the texture + framebuffer when the window, the framebuffer or its depth changed. */
    private static boolean ensureTargets(final Framebuffer source) {
        final int src = source.getDepthAttachment();
        if (texture != -1 && width == source.textureWidth && height == source.textureHeight
                && sourceTexture == src) {
            return true;
        }
        release();
        try {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(src);
            final int internal = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                    GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (internal == 0) {
                return false;
            }
            final boolean packed = internal == GL30.GL_DEPTH24_STENCIL8
                    || internal == GL30.GL_DEPTH32F_STENCIL8;
            final int format = packed ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
            final int type = internal == GL30.GL_DEPTH32F_STENCIL8
                    ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                    : (packed ? GL30.GL_UNSIGNED_INT_24_8 : GL11.GL_FLOAT);

            final int tex = newDepthTexture(source.textureWidth, source.textureHeight,
                    internal, format, type);
            if (tex == -1) {
                return false;
            }
            final int target = newDepthFbo(tex);
            if (target == -1) {
                GlStateManager._deleteTexture(tex);
                return false;
            }
            texture = tex;
            fbo = target;
            width = source.textureWidth;
            height = source.textureHeight;
            sourceTexture = src;
            MtrHeadlightClient.LOGGER.info("[lte] scatter: depth copy {}x{} internal=0x{} fbo={}",
                    Integer.valueOf(width), Integer.valueOf(height),
                    Integer.toHexString(internal), Integer.valueOf(fbo));
            return true;
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] scatter: depth target failed", t);
            release();
            return false;
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, source.fbo);
        }
    }

    private static int newDepthTexture(final int w, final int h, final int internal,
                                       final int format, final int type) {
        final int[] ids = new int[1];
        GlStateManager._genTextures(ids);
        GlStateManager._bindTexture(ids[0]);
        drainErrors();
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            GlStateManager._deleteTexture(ids[0]);
            return -1;
        }
        return ids[0];
    }

    private static int newDepthFbo(final int tex) {
        final int id = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, tex, 0);
        // Depth only: without these two the framebuffer is incomplete and the blit is rejected.
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
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
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GlStateManager._glBlitFrameBuffer(0, 0, source.textureWidth, source.textureHeight,
                    0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            final int error = GL11.glGetError();
            return error == GL11.GL_NO_ERROR;
        } catch (Throwable t) {
            return false;
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, source.fbo);
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
        } catch (Throwable ignored) {
            // Lost context: nothing to release.
        }
        fbo = -1;
        texture = -1;
        width = -1;
        height = -1;
        sourceTexture = -1;
    }
}
