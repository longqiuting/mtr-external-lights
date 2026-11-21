/*
 * 长站名 / 已到站 的离线断言（不开游戏、不进 Gradle）
 * ======================================================================
 *
 * 断言的三件事，全部走**屏上那一份纯函数**：
 *
 *   1) 长站名的文字**右缘 ≤ 列右缘**，且**绝不放大**（只缩不放），
 *      缩到 62% 还放不下才截断（尾部「…」）—— PIDS 两款版式 + PSD-LCD 终点站 / 当前站；
 *   2) 各视口（PSD-LCD 1…13 段；PIDS 固定 512×235）都成立；
 *   3) 到站档位：{@code 剩余 ≤ 0} 一律 ARRIVED（中「已到站」/ 英「Arrived」），
 *      两种模式都不可能出现 {@code 0秒 / 0 min}。
 *
 * 复用的实现：
 *   · {@link TextFit}               字号反解 / 截断（三块共用）
 *   · {@link ArrivalCountdown}      到站档位 + 语言键
 *   · {@link PsdLcdPainter#fitColumn}  PSD-LCD 各列的排版（绘制与断言同一函数）
 *   · {@link PidsXiamenLayout}       PIDS 的框 / 字号比例（真实常量）
 *
 * 用法：
 *   ./tools/xiamen-text-fit/check.sh              # 跑在 v1.20.1 上，输出到 temp/
 *   ./tools/xiamen-text-fit/check.sh D:/MC-MTR-JS/mtr-headlight/v1.20.1
 */
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 见文件头。断言全打印，任何一条失败都会让退出码为 1。 */
public final class FitCheck {

    /** 断言失败的条数（> 0 → 退出码 1）。 */
    private static int failures;

    /** 断言失败的明细（最后一起打，方便一次看完）。 */
    private static final List<String> FAILED = new ArrayList<>();

    /** 量宽容差（px）：字号是 float，deriveFont 四舍五入后墨迹宽可能差一点点。 */
    private static final float TOL = 0.75F;

    /** 长站名：用户报的就是这两个，再加几个更长的。 */
    private static final List<String> NAMES_ZH = List.of(
            "华侨大学", "董任", "第一码头枢纽站", "厦门北站附属客运中心",
            "厦门北站附属客运中心东广场", "厦门北站附属客运中心东广场站");

    private static final List<String> NAMES_EN = List.of(
            "Huaqiao University", "Dongren", "No.1 Wharf Junction",
            "Xiamen North Railway Station", "Xiamen North Railway Station Huangcuo Bus Terminal");

    private FitCheck() {
    }

    public static void main(final String[] args) throws IOException {
        final File mod = new File(args.length > 0 ? args[0] : ".");
        final File out = new File(args.length > 1 ? args[1] : new File(mod, "temp").getPath());
        if (!out.exists() && !out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }

        final PsdLcdPainter.Assets assets = new PsdLcdPainter.Assets(
                load(new File(mod, "src/main/resources/assets/mtrlte/textures/gui/psd_lcd/xiamen_logo.png")),
                PsdLcdTypeface.cjk(), PsdLcdTypeface.latin(), PsdLcdTypeface.digits());

        System.out.println("== PIDS（两款版式：地铁 3 行 / BRT 2 行）长站名 ==");
        checkPids();

        System.out.println();
        System.out.println("== PSD-LCD（1…13 段视口）终点站 / 当前站 ==");
        checkPsdLcd(assets);

        System.out.println();
        System.out.println("== PSD-LCD 到站行：标签列（实测宽）+ 站名列（列内居中）==");
        checkArrivalColumns(assets);

        System.out.println();
        System.out.println("== 到站档位：已到站 / 不出现 0秒 ==");
        checkArrived(mod);

        System.out.println();
        System.out.println("== 出图（长站名 + 已到站，人眼核对用） ==");
        renderSamples(assets, out);

        System.out.println();
        if (failures == 0) {
            System.out.println("全部断言通过（0 条失败）。");
        } else {
            System.out.println("断言失败 " + failures + " 条：");
            for (final String line : FAILED) {
                System.out.println("  · " + line);
            }
            System.exit(1);
        }
    }

    // ================================================================ PIDS

