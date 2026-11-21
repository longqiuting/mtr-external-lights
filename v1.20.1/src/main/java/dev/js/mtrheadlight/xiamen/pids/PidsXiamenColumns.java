// 标签列 / 站名列的列宽求解：**先量标签，再定语第一个 x**（不再写死两个 x）。
//
// 纯函数、不 import 任何 MC 类：游戏里的 PIDS 渲染器（MC 字形前进宽）和离线断言 /
// 预览（Java2D 字宽）都调这里 —— 「标签列多宽、站名从哪起、站名还剩多少」只有一个实现。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.List;

/**
 * 厦门 PIDS 一行的列几何：<b>标签列宽 = 所有标签的实测宽取最大值</b>，
 * 站名列起点 = 标签列右缘 + 固定间隙，站名列的可用宽 = 列右缘 − 起点。
 *
 * <h2>为什么不能写死两个 x</h2>
 * <p>以前标签列固定从 {@code x0 + 9px} 起、站名列固定从 {@code x0 + 69px} 起，
 * 标签一长（「下一班开往」5 个全角字）就顶到站名上；而且标签自己还被一个
 * {@code 0.34 × 框宽} 的上限压着缩字 —— 屏上读到的就是
 * 「下一班开往第一码头」粘成一句，英文 {@code Next trainfirst warf} 糊在一起。
 *
 * <p>现在的顺序反过来：
 * <pre>
 *   labelW    = max(每一行 × 中/英两档标签 在各自名义字号下的实测宽)   ← 三行共用这一个数
 *   gap       = 站名字高 × {@value #LABEL_GAP_RATIO}                   ← 固定间隙，不随内容变
 *   nameX     = labelX + labelW + gap                                     ← 站名列起点
 *   nameRight = 名字列右缘（数字列左缘 − 间距）                            ← 由版式给
 *   nameW     = nameRight − nameX                                         ← 交给 TextFit.fit 的预算
 * </pre>
 *
 * <p><b>三行必须共用同一套 labelW / gap</b>：只要某一行按自己的标签宽算起点，
 * 三行站名的左缘就会各差十几 px，一眼看出没对齐。
 *
 * <h2>度量从哪里来</h2>
 * <p>收一个 {@link TextFit.Metrics}（「给字号，返回这段字有多宽」）：
 * 游戏里是 {@code TextRenderer#getWidth}，离线是 Java2D 的前进宽。
 * 规则本身与字体无关，所以两边算出来的列宽只差字体本身的百分之一二。
 */
public final class PidsXiamenColumns {

    private PidsXiamenColumns() {
    }

    /**
     * 标签列与站名列之间的固定间隙 ÷ <b>站名字高</b>。
     *
     * <p>建议值是 0.35，实测在真版式上只有 5px（站名字高 15.1px）—— 512px 的屏缩到方块上，
     * 屏上仍是「下一班开往第一码头」连成一句（离线预览 {@code tools/pids-columns} 里一眼看得出来）。
     * 取 0.60 → 9.1px ≈ 一个汉字的 0.9 倍，读得开，而站名那一列还剩 79px
     * （4 字站名 66.8px 都不用缩）。想再松/再紧只改这一个数。
     */
    public static final float LABEL_GAP_RATIO = 0.60F;

    /** 间隙下限（px）：字号小到像素级时也不允许「贴合」。 */
    public static final float MIN_GAP_PX = 1.0F;

    /**
     * 一条待量的标签。
     *
     * @param text 文案（已经本地化好的那一段）
     * @param size 名义字号（px）
     */
    public record Label(String text, float size) {
    }

    /**
     * 一行的列几何（底图像素）。
     *
     * @param labelX   标签列左缘（左对齐）
     * @param labelW   标签列宽 = 所有标签实测宽的最大值
     * @param gap      标签列 → 站名列的固定间隙
     * @param nameX    站名列起点
     * @param nameRight 站名列右缘（= 数字列左缘 − 间距）
     */
    public record Columns(float labelX, float labelW, float gap, float nameX, float nameRight) {

        /** 站名列还能用多少宽（交给 {@link TextFit#fit}）。 */
        public float nameW() {
            return Math.max(0.0F, this.nameRight - this.nameX);
        }

