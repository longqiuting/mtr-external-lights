// 厦门地铁「屏蔽门顶板 LCD 挂板」（PSD-LCD）的整屏绘制：纯 Java2D，一个 MC 类都不 import。
//
// 渲染器（PsdLcdRenderer）和离线预览（tools/psd-lcd/PsdLcdPreview.java）都调本类的
// paint()，所以「游戏里看到的」和「预览图」是同一份代码画的，不会各画各的。
//
// 坐标系：屏幕像素，原点左上，+x 向右、+y 向下。**所有常量都以 h（屏幕高）为单位**，
// 数值是从实拍（扫描全能王 2026-11-19 19.07.jpg，6.9:1）上量出来的：既能量到墨迹
// 包围盒，就照墨迹包围盒摆，不靠眼睛估。量法见 temp/probe/*.java。
//
// 素材：
//   · M 标志 = textures/gui/psd_lcd/xiamen_logo.png（600×600，白 M + 红 ∧，来自资源包）
//   · 编组行的车头 / 单雪花卡片 / 双雪花卡片 = train_nose.png / car_end.png / car_middle.png
//     （用户提供的高清原图，逐级减半后再缩到目标尺寸，不压缩、不重绘）
//   · 线路徽标是纯色圆角块 + 两行字（「X号线」/「Line X」）—— 和用户给的示意图一致，
//     **里面没有任何图标**（那张图里只有色块、两行字和一行「注：线路色」的设计批注）。
//     这里不再画花形图案：示意图上没有的东西，屏幕上也不该有。
//
// 字体：和厦门普通 PIDS / 线路条带**同一套**（XiamenStripPainter#font()：SimHei →
// 微软雅黑 → Noto Sans SC 的 BOLD；拉丁行同族 PLAIN），不再从资源包 mtr:xmlcd/fonts/*
// 读字体（见 PsdLcdTypeface）。
//
// 面板几何（高度带 / 厚度 / 前后位置）见 PsdLcdLayout：对齐 MTR 顶板面板的 7px 带 +
// 参考模组 mtr_psd_lcd 的 1.6px 插屏厚度。所以 h（屏幕高）对应的世界高度是 7/16 格。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 整屏排版 + 绘制。单位 h = 屏幕高（px）；{@code u(v)} 把 h 单位换算成像素。
 *
 * <pre>
 *   ┌──────────────────────────────────────────────────────┬────────────┐
 *   │┌───────┐  开往      华侨大学      7  分钟            │            │
 *   ││  M    │  Bound for Huaqiao Univ.    min            │   广告区   │
 *   ││       │  下一趟    华侨大学     17  分钟   [6号线]  │  (洋红/图) │
 *   ││date   │  Next train Huaqiao Univ.  min   董任     │            │
 *   ││星期X  │                                  1车 2车 3车 4车        │
 *   │└───────┘ ┌──────────────────────────────────────────┐           │
 *   │          │ 此处滚动标语（MC 文字层，见渲染器）      │           │
 *   └──────────────────────────────────────────────────────┴────────────┘
 * </pre>
 */
public final class PsdLcdPainter {

    private PsdLcdPainter() {
    }

    // ================================================================ 尺寸（单位 h）

    /** 广告区宽度（h）。实拍：内容区右边界在 4.973h，屏宽 6.910h。 */
    public static final float AD_W = 1.936F;
    /** 设计稿屏宽（h）—— 6.9:1 实拍。屏更宽时多出来的宽度全部给中间的空隙。 */
    public static final float DESIGN_W = 6.910F;

    /** 左卡片外框。 */
    private static final float CARD_X0 = 0.0155F;
    private static final float CARD_X1 = 0.6790F;
    private static final float CARD_Y0 = 0.0218F;
    private static final float CARD_Y1 = 0.9833F;
    private static final float CARD_R = 0.0350F;
    private static final float CARD_SW = 0.0080F;

