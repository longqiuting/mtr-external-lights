// 厦门 PIDS 到站行的列几何离线断言（不开游戏、不进 Gradle）。
//
//   ./tools/pids-columns/check.sh
//
// 量的是**游戏里那套常量**：直接编译 src 里的 PidsXiamenLayout（它刻意零 MC 依赖）+
// PidsXiamenColumns + TextFit，所以「这里断言过了」= 「游戏里画出来也是这样」。
//
// 两套度量各跑一遍：
//   · MC  —— 复刻 TextRenderer#getWidth 的前进宽（ASCII 逐字宽表 + 全角 9px/字），游戏内的真值；
//   · AWT —— Java2D 前进宽（预览图就是它画的），保证预览里看到的间隙也是真的。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 列几何断言：标签墨迹右缘 ≤ 站名墨迹左缘、站名右缘 ≤ 列右缘、长站名不越界、三行站名左缘差 0。 */
public final class PidsColumnsCheck {

    // ------------------------------------------------------------------ 文案（与 lang 一致）

    private static final String[] ZH_LABEL = {"开往", "下一班开往", "第三班开往"};
    private static final String[] ZH_SUB = {"Bound for", "Next train", "Third train"};
    private static final String[] EN_LABEL = {"To", "Next", "Third"};
    private static final String[] EN_SUB = {"Bound for", "Next train", "Third train"};

    /** 站名：短 / 超长（11 个全角字）/ 短。 */
    private static final String[] ZH_DEST = {"第一码头", "厦门北站附属客运中心", "第一码头"};
    private static final String[] ZH_DEST_EN = {"first warf", "xiamen north station annex passenger transport center",
            "first warf"};
    private static final String[] EN_DEST = {"First Wharf", "Xiamen North Station Annex Passenger Transport Center",
            "First Wharf"};
    private static final String[] EN_DEST_EN = {"first wharf", "xiamen north station annex passenger transport centre",
            "first wharf"};

    /** 倒计时：「已到站」是 4 个全角字，最吃宽度。 */
    private static final String[] ZH_COUNT = {"3", "12", "已到站"};
    private static final String[] ZH_UNIT = {"分钟", "分钟", ""};
    private static final String[] EN_COUNT = {"3", "12", "Arrived"};
    private static final String[] EN_UNIT = {"min", "min", ""};

    private static int failures;

    private PidsColumnsCheck() {
    }

    public static void main(final String[] args) {
        final Font cjk = PsdLcdTypeface.cjk();
        final Font latin = PsdLcdTypeface.latin();
        System.out.println("== 厦门 PIDS 到站行列几何断言 ==");
        System.out.println("  字体：cjk=" + PsdLcdTypeface.name(cjk) + "  latin=" + PsdLcdTypeface.name(latin));

        run("MC 度量（游戏内真值）", mcMetrics());
        run("AWT 度量（离线预览用）", awtMetrics(latin));

        System.out.println();
        if (failures > 0) {
            System.out.println("FAILED: " + failures + " 条断言不成立");
            System.exit(1);
        }
        System.out.println("ALL PASS —— 标签列 / 站名列不再重叠，三行站名左缘对齐，长站名不越界。");
    }

    // ------------------------------------------------------------------ 一套语言 × 一种度量

    private static void run(final String banner, final TextFit.Metrics metrics) {
        System.out.println();
        System.out.println("-- " + banner + " --");
        // 中英各自量一遍（「中英各自量，取两者最大值」）：这里分开跑，两边都必须成立。
        check("zh_cn", metrics, ZH_LABEL, ZH_SUB, ZH_DEST, ZH_DEST_EN, ZH_COUNT, ZH_UNIT);
        check("en_us", metrics, EN_LABEL, EN_SUB, EN_DEST, EN_DEST_EN, EN_COUNT, EN_UNIT);
    }

