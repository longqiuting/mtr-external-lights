// 厦门地铁「屏蔽门顶板 LCD 挂板」（PSD-LCD）的整屏绘制：纯 Java2D，一个 MC 类都不 import。
//
// 渲染器（PsdLcdRenderer）和离线预览（tools/psd-lcd/PsdLcdPreview.java）都调本类的
// paint()，所以「游戏里看到的」和「预览图」是同一份代码画的，不会各画各的。
//
// 坐标系（改版式之前先读这段）：
//   · **x 一律按「占屏宽的比例」给**（0 = 左边缘，1 = 右边缘）；
//   · **y 和所有尺寸（字高 / 卡片宽高 / 描边）按「占排版高 h 的比例」给**。
// 数值全部是从设计稿 `厦门地铁PSD-LCD示意.png`（5383×779 = 6.910:1）上**逐像素量**出来的
// 归一化包围盒（量法见 temp/probe-design/DesignProbe6.java），不是估的：
//   java ... DesignProbe6 "C:/Users/AD/Desktop/厦门地铁PSD-LCD示意.png"
// 量出来的比例表在 docs/psd-lcd-geometry.md。
//
// 为什么 x 按宽、y 按高：设计稿是 6.91:1，而挂板按段数变宽（3 段 6.86:1、4 段 9.14:1、
// 13 段 29.7:1）。设计稿里每块的位置是「贴着横向某个比例」的（左卡片贴左边、广告区贴右边），
// 所以横向位置跟着宽度走；而字高、卡片大小跟高度走 —— 屏再宽，字也不会被拉成胖头鱼。
//
// 版式（x 占屏宽 / y 占排版高，设计稿量到的值）：
//
//   ┌───────────────────────────────────────────────────────┬──────────┐
//   │┌────┐ 开往   终点站   X  分钟   [X号线]   当前站       │          │
//   ││ M  │ Bound  Dest     min                        广告区（整高）  │
//   ││date│ 下一趟 终点站   X  分钟   ┌─车头─┬1车┬2车┬3车┐  │          │
//   ││星期│ Next   Dest     min       └──────┴───┴───┴───┘  │          │
//   │└────┘ ┌──── 标语（框 + 字都位图里）────┐              │          │
//   └───────────────────────────────────────────────────────┴──────────┘
//     0.10  0.16    0.20  0.32 0.37  0.44   0.57…0.69      0.72      1.0
//
// 素材：
//   · M 标志 = textures/gui/psd_lcd/xiamen_logo.png（600×600，白 M + 红 ∧）
//   · 编组行的车头 / 单雪花卡片 / 双雪花卡片 = train_nose.png / car_end.png / car_middle.png
//   · 广告区：优先画厦门 PIDS 那套广告图（PidsXiamenAds → 同一条资源链），拿不到就画
//     设计稿那种洋红块 + 居中「广告位」 —— 广告区**永远是独立的一块**，不能省。
//
// 字体：和厦门普通 PIDS / 线路条带**同一套**（XiamenStripPainter#font()：SimHei →
// 微软雅黑 → Noto Sans SC 的 BOLD；拉丁行同族 PLAIN），见 PsdLcdTypeface。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 整屏排版 + 绘制。宽高的单位：x 用「屏宽」，y / 尺寸用「排版高 h」，见类注释。
 *
 * <p><b>所有东西都画进这一张位图</b>：文字、图标、线路徽标、编组卡片、标语条、广告区。
 * 渲染器那边没有任何额外的文字 / 标语 / 广告 quad，所以屏上不可能出现两遍同样的内容。
 */
public final class PsdLcdPainter {

    private PsdLcdPainter() {
    }

    // ================================================================ 版式：设计稿比例

    /** 设计稿屏宽（h）—— 5383×779 = 6.910:1。窄屏（1~2 段）按这个比例收窄后上下居中。 */
    public static final float DESIGN_W = 6.910F;

    // ---- 左卡片（M 标 + 四行）
    private static final float CARD_X0 = 0.0020F;
    private static final float CARD_X1 = 0.0995F;
    private static final float CARD_Y0 = 0.0193F;
    private static final float CARD_Y1 = 0.9756F;
    private static final float CARD_R = 0.0480F;
    private static final float CARD_SW = 0.0090F;

    /** 卡片里四行字的中线（比卡片几何中线略偏左，设计稿就是这样）。 */
    private static final float CARD_TEXT_CX = 0.0477F;
    /** M 标志：墨迹中线 + 墨迹上沿 + 墨迹高（等比缩放，不拉伸）。 */
    private static final float LOGO_CX = 0.0499F;
    private static final float LOGO_TOP = 0.0295F;
    private static final float LOGO_H = 0.3350F;

