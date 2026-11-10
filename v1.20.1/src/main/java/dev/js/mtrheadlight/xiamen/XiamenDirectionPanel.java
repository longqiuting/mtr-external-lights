// 吊板/玻璃上那块“方向面板”的纯 AWT 绘制（不引用任何 MC / MTR 类型，可离线预览）。
package dev.js.mtrheadlight.xiamen;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 吊板（{@code RenderPSDTop}）/ 半高玻璃（{@code RenderAPGGlass}）上、门/玻璃那一格画的面板。
 *
 * <h2>为什么不沿用 MTR 那张</h2>
 * <p>MTR 这块面板走 {@code RenderRouteBase#render} 的 {@code RenderType.STATION_NAME} /
 * {@code ARROW} 分支 → {@code RouteMapGenerator#generateDirectionArrow}：左侧一个线路色圆，
 * 圆里写 <b>站台号</b>，目的地在圆的右边<b>左对齐</b>；两侧还各有一个<b>小黑箭头</b>。</p>
 *
 * <h2>v4 版式（用户实拍纠正）</h2>
 * <pre>
 *        集美软件园北                 ← 当前站中文（v4：不再写“往 XX”）
 *     Jimei Software Park
 * </pre>
 * <ul>
 *   <li><b>写当前站名</b>，中英文两行，整块水平 / 垂直居中；</li>
 *   <li><b>没有任何方向小箭头</b>（v4：全高吊板 / 半高玻璃上 MTR 原版那种小黑箭头都不要）；</li>
 *   <li>没有站台号圆。</li>
 * </ul>
 *
 * <p>尺寸以图片高度 h 为基准，宽度 = h × 面板宽高比，最终由 MTR 拉伸到面板面。</p>
 */
public final class XiamenDirectionPanel {

    /** 纹理高度（像素）。宽度 = 高度 × 面板宽高比。 */
    public static final int IMG_HEIGHT = 256;

    /** 纹理宽度上限。 */
    public static final int MAX_WIDTH = 8192;

    /** 左右内边距 / 高度。 */
    public static final float PAD_RATIO = 0.055f;

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
     * 画一块面板（当前站名 + 英文站名两行，居中，没有方向箭头）。
     *
     * @param cjk         中文站名（{@code 集美软件园}，可空）
     * @param en          英文站名（{@code Jimei Software Park}，可空）
     * @param aspectRatio 面板 宽/高
     */
    public static BufferedImage paint(final String cjk, final String en, final float aspectRatio) {
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

            // ---- 文字：当前站中文一行 + 英文一行，整块水平 / 垂直居中 ----
            final String line = cjk == null ? "" : cjk.trim();
            final String enText = en == null ? "" : en.trim();
            if (line.isEmpty() && enText.isEmpty()) {
                return image;
            }

            final int left = pad;
            final int avail = Math.max(8, width - pad * 2);

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
            final int totalH = (line.isEmpty() ? 0 : cjkFm.getHeight())
                    + (enText.isEmpty() ? 0 : enFm.getHeight());
            final int blockTop = (height - totalH) / 2;

            g.setColor(new Color(TEXT_DARK));
            int y = blockTop;
            if (!line.isEmpty()) {
                g.setFont(cjkFont);
                g.drawString(line, left + (avail - cjkW) / 2f, y + cjkFm.getAscent());
                y += cjkFm.getHeight();
            }
            if (!enText.isEmpty()) {
                g.setFont(enFont);
                g.drawString(enText, left + (avail - enW) / 2f, y + enFm.getAscent());
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
}