    private static void check(final String lang, final TextFit.Metrics metrics,
                              final String[] labels, final String[] subs,
                              final String[] dests, final String[] destEns,
                              final String[] counts, final String[] units) {
        final PidsXiamenLayout.Row[] rows = PidsXiamenLayout.METRO.rows();

        // ① 三行 × 中英两档标签，量完取最大值 —— 三行共用这一个 labelW。
        final List<PidsXiamenColumns.Label> all = new ArrayList<>();
        float glyph = 0.0F;
        for (int i = 0; i < rows.length; i++) {
            all.add(new PidsXiamenColumns.Label(labels[i], size(rows[i].label(), rows[i].card())));
            all.add(new PidsXiamenColumns.Label(subs[i], size(rows[i].labelSub(), rows[i].card())));
            glyph = Math.max(glyph, size(rows[i].dest(), rows[i].card()));
        }
        final float labelW = PidsXiamenColumns.labelWidth(metrics, all);
        final float gap = PidsXiamenColumns.gap(glyph);

        System.out.println("  [" + lang + "] labelW=" + f(labelW) + "px  gap=" + f(gap) + "px  字高=" + f(glyph));

        final float[] nameXs = new float[rows.length];
        for (int i = 0; i < rows.length; i++) {
            final PidsXiamenLayout.Row row = rows[i];
            final PidsXiamenLayout.Rect card = row.card();
            final float labelX = card.x0() + row.labelXInset();
            final PidsXiamenColumns.Columns cols =
                    PidsXiamenColumns.of(labelX, labelW, glyph, card.x1() - gap);
            nameXs[i] = cols.nameX();

            // 每一档标签的墨迹右缘（自己量出来的宽，不是拍脑袋的常量）
            final float labelRight = labelX + Math.max(
                    metrics.width(labels[i], size(row.label(), card)),
                    metrics.width(subs[i], size(row.labelSub(), card)));
            // 站名：用同一份 TextFit 反解（只缩不放，缩到底截断）
            final TextFit.Fitted fZh = TextFit.fit(dests[i], metrics,
                    size(row.dest(), card), cols.nameW(), TextFit.MIN_SCALE);
            final TextFit.Fitted fEn = TextFit.fit(destEns[i], metrics,
                    size(row.destSub(), card), cols.nameW(), TextFit.MIN_SCALE);
            final TextFit.Fitted fCount = TextFit.fit(counts[i], metrics,
                    size(row.count(), card), cols.nameW(), TextFit.MIN_SCALE);

            final float zhRight = cols.nameX() + metrics.width(fZh.text(), fZh.size());
            final float enRight = cols.nameX() + metrics.width(fEn.text(), fEn.size());
            final float countRight = cols.nameX() + metrics.width(fCount.text(), fCount.size());

            System.out.println(String.format(Locale.ROOT,
                    "    row%d  标签[%s | %s] 右缘 %.2f → 站名左缘 %.2f（间隙 %.2f）"
                            + "  站名 %.2f..%.2f  列右缘 %.2f  预算 %.2f",
                    i + 1, labels[i], subs[i], labelRight, cols.nameX(), cols.nameX() - labelRight,
                    cols.nameX(), zhRight, cols.nameRight(), cols.nameW()));
            System.out.println(String.format(Locale.ROOT,
                    "           英 %.2f..%.2f   倒计时[%s] %.2f..%.2f  %s",
                    cols.nameX(), enRight, counts[i], cols.nameX(), countRight,
                    fZh.truncated() || fEn.truncated() ? "（长站名已缩到下限 + 尾部截断）" : ""));

            // ---- 断言 1：标签墨迹右缘 ≤ 站名墨迹左缘（间隙 ≥ 0，且不小于固定间隙）
            expect("标签不压站名", labelRight <= cols.nameX() + 1.0E-3F,
                    "标签右缘 " + f(labelRight) + " > 站名左缘 " + f(cols.nameX()));
            expect("间隙不小于 字高×LABEL_GAP_RATIO", cols.nameX() - labelRight >= gap - 0.01F,
                    "实测间隙 " + f(cols.nameX() - labelRight) + " < " + f(gap));

            // ---- 断言 2：站名右缘 ≤ 数字列左缘 − 间距（= 站名列右缘，且不越过框右缘）
            expect("中文站名不越界", zhRight <= cols.nameRight() + 1.0E-3F,
                    "右缘 " + f(zhRight) + " > " + f(cols.nameRight()));
            expect("英文站名不越界", enRight <= cols.nameRight() + 1.0E-3F,
                    "右缘 " + f(enRight) + " > " + f(cols.nameRight()));
            expect("倒计时不越界", countRight <= cols.nameRight() + 1.0E-3F,
                    "右缘 " + f(countRight) + " > " + f(cols.nameRight()));
            expect("站名列右缘在框内", cols.nameRight() <= card.x1() + 1.0E-3F,
                    "列右缘 " + f(cols.nameRight()) + " > 框右缘 " + f(card.x1()));

            // ---- 断言 3：缩字有下限，截断只从尾部加「…」
            expect("缩字不低于 62%", fZh.size() >= size(row.dest(), card) * TextFit.MIN_SCALE - 0.01F,
                    "字号 " + f(fZh.size()) + " 掉到下限以下");
            if (fZh.truncated()) {
                expect("截断带省略号", fZh.text().endsWith(TextFit.ELLIPSIS),
                        "截断文本没有「…」：" + fZh.text());
            }
        }

        // ---- 断言 4：三行站名左缘差 = 0（两行一行宽一行窄会立刻露馅）
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (final float x : nameXs) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        expect("三行站名左缘对齐", Math.abs(max - min) < 0.001F,
                "左缘差 " + f(max - min) + "px（" + f(min) + ".." + f(max) + "）");
        System.out.println("    → 站名起点：" + f(min) + "px，站名残留宽度：" + f(
                rows[0].card().x1() - gap - min) + "px（框右内缘 " + f(rows[0].card().x1() - gap) + "）");
    }

