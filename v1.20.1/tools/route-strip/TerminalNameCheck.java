// 终点站站名锚点回归（多换乘回归）：同一个线路条，终点站分别挂 1 条 / 3 条换乘线，
// 站名与圆圈中心的偏差必须完全一致；站名不许越出条带、不许压换乘标、普通站不许被带歪。
//
//   bash tools/route-strip/check.sh            # 编译 + 跑断言 + 出 PNG（含标注图）
//   bash tools/route-strip/check.sh OUT_DIR
//
// 退出码 0 = 全部断言通过；1 = 有失败（每条失败都带像素坐标）。
// 纯 AWT + 纯逻辑：只依赖 XiamenStripPainter（不碰 net.minecraft / mtr）。
//
// 量法（全部是「像素墨迹」口径，不读 paint 内部变量；只做 0~1px 的几何交叉校验）：
//   · 圆圈中心 x   = 换乘环下半弧颜色的包围盒中点（下半弧关于圆心左右对称）
//   · 站名咬合点   = 圆心正上方一小段窗口里、离「圆心 + 环上沿」最近的站名墨迹像素
//                    （45° 站名只有第一/最后一个字会落在这一小段窗口里，所以不会量到隔壁站的站名）
//   · dev          = 咬合点的 x - 圆心 x（方向无关；1 换乘与 3 换乘必须相同）
//   · dist         = 咬合点到「圆心 + 环上沿」的距离（站名没咬住自己的圆圈时这个值会很大）
//   · 站名墨迹框   = 以咬合点为基准、取 45° 那条带上的全部墨迹（镜像/正常两种方向都适用）
package dev.js.mtrheadlight.xiamen;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;

import javax.imageio.ImageIO;

/** 终点站站名锚点 / 边界断言。 */
public final class TerminalNameCheck {

    private static String out = "D:/MC-MTR-JS/mtr-headlight/v1.20.1/temp/route-strip-terminal";
    private static int checks;
    private static int failures;

    /** 换乘线路色（2 号线绿 / 5 号线紫 / 1 号线蓝）。 */
    private static final int[] LINE_RGB = {0x00A651, 0xA03E9E, 0x0057A8};
    private static final String[] LINE_NUM = {"2", "5", "1"};

    /** 8 站（站距 360px，实拍那种一屏能看到三五站的宽条）：终点站 = 沉船博物馆。 */
    private static final String[] NAMES = {
        "镇海路|Zhenhai Road", "中山公园|Zhongshan Park", "将军祠|Jiangjunci", "文灶|Wenzao",
        "湖滨东路|Hubin East Road", "莲坂|Lianban", "文灶|Wenzao",
        "沉船博物馆|Shipwreck Museum"
    };

    /** 24 站（站距 110px 的密排线）：终点站名同样必须咬住自己的圆圈。 */
    private static final String[] DENSE = {
        "镇海路|Zhenhai Road", "中山公园|Zhongshan Park", "将军祠|Jiangjunci", "文灶|Wenzao",
        "湖滨东路|Hubin East Road", "莲坂|Lianban", "莲花路口|Lianhua Road", "吕厝|Lucuo",
        "乌石浦|Wushipu", "塘边|Tangbian", "火炬园|Torch Hi-Tech Park", "高崎|Gaoqi",
        "集美学村|Jimei School Village", "园博苑|Yuanboyuan", "杏林村|Xinglin Village", "杏锦路|Xingjin Road",
        "官任|Guanren", "市民中心|Citizen Center", "诚毅广场|Chengyi Square", "集美软件园|Jimei Software Park",
        "集美大道|Jimei Avenue", "天水路|Tianshui Road", "厦门北站|Xiamen North Railway Station",
        "沉船博物馆|Shipwreck Museum"
    };

    /** 实拍复现：6 站短条，终点站挂 2 号线 / 5 号线两块牌（用户截图那条）。 */
    private static final String[] SHOT = {
        "镇海路|Zhenhai Road", "集美软件园|Jimei Software Park", "文灶|Wenzao",
        "绫洛东站北广场|Lingluo East Station North Square", "莲坂|Lianban",
        "沉船博物馆|Shipwreck Museum"
    };

