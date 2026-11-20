/*
 * 厦门 PIDS 编组条叠加层的离线预览（不开游戏、不进 Gradle）
 * ======================================================
 *
 * 直接调 {@code PidsXiamenConsist#paint} + {@code #layout}（游戏里渲染器调的就是这两个），
 * 所以**预览对得上就等于游戏里对得上**。输出：
 *
 *   1. `consist_overlay_<n>.png`  —— 2 / 4 / 6 / 8 / 13 节五档叠加层（1332×132，4×，
 *                                    只有图形：底带 + 卡片 + 雪花，没有任何文字）
 *   2. `consist_compare.png`      —— 五档 + 底图原条放同一张图里，**并把这五个「N车」按
 *                                    布局摆上去**（AWT 模拟 MC 的 TextRenderer 位置），
 *                                    这样一眼能看出文字有没有、压在哪儿
 *   3. `consist_compare_1x.png`   —— 同一像素尺寸（1:1，333×33）：底图原条 / 我们的 6 节
 *   4. `consist_card_1to1.png`    —— <b>1:1 像素对比</b>：左边「底图原卡 8× 最近邻放大」，
 *                                    右边「我们叠加层第 1 张卡 2× 最近邻放大」（同为 8× 观感），
 *                                    并打印逐像素一致率 —— 证明切片没被缩放、没插值、没绿竖条
 *   5. `consist_layout_debug.png` —— 只给**看位置**用：在叠加层上框出每张卡片的文字盒
 *
 * 另外打印：条区矩形（像素 + 归一化）、各档卡片几何（步长 / 重叠 / 文字锚点）、
 * 底带三段宽度、切片矩形、垂直布局数值，以及**越界断言**（卡片 / 雪花 / 文字是否都在带内）。
 *
 * 用法：
 *   ./tools/pids-xiamen/preview.sh            # 输出到 v1.20.1/temp/
 *   ./tools/pids-xiamen/preview.sh /tmp/out   # 输出到指定目录
 */
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/** 编组条叠加层的离线预览 + 与原图的 1:1 像素对比。 */
public final class XiamenConsistPreview {

    private static final int[] CARS = {2, 4, 6, 8, 13};

    private static final Font LABEL = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final FontRenderContext FRC = new FontRenderContext(null, true, false);

    private XiamenConsistPreview() {
    }

