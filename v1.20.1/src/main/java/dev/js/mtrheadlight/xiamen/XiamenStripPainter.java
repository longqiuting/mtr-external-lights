// 线路条带的纯 AWT 绘制（不引用任何 MC / MTR 类型，可离线单独跑出 PNG 预览）。
package dev.js.mtrheadlight.xiamen;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 厦门实拍样式的线路条带，<b>纯 Java2D</b>（这个类刻意不 import 任何 {@code net.minecraft} /
 * {@code mtr} 类型，所以能用 {@code javac} 单独编译，直接吐出 PNG 看版式，不用开游戏）。
 *
 * <p>版式照 {@code live-pack/assets/mtr/xmlcd/draw.js} 的 LCD 全图（{@code drawFullLineMap} /
 * {@code drawStationMarker}）搬过来，比例全部相对“白底条带高度 bandH”：</p>
 * <pre>
 *   LCD                                              本类
 *   --------------------------------------------     ------------------------------------------
 *   线路带高 lcdH(0.05)                              lineW  = bandH * 0.09
 *   圆点 min(lcdH(0.125), step*0.75)                 circle = min(bandH*0.22, step*0.75)
 *   圆环描边 size*0.15 / 内环 0.58*size              完全相同
 *   站名 45° 斜排、在线条上方（rotate 1.75π）        相同（namesBelow=true 时正排到线下）
 *   字号 min(lcdH(0.125), step*1.35/字数, …)         按实测字宽反解（fitNameSize）
 *   已过站灰 / 区间灰                                不用灰：实拍吊板整条线都是线路色
 *   当前站橙底 #F5A623                               当前站 = 加大的空心圆 + 中心实心
 *   区间方向小箭头                                   只留终点箭头
 * </pre>
 *
 * <p>尺寸（IMG_HEIGHT=320、线宽 9% 等）见下面常量；一张图覆盖整个面板，所以白底只占中间 60%，
 * 上下透出面板原来的背景 —— <b>没换背景</b>。</p>
 */
public final class XiamenStripPainter {

    /** 纹理基准高度（像素）。宽度 = 高度 x 面板长宽比。 */
    public static final int IMG_HEIGHT = 320;

    /** 纹理宽度上限。 */
    public static final int MAX_WIDTH = 4096;

    /** 白底条带占面板高度：60%（要求 55–65%）。 */
    public static final float BAND_RATIO = 0.60f;

    /** 主线路宽：条带高度的 9%（要求 8–10%）。 */
    public static final float LINE_RATIO = 0.09f;

    /** 普通站圆点直径上限：条带高度的 22%。 */
    public static final float CIRCLE_RATIO = 0.22f;

    /** 当前站圆点相对普通点的倍数。 */
    public static final float CURRENT_SCALE = 1.35f;

    /** 圆环描边 / 内环（LCD drawStationMarker 的 0.15 / 0.58）。 */
    public static final float RING_RATIO = 0.15f;
    public static final float INNER_RATIO = 0.58f;

    /** 站名字号上限：条带高度的 12.5%（LCD 的 lcdH(0.125)）。 */
    public static final float NAME_RATIO = 0.125f;

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

    /** 站名正排时，非当前站用的 65% 灰。 */
    public static final int NAME_GREY = 0x9E9E9E;

    /** 深色文字（近黑：小字号下纯黑发毛）。 */
    public static final int TEXT_DARK = 0x1A1A1A;

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
        /** 当前站在 {@link #names} 里的下标。 */
        public int currentIndex;
        /** 线路色 {@code 0xRRGGBB}。 */
        public int lineRgb;
        /** 线路号（左侧色块里的字，空串 = 不画色块）。 */
        public String lineNumber;
        /** true = 行进方向朝左（MTR 的 arrowDirection == 2）。 */
        public boolean flip;
        /** true = 站名正排到线下方，false = 45° 斜排到线上方（默认，LCD 版式）。 */
        public boolean namesBelow;
        /** 面板宽/高。 */
        public float aspectRatio;
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
            final int current = Math.max(0, Math.min(count - 1, model.currentIndex));
            final int currentIndex = model.flip ? count - 1 - current : current;
            final int lineRgb = model.lineRgb & 0xFFFFFF;