    private static final int CURRENT = 2;
    private static final float ASPECT = 8.5f;

    public static void main(final String[] args) throws Exception {
        if (args.length > 0) {
            out = args[0].replace('\\', '/');
        }
        new File(out).mkdirs();

        System.out.println("=== 终点站站名锚点：1 换乘 vs 3 换乘（45° 斜排，实拍版式）===");
        final M one = run("T1-terminal-1-swap", 1, false, true, NAMES, 0);
        final M three = run("T2-terminal-3-swaps", 3, false, true, NAMES, 0);
        final M two = run("T0-terminal-2-swaps", 2, false, false, NAMES, 0);

        // ---- ① 终点站名与圆心的偏差：与换乘数量无关（本次回归的核心断言）----
        final float d13 = Math.abs(one.dev - three.dev);
        System.out.println();
        System.out.println("  终点站名咬合点 - 圆心：1 换乘 " + fs(one.dev) + "px / 3 换乘 " + fs(three.dev)
                + "px / 2 换乘 " + fs(two.dev) + "px");
        System.out.println("  |1 换乘 - 3 换乘| = " + f2(d13) + "px（要求 <= 0.5px）");
        check("终点站名偏差与换乘数量无关（" + f2(d13) + "px <= 0.5px）", d13 <= 0.5f);
        check("2 换乘（实拍那条）同一偏差（" + f2(Math.abs(two.dev - one.dev)) + "px <= 0.5px）",
                Math.abs(two.dev - one.dev) <= 0.5f);

        // ---- ② 终点站名压在自己的圆圈上 ----
        check("终点站名咬住自己的圆圈（1 换乘 dist=" + fs(one.dist) + "px <= " + fs(one.distMax) + "px）",
                one.dist <= one.distMax);
        check("终点站名咬住自己的圆圈（3 换乘 dist=" + fs(three.dist) + "px）", three.dist <= three.distMax);
        check("终点站名不再甩到面板右端（圆心右侧 " + one.spillRight + " 处墨迹）", one.spillRight < 0);
        check("paint 报告：终点站名锚点 = 圆心（1 换乘 anchor=" + one.anchor + " cx=" + one.cx + "）",
                Math.abs(one.anchor - one.cx) <= 1);
        check("paint 报告：终点站名锚点 = 圆心（3 换乘 anchor=" + three.anchor + " cx=" + three.cx + "）",
                Math.abs(three.anchor - three.cx) <= 1);
        check("终点站名走的是镜像排版（1 换乘 / 3 换乘都是）", one.mirrored && three.mirrored);

        // ---- ③ 普通站没被带歪 ----
        final float dMid = Math.abs(one.midDev - three.midDev);
        System.out.println("  普通站(湖滨东路)名咬合点 - 圆心：1 换乘 " + fs(one.midDev) + "px / 3 换乘 "
                + fs(three.midDev) + "px");
        check("普通站名不受换乘数量影响（" + f2(dMid) + "px <= 0.5px）", dMid <= 0.5f);
        check("普通站名照样咬住自己的圆圈（dist=" + fs(one.midDist) + "px）", one.midDist <= one.distMax);
        check("普通站名仍是旧规则「从圆圈往右上」（dev=" + fs(one.midDev) + "px，|x| <= 6px）",
                Math.abs(one.midDev) <= 6f);

        // ---- ④ 密排线（站距 110px）：同一条规则也成立 ----
        System.out.println();
        System.out.println("=== 密排 24 站（站距 110px）===");
        final M dense = run("T3-dense-24-terminal-3-swaps", 3, false, false, DENSE, 0);
        check("密排线：终点站名也咬住自己的圆圈（dist=" + fs(dense.dist) + "px）", dense.dist <= dense.distMax);
        check("密排线：终点站名不甩到面板右端", dense.spillRight < 0);
        System.out.println("  密排线：终点站名墨迹左沿 " + dense.nameX0 + "  >  上一站名右沿 " + dense.prevInkRight
                + " ?  " + (dense.nameX0 > dense.prevInkRight)
                + "（参考项：密排线的站名本来就满格，不参与断言）");

        // ---- ⑤ 实拍复现（6 站 / 终点站 2 号线+5 号线）+ 镜像方向 ----
        System.out.println();
        System.out.println("=== 实拍复现：6 站、终点站 2 号线 + 5 号线（用户截图那条）===");
        final M shot = run("T4-screenshot-6-stations-2-swaps", 2, false, true, SHOT, 0);
        check("实拍复现：终点站名咬住自己的圆圈（dist=" + fs(shot.dist) + "px）", shot.dist <= shot.distMax);
        check("实拍复现：终点站名锚点 = 圆心（anchor=" + shot.anchor + " cx=" + shot.cx + "）",
                Math.abs(shot.anchor - shot.cx) <= 1);
        final M flip = run("T5-screenshot-flip-2-swaps", 2, false, false, SHOT, 2);
        check("镜像方向：最右一列站名也咬住自己的圆圈（dist=" + fs(flip.dist) + "px）",
                flip.dist <= flip.distMax);

        System.out.println();
        System.out.println(failures == 0
                ? "ALL CHECKS PASSED (" + checks + " assertions)"
                : "FAILED " + failures + " / " + checks + " assertions");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** 一次渲染的实测结果。 */
    private static final class M {
        int cx;
        int anchor;
        boolean mirrored;
        int size;
        float dev;
        float dist;
        float distMax;
        int spillRight;
        int nameX0;
        int nameX1;
        int prevInkRight;
        float midDev;
        float midDist;
    }

    private static M run(final String tag, final int swapCount, final boolean namesBelow, final boolean mark,
                         final String[] rawNames, final int direction) throws Exception {
        final XiamenStripPainter.Model model = new XiamenStripPainter.Model();
        model.names = new String[rawNames.length];
        model.namesEn = new String[rawNames.length];
        for (int i = 0; i < rawNames.length; i++) {
            model.names[i] = XiamenStripPainter.cjkPart(rawNames[i]);
            model.namesEn[i] = XiamenStripPainter.enPart(rawNames[i]);
        }
        model.currentIndex = direction != 0 ? 0 : CURRENT;
        model.lineRgb = 0xE60012;
        model.lineNumber = "4";
        model.direction = direction;
        model.namesBelow = namesBelow;
        model.aspectRatio = ASPECT;
        final int term = rawNames.length - 1;
        // 镜像（direction != 0）时最右一列是数据顺序的第 0 站 —— 把它设成当前站，
        // 这样它的站名是「当前站红」，量得到；普通站那套断言在镜像场景不适用。
        final boolean flip = direction != 0;
        final int nameRgb = flip ? XiamenStripPainter.CURRENT_FILL : XiamenStripPainter.TEXT_DARK;
        model.interchanges = new XiamenStripPainter.Interchange[swapCount];
        for (int i = 0; i < swapCount; i++) {
            model.interchanges[i] = new XiamenStripPainter.Interchange(term, LINE_RGB[i], LINE_NUM[i],
                    LINE_NUM[i] + "号线");
        }

        final BufferedImage image = XiamenStripPainter.paint(model);
        ImageIO.write(image, "png", new File(out + "/" + tag + ".png"));

        // ---- 版面几何（和 paint 同一套公式；只用来划断言窗口 + 交叉校验）----
        final int count = model.names.length;
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int bandH = Math.max(8, Math.round(height * XiamenStripPainter.CONTENT_RATIO));
        final int bandTop = (height - bandH) / 2;
        final int padX = Math.max(XiamenStripPainter.PAD_X_MIN, Math.round(width * XiamenStripPainter.PAD_X_RATIO));
        final int padLeft = Math.min(padX, Math.max(2, width / 3));
        final int rightEdge = Math.max(padLeft + 2, width - padLeft);
        final float stepGuess = (float) Math.max(1, rightEdge - padLeft) / (count - 1);
        final float circle = Math.max(bandH * 0.09f,
                Math.min(bandH * XiamenStripPainter.CIRCLE_RATIO, stepGuess * 0.62f));
        final int radius = Math.round(circle / 2f);
        final int xFirst = padLeft + radius;
        final int xLast = Math.max(xFirst + 1, rightEdge - radius);
        final float step = (float) (xLast - xFirst) / (count - 1);
        final int lineY = bandTop + Math.round(bandH * (namesBelow ? 0.30f : 0.64f));
        final int nameTop = Math.max(2, Math.round(height * XiamenStripPainter.NAME_AREA_TOP_RATIO));
        final int ringD = Math.max(14, Math.round(bandH * XiamenStripPainter.IX_RING_RATIO));
        final int ringHalf = Math.round(ringD / 2f);
        final int cellHalf = Math.round(step * 0.5f);
        final int size = Math.max(1, XiamenStripPainter.lastNameSize);

        // ---- 终点站圆心：xs 公式（和 paint 同一套）；换乘站再用像素反查一次做交叉校验 ----
        //      换乘环下半弧 + 垂尾 = 第一条换乘线的颜色，只取「线下、牌带以上」那一条，避开同色小牌。
        final int cxGeo = Math.round(xFirst + step * term);
        final int[] ring = flip
                ? new int[]{-1, -1, -1, -1}
                : bboxRect(image, LINE_RGB[0], cxGeo - cellHalf, cxGeo + cellHalf,
                        lineY + 2, lineY + ringHalf - 1);
        final int cxPix = ring[0] < 0 ? -1 : (ring[0] + ring[2]) / 2;
        final int cx = cxGeo;
        if (cxPix >= 0) {
            check(tag + "：像素反查的圆心与几何一致（" + cxPix + " vs " + cxGeo + "）", Math.abs(cxPix - cxGeo) <= 2);
        }

        // ---- 站名咬合点：圆心正上方一小段窗口里离「圆心 + 环上沿」最近的站名墨迹 ----
        //      窗口宽度只够放一个字：镜像站名是最后一个字落在圆心左侧，正常站名是第一个字落在圆心右侧。
        final int attachY = lineY - ringHalf - 1;
        final int[] near = nearestInk(image, nameRgb,
                cx - Math.round(size * 1.8f), cx + 5, nameTop, lineY - ringHalf, cx, attachY);
        final int[] name = bandBbox(image, nameRgb, near, size, width, nameTop, lineY - ringHalf);

        // ---- 普通站圆心 + 咬合点（同一口径，窗口开在圆心右侧；镜像场景不适用）----
        final int midIndex = 4;                    // 湖滨东路：未过站、非当前站、非换乘站
        final int midCxGeo = Math.round(xFirst + step * midIndex);
        final int[] midRing = bboxRect(image, model.lineRgb, midCxGeo - cellHalf, midCxGeo + cellHalf,
                lineY - ringD - 1, lineY - ringHalf);          // 只取线上方的半圈，避开线条本身
        final int midCx = midRing[0] < 0 ? midCxGeo : (midRing[0] + midRing[2]) / 2;
        final int[] midNear = flip ? new int[]{-1, -1, -1}
                : nearestInk(image, nameRgb, midCx - 5, midCx + Math.round(size * 1.8f), nameTop,
                        lineY - ringHalf, midCx, attachY);

        final M m = new M();
        m.cx = cx;
        m.anchor = XiamenStripPainter.lastNameAnchorX;
        m.mirrored = XiamenStripPainter.lastNameMirrored;
        m.size = size;
        m.dev = near[1] - cx;
        m.dist = (float) Math.sqrt(near[0]);
        m.distMax = size * 1.5f;
        m.spillRight = bboxRect(image, nameRgb, rightEdge + 1, width, nameTop, lineY - ringHalf)[0];
        m.nameX0 = name[0];
        m.nameX1 = name[2];
        m.prevInkRight = bboxRect(image, nameRgb, 0, cx - Math.round(step * 1.2f), nameTop,
                lineY - ringHalf)[2];
        m.midDev = midNear[1] < 0 ? Float.NaN : midNear[1] - midCx;
        m.midDist = midNear[1] < 0 ? Float.NaN : (float) Math.sqrt(midNear[0]);

        System.out.println();
        System.out.println(tag + "  " + width + "x" + height + "  站距=" + Math.round(step)
                + "px  线 y=" + lineY + "  环 d=" + ringD + "  站名字号=" + size + "px");
        System.out.println("  终点站圆心 x=" + cx + "（几何 " + cxGeo + "）  换乘环下半弧包围盒 " + fmt(ring));
        System.out.println("  终点站名墨迹 " + fmt(name) + "  咬合点 x=" + near[1] + "  dev=" + fs(m.dev)
                + "px  dist=" + fs(m.dist) + "px  镜像=" + m.mirrored + "  锚点=" + m.anchor);
        System.out.println("  普通站圆心 x=" + midCx + "  咬合点 x=" + midNear[1] + "  dev=" + fs(m.midDev)
                + "px  dist=" + fs(m.midDist) + "px");
        System.out.println("  换乘标: count=" + XiamenStripPainter.lastBadgeCount
                + " dropped=" + XiamenStripPainter.lastBadgeDropped
                + " scale=" + String.format(Locale.ROOT, "%.2f", XiamenStripPainter.lastBadgeScale)
                + " plateW=" + XiamenStripPainter.lastSwapPlate + "px");

        // ---- 断言：站名在线路条内、不越界 ----
        check(tag + "：量到了终点站名墨迹", near[1] >= 0);
        check(tag + "：站名不越出条带左边（" + name[0] + " >= 0）", name[0] >= 0);
        check(tag + "：站名不越出条带右边（" + name[2] + " < " + width + "）", name[2] < width);
        if (namesBelow) {
            check(tag + "：站名不越出条带下边（" + name[3] + " < " + height + "）", name[3] < height);
        } else {
            check(tag + "：站名在线条上方（墨迹下沿 " + name[3] + " < 圆心上沿 " + (lineY - ringHalf) + "）",
                    name[3] < lineY - ringHalf);
        }
        // ---- 断言：站名不压换乘标（牌带里一个站名墨迹像素都不许有）----
        final int badgeTop = lineY + ringHalf + Math.max(3, Math.round(bandH * XiamenStripPainter.IX_PLATE_GAP_RATIO));
        final int[] badge = bboxRect(image, LINE_RGB[0], 0, width, badgeTop, height);
        if (badge[0] >= 0) {
            final int[] clash = bboxRect(image, nameRgb, badge[0], badge[2] + 1, badge[1], badge[3] + 1);
            System.out.println("  换乘标包围盒 " + fmt(badge) + "  与站名重叠的墨迹: " + fmt(clash));
            check(tag + "：站名不压换乘标（牌带里站名墨迹 " + (clash[0] < 0 ? 0 : 1) + " 处）", clash[0] < 0);
        }

        if (mark) {
            ImageIO.write(annotate(image, tag, cx, near[1], name[2], midCx, midNear[1], lineY),
                    "png", new File(out + "/" + tag + "-marked.png"));
        }
        return m;
    }

    /** 标注图：圆圈中心 / 站名咬合点打十字 + 竖虚线，顶部写像素坐标。 */
    private static BufferedImage annotate(final BufferedImage src, final String tag, final int cx,
                                          final int nameCx, final int nameR, final int midCx,
                                          final int midNameCx, final int lineY) {
        final BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight() + 34,
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(src, 0, 34, null);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[]{4f, 4f}, 0f));
        cross(g, cx, lineY + 34, new Color(0xCC00CC), "终点圆圆心");
        cross(g, nameCx, lineY + 34, new Color(0x0088CC), "站名咬合点");
        cross(g, midCx, lineY + 34, new Color(0x999999), "普通站圆心");
        g.setColor(new Color(0x333333));
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 16f));
        g.drawString(tag + "  终点站圆心 x=" + cx + "  站名咬合点 x=" + nameCx + "  站名墨迹右沿 x=" + nameR
                + "  普通站圆心 x=" + midCx + "  普通站名咬合点 x=" + midNameCx
                + "（品红 = 圆圈中心，蓝 = 站名咬合点）", 12, 23);
        g.dispose();
        return img;
    }

    private static void cross(final Graphics2D g, final int x, final int y, final Color c, final String label) {
        g.setColor(c);
        g.drawLine(x, y - 100, x, y + 5);
        g.drawLine(x - 9, y, x + 9, y);
        g.drawLine(x, y - 9, x, y + 9);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
        g.drawString(label, x + 6, y + 22);
    }

    // ---------------------------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------------------------

    private static void check(final String what, final boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + what);
        }
    }

    /** 窗口里离 ({@code ax},{@code ay}) 最近的该颜色像素：{距离平方, x, y}；没有就 {-1,-1,-1}。 */
    private static int[] nearestInk(final BufferedImage image, final int rgb, final int x0, final int x1,
                                    final int y0, final int y1, final int ax, final int ay) {
        final int want = 0xFF000000 | (rgb & 0xFFFFFF);
        int best = Integer.MAX_VALUE;
        int bx = -1;
        int by = -1;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++) {
                if (image.getRGB(x, y) == want) {
                    final int d = (x - ax) * (x - ax) + (y - ay) * (y - ay);
                    if (d < best) {
                        best = d;
                        bx = x;
                        by = y;
                    }
                }
            }
        }
        return bx < 0 ? new int[]{-1, -1, -1} : new int[]{best, bx, by};
    }

    /**
     * 以咬合点为基准取<b>整条</b> 45° 站名墨迹的包围盒。
     *
     * <p>45° 斜排的文字落在一条 45° 带子上：往右上（普通站）x + y ≈ 常数，往左上（终点站镜像）
     * x - y ≈ 常数。常数用咬合点那个像素定，带宽取 1.6 个字高（一行字 + 笔画外沿）。</p>
     */
    private static int[] bandBbox(final BufferedImage image, final int rgb, final int[] near,
                                  final int size, final int x1, final int y0, final int y1) {
        if (near[1] < 0) {
            return new int[]{-1, -1, -1, -1};
        }
        final int want = 0xFF000000 | (rgb & 0xFFFFFF);
        final int k = near[2] - near[1];                 // 镜像（往左上）用 x - y ≈ k
        final int k2 = near[2] + near[1];                // 往右上用 x + y ≈ k2
        final int band = Math.max(4, Math.round(size * 1.6f));
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = 0; x < Math.min(image.getWidth(), x1); x++) {
                if (image.getRGB(x, y) != want) {
                    continue;
                }
                if (Math.abs((y - x) - k) <= band || Math.abs((y + x) - k2) <= band) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? new int[]{-1, -1, -1, -1} : new int[]{minX, minY, maxX, maxY};
    }

    /** 在 [{@code x0},{@code x1}) x [{@code y0},{@code y1}) 里找精确颜色的包围盒。 */
    private static int[] bboxRect(final BufferedImage image, final int rgb, final int x0, final int x1,
                                  final int y0, final int y1) {
        final int want = 0xFF000000 | (rgb & 0xFFFFFF);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++) {
                if (image.getRGB(x, y) == want) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? new int[]{-1, -1, -1, -1} : new int[]{minX, minY, maxX, maxY};
    }

    private static String fmt(final int[] b) {
        return b[0] < 0 ? "none" : "[" + b[0] + "," + b[1] + " .. " + b[2] + "," + b[3] + "]";
    }

    private static String fs(final float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f2(final float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private TerminalNameCheck() {
    }
}
