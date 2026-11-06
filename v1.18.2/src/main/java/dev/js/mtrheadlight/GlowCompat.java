package dev.js.mtrheadlight;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;

/**
 * 1.18.2 shim for {@link RailGlow}: the calls that differ between the supported versions.
 *
 * <ul>
 *   <li>position matrix: {@code net.minecraft.util.math.Matrix4f}, {@code peek().getPositionMatrix()},</li>
 *   <li>shader supplier: {@code GameRenderer::getPositionColorShader},</li>
 *   <li>draw call: {@code end()} is still void here, so the builder itself goes to
 *       {@code BufferRenderer.draw}.</li>
 * </ul>
 */
final class GlowCompat {
    private GlowCompat() {
    }

    /** Draws {@code count} floats (x y z r g b a per vertex, quad order) in camera-relative space. */
    static void draw(final float[] data, final int count, final MatrixStack stack) {
        final Matrix4f matrix = stack.peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i + 6 < count; i += 7) {
            buffer.vertex(matrix, data[i], data[i + 1], data[i + 2])
                    .color(data[i + 3], data[i + 4], data[i + 5], data[i + 6])
                    .next();
        }
        buffer.end();
        BufferRenderer.draw(buffer);

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
    }
}