    private static final float DATE_TOP = 0.4223F;
    private static final float DATE_INK = 0.0436F;
    private static final float WDZH_TOP = 0.5558F;
    private static final float WDZH_INK = 0.0834F;
    private static final float WDEN_TOP = 0.6919F;
    private static final float WDEN_INK = 0.0680F;
    private static final float CLOCK_TOP = 0.8395F;
    private static final float CLOCK_INK = 0.0680F;

    // ---- 两行到站（第一行的中线 + 第二行整体往下挪多少）
    private static final float ROW1_CY = 0.3723F;
    private static final float ROW_DY = 0.2950F;

    /** 标签列左缘（左对齐）：开往 / Bound for。 */
    private static final float LBL_X = 0.1100F;
    private static final float LBLZH_TOP = -0.0539F;
    private static final float LBLZH_INK = 0.0591F;
    private static final float LBLEN_TOP = 0.0398F;
    private static final float LBLEN_INK = 0.0411F;

    /** 终点站列中线（中文 / 英文都居中在这条线上）。 */
    private static final float DEST_CX = 0.2212F;
    private static final float DEST_TOP = -0.0976F;
    private static final float DEST_INK = 0.1014F;
    private static final float DESTEN_TOP = 0.0462F;
    private static final float DESTEN_INK = 0.0513F;

    /** 大数字右缘（右对齐：7 和 17 都贴着这一列）、墨迹上沿、墨迹高。 */
    private static final float NUM_RIGHT = 0.3400F;
    private static final float NUM_TOP = -0.1066F;
    private static final float NUM_INK = 0.1772F;

    /** 「分钟 / min」两行的左缘 / 墨迹上沿 / 墨迹高。 */
    private static final float UNIT_X = 0.3660F;
    private static final float UNITZH_TOP = -0.0077F;
    private static final float UNITZH_INK = 0.0430F;
    private static final float UNITEN_TOP = 0.0397F;
    private static final float UNITEN_INK = 0.0373F;

    // ---- 线路徽标（色块 + 两行字，没有图标）
    private static final float BADGE_X0 = 0.4392F;
    private static final float BADGE_X1 = 0.5367F;
    private static final float BADGE_Y0 = 0.0565F;
    private static final float BADGE_Y1 = 0.3273F;
    private static final float BADGE_R = 0.0300F;
    private static final float BADGE_CX = 0.4880F;
    private static final float LINE_ZH_TOP = 0.0988F;
    private static final float LINE_ZH_INK = 0.1142F;
    private static final float LINE_EN_TOP = 0.2388F;
    private static final float LINE_EN_INK = 0.0757F;

    // ---- 当前站（大字两行）
    private static final float STA_CX = 0.6075F;
    private static final float STA_ZH_TOP = 0.0693F;
    private static final float STA_ZH_INK = 0.1643F;
    private static final float STA_EN_TOP = 0.2593F;
    private static final float STA_EN_INK = 0.0552F;

    // ---- 编组行：车头 + N 节卡片（右缘对齐 CAR_ROW_X1，车头往左独立占位）
    private static final float CAR_ROW_X1 = 0.6894F;
    /** 编组行最左能到哪（占屏宽）：再往左就压到「分钟 / min」那一列了。 */
    private static final float CAR_ROW_X0 = 0.3158F;
    private static final float CAR_Y0 = 0.4492F;
    private static final float CAR_Y1 = 0.6870F;
    /** 卡片自然宽（h）。设计稿 4 节卡片是 0.0656 屏宽 = 0.453h，素材比例同一档。 */
    private static final float CAR_W = 0.4690F;
    /** 卡片之间 / 车头与首卡之间的间隙 ÷ 卡片宽（设计稿 0.0068 屏宽 ÷ 0.0656）。 */
    private static final float CAR_GAP_RATIO = 0.1000F;
    /** 车头高 ÷ 卡片高（素材 74×180，圆头朝左）。 */
    private static final float NOSE_H_RATIO = 1.035F;
    /** 卡片素材的原始像素尺寸（按比例缩放，绝不拉伸）。 */
    private static final float CARD_PX_W = 2144.0F;
    private static final float CARD_PX_H = 1024.0F;
    /** 素材里「车」字的墨迹位置（占卡片宽 / 高的比例）。 */
    private static final float CAR_TEXT_LEFT = 697.0F / CARD_PX_W;
    private static final float CAR_TEXT_INK_H = 205.0F / CARD_PX_H;
    private static final float CAR_TEXT_CY = 786.5F / CARD_PX_H;

    /**
     * 编组注记（编组行下面那一行小字）。
     *
     * <p>左缘放在「分钟 / min」那一列的右边（{@link #UNIT_X} 之后）：设计稿里它是从车头底下
     * 起笔的，但车头那一块正好压在第二行的「分钟 / min」下面，照抄会两行字叠在一起。
     */
    private static final float NOTE_X = 0.3950F;
    private static final float NOTE_TOP = 0.7000F;
    private static final float NOTE_INK = 0.0420F;