        /** 标签列右缘（墨迹可达的最右处）。 */
        public float labelRight() {
            return this.labelX + this.labelW;
        }
    }

    /** 所有标签在各自名义字号下的实测宽，取最大值；空表 / 空串 → 0。 */
    public static float labelWidth(final TextFit.Metrics metrics, final List<Label> labels) {
        if (metrics == null) {
            return 0.0F;
        }
        return labelWidth(labels, defaultMetrics(metrics, labels));
    }

    /**
     * 同上，但每条标签可以用自己的度量（预览里中文走黑体、英文走常规字重时用；
     * 游戏里全屏只有一套 MC 字形，两者等价）。
     *
     * @param labels          标签
     * @param metricsPerLabel 与 {@code labels} 一一对应的量字函数（{@code null} 项跳过）
     */
    public static float labelWidth(final List<Label> labels, final List<TextFit.Metrics> metricsPerLabel) {
        if (labels == null || labels.isEmpty() || metricsPerLabel == null) {
            return 0.0F;
        }
        float widest = 0.0F;
        for (int i = 0; i < labels.size() && i < metricsPerLabel.size(); i++) {
            final Label label = labels.get(i);
            final TextFit.Metrics metrics = metricsPerLabel.get(i);
            if (metrics == null || label == null || label.text() == null || label.text().isEmpty()) {
                continue;
            }
            final float w = metrics.width(label.text(), label.size());
            if (w > widest) {
                widest = w;
            }
        }
        return widest;
    }

    /** 同一个度量填满整张表（只为让单度量版本复用上面的实现）。 */
    private static List<TextFit.Metrics> defaultMetrics(final TextFit.Metrics metrics, final List<Label> labels) {
        final List<TextFit.Metrics> out = new java.util.ArrayList<>(labels == null ? 0 : labels.size());
        if (labels != null) {
            for (int i = 0; i < labels.size(); i++) {
                out.add(metrics);
            }
        }
        return out;
    }

    /**
     * 标签列 → 站名列的固定间隙（px）：<b>这一行主文字（站名）的字高</b> × {@value #LABEL_GAP_RATIO}，
     * 不低于 {@value #MIN_GAP_PX}。
     *
     * <p>为什么按「站名字高」而不是标签字高：间隙两侧一边是标签、一边是站名，
     * 视觉上要跟**大一点的那一档字**（站名）成比例才像空格；按标签字高（9.9px）算只有 3.5px，
     * 实机缩到方块屏上仍然读成「下一班开往第一码头」粘连（离线预览一眼就能看出）。
     */
    public static float gap(final float rowGlyphSize) {
        return Math.max(MIN_GAP_PX, rowGlyphSize * LABEL_GAP_RATIO);
    }

    /**
     * 解出一行的列几何（已经量好标签列宽时用这个）。
     *
     * @param labelX          标签列左缘（px）
     * @param labelW          标签列宽（所有标签实测宽的最大值，通常跨行共用）
     * @param rowGlyphSize    这一行主文字（站名）的字高（px），用来算固定间隙
     * @param nameRight       站名列右缘（px）
     */
    public static Columns of(final float labelX, final float labelW, final float rowGlyphSize,
                             final float nameRight) {
        final float gap = gap(rowGlyphSize);
        return new Columns(labelX, labelW, gap, labelX + labelW + gap, nameRight);
    }

    /**
     * 解出一行的列几何（现量标签宽）。
     *
     * @param metrics         量字函数（{@code null} → 标签列宽 0，只剩间隙）
     * @param labels          这一版式里**所有**行的标签（中/英都放进来；跨行共用一份结果）
     * @param labelX          标签列左缘（px）
     * @param rowGlyphSize    这一行主文字（站名）的字高（px），用来算固定间隙
     * @param nameRight       站名列右缘（px）
     */
    public static Columns of(final TextFit.Metrics metrics, final List<Label> labels,
                             final float labelX, final float rowGlyphSize, final float nameRight) {
        return of(labelX, labelWidth(metrics, labels), rowGlyphSize, nameRight);
    }
}