    public static void main(final String[] args) throws IOException {
        final File mod = new File(args.length > 0 ? args[0] : ".");
        final File out = new File(args.length > 1 ? args[1] : new File(mod, "temp").getPath());
        if (!out.exists() && !out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }

        final File texDir = new File(mod, "src/main/resources/assets/mtrlte/textures");
        final BufferedImage panel = ImageIO.read(new File(texDir, "block/pids_xiamen_metro.png"));
        final BufferedImage band = ImageIO.read(new File(texDir, "gui/pids/consist_band.png"));
        final BufferedImage card = ImageIO.read(new File(texDir, "gui/pids/consist_card.png"));
        final BufferedImage flake = ImageIO.read(new File(texDir, "gui/pids/consist_flake.png"));
        if (panel == null || band == null || card == null || flake == null) {
            throw new IOException("素材缺失（panel / consist_band / consist_card / consist_flake）"
                    + " —— 先跑 node tools/pids-xiamen/scan-consist-strip.js --emit");
        }

        System.out.println("== 编组条几何 ==");
        final float[] strip = PidsXiamenConsist.strip();
        final float[] norm = PidsXiamenConsist.stripNormalized();
        System.out.printf("  矩形(px)      x %.0f..%.0f  y %.0f..%.0f   (%.0f × %.0f)%n",
                strip[0], strip[0] + strip[2], strip[1], strip[1] + strip[3], strip[2], strip[3]);
        System.out.printf("  归一化        x %.4f..%.4f  y %.4f..%.4f%n",
                norm[0], norm[0] + norm[2], norm[1], norm[1] + norm[3]);
        System.out.printf("  叠加层分辨率  %d × %d  (%d×)%n",
                PidsXiamenConsist.overlayWidth(), PidsXiamenConsist.overlayHeight(),
                PidsXiamenConsist.SCALE);

        System.out.println();
        System.out.println("== 切片（像素级切自底图，原尺寸不缩放）==");
        System.out.printf("  卡片  consist_card.png   %d × %d   底图 x %d..%d  y %d..%d%n",
                card.getWidth(), card.getHeight(),
                PidsXiamenConsist.CARD_SRC_X,
                PidsXiamenConsist.CARD_SRC_X + PidsXiamenConsist.CARD_SRC_W - 1,
                PidsXiamenConsist.CARD_SRC_Y,
                PidsXiamenConsist.CARD_SRC_Y + PidsXiamenConsist.CARD_SRC_H - 1);
        System.out.printf("  雪花  consist_flake.png  %d × %d   底图 x %d..%d  y %d..%d%n",
                flake.getWidth(), flake.getHeight(),
                PidsXiamenConsist.FLAKE_SRC_X,
                PidsXiamenConsist.FLAKE_SRC_X + PidsXiamenConsist.FLAKE_SRC_W - 1,
                PidsXiamenConsist.FLAKE_SRC_Y,
                PidsXiamenConsist.FLAKE_SRC_Y + PidsXiamenConsist.FLAKE_SRC_H - 1);
        System.out.printf("  4× 后：卡片 %d × %d，雪花 %d × %d（块复制，不插值）%n",
                PidsXiamenConsist.CARD_SRC_W * PidsXiamenConsist.SCALE,
                PidsXiamenConsist.CARD_SRC_H * PidsXiamenConsist.SCALE,
                PidsXiamenConsist.FLAKE_SRC_W * PidsXiamenConsist.SCALE,
                PidsXiamenConsist.FLAKE_SRC_H * PidsXiamenConsist.SCALE);

        System.out.println();
        System.out.println("== 垂直布局（面板 px；底带 0..32，内圈深蓝 2..31）==");
        System.out.printf("  卡片      y %.0f..%.0f  (%d 行)   → 4× 设备像素 y %d..%d%n",
                PidsXiamenConsist.CARD_TOP,
                PidsXiamenConsist.CARD_TOP + PidsXiamenConsist.CARD_H - 1,
                (int) PidsXiamenConsist.CARD_H,
                Math.round(PidsXiamenConsist.CARD_TOP * 4),
                Math.round((PidsXiamenConsist.CARD_TOP + PidsXiamenConsist.CARD_H) * 4));
        System.out.printf("  雪花      y %.0f..%.0f  (%d 行)   → 4× 设备像素 y %d..%d%n",
                PidsXiamenConsist.FLAKE_TOP,
                PidsXiamenConsist.FLAKE_TOP + PidsXiamenConsist.FLAKE_SRC_H - 1,
                PidsXiamenConsist.FLAKE_SRC_H,
                Math.round(PidsXiamenConsist.FLAKE_TOP * 4),
                Math.round((PidsXiamenConsist.FLAKE_TOP + PidsXiamenConsist.FLAKE_SRC_H) * 4));
        System.out.printf("  「N车」    y %.0f..%.0f  (%d 行)   → 4× 设备像素 y %d..%d（基线在带内）%n",
                PidsXiamenConsist.CAR_TEXT_TOP,
                PidsXiamenConsist.CAR_TEXT_TOP + PidsXiamenConsist.CAR_TEXT_INK_H,
                (int) PidsXiamenConsist.CAR_TEXT_INK_H,
                Math.round(PidsXiamenConsist.CAR_TEXT_TOP * 4),
                Math.round((PidsXiamenConsist.CAR_TEXT_TOP + PidsXiamenConsist.CAR_TEXT_INK_H) * 4));

        final int[] seg = PidsXiamenConsist.bandSegments(band);
        final int bodyW = PidsXiamenConsist.bandBodyWidth(band);
        System.out.printf("%n  底带素材      %d × %d（三段铺）  左 %d / 中 %d（拉伸）/ 收边 %d"
                        + "   合计 = 带体 %d 列%n",
                band.getWidth(), band.getHeight(), seg[0], seg[1], seg[2], bodyW);
        System.out.printf("  带体右缘      素材 x %d（带子能画到的最远处）；素材 x %d..%d 那 %d 列是青色光晕 → 不画%n",
                bodyW - 1, bodyW, band.getWidth() - 1, band.getWidth() - bodyW);
        System.out.printf("  带外底色      PANEL_BG = 0x%08X = RGB(%d,%d,%d)%n",
                PidsXiamenConsist.PANEL_BG,
                (PidsXiamenConsist.PANEL_BG >> 16) & 0xFF,
                (PidsXiamenConsist.PANEL_BG >> 8) & 0xFF,
                PidsXiamenConsist.PANEL_BG & 0xFF);

        System.out.println();
        System.out.println("== 排版（行左对齐 210，长编组只压 x 宽度，不重叠 / 不越界）==");
        System.out.printf("  行区 %.0f..%.0f（%d px）  卡片自然 %d×%d  宽下限 %.0f  gap %.1f→%.0f/%.0f%n",
                PidsXiamenConsist.ROW_X0, PidsXiamenConsist.ROW_X1,
                (int) PidsXiamenConsist.ROW_W, (int) PidsXiamenConsist.CARD_W,
                (int) PidsXiamenConsist.CARD_H, PidsXiamenConsist.MIN_CARD_W,
                PidsXiamenConsist.GAP, PidsXiamenConsist.GAP_MIN,
                PidsXiamenConsist.GAP_HARD_MIN);
        boolean allInside = true;
        boolean allNoOverlap = true;
        boolean allLeftAligned = true;
        boolean allFlakeSemantics = true;
        for (final int cars : CARS) {
            final PidsXiamenConsist.Card[] cards = PidsXiamenConsist.layout(cars);
            final PidsXiamenConsist.Card first = cards[0];
            final PidsXiamenConsist.Card last = cards[cards.length - 1];
            final PidsXiamenConsist.Card mid = cards[cards.length / 2];
            final float cw = first.w();
            final float gap = cards.length > 1 ? cards[1].x0() - first.x1() : 0.0F;
            boolean inside = true;
            boolean noOverlap = true;
            boolean flakeSemantics = true;
            for (int i = 0; i < cards.length; i++) {
                inside &= PidsXiamenConsist.insideBand(cards[i]);
                if (i > 0) {
                    noOverlap &= cards[i].x0() >= cards[i - 1].x1() - 0.001F;
                }
                // 首 / 尾车 1 颗，中间车永远 2 颗；雪花组整块在自己的格子里
                final int want = (i == 0 || i == cards.length - 1) ? 1 : 2;
                flakeSemantics &= cards[i].flakes() == want;
                flakeSemantics &= cards[i].flakeW() >= PidsXiamenConsist.FLAKE_MIN_W
                        && cards[i].flakeW() <= PidsXiamenConsist.FLAKE_W;
                flakeSemantics &= cards[i].flakeX0() >= cards[i].x0()
                        && cards[i].flakeX0() + cards[i].snowW() <= cards[i].x1() + 0.001F;
            }
            final boolean leftAligned = Math.abs(first.x0() - PidsXiamenConsist.ROW_X0) < 0.001F;
            allInside &= inside;
            allNoOverlap &= noOverlap;
            allLeftAligned &= leftAligned;
            allFlakeSemantics &= flakeSemantics;
            System.out.printf("  %2d 节：%2d 张  cw %5.2f × %.0f  gap %4.2f  首卡 x %.1f  末卡右缘 %.1f"
                            + "（行 210..483，右侧余 %4.1f）  重叠 %s  越界 %s%n",
                    cars, cards.length, cw, PidsXiamenConsist.CARD_H, gap,
                    first.x0(), last.x1(), PidsXiamenConsist.ROW_X1 + 1.0F - last.x1(),
                    noOverlap ? "无" : "有！", inside ? "无" : "有！");
            System.out.printf("        雪花 首 %d 颗 @%.0fpx  中 %d 颗 @%.0fpx  尾 %d 颗 @%.0fpx"
                            + "   文字宽 首 %.1f / 中 %.1f px   颗数语义 %s%n",
                    first.flakes(), first.flakeW(), mid.flakes(), mid.flakeW(),
                    last.flakes(), last.flakeW(), first.textW(), mid.textW(),
                    flakeSemantics ? "通过" : "失败！");
        }
        System.out.println("  断言：左对齐=" + (allLeftAligned ? "通过" : "失败")
                + "  无重叠=" + (allNoOverlap ? "通过" : "失败")
                + "  无越界=" + (allInside ? "通过（卡片 / 雪花 / 文字都在底带内）" : "失败")
                + "  雪花语义=" + (allFlakeSemantics ? "通过（首尾 1 颗 / 中间 2 颗，尺寸 7→4 缩，不越界、不压字）"
                : "失败！"));

        // ---- 底带伸缩：右端必须包住最后一张卡、又不越出行区；2 节明显短于 13 节
        System.out.println();
        System.out.println("== 底带伸缩（右端 = 最后一张卡 + 收边 + 留白）==");
        boolean bandOk = true;
        float bandW2 = 0.0F;
        float bandW13 = 0.0F;
        for (final int cars : CARS) {
            final PidsXiamenConsist.Card[] row = PidsXiamenConsist.layout(cars);
            final float lastRight = row[row.length - 1].x1();
            final float bandW = PidsXiamenConsist.bandWidth(band, cars);
            final float bandRight = PidsXiamenConsist.bandRight(band, cars);
            final boolean covers = bandRight >= lastRight - 1.0e-4F;
            final boolean inside = bandRight <= PidsXiamenConsist.STRIP_X0 + PidsXiamenConsist.STRIP_W;
            bandOk &= covers && inside;
            if (cars == 2) {
                bandW2 = bandW;
            }
            if (cars == 13) {
                bandW13 = bandW;
            }
            System.out.printf("  %2d 节：底带 %6.1f px  右缘 x %.1f  末卡右缘 %.1f  余量 %+5.1f"
                            + "  行区右界 511  包住 %s  不越界 %s%n",
                    cars, bandW, bandRight, lastRight, bandRight - lastRight,
                    covers ? "是" : "否！", inside ? "是" : "否！");
        }
        System.out.printf("  2 节 %.1f px < 13 节 %.1f px：%s（差 %.1f px）%n",
                bandW2, bandW13, bandW2 < bandW13 ? "通过" : "失败！", bandW13 - bandW2);
        System.out.println("  断言：底带长度随节数 = " + (bandOk && bandW2 < bandW13 ? "通过" : "失败！"));

        final BufferedImage[] overlays = new BufferedImage[CARS.length];
        for (int i = 0; i < CARS.length; i++) {
            overlays[i] = PidsXiamenConsist.paint(band, card, flake, CARS[i]);
            if (overlays[i] == null) {
                throw new IllegalStateException(CARS[i] + " 节：叠加层没画出来（paint 返回 null）");
            }
            write(new File(out, "consist_overlay_" + CARS[i] + ".png"), overlays[i]);
        }
        final BufferedImage none = PidsXiamenConsist.paint(band, card, flake, 0);
        boolean noneBlank = none != null;
        if (none != null) {
            for (int yy = 0; yy < none.getHeight(); yy += 16) {
                for (int xx = 0; xx < none.getWidth(); xx += 97) {
                    noneBlank &= none.getRGB(xx, yy) == PidsXiamenConsist.PANEL_BG;
                }
            }
            write(new File(out, "consist_overlay_0.png"), none);
        }
        System.out.println("  读不到编组(0) → " + (noneBlank
                ? "整条行区只有面板底色（无编组条 / 无卡片 / 无雪花 / 无车号）"
                : "画了别的东西（错！）"));

        // ---- 底带右缘：bandWidth 右边一个青色像素都不许有，且必须逐字节 = PANEL_BG
        System.out.println();
        System.out.println("== 底带右缘（不许有青色块）/ 带外底色（必须 = 底图底色）==");
        boolean edgeOk = true;
        for (int i = 0; i < CARS.length; i++) {
            final int cars = CARS[i];
            final int bandDev = Math.round(PidsXiamenConsist.bandWidth(band, cars) * PidsXiamenConsist.SCALE);
            int cyan = 0;
            int other = 0;
            for (int yy = 0; yy < overlays[i].getHeight(); yy++) {
                for (int xx = Math.min(bandDev, overlays[i].getWidth()); xx < overlays[i].getWidth(); xx++) {
                    final int argb = overlays[i].getRGB(xx, yy);
                    final int r = (argb >> 16) & 0xFF;
                    final int g2 = (argb >> 8) & 0xFF;
                    final int b2 = argb & 0xFF;
                    if (r < 110 && g2 > 150 && b2 > 150) {
                        cyan++;
                    } else if (argb != PidsXiamenConsist.PANEL_BG) {
                        other++;
                    }
                }
            }
            final boolean ok = cyan == 0 && other == 0;
            edgeOk &= ok;
            System.out.printf("  %2d 节：底带右缘 %.1f px（设备 x %d）  右侧青色像素 %d（必须 0）"
                            + "  非面板底色像素 %d（必须 0）  %s%n",
                    cars, PidsXiamenConsist.bandWidth(band, cars), bandDev, cyan, other,
                    ok ? "通过" : "失败！");
        }
        if (none != null) {
            int leftOver = 0;
            for (int yy = 0; yy < none.getHeight(); yy++) {
                for (int xx = 0; xx < none.getWidth(); xx++) {
                    if (none.getRGB(xx, yy) != PidsXiamenConsist.PANEL_BG) {
                        leftOver++;
                    }
                }
            }
            edgeOk &= leftOver == 0;
            System.out.printf("   0 节：整条 1332×132 里非面板底色像素 %d（必须 0）  %s%n",
                    leftOver, leftOver == 0 ? "通过" : "失败！");
        }

        // 底图同一行（底带左边紧邻的 3 列 × 全部 33 行）逐像素核对 PANEL_BG
        // —— 证明这块色是从底图采出来的，叠加层带外那块和周围面板像素一模一样
        int bgMismatch = 0;
        for (int yy = PidsXiamenConsist.STRIP_Y0; yy < PidsXiamenConsist.STRIP_Y0 + PidsXiamenConsist.STRIP_H; yy++) {
            for (int xx = PidsXiamenConsist.STRIP_X0 - 3; xx < PidsXiamenConsist.STRIP_X0; xx++) {
                if ((panel.getRGB(xx, yy) | 0xFF000000) != PidsXiamenConsist.PANEL_BG) {
                    bgMismatch++;
                }
            }
        }
        System.out.printf("  底图 x %d..%d × y %d..%d 逐像素核对 PANEL_BG：不一致 %d 个（必须 0）  %s%n",
                PidsXiamenConsist.STRIP_X0 - 3, PidsXiamenConsist.STRIP_X0 - 1,
                PidsXiamenConsist.STRIP_Y0, PidsXiamenConsist.STRIP_Y0 + PidsXiamenConsist.STRIP_H - 1,
                bgMismatch, bgMismatch == 0 ? "通过" : "失败！");
        System.out.println("  断言：底带右缘无青色块 + 带外 = 底图底色 = "
                + (edgeOk && bgMismatch == 0 ? "通过" : "失败！"));

        // 底图里那块（1:1 裁切），做对比参照
        final BufferedImage original = copy(panel.getSubimage(
                PidsXiamenConsist.STRIP_X0, PidsXiamenConsist.STRIP_Y0,
                PidsXiamenConsist.STRIP_W, PidsXiamenConsist.STRIP_H));

        // ---- 五档放同一张图：最上面是底图原条，下面每档都是「底图原条 + 我们的叠加层压上去」
        //      （= 游戏里就是这么叠的；右边那截没有底带的空行一眼能看出来）
        final int w = PidsXiamenConsist.overlayWidth();
        final int h = PidsXiamenConsist.overlayHeight();
        final int rows = CARS.length + 2;
        final BufferedImage compare = new BufferedImage(w + 8, (h + 18) * rows + 8,
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = compare.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x20, 0x20, 0x28));
            g.fillRect(0, 0, compare.getWidth(), compare.getHeight());
            g.setFont(LABEL);