    /**
     * PIDS 的字宽模型：MC 位图字体的**前进宽**近似 —— CJK 9px、ASCII 6px、空格 4px、
     * 窄字（i l . : | ! ,）2px，再乘 {@code size / 8}（和 {@code PidsXiamenText.width} 同一口径）。
     *
     * <p>真实度量是 {@code TextRenderer#getWidth}，离线拿不到；但「缩不过下限 / 不越界 /
     * 只缩不放」这几条性质对**任何**度量都成立，模型只决定具体的字号数值。
     */
    private static float mcWidth(final String text, final float size) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        int advance = 0;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x2E80) {
                advance += 9;
            } else if (cp == ' ') {
                advance += 4;
            } else if ("il.:|!,;'".indexOf(cp) >= 0) {
                advance += 2;
            } else if ("mwMW".indexOf(cp) >= 0) {
                advance += 8;
            } else {
                advance += 6;
            }
        }
        return advance * size / PidsXiamenLayout.FONT_UNIT_PX;
    }

    /** 两款版式 × 每个到站框 × 终点站那两行（中文大 / 英文小）。 */
    private static void checkPids() {
        System.out.printf("  屏幕：%.0f × %.0f px（底图坐标，1px = 1px）；下限 %.0f%%；量宽模型：CJK 9 / ASCII 6（size/8）%n",
                PidsXiamenLayout.SCREEN_WIDTH, PidsXiamenLayout.SCREEN_HEIGHT, TextFit.MIN_SCALE * 100);

        checkPidsLayout("地铁", PidsXiamenLayout.METRO.rows(), true);
        checkPidsLayout("BRT ", PidsXiamenLayout.BRT.rows(), false);
    }

    private static void checkPidsLayout(final String name, final Object[] rows, final boolean metro) {
        final List<String> report = new ArrayList<>();
        float worstRight = -1.0F;
        String worstText = "";
        for (final Object rowObject : rows) {
            final PidsXiamenLayout.Row row = metro ? (PidsXiamenLayout.Row) rowObject : null;
            final PidsXiamenLayout.BrtRow brtRow = metro ? null : (PidsXiamenLayout.BrtRow) rowObject;
            final PidsXiamenLayout.Rect card = metro ? row.card() : brtRow.card();
            final PidsXiamenLayout.TextStyle dest = metro ? row.dest() : brtRow.dest();
            final PidsXiamenLayout.TextStyle destSub = metro ? row.destSub() : null;

            final List<String> texts = new ArrayList<>();
            for (final String zh : NAMES_ZH) {
                // 地铁：终点站就是站名；BRT：整行是「开往%s站」（名字本身带「站」就不再补一个）
                texts.add(metro || zh.endsWith("站") ? (metro ? zh : "开往" + zh) : "开往" + zh + "站");
            }
            for (final String text : texts) {
                final Box box = pidsBox(metro ? "终点站" : "开往…站", dest, card, text);
                if (box.right() > worstRight) {
                    worstRight = box.right();
                    worstText = text;
                }
                report.add(box.line(name));
            }
            if (destSub != null) {
                for (final String en : NAMES_EN) {
                    final Box box = pidsBox("终点站英文", destSub, card, en);
                    if (box.right() > worstRight) {
                        worstRight = box.right();
                        worstText = en;
                    }
                    report.add(box.line(name));
                }
            }
        }
        // 只打最长的三条（其余只断言，不刷屏）
        report.sort((a, b) -> b.length() - a.length());
        for (int i = 0; i < Math.min(3, report.size()); i++) {
            System.out.println("  " + report.get(i));
        }
        System.out.printf("  %s：任一行文字右缘最大 %.1f px（最长的那条：「%s」）%n", name, worstRight, worstText);
    }

    /** 一行 PIDS 文字的排版结果。 */
    private record Box(String what, String text, float nominal, float size, float left, float right,
                       float columnRight, boolean truncated) {

        String line(final String layout) {
            return String.format("%s %s \"%s\"：名义 %.1fpx → %.1fpx（%.0f%%）%s  %.1f…%.1f ≤ 框右缘 %.1f",
                    layout, this.what, this.text, this.nominal, this.size, 100.0F * this.size / this.nominal,
                    this.truncated ? " 截断" : "", this.left, this.right, this.columnRight);
        }
    }

    /**
     * 一行 PIDS 文字：反解字号 → 量宽 → 判「右缘 ≤ 列右缘」。
     */
    private static Box pidsBox(final String what, final PidsXiamenLayout.TextStyle style,
                               final PidsXiamenLayout.Rect card, final String text) {
        final float nominal = Math.max(3.0F, card.h() * style.sizeRatio());
        final float maxWidth = card.w() * style.maxWidthRatio();
        // 和 PidsXiamenRenderer#fittedLine 完全同一组输入：名义字号 = 框高 × sizeRatio，
        // 列宽 = 框宽 × maxWidthRatio，下限 TextFit.MIN_SCALE。
        final TextFit.Fitted fitted = TextFit.fit(text, FitCheck::mcWidth, nominal, maxWidth, TextFit.MIN_SCALE);
        final float drawn = mcWidth(fitted.text(), fitted.size());
        final float left = card.x(style.xRatio());
        final float right = left + drawn;
        // 列右缘 = 这一格的右边界：文字只能在自己那一列里，越过去就是压到隔壁（时间 / 数字）那列。
        final float columnRight = card.x1();

        final Box box = new Box(what, text, nominal, fitted.size(), left, right,
                columnRight, fitted.truncated());

        check(fitted.size() <= nominal + 0.01F,
                "PIDS 放大了字号：" + text + " " + fitted.size() + " > " + nominal);
        check(right <= left + maxWidth + TOL,
                "PIDS 越出自己的列宽：" + text + " 右缘 " + right + " > " + (left + maxWidth));
        check(right <= columnRight - 2.0F,
                "PIDS 压到框外：" + text + " 右缘 " + right + " > " + (columnRight - 2.0F));
        if (fitted.truncated()) {
            check(fitted.text().endsWith(TextFit.ELLIPSIS), "PIDS 截断没加省略号：" + fitted.text());
            // 「最后才截断」：截断时字号必须已经压到下限（说明它是真的放不下）
            check(fitted.size() <= nominal * TextFit.MIN_SCALE + 0.6F,
                    "PIDS 没缩到下限就截断了：" + text + " size=" + fitted.size()
                            + " lower=" + (nominal * TextFit.MIN_SCALE));
        }
        return box;
    }

    // ================================================================ PSD-LCD

    /** 1…13 段；每段 520px 宽、整块 227.5px 高（{@link PsdLcdLayout}）。 */
    private static void checkPsdLcd(final PsdLcdPainter.Assets assets) {
        System.out.printf("  视口：宽 = %d…%d px（1…13 段 × %.0f），高 %.1f px；排版高按 6.91:1 收窄%n",
                (int) PsdLcdLayout.PX_PER_BLOCK, (int) (PsdLcdLayout.PX_PER_BLOCK * 13),
                PsdLcdLayout.PX_PER_BLOCK, PsdLcdLayout.HEIGHT_PX);

        int cases = 0;
        for (int segments = 1; segments <= 13; segments++) {
            final float w = PsdLcdLayout.PX_PER_BLOCK * segments;
            final float h = PsdLcdLayout.HEIGHT_PX;
            // 列几何与绘制同一份（终点站两列的宽度 = 实测标签列之后剩下的那一列）
            final PsdLcdPainter.ArrivalColumns cols = PsdLcdPainter.arrivalColumns(assets, w, h);

            // 名义字号：必须用「一定放得下的单字」量 —— 用 4 字站名量会量到**缩过之后**的字号
            // （1 段那档列只有 ~49px，4 个字本来就放不下），再拿去断言「只缩不放」就成了假通过。
            final float destNominalZh = PsdLcdPainter.fitColumn(PsdLcdPainter.Column.DEST_ZH,
                    "一", assets, w, h, cols).size();
            final float destNominalEn = PsdLcdPainter.fitColumn(PsdLcdPainter.Column.DEST_EN,
                    "a", assets, w, h, cols).size();
            final float staNominalZh = PsdLcdPainter.fitColumn(PsdLcdPainter.Column.STATION_ZH,
                    "一", assets, w, h, cols).size();
            final float staNominalEn = PsdLcdPainter.fitColumn(PsdLcdPainter.Column.STATION_EN,
                    "a", assets, w, h, cols).size();

            final List<String> texts = new ArrayList<>();
            texts.addAll(NAMES_ZH);
            texts.addAll(NAMES_EN);

            for (final String text : texts) {
                final boolean latin = !text.isEmpty() && text.codePointAt(0) < 0x2E80;
                final PsdLcdPainter.Column destColumn = latin
                        ? PsdLcdPainter.Column.DEST_EN : PsdLcdPainter.Column.DEST_ZH;
                final PsdLcdPainter.Column staColumn = latin
                        ? PsdLcdPainter.Column.STATION_EN : PsdLcdPainter.Column.STATION_ZH;

                psdCase("终点站", destColumn, text, assets, w, h,
                        latin ? destNominalEn : destNominalZh, segments, cols);
                psdCase("当前站", staColumn, text, assets, w, h,
                        latin ? staNominalEn : staNominalZh, segments, cols);
                cases += 2;
            }

            // 倒计时数字 / 状态文案
            final float countNominal = PsdLcdPainter.fitColumn(PsdLcdPainter.Column.COUNT,
                    "0", assets, w, h, cols).size();
            final float stateNominal = PsdLcdPainter.fitColumn(PsdLcdPainter.Column.COUNT_STATE,
                    "一", assets, w, h, cols).size();
            for (final String count : new String[]{"0", "3", "17", "99", "120"}) {
                psdCase("数字", PsdLcdPainter.Column.COUNT, count, assets, w, h,
                        countNominal, segments, cols);
                cases++;
            }
            for (final String state : new String[]{"已到站", "即将进站", "Arrived", "Arriving"}) {
                psdCase("状态", PsdLcdPainter.Column.COUNT_STATE, state, assets, w, h,
                        stateNominal, segments, cols);
                cases++;
            }
        }
        System.out.printf("  小计 %d 个（列 × 文本 × 视口）组合，全部：字号 ≤ 名义、左缘 ≥ 列左缘、右缘 ≤ 列右缘%n", cases);

        // 报告里能直接引用的几行明细
        System.out.println("  明细（3 段视口 1560×227.5）：");
        final float w3 = PsdLcdLayout.PX_PER_BLOCK * 3;
        final float h3 = PsdLcdLayout.HEIGHT_PX;
        final PsdLcdPainter.ArrivalColumns cols3 = PsdLcdPainter.arrivalColumns(assets, w3, h3);
        for (final String text : new String[]{"华侨大学", "第一码头枢纽站", "厦门北站附属客运中心"}) {
            final PsdLcdPainter.Column col = PsdLcdPainter.Column.DEST_ZH;
            final PsdLcdPainter.Fitted f = PsdLcdPainter.fitColumn(col, text, assets, w3, h3, cols3);
            System.out.printf("    终点站 \"%s\"：%.1fpx%s  右缘 %.2f ≤ 列右缘 %.2f（数字列左缘 %.2f）%n",
                    text, f.size(), f.truncated() ? "（截断）" : "",
                    PsdLcdPainter.inkRight(col, f, w3, cols3), PsdLcdPainter.columnRight(col, w3, cols3),
                    PsdLcdPainter.columnAnchor(PsdLcdPainter.Column.COUNT_STATE, w3));
        }
        for (final String text : new String[]{"董任", "第一码头枢纽站", "厦门北站附属客运中心"}) {
            final PsdLcdPainter.Column col = PsdLcdPainter.Column.STATION_ZH;
            final PsdLcdPainter.Fitted f = PsdLcdPainter.fitColumn(col, text, assets, w3, h3, cols3);
            System.out.printf("    当前站 \"%s\"：%.1fpx%s  左缘 %.2f ≥ %.2f，右缘 %.2f ≤ %.2f%n",
                    text, f.size(), f.truncated() ? "（截断）" : "",
                    PsdLcdPainter.inkLeft(col, f, w3), PsdLcdPainter.columnLeft(col, w3),
                    PsdLcdPainter.inkRight(col, f, w3), PsdLcdPainter.columnRight(col, w3));
        }
    }

    /** 一列一文本一视口：三件事一起断言。 */
    private static void psdCase(final String what, final PsdLcdPainter.Column column, final String text,
                                final PsdLcdPainter.Assets assets, final float w, final float h,
                                final float nominal, final int segments,
                                final PsdLcdPainter.ArrivalColumns cols) {
        final PsdLcdPainter.Fitted fitted = PsdLcdPainter.fitColumn(column, text, assets, w, h, cols);
        if (text == null || text.isEmpty()) {
            return;
        }
        final float left = PsdLcdPainter.inkLeft(column, fitted, w, cols);
        final float right = PsdLcdPainter.inkRight(column, fitted, w, cols);
        final String tag = what + " \"" + text + "\" @" + segments + " 段";

        // 1) 只缩不放
        check(fitted.size() <= nominal + 0.01F,
                "PSD-LCD 放大了字号：" + tag + " " + fitted.size() + " > " + nominal);
        // 2) 右缘 ≤ 列右缘（= 时间/数字列左缘 − 间距），左缘 ≥ 列左缘
        check(right <= PsdLcdPainter.columnRight(column, w, cols) + TOL,
                "PSD-LCD 右缘越界：" + tag + " " + right + " > " + PsdLcdPainter.columnRight(column, w, cols));
        check(left >= PsdLcdPainter.columnLeft(column, w, cols) - TOL,
                "PSD-LCD 左缘越界：" + tag + " " + left + " < " + PsdLcdPainter.columnLeft(column, w, cols));
        // 3) 截断只发生在「缩到下限仍放不下」之后，且尾部是省略号
        if (fitted.truncated()) {
            check(fitted.text().endsWith(TextFit.ELLIPSIS), "PSD-LCD 截断没加省略号：" + tag);
            check(fitted.size() <= nominal * TextFit.MIN_SCALE + 0.6F,
                    "PSD-LCD 没缩到下限就截断了：" + tag + " size=" + fitted.size()
                            + " lower=" + (nominal * TextFit.MIN_SCALE));
        }
        if (column == PsdLcdPainter.Column.COUNT) {
            // 数字不截断（截成「1…」没有意义），且右缘永远贴 NUM_RIGHT
            check(!fitted.truncated(), "PSD-LCD 把数字截断了：" + tag + " → " + fitted.text());
        }
    }

    // ================================================================ 到站行（标签列 / 站名列）

    /**
     * 两行到站的全部离线断言（1…13 段 × 中/英两套标签 × 长站名）。
     *
     * <ol>
     *   <li>标签墨迹右缘 ≤ 站名列起点（{@code nameX}），且标签 → 站名墨迹间隙 ≥ 4px；</li>
     *   <li>两行站名用<b>同一份</b>列几何：{@code nameX} / {@code nameRight} 差 0px（同一文本在两个视口
     *       排版出来的左/右缘也完全相等）；</li>
     *   <li>站名墨迹右缘 ≤ {@code DEST_COL_R}（= 数字列左缘 − 间距），不压「分钟 / min」也不压数字；</li>
     *   <li>长站名不越界：左缘 ≥ {@code nameX}、右缘 ≤ {@code nameRight}、字号 ≤ 名义（只缩不放）。</li>
     * </ol>
     */
    private static void checkArrivalColumns(final PsdLcdPainter.Assets assets) {
        System.out.printf("  屏：%d 段 = %.0f×%.1f px … 13 段 = %.0f×%.1f px；间隙 = 站名字高 × %.2f（≥ %.0fpx）%n",
                (int) PsdLcdLayout.PX_PER_BLOCK, PsdLcdLayout.PX_PER_BLOCK, PsdLcdLayout.HEIGHT_PX,
                PsdLcdLayout.PX_PER_BLOCK * 13, PsdLcdLayout.HEIGHT_PX,
                PsdLcdPainter.LABEL_GAP_RATIO, PsdLcdPainter.MIN_LABEL_GAP_PX);

        // 中文客户端的标签 / 英文客户端的标签（渲染器实际传给画家的是这两套）
        final String[][] labelSets = {
                {"开往", "Bound for", "下一趟开往", "Next train"},
                {"To", "Bound for", "Next", "Next train"},
        };

        int combos = 0;
        float worstGap = Float.MAX_VALUE;
        String worstGapText = "";
        for (int segments = 1; segments <= 13; segments++) {
            final float w = PsdLcdLayout.PX_PER_BLOCK * segments;
            final float h = PsdLcdLayout.HEIGHT_PX;
            for (final String[] set : labelSets) {
                // 第一行 / 第二行各自解一次 —— 两次结果必须逐字段相同（= 两行共用一份列几何）
                final PsdLcdPainter.ArrivalColumns row1 = PsdLcdPainter.arrivalColumns(
                        set[0], set[1], set[2], set[3], assets, w, h);
                final PsdLcdPainter.ArrivalColumns row2 = PsdLcdPainter.arrivalColumns(
                        set[0], set[1], set[2], set[3], assets, w, h);
                final String tag = segments + " 段";
                check(row1.equals(row2),
                        "PSD-LCD 两行站名列不一致（" + tag + "，" + set[0] + "）：" + row1 + " vs " + row2);
                check(row1.nameX() == row2.nameX() && row1.nameRight() == row2.nameRight(),
                        "PSD-LCD 两行站名左/右缘差不是 0px（" + tag + "）："
                                + (row1.nameX() - row2.nameX()) + " / " + (row1.nameRight() - row2.nameRight()));
                check(Math.abs(row1.labelRight() + row1.gap() - row1.nameX()) < 0.01F,
                        "PSD-LCD 站名列起点 ≠ 标签列右缘 + 间隙（" + tag + "）："
                                + row1.labelRight() + " + " + row1.gap() + " vs " + row1.nameX());
                check(row1.gap() >= PsdLcdPainter.MIN_LABEL_GAP_PX - 1e-4F,
                        "PSD-LCD 标签 ↔ 站名间隙小于下限（" + tag + "）：" + row1.gap());
                check(row1.nameW() > 0.0F, "PSD-LCD 站名列宽度 ≤ 0（" + tag + "）：" + row1.nameW());
                // 站名列整个在「数字 / 状态」列左边（= 不压分钟列）：设计稿留 0.007 屏宽的空档，
                // 1 段那档只有 3.6px，所以这里只要求「严格在左边」，不套 4px（4px 是标签 ↔ 站名的要求）
                final float countStateLeft = PsdLcdPainter.columnAnchor(PsdLcdPainter.Column.COUNT_STATE, w);
                check(row1.nameRight() <= countStateLeft - 1.0F,
                        "PSD-LCD 站名列右缘压到数字列（" + tag + "）："
                                + row1.nameRight() + " > " + (countStateLeft - 1.0F));

                // 标签：墨迹不越过标签列右缘，且出厂标签一个都不该被截断
                for (int r = 0; r < 2; r++) {
                    final String zh = set[r * 2];
                    final String en = set[r * 2 + 1];
                    final float zhRight = PsdLcdPainter.labelInkRight(zh, true, assets, w, h, row1);
                    final float enRight = PsdLcdPainter.labelInkRight(en, false, assets, w, h, row1);
                    check(zhRight <= row1.labelRight() + TOL,
                            "PSD-LCD 中文标签越出标签列（" + tag + "，" + zh + "）：" + zhRight
                                    + " > " + row1.labelRight());
                    check(enRight <= row1.labelRight() + TOL,
                            "PSD-LCD 英文标签越出标签列（" + tag + "，" + en + "）：" + enRight
                                    + " > " + row1.labelRight());
                    check(!PsdLcdPainter.fitLabel(zh, true, assets, w, h, row1).truncated(),
                            "PSD-LCD 出厂中文标签被截断（" + tag + "）：" + zh);
                    check(!PsdLcdPainter.fitLabel(en, false, assets, w, h, row1).truncated(),
                            "PSD-LCD 出厂英文标签被截断（" + tag + "）：" + en);
                }

                // 站名：每个长站名都在列内，且离标签 ≥ 4px
                for (final String name : NAMES_ZH) {
                    final PsdLcdPainter.Fitted f = PsdLcdPainter.fitColumn(
                            PsdLcdPainter.Column.DEST_ZH, name, assets, w, h, row1);
                    final float left = PsdLcdPainter.inkLeft(PsdLcdPainter.Column.DEST_ZH, f, w, row1);
                    final float right = PsdLcdPainter.inkRight(PsdLcdPainter.Column.DEST_ZH, f, w, row1);
                    final float gap = left - PsdLcdPainter.labelInkRight(set[2], true, assets, w, h, row1);
                    if (gap < worstGap) {
                        worstGap = gap;
                        worstGapText = name + " @" + tag;
                    }
                    check(left >= row1.nameX() - TOL, "PSD-LCD 站名左缘越界（" + tag + "，" + name + "）："
                            + left + " < " + row1.nameX());
                    check(right <= row1.nameRight() + TOL, "PSD-LCD 站名右缘越界（" + tag + "，" + name + "）："
                            + right + " > " + row1.nameRight());
                    check(gap >= PsdLcdPainter.MIN_LABEL_GAP_PX - TOL,
                            "PSD-LCD 标签压到站名（" + tag + "，" + set[2] + " → " + name
                                    + "）：间隙 " + gap + "px");
                    combos++;
                }
                for (final String name : NAMES_EN) {
                    final PsdLcdPainter.Fitted f = PsdLcdPainter.fitColumn(
                            PsdLcdPainter.Column.DEST_EN, name, assets, w, h, row1);
                    final float left = PsdLcdPainter.inkLeft(PsdLcdPainter.Column.DEST_EN, f, w, row1);
                    final float right = PsdLcdPainter.inkRight(PsdLcdPainter.Column.DEST_EN, f, w, row1);
                    final float gap = left - PsdLcdPainter.labelInkRight(set[3], false, assets, w, h, row1);
                    if (gap < worstGap) {
                        worstGap = gap;
                        worstGapText = name + " @" + tag;
                    }
                    check(left >= row1.nameX() - TOL, "PSD-LCD 英文站名左缘越界（" + tag + "，" + name + "）："
                            + left + " < " + row1.nameX());
                    check(right <= row1.nameRight() + TOL, "PSD-LCD 英文站名右缘越界（" + tag + "，" + name + "）："
                            + right + " > " + row1.nameRight());
                    check(gap >= PsdLcdPainter.MIN_LABEL_GAP_PX - TOL,
                            "PSD-LCD 英文标签压到站名（" + tag + "，" + set[3] + " → " + name
                                    + "）：间隙 " + gap + "px");
                    combos++;
                }
            }
        }
        System.out.printf("  %d 段 × %d 套标签 × 长站名（%d 组）：标签右缘 ≤ 列右缘、"
                        + "间隙 ≥ %.0fpx、站名在列内、两行左/右缘差 0px%n",
                13, labelSets.length, combos, PsdLcdPainter.MIN_LABEL_GAP_PX);
        System.out.printf("  最紧的一处：%s，间隙 %.2f px%n", worstGapText, worstGap);

        // 报告用明细：1 段（最窄）/ 2 段 / 3 段 / 设计稿比例
        for (final float[] view : new float[][]{{PsdLcdLayout.PX_PER_BLOCK, PsdLcdLayout.HEIGHT_PX},
                {PsdLcdLayout.PX_PER_BLOCK * 2, PsdLcdLayout.HEIGHT_PX},
                {PsdLcdLayout.PX_PER_BLOCK * 3, PsdLcdLayout.HEIGHT_PX},
                {3072.0F, 448.0F}}) {
            final PsdLcdPainter.ArrivalColumns cols = PsdLcdPainter.arrivalColumns(
                    "开往", "Bound for", "下一趟开往", "Next train", assets, view[0], view[1]);
            System.out.printf("  %.0f×%.0f：labelX %.1f  labelW %.1f（下一趟开往 %.1f）  gap %.1f  "
                            + "nameX %.1f  nameRight %.1f  nameW %.1f%n",
                    view[0], view[1], cols.labelX(), cols.labelW(),
                    PsdLcdPainter.labelInkRight("下一趟开往", true, assets, view[0], view[1], cols)
                            - cols.labelX(),
                    cols.gap(), cols.nameX(), cols.nameRight(), cols.nameW());
            for (final String name : new String[]{"第一码头枢纽站", "厦门北站附属客运中心东广场"}) {
                final PsdLcdPainter.Fitted f = PsdLcdPainter.fitColumn(
                        PsdLcdPainter.Column.DEST_ZH, name, assets, view[0], view[1], cols);
                System.out.printf("    终点站 \"%s\"：%.1fpx%s  墨迹 %.2f…%.2f（列 %.2f…%.2f）%n",
                        name, f.size(), f.truncated() ? " 截断" : "",
                        PsdLcdPainter.inkLeft(PsdLcdPainter.Column.DEST_ZH, f, view[0], cols),
                        PsdLcdPainter.inkRight(PsdLcdPainter.Column.DEST_ZH, f, view[0], cols),
                        cols.nameX(), cols.nameRight());
            }
        }
    }

    // ================================================================ 已到站

    private static void checkArrived(final File mod) throws IOException {
        // ---- 档位
        final long[][] arrivedCases = {{-3600L, 0L}, {-5L, 0L}, {0L, 0L}, {-1L, 1L}, {0L, 1L}};
        for (final long[] c : arrivedCases) {
            final boolean arriving = c[1] != 0L;
            final ArrivalCountdown.State state = ArrivalCountdown.state(c[0], arriving);
            check(state == ArrivalCountdown.State.ARRIVED,
                    "剩余 " + c[0] + "s（arriving=" + arriving + "）没判成已到站：" + state);
            check(ArrivalCountdown.digits(state, c[0]).isEmpty(),
                    "已到站还带着数字：" + ArrivalCountdown.digits(state, c[0]));
            check(ArrivalCountdown.unitKey(state) == null, "已到站还带着单位");
        }
        System.out.println("  剩余 ≤ 0（含 -3600 / -5 / 0，arriving 开与关）→ ARRIVED，且无数字、无单位：通过");

        // ---- 两种模式在 1s…2h 之间都不会写出「0」
        int checked = 0;
        for (long remaining = 1L; remaining <= 7200L; remaining++) {
            for (final boolean arriving : new boolean[]{false, true}) {
                final ArrivalCountdown.State state = ArrivalCountdown.state(remaining, arriving);
                check(state != ArrivalCountdown.State.ARRIVED,
                        "剩余 " + remaining + "s 被误判成已到站");
                final String digits = ArrivalCountdown.digits(state, remaining);
                if (state == ArrivalCountdown.State.ARRIVING) {
                    check(digits.isEmpty(), "即将进站还带着数字：" + digits);
                } else {
                    final int value = Integer.parseInt(digits);
                    check(value >= 1, "写出了 0（" + remaining + "s ⇒ \"" + digits + "\"）");
                    check(!digits.startsWith("0"), "数字带前导 0：" + digits);
                }
                checked++;
            }
        }
        System.out.printf("  1…7200 秒 × arriving 开/关（%d 组）：秒/分钟档的数字恒 ≥ 1，不出现「0秒」「0 min」%n",
                checked);

        // ---- 文案：语言键 → 语言文件
        check("gui.mtrlte.pids.arrived".equals(
                        ArrivalCountdown.stateKey(ArrivalCountdown.State.ARRIVED, false)),
                "PIDS 已到站的语言键不对");
        check("gui.mtrlte.psd_lcd.arrived".equals(
                        ArrivalCountdown.stateKey(ArrivalCountdown.State.ARRIVED, true)),
                "PSD-LCD 已到站的语言键不对");

        final File lang = new File(mod, "src/main/resources/assets/mtrlte/lang");
        final String zh = read(new File(lang, "zh_cn.json"));
        final String en = read(new File(lang, "en_us.json"));
        expect(zh, "gui.mtrlte.pids.arrived", "已到站");
        expect(zh, "gui.mtrlte.psd_lcd.arrived", "已到站");
        expect(en, "gui.mtrlte.pids.arrived", "Arrived");
        expect(en, "gui.mtrlte.psd_lcd.arrived", "Arrived");
        expect(zh, "gui.mtrlte.pids.arriving", "即将进站");
        expect(en, "gui.mtrlte.pids.arriving", "Arriving");
        expect(zh, "gui.mtrlte.pids.minute", "分钟");
        expect(en, "gui.mtrlte.pids.minute", "min");
        expect(zh, "gui.mtrlte.pids.second", "秒");
        expect(en, "gui.mtrlte.pids.second", "s");
        System.out.println("  文案：gui.mtrlte.pids.arrived = 已到站 / Arrived，"
                + "gui.mtrlte.psd_lcd.arrived = 已到站 / Arrived（中英都核过）");
    }

    /** 语言文件里 {@code "key": "value"} 必须是期望值。 */
    private static void expect(final String json, final String key, final String value) {
        final Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (!m.find()) {
            fail("语言文件缺少键：" + key);
            return;
        }
        check(value.equals(m.group(1)),
                "语言键 " + key + " = \"" + m.group(1) + "\"，应为 \"" + value + "\"");
    }

    // ================================================================ 出图

    /**
     * 两张 3 段挂板：长站名 + 到站状态。
     *
     * <p>不是断言，是给人看的 —— 断言在上面那些数字里。图里的每一行都走 {@link PsdLcdPainter#line}，
     * 所以「长站名缩到 62% / 尾部 …」在图上是什么样，屏上就是什么样。
     */
    private static void renderSamples(final PsdLcdPainter.Assets assets, final File out) throws IOException {
        final int w = Math.round(PsdLcdLayout.PX_PER_BLOCK * 3);
        final int h = Math.round(PsdLcdLayout.HEIGHT_PX);
        final String[][] samples = {
                {"psd_lcd_fit_long_zh.png", "厦门北站附属客运中心", "第一码头枢纽站", "已到站", "分钟", "厦门北站附属客运中心"},
                {"psd_lcd_fit_long_en.png", "Xiamen North Railway Station", "No.1 Wharf Junction", "Arrived", "min", "Xiamen North Railway Station"},
        };
        for (final String[] s : samples) {
            // 第一行是「已到站」（文字状态 → 没有单位，和 unitOf 一致），第二行是「9 分钟」
            final PsdLcdPainter.Content content = new PsdLcdPainter.Content(
                    "2026-11-20", "星期五", "Friday", "18:42",
                    "开往", "Bound for", s[1], s[1], s[3],
                    "下一趟开往", "Next train", s[2], s[2], "9",
                    "", "",
                    s[4], s[4],
                    "6号线", "Line 6", 0xFFE8322E,
                    s[5], s[5],
                    java.util.Arrays.asList("1", "2", "3", "4", "5", "6"),
                    "注：编组按线路实际运营车辆读取",
                    "欢迎乘坐厦门地铁，本次列车开往" + s[5],
                    "广告位");
            final BufferedImage image = PsdLcdPainter.paint(content, assets, null, w, h);
            final File file = new File(out, s[0]);
            javax.imageio.ImageIO.write(image, "png", file);
            System.out.println("  " + file.getPath());
        }
    }

    // ================================================================ 工具

    private static BufferedImage load(final File file) throws IOException {
        return file.exists() ? javax.imageio.ImageIO.read(file) : null;
    }

    private static String read(final File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void check(final boolean ok, final String message) {
        if (!ok) {
            fail(message);
        }
    }

    private static void fail(final String message) {
        failures++;
        FAILED.add(message);
    }
}
