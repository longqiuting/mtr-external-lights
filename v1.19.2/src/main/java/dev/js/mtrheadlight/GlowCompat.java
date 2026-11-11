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
 * 1.19.4 shim for {@link RailGlow}: the three calls that differ between the supported versions -
 * the position matrix accessor, the position-colour shader supplier and the buffer draw call.
 */
final class GlowCompat {
    private GlowCompat() {
    }

    /**
     * Draws {@code count} floats (x y z r g b a per vertex, quad order) in camera-relative space.
     * The position matrix is taken here because its accessor differs between 1.18/1.19.2
     * ({@code peek().getModel()}) and 1.19.3+ ({@code peek().getPositionMatrix()}).
     */
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
        // 1.19.4: end() returns the built buffer. Do NOT use BufferRenderer.draw(BuiltBuffer) here:
        // in 1.19.4 that ends in VertexBuffer.draw() (no-arg), which only issues
        // RenderSystem.drawElements(...) - it never uploads ModelViewMat/ProjMat, so the quad is
        // transformed by whatever uniforms the position_color program still holds from its last
        // user (GUI/HUD). The sheet then lands off-screen with no exception and nothing is drawn.
        // drawWithGlobalProgram(...) is the 1.19.4 spelling of 1.19.2's drawWithShader(...): it
        // calls VertexBuffer.draw(RenderSystem.getModelViewMatrix(),
        // RenderSystem.getProjectionMatrix(), RenderSystem.getShader()).
        BufferRenderer.drawWithShader(buffer.end());

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
    }
}
