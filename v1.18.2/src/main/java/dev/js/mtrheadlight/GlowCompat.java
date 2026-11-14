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
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

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

    /* ------------------------------------------------------------------------------------------
     * 取证：draw 那一刻<b>真实读回</b>的 GL 状态。由 {@code [lte] flare draw:} 那一行打印。
     * 全部是静态量，因为灯晕一帧只画一次（一次 draw 覆盖上一帧的值），读它们的人就在同一个线程上。
     * ---------------------------------------------------------------------------------------- */

    /** 光栅化那一刻的 {@code GL_DEPTH_FUNC}；-1 = 还没画过。 */
    static int diagDepthFunc = -1;
    /** 进入 {@code drawTextured} 时上一位调用者留下的 {@code GL_DEPTH_FUNC}；-1 = 没读。 */
    static int diagDepthFuncOnEntry = -1;
    /** 光栅化那一刻的 {@code GL_BLEND_SRC_RGB} / {@code GL_BLEND_DST_RGB}；-1 = 还没画过。 */
    static int diagBlendSrc = -1;
    static int diagBlendDst = -1;
    static boolean diagBlendEnabled;
    static boolean diagCullEnabled;
    /** 这次 draw 实际绑在贴图槽 0 上的 GL 纹理 id；0 = 什么都没绑。 */
    static int diagTextureId = -1;
    /** 这次 draw 是不是走了 {@code forceOcclusion}（强制 {@code GL_LEQUAL}）那条分支。 */
    static boolean diagForceOcclusion;

    /**
     * Draws {@code count} floats (x y z r g b a per vertex, quad order) in camera-relative space.
     * The position matrix is taken here because its accessor and its owning package differ between
     * 1.18/1.19.2 ({@code com.mojang.math}) and 1.19.3+ ({@code org.joml}).
     */
    static void draw(final float[] data, final int count, final MatrixStack stack) {
        final Matrix4f matrix = stack.peek().getPositionMatrix();
        // Additive, depth-tested, no depth write: light on top of the rail, nothing occluded by it.
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
        BufferRenderer.draw(buffer);

        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        // 还回全局 shader 状态：调用方（以及之后渲染的粒子/天气）默认会继承它。
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
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
     * {@link #drawTextured} 的深度版本：{@code forceOcclusion = true} 时把深度<b>比较</b>函数<b>强制</b>
     * 成 {@code GL_LEQUAL}（写完再还原成进来时的那一个）：四边形仍然不写深度、仍然加色，但它<b>会</b>
     * 被车体 / 站台 / 隧道壁按深度挡掉。
     *
     * <p>为什么灯晕要「强制 LEQUAL」而不是 {@code GL_ALWAYS}：1.0.62 用 ALWAYS 换来了「看得见」，
     * 代价是隔着方块、隔着轨道、隔着整座山也能看见灯晕 —— 加色四边形不测深度，于是它穿墙。遮挡
     * 只能靠<b>位置</b>解决：{@code TrainLamps} 把灯放在端面<b>外侧</b>（{@code back = inset +
     * FLARE_GAP} 沿「向外」方向推，所以至少 0.08 格外、模型量到的 inset 还会再往外），四边形再从
     * 那里往相机拉一点点（{@code LightFlare#MAX_PULL} = 0.6 格上限），够把它从灯座里拔出来，
     * 又不至于穿到墙外。而「不被别的模组留下的深度状态吃掉」靠这里的强制 LEQUAL：深度函数是全局
     * 状态，进出都不许假设。
     *
     * <h2>为什么不走 RenderSystem：1.20.1 的缓存会把这次设置吃掉</h2>
     * {@code RenderSystem.depthFunc} → {@code GlStateManager._depthFunc}，而后者在 1.20.1 里是
     * <b>带缓存的</b>（javap 出来的字节码就是 {@code if (func == DEPTH.func) return;}，值相等时
     * 根本不调 {@code glDepthFunc}）。只要有别的模组直接调过一次 {@code glDepthFunc}（Sodium /
     * Embeddium 这类优化模组满街都是），缓存就和真实 GL 状态脱钩：我们「设了 LEQUAL」，实际硬件上
     * 可能还留着别人设的 ALWAYS / NEVER。所以这里用 {@link #forceDepthFunc} 直调 GL。
     *
     * <p>另外每次 draw 之后都用 {@code glGetInteger(GL_DEPTH_FUNC)} / {@code glIsEnabled} 把
     * <b>真实</b>状态读回来放进 {@code diag*} 字段 —— 灯晕那条 5 秒日志报的就是这个值，不是我们
     * 以为设了什么。
     */
    static void drawTextured(final float[] data, final int count, final MatrixStack stack,
                             final Identifier texture, final boolean forceOcclusion) {
        final Matrix4f matrix = stack.peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        RenderSystem.enableDepthTest();
        // 进来时上一位调用者留下的深度函数（可能是任何值，不许假设 LEQUAL）。
        final int entryFunc = forceOcclusion ? GL11.glGetInteger(GL11.GL_DEPTH_FUNC) : -1;
        if (forceOcclusion) {
            // 遮挡 = LEQUAL（深度真的在挡），而不是 1.0.62 的 ALWAYS（穿墙可见）。
            forceDepthFunc(GL11.GL_LEQUAL);
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
        BufferRenderer.draw(buffer);

        /*
         * 取证：读回光栅化那一刻的真实状态。
         *
         * 为什么在 draw 之后读：{@code BufferRenderer.drawWithGlobalProgram} →
         * {@code VertexBuffer.draw} 会在内部 {@code ShaderProgram.bind()}，而 bind 会把 json 里
         * 声明的 gl 状态（blend）盖上来 —— 所以「我们 draw 前设的」从来不代表「draw 时生效的」。
         * draw 之后、我们的还原之前，GL 里留着的就是这一次绘制真正用的状态。
         * 这也顺便证明了 position_tex_color.json 自带的 blend 是 srcalpha / 1-srcalpha，
         * 而不是这里设的加色。
         */
        diagDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        diagBlendSrc = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        diagBlendDst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        diagBlendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        diagCullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        diagTextureId = RenderSystem.getShaderTexture(0);
        diagForceOcclusion = forceOcclusion;
        diagDepthFuncOnEntry = entryFunc;

        if (forceOcclusion) {
            forceDepthFunc(entryFunc == -1 ? GL11.GL_LEQUAL : entryFunc);
        }
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    /**
     * 真正改掉深度比较函数，绕过 {@code GlStateManager} 的缓存（见 {@link #drawTextured} 的说明）。
     *
     * <p>顺序是「先直调 GL 把硬件状态改掉，再把缓存揉成一致」：{@code _depthFunc} 只在值变化时才
     * 下发，所以先用一个不同的值把缓存推开，再写目标值，保证缓存那一步也真的是一次 GL 调用 ——
     * 否则结束时还原 LEQUAL 会因为「缓存里本来就是 LEQUAL」而被跳过，把 ALWAYS 留在硬件上，后面
     * 整个世界的深度测试全废。
     */
    private static void forceDepthFunc(final int func) {
        GL11.glDepthFunc(func);
        final int other = func == GL11.GL_LEQUAL ? GL11.GL_ALWAYS : GL11.GL_LEQUAL;
        GlStateManager._depthFunc(other);
        GlStateManager._depthFunc(func);
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
        // 这两个 getter 返回的就是 RenderSystem 内部那一份实例，1.18.2 的 net.minecraft.util.math.Matrix4f
        // 只有 loadIdentity()/load(..)，没有 1.20.1 的 identity()/set(..)。
        final Matrix4f projection = RenderSystem.getProjectionMatrix();
        final Matrix4f modelView = RenderSystem.getModelViewMatrix();
        final Matrix4f savedProjection = projection.copy();
        final Matrix4f savedModelView = modelView.copy();
        try {
            projection.loadIdentity();
            modelView.loadIdentity();

            RenderSystem.setShader(GameRenderer::getPositionColorShader);
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
            identity.loadIdentity();
            buffer.vertex(identity, x0, y0, 0.0F).color(r, g, b, a).next();
            buffer.vertex(identity, x0, y1, 0.0F).color(r, g, b, a).next();
            buffer.vertex(identity, x1, y1, 0.0F).color(r, g, b, a).next();
            buffer.vertex(identity, x1, y0, 0.0F).color(r, g, b, a).next();
            BufferRenderer.draw(buffer);

            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
        } finally {
            // 顺序要紧：先还原矩阵（下一次 VertexBuffer.draw 会重新上传它们），再还原 shader 状态。
            modelView.load(savedModelView);
            projection.load(savedProjection);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
