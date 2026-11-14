// 七段数码管字形：把 "00:00" 画成一截截发光段，不碰任何 GL 状态。
package dev.js.mtrheadlight.xiamen.dep;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;

/**
 * 挂墙发车倒数器的<b>七段数码管</b>数字（1 px = 1 LED 像素，坐标同 {@link DepOnWallLayout}：
 * +X 向右、+Y 向下，原点 = 屏幕左上角）。
 *
 * <h2>为什么自己画几何，而不是烘一张字体</h2>
 * <p>屏幕只有 96×64 个 LED 像素，字号要跟着数字位数（{@code 00:00} / {@code 10:00}）和
 * 屏幕高度自动变。走 {@code TextRenderer} 要么得把 {@code dg.ttf} 用 AWT 烘成位图再塞进
 * 字体资源（多一份运行时依赖、多一套 UV 账），要么字形一放大就糊。直接按七段画四边形则：
 * <ul>
 *   <li>任意字号都锐利 —— 每段都是一块平行四边形，顶点按当前字高现算；</li>
 *   <li>颜色就是顶点色，绿 / 红切换不用换贴图；</li>
 *   <li>和它所在的那块自发光底图同一个 {@code RenderLayer}，永远不会被环境光压暗。</li>
 * </ul>
 *
 * <h2>段的形状</h2>
 * <p>每一段都是一个<b>两端斜切的六边形</b>（不是矩形）：七段管真实的段是「中间粗、两端收成
 * 45° 尖」的，尖与尖之间留出的那条斜缝正是数码管看上去「一节一节」的来源。矩形段拼起来
 * 会变成一块实心色块，远看认不出是 LED。
 *
 * <h2>顶点绕向</h2>
 * <p>自发光层（{@code getEntityTranslucentEmissive}）在 1.20.1 是 {@code DISABLE_CULLING}
 * 的（{@code RenderLayer} 里查得到），正反两面都画，所以绕向不会决定「看不看得见」。
 * 仍然统一按和底图那块四边形一样的方向发顶点 —— 左上 → 左下 → 右下 → 右上，
 * 在本文件的局部坐标系里<b>有向面积为负</b>，法线朝 -Z（= 朝观察者），
 * 换个平台 / 换个层要开剔除时也不会突然消失。
 * <p>注意绕向和<b>读数方向</b>是两件事：读数方向只取决于局部 +X 在世界上指向哪边
 * （见 {@code DepOnWallRenderer#screenBasis} 与 {@link DepOnWallOrient}），不要靠镜像去调它。
 *
 * <h2>字宽</h2>
 * <p>所有比例都以<b>字高</b>为单位（{@link DepOnWallLayout#LED_DIGIT_ASPECT} 等），
 * 所以 {@link #width(String, float)} 传 1.0 得到的就是「字高 = 1 时整串有多宽」——
 * 渲染器用它做「撑满可用宽高」的等比缩放。
 */
final class DepOnWallDigits {

    private DepOnWallDigits() {
    }

    // ------------------------------------------------------------------ 段掩码

    /** 段位：A 上、B 右上、C 右下、D 下、E 左下、F 左上、G 中。 */
    private static final int A = 1;
    private static final int B = 1 << 1;
    private static final int C = 1 << 2;
    private static final int D = 1 << 3;
    private static final int E = 1 << 4;
    private static final int F = 1 << 5;
    private static final int G = 1 << 6;

    /** {@code '0'..'9'} 各要点亮哪几段（下标 = 字符 - '0'）。 */
    private static final int[] GLYPHS = {
            A | B | C | D | E | F,          // 0
            B | C,                          // 1
            A | B | G | E | D,              // 2
            A | B | G | C | D,              // 3
            F | G | B | C,                  // 4
            A | F | G | C | D,              // 5
            A | F | G | E | C | D,          // 6
            A | B | C,                      // 7
            A | B | C | D | E | F | G,      // 8
            A | B | C | D | F | G,          // 9
    };

    // ------------------------------------------------------------------ 度量

    /**
     * 整串在「字高 = {@code height}」时占多宽（px）。
     *
     * @param height 字高（px）；传 1.0 得到的就是以字高为单位宽度
     */
    static float width(final String text, final float height) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        final float digitWidth = height * DepOnWallLayout.LED_DIGIT_ASPECT;
        final float colonWidth = height * DepOnWallLayout.LED_COLON_WIDTH;
        final float gap = height * DepOnWallLayout.LED_DIGIT_GAP;

