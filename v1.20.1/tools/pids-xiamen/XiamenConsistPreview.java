/*
 * 厦门 PIDS 编组条叠加层的离线预览（不开游戏、不进 Gradle）
 * ======================================================
 *
 * 直接调 {@code PidsXiamenConsist#paint} / {@code #layout} / {@code #contentWidth} /
 * {@code #bandX0}（游戏里渲染器调的就是同一份），所以**预览对得上就等于游戏里对得上**。输出：
 *
 *   1. `consist_overlay_<n>.png`  —— 2 / 4 / 6 / 8 / 13 节五档叠加层（1332×132，4×，透明底，
 *                                    只有图形：底带 + 卡片 + 雪花，没有任何文字）
 *   2. `consist_compare.png`      —— 每档「底图条区 + 我们的叠加层 + N车」压成一张（= 游戏里就是这么叠的），
 *                                    最后一行是 cars = 0（读不到编组）—— 应该只剩底图的青蓝色
 *   3. `consist_compare_1x.png`   —— 1:1 像素（333×33）：底图原条区 / 我们 6 节 / 我们 2 节
 *   4. `consist_layout_debug.png` —— 只给**看位置**用：在叠加层上框出每张卡片的文字盒
 *
 * 另外打印：条区矩形（像素 + 归一化）、各档卡片几何（步长 / 重叠 / 文字锚点）、
 * 底带三段宽度、内容宽 / 居中左右余量，以及断言：
 *   · 底带以外**一个非透明像素都没有**（条区里只有带子和卡片，其余透出底图）；
 *   · 整段左右余量相等（水平居中），内容宽不得超过条区宽；
 *   · 合成后条区里底带以外的像素与底图**逐字节相同**（背景恒青蓝、无拼缝）；
 *   · cars = 0 时叠加层全透明（编组条留空）。
 *
 * 用法：
 *   ./tools/pids-xiamen/preview.sh            # 输出到 v1.20.1/temp/
 *   ./tools/pids-xiamen/preview.sh /tmp/out   # 输出到指定目录
 */
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/** 编组条叠加层的离线预览 + 居中 / 透明 / 背景不变的断言。 */
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
            throw new IOException("素材缺失（panel / consist_band / consist_card / consist_flake）");
        }

        System.out.println("== 条区几何 ==");
        final float[] strip = PidsXiamenConsist.strip();
        final float[] norm = PidsXiamenConsist.stripNormalized();
        System.out.printf("  矩形(px)      x %.0f..%.0f  y %.0f..%.0f   (%.0f × %.0f)%n",
                strip[0], strip[0] + strip[2], strip[1], strip[1] + strip[3], strip[2], strip[3]);
        System.out.printf("  归一化        x %.4f..%.4f  y %.4f..%.4f%n",
                norm[0], norm[0] + norm[2], norm[1], norm[1] + norm[3]);
        System.out.printf("  叠加层分辨率  %d × %d  (%d×)   叠在底图同一块矩形上，其余像素透明%n",
                PidsXiamenConsist.overlayWidth(), PidsXiamenConsist.overlayHeight(),
                PidsXiamenConsist.SCALE);
        System.out.printf("  底图条区颜色  (44,242,255) 一类的青蓝 —— 叠加层不铺任何底色，一律透出来%n");

        final int[] seg = PidsXiamenConsist.bandSegments(band);
        final int bodyW = PidsXiamenConsist.bandBodyWidth(band);
        System.out.printf("%n  底带素材      %d × %d（三段铺）  左 %d / 中 %d（拉伸）/ 收边 %d   合计 = 带体 %d 列%n",
                band.getWidth(), band.getHeight(), seg[0], seg[1], seg[2], bodyW);
        System.out.printf("  青色余边      带体右缘 = 素材 x %d；素材 x %d..%d 那 %d 列青色 → **不画**（底图透出来）%n",
                bodyW - 1, bodyW, band.getWidth() - 1, band.getWidth() - bodyW);
        System.out.printf("  墨迹左内边    素材 x 0..%d 全是青色 → 画的时候透明，**不算进带子长度**"
                        + "（居中要减掉它，否则带子整体右移 %.1fpx）%n",
                PidsXiamenConsist.bandInkInset() - 1, PidsXiamenConsist.bandInkInset() * 0.5F);
        System.out.printf("  卡片行起点    带左缘 + %d px（= 墨迹左边 + %dpx）"
                        + "   尾段 tailPad = %.0fpx（与左边等宽 → 卡片行在墨迹里居中）%n",
                PidsXiamenConsist.ROW_INSET,
                PidsXiamenConsist.ROW_INSET - PidsXiamenConsist.bandInkInset(),
                PidsXiamenConsist.tailPad());
        System.out.printf("  卡片行可用宽  %.0f px（条区宽 %d − 左内边 %d − 尾段 %.0f）%n",
                PidsXiamenConsist.rowAvail(), PidsXiamenConsist.STRIP_W,
                PidsXiamenConsist.ROW_INSET, PidsXiamenConsist.tailPad());

        System.out.println();
        System.out.println("== 居中（基准 = 条区矩形 179..512；量的是**带子墨迹**的左右余量）==");
        boolean allCentered = true;
        boolean allInside = true;
        boolean allNoOverlap = true;
        boolean allFlakeSemantics = true;
        for (final int cars : CARS) {
            final PidsXiamenConsist.Card[] cards = PidsXiamenConsist.layout(cars);
            final PidsXiamenConsist.Card first = cards[0];
            final PidsXiamenConsist.Card last = cards[cards.length - 1];
            final PidsXiamenConsist.Card mid = cards[cards.length / 2];
            final float cw = first.w();
            final float gap = cards.length > 1 ? cards[1].x0() - first.x1() : 0.0F;
            final float contentW = PidsXiamenConsist.contentWidth(cars);
            final float x0 = PidsXiamenConsist.bandX0(cars);
            final float[] margins = PidsXiamenConsist.bandMargins(cars);
            final float leftPad = margins[0];
            final float rightPad = margins[1];
            final float inkW = contentW - PidsXiamenConsist.bandInkInset();
            boolean inside = true;
            boolean noOverlap = true;
            boolean flakeSemantics = true;
            for (int i = 0; i < cards.length; i++) {
                inside &= PidsXiamenConsist.insideBand(cards[i]);
                if (i > 0) {
                    noOverlap &= cards[i].x0() >= cards[i - 1].x1() - 0.001F;
                }
                final int want = (i == 0 || i == cards.length - 1) ? 1 : 2;
                flakeSemantics &= cards[i].flakes() == want;
                flakeSemantics &= cards[i].flakeW() >= PidsXiamenConsist.FLAKE_MIN_W
                        && cards[i].flakeW() <= PidsXiamenConsist.FLAKE_W;
                flakeSemantics &= cards[i].flakeX0() >= cards[i].x0()
                        && cards[i].flakeX0() + cards[i].snowW() <= cards[i].x1() + 0.001F;
            }
            final boolean centered = Math.abs(leftPad - rightPad) <= 1.0F
                    && contentW <= PidsXiamenConsist.STRIP_W + 1.0e-4F;
            allCentered &= centered;
            allInside &= inside;
            allNoOverlap &= noOverlap;
            allFlakeSemantics &= flakeSemantics;
            System.out.printf("  %2d 节：%2d 张  cw %5.2f × %.0f  gap %4.2f  内容宽 %5.1f（墨迹 %5.1f）  x0 %.1f"
                            + "   左余 %4.1f / 右余 %4.1f（差 %4.1f）  居中 %s  重叠 %s  越界 %s%n",
                    cars, cards.length, cw, PidsXiamenConsist.CARD_H, gap, contentW, inkW, x0,
                    leftPad, rightPad, Math.abs(leftPad - rightPad), centered ? "是" : "否！",
                    noOverlap ? "无" : "有！", inside ? "无" : "有！");
            System.out.printf("        首卡 x %.1f  末卡右缘 %.1f  卡行中心 %.1f（条区中心 %.1f）"
                            + "  雪花 首 %d 颗 @%.0fpx / 中 %d 颗 @%.0fpx   颗数语义 %s%n",
                    first.x0(), last.x1(), (first.x0() + last.x1()) * 0.5F,
                    PidsXiamenConsist.STRIP_X0 + PidsXiamenConsist.STRIP_W * 0.5F,
                    first.flakes(), first.flakeW(), mid.flakes(), mid.flakeW(),
                    flakeSemantics ? "通过" : "失败！");
        }
        System.out.println("  断言：水平居中（|左余 − 右余| ≤ 1px）=" + (allCentered ? "通过" : "失败！")
                + "  无重叠=" + (allNoOverlap ? "通过" : "失败！")
                + "  无越界=" + (allInside ? "通过（卡片 / 雪花 / 文字都在条区内）" : "失败！")
                + "  雪花语义=" + (allFlakeSemantics ? "通过" : "失败！"));


        // ---------------------------------------------------------------- 画叠加层
        final BufferedImage[] overlays = new BufferedImage[CARS.length];
        for (int i = 0; i < CARS.length; i++) {
            overlays[i] = PidsXiamenConsist.paint(band, card, flake, CARS[i]);
            if (overlays[i] == null) {
                throw new IllegalStateException(CARS[i] + " 节：叠加层没画出来（paint 返回 null）");
            }
            write(new File(out, "consist_overlay_" + CARS[i] + ".png"), overlays[i]);
        }
        final BufferedImage none = PidsXiamenConsist.paint(band, card, flake, 0);
        if (none == null) {
            throw new IllegalStateException("0 节：叠加层没画出来（paint 返回 null）");
        }
        write(new File(out, "consist_overlay_0.png"), none);

        final BufferedImage stripCrop = copy(panel.getSubimage(
                PidsXiamenConsist.STRIP_X0, PidsXiamenConsist.STRIP_Y0,
                PidsXiamenConsist.STRIP_W, PidsXiamenConsist.STRIP_H));

        // ---------------------------------------------------------------- 垂直 / 透明 / 背景不变
        System.out.println();
        System.out.println("== 不透明区域：墨迹边界 / 垂直不溢出 / 带外透明 + 背景逐字节不变 ==");
        boolean transparentOk = true;
        boolean bgOk = true;
        boolean verticalOk = true;
        boolean centeredPx = true;
        for (int i = 0; i < CARS.length; i++) {
            final int cars = CARS[i];
            final BufferedImage o = overlays[i];
            // 期望的墨迹列（设备 px，含）：bandX0 + 墨迹内边 … bandX0 + 内容宽 − 1
            final int inkL = Math.round((PidsXiamenConsist.bandX0(cars) - PidsXiamenConsist.STRIP_X0
                    + PidsXiamenConsist.bandInkInset()) * PidsXiamenConsist.SCALE);
            final int inkR = Math.round((PidsXiamenConsist.bandX0(cars) - PidsXiamenConsist.STRIP_X0
                    + PidsXiamenConsist.contentWidth(cars)) * PidsXiamenConsist.SCALE) - 1;
            int minX = Integer.MAX_VALUE;
            int maxX = -1;
            int maxY = -1;
            int opaque = 0;
            for (int yy = 0; yy < o.getHeight(); yy++) {
                for (int xx = 0; xx < o.getWidth(); xx++) {
                    if (((o.getRGB(xx, yy) >>> 24) & 0xFF) == 0) {
                        continue;
                    }
                    opaque++;
                    if (xx < minX) {
                        minX = xx;
                    }
                    if (xx > maxX) {
                        maxX = xx;
                    }
                    if (yy > maxY) {
                        maxY = yy;
                    }
                }
            }
            final int below = PidsXiamenConsist.opaqueBelowStrip(o);
            final float lm = minX / (float) PidsXiamenConsist.SCALE;
            final float rm = (o.getWidth() - 1 - maxX) / (float) PidsXiamenConsist.SCALE;
            final float bandBottom = (maxY + 1) / (float) PidsXiamenConsist.SCALE;
            final boolean edges = Math.abs(minX - inkL) <= 1 && Math.abs(maxX - inkR) <= 1;
            final boolean centered = Math.abs(lm - rm) <= 1.0F;
            final boolean vert = below == 0
                    && maxY <= PidsXiamenConsist.STRIP_H * PidsXiamenConsist.SCALE - 1
                    && bandBottom <= PidsXiamenConsist.STRIP_H;
            // 合成：底图条区 + 叠加层（alpha over）。墨迹以外的像素必须和底图逐字节相同。
            final BufferedImage comp = composite(stripCrop, o);
            final BufferedImage base4x = upscaleNearest(stripCrop, comp.getWidth(), comp.getHeight());
            int bgDiff = 0;
            for (int yy = 0; yy < comp.getHeight(); yy++) {
                for (int xx = 0; xx < comp.getWidth(); xx++) {
                    if (xx >= minX && xx <= maxX) {
                        continue;               // 墨迹内不比对（带子盖住了底图）
                    }
                    if ((comp.getRGB(xx, yy) & 0xFFFFFF) != (base4x.getRGB(xx, yy) & 0xFFFFFF)) {
                        bgDiff++;
                    }
                }
            }
            transparentOk &= bgDiff == 0 && opaque > 0;
            bgOk &= bgDiff == 0;
            verticalOk &= vert;
            centeredPx &= centered && edges;
            System.out.printf("  %2d 节：墨迹 x %d..%d（%.2f..%.2f 面板 px）  左右余量 %.2f / %.2f（差 %.2f）"
                            + "  带底 y %.2f（条区底 %d）  带底以下不透明 %d%n",
                    cars, minX, maxX, minX / (double) PidsXiamenConsist.SCALE,
                    maxX / (double) PidsXiamenConsist.SCALE, lm, rm, Math.abs(lm - rm),
                    bandBottom, PidsXiamenConsist.STRIP_H, below);
            System.out.printf("        不透明 %d px  墨迹边界对得上 %s  左右对称 %s  垂直不溢出 %s"
                            + "  合成后墨迹外与底图不同 %d（必须 0）%n",
                    opaque, edges ? "是" : "否！", centered ? "是" : "否！",
                    vert ? "是" : "否！", bgDiff);
        }
        int noneOpaque = 0;
        for (int yy = 0; yy < none.getHeight(); yy++) {
            for (int xx = 0; xx < none.getWidth(); xx++) {
                if ((none.getRGB(xx, yy) >>> 24) != 0) {
                    noneOpaque++;
                }
            }
        }
        System.out.printf("   0 节（读不到编组）：整条 1332×132 里不透明像素 %d（必须 0）  %s%n",
                noneOpaque, noneOpaque == 0 ? "通过（编组条整块留空，底图青蓝色透出）" : "失败！");
        System.out.println("  断言：墨迹外全透明=" + (transparentOk && noneOpaque == 0 ? "通过" : "失败！")
                + "  合成后背景 = 底图=" + (bgOk ? "通过" : "失败！")
                + "  垂直不溢出（y ≥ 33 不透明 = 0）=" + (verticalOk ? "通过" : "失败！")
                + "  墨迹居中（|左余 − 右余| ≤ 1px）=" + (centeredPx ? "通过" : "失败！"));

        // ---------------------------------------------------------------- 拼成对比图
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
            g.drawString("底图条区（4× 最近邻放大，参照：纯青蓝色，没有烘死的卡片）", 4, y + 13);
            g.drawImage(stripCrop, 4, y + 16, w, h, null);
            y += h + 18;

            for (int i = 0; i < CARS.length; i++) {
                final PidsXiamenConsist.Card[] row = PidsXiamenConsist.layout(CARS[i]);
                final float cw = row[0].w();
                final float gap = row.length > 1 ? row[1].x0() - row[0].x1() : 0.0F;
                g.setColor(Color.WHITE);
                g.drawString("我们的叠加层 · " + CARS[i] + " 节"
                                + String.format("   内容宽 %.0fpx（左余 %.1f / 右余 %.1f，居中）"
                                        + "  cw %.1f（%s）gap %.1f   带外透明",
                                PidsXiamenConsist.contentWidth(CARS[i]),
                                PidsXiamenConsist.bandX0(CARS[i]) - PidsXiamenConsist.STRIP_X0,
                                PidsXiamenConsist.STRIP_X0 + PidsXiamenConsist.STRIP_W
                                        - PidsXiamenConsist.bandRight(CARS[i]),
                                cw, cw >= PidsXiamenConsist.CARD_W - 0.01F ? "原宽" : "x 压缩", gap),
                        4, y + 13);
                // 底图条区打底（游戏里底图在下面），再压我们的叠加层 + 「N车」
                g.drawImage(stripCrop, 4, y + 16, w, h, null);
                g.drawImage(withCarLabels(overlays[i], CARS[i]), 4, y + 16, null);
                y += h + 18;
            }

            g.setColor(new Color(0xFF, 0x90, 0x90));
            g.drawString("读不到编组（cars = 0）→ 编组条整块留空：底带 / 卡片 / 雪花 / 车号一个都不画，"
                    + "底图青蓝色原样", 4, y + 13);
            g.drawImage(stripCrop, 4, y + 16, w, h, null);
        } finally {
            g.dispose();
        }
        write(new File(out, "consist_compare.png"), compare);

        // ---------------------------------------------------------------- 1:1
        final BufferedImage ourSix = downscale(withCarLabels(overlays[2], 6),
                PidsXiamenConsist.STRIP_W, PidsXiamenConsist.STRIP_H);
        final BufferedImage ourTwo = downscale(withCarLabels(overlays[0], 2),
                PidsXiamenConsist.STRIP_W, PidsXiamenConsist.STRIP_H);
        final int bw = PidsXiamenConsist.STRIP_W;
        final int bh = PidsXiamenConsist.STRIP_H;
        final BufferedImage compare1x = new BufferedImage(bw + 8, bh * 3 + 82,
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g1 = compare1x.createGraphics();
        try {
            g1.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g1.setColor(new Color(0x20, 0x20, 0x28));
            g1.fillRect(0, 0, compare1x.getWidth(), compare1x.getHeight());
            g1.setFont(LABEL);
            g1.setColor(Color.WHITE);
            g1.drawString("底图条区 1:1（纯青蓝）", 4, 15);
            g1.drawImage(stripCrop, 4, 19, null);
            g1.drawString("我们的叠加层 6 节 1:1（居中，底带以外透出底图）", 4, bh + 37);
            g1.drawImage(ourSix, 4, bh + 41, null);
            g1.drawString("我们的叠加层 2 节 1:1（底带更短、整体仍在正中）", 4, bh * 2 + 59);
            g1.drawImage(ourTwo, 4, bh * 2 + 63, null);
        } finally {
            g1.dispose();
        }
        write(new File(out, "consist_compare_1x.png"), compare1x);

        // ---------------------------------------------------------------- 整屏（512×235 → 4×）
        // 底图 + 我们的叠加层（放在条区真实位置）+ 车号：一眼能看出「条区青蓝不变、带子居中」。
        for (int i = 0; i <= CARS.length; i++) {
            final int cars = i == CARS.length ? 0 : CARS[i];
            final BufferedImage overlayWithLabels = cars > 0 ? withCarLabels(overlays[i], cars) : none;
            write(new File(out, "pids_panel_" + cars + ".png"), fullPanel(panel, overlayWithLabels));
        }

        // ---------------------------------------------------------------- 整屏 png 的真实像素测量
        // 不信任计算的坐标：把刚写出来的 pids_panel_*.png 读回来，逐像素和「底图 4×」比，
        // 量出带子的左缘 / 右缘 / 带底，并断言带底以下一个像素都没被改（= 不溢出到面板蓝底）。
        System.out.println();
        System.out.println("== 整屏 png 实测（读回 pids_panel_*.png，和底图 4× 逐像素比）==");
        boolean panelOk = true;
        for (final int cars : new int[]{2, 4, 6}) {
            final BufferedImage shot = ImageIO.read(new File(out, "pids_panel_" + cars + ".png"));
            if (shot == null) {
                throw new IOException("pids_panel_" + cars + ".png 读不回来");
            }
            final int s = PidsXiamenConsist.SCALE;
            final BufferedImage base = upscaleNearest(panel, panel.getWidth() * s, panel.getHeight() * s);
            final int stripL = PidsXiamenConsist.STRIP_X0 * s;
            final int stripB = (PidsXiamenConsist.STRIP_Y0 + PidsXiamenConsist.STRIP_H) * s;
            int minX = Integer.MAX_VALUE;
            int maxX = -1;
            int maxY = -1;
            int below = 0;
            int leftOfStrip = 0;
            int rightOfStrip = 0;
            for (int yy = 0; yy < shot.getHeight(); yy++) {
                for (int xx = 0; xx < shot.getWidth(); xx++) {
                    if ((shot.getRGB(xx, yy) & 0xFFFFFF) == (base.getRGB(xx, yy) & 0xFFFFFF)) {
                        continue;                        // 和底图一样 = 没被叠加层碰过
                    }
                    if (yy >= stripB) {
                        below++;                          // ← 条区底下被改的像素（必须 0）
                        continue;
                    }
                    if (xx < stripL) {
                        leftOfStrip++;                    // ← 条区左边被改的像素（必须 0）
                        continue;
                    }
                    if (xx < minX) {
                        minX = xx;
                    }
                    if (xx > maxX) {
                        maxX = xx;
                    }
                    if (yy > maxY) {
                        maxY = yy;
                    }
                }
            }
            if (maxX < 0) {
                throw new IllegalStateException(cars + " 节：整屏里找不到叠加层的像素");
            }
            final double lm = (minX - stripL) / (double) s;
            final double rm = ((PidsXiamenConsist.STRIP_X0 + PidsXiamenConsist.STRIP_W) * s - 1 - maxX)
                    / (double) s;
            final double bandBottom = (maxY + 1) / (double) s;
            final boolean ok = below == 0 && leftOfStrip == 0 && rightOfStrip == 0
                    && Math.abs(lm - rm) <= 1.0 && bandBottom <= PidsXiamenConsist.STRIP_H;
            panelOk &= ok;
            System.out.printf("  %d 节：带左缘 x %d（设备）/ %.2f（面板，条区左缘 +%.2f）"
                            + "   带右缘 x %d / %.2f（条区右缘 −%.2f）%n",
                    cars, minX, minX / (double) s, lm, maxX, (maxX + 1) / (double) s, rm);
            System.out.printf("        带底 y %.2f（设备 %d 行结束；条区底 %.0f / 设备 %d）"
                            + "   左右余量差 %.2f px   条区下被改像素 %d、条区左被改像素 %d  %s%n",
                    bandBottom, maxY + 1, (double) PidsXiamenConsist.STRIP_H, stripB,
                    Math.abs(lm - rm), below, leftOfStrip, ok ? "通过" : "失败！");
        }
        System.out.println("  断言：整屏 png 带宽 = 条区高（带底 ≤ 33、条区下被改像素 = 0）且左右余量差 ≤ 1px="
                + (panelOk ? "通过" : "失败！"));

        // ---------------------------------------------------------------- 位置自检
        write(new File(out, "consist_layout_debug.png"), layoutDebug(overlays[2], 6));

        System.out.println();
        System.out.println("预览：");
        final String[] names = {"consist_compare.png", "consist_compare_1x.png",
                "consist_layout_debug.png",
                "pids_panel_2.png", "pids_panel_4.png", "pids_panel_6.png",
                "pids_panel_8.png", "pids_panel_13.png", "pids_panel_0.png",
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
     * 整屏预览：底图（4× 最近邻放大）+ 叠加层贴回条区真实位置（{@link PidsXiamenConsist#strip()}）。
     * <p>游戏里就是这样叠的：底图在下面，叠加层只盖住条区那一块，透明像素露出底图的青蓝色。
     */
    private static BufferedImage fullPanel(final BufferedImage panel, final BufferedImage overlay) {
        final int scale = PidsXiamenConsist.SCALE;
        final BufferedImage out = new BufferedImage(panel.getWidth() * scale,
                panel.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(panel, 0, 0, out.getWidth(), out.getHeight(), null);
            g.drawImage(overlay, PidsXiamenConsist.STRIP_X0 * scale,
                    PidsXiamenConsist.STRIP_Y0 * scale, null);
        } finally {
            g.dispose();
        }
        return out;
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
                g.setColor(new Color(0xFF, 0x60, 0x60, 0xA0));
                g.drawRect(Math.round(left), Math.round(top),
                        Math.round(card.textW() * PidsXiamenConsist.SCALE), Math.round(inkH));
                g.setColor(new Color(0x60, 0xE0, 0xFF, 0x70));
                g.drawRect(Math.round(left), Math.round(PidsXiamenConsist.CARD_TOP
                                * PidsXiamenConsist.SCALE),
                        Math.round(card.w() * PidsXiamenConsist.SCALE) - 1,
                        Math.round(PidsXiamenConsist.CARD_H * PidsXiamenConsist.SCALE) - 1);
            }
            // 内容宽左右两条界线（黄）：整段应当关于条区中心对称
            final float x0 = (PidsXiamenConsist.bandX0(cars) - PidsXiamenConsist.STRIP_X0)
                    * PidsXiamenConsist.SCALE;
            final float x1 = x0 + PidsXiamenConsist.contentWidth(cars) * PidsXiamenConsist.SCALE;
            g.setColor(new Color(0xFF, 0xE0, 0x40, 0xC0));
            g.drawLine(Math.round(x0), 0, Math.round(x0), out.getHeight());
            g.drawLine(Math.round(x1), 0, Math.round(x1), out.getHeight());
            // 条区中心线（洋红）
            g.setColor(new Color(0xFF, 0x40, 0xFF, 0xC0));
            g.drawLine(out.getWidth() / 2, 0, out.getWidth() / 2, out.getHeight());
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

    /** alpha over 合成：底（不透明）+ 叠加层（可能带 alpha）→ 新的 4× 位图。 */
    private static BufferedImage composite(final BufferedImage base, final BufferedImage over) {
        final BufferedImage out = new BufferedImage(base.getWidth() * PidsXiamenConsist.SCALE,
                base.getHeight() * PidsXiamenConsist.SCALE, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(base, 0, 0, out.getWidth(), out.getHeight(), null);
            g.drawImage(over, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 最近邻放大（只用于把底图条区升到 4× 做逐像素比对）。 */
    private static BufferedImage upscaleNearest(final BufferedImage src, final int w, final int h) {
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 区域平均降到 1:1（只给 1:1 对比图看）。 */
    private static BufferedImage downscale(final BufferedImage src, final int w, final int h) {
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static Font fitted(final Font base, final String text, final float inkH) {
        final Font probe = base.deriveFont(100.0F);
        final Shape s = outline(probe, text);
        final float ih = s == null ? 0.0F : (float) s.getBounds2D().getHeight();
        return ih < 0.5F ? probe : base.deriveFont(Math.max(1.0F, 100.0F * inkH / ih));
    }

    private static Shape outline(final Font font, final String text) {
        return font.createGlyphVector(FRC, text).getOutline(0.0F, 0.0F);
    }

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

    private static void write(final File file, final BufferedImage image) throws IOException {
        ImageIO.write(image, "png", file);
    }
}
