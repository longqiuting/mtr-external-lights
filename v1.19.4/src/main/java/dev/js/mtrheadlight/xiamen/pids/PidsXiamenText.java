// 屏幕上的文字：只做「按 px 摆放 + 按 px 裁切」，不碰任何 GL 状态。
package dev.js.mtrheadlight.xiamen.pids;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;

/**
 * 厦门 PIDS 的文字基元。
 *
 * <h2>为什么不用 MTR 的 {@code IDrawing.drawStringWithFont}</h2>
 * <p>它是个「整块居中 + 自动缩放」的排版器，语义绕：
 * <pre>
 *   字号 = 1 / lineHeight（lineHeight 越小字越大，反直觉）
 *   y 的含义随 {@code VerticalAlignment} 变（TOP 时不是上沿），x 还要乘 lineHeight
 *   maxWidth/maxHeight 是「行高倍数」，不是 px
 * </pre>
 * 上一版的「两行字叠在一起」就是这么来的：一行按 TOP、一行按 BOTTOM 传，落在同一个 y 上。
 * 这里换成 vanilla 的 {@code TextRenderer.draw}，<b>1 px = 1 px</b>，y 就是字形上沿，
 * 想放哪放哪；字号 = 目标字高 / {@link PidsXiamenLayout#FONT_UNIT_PX}（=8）。
 *
 * <h2>字形盒</h2>
 * <p>{@code TextRenderer} 的字形在缩放 1 下占 8px 高（行高 9px）。所以：
 * <pre>
 *   scale   = 目标字高px / 8
 *   glyphBox = [yTop, yTop + 目标字高px]
 * </pre>
 * 行与行之间由调用方给不同的 yTop，因此<b>不可能叠字</b>。
 *
 * <h2>裁切：真正在边缘把字切开（不是整字跳进跳出）</h2>
 * <p>滚动标语要「滚出框就看不见」，而且必须<b>在边框那一像素上被切开</b> ——
 * 半个「厦」字露在条内、另一半被切掉，和天津的 {@code ScrollingText} 用 UV 裁边同一个观感。
 *
 * <p>上一版是「逐字裁切」：整字落在窗口外就不画，于是边缘是「一个字突然出现 / 消失」，
 * 而且跨界的那一个字会整字画出去（最多溢出一个字的宽度，压到标语条的描边上）——
 * 看着既跳又不齐。
 *
 * <p>现在改成<b>顶点级裁切</b>：{@code TextRenderer} 的每个字形就是一个四边形，
 * 我们自己实现一个 {@link VertexConsumerProvider}（{@link ClippingProvider}）把它的
 * {@link VertexConsumer} 包起来，四边形落进缓冲区之前把 x 夹到
 * {@code [clipLeft, clipRight]}，<b>u 按夹取比例线性插值</b>——于是被切掉的那部分纹理不采样，
 * 边缘正好落在窗口边上。整个过程<b>不碰任何 GL 状态</b>（不用 scissor、不中途 flush 共享 buffer），
 * 与「一个 GL 状态都不碰」的约定一致。
 *
 * <p>顶点坐标在进入我们的 consumer 前已经被 {@code TextRenderer} 乘过矩阵
 * （{@code VertexConsumer#vertex(Matrix4f, …)} 的默认实现就是先变换再交给
 * {@code vertex(double, double, double)}），所以拿到的 x 已经是屏幕平面 px。
 */
final class PidsXiamenText {

    private PidsXiamenText() {
    }

    /** 一次性诊断：最近一个字形落在哪个字体层上（每 JVM 记第一次，之后不再 toString）。 */
    private static volatile String lastLayer;

    /** 一次性诊断用；{@code null} = 还没画过带裁切的字。 */
    static String lastLayerName() {
        return lastLayer;
    }

    private static void noteLayer(final RenderLayer layer) {
        if (lastLayer == null) {
            lastLayer = String.valueOf(layer);
        }
    }

    /** 一段文字（自带字号与颜色）。 */
    record Segment(String text, float size, int color) {
    }

