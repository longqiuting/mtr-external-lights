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
 * <h2>v7 版式（2026-11-12：彻底不画方向小箭头）</h2>
 * <pre>
 *        集美软件园                 ← 当前站中文（0.385×高度）
 *     Jimei Software Park             ← 英文（中文的 0.41）
 * </pre>
 * <ul>
 *   <li><b>写当前站名</b>，中英文两行，在整幅宽度里水平居中；</li>
 *   <li>没有站台号圆；</li>
 *   <li><b>没有任何箭头</b>（v7 用户明确要求：“删掉所有黑色小箭头”）：MTR 的
 *       {@code ARROW_DIRECTION} 四个形态（0/1/2/3）<b>画出来完全一样</b> ——
 *       {@code hasLeft / hasRight} 仍然照收，但<b>只用于日志</b>，不再画一笔；</li>
 *   <li>v5：<b>英文行自己再按宽度收一次</b>（v4 只缩中文，长站名时英文会顶出面板两边），
 *       两行的总高也会被夹在面板高度内 —— 最长站名 + 最长英文也出不去。</li>
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

    /**
     * 中文行字号上限 / 高度：<b>0.385</b>（v5）。
     *
     * <p>v4 是 0.46 —— 用户反馈“吊板上的站名太大”，v5 缩 16%，两行都跟着小一档，
     * 站名上下也自然多出留白（吊板玻璃边框不会贴着字）。</p>
     */
    public static final float CJK_RATIO = 0.385f;

    /**
     * 英文行 / 中文行：<b>0.41</b>（v5）。
     *
     * <p>v4 是 0.42；v5 中文本身缩了 16%，英文再比中文小一档 —— 实拍里英文就是明显小一号的第二行。
     * 合下来英文行 0.41×0.385 ≈ 0.158×高度（v4 是 0.193，-18%）。</p>
     */
    public static final float EN_RATIO = 0.41f;

    /** 深色文字（与条带一致）。 */
    public static final int TEXT_DARK = 0x1A1A1A;

    /** 上一次绘制用到的尺寸（日志 / 报告用）。 */
    public static volatile int lastWidth;
    public static volatile int lastHeight;
    public static volatile int lastCjkSize;
    public static volatile int lastEnSize;
    /**
     * MTR 这次<b>要求</b>画的方向（{@code ARROW_DIRECTION} 拆出来的两位）。
     *
     * <p>v7 起这两个值<b>只进日志</b>：图上不画任何箭头，四个形态的位图内容完全一致。</p>
     */
    public static volatile boolean lastHasLeft;
    public static volatile boolean lastHasRight;

    private XiamenDirectionPanel() {
    }

    /** 不画任何箭头（v7 起四个形态都不画，默认态）。 */
    public static BufferedImage paint(final String cjk, final String en, final float aspectRatio) {
        return paint(cjk, en, aspectRatio, false, false);
    }

    /**
     * 画一块面板（当前站名 + 英文站名两行，居中；<b>不画任何方向箭头</b>）。
     *
     * @param cjk         中文站名（{@code 集美软件园}，可空）
     * @param en          英文站名（{@code Jimei Software Park}，可空）
     * @param aspectRatio 面板 宽/高
     * @param hasLeft     MTR {@code ARROW_DIRECTION} 的 bit0（v7 起<b>只记日志</b>，不画）
     * @param hasRight    MTR {@code ARROW_DIRECTION} 的 bit1（同上；4 = 双向也只是日志）
     */
    public static BufferedImage paint(final String cjk, final String en, final float aspectRatio,
                                      final boolean hasLeft, final boolean hasRight) {
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

            // ---- v7：不画任何方向箭头（用户指定“删掉所有黑色小箭头”）----
            // MTR 的 ARROW_DIRECTION（0/1/2/3）仍旧按原样传进来，但图上不再占位置：
            // 文字区 = 整幅宽度（左右各留 pad），四个形态画出来完全一样。

            // ---- 文字：当前站中文一行 + 英文一行，整块水平 / 垂直居中 ----
            final String line = cjk == null ? "" : cjk.trim();
            final String enText = en == null ? "" : en.trim();
            if (line.isEmpty() && enText.isEmpty()) {
                lastHasLeft = hasLeft;
                lastHasRight = hasRight;
                return image;
            }

            final int left = pad;
            final int avail = Math.max(8, width - pad * 2);

            final Font ref = XiamenStripPainter.font().deriveFont(Font.BOLD, 100f);
            final int cjkW100 = line.isEmpty() ? 0 : g.getFontMetrics(ref).stringWidth(line);
            final int enW100 = enText.isEmpty() ? 0 : g.getFontMetrics(ref).stringWidth(enText);

            // v5：中文 0.385×高度（v4 是 0.46，-16%）；英文 0.40×中文（v4 是 0.42，再小一档）。
            float cjkSize = Math.max(7f, height * CJK_RATIO);
            if (cjkW100 > 0) {
                cjkSize = Math.min(cjkSize, 100.0f * avail / cjkW100);
            }
            float enSize = cjkSize * EN_RATIO;
            if (enW100 > 0) {
                // v5：英文行自己也按宽度收一次 —— v4 只缩中文，长站名（Xiamen North Railway Station）
                // 的英文会直接顶出面板左右两边。
                enSize = Math.min(enSize, 100.0f * avail / enW100);
            }
            if (line.isEmpty()) {
                // 只有英文时，以英文为准（中文那一档不参与）。
                cjkSize = Math.max(7f, enSize / EN_RATIO);
            }
            cjkSize = Math.max(7f, cjkSize);
            enSize = Math.max(5f, enSize);

            Font cjkFont = ref.deriveFont(cjkSize);
            Font enFont = ref.deriveFont(Font.PLAIN, enSize);
            FontMetrics cjkFm = g.getFontMetrics(cjkFont);
            FontMetrics enFm = g.getFontMetrics(enFont);
            int totalH = (line.isEmpty() ? 0 : cjkFm.getHeight())
                    + (enText.isEmpty() ? 0 : enFm.getHeight());
            // v5：两行加起来也必须塞进面板高度（v4 只管宽度，窄面板上会顶到上下边框）。
            final int availH = Math.max(8, height - pad * 2);
            if (totalH > availH) {
                final float k = (float) availH / (float) totalH;
                cjkSize = Math.max(6f, cjkSize * k);
                enSize = Math.max(5f, enSize * k);
                cjkFont = ref.deriveFont(cjkSize);
                enFont = ref.deriveFont(Font.PLAIN, enSize);
                cjkFm = g.getFontMetrics(cjkFont);
                enFm = g.getFontMetrics(enFont);
                totalH = (line.isEmpty() ? 0 : cjkFm.getHeight())
                        + (enText.isEmpty() ? 0 : enFm.getHeight());
            }
            final int cjkW = line.isEmpty() ? 0 : cjkFm.stringWidth(line);
            final int enW = enText.isEmpty() ? 0 : enFm.stringWidth(enText);
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
            lastHasLeft = hasLeft;
            lastHasRight = hasRight;
        } finally {
            g.dispose();
        }
        return image;
    }
}

