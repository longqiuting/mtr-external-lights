// 厦门 PIDS（地铁 / BRT）的整屏排版：**纯 Java2D**，不 import 任何 net.minecraft / mtr 类型。
//
// 为什么从「底图烘字」改成函数绘制：
//   · 老路子是把版式和文字一起烘进 pids_xiamen_metro.png / pids_xiamen_brt.png，换一句话就要重导 PNG，
//     中文字缺字（资源包字体没有那几个字）时整行直接看不见；
//   · 现在底图只当**面板背景**（用户新给的两张纯版式底板：只有色块 / 描边框），
//     文字 / 数字 / 广告 / 编组卡片全部由这里的函数画进一张 BufferedImage，
//     再由渲染器上传成动态贴图（和 PSD-LCD 吊板同一个路子，见 PsdLcdPainter）。
//
// 这个类刻意不 import MC：渲染器和离线预览（tools/pids-xiamen/XiamenPidsPreview.java）
// 跑的是**同一份**排版代码，所以「预览对得上」就等于「游戏里对得上」。
//
// 屏幕坐标系 = 底图像素：512×235，原点左上，+x 向右，+y 向下。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 厦门 PIDS 的整屏排版 + 绘制（地铁 / BRT 各一套版式）。
 *
 * <h2>一张位图里有什么</h2>
 * <ol>
 *   <li><b>底图</b>（用户给的纯版式底板，512×235）：色块 / 圆角 / 描边框；</li>
 *   <li><b>M 标志</b>：{@code textures/gui/psd_lcd/xiamen_logo.png}（600×600，与 PSD-LCD 同一张），
 *       按底图里那个红色 ⟨ 的包围盒对位（见 {@link #logoBox}）；</li>
 *   <li><b>日期 / 时间</b>；</li>
 *   <li><b>到站行</b>：地铁三行（开往 / 下一趟开往 / 第三趟开往），BRT 两行；</li>
 *   <li><b>编组条</b>（只有地铁底图有那条青色带）：首尾车单雪花、中间车双雪花，
 *       节数由渲染器按 MTR 实际编组给（见 {@link #consist}）；</li>
 *   <li><b>广告图</b>：铺满广告矩形（「可以拉伸」）。</li>
 * </ol>
 * 滚动标语**不在**这张位图里：它是一张单独的小贴图（{@link #paintTickerStrip}），
 * 每 tick 只重画条内那一小块，整屏位图完全不参与滚动 —— 否则整屏要 20 次/秒重画。
 *
 * <h2>字体</h2>
 * 中文 / 英文 / 大数字全部来自 {@code XiamenStripPainter#font()}（系统黑体，见
 * {@code PsdLcdTypeface}），<b>不读资源包字体</b>：这块屏和普通线路条带挂在同一个站台上，
 * 字体必须一致；系统黑体也不会缺字。
 *
 * <h2>量字号的方法</h2>
 * 版式量到的是「墨迹包围盒」，不是字号，所以 {@link #line} 吃的是「参考字串 + 它在版式里的
 * 墨迹高」，运行期反推字号（和 {@code PsdLcdPainter} 同一套做法）：换字体也不会跑版。
 */
public final class PidsXiamenPainter {

    private PidsXiamenPainter() {
    }

    /** 屏幕宽（= 底图宽，px）。 */
    public static final float SCREEN_W = 512.0F;
    /** 屏幕高（= 底图高，px）。 */
    public static final float SCREEN_H = 235.0F;

    // ================================================================ 版式常量（px）

    // ---- 地铁：左上「M 标 + 日期时间」卡片（底图白描边框 y 6..47 / x 7..169）
    /** 地铁 M 标志在底图里的墨迹框（白 M + 红 ⟨；用来给 logo 贴图对位）。 */
    private static final float[] METRO_LOGO_BOX = {13.0F, 10.0F, 60.0F, 48.0F};
    private static final float METRO_DATE_X = 64.0F;
    private static final float METRO_DATE_TOP = 10.0F;
    private static final float METRO_DATE_INK = 11.0F;
    private static final float METRO_TIME_X = 64.0F;
    private static final float METRO_TIME_TOP = 26.5F;
    private static final float METRO_TIME_INK = 15.5F;

    // ---- 地铁：三个到站框（x 7..169；y 51..107 / 111..166 / 172..227）
    private static final float[][] METRO_ROW_CARDS = {
            {7.0F, 51.0F, 169.0F, 107.0F},
            {7.0F, 111.0F, 169.0F, 166.0F},
            {7.0F, 172.0F, 169.0F, 227.0F}
    };
    /** 左列「开往 / Bound for」的左缘。 */
    private static final float METRO_LABEL_X = 16.0F;
    /** 右列「终点站 / 大数字」的左缘。 */
    private static final float METRO_DEST_X = 76.0F;

    // 行内每一行的墨迹上沿 / 墨迹高（相对框顶，参考图量出来的）
    private static final float ROW_LBL_TOP = 5.0F;
    private static final float ROW_LBL_INK = 14.0F;
    private static final float ROW_SUB_TOP = 21.5F;
    private static final float ROW_SUB_INK = 7.0F;
    private static final float ROW_NUM_TOP = 31.0F;
    private static final float ROW_NUM_INK = 20.0F;
    private static final float ROW_UNIT_TOP = 36.5F;
    private static final float ROW_UNIT_INK = 8.0F;
    private static final float ROW_UNITEN_TOP = 45.0F;
    private static final float ROW_UNITEN_INK = 6.5F;

    // ---- 地铁：广告区 / 标语条 / 编组带
    private static final float[] METRO_AD = {179.0F, 34.0F, 511.0F, 198.0F};
    private static final float[] METRO_TICKER = {177.0F, 199.0F, 505.0F, 227.0F};
    /** 标语字高 ÷ 条高（地铁取 0.52 = {@code PidsXiamenTicker#REF_FONT_PX} 那一档）。 */
    private static final float METRO_TICKER_RATIO = 0.52F;
    /** 顶部编组条的青色带（底图里 x 179..511 / y 0..32）。 */
    private static final float[] METRO_CONSIST_BAND = {179.0F, 0.0F, 511.0F, 32.0F};

    // ---- BRT：左上「M 标 + 日期时间」卡片（x 9..167 / y 9..70）
    private static final float[] BRT_LOGO_BOX = {22.0F, 26.0F, 58.0F, 54.0F};
    private static final float BRT_DATE_X = 66.0F;
    private static final float BRT_DATE_TOP = 21.0F;
    private static final float BRT_DATE_INK = 10.5F;
    private static final float BRT_TIME_X = 70.0F;
    private static final float BRT_TIME_TOP = 37.0F;
    private static final float BRT_TIME_INK = 14.5F;

    // ---- BRT：两个到站卡片（x 9..167；y 77..148 / 155..226）
    private static final float[][] BRT_ROW_CARDS = {
            {9.0F, 77.0F, 167.0F, 148.0F},
            {9.0F, 155.0F, 167.0F, 226.0F}
    };
    private static final float BRT_ROW_X = 22.0F;
    /** BRT 第一行「开往XXXX站」：墨迹上沿（相对框顶）/ 墨迹高。 */
    private static final float BRT_LINE1_TOP = 9.0F;
    private static final float BRT_LINE1_INK = 14.0F;
    /** BRT 第二行：大数字的墨迹底边（相对框顶）——其它段按它底对齐。 */
    private static final float BRT_LINE2_BOTTOM = 57.0F;
    private static final float BRT_NUM_INK = 21.0F;
    private static final float BRT_ROUTE_INK = 13.0F;
    private static final float BRT_ABOUT_INK = 9.0F;
    private static final float BRT_UNIT_INK = 9.0F;
    private static final float BRT_UNITEN_INK = 7.0F;

    private static final float[] BRT_AD = {179.0F, 10.0F, 504.0F, 176.0F};
    private static final float[] BRT_TICKER = {178.0F, 185.0F, 504.0F, 226.0F};
    private static final float BRT_TICKER_RATIO = 0.44F;

    /** 标语条左右内缩（字在这里面被位图边界裁掉）。 */
    public static final float TICKER_INSET = 5.0F;

    // ---- 编组条（只有地铁有）
    /** 编组带上下内缩（px）。 */
    private static final float CONSIST_PAD_Y = 2.0F;
    /** 卡片高度（px）—— <b>固定</b>，节数再多也不缩。 */
    private static final float CONSIST_CARD_H = 29.0F;
    /** 卡片素材原始像素（2144×1024，横版）。 */
    private static final float CARD_PX_W = 2144.0F;
    private static final float CARD_PX_H = 1024.0F;
    /** 卡片间隙 ÷ 卡片宽（压缩时同比例缩）。 */
    private static final float CONSIST_GAP_RATIO = 0.12F;
    /** 素材里「车」字的墨迹位置（占卡片宽 / 高的比例）——数字就补在它左边。 */
    private static final float CAR_TEXT_LEFT = 697.0F / CARD_PX_W;
    private static final float CAR_TEXT_INK_H = 205.0F / CARD_PX_H;
    private static final float CAR_TEXT_CY = 786.5F / CARD_PX_H;
    /** 防呆：真出现 20 节的编组，卡片会窄到看不清，不如截断。 */
    private static final int CONSIST_MAX = 16;

    // ================================================================ 颜色

    /** 地铁底图主色（底图丢了时的兜底底色）。 */
    private static final int COLOR_METRO_BG = 0xFF0000E6;
    /** BRT 底图主色。 */
    private static final int COLOR_BRT_BG = 0xFF020203;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    /** 「暂无数据」这种占位字的颜色（比正文暗一档，一眼看出是没数据而不是没画）。 */
    private static final int COLOR_PLACEHOLDER = 0xCCFFFFFF;

    /** 量字用的 FRC：关掉抗锯齿量墨迹，得到的是这个字号下字形的真实轮廓。 */
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    // ================================================================ 数据类型

    /** 这套屏用哪套版式。 */
    public enum Kind {
        METRO,
        BRT
    }

    /** 水平对齐。 */
    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    /**
     * 一行到站（字段都是已经解析好的字符串，排版不认识 MTR）。
     *
     * @param label     地铁左列大标签（开往 / 下一趟开往 / 第三趟开往）
     * @param labelSub  地铁左列小标签（Bound for / Next train / Third train）
     * @param dest      终点站（地铁右列大、BRT 第一行「开往XXX站」）
     * @param destSub   终点站英文（地铁右列小）
     * @param route     BRT 第二行线路号（地铁不用）
     * @param routeColor BRT 线路号颜色（ARGB；地铁不用）
     * @param about     BRT 的「约」（地铁不用；空 = 这一档没有数字也没有单位）
     * @param number    大数字 / 状态（即将进站、已到站）
     * @param unitZh    「分钟 / 秒」
     * @param unitEn    「min / s」
     */
    public record Row(String label, String labelSub, String dest, String destSub,
                      String route, int routeColor, String about,
                      String number, String unitZh, String unitEn) {

        /** 空行（只有标签，没有数据）。 */
        public static Row empty(final String label, final String labelSub) {
            return new Row(label, labelSub, "", "", "", COLOR_WHITE, "", "", "", "");
        }
    }

    /**
     * 整屏要显示的内容。
     *
     * @param date        地铁 {@code YYYY-MM-DD} / BRT {@code MM月DD日星期X}
     * @param clock       {@code HH:mm}
     * @param rows        到站行（地铁 3 / BRT 2）
     * @param carNumbers  编组节号（地铁用；空 = 不画编组条，BRT 永远不画）
     * @param placeholder 拿不到数据时写在终点站位置上的占位文字
     */
    public record Content(String date, String clock, List<Row> rows,
                          List<String> carNumbers, String placeholder) {
    }

    /** 素材 + 字体（{@code null} 时各段自己退回兜底，不会崩）。 */
    public static final class Assets {
        /** 地铁版式底板（512×235，只有色块框，没有文字）。 */
        public BufferedImage metroBase;
        /** BRT 版式底板。 */
        public BufferedImage brtBase;
        /** M 标志（psd_lcd/xiamen_logo.png，600×600，与 PSD-LCD 同一张）。 */
        public BufferedImage logo;
        /** 头 / 尾车卡片（单雪花）。 */
        public BufferedImage carEnd;
        /** 中间车卡片（双雪花）。 */
        public BufferedImage carMiddle;
        public Font cjk;
        public Font latin;
        public Font digits;

        public Assets() {
        }

        Font cjk() {
            return this.cjk != null ? this.cjk : new Font(Font.SANS_SERIF, Font.BOLD, 100);
        }

        Font latin() {
            return this.latin != null ? this.latin : new Font(Font.SANS_SERIF, Font.PLAIN, 100);
        }

        Font digits() {
            return this.digits != null ? this.digits : cjk();
        }

        BufferedImage base(final Kind kind) {
            return kind == Kind.BRT ? this.brtBase : this.metroBase;
        }
    }

    // ================================================================ 入口

    /** 画整屏（{@code ad} 可以为 null = 这一帧没有广告，广告区留底图）。 */
    public static BufferedImage paint(final Content c, final Assets assets, final BufferedImage ad,
                                      final Kind kind, final int width, final int height) {
        final BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            paintInto(g, c, assets, ad, kind, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 画到现成的画布上（离线预览拼图 / 渲染器复用画布时用）。 */
    public static void paintInto(final Graphics2D g, final Content c, final Assets assets,
                                 final BufferedImage ad, final Kind kind,
                                 final float width, final float height) {
        if (g == null) {
            return;
        }
        final float sx = width / SCREEN_W;
        final float sy = height / SCREEN_H;
        final java.awt.geom.AffineTransform saved = g.getTransform();
        // 整屏按 512×235 的版面坐标画，画布尺寸变了就整体缩放（游戏里永远是 512×235，比例 1）
        g.scale(sx, sy);
        try {
            paintLayout(g, c, assets, ad, kind);
        } finally {
            g.setTransform(saved);
        }
    }

    private static void paintLayout(final Graphics2D g, final Content c, final Assets assets,
                                     final BufferedImage ad, final Kind kind) {
        // 1) 底图（纯版式底板）。丢了就用主色铺底 + 后面照常画字，不黑屏。
        final BufferedImage base = assets == null ? null : assets.base(kind);
        if (base != null) {
            g.drawImage(base, 0, 0, Math.round(SCREEN_W), Math.round(SCREEN_H), null);
        } else {
            g.setColor(argb(kind == Kind.BRT ? COLOR_BRT_BG : COLOR_METRO_BG));
            g.fillRect(0, 0, Math.round(SCREEN_W), Math.round(SCREEN_H));
        }
        if (assets == null) {
            return;
        }

        // 2) M 标志（压在底图那个 M 上，见 logo）
        logo(g, assets.logo, base, kind);

        // 3) 日期 / 时间
        clock(g, c, assets, kind);

        // 4) 到站行
        if (kind == Kind.BRT) {
            brtRows(g, c, assets);
        } else {
            metroRows(g, c, assets);
        }

        // 5) 编组条（只有地铁底图有那条青色带；BRT 底图没有 → 不画，别硬加）
        if (kind == Kind.METRO) {
            consist(g, assets, c.carNumbers());
        }

        // 6) 广告（铺满版式矩形，四边贴齐）
        advertisement(g, ad, kind);
    }

    // ================================================================ M 标志

    /**
     * M 标志：先把底图那个 M 擦掉（用卡片自己的底色填），再把 600×600 的
     * {@code xiamen_logo.png} 等比塞进同一个框里。
     *
     * <p>为什么要擦：logo 贴图自己带白 M + 红 ⟨（四周透明），而底图也烘了一个 M。
     * 直接叠上去会「白 M 盖白 M」（描边看着被吃掉）+ 两圈红 ⟨ 错开露出毛边 ——
     * 尺寸怎么调都对不齐。先按卡片底色把框填掉，再画贴图，位置就完全由这里的常量决定。
     *
     * <p>底色是从底图**采样**的（{@link #sample}），所以换底板、换主题色都不用改代码。
     */
    private static void logo(final Graphics2D g, final BufferedImage logo, final BufferedImage base,
                             final Kind kind) {
        if (logo == null) {
            return;
        }
        final float[] box = kind == Kind.BRT ? BRT_LOGO_BOX : METRO_LOGO_BOX;
        final float targetW = box[2] - box[0];
        final float targetH = box[3] - box[1];
        if (targetW <= 0.0F || targetH <= 0.0F) {
            return;
        }
        // 擦掉底图里那个 M（拿不到底图就不擦，直接叠 —— 最多是两圈红 ⟨ 略错开）
        if (base != null) {
            g.setColor(sample(base, kind));
            g.fillRect(Math.round(box[0]), Math.round(box[1]),
                    Math.round(targetW), Math.round(targetH));
        }
        // 贴图里墨迹（白 M + 红 ⟨）的包围盒（量出来的）
        final float srcX = 65.0F;
        final float srcY = 112.0F;
        final float srcW = 470.0F;
        final float srcH = 376.0F;
        // 留 2% 余量：贴图的抗锯齿边缘不至于压到卡片描边
        final float scale = Math.min(targetW / srcW, targetH / srcH) * 0.98F;
        final float drawW = logo.getWidth() * scale;
        final float drawH = logo.getHeight() * scale;
        final float inkCx = srcX + srcW * 0.5F;
        final float inkCy = srcY + srcH * 0.5F;
        final float x = (box[0] + box[2]) * 0.5F - inkCx * scale;
        final float y = (box[1] + box[3]) * 0.5F - inkCy * scale;
        g.drawImage(logo, Math.round(x), Math.round(y), Math.round(drawW), Math.round(drawH), null);
    }

    /** 卡片底色采样点（都落在卡片里、避开 M 和坐标字）。 */
    private static Color sample(final BufferedImage base, final Kind kind) {
        final int x = 150;
        final int y = kind == Kind.BRT ? 14 : 14;
        if (x < base.getWidth() && y < base.getHeight()) {
            final int argb = base.getRGB(x, y);
            if ((argb >>> 24) > 0) {
                return new Color(argb, true);
            }
        }
        return new Color(kind == Kind.BRT ? COLOR_BRT_BG : COLOR_METRO_BG, true);
    }

    // ================================================================ 日期 / 时间

    private static void clock(final Graphics2D g, final Content c, final Assets assets,
                              final Kind kind) {
        if (kind == Kind.BRT) {
            line(g, c.date(), assets.cjk(), "11月20日", BRT_DATE_TOP, BRT_DATE_INK,
                    BRT_DATE_X, Align.LEFT, 167.0F - BRT_DATE_X, COLOR_WHITE);
            line(g, c.clock(), assets.digits(), "00:00", BRT_TIME_TOP, BRT_TIME_INK,
                    BRT_TIME_X, Align.LEFT, 167.0F - BRT_TIME_X, COLOR_WHITE);
        } else {
            line(g, c.date(), assets.cjk(), "0000-00-00", METRO_DATE_TOP, METRO_DATE_INK,
                    METRO_DATE_X, Align.LEFT, 169.0F - METRO_DATE_X, COLOR_WHITE);
            line(g, c.clock(), assets.digits(), "00:00", METRO_TIME_TOP, METRO_TIME_INK,
                    METRO_TIME_X, Align.LEFT, 169.0F - METRO_TIME_X, COLOR_WHITE);
        }
    }

    // ================================================================ 到站行

    /** 地铁：左列「开往 / Bound for」，右列「终点站 / Destination / 大数字 + 分钟」。 */
    private static void metroRows(final Graphics2D g, final Content c, final Assets assets) {
        for (int i = 0; i < METRO_ROW_CARDS.length; i++) {
            final float[] card = METRO_ROW_CARDS[i];
            final float cardW = card[2] - card[0];
            final Row row = rowAt(c, i);
            final float y0 = card[1];

            // 左列：标签 + 英文小字（没有数据也有，标签是版式的一部分）
            final String label = row == null ? "" : row.label();
            final String labelSub = row == null ? "" : row.labelSub();
            line(g, label, assets.cjk(), "第三趟开往", y0 + ROW_LBL_TOP, ROW_LBL_INK,
                    METRO_LABEL_X, Align.LEFT, METRO_DEST_X - METRO_LABEL_X - 7.0F, COLOR_WHITE);
            line(g, labelSub, assets.latin(), "Third train", y0 + ROW_SUB_TOP, ROW_SUB_INK,
                    METRO_LABEL_X, Align.LEFT, METRO_DEST_X - METRO_LABEL_X - 7.0F, COLOR_WHITE);

            // 右列：终点站 + 英文 + 大数字（+ 分钟 / min）
            final String dest = row == null || row.dest().isEmpty() ? "" : row.dest();
            final String destSub = row == null ? "" : row.destSub();
            if (dest.isEmpty()) {
                // 拿不到数据：写占位字，不留一块空白（用户要的「别像黑屏」）
                line(g, c.placeholder(), assets.cjk(), "暂无列车信息", y0 + ROW_LBL_TOP, ROW_LBL_INK,
                        METRO_DEST_X, Align.LEFT, card[2] - METRO_DEST_X - 5.0F,
                        COLOR_PLACEHOLDER);
                continue;
            }
            line(g, dest, assets.cjk(), "终点站", y0 + ROW_LBL_TOP, ROW_LBL_INK,
                    METRO_DEST_X, Align.LEFT, card[2] - METRO_DEST_X - 5.0F, COLOR_WHITE);
            line(g, destSub, assets.latin(), "Destination", y0 + ROW_SUB_TOP, ROW_SUB_INK,
                    METRO_DEST_X, Align.LEFT, card[2] - METRO_DEST_X - 5.0F, COLOR_WHITE);

            final String number = row.number() == null ? "" : row.number();
            final String unitZh = row.unitZh() == null ? "" : row.unitZh();
            final String unitEn = row.unitEn() == null ? "" : row.unitEn();
            final boolean state = unitZh.isEmpty();
            // 「即将进站 / 已到站」这一档没有数字：用正文那一档字号（20px 的四个汉字放不下）
            final float numInk = state ? 14.0F : ROW_NUM_INK;
            final float numTop = state ? y0 + ROW_UNIT_TOP : y0 + ROW_NUM_TOP;
            final Font numFont = fitted(assets.digits(), "8", numInk, number, cardW * 0.5F);
            line(g, number, numFont, "8", numTop, numInk, METRO_DEST_X, Align.LEFT, 0.0F, COLOR_WHITE);
            if (!state) {
                // 单位跟在数字右边同一行（「X 分钟」），英文单位再往下一行（「min」）
                final float unitX = METRO_DEST_X + inkWidth(numFont, number) + 4.0F;
                line(g, unitZh, assets.cjk(), "分钟", y0 + ROW_UNIT_TOP, ROW_UNIT_INK,
                        unitX, Align.LEFT, card[2] - unitX - 2.0F, COLOR_WHITE);
                line(g, unitEn, assets.latin(), "min", y0 + ROW_UNITEN_TOP, ROW_UNITEN_INK,
                        unitX, Align.LEFT, card[2] - unitX - 2.0F, COLOR_WHITE);
            }
        }
    }

    /** BRT：第一行「开往XXXX站」，第二行「线路号（线路色）约 X 分钟」。 */
    private static void brtRows(final Graphics2D g, final Content c, final Assets assets) {
        for (int i = 0; i < BRT_ROW_CARDS.length; i++) {
            final float[] card = BRT_ROW_CARDS[i];
            final Row row = rowAt(c, i);
            final float y0 = card[1];
            final String dest = row == null || row.dest().isEmpty() ? "" : row.dest();
            if (dest.isEmpty()) {
                line(g, c.placeholder(), assets.cjk(), "暂无列车信息", y0 + BRT_LINE1_TOP,
                        BRT_LINE1_INK, BRT_ROW_X, Align.LEFT, card[2] - BRT_ROW_X - 5.0F,
                        COLOR_PLACEHOLDER);
                continue;
            }
            line(g, dest, assets.cjk(), "开往XXXX站", y0 + BRT_LINE1_TOP, BRT_LINE1_INK,
                    BRT_ROW_X, Align.LEFT, card[2] - BRT_ROW_X - 5.0F, COLOR_WHITE);

            final float bottom = y0 + BRT_LINE2_BOTTOM;
            final float maxRight = card[2] - 4.0F;
            final String route = row.route() == null ? "" : row.route();
            final String number = row.number() == null ? "" : row.number();
            final String unitZh = row.unitZh() == null ? "" : row.unitZh();
            final String unitEn = row.unitEn() == null ? "" : row.unitEn();
            float x = BRT_ROW_X;

            // 线路号（线路色）
            if (!route.isEmpty()) {
                line(g, route, assets.cjk(), "1号线", bottom - BRT_ROUTE_INK, BRT_ROUTE_INK,
                        x, Align.LEFT, 0.0F, row.routeColor());
                x += inkWidth(fitted(assets.cjk(), "1号线", BRT_ROUTE_INK, route, 0.0F), route) + 3.0F;
            }
            final boolean state = unitZh.isEmpty();
            if (state) {
                // 「即将进站 / 已到站」：没有数字也没有单位，「约」也一起省掉
                line(g, number, assets.cjk(), "即将进站", bottom - 15.0F, 15.0F,
                        x, Align.LEFT, maxRight - x, COLOR_WHITE);
                continue;
            }
            // 「约」
            final String about = row.about() == null ? "" : row.about();
            if (!about.isEmpty()) {
                line(g, about, assets.cjk(), "约", bottom - BRT_ABOUT_INK, BRT_ABOUT_INK,
                        x, Align.LEFT, 0.0F, COLOR_WHITE);
                x += inkWidth(fitted(assets.cjk(), "约", BRT_ABOUT_INK, about, 0.0F), about) + 3.0F;
            }
            // 大数字
            final Font numFont = fitted(assets.digits(), "8", BRT_NUM_INK, number, 0.0F);
            line(g, number, numFont, "8", bottom - BRT_NUM_INK, BRT_NUM_INK,
                    x, Align.LEFT, 0.0F, COLOR_WHITE);
            x += inkWidth(numFont, number) + 4.0F;
            // 「分钟」/「min」
            line(g, unitZh, assets.cjk(), "分钟", bottom - BRT_UNIT_INK, BRT_UNIT_INK,
                    x, Align.LEFT, 0.0F, COLOR_WHITE);
            line(g, unitEn, assets.latin(), "min", bottom + 1.0F, BRT_UNITEN_INK,
                    x, Align.LEFT, 0.0F, COLOR_WHITE);
        }
    }

    private static Row rowAt(final Content c, final int index) {
        if (c == null || c.rows() == null || index >= c.rows().size()) {
            return null;
        }
        return c.rows().get(index);
    }

    // ================================================================ 编组条

    /**
     * 顶部编组条：首 / 尾车用单雪花卡片、中间车用双雪花卡片，节数 = 渲染器读到的实际编组。
     *
     * <h2>压缩公式（用户定的：只压宽 + 间距，不整体等比缩小）</h2>
     * <pre>
     *   ch  = {@value #CONSIST_CARD_H}px                       ← 固定，与节数 n 无关
     *   cw₀ = ch × (2144 / 1024)                               ← 「高度不缩」对应的自然宽
     *   gap = cw × {@value #CONSIST_GAP_RATIO}                 ← 间隙跟卡片同比例缩
     *   cw  = min(cw₀, 槽宽 / (n × (1 + gapRatio)))            ← 整行正好等于槽宽
     *   总宽 = n·cw + (n−1)·gap
     * </pre>
     * 于是 6 节时卡片被横向压扁一点（高度、编号字号都不动），2 节时用自然宽、在带里居中 ——
     * <b>任何节数都不会越界压到别的列</b>（槽宽就是青色带减内缩）。
     */
    private static void consist(final Graphics2D g, final Assets assets,
                                final List<String> carNumbers) {
        if (carNumbers == null || carNumbers.isEmpty() || assets == null) {
            return;
        }
        final int n = Math.min(CONSIST_MAX, carNumbers.size());
        final float[] band = METRO_CONSIST_BAND;
        final float bandW = band[2] - band[0];
        final float ch = CONSIST_CARD_H;
        final float naturalW = ch * (CARD_PX_W / CARD_PX_H);
        final float avail = Math.max(1.0F, bandW - 4.0F);
        final float cw = Math.min(naturalW, avail / (n * (1.0F + CONSIST_GAP_RATIO)));
        final float gap = cw * CONSIST_GAP_RATIO;
        final float total = n * cw + (n - 1) * gap;
        final float x0 = band[0] + (bandW - total) * 0.5F;
        final float y0 = band[1] + (band[3] - band[1] - ch) * 0.5F + 0.5F;

        for (int i = 0; i < n; i++) {
            final BufferedImage card = (i == 0 || i == n - 1) ? assets.carEnd : assets.carMiddle;
            final float cx = x0 + i * (cw + gap);
            if (card != null) {
                // 宽度可压、高度不变 ⇒ 卡片被横向压扁（用户允许），不会缩小整体
                drawScaled(g, card, cx, y0, cw, ch);
            }
            final String number = carNumbers.get(i);
            if (number == null || number.isEmpty()) {
                continue;
            }
            // 车号补在素材里「车」字的左边：字号按**固定**片高算，节数再多编号也一样大
            final float inkH = ch * CAR_TEXT_INK_H;
            final float textRight = Math.max(cx + cw * 0.02F,
                    cx + cw * CAR_TEXT_LEFT - cw * 0.025F);
            final float inkCy = y0 + ch * CAR_TEXT_CY;
            line(g, number, assets.cjk(), "1", inkCy - inkH * 0.5F, inkH,
                    textRight, Align.RIGHT, 0.0F, COLOR_WHITE);
        }
    }

    // ================================================================ 广告

    /** 广告图铺满版式矩形（{@code null} = 这一帧没有广告，留底图）。 */
    private static void advertisement(final Graphics2D g, final BufferedImage ad, final Kind kind) {
        if (ad == null) {
            return;
        }
        final float[] box = kind == Kind.BRT ? BRT_AD : METRO_AD;
        drawScaled(g, ad, box[0], box[1], box[2] - box[0], box[3] - box[1]);
    }

    /** 广告区矩形（渲染器诊断日志 / 离线预览用）。 */
    public static float[] adRect(final Kind kind) {
        return (kind == Kind.BRT ? BRT_AD : METRO_AD).clone();
    }

    /** 标语条外框矩形（{@code x0,y0,x1,y1}）。 */
    public static float[] tickerRect(final Kind kind) {
        return (kind == Kind.BRT ? BRT_TICKER : METRO_TICKER).clone();
    }

    /** 标语条「能放字」的矩形：扣掉左右内缩（{@code x0,y0,x1,y1}）。 */
    public static float[] tickerInner(final Kind kind) {
        final float[] bar = tickerRect(kind);
        return new float[]{bar[0] + TICKER_INSET, bar[1] + TICKER_INSET,
                bar[2] - TICKER_INSET, bar[3] - TICKER_INSET};
    }

    /** 标语字高（px）= 条高 × 比例。 */
    public static float tickerFontPx(final Kind kind) {
        final float[] bar = tickerRect(kind);
        final float ratio = kind == Kind.BRT ? BRT_TICKER_RATIO : METRO_TICKER_RATIO;
        return Math.max(4.0F, (bar[3] - bar[1]) * ratio);
    }

    /** 编组带矩形（离线预览 / 版式报告用）。 */
    public static float[] consistBand() {
        return METRO_CONSIST_BAND.clone();
    }

    // ================================================================ 滚动标语

    /** 一条标语（颜色 + 文字）。 */
    public record TickerMessage(String text, int color) {
    }

    /**
     * 一条标语滚完一轮的时间表（单位：tick）。
     *
     * @param width  每条的自然宽度（px）
     * @param start  这一条<b>开始</b>滚的时刻（tick，相对循环起点）
     * @param scroll 从右边缘滑到「整条出左边缘」要多少 tick
     * @param gap    滚完之后到吓一条进来之前的空档（tick）
     * @param cycle  整个循环（Σ scroll + Σ gap）
     * @param span   从开始滚到<b>最后一条整条滚出左边缘</b>（最后那段空档不算）—— 广告换图的时刻
     * @param messages 标语原文（{@link #paintTickerStrip} 要拿它画字 / 取颜色）
     * @param font     标语字体（{@code Assets#cjk()}，量宽和画字必须同一支）
     */
    public record TickerPlan(float[] width, float[] start, float[] scroll, float[] gap,
                             double cycle, double span, float fontPx, float innerWidth,
                             List<TickerMessage> messages, Font font) {
    }

    /**
     * 排一条标语的滚动时间表。
     *
     * <p>速度走 {@code PidsXiamenTicker#pxPerTick(fontPx)}（渲染器传进来，全模组只有那一份换算），
     * 所以字大的屏滚得也快，观感和 PSD-LCD 吊板一致。
     *
     * @param messages    标语表
     * @param font        标语字体（{@code Assets#cjk()}）
     * @param fontPx      标语字高（px）
     * @param innerWidth  标语条内宽（px）
     * @param pxPerTick   每 tick 滚多少像素
     * @param gapPx       两条之间的空隙（px）
     */
    public static TickerPlan tickerPlan(final List<TickerMessage> messages, final Font font,
                                        final float fontPx, final float innerWidth,
                                        final float pxPerTick, final float gapPx) {
        if (messages == null || messages.isEmpty() || innerWidth <= 4.0F || pxPerTick <= 0.0F) {
            return null;
        }
        final Font base = font != null ? font : new Font(Font.SANS_SERIF, Font.BOLD, 100);
        final int count = messages.size();
        final float[] width = new float[count];
        final float[] start = new float[count];
        final float[] scroll = new float[count];
        final float[] gap = new float[count];
        double t = 0.0D;
        double span = 0.0D;
        for (int i = 0; i < count; i++) {
            final TickerMessage message = messages.get(i);
            width[i] = Math.max(1.0F, inkWidth(fitted(base, message.text(), fontPx, message.text(), 0.0F),
                    message.text()));
            start[i] = (float) t;
            // 从右边缘滑到「整条出左边缘」= 条宽 + 字宽
            scroll[i] = (innerWidth + width[i]) / pxPerTick;
            gap[i] = Math.max(0.0F, gapPx) / pxPerTick;
            t += scroll[i] + gap[i];
            span += scroll[i] + (i < count - 1 ? gap[i] : 0.0D);
        }
        return new TickerPlan(width, start, scroll, gap, t, span, fontPx, innerWidth, messages, base);
    }

    /** 这条标语表滚完一轮要多少 tick（= 广告换下一张的时刻）。 */
    public static int tickerSpanTicks(final TickerPlan plan) {
        if (plan == null || plan.span() <= 0.0D) {
            return 0;
        }
        return Math.max(1, (int) Math.round(plan.span()));
    }

    /**
     * 画标语条里那一帧（<b>透明底</b>小贴图，只有字）。
     *
     * <p>文本 x = 条内右缘 − 进度 ×（条宽 + 字宽）：进度 0 时整句在右边缘外，进度 1 时整句
     * 已经出了左边缘。超出位图的部分自然被裁掉（就是「在边缘上被切开」的效果）。
     */
    public static BufferedImage paintTickerStrip(final TickerPlan plan, final long tick,
                                                 final int width, final int height) {
        final BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB);
        if (plan == null) {
            return image;
        }
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            final int index = messageIndexAt(plan, tick);
            if (index < 0) {
                return image;
            }
            final TickerMessage message = plan.messages().get(index);
            final float inkH = plan.fontPx();
            final float inkTop = (height - inkH) * 0.5F;
            // 位图可能比「条内宽」宽一点（凑整），x 在这里居中补偿
            final float x = messageX(plan, tick, index)
                    + Math.max(0.0F, (width - plan.innerWidth()) * 0.5F);
            line(g, message.text(), plan.font(), message.text(), inkTop, inkH, x, Align.LEFT, 0.0F,
                    message.color());
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 当前这一帧轮到第几条（{@code -1} = 空档 / 没标语）。 */
    private static int messageIndexAt(final TickerPlan plan, final long tick) {
        if (plan == null || plan.cycle() <= 0.0D) {
            return -1;
        }
        double t = tick % plan.cycle();
        if (t < 0.0D) {
            t += plan.cycle();
        }
        for (int i = 0; i < plan.width().length; i++) {
            if (t < plan.scroll()[i]) {
                return i;
            }
            t -= plan.scroll()[i];
            if (t < plan.gap()[i]) {
                return -1;
            }
            t -= plan.gap()[i];
        }
        return -1;
    }

    /** 这一条现在滚到哪（相对条内左缘的 x，px）。 */
    private static float messageX(final TickerPlan plan, final long tick, final int index) {
        double t = tick % plan.cycle();
        if (t < 0.0D) {
            t += plan.cycle();
        }
        t -= plan.start()[index];
        final float progress = (float) Math.max(0.0D, Math.min(1.0D, t / plan.scroll()[index]));
        return plan.innerWidth() - progress * (plan.innerWidth() + plan.width()[index]);
    }

    /**
     * 「这一帧的滚动相位」的整数指纹：只在<b>滚过一个像素</b>（或换到另一句）时变。
     *
     * <p>渲染器拿它跟上一帧比 —— 一样就不重画、不重传那张小贴图
     * （一 tick 走 2~4px，所以实际上最多每 tick 重画一次，空档期一次都不用）。
     *
     * @return {@code -1} = 这一帧是空档（不画字）
     */
    public static int tickerPhaseKey(final TickerPlan plan, final long tick) {
        final int index = messageIndexAt(plan, tick);
        if (index < 0) {
            return -1;
        }
        return index * 100_000 + Math.round(messageX(plan, tick, index));
    }

    // ================================================================ 文字（量墨迹）

    /**
     * 画一行字：按「参考字串的墨迹高」定字号、按「墨迹上沿」定位。
     *
     * @param text    要画的字（空则不画）
     * @param base    字体（null = 逻辑字体）
     * @param ref     量字号用的参考字串（null / 空 = 用 {@code text} 自己）
     * @param inkTop  墨迹上沿（px）
     * @param inkH    墨迹高（px）
     * @param anchor  对齐锚点（px）
     * @param maxW    最宽多少（px；{@code <= 0} = 不限）
     */
    static void line(final Graphics2D g, final String text, final Font base, final String ref,
                     final float inkTop, final float inkH, final float anchor,
                     final Align align, final float maxW, final int argbColor) {
        if (g == null || text == null || text.isEmpty() || inkH <= 0.0F) {
            return;
        }
        final Font font = fitted(base, ref == null || ref.isEmpty() ? text : ref, inkH, text, maxW);
        final float w = inkWidth(font, text);
        final float inkX0 = inkX0(font, text);
        final float inkY0 = inkY0(font, text);
        final float x = switch (align) {
            case CENTER -> anchor - inkX0 - w * 0.5F;
            case RIGHT -> anchor - inkX0 - w;
            default -> anchor - inkX0;
        };
        g.setFont(font);
        g.setColor(argb(argbColor));
        g.drawString(text, x, inkTop - inkY0);
    }

    /** 同上，但 {@code ref} 就是 {@code text} 自己（最常见的用法）。 */
    static void line(final Graphics2D g, final String text, final Font base,
                     final float inkTop, final float inkH, final float anchor,
                     final Align align, final float maxW, final int argbColor) {
        line(g, text, base, null, inkTop, inkH, anchor, align, maxW, argbColor);
    }

    /** 反推字号：{@code ref} 在这个字号下的墨迹高正好等于 {@code inkH}（超宽再按 {@code maxW} 缩）。 */
    static Font fitted(final Font base, final String ref, final float inkH,
                       final String text, final float maxW) {
        final Font font = base != null ? base : new Font(Font.SANS_SERIF, Font.PLAIN, 100);
        final Font probe = font.deriveFont(100.0F);
        final float ih = inkHeight(probe, ref == null || ref.isEmpty() ? text : ref);
        if (ih < 0.5F) {
            return probe;
        }
        Font out = font.deriveFont(Math.max(1.0F, 100.0F * inkH / ih));
        if (maxW > 0.0F && text != null && !text.isEmpty()) {
            final float w = inkWidth(out, text);
            if (w > maxW && w > 0.01F) {
                out = out.deriveFont(out.getSize2D() * (maxW / w));
            }
        }
        return out;
    }

    /** 墨迹宽（px）。 */
    static float inkWidth(final Font font, final String text) {
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

    private static Shape outline(final Font font, final String text) {
        if (font == null || text == null || text.isEmpty()) {
            return null;
        }
        final GlyphVector gv = font.createGlyphVector(FRC, text);
        return gv.getOutline(0.0F, 0.0F);
    }

    // ================================================================ 缩放

    /**
     * 等比缩放画一张素材：**逐级减半**再落到目标尺寸。
     *
     * <p>卡片素材是 2144×1024，屏上只有几十像素宽 —— 一次 drawImage 缩 20 倍，Java2D 的双线性
     * 只采 2×2 个源像素，细线（座椅 / 雪花）会抖成锯齿。逐级减半每级只缩 2 倍，等于做了多抽头
     * 滤波（和 {@code PsdLcdPainter#drawScaled} 同一份做法）。
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
            final BufferedImage half = new BufferedImage(Math.max(1, cw / 2), Math.max(1, chh / 2),
                    BufferedImage.TYPE_INT_ARGB);
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

    private static Color argb(final int c) {
        return new Color(c, true);
    }
}