    /**
     * 摆一串「不同字号」的文字，<b>底边对齐</b>到 {@code boxTop + boxHeight}。
     *
     * <p>用于「X 分钟」「“线路号” 约X 分钟」这种数字大、单位小的混排：
     * 数字和单位坐在同一条基线上，参考图就是这样。
     *
     * @param x         锚点（由 {@code align} 决定含义）
     * @param boxTop    这一行的上沿
     * @param boxHeight 这一行的高度（= 最大的那个字号，用来定基线）
     * @param align     水平对齐
     * @param maxWidth  最大宽度（px），超了整体等比缩小；{@code <= 0} = 不限
     * @param clipLeft  裁切左边界（px），{@code -inf} = 不裁
     * @param clipRight 裁切右边界（px），{@code +inf} = 不裁
     */
    static void drawRun(final MatrixStack matrices, final TextRenderer textRenderer,
                        final VertexConsumerProvider.Immediate immediate, final Segment[] segments,
                        final float x, final float boxTop, final float boxHeight,
                        final PidsXiamenLayout.HAlign align, final float maxWidth,
                        final float clipLeft, final float clipRight) {
        if (segments == null || segments.length == 0) {
            return;
        }

        // 1) 先量总宽，超了就整体等比缩小（不换行、不压边）
        float total = 0.0F;
        for (final Segment segment : segments) {
            total += width(textRenderer, segment.text(), segment.size());
        }
        float shrink = 1.0F;
        if (maxWidth > 0.0F && total > maxWidth) {
            shrink = maxWidth / total;
            total = maxWidth;
        }

        // 2) 按对齐算出起点
        final float startX = x + switch (align) {
            case LEFT -> 0.0F;
            case CENTER -> -total * 0.5F;
            case RIGHT -> -total;
        };

        // 3) 逐段画，底边落在 boxTop + boxHeight
        final float bottom = boxTop + boxHeight;
        float cursor = startX;
        // 裁切只在「标语滚动」这种调用里开启。开了就必须换一套坐标系：
        // TextRenderer 收到的矩阵里不能带世界变换（否则顶点已经是世界坐标，没法拿
        // 标语条的 px 边界去夹），所以此时用一张只含 translate+scale 的本地矩阵，
        // 由 ClippingConsumer 在 emit 的时候再把世界矩阵乘回去。
        final boolean clip = !Float.isInfinite(clipLeft) || !Float.isInfinite(clipRight);
        final Matrix4f world = clip ? new Matrix4f(matrices.peek().getPositionMatrix()) : null;
        final VertexConsumerProvider provider = clip
                ? new ClippingProvider(immediate, clipLeft, clipRight, world)
                : immediate;
        for (final Segment segment : segments) {
            if (segment.text() == null || segment.text().isEmpty()) {
                continue;
            }
            final float size = Math.max(1.0F, segment.size() * shrink);
            final float scale = size / PidsXiamenLayout.FONT_UNIT_PX;
            blit(textRenderer, provider, segment.text(), cursor, bottom - size, scale,
                    segment.color(), clip ? new MatrixStack() : matrices);
            cursor += textRenderer.getWidth(segment.text()) * scale;
        }
    }

