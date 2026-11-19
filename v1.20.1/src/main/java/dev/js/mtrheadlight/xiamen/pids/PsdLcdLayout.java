// 厦门「屏蔽门顶板 LCD 挂板」（PSD-LCD）的排版：**一个字符都不依赖 MC**，
// 所以离线预览工具可以把这一份源码原样编译过去、画出和渲染器一模一样的图。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.ArrayList;
import java.util.List;

/**
 * 厦门地铁「PSD-LCD」挂板（{@code mtrlte:psd_lcd_xiamen}）的<b>全部</b>排版。
 *
 * <h2>为什么排版是「一张绘制指令表」</h2>
 * <p>游戏里由 {@link PsdLcdRenderer} 把 {@link #plan} 生成的指令交给
 * {@code VertexConsumer} / {@code TextRenderer} 画；离线预览
 * （{@code tools/preview-psd-lcd.js} → {@code tools/psd-lcd/PsdLcdPreview.java}）把
 * <b>同一张指令表</b>交给 {@code java.awt.Graphics2D} 画。两边都不写自己的坐标，
 * 所以预览图和游戏里看到的不会各画各的。本类因此<b>不许 import 任何 MC 类</b>。
 *
 * <h2>坐标系 = 屏幕像素</h2>
 * <p>屏幕平面宽 {@code 520px × 段数}、高 {@value #HEIGHT_PX}px，原点左上、x 向右、y 向下。
 * 渲染时整块平面被 {@code scale(1 / }{@value #PX_PER_BLOCK}{@code )} 压进世界：
 * <pre>
 *   520 px = 1 格宽     160 px = 160/520 = 0.3077 格高
 * </pre>
 * 于是<b>四段</b>的挂板是 2080×160 px = <b>13:1</b>（实拍量出来的比例），
 * 一段是 520×160 = 3.25:1。高度<b>不随段数变</b>：横向连接时只有宽度变宽。
 *
 * <h2>所有数字的单位都是 h（面板高）</h2>
 * <p>下面的常量都写成 {@code × h}，h = {@value #HEIGHT_PX}。左端卡片、两行到站、标语条
 * 全部按实拍量出来的比例摆；线路徽标 / 当前站 / 编组是「右侧一组」，跟着内容区右边界走。
 *
 * <h2>宽了 / 窄了怎么办</h2>
 * <ul>
 *   <li><b>宽了</b>（段数 ≥ 4）：内容区比 {@value #DESIGN_CONTENT_H}h 宽，多出来的地方
 *       全给「到站行」和「右侧一组」之间那条空隙 —— 右侧一组在空隙里居中，
 *       两头留白一样多，看起来是有意留的呼吸区，而不是一边挤一边空；</li>
 *   <li><b>窄了</b>（内容区 &lt; {@value #NARROW_H}h，也就是一两个方块）：走「压缩版」——
 *       卡片变窄、三列（标签 / 终点站 / 数字）从右边倒着排，只留「当前站」两行小字，
 *       线路徽标和编组卡片不画（画了也只剩一堆看不清的小方块），字号按 {@link #textScale} 缩。</li>
 * </ul>
 */
public final class PsdLcdLayout {

    private PsdLcdLayout() {
    }

    // ================================================================ 尺寸

    /** 每格（= 每段）横向多少屏幕像素。同时也是世界缩放的倒数。 */
    public static final float PX_PER_BLOCK = 520.0F;

    /** 屏幕高度（px）。**不随段数变化**。 */
    public static final float HEIGHT_PX = 160.0F;

    /** 屏幕在世界里的高度（格）= 160/520 ≈ 0.3077。 */
    public static final float HEIGHT_BLOCKS = HEIGHT_PX / PX_PER_BLOCK;

    /** 渲染缩放：{@code matrices.scale(1 / 520)} 之后 1px = 1/520 格。 */
    public static final float SCREEN_SCALE_DIVISOR = PX_PER_BLOCK;

