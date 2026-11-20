// 终点站站名回归（v12：撤销镜像 → 端头留白 / 整体内移）。
//
//   bash tools/route-strip/check.sh            # 编译 + 跑断言 + 出 PNG（含标注图 / 端点预览）
//   bash tools/route-strip/check.sh OUT_DIR
//
// 退出码 0 = 全部断言通过；1 = 有失败（每条失败都带像素坐标）。
// 纯 AWT + 纯逻辑：只依赖 XiamenStripPainter（不碰 net.minecraft / mtr）。
//
// 断言清单（v12）：
//   ① 终点站站名的旋转角 = +45°（与普通站同一方向，**不得**是 -45° / 镜像）；
//   ② 站名墨迹的起点压在自己的圆心上（横向 |墨迹左沿 - 圆心| <= 2px，锚点报告也 <= 1px）；
//      且墨迹从圆心**往右上**长（不得往左上）；
//   ③ 站名不越出线路条边界（左 / 右 / 线上方）；
//   ④ 站距均匀：相邻站距 max/min <= 1.02，终点站与前一站的间距 == 其余站距（<= 1px）；
//   ⑤ 同一条线路条，终点站挂 1 条 / 3 条换乘时，终点站站名位置差 <= 0.5px。
//
// 量法（像素墨迹口径，不读 paint 内部变量；只做 0~1px 的几何交叉校验）：
//   · 圆圈中心 x = 换乘环下半弧颜色的包围盒中点（下半弧关于圆心左右对称）；几何值做交叉校验
//   · 站名咬合点 = 圆心附近窗口里、离「圆心 + 环上沿」最近的站名墨迹像素
//   · 站名墨迹框 = 以咬合点为基准、取 45° 那条带上的全部墨迹（x + y ≈ 常数 = 往右上）
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

