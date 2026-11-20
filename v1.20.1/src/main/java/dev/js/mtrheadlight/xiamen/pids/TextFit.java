// 长站名 / 长文本的「字号反解」：**只缩不放**，缩到下限还放不下才截断（学厦门 LCD 顶栏的版式求解）。
//
// 纯函数、不 import 任何 MC 类：PIDS（地铁 / BRT 两款版式）与 PSD-LCD 挂板都调这里，
// 离线断言（tools/xiamen-text-fit/FitCheck.java）也调这里 —— 「屏上怎么缩」只有一个实现。
package dev.js.mtrheadlight.xiamen.pids;

/**
 * 文字放进一列里：<b>先反推字号，只缩不放，缩到底才截断</b>。
 *
 * <h2>为什么不是「超了就等比缩到刚好」</h2>
 * <p>以前的写法是 {@code w > maxWidth → font × (maxWidth / w)}，没有下限：
 * 一个 10 字的站名会被压到名义字号的 40%，屏上就是一条糊线；
 * 而且它把「缩字」当成唯一手段 —— 真的放不下时也只是越缩越小，不会承认放不下。
 *
 * <p>厦门 LCD 的顶栏（{@code draw.js} 的版式求解）做法是反过来的：
 * <b>先量出各块文字在名义字号下的实际宽度，再按可分得的宽度反推允许的字号上限</b>。
 * 常规情况解出来就是名义字号（1.0），只有真的放不下才往下走，并且<b>有下限</b>；
 * 到了下限还放不下，才承认这一格装不下，从<b>尾部截断</b>加 {@value #ELLIPSIS}。
 *
 * <h2>公式</h2>
 * <pre>
 *   fit   = maxWidth / width(text, nominalSize)        // 名义字号下的实际宽度反推出来的比例
 *   上限   = nominalSize × min(1, fit)                  // ← 只缩不放：fit &gt; 1 时仍是名义字号
 *   下限   = nominalSize × minScale                     // 默认 {@value #MIN_SCALE}
 *   size  = 在 [下限, 上限] 里二分出的「测得下」的最大字号（14 次收敛到 0.01px）
 *   size  = 下限（连下限都放不下时）→ 交给 {@link #ellipsize} 截断
 * </pre>
 *
 * <p><b>二分返回的一定是「测得下」的那一侧</b>，所以按它画出来的右缘不会越过列边界 ——
 * 这一点由 {@code tools/xiamen-text-fit} 在多个视口上逐一断言。
 *
 * <h2>为什么参数是「量的函数」而不是一个字体对象</h2>
 * <p>两款 PIDS 的字宽来自 MC 的 {@code TextRenderer#getWidth}（1px = 1px，CJK 9px/字），
 * PSD-LCD 的字宽来自 Java2D 的 {@code GlyphVector} 墨迹包围盒 —— 两套完全不同的度量。
 * 这里收一个 {@link Metrics}（「给字号，返回这段字有多宽」），两种情况都能接，
 * 规则本身仍然是同一份代码。
 */
public final class TextFit {

    private TextFit() {
    }

    /** 缩放下限：再小就不是「缩字」而是「糊字」了（用户定的 62%）。 */
    public static final float MIN_SCALE = 0.62F;

    /** 截断用的省略号（一个全角字符，屏上量得出来）。 */
    public static final String ELLIPSIS = "\u2026";

    /** 二分次数：{@code 2^-14 ≈ 6e-5}，对 100px 的字号已经收敛到 0.01px 以内。 */
    private static final int STEPS = 14;

    /**
     * 量字的入口：给字号（px），返回这段文字在这个字号下的宽度（px）。
     *
     * <p>实现方自己决定量什么 —— PIDS 量 MC 字形的**前进宽**，PSD-LCD 量 Java2D 的**墨迹宽**
     * （和它们各自 {@code drawString} 出来的宽度同一个口径）。
     */
    public interface Metrics {

        float width(String text, float size);
    }

    /** 一块文字的排版结果：最终字号 + 最终文本（可能被截断）+ 是哪一档处理。 */
    public record Fitted(float size, String text, boolean shrunk, boolean truncated) {