    /** 屏幕平面离开方块中心、朝外多少格（贴住方块外表面再让 4mm）。 */
    public static final float ANCHOR_OUT = 0.5F + 0.004F;

    /**
     * 设计稿的内容区宽度（以 h 为单位）—— 实拍（6.9:1 那版）里广告区左边那条线
     * 正好在 {@code 5.03h}。窄于它就是压缩版，宽于它就把余量分给中间的空隙。
     */
    public static final float DESIGN_CONTENT_H = 5.03F;

    // ================================================================ 颜色

    /** 底色：实拍量出来的深蓝。 */
    public static final int COLOR_BG = 0xFF0A12E0;
    public static final int COLOR_WHITE = 0xFFFFFFFF;
    /** 左端卡片 / 标语条的白色描边（标语条略暗一点，实拍上它是灰白）。 */
    public static final int COLOR_CARD_STROKE = 0xFFFFFFFF;
    public static final int COLOR_TICKER_STROKE = 0xFFEDEDFF;
    /** 编组卡片：浅蓝底 / 更浅的窗口 / 绿色座椅 / 深蓝标签带。 */
    public static final int COLOR_CAR_CARD = 0xFFB9DCEC;
    public static final int COLOR_CAR_WINDOW = 0xFFE9F6FB;
    public static final int COLOR_CAR_SEAT = 0xFF6FC47A;
    public static final int COLOR_CAR_BAND = 0xFF0B1466;
    /** 没有广告图时的示意色块（洋红）。 */
    public static final int COLOR_AD_BG = 0xFFD81BB0;
    public static final int COLOR_AD_TEXT = 0xFFFFFFFF;
    /** 「当前站 / This station」这行小字：压暗的白，不抢站名。 */
    public static final int COLOR_DIM = 0xC8FFFFFF;
    /** 厦门地铁 M 标志的红。 */
    public static final int COLOR_LOGO_RED = 0xFFE0161C;

    // ================================================================ 数据类型

    /** 水平对齐。 */
    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    /** 一条绘制指令。 */
    public sealed interface Op permits Fill, Quad, RoundFill, RoundStroke, Disc, Text, AdSlot, Ticker {
    }

    /** 轴对齐实心矩形。 */
    public record Fill(float x0, float y0, float x1, float y1, int color) implements Op {
    }

    /** 任意四边形（描边 / 斜条都用它；顶点顺时针，两个三角形都不会退化）。 */
    public record Quad(float x0, float y0, float x1, float y1,
                       float x2, float y2, float x3, float y3, int color) implements Op {
    }

    /** 圆角实心矩形。 */
    public record RoundFill(float x0, float y0, float x1, float y1, float radius, int color) implements Op {
    }

    /** 圆角矩形描边（厚度往内长）。 */
    public record RoundStroke(float x0, float y0, float x1, float y1,
                              float radius, float thickness, int color) implements Op {
    }

    /** 圆（标志花瓣、圆点）。 */
    public record Disc(float cx, float cy, float radius, int color) implements Op {
    }

    /**
     * 一行文字。
     *
     * @param x        锚点（含义由 {@code align} 定），字形<b>左</b>边界 / 中心 / 右边界
     * @param yTop     字形上沿
     * @param size     字形高度（px）
     * @param maxWidth 最宽多少 px（超了等比缩；{@code <= 0} = 不限）
     */
    public record Text(String text, float x, float yTop, float size, int color,
                       Align align, float maxWidth) implements Op {
    }

    /** 广告图该画在哪（有图时；没图时排版会换成色块 + 文字）。 */
    public record AdSlot(float x0, float y0, float x1, float y1) implements Op {
    }

    /**
     * 底部滚动标语条。
     *
     * @param sizeRatio 字高 ÷ 条高（交给 {@link PidsXiamenTicker#draw}）
     * @param sample    离线预览用的示例文字；游戏里忽略它，照旧滚资源包 / 广告自带的那条
     */
    public record Ticker(float x0, float y0, float x1, float y1, float sizeRatio,
                         String sample) implements Op {
    }