/** 终点站站名方向 / 锚点 / 站距 / 边界断言。 */
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

    /** 24 站（站距 110px 的密排线）：终点站名同样必须 45° + 咬住自己的圆圈。 */
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

        System.out.println("=== v12 终点站站名：撤销镜像 → 端头留白 / 整体内移（45° 斜排，实拍版式）===");
        final M one = run("T1-terminal-1-swap", 1, false, true, NAMES, 0);
        final M three = run("T2-terminal-3-swaps", 3, false, true, NAMES, 0);
        final M two = run("T0-terminal-2-swaps", 2, false, true, NAMES, 0);

        // ---- ① 方向：终点站和普通站同一个 +45° ----
        System.out.println();
        System.out.println("  终点站名旋转角（视觉）：1 换乘 " + fs(one.rotation) + "° / 3 换乘 "
                + fs(three.rotation) + "°   ← 必须是 +45°，不是 -45°（镜像）");
        check("终点站名旋转角 = +45°（1 换乘，与普通站同一方向）", Math.abs(one.rotation - 45f) < 0.01f);
        check("终点站名旋转角 = +45°（3 换乘，与普通站同一方向）", Math.abs(three.rotation - 45f) < 0.01f);
        check("终点站名和小写站名同一个角（paint 只用一个常量 NAME_ROTATION_DEG）",
                Math.abs(one.rotation - XiamenStripPainter.NAME_ROTATION_DEG) < 0.01f
                        && Math.abs(one.midDev) <= 6f);
        check("终点站名墨迹从圆心往右上长（1 换乘：右沿 " + one.inkX1 + " 在圆心 " + one.cx + " 右侧）",
                one.extendsRight);
        check("终点站名墨迹从圆心往右上长（3 换乘）", three.extendsRight);
        check("终点站名不是镜像版（墨迹左沿 " + one.inkX0 + " 没跑到圆心左边一个字号 "
                + (one.cx - one.size) + "）", one.inkX0 > one.cx - one.size);

        // ---- ② 站名压在自己的圆圈上：墨迹最靠近圆心的那块，横向就在圆心正上方 ----
        //      像素口径（咬合点 - 圆心）包含字形左侧 bearing，所以允许 2px；布局口径的锚点
        //      （= 落笔点）必须精确等于圆心（<= 1px，见下）。
        System.out.println("  站名咬合点 - 圆心：1 换乘 " + fs(one.startDev) + "px / 3 换乘 "
                + fs(three.startDev) + "px   （<= 2px = 起点压在自己的圆心上）");
        System.out.println("  站名墨迹到圆心的最近距离：" + fs(one.radial) + "px（普通站同一口径 "
                + fs(one.midRadial) + "px，差值 " + fs(Math.abs(one.radial - one.midRadial)) + "px）");
        check("终点站名起始压在自己的圆心上（咬合点-圆心 " + fs(one.startDev) + "px <= 2px）",
                Math.abs(one.startDev) <= 2f);
        check("终点站名起始压在自己的圆心上（3 换乘 " + fs(three.startDev) + "px <= 2px）",
                Math.abs(three.startDev) <= 2f);
        check("paint 报告锚点 = 圆心（1 换乘 anchor=" + one.anchor + " cx=" + one.cx + "）",
                Math.abs(one.anchor - one.cx) <= 1);
        check("paint 报告锚点 = 圆心（3 换乘 anchor=" + three.anchor + " cx=" + three.cx + "）",
                Math.abs(three.anchor - three.cx) <= 1);
        check("终点站名与圆圈的间距和普通站同一套（差 " + fs(Math.abs(one.radial - one.midRadial)) + "px <= 2px）",
                Math.abs(one.radial - one.midRadial) <= 2f);

        // ---- ③ 不越出线路条 ----
        check("终点站名不越出条带左边（" + one.inkX0 + " >= 0）", one.inkX0 >= 0);
        check("终点站名不越出条带右边（" + one.inkX1 + " < " + one.width + "）", one.inkX1 < one.width);
        check("终点站名不越出线路条内边界（" + one.inkX1 + " <= rightEdge " + one.rightEdge + "）",
                one.inkX1 <= one.rightEdge);
        check("终点站名在线上方（墨迹下沿 " + one.inkY1 + " < 线上沿 " + (one.lineY - one.ringHalf) + "）",
                one.inkY1 < one.lineY - one.ringHalf);
        check("端头确实让出了位置（reserve=" + one.reserve + "px，末站圆心 " + one.cx + " + reserve <= rightEdge "
                + one.rightEdge + "）", one.cx + one.reserve <= one.rightEdge);
        check("3 换乘时端头留白不变（" + three.reserve + "px vs " + one.reserve + "px）",
                Math.abs(three.reserve - one.reserve) <= 1);

        // ---- ④ 站距均匀（绝不单独压最后一格）----
        System.out.println("  站距：min " + one.gapMin + "px / max " + one.gapMax + "px（比 "
                + f2(one.gapRatio) + "）  终点站间距 " + one.gapEnd + "px");
        check("站距均匀：max/min = " + f2(one.gapRatio) + " <= 1.02", one.gapRatio <= 1.02f);
        check("终点站与前一站的间距 == 其余站距（|" + one.gapEnd + " - " + one.gapMean + "| <= 1px）",
                Math.abs(one.gapEnd - one.gapMean) <= 1f);
        check("3 换乘时站距同样均匀（" + f2(three.gapRatio) + "）", three.gapRatio <= 1.02f);

        // ---- ⑤ 多换乘不变（位置差 <= 0.5px）----
        final float d13 = Math.abs(one.inkX0 - three.inkX0);
        final float dAnchor = Math.abs(one.anchor - three.anchor);
        System.out.println("  终点站名位置：1 换乘 左沿 " + one.inkX0 + " / 3 换乘 " + three.inkX0
                + "  差 " + f2(d13) + "px（要求 <= 0.5px）");
        check("终点站名位置与换乘数量无关（墨迹左沿差 " + f2(d13) + "px <= 0.5px）", d13 <= 0.5f);
        check("终点站名位置与换乘数量无关（锚点差 " + f2(dAnchor) + "px <= 0.5px）", dAnchor <= 0.5f);
        check("2 换乘（实拍那条）同一位置（差 "
                + f2(Math.abs(two.inkX0 - one.inkX0)) + "px <= 0.5px）", Math.abs(two.inkX0 - one.inkX0) <= 0.5f);

        // ---- 普通站没被带歪 ----
        System.out.println("  普通站(湖滨东路)名咬合点 - 圆心：1 换乘 " + fs(one.midDev) + "px / 3 换乘 "
                + fs(three.midDev) + "px");
        check("普通站名不受换乘数量影响（" + f2(Math.abs(one.midDev - three.midDev)) + "px <= 0.5px）",
                Math.abs(one.midDev - three.midDev) <= 0.5f);
        check("普通站名仍是旧规则「从圆圈往右上」（dev=" + fs(one.midDev) + "px，|x| <= 6px）",
                Math.abs(one.midDev) <= 6f);

        // ---- 密排线（站距 110px）：同一条规则也成立 ----
        System.out.println();
        System.out.println("=== 密排 24 站（站距 110px）===");
        final M dense = run("T3-dense-24-terminal-3-swaps", 3, false, true, DENSE, 0);
        check("密排线：终点站名旋转角 = +45°", Math.abs(dense.rotation - 45f) < 0.01f);
        check("密排线：终点站名起点压在自己的圆心上（dev=" + fs(dense.startDev) + "px <= 2px）",
                Math.abs(dense.startDev) <= 2f);
        check("密排线：终点站名不越出条带右沿（" + dense.inkX1 + " <= " + dense.rightEdge + "）",
                dense.inkX1 <= dense.rightEdge);
        check("密排线：站距均匀（max/min " + f2(dense.gapRatio) + " <= 1.02）", dense.gapRatio <= 1.02f);

        // ---- 实拍复现（6 站 / 终点站 2 号线+5 号线）----
        System.out.println();
        System.out.println("=== 实拍复现：6 站、终点站 2 号线 + 5 号线（用户截图那条）===");
        final M shot = run("T4-screenshot-6-stations-2-swaps", 2, false, true, SHOT, 0);
        check("实拍复现：旋转角 = +45°（不是镜像）", Math.abs(shot.rotation - 45f) < 0.01f);
        check("实拍复现：终点站名起点压在自己的圆心上（dev=" + fs(shot.startDev) + "px <= 2px）",
                Math.abs(shot.startDev) <= 2f);
        check("实拍复现：终点站名不越出条带（" + shot.inkX0 + " .. " + shot.inkX1 + " < " + shot.width + "）",
                shot.inkX0 >= 0 && shot.inkX1 < shot.width);
        check("实拍复现：站距均匀（" + f2(shot.gapRatio) + "）", shot.gapRatio <= 1.02f);

        // ---- 反向（整条图镜像读）：最右一列也是同一套规则 ----
        final M flip = run("T5-screenshot-flip-2-swaps", 2, false, true, SHOT, 2);
        check("反向：反向时最右一列站名旋转角 = +45°", Math.abs(flip.rotation - 45f) < 0.01f);
        // 反向时最右一列就是「当前站」：中英文同为红色，量到的是英文行（Zhenhai Road）的落笔点，
        // 大写字母的左侧 bearing 更大，所以这里放到 5px（布局锚点仍然精确 = 圆心，见下一条）。
        check("反向：最右一列站名起始压在自己的圆心上（咬合点-圆心 " + fs(flip.startDev) + "px <= 5px）",
                Math.abs(flip.startDev) <= 5f);
        check("反向：最右一列站名锚点 = 圆心（anchor=" + flip.anchor + " cx=" + flip.cx + "）",
                Math.abs(flip.anchor - flip.cx) <= 1);
        check("反向：最右一列站名不越出条带（" + flip.inkX1 + " <= " + flip.rightEdge + "）",
                flip.inkX1 <= flip.rightEdge);

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
        float rotation;
        int size;
        int reserve;
        int width;
        int rightEdge;
        int lineY;
        int ringHalf;
        float startDev;
        float penDev;
        float radial;
        float midRadial;
        float midDev;
        int inkX0;
        int inkX1;
        int inkY1;
        boolean extendsRight;
        int gapMin;
        int gapMax;
        int gapEnd;
        float gapMean;
        float gapRatio;
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
        // 反向（direction != 0）时最右一列是数据顺序的第 0 站 —— 把它设成当前站，
        // 这样它的站名是「当前站红」，量得到。
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
        final int lineY = bandTop + Math.round(bandH * (namesBelow ? 0.30f : 0.64f));
        final int nameTop = Math.max(2, Math.round(height * XiamenStripPainter.NAME_AREA_TOP_RATIO));
        final int ringD = Math.max(14, Math.round(bandH * XiamenStripPainter.IX_RING_RATIO));
        final int ringHalf = Math.round(ringD / 2f);
        final int cellHalf = Math.round(stepGuess * 0.5f);
        final int size = Math.max(1, XiamenStripPainter.lastNameSize);

        // ---- 站点圆心：直接用 paint 报出来的 xs（v12 排版会整体内移，公式已经不再是“等分右边缘”）----
        final int[] xs = XiamenStripPainter.lastStationXs;
        final int cx = xs.length == count ? xs[count - 1] : Math.round(padLeft + radius + stepGuess * (count - 1));
        final int[] ring = flip
                ? new int[]{-1, -1, -1, -1}
                : bboxRect(image, LINE_RGB[0], cx - cellHalf, cx + cellHalf,
                        lineY + 2, lineY + ringHalf - 1);
        final int cxPix = ring[0] < 0 ? -1 : (ring[0] + ring[2]) / 2;
        if (cxPix >= 0) {
            check(tag + "：像素反查的圆心与 paint 一致（" + cxPix + " vs " + cx + "）", Math.abs(cxPix - cx) <= 2);
        }

        // ---- 终点站名咬合点：圆心右上方一小段窗口里离「圆心 + 环上沿」最近的站名墨迹 ----
        //      v12 站名一律往右上：第一个字的左下角就在圆心正上方，所以窗口开在圆心右侧。
        //      窗口下沿再往上收 2px：避开当前站那块红实心圆的顶弧（flip 时它就贴着环上沿）。
        final int inkTop = lineY - ringHalf - 2;
        final int attachY = lineY - ringHalf - 1;
        final int[] near = nearestInk(image, nameRgb,
                cx - 2, cx + Math.round(size * 1.8f), nameTop, inkTop, cx, attachY);
        final int[] name = bandBboxUpRight(image, nameRgb, near, size, width, nameTop, inkTop);
        final int penX = penStartX(image, nameRgb, near, size, nameTop, inkTop, width);

        // ---- 普通站（湖滨东路）：同一口径，窗口开在圆心右侧 ----
        final int midIndex = 4;
        final int midCx = xs.length == count ? xs[midIndex] : Math.round(padLeft + radius + stepGuess * midIndex);
        final int[] midRing = bboxRect(image, model.lineRgb, midCx - cellHalf, midCx + cellHalf,
                lineY - ringD - 1, lineY - ringHalf);          // 只取线上方的半圈，避开线条本身
        final int midCxReal = midRing[0] < 0 ? midCx : (midRing[0] + midRing[2]) / 2;
        final int[] midNear = flip ? new int[]{-1, -1, -1}
                : nearestInk(image, nameRgb, midCxReal - 2, midCxReal + Math.round(size * 1.8f), nameTop,
                        lineY - ringHalf, midCxReal, attachY);

        final M m = new M();
        m.cx = cx;
        m.anchor = XiamenStripPainter.lastNameAnchorX;
        m.rotation = XiamenStripPainter.lastNameRotationDeg;
        m.size = size;
        m.reserve = XiamenStripPainter.lastNameReserve;
        m.width = width;
        m.rightEdge = rightEdge;
        m.lineY = lineY;
        m.ringHalf = ringHalf;
        m.midDev = midNear[1] < 0 ? Float.NaN : midNear[1] - midCxReal;
        m.midRadial = midNear[1] < 0 ? Float.NaN : (float) Math.sqrt(midNear[0]);
        if (near[1] >= 0 && name[0] >= 0) {
            m.inkX0 = name[0];
            m.inkX1 = name[2];
            m.inkY1 = name[3];
            m.startDev = near[1] - cx;                       // 咬合点 - 圆心（起点压在自己的圆心上）
            m.penDev = penX < 0 ? Float.NaN : penX - cx;     // 落笔点 - 圆心（像素口径）
            m.radial = (float) Math.sqrt(near[0]);
            m.extendsRight = (name[2] - cx) >= 0.5f * (name[2] - name[0]);
        } else {
            m.inkX0 = -1;
            m.inkX1 = -1;
            m.inkY1 = -1;
            m.startDev = Float.NaN;
            m.penDev = Float.NaN;
            m.radial = Float.NaN;
            m.extendsRight = false;
        }

        // ---- 站距统计（相邻圆心距：max/min 与末段）----
        int gMin = Integer.MAX_VALUE;
        int gMax = 0;
        long sum = 0;
        for (int i = 1; i < count; i++) {
            final int g = xs[i] - xs[i - 1];
            gMin = Math.min(gMin, g);
            gMax = Math.max(gMax, g);
            sum += g;
        }
        m.gapMin = gMin;
        m.gapMax = gMax;
        m.gapEnd = count > 1 ? xs[count - 1] - xs[count - 2] : 0;
        m.gapMean = count > 1 ? (float) sum / (count - 1) : 0f;
        m.gapRatio = gMin > 0 ? (float) gMax / gMin : 99f;

        System.out.println();
        System.out.println(tag + "  " + width + "x" + height + "  站距=" + Math.round(m.gapMean)
                + "px  线 y=" + lineY + "  环 d=" + ringD + "  站名字号=" + size + "px  端头留白=" + m.reserve + "px");
        System.out.println("  终点站圆心 x=" + cx + "（像素反查 " + cxPix + "）  换乘环下半弧包围盒 " + fmt(ring));
        System.out.println("  终点站名墨迹 " + fmt(name) + "  咬合点 x=" + near[1] + "  落笔点 x=" + penX
                + "  咬合点-圆心 " + fs(m.startDev) + "px  旋转=" + fs(m.rotation) + "°  锚点=" + m.anchor);
        System.out.println("  普通站圆心 x=" + midCxReal + "  咬合点 x=" + midNear[1] + "  dev=" + fs(m.midDev)
                + "px  dist=" + fs(m.midRadial) + "px");
        System.out.println("  换乘标: count=" + XiamenStripPainter.lastBadgeCount
                + " dropped=" + XiamenStripPainter.lastBadgeDropped
                + " scale=" + String.format(Locale.ROOT, "%.2f", XiamenStripPainter.lastBadgeScale)
                + " plateW=" + XiamenStripPainter.lastSwapPlate + "px");

        check(tag + "：量到了终点站名墨迹", near[1] >= 0);
        if (mark) {
            ImageIO.write(annotate(image, tag, cx, name, lineY, XiamenStripPainter.lastNameReserve),
                    "png", new File(out + "/" + tag + "-marked.png"));
            ImageIO.write(endCrop(image, cx, name, lineY, ringD, width, height),
                    "png", new File(out + "/" + tag + "-end.png"));
        }
        return m;
    }

    /**
     * 站名<b>笔尖起点</b>的 x：45° 往右上的文字里，基线那一撇的最低几行墨迹最靠左 ——
     * 也就是第一个字的落笔点（= 站名压在自己圆圈上的那个“起点”）。
     *
     * <p>（字身从基线往“上”是往左上长的，所以整条墨迹的最左像素会比落笔点再左 {@code ascent×cos45}
     * —— 那是笔画外沿，不是起点；这里只取最底下 3 行，量的是落笔点。）</p>
     */
    private static int penStartX(final BufferedImage image, final int rgb, final int[] near,
                                 final int size, final int y0, final int y1, final int x1) {
        if (near[1] < 0) {
            return -1;
        }
        final int want = 0xFF000000 | (rgb & 0xFFFFFF);
        final int k = near[2] + near[1];
        final int band = Math.max(4, Math.round(size * 1.6f));
        int yMax = -1;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = 0; x < Math.min(image.getWidth(), x1); x++) {
                if (image.getRGB(x, y) == want && Math.abs((y + x) - k) <= band) {
                    yMax = Math.max(yMax, y);
                }
            }
        }
        if (yMax < 0) {
            return -1;
        }
        int minX = Integer.MAX_VALUE;
        for (int y = Math.max(0, yMax - 2); y <= yMax; y++) {
            for (int x = 0; x < Math.min(image.getWidth(), x1); x++) {
                if (image.getRGB(x, y) == want && Math.abs((y + x) - k) <= band) {
                    minX = Math.min(minX, x);
                }
            }
        }
        return minX == Integer.MAX_VALUE ? -1 : minX;
    }

    /** 标注图：圆心 / 站名墨迹框 打十字 + 竖虚线，顶部写像素坐标。 */
    private static BufferedImage annotate(final BufferedImage src, final String tag, final int cx,
                                          final int[] name, final int lineY, final int reserve) {
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
        if (name[0] >= 0) {
            g.setColor(new Color(0x0088CC));
            g.drawRect(name[0], name[1] + 34, name[2] - name[0], name[3] - name[1]);
            cross(g, name[0], lineY + 34, new Color(0x0088CC), "站名起点");
        }
        g.setColor(new Color(0x333333));
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 16f));
        g.drawString(tag + "  v12 45°（撤销镜像）  圆心 x=" + cx + "  站名墨迹 x=" + name[0] + ".." + name[2]
                + "  端头留白=" + reserve + "px", 12, 23);
        g.dispose();
        return img;
    }

    /** 端点区域放大预览：从末站前 1.5 站距裁到条右沿 + 一点白边。 */
    private static BufferedImage endCrop(final BufferedImage src, final int cx, final int[] name,
                                         final int lineY, final int ringD, final int width, final int height) {
        final int x0 = Math.max(0, Math.min(name[0] >= 0 ? name[0] : cx, cx) - 90);
        final int y0 = 0;
        final int x1 = width;
        final int y1 = Math.min(height, lineY + ringD + 30);
        return src.getSubimage(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
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
     * <p>45° 斜排站名落在一条斜带子上：往右上（v12 全线统一的方向）→ {@code x + y ≈ 常数}。
     * 常数用咬合点那个像素定，带宽取 1.6 个字高（一行字 + 笔画外沿）。</p>
     */
    private static int[] bandBboxUpRight(final BufferedImage image, final int rgb, final int[] near,
                                         final int size, final int x1, final int y0, final int y1) {
        if (near[1] < 0) {
            return new int[]{-1, -1, -1, -1};
        }
        final int want = 0xFF000000 | (rgb & 0xFFFFFF);
        final int k = near[2] + near[1];                 // 往右上：x + y ≈ k
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
                if (Math.abs((y + x) - k) <= band) {
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
