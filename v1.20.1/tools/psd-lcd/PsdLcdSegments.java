/*
 * PSD-LCD：**按面板实际段数**排编组行 / 广告区的离线验证（1 段 / 2 段 / 3 段 × 4 节 / 6 节）。
 * ==============================================================================
 *
 * 出 6 张图 + 逐条断言（全用 PsdLcdPainter / PsdLcdLayout 里那一份几何，不另算一份）：
 *
 *   1. 编组行包围盒 = { 车头 + 卡片 + 尾部「…」 }，必须留在 [rowLeft, CAR_ROW_X1] 里，
 *      不许碰到「已到站 / 即将进站」那一列（0 像素重叠）、不许碰到广告区；
 *   2. 卡片之间不许互相压（相邻卡片间隙 > 0），卡片数 = 声明节数，或者 =
 *      「压到宽度下限后还能放几节」+ 尾部「…」；
 *   3. 卡片高与节数无关（只压宽，不整体缩）；
 *   4. 广告：四边形 = adRegion（四边 0 误差）、完全落在屏幕窗口四边形里、UV 在 0…1，
 *      并且**逐像素**检查：区内每个采样点都不是底色（铺满了）、区外一圈都是底色（没溢出）。
 *
 * 用法：
 *   JAVA_HOME=… ./tools/psd-lcd/preview-segments.sh            # 输出到 v1.20.1/temp/
 *   JAVA_HOME=… ./tools/psd-lcd/preview-segments.sh D:/out
 *
 * 退出码：0 = 全部通过；1 = 有断言失败（图仍会写出来，方便看）。
 */
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/** PSD-LCD 的「段数 → 编组 / 广告」离线验证。 */
public final class PsdLcdSegments {

    /** 面板高（位图 px）：一段的窗口是 16×7 px，位图 1040×455（和游戏里一致）。 */
    private static final int H = 455;
    /** 一段的位图宽（= PX_PER_BLOCK × BITMAP_SCALE）。 */
    private static final int SEG_W = 1040;
    /** 要验的段数 / 节数。 */
    private static final int[] SEGS = {1, 2, 3};
    private static final int[] CARS = {4, 6};
    /** 底色（PsdLcdPainter.COLOR_BG）的通道值，用来判「这块画没画东西」。 */
    private static final int BG = PsdLcdPainter.COLOR_BG;

    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> LINES = new ArrayList<>();

    private PsdLcdSegments() {
    }