    /**
     * 这块屏要显示的内容（全部是已经解析好的字符串 —— 排版不认识 MTR）。
     *
     * @param date           {@code 2026-09-23}
     * @param weekdayZh      {@code 星期三}
     * @param weekdayEn      {@code Wednesday}
     * @param clock          {@code 11:10}
     * @param boundLabel     第一行标签（开往）
     * @param boundSub       第一行英文标签（Bound for）
     * @param dest1          第一行终点站（中文）
     * @param dest1En        第一行终点站（英文）
     * @param count1         第一行倒计时数字 / 状态（7、即将进站…）
     * @param nextLabel      第二行标签（下一趟开往）
     * @param nextSub        第二行英文标签（Next train）
     * @param dest2          第二行终点站（中文）
     * @param dest2En        第二行终点站（英文）
     * @param count2         第二行倒计时数字 / 状态
     * @param unitZh         倒计时单位（分钟）
     * @param unitEn         倒计时单位英文（min）
     * @param lineZh         线路徽标主字（6号线）
     * @param lineEn         线路徽标副字（Line 6）
     * @param lineColor      线路徽标底色（ARGB，取 MTR 的 route.color）
     * @param stationZh      当前站（中文）
     * @param stationEn      当前站（英文）
     * @param stationLabel   当前站的小标签（当前站 / This station）
     * @param cars           编组几节（没有数据时用 4）
     * @param carLabels      车厢号（1车 / 2车 …，长度就是画几张卡片）
     * @param adPlaceholder  没有广告图时那块色块上的字（此处放置广告）
     * @param tickerSample   离线预览画标语条用的示例文字（游戏里忽略，见 {@link Ticker}）
     * @param hasAd          有没有广告图
     */
    public record Content(String date, String weekdayZh, String weekdayEn, String clock,
                          String boundLabel, String boundSub, String dest1, String dest1En, String count1,
                          String nextLabel, String nextSub, String dest2, String dest2En, String count2,
                          String unitZh, String unitEn,
                          String lineZh, String lineEn, int lineColor,
                          String stationZh, String stationEn, String stationLabel,
                          int cars, List<String> carLabels,
                          String adPlaceholder, String tickerSample, boolean hasAd) {
    }

    // ================================================================ 出入口

    /** 这块屏的宽度（px）。 */
    public static float widthPx(final int segments) {
        return Math.max(1, segments) * PX_PER_BLOCK;
    }

    /** 窄版字号系数（见类注释）。 */
    public static float textScale(final int segments) {
        return Math.max(0.62F, Math.min(1.0F, Math.max(1, segments) / 4.0F));
    }

    /** 广告区占多宽（px）：约 1/4 屏宽，但不吃掉主区（窄屏时按比例缩，别把主区挤没）。 */
    public static float adWidth(final float width) {
        return Math.max(0.25F * width, Math.min(1.55F * HEIGHT_PX, 0.30F * width));
    }

    /** 内容区窄于这个宽度（以 h 为单位）就走「压缩版」排版：不画编组 / 线路徽标。 */
    public static final float NARROW_H = 4.0F;

    /** 内容区够不够宽（够宽才画线路徽标 / 当前站 / 编组那一组）。 */
    public static boolean narrow(final int segments) {
        return contentWidthPx(segments) < NARROW_H * HEIGHT_PX;
    }

    /** 内容区（= 广告区左边那条线到屏幕左边缘）的宽度。 */
    public static float contentWidthPx(final int segments) {
        final float w = widthPx(segments);
        return w - adWidth(w);
    }

    /** 一个矩形（屏幕 px）。 */
    public record Box(float x0, float y0, float x1, float y1) {
    }