            int y = 4;
            g.setColor(new Color(0xFF, 0xC8, 0x60));
            g.drawString("底图里烘死的原条（4× 最近邻放大，参照：6 节，卡片 = 我们要切的那张）", 4, y + 13);
            g.drawImage(original, 4, y + 16, w, h, null);
            y += h + 18;

            for (int i = 0; i < CARS.length; i++) {
                final PidsXiamenConsist.Card[] row = PidsXiamenConsist.layout(CARS[i]);
                final float cw = row[0].w();
                final float gap = row.length > 1 ? row[1].x0() - row[0].x1() : 0.0F;
                g.setColor(Color.WHITE);
                g.drawString("我们的叠加层 · " + CARS[i] + " 节" + (i == 2 ? "（卡片行 = 原条那张）" : "")
                        + String.format("   底带 %.0fpx（右缘 x %.0f / 行区右界 511）  cw %.1f（%s）gap %.1f",
                        PidsXiamenConsist.bandWidth(band, CARS[i]),
                        PidsXiamenConsist.bandRight(band, CARS[i]),
                        cw, cw >= PidsXiamenConsist.CARD_W - 0.01F ? "原宽" : "x 压缩", gap)
                        + "  右端以外不画", 4, y + 13);
                // 底图原条打底（游戏里底图在下面），再压我们的叠加层 + 「N车」
                g.drawImage(original, 4, y + 16, w, h, null);
                g.drawImage(withCarLabels(overlays[i], CARS[i]), 4, y + 16, null);
                y += h + 18;
            }

