package dev.js.mtrheadlight.clientgui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/**
 * 1.19.4 stand-in for 1.20.1's {@code net.minecraft.client.gui.DrawContext}.
 *
 * <p>The 1.20.1 editor screens were written against that class; every call they make has an exact
 * 1.19.4 twin on {@code DrawableHelper}/{@code RenderSystem} that takes an explicit
 * {@code MatrixStack}. So instead of rewriting 8 000 lines of screen code, this class keeps the same
 * shape and forwards to those twins with the stack captured at construction. The screens only need
 * two mechanical edits: the import line, and the {@code render} override signature
 * ({@code DrawContext context} -&gt; {@code MatrixStack matrices} + one {@code new DrawContext(matrices)}).
 *
 * <p>Only the surface the screens actually use is implemented — fill / gradient / texture / text /
 * scissor.
 */
public final class DrawContext {
    private final MatrixStack matrices;

    public DrawContext(final MatrixStack matrices) {
        this.matrices = matrices;
    }

    public MatrixStack getMatrices() {
        return matrices;
    }

    // ---------------------------------------------------------------- rect fills

    public void fill(final int x1, final int y1, final int x2, final int y2, final int color) {
        DrawableHelper.fill(matrices, x1, y1, x2, y2, color);
    }

    /**
     * Vertical gradient. 1.19.4's {@code DrawableHelper.fillGradient} is {@code protected static},
     * so it is not reachable from here — the quad is emitted directly as position-colour.
     */
    public void fillGradient(final int x1, final int y1, final int x2, final int y2,
                             final int topColor, final int bottomColor) {
        final float a1 = (topColor >> 24 & 0xFF) / 255.0F;
        final float r1 = (topColor >> 16 & 0xFF) / 255.0F;
        final float g1 = (topColor >> 8 & 0xFF) / 255.0F;
        final float b1 = (topColor & 0xFF) / 255.0F;
        final float a2 = (bottomColor >> 24 & 0xFF) / 255.0F;
        final float r2 = (bottomColor >> 16 & 0xFF) / 255.0F;
        final float g2 = (bottomColor >> 8 & 0xFF) / 255.0F;
        final float b2 = (bottomColor & 0xFF) / 255.0F;
        final int xa = Math.min(x1, x2);
        final int xb = Math.max(x1, x2);
        final int ya = Math.min(y1, y2);
        final int yb = Math.max(y1, y2);
        final Matrix4f m = matrices.peek().getPositionMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        final BufferBuilder buf = Tessellator.getInstance().getBuffer();
        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buf.vertex(m, xa, yb, 0.0F).color(r2, g2, b2, a2).next();
        buf.vertex(m, xb, yb, 0.0F).color(r2, g2, b2, a2).next();
        buf.vertex(m, xb, ya, 0.0F).color(r1, g1, b1, a1).next();
        buf.vertex(m, xa, ya, 0.0F).color(r1, g1, b1, a1).next();
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.disableBlend();
    }

    // ---------------------------------------------------------------- textures

    /** {@code drawTexture(Identifier, x, y, u, v, width, height)} — 7 args, sheet assumed 256². */
    public void drawTexture(final Identifier texture, final int x, final int y, final int u,
                            final int v, final int width, final int height) {
        drawTexturedQuad(texture, x, y, width, height, u, v, width, height, 256, 256);
    }

    /** {@code drawTexture(Identifier, x, y, u, v, width, height, textureWidth, textureHeight)}. */
    public void drawTexture(final Identifier texture, final int x, final int y, final int u,
                            final int v, final int width, final int height,
                            final int textureWidth, final int textureHeight) {
        drawTexturedQuad(texture, x, y, width, height, u, v, width, height, textureWidth,
                textureHeight);
    }

    /** {@code drawTexture(Identifier, x, y, width, height, u, v, regionWidth, regionHeight, texW, texH)}. */
    public void drawTexture(final Identifier texture, final int x, final int y, final int width,
                            final int height, final float u, final float v,
                            final int regionWidth, final int regionHeight,
                            final int textureWidth, final int textureHeight) {
        drawTexturedQuad(texture, x, y, width, height, u, v, regionWidth, regionHeight,
                textureWidth, textureHeight);
    }