    /**
     * 底部标语条的位置（px）。{@link #plan} 和渲染器算「一张广告播多久」都要用它 ——
     * 同一个函数出同一块矩形，两边的节拍才对得上。
     */
    public static Box tickerBox(final int segments) {
        final float h = HEIGHT_PX;
        final float contentW = contentWidthPx(segments);
        if (narrow(segments)) {
            return new Box(0.570F * h, 0.795F * h, contentW - 0.040F * h, 0.955F * h);
        }
        final float cw = Math.max(contentW, DESIGN_CONTENT_H * h);
        final float sx = contentW / cw;
        return new Box((0.660F * h + 0.060F * h) * sx, 0.795F * h,
                (cw - 0.060F * h) * sx, 0.955F * h);
    }

    // ================================================================ 排版

    /**
     * 生成这张屏的绘制指令表：<b>先所有色块 / 描边 / 图标，再所有文字</b>
     * （游戏里前者进色块层、后者进文字层，两个 buffer 的先后就是这里加进列表的先后，
     * 所以文字一定压在色块上面）。
     */
    public static List<Op> plan(final Content c, final int segments) {
        final int n = Math.max(1, segments);
        final float w = widthPx(n);
        final float h = HEIGHT_PX;
        final float t = textScale(n);

        final float adW = adWidth(w);
        final float adX0 = w - adW;
        final float contentW = adX0;
        // 内容区按「设计稿宽度」排版；窄屏再把横坐标整体压回来（压缩版）。
        final boolean narrow = contentW < NARROW_H * h;
        final float cw = narrow ? contentW : Math.max(contentW, DESIGN_CONTENT_H * h);
        final float sx = narrow ? 1.0F : contentW / cw;

        final List<Op> shapes = new ArrayList<>();
        final List<Op> texts = new ArrayList<>();

        // ---------------------------------------------------------- 左端卡片（空心白框）
        final float cardX0 = (narrow ? 0.050F : 0.060F) * h;
        final float cardX1 = (narrow ? 0.560F : 0.660F) * h;
        final float cardY0 = 0.045F * h;
        final float cardY1 = 0.955F * h;
        shapes.add(new RoundStroke(cardX0, cardY0, cardX1, cardY1,
                0.055F * h, Math.max(1.0F, 0.011F * h), COLOR_CARD_STROKE));

        // M 标志：白 M + 红色 ∧（实拍上的厦门地铁标志），画在卡片上半部分
        final float logoW = (cardX1 - cardX0) * 0.74F;
        logo(shapes, (cardX0 + cardX1) * 0.5F - logoW * 0.5F, 0.070F * h, logoW, logoW * 0.52F);

        final float cardCx = (cardX0 + cardX1) * 0.5F;
        final float cardTextW = (cardX1 - cardX0) - 0.060F * h;
        texts.add(new Text(c.date(), cardCx, 0.400F * h, 0.062F * h * t,
                COLOR_WHITE, Align.CENTER, cardTextW));
        texts.add(new Text(c.weekdayZh(), cardCx, 0.528F * h, 0.078F * h * t,
                COLOR_WHITE, Align.CENTER, cardTextW));
        texts.add(new Text(c.weekdayEn(), cardCx, 0.638F * h, 0.050F * h * t,
                COLOR_WHITE, Align.CENTER, cardTextW));
        texts.add(new Text(c.clock(), cardCx, 0.792F * h, 0.115F * h * t,
                COLOR_WHITE, Align.CENTER, cardTextW));

        // ---------------------------------------------------------- 主区（两行到站）
        // 宽屏：标签 / 终点站 / 数字三列固定比例（实拍的位置）；
        // 压缩版：卡片更窄，三列从右边倒着排，数字列贴住内容区右边界。
        final float labelX = narrow ? 0.620F * h : 0.760F * h;
        final float destX = narrow ? 0.960F * h : 1.360F * h;
        final float countRightX = narrow ? cw - 0.290F * h : destX + 1.060F * h;
        final float unitX = countRightX + 0.050F * h;
        final float destMaxW = Math.max(0.45F * h, countRightX - destX - 0.080F * h);
        final float unitMaxW = Math.max(0.24F * h, cw - unitX - 0.040F * h);

        row(texts, h, t, 0.345F * h, labelX, destX, countRightX, unitX,
                c.boundLabel(), c.boundSub(), c.dest1(), c.dest1En(), c.count1(),
                c.unitZh(), c.unitEn(), destMaxW, unitMaxW);
        row(texts, h, t, 0.680F * h, labelX, destX, countRightX, unitX,
                c.nextLabel(), c.nextSub(), c.dest2(), c.dest2En(), c.count2(),
                c.unitZh(), c.unitEn(), destMaxW, unitMaxW);

        // ---------------------------------------------------------- 右侧一组（线路徽标 + 当前站）
        if (narrow) {
            // 压缩版：只留「当前站」两行小字，右对齐贴着内容区右边（徽标 / 编组画不下）
            texts.add(new Text(c.stationZh(), cw - 0.040F * h, 0.045F * h, 0.105F * h * t,
                    COLOR_WHITE, Align.RIGHT, cw * 0.55F));
            texts.add(new Text(c.stationEn(), cw - 0.040F * h, 0.165F * h, 0.050F * h * t,
                    COLOR_WHITE, Align.RIGHT, cw * 0.55F));
        } else {
            final float badgeW = 0.780F * h;
            final float badgeH = 0.290F * h;
            final float badgeY0 = 0.045F * h;
            // 「贴右边界」是实拍的位置；内容区比设计稿宽时，把多出来的空隙分一半给它左边，
            // 于是右边一组落在空隙中间，两头留白一样多（见类注释「宽了怎么排」）。
            final float anchoredRight = cw - 2.220F * h;
            final float anchoredLeft = countRightX + 1.050F * h;
            final float badgeX0 = anchoredRight - anchoredLeft > 0.600F * h
                    ? anchoredLeft + (anchoredRight - anchoredLeft) * 0.5F
                    : anchoredRight;
            shapes.add(new RoundFill(badgeX0, badgeY0, badgeX0 + badgeW, badgeY0 + badgeH,
                    0.055F * h, c.lineColor()));
            flower(shapes, badgeX0 + 0.040F * h, badgeY0 + 0.040F * h, 0.210F * h);
            final float lineTextX = badgeX0 + 0.255F * h;
            final float lineTextW = badgeW - 0.275F * h;
            texts.add(new Text(c.lineZh(), lineTextX, badgeY0 + 0.040F * h, 0.110F * h * t,
                    COLOR_WHITE, Align.LEFT, lineTextW));
            texts.add(new Text(c.lineEn(), lineTextX, badgeY0 + 0.170F * h, 0.058F * h * t,
                    COLOR_WHITE, Align.LEFT, lineTextW));

            final float stationX = badgeX0 + badgeW + 0.300F * h;
            final float stationW = Math.max(0.50F * h, cw - 0.080F * h - stationX);
            texts.add(new Text(c.stationZh(), stationX, 0.030F * h, 0.210F * h * t,
                    COLOR_WHITE, Align.LEFT, stationW));
            texts.add(new Text(c.stationEn(), stationX + 0.010F * h, 0.258F * h, 0.072F * h * t,
                    COLOR_WHITE, Align.LEFT, stationW));
            texts.add(new Text(c.stationLabel(), stationX, 0.348F * h, 0.045F * h * t,
                    COLOR_DIM, Align.LEFT, stationW));

            // ------------------------------------------------------ 编组（右下，浅蓝小卡）
            final List<String> labels = c.carLabels() == null || c.carLabels().isEmpty()
                    ? List.of("1车", "2车", "3车", "4车") : c.carLabels();
            final int cars = Math.max(1, Math.min(8, Math.min(c.cars() <= 0 ? labels.size() : c.cars(),
                    labels.size())));
            final float carGap = 0.058F * h;
            final float carW = 0.440F * h;
            final float carH = 0.230F * h;
            final float carY0 = 0.455F * h;
            // 编组卡片和「右侧一组」一起居中（实拍里它比徽标左沿再往左出一点）
            final float groupCx = badgeX0 + badgeW * 0.5F + 0.700F * h;
            final float carX0 = groupCx - (cars * carW + (cars - 1) * carGap) * 0.5F;
            for (int i = 0; i < cars; i++) {
                final float x0 = carX0 + i * (carW + carGap);
                carCard(shapes, texts, h, t, x0, carY0, x0 + carW, carY0 + carH, labels.get(i));
            }
        }

        // ---------------------------------------------------------- 底部标语条（通长、胶囊形）
        final Box bar = tickerBox(n);
        if (bar.x1() - bar.x0() > 4.0F) {
            final float radius = (bar.y1() - bar.y0()) * 0.5F;
            shapes.add(new RoundStroke(bar.x0(), bar.y0(), bar.x1(), bar.y1(), radius,
                    Math.max(1.0F, 0.010F * h), COLOR_TICKER_STROKE));
            shapes.add(new Ticker(bar.x0(), bar.y0(), bar.x1(), bar.y1(), 0.44F, c.tickerSample()));
        }

        // ---------------------------------------------------------- 拼起来（广告区在内容区之外）
        final List<Op> out = new ArrayList<>(shapes.size() + texts.size() + 3);
        out.add(new Fill(0.0F, 0.0F, w, h, COLOR_BG));
        for (final Op op : shapes) {
            out.add(sx == 1.0F ? op : scaleX(op, sx));
        }
        if (c.hasAd()) {
            out.add(new AdSlot(adX0, 0.0F, w, h));
        } else {
            out.add(new Fill(adX0, 0.0F, w, h, COLOR_AD_BG));
            out.add(new Text(c.adPlaceholder(), (adX0 + w) * 0.5F, h * 0.5F - h * 0.038F,
                    h * 0.076F, COLOR_AD_TEXT, Align.CENTER, adW * 0.86F));
        }
        for (final Op op : texts) {
            out.add(sx == 1.0F ? op : scaleX(op, sx));
        }
        return out;
    }