    /** 一段文字的像素宽度（缩放前）。 */
    static float width(final TextRenderer textRenderer, final String text, final float size) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        return textRenderer.getWidth(text) * (size / PidsXiamenLayout.FONT_UNIT_PX);
    }

    /**
     * 真正落笔。
     *
     * <p><b>1.19.4 的真实签名</b>（{@code javap} 1.19.4 yarn+build.2 named jar，十个参数）：
     * <pre>
     * TextRenderer.draw(String, float x, float y, int color, boolean shadow,
     *                   <b>org.joml.Matrix4f</b> matrix, VertexConsumerProvider,
     *                   <b>TextRenderer.TextLayerType</b>, int backgroundColor, int light)
     * </pre>
     * 也就是：1.19.4 已经和 1.20.1 同形（JOML {@code Matrix4f} + {@code TextLayerType} 枚举），
     * 只有 1.18.2 / 1.19.2 是「{@code net.minecraft.util.math.Matrix4f} + boolean seeThrough」。
     *
     * @param stack 坐标系载体：不裁切时是渲染器那张（含世界变换）；裁切时是新建的本地栈
     *              （只有 translate + scale，顶点 = 屏幕 px，由 ClippingConsumer 再乘世界矩阵）
     */
    private static void blit(final TextRenderer textRenderer, final VertexConsumerProvider provider,
                             final String text, final float x, final float yTop, final float scale,
                             final int color, final MatrixStack stack) {
        stack.push();
        try {
            stack.translate(x, yTop, 0.0F);
            stack.scale(scale, scale, 1.0F);
            // 逐位写清楚，不靠位置猜：
            final boolean shadow = false;                                  // 屏幕上的字不带阴影
            final TextRenderer.TextLayerType layerType = TextRenderer.TextLayerType.NORMAL;
            final int backgroundColor = 0;                                 // 不画字底
            final int light = PidsXiamenLayout.FULL_LIGHT;                 // 0xF000F0，满亮
            textRenderer.draw(text, 0.0F, 0.0F, color, shadow,
                    stack.peek().getPositionMatrix(), provider,
                    layerType, backgroundColor, light);
        } finally {
            stack.pop();
        }
    }

    // ================================================================== 裁边

    /**
     * 把所有 {@link VertexConsumer} 包一层「按 x 裁剪」的 provider。
     *
     * <p>{@code TextRenderer} 是按<b>字体图集</b>取 buffer 的（一个字形可能落在不同的
     * {@code RenderLayer} 上），所以只能包 provider，不能包单个 consumer。
     *
     * <h2>为什么不能按层缓存 consumer（旧版中文乱码的真因）</h2>
     * <p>1.18.2 / 1.19.2 / 1.19.4 的 {@code VertexConsumerProvider.Immediate} 里，
     * 字体图集层<b>没有</b>自己的 {@code BufferBuilder}：{@code BufferBuilderStorage} 只给
     * glint / lines / water_mask 那几个特殊层预建 buffer（{@code layerBuffers}），
     * 其余所有层共用<b>同一个 fallback {@code BufferBuilder}</b>（见
     * {@code Immediate#getBufferInternal} 的 {@code getOrDefault(layer, fallbackBuffer)}）。
     * 也就是说：顶点到底属于哪一层，<b>不是</b>由你手里那个 consumer 记住的，而是由
     * {@code Immediate.currentLayer} 在<b>绘制那一刻</b>决定；而 {@code currentLayer} 只在
     * {@code delegate.getBuffer(layer)} 被调用、且 layer 与当前不同时才切换（切的时候顺便把
     * 上一层的顶点画掉）。
     *
     * <p>所以「按层缓存 consumer、之后复用」等于：<b>第二次用到某一层时不再调用 delegate</b>，
     * {@code currentLayer} 还停在上一个图集上 → 这些顶点被写进上一层的 buffer、用上一层的
     * 贴图画出来。
     *
     * <p>为什么只有中文坏：一条纯 ASCII 文字只落在<b>一个</b>图集层上，怎么缓存都不会串；
     * 一条中文会跨 <b>≥2 个</b>图集层（字形按 {@code hasColor()} 分两张图集，
     * 256×256 的图集页装满后也会溢出到下一页 —— 中文用量大的存档两张页一定都在用），
     * 于是逐字交替 → 中文字形被按 ASCII 图集采样 → 「英文正常、中文全乱」。
     * 同一块屏上「到站行」的中文走原版 provider（不裁切、不缓存），所以它是好的。
     */
    private static final class ClippingProvider implements VertexConsumerProvider {

        private final VertexConsumerProvider delegate;
        private final float left;
        private final float right;
        /** 屏幕平面 → 世界 的矩阵（裁切在屏幕 px 里做，emit 的时候再乘回去）。 */
        private final Matrix4f world;
        /**
         * <b>只有一个</b> consumer，<b>绝不按层缓存</b>（同一时刻只有一个字形在攒顶点）。
         * 见类注释「为什么不能按层缓存」。
         */
        private final ClippingConsumer consumer;

        ClippingProvider(final VertexConsumerProvider delegate, final float left, final float right,
                         final Matrix4f world) {
            this.delegate = delegate;
            this.left = left;
            this.right = right;
            this.world = world;
            this.consumer = new ClippingConsumer(left, right, world);
        }

        @Override
        public VertexConsumer getBuffer(final RenderLayer layer) {
            // 关键：每个字形都重新问一次 delegate。
            // 旧版（1.18.2 / 1.19.2 / 1.19.4）的 Immediate 所有层共用同一个 fallback
            // BufferBuilder，顶点归属哪一层**只由 currentLayer 决定**，而 currentLayer 只有
            // 在这次调用里才会切换。缓存 per-layer consumer 就会跳过这次切换 →
            // 顶点被记到上一个图集层上、用上一层的贴图画出来（中文乱码、英文正常）。
            this.consumer.bind(this.delegate.getBuffer(layer));
            noteLayer(layer);
            return this.consumer;
        }
    }

    /**
     * 顶点级裁切：攒满一个四边形（4 个顶点）就按窗口切一刀再交给真正的 consumer。
     *
     * <p>切法：取这个四边形 x 的跨度 {@code [x0, x1]} 与 u 的跨度 {@code [u0, u1]}，
     * 把每个顶点的 x 夹到 {@code [left, right]}，u 按 {@code (x − x0) / (x1 − x0)} 线性插值。
     * 完全在窗口外的四边形整块丢掉（不产生退化三角形）。
     *
     * <p>字形的四边形永远是「左上/左下/右下/右上」且 u 只随 x 变化，所以这个插值就是
     * 纹理坐标上的等比裁切（= 天津 ScrollingText 里 {@code drawTexture} 的
     * {@code u1/u2} 夹取）。
     */
    private static final class ClippingConsumer implements VertexConsumer {

        /** 当前这个字形要写进去的 buffer —— {@link ClippingProvider#getBuffer} 每字都换一次。 */
        private VertexConsumer delegate;
        private final float left;
        private final float right;
        /** 屏幕平面（px）→ 世界。裁剪后在这个坐标系里落顶点。 */
        private final Matrix4f world;

        /** 一个四边形的 4 个顶点（屏幕平面 px / uv）。 */
        private final float[] xs = new float[4];
        private final float[] ys = new float[4];
        private final float[] zs = new float[4];
        private final float[] us = new float[4];
        private final float[] vs = new float[4];
        /** 已经攒了几个顶点 / 已经 next() 了几个顶点（{@code next()} 是「这个顶点完了」，不是「四边形完了」）。 */
        private int count;
        private int done;

        /** 顶点级的固定状态（字形四边形内全都一样）。 */
        private int red = 255;
        private int green = 255;
        private int blue = 255;
        private int alpha = 255;
        private int lightU = -1;
        private int lightV = -1;
        private int overlayU;
        private int overlayV;
        private boolean hasOverlay;
        private boolean hasNormal;
        private float nx;
        private float ny = 1.0F;
        private float nz;

        /** 复用，别每顶点 new 一个。 */
        private final Vector4f vector = new Vector4f();

        ClippingConsumer(final float left, final float right, final Matrix4f world) {
            this.left = left;
            this.right = right;
            this.world = world;
        }

        /** 换到「这个字形的层」对应的 buffer（每字一次，{@code null} 只可能出现在建对象那一刻）。 */
        void bind(final VertexConsumer delegate) {
            this.delegate = delegate;
        }

        // ---------------------------------------------------------- 顶点

        @Override
        public VertexConsumer vertex(final double x, final double y, final double z) {
            this.raw((float) x, (float) y, (float) z);
            return this;
        }

        /** {@code TextRenderer} 走的就是这个重载（默认实现先乘矩阵再调 {@code vertex(double…)}）。 */
        @Override
        public VertexConsumer vertex(final Matrix4f matrix, final float x, final float y, final float z) {
            this.vector.set(x, y, z, 1.0F);
            matrix.transform(this.vector);
            this.raw(this.vector.x(), this.vector.y(), this.vector.z());
            return this;
        }

        private void raw(final float x, final float y, final float z) {
            if (this.count >= 4) {
                // 不是四边形（理论上不会发生）：先把攒着的发掉，别丢几何。
                this.flush();
            }
            this.xs[this.count] = x;
            this.ys[this.count] = y;
            this.zs[this.count] = z;
            this.count++;
        }

        @Override
        public VertexConsumer color(final int r, final int g, final int b, final int a) {
            this.red = r;
            this.green = g;
            this.blue = b;
            this.alpha = a;
            return this;
        }

        @Override
        public VertexConsumer texture(final float u, final float v) {
            if (this.count > 0) {
                this.us[this.count - 1] = u;
                this.vs[this.count - 1] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer overlay(final int u, final int v) {
            this.overlayU = u;
            this.overlayV = v;
            this.hasOverlay = true;
            return this;
        }

        @Override
        public VertexConsumer light(final int u, final int v) {
            this.lightU = u;
            this.lightV = v;
            return this;
        }

        @Override
        public VertexConsumer normal(final float x, final float y, final float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            this.hasNormal = true;
            return this;
        }

        @Override
        public void fixedColor(final int r, final int g, final int b, final int a) {
            if (this.delegate != null) {
                this.delegate.fixedColor(r, g, b, a);
            }
        }

        @Override
        public void unfixColor() {
            if (this.delegate != null) {
                this.delegate.unfixColor();
            }
        }

        // ---------------------------------------------------------- 发顶点

        @Override
        public void next() {
            // MC 的约定：next() = 「这个顶点写完了」（一个四边形要 4 次）。
            this.done++;
            if (this.done >= 4) {
                this.flush();
            }
        }

        /** 一个四边形画完：夹到窗口、按比例改 u，再原样交给真正的 consumer。 */
        private void flush() {
            final int n = this.count;
            this.count = 0;
            this.done = 0;
            if (n <= 0) {
                return;
            }
            if (n < 4) {
                // 不成四边形：原样转发（不裁，宁可多画一点也不丢内容）。
                for (int i = 0; i < n; i++) {
                    this.emit(this.xs[i], this.ys[i], this.zs[i], this.us[i], this.vs[i]);
                }
                return;
            }

            float x0 = this.xs[0];
            float x1 = this.xs[0];
            int i0 = 0;
            int i1 = 0;
            for (int i = 1; i < 4; i++) {
                if (this.xs[i] < x0) {
                    x0 = this.xs[i];
                    i0 = i;
                }
                if (this.xs[i] > x1) {
                    x1 = this.xs[i];
                    i1 = i;
                }
            }
            // 整块在窗口外：一个字都不画（这就是「滚出去就没了」）
            if (x1 <= this.left || x0 >= this.right) {
                return;
            }
            // u 与 x 的关系取「最左顶点 → 最右顶点」这一对（RTL / 镜像字形也照样对）
            final float dx = x1 - x0;
            final float uAtLeft = this.us[i0];
            final float du = this.us[i1] - uAtLeft;
            for (int i = 0; i < 4; i++) {
                final float clamped = Math.max(this.left, Math.min(this.right, this.xs[i]));
                final float u;
                if (Math.abs(dx) < 1.0E-4F || Math.abs(du) < 1.0E-6F) {
                    // 退化四边形（零宽 / 纯色）：不动 uv
                    u = this.us[i];
                } else {
                    u = uAtLeft + (clamped - x0) / dx * du;
                }
                this.emit(clamped, this.ys[i], this.zs[i], u, this.vs[i]);
            }
        }

        private void emit(final float x, final float y, final float z, final float u, final float v) {
            if (this.delegate == null) {
                // 还没 bind 过（理论上不会发生：getBuffer 一定先调）：宁可丢这一点几何，别 NPE 掉整块屏。
                return;
            }
            // 顶点在屏幕 px 里，落笔前乘回世界矩阵（这里是唯一需要它的地方）。
            this.delegate.vertex(this.world, x, y, z)
                    .color(this.red, this.green, this.blue, this.alpha)
                    .texture(u, v);
            if (this.hasOverlay) {
                this.delegate.overlay(this.overlayU, this.overlayV);
            }
            if (this.lightU >= 0) {
                this.delegate.light(this.lightU, this.lightV);
            }
            if (this.hasNormal) {
                this.delegate.normal(this.nx, this.ny, this.nz);
            }
            this.delegate.next();
        }
    }
}
