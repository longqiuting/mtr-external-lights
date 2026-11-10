// 线路条带的纯 AWT 绘制（不引用任何 MC / MTR 类型，可离线单独跑出 PNG 预览）。
package dev.js.mtrheadlight.xiamen;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 厦门实拍样式的线路条带，<b>纯 Java2D</b>（这个类刻意不 import 任何 {@code net.minecraft} /
 * {@code mtr} 类型，所以能用 {@code javac} 单独编译，直接吐出 PNG 看版式，不用开游戏）。
 *
 * <h2>v3 版式（2026-11-10 实拍纠正）</h2>
 * <pre>
 *   白底条带            100% 面板高度（和 MTR 原版线路图一样铺满），上下没有透明缝 → 不再透出灰板
 *   主线                条带高度的 8.5%（MTR 的 lineSize = 图高/8，同一比例）
 *   普通站             白心空心环，环宽 15%（MTR drawStation：半径 = 线宽，环厚 0.29×半径）
 *   终点站             更大的空心环 + 圆圈内侧一个指向圆环的<b>实心三角</b>（v3 修正）
 *   当前站             1.3× 空心环 + 一圈细内环
 *   换乘站             追逐箭头环：上半 = 当前线路色、下半（含垂尾）= 换乘线路色，一笔连成
 *                       一个环（v3 修正）；垂尾指向图标正下方<b>居中</b>的换乘线路牌
 *   站名               45° 斜排、中文 + 英文两行、黑体加粗（v3 修正字体）
 * </pre>
 *
 * <p>尺寸全部相对“条带高度 bandH”，见下面的常量。</p>
 */
public final class XiamenStripPainter {

    /** 纹理基准高度（像素）。宽度 = 高度 x 面板长宽比。 */
    public static final int IMG_HEIGHT = 320;

    /** 纹理宽度上限。 */
    public static final int MAX_WIDTH = 4096;

    /**
     * 白底条带占面板高度：<b>100%</b>。
     *
     * <p>v1/v2 是 60%，上下 40% 透明 —— 吊板的透明像素透出模型那块深灰面板，看上去就是
     * “条带背后贴了一层灰底”。v3 直接铺满：面板可用高度里全是白，和 MTR 原版线路图一样。</p>
     */
    public static final float BAND_RATIO = 1.00f;

    /** 主线路宽：条带高度的 8.5%（MTR {@code lineSize = scale / 8}，即图高 12.5% 的上限内）。 */
    public static final float LINE_RATIO = 0.085f;

    /** 普通站圆点直径上限：条带高度的 19%（MTR：直径 = 2×线宽）。 */
    public static final float CIRCLE_RATIO = 0.19f;

    /** 当前站圆点相对普通点的倍数。 */
    public static final float CURRENT_SCALE = 1.30f;

    /** 终点站圆环相对普通点的倍数（实拍：终点那个圈明显比中间站大一圈）。 */
    public static final float TERMINAL_SCALE = 1.60f;

    /** 圆环描边 / 内环（MTR drawStation 的 0.29×半径 → 直径的 14.5%）。 */
    public static final float RING_RATIO = 0.15f;
    public static final float INNER_RATIO = 0.58f;

    // ---- 换乘标记（追逐箭头环 + 垂尾 + 正下方居中的线路牌）----

    /** 追逐箭头环直径（相对条带高度）。比普通站环大一圈，实拍如此。 */
    public static final float IX_RING_RATIO = 0.260f;
    /** 环厚 / 直径。 */
    public static final float IX_RING_THICK = 0.200f;
    /** 箭头尖长度 / 环厚，宽度 / 环厚。 */
    public static final float IX_HEAD_LEN = 1.55f;
    public static final float IX_HEAD_W = 1.85f;
    /** 垂尾根宽 / 环直径。 */
    public static final float IX_TAIL_W = 0.50f;
    /** 环下缘 → 线路牌上沿。 */
    public static final float IX_PLATE_GAP_RATIO = 0.020f;
    /** 线路牌高度 / 条带高度（两行字：中文 + Line N）。 */
    public static final float IX_PLATE_H_RATIO = 0.155f;
    /** 线路牌左右内边距（× 牌高）。 */
    public static final float IX_PLATE_PAD_RATIO = 0.22f;

    /** 站名字号上限：条带高度的 14%。 */
    public static final float NAME_RATIO = 0.140f;

    /** 英文行字号 / 中文行字号（实拍：英文明显小一号，贴在中文行下方）。 */
    public static final float EN_RATIO = 0.46f;

    /** 字号下限（像素），再小就减少站名数量。 */
    public static final float NAME_FLOOR = 8.0f;

    /** 45° 斜排时的投影系数。 */
    private static final double DIAG = 1.0 / Math.sqrt(2.0);

    /** 相邻站名之间允许占用的站距比例。 */
    private static final float NAME_GAP = 0.92f;

    /** 实际选中的字体名（日志 / 报告用）。 */
    public static volatile String lastFontName = "?";

    // ---- 上一次绘制用到的像素尺寸（日志 / 报告用；不影响绘制） ----
    public static volatile int lastBandH;
    public static volatile int lastLineW;
    public static volatile int lastCircle;
    public static volatile int lastCurrent;
    public static volatile int lastNameSize;
    /** 本次真正画出来的换乘站数量（0 = 全线没有换乘站，和以前完全一样）。 */
    public static volatile int lastInterchangeCount;