    /** 横坐标整体乘一个系数（压缩版）。 */
    private static Op scaleX(final Op op, final float sx) {
        if (op instanceof Fill f) {
            return new Fill(f.x0() * sx, f.y0(), f.x1() * sx, f.y1(), f.color());
        }
        if (op instanceof Quad q) {
            return new Quad(q.x0() * sx, q.y0(), q.x1() * sx, q.y1(),
                    q.x2() * sx, q.y2(), q.x3() * sx, q.y3(), q.color());
        }
        if (op instanceof RoundFill r) {
            return new RoundFill(r.x0() * sx, r.y0(), r.x1() * sx, r.y1(), r.radius(), r.color());
        }
        if (op instanceof RoundStroke s) {
            return new RoundStroke(s.x0() * sx, s.y0(), s.x1() * sx, s.y1(),
                    s.radius(), s.thickness(), s.color());
        }
        if (op instanceof Disc d) {
            return new Disc(d.cx() * sx, d.cy(), d.radius(), d.color());
        }
        if (op instanceof Text when) {
            return new Text(when.text(), when.x() * sx, when.yTop(), when.size(), when.color(),
                    when.align(), when.maxWidth() * sx);
        }
        if (op instanceof AdSlot slot) {
            return new AdSlot(slot.x0() * sx, slot.y0(), slot.x1() * sx, slot.y1());
        }
        if (op instanceof Ticker ticker) {
            return new Ticker(ticker.x0() * sx, ticker.y0(), ticker.x1() * sx, ticker.y1(),
                    ticker.sizeRatio(), ticker.sample());
        }
        return op;
    }

