// 吊板/玻璃上那块“方向面板”的纯 AWT 绘制（不引用任何 MC / MTR 类型，可离线预览）。
package dev.js.mtrheadlight.xiamen;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * 吊板（{@code RenderPSDTop}）上方、门/玻璃那一格画的方向面板。
 *
 * <h2>为什么不沿用 MTR 那张</h2>
 * <p>MTR 这块面板走 {@code RenderRouteBase#render} 的 {@code RenderType.STATION_NAME} /
 * {@code ARROW} 分支 → {@code RouteMapGenerator#generateDirectionArrow}：左侧一个线路色圆，
 * 圆里写 <b>站台号</b>（{@code Platform#name}），目的地在圆的右边<b>左对齐</b>。实拍吊板既没有
 * 站台号、文字也是居中的，所以这里整块重画：</p>
 * <pre>
 *   [◀]      往 集美软件园北        [▶]
 *            Jimei Software Park North
 * </pre>
 * <p>尺寸以图片高度 h 为基准，宽度 = h × 面板宽高比，最终由 MTR 拉伸到面板面。</p>
 */
public final class XiamenDirectionPanel {

    /** 纹理高度（像素）。宽度 = 高度 × 面板宽高比。 */
    public static final int IMG_HEIGHT = 256;

    /** 纹理宽度上限。 */
    public static final int MAX_WIDTH = 8192;

    /** 左右内边距 / 高度。 */
    public static final float PAD_RATIO = 0.055f;

    /** 方向箭头：高 / 高度，长 / 高度。 */
    public static final float ARROW_H_RATIO = 0.34f;
    public static final float ARROW_L_RATIO = 0.20f;

    /** 中文行字号上限 / 高度。 */
    public static final float CJK_RATIO = 0.46f;

    /** 英文行 / 中文行。 */
    public static final float EN_RATIO = 0.42f;

    /** 深色文字（与条带一致）。 */
    public static final int TEXT_DARK = 0x1A1A1A;

    /** 上一次绘制用到的尺寸（日志 / 报告用）。 */
    public static volatile int lastWidth;
    public static volatile int lastHeight;
    public static volatile int lastCjkSize;
    public static volatile int lastEnSize;

    private XiamenDirectionPanel() {
    }

    /**
     * 画一块方向面板。
     *
     * @param cjk        目的地中文（{@code 集美软件园北}，可空）
     * @param en         目的地英文（{@code Jimei Software Park North}，可空）
     * @param hasLeft    左侧要不要画箭头
     * @param hasRight   右侧要不要画箭头
     * @param showToString 文字前面加 “往”
     * @param aspectRatio 面板 宽/高
     */
    public static BufferedImage paint(final String cjk, final String en, final boolean hasLeft,
                                      final boolean hasRight, final boolean showToString,
                                      final float aspectRatio) {
        if (!(aspectRatio > 0.05f)) {
            return null;
        }
        int height = IMG_HEIGHT;
        int width = Math.max(height, Math.round(height * aspectRatio));
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

            // 白底：和线路条带同一块白板（吊板面板面是白的，实拍如此）。
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            final int pad = Math.max(2, Math.round(height * PAD_RATIO));
            final int arrowH = Math.max(6, Math.round(height * ARROW_H_RATIO));
            final int arrowL = Math.max(4, Math.round(height * ARROW_L_RATIO));

            // ---- 左右方向箭头 ----
            final int textRgb = TEXT_DARK;
            if (hasLeft) {
                drawArrow(g, pad, height / 2, arrowL, arrowH, true, textRgb);
            }
            if (hasRight) {
                drawArrow(g, width - pad, height / 2, arrowL, arrowH, false, textRgb);
            }

            // ---- 文字：中文一行 + 英文一行，整块水平 / 垂直居中 ----
            final String cn = cjk == null ? "" : cjk.trim();
            final String line = showToString && !cn.isEmpty() && !cn.startsWith("往") ? "往" + cn : cn;
            final String enText = en == null ? "" : en.trim();
            if (line.isEmpty() && enText.isEmpty()) {
                return image;
            }

            int left = hasLeft ? pad + arrowL + pad / 2 : pad;
            int right = hasRight ? width - pad - arrowL - pad / 2 : width - pad;
            int avail = Math.max(8, right - left);

            float cjkSize = Math.max(7f, height * CJK_RATIO);
            final Font ref = XiamenStripPainter.font().deriveFont(Font.BOLD, 100f);
            if (!line.isEmpty()) {
                final int w100 = g.getFontMetrics(ref).stringWidth(line);
                if (w100 > 0) {
                    cjkSize = Math.min(cjkSize, 100.0f * avail / w100);
                }
            } else {
                final int w100 = g.getFontMetrics(ref).stringWidth(enText);
                cjkSize = Math.min(cjkSize, 100.0f * avail / Math.max(1, w100) * EN_RATIO);
            }
            cjkSize = Math.max(7f, cjkSize);
            final float enSize = Math.max(6f, cjkSize * EN_RATIO);

            final Font cjkFont = ref.deriveFont(cjkSize);
            final Font enFont = ref.deriveFont(Font.PLAIN, enSize);
            final FontMetrics cjkFm = g.getFontMetrics(cjkFont);
            final FontMetrics enFm = g.getFontMetrics(enFont);
            final int cjkW = line.isEmpty() ? 0 : cjkFm.stringWidth(line);
            final int enW = enText.isEmpty() ? 0 : enFm.stringWidth(enText);
            final int totalH = cjkFm.getHeight() + (enText.isEmpty() ? 0 : enFm.getHeight());
            final int blockTop = (height - totalH) / 2;

            g.setColor(new Color(TEXT_DARK));
            if (!line.isEmpty()) {
                g.setFont(cjkFont);
                g.drawString(line, left + (avail - cjkW) / 2f, blockTop + cjkFm.getAscent());
            }
            if (!enText.isEmpty()) {
                g.setFont(enFont);
                g.drawString(enText, left + (avail - enW) / 2f,
                        blockTop + cjkFm.getHeight() + enFm.getAscent());
            }

            lastWidth = width;
            lastHeight = height;
            lastCjkSize = Math.round(cjkSize);
            lastEnSize = Math.round(enSize);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 实心方向箭头（尖端朝外）。 */
    private static void drawArrow(final Graphics2D g, final int x, final int cy, final int len, final int h,
                                  final boolean left, final int rgb) {
        final int half = h / 2;
        final int head = Math.max(4, Math.round(len * 0.62f));
        final int shaft = Math.max(2, Math.round(h * 0.34f));
        final Path2D.Float path = new Path2D.Float();
        if (left) {
            path.moveTo(x, cy);
            path.lineTo(x + head, cy - half);
            path.lineTo(x + head, cy - shaft / 2f);
            path.lineTo(x + len, cy - shaft / 2f);
            path.lineTo(x + len, cy + shaft / 2f);
            path.lineTo(x + head, cy + shaft / 2f);
            path.lineTo(x + head, cy + half);
        } else {
            path.moveTo(x, cy);
            path.lineTo(x - head, cy - half);
            path.lineTo(x - head, cy - shaft / 2f);
            path.lineTo(x - len, cy - shaft / 2f);
            path.lineTo(x - len, cy + shaft / 2f);
            path.lineTo(x - head, cy + shaft / 2f);
            path.lineTo(x - head, cy + half);
        }
        path.closePath();
        g.setColor(new Color(rgb));
        g.fill(path);
        g.setStroke(new BasicStroke(1f));
    }
}
