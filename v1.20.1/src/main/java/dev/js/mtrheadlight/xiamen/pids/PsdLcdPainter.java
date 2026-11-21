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

    /** 设计稿屏宽（h）—— 5383×779 = 6.910:1。只写来给离线断言对账用，不再参与排版高度（见 {@link #layoutHeight}）。 */
    public static final float DESIGN_W = 6.910F;

    /**
     * 内容盒**最扁的宽高比**（宽 ÷ 高）：内容盒高 = {@code min(屏高, 屏宽 / 3.9)}。
     *
     * <p>版式里「横向位置」全是占屏宽的比例、「字号 / 行高」全是占内容盒高的比例 —— 内容盒
     * 宽高比就是这两把尺子的换算率。设计稿是 6.91:1（列宽宽松、字号宽松，两边都放得下）。
     * 屏幕被连成 1 段时只有 2.29:1：如果继续按 6.91:1 摆，内容盒只有屏高的 33%（上下各留
     * 1/3 屏高的蓝边，字小到看不清）；反过来如果直接用整屏高，列宽只有设计稿的 1/3，站名 /
     * 徽标 / 编号全部要被压到 62% 以下再截断。
     *
     * <p>所以取 3.9:1 作为下限：窄屏时内容盒压缩到 3.9:1 上下居中，字不再跟着缩，列也不至于
     * 被压到 62% 以下（按 3.9:1 算，1 段屏上最紧的「6号线」徽标也还有 ~100% 的字号余量）。
     * 2 段（4.57:1）及以上都比它宽 ⇒ 直接用整块高，不留边。
     *
     * <p><b>这个数不参与编组行的可用宽度</b>：编组行的每一分宽度都是从面板实际像素宽里减出来的
     * （{@link #carRow(int, Assets, float, float)}），段数由 {@link #segments(float)} 现算。
     */
    public static final float MIN_CONTENT_ASPECT = 3.900F;

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
    private static final float LOGO_TOP = 0.0405F;
    private static final float LOGO_H = 0.2980F;

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

    /**
     * 标签列 → 终点站列的固定间隙 ÷ <b>站名字高</b>（和 PIDS 共用同一份规则：
     * {@link PidsXiamenColumns#LABEL_GAP_RATIO}）。
     *
     * <p>为什么两列之间必须有一段「几何上的间隙」：以前标签按 {@code LBL_X + 0.10·屏宽} 画、
     * 站名固定居中在 {@link #DEST_CX}，两者没有任何共同的列边界。「下一趟开往」是 5 个全角字，
     * 一长就压到站名上 —— 实机截图里中英两行都糊成「下一趟开往第一码头」「Next trainfirst warf」。
     * 现在标签列宽由**实测**得出（{@link #arrivalColumns}），站名从标签列右缘 + 这个间隙起。
     */
    public static final float LABEL_GAP_RATIO = PidsXiamenColumns.LABEL_GAP_RATIO;

    /** 间隙下限（px）：屏再小、字再小也不许贴合；离线断言按「间隙 ≥ 4px」判。 */
    public static final float MIN_LABEL_GAP_PX = 4.0F;

    /**
     * 终点站列**设计稿**中线（占屏宽）。
     *
     * <p><b>绘制不再无条件居中对齐到这条线</b>：站名现在在「标签列右缘 + 间隙 … {@link #DEST_COL_R}」
     * 这一列里居中（见 {@link #arrivalColumns}），所以短站名的中线会随标签列宽左右移动。
     * 这个常量保留下来是给 {@code tools/psd-lcd-fit-check.js} 对照设计稿、以及不带列几何的老调用方用的。
     */
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

    // ================================================================ 各列能用到哪儿（长站名的字号靠它反解）
    //
    // 长站名以前是「超了就 ∝ 缩」，没有下限、也没有真正的列边界 —— 10 个字的站名会被压到 40%
    // （糊成一条线）；而「大数字」那一列是右对齐、宽度没约束，四个字的状态文案（即将进站 / 已到站）
    // 会从数字右缘往左伸展，压进终点站那一列。这里把三列的**左右边界**量出来写成常量：
    // 文字右缘 ≤ 列右缘、左缘 ≥ 列左缘，剩下的宽度交给 TextFit 反解字号
    // （只缩不放，下限 62%，缩到底才截断）。数值是 W=3072 / h=444.6 / BOLD 黑体逐条量出来的，
    // 量法与实测宽度见 tools/xiamen-text-fit 的运行输出。

    /**
     * 终点站列**设计稿**左缘（占屏宽）：左标签列最宽的一行墨迹右缘 + 间距。
     * 「下一班开往」= 0.1545、「Third train」= 0.1569 → 0.1569 + 0.006。
     *
     * <p><b>绘制不再用这个数</b>：站名列起点由实测标签列宽现算
     * （{@code nameX = LBL_X·屏宽 + labelW + gap}，见 {@link #arrivalColumns}），
     * 出厂中英文标签算出来正好落在这一带（≈ 0.163 屏宽）。留作设计稿对照。
     */
    private static final float DEST_COL_L = 0.1629F;
    /**
     * 终点站列右缘（占屏宽）：时间/数字列左缘 − 间距。两位数「23」墨迹宽 0.0349、右缘
     * {@link #NUM_RIGHT} = 0.34 ⇒ 数字列左缘 0.3051 → 0.3051 − 0.007。
     *
     * <p>这一条<b>仍然生效</b>：站名在「{@code nameX} … 这里」的列内居中，长站名缩到 62% 再截断，
     * 所以它绝对不会越过这条线去压数字 / 分钟那一列。
     */
    private static final float DEST_COL_R = 0.2981F;

    /** 当前站列左缘：线路徽标右缘 {@link #BADGE_X1} 0.5367 + 间距 0.007。 */
    private static final float STA_COL_L = 0.5437F;
    /** 当前站列右缘：广告区左缘 {@link #AD_X0} 0.7197 − 间距 0.007。 */
    private static final float STA_COL_R = 0.7127F;

    /**
     * 时间/数字列的左缘（占屏宽）：两位数字的墨迹左缘（量得 0.3051）。
     * 数字右对齐到 {@link #NUM_RIGHT}，所以「数字」这一格的左界就是它。
     */
    private static final float NUM_COL_L = 0.3051F;
    /**
     * 状态文案（即将进站 / 已到站）能用到的最右缘：单位列右边、线路徽标左边。
     * 这一档没有数字也没有单位，所以整块 0.3051…0.4322 都归它（4 个全角字 0.104 放得下）。
     */
    private static final float NUM_COL_R = 0.4322F;

    // ---- 编组行：车头 + N 节卡片（右缘对齐 CAR_ROW_X1，车头往左独立占位）
    private static final float CAR_ROW_X1 = 0.6894F;
    /** 编组行最左能到哪（占屏宽）：设计稿量到的行槽左缘（**含**第二行的数字「17」那一列）。 */
    private static final float CAR_ROW_X0 = 0.3158F;
    /**
     * 编组行的**有效**左界（占屏宽）：{@code max(CAR_ROW_X0, CAR_ROW_GUARD_X)} =
     * <b>0.4392</b>（= {@link #NUM_COL_R} 0.4322 + 0.007 空档）。
     *
     * <p>为什么这么靠右：第二行的「已到站 / 即将进站」是从字数那边左对齐写起的四字文案，
     * y 带 {@code ROW1_CY + ROW_DY + NUM_TOP … + NUM_INK} = 0.561…0.738 正好落在编组行的
     * y 带（0.449…0.687）里，x 又一直铺到 {@link #NUM_COL_R} 0.4322 —— 行槽只要从 0.3158
     * 起就<b>一定</b>会压掉「站」字（实机截图就是这个：第 2 行「即将进站」右半被车头盖住）。
     *
     * <p>以前这里只守到 0.388（只让开「分钟 / min」那一列 ~0.379）。数字档位没有单位，
     * 状态档位又没有数字，两者共用 0.3051…0.4322 这一整块，所以守卫线必须让到状态文案的右缘。
     * 0.007 的余量 = 描边级。{@code tools/psd-lcd-fit-check.js} 与
     * {@code tools/psd-lcd/PsdLcdSegments} 都有「状态文案包围盒 × 编组行包围盒不相交」的断言。
     *
     * <p>守卫线<b>不随内容变</b>（不因为这一行是数字还是「已到站」而左右跳）：倒计时从
     * 「3」跳到「已到站」时整行不能跟着平移。
     */
    private static final float CAR_ROW_GUARD_X = 0.4392F;
    private static final float CAR_Y0 = 0.4492F;
    private static final float CAR_Y1 = 0.6870F;
    /** 卡片自然宽（h）。设计稿 4 节卡片是 0.0656 屏宽 = 0.453h，素材比例同一档。 */
    private static final float CAR_W = 0.4690F;
    /** 卡片之间 / 车头与首卡之间的间隙 ÷ 卡片宽（设计稿 0.0068 屏宽 ÷ 0.0656）。 */
    private static final float CAR_GAP_RATIO = 0.1000F;
    /** 车头高 ÷ 卡片高（素材 74×180，圆头朝左）。 */
    private static final float NOSE_H_RATIO = 1.035F;
    /**
     * 卡片宽的下限（× 自然宽）：<b>压到 32% 就不再压</b>，改成「少画几节 + 尾部 …」。
     *
     * <p>为什么要有下限：卡片高是固定的（只压宽），宽压到 25% 以下就成了一条竖线，编号也糊了；
     * 而「少画几节」只是丢信息，屏上还是认得出来的车。32% 时卡片 0.67:1（素材是 2.09:1）——
     * 比正方形略高，车号和座椅花纹都还看得清。
     *
     * <p>这个数定在 32% 是量出来的：2 段 + 6 节刚好需要 34%（不降级，6 节全画），
     * 1 段 + 6 节需要 28.5%（降到下限后放得下 4 节，尾部补 …）—— 正好是用户说的
     * 「段数少到实在放不下才减少显示节数」。
     */
    private static final float CAR_MIN_SCALE = 0.3200F;
    /** 降级时尾部「…」占位槽的宽 ÷ 卡片宽。 */
    private static final float CAR_ELLIPSIS_W = 0.5500F;
    /** 编组节数上限（防呆：真 20 节的编组先截断，不画成一片竖线）。 */
    private static final int CAR_CAP = 16;
    /** 卡片素材的原始像素尺寸（按比例缩放，绝不拉伸）。 */
    private static final float CARD_PX_W = 2144.0F;
    private static final float CARD_PX_H = 1024.0F;
    /** 车头素材的原始像素尺寸（没有素材时按它算占位宽，几何不跟着资源走）。 */
    private static final float NOSE_PX_W = 74.0F;
    private static final float NOSE_PX_H = 180.0F;
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

    // ---- 广告区：0.7197 屏宽 → 右边缘，整屏高；没有广告图时整块留空（不画占位块）
    private static final float AD_X0 = 0.7197F;

    // ================================================================ 颜色

    public static final int COLOR_BG = 0xFF0206E0;
    public static final int COLOR_WHITE = 0xFFFFFFFF;
    public static final int COLOR_CARD_STROKE = 0xFFF4F6FF;
    public static final int COLOR_TICK_STROKE = 0xFFE6EAFF;

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
     * @param carNumbers 车厢编号（1 2 3 4），长度 = 画几张卡片（空 = 编组行整块不画）
     * @param carNote    编组行下面那行小字（空 = 不画）
     * @param tickerText 标语条文字（<b>一律滚动</b>，见 {@link #paintTickerStrip}；空 = 连标语框都不画）
     * @param adLabel    广告位的占位字 —— <b>已经不用了</b>（没有广告图时广告区整块留空，
     *                   不画占位色块也不画占位文字）；字段留着只为不改动构造函数签名

     * @param unitZh2/unitEn2 第二行的单位（第一行是 {@code unitZh/unitEn}）—— **两行各带各的**，
     *                        以前共用一个字段，第二行的「分钟 / min」就会跟着第一行一起空掉
     */
    public record Content(String date, String weekdayZh, String weekdayEn, String clock,
                          String boundLabel, String boundSub, String dest1, String dest1En, String count1,
                          String nextLabel, String nextSub, String dest2, String dest2En, String count2,
                          String unitZh, String unitEn,
                          String unitZh2, String unitEn2,
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
        return paint(c, assets, ad, width, height, null);
    }

    /**
     * 同上，另外把「标语滚到 {@code tickerPhase}」的那一帧一起烘进位图。
     *
     * <p><b>只有离线预览会用这个重载</b>（{@code tools/psd-lcd/PsdLcdPreview}）：游戏里长句标语
     * 是渲染器每 tick 单独画到一张小贴图上的（{@link #paintTickerStrip}），不能烘进整屏位图 ——
     * 整屏位图只在内容变化时重画，烘进去就等于「滚动标语又不滚了」。
     *
     * @param tickerPhase 0…1 的滚动进度；{@code null} = 位图里不烘字（游戏路径，字由渲染器滚）
     */
    public static BufferedImage paint(final Content c, final Assets assets, final BufferedImage ad,
                                      final int width, final int height, final Float tickerPhase) {
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
            paintInto(g, c, assets, ad, width, height, tickerPhase);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 画到现成的画布上（预览工具拼多张图 / 渲染器复用一个 BufferedImage 时用）。 */
    public static void paintInto(final Graphics2D g, final Content c, final Assets assets,
                                 final float width, final float height) {
        paintInto(g, c, assets, null, width, height, null);
    }

    /** 画到现成的画布上，另带广告图。 */
    public static void paintInto(final Graphics2D g, final Content c, final Assets assets,
                                 final BufferedImage ad, final float width, final float height) {
        paintInto(g, c, assets, ad, width, height, null);
    }

    /** 画到现成的画布上，另带广告图与标语滚动进度（{@code null} = 长句不画）。 */
    public static void paintInto(final Graphics2D g, final Content c, final Assets assets,
                                 final BufferedImage ad, final float width, final float height,
                                 final Float tickerPhase) {
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

            // ---- 两行到站（列几何一次解出：标签列宽 = 四条标签实测最宽值，两行共用）
            final ArrivalColumns arrivalCols = arrivalColumns(c.boundLabel(), c.boundSub(),
                    c.nextLabel(), c.nextSub(), assets, width, h);
            row(g, c, assets, width, h, ROW1_CY, arrivalCols, c.boundLabel(), c.boundSub(),
                    c.dest1(), c.dest1En(), c.count1(), c.unitZh(), c.unitEn());
            row(g, c, assets, width, h, ROW1_CY + ROW_DY, arrivalCols, c.nextLabel(), c.nextSub(),
                    c.dest2(), c.dest2En(), c.count2(), c.unitZh2(), c.unitEn2());

            // ---- 右侧一组
            badge(g, c, assets, width, h);
            station(g, c, assets, width, h);
            cars(g, c, assets, width, h);

            // ---- 标语条：框只要有字就画；字一律由渲染器滚动（预览时按 tickerPhase 烘进来）
            paintTicker(g, c, assets, width, height, tickerPhase);
        } finally {
            g.setTransform(savedTransform);
        }
    }

    /**
     * 内容盒高（px）：{@code min(屏高, 屏宽 / }
     * {@link #MIN_CONTENT_ASPECT}{@code )}，见那个常量的注释（为什么窄屏不再按设计稿 6.91:1 压）。
     *
     * <p>2 段及以上（宽高比 ≥ 4.57:1）恒等于屏高 —— 位图第一行就是内容第一行，没有上下蓝边；
     * 1 段（2.29:1）压缩到 3.9:1 后上下居中，既不留 2/3 屏高的空边，也不把列宽压到截断。
     */
    public static float layoutHeight(final float width, final float height) {
        final float byWidth = width / MIN_CONTENT_ASPECT;
        return Math.min(height, Math.max(1.0F, byWidth));
    }

    /**
     * 面板的段数（横向连接了几格）：每段位图宽 {@code PX_PER_BLOCK × BITMAP_SCALE} = 1040px。
     *
     * <p>版式不再假设「屏一定是设计稿那么宽」：编组行的可用宽度、每个槽能放几节卡片，
     * 全部按这个段数算出来的实际像素宽来解（见 {@link #carRow(int, Assets, float, float)}）。
     */
    public static int segments(final float width) {
        return Math.max(1, Math.round(
                width / (PsdLcdLayout.PX_PER_BLOCK * PsdLcdLayout.BITMAP_SCALE)));
    }

    // ================================================================ 广告区

    /**
     * 广告区在位图里的矩形（px）：{@code {x0, y0, x1, y1}}。
     *
     * <p>左缘 = {@link #AD_X0} × 位图宽（取整到像素），其余三边贴位图边缘（整屏高）。<b>画和断言
     * 用的是同一个函数</b>，所以「广告画到哪」与「断言说它该在哪」不可能走岔。
     */
    public static float[] adRegion(final float width, final float height) {
        return new float[]{Math.round(AD_X0 * width), 0.0F,
                Math.round(width), Math.round(height)};
    }

    /** 广告区左缘（占整条链宽 = 占位图宽）：{@link #AD_X0} 的只读副本，离线报告换算世界四边形时用。 */
    public static float adLeft() {
        return AD_X0;
    }

    /**
     * 广告图取源的 UV 矩形 {@code {u0, v0, u1, v1}}。
     *
     * <p>非等比拉伸铺满整块区域 ⇒ 整张图都要用到，UV 恒为 {@code 0,0,1,1}；这里仍然显式夹到
     * 0…1（{@link #clamp01}），万一哪天换成「只取广告图的一部分」，越界的 UV 也不会被
     * 采样到区域外面去。离线断言逐项检查这四个数都在 0…1。
     */
    public static float[] adSourceUv() {
        return new float[]{clamp01(0.0F), clamp01(0.0F), clamp01(1.0F), clamp01(1.0F)};
    }

    /**
     * 广告区：{@link #AD_X0}（0.7197 屏宽）→ 右边缘，整屏高。
     *
     * <p><b>允许非等比拉伸</b>（用户定的）：广告图直接拉伸铺满这块矩形，<b>不再按 cover 保比例</b>
     * —— 保比例时宽图会裁掉上下、高图会裁掉左右，屏上看着就像「广告没落在它那块区域里」。
     * 现在图被拉伸到恰好等于区域，四边严丝合缝（允许变形，不允许越界 / 留边）。
     *
     * <p><b>严格夹在区内</b>，三重保证：目标矩形一律用 {@link #adRegion}（整数像素）、源 UV 用
     * {@link #adSourceUv}（夹到 0…1）、画的时候再套一层同矩形的 clip。所以哪怕素材尺寸奇怪、
     * 或者 Java2D 的插值在边界上多画半个像素，也不会有任何像素落到区域外。
     * 离线断言（{@code tools/psd-lcd/PsdLcdSegments}）在 1/2/3 段上逐像素检查：区内没有底色、
     * 区外一圈没有广告色。
     *
     * <p><b>没有广告图 → 整块留空</b>（不画洋红占位块、也不画「广告位」文字）：拿不到内容的那块
     * 就什么都不画，和 PIDS 那边「没广告 = 只留底图空白」一致。以前画的是设计稿里那块洋红
     * —— 那是稿子上的标注，不是屏上内容。
     */
    private static void adBlock(final Graphics2D g, final Content c, final Assets assets,
                                final BufferedImage ad, final float width, final float height,
                                final float h, final float offY) {
        final float[] region = adRegion(width, height);
        final float x0 = region[0];
        final float y0 = region[1];
        final float x1 = region[2];
        final float y1 = region[3];
        if (x1 - x0 < 1.0F || y1 - y0 < 1.0F) {
            return;
        }
        if (ad == null || ad.getWidth() <= 0 || ad.getHeight() <= 0) {
            // 没有广告图 → 整块留空（背景透出，不画占位色块 / 占位文字）
            return;
        }
        // 源 UV 夹在 0…1（现在恒为整张图）；目标矩形严格等于 adRegion ⇒ 四边 0 误差。
        final float[] uv = adSourceUv();
        final Shape old = g.getClip();
        g.clip(new Rectangle2D.Float(x0, y0, x1 - x0, y1 - y0));
        try {
            drawScaled(g, ad, x0, y0, x1 - x0, y1 - y0, uv);
        } finally {
            g.setClip(old);
        }
    }

    private static float clamp01(final float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
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
        // 墨迹框**运行期量**，不写死。
        // 旧版把 600×600 的 M 标量到的「墨迹 470×376、左上 65,112」写死成常量：
        // 一旦资源包把 logo 换成别家的图（同样 600×600、但墨迹大小/位置不同），
        // 缩放和锚点就全错 —— 表现就是「标志偏大 / 偏出卡片」。
        // 现在按 alpha 扫出真实墨迹框，原版图与资源包图都按自己的墨迹对齐。
        final int[] box = inkBox(image);
        final float inkW = Math.max(1.0F, box[2] - box[0]);
        final float inkH = Math.max(1.0F, box[3] - box[1]);
        // 宽也要夹在卡片里：窄屏（1 段）时内容盒被压到 3.9:1，卡片只有 0.0975 屏宽，而 M 标
        // 是按高度定大小的 —— 只按高度算的话墨迹会横出卡片、压到「开往」那一列。取两者小的那个。
        final float cardInk = (CARD_X1 - CARD_X0) * w - 0.014F * h;
        final float scale = Math.min((LOGO_H * h) / inkH, cardInk / inkW);
        final float drawW = image.getWidth() * scale;
        final float drawH = image.getHeight() * scale;
        final float inkLeftInImage = box[0] * scale;
        final float inkTopInImage = box[1] * scale;
        final float x = LOGO_CX * w - (inkW * scale) * 0.5F - inkLeftInImage;
        final float y = LOGO_TOP * h - inkTopInImage;
        drawScaled(g, image, x, y, drawW, drawH);
    }

    /**
     * 量出一张图的**墨迹框**（alpha &gt; 1/8 的像素组成的包围盒），返回 {@code [x0, y0, x1, y1]}。
     *
     * <p>没有 alpha 通道（整张不透明）时退化为「与左上角像素颜色不同」的包围盒；
     * 全透明/全同色则返回整张图，至少不会算出 0 尺寸。
     */
    private static int[] inkBox(final BufferedImage image) {
        final int iw = image.getWidth();
        final int ih = image.getHeight();
        int x0 = iw;
        int y0 = ih;
        int x1 = -1;
        int y1 = -1;
        final boolean hasAlpha = image.getColorModel().hasAlpha();
        final int corner = image.getRGB(0, 0);
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                final int argb = image.getRGB(x, y);
                final boolean ink = hasAlpha ? ((argb >>> 24) > 31) : (argb != corner);
                if (ink) {
                    if (x < x0) {
                        x0 = x;
                    }
                    if (y < y0) {
                        y0 = y;
                    }
                    if (x > x1) {
                        x1 = x;
                    }
                    if (y > y1) {
                        y1 = y;
                    }
                }
            }
        }
        if (x1 < 0 || y1 < 0) {
            return new int[]{0, 0, iw, ih};
        }
        return new int[]{x0, y0, x1 + 1, y1 + 1};
    }

    private static void cardTexts(final Graphics2D g, final Content c, final Assets assets,
                                  final float w, final float h) {
        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final float cx = CARD_TEXT_CX * w;
        // 卡片内宽再留 10% 余量：窄屏（1 段）时日期正好贴着墨迹宽，居中会顶到左边那条描边上
        final float maxW = (CARD_X1 - CARD_X0) * w * 0.90F;
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

    // ================================================================ 一行到站的列几何

    /**
     * 两行到站共用的列几何（px）：
     * {@code [labelX, labelX+labelW] → gap → [nameX, nameRight]}。
     *
     * @param labelX    标签列左缘（左对齐）
     * @param labelW    标签列宽 = 四条标签实测宽的最大值（两行、中英共用）
     * @param gap       标签列 → 站名列的固定间隙
     * @param nameX     站名列起点
     * @param nameRight 站名列右缘（= 数字列左缘 − 间距，见 {@link #DEST_COL_R}）
     */
    public record ArrivalColumns(float labelX, float labelW, float gap, float nameX, float nameRight) {

        /** 标签列右缘（墨迹可达的最右处）。 */
        public float labelRight() {
            return this.labelX + this.labelW;
        }

        /** 站名列可用宽（交给 {@link TextFit#fitFontSize} 反解字号）。 */
        public float nameW() {
            return Math.max(0.0F, this.nameRight - this.nameX);
        }

        /** 站名列中线 —— 站名<b>在这一列里</b>居中（不再是无条件对齐设计稿中线）。 */
        public float nameCenter() {
            return this.nameX + this.nameW() * 0.5F;
        }
    }

    /**
     * 解出两行到站的列几何：<b>先量四条标签，再定语站名从哪起</b>。
     *
     * <pre>
     *   labelX    = LBL_X × 屏宽
     *   labelW    = max(开往, Bound for, 下一趟开往, Next train 在各自名义字号下的实测宽)  ← 两行共用
     *   gap       = 站名字高（DEST_INK × h）× {@value #LABEL_GAP_RATIO}，不低于 {@value #MIN_LABEL_GAP_PX}px
     *   nameX     = labelX + labelW + gap
     *   nameRight = DEST_COL_R × 屏宽
     *   nameW     = nameRight − nameX                     ← 交给 TextFit（只缩不放，下限 62%）
     * </pre>
     *
     * <p><b>两行必须共用同一份</b>（所以这个方法一次收四条标签）：只要某一行按自己的标签宽算起点，
     * 两行站名的列就会错开十几 px。绘制（{@link #row}）和离线断言
     * （{@code tools/xiamen-text-fit}）调的是同一个函数，所以断言量的就是屏上那一次。
     *
     * <p>标签自己也被压在 {@code labelW} 这一列里（{@link #fitLabel}）—— 哪怕某个语言把标签
     * 翻得特别长，它也只会在标签列里缩字 / 截断，<b>绝不会</b>再压到站名上。
     */
    public static ArrivalColumns arrivalColumns(final String label1, final String sub1,
                                                final String label2, final String sub2,
                                                final Assets assets, final float width,
                                                final float height) {
        final Assets a = assets == null ? new Assets() : assets;
        final float h = layoutHeight(width, height);
        final Font zh = labelZhFont(a, h);
        final Font en = labelEnFont(a, h);
        final List<PidsXiamenColumns.Label> labels = new java.util.ArrayList<>(4);
        final List<TextFit.Metrics> metrics = new java.util.ArrayList<>(4);
        addLabel(labels, metrics, label1, zh);
        addLabel(labels, metrics, sub1, en);
        addLabel(labels, metrics, label2, zh);
        addLabel(labels, metrics, sub2, en);
        final float labelX = LBL_X * width;
        final float labelW = PidsXiamenColumns.labelWidth(labels, metrics);
        final float gap = Math.max(MIN_LABEL_GAP_PX, PidsXiamenColumns.gap(DEST_INK * h));
        final float nameRight = DEST_COL_R * width;
        return new ArrivalColumns(labelX, labelW, gap,
                Math.min(labelX + labelW + gap, nameRight), nameRight);
    }

    /**
     * 出厂标签（开往 / Bound for / 下一趟开往 / Next train）的那一份列几何。
     *
     * <p>离线断言 / {@link #textBox} 不带自定义文案时用它；中文这一档就是渲染器实际传进来的那四条，
     * 所以和屏上完全一致。
     */
    public static ArrivalColumns arrivalColumns(final Assets assets, final float width, final float height) {
        return arrivalColumns("开往", "Bound for", "下一趟开往", "Next train",
                assets, width, height);
    }

    /** 把一条标签连同它的量字函数追加进表里（空串 / 没有字体就跳过）。 */
    private static void addLabel(final List<PidsXiamenColumns.Label> labels,
                                 final List<TextFit.Metrics> metrics,
                                 final String text, final Font nominal) {
        if (text == null || text.isEmpty() || nominal == null) {
            return;
        }
        labels.add(new PidsXiamenColumns.Label(text, nominal.getSize2D()));
        metrics.add((t, size) -> inkWidth(nominal.deriveFont(Math.max(1.0F, size)), t));
    }

    /**
     * 标签列一行的排版结果（绘制与断言同一函数）：名义字号按「开往 / Bound for」的墨迹高反推，
     * 列宽上限 = {@link ArrivalColumns#labelW()}。
     *
     * @param chinese {@code true} = 中文那一行（黑体 + 大一号），{@code false} = 英文那一行
     */
    public static Fitted fitLabel(final String text, final boolean chinese, final Assets assets,
                                  final float width, final float height, final ArrivalColumns cols) {
        final Assets a = assets == null ? new Assets() : assets;
        final float h = layoutHeight(width, height);
        final Font base = chinese ? labelZhFont(a, h) : labelEnFont(a, h);
        final String ref = chinese ? "开往" : "Bound for";
        final float inkH = (chinese ? LBLZH_INK : LBLEN_INK) * h;
        final float maxW = cols == null ? 0.0F : cols.labelW();
        return fitToColumn(text, base, ref, inkH, maxW);
    }

    /** 标签的墨迹右缘（px，左对齐）—— 离线断言判「标签不压站名」用。 */
    public static float labelInkRight(final String text, final boolean chinese, final Assets assets,
                                      final float width, final float height, final ArrivalColumns cols) {
        final float labelX = cols == null ? LBL_X * width : cols.labelX();
        final Fitted fitted = fitLabel(text, chinese, assets, width, height, cols);
        return labelX + inkWidth(fitted.font(), fitted.text());
    }

    /** 标签列中文那一行的名义字体（「开往」的墨迹高 → 字号）；和 {@link #line} 里反推的是同一个。 */
    private static Font labelZhFont(final Assets a, final float h) {
        return fit(a.cjk(), "开往", LBLZH_INK * h);
    }

    /** 标签列英文那一行的名义字体（「Bound for」的墨迹高 → 字号）。 */
    private static Font labelEnFont(final Assets a, final float h) {
        return fit(a.latin(), "Bound for", LBLEN_INK * h);
    }

    // ================================================================ 一行到站

    /**
     * 一行到站：标签列（左对齐，宽 = 实测标签列宽）+ 终点站（列内居中）+ 大数字（右对齐）+ 单位列。
     *
     * @param cyH  这一行的中线（h）
     * @param cols 两行共用的列几何（{@link #arrivalColumns}）—— 标签列宽 / 间隙 / 站名列边界
     */
    private static void row(final Graphics2D g, final Content c, final Assets assets,
                            final float w, final float h, final float cyH,
                            final ArrivalColumns cols,
                            final String label, final String labelSub, final String dest,
                            final String destEn, final String count,
                            final String unitZh, final String unitEn) {
        final Font cjk = assets == null ? null : assets.cjk();
        final Font latin = assets == null ? null : assets.latin();
        final Font digits = assets == null ? null : assets.digits();
        final float cy = cyH * h;
        final int white = COLOR_WHITE;

        // 这一行取不到到站（没有排班 / 线路表还没同步）→ **整行留空**：连「开往 / Bound for」
        // 标签也不画，那块就是底图的蓝色（不摆占位字）。
        if ((dest == null || dest.isBlank()) && (destEn == null || destEn.isBlank())
                && (count == null || count.isBlank())) {
            return;
        }

        // 标签列：左缘 LBL_X、宽 = 四条标签的实测最宽值（cols.labelW）。
        // 标签被压在自己这一列里 —— 再长也只在列内缩字 / 截断，绝不会压到站名上。
        line(g, label, cjk, "开往", cy + LBLZH_TOP * h, LBLZH_INK * h,
                cols.labelX(), Align.LEFT, cols.labelW(), white);
        line(g, labelSub, latin, "Bound for", cy + LBLEN_TOP * h, LBLEN_INK * h,
                cols.labelX(), Align.LEFT, cols.labelW(), white);

        // 终点站：在 [nameX, nameRight] 这一列里居中（不再无条件对齐设计稿中线 DEST_CX）。
        // 短站名居中在列里；长站名只缩不放（下限 62%），缩到底才截断 —— 绝不压进右边那一列。
        final float nameW = Math.max(1.0F, cols.nameW());
        line(g, dest, cjk, "华侨大学", cy + DEST_TOP * h, DEST_INK * h,
                cols.nameCenter(), Align.CENTER, nameW, white);
        line(g, destEn, latin, "Huaqiao University", cy + DESTEN_TOP * h, DESTEN_INK * h,
                cols.nameCenter(), Align.CENTER, nameW, white);

        // 大数字 / 到站状态：
        //   · 数字：右缘贴 NUM_RIGHT，左界是两位数字的墨迹左缘（只缩不截断）；
        //   · 状态（即将进站 / 已到站）：没有数字也没有单位，从数字列左缘左对齐写起，
        //     可以用到单位列右边 —— 以前它走数字那一格（右对齐 + 0.07w），4 个全角字会被压到
        //     67% 并往左伸进终点站那一列。
        if (count != null && !count.isEmpty()) {
            final boolean state = !count.codePoints().allMatch(Character::isDigit);
            if (state) {
                line(g, count, cjk, "已到站", cy + NUM_TOP * h, NUM_INK * h,
                        columnAnchor(Column.COUNT_STATE, w), Align.LEFT,
                        columnWidth(Column.COUNT_STATE, w), white);
            } else {
                // 三位数（如秒数模式的 120）比两位宽，数字列只有 0.0419W：
                // 先按真实列宽把字号压下来（不截断），再交给 line() —— line() 自己还有 0.62 下限，
                // 不先压就会右对齐后往左溢出到终点站那一列。
                final float numColW = columnWidth(Column.COUNT, w);
                float numInk = NUM_INK * h;
                final float natW = inkWidth(digits.deriveFont(numInk), count);
                if (natW > numColW && natW > 0.0F) {
                    numInk *= Math.max(0.42F, numColW / natW);
                }
                line(g, count, digits, "0", cy + NUM_TOP * h, numInk,
                        NUM_RIGHT * w, Align.RIGHT, numColW, white);
            }
            // 「已到站 / 即将进站」这一档没有数字也没有单位：整块 0.3051…0.4322 都归它。
            // 单位行**不画**（渲染器那边 unitOf() 对状态文案也返回空串；这里再兜一道，
            // 免得别的调用方传了「分钟 / min」时和状态文案叠在同一列里）。
            if (state) {
                return;
            }
            final float unitX = UNIT_X * w;
            line(g, unitZh, cjk, "分钟", cy + UNITZH_TOP * h, UNITZH_INK * h,
                    unitX, Align.LEFT, 0.045F * w, white);
            line(g, unitEn, latin, "min", cy + UNITEN_TOP * h, UNITEN_INK * h,
                    unitX, Align.LEFT, 0.045F * w, white);
        }
    }

    // ================================================================ 右侧一组

    /**
     * 线路徽标（徐标）：一个色块 + 两行字。
     *
     * <p><b>取不到就整块不画</b>：没有线路名（{@code lineZh / lineEn} 都空）或者拿不到线路色
     * （{@code lineColor} 的 alpha = 0）时直接返回 —— 那块保持底图的蓝色，<b>不画占位文字、
     * 也不画默认色块</b>。以前 route 拿不到时会画一块 {@code 0xFF2B36C8} 的深蓝圆角块，
     * 看着像「有个来历不明的色块」，这一版去掉了。
     */
    private static void badge(final Graphics2D g, final Content c, final Assets assets,
                              final float w, final float h) {
        final boolean hasLine = (c.lineZh() != null && !c.lineZh().isBlank())
                || (c.lineEn() != null && !c.lineEn().isBlank());
        final int color = c.lineColor();
        if (!hasLine || ((color >>> 24) & 0xFF) == 0) {
            // 没有线路 / 没有线路色 → 徐标整块留空（背景透出）
            return;
        }
        final float x0 = BADGE_X0 * w;
        final float x1 = BADGE_X1 * w;
        final float y0 = BADGE_Y0 * h;
        final float y1 = BADGE_Y1 * h;
        g.setColor(new Color(color, true));
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
        // 当前站：列宽 = 线路徽标右缘 … 广告区左缘。长站名只缩不放（下限 62%），
        // 缩到底才截断 —— 一行大字压到徽标或广告上，比截断难看得多。
        line(g, c.stationZh(), cjk, "董任", STA_ZH_TOP * h, STA_ZH_INK * h,
                cx, Align.CENTER, columnWidth(Column.STATION_ZH, w), white);
        line(g, c.stationEn(), latin, "Dongren", STA_EN_TOP * h, STA_EN_INK * h,
                cx, Align.CENTER, columnWidth(Column.STATION_EN, w), white);
    }

    /**
     * 编组行的几何：<b>画一次、断言一次，都用这一份</b>（所以不可能出现「画的和量的不是同一个」）。
     *
     * <p>所有量都是位图像素；{@code segments} 是面板实际段数（{@link #segments(float)}），
     * {@code avail} 是这一工况下行槽真正能用的宽度（= {@code boxX1 − rowLeft − 车头宽}）。
     */
    public record CarRow(int segments, int declared, int shown, boolean degraded,
                         float boxX0, float boxX1, float boxY0, float boxY1,
                         float cardsX0, float cardW, float cardWNat, float gap, float cardH,
                         float cardY0, float noseX, float noseW, float noseH,
                         float ellipsisW, float avail) {

        /** 整块（车头 + 卡片 + 「…」）的包围盒：{@code {x0, y0, x1, y1}}。 */
        public float[] box() {
            return new float[]{boxX0, boxY0, boxX1, boxY1};
        }
    }

    /**
     * 编组行左界（px）：{@code max(CAR_ROW_X0, CAR_ROW_GUARD_X) × 屏宽}，
     * 见 {@link #CAR_ROW_GUARD_X}（要让开「已到站 / 即将进站」那一列）。
     */
    public static float rowLeft(final float width) {
        return Math.max(CAR_ROW_X0, CAR_ROW_GUARD_X) * width;
    }

    /**
     * 编组行几何：车头 + N 节卡片，节距均匀，整行<b>右缘</b>对齐 {@link #CAR_ROW_X1}。
     *
     * <h2>压缩规则（只压宽，不整体等比缩；按面板实际段数解）</h2>
     * <pre>
     *   segments = round(屏宽 / 1040)                       ← 面板实际段数（1 / 2 / 3 / …）
     *   h        = min(屏高, 屏宽 / 3.9)                    ← 内容盒高（见 MIN_CONTENT_ASPECT）
     *   ch       = min(CAR_W·h·(1024/2144), 行高)           ← 卡片高：固定，与节数无关
     *   cwNat    = ch ÷ (1024/2144)                         ← 「高度不缩」对应的自然宽
     *   gap      = cw × CAR_GAP_RATIO                       ← 间隙与卡片同比例缩
     *   left     = max(CAR_ROW_X0, CAR_ROW_GUARD_X) × 屏宽
     *   right    = CAR_ROW_X1 × 屏宽
     *   avail    = right − left − 车头宽                    ← 车头之外能用的宽度（= 本记录里的 avail）
     *   cw       = min(cwNat, avail ÷ (n × (1 + gapRatio)))
     * </pre>
     * 上式里 {@code cw = avail/(n(1+gapRatio))} 时整行正好等于 {@code avail} ⇒ 车头左缘落在
     * {@code left}、末卡右缘落在 {@code right}，<b>任何节数都不会越界、不会互相压</b>。
     *
     * <h2>降级：压到下限就少画几节 + 尾部「…」</h2>
     * <p>{@code cw < cwNat × CAR_MIN_SCALE(0.32)} 时不再往窄里压（再压就是一条竖线），
     * 而是把卡片宽锁在下限，算出还能放几节：
     * <pre>
     *   cw   = cwNat × 0.32
     *   slot = cw × (1 + gapRatio)                  ← 一节卡片 + 一个间隙
     *   ell  = cw × 0.55                            ← 尾部「…」的占位槽
     *   k    = floor((avail − ell − cw·gapRatio) ÷ slot)   （夹到 1…n）
     * </pre>
     * 画前 k 节 + 三个白点（「…」）。所以「1 段 + 6 节」这种工况是 <b>4 节 + …</b>，
     * 而不是 6 张叠在一起；2 段 / 3 段的 6 节则照常全画（34% / 52% 宽）。
     *
     * @param cars 编组节数（渲染器按 MTR 里那列车的实际编组给；&lt;=0 时不画）
     */
    public static CarRow carRow(final int cars, final Assets assets, final float width, final float height) {
        final float h = layoutHeight(width, height);
        final float rowBottom = CAR_Y0 * h;
        final float rowTop = CAR_Y1 * h;
        final float rowH = Math.max(1.0F, rowTop - rowBottom);
        final float right = CAR_ROW_X1 * width;
        final float left = rowLeft(width);

        final float ratio = CARD_PX_H / CARD_PX_W;
        // 卡片高度 = 自然高（只受行高上限约束），**不随节数变小**；压缩只发生在宽度方向上
        final float ch = Math.max(1.0F, Math.min(CAR_W * h * ratio, rowH));
        final float cwNat = ch / ratio;

        // 车头：按同一份固定高度算，节数再多也不缩
        final float noseH = ch * NOSE_H_RATIO;
        final boolean hasNose = assets != null && assets.nose != null;
        final float noseAspect = hasNose
                ? assets.nose.getWidth() / (float) assets.nose.getHeight()
                : NOSE_PX_W / NOSE_PX_H;
        final float noseW = hasNose ? noseH * noseAspect : 0.0F;

        final int n = Math.max(1, Math.min(CAR_CAP, cars));
        final float r = CAR_GAP_RATIO;
        // 车头之外能用多少宽度（车头 + 一个间隙也在这一行里，间隙按卡片宽算，所以先粗留）
        final float avail = Math.max(1.0F, right - left - noseW);

        float cw = Math.min(cwNat, avail / (n * (1.0F + r)));
        int shown = n;
        boolean degraded = false;
        if (cw < cwNat * CAR_MIN_SCALE) {
            // 压到下限还排不下 ⇒ 锁住下限，少画几节，尾部补「…」
            cw = cwNat * CAR_MIN_SCALE;
            final float slot = cw * (1.0F + r);
            final float ell = cw * CAR_ELLIPSIS_W;
            final int fit = (int) Math.floor((avail - ell - cw * r) / slot);
            shown = Math.max(1, Math.min(n, fit));
            degraded = shown < n;
        }
        final float gap = cw * r;
        final float ellipsisW = degraded ? cw * CAR_ELLIPSIS_W : 0.0F;

        // 卡片组（含尾部「…」）右缘贴 right，车头挂在首卡左边
        final float cardsW = shown * cw + (shown - 1) * gap
                + (degraded ? gap + ellipsisW : 0.0F);
        final float cardsX0 = right - cardsW;
        final float cardY0 = rowBottom + (rowH - ch) * 0.5F;
        final float noseX = cardsX0 - gap - noseW;
        // 包围盒：车头比卡片高，别漏掉它上下那半格
        final float noseY0 = cardY0 + (ch - noseH) * 0.5F;
        final float boxX0 = hasNose ? Math.min(noseX, cardsX0) : cardsX0;
        final float boxY0 = hasNose ? Math.min(cardY0, noseY0) : cardY0;
        final float boxY1 = hasNose ? Math.max(cardY0 + ch, noseY0 + noseH) : cardY0 + ch;
        return new CarRow(segments(width), n, shown, degraded, boxX0, right, boxY0, boxY1,
                cardsX0, cw, cwNat, gap, ch, cardY0, noseX, noseW, noseH, ellipsisW, avail);
    }

    /**
     * 编组行在位图上的包围盒（px）：{@code {x0, y0, x1, y1}}（已经算上窄屏的 {@code offY}）。
     *
     * <p>就是 {@link #carRow} 画出来的那一块 —— 离线断言拿它和状态文案的包围盒判「0 像素重叠」。
     * 编组不画（没有编组 / 没有素材）时返回空数组。
     */
    public static float[] carsBox(final int cars, final Assets assets,
                                  final float width, final float height) {
        if (cars <= 0 || assets == null) {
            return new float[0];
        }
        final CarRow row = carRow(cars, assets, width, height);
        final float offY = (height - layoutHeight(width, height)) * 0.5F;
        return new float[]{row.boxX0(), offY + row.boxY0(), row.boxX1(), offY + row.boxY1()};
    }

    /** 一行字的墨迹上沿 / 墨迹高（h 单位）：按列名给，和 {@link #row} / {@link #station} 画的那一份一致。 */
    private static float[] columnInkBand(final Column column) {
        return switch (column) {
            case DEST_ZH -> new float[]{DEST_TOP, DEST_INK};
            case DEST_EN -> new float[]{DESTEN_TOP, DESTEN_INK};
            case STATION_ZH -> new float[]{STA_ZH_TOP, STA_ZH_INK};
            case STATION_EN -> new float[]{STA_EN_TOP, STA_EN_INK};
            case COUNT, COUNT_STATE -> new float[]{NUM_TOP, NUM_INK};
        };
    }

    /**
     * 某一列文字在位图上的**墨迹包围盒**（px）：{@code {x0, y0, x1, y1}}。
     *
     * <p>和 {@link #line} 画出来的完全同一份算术（同一个 {@link #fitColumn}、同一个 ink 度量、
     * 同一个「缩过就垂直居中」规则），所以断言量到的就是屏上那一块。
     * {@code row} 只对「到站」那几列有意义（0 = 第一行、1 = 第二行，y 跟着 {@link #ROW1_CY} +
     * {@link #ROW_DY} 走）；当前站两列是绝对位置，{@code row} 不起作用。
     *
     * @return 空数组 = 这一列没画字
     */
    public static float[] textBox(final Column column, final int row, final String text,
                                 final Assets assets, final float width, final float height) {
        if (text == null || text.isEmpty()) {
            return new float[0];
        }
        // 列几何与绘制同一份（标签列宽实测），否则「断言说站名在哪」和「屏上画在哪」会错开。
        final ArrivalColumns cols = arrivalColumns(assets, width, height);
        final Fitted fitted = fitColumn(column, text, assets, width, height, cols);
        final float h = layoutHeight(width, height);
        final float offY = (height - h) * 0.5F;
        final float inkH = inkHeight(fitted.font(), fitted.text());
        final float[] band = columnInkBand(column);
        // 当前站两列是绝对 y（station() 里直接用 STA_*_TOP），到站那几列挂在行的中线上
        final boolean absolute = column == Column.STATION_ZH || column == Column.STATION_EN;
        final float bandTop = absolute
                ? band[0] * h
                : (ROW1_CY + Math.max(0, row) * ROW_DY) * h + band[0] * h;
        final float top = fitted.shrunk() ? bandTop + (band[1] * h - inkH) * 0.5F : bandTop;
        final float x0 = inkLeft(column, fitted, width, cols);
        return new float[]{x0, offY + top, x0 + inkWidth(fitted.font(), fitted.text()),
                offY + top + inkH};
    }

    /**
     * 编组行：车头（行首独立占位）+ N 节卡片，节距均匀，整行**右缘**对齐 {@link #CAR_ROW_X1}。
     *
     * <p>几何全部由 {@link #carRow(int, Assets, float, float)} 算（画与断言同一份），这里只管落笔：
     * 卡片高度不变、宽度可压；压到 {@link #CAR_MIN_SCALE} 下限就少画几节并在尾部写「…」。
     */
    private static void cars(final Graphics2D g, final Content c, final Assets assets,
                             final float w, final float h) {
        final List<String> numbers = c.carNumbers();
        if (numbers == null || numbers.isEmpty() || assets == null) {
            return;
        }
        final CarRow row = carRow(numbers.size(), assets, w, h);
        if (assets.nose != null && row.noseW() > 0.0F) {
            drawScaled(g, assets.nose, row.noseX(),
                    row.cardY0() + (row.cardH() - row.noseH()) * 0.5F, row.noseW(), row.noseH());
        }
        for (int i = 0; i < row.shown(); i++) {
            // 首尾车 = 单雪花素材，中间车 = 双雪花素材（素材自己画好座椅 / 雪花）
            final BufferedImage card = (i == 0 || i == row.shown() - 1)
                    ? assets.carEnd : assets.carMiddle;
            final float cx = row.cardsX0() + i * (row.cardW() + row.gap());
            if (card != null) {
                // 宽度可压、高度不变 ⇒ 卡片被横向压扁（用户允许）
                drawScaled(g, card, cx, row.cardY0(), row.cardW(), row.cardH());
            }
            // 车号：素材上只有「车 ❄」，编号由这里补在「车」的左边。
            // 字号按**固定**的片高算（CAR_TEXT_INK_H），不跟卡片宽走 —— 节数再多编号也一样大。
            final String number = numbers.get(i);
            if (number != null && !number.isEmpty()) {
                final float inkH = row.cardH() * CAR_TEXT_INK_H;
                final float textRight = Math.max(cx + row.cardW() * 0.02F,
                        cx + row.cardW() * CAR_TEXT_LEFT - row.cardW() * 0.025F);
                final float inkCy = row.cardY0() + row.cardH() * CAR_TEXT_CY;
                line(g, number, assets.cjk(), "1", inkCy - inkH * 0.5F, inkH,
                        textRight, Align.RIGHT, 0.0F, COLOR_WHITE);
            }
        }
        if (row.degraded() && row.ellipsisW() > 0.0F) {
            // 尾部「…」：三个白点，占满它自己那一槽、和车号同一条中线 —— 不压上一张卡、也不越出 right。
            // 为什么不画 U+2026：那是**字体字形**，SimHei / 资源包字体里经常缺这个码位（缺了就画成
            // 一个白方块），而且墨迹高只有小圆点、按 inkHeight 反推字号会算出一个巨大的字。三个
            // 圆点是纯几何，任何字体 / 任何分辨率下都一样。
            final float lastRight = row.cardsX0()
                    + (row.shown() - 1) * (row.cardW() + row.gap()) + row.cardW();
            final float ellX0 = lastRight + row.gap();
            final float ellX1 = ellX0 + row.ellipsisW();
            final float d = Math.max(1.5F, Math.min(row.cardH() * 0.13F, row.ellipsisW() * 0.22F));
            final float step = d * 1.9F;
            final float total = d + 2.0F * step;
            final float cy = row.cardY0() + row.cardH() * CAR_TEXT_CY;
            float dx = (ellX0 + ellX1) * 0.5F - total * 0.5F;
            g.setColor(argb(COLOR_WHITE));
            for (int i = 0; i < 3; i++) {
                g.fill(new java.awt.geom.Ellipse2D.Float(dx, cy - d * 0.5F, d, d));
                dx += step;
            }
        }
    }

    /**
     * 编组行下面那行小字（设计稿：注：此处按线路实际运营车辆读取编组）。
     *
     * <p>编组行<b>整块不画</b>时（读不到实时编组）这行小字也一起不画 —— 它属于编组行，
     * 留一行「注：……」挂在空行下面比空着更奇怪。
     */
    private static void note(final Graphics2D g, final Content c, final Assets assets,
                             final float w, final float h) {
        final String text = c.carNote();
        if (text == null || text.isBlank()
                || c.carNumbers() == null || c.carNumbers().isEmpty()) {
            return;
        }
        line(g, text, assets == null ? null : assets.cjk(), "注：编组按线路实际运营车辆", NOTE_TOP * h,
                NOTE_INK * h, NOTE_X * w, Align.LEFT, (CAR_ROW_X1 - NOTE_X) * w, COLOR_WHITE);
    }

    // ================================================================ 标语条（滚动）

    /**
     * 标语条（胶囊）在位图里的矩形：{@code {x0, y0, x1, y1}}。
     *
     * <p>已经算上「窄屏（1~2 段）按设计稿比例收窄后上下居中」的那条 {@code offY}，
     * 所以渲染器拿它算四边形和拿它画位图是同一套坐标。
     */
    public static float[] tickerBar(final float width, final float height) {
        final float h = layoutHeight(width, height);
        final float offY = (height - h) * 0.5F;
        return new float[]{TICK_X0 * width, offY + TICK_Y0 * h, TICK_X1 * width, offY + TICK_Y1 * h};
    }

    /** 标语条里**能放字**的矩形（扣掉描边 + 一圈余量）：{@code {x0, y0, x1, y1}}。 */
    public static float[] tickerInner(final float width, final float height) {
        final float[] bar = tickerBar(width, height);
        final float h = layoutHeight(width, height);
        final float inset = Math.max(1.0F, TICK_SW * h) * 1.5F;
        return new float[]{bar[0] + inset, bar[1] + inset, bar[2] - inset, bar[3] - inset};
    }

    /** 标语字高（位图像素）= 条高 × {@link #TICK_SIZE_RATIO}。滚速按它换算，见 {@code PidsXiamenTicker#pxPerTick}。 */
    public static float tickerFontPx(final float width, final float height) {
        final float[] bar = tickerBar(width, height);
        return Math.max(1.0F, (bar[3] - bar[1]) * TICK_SIZE_RATIO);
    }

    /** 标语文字的自然宽度（位图像素；空文字 = 0）。和 {@link #tickerInner} 的宽比较就知道滚不滚。 */
    public static float tickerTextWidth(final Content c, final Assets assets,
                                        final float width, final float height) {
        return tickerTextWidthFor(c == null ? null : c.tickerText(), assets, width, height);
    }

    /** 同上，直接给字符串（渲染器算「滚一轮多少 tick」时用，不用先造一个 Content）。 */
    public static float tickerTextWidthFor(final String text, final Assets assets,
                                           final float width, final float height) {
        if (text == null || text.isBlank()) {
            return 0.0F;
        }
        final float inkH = tickerFontPx(width, height);
        // 一行字的墨迹宽要现算字形轮廓（GlyphVector），而渲染器每帧都会问一次 ——
        // 缓存最近一次（渲染线程单线程访问，一条 volatile 足够）。
        final String fontKey = assets == null ? "logic" : String.valueOf(System.identityHashCode(assets.cjk));
        if (text.equals(widthCacheText) && Math.abs(inkH - widthCacheInk) < 1.0E-4F
                && fontKey.equals(widthCacheFont)) {
            return widthCacheWidth;
        }
        final Font font = fit(assets == null ? null : assets.cjk(), text, inkH);
        final float ink = inkWidth(font, text);
        widthCacheText = text;
        widthCacheInk = inkH;
        widthCacheFont = fontKey;
        widthCacheWidth = ink;
        return ink;
    }

    /** 上一帧量过的标语（字 + 字高 + 字体）。 */
    private static String widthCacheText = "";
    private static String widthCacheFont = "";
    private static float widthCacheInk = -1.0F;
    private static float widthCacheWidth = 0.0F;

    /** 滚动小贴图的宽（位图像素）：就是 {@link #tickerInner} 的宽，四舍五入。 */
    public static int tickerStripWidth(final float width, final float height) {
        final float[] inner = tickerInner(width, height);
        return Math.max(1, Math.round(inner[2] - inner[0]));
    }

    /** 滚动小贴图的高（位图像素）。 */
    public static int tickerStripHeight(final float width, final float height) {
        final float[] inner = tickerInner(width, height);
        return Math.max(1, Math.round(inner[3] - inner[1]));
    }

    /** 一句话滚完一轮要走多少像素 = 框内宽 + 字宽（右缘进 → 左缘出）。 */
    public static float tickerScrollSpanPx(final Content c, final Assets assets,
                                           final float width, final float height) {
        final float[] inner = tickerInner(width, height);
        return (inner[2] - inner[0]) + tickerTextWidth(c, assets, width, height);
    }

    /**
     * 只画标语条里的那段字：<b>透明底</b>的小位图（{@code tickerStripWidth × tickerStripHeight}）。
     *
     * <p>为什么单独一张：整屏位图只在「内容变了」时重画（时钟 / 倒计时是分钟级），
     * 滚动标语每 tick 都要动 —— 把相位算进整屏的内容哈希就等于每 tick 重画 3000×455 的位图。
     * 所以整屏位图里只有那一圈胶囊框，字<b>一律</b>画进这张「条内小贴图」（短句也滚，
     * 从右缘外进来、滚出左缘）。
     *
     * <p>落点与 PIDS 一致：{@code x = 框内右缘 − phase × (框内宽 + 字宽)} —— phase=0 时整句
     * 刚好在框外右侧，phase=1 时整句从左缘出去；跨过边缘的字被位图边界直接切掉（半字），
     * 不是整字消失。速度由 {@code PidsXiamenTicker#pxPerTick} 换算（和 PIDS 同一份）。
     */
    public static BufferedImage paintTickerStrip(final Content c, final Assets assets,
                                                 final float width, final float height,
                                                 final float phase) {
        final int sw = tickerStripWidth(width, height);
        final int sh = tickerStripHeight(width, height);
        final BufferedImage image = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        final String text = c == null ? null : c.tickerText();
        if (text == null || text.isBlank()) {
            return image;
        }
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            final float inkH = tickerFontPx(width, height);
            final float span = tickerScrollSpanPx(c, assets, width, height);
            final float p = Math.max(0.0F, Math.min(1.0F, phase));
            // 相对「条内左缘」的 x：phase=0 → 框内右缘（整句在框外），phase=1 → 左缘 − 字宽
            final float x = sw - p * span;
            line(g, text, assets == null ? null : assets.cjk(), text,
                    sh * 0.5F - inkH * 0.5F, inkH, x, Align.LEFT, 0.0F, COLOR_WHITE);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * 标语条：<b>胶囊框只要有字就画</b>，字一律<b>不烘进整屏位图</b> ——
     * <ul>
     *   <li>游戏里：渲染器每 tick 把那一帧画到一张透明小贴图上滚（{@link #paintTickerStrip}），
     *       短句长句同一套，都是「从右缘外进来、滚出左缘」；</li>
     *   <li>离线预览：把 {@code tickerPhase} 传进来时，这里把那一帧合成就来，方便看图。</li>
     * </ul>
     *
     * <p>以前短句（字宽 ≤ 框内宽）是居中静止烘在位图里的 —— 现在<b>一律滚</b>，不再有那条分支：
     * 短句和长句用同一个 {@code pxPerTick} 速度、同一条「框内宽 + 字宽」的行程。
     */
    private static void paintTicker(final Graphics2D g, final Content c, final Assets assets,
                                    final float width, final float height,
                                    final Float tickerPhase) {
        final String text = c.tickerText();
        if (text == null || text.isBlank()) {
            // 没有标语就不画框：空胶囊看着像没画完
            return;
        }
        final float[] bar = tickerBar(width, height);
        final float h = layoutHeight(width, height);
        // paintInto 已经把画布按 offY 平移过了，这里是「排版高 h」里的坐标 → 减掉 offY 才是当前画布坐标
        final float offY = (height - h) * 0.5F;
        final float tx0 = bar[0];
        final float tx1 = bar[2];
        final float ty0 = bar[1] - offY;
        final float ty1 = bar[3] - offY;
        final float radius = (ty1 - ty0) * 0.5F;
        g.setColor(argb(COLOR_TICK_STROKE));
        g.setStroke(new BasicStroke(Math.max(1.0F, TICK_SW * h), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(tx0 + TICK_SW * h * 0.5F, ty0 + TICK_SW * h * 0.5F,
                (tx1 - tx0) - TICK_SW * h, (ty1 - ty0) - TICK_SW * h, radius * 2.0F, radius * 2.0F));
        g.setStroke(new BasicStroke(1.0F));

        final float[] inner = tickerInner(width, height);
        final float textW = tickerTextWidth(c, assets, width, height);
        if (tickerPhase == null || textW <= 0.0F) {
            // 游戏路径：字由渲染器滚动画在小贴图上，位图里不烘（短句也一样）
            return;
        }
        // 预览路径：把「滚到 tickerPhase」那一帧合成就来（短句长句同一套）
        final BufferedImage strip = paintTickerStrip(c, assets, width, height, tickerPhase);
        g.drawImage(strip, Math.round(inner[0]), Math.round(inner[1] - offY), null);
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

    /**
     * 同上面那个，但只取源图的 {@code uv} 那块（{@code {u0, v0, u1, v1}}，先夹到 0…1）并
     * <b>拉伸</b>到目标矩形上 —— 目标宽高比和素材不一致是<b>允许</b>的（广告要的非等比拉伸）。
     *
     * <p>为什么不做逐级减半：那是给 2144×1024 的卡片素材缩到 100px 用的；广告是放大 / 近似 1:1，
     * 减半只会多一道重采样。直接用 8 参数 {@code drawImage} 一次画准，边界由调用方的 clip 兜住。
     */
    private static void drawScaled(final Graphics2D g, final BufferedImage src,
                                   final float x, final float y, final float w, final float h,
                                   final float[] uv) {
        if (src == null || w < 1.0F || h < 1.0F) {
            return;
        }
        if (uv == null || uv.length < 4) {
            drawScaled(g, src, x, y, w, h);
            return;
        }
        final int iw = src.getWidth();
        final int ih = src.getHeight();
        final float u0 = clamp01(Math.min(uv[0], uv[2]));
        final float v0 = clamp01(Math.min(uv[1], uv[3]));
        final float u1 = clamp01(Math.max(uv[0], uv[2]));
        final float v1 = clamp01(Math.max(uv[1], uv[3]));
        final int sx0 = Math.round(u0 * iw);
        final int sy0 = Math.round(v0 * ih);
        final int sx1 = Math.max(sx0 + 1, Math.round(u1 * iw));
        final int sy1 = Math.max(sy0 + 1, Math.round(v1 * ih));
        final int dx0 = Math.round(x);
        final int dy0 = Math.round(y);
        g.drawImage(src, dx0, dy0, dx0 + Math.round(w), dy0 + Math.round(h),
                sx0, sy0, sx1, sy1, null);
    }

    // ================================================================ 文字

    /**
     * 画一行字，<b>按「参考字串的墨迹高」定字号、按「墨迹上沿」定位</b>。
     *
     * <p>为什么这样做：设计稿能量到的是「字形的墨迹包围盒」，不是字号；而不同字体、
     * 不同字符（有没有下伸部）在同一字号下的墨迹高并不一样。所以这里给的是
     * 「参考字串 + 它在设计稿里的墨迹高」，运行期反推字号 —— 换字体也不会跑版。
     *
     * <p><b>宽度</b>：{@code maxW} 是这一列能用的宽度，超出时交给 {@link TextFit} ——
     * 先反解字号（只缩不放，下限 {@link TextFit#MIN_SCALE}），缩到下限还放不下才截断加「…」。
     * 以前是无下限地等比缩小（长站名被压成一条糊线），而且缩成什么样都不会承认放不下。
     *
     * @param text    真正要画的字（可为空）
     * @param base    字体（null = 逻辑字体）
     * @param ref     量字号用的参考字串（null = 用 text 自己）
     * @param inkTop  墨迹上沿（px）
     * @param inkH    墨迹高（px）
     * @param anchor  对齐锚点（px）
     * @param maxW    这一列能用的宽度（px；&lt;=0 = 不限）
     */
    private static void line(final Graphics2D g, final String text, final Font base, final String ref,
                             final float inkTop, final float inkH, final float anchor,
                             final Align align, final float maxW, final int argbColor) {
        if (text == null || text.isEmpty() || inkH <= 0.0F) {
            return;
        }
        final Fitted fitted = fitToColumn(text, base, ref == null ? text : ref, inkH, maxW);
        final Font font = fitted.font();
        final String drawn = fitted.text();
        final float w = inkWidth(font, drawn);
        final float inkX0 = inkX0(font, drawn);
        final float inkY0 = inkY0(font, drawn);
        // 缩过的字在**原来那一行**里垂直居中：墨迹上沿按「缩掉的高度」下移一半，
        // 否则它贴着上沿画，看着像整行往上飘了。
        final float top = fitted.shrunk()
                ? inkTop + (inkH - inkHeight(font, drawn)) * 0.5F : inkTop;
        final float x = switch (align) {
            case CENTER -> anchor - inkX0 - w * 0.5F;
            case RIGHT -> anchor - inkX0 - w;
            default -> anchor - inkX0;
        };
        g.setFont(font);
        g.setColor(new Color(argbColor, true));
        g.drawString(drawn, x, top - inkY0);
    }

    // ---------------------------------------------------------------- 按列宽反解字号（长站名）

    /** 一次排版的结果：最终字体（已按列宽反解）+ 最终文本（可能截断）+ 缩/裁了没有。 */
    public record Fitted(Font font, String text, float size, boolean shrunk, boolean truncated) {
    }

    /** PSD-LCD 里需要「按列宽反解字号」的几格字。 */
    public enum Column {
        /** 终点站（中文，大）。 */
        DEST_ZH,
        /** 终点站（英文，小）。 */
        DEST_EN,
        /** 当前站（中文，大）。 */
        STATION_ZH,
        /** 当前站（英文，小）。 */
        STATION_EN,
        /** 倒计时数字（右对齐贴 {@link #NUM_RIGHT}，只缩不截断 —— 「1…」没有意义）。 */
        COUNT,
        /** 到站状态文案（即将进站 / 已到站，占据数字 + 单位那一整块）。 */
        COUNT_STATE
    }

    /**
     * 这一列能用的宽度（px）：居中的列取「中心到左右边界里更近的那一边」×2
     * —— 所以居中画出来的字左右都不会越界。
     *
     * <p>公开给离线断言（{@code tools/xiamen-text-fit}）用：断言量的是**同一份**边界。
     */
    public static float columnWidth(final Column column, final float width) {
        return switch (column) {
            case DEST_ZH, DEST_EN ->
                    2.0F * Math.min(DEST_CX - DEST_COL_L, DEST_COL_R - DEST_CX) * width;
            case STATION_ZH, STATION_EN ->
                    2.0F * Math.min(STA_CX - STA_COL_L, STA_COL_R - STA_CX) * width;
            case COUNT -> (NUM_RIGHT - DEST_COL_R) * width;
            case COUNT_STATE -> (NUM_COL_R - NUM_COL_L) * width;
        };
    }

    /**
     * 带列几何的版本 —— <b>绘制 / 断言都走这个</b>：终点站两列用
     * {@link ArrivalColumns#nameW()}（= 标签列右缘 + 间隙 … {@link #DEST_COL_R}），
     * 其余列和不带的版本一致。
     *
     * <p>不带 {@code cols} 的老版本对终点站用的是设计稿中线 {@link #DEST_CX} 左右各取一半，
     * 那是「标签列宽写死」时代的列；现在标签列宽是实测的，两者会差十几 px，别混用。
     */
    public static float columnWidth(final Column column, final float width, final ArrivalColumns cols) {
        if (cols != null && (column == Column.DEST_ZH || column == Column.DEST_EN)) {
            return cols.nameW();
        }
        return columnWidth(column, width);
    }

    /** 这一列的水平锚点（px）：居中列给中线、右对齐列给右缘、左对齐列给左缘。 */
    public static float columnAnchor(final Column column, final float width) {
        return switch (column) {
            case DEST_ZH, DEST_EN -> DEST_CX * width;
            case STATION_ZH, STATION_EN -> STA_CX * width;
            case COUNT -> NUM_RIGHT * width;
            case COUNT_STATE -> NUM_COL_L * width;
        };
    }

    /**
     * 带列几何的版本：终点站两列的「中线」= {@link ArrivalColumns#nameCenter()}（列内居中），
     * 不再是无条件对齐设计稿中线 {@link #DEST_CX}。
     */
    public static float columnAnchor(final Column column, final float width, final ArrivalColumns cols) {
        if (cols != null && (column == Column.DEST_ZH || column == Column.DEST_EN)) {
            return cols.nameCenter();
        }
        return columnAnchor(column, width);
    }

    /** 这一列的对齐方式。 */
    public static Align columnAlign(final Column column) {
        return switch (column) {
            case DEST_ZH, DEST_EN, STATION_ZH, STATION_EN -> Align.CENTER;
            case COUNT -> Align.RIGHT;
            case COUNT_STATE -> Align.LEFT;
        };
    }

    /**
     * 这一列文字能到的最左缘（px）：居中 / 左对齐列就是「锚点 − 0 或 − 半宽」，
     * 右对齐列是「锚点 − 列宽」。它们由 {@link #columnWidth} 反推，所以和量出来的边界是同一份。
     */
    public static float columnLeft(final Column column, final float width) {
        final float anchor = columnAnchor(column, width);
        return switch (columnAlign(column)) {
            case LEFT -> anchor;
            case RIGHT -> anchor - columnWidth(column, width);
            case CENTER -> anchor - columnWidth(column, width) * 0.5F;
        };
    }

    /** 带列几何的版本（终点站两列按列内居中反推左缘）。离线断言用。 */
    public static float columnLeft(final Column column, final float width, final ArrivalColumns cols) {
        final float anchor = columnAnchor(column, width, cols);
        return switch (columnAlign(column)) {
            case LEFT -> anchor;
            case RIGHT -> anchor - columnWidth(column, width, cols);
            case CENTER -> anchor - columnWidth(column, width, cols) * 0.5F;
        };
    }

    /** 这一列文字能到的最右缘（px）。离线断言用它判「右缘 ≤ 列右缘」。 */
    public static float columnRight(final Column column, final float width) {
        final float anchor = columnAnchor(column, width);
        return switch (columnAlign(column)) {
            case RIGHT -> anchor;
            case LEFT -> anchor + columnWidth(column, width);
            case CENTER -> anchor + columnWidth(column, width) * 0.5F;
        };
    }

    /** 带列几何的版本。 */
    public static float columnRight(final Column column, final float width, final ArrivalColumns cols) {
        final float anchor = columnAnchor(column, width, cols);
        return switch (columnAlign(column)) {
            case RIGHT -> anchor;
            case LEFT -> anchor + columnWidth(column, width, cols);
            case CENTER -> anchor + columnWidth(column, width, cols) * 0.5F;
        };
    }

    /** 这一列文字的墨迹左缘（px，按 {@code fitted} 的实际宽度算）。离线断言用。 */
    public static float inkLeft(final Column column, final Fitted fitted, final float width) {
        final float anchor = columnAnchor(column, width);
        final float w = inkWidth(fitted.font(), fitted.text());
        return switch (columnAlign(column)) {
            case LEFT -> anchor;
            case RIGHT -> anchor - w;
            case CENTER -> anchor - w * 0.5F;
        };
    }

    /** 带列几何的版本：终点站两列按列内居中的中线反推墨迹左缘。离线断言用。 */
    public static float inkLeft(final Column column, final Fitted fitted, final float width,
                                final ArrivalColumns cols) {
        final float anchor = columnAnchor(column, width, cols);
        final float w = inkWidth(fitted.font(), fitted.text());
        return switch (columnAlign(column)) {
            case LEFT -> anchor;
            case RIGHT -> anchor - w;
            case CENTER -> anchor - w * 0.5F;
        };
    }

    /** 这一列文字的墨迹右缘（px）。 */
    public static float inkRight(final Column column, final Fitted fitted, final float width) {
        return inkLeft(column, fitted, width) + inkWidth(fitted.font(), fitted.text());
    }

    /** 带列几何的版本。 */
    public static float inkRight(final Column column, final Fitted fitted, final float width,
                                 final ArrivalColumns cols) {
        return inkLeft(column, fitted, width, cols) + inkWidth(fitted.font(), fitted.text());
    }

    /**
     * 这一列的排版结果（字号 / 最终文本 / 是否缩过 / 是否截断）。
     *
     * <p>绘制（{@link #row} / {@link #station}）和离线断言（{@code tools/xiamen-text-fit}）
     * 调的是<b>同一个函数</b>，所以断言量的就是屏上画的那一次。
     */
    public static Fitted fitColumn(final Column column, final String text, final Assets assets,
                                   final float width, final float height) {
        return fitColumn(column, text, assets, width, height, arrivalColumns(assets, width, height));
    }

    /**
     * 带列几何的版本（绘制 / 断言同一函数）：终点站两列的宽度 = {@link ArrivalColumns#nameW()}。
     */
    public static Fitted fitColumn(final Column column, final String text, final Assets assets,
                                   final float width, final float height, final ArrivalColumns cols) {
        final Assets a = assets == null ? new Assets() : assets;
        final float h = layoutHeight(width, height);
        final Font base;
        final String ref;
        final float inkH;
        switch (column) {
            case DEST_ZH -> {
                base = a.cjk();
                ref = "华侨大学";
                inkH = DEST_INK * h;
            }
            case DEST_EN -> {
                base = a.latin();
                ref = "Huaqiao University";
                inkH = DESTEN_INK * h;
            }
            case STATION_ZH -> {
                base = a.cjk();
                ref = "董任";
                inkH = STA_ZH_INK * h;
            }
            case STATION_EN -> {
                base = a.latin();
                ref = "Dongren";
                inkH = STA_EN_INK * h;
            }
            case COUNT -> {
                base = a.digits();
                ref = "0";
                inkH = NUM_INK * h;
            }
            default -> {
                base = a.cjk();
                ref = "已到站";
                inkH = NUM_INK * h;
            }
        }
        final boolean mayTruncate = column != Column.COUNT;
        return fitToColumn(text, base, ref, inkH, columnWidth(column, width, cols), mayTruncate);
    }

    /**
     * 排版一列字：名义字号（按参考字串的墨迹高反推）→ 按列宽反解 → 缩到下限才截断。
     *
     * @param mayTruncate 放不下时允不允许截断（数字那一格不允许：截成「1…」没有意义，
     *                    宁可让它在 62% 上略微出格 —— 实际上只有 4 位以上的分钟数才会走到）
     */
    private static Fitted fitToColumn(final String text, final Font base, final String ref,
                                      final float nominalInkH, final float maxW) {
        return fitToColumn(text, base, ref, nominalInkH, maxW, true);
    }

    private static Fitted fitToColumn(final String text, final Font base, final String ref,
                                      final float nominalInkH, final float maxW,
                                      final boolean mayTruncate) {
        final Font nominal = fit(base, ref, nominalInkH);
        final float nominalSize = nominal.getSize2D();
        if (text == null || text.isEmpty() || maxW <= 0.0F) {
            return new Fitted(nominal, text == null ? "" : text, nominalSize, false, false);
        }
        final TextFit.Metrics metrics = metrics(nominal);
        final float size = TextFit.fitFontSize(text, metrics, nominalSize, maxW, TextFit.MIN_SCALE);
        final boolean shrunk = size < nominalSize - 0.01F;
        final String drawn = mayTruncate
                ? TextFit.ellipsize(text, metrics, size, maxW)
                : text;
        return new Fitted(nominal.deriveFont(Math.max(1.0F, size)), drawn, size,
                shrunk, !drawn.equals(text));
    }

    /** 量字：给字号 → 这段字的墨迹宽（和 {@code drawString} 出来的墨迹同一个口径）。 */
    private static TextFit.Metrics metrics(final Font base) {
        return (text, size) -> inkWidth(base.deriveFont(Math.max(1.0F, size)), text);
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