    // ================================================================ 一行到站

    private static void row(final List<Op> texts, final float h, final float t,
                            final float centerY, final float labelX, final float destX,
                            final float countRightX, final float unitX,
                            final String label, final String labelSub,
                            final String dest, final String destEn, final String count,
                            final String unitZh, final String unitEn,
                            final float destMaxW, final float unitMaxW) {
        texts.add(new Text(label, labelX, centerY - 0.078F * h, 0.062F * h * t,
                COLOR_WHITE, Align.LEFT, destX - labelX - 0.04F * h));
        texts.add(new Text(labelSub, labelX, centerY + 0.026F * h, 0.046F * h * t,
                COLOR_WHITE, Align.LEFT, destX - labelX - 0.04F * h));
        texts.add(new Text(dest, destX, centerY - 0.086F * h, 0.135F * h * t,
                COLOR_WHITE, Align.LEFT, destMaxW));
        texts.add(new Text(destEn, destX, centerY + 0.066F * h, 0.050F * h * t,
                COLOR_WHITE, Align.LEFT, destMaxW));
        // 倒计时：数字右对齐到 countRightX，单位紧跟着左对齐 —— 「7 分钟」永远是一组
        texts.add(new Text(count, countRightX, centerY - 0.114F * h, 0.235F * h * t,
                COLOR_WHITE, Align.RIGHT, 0.90F * h));
        texts.add(new Text(unitZh, unitX, centerY - 0.046F * h, 0.052F * h * t,
                COLOR_WHITE, Align.LEFT, unitMaxW));
        texts.add(new Text(unitEn, unitX, centerY + 0.036F * h, 0.044F * h * t,
                COLOR_WHITE, Align.LEFT, unitMaxW));
    }

