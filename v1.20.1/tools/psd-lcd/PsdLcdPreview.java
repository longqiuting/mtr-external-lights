/*
 * 厦门 PSD-LCD（屏蔽门顶板 LCD 挂板）的离线预览 —— 不开游戏、不进 Gradle
 * ======================================================================
 *
 * 直接调 {@code PsdLcdPainter#paint}（游戏里 PsdLcdRenderer 通过 PsdLcdTextures 调的就是同一个），
 * 所以「预览对得上」就等于屏上对得上。两张图：
 *
 *   1. `psd_lcd_with_line.png` —— 有线路：6 号线 / 徐标色块 + 站名 + 两班到站 + 6 节编组 + 标语
 *   2. `psd_lcd_no_line.png`   —— **取不到线路**：徐标整块留空（不画占位块 / 占位文字）、
 *                                 没有站名 → 站名留空、没有到站 → 到站行留空、没有编组 → 编组行留空
 *   3. `psd_lcd_compare.png`   —— 两张并排（上 = 有线路，下 = 无线路），一眼看出差别
 *
 * 断言（打印）：
 *   · 无线路那张里，徐标矩形内**没有任何非底色像素**（= 那块是背景，什么都没画）；
 *   · 无线路那张里，站名矩形内也没有非底色像素（不画占位字）；
 *   · 有线路那张里，徐标矩形内**有**线路色像素（对照，证明断言确实能测出东西）。
 *
 * 用法：
 *   ./tools/psd-lcd/preview.sh            # 输出到 v1.20.1/temp/
 *   ./tools/psd-lcd/preview.sh D:/out     # 输出到指定目录
 */
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collections;

import javax.imageio.ImageIO;

/** PSD-LCD 整屏预览 + 「取不到线路就留空」的像素断言。 */
public final class PsdLcdPreview {

    /** 3 段挂板（6.857:1）—— 和游戏里 3 块连成一条时同一套比例。 */
    private static final int W = 3072;
    private static final int H = 448;

    private static final Font LABEL = new Font(Font.SANS_SERIF, Font.PLAIN, 22);