        /** 原样画（既没缩也没裁）。 */
        public boolean untouched() {
            return !this.shrunk && !this.truncated;
        }
    }

    /**
     * 反推字号：<b>只缩不放</b>。
     *
     * @param text        要画的字
     * @param font        量字函数（见 {@link Metrics}）
     * @param nominalSize 名义字号（版式算出来的那个，常规情况就是它）
     * @param maxWidth    这一列能用的宽度；{@code <= 0} 或非有限 = 不限宽
     * @param minScale    下限比例（0.62），超过 1 会被夹到 1（不允许放大）
     * @return 名义字号（放得下）→ 二分出来的字号（缩过）→ 下限（连下限都放不下，调用方去截断）
     */
    public static float fitFontSize(final String text, final Metrics font,
                                    final float nominalSize, final float maxWidth,
                                    final float minScale) {
        if (font == null || text == null || text.isEmpty() || nominalSize <= 0.0F) {
            return nominalSize;
        }
        // maxWidth 不限 → 名义字号。非有限值（+inf / NaN）也按不限处理，绝不让 NaN 传出去。
        if (!(maxWidth > 0.0F) || Float.isInfinite(maxWidth)) {
            return nominalSize;
        }
        // 放得下就是名义字号 —— **这一条就是「只缩不放」**：没有任何放大分支。
        if (font.width(text, nominalSize) <= maxWidth) {
            return nominalSize;
        }
        final float floorScale = Math.max(0.01F, Math.min(1.0F, minScale <= 0.0F ? MIN_SCALE : minScale));
        final float low = nominalSize * floorScale;
        // 连下限都放不下：把下限交回去（调用方据此截断），不再往小压。
        if (font.width(text, low) > maxWidth) {
            return low;
        }
        // 在 [low, nominal) 之间二分：始终维护 low = 「测得下」，所以返回值一定不越界。
        float lo = low;
        float hi = nominalSize;
        for (int i = 0; i < STEPS; i++) {
            final float mid = (lo + hi) * 0.5F;
            if (mid <= lo || mid >= hi) {
                break;
            }
            if (font.width(text, mid) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * 把文字裁到 {@code maxWidth} 以内（尾部加 {@value #ELLIPSIS}）；放得下就原样返回。
     *
     * <p><b>这是最后手段</b>：只有 {@link #fitFontSize} 已经缩到下限还放不下时才应该走到这里
     * （{@link #fit} 自己保证了这个顺序）。按<b>码点</b>裁，不会把代理对切成半个字。
     */
    public static String ellipsize(final String text, final Metrics font,
                                   final float size, final float maxWidth) {
        if (font == null || text == null || text.isEmpty() || !(maxWidth > 0.0F)) {
            return text;
        }
        if (font.width(text, size) <= maxWidth) {
            return text;
        }
        final int codePoints = text.codePointCount(0, text.length());
        // 从「留 n 个字 + …」往下试，第一个放得下的就是答案（连「…」都放不下就只画「…」）。
        for (int keep = codePoints - 1; keep >= 1; keep--) {
            final String cut = text.substring(0, text.offsetByCodePoints(0, keep)) + ELLIPSIS;
            if (font.width(cut, size) <= maxWidth) {
                return cut;
            }
        }
        return font.width(ELLIPSIS, size) <= maxWidth ? ELLIPSIS : "";
    }

    /**
     * 一步到位：反解字号，缩到下限还放不下才截断。
     *
     * @return 字号 + 最终文本 + 是否缩过 / 是否截断
     */
    public static Fitted fit(final String text, final Metrics font,
                             final float nominalSize, final float maxWidth, final float minScale) {
        final float size = fitFontSize(text, font, nominalSize, maxWidth, minScale);
        if (text == null || text.isEmpty() || font == null) {
            return new Fitted(size, text, false, false);
        }
        final String fitted = ellipsize(text, font, size, maxWidth);
        final boolean shrunk = size < nominalSize - 0.01F;
        final boolean truncated = !fitted.equals(text);
        return new Fitted(size, fitted, shrunk, truncated);
    }
}
