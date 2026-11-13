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
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * 1.20.1 shim for {@link RailGlow}: the three calls that differ between the supported versions -
 * the position matrix accessor, the position-colour shader supplier and the buffer draw call.
 *
 * <p><b>这是本版本唯一「怎么把顶点交给 GL」的地方</b>，三条可见几何全部走它：
 * <ul>
 *   <li>{@link #draw} —— 贴地光带（{@link BeamGlow}），{@code POSITION_COLOR}，无贴图；</li>
 *   <li>{@link #drawTextured} —— 灯位灯晕（{@link LightFlare}），{@code POSITION_TEXTURE_COLOR}；
 *       和上面<b>同一套</b> BufferBuilder → {@code BufferRenderer.drawWithGlobalProgram} →
 *       同一串 GL 状态与复位，区别只有 vertex format 和贴图，所以两者不可能「一条能画、一条不能」；</li>
 *   <li>{@link #drawScreenQuad} —— 屏幕空间（NDC）的纯色矩形，灯晕/散射的 lighttest 探针用。</li>
 * </ul>
 *
 * <h2>坐标空间（最容易错的地方）</h2>
 * 传进来的 {@code data} 必须是<b>相机相对</b>坐标（{@code world - camera.getPos()}）。世界渲染的
 * {@code MatrixStack} 只带相机<b>旋转</b>，不含平移：原版把相机位移烘在区块网格和实体位置上，而不是
 * 矩阵里。喂世界绝对坐标进去，几何会被放到离相机几百上千格的地方，直接被视锥裁掉 —— 顶点数照样在
 * 涨，屏幕上一个像素都没有。这一点在 {@code BeamGlow} 与 {@code LightFlare} 的类注释里各写了一遍，
 * 因为它是本项目里出现过两次的同一个 bug。
 */
final class GlowCompat {
    private GlowCompat() {
    }

    /**
     * Draws {@code count} floats (x y z r g b a per vertex, quad order) in camera-relative space.
     * The position matrix is taken here because its accessor and its owning package differ between
     * 1.18/1.19.2 ({@code com.mojang.math}) and 1.19.3+ ({@code org.joml}).
     */
    static void draw(final float[] data, final int count, final MatrixStack stack) {
        final Matrix4f matrix = stack.peek().getPositionMatrix();
        // Additive, depth-tested, no depth write: light on top of the rail, nothing occluded by it.
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
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
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        // 还回全局 shader 状态：调用方（以及之后渲染的粒子/天气）默认会继承它。
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
    }

    /**
     * {@link #draw} 的贴图版本：{@code count} 个 float，每顶点 9 个
     * ({@code x y z u v r g b a})，相机相对，四边形顺序。
     *
     * <p>除了 vertex format（{@code POSITION_TEXTURE_COLOR}）、程序
     * （{@code getPositionTexColorProgram}）和贴图绑定之外，其余每一行都和 {@link #draw} <b>逐字相同</b>：
     * 同一个共享 Tessellator、同一次 {@code drawWithGlobalProgram}、同一串 GL 状态与复位。这就是
     * 「灯晕改用光带同款渲染路径」的落地方式 —— 不是另写一条看起来差不多的路，而是把两条路合并成
     * 一个 shim 的两个 format 重载。
     *
     * <p>贴图槽 0 必须在 {@code drawWithGlobalProgram} <b>之前</b>设好：那颗 draw 会把
     * {@code Sampler0..11} 从 {@link RenderSystem#getShaderTexture(int)} 一起塞进程序。
     */
    static void drawTextured(final float[] data, final int count, final MatrixStack stack,
                             final Identifier texture) {
        drawTextured(data, count, stack, texture, false);
    }

    /**
     * {@link #drawTextured} 的深度覆盖版本。
     *
     * <p>{@code alwaysDepth = true} 时把深度<b>比较</b>函数换成 {@code GL_ALWAYS}（写完逐位还原成
     * {@code GL_LEQUAL}）：四边形仍然不写深度、仍然加色，但不再被车体/站台/隧道壁挡掉。
     *
     * <p>为什么灯晕需要它：灯晕挂在灯位上，而灯位在车体端面<b>里面</b>几个厘米（{@code TrainLamps}
     * 的 {@code FLARE_GAP} + 模型的 {@code inset}）。即使再往相机拉 2 格，斜视时这个四边形仍可能
     * 有一半埋在车头网格里 —— 于是「quads 一直在涨、屏幕上什么都没有」。加色四边形遮挡不了场景，
     * 穿过去最多是隔着墙看到一点光晕，比彻底看不见好得多。
     */
    static void drawTextured(final float[] data, final int count, final MatrixStack stack,
                             final Identifier texture, final boolean alwaysDepth) {
        final Matrix4f matrix = stack.peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        RenderSystem.enableDepthTest();
        if (alwaysDepth) {
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
        }
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        for (int i = 0; i + 8 < count; i += 9) {
            buffer.vertex(matrix, data[i], data[i + 1], data[i + 2])
                    .texture(data[i + 3], data[i + 4])
                    .color(data[i + 5], data[i + 6], data[i + 7], data[i + 8])
                    .next();
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        if (alwaysDepth) {
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
        }
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
    }

    /**
     * 屏幕空间（NDC）的纯色矩形，{@code -1..1} 两端，加色混合、不写深度、不测深度。
     *
     * <p>原版的 {@code position_color} 顶点着色器算的是 {@code ProjMat * ModelViewMat * Position}，
     * 所以「直接在屏幕上画一个矩形」需要把这两个矩阵临时换成单位矩阵 —— 只把顶点写成 NDC 是不够的
     * （世界投影矩阵会把它甩到相机后面）。两个矩阵都在退出时逐位还原，调用方拿回的是进来时那一份。
     *
     * <p>它存在的唯一目的是取证：{@code /mtr_lte lighttest} 用它证明
     * {@code WorldRenderEvents.LAST} 那一次真的能往主 framebuffer 写颜色，与我们自己的
     * {@code light_scatter} 程序和深度拷贝完全无关。
     */
    static void drawScreenQuad(final float x0, final float y0, final float x1, final float y1,
                               final float r, final float g, final float b, final float a) {
        // 这两个 getter 返回的就是 RenderSystem 内部那一份实例（VertexBuffer.draw 上传的也是它），
        // 所以直接改、退出时逐位写回 —— 1.20.1 没有「换个投影矩阵」的单参数 setter。
        final Matrix4f projection = RenderSystem.getProjectionMatrix();
        final Matrix4f modelView = RenderSystem.getModelViewMatrix();
        final Matrix4f savedProjection = new Matrix4f(projection);
        final Matrix4f savedModelView = new Matrix4f(modelView);
        try {
            projection.identity();
            modelView.identity();

            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
            // 这一层是「把颜色写上去」的证明，不该被任何深度值挡掉。
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            final Matrix4f identity = new Matrix4f();
            buffer.vertex(identity, x0, y0, 0.0F).color(r, g, b, a).next();
            buffer.vertex(identity, x0, y1, 0.0F).color(r, g, b, a).next();
            buffer.vertex(identity, x1, y1, 0.0F).color(r, g, b, a).next();
            buffer.vertex(identity, x1, y0, 0.0F).color(r, g, b, a).next();
            BufferRenderer.drawWithGlobalProgram(buffer.end());

            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
        } finally {
            // 顺序要紧：先还原矩阵（下一次 VertexBuffer.draw 会重新上传它们），再还原 shader 状态。
            modelView.set(savedModelView);
            projection.set(savedProjection);
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