    /** 卡片内文字的公共中线（实拍四条都是这个中线，比卡片几何中线略偏左）。 */
    private static final float CARD_TEXT_CX = 0.3229F;
    /** M 标志：中线 + 墨迹上沿 + 墨迹高（等比缩放，不拉伸）。 */
    private static final float LOGO_CX = 0.3356F;
    private static final float LOGO_TOP = 0.0462F;
    /**
     * 墨迹高。实拍那块 M 的墨迹是 0.475h × 0.312h（宽高比 1.52），而 station.png 的墨迹是
     * 470×376（1.25）—— 屏上的图被横向拉过。这里取<b>面积相等</b>的那个尺度（√(0.475·0.312
     * ÷ (470·376 / 376²)) ≈ 0.344h），标志既不变形、视觉分量也和实拍一致。
     */
    private static final float LOGO_H = 0.3440F;

    // 卡片四行：墨迹上沿 + 墨迹高
    private static final float DATE_TOP = 0.4223F;
    private static final float DATE_INK = 0.0540F;
    private static final float WDZH_TOP = 0.5584F;
    private static final float WDZH_INK = 0.0719F;
    private static final float WDEN_TOP = 0.6958F;
    private static final float WDEN_INK = 0.0655F;
    private static final float CLOCK_TOP = 0.8344F;
    private static final float CLOCK_INK = 0.0783F;

    // 两行到站：行中线 + 各行元素的相对偏移
    private static final float ROW1_CY = 0.3810F;
    private static final float ROW2_CY = 0.6785F;
    private static final float LBL_TOP = -0.0639F;
    private static final float LBL_INK = 0.0590F;
    private static final float LBLEN_TOP = 0.0298F;
    private static final float LBLEN_INK = 0.0295F;
    private static final float DEST_TOP = -0.1076F;
    private static final float DEST_INK = 0.1040F;
    private static final float DESTEN_TOP = 0.0465F;
    private static final float DESTEN_INK = 0.0616F;

    /** 标签列左缘 / 终点站列中线 / 数字列中线 / 单位列左缘。 */
    private static final float LABEL_X = 0.7638F;
    private static final float DEST_CX = 1.5330F;
    private static final float NUM_CX = 2.2570F;
    private static final float NUM_CY = -0.0242F;
    /** 大数字墨迹上沿（相对行中线）＝ 中线偏移 − 半个墨迹高。 */
    private static final float NUM_TOP = NUM_CY - 0.1771F * 0.5F;
    private static final float NUM_INK = 0.1771F;
    private static final float UNIT_X = 2.5237F;
    private static final float UNITZH_TOP = -0.0190F;
    private static final float UNITZH_INK = 0.0449F;
    private static final float UNITEN_TOP = 0.0619F;
    private static final float UNITEN_INK = 0.0295F;

    // 右侧一组（跟着内容区右边界走：实拍 4.973h）
    private static final float BADGE_DX = 1.9360F;   // 徽标左缘 = 内容区右边界 - 这个值
    private static final float BADGE_W = 0.6700F;
    private static final float BADGE_Y0 = 0.0580F;
    private static final float BADGE_H = 0.2630F;
    private static final float BADGE_R = 0.0350F;
    /**
     * 徽标里两行字的墨迹上沿 / 墨迹高（屏幕坐标，不是相对徽标 —— 不要再加 y0）。
     *
     * <p>数值是照用户那张示意图量的（5383×779）：色块 x 2363…2890、y 45…250；
     * 「X号线」墨迹 y 90…160（高 70），「Line X」y 180…215（高 35），两行都<b>水平居中</b>。
     * <b>没有图标</b>：原来那块三角梅是照实拍补的，示意图上不存在，已删除。</p>
     */
    private static final float LINE_ZH_TOP = 0.1155F;
    private static final float LINE_ZH_INK = 0.0900F;
    private static final float LINE_EN_TOP = 0.2311F;
    private static final float LINE_EN_INK = 0.0450F;

    private static final float STA_DX = 0.7750F;     // 站名中线 = 内容区右边界 - 这个值
    private static final float STA_ZH_TOP = 0.0693F;
    private static final float STA_ZH_INK = 0.1630F;
    private static final float STA_EN_TOP = 0.2580F;
    private static final float STA_EN_INK = 0.0732F;