    // ================================================================ 编组卡片

    private static void carCard(final List<Op> shapes, final List<Op> texts,
                                final float h, final float t,
                                final float x0, final float y0, final float x1, final float y1,
                                final String label) {
        final float r = 0.030F * h;
        // 卡片本体（浅蓝）
        shapes.add(new RoundFill(x0, y0, x1, y1, r, COLOR_CAR_CARD));
        // 座位窗口（更浅的底 + 绿色描边）—— 上半部分
        final float wx0 = x0 + 0.028F * h;
        final float wx1 = x1 - 0.028F * h;
        final float wy0 = y0 + 0.026F * h;
        final float wy1 = y1 - 0.080F * h;
        shapes.add(new RoundFill(wx0, wy0, wx1, wy1, 0.018F * h, COLOR_CAR_WINDOW));
        shapes.add(new RoundStroke(wx0, wy0, wx1, wy1, 0.018F * h,
                Math.max(0.8F, 0.008F * h), COLOR_CAR_SEAT));
        // 座椅图标（绿色的「靠背 + 坐垫 + 椅腿」）
        seat(shapes, (wx0 + wx1) * 0.5F - 0.050F * h, (wy0 + wy1) * 0.5F - 0.044F * h,
                0.100F * h, 0.088F * h);
        // 底部深蓝标签带（车厢号 + 空调雪花）
        shapes.add(new Fill(x0 + 0.012F * h, y1 - 0.072F * h, x1 - 0.012F * h, y1 - 0.010F * h,
                COLOR_CAR_BAND));
        final float bandCy = y1 - 0.041F * h;
        texts.add(new Text(label, (x0 + x1) * 0.5F - 0.032F * h, bandCy - 0.024F * h,
                0.050F * h * t, COLOR_WHITE, Align.CENTER, 0.22F * h));
        snowflake(shapes, x1 - 0.108F * h, bandCy - 0.022F * h, 0.046F * h);
    }

    // ================================================================ 图标（全部展开成四边形 / 圆）