    /** 标语条（圆角描边胶囊）：框和字都画在位图里。 */
    private static final float TICK_X0 = 0.0999F;
    private static final float TICK_X1 = 0.7171F;
    private static final float TICK_Y0 = 0.8280F;
    private static final float TICK_Y1 = 0.9756F;
    private static final float TICK_SW = 0.0075F;
    /** 标语字高 ÷ 条高。 */
    private static final float TICK_SIZE_RATIO = 0.52F;

    // ---- 广告区：0.7197 屏宽 → 右边缘，整屏高，独立一块（设计稿的洋红块）
    private static final float AD_X0 = 0.7197F;
    private static final float AD_CY = 0.4936F;
    private static final float AD_TEXT_INK = 0.0757F;

    // ================================================================ 颜色

    public static final int COLOR_BG = 0xFF0206E0;
    public static final int COLOR_WHITE = 0xFFFFFFFF;
    public static final int COLOR_CARD_STROKE = 0xFFF4F6FF;
    public static final int COLOR_TICK_STROKE = 0xFFE6EAFF;
    /** 广告位底色（设计稿取色：rgb(e600c3)）。 */
    public static final int COLOR_AD = 0xFFE600C3;

    /** 量字用的 FRC：关掉抗锯齿量墨迹，得到的是「这个字号下字形的真实轮廓」。 */
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    // ================================================================ 数据

    /** 水平对齐。 */
    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    /**
     * 这块屏要显示的内容（全部是已经解析好的字符串 —— 排版不认识 MTR）。
     *
     * @param carNumbers 车厢编号（1 2 3 4），长度 = 画几张卡片
     * @param carNote    编组行下面那行小字（空 = 不画）
     * @param tickerText 标语条文字（画进位图；空 = 连标语框都不画）
     */
    public record Content(String date, String weekdayZh, String weekdayEn, String clock,
                          String boundLabel, String boundSub, String dest1, String dest1En, String count1,
                          String nextLabel, String nextSub, String dest2, String dest2En, String count2,
                          String unitZh, String unitEn,
                          String lineZh, String lineEn, int lineColor,
                          String stationZh, String stationEn,
                          List<String> carNumbers, String carNote,
                          String tickerText,
                          String adLabel) {
    }

    /** 素材 + 字体。字体为 null 时退回逻辑字体（预览工具/资源包缺字体时不会崩）。 */
    public static final class Assets {
        /** 厦门地铁 M 标志（textures/gui/psd_lcd/xiamen_logo.png，600×600）。 */
        public BufferedImage logo;
        /** 车头（train_nose.png，74×180，圆头朝左 = 线路前进方向）。 */
        public BufferedImage nose;
        /** 头/尾车卡片（car_end.png，单雪花）。 */
        public BufferedImage carEnd;
        /** 中间车卡片（car_middle.png，双雪花）。 */
        public BufferedImage carMiddle;
        public Font cjk;
        public Font latin;
        public Font digits;

        public Assets() {
        }

        public Assets(final BufferedImage logo, final Font cjk, final Font latin, final Font digits) {
            this.logo = logo;
            this.cjk = cjk;
            this.latin = latin;
            this.digits = digits;
        }

        Font cjk() {
            return this.cjk != null ? this.cjk : new Font(Font.SANS_SERIF, Font.BOLD, 100);
        }

        Font latin() {
            return this.latin != null ? this.latin : new Font(Font.SANS_SERIF, Font.PLAIN, 100);
        }

        Font digits() {
            return this.digits != null ? this.digits : latin();
        }
    }

    // ================================================================ 出入口

    /** 整屏画到一张新图上（{@code width/height} 是屏幕像素，比例自己给：6.9:1 / 13:1 都行）。 */
    public static BufferedImage paint(final Content c, final Assets assets, final int width, final int height) {
        return paint(c, assets, null, width, height);
    }