            g.setColor(new Color(0xFF, 0x90, 0x90));
            g.drawString("读不到编组（cars = 0）→ 编组条整块空着：底带 / 卡片 / 雪花 / 车号一个都不画", 4, y + 13);
            g.drawImage(original, 4, y + 16, w, h, null);
            if (none != null) {
                g.drawImage(none, 4, y + 16, null);
            }
        } finally {
            g.dispose();
        }
        write(new File(out, "consist_compare.png"), compare);

        // ---- 1:1 同尺寸并排（底图原条 / 我们的 6 节）
        final BufferedImage six = overlays[2];
        final BufferedImage ourOneToOne = downscale(withCarLabels(six, 6),
                PidsXiamenConsist.STRIP_W, PidsXiamenConsist.STRIP_H);
        final int bw = PidsXiamenConsist.STRIP_W;
        final int bh = PidsXiamenConsist.STRIP_H;
        final BufferedImage compare1x = new BufferedImage(bw + 8, bh * 2 + 56,
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g1 = compare1x.createGraphics();
        try {
            g1.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g1.setColor(new Color(0x20, 0x20, 0x28));
            g1.fillRect(0, 0, compare1x.getWidth(), compare1x.getHeight());
            g1.setFont(LABEL);
            g1.setColor(Color.WHITE);
            g1.drawString("底图原条 1:1（带烘死的卡片和车号）", 4, 15);
            g1.drawImage(original, 4, 19, null);
            g1.drawString("我们的叠加层 6 节 1:1（4× 位图按区域平均降到 1:1，文字另画）", 4, bh + 33);
            g1.drawImage(ourOneToOne, 4, bh + 37, null);
        } finally {
            g1.dispose();
        }
        write(new File(out, "consist_compare_1x.png"), compare1x);

        // ---- 1:1 像素对比：底图原卡 vs 我们画出来的第 1 张卡
        final int[] identity = cardIdentity(panel, six);
        write(new File(out, "consist_card_1to1.png"), cardCompare(panel, six, identity));

        // ---- 位置自检（只在预览里用 AWT 画字，游戏里是 TextRenderer）
        write(new File(out, "consist_layout_debug.png"), layoutDebug(six, 6));

        // ---- 锐度：1:1 下平均 |Laplacian|（越高越锐）
        final double sharpOriginal = sharpness(original);
        final double sharpOurs = sharpness(ourOneToOne);
        System.out.println();
        System.out.println("== 1:1 像素对比（我们的第 1 张卡 vs 底图原卡）==");
        System.out.printf("  逐像素一致：%d / %d  (%.1f%%)   最大通道差 %d%n",
                identity[0], identity[1], 100.0 * identity[0] / Math.max(1, identity[1]), identity[2]);
        System.out.println(identity[0] == identity[1]
                ? "  → 完全一致：切片没被缩放 / 插值，也没有绿竖条"
                : "  → 有差异！检查切片矩形与块复制路径");

        System.out.println();
        System.out.println("== 锐度（1:1，平均 |Laplacian|）==");
        System.out.printf("  底图原条      %.2f%n", sharpOriginal);
        System.out.printf("  我们的 6 节   %.2f   (%.0f%% of 原图)%n",
                sharpOurs, sharpOriginal > 0.01 ? sharpOurs / sharpOriginal * 100.0 : 0.0);
        System.out.println(sharpOurs >= sharpOriginal * 0.85
                ? "  → 不比原图软（阈值 85%）"
                : "  → 偏软！需要提高分辨率或改缩放路径");

        System.out.println();
        System.out.println("预览：");
        final String[] names = {"consist_compare.png", "consist_compare_1x.png",
                "consist_card_1to1.png", "consist_layout_debug.png",
                "consist_overlay_0.png",
                "consist_overlay_2.png", "consist_overlay_4.png", "consist_overlay_6.png",
                "consist_overlay_8.png", "consist_overlay_13.png"};
        for (final String name : names) {
            final File file = new File(out, name);
            if (file.exists()) {
                System.out.println("  " + file.getPath());
            }
        }
    }

    /**
     * 按 {@link PidsXiamenConsist#layout(int)} 把「N车」画上去（<b>仅预览</b>：
     * 游戏里这一步由 MC 的 {@code TextRenderer} 做，位置读的是同一份 layout）。
     *
     * <p>右对齐到 {@code textRight}、底边压在 {@code CAR_TEXT_TOP + CAR_TEXT_INK_H}，
     * 和 {@code PidsXiamenText#drawRun} 的语义一致（它也是「底边 = boxTop + boxHeight」）。
     */
    private static BufferedImage withCarLabels(final BufferedImage overlay, final int cars) {
        final BufferedImage out = new BufferedImage(overlay.getWidth(), overlay.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.drawImage(overlay, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            for (final PidsXiamenConsist.Card card : PidsXiamenConsist.layout(cars)) {
                final String text = (card.index() + 1) + "车";
                final float inkH = PidsXiamenConsist.CAR_TEXT_INK_H * PidsXiamenConsist.SCALE;
                float size = inkH;
                Font font = fitted(new Font(Font.SANS_SERIF, Font.PLAIN, 100), text, size);
                Shape ink = outline(font, text);
                final float inkW = (float) ink.getBounds2D().getWidth();
                // 超宽就等比缩（和 drawRun 的 maxWidth 语义一致）
                final float maxW = card.textW() * PidsXiamenConsist.SCALE;
                if (inkW > maxW && maxW > 1.0F) {
                    size *= maxW / inkW;
                    font = fitted(new Font(Font.SANS_SERIF, Font.PLAIN, 100), text, size);
                    ink = outline(font, text);
                }
                final float right = (card.textRight() - PidsXiamenConsist.STRIP_X0)
                        * PidsXiamenConsist.SCALE;
                final float bottom = (PidsXiamenConsist.CAR_TEXT_TOP
                        + PidsXiamenConsist.CAR_TEXT_INK_H - PidsXiamenConsist.STRIP_Y0)
                        * PidsXiamenConsist.SCALE;
                g.setFont(font);
                g.drawString(text,
                        right - (float) ink.getBounds2D().getWidth() - (float) ink.getBounds2D().getX(),
                        bottom - (float) ink.getBounds2D().getY() - (float) ink.getBounds2D().getHeight());
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 在叠加层上画出每张卡片的文字盒（<b>仅预览</b>）。 */
    private static BufferedImage layoutDebug(final BufferedImage overlay, final int cars) {
        final BufferedImage out = withCarLabels(overlay, cars);
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (final PidsXiamenConsist.Card card : PidsXiamenConsist.layout(cars)) {
                final float inkH = PidsXiamenConsist.CAR_TEXT_INK_H * PidsXiamenConsist.SCALE;
                final float left = (card.x0() - PidsXiamenConsist.STRIP_X0)
                        * PidsXiamenConsist.SCALE;
                final float top = (PidsXiamenConsist.CAR_TEXT_TOP - PidsXiamenConsist.STRIP_Y0)
                        * PidsXiamenConsist.SCALE;
                // 文字盒（红）
                g.setColor(new Color(0xFF, 0x60, 0x60, 0xA0));
                g.drawRect(Math.round(left), Math.round(top),
                        Math.round(card.textW() * PidsXiamenConsist.SCALE), Math.round(inkH));
                // 卡片可见格（青）
                g.setColor(new Color(0x60, 0xE0, 0xFF, 0x70));
                g.drawRect(Math.round(left), Math.round(PidsXiamenConsist.CARD_TOP
                                * PidsXiamenConsist.SCALE),
                        Math.round(card.w() * PidsXiamenConsist.SCALE) - 1,
                        Math.round(PidsXiamenConsist.CARD_H * PidsXiamenConsist.SCALE) - 1);
            }
            // 底带上下内界线（绿）：y 2 / y 31
            g.setColor(new Color(0x60, 0xFF, 0x60, 0x90));
            g.drawLine(0, 2 * PidsXiamenConsist.SCALE, out.getWidth(),
                    2 * PidsXiamenConsist.SCALE);
            g.drawLine(0, 31 * PidsXiamenConsist.SCALE, out.getWidth(),
                    31 * PidsXiamenConsist.SCALE);
        } finally {
            g.dispose();
        }
        return out;
    }

    // ---------------------------------------------------------------- 1:1 像素对比

    /**
     * 逐像素比对：底图原卡的 41×20 与我们在叠加层上画出来的第 1 张卡（4× 块的中心像素）。
     *
     * <p>只比「不透明」的像素（切片里 {@code max(r,g) ≥ 96} 的那些 —— 底带深蓝被 keying
     * 成透明的边缘不算），那些必须<b>字节完全相等</b>。
     *
     * @return {@code {一致数, 参与比对数, 最大通道差}}
     */
    private static int[] cardIdentity(final BufferedImage panel, final BufferedImage overlay) {
        final int scale = PidsXiamenConsist.SCALE;
        final int cx = Math.round(PidsXiamenConsist.CARD_SRC_X - PidsXiamenConsist.STRIP_X0) * scale;
        final int cy = Math.round(PidsXiamenConsist.CARD_SRC_Y - PidsXiamenConsist.STRIP_Y0) * scale;
        int same = 0;
        int total = 0;
        int maxDelta = 0;
        for (int y = 0; y < PidsXiamenConsist.CARD_SRC_H; y++) {
            for (int x = 0; x < PidsXiamenConsist.CARD_SRC_W; x++) {
                final int src = panel.getRGB(
                        PidsXiamenConsist.CARD_SRC_X + x, PidsXiamenConsist.CARD_SRC_Y + y);
                final int sr = (src >> 16) & 0xFF;
                final int sg = (src >> 8) & 0xFF;
                if (Math.max(sr, sg) < 96) {
                    continue;                       // 深蓝底 / 抗锯齿外沿：keying 成透明，不参与比对
                }
                final int ours = overlay.getRGB(cx + x * scale + scale / 2,
                        cy + y * scale + scale / 2);
                final int d = Math.max(Math.abs(((ours >> 16) & 0xFF) - sr),
                        Math.max(Math.abs(((ours >> 8) & 0xFF) - sg),
                                Math.abs((ours & 0xFF) - (src & 0xFF))));
                total++;
                if (d == 0) {
                    same++;
                }
                maxDelta = Math.max(maxDelta, d);
            }
        }
        return new int[]{same, total, maxDelta};
    }

    /**
     * 1:1 像素对比图：左 = 底图原卡（8× 最近邻），右 = 我们画出来的第 1 张卡
     * （4× 位图再 2× 最近邻 → 同为 8× 观感，像素块对得上），逐块标注一致与否。
     */
    private static BufferedImage cardCompare(final BufferedImage panel, final BufferedImage overlay,
                                             final int[] identity) {
        final int scale = 8;
        final int cw = PidsXiamenConsist.CARD_SRC_W;
        final int ch = PidsXiamenConsist.CARD_SRC_H;
        final int pad = 12;
        final int imgW = cw * scale;
        final int imgH = ch * scale;
        final BufferedImage out = new BufferedImage(imgW * 2 + pad * 3, imgH + 60,
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x18, 0x18, 0x20));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.setFont(LABEL);
            g.setColor(new Color(0xFF, 0xC8, 0x60));
            g.drawString("底图原卡  x 210..250  y 3..22  (41 × 20)，8× 最近邻", pad, 16);

            final BufferedImage src = panel.getSubimage(PidsXiamenConsist.CARD_SRC_X,
                    PidsXiamenConsist.CARD_SRC_Y, cw, ch);
            g.drawImage(src, pad, 24, imgW, imgH, null);

            g.setColor(Color.WHITE);
            g.drawString("我们的第 1 张卡（叠加层 4× 块 → 2× 最近邻，同为 8×）", imgW + pad * 2, 16);
            final int ox = Math.round(PidsXiamenConsist.CARD_SRC_X - PidsXiamenConsist.STRIP_X0)
                    * PidsXiamenConsist.SCALE;
            final int oy = Math.round(PidsXiamenConsist.CARD_SRC_Y - PidsXiamenConsist.STRIP_Y0)
                    * PidsXiamenConsist.SCALE;
            final BufferedImage tile = overlay.getSubimage(ox, oy,
                    cw * PidsXiamenConsist.SCALE, ch * PidsXiamenConsist.SCALE);
            g.drawImage(tile, imgW + pad * 2, 24, imgW, imgH, null);

            g.setColor(identity[0] == identity[1] ? new Color(0x80, 0xFF, 0x80)
                    : new Color(0xFF, 0x80, 0x80));
            g.drawString("逐像素一致 " + identity[0] + " / " + identity[1]
                    + "（不透明像素字节相等；" + cw + "×" + ch + " = " + (cw * ch) + "）",
                    pad, imgH + 48);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void write(final File file, final BufferedImage image) throws IOException {
        ImageIO.write(image, "PNG", file);
    }

    /** 独立拷贝（{@code getSubimage} 共享栅格）。 */
    private static BufferedImage copy(final BufferedImage src) {
        final BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 区域平均降到 1:1（模拟屏上 1 面板像素 ≈ 1 屏像素时的观感）。 */
    private static BufferedImage downscale(final BufferedImage src, final int w, final int h) {
        final Image scaled = src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING);
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.drawImage(scaled, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 平均 |Laplacian|（亮度），只算内部像素。 */
    private static double sharpness(final BufferedImage img) {
        final int w = img.getWidth();
        final int h = img.getHeight();
        double sum = 0.0;
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                final double c = luma(img.getRGB(x, y));
                final double lap = 4 * c
                        - luma(img.getRGB(x - 1, y)) - luma(img.getRGB(x + 1, y))
                        - luma(img.getRGB(x, y - 1)) - luma(img.getRGB(x, y + 1));
                sum += Math.abs(lap);
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double luma(final int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    private static Shape outline(final Font font, final String text) {
        final GlyphVector gv = font.createGlyphVector(FRC, text);
        return gv.getOutline(0.0F, 0.0F);
    }

    private static Font fitted(final Font base, final String ref, final float inkH) {
        final float ih = (float) outline(base.deriveFont(100.0F), ref).getBounds2D().getHeight();
        if (ih < 0.5F) {
            return base.deriveFont(100.0F);
        }
        return base.deriveFont(Math.max(1.0F, 100.0F * inkH / ih));
    }
}