    /** 厦门地铁 M：红色 ∧ 打底，白色 M 压在上面（实拍的观感：红在两条腿之间）。 */
    private static void logo(final List<Op> out, final float x0, final float y0,
                             final float w, final float h) {
        final float lw = 0.175F * w;          // 笔画粗细
        // 红色 ∧：顶点在中间偏下，两条腿落到左右下角
        stroke(out, x0 + 0.50F * w, y0 + 0.62F * h, x0 + 0.10F * w, y0 + 1.02F * h, 0.26F * w,
                COLOR_LOGO_RED);
        stroke(out, x0 + 0.50F * w, y0 + 0.62F * h, x0 + 0.90F * w, y0 + 1.02F * h, 0.26F * w,
                COLOR_LOGO_RED);
        // 白色 M：左竖 + 左斜 + 右斜 + 右竖
        stroke(out, x0 + lw * 0.5F, y0 + 1.00F * h, x0 + lw * 0.5F, y0 + 0.06F * h, lw, COLOR_WHITE);
        stroke(out, x0 + lw * 0.5F, y0 + 0.06F * h, x0 + 0.50F * w, y0 + 0.68F * h, lw, COLOR_WHITE);
        stroke(out, x0 + 0.50F * w, y0 + 0.68F * h, x0 + w - lw * 0.5F, y0 + 0.06F * h, lw, COLOR_WHITE);
        stroke(out, x0 + w - lw * 0.5F, y0 + 0.06F * h, x0 + w - lw * 0.5F, y0 + 1.00F * h, lw,
                COLOR_WHITE);
    }

    /** 线路徽标左边那朵白花：三片花瓣 + 一个花心。 */
    private static void flower(final List<Op> out, final float x0, final float y0, final float size) {
        final float cx = x0 + size * 0.5F;
        final float cy = y0 + size * 0.5F;
        final float petal = size * 0.285F;
        final float reach = size * 0.285F;
        out.add(new Disc(cx, cy - reach, petal, COLOR_WHITE));
        out.add(new Disc(cx - reach * 0.87F, cy + reach * 0.52F, petal, COLOR_WHITE));
        out.add(new Disc(cx + reach * 0.87F, cy + reach * 0.52F, petal, COLOR_WHITE));
        out.add(new Disc(cx, cy, size * 0.105F, COLOR_WHITE));
    }

    /** 座椅（侧视）：一条靠背竖笔 + 一条坐垫横笔 + 前端下垂 + 椅腿。 */
    private static void seat(final List<Op> out, final float x0, final float y0,
                             final float w, final float h) {
        final float t = 0.115F * Math.min(w, h);
        stroke(out, x0 + 0.74F * w, y0 + 0.06F * h, x0 + 0.70F * w, y0 + 0.48F * h, t, COLOR_CAR_SEAT);
        stroke(out, x0 + 0.70F * w, y0 + 0.48F * h, x0 + 0.20F * w, y0 + 0.48F * h, t, COLOR_CAR_SEAT);
        stroke(out, x0 + 0.20F * w, y0 + 0.48F * h, x0 + 0.20F * w, y0 + 0.80F * h, t, COLOR_CAR_SEAT);
        stroke(out, x0 + 0.70F * w, y0 + 0.48F * h, x0 + 0.74F * w, y0 + 0.92F * h, t, COLOR_CAR_SEAT);
    }

    /** 空调雪花：三根交叉的短笔画。 */
    private static void snowflake(final List<Op> out, final float x0, final float y0, final float size) {
        final float cx = x0 + size * 0.5F;
        final float cy = y0 + size * 0.5F;
        final float r = size * 0.5F;
        final float t = Math.max(0.8F, size * 0.15F);
        stroke(out, cx - r, cy, cx + r, cy, t, COLOR_WHITE);
        stroke(out, cx - r * 0.5F, cy - r * 0.87F, cx + r * 0.5F, cy + r * 0.87F, t, COLOR_WHITE);
        stroke(out, cx + r * 0.5F, cy - r * 0.87F, cx - r * 0.5F, cy + r * 0.87F, t, COLOR_WHITE);
    }

    /** 一根「粗线」= 一个四边形。 */
    private static void stroke(final List<Op> out, final float ax, final float ay,
                               final float bx, final float by, final float width, final int color) {
        float dx = bx - ax;
        float dy = by - ay;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-4F) {
            return;
        }
        dx /= len;
        dy /= len;
        final float nx = -dy * width * 0.5F;
        final float ny = dx * width * 0.5F;
        out.add(new Quad(ax - nx, ay - ny, bx - nx, by - ny, bx + nx, by + ny, ax + nx, ay + ny,
                color));
    }
}