    // 编组卡片（素材 car_end.png / car_middle.png，原图 2144×1024；车头 train_nose.png 74×180）
    private static final float CAR_W = 0.4690F;
    private static final float CAR_Y0 = 0.4480F;
    private static final float CARS_DX = 0.2070F;    // 卡片行右缘 = 内容区右边界 - 这个值
    /** 卡片之间 / 车头与首卡之间的间隙 ÷ 卡片宽（实拍量出来 0.0325h ÷ 0.469h）。 */
    private static final float CAR_GAP_RATIO = 0.0693F;
    /** 车头宽 ÷ 卡片宽（车头 1.035 卡片高、比例 74:180 → 0.478 × 0.411 × 1.035 = 0.203）。 */
    private static final float NOSE_RATIO = 0.2030F;
    /** 编组行最左能到哪（h，屏幕坐标）—— 再往左就压到「分钟 / min」那一列了。 */
    private static final float CARS_LEFT_LIMIT = 2.6600F;
    /** 卡片素材的原始像素尺寸（按比例缩放，绝不拉伸）。 */
    private static final float CARD_PX_W = 2144.0F;
    private static final float CARD_PX_H = 1024.0F;
    private static final float NOSE_H_RATIO = 1.035F;   // 车头高 / 卡片高
    /** 素材里「车」字的墨迹位置（占卡片宽 / 高的比例，见 temp/probe/Proj2.java 的量法）。 */
    private static final float CAR_TEXT_LEFT = 697.0F / CARD_PX_W;
    private static final float CAR_TEXT_INK_H = 205.0F / CARD_PX_H;
    private static final float CAR_TEXT_CY = 786.5F / CARD_PX_H;

    // 标语条（胶囊）—— 文字由渲染器用 MC 文字层画，这里只画框
    private static final float TICK_X0 = 0.6860F;
    private static final float TICK_DX1 = 0.0300F;
    private static final float TICK_Y0 = 0.8408F;
    private static final float TICK_Y1 = 0.9833F;
    private static final float TICK_SW = 0.0080F;
    /** 标语字高 ÷ 条高（渲染器的 MC 文字层和离线预览共用这一个数）。 */
    public static final float TICK_SIZE_RATIO = 0.50F;

    // ================================================================ 颜色

    public static final int COLOR_BG = 0xFF0206E0;
    public static final int COLOR_WHITE = 0xFFFFFFFF;
    public static final int COLOR_CARD_STROKE = 0xFFF4F6FF;
    public static final int COLOR_TICK_STROKE = 0xFFE6EAFF;
    public static final int COLOR_AD_BG = 0xFFD81BB0;
    public static final int COLOR_AD_TEXT = 0xFFFFFFFF;

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
     * @param carNumbers  车厢编号（1 2 3 4），长度 = 画几张卡片
     * @param tickerText  标语条文字（只在离线预览里由本类画；游戏里走 MC 文字层）
     */
    public record Content(String date, String weekdayZh, String weekdayEn, String clock,
                          String boundLabel, String boundSub, String dest1, String dest1En, String count1,
                          String nextLabel, String nextSub, String dest2, String dest2En, String count2,
                          String unitZh, String unitEn,
                          String lineZh, String lineEn, int lineColor,
                          String stationZh, String stationEn,
                          List<String> carNumbers,
                          String adPlaceholder, String tickerText, boolean hasAd) {
    }