    private PsdLcdPreview() {
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

        final PsdLcdPainter.Content withLine = new PsdLcdPainter.Content(
                "2026-11-20", "星期五", "Friday", "18:42",
                "开往", "Bound for", "华侨大学", "Huaqiao University", "3",
                "下一趟开往", "Next train", "董任", "Dongren", "9",
                "分钟", "min",
                "分钟", "min",
                "6号线", "Line 6", 0xFFE8322E,
                "董任", "Dongren",
                java.util.Arrays.asList("1", "2", "3", "4", "5", "6"),
                "注：此处按线路实际运营车辆读取编组",
                "欢迎乘坐厦门地铁，本次列车开往华侨大学站，请先下后上",
                "广告位");

        // 取不到线路：名字空 + 色 alpha = 0；没有站名、没有到站、没有编组、没有标语
        final PsdLcdPainter.Content noLine = new PsdLcdPainter.Content(
                "2026-11-20", "星期五", "Friday", "18:42",
                "开往", "Bound for", "", "", "",
                "下一趟开往", "Next train", "", "", "",
                "", "",
                "", "",
                "", "", 0,
                "", "",
                Collections.emptyList(),
                "",
                "",
                "");

        final BufferedImage withLineImage = PsdLcdPainter.paint(withLine, assets, null, W, H);
        final BufferedImage noLineImage = PsdLcdPainter.paint(noLine, assets, null, W, H);
        write(new File(out, "psd_lcd_with_line.png"), withLineImage);
        write(new File(out, "psd_lcd_no_line.png"), noLineImage);

        // ---------------------------------------------------------------- 断言
        final float h = PsdLcdPainter.layoutHeight(W, H);
        final int bg = PsdLcdPainter.COLOR_BG;
        // 徐标的矩形（和画家同一套比例：x 占屏宽、y 占排版高）
        final int bx0 = Math.round(0.4392F * W);
        final int bx1 = Math.round(0.5367F * W);
        final int by0 = Math.round(0.0565F * h);
        final int by1 = Math.round(0.3273F * h);
        // 站名那块（STA_CX 0.6075 附近，上下两行）
        final int sx0 = Math.round(0.5500F * W);
        final int sx1 = Math.round(0.6660F * W);
        final int sy0 = Math.round(0.0600F * h);
        final int sy1 = Math.round(0.3200F * h);

        final int noLineBadge = countNotBg(noLineImage, bx0, bx1, by0, by1, bg);
        final int withLineBadge = countNotBg(withLineImage, bx0, bx1, by0, by1, bg);
        final int noLineStation = countNotBg(noLineImage, sx0, sx1, sy0, sy1, bg);

        System.out.println("== PSD-LCD 徐标（线路徽标）==");
        System.out.printf("  徐标矩形（屏像素）  x %d..%d  y %d..%d   (%d × %d)%n",
                bx0, bx1, by0, by1, bx1 - bx0, by1 - by0);
        System.out.printf("  有线路（6 号线，红）：非底色像素 %d（应 > 0）  %s%n",
                withLineBadge, withLineBadge > 0 ? "通过（色块 + 两行字画出来了）" : "失败！");
        System.out.printf("  无线路（route 取不到）：非底色像素 %d（必须 0）  %s%n",
                noLineBadge, noLineBadge == 0
                        ? "通过（整块留空，背景透出，无占位块 / 占位文字）" : "失败！");
        System.out.printf("  无线路时站名那块：非底色像素 %d（必须 0）  %s%n",
                noLineStation, noLineStation == 0 ? "通过（不画「当前站」占位字）" : "失败！");

        // 其余「留空」项：编组行 / 标语框都不该出现
        final int noLineCars = countNotBg(noLineImage,
                Math.round(0.3158F * W), Math.round(0.6894F * W),
                Math.round(0.4492F * h), Math.round(0.6870F * h), bg);
        final int noLineTicker = countNotBg(noLineImage,
                Math.round(0.0999F * W) + 2, Math.round(0.7171F * W) - 2,
                Math.round(0.8280F * h) + 2, Math.round(0.9756F * h) - 2, bg);
        System.out.printf("  无线路时编组行：非底色像素 %d（必须 0 —— 读不到编组整行不画）  %s%n",
                noLineCars, noLineCars == 0 ? "通过" : "失败！");
        System.out.printf("  无标语时标语条内部：非底色像素 %d（必须 0 —— 没字就不画框）  %s%n",
                noLineTicker, noLineTicker == 0 ? "通过" : "失败！");

        // ---------------------------------------------------------------- 并排对比
        final BufferedImage compare = new BufferedImage(W, H * 2 + 96 + 8,
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = compare.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x18, 0x18, 0x20));
            g.fillRect(0, 0, compare.getWidth(), compare.getHeight());
            g.setFont(LABEL);
            g.setColor(Color.WHITE);
            g.drawString("有线路：徐标（色块 + 6 号线 / Line 6）+ 站名 + 两班到站 + 6 节编组", 12, 34);
            g.drawImage(withLineImage, 0, 40, null);
            g.setColor(new Color(0xFF, 0xD0, 0x60));
            g.drawString("取不到线路：徐标整块留空（不画占位块 / 占位文字），站名 / 到站 / 编组也一并留空",
                    12, H + 74);
            g.drawImage(noLineImage, 0, H + 80, null);
        } finally {
            g.dispose();
        }
        write(new File(out, "psd_lcd_compare.png"), compare);

        System.out.println();
        System.out.println("预览：");
        for (final String name : new String[]{"psd_lcd_with_line.png", "psd_lcd_no_line.png",
                "psd_lcd_compare.png"}) {
            final File file = new File(out, name);
            if (file.exists()) {
                System.out.println("  " + file.getPath());
            }
        }
    }

    /** 矩形里与底色不同的像素数（含半透明抗锯齿边，用来判断「这块到底画没画东西」）。 */
    private static int countNotBg(final BufferedImage image, final int x0, final int x1,
                                  final int y0, final int y1, final int bg) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != (bg & 0xFFFFFF)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void write(final File file, final BufferedImage image) throws IOException {
        ImageIO.write(image, "png", file);
    }
}