    /** 换乘标记的实测像素尺寸（日志 / 报告用）。 */
    public static volatile int lastSwapHead;
    public static volatile int lastSwapPlate;
    /** 终点站圆环直径 / 终点三角箭头长度（日志 / 报告用）。 */
    public static volatile int lastTerminalRing;
    public static volatile int lastTerminalArrow;

    /** 站名正排时，非当前站用的 65% 灰。 */
    public static final int NAME_GREY = 0x9E9E9E;

    /** 深色文字（近黑：小字号下纯黑发毛）。 */
    public static final int TEXT_DARK = 0x1A1A1A;

    /** 英文行稍微浅一点，和中文区分开（实拍里英文也偏深，这里只降一点）。 */
    public static final int TEXT_EN = 0x3A3A3A;

    private static volatile Font cjkFont;

    private XiamenStripPainter() {
    }

    /** 亮度 &gt; 128 算亮色（与 LCD 资源包 {@code lib/color_util.js#isLightColor} 同一阈值）。 */
    public static boolean isLight(final int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b > 128.0;
    }

    /** 画在线路色底上的文字颜色（{@code 0xFF000000} / {@code 0xFFFFFFFF}）。 */
    public static int contrastText(final int rgb) {
        return isLight(rgb) ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** 白底上的普通站名：深色线路用线路色，浅色线路用近黑。 */
    public static int onWhiteText(final int rgb) {
        return isLight(rgb) ? TEXT_DARK : (0xFF000000 | (rgb & 0xFFFFFF));
    }

    /** 白底上的当前站站名（加粗）：深色线路用加深的线路色，浅色线路用近黑。 */
    public static int currentNameText(final int rgb) {
        return isLight(rgb) ? TEXT_DARK : darken(rgb, 0.88);
    }

    /** 颜色按比例加深（保持 A=255）。 */
    public static int darken(final int rgb, final double factor) {
        final int r = (int) Math.round(((rgb >> 16) & 0xFF) * factor);
        final int g = (int) Math.round(((rgb >> 8) & 0xFF) * factor);
        final int b = (int) Math.round((rgb & 0xFF) * factor);
        return 0xFF000000
                | (Math.min(255, Math.max(0, r)) << 16)
                | (Math.min(255, Math.max(0, g)) << 8)
                | Math.min(255, Math.max(0, b));
    }

    /** 一张条带要画的全部内容（与 MC / MTR 无关的纯数据）。 */
    public static final class Model {
        /** 站名，按行车方向排列（已去掉 “|English” 部分）。 */
        public String[] names;
        /** 站名英文（与 {@link #names} 一一对应，可空）。 */
        public String[] namesEn;
        /** 当前站在 {@link #names} 里的下标。 */
        public int currentIndex;
        /** 线路色 {@code 0xRRGGBB}。 */
        public int lineRgb;
        /** 线路号（左侧色块里的字，空串 = 不画色块）。 */
        public String lineNumber;
        /** true = 行进方向朝左（MTR 的 arrowDirection == 2）。 */
        public boolean flip;
        /** true = 站名正排到线下方，false = 45° 斜排到线上方（默认，实拍版式）。 */
        public boolean namesBelow;
        /** 面板宽/高。 */
        public float aspectRatio;
        /** 换乘站（可空）：命中的站点改画追逐箭头环 + 线路牌。 */
        public Interchange[] interchanges;
    }

    /**
     * 一个换乘站：<b>站序下标 + 换乘线路的颜色 / 编号</b>。
     * 数据来自 MTR 的 {@code PlatformRouteDetails.StationDetails#interchangeRoutes}（见
     * {@code XiamenRouteStrip}），所以颜色和编号都跟着存档里那条线路走，不写死。
     */
    public static final class Interchange {
        /** 在 {@code names[]} 里的下标（flip 之前的“数据顺序”）。 */
        public int stationIndex;
        /** 换乘线路色 {@code 0xRRGGBB}。 */
        public int rgb;
        /** 线路号：{@code 4}（可能为空串 —— 那时小牌只写线路名）。 */
        public String number;
        /** 换乘线路中文短名：{@code 4号线}（可能为空串）。 */
        public String name;

        public Interchange() {
        }

        public Interchange(final int stationIndex, final int rgb, final String number, final String name) {
            this.stationIndex = stationIndex;
            this.rgb = rgb;
            this.number = number == null ? "" : number;
            this.name = name == null ? "" : name;
        }
    }

    /** 画一张（返回 ARGB 的 BufferedImage；比例或数据不合法时返回 null）。 */
    public static BufferedImage paint(final Model model) {
        if (model == null || model.names == null || model.names.length < 2 || !(model.aspectRatio > 0.05f)) {
            return null;
        }
        int height = IMG_HEIGHT;
        int width = Math.max(height, Math.round(height * model.aspectRatio));
        if (width > MAX_WIDTH) {
            height = Math.max(48, Math.round(height * (float) MAX_WIDTH / width));
            width = MAX_WIDTH;
        }
        if (width < 16 || height < 16) {
            return null;
        }

        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            final int count = model.names.length;
            final boolean flip = model.flip;
            final int current = Math.max(0, Math.min(count - 1, model.currentIndex));
            final int lineRgb = model.lineRgb & 0xFFFFFF;

            // flip（行进方向朝左）时整条线要“镜像读”：列 0 显示的是数据里的最后一站。
            // 这样终点箭头、当前站、换乘牌、站名永远落在同一个站上（v3 修正：v2 只镜像了
            // 当前站和换乘牌，站名没镜像，三者会错位，终点也会指错站）。
            final String[] names = new String[count];
            final String[] namesEn = new String[count];
            final Interchange[] swaps = new Interchange[count];
            for (int i = 0; i < count; i++) {
                final int d = flip ? count - 1 - i : i;
                names[i] = model.names[d];
                namesEn[i] = model.namesEn != null && d < model.namesEn.length ? model.namesEn[d] : "";
                swaps[i] = dataInterchange(model, d);
            }
            final int currentIndex = flip ? count - 1 - current : current;

            // ---- 白底条带：铺满整张图（v3：不再留上下透明缝）----
            final int bandH = Math.max(8, Math.round(height * BAND_RATIO));
            final int bandTop = (height - bandH) / 2;
            final int bandBottom = bandTop + bandH;
            final int lineW = Math.max(4, Math.round(bandH * LINE_RATIO));
            final boolean namesBelow = model.namesBelow;
            // 45° 站名在线上方 → 线压到条带下部；正排站名在下方 → 线抬高。
            final int lineY = bandTop + Math.round(bandH * (namesBelow ? 0.30f : 0.68f));

            g.setColor(Color.WHITE);
            g.fillRect(0, bandTop, width, bandH);

            // ---- 左侧线路号色块 ----
            final String number = model.lineNumber == null ? "" : model.lineNumber;
            final int badgeSide = Math.round(bandH * 0.46f);
            final int badgeX = Math.max(2, Math.round(bandH * 0.09f));
            final int badgeY = lineY - badgeSide / 2;
            final int gapAfterBadge = Math.max(4, Math.round(bandH * 0.07f));
            int contentX = badgeX + badgeSide + gapAfterBadge;
            if (number.isEmpty()) {
                contentX = badgeX;
            }

            // ---- 站点几何 ----
            // 直径上限同时受“条带高度”和“站距”限制，而站距又取决于终点圆要占的边距，
            // 所以先按纯高度算一遍，再用真正的站距修正一次。
            final int sidePad = Math.max(3, Math.round(bandH * 0.045f));
            final int termRGuess = Math.round(bandH * CIRCLE_RATIO * TERMINAL_SCALE / 2f);
            final float stepGuess = (float) Math.max(1, width - sidePad - termRGuess - contentX - termRGuess)
                    / (count - 1);
            final float circle = Math.max(bandH * 0.09f,
                    Math.min(bandH * CIRCLE_RATIO, stepGuess * 0.62f));
            final int termR = Math.round(circle * TERMINAL_SCALE / 2f);
            final int edgePad = sidePad + termR;
            final int xFirst = contentX + termR;
            final int xLast = Math.max(xFirst + 1, width - edgePad);
            final float step = (float) (xLast - xFirst) / (count - 1);
            final int[] xs = new int[count];
            for (int i = 0; i < count; i++) {
                xs[i] = Math.round(xFirst + step * i);
            }

            // ---- 主线 ----
            g.setColor(new Color(lineRgb));
            g.fillRect(xFirst, lineY - lineW / 2, Math.max(1, xLast - xFirst), lineW);

            // ---- 终点三角箭头（v3：画在终点圆环内侧、靠线路那一侧，尖端顶到圆环上）----
            final int terminalIndex = flip ? 0 : count - 1;
            final int termArrowLen = Math.max(8, Math.round(lineW * 3.0f));
            final int termArrowHalf = Math.max(5, Math.round(lineW * 1.0f));
            drawTerminalArrow(g, flip, xs[terminalIndex], lineY, termR, termArrowLen, termArrowHalf);

            // ---- 站点标记 ----
            final int plateFloorY = bandBottom - Math.max(2, Math.round(bandH * 0.012f));
            int swapDrawn = 0;
            int plateBottomMax = 0;
            for (int i = 0; i < count; i++) {
                if (i == currentIndex) {
                    continue;
                }
                final Interchange swap = swaps[i];
                final boolean terminal = i == terminalIndex;
                if (swap != null) {
                    final int[] box = new int[2];
                    if (drawInterchange(g, xs[i], lineY, lineRgb, swap, bandH, step, width, plateFloorY, box)) {
                        swapDrawn++;
                        plateBottomMax = Math.max(plateBottomMax, box[1]);
                    }
                } else if (terminal) {
                    drawCurrentDot(g, xs[i], lineY, circle * TERMINAL_SCALE, lineRgb, 0f);
                } else {
                    drawDot(g, xs[i], lineY, circle, lineRgb);
                }
            }
            final Interchange currentSwap = swaps[currentIndex];
            final boolean currentIsTerminal = currentIndex == terminalIndex;
            if (currentSwap != null) {
                final int[] box = new int[2];
                if (drawInterchange(g, xs[currentIndex], lineY, lineRgb, currentSwap, bandH, step, width,
                        plateFloorY, box)) {
                    swapDrawn++;
                    plateBottomMax = Math.max(plateBottomMax, box[1]);
                }
            } else {
                drawCurrentDot(g, xs[currentIndex], lineY,
                        circle * (currentIsTerminal ? TERMINAL_SCALE : CURRENT_SCALE), lineRgb, 0.15f);
            }
            lastInterchangeCount = swapDrawn;

            // 正排站名（namesBelow）时要把换乘牌的位置让出来，否则站名会压在牌子上。
            final float belowExtra = namesBelow && plateBottomMax > 0
                    ? Math.max(0f, plateBottomMax - (lineY + circle * 0.5f)) : 0f;
            final float nameSize = drawNames(g, names, namesEn, xs, currentIndex, lineRgb, bandTop, bandBottom,
                    lineY, circle, step, namesBelow, width, contentX, belowExtra);

            // ---- 线路号色块（最后画，永远不被站名压住）----
            if (!number.isEmpty()) {
                drawBadge(g, number, lineRgb, badgeX, badgeY, badgeSide);
            }

            lastBandH = bandH;
            lastLineW = lineW;
            lastCircle = Math.round(circle);
            lastCurrent = Math.round(circle * CURRENT_SCALE);
            lastNameSize = Math.round(nameSize);
            lastTerminalRing = termR * 2;
            lastTerminalArrow = termArrowLen;
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 线路号色块：圆角矩形 + 对比色数字（亮色线路黑字，深色线路白字）。 */
    private static void drawBadge(final Graphics2D g, final String number, final int lineRgb,
                                  final int x, final int y, final int side) {
        g.setColor(new Color(lineRgb));
        g.fill(new RoundRectangle2D.Float(x, y, side, side, side * 0.22f, side * 0.22f));
        g.setColor(new Color(contrastText(lineRgb), true));
        final float size = side * 0.62f;
        final Font font = font().deriveFont(Font.BOLD, size);
        g.setFont(font);
        final int textW = g.getFontMetrics(font).stringWidth(number);
        final int ascent = g.getFontMetrics(font).getAscent();
        g.drawString(number, x + (side - textW) / 2f, y + side / 2f + ascent / 2f - size * 0.08f);
    }

    /**
     * 终点三角箭头：<b>实心三角，画在终点圆环内侧靠线路的那一边，尖端顶在圆环外缘上</b>
     * （v3 修正 —— v2 是画在线端外侧往外指）。
     */
    private static void drawTerminalArrow(final Graphics2D g, final boolean flip, final int cx, final int lineY,
                                          final int ringR, final int len, final int half) {
        if (ringR <= 0 || len <= 0) {
            return;
        }
        if (flip) {
            // 终点在左端：尖端在圆的右侧（线路侧），指向左。
            final int tip = cx + ringR;
            g.fillPolygon(new int[]{tip, tip + len, tip + len}, new int[]{lineY, lineY - half, lineY + half}, 3);
        } else {
            // 终点在右端：尖端在圆的左侧，指向右。
            final int tip = cx - ringR;
            g.fillPolygon(new int[]{tip, tip - len, tip - len}, new int[]{lineY, lineY - half, lineY + half}, 3);
        }
    }

    /**
     * 普通站点：<b>白心空心圆环</b>，环 = 线路色，环宽约直径的 15–18%。
     * 没有实心点、没有内环、没有箭头（按实拍纠正）。
     */
    private static void drawDot(final Graphics2D g, final int cx, final int cy, final float size, final int lineRgb) {
        ring(g, cx, cy, size, lineRgb, 0f, 0f);
    }

    /**
     * 大圆环（当前站 / 终点站）：白心 + 粗线路色环，{@code innerStroke > 0} 时再补一圈细内环
     * （只有当前站有 —— 终点站就是一个干净的大环）。
     */
    private static void drawCurrentDot(final Graphics2D g, final int cx, final int cy, final float size,
                                       final int lineRgb, final float innerStroke) {
        ring(g, cx, cy, size, lineRgb, innerStroke, INNER_RATIO);
    }

    private static void ring(final Graphics2D g, final int cx, final int cy, final float size, final int lineRgb,
                             final float innerStroke, final float innerRatio) {
        final int ringRgb = isLight(lineRgb) ? darken(lineRgb, 0.72) : lineRgb;
        final int d = Math.max(4, Math.round(size));
        final int left = cx - d / 2;
        final int top = cy - d / 2;
        g.setColor(Color.WHITE);
        g.fillOval(left, top, d, d);
        g.setColor(new Color(ringRgb));
        g.setStroke(new BasicStroke(Math.max(1.2f, d * RING_RATIO), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.drawOval(left, top, d - 1, d - 1);
        if (innerStroke > 0f && innerRatio > 0f) {
            final float inner = d * innerRatio;
            final int innerLeft = Math.round(cx - inner / 2f);
            final int innerTop = Math.round(cy - inner / 2f);
            g.setStroke(new BasicStroke(Math.max(1f, d * innerStroke), BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_ROUND));
            g.drawOval(innerLeft, innerTop, Math.round(inner) - 1, Math.round(inner) - 1);
        }
        g.setStroke(new BasicStroke(1f));
    }

    /** 数据下标 {@code dataIndex} 的换乘信息；不是换乘站就 null。 */
    private static Interchange dataInterchange(final Model model, final int dataIndex) {
        if (model.interchanges == null || model.interchanges.length == 0) {
            return null;
        }
        for (final Interchange ic : model.interchanges) {
            if (ic != null && ic.stationIndex == dataIndex) {
                return ic;
            }
        }
        return null;
    }

    /**
     * 换乘站（v3 版式）：
     * <ul>
     *   <li><b>追逐箭头环</b>：一个环被水平切成两半 —— <b>上半 = 当前线路色</b>、
     *       <b>下半 = 换乘线路色</b>，两半共用同一个圆心 / 半径 / 厚度，只是分色，
     *       看起来是一笔连成的环，不是两个独立图形；</li>
     *   <li>两段弧各带一个箭头尖（上段尖端在下、下段尖端在上，环形追逐）；</li>
     *   <li>下半的环底伸出一条<b>换乘线路色</b>的锥形垂尾，指向正下方的线路牌；</li>
     *   <li>线路牌<b>始终挂在图标正下方、水平居中</b>（v3 修正：终点站不再被推到侧面）。</li>
     * </ul>
     *
     * @param box 输出 {牌上沿, 牌下沿}，站名排版要避让
     * @return 真的把牌子也画出来了（false = 连最小号的牌子都放不下，此时只有环 + 垂尾）
     */
    private static boolean drawInterchange(final Graphics2D g, final int cx, final int lineY, final int lineRgb,
                                           final Interchange swap, final int bandH, final float step,
                                           final int width, final int floorY, final int[] box) {
        final int swapRgb = swap.rgb & 0xFFFFFF;
        final int ringD = Math.max(14, Math.round(bandH * IX_RING_RATIO));
        final float rOut = ringD / 2f;
        final float thick = ringD * IX_RING_THICK;
        final float rIn = Math.max(1f, rOut - thick);
        final int gap = Math.max(3, Math.round(bandH * IX_PLATE_GAP_RATIO));

        // ---- 线路牌几何（先算，垂尾要指向它）----
        int plateH = Math.max(10, Math.round(bandH * IX_PLATE_H_RATIO));
        int plateTop = lineY + Math.round(rOut) + gap;
        if (plateTop + plateH > floorY) {
            plateH = Math.max(10, floorY - plateTop);
        }
        // 始终居中：牌宽先按“到图片最近边界的 2 倍”夹住，再按站距夹住。
        final int maxByEdge = Math.max(18, 2 * (Math.min(cx, width - cx) - 2));
        final int maxByStep = Math.max(18, Math.round(step * 1.50f));
        final int maxW = Math.max(18, Math.min(maxByEdge, maxByStep));

        final String cn = plateCjk(swap);
        final String en = plateEn(swap);
        float cjkSize = 0f;
        float enSize = 0f;
        int cjkW = 0;
        int enW = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            cjkSize = plateH * (0.46f - 0.05f * attempt);
            enSize = cjkSize * 0.62f;
            final Font cjkFont = font().deriveFont(Font.BOLD, cjkSize);
            final Font enFont = font().deriveFont(Font.BOLD, enSize);
            cjkW = cn.isEmpty() ? 0 : g.getFontMetrics(cjkFont).stringWidth(cn);
            enW = en.isEmpty() ? 0 : g.getFontMetrics(enFont).stringWidth(en);
            final int need = Math.max(cjkW, enW) + Math.round(plateH * IX_PLATE_PAD_RATIO * 2f);
            if (need <= maxW || cjkSize <= 7f) {
                break;
            }
        }
        if (cn.isEmpty() && en.isEmpty()) {
            return false;
        }
        final int padX = Math.max(3, Math.round(plateH * IX_PLATE_PAD_RATIO));
        final int plateW = Math.max(18, Math.min(maxW, Math.max(cjkW, enW) + padX * 2));
        final int px = cx - plateW / 2;   // 永远以图标中心为准，不为了塞进画面而左右平移

        // ---- 追逐箭头环：先下半（换乘线路色），再上半（当前线路色），共圆心共半径 ----
        final int ringRgb = isLight(lineRgb) ? darken(lineRgb, 0.72) : lineRgb;
        final float cy = lineY;

        // 环内白底（在线上也保持“空心”）
        g.setColor(Color.WHITE);
        g.fillOval(Math.round(cx - rOut), Math.round(cy - rOut), ringD, ringD);

        // 下半：175° → 340°，末端箭头尖朝右上（换乘线路色）
        g.setColor(new Color(swapRgb));
        g.fill(annularSector(cx, cy, rOut, rIn, 175f, 340f));
        arrowHead(g, cx, cy, rOut, rIn, 340f, swapRgb);

        // 上半：20° → 150°，末端箭头尖朝左下（当前线路色）
        g.setColor(new Color(ringRgb));
        g.fill(annularSector(cx, cy, rOut, rIn, 20f, 150f));
        arrowHead(g, cx, cy, rOut, rIn, 150f, ringRgb);

        // ---- 垂尾：从环底（内缘）收到牌子上方的一个尖，和下半环同色 → 连成一体 ----
        final float tailW = ringD * IX_TAIL_W;
        final float y0 = cy + rIn * 0.94f;
        final float tipY = plateTop - Math.max(1f, gap * 0.30f);
        if (tipY > y0) {
            g.setColor(new Color(swapRgb));
            g.fillPolygon(
                    new int[]{Math.round(cx - tailW / 2f), Math.round(cx + tailW / 2f), cx},
                    new int[]{Math.round(y0), Math.round(y0), Math.round(tipY)}, 3);
            // 垂尾与环之间再补一小段矩形，保证接缝处没有白缝
            g.fillRect(Math.round(cx - tailW / 2f), Math.round(cy + rOut - thick * 0.45f),
                    Math.max(1, Math.round(tailW)), Math.max(1, Math.round(y0 - (cy + rOut - thick * 0.45f))));
        }

        // ---- 线路牌：换乘线路色圆角矩形 + 两行居中文字（中文 / Line N）----
        if (plateW <= 0 || plateH <= 0) {
            return false;
        }
        g.setColor(new Color(swapRgb));
        g.fill(new RoundRectangle2D.Float(px, plateTop, plateW, plateH,
                plateH * 0.34f, plateH * 0.34f));
        g.setColor(new Color(contrastText(swapRgb), true));
        if (!cn.isEmpty()) {
            final Font cjkFont = font().deriveFont(Font.BOLD, cjkSize);
            g.setFont(cjkFont);
            final FontMetrics fm = g.getFontMetrics(cjkFont);
            final String text = cjkW > plateW - 2 * padX ? fitText(g, cjkFont, cn, plateW - 2 * padX) : cn;
            final int tw = g.getFontMetrics(cjkFont).stringWidth(text);
            final float baseline = plateTop + plateH * (en.isEmpty() ? 0.5f : 0.44f)
                    + (fm.getAscent() - fm.getDescent()) / 2f - plateH * 0.04f;
            g.drawString(text, px + (plateW - tw) / 2f, baseline);
        }
        if (!en.isEmpty()) {
            final float size = cn.isEmpty() ? plateH * 0.50f : enSize;
            final Font enFont = font().deriveFont(Font.BOLD, size);
            g.setFont(enFont);
            final FontMetrics fm = g.getFontMetrics(enFont);
            final String text = enW > plateW - 2 * padX ? fitText(g, enFont, en, plateW - 2 * padX) : en;
            final int tw = g.getFontMetrics(enFont).stringWidth(text);
            g.drawString(text, px + (plateW - tw) / 2f, plateTop + plateH * 0.86f
                    + (fm.getAscent() - fm.getDescent()) / 2f - plateH * 0.06f);
        }

        box[0] = plateTop;
        box[1] = plateTop + plateH;
        lastSwapHead = ringD;
        lastSwapPlate = plateW;
        return true;
    }

    /** 把文字缩到给定宽度内（只作为极端情况下的兜底）。 */
    private static String fitText(final Graphics2D g, final Font font, final String text, final int maxWidth) {
        if (maxWidth <= 4 || text.isEmpty()) {
            return text;
        }
        float size = font.getSize2D();
        for (int i = 0; i < 8 && size > 6f; i++) {
            size *= 0.9f;
            if (g.getFontMetrics(font.deriveFont(size)).stringWidth(text) <= maxWidth) {
                break;
            }
        }
        final Font smaller = font.deriveFont(size);
        g.setFont(smaller);
        return text;
    }

    /**
     * 环形扇区（外弧 a1→a2 + 内弧 a2→a1），角度是数学角（0° = 正右，90° = 正上），
     * 屏幕坐标 y 向下，所以纵向取反。
     */
    private static Path2D.Float annularSector(final float cx, final float cy, final float rOut, final float rIn,
                                              final float a1, final float a2) {
        final int steps = 28;
        final Path2D.Float path = new Path2D.Float();
        for (int i = 0; i <= steps; i++) {
            final double a = Math.toRadians(a1 + (a2 - a1) * i / steps);
            final float x = (float) (cx + rOut * Math.cos(a));
            final float y = (float) (cy - rOut * Math.sin(a));
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        for (int i = steps; i >= 0; i--) {
            final double a = Math.toRadians(a1 + (a2 - a1) * i / steps);
            final float x = (float) (cx + rIn * Math.cos(a));
            final float y = (float) (cy - rIn * Math.sin(a));
            path.lineTo(x, y);
        }
        path.closePath();
        return path;
    }

    /** 弧端点的箭头尖：沿弧的“前进方向”（逆时针）伸出去。 */
    private static void arrowHead(final Graphics2D g, final float cx, final float cy, final float rOut, final float rIn,
                                  final float angleDeg, final int rgb) {
        final double a = Math.toRadians(angleDeg);
        final float rMid = (rOut + rIn) / 2f;
        final float px = (float) (cx + rMid * Math.cos(a));
        final float py = (float) (cy - rMid * Math.sin(a));
        // 前进方向（角度增大 = 逆时针）的切向
        final float tx = (float) -Math.sin(a);
        final float ty = (float) -Math.cos(a);
        final float nx = -ty;
        final float ny = tx;
        final float thick = rOut - rIn;
        final float headLen = thick * IX_HEAD_LEN;
        final float headW = thick * IX_HEAD_W / 2f;
        g.setColor(new Color(rgb));
        g.fillPolygon(
                new int[]{Math.round(px + tx * headLen), Math.round(px + nx * headW), Math.round(px - nx * headW)},
                new int[]{Math.round(py + ty * headLen), Math.round(py + ny * headW), Math.round(py - ny * headW)},
                3);
    }

    /** 线路牌第一行：{@code N号线}（拿不到线路号时用换乘线的中文名）。 */
    private static String plateCjk(final Interchange swap) {
        final String number = swap.number == null ? "" : swap.number.trim();
        if (!number.isEmpty()) {
            return number + "号线";
        }
        return swap.name == null ? "" : swap.name.trim();
    }

    /** 线路牌第二行：{@code Line N}。 */
    private static String plateEn(final Interchange swap) {
        final String number = swap.number == null ? "" : swap.number.trim();
        return number.isEmpty() ? "" : "Line " + number;
    }

    /**
     * 站名：默认 45° 斜排在线条上方（实拍版式），中文一行 + 英文小字一行；
     * {@code namesBelow} 时正排到线条下方（英文贴在中文下面）。
     *
     * <p>超长线路：先按实测字宽反解字号（45° 投影不超过站距 92%，且不超过可用竖直空间），
     * 低于 {@link #NAME_FLOOR} 就隔一个画一个（stride 2/3/4），还不行就不画站名 ——
     * <b>不换行、不重叠</b>。
     */
    private static float drawNames(final Graphics2D g, final String[] names, final String[] ens, final int[] xs,
                                   final int currentIndex, final int lineRgb, final int bandTop,
                                   final int bandBottom, final int lineY, final float circle, final float step,
                                   final boolean namesBelow, final int width, final int firstTextX,
                                   final float belowExtra) {
        final int count = names.length;
        final float vertAvail = namesBelow
                ? bandBottom - lineY - circle * 0.5f - belowExtra - 3f
                // 斜排时文字沿 45° 往上走，顶部留一点白边。
                : lineY - circle * 0.5f - bandTop - Math.max(3f, (bandBottom - bandTop) * 0.03f);
        if (vertAvail < NAME_FLOOR) {
            return 0f;
        }

        int stride = 1;
        float size = 0f;
        for (int attempt = 0; attempt < 4; attempt++) {
            size = fitNameSize(names, step * stride, vertAvail, namesBelow,
                    Math.round((bandBottom - bandTop) * NAME_RATIO));
            if (size >= NAME_FLOOR) {
                break;
            }
            stride++;
        }
        if (size < NAME_FLOOR) {
            return 0f;
        }

        final Font base = font();
        final Font plain = base.deriveFont(Font.BOLD, size);
        final Font bold = base.deriveFont(Font.BOLD, size * 1.06f);
        // 英文行：字号最多是中文的 EN_RATIO，且必须塞进同一段站距；塞不下就不写英文（整条线统一）。
        float enScale = 0f;
        if (ens != null && hasEnglish(ens)) {
            final Font refEn = base.deriveFont(Font.PLAIN, 100f);
            int maxEn = 0;
            for (final String en : ens) {
                if (en != null && !en.isEmpty()) {
                    maxEn = Math.max(maxEn, g.getFontMetrics(refEn).stringWidth(en));
                }
            }
            if (maxEn > 0) {
                final double limit = step * stride * NAME_GAP;
                final double maxEnSize = limit / (maxEn / 100.0 * DIAG);
                enScale = (float) Math.min(EN_RATIO, maxEnSize / Math.max(1f, size));
                if (enScale < 0.30f) {
                    enScale = 0f;
                }
            }
        }
        final Font enFont = base.deriveFont(Font.PLAIN, Math.max(1f, size * enScale));
        final Shape clip = namesBelow
                ? new Rectangle(Math.max(0, firstTextX), Math.round(lineY + circle * 0.5f) + 1,
                        Math.max(1, width - firstTextX), Math.max(1, bandBottom - lineY))
                : new Rectangle(Math.max(0, firstTextX), bandTop, Math.max(1, width - firstTextX),
                        Math.max(1, lineY - bandTop));

        final Shape oldClip = g.getClip();
        g.setClip(clip);
        for (int i = 0; i < count; i += stride) {
            final String label = names[i];
            if (label == null || label.isEmpty()) {
                continue;
            }
            final String en = enScale > 0f && ens != null && i < ens.length ? ens[i] : null;
            final boolean isCurrent = i == currentIndex;
            final int textRgb = isCurrent
                    ? currentNameText(lineRgb)
                    : (namesBelow ? NAME_GREY : TEXT_DARK);
            g.setFont(isCurrent ? bold : plain);
            // 颜色常量是 0xRRGGBB，没有 alpha 位；new Color(rgb, true) 会把 alpha 当成 0（= 全透明），
            // 所以这里必须显式补上 0xFF000000。
            g.setColor(new Color(0xFF000000 | textRgb, true));
            if (namesBelow) {
                final int textW = g.getFontMetrics(g.getFont()).stringWidth(label);
                int x = Math.round(xs[i] - textW / 2f);
                x = Math.max(x, Math.min(Math.round(xs[i]), firstTextX));
                x = Math.min(x, width - textW - 2);
                final float baseline = lineY + circle * 0.5f + belowExtra
                        + g.getFontMetrics().getAscent() + 3f;
                g.drawString(label, x, baseline);
                if (en != null && !en.isEmpty()) {
                    g.setFont(enFont);
                    g.setColor(new Color(0xFF000000 | (isCurrent ? textRgb : TEXT_EN), true));
                    final int enW = g.getFontMetrics(enFont).stringWidth(en);
                    g.drawString(en, Math.min(width - enW - 2, Math.max(firstTextX, xs[i] - enW / 2f)),
                            baseline + size * EN_RATIO * 1.25f);
                }
            } else {
                final int textW = g.getFontMetrics(g.getFont()).stringWidth(label);
                // 45° 下的水平占用 = max(中文宽, 英文宽) x cos45；英文画在第二行，还要再加一个行距的
                // 斜向投影，否则贴右沿时英文尾部会出画。最后留 3 px 余量（字形右侧还有 bearing）。
                final int lineStepPx = Math.round(size * enScale * 1.30f);
                final int projected = (int) Math.ceil(Math.max(textW, enWidth(g, enFont, en)) * DIAG)
                        + (en == null ? 0 : (int) Math.ceil(lineStepPx * DIAG)) + 4;
                int x = xs[i];
                if (x + projected > width - 6) {
                    x = Math.max(firstTextX, width - 6 - projected);
                }
                drawRotatedName(g, label, en, x, lineY - Math.round(circle * 0.5f) - 4,
                        g.getFont(), enFont, size * EN_RATIO * 1.30f,
                        new Color(0xFF000000 | (isCurrent ? textRgb : TEXT_EN), true));
            }
        }
        g.setClip(oldClip);
        return size;
    }

    /** 英文行在 45° 下的水平占用（没英文就是 0）。 */
    private static float enWidth(final Graphics2D g, final Font enFont, final String en) {
        return en == null || en.isEmpty() ? 0f : g.getFontMetrics(enFont).stringWidth(en);
    }

    /** 这一排站名里有没有英文。 */
    private static boolean hasEnglish(final String[] ens) {
        for (final String en : ens) {
            if (en != null && !en.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 45° 斜排两行：中文（粗）+ 英文（细，贴在中文行下方）。 */
    private static void drawRotatedName(final Graphics2D g, final String cjk, final String en, final int x,
                                        final int baseY, final Font cjkFont, final Font enFont,
                                        final float lineStep, final Color enColor) {
        final AffineTransform old = g.getTransform();
        g.rotate(-Math.PI / 4, x, baseY);
        g.setFont(cjkFont);
        g.drawString(cjk, x, baseY);
        if (en != null && !en.isEmpty()) {
            g.setFont(enFont);
            g.setColor(enColor);
            g.drawString(en, x, baseY + lineStep);
        }
        g.setTransform(old);
    }

    /** 按实测字宽反解字号（像素）：相邻不重叠 + 不越出条带 + 上限 {@link #NAME_RATIO}。 */
    private static float fitNameSize(final String[] names, final float usableStep,
                                     final float vertAvail, final boolean namesBelow, final int maxSize) {
        final Font ref = font().deriveFont(Font.BOLD, 100f);
        final FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        double maxWidth = 0;
        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            if (name != null && !name.isEmpty()) {
                maxWidth = Math.max(maxWidth, ref.getStringBounds(name, frc).getWidth());
            }
        }
        if (maxWidth <= 0) {
            return 0f;
        }
        final double footprint = namesBelow ? maxWidth : maxWidth * DIAG;
        final double limit = usableStep * NAME_GAP;
        float size = (float) (100.0 * limit / footprint);
        size = Math.min(size, (float) (100.0 * vertAvail / footprint));
        size = Math.min(size, Math.max(NAME_FLOOR, maxSize));
        return Math.max(0f, size);
    }

    // ------------------------------------------------------------------------------------------
    // 字体
    // ------------------------------------------------------------------------------------------

    /**
     * 黑体：<b>SimHei / 微软雅黑</b> 优先（实拍是笔画较粗的黑体，Noto Sans CJK Regular 太细）。
     * 逐个候选检查能不能显示 {@code 厦 / 线}，找不到再退回系统里任意能显示中文的字体。
     */
    public static Font font() {
        Font font = cjkFont;
        if (font != null) {
            return font;
        }
        synchronized (XiamenStripPainter.class) {
            font = cjkFont;
            if (font == null) {
                font = resolveFont();
                cjkFont = font;
            }
        }
        return font;
    }

    private static Font resolveFont() {
        try {
            final GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Font best = null;
            int bestScore = Integer.MAX_VALUE;
            for (final Font f : env.getAllFonts()) {
                if (!f.canDisplay('厦') || !f.canDisplay('线')) {
                    continue;
                }
                // 家族名的本地化很坑（SimHei 在中文 Windows 上叫“黑体”），所以用 PS 名 / 英文族名判断。
                final String key = (f.getPSName() + "|" + f.getFontName(Locale.ENGLISH) + "|"
                        + f.getFamily(Locale.ENGLISH)).toLowerCase(Locale.ROOT);
                int score;
                if (key.contains("simhei")) {
                    score = 0;
                } else if (key.contains("yahei")) {
                    score = key.contains("light") ? 4 : 1;
                } else if (key.contains("noto sans sc") || key.contains("source han sans")
                        || key.contains("noto sans cjk")) {
                    score = 3;
                } else if (key.contains("simsun") || key.contains("jhenghei")) {
                    score = 5;
                } else {
                    score = 8;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            if (best != null) {
                lastFontName = best.getPSName();
                return best.deriveFont(Font.BOLD, 100f);
            }
        } catch (Throwable ignored) {
            lastFontName = "logical";
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 100);
    }

    /** 站名里 “中文|English” 只取中文段。 */
    public static String cjkPart(final String raw) {
        final String text = raw == null ? "" : raw.replace("||", "|").trim();
        if (text.isEmpty()) {
            return "";
        }
        final int cut = text.indexOf('|');
        if (cut < 0) {
            return text;
        }
        final String head = text.substring(0, cut).trim();
        return head.isEmpty() ? text.substring(cut + 1).trim() : head;
    }

    /** 站名里 “中文|English” 只取英文段（没有就是空串）。 */
    public static String enPart(final String raw) {
        final String text = raw == null ? "" : raw.replace("||", "|").trim();
        final int cut = text.indexOf('|');
        if (cut < 0) {
            return "";
        }
        return text.substring(cut + 1).trim();
    }
}