    /** 同上，另带一张广告图（广告区铺这张图；{@code null} = 洋红占位块）。 */
    public static BufferedImage paint(final Content c, final Assets assets, final BufferedImage ad,
                                      final int width, final int height) {
        final BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            // 素材（2144×1024 的卡片）缩到屏幕尺寸：双线性 + drawScaled 的逐级减半，不用最近邻
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            paintInto(g, c, assets, ad, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 画到现成的画布上（预览工具拼多张图 / 渲染器复用一个 BufferedImage 时用）。 */
    public static void paintInto(final Graphics2D g, final Content c, final Assets assets,
                                 final float width, final float height) {
        paintInto(g, c, assets, null, width, height);
    }

    /** 画到现成的画布上，另带广告图。 */
    public static void paintInto(final Graphics2D g, final Content c, final Assets assets,
                                 final BufferedImage ad, final float width, final float height) {
        if (g == null || c == null) {
            return;
        }
        // h = 排版高。面板比设计稿更窄（只有 1~2 段）时按 6.91:1 收窄，内容上下居中留边，
        // 而不是让左卡片和右侧一组叠在一起 —— 「滑到底也看不到」这种挤爆的排版比留边难看得多。
        // 注意：**只收窄、不拉伸** —— 位图本身的宽高比永远等于模型窗口的宽高比。
        final float h = layoutHeight(width, height);
        final float offY = (height - h) * 0.5F;

        // ---- 底（整屏一个颜色）
        g.setColor(argb(COLOR_BG));
        g.fill(new Rectangle2D.Float(0.0F, 0.0F, width, height));

        // ---- 广告区：**整屏高**独立一块（不受上下留边影响，量的是位图高度）
        adBlock(g, c, assets, ad, width, height, h, offY);

        final java.awt.geom.AffineTransform savedTransform = g.getTransform();
        if (offY > 0.01F) {
            g.translate(0.0D, offY);
        }
        try {
            // ---- 左卡片
            card(g, width, h);
            logo(g, assets, width, h);
            cardTexts(g, c, assets, width, h);

            // ---- 两行到站
            row(g, c, assets, width, h, ROW1_CY, c.boundLabel(), c.boundSub(),
                    c.dest1(), c.dest1En(), c.count1(), c.unitZh(), c.unitEn());
            row(g, c, assets, width, h, ROW1_CY + ROW_DY, c.nextLabel(), c.nextSub(),
                    c.dest2(), c.dest2En(), c.count2(), c.unitZh(), c.unitEn());

            // ---- 右侧一组
            badge(g, c, assets, width, h);
            station(g, c, assets, width, h);
            cars(g, c, assets, width, h);
            note(g, c, assets, width, h);

            // ---- 标语条：框 + 字都在位图里
            paintTicker(g, c, assets, width, h);
        } finally {
            g.setTransform(savedTransform);
        }
    }

    /**
     * 排版高：和面板高一致（面板一段就是 16/7 = 2.29:1）。
     *
     * <p>和设计稿 {@link #DESIGN_W}:1 的比例差在 1% 以内时直接用整块高 —— 不留边，位图第一行就是
     * 内容第一行（3 段 6.857:1 vs 设计稿 6.910:1 只差 0.77%，以前那 0.77% 会变成上下两条底色边）。
     * 面板明显比设计稿窄（1~2 段）时按设计比例把内容收窄后上下居中，免得左卡片和右侧一组叠在一起。
     */
    public static float layoutHeight(final float width, final float height) {
        final float design = width / DESIGN_W;
        if (Math.abs(design - height) <= height * 0.01F) {
            return height;
        }
        return Math.min(height, Math.max(1.0F, design));
    }

    // ================================================================ 广告区

    /**
     * 广告区：{@link #AD_X0}（0.7197 屏宽）→ 右边缘，整屏高。
     *
     * <p>设计稿里它就是**独立的一块**（洋红），和左边蓝色内容区之间没有留白 —— 这一格一旦省掉，
     * 整块屏就变成「一片蓝到底」，和设计稿对不上（上一轮就是这么错的）。
     *
     * <p>内容优先铺厦门 PIDS 那套广告图（{@code PidsXiamenAds} 给的那张，按 cover 铺满、
     * 超出部分裁掉）；没有图就画洋红底 + 居中「广告位」。
     */
    private static void adBlock(final Graphics2D g, final Content c, final Assets assets,
                                final BufferedImage ad, final float width, final float height,
                                final float h, final float offY) {
        final float x0 = AD_X0 * width;
        final float x1 = width;
        if (x1 - x0 < 1.0F) {
            return;
        }
        g.setColor(argb(COLOR_AD));
        g.fill(new Rectangle2D.Float(x0, 0.0F, x1 - x0, height));

        if (ad != null && ad.getWidth() > 0 && ad.getHeight() > 0) {
            // cover：按大的一边铺满，另一边居中溢出后被裁掉（不拉伸变形、不留边）
            final float scale = Math.max((x1 - x0) / ad.getWidth(), height / ad.getHeight());
            final float dw = ad.getWidth() * scale;
            final float dh = ad.getHeight() * scale;
            final Shape old = g.getClip();
            g.clip(new Rectangle2D.Float(x0, 0.0F, x1 - x0, height));
            try {
                drawScaled(g, ad, x0 + ((x1 - x0) - dw) * 0.5F, (height - dh) * 0.5F, dw, dh);
            } finally {
                g.setClip(old);
            }
            return;
        }

        // 占位：洋红块 + 居中「广告位」（字高按排版高，位置跟着排版区走）
        final String label = c.adLabel() == null || c.adLabel().isBlank() ? "广告位" : c.adLabel();
        line(g, label, assets == null ? null : assets.cjk(), "广告位",
                offY + (AD_CY - AD_TEXT_INK * 0.5F) * h, AD_TEXT_INK * h,
                (x0 + x1) * 0.5F, Align.CENTER, (x1 - x0) * 0.8F, COLOR_WHITE);
    }

    // ================================================================ 左卡片

    private static void card(final Graphics2D g, final float w, final float h) {
        final float x0 = CARD_X0 * w;
        final float x1 = CARD_X1 * w;
        final float y0 = CARD_Y0 * h;
        final float y1 = CARD_Y1 * h;
        final float sw = Math.max(1.0F, CARD_SW * h);
        g.setColor(argb(COLOR_CARD_STROKE));
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(x0 + sw * 0.5F, y0 + sw * 0.5F,
                (x1 - x0) - sw, (y1 - y0) - sw, CARD_R * h * 2.0F, CARD_R * h * 2.0F));
        g.setStroke(new BasicStroke(1.0F));
    }

    private static void logo(final Graphics2D g, final Assets assets, final float w, final float h) {
        if (assets == null || assets.logo == null) {
            return;
        }
        final BufferedImage image = assets.logo;
        // 等比：以墨迹高为准（xiamen_logo.png 的白色墨迹在 600×600 里是 470×376，四周有留白，
        // 所以这里按「墨迹」而不是「整张图」对齐 —— 卡片里看到的大小才是对的）。
        final float inkW = 470.0F;
        final float inkH = 376.0F;
        final float scale = (LOGO_H * h) / inkH;         // 每张图像素 -> 屏幕像素
        final float drawW = image.getWidth() * scale;
        final float drawH = image.getHeight() * scale;
        // 墨迹在整张图里的左上角（65,112）：把它摆到 LOGO_CX ± inkW/2、LOGO_TOP
        final float inkLeftInImage = 65.0F * scale;
        final float inkTopInImage = 112.0F * scale;
        final float x = LOGO_CX * w - (inkW * scale) * 0.5F - inkLeftInImage;
        final float y = LOGO_TOP * h - inkTopInImage;
        drawScaled(g, image, x, y, drawW, drawH);
    }

    private static void cardTexts(final Graphics2D g, final Content c, final Assets assets,
                                  final float w, final float h) {
        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final float cx = CARD_TEXT_CX * w;
        final float maxW = (CARD_X1 - CARD_X0) * w - 0.02F * h;
        final int white = COLOR_WHITE;

        line(g, dashDate(c.date()), latin, "2026-09-23", DATE_TOP * h, DATE_INK * h,
                cx, Align.CENTER, maxW, white);
        line(g, c.weekdayZh(), cjk, "星期三", WDZH_TOP * h, WDZH_INK * h,
                cx, Align.CENTER, maxW, white);
        line(g, c.weekdayEn(), latin, "Wednesday", WDEN_TOP * h, WDEN_INK * h,
                cx, Align.CENTER, maxW, white);
        line(g, c.clock(), latin, "11:10", CLOCK_TOP * h, CLOCK_INK * h,
                cx, Align.CENTER, maxW, white);
    }

    /** 日期里的连字符换成长划线（实拍就是这样，短横看着太碎）。 */
    private static String dashDate(final String date) {
        return date == null ? "" : date.replace('-', '\u2013').replace('/', '\u2013');
    }

    // ================================================================ 一行到站

    /**
     * 一行到站：标签列（左对齐）+ 终点站（中线居中）+ 大数字（右对齐）+ 单位列。
     *
     * @param cyH 这一行的中线（h）
     */
    private static void row(final Graphics2D g, final Content c, final Assets assets,
                            final float w, final float h, final float cyH,
                            final String label, final String labelSub, final String dest,
                            final String destEn, final String count,
                            final String unitZh, final String unitEn) {
        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final Font digits = assets == null ? null : assets.digits();
        final float cy = cyH * h;
        final int white = COLOR_WHITE;

        final float labelX = LBL_X * w;
        line(g, label, cjk, "开往", cy + LBLZH_TOP * h, LBLZH_INK * h,
                labelX, Align.LEFT, 0.10F * w, white);
        line(g, labelSub, latin, "Bound for", cy + LBLEN_TOP * h, LBLEN_INK * h,
                labelX, Align.LEFT, 0.10F * w, white);

        final float destCx = DEST_CX * w;
        line(g, dest, cjk, "华侨大学", cy + DEST_TOP * h, DEST_INK * h,
                destCx, Align.CENTER, 0.10F * w, white);
        line(g, destEn, latin, "Huaqiao University", cy + DESTEN_TOP * h, DESTEN_INK * h,
                destCx, Align.CENTER, 0.10F * w, white);

        // 大数字：右缘对齐（7 与 17 都贴着这一列的右边）
        line(g, count, digits, "0", cy + NUM_TOP * h, NUM_INK * h,
                NUM_RIGHT * w, Align.RIGHT, 0.07F * w, white);
        if (count != null && !count.isEmpty()) {
            final float unitX = UNIT_X * w;
            line(g, unitZh, cjk, "分钟", cy + UNITZH_TOP * h, UNITZH_INK * h,
                    unitX, Align.LEFT, 0.045F * w, white);
            line(g, unitEn, latin, "min", cy + UNITEN_TOP * h, UNITEN_INK * h,
                    unitX, Align.LEFT, 0.045F * w, white);
        }
    }

    // ================================================================ 右侧一组

    private static void badge(final Graphics2D g, final Content c, final Assets assets,
                              final float w, final float h) {
        final float x0 = BADGE_X0 * w;
        final float x1 = BADGE_X1 * w;
        final float y0 = BADGE_Y0 * h;
        final float y1 = BADGE_Y1 * h;
        g.setColor(new Color(c.lineColor(), true));
        g.fill(new RoundRectangle2D.Float(x0, y0, x1 - x0, y1 - y0, BADGE_R * h * 2.0F, BADGE_R * h * 2.0F));

        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final int white = COLOR_WHITE;
        // 只有色块 + 两行字，两行都居中（示意图就是这样；没有图标）。
        final float cx = BADGE_CX * w;
        final float maxW = (x1 - x0) * 0.90F;
        line(g, c.lineZh(), cjk, "6号线", LINE_ZH_TOP * h, LINE_ZH_INK * h, cx, Align.CENTER, maxW, white);
        line(g, c.lineEn(), latin, "Line 6", LINE_EN_TOP * h, LINE_EN_INK * h, cx, Align.CENTER, maxW, white);
    }

    private static void station(final Graphics2D g, final Content c, final Assets assets,
                                final float w, final float h) {
        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final float cx = STA_CX * w;
        final int white = COLOR_WHITE;
        line(g, c.stationZh(), cjk, "董任", STA_ZH_TOP * h, STA_ZH_INK * h,
                cx, Align.CENTER, 0.11F * w, white);
        line(g, c.stationEn(), latin, "Dongren", STA_EN_TOP * h, STA_EN_INK * h,
                cx, Align.CENTER, 0.11F * w, white);
    }

    /**
     * 编组行：车头（行首独立占位）+ N 节卡片，节距均匀，整行**右缘**对齐 {@link #CAR_ROW_X1}。
     *
     * <p>宽度规则（设计稿 4 节量出来 0.0656 屏宽 / 节距 0.0727）：
     * <pre>
     *   naturalW = 0.469h（实拍卡片宽）；ch = naturalW × 1024/2144（卡片高）
     *   ch 上限 = 行高（CAR_Y1 − CAR_Y0）—— 卡片不许高过行
     *   avail    = 行槽宽 − 车头占地 − 一个间隙
     *   cardW    = min(naturalW, avail / (n + (n−1) × gapRatio))
     * </pre>
     * 节数多时<b>只缩卡片宽</b>：车头尺寸固定（按自然卡片高算），所以车头不会被压成一条线。
     */
    private static void cars(final Graphics2D g, final Content c, final Assets assets,
                             final float w, final float h) {
        final List<String> numbers = c.carNumbers();
        if (numbers == null || numbers.isEmpty() || assets == null) {
            return;
        }
        // 编组节数由渲染器按 MTR 里那列车的实际编组给（拿不到才退回 4 节）；
        // 上限 16 只是防呆（真出现 20 节的编组，卡片会窄到看不清，不如截断）。
        final int n = Math.min(16, numbers.size());

        final float rowBottom = CAR_Y0 * h;
        final float rowTop = CAR_Y1 * h;
        final float rowH = rowTop - rowBottom;
        final float right = CAR_ROW_X1 * w;
        final float left = CAR_ROW_X0 * w;

        final float naturalW = CAR_W * h;
        final float ratio = CARD_PX_H / CARD_PX_W;
        // 高度不许超过行高：卡片宽也跟着这条上限
        final float capW = Math.min(naturalW, rowH / ratio);

        // 车头：按**自然**卡片高算，不跟着缩
        final float naturalCh = naturalW * ratio;
        final float noseH = naturalCh * NOSE_H_RATIO;
        final float noseW = assets.nose == null ? 0.0F
                : noseH * (assets.nose.getWidth() / (float) assets.nose.getHeight());

        // 节距与卡片宽同比例（设计稿 0.0068 屏宽 ÷ 0.0656 卡片宽 = 0.104），所以卡片缩窄时间隙跟着缩 ——
        // 否则 13 节时「固定的间隙 + 缩窄的卡片」会把整行撑回左边的「分钟」列上去。
        //   行宽 = 车头 + 车头间隙 + n 张卡 + (n−1) 个间隙
        //        = noseW + cw·gapRatio + n·cw + (n−1)·cw·gapRatio
        //   ⇒ cw = (槽宽 − noseW) / (n + (2n−1)·gapRatio)
        final float avail = Math.max(capW, right - left - noseW);
        final float per = avail / (n + (2 * n - 1) * CAR_GAP_RATIO);
        final float cw = Math.min(capW, per);
        final float ch = cw * ratio;
        final float gap = cw * CAR_GAP_RATIO;

        // 竖直：整行在行槽里居中
        final float y0 = rowBottom + (rowH - ch) * 0.5F;
        // 水平：卡片右缘对齐 right，车头挂在首卡左边（车头自己那一格宽度固定，不跟着缩）
        final float cardsW = n * cw + (n - 1) * gap;
        final float x0 = right - cardsW;

        if (assets.nose != null && noseW > 0.0F) {
            // 车头（线路前进方向）：紧贴第一节车卡的左边，高度不缩
            drawScaled(g, assets.nose, x0 - gap - noseW, y0 + (ch - noseH) * 0.5F, noseW, noseH);
        }

        for (int i = 0; i < n; i++) {
            // 首尾车 = 单雪花素材，中间车 = 双雪花素材（素材自己画好座椅 / 雪花）
            final BufferedImage card = (i == 0 || i == n - 1) ? assets.carEnd : assets.carMiddle;
            final float cx = x0 + i * (cw + gap);
            if (card != null) {
                drawScaled(g, card, cx, y0, cw, ch);
            }
            // 车号：素材上只有「车 ❄」，编号由这里补在「车」的左边（字号跟着压缩后的卡片走）
            final String number = numbers.get(i);
            if (number != null && !number.isEmpty()) {
                final float inkH = ch * CAR_TEXT_INK_H;
                final float textRight = cx + cw * CAR_TEXT_LEFT - cw * 0.025F;
                final float inkCy = y0 + ch * CAR_TEXT_CY;
                line(g, number, assets.cjk(), "1", inkCy - inkH * 0.5F, inkH,
                        textRight, Align.RIGHT, 0.0F, COLOR_WHITE);
            }
        }
    }

    /** 编组行下面那行小字（设计稿：注：此处按线路实际运营车辆读取编组）。 */
    private static void note(final Graphics2D g, final Content c, final Assets assets,
                             final float w, final float h) {
        final String text = c.carNote();
        if (text == null || text.isBlank()) {
            return;
        }
        line(g, text, assets == null ? null : assets.cjk(), "注：编组按线路实际运营车辆", NOTE_TOP * h,
                NOTE_INK * h, NOTE_X * w, Align.LEFT, (CAR_ROW_X1 - NOTE_X) * w, COLOR_WHITE);
    }

    /** 标语条：胶囊框 + 一句字（超宽就截断，不画到框外）。 */
    private static void paintTicker(final Graphics2D g, final Content c, final Assets assets,
                                    final float w, final float h) {
        final String text = c.tickerText();
        if (text == null || text.isBlank()) {
            // 没有标语就不画框：空胶囊看着像没画完
            return;
        }
        final float tx0 = TICK_X0 * w;
        final float tx1 = TICK_X1 * w;
        final float ty0 = TICK_Y0 * h;
        final float ty1 = TICK_Y1 * h;
        final float radius = (ty1 - ty0) * 0.5F;
        g.setColor(argb(COLOR_TICK_STROKE));
        g.setStroke(new BasicStroke(Math.max(1.0F, TICK_SW * h), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(tx0 + TICK_SW * h * 0.5F, ty0 + TICK_SW * h * 0.5F,
                (tx1 - tx0) - TICK_SW * h, (ty1 - ty0) - TICK_SW * h, radius * 2.0F, radius * 2.0F));
        g.setStroke(new BasicStroke(1.0F));

        final Shape old = g.getClip();
        g.clip(new RoundRectangle2D.Float(tx0 + TICK_SW * h, ty0 + TICK_SW * h,
                (tx1 - tx0) - TICK_SW * h * 2.0F, (ty1 - ty0) - TICK_SW * h * 2.0F,
                radius * 2.0F, radius * 2.0F));
        try {
            final float inkH = (ty1 - ty0) * TICK_SIZE_RATIO;
            line(g, text, assets == null ? null : assets.cjk(), text,
                    (ty0 + ty1) * 0.5F - inkH * 0.5F, inkH,
                    (tx0 + tx1) * 0.5F, Align.CENTER, (tx1 - tx0) - 0.07F * h, COLOR_WHITE);
        } finally {
            g.setClip(old);
        }
    }

    /**
     * 等比缩放画一张素材：<b>逐级减半</b>再落到目标尺寸。
     *
     * <p>卡片素材是 2144×1024，屏幕上只有 150×70 左右 —— 直接一次 drawImage 缩 14 倍，
     * Java2D 的双线性只采 2×2 个源像素，细线（座椅 / 雪花）会抖成锯齿。逐级减半
     * 每级只缩 2 倍，等于做了多抽头滤波，缩到目标附近再画一次就干净了。
     */
    private static void drawScaled(final Graphics2D g, final BufferedImage src,
                                   final float x, final float y, final float w, final float h) {
        if (src == null || w < 1.0F || h < 1.0F) {
            return;
        }
        BufferedImage current = src;
        int cw = src.getWidth();
        int chh = src.getHeight();
        while (cw / 2 >= w * 2.0F && chh / 2 >= h * 2.0F) {
            final BufferedImage half = new BufferedImage(cw / 2, chh / 2, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D hg = half.createGraphics();
            try {
                hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                hg.drawImage(current, 0, 0, cw / 2, chh / 2, null);
            } finally {
                hg.dispose();
            }
            current = half;
            cw /= 2;
            chh /= 2;
        }
        g.drawImage(current, Math.round(x), Math.round(y), Math.round(w), Math.round(h), null);
    }

    // ================================================================ 文字

    /**
     * 画一行字，<b>按「参考字串的墨迹高」定字号、按「墨迹上沿」定位</b>。
     *
     * <p>为什么这样做：设计稿能量到的是「字形的墨迹包围盒」，不是字号；而不同字体、
     * 不同字符（有没有下伸部）在同一字号下的墨迹高并不一样。所以这里给的是
     * 「参考字串 + 它在设计稿里的墨迹高」，运行期反推字号 —— 换字体也不会跑版。
     *
     * @param text    真正要画的字（可为空）
     * @param base    字体（null = 逻辑字体）
     * @param ref     量字号用的参考字串（null = 用 text 自己）
     * @param inkTop  墨迹上沿（px）
     * @param inkH    墨迹高（px）
     * @param anchor  对齐锚点（px）
     * @param maxW    最宽多少（px；&lt;=0 = 不限）
     */
    private static void line(final Graphics2D g, final String text, final Font base, final String ref,
                             final float inkTop, final float inkH, final float anchor,
                             final Align align, final float maxW, final int argbColor) {
        if (text == null || text.isEmpty() || inkH <= 0.0F) {
            return;
        }
        Font font = fit(base, ref == null ? text : ref, inkH);
        float w = inkWidth(font, text);
        if (maxW > 0.0F && w > maxW && w > 0.01F) {
            font = font.deriveFont(font.getSize2D() * (maxW / w));
            w = inkWidth(font, text);
        }
        final float inkX0 = inkX0(font, text);
        final float inkY0 = inkY0(font, text);
        final float x = switch (align) {
            case CENTER -> anchor - inkX0 - w * 0.5F;
            case RIGHT -> anchor - inkX0 - w;
            default -> anchor - inkX0;
        };
        g.setFont(font);
        g.setColor(new Color(argbColor, true));
        g.drawString(text, x, inkTop - inkY0);
    }

    /** 反推字号：让 {@code ref} 在这个字号下的墨迹高正好等于 {@code inkH}。 */
    private static Font fit(final Font base, final String ref, final float inkH) {
        final Font font = base != null ? base : new Font(Font.SANS_SERIF, Font.PLAIN, 100);
        final Font probe = font.deriveFont(100.0F);
        final float ih = inkHeight(probe, ref);
        if (ih < 0.5F) {
            return probe;
        }
        return font.deriveFont(Math.max(1.0F, 100.0F * inkH / ih));
    }

    private static Shape outline(final Font font, final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        final GlyphVector gv = font.createGlyphVector(FRC, text);
        return gv.getOutline(0.0F, 0.0F);
    }

    private static float inkWidth(final Font font, final String text) {
        final Shape s = outline(font, text);
        return s == null ? 0.0F : (float) s.getBounds2D().getWidth();
    }

    private static float inkHeight(final Font font, final String text) {
        final Shape s = outline(font, text);
        return s == null ? 0.0F : (float) s.getBounds2D().getHeight();
    }

    private static float inkX0(final Font font, final String text) {
        final Shape s = outline(font, text);
        return s == null ? 0.0F : (float) s.getBounds2D().getX();
    }

    private static float inkY0(final Font font, final String text) {
        final Shape s = outline(font, text);
        return s == null ? 0.0F : (float) s.getBounds2D().getY();
    }

    // ================================================================ 工具

    private static Color argb(final int c) {
        return new Color(c, true);
    }
}