    /** 素材 + 字体。字体为 null 时退回逻辑字体（预览工具/资源包缺字体时不会崩）。 */
    public static final class Assets {
        /** 厦门地铁 M 标志（textures/gui/psd_lcd/xiamen_logo.png，600×600）。 */
        public BufferedImage logo;
        /** 广告图（有广告时才画，铺满右侧那块）。 */
        public BufferedImage ad;
        /** 车头（train_nose.png，74×180，圆头朝左 = 线路前进方向）。 */
        public BufferedImage nose;
        /** 头/尾车卡片（car_end.png，381×182，单雪花）。 */
        public BufferedImage carEnd;
        /** 中间车卡片（car_middle.png，381×182，双雪花）。 */
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
            paintInto(g, c, assets, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 画到现成的画布上（预览工具拼多张图 / 渲染器复用一个 BufferedImage 时用）。 */
    public static void paintInto(final Graphics2D g, final Content c, final Assets assets,
                                 final float width, final float height) {
        if (g == null || c == null) {
            return;
        }
        // h = 排版高。面板比设计稿更窄（只有 1~2 段）时按 6.91:1 收窄，内容上下居中留边，
        // 而不是让左卡片和右侧一组叠在一起 —— 「滑到底也看不到」这种挤爆的排版比留边难看得多。
        final float h = layoutHeight(width, height);
        final float contentRight = width - AD_W * h;
        final float offY = (height - h) * 0.5F;

        // ---- 底 + 广告区（广告永远是满高的那一条，不参与布局收窄）
        g.setColor(argb(COLOR_BG));
        g.fill(new java.awt.geom.Rectangle2D.Float(0.0F, 0.0F, width, height));
        paintAd(g, c, assets, contentRight, width, height, h);

        final java.awt.geom.AffineTransform savedTransform = g.getTransform();
        if (offY > 0.01F) {
            g.translate(0.0D, offY);
        }
        try {
            // ---- 左卡片
            card(g, h);
            logo(g, assets, h);
            cardTexts(g, c, assets, h);

            // ---- 两行到站
            row(g, c, assets, h, ROW1_CY, c.boundLabel(), c.boundSub(), c.dest1(), c.dest1En(),
                    c.count1(), c.unitZh(), c.unitEn(), contentRight);
            row(g, c, assets, h, ROW2_CY, c.nextLabel(), c.nextSub(), c.dest2(), c.dest2En(),
                    c.count2(), c.unitZh(), c.unitEn(), contentRight);

            // ---- 右侧一组
            badge(g, c, assets, h, contentRight);
            station(g, c, assets, h, contentRight);
            cars(g, c, assets, h, contentRight);

            // ---- 标语条外框（文字由渲染器叠在上面）
            final float tx0 = TICK_X0 * h;
            final float tx1 = Math.max(tx0 + 0.4F * h, contentRight - TICK_DX1 * h);
            final float ty0 = TICK_Y0 * h;
            final float ty1 = TICK_Y1 * h;
            final float radius = (ty1 - ty0) * 0.5F;
            g.setColor(argb(COLOR_TICK_STROKE));
            g.setStroke(new BasicStroke(Math.max(1.0F, TICK_SW * h), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Float(tx0 + TICK_SW * h * 0.5F, ty0 + TICK_SW * h * 0.5F,
                    (tx1 - tx0) - TICK_SW * h, (ty1 - ty0) - TICK_SW * h, radius * 2.0F, radius * 2.0F));
            g.setStroke(new BasicStroke(1.0F));

            // 标语文字：只在离线预览里画（游戏里由渲染器用 MC 文字层滚动，见 PsdLcdRenderer）。
            if (c.tickerText() != null && !c.tickerText().isEmpty()) {
                final Shape old = g.getClip();
                g.clip(new RoundRectangle2D.Float(tx0 + TICK_SW * h, ty0 + TICK_SW * h,
                        (tx1 - tx0) - TICK_SW * h * 2.0F, (ty1 - ty0) - TICK_SW * h * 2.0F,
                        radius * 2.0F, radius * 2.0F));
                line(g, c.tickerText(), assets == null ? null : assets.cjk(), c.tickerText(),
                        (ty0 + ty1) * 0.5F - (ty1 - ty0) * TICK_SIZE_RATIO * 0.5F,
                        (ty1 - ty0) * TICK_SIZE_RATIO,
                        tx0 + 0.035F * h, Align.LEFT, 0.0F, COLOR_WHITE);
                g.setClip(old);
            }
        } finally {
            g.setTransform(savedTransform);
        }
    }

    /**
     * 排版高：正常情况下就是面板高（面板本身已经是 7/16 格 = 2.29:1 一段）；
     * 面板比设计稿 {@link #DESIGN_W}:1 还窄时（1~2 段），按设计比例把它当成「高一点」来排，
     * 内容上下居中 —— 左卡片 / 到站行 / 右侧一组不会互相压。
     */
    public static float layoutHeight(final float width, final float height) {
        return Math.min(height, Math.max(1.0F, width / DESIGN_W));
    }

    /** 标语条的矩形（渲染器算「这条滚完要多久」要用，和上面画框用的是同一组数）。 */
    public static float[] tickerBox(final float width, final float height) {
        final float h = layoutHeight(width, height);
        final float offY = (height - h) * 0.5F;
        final float contentRight = width - AD_W * h;
        return new float[]{TICK_X0 * h, offY + TICK_Y0 * h,
                Math.max(TICK_X0 * h + 0.4F * h, contentRight - TICK_DX1 * h),
                offY + TICK_Y1 * h};
    }

    /** 广告位的矩形（渲染器把广告图叠在这块上）。 */
    public static float[] adBox(final float width, final float height) {
        final float h = layoutHeight(width, height);
        final float x0 = width - AD_W * h;
        return new float[]{x0, 0.0F, width, height};
    }

    // ================================================================ 广告区

    private static void paintAd(final Graphics2D g, final Content c, final Assets assets,
                                final float contentRight, final float width, final float height,
                                final float h) {
        final float x0 = contentRight;
        final float adW = width - x0;
        if (assets != null && assets.ad != null) {
            // 有图就铺满（等比 cover，多出来的裁掉），文字留给渲染器的广告层。
            cover(g, assets.ad, x0, 0.0F, adW, height);
            return;
        }
        if (c.hasAd()) {
            // 有广告但图在渲染器的广告层（不进位图）：这里只铺底色，别画「此处放置广告」。
            g.setColor(argb(COLOR_BG));
            g.fill(new java.awt.geom.Rectangle2D.Float(x0, 0.0F, adW, height));
            return;
        }
        g.setColor(argb(COLOR_AD_BG));
        g.fill(new java.awt.geom.Rectangle2D.Float(x0, 0.0F, adW, height));
        final String text = c.adPlaceholder() == null ? "" : c.adPlaceholder();
        if (!text.isEmpty()) {
            line(g, text, assets == null ? null : assets.cjk(), text,
                    height * 0.5F - 0.034F * h, 0.068F * h,
                    x0 + adW * 0.5F, Align.CENTER, adW * 0.88F, COLOR_AD_TEXT);
        }
    }

    // ================================================================ 左卡片

    private static void card(final Graphics2D g, final float h) {
        final float x0 = CARD_X0 * h;
        final float y0 = CARD_Y0 * h;
        final float x1 = CARD_X1 * h;
        final float y1 = CARD_Y1 * h;
        final float sw = Math.max(1.0F, CARD_SW * h);
        g.setColor(argb(COLOR_CARD_STROKE));
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(x0 + sw * 0.5F, y0 + sw * 0.5F,
                (x1 - x0) - sw, (y1 - y0) - sw, CARD_R * h * 2.0F, CARD_R * h * 2.0F));
        g.setStroke(new BasicStroke(1.0F));
    }

    private static void logo(final Graphics2D g, final Assets assets, final float h) {
        if (assets == null || assets.logo == null) {
            return;
        }
        final BufferedImage image = assets.logo;
        // 等比：以墨迹高为准（station.png 的白色墨迹在 600×600 里是 470×376，四周有留白，
        // 所以这里按「墨迹」而不是「整张图」对齐 —— 卡片里看到的大小才是对的）。
        final float inkW = 470.0F;
        final float inkH = 376.0F;
        final float boxH = image.getHeight();
        final float scale = (LOGO_H * h) / inkH;         // 每张图像素 -> 屏幕像素
        final float drawW = image.getWidth() * scale;
        final float drawH = boxH * scale;
        // 墨迹在整张图里的左上角（65,112）：把它摆到 LOGO_CX ± inkW/2、LOGO_TOP
        final float inkLeftInImage = 65.0F * scale;
        final float inkTopInImage = 112.0F * scale;
        final float x = LOGO_CX * h - (inkW * scale) * 0.5F - inkLeftInImage;
        final float y = LOGO_TOP * h - inkTopInImage;
        drawScaled(g, image, x, y, drawW, drawH);
    }

    private static void cardTexts(final Graphics2D g, final Content c, final Assets assets, final float h) {
        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final float cx = CARD_TEXT_CX * h;
        final float maxW = (CARD_X1 - CARD_X0) * h - 0.05F * h;
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

    private static void row(final Graphics2D g, final Content c, final Assets assets, final float h,
                            final float cyH, final String label, final String labelSub,
                            final String dest, final String destEn, final String count,
                            final String unitZh, final String unitEn, final float contentRight) {
        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final Font digits = assets == null ? null : assets.digits();
        final float cy = cyH * h;
        final int white = COLOR_WHITE;

        final float labelX = LABEL_X * h;
        line(g, label, cjk, "开往", cy + LBL_TOP * h, LBL_INK * h,
                labelX, Align.LEFT, 0.55F * h, white);
        line(g, labelSub, latin, "Bound for", cy + LBLEN_TOP * h, LBLEN_INK * h,
                labelX, Align.LEFT, 0.55F * h, white);

        final float destCx = DEST_CX * h;
        line(g, dest, cjk, "华侨大学", cy + DEST_TOP * h, DEST_INK * h,
                destCx, Align.CENTER, 1.15F * h, white);
        line(g, destEn, latin, "Huaqiao University", cy + DESTEN_TOP * h, DESTEN_INK * h,
                destCx, Align.CENTER, 1.15F * h, white);

        // 大数字：等宽中线居中（7 与 17 在实拍里是同一个中线）
        line(g, count, digits, "0", cy + NUM_TOP * h, NUM_INK * h,
                NUM_CX * h, Align.CENTER, 0.95F * h, white);
        if (count != null && !count.isEmpty()) {
            final float unitX = UNIT_X * h;
            line(g, unitZh, cjk, "分钟", cy + UNITZH_TOP * h, UNITZH_INK * h,
                    unitX, Align.LEFT, 0.60F * h, white);
            line(g, unitEn, latin, "min", cy + UNITEN_TOP * h, UNITEN_INK * h,
                    unitX, Align.LEFT, 0.60F * h, white);
        }
    }

    // ================================================================ 右侧一组

    private static void badge(final Graphics2D g, final Content c, final Assets assets,
                              final float h, final float contentRight) {
        final float x0 = contentRight - BADGE_DX * h;
        final float y0 = BADGE_Y0 * h;
        final float x1 = x0 + BADGE_W * h;
        final float y1 = y0 + BADGE_H * h;
        g.setColor(new Color(c.lineColor(), true));
        g.fill(new RoundRectangle2D.Float(x0, y0, x1 - x0, y1 - y0, BADGE_R * h * 2.0F, BADGE_R * h * 2.0F));

        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final int white = COLOR_WHITE;
        // 只有色块 + 两行字，两行都居中（示意图就是这样；没有图标）。
        final float cx = (x0 + x1) * 0.5F;
        final float maxW = (BADGE_W - 0.05F) * h;
        line(g, c.lineZh(), cjk, "6号线", LINE_ZH_TOP * h, LINE_ZH_INK * h, cx, Align.CENTER, maxW, white);
        line(g, c.lineEn(), latin, "Line 6", LINE_EN_TOP * h, LINE_EN_INK * h, cx, Align.CENTER, maxW, white);
    }

    private static void station(final Graphics2D g, final Content c, final Assets assets,
                                final float h, final float contentRight) {
        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final float cx = contentRight - STA_DX * h;
        final int white = COLOR_WHITE;
        line(g, c.stationZh(), cjk, "董任", STA_ZH_TOP * h, STA_ZH_INK * h,
                cx, Align.CENTER, 0.95F * h, white);
        line(g, c.stationEn(), latin, "Dongren", STA_EN_TOP * h, STA_EN_INK * h,
                cx, Align.CENTER, 0.95F * h, white);
    }

    private static void cars(final Graphics2D g, final Content c, final Assets assets,
                             final float h, final float contentRight) {
        final List<String> numbers = c.carNumbers();
        if (numbers == null || numbers.isEmpty() || assets == null) {
            return;
        }
        // 编组节数由渲染器按 MTR 里那列车的实际编组给（拿不到才退回 4 节）；
        // 上限 16 只是防呆（真出现 20 节的编组，卡片会窄到看不清，不如截断）。
        final int n = Math.min(16, numbers.size());

        // ---- 宽度规则（长编组逐节压缩，绝不溢出、绝不盖广告位）
        //   naturalW = 0.469h（实拍卡片宽）
        //   availW   = 卡片行右缘 − CARS_LEFT_LIMIT（再往左就压到「分钟 / min」那一列）
        //   cardW    = min(naturalW, availW / (n × (1 + gapRatio) + noseRatio))
        //              └ gapRatio 0.0693（卡片间隙 ÷ 卡片宽）、noseRatio 0.203（车头宽 ÷ 卡片宽）
        //   节数少（4 节）时算出来 ≥ naturalW，取 naturalW → 与实拍一像素不差；
        //   6 / 8 / 13 节时按同一比例缩窄（等比缩，不裁切），高度和字号跟着一起缩。
        final float naturalW = CAR_W * h;
        final float naturalCh = naturalW * (CARD_PX_H / CARD_PX_W);
        final float right = contentRight - CARS_DX * h;
        final float avail = Math.max(naturalW, right - CARS_LEFT_LIMIT * h);
        final float per = avail / (n * (1.0F + CAR_GAP_RATIO) + NOSE_RATIO);
        final float w = Math.min(naturalW, per);
        final float ch = w * (CARD_PX_H / CARD_PX_W);
        final float gap = w * CAR_GAP_RATIO;
        final float rowW = n * w + (n - 1) * gap;
        final float x0 = right - rowW;
        // 垂直：整行按「正常宽度时那条带」居中，压缩后不会浮到上面
        final float y0 = CAR_Y0 * h + (naturalCh - ch) * 0.5F;

        // 车头（线路前进方向）：紧贴第一节车卡的左边
        final BufferedImage nose = assets.nose;
        if (nose != null) {
            final float nh = ch * NOSE_H_RATIO;
            final float nw = nh * (nose.getWidth() / (float) nose.getHeight());
            final float nx = x0 - gap - nw;
            drawScaled(g, nose, nx, y0 + (ch - nh) * 0.5F, nw, nh);
        }

        for (int i = 0; i < n; i++) {
            // 首尾车 = 单雪花素材，中间车 = 双雪花素材（素材自己画好座椅 / 雪花）
            final BufferedImage card = (i == 0 || i == n - 1) ? assets.carEnd : assets.carMiddle;
            final float cx = x0 + i * (w + gap);
            if (card != null) {
                drawScaled(g, card, cx, y0, w, ch);
            }
            // 车号：素材上只有「车 ❄」，编号由这里补在「车」的左边（字号跟着压缩后的卡片走）
            final String number = numbers.get(i);
            if (number != null && !number.isEmpty()) {
                final float inkH = ch * CAR_TEXT_INK_H;
                final float textRight = cx + w * CAR_TEXT_LEFT - w * 0.025F;
                final float inkCy = y0 + ch * CAR_TEXT_CY;
                line(g, number, assets.cjk(), "1", inkCy - inkH * 0.5F, inkH,
                        textRight, Align.RIGHT, 0.0F, COLOR_WHITE);
            }
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
     * 画一行字，<b>按「参考字串的墨迹高」定字号、按「墨迹上沿 / 中线」定位</b>。
     *
     * <p>为什么这样做：实拍能量到的是「字形的墨迹包围盒」，不是字号；而不同字体、
     * 不同字符（有没有下伸部）在同一字号下的墨迹高并不一样。所以这里给的是
     * 「参考字串 + 它在实拍里的墨迹高」，运行期反推字号 —— 换字体也不会跑版。
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

    /** 等比铺满（多出来的裁掉）。 */
    private static void cover(final Graphics2D g, final BufferedImage image,
                              final float x, final float y, final float w, final float h) {
        if (image == null || w <= 0.0F || h <= 0.0F) {
            return;
        }
        final float sa = image.getWidth() / (float) image.getHeight();
        final float ba = w / h;
        float dw = w;
        float dh = h;
        if (sa > ba) {
            dw = h * sa;
        } else {
            dh = w / sa;
        }
        final Shape old = g.getClip();
        g.clip(new java.awt.geom.Rectangle2D.Float(x, y, w, h));
        g.drawImage(image, Math.round(x + (w - dw) * 0.5F), Math.round(y + (h - dh) * 0.5F),
                Math.round(dw), Math.round(dh), null);
        g.setClip(old);
    }

    private static Color argb(final int c) {
        return new Color(c, true);
    }
}