    // ------------------------------------------------------------------ 工具

    private static float size(final PidsXiamenLayout.TextStyle style, final PidsXiamenLayout.Rect card) {
        return Math.max(3.0F, card.h() * style.sizeRatio());
    }

    private static void expect(final String what, final boolean ok, final String detail) {
        if (!ok) {
            failures++;
            System.out.println("    [FAIL] " + what + " :: " + detail);
        }
    }

    private static String f(final float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------ 度量

    /** MC {@code TextRenderer#getWidth} 的前进宽：ASCII 逐字宽表 + 全角 9px/字（缩放 1 时）。 */
    static TextFit.Metrics mcMetrics() {
        return (text, size) -> {
            if (text == null || text.isEmpty()) {
                return 0.0F;
            }
            int units = 0;
            for (int i = 0; i < text.length(); ) {
                final int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                units += advance(cp);
            }
            return units * (size / PidsXiamenLayout.FONT_UNIT_PX);
        };
    }

    /** 一个码点在缩放 1 下的前进宽（px）。 */
    private static int advance(final int cp) {
        if (cp >= 0x2E80 || cp == 0x2026) {
            return 9;   // 全角（CJK 汉字 / 全角标点 / 「…」）
        }
        if (cp >= 'a' && cp <= 'z') {
            return switch ((char) cp) {
                case 'i' -> 2;
                case 'l' -> 3;
                case 't', 'f' -> 4;
                default -> 6;
            };
        }
        if (cp >= 'A' && cp <= 'Z') {
            return cp == 'I' ? 4 : 6;
        }
        if (cp >= '0' && cp <= '9') {
            return 6;
        }
        return switch ((char) cp) {
            case ' ' -> 4;
            case '!' -> 2;
            case ',' , '.' , ':' , ';' , '\'' -> 2;
            case '[' , ']' -> 4;
            case '(' , ')' , '*' , '{' , '}' -> 5;
            case '/', '\\', '-', '_', '+' , '=' , '<' , '>' , '^' -> 6;
            case '@' , '~' -> 7;
            default -> 6;
        };
    }

    /** Java2D 前进宽（预览图就是按它画的）。 */
    static TextFit.Metrics awtMetrics(final Font font) {
        final FontRenderContext frc = new FontRenderContext(null, true, true);
        return (text, size) -> {
            if (text == null || text.isEmpty() || font == null) {
                return 0.0F;
            }
            return (float) font.deriveFont(size).getStringBounds(text, frc).getWidth();
        };
    }
}
