// 线路条带的纯 AWT 绘制（不引用任何 MC / MTR 类型，可离线单独跑出 PNG 预览）。
package dev.js.mtrheadlight.xiamen;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 厦门实拍样式的线路条带，<b>纯 Java2D</b>（这个类刻意不 import 任何 {@code net.minecraft} /
 * {@code mtr} 类型，所以能用 {@code javac} 单独编译，直接吐出 PNG 看版式，不用开游戏）。
 *
 * <h2>v7 版式（2026-11-12：无黑箭头 / 方向统一镜像 / 当前站只填红实心）</h2>
 * <pre>
 *   绘制顺序（从底到顶，v8.1）：
 *     1. 白底条带（fillRect 铺满整张图）
 *     2. 线路主线（先整条铺“已过站灰”，再把「当前站 → 终点」这段用线路色盖回来）
 *     3. 所有站点标记（普通环 / 换乘环 / 当前站红色实心圆，当前站最后）
 *        普通环、换乘图标各自先铺一层**不透明的环身底色**（普通站白心、当前站白底 + 红圆），
 *        主线因此在环边上“断”开 —— 不会从追逐箭头环左右两侧的缺口里横穿过去
 *     4. 站名文字（45° 斜排 / 正排）
 *     5. <b>终点箭头</b>（箭杆 + 三角）—— 整张图的最后一笔
 *
 *   换乘图标内部画序（同一格）：
 *        环身白底 → 下弧（换乘线路色）→ 上弧（当前线路色；<b>当前站 = 当前站红</b>）→ 垂尾 → 线路牌
 *        即 <b>箭头永远压在环身白底上面</b>；v9 起当前站不再画环内红实心圆，
 *        当前站的标记就是「上半弧红 + 下半弧换乘色」的追逐箭头环（用户实拍确认）
 *
 *   <b>镜像</b>         <b>只有 {@link Model#direction} 一个输入</b>（MTR 的 ARROW_DIRECTION 原值）：
 *                     0 = 终点在右，1 / 2 / 3 = 整条镜像（终点在左）。站位、站名、换乘杆、主线、
 *                     终点箭头全部由它推出来 —— 不会出现“条带翻了、箭头没翻”
 *
 *   白底              铺满整张图（= 面板尺寸不变，不会透出灰板）
 *   左右内边距        PAD_X = 宽度 x 3%（最小 24px）：首站 / 末站 / 箭头 / 主线 / 站名都不贴边
 *   内容区            只占高度的 <b>60%</b>（居中）：线宽 / 圆环 / 线路牌都按内容区缩，
 *                     上下留白比 v4 再大一档 —— 多出来的白边<b>让给站名</b>
 *   主线              内容区高度的 8.5%；从 PAD_X 起、到 宽 - PAD_X 止（终点那头停在箭头底边）
 *   <b>已过站</b>         数据顺序里排在当前站之前的站：<b>圆环 + 站名 + 换乘牌固定灰</b>
 *                     （{@link #PASSED_GREY} / {@link #PASSED_NAME_GREY}），
 *                     对应的<b>区段</b>也用固定灰 {@link #PASSED_SEGMENT_GREY} —— 与线路色无关
 *   普通站            白心空心环，环宽 15%
 *   终点站            和普通站<b>完全同尺寸</b>的环 + <b>线路同色的实心箭头</b>：
 *                     箭杆就是线路本身（主线停在箭头底边），<b>宽三角</b>箭尖顶住环外缘并压进环 2px
 *                     （宽 2.3×线宽、长 1.9×线宽），且是<b>最后画的</b> → 箭尖不会被任何东西盖住
 *   <b>当前站</b>         <b>固定红</b>（{@link #CURRENT_FILL}）的<b>实心圆</b>，直径 = 普通站圆环
 *                     的可见外缘（<b>不放大、不套外圈</b>）；站名也是同一个红 ——
 *                     <b>不跟线路色走</b>（v7 用户实拍指定）
 *   换乘站            追逐箭头环（上半 = 当前线路色、下半 = 换乘线路色，两段之间留空隙）；
 *                     下方「矩形杆 + 尖三角」+ 圆角矩形线路牌（v5：牌高 +14%、左右内边距 +18%）；
 *                     环身<b>自带不透明白底</b>，线路主带在环边上断开（v8.1）；
 *                     换乘线路名不做任何「未命名」过滤 —— 站名/线路名就叫「未命名」也是合法名字，照常画
 *   <b>多换乘</b>       一个站有 2 条以上换乘线路（v10）：线路牌换成一排<b>单行小牌</b>一字排开，
 *                     放不下先整体缩（下限 55%），再放不下从<b>尾部截断</b>（至少留 1 个）——
 *                     全部走纯函数 {@link #fitBadgeRow}（可离线断言），可用宽 = 本格站距 x 92%，
 *                     所以既不压相邻站的站名，也不越出面板
 *   站名              45° 斜排、中文 + 英文两行；<b>可用到图片顶部的留白</b>，
 *                     逐站按字宽反解字号（超长的自己变小，再不行裁字加 “…”），绝不越界；
 *                     <b>画面最右那一站</b>（终点站 / 镜像后的起点站）改成往左上的镜像 45°，
 *                     墨迹末端压在自己的圆圈上（v11：以前是整条往左平移，站名离圆一圈空一个字宽）
 *   线路号牌          已删除（v4：用户不需要左侧圆角号码牌）
 * </pre>
 *
 * <p>尺寸全部相对“内容区高度 contentH”（站名字号相对图片高度），见下面的常量。</p>
 */
public final class XiamenStripPainter {

    /** 纹理基准高度（像素）。宽度 = 高度 x 面板长宽比。 */
    public static final int IMG_HEIGHT = 320;

    /** 纹理宽度上限。 */
    public static final int MAX_WIDTH = 4096;

    /**
     * 白底（面板底色）占图片高度：<b>100%</b> —— 背景永远是整块白，尺寸不变。
     *
     * <p>v1/v2 是 60%，上下 40% 透明 —— 吊板的透明像素透出模型那块深灰面板，看上去就是
     * “条带背后贴了一层灰底”。v3 起直接铺满。</p>
     */
    public static final float BAND_RATIO = 1.00f;

    /**
     * <b>内容区</b>占图片高度：<b>60%</b>（v5）。
     *
     * <p>v3 的线宽 / 圆环 / 字号全部按整张图高算，画出来“撑满面板、整体偏大”。v4 缩到 68%，
     * v5 再降到 60%：线宽 / 圆环 / 线路牌继续按这个“内容区高度”算，内容区上下居中，
     * 于是白底尺寸不变、上下留白更大 —— <b>多出来的白边不是浪费，是给 45° 站名用的</b>
     * （站名字号按图片高度算，见 {@link #NAME_MAX_RATIO}）。</p>
     */
    public static final float CONTENT_RATIO = 0.600f;

    /** 主线路宽：内容区高度的 8.5%（MTR {@code lineSize = scale / 8}）。 */
    public static final float LINE_RATIO = 0.085f;

    /** 普通站圆点直径上限：内容区高度的 19%（MTR：直径 = 2×线宽）。 */
    public static final float CIRCLE_RATIO = 0.19f;

    /** 圆环描边 / 内环（MTR drawStation 的 0.29×半径 → 直径的 14.5%）。 */
    public static final float RING_RATIO = 0.15f;
    public static final float INNER_RATIO = 0.58f;

    /**
     * 终点箭头（v5.1，实拍照着来）：<b>长 / 宽（× 线宽）</b>。
     *
     * <p>实拍里箭尖顶住终点圆环、<b>箭杆就是线路本身</b>，头部是一个<b>宽三角</b>：
     * 全长 ≈ 1.9×线宽、总宽 ≈ 2.3×线宽（{@link #TERM_ARROW_HALF} = 1.15×线宽 即半宽）。</p>
     *
     * <p>v5.1 的两处修正（实机截图暴露的问题）：</p>
     * <ul>
     *   <li><b>主线必须停在箭头的底边</b>（v5 主线一直画到站点中心 = 箭尖那一带），
     *       否则主线和箭头同色、粗细又大于箭尖附近，箭尖整个被“埋在”主线里 ——
     *       看上去就是一个粗矩形 + 上下两根刺，不是箭头；</li>
     *   <li><b>箭头是整张图最后画的一笔</b>，箭尖还要往环里压 {@link #TERM_ARROW_TIP_OVERLAP}px，
     *       这样箭尖 100% 露在最上层、又不会和圆环之间留白缝。</li>
     * </ul>
     *
     * <p><b>v7 结论：这里的几何 / 画序一点没动。</b>用户报的“尖没露出来”查下来是<b>镜像</b>的问题
     * （{@link Model#direction} 只跟着 MTR 的 {@code flip} 走，方向 1 / 3 整条图不翻，箭头自然看着
     * “停在原来那一端”），已按“一个方向值驱动全部元素”修好 —— 不要再改这里的尺寸 / 覆盖顺序。</p>
     */
    public static final float TERM_ARROW_LEN = 1.90f;
    public static final float TERM_ARROW_HALF = 1.15f;

    /** 箭尖往终点圆环里压的像素数（消掉描边抗锯齿留下的白缝；箭头最后画，压住也不算被覆盖）。 */
    public static final int TERM_ARROW_TIP_OVERLAP = 2;

    /**
     * 主线<b>非箭头端</b>往端头标记里压的像素数（v9）。
     *
     * <p>主线两端现在对齐「端头标记画出来的外缘」（普通环 / 换乘图标用 {@code visR}，
     * 普通当前站的红实心圆用它自己的 {@link #currentDotDiameter}），再往里压这么几像素：
     * 线头一定压在标记底下 —— <b>既不会在起点站旁边露出裸灰线，也不会和圆环之间留白缝</b>。</p>
     */
    public static final int END_JOINT_OVERLAP = 2;

    /**
     * 线路图左右内边距（v5.1）：<b>宽度 × 3%，且不小于 {@link #PAD_X_MIN}px</b>。
     *
     * <p>v5 之前首站 / 末站直接顶到图片左右边缘（实机上就是“贴边”）。现在首站圆心、末站圆心、
     * 终点箭头、主线两端、站名可用的横向范围全部按 PAD_X 内缩。</p>
     */
    public static final float PAD_X_RATIO = 0.03f;
    public static final int PAD_X_MIN = 24;

    // ---- 换乘标记（追逐箭头环 + 「矩形杆 + 尖三角」+ 正下方居中的线路牌）----

    /** 追逐箭头环直径（相对内容区高度）。比普通站环大一圈，实拍如此。 */
    public static final float IX_RING_RATIO = 0.260f;
    /** 环厚 / 直径。 */
    public static final float IX_RING_THICK = 0.200f;
    /** 两段弧的起止角（数学角，0° = 正右、90° = 正上）：上段 30–150、下段 210–330。 */
    public static final float IX_ARC_TOP_FROM = 30f;
    public static final float IX_ARC_TOP_TO = 150f;
    public static final float IX_ARC_BOTTOM_FROM = 210f;
    public static final float IX_ARC_BOTTOM_TO = 330f;
    /** 箭头尖长度 / 环厚，宽度 / 环厚（v4 调小，免得把两段之间的空隙吃掉）。 */
    public static final float IX_HEAD_LEN = 1.05f;
    public static final float IX_HEAD_W = 1.45f;
    /** 垂尾（矩形杆）根宽 / 环直径。 */
    public static final float IX_TAIL_W = 0.24f;
    /** 垂尾末端尖三角的高度 / 环直径（v4：三角比杆短，杆才是主体）。 */
    public static final float IX_TAIL_HEAD = 0.24f;
    /** 环下缘 → 线路牌上沿（v4 加大：杆 + 尖三角都要塞在这段空隙里）。 */
    public static final float IX_PLATE_GAP_RATIO = 0.090f;
    /** 线路牌高度 / 内容区高度（两行字：中文 + Line N；v5：0.135 → 0.165，牌高 +14%）。 */
    public static final float IX_PLATE_H_RATIO = 0.165f;
    /** 线路牌左右内边距（× 牌高；v5：0.22 → 0.26，牌变高变宽后文字不贴边）。 */
    public static final float IX_PLATE_PAD_RATIO = 0.26f;
    /** 线路牌文字字号 / 牌高（v5：0.46 → 0.47，牌变大后文字随之略增）。 */
    public static final float IX_PLATE_TEXT_RATIO = 0.47f;

    // ---- 多换乘（v10）：一个站有好几条换乘线路时，两行线路牌换成一排单行小牌 ----

    /**
     * 一排小牌的左右内边距 / 牌高：<b>0.30</b>。
     *
     * <p>规则抄自厦门 LCD {@code draw.js#drawTransBadges}（{@code pad = 标高 x 0.30}）：
     * 每个标的宽度 = <b>文字宽 + 2 x 内边距</b>，缩小时字号和内边距一起线性缩。
     * 这里只借规则，不抄它的版面。</p>
     */
    public static final float BADGE_PAD_RATIO = 0.30f;

    /** 一排小牌之间的间距 / 牌高（比内边距小一档：一排牌看起来是“一组”）。 */
    public static final float BADGE_GAP_RATIO = 0.16f;

    /** 一排小牌的牌高 / 两行线路牌的高（单行字，所以矮一点）。 */
    public static final float BADGE_H_RATIO = 0.80f;

    /** 小牌文字字号 / 牌高（单行居中）。 */
    public static final float BADGE_TEXT_RATIO = 0.52f;

    /**
     * 一排小牌的<b>缩放下限 55%</b>（用户指定）。
     *
     * <p>整排比本站可用宽还宽时按 {@code 可用宽 / 总宽} 缩，最多缩到 55%；缩到 55% 仍放不下
     * 就<b>从尾部截断</b>（只画前面放得下的，至少留 1 个）—— 见 {@link #fitBadgeRow}。</p>
     */
    public static final float BADGE_MIN_SCALE = 0.55f;

    /**
     * 一排可用的横向宽度 / 站距：<b>0.92</b>。
     *
     * <p>站距就是“本格可用宽”（相邻两站圆心之间的距离）。留 8% 余量，一排牌无论怎么缩都
     * <b>不会伸进相邻站那一格</b>，也就不可能压到旁边的站名 / 圆环。</p>
     */
    public static final float BADGE_AVAIL_RATIO = 0.92f;

    /**
     * 站名字号上限：<b>图片高度的 9.5%</b>（v5）。
     *
     * <p>v4 是“内容区高度的 14%”（= 图片高度的 9.5%）。v5 内容区缩到 60% 后如果还按内容区算，
     * 站名会跟着缩 12%，和“站名太大/被切”的诉求正好相反 —— 所以基准改成图片高度：
     * 内容变小、站名不变，多出来的白边正好给站名用。</p>
     */
    public static final float NAME_MAX_RATIO = 0.095f;

    /** 站名可以往上用到的白边：图片顶部只留 3.5% 就够（v4 是被内容区顶边 16% 卡住的）。 */
    public static final float NAME_AREA_TOP_RATIO = 0.035f;

    /** 站名与圆环外缘之间的最小 / 最大空隙（像素，随字号在 8–12px 之间取）。 */
    public static final float NAME_CLEARANCE_MIN = 8.0f;
    public static final float NAME_CLEARANCE_MAX = 12.0f;

    /** 英文行字号 / 中文行字号（实拍：英文明显小一号，贴在中文行下方）。 */
    public static final float EN_RATIO = 0.46f;

    /** 字号下限（像素），再小就减少站名数量。 */
    public static final float NAME_FLOOR = 7.5f;

    /**
     * 逐站缩字的下限：基准字号的 <b>0.45</b> 倍（v5）。
     *
     * <p>超长站名自己缩到 45% 就打住 —— 再长就不是“缩字”能解决的，直接裁字加 “…”（LCD 的做法）。
     * 45%（而不是更小）是实拍看下来最小的可读比例：320px 高的吊板上 ≈ 13px。</p>
     */
    public static final float NAME_MIN_SCALE = 0.45f;

    /** 45° 斜排时的投影系数。 */
    private static final double DIAG = 1.0 / Math.sqrt(2.0);

    /** 相邻站名之间允许占用的站距比例。 */
    private static final float NAME_GAP = 0.92f;

    /** 实际选中的字体名（日志 / 报告用）。 */
    public static volatile String lastFontName = "?";

    // ---- 上一次绘制用到的像素尺寸（日志 / 报告用；不影响绘制） ----
    public static volatile int lastBandH;
    public static volatile int lastLineW;
    public static volatile int lastCircle;
    public static volatile int lastCurrent;
    public static volatile int lastNameSize;
    /** 本次真正画出来的换乘站数量（0 = 全线没有换乘站，和以前完全一样）。 */
    public static volatile int lastInterchangeCount;

    /** 换乘标记的实测像素尺寸（日志 / 报告用）。 */
    public static volatile int lastSwapHead;
    public static volatile int lastSwapPlate;
    /** 线路牌高度（v5 报告用）。 */
    public static volatile int lastSwapPlateH;
    /** v10：本次整张图“多换乘一排”一共画了几个标（0 = 没有多换乘站）。 */
    public static volatile int lastBadgeCount;
    /** v10：本次最紧的那一排缩到多少（1.00 = 一次都没缩；{@link #BADGE_MIN_SCALE} = 缩到下限）。 */
    public static volatile float lastBadgeScale = 1f;
    /** v10：本次从尾部截掉的总数（0 = 全画下了）。 */
    public static volatile int lastBadgeDropped;
    /**
     * v11：画面最右那一站（终点站 / 镜像后的起点站）站名的锚点 x。
     *
     * <ul>
     *   <li><b>45° 镜像排版</b>（{@link #lastNameMirrored}）：= <b>圆心</b>，站名末端压在自己的圆圈上；</li>
     *   <li><b>正排（namesBelow）</b>：= 站名<b>中心</b>（正常就等于圆心，夹边时会被推回来）；</li>
     *   <li><b>45° 未镜像</b>：= 文字起点（等于圆心；被右边夹过就是夹回来的那个 x）。</li>
     * </ul>
     *
     * <p>0 = 这次没画站名。</p>
     */
    public static volatile int lastNameAnchorX;
    /** v11：终点站站名这次是不是走了「往左上」的镜像排版（45° 斜排、右侧放不下时）。 */
    public static volatile boolean lastNameMirrored;
    /** 终点站圆环直径 / 终点箭头长度（日志 / 报告用）。 */
    public static volatile int lastTerminalRing;
    public static volatile int lastTerminalArrow;
    /** v5：本次有多少站名因为太长被单独缩字 / 裁字（报告用）。 */
    public static volatile int lastNameShrunk;
    public static volatile int lastNameClipped;

    /** 站名正排时，非当前站用的 65% 灰。 */
    public static final int NAME_GREY = 0x9E9E9E;

    /** 深色文字（近黑：小字号下纯黑发毛）。 */
    public static final int TEXT_DARK = 0x1A1A1A;

    /** 英文行稍微浅一点，和中文区分开（实拍里英文也偏深，这里只降一点）。 */
    public static final int TEXT_EN = 0x3A3A3A;

    // ---- 已过站 / 当前站（v6，用户实拍指定的固定颜色，<b>不跟线路色</b>）----

    /** 已过站（列车已经经过的站）的圆环：<b>固定灰</b>。 */
    public static final int PASSED_GREY = 0xB2B2B2;

    /** 已经过的区段（当前站之前那段主线）：<b>固定灰</b>，比圆环略深一档。 */
    public static final int PASSED_SEGMENT_GREY = 0xA7A8A8;

    /** 已过站的站名：灰（白底上比环深一档，否则小字号读不清）。 */
    public static final int PASSED_NAME_GREY = 0x9E9E9E;

    /**
     * 当前站的颜色：<b>固定正红</b>（用户指定的厦门实拍红）。
     *
     * <p>当前站的实心圆、站名文字全部用这一个常量 ——
     * <b>不跟线路色走</b>（4 号线红、1 号线绿，当前站都是这一块红）。改一处即可整体换色。</p>
     */
    public static final int CURRENT_FILL = 0xE60012;

    private static volatile Font cjkFont;

    private XiamenStripPainter() {
    }

    /** 亮度 &gt; 128 算亮色（与 LCD 资源包 {@code lib/color_util.js#isLightColor} 同一阈值）。 */
    public static boolean isLight(final int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b > 128.0;
    }

    /** 画在线路色底上的文字颜色（{@code 0xFF000000} / {@code 0xFFFFFFFF}）。 */
    public static int contrastText(final int rgb) {
        return isLight(rgb) ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** 白底上的普通站名：深色线路用线路色，浅色线路用近黑。 */
    public static int onWhiteText(final int rgb) {
        return isLight(rgb) ? TEXT_DARK : (0xFF000000 | (rgb & 0xFFFFFF));
    }

    /**
     * 白底上的当前站站名：<b>固定红</b>（{@link #CURRENT_FILL}）。
     *
     * <p>v6 起当前站的颜色不再跟线路色走（用户实拍指定：圆环、填充、站名都是同一块红），
     * 所以 {@code rgb} 参数已不再参与计算，只为保持调用方签名不变。</p>
     */
    public static int currentNameText(final int rgb) {
        return CURRENT_FILL;
    }

    /** 颜色按比例加深（保持 A=255）。 */
    public static int darken(final int rgb, final double factor) {
        final int r = (int) Math.round(((rgb >> 16) & 0xFF) * factor);
        final int g = (int) Math.round(((rgb >> 8) & 0xFF) * factor);
        final int b = (int) Math.round((rgb & 0xFF) * factor);
        return 0xFF000000
                | (Math.min(255, Math.max(0, r)) << 16)
                | (Math.min(255, Math.max(0, g)) << 8)
                | Math.min(255, Math.max(0, b));
    }

    /** 一张条带要画的全部内容（与 MC / MTR 无关的纯数据）。 */
    public static final class Model {
        /** 站名，按行车方向排列（已去掉 “|English” 部分）。 */
        public String[] names;
        /** 站名英文（与 {@link #names} 一一对应，可空）。 */
        public String[] namesEn;
        /** 当前站在 {@link #names} 里的下标。 */
        public int currentIndex;
        /** 线路色 {@code 0xRRGGBB}。 */
        public int lineRgb;
        /** 线路号（v4 起不再画左侧号码牌，这里保留字段只做数据透传 / 日志）。 */
        public String lineNumber;
        /**
         * 刷子方向 = MTR 的 {@code ARROW_DIRECTION} 原值（{@code 0 / 1 / 2 / 3}）。
         *
         * <p><b>0 = 条带从左往右读（终点在右）；1 / 2 / 3 = 整条镜像（终点在左）。</b>
         * 这是<b>唯一</b>的镜像输入 —— 站位、站名、换乘杆、主线、<b>终点三角</b>全部由它推出来
         * （{@code paint} 里的局部变量 {@code flip}），所以不可能出现“条带翻了、箭头没翻”。</p>
         *
         * <p>v7 之前这里叫 {@code flip}，值直接来自 MTR 传进
         * {@code generateRouteMap} 的那个 {@code flip} —— 而 MTR 只在方向 2 时才传 true
         * （{@code RenderRouteBase#render} 的 {@code arrowDirection == 2}），
         * 于是 1 / 3 两个形态条带不动，终点三角看上去就“停在原来那一端”。</p>
         */
        public int direction;
        /** true = 站名正排到线下方，false = 45° 斜排到线上方（默认，实拍版式）。 */
        public boolean namesBelow;
        /** 面板宽/高。 */
        public float aspectRatio;
        /** 换乘站（可空）：命中的站点改画追逐箭头环 + 线路牌。 */
        public Interchange[] interchanges;
    }

    /**
     * 一个换乘站：<b>站序下标 + 换乘线路的颜色 / 编号</b>。
     * 数据来自 MTR 的 {@code PlatformRouteDetails.StationDetails#interchangeRoutes}（见
     * {@code XiamenRouteStrip}），所以颜色和编号都跟着存档里那条线路走，不写死。
     */
    public static final class Interchange {
        /** 在 {@code names[]} 里的下标（flip 之前的“数据顺序”）。 */
        public int stationIndex;
        /** 换乘线路色 {@code 0xRRGGBB}。 */
        public int rgb;
        /** 线路号：{@code 4}（可能为空串 —— 那时小牌只写线路名）。 */
        public String number;
        /** 换乘线路中文短名：{@code 4号线}（可能为空串）。 */
        public String name;

        public Interchange() {
        }

        public Interchange(final int stationIndex, final int rgb, final String number, final String name) {
            this.stationIndex = stationIndex;
            this.rgb = rgb;
            this.number = number == null ? "" : number;
            this.name = name == null ? "" : name;
        }
    }

    /**
     * 一排换乘小牌的排版结果（<b>纯数据</b>，不含 AWT / MC 类型 —— 离线脚本可以直接断言字段）。
     *
     * @see #fitBadgeRow(float[], float, float)
     */
    public static final class BadgeFit {
        /** 字号 / 内边距 / 间距的缩放系数，永远在 {@code [BADGE_MIN_SCALE, 1]} 之间。 */
        public final float scale;
        /** 从<b>头部</b>开始画几个（1..输入个数）；后面的从尾部截断，不画。 */
        public final int count;
        /** 缩放后每个标的宽度（只填前 {@link #count} 个；含左右内边距）。 */
        public final float[] widths;
        /** 缩放后相邻标之间的间距。 */
        public final float gap;
        /** 缩放后整排总宽（含间距）。 */
        public final float total;
        /** {@code true} = 只剩 1 个、缩到 55% 也放不下（调用方仍画那 1 个，但要收进面板）。 */
        public final boolean overflow;

        BadgeFit(final float scale, final int count, final float[] widths, final float gap, final float total,
                 final boolean overflow) {
            this.scale = scale;
            this.count = count;
            this.widths = widths;
            this.gap = gap;
            this.total = total;
            this.overflow = overflow;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "BadgeFit{count=%d/%d, scale=%.3f, total=%.1f, gap=%.1f%s}",
                    count, widths.length, scale, total, gap, overflow ? ", OVERFLOW" : "");
        }
    }

    /**
     * 多换乘一排的<b>排版纯函数</b>（离线可断言：不碰 Graphics2D、不碰 MC）。
     *
     * <h2>规则（用户指定，源自 LCD {@code drawTransBadges} / {@code xmlcdTransBadgeMeasure}）</h2>
     * <ol>
     *   <li>每个标的宽度 = <b>文字宽 + 2 x 内边距</b>，内边距 = 标高 x {@link #BADGE_PAD_RATIO}；</li>
     *   <li>一排总宽（含间距）= 各标宽之和 + 间距 x (个数 - 1)；比可用宽还宽 →
     *       缩放系数 = {@code 可用宽 / 总宽}，<b>下限 {@link #BADGE_MIN_SCALE}</b>（字号 / 内边距 / 间距一起缩）；</li>
     *   <li>缩到下限仍放不下 → <b>从尾部截断</b>，只留前面放得下的，<b>至少留 1 个</b>；</li>
     *   <li>截断后腾出来的宽度可以再把字放大（但不超过 100%）—— 所以“画的个数”一定取
     *       <b>能让缩放系数 &gt;= 下限</b> 的最大那个前缀。</li>
     * </ol>
     *
     * @param textWidths 各标的<b>文字</b>宽（按满缩字号量出来的像素，不含内边距；索引 = 画出来的顺序）
     * @param available  本站可用宽（调用方传 {@code 站距 x BADGE_AVAIL_RATIO}；&lt;= 0 = 一格都放不下）
     * @param baseHeight 标的<b>基础</b>高度（满缩时的牌高），内边距 / 间距都由它推
     * @return 缩放系数 + 要画的个数 + 缩放后每块宽度 / 间距 / 总宽
     */
    public static BadgeFit fitBadgeRow(final float[] textWidths, final float available, final float baseHeight) {
        final int n = textWidths == null ? 0 : textWidths.length;
        if (n <= 0) {
            return new BadgeFit(1f, 0, new float[0], 0f, 0f, false);
        }
        final float h = Math.max(1f, baseHeight);
        final float pad = h * BADGE_PAD_RATIO;
        final float gap = h * BADGE_GAP_RATIO;
        final float avail = Math.max(0f, available);
        // 满缩时每个标的宽度（文字宽 + 左右内边距）。
        final float[] natural = new float[n];
        for (int i = 0; i < n; i++) {
            natural[i] = Math.max(1f, textWidths[i]) + pad * 2f;
        }
        // 从“全画”往下试：第一个能让缩放系数 >= 下限的前缀就是答案（截断从尾部丢）。
        for (int keep = n; keep >= 1; keep--) {
            float raw = 0f;
            for (int i = 0; i < keep; i++) {
                raw += natural[i];
            }
            raw += gap * (keep - 1);
            final float fitScale = raw > 0f ? avail / raw : 1f;
            if (fitScale < BADGE_MIN_SCALE && keep > 1) {
                continue;                        // 还放不下 → 丢尾部一个再试
            }
            // 只剩 1 个时无论放不放得下都要画：缩到下限为止（overflow 标记给调用方收边用）。
            final float scale = Math.max(BADGE_MIN_SCALE, Math.min(1f, fitScale));
            final float[] widths = new float[keep];
            float total = 0f;
            for (int i = 0; i < keep; i++) {
                widths[i] = natural[i] * scale;
                total += widths[i];
            }
            final float scaledGap = gap * scale;
            total += scaledGap * (keep - 1);
            return new BadgeFit(scale, keep, widths, scaledGap, total, total > avail + 0.5f);
        }
        // 理论上到不了这里（keep == 1 一定 return）。
        return new BadgeFit(BADGE_MIN_SCALE, 1, new float[]{natural[0] * BADGE_MIN_SCALE},
                gap * BADGE_MIN_SCALE, natural[0] * BADGE_MIN_SCALE, true);
    }

    /** 画一张（返回 ARGB 的 BufferedImage；比例或数据不合法时返回 null）。 */
    public static BufferedImage paint(final Model model) {
        if (model == null || model.names == null || model.names.length < 2 || !(model.aspectRatio > 0.05f)) {
            return null;
        }
        int height = IMG_HEIGHT;
        int width = Math.max(height, Math.round(height * model.aspectRatio));
        if (width > MAX_WIDTH) {
            height = Math.max(48, Math.round(height * (float) MAX_WIDTH / width));
            width = MAX_WIDTH;
        }
        if (width < 16 || height < 16) {
            return null;
        }

        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            // v10：多换乘一排的实测值（日志 / 报告用），每次重画都从“没有多换乘”开始。
            lastBadgeCount = 0;
            lastBadgeScale = 1f;
            lastBadgeDropped = 0;
            lastNameAnchorX = 0;
            lastNameMirrored = false;

            final int count = model.names.length;
            // v7：唯一的镜像规则 —— 方向 1 / 2 / 3 都是“列车朝左”，整条图一起镜像（含终点三角）。
            final boolean flip = model.direction != 0;
            final int current = Math.max(0, Math.min(count - 1, model.currentIndex));
            final int lineRgb = model.lineRgb & 0xFFFFFF;

            // flip（行进方向朝左）时整条线要“镜像读”：列 0 显示的是数据里的最后一站。
            // 这样终点箭头、当前站、换乘牌、站名永远落在同一个站上（v3 修正：v2 只镜像了
            // 当前站和换乘牌，站名没镜像，三者会错位，终点也会指错站）。
            final String[] names = new String[count];
            final String[] namesEn = new String[count];
            // v10：一个站可以有多条换乘线路 —— 每个站是一组（0 = 不是换乘站，1 = 老的两行线路牌，
            //      2+ = 一排小牌，见 {@link #fitBadgeRow}）。
            final Interchange[][] swaps = new Interchange[count][];
            // v6：已过站 = “数据顺序里排在当前站之前”的站（列车已经开过的那些）。
            // 灰化必须按数据顺序判定再随 flip 一起镜像，否则 flip 时灰的是未来的站。
            final boolean[] passed = new boolean[count];
            for (int i = 0; i < count; i++) {
                final int d = flip ? count - 1 - i : i;
                names[i] = model.names[d];
                namesEn[i] = model.namesEn != null && d < model.namesEn.length ? model.namesEn[d] : "";
                swaps[i] = dataInterchanges(model, d);
                passed[i] = d < current;
            }
            final int currentIndex = flip ? count - 1 - current : current;

            // ---- 白底：铺满整张图（面板尺寸不变；v4 只缩内容，不缩背景）----
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            // ---- 内容区：所有线条 / 圆环 / 字号 / 间距只占高度的 CONTENT_RATIO，上下居中 ----
            final int bandH = Math.max(8, Math.round(height * CONTENT_RATIO));
            final int bandTop = (height - bandH) / 2;
            final int bandBottom = bandTop + bandH;
            final int lineW = Math.max(3, Math.round(bandH * LINE_RATIO));
            final boolean namesBelow = model.namesBelow;
            // 45° 站名在线上方 → 线压到内容区下部；正排站名在下方 → 线抬高。
            final int lineY = bandTop + Math.round(bandH * (namesBelow ? 0.30f : 0.64f));

            // ---- 站点几何（v5.1：左右各留 PAD_X 内边距，首/末站不再贴边）----
            final int padX = Math.max(PAD_X_MIN, Math.round(width * PAD_X_RATIO));
            // 极端窄图兜底：左右内边距加起来不能超过宽度的 2/3，否则站距被挤成 0。
            final int padLeft = Math.min(padX, Math.max(2, width / 3));
            final int padRight = padLeft;
            final int rightEdge = Math.max(padLeft + 2, width - padRight);
            // v5.1：终点箭头改成“箭杆（= 主线）停在箭头底边 + 宽三角顶住圆环”——
            // 主线不再画到站点中心，否则箭尖被同色的主线埋掉（实机截图里就是这个现象）。
            final int termArrowLen = Math.max(4, Math.round(lineW * TERM_ARROW_LEN));
            final int termArrowHalf = Math.max(3, Math.round(lineW * TERM_ARROW_HALF));
            final float stepGuess = (float) Math.max(1, rightEdge - padLeft) / (count - 1);
            final float circle = Math.max(bandH * 0.09f,
                    Math.min(bandH * CIRCLE_RATIO, stepGuess * 0.62f));
            final int radius = Math.round(circle / 2f);
            final int xFirst = padLeft + radius;
            final int xLast = Math.max(xFirst + 1, rightEdge - radius);
            final float step = (float) (xLast - xFirst) / (count - 1);
            final int[] xs = new int[count];
            for (int i = 0; i < count; i++) {
                xs[i] = Math.round(xFirst + step * i);
            }
            final int contentX = padLeft;

            // ---- 每个站标记的“可见外缘半径”：站名让位 / 终点三角 / 主线两端都按它算 ----
            final int ixRingD = Math.max(14, Math.round(bandH * IX_RING_RATIO));
            // v7：当前站 = 一个红色实心圆，直径和普通站圆环的「可见外缘」完全一样（不放大、不套环）。
            final float curD = circle + Math.max(1.2f, circle * RING_RATIO);
            // v9：当前站同时是换乘站时，标记仍是完整的追逐箭头环（只是上半圈变红，见 drawInterchange），
            //     所以它的可见半径和普通换乘站一样 —— 不再按 curD 算。
            final float[] visR = new float[count];
            for (int i = 0; i < count; i++) {
                if (swaps[i] != null) {
                    visR[i] = Math.max(circle, ixRingD) / 2f;
                } else {
                    // 普通站是空心环、描边居中描 → 外缘 = 半径 + 半个描边厚度；
                    // 当前站是实心圆、没有描边 → 外缘 = 半径。两者都正好等于 curD / 2。
                    visR[i] = curD / 2f;
                }
            }
            float maxVisR = 0f;
            for (final float r : visR) {
                maxVisR = Math.max(maxVisR, r);
            }
            // v9：主线两端对齐“端头标记真正画出来的外缘”时用的半径。
            //     visR 是“设计外缘”，普通当前站实际只填了一个内缩一圈的红色实心圆
            //     （见 {@link #drawCurrentDot} / {@link #currentDotDiameter}），比 visR 小一圈 ——
            //     拿 visR 当端头锚点，灰线就会从红圆外侧露出一小截（用户实机反馈的那根“灰刺”）。
            final float[] markR = new float[count];
            for (int i = 0; i < count; i++) {
                markR[i] = swaps[i] == null && i == currentIndex
                        ? currentDotDiameter(curD) / 2f
                        : visR[i];
            }

            // ---- 终点箭头几何（先算出来，主线要停在箭头的底边上）----
            final int terminalIndex = flip ? 0 : count - 1;
            final int termX = xs[terminalIndex];
            // 箭尖顶住终点圆环的**外缘**（visR 已含描边），再往环里压 TERM_ARROW_TIP_OVERLAP px：
            // 既没有白缝，又因为箭头是最后画的一笔 —— 压住的部分也在最上层。
            final int tipInset = Math.max(0, Math.round(visR[terminalIndex]) - TERM_ARROW_TIP_OVERLAP);
            final int tipX = flip ? termX + tipInset : termX - tipInset;
            final int headBaseX = flip ? tipX + termArrowLen : tipX - termArrowLen;

            // ---- 主线（v5.1：从 PAD_X 起、到 宽 - PAD_X 止；终点那头停在箭头底边）----
            // v9：两端改成对齐「端头标记画出来的外缘」—— 起点站那侧 = 视觉首站的 markR，
            //     终点箭头那侧 = headBaseX（箭尖 → 底边这段让给三角，箭尖才不会被同色主线埋掉）。
            //     旧版非箭头端取 padLeft / rightEdge（= 圆心 -/+ 半径）：普通环外边刚好压在它上面，
            //     但当前站红圆更小（drawCurrentDot 内缩一圈），于是“当前站正好落在端头”时会从
            //     红圆旁边露出一小截裸灰线（用户复现的灰刺）。内压 END_JOINT_OVERLAP px 保证
            //     线头一定压在标记底下 —— 不会反过来出现“线没接上圆环”的缝。
            // v6：整条先铺“已过站灰”，再把「当前站 → 终点」这一段用线路色盖回来 ——
            //     于是当前站之前（列车已经开过）的区段是灰的，之后仍是线路色。
            final int lineStartX = Math.max(0, Math.round(xs[0] - markR[0]) + END_JOINT_OVERLAP);
            final int lineEndX = Math.min(width, Math.round(xs[count - 1] + markR[count - 1]) - END_JOINT_OVERLAP);
            final int lineFrom = flip ? headBaseX : lineStartX;
            final int lineTo = flip ? Math.max(headBaseX + 1, lineEndX) : headBaseX;
            g.setColor(new Color(PASSED_SEGMENT_GREY));
            g.fillRect(lineFrom, lineY - lineW / 2, Math.max(1, lineTo - lineFrom), lineW);
            final int curX = xs[currentIndex];
            final int futureA = flip ? lineFrom : Math.min(curX, lineTo);
            final int futureB = flip ? Math.max(curX, lineFrom) : lineTo;
            g.setColor(new Color(lineRgb));
            g.fillRect(futureA, lineY - lineW / 2, Math.max(1, futureB - futureA), lineW);

            // ---- 站点标记（只画环；终点箭头放到最后，保证不被圆环盖住）----
            // v5：内容区缩到 60% 后，线路牌可以借用下方多出来的白边（最多借一半），
            // 这样牌高才能真的 +14%，而不会被“内容区底边”裁掉。
            final int marginV = Math.max(0, (height - bandH) / 2);
            final int plateFloorY = bandBottom + Math.round(marginV * 0.55f)
                    - Math.max(1, Math.round(height * 0.010f));
            // 站名可用的纵向范围：顶部只留 NAME_AREA_TOP_RATIO 的白边（不再受内容区顶边限制）。
            final int nameTop = Math.max(2, Math.round(height * NAME_AREA_TOP_RATIO));
            final int nameCap = Math.max(9, Math.round(height * NAME_MAX_RATIO));
            int swapDrawn = 0;
            int plateBottomMax = 0;
            for (int i = 0; i < count; i++) {
                if (i == currentIndex) {
                    continue;
                }
                final Interchange[] swap = swaps[i];
                if (swap != null) {
                    final int[] box = new int[2];
                    // v10：一个站多条换乘 → 一排小牌（一字排开 / 缩 / 尾部截断），一条 → 老的两行线路牌。
                    if (drawSwap(g, xs[i], lineY, lineRgb, swap, bandH, step, width, plateFloorY, box,
                            passed[i], false)) {
                        swapDrawn++;
                        plateBottomMax = Math.max(plateBottomMax, box[1]);
                    }
                } else {
                    // v4：终点站也是普通尺寸的环，只是多一个三角箭头。
                    // v6：已过站用固定灰（圆环），未到的站用线路色。
                    drawDot(g, xs[i], lineY, circle, passed[i] ? PASSED_GREY : lineRgb, passed[i]);
                }
            }
            final Interchange[] currentSwap = swaps[currentIndex];
            if (currentSwap != null) {
                final int[] box = new int[2];
                // 当前站同时是换乘站（v9）：完整的追逐箭头环，上半圈用当前站红、下半圈是换乘线路色，
                // 环里不再放红实心圆；下方的线路牌照旧画出来。
                if (drawSwap(g, xs[currentIndex], lineY, lineRgb, currentSwap, bandH, step, width,
                        plateFloorY, box, false, true)) {
                    swapDrawn++;
                    plateBottomMax = Math.max(plateBottomMax, box[1]);
                }
            } else {
                drawCurrentDot(g, xs[currentIndex], lineY, curD);
            }
            lastInterchangeCount = swapDrawn;

            // 正排站名（namesBelow）时要把换乘牌的位置让出来，否则站名会压在牌子上。
            final float belowExtra = namesBelow && plateBottomMax > 0
                    ? Math.max(0f, plateBottomMax - (lineY + maxVisR)) : 0f;
            final float nameSize = drawNames(g, names, namesEn, xs, visR, currentIndex, passed, lineRgb, nameTop,
                    bandBottom, lineY, step, namesBelow, rightEdge, width, contentX, belowExtra, nameCap);

            // ---- 终点箭头：**整张图的最后一笔**（v5.1）----
            // 顺序：白底 → 主线 → 所有圆环 → 站名 → 箭头。箭头画在最后 = 箭尖永远在最上层，
            // 压在圆环上 1–2px 也是箭头在上，不会被任何东西盖住。
            drawTerminalArrow(g, tipX, headBaseX, lineY, termArrowHalf, lineRgb);

            lastBandH = bandH;
            lastLineW = lineW;
            lastCircle = Math.round(circle);
            lastCurrent = Math.round(curD);
            lastNameSize = Math.round(nameSize);
            lastTerminalRing = Math.round(circle);
            lastTerminalArrow = termArrowLen;
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * 终点箭头（v5.1，实拍样式）：<b>与线路同色的实心宽三角</b>，三角形的底边接主线、箭尖顶住终点圆环。
     *
     * <ul>
     *   <li><b>箭杆 = 主线本身</b>：调用方让主线停在 {@code baseX}，所以这条三角就是“箭头”那一段，
     *       不会和主线重叠成一根粗矩形（v5 的箭尖就是被同色主线埋掉的）；</li>
     *   <li>{@code tipX} 由调用方算好：顶在圆环外缘、并往环里压 {@link #TERM_ARROW_TIP_OVERLAP}px，
     *       且这一步是整张图<b>最后</b>画的 —— 重叠处也是箭头在上、箭尖 100% 露出；</li>
     *   <li>宽三角：总宽 2.3×线宽、长 1.9×线宽（{@link #TERM_ARROW_HALF} / {@link #TERM_ARROW_LEN}）。</li>
     * </ul>
     *
     * @param tipX    箭尖 x（flip 时在圆环右边、否则在左边）
     * @param baseX   三角形底边 x（靠主线那一侧，主线正好画到这里）
     */
    private static void drawTerminalArrow(final Graphics2D g, final int tipX, final int baseX, final int lineY,
                                          final int half, final int rgb) {
        if (half <= 0 || tipX == baseX) {
            return;
        }
        g.setColor(new Color(rgb));
        g.fillPolygon(new int[]{tipX, baseX, baseX}, new int[]{lineY, lineY - half, lineY + half}, 3);
    }

    /**
     * 普通站点：<b>白心空心圆环</b>，环 = 线路色，环宽约直径的 15–18%。
     * 没有实心点、没有内环、没有箭头（按实拍纠正）。
     *
     * <p>v6：{@code grey = true}（已过站）时圆环用固定灰 {@link #PASSED_GREY}，
     * 而且<b>不再按“浅色线路加深”那条规则处理</b>（灰就是灰，不能被压成 #808080）。</p>
     */
    private static void drawDot(final Graphics2D g, final int cx, final int cy, final float size, final int rgb,
                                final boolean grey) {
        ring(g, cx, cy, size, grey ? PASSED_GREY : ringColor(rgb), 0f, 0f);
    }

    /**
     * 当前站（v7）：<b>一个固定红色的实心圆</b>，别的一律不画。
     *
     * <p>v6 是“放大 1.3× 的红环 + 一圈白 + 环内红色实心”，实机上被当成“当前站外围多了一圈
     * 线路色圆环”：用户明确纠正 —— <b>只填红实心</b>。所以这里只剩一个 {@code fillOval}：
     * 直径由调用方给（{@code curD} = 普通站圆环的可见外缘，<b>不放大</b>），
     * 颜色固定 {@link #CURRENT_FILL}（不跟线路色）。</p>
     */
    private static void drawCurrentDot(final Graphics2D g, final int cx, final int cy, final float size) {
        // v8：只在线路色/灰色圆环**内部**填红，内缩一个描边宽度 —— 否则会盖掉圆环本身
        // （用户实机反馈「红圈画太大把线路色描边弄没了」）。尺寸统一走 currentDotDiameter。
        final int fd = currentDotDiameter(size);
        g.setColor(new Color(CURRENT_FILL));
        g.fillOval(cx - fd / 2, cy - fd / 2, fd, fd);
    }

    /**
     * 当前站红色实心圆的<b>实际直径</b>（v8：内缩一个描边宽度；v9 起几何计算也用它）。
     *
     * <p>v9：主线端头锚点要用“真正画出来的半径”，所以外面排版和这里必须是同一份算法 ——
     * 否则“当前站正好落在端头”时，灰线会从红圆旁边露出一小截（用户实机反馈的灰刺）。</p>
     */
    private static int currentDotDiameter(final float size) {
        final int d = Math.max(4, Math.round(size));
        final int inset = Math.max(1, Math.round(d * RING_RATIO));
        return Math.max(2, d - inset * 2);
    }

    /** 圆环颜色：浅色线路（黄 / 浅绿）压深一点，否则白底上看不见；深色线路原样。 */
    private static int ringColor(final int rgb) {
        return isLight(rgb) ? darken(rgb, 0.72) : rgb;
    }

    private static void ring(final Graphics2D g, final int cx, final int cy, final float size, final int rgb,
                             final float innerStroke, final float innerRatio) {
        final int d = Math.max(4, Math.round(size));
        final int left = cx - d / 2;
        final int top = cy - d / 2;
        g.setColor(Color.WHITE);
        g.fillOval(left, top, d, d);
        g.setColor(new Color(rgb));
        g.setStroke(new BasicStroke(Math.max(1.2f, d * RING_RATIO), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.drawOval(left, top, d - 1, d - 1);
        if (innerStroke > 0f && innerRatio > 0f) {
            final float inner = d * innerRatio;
            final int innerLeft = Math.round(cx - inner / 2f);
            final int innerTop = Math.round(cy - inner / 2f);
            g.setStroke(new BasicStroke(Math.max(1f, d * innerStroke), BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_ROUND));
            g.drawOval(innerLeft, innerTop, Math.round(inner) - 1, Math.round(inner) - 1);
        }
        g.setStroke(new BasicStroke(1f));
    }

    /**
     * 数据下标 {@code dataIndex} 的<b>全部</b>换乘线路；不是换乘站就 null。
     *
     * <p>v10：以前只返回第一条（{@code return ic}），一个站挂 3 条换乘线也只画得出 1 条。
     * 现在按 {@code model.interchanges} 的顺序把同站的全部收下来 —— 顺序由
     * {@code XiamenRouteStrip#interchanges} 定（线路色升序，同一存档每次都一样），
     * 尾部截断时丢的永远是最后那条，结果可复现。</p>
     */
    private static Interchange[] dataInterchanges(final Model model, final int dataIndex) {
        if (model.interchanges == null || model.interchanges.length == 0) {
            return null;
        }
        int n = 0;
        for (final Interchange ic : model.interchanges) {
            if (ic != null && ic.stationIndex == dataIndex) {
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        final Interchange[] found = new Interchange[n];
        int k = 0;
        for (final Interchange ic : model.interchanges) {
            if (ic != null && ic.stationIndex == dataIndex) {
                found[k++] = ic;
            }
        }
        return found;
    }

    /**
     * 换乘站（v4 版式）：
     * <ul>
     *   <li><b>追逐箭头环</b>：一个环由两段圆弧组成 —— <b>上段 = 当前线路色</b>、
     *       <b>下段 = 换乘线路色</b>，共圆心 / 共半径 / 共厚度；两段之间<br>
     *       <b>留 60° 的明显空隙</b>（v4：v3 两段几乎连成一片），箭头尖也调小，不再把空隙吃掉；</li>
     *   <li>下半的环底伸出一条<b>换乘线路色的矩形杆</b>，杆端再收成<b>一个尖三角</b>指向线路牌
     *       （v4：v3 是一整条锥形垂尾，看起来像梯形）；</li>
     *   <li>线路牌<b>始终挂在图标正下方、水平居中</b>。</li>
     * </ul>
     *
     * @param box 输出 {牌上沿, 牌下沿}，站名排版要避让
     * @param grey {@code true} = 这是<b>已过站</b>的换乘站：环 / 杆 / 牌子全部改用固定灰
     * @param asCurrent {@code true} = 这一站同时是<b>当前站</b>：<b>环里不填红圆</b>，
     *                  而是把追逐箭头的<b>上半圈换成当前站红</b>（下半仍是换乘线路色）。
     *                  v9 用户实拍确认：当前站与普通换乘站的区别只体现在上半弧的颜色上。
     * @return 真的把牌子也画出来了（false = 连最小号的牌子都放不下，此时只有环 + 杆）
     */
    private static boolean drawInterchange(final Graphics2D g, final int cx, final int lineY, final int lineRgb,
                                           final Interchange swap, final int bandH, final float step,
                                           final int width, final int floorY, final int[] box,
                                           final boolean grey, final boolean asCurrent) {
        final int swapRgb = grey ? PASSED_GREY : (swap.rgb & 0xFFFFFF);
        final int ringD = Math.max(14, Math.round(bandH * IX_RING_RATIO));
        // 图标本体半径：任何情况都是追逐箭头环（v9 起当前站也是完整环，只是上半圈红），
        // 垂尾和线路牌都从“本体的下沿”开始算。
        final float bodyR = ringD / 2f;
        final int gap = Math.max(3, Math.round(bandH * IX_PLATE_GAP_RATIO));

        // ---- 线路牌几何（先算，垂尾要指向它）----
        int plateH = Math.max(10, Math.round(bandH * IX_PLATE_H_RATIO));
        int plateTop = lineY + Math.round(bodyR) + gap;
        if (plateTop + plateH > floorY) {
            plateH = Math.max(10, floorY - plateTop);
        }
        // 始终居中：牌宽先按“到图片最近边界的 2 倍”夹住，再按站距夹住。
        final int maxByEdge = Math.max(18, 2 * (Math.min(cx, width - cx) - 2));
        final int maxByStep = Math.max(18, Math.round(step * 1.50f));
        final int maxW = Math.max(18, Math.min(maxByEdge, maxByStep));

        final String cn = plateCjk(swap);
        final String en = plateEn(swap);
        float cjkSize = 0f;
        float enSize = 0f;
        int cjkW = 0;
        int enW = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            cjkSize = plateH * (IX_PLATE_TEXT_RATIO - 0.05f * attempt);
            enSize = cjkSize * 0.62f;
            final Font cjkFont = font().deriveFont(Font.BOLD, cjkSize);
            final Font enFont = font().deriveFont(Font.BOLD, enSize);
            cjkW = cn.isEmpty() ? 0 : g.getFontMetrics(cjkFont).stringWidth(cn);
            enW = en.isEmpty() ? 0 : g.getFontMetrics(enFont).stringWidth(en);
            final int need = Math.max(cjkW, enW) + Math.round(plateH * IX_PLATE_PAD_RATIO * 2f);
            if (need <= maxW || cjkSize <= 7f) {
                break;
            }
        }
        if (cn.isEmpty() && en.isEmpty()) {
            return false;
        }
        final int padX = Math.max(3, Math.round(plateH * IX_PLATE_PAD_RATIO));
        final int plateW = Math.max(18, Math.min(maxW, Math.max(cjkW, enW) + padX * 2));
        final int px = cx - plateW / 2;   // 永远以图标中心为准，不为了塞进画面而左右平移

        // ---- 追逐箭头环 + 垂尾：先下半（换乘线路色），再上半（当前线路色 / 当前站红），共圆心共半径 ----
        // v9：当前站同时是换乘站时，上半圈改用当前站的红（{@link #CURRENT_FILL}），下半仍是换乘线路色，
        //     两段之间的空隙照旧 —— 用户实拍确认「当前站与普通换乘站的区别体现在箭头上」，
        //     不再画环内红实心圆。
        // v10：环 + 垂尾抽成 {@link #drawSwapBody}，多换乘那一排复用同一份画法（单条换乘像素级不变）。
        final int ringRgb = asCurrent ? CURRENT_FILL : (grey ? PASSED_GREY : ringColor(lineRgb));
        drawSwapBody(g, cx, lineY, ringRgb, swapRgb, ringD, plateTop - 2f);

        // ---- 线路牌：换乘线路色圆角矩形 + 两行居中文字（中文 / Line N）----
        if (plateW <= 0 || plateH <= 0) {
            return false;
        }
        g.setColor(new Color(swapRgb));
        g.fill(new RoundRectangle2D.Float(px, plateTop, plateW, plateH,
                plateH * 0.34f, plateH * 0.34f));
        g.setColor(new Color(contrastText(swapRgb), true));
        if (!cn.isEmpty()) {
            final Font cjkFont = font().deriveFont(Font.BOLD, cjkSize);
            g.setFont(cjkFont);
            final FontMetrics fm = g.getFontMetrics(cjkFont);
            final String text = cjkW > plateW - 2 * padX ? fitText(g, cjkFont, cn, plateW - 2 * padX) : cn;
            final int tw = g.getFontMetrics(cjkFont).stringWidth(text);
            final float baseline = plateTop + plateH * (en.isEmpty() ? 0.5f : 0.44f)
                    + (fm.getAscent() - fm.getDescent()) / 2f - plateH * 0.04f;
            g.drawString(text, px + (plateW - tw) / 2f, baseline);
        }
        if (!en.isEmpty()) {
            final float size = cn.isEmpty() ? plateH * 0.50f : enSize;
            final Font enFont = font().deriveFont(Font.BOLD, size);
            g.setFont(enFont);
            final FontMetrics fm = g.getFontMetrics(enFont);
            final String text = enW > plateW - 2 * padX ? fitText(g, enFont, en, plateW - 2 * padX) : en;
            final int tw = g.getFontMetrics(enFont).stringWidth(text);
            g.drawString(text, px + (plateW - tw) / 2f, plateTop + plateH * 0.86f
                    + (fm.getAscent() - fm.getDescent()) / 2f - plateH * 0.06f);
        }

        box[0] = plateTop;
        box[1] = plateTop + plateH;
        lastSwapHead = ringD;
        lastSwapPlate = plateW;
        lastSwapPlateH = plateH;
        return true;
    }

    /**
     * 一站的换乘标记分派：<b>1 条</b>换乘 → 老的两行线路牌（{@link #drawInterchange}，版式一个字不动）；
     * <b>2 条以上</b> → 一排单行小牌（{@link #drawInterchangeRow}）。
     *
     * @param box 输出 {牌上沿, 牌下沿}，站名排版要避让
     */
    private static boolean drawSwap(final Graphics2D g, final int cx, final int lineY, final int lineRgb,
                                    final Interchange[] swaps, final int bandH, final float step,
                                    final int width, final int floorY, final int[] box,
                                    final boolean grey, final boolean asCurrent) {
        if (swaps == null || swaps.length == 0) {
            return false;
        }
        if (swaps.length == 1) {
            return drawInterchange(g, cx, lineY, lineRgb, swaps[0], bandH, step, width, floorY, box, grey, asCurrent);
        }
        return drawInterchangeRow(g, cx, lineY, lineRgb, swaps, bandH, step, width, floorY, box, grey, asCurrent);
    }

    /**
     * 追逐箭头环 + 垂尾（v10 从 {@link #drawInterchange} 里原样抽出，单条换乘那条路像素级不变）。
     *
     * <p>画序：环内白底（把线路主带挡在环外，主带才会在环边“断”开）→ 下半弧（换乘线路色）→
     * 上半弧（当前线路色 / 当前站红）→ 垂尾（矩形杆 + 尖三角）指向 {@code tipY}。</p>
     *
     * @param ringRgb 上半弧颜色
     * @param swapRgb 下半弧 / 垂尾颜色（已过站调用方传灰）
     * @param tipY    垂尾尖端的 y（单条换乘 = 线路牌上沿 - 2，一排小牌 = 那一排的上沿 - 2）
     */
    private static void drawSwapBody(final Graphics2D g, final int cx, final int lineY, final int ringRgb,
                                     final int swapRgb, final int ringD, final float tipY) {
        final float rOut = ringD / 2f;
        final float thick = ringD * IX_RING_THICK;
        final float rIn = Math.max(1f, rOut - thick);
        final float cy = lineY;

        // 环内底色：两段弧在 0° / 180°（环左右两侧）各留一个缺口，主线正好从缺口横穿过去；
        // 只有环身有不透明底色时，主带才会在环边上断开（v8.1）。
        g.setColor(Color.WHITE);
        g.fillOval(Math.round(cx - rOut), Math.round(cy - rOut), ringD, ringD);

        g.setColor(new Color(swapRgb));
        g.fill(annularSector(cx, cy, rOut, rIn, IX_ARC_BOTTOM_FROM, IX_ARC_BOTTOM_TO));
        arrowHead(g, cx, cy, rOut, rIn, IX_ARC_BOTTOM_TO, swapRgb);

        // 上半：30° → 150°，末端箭头尖朝左下（当前线路色；当前站 = 当前站红）
        g.setColor(new Color(ringRgb));
        g.fill(annularSector(cx, cy, rOut, rIn, IX_ARC_TOP_FROM, IX_ARC_TOP_TO));
        arrowHead(g, cx, cy, rOut, rIn, IX_ARC_TOP_TO, ringRgb);

        // ---- 垂尾（v4）：环底伸出一条矩形杆，杆端再收成一个尖三角 ----
        final float tailW = ringD * IX_TAIL_W;
        // 起点贴住环底下沿再往内压一点，接缝处不会有白缝。
        final float shaftTop = cy + rOut - thick * 0.45f;
        // 尖三角的高度按「环底到牌」之间真正剩下的空间取（不然空间不够时又被挤成一根光杆）。
        final float tailHead = Math.min(ringD * IX_TAIL_HEAD, Math.max(3f, (tipY - shaftTop) * 0.42f));
        final float headBaseY = tipY - tailHead;
        if (headBaseY > shaftTop + 1f) {
            g.setColor(new Color(swapRgb));
            // 矩形杆
            g.fillRect(Math.round(cx - tailW / 2f), Math.round(shaftTop),
                    Math.max(1, Math.round(tailW)), Math.max(1, Math.round(headBaseY - shaftTop)));
            // 杆端尖三角（底边与杆同宽，尖朝下指向线路牌）
            g.fillPolygon(
                    new int[]{Math.round(cx - tailW / 2f), Math.round(cx + tailW / 2f), cx},
                    new int[]{Math.round(headBaseY), Math.round(headBaseY), Math.round(tipY)}, 3);
        } else if (tipY > shaftTop) {
            g.setColor(new Color(swapRgb));
            g.fillRect(Math.round(cx - tailW / 2f), Math.round(shaftTop),
                    Math.max(1, Math.round(tailW)), Math.max(1, Math.round(tipY - shaftTop)));
        }
    }

    /**
     * 一个站有<b>多条</b>换乘线路（v10）：追逐箭头环照旧（下半用<b>第一条</b>换乘线的颜色），
     * 下方的两行线路牌换成一排<b>单行小牌</b>，一字排开、以站点中心为准居中。
     *
     * <h2>放不下时怎么办（用户指定，规则同 LCD {@code drawTransBadges}）</h2>
     * <ol>
     *   <li>先整体缩：{@code 缩放系数 = 可用宽 / 总宽}，下限 {@link #BADGE_MIN_SCALE}（55%），
     *       字号 / 内边距 / 间距一起缩 —— 排版全在纯函数 {@link #fitBadgeRow} 里算；</li>
     *   <li>缩到 55% 仍放不下 → <b>从尾部截断</b>，只画前面放得下的，<b>至少留 1 个</b>；</li>
     *   <li>可用宽 = 本格<b>站距 x {@link #BADGE_AVAIL_RATIO}</b>（92%）—— 一排牌永远不会伸进
     *       相邻站那一格，所以既不压旁边的站名，也不越出面板（最后再按面板左右边夹一次）。</li>
     * </ol>
     *
     * <p>已过站：小牌统一用 {@link #PASSED_NAME_GREY}（<b>和站名同一个灰</b>），不用线路色；
     * 环 / 垂尾沿用 {@link #PASSED_GREY}（和单条换乘的老画法一致）。</p>
     *
     * @param box 输出 {这一排的上沿, 下沿}，站名排版要避让
     * @return 真的画出这一排了（标签全空的极端情况返回 false）
     */
    private static boolean drawInterchangeRow(final Graphics2D g, final int cx, final int lineY,
                                              final int lineRgb, final Interchange[] swaps, final int bandH,
                                              final float step, final int width, final int floorY,
                                              final int[] box, final boolean grey, final boolean asCurrent) {
        final int ringD = Math.max(14, Math.round(bandH * IX_RING_RATIO));
        final float bodyR = ringD / 2f;
        final int gap = Math.max(3, Math.round(bandH * IX_PLATE_GAP_RATIO));
        // 一排小牌比两行线路牌矮一档（只有一行字），但上沿仍在同一条基线上 —— 其它元素位置不变。
        final int top = lineY + Math.round(bodyR) + gap;
        int badgeH = Math.max(9, Math.round(bandH * IX_PLATE_H_RATIO * BADGE_H_RATIO));
        if (top + badgeH > floorY) {
            badgeH = Math.max(9, floorY - top);
        }
        final float baseText = Math.max(5f, badgeH * BADGE_TEXT_RATIO);
        final Font baseFont = font().deriveFont(Font.BOLD, baseText);

        // 每个标的文字（单行：优先 “N号线”，没有线路号就用线路名）和满缩时的文字宽。
        final String[] labels = new String[swaps.length];
        final float[] textW = new float[swaps.length];
        int nonEmpty = 0;
        for (int i = 0; i < swaps.length; i++) {
            String label = plateCjk(swaps[i]);
            if (label.isEmpty()) {
                label = plateEn(swaps[i]);
            }
            labels[i] = label;
            if (!label.isEmpty()) {
                nonEmpty++;
                textW[i] = g.getFontMetrics(baseFont).stringWidth(label);
            } else {
                textW[i] = 1f;
            }
        }
        if (nonEmpty == 0) {
            return false;
        }

        // 可用宽 = 本格站距的 92%；排版（缩放 / 尾部截断）交给纯函数。
        final float avail = Math.max(8f, step * BADGE_AVAIL_RATIO);
        final BadgeFit fit = fitBadgeRow(textW, avail, badgeH);

        // 环先画：下半圈用第一条换乘线的颜色（已过站 = 灰），垂尾指向这一排的上沿。
        final int firstRgb = grey ? PASSED_GREY : (swaps[0].rgb & 0xFFFFFF);
        final int ringRgb = asCurrent ? CURRENT_FILL : (grey ? PASSED_GREY : ringColor(lineRgb));
        drawSwapBody(g, cx, lineY, ringRgb, firstRgb, ringD, top - 2f);

        // 一排：以站点中心居中，再按面板左右边夹一次（端头站才用得上，绝不越出面板）。
        final int totalW = Math.max(1, Math.round(fit.total));
        int x = Math.round(cx - fit.total / 2f);
        if (x < 1) {
            x = 1;
        }
        if (x + totalW > width - 1) {
            x = Math.max(1, width - 1 - totalW);
        }
        for (int i = 0; i < fit.count; i++) {
            final int w = Math.max(4, Math.round(fit.widths[i]));
            final int rgb = grey ? PASSED_NAME_GREY : (swaps[i].rgb & 0xFFFFFF);
            g.setColor(new Color(rgb));
            g.fill(new RoundRectangle2D.Float(x, top, w, badgeH, badgeH * 0.34f, badgeH * 0.34f));
            final String label = labels[i];
            if (!label.isEmpty()) {
                final int inner = Math.max(2, w - Math.round(badgeH * BADGE_PAD_RATIO * fit.scale * 2f));
                float size = Math.max(4f, baseText * fit.scale);
                Font f = baseFont.deriveFont(size);
                // 缩到 55% 之后仍差一点点（舍入 / 字形 bearing）→ 在自己这块牌里再收一收。
                for (int k = 0; k < 5 && size > 4f && g.getFontMetrics(f).stringWidth(label) > inner; k++) {
                    size *= 0.88f;
                    f = baseFont.deriveFont(size);
                }
                g.setFont(f);
                g.setColor(new Color(contrastText(rgb), true));
                final String text = label;
                final int tw = g.getFontMetrics(f).stringWidth(text);
                // 兜底：文字永远不越出自己那块牌（裁在牌内，绝不压到旁边的标 / 站名）。
                final Shape oldClip = g.getClip();
                g.setClip(new Rectangle(x, top, w, badgeH));
                g.drawString(text, x + (w - tw) / 2f,
                        top + badgeH * 0.5f + (g.getFontMetrics(f).getAscent()
                                - g.getFontMetrics(f).getDescent()) / 2f);
                g.setClip(oldClip);
            }
            x += w + Math.max(0, Math.round(fit.gap));
        }

        box[0] = top;
        box[1] = top + badgeH;
        lastSwapHead = ringD;
        lastSwapPlate = totalW;
        lastSwapPlateH = badgeH;
        // 报告口径：整张图所有“多换乘一排”加起来画了几个、丢了几个、最紧的那次缩到多少。
        lastBadgeCount += fit.count;
        lastBadgeDropped += swaps.length - fit.count;
        lastBadgeScale = Math.min(lastBadgeScale, fit.scale);
        return true;
    }

    /** 把文字缩到给定宽度内（只作为极端情况下的兜底）。 */
    private static String fitText(final Graphics2D g, final Font font, final String text, final int maxWidth) {
        if (maxWidth <= 4 || text.isEmpty()) {
            return text;
        }
        float size = font.getSize2D();
        for (int i = 0; i < 8 && size > 6f; i++) {
            size *= 0.9f;
            if (g.getFontMetrics(font.deriveFont(size)).stringWidth(text) <= maxWidth) {
                break;
            }
        }
        final Font smaller = font.deriveFont(size);
        g.setFont(smaller);
        return text;
    }

    /**
     * 环形扇区（外弧 a1→a2 + 内弧 a2→a1），角度是数学角（0° = 正右，90° = 正上），
     * 屏幕坐标 y 向下，所以纵向取反。
     */
    private static Path2D.Float annularSector(final float cx, final float cy, final float rOut, final float rIn,
                                              final float a1, final float a2) {
        final int steps = 28;
        final Path2D.Float path = new Path2D.Float();
        for (int i = 0; i <= steps; i++) {
            final double a = Math.toRadians(a1 + (a2 - a1) * i / steps);
            final float x = (float) (cx + rOut * Math.cos(a));
            final float y = (float) (cy - rOut * Math.sin(a));
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        for (int i = steps; i >= 0; i--) {
            final double a = Math.toRadians(a1 + (a2 - a1) * i / steps);
            final float x = (float) (cx + rIn * Math.cos(a));
            final float y = (float) (cy - rIn * Math.sin(a));
            path.lineTo(x, y);
        }
        path.closePath();
        return path;
    }

    /** 弧端点的箭头尖：沿弧的“前进方向”（逆时针）伸出去。 */
    private static void arrowHead(final Graphics2D g, final float cx, final float cy, final float rOut, final float rIn,
                                  final float angleDeg, final int rgb) {
        final double a = Math.toRadians(angleDeg);
        final float rMid = (rOut + rIn) / 2f;
        final float px = (float) (cx + rMid * Math.cos(a));
        final float py = (float) (cy - rMid * Math.sin(a));
        // 前进方向（角度增大 = 逆时针）的切向
        final float tx = (float) -Math.sin(a);
        final float ty = (float) -Math.cos(a);
        final float nx = -ty;
        final float ny = tx;
        final float thick = rOut - rIn;
        final float headLen = thick * IX_HEAD_LEN;
        final float headW = thick * IX_HEAD_W / 2f;
        g.setColor(new Color(rgb));
        g.fillPolygon(
                new int[]{Math.round(px + tx * headLen), Math.round(px + nx * headW), Math.round(px - nx * headW)},
                new int[]{Math.round(py + ty * headLen), Math.round(py + ny * headW), Math.round(py - ny * headW)},
                3);
    }

    /**
     * 线路牌第一行：{@code N号线}。
     *
     * <p>只有线路号是<b>纯数字</b>时才拼 {@code 号线}；{@code K3} 这类轻轨编号（来自
     * {@code Route#lightRailRouteNumber}）不拼，否则牌子上会出现一条现实中不存在的“K3号线”，
     * 那时直接用线路名。</p>
     */
    private static String plateCjk(final Interchange swap) {
        final String number = swap.number == null ? "" : swap.number.trim();
        if (isAllDigits(number)) {
            return number + "号线";
        }
        final String name = swap.name == null ? "" : swap.name.trim();
        return name.isEmpty() ? number : name;
    }

    /** 线路牌第二行：{@code Line N}（线路号不是纯数字时留空，第二行不画）。 */
    private static String plateEn(final Interchange swap) {
        final String number = swap.number == null ? "" : swap.number.trim();
        return isAllDigits(number) ? "Line " + number : "";
    }

    private static boolean isAllDigits(final String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 站名：默认 45° 斜排在线条上方（实拍版式），中文一行 + 英文小字一行；
     * {@code namesBelow} 时正排到线条下方（英文贴在中文下面）。
     *
     * <h3>v5：边界放宽到整张图 + 逐站反解字号</h3>
     * <ul>
     *   <li><b>不再卡在内容区里</b>：可用上界是图片顶部 {@link #NAME_AREA_TOP_RATIO} 的留白
     *       （内容区缩到 60% 后多出来的白边就是给站名用的），所以长站名不会再被顶边切掉；</li>
     *   <li>先按站距解一个<b>全线统一</b>的基准字号（只有站太密时才隔站画），
     *       再<b>逐站</b>按 45° 的真实投影反解 —— 字宽和字高都要乘 cos45，
     *       谁的站名太长谁自己变小，短站名不会被连累；</li>
     *   <li>下限 {@link #NAME_MIN_SCALE}×基准（且不低于 {@link #NAME_FLOOR}px），
     *       缩到下限还放不下就按字宽<b>裁字加 “…”</b>（LCD 的兜底做法）——
     *       <b>最长站名也不会越出面板</b>。</li>
     *   <li><b>终点站（画面里最后一个站）</b>：45° 往右上一定放不下 → 改成<b>往左上的镜像排版</b>，
     *       墨迹末端压在自己的圆心（{@link #drawRotatedNameEnd}）。老做法是把整条站名往左平移
     *       {@code projected} 像素，站名离自己的圆圈空出一整个字宽（实拍里“终点站名整体左移”）。</li>
     * </ul>
     */
    private static float drawNames(final Graphics2D g, final String[] names, final String[] ens, final int[] xs,
                                   final float[] radii, final int currentIndex, final boolean[] passed,
                                   final int lineRgb, final int nameTop, final int bandBottom, final int lineY,
                                   final float step, final boolean namesBelow, final int rightEdge,
                                   final int imgW, final int firstTextX, final float belowExtra,
                                   final int nameCap) {
        final int count = names.length;
        float maxR = 0f;
        for (final float r : radii) {
            maxR = Math.max(maxR, r);
        }
        final float roomTop = namesBelow
                ? bandBottom - lineY - maxR - NAME_CLEARANCE_MIN - belowExtra - 3f
                // 斜排时文字沿 45° 往上走，顶部只留 nameTop 的白边。
                : lineY - maxR - NAME_CLEARANCE_MIN - nameTop;
        if (roomTop < NAME_FLOOR) {
            return 0f;
        }

        final Font base = font();
        final Font ref100 = base.deriveFont(Font.BOLD, 100f);
        final FontMetrics fm100 = g.getFontMetrics(ref100);
        final FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        final float asc100 = Math.max(1f, fm100.getAscent());
        final float desc100 = Math.max(0f, fm100.getDescent());

        double maxW100 = 0;
        for (final String name : names) {
            if (name != null && !name.isEmpty()) {
                maxW100 = Math.max(maxW100, ref100.getStringBounds(name, frc).getWidth());
            }
        }
        if (maxW100 <= 0) {
            return 0f;
        }

        // ---- 基准字号（全线统一）：只由站距 + 上限决定，纵向交给下面的逐站反解 ----
        final double footprint = namesBelow ? maxW100 : maxW100 * DIAG;
        int stride = 1;
        float size = 0f;
        for (int attempt = 0; attempt < 4; attempt++) {
            size = (float) (100.0 * step * stride * NAME_GAP / footprint);
            size = Math.min(size, nameCap);
            if (size >= NAME_FLOOR) {
                break;
            }
            stride++;
        }
        if (size < NAME_FLOOR) {
            return 0f;
        }

        // 英文行：字号最多是中文的 EN_RATIO，且必须塞进同一段站距；塞不下就不写英文（整条线统一）。
        float enScale = 0f;
        if (ens != null && hasEnglish(ens)) {
            int maxEn100 = 0;
            for (final String en : ens) {
                if (en != null && !en.isEmpty()) {
                    maxEn100 = Math.max(maxEn100, fm100.stringWidth(en));
                }
            }
            if (maxEn100 > 0) {
                final double limit = step * stride * NAME_GAP;
                final double maxEnSize = limit / (maxEn100 / 100.0 * (namesBelow ? 1.0 : DIAG));
                enScale = (float) Math.min(EN_RATIO, maxEnSize / Math.max(1f, size));
                if (enScale < 0.30f) {
                    enScale = 0f;
                }
            }
        }
        final float minSize = Math.max(NAME_FLOOR, size * NAME_MIN_SCALE);

        final Shape clip = namesBelow
                ? new Rectangle(Math.max(0, firstTextX), Math.round(lineY + maxR) + 1,
                        Math.max(1, rightEdge - firstTextX), Math.max(1, bandBottom - lineY))
                : new Rectangle(Math.max(0, firstTextX), nameTop, Math.max(1, rightEdge - firstTextX),
                        Math.max(1, lineY - nameTop));

        int shrunk = 0;
        int clipped = 0;
        final Shape oldClip = g.getClip();
        g.setClip(clip);
        for (int i = 0; i < count; i += stride) {
            final String raw = names[i];
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            final boolean isCurrent = i == currentIndex;
            final String rawEn = enScale > 0f && ens != null && i < ens.length ? ens[i] : null;
            final boolean hasEn = rawEn != null && !rawEn.isEmpty();
            final float boost = isCurrent ? 1.06f : 1f;

            // ---- 逐站反解字号：45° 下字宽和字高都要投影，按这个站真实剩下的空间解 ----
            final double wCjk = ref100.getStringBounds(raw, frc).getWidth();
            final double wEnRaw = hasEn ? ref100.getStringBounds(rawEn, frc).getWidth() : 0.0;
            final double wEn = wEnRaw * enScale;
            final float clr0 = Math.max(NAME_CLEARANCE_MIN, Math.min(NAME_CLEARANCE_MAX, size * 0.35f));
            final float enStep0 = (float) (size * enScale * 1.30f);
            final float availV = namesBelow
                    ? bandBottom - lineY - radii[i] - belowExtra - clr0 - 2f
                    : lineY - radii[i] - clr0 - size * 0.20f - enStep0 - nameTop;
            final float availH = rightEdge - 6f - firstTextX;
            final double needV = namesBelow
                    ? (asc100 + desc100 + 130.0 * enScale) * boost
                    : DIAG * (wCjk + asc100) * boost;
            final double needH = namesBelow
                    ? Math.max(wCjk, wEn) * boost + 4.0
                    : DIAG * Math.max(wCjk, wEn) * boost + 2.0;
            float s = (float) Math.min(size, Math.min(100.0 * Math.max(1f, availV) / Math.max(1.0, needV),
                    100.0 * Math.max(1f, availH) / Math.max(1.0, needH)));
            s = Math.max(minSize, Math.min(size, s));
            if (s < size - 0.4f) {
                shrunk++;
            }

            final float clr = Math.max(NAME_CLEARANCE_MIN, Math.min(NAME_CLEARANCE_MAX, s * 0.35f));
            // “文字方向”上的可用长度：垂直投影和水平投影取小的那个。
            final float runAvailV = namesBelow ? Float.MAX_VALUE
                    : (lineY - radii[i] - clr - s * 0.20f - nameTop) / (float) DIAG;
            final float runAvailH = namesBelow ? (rightEdge - 6f - firstTextX)
                    : (rightEdge - 6f - firstTextX) / (float) DIAG;
            final float runAll = Math.min(runAvailV, runAvailH);

            // 英文行：先按中文缩小，再按“剩下的长度”自己收 —— 宁可小也不裁字
            //（v4 是直接画出去，被面板边界切掉半句话）。
            boolean showEn = hasEn;
            float enSize = hasEn ? s * enScale : 0f;
            float enStep = enSize * 1.30f;
            if (hasEn && wEnRaw > 0) {
                for (int k = 0; k < 3; k++) {
                    final float avail = runAll - enStep;
                    if (avail <= 4f) {
                        break;
                    }
                    final float fit = (float) (100.0 * avail / wEnRaw);
                    if (fit >= enSize - 0.2f) {
                        break;
                    }
                    enSize = Math.max(4f, fit);
                    enStep = enSize * 1.30f;
                }
                if (enSize < 4.5f) {
                    showEn = false;
                    enSize = 0f;
                    enStep = 0f;
                }
            }
            final float enOffset = showEn ? enStep : 0f;
            // 缩到下限还放不下（极端长的站名）→ 裁字加 “…”（LCD 的兜底做法）。
            final String label = clipToWidth(ref100, frc, raw, s * boost, runAll - enOffset);
            final String en = showEn
                    ? clipToWidth(ref100, frc, rawEn, enSize, Math.max(4f, runAll - enStep))
                    : null;
            if (!label.equals(raw) || (showEn && en != null && !en.equals(rawEn))) {
                clipped++;
            }

            final Font cjkFont = base.deriveFont(Font.BOLD, s * boost);
            final Font enFont = base.deriveFont(Font.PLAIN, Math.max(1f, enSize));
            // v6：当前站 = 固定红；已过站 = 灰；其余站名按原规则（正排非当前站 65% 灰 / 斜排近黑）。
            final boolean isPassed = passed != null && i < passed.length && passed[i];
            final int textRgb = isCurrent
                    ? CURRENT_FILL
                    : (isPassed ? PASSED_NAME_GREY : (namesBelow ? NAME_GREY : TEXT_DARK));
            // 颜色常量是 0xRRGGBB，没有 alpha 位；new Color(rgb, true) 会把 alpha 当成 0（= 全透明），
            // 所以这里必须显式补上 0xFF000000。
            final Color textColor = new Color(0xFF000000 | textRgb, true);
            final Color enColor = new Color(0xFF000000
                    | (isCurrent ? textRgb : (isPassed ? PASSED_NAME_GREY : TEXT_EN)), true);
            g.setFont(cjkFont);
            g.setColor(textColor);
            if (namesBelow) {
                final int textW = g.getFontMetrics(cjkFont).stringWidth(label);
                int x = Math.round(xs[i] - textW / 2f);
                x = Math.max(x, Math.min(Math.round(xs[i]), firstTextX));
                // v11：终点站优先“压在自己圆圈上居中”——允许用满到图片右边缘的那点白边
                //      （不再只用到 rightEdge 的内边距），真的还放不下才夹回来。
                x = Math.min(x, Math.max(firstTextX, imgW - 2 - textW));
                x = Math.max(x, 2);
                if (i == count - 1) {
                    lastNameAnchorX = x + textW / 2;
                }
                final float baseline = lineY + radii[i] + belowExtra + clr
                        + g.getFontMetrics().getAscent();
                g.drawString(label, x, baseline);
                if (en != null) {
                    g.setFont(enFont);
                    g.setColor(enColor);
                    final int enW = g.getFontMetrics(enFont).stringWidth(en);
                    g.drawString(en, Math.min(imgW - 2 - enW, Math.max(firstTextX, xs[i] - enW / 2f)),
                            baseline + enOffset);
                }
            } else {
                final int textW = g.getFontMetrics(cjkFont).stringWidth(label);
                final int enW = en == null ? 0 : g.getFontMetrics(enFont).stringWidth(en);
                // 45° 下的水平占用 = max(中文宽, 英文宽) x cos45；英文画在第二行，还要再加一个行距的
                // 斜向投影，否则贴右沿时英文尾部会出画。最后留 3 px 余量（字形右侧还有 bearing）。
                final int projected = (int) Math.ceil(Math.max(textW, enW) * DIAG)
                        + (en == null ? 0 : (int) Math.ceil(enOffset * DIAG)) + 4;
                final int baseY = Math.round(lineY - radii[i] - clr - s * 0.20f) - Math.round(enOffset);
                // ★ 终点站（画面里最后一个站）：45° 往右上走必定伸出面板右边，于是以前是把整条站名
                //   **往左平移 projected 像素**（= 墨迹右上角贴面板右边），结果站名离自己的圆圈隔了
                //   一整个字宽 —— 用户实拍里终点站名“整体左移”，和别的站不一个规矩。
                //   v11 改成**镜像排版**：同样 45°，但文字往左上走、**墨迹末端压在自己的圆心**上。
                //   横向占用还是 projected 像素（一个像素都没多），所以既不会越出面板、也不会比原来
                //   多压到左边那一格；跟“这个站挂了几条换乘”完全无关（换乘只影响线下的线路牌）。
                final boolean mirrorEnd = i == count - 1
                        && xs[i] + projected > rightEdge - 6        // 往右上放不下
                        && xs[i] - projected >= firstTextX;         // 往左上放得下
                if (mirrorEnd) {
                    lastNameMirrored = true;
                    lastNameAnchorX = xs[i];
                    drawRotatedNameEnd(g, label, en, textW, enW, xs[i], baseY, cjkFont, enFont, enStep,
                            enColor);
                } else {
                    int x = xs[i];
                    if (x + projected > rightEdge - 6) {
                        x = Math.max(firstTextX, rightEdge - 6 - projected);
                    }
                    if (i == count - 1) {
                        lastNameAnchorX = x;
                    }
                    drawRotatedName(g, label, en, x, baseY, cjkFont, enFont, enStep, enColor);
                }
            }
        }
        g.setClip(oldClip);
        lastNameShrunk = shrunk;
        lastNameClipped = clipped;
        return size;
    }

    /**
     * 把文字裁到 {@code maxWidth} 内（末尾加 “…”）；本来放得下就原样返回。
     *
     * <p>{@code ref100} 是同族 {@code size=100} 的字体，{@code size} 是真正要画的字号 ——
     * 这样一次测量就能按比例算出任意字号下的字宽，不用反复 deriveFont。</p>
     */
    private static String clipToWidth(final Font ref100, final FontRenderContext frc, final String text,
                                      final float size, final float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 2f) {
            return text;
        }
        if (ref100.getStringBounds(text, frc).getWidth() * size / 100.0 <= maxWidth) {
            return text;
        }
        final String ellipsis = "…";
        for (int len = text.length() - 1; len >= 1; len--) {
            final String cut = text.substring(0, len) + ellipsis;
            if (ref100.getStringBounds(cut, frc).getWidth() * size / 100.0 <= maxWidth) {
                return cut;
            }
        }
        return ellipsis;
    }

    /** 英文行在 45° 下的水平占用（没英文就是 0）。 */
    private static float enWidth(final Graphics2D g, final Font enFont, final String en) {
        return en == null || en.isEmpty() ? 0f : g.getFontMetrics(enFont).stringWidth(en);
    }

    /** 这一排站名里有没有英文。 */
    private static boolean hasEnglish(final String[] ens) {
        for (final String en : ens) {
            if (en != null && !en.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 45° 斜排两行：中文（粗）+ 英文（细，贴在中文行下方）。 */
    private static void drawRotatedName(final Graphics2D g, final String cjk, final String en, final int x,
                                        final int baseY, final Font cjkFont, final Font enFont,
                                        final float lineStep, final Color enColor) {
        final AffineTransform old = g.getTransform();
        g.rotate(-Math.PI / 4, x, baseY);
        g.setFont(cjkFont);
        g.drawString(cjk, x, baseY);
        if (en != null && !en.isEmpty()) {
            g.setFont(enFont);
            g.setColor(enColor);
            g.drawString(en, x, baseY + lineStep);
        }
        g.setTransform(old);
    }

    /**
     * 终点站站名的<b>镜像 45°</b>排版（v11）：同样 45°，但文字<b>往左上走</b>，墨迹末端
     * （最后一个字的下沿）压在 {@code (x, baseY)} —— 也就是压在自己的圆圈上。
     *
     * <p>为什么不直接把整条往左平移（老做法）：45° 站名往右上走会伸出面板，老做法把整条
     * 往左推 {@code projected} 像素，于是站名和它自己的圆圈之间空出一整个字宽，看起来就是
     * “终点站名整体左移”。镜像排版横向占用一个像素没变，但末端咬住圆心，与普通站同一套
     * “站名从自己的圆圈长出来”的规矩。</p>
     *
     * <p>字形<b>不镜像</b>（照样能读）：只是把基线方向从「右下 → 右上」换成「左上 → 右下」，
     * 文字按阅读方向仍然是正着的 45°。</p>
     *
     * @param cjkW 中文行宽（px，paint 里量好的）
     * @param enW  英文行宽（px，没有英文传 0）
     */
    private static void drawRotatedNameEnd(final Graphics2D g, final String cjk, final String en,
                                           final int cjkW, final int enW, final int x, final int baseY,
                                           final Font cjkFont, final Font enFont, final float lineStep,
                                           final Color enColor) {
        final AffineTransform old = g.getTransform();
        g.translate(x, baseY);
        g.rotate(Math.PI / 4);
        g.setFont(cjkFont);
        // 从 x = -宽 画到 x = 0：墨迹末端正好落在锚点（自己的圆心）上。
        g.drawString(cjk, -cjkW, 0);
        if (en != null && !en.isEmpty()) {
            g.setFont(enFont);
            g.setColor(enColor);
            g.drawString(en, -enW, lineStep);
        }
        g.setTransform(old);
    }

    // ------------------------------------------------------------------------------------------
    // 字体
    // ------------------------------------------------------------------------------------------

    /**
     * 黑体：<b>SimHei / 微软雅黑</b> 优先（实拍是笔画较粗的黑体，Noto Sans CJK Regular 太细）。
     * 逐个候选检查能不能显示 {@code 厦 / 线}，找不到再退回系统里任意能显示中文的字体。
     */
    public static Font font() {
        Font font = cjkFont;
        if (font != null) {
            return font;
        }
        synchronized (XiamenStripPainter.class) {
            font = cjkFont;
            if (font == null) {
                font = resolveFont();
                cjkFont = font;
            }
        }
        return font;
    }

    private static Font resolveFont() {
        try {
            final GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Font best = null;
            int bestScore = Integer.MAX_VALUE;
            for (final Font f : env.getAllFonts()) {
                if (!f.canDisplay('厦') || !f.canDisplay('线')) {
                    continue;
                }
                // 家族名的本地化很坑（SimHei 在中文 Windows 上叫“黑体”），所以用 PS 名 / 英文族名判断。
                final String key = (f.getPSName() + "|" + f.getFontName(Locale.ENGLISH) + "|"
                        + f.getFamily(Locale.ENGLISH)).toLowerCase(Locale.ROOT);
                int score;
                if (key.contains("simhei")) {
                    score = 0;
                } else if (key.contains("yahei")) {
                    score = key.contains("light") ? 4 : 1;
                } else if (key.contains("noto sans sc") || key.contains("source han sans")
                        || key.contains("noto sans cjk")) {
                    score = 3;
                } else if (key.contains("simsun") || key.contains("jhenghei")) {
                    score = 5;
                } else {
                    score = 8;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            if (best != null) {
                lastFontName = best.getPSName();
                return best.deriveFont(Font.BOLD, 100f);
            }
        } catch (Throwable ignored) {
            lastFontName = "logical";
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 100);
    }

    /** 站名里 “中文|English” 只取中文段。 */
    public static String cjkPart(final String raw) {
        final String text = raw == null ? "" : raw.replace("||", "|").trim();
        if (text.isEmpty()) {
            return "";
        }
        final int cut = text.indexOf('|');
        if (cut < 0) {
            return text;
        }
        final String head = text.substring(0, cut).trim();
        return head.isEmpty() ? text.substring(cut + 1).trim() : head;
    }

    /** 站名里 “中文|English” 只取英文段（没有就是空串）。 */
    public static String enPart(final String raw) {
        final String text = raw == null ? "" : raw.replace("||", "|").trim();
        final int cut = text.indexOf('|');
        if (cut < 0) {
            return "";
        }
        return text.substring(cut + 1).trim();
    }
}