            final int bandH = Math.round(height * BAND_RATIO);
            final int bandTop = (height - bandH) / 2;
            final int bandBottom = bandTop + bandH;
            final int lineW = Math.max(3, Math.round(bandH * LINE_RATIO));
            final boolean namesBelow = model.namesBelow;
            // 斜排站名在线条上方，所以线压到条带下部（LCD：线在 0.65、站名在 0.55）。
            final int lineY = bandTop + Math.round(bandH * (namesBelow ? 0.34f : 0.68f));

            // ---- 白底长条 ----
            g.setColor(Color.WHITE);
            g.fillRect(0, bandTop, width, bandH);

            // ---- 左侧线路号色块 ----
            final String number = model.lineNumber == null ? "" : model.lineNumber;
            final int badgeSide = Math.round(bandH * 0.46f);
            final int badgeX = Math.max(2, Math.round(bandH * 0.09f));
            final int badgeY = lineY - badgeSide / 2;
            int contentX = badgeX + badgeSide + Math.max(4, Math.round(bandH * 0.07f));
            if (number.isEmpty()) {
                contentX = badgeX;
            } else {
                drawBadge(g, number, lineRgb, badgeX, badgeY, badgeSide);
            }

            // ---- 主线 ----
            final int arrowLen = Math.max(6, Math.round(lineW * 2.4f));
            final int arrowHalf = Math.max(4, Math.round(lineW * 1.5f));
            final int padRight = Math.max(3, Math.round(bandH * 0.06f));
            final int xFirst = contentX + (model.flip ? arrowLen : 0);
            final int xLast = Math.max(xFirst + 1, width - padRight - arrowLen);
            final float step = (float) (xLast - xFirst) / (count - 1);

            g.setColor(new Color(lineRgb));
            g.fillRect(xFirst, lineY - lineW / 2, xLast - xFirst, lineW);
            drawArrow(g, model.flip, xFirst, xLast, lineY, arrowLen, arrowHalf);