        float total = 0.0F;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (i > 0) {
                total += gap;
            }
            total += c == ':' ? colonWidth : digitWidth;
        }
        return total;
    }

    // ------------------------------------------------------------------ 绘制

    /**
     * 画一整串数字。
     *
     * @param buffer  已经是自发光层（{@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}）的顶点流
     * @param left    整串的左沿（px）
     * @param top     整串的上沿（px）
     * @param height  字高（px）
     * @param argb    顶点色（数码管就是「纯色发光」，没有阴影 / 描边）
     */
    static void draw(final VertexConsumer buffer, final Matrix4f position, final Matrix3f normal,
                     final String text, final float left, final float top, final float height,
                     final int argb) {
        if (text == null || text.isEmpty() || height <= 0.0F) {
            return;
        }
        final float digitWidth = height * DepOnWallLayout.LED_DIGIT_ASPECT;
        final float colonWidth = height * DepOnWallLayout.LED_COLON_WIDTH;
        final float gap = height * DepOnWallLayout.LED_DIGIT_GAP;
        final float thickness = height * DepOnWallLayout.LED_SEG_THICKNESS;

        float x = left;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (i > 0) {
                x += gap;
            }
            if (c == ':') {
                drawColon(buffer, position, normal, x, top, colonWidth, height, thickness, argb);
                x += colonWidth;
            } else {
                drawDigit(buffer, position, normal, c, x, top, digitWidth, height, thickness, argb);
                x += digitWidth;
            }
        }
    }

    /** 一个数字：按 {@link #GLYPHS} 只画亮着的段（灭的段什么都不画 = 就是屏幕底色）。 */
    private static void drawDigit(final VertexConsumer buffer, final Matrix4f position,
                                  final Matrix3f normal, final char c, final float left,
                                  final float top, final float width, final float height,
                                  final float thickness, final int argb) {
        final int digit = c - '0';
        if (digit < 0 || digit >= GLYPHS.length) {
            return;
        }
        final int mask = GLYPHS[digit];

        // 段的中轴线：横段在 x = 左 / 中 / 右三档，竖段在 y = 上 / 下两档。
        // 各往内缩半个段厚，七段的「外轮廓」才正好框住 width × height。
        final float half = thickness * 0.5F;
        final float x0 = left + half;
        final float x1 = left + width - half;
        final float y0 = top + half;
        final float y1 = top + height - half;
        final float yMid = top + height * 0.5F;

        if ((mask & A) != 0) {
            horizontal(buffer, position, normal, x0, x1, y0, thickness, argb);
        }
        if ((mask & D) != 0) {
            horizontal(buffer, position, normal, x0, x1, y1, thickness, argb);
        }
        if ((mask & G) != 0) {
            horizontal(buffer, position, normal, x0, x1, yMid, thickness, argb);
        }
        if ((mask & F) != 0) {
            vertical(buffer, position, normal, x0, y0, yMid, thickness, argb);
        }
        if ((mask & B) != 0) {
            vertical(buffer, position, normal, x1, y0, yMid, thickness, argb);
        }
        if ((mask & E) != 0) {
            vertical(buffer, position, normal, x0, yMid, y1, thickness, argb);
        }
        if ((mask & C) != 0) {
            vertical(buffer, position, normal, x1, yMid, y1, thickness, argb);
        }
    }

    /**
     * 冒号：两个方点（不用一个字形的矩形段画）。竖直位置取字框的 30% / 70%，
     * 和两侧数字的中段错开一点，远看是 {@code 12:34} 而不是 {@code 12.34}。
     */
    private static void drawColon(final VertexConsumer buffer, final Matrix4f position,
                                  final Matrix3f normal, final float left, final float top,
                                  final float width, final float height, final float thickness,
                                  final int argb) {
        final float size = thickness * 1.15F;
        final float cx = left + width * 0.5F;
        final float half = size * 0.5F;
        dot(buffer, position, normal, cx, top + height * 0.30F, half, argb);
        dot(buffer, position, normal, cx, top + height * 0.70F, half, argb);
    }

    // ------------------------------------------------------------------ 基本形状

    /** 横段：以 {@code y} 为中轴，从 {@code x0} 到 {@code x1}，两端 45° 斜切。 */
    private static void horizontal(final VertexConsumer buffer, final Matrix4f position,
                                   final Matrix3f normal, final float x0, final float x1,
                                   final float y, final float thickness, final int argb) {
        final float h = thickness * 0.5F;
        // p0 左尖、p1 左下斜角、p2 右下斜角、p3 右尖、p4 右上斜角、p5 左上斜角。
        final float px0 = x0;
        final float py0 = y;
        final float px1 = x0 + h;
        final float py1 = y + h;
        final float px2 = x1 - h;
        final float py2 = y + h;
        final float px3 = x1;
        final float py3 = y;
        final float px4 = x1 - h;
        final float py4 = y - h;
        final float px5 = x0 + h;
        final float py5 = y - h;

        hexagon(buffer, position, normal, argb,
                px0, py0, px1, py1, px2, py2, px3, py3, px4, py4, px5, py5);
    }

    /** 竖段：以 {@code x} 为中轴，从 {@code y0} 到 {@code y1}，两端 45° 斜切。 */
    private static void vertical(final VertexConsumer buffer, final Matrix4f position,
                                 final Matrix3f normal, final float x, final float y0,
                                 final float y1, final float thickness, final int argb) {
        final float h = thickness * 0.5F;
        // q0 上尖、q1 左上斜角、q2 左下斜角、q3 下尖、q4 右下斜角、q5 右上斜角。
        hexagon(buffer, position, normal, argb,
                x, y0,
                x - h, y0 + h,
                x - h, y1 - h,
                x, y1,
                x + h, y1 - h,
                x + h, y0 + h);
    }

    /** 一个小方点（冒号）。 */
    private static void dot(final VertexConsumer buffer, final Matrix4f position,
                            final Matrix3f normal, final float cx, final float cy,
                            final float half, final int argb) {
        quad(buffer, position, normal, argb,
                cx - half, cy - half,
                cx - half, cy + half,
                cx + half, cy + half,
                cx + half, cy - half);
    }

    /**
     * 凸六边形 → 三个四边形。<b>不能</b>直接塞 6 个顶点：{@code QUADS} 模式下顶点流按
     * 4 个一组解释，多出来的点会和下一段拼成一条错误的四边形。
     *
     * <p>拆法是从质心往外扇形分三块（{@code p0-p1-p2-中心}、{@code p2-p3-p4-中心}、
     * {@code p4-p5-p0-中心}），三块拼起来正好盖满原六边形；扇形保号，所以每块四边形的绕向
     * 和原六边形一致（见类注释的「顶点绕向」）。
     */
    private static void hexagon(final VertexConsumer buffer, final Matrix4f position,
                                final Matrix3f normal, final int argb,
                                final float x0, final float y0,
                                final float x1, final float y1,
                                final float x2, final float y2,
                                final float x3, final float y3,
                                final float x4, final float y4,
                                final float x5, final float y5) {
        final float cx = (x0 + x1 + x2 + x3 + x4 + x5) / 6.0F;
        final float cy = (y0 + y1 + y2 + y3 + y4 + y5) / 6.0F;

        quad(buffer, position, normal, argb, x0, y0, x1, y1, x2, y2, cx, cy);
        quad(buffer, position, normal, argb, x2, y2, x3, y3, x4, y4, cx, cy);
        quad(buffer, position, normal, argb, x4, y4, x5, y5, x0, y0, cx, cy);
    }

    private static void quad(final VertexConsumer buffer, final Matrix4f position,
                             final Matrix3f normal, final int argb,
                             final float x0, final float y0,
                             final float x1, final float y1,
                             final float x2, final float y2,
                             final float x3, final float y3) {
        vertex(buffer, position, normal, x0, y0, argb);
        vertex(buffer, position, normal, x1, y1, argb);
        vertex(buffer, position, normal, x2, y2, argb);
        vertex(buffer, position, normal, x3, y3, argb);
    }

    /**
     * 一个顶点。UV 固定在 {@code (0.5, 0.5)}：横竖段都用 {@code dep_on_wall_led.png}
     * 那张 1 色纯白贴图，取正中就是纯白，颜色完全由 {@code argb} 决定。
     */
    private static void vertex(final VertexConsumer buffer, final Matrix4f position,
                               final Matrix3f normal, final float x, final float y,
                               final int argb) {
        buffer.vertex(position, x, y, 0.0F)
                .color(argb)
                .texture(0.5F, 0.5F)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(DepOnWallLayout.FULL_LIGHT)
                .normal(normal, 0.0F, 0.0F, -1.0F)
                .next();
    }
}