    public static void main(final String[] args) throws IOException {
        final File mod = new File(args.length > 0 ? args[0] : ".");
        final File out = new File(args.length > 1 ? args[1] : new File(mod, "temp").getPath());
        if (!out.exists() && !out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }

        final File tex = new File(mod, "src/main/resources/assets/mtrlte/textures/gui/psd_lcd");
        final PsdLcdPainter.Assets assets = new PsdLcdPainter.Assets(
                ImageIO.read(new File(tex, "xiamen_logo.png")),
                PsdLcdTypeface.cjk(), PsdLcdTypeface.latin(), PsdLcdTypeface.digits());
        assets.nose = ImageIO.read(new File(tex, "train_nose.png"));
        assets.carEnd = ImageIO.read(new File(tex, "car_end.png"));
        assets.carMiddle = ImageIO.read(new File(tex, "car_middle.png"));
        final BufferedImage ad = ImageIO.read(
                new File(mod, "src/main/resources/assets/mtrlte/advertisements/scenery1.png"));

        Line("== PSD-LCD：编组行 / 广告区按实际段数（1 / 2 / 3 段 × 4 / 6 节）==");
        Line("  素材：卡片 2144×1024，车头 74×180，广告 " + ad.getWidth() + "×" + ad.getHeight());

        for (final int seg : SEGS) {
            final int w = seg * SEG_W;
            for (final int cars : CARS) {
                final String name = "psd_lcd_seg" + seg + "_cars" + cars;
                final BufferedImage image = PsdLcdPainter.paint(content(cars), assets, ad, w, H, 0.30F);
                ImageIO.write(image, "png", new File(out, name + ".png"));
                Line("");
                Line("---- " + seg + " 段（位图 " + w + "×" + H + "）· 声明 " + cars + " 节 ----");
                checkCars(w, cars, assets);
                checkAd(image, w);
            }
        }

        // ---- 广告的世界四边形（facing=NORTH，剪不剪两档）
        Line("");
        Line("== 广告四边形（世界坐标，facing=NORTH，相对 anchor 那一格的最小角）==");
        for (final boolean cut : new boolean[]{false, true}) {
            for (final int seg : SEGS) {
                final float[][] q = PsdLcdLayout.adQuad(PsdLcdLayout.Face.NORTH, seg * 16.0F,
                        PsdLcdPainter.adLeft(), cut);
                final float[][] win = PsdLcdLayout.windowQuad(PsdLcdLayout.Face.NORTH,
                        0.0F, seg * 16.0F, cut);
                final String tag = (cut ? "剪后" : "整块") + " " + seg + " 段";
                Line(String.format("  %s  广告四边形 左上(%.4f, %.4f, %.4f) 左下(%.4f, %.4f, %.4f)"
                                + " 右下(%.4f, %.4f, %.4f) 右上(%.4f, %.4f, %.4f)",
                        tag, q[0][0], q[0][1], q[0][2], q[1][0], q[1][1], q[1][2],
                        q[2][0], q[2][1], q[2][2], q[3][0], q[3][1], q[3][2]));
                final boolean inside = minX(q) >= minX(win) - 1e-6 && maxX(q) <= maxX(win) + 1e-6
                        && Math.abs(q[0][1] - win[0][1]) < 1e-6 && Math.abs(q[1][1] - win[1][1]) < 1e-6
                        && Math.abs(q[0][2] - win[0][2]) < 1e-6 && Math.abs(q[2][2] - win[2][2]) < 1e-6;
                Check(inside, tag + " 广告四边形完全落在屏幕窗口四边形内（0 误差）",
                        String.format("广告 x %.4f…%.4f ⊆ 窗口 x %.4f…%.4f（y 带 / z 面同一份）",
                                minX(q), maxX(q), minX(win), maxX(win)));
            }
        }

        // ---- 报告落盘
        final File report = new File(out, "psd-lcd-segments-report.txt");
        try (PrintWriter pw = new PrintWriter(report, "UTF-8")) {
            for (final String l : LINES) {
                pw.println(l);
            }
        }
        for (final String l : LINES) {
            System.out.println(l);
        }
        System.out.println();
        System.out.println("报告：" + report.getPath());
        System.out.println("预览：" + out.getPath());
        if (!FAILURES.isEmpty()) {
            System.out.println();
            System.out.println("FAILED (" + FAILURES.size() + "):");
            for (final String f : FAILURES) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("所有断言通过。");
    }

    // ---------------------------------------------------------------- 编组行

    private static void checkCars(final int w, final int cars, final PsdLcdPainter.Assets assets) {
        final PsdLcdPainter.CarRow row = PsdLcdPainter.carRow(cars, assets, w, H);
        final float[] box = PsdLcdPainter.carsBox(cars, assets, w, H);
        final float left = PsdLcdPainter.rowLeft(w);
        final float right = 0.6894F * w;                       // CAR_ROW_X1
        final float adX0 = PsdLcdPainter.adRegion(w, H)[0];

        Line(String.format("  段数 %d · 卡片高 %.1fpx（与节数无关）· 卡宽 %.1f / 自然宽 %.1f（%.0f%%）"
                        + "· 间隙 %.1f · 车头 %.1f · 行槽 %.1fpx",
                row.segments(), row.cardH(), row.cardW(), row.cardWNat(),
                row.cardW() / row.cardWNat() * 100.0F, row.gap(), row.noseW(), row.avail()));
        Line(String.format("  显示 %d / 声明 %d 节%s，包围盒 x %.1f…%.1f  y %.1f…%.1f",
                row.shown(), row.declared(), row.degraded() ? "（压到下限 → 少画 + 尾部 …）" : "",
                box[0], box[2], box[1], box[3]));

        Check(row.segments() == Math.round(w / (float) SEG_W),
                "段数 = 位图宽 ÷ 1040", row.segments() + " 段");
        Check(row.declared() == cars, "声明节数 = 车号个数", String.valueOf(cars));
        Check(row.shown() >= 1 && row.shown() <= cars, "显示节数在 1…声明节数之间",
                row.shown() + " ≤ " + cars);
        Check(row.degraded() == (row.shown() < cars), "降级标记 = 「显示少于声明」",
                row.degraded() ? "降级（尾部有 …）" : "未降级（全部画出）");
        Check(box[0] >= left - 0.6F, "编组行左缘 ≥ 守卫线（不压「已到站 / 即将进站」）",
                String.format("%.1f ≥ %.1f", box[0], left));
        Check(box[2] <= right + 0.6F, "编组行右缘 ≤ CAR_ROW_X1（不越界）",
                String.format("%.1f ≤ %.1f", box[2], right));
        Check(box[2] < adX0, "编组行整块在广告区左边", String.format("%.1f < %.1f", box[2], adX0));

        // 卡片之间不互相压：第 i 张的右缘 ≤ 第 i+1 张的左缘
        float prevRight = Float.NEGATIVE_INFINITY;
        boolean apart = true;
        for (int i = 0; i < row.shown(); i++) {
            final float cx = row.cardsX0() + i * (row.cardW() + row.gap());
            if (i > 0 && cx - prevRight < row.gap() - 1e-3F) {
                apart = false;
            }
            prevRight = cx + row.cardW();
        }
        Check(apart, row.shown() + " 张卡片互不重叠（每张都留满间隙）",
                String.format("间隙 %.1fpx", row.gap()));
        Check(prevRight + (row.degraded() ? row.gap() + row.ellipsisW() : 0.0F) <= right + 0.6F,
                "「…」槽 / 末卡右缘 = 行右缘（不越出 CAR_ROW_X1）",
                String.format("%.1f ≤ %.1f", prevRight, right));

        // 状态文案（两行）必须和编组行 0 像素重叠
        for (final int r : new int[]{0, 1}) {
            final String[] states = {"已到站", "即将进站"};
            final float[] tb = PsdLcdPainter.textBox(PsdLcdPainter.Column.COUNT_STATE, r,
                    states[r], assets, w, H);
            final boolean noOverlap = tb.length == 0 || tb[2] <= box[0] + 1e-3F
                    || box[2] <= tb[0] + 1e-3F || tb[3] <= box[1] + 1e-3F || box[3] <= tb[1] + 1e-3F;
            Check(noOverlap, "第 " + (r + 1) + " 行「" + states[r] + "」与编组行包围盒 0 像素重叠",
                    String.format("文案 x %.1f…%.1f y %.1f…%.1f vs 车 x %.1f…%.1f",
                            tb[0], tb[2], tb[1], tb[3], box[0], box[2]));
            Check(tb[2] <= PsdLcdPainter.columnRight(PsdLcdPainter.Column.COUNT_STATE, w) + 0.5F,
                    "第 " + (r + 1) + " 行「" + states[r] + "」不出自己的列（≤ NUM_COL_R）",
                    String.format("%.1f ≤ %.1f", tb[2],
                            PsdLcdPainter.columnRight(PsdLcdPainter.Column.COUNT_STATE, w)));
        }
        // 当前站列（和编组行同一条 x 带，但在编组行上面）也不能碰
        final float[] st = PsdLcdPainter.textBox(PsdLcdPainter.Column.STATION_ZH, 0,
                "小树林", assets, w, H);
        Check(st.length == 0 || st[3] <= box[1], "「当前站」文字在编组行上方（不重叠）",
                String.format("站 y 到 %.1f vs 车 y 起 %.1f", st[3], box[1]));
    }

    // ---------------------------------------------------------------- 广告区

    private static void checkAd(final BufferedImage img, final int w) {
        final float[] region = PsdLcdPainter.adRegion(w, H);
        final int x0 = (int) region[0];
        final int y0 = (int) region[1];
        final int x1 = (int) region[2];
        final int y1 = (int) region[3];
        final float[] uv = PsdLcdPainter.adSourceUv();

        Check(y0 == 0 && y1 == H && x1 == w, "广告区 = 整屏高、右缘贴位图右缘",
                "x " + x0 + "…" + x1 + "  y " + y0 + "…" + y1);
        Check(x0 == Math.round(PsdLcdPainter.adLeft() * w), "广告区左缘 = AD_X0 × 屏宽（取整，0 误差）",
                x0 + " = round(" + PsdLcdPainter.adLeft() + " × " + w + ")");
        boolean uvOk = uv.length == 4;
        for (final float v : uv) {
            uvOk &= v >= 0.0F && v <= 1.0F;
        }
        Check(uvOk, "广告 UV 全部夹在 0…1",
                String.format("%.0f,%.0f,%.0f,%.0f", uv[0], uv[1], uv[2], uv[3]));

        // 区内必须铺满（没有底色 / 没有黑边）
        int bgInside = 0;
        int samples = 0;
        for (int iy = 0; iy <= 20; iy++) {
            for (int ix = 0; ix <= 20; ix++) {
                final int x = Math.min(x1 - 1, x0 + (int) Math.round((x1 - 1 - x0) * ix / 20.0));
                final int y = Math.min(y1 - 1, y0 + (int) Math.round((y1 - 1 - y0) * iy / 20.0));
                samples++;
                if (isBg(img.getRGB(x, y))) {
                    bgInside++;
                }
            }
        }
        Check(bgInside == 0, "广告区内铺满（" + samples + " 个采样点没有底色 / 黑边）",
                "底色采样点 " + bgInside);

        // 区外一圈必须是底色（= 广告没越过边界）。取编组行 / 标语条都没占到的 y 带，
        // 免得把「内容自己的像素」当成广告溢出。
        int dirtyOutside = 0;
        int checked = 0;
        final int bandY0 = Math.round(0.02F * H);
        final int bandY1 = Math.round(0.40F * H);
        for (int x = Math.max(0, x0 - 6); x < x0; x++) {
            for (int y = bandY0; y <= bandY1; y += 3) {
                checked++;
                if (!isBg(img.getRGB(x, y))) {
                    dirtyOutside++;
                }
            }
        }
        Check(dirtyOutside == 0, "广告区左边界外侧 " + (x0 - Math.max(0, x0 - 6)) + " 列全是底色"
                + "（广告没有溢出到左边界外）", "脏像素 " + dirtyOutside + " / " + checked);

        // 上 / 下边界：广告区就是整屏高，越界的唯一可能是「上下留边」—— 四角必须是广告色
        int cornerBad = 0;
        for (final int[] p : new int[][]{{x0, 0}, {x1 - 1, 0}, {x0, y1 - 1}, {x1 - 1, y1 - 1}}) {
            if (isBg(img.getRGB(p[0], p[1]))) {
                cornerBad++;
            }
        }
        Check(cornerBad == 0, "广告区四角都是广告色（四边严丝合缝，没有留边）", "底色角 " + cornerBad);
    }

    private static boolean isBg(final int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        return Math.abs(r - ((BG >> 16) & 0xFF)) <= 8
                && Math.abs(g - ((BG >> 8) & 0xFF)) <= 8
                && Math.abs(b - (BG & 0xFF)) <= 12;
    }

    // ---------------------------------------------------------------- 内容

    private static PsdLcdPainter.Content content(final int cars) {
        final List<String> numbers = new ArrayList<>(
                Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8").subList(0, cars));
        return new PsdLcdPainter.Content(
                "2026-11-21", "星期六", "Saturday", "12:50",
                "开往", "Bound for", "华侨大学", "Huaqiao University", "已到站",
                "下一趟开往", "Next train", "董任", "Dongren", "即将进站",
                "分钟", "min", "分钟", "min",
                "6号线", "Line 6", 0xFFE8322E,
                "小树林", "M forest",
                numbers,
                "注：编组按线路实际运营车辆",
                "终有一天，无论多远，都会抵达。",
                "");
    }

    // ---------------------------------------------------------------- 输出

    private static void Line(final String s) {
        LINES.add(s);
    }

    /** 四边形四点的最小 / 最大 x（facing 决定铺开方向，可能递减，所以不能直接取下标的 x）。 */
    private static float minX(final float[][] quad) {
        float v = quad[0][0];
        for (final float[] p : quad) {
            v = Math.min(v, p[0]);
        }
        return v;
    }

    private static float maxX(final float[][] quad) {
        float v = quad[0][0];
        for (final float[] p : quad) {
            v = Math.max(v, p[0]);
        }
        return v;
    }

    private static void Check(final boolean cond, final String label, final String detail) {
        final String line = (cond ? "  PASS  " : "  FAIL  ") + label + (detail == null ? "" : "   " + detail);
        LINES.add(line);
        if (!cond) {
            FAILURES.add(label);
        }
    }

    /** 未使用：留着给将来的字体探针用（避免 import 警告）。 */
    static void probe(final Font font) {
        final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.dispose();
    }
}