    /**
     * 1.19.4 has no {@code DrawableHelper.drawTexture(..., Identifier, ...)} overload — the texture
     * is bound to unit 0 first. The source region is <b>tiled</b> across the destination in
     * {@code regionWidth x regionHeight} steps, the way the 1.20.1 call it replaces behaves (all the
     * call sites are the repeating options background), rather than stretched.
     */
    private void drawTexturedQuad(final Identifier texture, final int x, final int y,
                                  final int width, final int height, final float u, final float v,
                                  final int regionWidth, final int regionHeight,
                                  final int textureWidth, final int textureHeight) {
        if (width <= 0 || height <= 0) {
            return;
        }
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        final Matrix4f m = matrices.peek().getPositionMatrix();
        final BufferBuilder buf = Tessellator.getInstance().getBuffer();
        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        final int stepX = regionWidth <= 0 ? width : regionWidth;
        final int stepY = regionHeight <= 0 ? height : regionHeight;
        for (int dy = 0; dy < height; dy += stepY) {
            final int th = Math.min(stepY, height - dy);
            for (int dx = 0; dx < width; dx += stepX) {
                final int tw = Math.min(stepX, width - dx);
                final float u1 = u / textureWidth;
                final float u2 = (u + tw) / textureWidth;
                final float v1 = v / textureHeight;
                final float v2 = (v + th) / textureHeight;
                final float x1 = x + dx;
                final float x2 = x1 + tw;
                final float y1 = y + dy;
                final float y2 = y1 + th;
                buf.vertex(m, x1, y2, 0.0F).texture(u1, v2).color(255, 255, 255, 255).next();
                buf.vertex(m, x2, y2, 0.0F).texture(u2, v2).color(255, 255, 255, 255).next();
                buf.vertex(m, x2, y1, 0.0F).texture(u2, v1).color(255, 255, 255, 255).next();
                buf.vertex(m, x1, y1, 0.0F).texture(u1, v1).color(255, 255, 255, 255).next();
            }
        }
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.disableBlend();
    }

    // ---------------------------------------------------------------- text

    public void drawTextWithShadow(final TextRenderer renderer, final Text text,
                                   final int x, final int y, final int color) {
        DrawableHelper.drawTextWithShadow(matrices, renderer, text, x, y, color);
    }

    public void drawTextWithShadow(final TextRenderer renderer, final String text,
                                   final int x, final int y, final int color) {
        DrawableHelper.drawTextWithShadow(matrices, renderer, text, x, y, color);
    }

    public void drawTextWithShadow(final TextRenderer renderer, final OrderedText text,
                                   final int x, final int y, final int color) {
        DrawableHelper.drawTextWithShadow(matrices, renderer, text, x, y, color);
    }

    public void drawCenteredTextWithShadow(final TextRenderer renderer, final Text text,
                                           final int centerX, final int y, final int color) {
        DrawableHelper.drawCenteredTextWithShadow(matrices, renderer, text, centerX, y, color);
    }

    public void drawCenteredTextWithShadow(final TextRenderer renderer, final String text,
                                           final int centerX, final int y, final int color) {
        DrawableHelper.drawCenteredTextWithShadow(matrices, renderer, text, centerX, y, color);
    }

    public void drawCenteredTextWithShadow(final TextRenderer renderer, final OrderedText text,
                                           final int centerX, final int y, final int color) {
        DrawableHelper.drawCenteredTextWithShadow(matrices, renderer, text, centerX, y, color);
    }

    // ---------------------------------------------------------------- scissor

    /** {@code DrawableHelper.enableScissor} is already public static on 1.19.4 and scales itself. */
    public void enableScissor(final int x1, final int y1, final int x2, final int y2) {
        DrawableHelper.enableScissor(x1, y1, x2, y2);
    }

    public void disableScissor() {
        DrawableHelper.disableScissor();
    }
}
