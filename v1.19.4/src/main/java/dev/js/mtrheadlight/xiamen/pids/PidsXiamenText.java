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
     * 真正落笔（{@code TextRenderer.draw} 的 1.20.1 签名：text, x, y, color, shadow, matrix, provider, layer, bg, light）。
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
            textRenderer.draw(text, 0.0F, 0.0F, color, false,
                    stack.peek().getPositionMatrix(), provider,
                    TextRenderer.TextLayerType.NORMAL, 0,
                    PidsXiamenLayout.FULL_LIGHT);
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
     */
    private static final class ClippingProvider implements VertexConsumerProvider {

        private final VertexConsumerProvider delegate;
        private final float left;
        private final float right;
        /** 屏幕平面 → 世界 的矩阵（裁切在屏幕 px 里做，emit 的时候再乘回去）。 */
        private final Matrix4f world;
        /** 每个 RenderLayer 一个 consumer（字形图集通常只有 1~2 页）：避免每字都新建对象。 */
        private final java.util.Map<RenderLayer, ClippingConsumer> consumers = new java.util.HashMap<>();

        ClippingProvider(final VertexConsumerProvider delegate, final float left, final float right,
                         final Matrix4f world) {
            this.delegate = delegate;
            this.left = left;
            this.right = right;
            this.world = world;
        }

        @Override
        public VertexConsumer getBuffer(final RenderLayer layer) {
            return this.consumers.computeIfAbsent(layer,
                    key -> new ClippingConsumer(this.delegate.getBuffer(key), this.left, this.right, this.world));
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

        private final VertexConsumer delegate;
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

        ClippingConsumer(final VertexConsumer delegate, final float left, final float right,
                         final Matrix4f world) {
            this.delegate = delegate;
            this.left = left;
            this.right = right;
            this.world = world;
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
            this.delegate.fixedColor(r, g, b, a);
        }

        @Override
        public void unfixColor() {
            this.delegate.unfixColor();
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