            // ---- 站点圆点 ----
            final float circle = Math.max(bandH * 0.09f, Math.min(bandH * CIRCLE_RATIO, step * 0.75f));
            final int[] xs = new int[count];
            for (int i = 0; i < count; i++) {
                xs[i] = Math.round(xFirst + step * i);
            }
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < count; i++) {
                if (i != currentIndex) {
                    drawDot(g, xs[i], lineY, circle, lineRgb);
                }
            }
            drawCurrentDot(g, xs[currentIndex], lineY, circle * CURRENT_SCALE, lineRgb);

            // ---- 站名 ----
            final float nameSize = drawNames(g, model.names, xs, currentIndex, lineRgb, bandTop, bandBottom,
                    lineY, circle, step, namesBelow, width, contentX);

            // 记录本次实际用到的像素尺寸（日志 / 报告 / 调参用）。
            lastBandH = bandH;
            lastLineW = lineW;
            lastCircle = Math.round(circle);
            lastCurrent = Math.round(circle * CURRENT_SCALE);
            lastNameSize = Math.round(nameSize);
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

    /** 终点箭头：实心三角，尖端朝行进方向（flip 时贴在线左端外侧、朝左）。 */
    private static void drawArrow(final Graphics2D g, final boolean flip, final int xFirst, final int xLast,
                                  final int lineY, final int arrowLen, final int arrowHalf) {
        if (flip) {
            g.fillPolygon(new int[]{xFirst - arrowLen, xFirst + 1, xFirst + 1},
                    new int[]{lineY, lineY - arrowHalf, lineY + arrowHalf}, 3);
        } else {
            g.fillPolygon(new int[]{xLast + arrowLen, xLast - 1, xLast - 1},
                    new int[]{lineY, lineY - arrowHalf, lineY + arrowHalf}, 3);
        }
    }

    /** 普通站点：白底 + 线路色圆环 + 内环（LCD 的两层圆）。浅色线路的环加深一档，白底上才看得见。 */
    private static void drawDot(final Graphics2D g, final int cx, final int cy, final float size, final int lineRgb) {
        final int ring = isLight(lineRgb) ? darken(lineRgb, 0.72) : lineRgb;
        final float half = size / 2f;
        g.setColor(Color.WHITE);
        g.fillOval(Math.round(cx - half), Math.round(cy - half), Math.round(size), Math.round(size));
        g.setColor(new Color(ring));
        g.setStroke(new BasicStroke(Math.max(1.2f, size * RING_RATIO), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawOval(Math.round(cx - half), Math.round(cy - half), Math.round(size) - 1, Math.round(size) - 1);
        final float inner = size * INNER_RATIO;
        final float innerHalf = inner / 2f;
        g.setStroke(new BasicStroke(Math.max(1f, size * RING_RATIO * 0.72f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawOval(Math.round(cx - innerHalf), Math.round(cy - innerHalf),
                Math.round(inner) - 1, Math.round(inner) - 1);
        g.setStroke(new BasicStroke(1f));
    }

    /** 当前站：加大的空心圆（线路色粗环）+ 中心实心圆点。 */
    private static void drawCurrentDot(final Graphics2D g, final int cx, final int cy, final float size, final int lineRgb) {
        final int ring = isLight(lineRgb) ? darken(lineRgb, 0.72) : lineRgb;
        final float half = size / 2f;
        g.setColor(Color.WHITE);
        g.fillOval(Math.round(cx - half), Math.round(cy - half), Math.round(size), Math.round(size));
        g.setColor(new Color(ring));
        g.setStroke(new BasicStroke(Math.max(2f, size * RING_RATIO * 1.15f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawOval(Math.round(cx - half), Math.round(cy - half), Math.round(size) - 1, Math.round(size) - 1);
        final float core = size * 0.42f;
        final float coreHalf = core / 2f;
        g.fillOval(Math.round(cx - coreHalf), Math.round(cy - coreHalf), Math.round(core), Math.round(core));
        g.setStroke(new BasicStroke(1f));
    }

    /**
     * 站名：默认 45° 斜排在线条上方（LCD 全图版式），{@code namesBelow} 时正排到线条下方。
     *
     * <p>超长线路：先按实测字宽反解字号（45° 投影不超过站距 92%，且不超过可用竖直空间），
     * 低于 {@link #NAME_FLOOR} 就隔一个画一个（stride 2/3/4），还不行就不画站名 ——
     * <b>不换行、不重叠</b>。
     */
    private static float drawNames(final Graphics2D g, final String[] names, final int[] xs, final int currentIndex,
                                  final int lineRgb, final int bandTop, final int bandBottom, final int lineY,
                                  final float circle, final float step, final boolean namesBelow, final int width,
                                  final int firstTextX) {
        final int count = names.length;
        final float vertAvail = namesBelow
                ? bandBottom - lineY - circle * 0.5f - 3f
                // 斜排时文字沿 45° 往上走，顶部留一点白边（否则最长那条贴着条带上沿，看着像被切了）。
                : lineY - circle * 0.5f - bandTop - Math.max(3f, (bandBottom - bandTop) * 0.06f);
        if (vertAvail < NAME_FLOOR) {
            return 0f;
        }

        int stride = 1;
        float size = 0f;
        for (int attempt = 0; attempt < 4; attempt++) {
            size = fitNameSize(names, step * stride, vertAvail, namesBelow);
            if (size >= NAME_FLOOR) {
                break;
            }
            stride++;
        }
        if (size < NAME_FLOOR) {
            return 0f;
        }

        final Font base = font();
        final Font plain = base.deriveFont(Font.PLAIN, size);
        final Font bold = base.deriveFont(Font.BOLD, size * 1.12f);
        final Shape clip = namesBelow
                ? new Rectangle(0, Math.round(lineY + circle * 0.5f) + 1, width, Math.max(1, bandBottom - lineY))
                : new Rectangle(0, bandTop, width, Math.max(1, lineY - bandTop));

        final Shape oldClip = g.getClip();
        g.setClip(clip);
        for (int i = 0; i < count; i += stride) {
            final String label = names[i];
            if (label == null || label.isEmpty()) {
                continue;
            }
            final boolean isCurrent = i == currentIndex;
            g.setFont(isCurrent ? bold : plain);
            // 颜色常量是 0xRRGGBB，没有 alpha 位；new Color(rgb, true) 会把 alpha 当成 0（= 全透明），
            // 所以这里必须显式补上 0xFF000000。
            g.setColor(new Color(0xFF000000 | (isCurrent
                    ? currentNameText(lineRgb)
                    : (namesBelow ? NAME_GREY : TEXT_DARK)), true));
            if (namesBelow) {
                final int textW = g.getFontMetrics(g.getFont()).stringWidth(label);
                // 夹在条带里：左不过是线路号色块的右边，右不过图片右沿（第一/最后一站最容易溢出）。
                int x = Math.round(xs[i] - textW / 2f);
                x = Math.max(x, Math.min(Math.round(xs[i]), firstTextX));
                x = Math.min(x, width - textW - 2);
                g.drawString(label, x, lineY + circle * 0.5f + g.getFontMetrics().getAscent() + 3f);
            } else {
                final int textW = g.getFontMetrics(g.getFont()).stringWidth(label);
                // 最后一站的斜排文字会顶到图片右沿：整体往左挪一点（方向不变，避免和前一站交叉）。
                // 45° 下的水平占用 = 字宽 x cos45，再留 3 px 余量（字形右侧还有 bearing）。
                final int projected = (int) Math.ceil(textW * DIAG) + 3;
                int x = xs[i];
                if (x + projected > width - 6) {
                    x = Math.max(firstTextX, width - 6 - projected);
                }
                drawRotated(g, label, x, lineY - Math.round(circle * 0.5f) - 4, g.getFont());
            }
        }
        g.setClip(oldClip);
        return size;
    }

    /** 按实测字宽反解字号（像素）：相邻不重叠 + 不越出条带 + 上限 {@link #NAME_RATIO}。 */
    private static float fitNameSize(final String[] names, final float usableStep, final float vertAvail,
                                     final boolean namesBelow) {
        final Font ref = font().deriveFont(Font.PLAIN, 100f);
        final FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        double maxWidth = 0;
        for (final String name : names) {
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
        return Math.max(0f, size);
    }

    /** 45° 斜排：基线贴着圆点上沿，文字向右上展开。 */
    private static void drawRotated(final Graphics2D g, final String text, final int x, final int baseY, final Font font) {
        final AffineTransform old = g.getTransform();
        g.setFont(font);
        g.rotate(-Math.PI / 4, x, baseY);
        g.drawString(text, x, baseY);
        g.setTransform(old);
    }

    // ------------------------------------------------------------------------------------------
    // 字体
    // ------------------------------------------------------------------------------------------

    /** 系统里的 CJK 字体（不打包字体文件）；MTR 自己用 Noto Sans/Serif CJK，所以优先同族。 */
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
        final String[] preferred = {
            "Noto Sans CJK SC", "Noto Sans CJK TC", "Noto Sans CJK JP", "Noto Sans SC",
            "Source Han Sans SC", "Source Han Sans CN", "Microsoft YaHei", "Microsoft JhengHei",
            "SimHei", "SimSun", "Noto Serif CJK TC", "PingFang SC", "WenQuanYi Micro Hei",
        };
        try {
            final GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            final Set<String> available = new HashSet<>();
            for (final String name : env.getAvailableFontFamilyNames(Locale.ROOT)) {
                available.add(name);
            }
            for (final String name : preferred) {
                if (!available.contains(name)) {
                    continue;
                }
                final Font candidate = new Font(name, Font.PLAIN, 100);
                if (candidate.canDisplay('厦')) {
                    lastFontName = name;
                    return candidate;
                }
            }
            for (final Font candidate : env.getAllFonts()) {
                if (candidate.canDisplay('厦') && candidate.canDisplay('线')) {
                    lastFontName = candidate.getFontName();
                    return candidate.deriveFont(Font.PLAIN, 100f);
                }
            }
        } catch (Throwable ignored) {
            lastFontName = "logical";
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 100);
    }

    /** 站名里 “中文|English” 只取中文段（斜排放不下两行，MTR 原图也是单行）。 */
    public static String cjkPart(final String raw) {
        if (raw == null) {
            return "";
        }
        final String text = raw.replace("||", "|").trim();
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
}
