// 厦门 PIDS（地铁）顶部编组条的**动态叠加层**：只重画那条编组，面板其余像素一个都不动。
//
// 背景（2026-11 第三版）：底图换成新的一张 —— 顶部条区只有纯青蓝色（**没有**烘死的卡片），
// 编组条整块交给叠加层画：「底图照旧 + 在这块矩形上再贴一张小贴图 + 车号仍然走 TextRenderer」：
//
//   <b>贴图层（这里，4× 位图）只放图形，一个字的文字都没有：</b>
//   ① 底带 = 用户单独给的 `consist_band.png`（333×33，纯底带：深蓝圆角带 + 左端斜车头 +
//      两侧一圈青色余边，**不带卡片也不带字**），按<b>三段</b>铺（{@link #paintBand}）：
//      左段（斜车头，35px）与右段（收边，2px 暗边线）只按比例放大、绝不横向拉伸，
//      中段（276px 纯色）横向拉伸 —— 所以 2 节到 16 节车头形状都不变形、也没有接缝。
//      <b>总长跟着节数伸缩</b>（{@link #contentWidth}）：右端 = 最后一张卡 + 与左边对称的尾段，
//      2 节短、7 节以上顶到条区上限；
//      <b>整段在条区里水平居中</b>（{@link #bandX0}）：<b>居中量的是底带的墨迹，不是素材矩形</b> ——
//      素材 x 0..15 那圈青色光晕画的时候是透明的（让底图青蓝透出来），把它算进长度里会让
//      看得见的带子整体右移 8px（2026-11 修的就是这个：2 节时带左缘余量 120 / 右缘 104）。
//      现在 {@code x0 = 条区中心 − (墨迹内边 + 内容宽)/2}，而卡片行在墨迹里左右等宽
//      （各 {@code ROW_INSET − 墨迹内边} = 15px），所以<b>带子、卡片、车号三者的中心都压在条区中心上</b>；
//      <b>底带以外的像素一律透明</b> —— 条区里没铺到的地方 alpha = 0，底图那条青蓝色原样透出
//      （素材两侧那圈青色余边也不画：那是条区自己的颜色，底图里就有）；
//   ② 卡片 = <b>从旧底图里像素级切出来的那一张</b>（`gui/pids/consist_card.png`，41×20，
//      旧底图坐标 x 210..250 / y 3..22）**按原尺寸平铺 N 次** —— 4× 时是逐像素块复制
//      （最近邻、不插值、不重采样），所以卡片和底图里烘死的那排一模一样锐，
//      不会再有「缩放取样带进来的绿竖条」。
//      <b>长编组只压 x 方向</b>：n·41 + (n−1)·5.6 &gt; 297 时 {@code cw = (297 − (n−1)·gap)/n}
//      把卡片横向压窄（高度永远是 20，最近邻不插值），<b>绝不重叠、绝不越界</b>；
//      卡片行整体跟着底带居中（见 {@link #layout}）
//   ③ 雪花 = 同样从旧底图标签区切出来的那一颗（`gui/pids/consist_flake.png`，7×7，
//      旧底图坐标 x 228..234 / y 23..29），首 / 尾车 1 颗、中间车<b>永远 2 颗</b>
//      （编组图的语义：2 颗 = 中间车）。格子窄了就<b>缩雪花尺寸</b>而不是减颗数：
//      边长按 7 → 6 → 5 → 4 面板像素依次试，4× 后是 28 / 24 / 20 / 16 设备像素，
//      仍是最近邻块复制、不插值（见 {@code flakeSizeFor}）。
//
//   <b>文字层（渲染器里画）：</b>「N车」这几个字**不烘进位图**，走和面板其它文字、和天津
//   地铁同一套的 MC {@code TextRenderer} 顶点级绘制（{@code PidsXiamenText#drawRun}）。
//   卡片 / 雪花 / 文字的位置全部来自**同一份** {@link #layout(int)}：文字就摆在那条记录给的
//   {@code textRight} 右对齐位置上 —— 位图和文字的位置因此永远对得上。
//
// <h2>为什么是 4×</h2>
// 这块矩形只有 333×33 面板像素，屏在世界里被压成 1.96 格宽 —— 1× 的图贴上去，远看就是一团。
// 这里叠加层 = <b>333×4 × 33×4 = 1332×132</b>，底带按 4×4 铺、卡片和雪花按 4×4 块复制 ——
// 没有任何一步是「画小再放大」，也没有任何一步是插值。贴图上传时过滤器显式设成 NEAREST
// （见 {@code PidsXiamenConsistTextures}），不会被线性插值抹糊。
//
// <h2>量出来的数（脚本：{@code v1.20.1/tools/pids-xiamen/probe-new-panel.js}）</h2>
// <pre>
//   条区（底图顶部那条青蓝色）  x 179..511   y 0..32     = 333 × 33 px   ← 叠加层就贴这块矩形
//                归一化 x 0.3496..1.0000    y 0.0000..0.1404（÷512 / ÷235）
//                颜色 (44,242,255) 一类的青蓝，左缘 6 列是圆角渐变
//   底带素材     consist_band.png 333×33：青色光晕 0..15（**不画**）/ 车头斜边 16..34 /
//                带体中段 35..310 / 收边 311..312 / 右侧青色余边 313..332（**不画**）
//                → 墨迹 = x 16..312（297 列）、y 2..32，所以 bandInkInset() = 16
//   旧底图切出来的切片  卡片 (210, 3, 41, 20)      雪花 (228, 23, 7, 7)
//   内容宽       2 节 133.6px / 6 节 320px / 7 节起 333px（= 条区宽，上限）
//   墨迹余量     2 节 107.7 / 107.7   4 节 53.2 / 53.2   6 节 14.5 / 14.5（左右恒等）
// </pre>
//
// <h2>垂直布局（面板 px，带子 0..32、内圈深蓝 2..31）</h2>
// <pre>
//   底带       y 0..32   —— 和条区**逐行等高**：叠加层高 132 = 33×4，最后一行设备像素就是 y 32，
//              y ≥ 33 一个不透明像素都没有（素材底部的暗边就在第 33 行里，不会再往下多出一行）
//   卡片      y 3..22   （20 行）→ 4× 设备像素 y 12..92    上下都留余量，绝不越出底带
//   雪花      y 23..29  （7 行） → 4× 设备像素 y 92..120
//   「N车」    y 24..30  （6 行） → 4× 设备像素 y 96..120   基线与雪花齐平
// </pre>
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 编组条叠加层的画家：<b>纯 Java2D，零 MC import、零文字</b> —— 离线预览
 * （{@code tools/pids-xiamen/XiamenConsistPreview}）和游戏里跑的是同一份代码。
 *
 * <p>{@link #paint} 出来的是「整条编组带」的小贴图（4×，1332×132），渲染器把它铺回
 * {@link #STRIP} 那个矩形上，再用 {@code PidsXiamenText} 把车号写在
 * {@link #layout(int)} 给出的位置上。
 *
 * <p><b>底带长度跟着节数走、整段居中</b>（{@link #contentWidth}）：右端 = 最后一张卡右缘 +
 * 与带左侧对称的尾段（{@link #tailPad}），节数少底带就短，最长到<b>整条条区</b>（{@link #STRIP_W}）。
 * 底带画多长由内容宽度定，<b>条区里剩下的像素一律透明</b>（alpha = 0）：底图那条青蓝色永远是背景，
 * 叠加层只往上叠「深蓝底带 + 卡片 + 雪花」，不再自己铺一块底色。
 * <b>居中基准 = 条区矩形本身</b>（{@link #STRIP_X0}..{@code STRIP_X0 + STRIP_W}），而且量的是
 * <b>带子的墨迹</b>：素材左边那圈青色光晕（{@link #bandInkInset} 列）画的时候透明，
 * 不算进长度 —— 所以看得见的带子左右余量恒等（见 {@link #bandMargins}），
 * 卡片行在墨迹里也是左右等宽，整条编组看着才是真的在条区正中。
 * {@code cars <= 0}（读不到编组）时整张叠加层全透明 → <b>编组条整块空着</b>，底图青蓝色原样透出。
 */
public final class PidsXiamenConsist {

    // ---------------------------------------------------------------- 量出来的常量

    /** 叠加层分辨率倍数（1 面板像素 → 4 贴图像素）。 */
    public static final int SCALE = 4;

    /** 底带在底图上的矩形（含首末像素）：x 179..511、y 0..32 → 333 × 33。 */
    public static final int STRIP_X0 = 179;
    public static final int STRIP_Y0 = 0;
    public static final int STRIP_W = 333;
    public static final int STRIP_H = 33;

    /**
     * <b>兜底用的</b>「带子墨迹左内边」（面板 px）= 底带素材左边那圈青色光晕的列数。
     * <p>素材 {@code consist_band.png} 的 x 0..15 全是条区自己的青蓝色（画的时候按
     * {@link #isCyan} 跳过 → <b>透明</b>），墨迹从 x 16 才开始 —— 所以「带子画多长」
     * 不能从素材 x0 数起，否则看得见的带子会整体右移 8px。
     * <p>运行期以 {@link #bandInkInset} 从素材像素量出来的值为准，这里只是文档 / 预览的兜底。
     */
    public static final int BAND_INK_X0_DEFAULT = 16;

    /**
     * 底带右端在「最后一张卡右缘」之外<b>至少</b>再留的空白（面板 px）。
     * <p>实际尾段取 {@link #tailPad} = {@code max(收边 + 1, ROW_INSET − 墨迹内边)}，
     * 默认素材下是 {@code max(3, 15) = 15} —— 和左边那 15px（{@code ROW_INSET − 墨迹内边}）
     * 等宽，卡片行因此在墨迹里左右对称。{@link #BAND_PAD} 只是这条尾巴的下限
     * （换一份「墨迹贴边」的素材时仍然留得出收边）。
     */
    public static final float BAND_PAD = 3.0F;

    /**
     * 卡片行左缘到<b>带左缘（素材 x0，不是墨迹）</b>的距离（面板 px）。
     *
     * <p>量自底带素材 {@code consist_band.png}：x 0..15 是条区里的青色光晕（这部分绘制时
     * <b>透明</b>，让底图的青蓝色透出来），x 16..34 是斜车头，所以第一张卡从带左缘 +31 起 ——
     * 这就是旧版底图里 {@code ROW_X0(210) − STRIP_X0(179) = 31}。
     * <p>卡片行到墨迹左缘还差 {@code ROW_INSET − 墨迹内边} = 15px，{@link #tailPad} 就是
     * 按这个数给右边也留同样多 —— 两边的量对上，带子和卡片才会一起居中。
     */
    public static final int ROW_INSET = 31;

    /**
     * 底带素材里「右端收边」（那条压暗的边线）的<b>默认</b>列数 —— 仓库里那份 {@code consist_band.png}
     * 实测 2 列。运行期以 {@link #segments} 量出来的值为准（换资源包会跟着变），这里只是文档 / 预览用的兜底。
     */
    public static final int BAND_CAP_DEFAULT = 2;

    /**
     * 卡片行能用多宽（面板 px）= 条区宽 − {@link #ROW_INSET} − {@link #tailPad}。
     *
     * <p>默认素材下是 {@code 333 − 31 − 15 = 287}。6 节时卡片仍是原宽 41、间隙 5.6（总共 274，
     * 和旧底图逐像素一致，因为 {@code cw} 被夹在 {@link #CARD_W}）；<b>7 节起顶到 287</b>
     * → 内容宽 = 条区宽 333（居中的上限就是条区宽）。
     */
    public static float rowAvail() {
        return STRIP_W - ROW_INSET - tailPad();
    }

    /**
     * 卡片行右缘到<b>带右缘</b>的尾段长度（面板 px）= {@code max(BAND_PAD + 收边, ROW_INSET − 墨迹内边)}。
     *
     * <p>默认素材下 = {@code max(5, 31 − 16) = 15}：和卡片行左边那段（带左缘到第一张卡）在墨迹里
     * 正好等宽 15px（{@code ROW_INSET − 墨迹内边}），所以卡片行在墨迹里左右对称 ——
     * 墨迹再在条区里居中，卡片行的中心也就压在条区中心上。
     */
    public static float tailPad() {
        return Math.max(BAND_PAD + Math.max(1, GEOM_CAP), ROW_INSET - bandInkInset());
    }


    /** 卡片切片在底图上的矩形（像素级切，来自 scan-consist-strip.js --emit）。 */
    public static final int CARD_SRC_X = 210;
    public static final int CARD_SRC_Y = 3;
    public static final int CARD_SRC_W = 41;
    public static final int CARD_SRC_H = 20;

    /** 雪花切片在底图上的矩形（标签区里第 1 颗雪花）。 */
    public static final int FLAKE_SRC_X = 228;
    public static final int FLAKE_SRC_Y = 23;
    public static final int FLAKE_SRC_W = 7;
    public static final int FLAKE_SRC_H = 7;

    /** 卡片的自然尺寸（面板 px）= 切片的原尺寸，<b>永不缩放</b>。 */
    public static final float CARD_W = CARD_SRC_W;
    public static final float CARD_H = CARD_SRC_H;

    /** 卡片行顶（面板像素）—— 底图里烘死卡片的顶行 y=3。 */
    public static final float CARD_TOP = CARD_SRC_Y;

    /**
     * 卡片之间的自然间隙（面板像素）= 底图实测 5..6（平均 5.6）。
     */
    public static final float GAP = 5.6F;

    /**
     * 长编组里卡片能被<b>横向</b>压到的最小宽度（面板 px）。
     * <p>到这条线还放不下，就先收间隙（{@link #GAP_MIN}），再收 {@link #GAP_HARD_MIN}；
     * 只有 {@link #MAX_CARS} 节这种极端情况才会真的比它更窄 —— 但永远不重叠、不越界。
     */
    public static final float MIN_CARD_W = 18.0F;

    /** 间隙的下限（面板 px）：少于 2px 卡片就黏在一起分不清了，只在极限节数下才破例。 */
    public static final float GAP_MIN = 2.0F;

    /** 间隙的硬下限（面板 px）：到这儿就不再缩，宁可卡片再窄一点。 */
    public static final float GAP_HARD_MIN = 1.0F;

    /** 雪花行顶（面板像素）—— 底图里那颗雪花是 y 23..29。 */
    public static final float FLAKE_TOP = FLAKE_SRC_Y;

    /** 雪花：单颗宽（面板 px）、两颗之间的空隙、到卡片右缘的留白。 */
    public static final float FLAKE_W = FLAKE_SRC_W;
    public static final float FLAKE_GAP = 2.0F;
    public static final float FLAKE_PAD = 1.0F;

    /**
     * 车号那一行：<b>字形盒</b>的上沿与高度（面板像素）—— 上沿 24、底边 30，
     * 落在底带内圈（2..31）里、和雪花同一行。
     * <p>渲染器直接把这两个值交给 {@code PidsXiamenText#drawRun}，所以文字基线和
     * 底下的雪花永远齐。
     */
    public static final float CAR_TEXT_TOP = 24.0F;
    public static final float CAR_TEXT_INK_H = 6.0F;

    /** 数字和雪花之间的空隙（面板像素）。 */
    public static final float CAR_TEXT_GAP = 1.0F;

    /**
     * 雪花能缩到的<b>最小</b>边长（面板 px）。中间车<b>永远是 2 颗</b> —— 格子窄就把雪花缩小，
     * 绝不减少颗数（编组图的语义：2 颗 = 中间车、1 颗 = 首 / 尾车）。
     * 取值一律整数（7 → 6 → 5 → 4），4× 后是 28 / 24 / 20 / 16 设备像素，
     * 仍然是最近邻块复制、不插值。
     */
    public static final float FLAKE_MIN_W = 4.0F;

    /**
     * 「N车」至少要有这么宽（面板 px）。雪花选尺寸时按它给文字留位：
     * 再窄字号就缩到认不出字了，宁可雪花用 {@link #FLAKE_MIN_W}。
     * 13 节这种极端节数下字号会被继续压小（{@code drawRun} 的 {@code maxWidth} 语义），
     * 但<b>文字与雪花永不重叠</b>。
     * <p>取 5 而不是 6：19.8px 宽的 11 节格子差 0.2px 就能用 5px 雪花，
     * 卡在 6 会让阶梯直接掉到 4px —— 5 正好让 7/6/5/4 四档都用得上。
     */
    public static final float CAR_TEXT_MIN_W = 5.0F;

    /** 防呆：真出现 20 节编组，卡片会挤在一起，不如截断。 */
    public static final int MAX_CARS = 16;

    /**
     * 断言用：叠加层在<b>条区底部以下</b>（{@code y >= STRIP_H × SCALE = 132}）的不透明像素数 ——
     * <b>必须 0</b>。
     *
     * <p>叠加层的高度本身就是 {@link #overlayHeight()} = 132（= 条区高 33 × 4），所以底带、
     * 卡片、雪花<b>在结构上就出不去</b>；这个函数是给预览 / 单测留的一条硬断言：
     * 「底带绝不比条区高、绝不溢出到下方面板蓝底上」。
     *
     * @param overlay {@link #paint} 出来的位图（可以是 {@code null}）
     * @return 条区底部以下几行里的不透明像素数（应当恒为 0）
     */
    public static int opaqueBelowStrip(final BufferedImage overlay) {
        if (overlay == null) {
            return 0;
        }
        final int from = Math.min(overlay.getHeight(), STRIP_H * SCALE);
        int count = 0;
        for (int y = from; y < overlay.getHeight(); y++) {
            for (int x = 0; x < overlay.getWidth(); x++) {
                if (((overlay.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 内容版本：改了绘制逻辑就 +1 —— 渲染器用它和节数一起算内容哈希，
     * 哈希没变就不重传贴图（见 {@code PidsXiamenConsistTextures}）。
     * <p>8：居中基准改成「底带墨迹」（{@link #bandInkInset}），尾段与左侧等宽（{@link #tailPad}）。
     */
    public static final int CONTENT_VERSION = 8;

    /** 切片 keying（把深蓝底变成透明）时，alpha 到这个亮度就满不透明。 */
    private static final int KEY_FULL = 96;

    // ---------------------------------------------------------------- 运行期量出来的几何

    /**
     * 底带素材量出来的三段与带体（源像素）：左段 / 右端收边 / 带体右缘。
     * <p>只在素材实例换了（F3+T / 换包）时重量一次（{@link #segments}）；量不到素材时用仓库里
     * {@code consist_band.png} 的实测值兜底，所以 {@link #layout(int)} 不依赖「素材已经加载」。
     */
    private static int GEOM_LEFT = 35;
    private static int GEOM_CAP = BAND_CAP_DEFAULT;
    private static int GEOM_BODY = 313;
    /** 墨迹左内边（源列）：素材左侧全是青色的那几列 —— 默认素材 = 16。 */
    private static int GEOM_INK_X0 = BAND_INK_X0_DEFAULT;
    private static BufferedImage GEOM_FOR;

    private PidsXiamenConsist() {
    }

    // ================================================================ 对外

    /** 编组带矩形（面板像素，{@code x0,y0,w,h}）—— 渲染器按它摆四边形。 */
    public static float[] strip() {
        return new float[]{STRIP_X0, STRIP_Y0, STRIP_W, STRIP_H};
    }

    /** 叠加层的像素宽度。 */
    public static int overlayWidth() {
        return STRIP_W * SCALE;
    }

    /** 叠加层的像素高度。 */
    public static int overlayHeight() {
        return STRIP_H * SCALE;
    }

    /** 编组带的归一化矩形（÷ 512 / ÷ 235，报告和注释用）。 */
    public static float[] stripNormalized() {
        return new float[]{STRIP_X0 / 512.0F, STRIP_Y0 / 235.0F,
                STRIP_W / 512.0F, STRIP_H / 235.0F};
    }

    /**
     * 底带三段的宽度（源像素）：{@code 左（斜车头）, 中（纯色，横向拉伸）, 收边（右端暗边线）}。
     * <p>三段加起来 = 带体宽度（{@link #bandBodyWidth}）—— 素材两侧那圈青色余边不在里面：
     * 那是条区自己的青蓝色（底图里就有），叠加层里一律<b>透明</b>（见 {@link #paintBand}），
     * 所以底带收短 / 拉长都不会在它后面多出一块青色方块。报告 / 预览用，游戏里走的是同一套量法。
     */
    public static int[] bandSegments(final BufferedImage band) {
        if (band == null) {
            return new int[]{GEOM_LEFT, Math.max(1, GEOM_BODY - GEOM_LEFT - GEOM_CAP), GEOM_CAP};
        }
        segments(band);
        final int bodyW = GEOM_BODY;
        final int left = Math.min(bodyW - 2, GEOM_LEFT);
        final int cap = Math.max(1, Math.min(bodyW - left - 1, GEOM_CAP));
        return new int[]{left, Math.max(1, bodyW - left - cap), cap};
    }

    /**
     * <b>带体宽度</b>（源像素）= 底带素材里深蓝那一截的右缘 + 1 —— <b>底带能画到的最远处</b>。
     * <pre>
     *   consist_band.png  333 列 = 左 34 列青色/车头 + 带体 296 列（x 17..312）+ 右 21 列青色
     *   → bandBodyWidth() = 313
     * </pre>
     * 带体右缘由像素量出来（{@link #bodyRight}），换底带不用改代码；素材缺失时用实测值兜底。
     */
    public static int bandBodyWidth(final BufferedImage band) {
        if (band != null) {
            segments(band);
        }
        return GEOM_BODY;
    }

    /**
     * <b><code>内容宽度</code></b>（面板 px）= 底带要画的长度 —— 跟着节数伸缩，<b>整段按它居中</b>。
     *
     * <pre>
     *   内容宽 = ROW_INSET（卡片行到带左缘）+ 卡片行总宽 + 尾段 tailPad()
     *   尾段   = ROW_INSET − 墨迹内边（= 15）        ← 让卡片行在带子墨迹里左右等宽
     *   最小   = ROW_INSET + 一张卡 + 尾段（= 87）   ← 只挂 1 节也包得住
     *   上限   = STRIP_W（条区宽 333）               ← 到这儿不再变长（居中上限）
     * </pre>
     *
     * <p>2 节 133.6px、6 节 320px（卡片仍是原宽，和旧底图一致）、<b>7 节起 333px 顶到上限</b>：
     * 节数越多底带越长，右端永远压在最后一张卡之后一点点；条区里剩下的像素
     * <b>一律透明</b> —— 底图那条青蓝色原样透出，不需要再铺任何底色。
     *
     * @return 面板 px；{@code cars <= 0} → 0
     */
    public static float contentWidth(final int cars) {
        final Card[] cards = layout(cars);
        if (cards.length == 0) {
            return 0.0F;
        }
        return contentWidthOf(cards.length, cards[0].w(),
                cards.length > 1 ? cards[1].x0() - cards[0].x1() : 0.0F);
    }

    /** {@link #contentWidth(int)} 的内核：卡片数 + 单卡宽 + 间隙 → 内容宽（面板 px）。 */
    private static float contentWidthOf(final int n, final float cw, final float gap) {
        if (n <= 0) {
            return 0.0F;
        }
        final float span = n * cw + (n - 1) * gap;
        final float tail = tailPad();
        final float natural = ROW_INSET + span + tail;
        final float minimal = ROW_INSET + CARD_W + tail;        // 一节的底带也包得住，而且左右对称
        return Math.min(STRIP_W, Math.max(minimal, natural));
    }

    /**
     * <b>带子墨迹的左内边</b>（面板 px）= 底带素材左侧那圈青色光晕的列数（默认素材 = 16）。
     * <p>这几个列画的时候是<b>透明</b>的（{@link #isCyan} 跳过，让底图青蓝透出来），所以
     * 「看得见的带子」从 {@code bandX0 + bandInkInset()} 开始 —— 居中的公式必须减掉它，
     * 否则带子会整体右移半个内边（默认素材下 8px）。
     */
    public static int bandInkInset() {
        return Math.max(0, Math.min(Math.max(0, GEOM_BODY - 2), GEOM_INK_X0));
    }

    /**
     * <b>整段的左缘</b>（面板 px，= 底带素材 x0 落在哪儿）—— 水平居中的公式：
     * <pre>
     *   bandX0 = 条区中心 − (墨迹内边 + 内容宽) / 2
     *          = STRIP_X0 + (STRIP_W − bandInkInset() − contentWidth) / 2
     * </pre>
     * 底部 + 卡片 + 车号 + 雪花都按这条 x0 排。<b>居中量的是墨迹</b>：{@code bandInkInset()} 那几列
     * 青色光晕是透明的，把它们算进去就会把看得见的带子推向右边。
     * 内容宽顶到 {@link #STRIP_W} 时两端各留半个内边的青色（左右仍然相等）。
     * {@code cars <= 0} → 条区中心，画不出东西。
     */
    public static float bandX0(final int cars) {
        return bandX0Of(contentWidth(cars));
    }

    /** {@link #bandX0(int)} 的内核：内容宽 → 带左缘（面板 px）。 */
    private static float bandX0Of(final float contentW) {
        return STRIP_X0 + (STRIP_W - bandInkInset() - contentW) * 0.5F;
    }

    /**
     * 带子墨迹在条区里的<b>左右余量</b>（面板 px，{@code {左, 右}}）—— 居中断言用。
     * <p>墨迹 = {@code [bandX0 + bandInkInset(), bandX0 + contentWidth)}，也就是叠加层里
     * <b>真正不透明的那些列</b>；两边的余量必须相等（差 ≤ 1px），带子看上去才不偏。
     */
    public static float[] bandMargins(final int cars) {
        final float x0 = bandX0(cars);
        final float inkLeft = x0 + bandInkInset();
        final float inkRight = x0 + contentWidth(cars);
        return new float[]{inkLeft - STRIP_X0, (STRIP_X0 + STRIP_W) - inkRight};
    }

    /** 底带右缘的底图 x 坐标（面板 px）= {@code bandX0 + contentWidth}；{@code cars <= 0} → 条区中心。 */
    public static float bandRight(final int cars) {
        return bandX0(cars) + contentWidth(cars);
    }

    /**
     * 一张卡片的位置（面板像素）。贴图按它画卡片和雪花，文字按它摆「N车」——
     * 两边读同一份数据，所以永远不会错位。
     *
     * @param index    第几节（0 = 首车）
     * @param x0       卡片左边界（= 车号那一格的左边界）
     * @param slotW    这一格可见的宽度 = 卡片宽（{@code cw}）。6 节以内是 41（切片原宽），
     *                 长编组只压 x 方向 → 小于 41，高度永远是 {@link #CARD_H} = 20
     * @param flakes   画几颗雪花（首 / 尾车 1、中间车<b>永远 2</b> —— 格窄只缩尺寸，不减颗数）
     * @param flakeW   这一格的雪花边长（面板 px）：7 / 6 / 5 / 4，见 {@link #flakeSizeFor}
     * @param flakeX0  雪花组的左边界
     * @param snowW    雪花组的宽度（{@link #flakeW} × 颗数 + 间隔）
     * @param textRight 「N车」右对齐的锚点（= 雪花左边 − 空隙）
     * @param textW    文字最多能有多宽（= 卡片左边界 → {@code textRight}）
     */
    public record Card(int index, float x0, float slotW, int flakes, float flakeW,
                       float flakeX0, float snowW, float textRight, float textW) {

        /** 卡片可见宽（= 格子宽，供预览 / 断言打印）。 */
        public float w() {
            return this.slotW;
        }

        /** 卡片右边界（不含）—— 相邻卡片必须满足 {@code 前一张.x1() <= 后一张.x0()}。 */
        public float x1() {
            return this.x0 + this.slotW;
        }
    }

    /**
     * 一排卡片的排版（节数 → 每张卡片的几何）。<b>整行跟着带子墨迹一起在条区里水平居中</b>。
     *
     * <pre>
     *   avail = rowAvail() = 条区宽 − ROW_INSET − tailPad()   ← 默认 287
     *   gap   = 5.6（自然）→ 需要时往下收，下限 2、硬下限 1
     *   cw    = 41（切片原宽）；只有 n·41 + (n−1)·gap &gt; 287 时才压 x：
     *           cw = (287 − (n−1)·gap) / n     ← 只压宽、不压高（仍是 20px）
     *   总宽  = n·cw + (n−1)·gap ≤ 287          ← 永不出界，卡片永不重叠
     *   内容宽 = ROW_INSET + 总宽 + tailPad()   ← 底带要画多长（见 {@link #contentWidth}）
     *   x(i)  = bandX0 + ROW_INSET + i·(cw + gap)   ← bandX0 让带子墨迹居于条区正中
     *   雪花  = 首 / 尾车 1 颗、中间车 2 颗；边长 7 → 6 → 5 → 4 挑第一个塞得下的
     *           预算 = cw − 留白 1 − 文字间隙 1 − 文字最小宽 5（见 {@link #flakeSizeFor}）
     *           右对齐到卡片右缘 −1px；两颗之间 2px
     *   文字  = 右对齐到雪花左边 −1px，宽度 = 那一段（超宽就缩字号，永不与雪花重叠）
     * </pre>
     *
     * <p>6 节时 {@code gap = 5.6}、{@code cw = 41}，卡片行本身 274px 宽 —— 和旧底图里那排卡片
     * 逐像素同尺寸，位置换到条区正中（不再是贴着左缘 210 起）；7 节起卡片行顶到 287、
     * 内容宽到条区上限 333。8 节 {@code cw ≈ 30}、13 节 {@code cw ≈ 18}——
     * 卡片只是变窄，<b>不会</b>再互相覆盖成一条链。
     *
     * <p><b>卡片行在墨迹里左右等宽</b>：左边 {@code ROW_INSET − 墨迹内边} = 15px、
     * 右边 {@link #tailPad} = 15px，所以居中的墨迹一摆正，卡片行（和车号）的中心也就压在
     * 条区中心上 —— 这是 2026-11 那次「整体略偏右」的修法。
     *
     * <p><b>颗数不随宽度变化</b>：中间车永远是 2 颗 —— 格子窄就缩雪花（最小 4px），
     * 这样 8 节 / 13 节仍然一眼看得出「中间车 = 2 颗」。
     *
     * @param cars 车厢数；{@code <= 0} 返回空数组
     */
    public static Card[] layout(final int cars) {
        if (cars <= 0) {
            return new Card[0];
        }
        final int n = Math.min(MAX_CARS, cars);
        final float avail = rowAvail();

        // ① 先按自然间隙试：cw 可能 > 41（节数少），下面统一夹到 41（绝不拉伸素材）
        float gap = GAP;
        float cw = widthFor(n, gap);
        if (cw < CARD_W) {
            // ② 要压 x 了。优先只压卡片（gap 保持自然）—— 压到 18px 还不行才收间隙
            if (cw < MIN_CARD_W) {
                gap = Math.max(GAP_MIN, gapFor(n, MIN_CARD_W));
                cw = widthFor(n, gap);
            }
            // ③ 收到 2px 仍不到 18px（n 极大）：把 gap 一直收到硬下限 1px
            if (cw < MIN_CARD_W) {
                gap = Math.max(GAP_HARD_MIN, gapFor(n, MIN_CARD_W));
                cw = widthFor(n, gap);
            }
        }
        cw = Math.min(CARD_W, cw);
        // 浮点防呆：算出 cw 后若总宽还差一丝，按比例把 cw 收回来（永不出界）
        if (n > 1 && n * cw + (n - 1) * gap > avail) {
            cw = Math.max(1.0F, (avail - (n - 1) * gap) / n);
        }

        final float step = cw + gap;
        // ← 水平居中：内容宽定下来以后，带子墨迹（含卡片 + 车号 + 雪花）落在条区正中
        final float contentW = contentWidthOf(n, cw, gap);
        final float x0 = bandX0Of(contentW) + ROW_INSET;

        final Card[] out = new Card[n];
        for (int i = 0; i < n; i++) {
            final float cx = x0 + i * step;
            final float slotW = cw;
            // 首 / 尾车 1 颗，中间车<b>永远</b> 2 颗 —— 窄了只缩雪花，不减颗数
            final int flakes = (i == 0 || i == n - 1) ? 1 : 2;
            final float flakeW = flakeSizeFor(slotW, flakes);
            final float snowW = flakes * flakeW + (flakes - 1) * FLAKE_GAP;
            // 右对齐到卡片右缘 − 留白；极端节数下再夹一次，绝不外溢到前一张卡
            final float flakeX0 = Math.max(cx + 0.5F, cx + slotW - FLAKE_PAD - snowW);
            final float textRight = flakeX0 - CAR_TEXT_GAP;
            final float textW = Math.max(2.0F, textRight - cx - 0.5F);
            out[i] = new Card(i, cx, slotW, flakes, flakeW, flakeX0, snowW, textRight, textW);
        }
        return out;
    }

    /**
     * 这一格该用多大的雪花（面板 px，整数 7 / 6 / 5 / 4）—— <b>颗数不变，只缩尺寸</b>。
     *
     * <p>雪花组要占的宽度 = {@code 颗数 × 边长 + (颗数−1) × 间隔}，可用预算是
     * {@code cw − 留白(1) − 文字间隙(1) − 文字最小宽(5)}；从 7 往下试，挑第一个塞得下的。
     * <pre>
     *   cw 41.0（2..6 节）   预算 34.0  → 7px（原尺寸，和底图一致）
     *   cw 29.4（8 节）      预算 22.4  → 7px（两颗 16）
     *   cw 22.4（10 节）     预算 15.4  → 6px（两颗 14）
     *   cw 19.8（11 节）     预算 12.8  → 5px（两颗 12）
     *   cw 18.0（12/13 节）  预算 11.0  → 4px（最小号，文字只剩 5.5px 宽，继续缩字号）
     * </pre>
     *
     * <p>4px 两颗还塞不下的极端节数（15 节以上）：照样返回 4，然后由
     * {@link #layout(int)} 把雪花组夹回卡片内（文字宽度跟着变窄、靠缩字号塞进去）——
     * 两颗都在、不越界、不与文字重叠。
     */
    public static float flakeSizeFor(final float slotW, final int flakes) {
        final float budget = slotW - FLAKE_PAD - CAR_TEXT_GAP - CAR_TEXT_MIN_W;
        for (float fs = FLAKE_W; fs >= FLAKE_MIN_W; fs -= 1.0F) {
            if (flakes * fs + (flakes - 1) * FLAKE_GAP <= budget + 1.0e-4F) {
                return fs;
            }
        }
        return FLAKE_MIN_W;
    }

    /** {@code n} 节、间隙 {@code gap} 时卡片能有多宽（面板 px，可能 &gt; 41，调用方夹）。 */
    private static float widthFor(final int n, final float gap) {
        return n <= 0 ? CARD_W : (rowAvail() - (n - 1) * gap) / n;
    }

    /** 反解：想让 {@code n} 节每张卡都至少 {@code w} 宽，间隙最多能有多大（面板 px）。 */
    private static float gapFor(final int n, final float w) {
        return n <= 1 ? GAP : (rowAvail() - n * w) / (n - 1);
    }

    /**
     * 卡片 / 雪花 / 车号是否整块落在<b>底带内圈</b>里（面板 px）—— 预览和单测拿它做断言。
     *
     * @return {@code true} = 卡片、雪花组、车号字形盒都在 y 2..31、x 条区左右界之内
     */
    public static boolean insideBand(final Card card) {
        final boolean cardOk = CARD_TOP >= 2.0F && CARD_TOP + CARD_H <= 31.0F
                && card.x0() >= STRIP_X0 && card.x1() <= STRIP_X0 + STRIP_W;
        // 雪花按自己的边长（7/6/5/4）底对齐到 y 30，所以上沿 = FLAKE_TOP + (7 − 边长)
        final float flakeTop = FLAKE_TOP + (FLAKE_SRC_H - card.flakeW());
        final boolean flakeOk = flakeTop >= 2.0F && flakeTop + card.flakeW() <= 31.0F
                && card.flakeX0() >= card.x0()
                && card.flakeX0() + card.snowW() <= card.x1()
                && card.flakeX0() >= STRIP_X0 && card.flakeX0() + card.snowW() <= STRIP_X0 + STRIP_W;
        final boolean textOk = CAR_TEXT_TOP >= FLAKE_TOP
                && CAR_TEXT_TOP + CAR_TEXT_INK_H <= 31.0F
                && card.textRight() <= STRIP_X0 + STRIP_W
                // 文字与雪花不重叠：文字的右缘（textRight）永远在雪花左边 − 空隙上
                && card.textRight() <= card.flakeX0() - CAR_TEXT_GAP + 1.0e-4F;
        return cardOk && flakeOk && textOk;
    }

    /**
     * 内容哈希：节数变了、素材换了（F3+T）、绘制逻辑改了才会变。
     *
     * @param cars   车厢数（1..{@link #MAX_CARS}）
     * @param assets 底带 / 卡片切片 / 雪花切片（按实例身份参与哈希 —— 重载资源包会换实例）
     */
    public static long hash(final int cars, final BufferedImage... assets) {
        long h = CONTENT_VERSION * 1_000_003L + cars;
        for (final BufferedImage asset : assets) {
            h = h * 31L + System.identityHashCode(asset);
        }
        return h;
    }

    /**
     * 画整条编组叠加层（{@code 1332 × 132}）—— <b>只有图形，没有文字</b>。
     *
     * <p>位图默认<b>全透明</b>，只有「底带 + 卡片 + 雪花」盖住的像素才有颜色。所以条区里
     * 底带没铺到的地方 alpha = 0 —— 底图那条青蓝色原样透出来，叠加层不铺任何底色。
     * {@code cars <= 0}（读不到编组）→ 整张位图全透明：<b>编组条整块空着</b>（底带 / 卡片 /
     * 雪花 / 车号一个都不画），底图的青蓝条区不受影响。
     *
     * @param band   底带素材（{@code consist_band.png}，333×33，纯底带，不带卡片与文字）
     * @param card   卡片切片（{@code consist_card.png}，41×20，从底图像素级切出来）
     * @param flake  雪花切片（{@code consist_flake.png}，7×7）
     * @param cars   实际车厢数；{@code <= 0} = 读不到编组 → 全透明（编组条空着）
     * @return 4× 的 ARGB 位图；{@code cars > 0} 但素材缺失 → {@code null}
     */
    public static BufferedImage paint(final BufferedImage band, final BufferedImage card,
                                      final BufferedImage flake, final int cars) {
        final int n = Math.min(MAX_CARS, Math.max(0, cars));
        if (n > 0 && (band == null || band.getWidth() < 8 || band.getHeight() < 4
                || card == null || card.getWidth() < 2 || card.getHeight() < 2
                || flake == null || flake.getWidth() < 2 || flake.getHeight() < 2)) {
            return null;
        }
        try {
            return paintUnsafe(band, card, flake, n);
        } catch (final Throwable t) {
            return null;
        }
    }

    // ================================================================ 主流程

    private static BufferedImage paintUnsafe(final BufferedImage band, final BufferedImage card,
                                             final BufferedImage flake, final int cars) {
        final int w = overlayWidth();
        final int h = overlayHeight();
        final int[] pixels = new int[w * h];

        // ① 整张叠加层从「全透明」开始：底图那条青蓝色就是背景，叠加层只往上叠带子和卡片。
        //    这样底带收短 / 节数读不到时都不会出现一块自铺的底色（也就没有拼缝可言）。
        java.util.Arrays.fill(pixels, 0);

        if (cars > 0) {
            // ② 底带：长度跟着节数走，整段在条区里居中（见 bandX0 / contentWidth）
            paintBand(pixels, band, dev(bandX0(cars) - STRIP_X0),
                    Math.round(contentWidth(cars) * SCALE));

            final BufferedImage cardKeyed = keyed(card, true);
            final BufferedImage flakeKeyed = keyed(flake, false);
            for (final Card c : layout(cars)) {
                // 卡片：高度永远是 4× 块复制；宽度按 cw 最近邻压（cw = 41 时就是纯块复制）
                blitSqueezed(pixels, w, h, cardKeyed,
                        dev(c.x0() - STRIP_X0), dev(CARD_TOP - STRIP_Y0),
                        Math.max(1, Math.round(c.w() * SCALE)),
                        Math.max(1, Math.round(CARD_H * SCALE)));
                // 雪花：首 / 尾 1 颗、中间 2 颗；边长 7/6/5/4 也按 4× 块复制（7px 时纯块复制）
                final int flakeDev = Math.max(1, Math.round(c.flakeW() * SCALE));
                final int flakeTop = dev(FLAKE_TOP + (FLAKE_SRC_H - c.flakeW()) - STRIP_Y0);
                for (int k = 0; k < c.flakes(); k++) {
                    final float fx = c.flakeX0() + k * (c.flakeW() + FLAKE_GAP);
                    blitSqueezed(pixels, w, h, flakeKeyed,
                            dev(fx - STRIP_X0), flakeTop, flakeDev, flakeDev);
                }
            }
        }

        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

    /** 面板像素 → 叠加层像素。 */
    private static int dev(final float panelPx) {
        return Math.round(panelPx * SCALE);
    }

    /**
     * 把一张切片贴进目标像素数组，<b>最近邻缩放到 {@code targetW × targetH}</b> 设备像素，
     * 带 alpha 合成 —— 卡片和雪花都走这一条。
     *
     * <p>采样一律<b>最近邻</b>：{@code sx = floor(dx · sw / targetW)}、
     * {@code sy = floor(dy · sh / targetH)} —— 不做平滑、不插值、不重采样颜色。
     * {@code targetW == sw · scale && targetH == sh · scale} 时（卡片 41×20 → 164×80、
     * 7px 雪花 → 28×28）就退化成逐像素 4×4 块复制，6 节编组的像素与底图完全一致；
     * 雪花缩到 6/5/4px（24/20/16 设备像素）时同样是整齐的方块，不会出现灰边。
     */
    private static void blitSqueezed(final int[] dst, final int dstWidth, final int dstHeight,
                                     final BufferedImage src, final int x0, final int y0,
                                     final int targetW, final int targetH) {
        if (targetW <= 0 || targetH <= 0) {
            return;
        }
        final int sw = src.getWidth();
        final int sh = src.getHeight();
        for (int dx = 0; dx < targetW; dx++) {
            final int px = x0 + dx;
            if (px < 0 || px >= dstWidth) {
                continue;
            }
            final int sx = Math.min(sw - 1, (int) ((long) dx * sw / targetW));
            for (int dy = 0; dy < targetH; dy++) {
                final int py = y0 + dy;
                if (py < 0 || py >= dstHeight) {
                    continue;
                }
                final int argb = src.getRGB(sx, Math.min(sh - 1, (int) ((long) dy * sh / targetH)));
                final int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }
                final int i = py * dstWidth + px;
                dst[i] = a >= 255 ? argb : over(dst[i], argb, a);
            }
        }
    }

    /** source-over：{@code a} 是 src 的 alpha（0..254）。 */
    private static int over(final int dstArgb, final int srcArgb, final int a) {
        final int inv = 255 - a;
        final int r = (((srcArgb >> 16) & 0xFF) * a + ((dstArgb >> 16) & 0xFF) * inv) / 255;
        final int g = (((srcArgb >> 8) & 0xFF) * a + ((dstArgb >> 8) & 0xFF) * inv) / 255;
        final int b = ((srcArgb & 0xFF) * a + (dstArgb & 0xFF) * inv) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ================================================================ 底带

    /**
     * 量一次三段（左段 / 收边 / 带体右缘）写进 {@link #GEOM_LEFT} 等，按素材实例缓存。
     * <p>{@link #layout(int)} / {@link #contentWidth(int)} 也读这几个值，所以「量不到素材」时
     * 用的是仓库里那份 {@code consist_band.png} 的实测兜底（35 / 2 / 313）—— 排版不依赖加载顺序。
     */
    private static void segments(final BufferedImage band) {
        if (GEOM_FOR == band && GEOM_LEFT > 0) {
            return;
        }
        final int left = leftSegment(band);
        final int bodyRightCol = Math.max(left + 1, bodyRight(band));
        final int span = Math.max(2, bodyRightCol + 1 - left);
        GEOM_LEFT = left;
        GEOM_BODY = bodyRightCol + 1;
        // 收边 = 带体右缘那条压暗的边线（+1 是把最右那一列本身也包进来）
        GEOM_CAP = Math.max(1, Math.min(Math.min(8, span - 1), edgeRun(band, bodyRightCol) + 1));
        // 墨迹左内边 = 素材最左边那几列「整列都是青色」的列数（画的时候透明，不算长度）
        GEOM_INK_X0 = Math.max(0, Math.min(GEOM_LEFT - 1, inkX0(band)));
        GEOM_FOR = band;
    }

    /**
     * 素材最左边那几列「整列都是青色」的列数 —— 也就是<b>看得见的带子</b>前面有多少列是透明的。
     * <p>默认素材 {@code consist_band.png} 量出来是 16（x 0..15 全是条区青蓝）。
     * 逐行取「第一个不是青色的列」，再取所有行里的最小值 —— 车头斜边只要有一行提前开始，
     * 那列就算墨迹，宁可把带子算宽一点，也不能让它看起来偏。
     */
    private static int inkX0(final BufferedImage band) {
        int first = band.getWidth();
        for (int y = 0; y < band.getHeight() && first > 0; y++) {
            for (int x = 0; x < first; x++) {
                if (!isCyan(band.getRGB(x, y))) {
                    first = x;
                    break;
                }
            }
        }
        return first >= band.getWidth() ? 0 : first;
    }

    /**
     * 底带按<b>三段</b>铺（9-slice 的横向版），从叠加层的 {@code dstX0} 起铺 {@code dstBandW} 宽：
     * <pre>
     *   左段  x 0..leftW-1                  含斜车头（和它左边那点青色光晕），等比放大，绝不横向拉伸
     *   中段  leftW..bodyRight-capW         纯深蓝 + 上下那两条浅色边，横向拉伸填满剩余宽度
     *   收边  bodyRight-capW+1..bodyRight   右端那条压暗的边线，同样只按比例放大
     * </pre>
     * 三段用<b>同一个</b>缩放比（= 目标高 ÷ 底带高，这里正好 4），所以接缝处的行高、颜色完全对齐，
     * 2 节到 16 节都看不出接缝；车头形状在任何节数下都是原样。
     *
     * <p><b>青色像素一律透明</b>（{@link #isCyan}）：素材两侧那圈青色是「条区自己的颜色」，
     * 底图里本来就有 —— 跳过它，底图的青蓝色就从叠加层下面透出来，条区颜色因此<b>恒定为底图那一档</b>，
     * 带子收短 / 拉长都不可能出现「带子后面多出一块青色矩形」或比底图亮一档的拼缝。
     *
     * <p><b>从 dstX0 起</b>：整段在条区里水平居中（{@link #bandX0}），所以底带不再固定贴左缘。
     *
     * <p>采样一律最近邻（放大时就是 4×4 块复制）——底带是纯色块，插值只会把它抹软。
     */
    private static void paintBand(final int[] pixels, final BufferedImage band,
                                  final int dstX0, final int dstBandW) {
        final int dstW = overlayWidth();
        final int dstH = overlayHeight();
        final int target = Math.min(dstW - dstX0, dstBandW);
        if (target <= 0 || dstX0 >= dstW) {
            return;
        }
        final int srcH = band.getHeight();
        final float scale = dstH / (float) srcH;

        segments(band);
        final int leftW = GEOM_LEFT;
        final int capW = GEOM_CAP;
        final int bodyW = GEOM_BODY;                      // ← 带体右缘：青色余边不算
        final int midSrc = Math.max(1, bodyW - leftW - capW);
        final int capSrcX0 = bodyW - capW;

        final int dstLeft = Math.min(target, Math.round(leftW * scale));
        final int dstCap = Math.min(target - dstLeft, Math.round(capW * scale));
        final int dstMid = Math.max(1, target - dstLeft - dstCap);

        for (int y = 0; y < dstH; y++) {
            final int sy = Math.min(srcH - 1, (int) (y / scale));
            final int row = y * dstW;
            for (int x = 0; x < target; x++) {
                final int sx;
                if (x < dstLeft) {
                    // 左段：只按比例放大（不拉伸）→ 车头形状不变
                    sx = Math.min(leftW - 1, (int) (x / scale));
                } else if (x >= target - dstCap) {
                    // 收边：带体右缘那条暗边线，只按比例放大 → 右端收边不变形
                    sx = capSrcX0 + Math.min(capW - 1, (int) ((x - (target - dstCap)) / scale));
                } else {
                    // 中段：纯色，横向拉伸填满
                    sx = leftW + Math.min(midSrc - 1,
                            (int) ((x - dstLeft) * (float) midSrc / dstMid));
                }
                final int argb = band.getRGB(sx, sy);
                if (isCyan(argb)) {
                    // 条区自己的青蓝色：不画，让底图那层透出来
                    continue;
                }
                final int px = dstX0 + x;
                if (px < 0 || px >= dstW) {
                    continue;
                }
                pixels[row + px] = argb | 0xFF000000;
            }
        }
    }

    /**
     * 左段宽度：逐行找「不是青色背景」的最左列，取最大值 +2（= 斜车头最外沿再留 2px）。
     * 完全由底带像素决定，换底带不用改代码。
     */
    private static int leftSegment(final BufferedImage band) {
        int maxFirst = 0;
        for (int y = 2; y < band.getHeight() - 2; y++) {
            for (int x = 0; x < band.getWidth(); x++) {
                if (!isCyan(band.getRGB(x, y))) {
                    maxFirst = Math.max(maxFirst, x);
                    break;
                }
            }
        }
        return Math.min(band.getWidth() - 2, maxFirst + 3);
    }

    /**
     * <b>带体右缘</b>（源列，含）= 逐行找「深蓝带体」的最右列，取最大值。
     * <p>素材右边那 20 列青色光晕不是带体 —— 这一列往右的像素一个都不画（见 {@link #paintBand}）。
     * 换底带不用改代码，量出来的值会跟着变。
     */
    static int bodyRight(final BufferedImage band) {
        int right = -1;
        for (int y = 3; y < band.getHeight() - 2; y++) {
            for (int x = band.getWidth() - 1; x >= 0; x--) {
                if (isNavy(band.getRGB(x, y))) {
                    right = Math.max(right, x);
                    break;
                }
            }
        }
        return right < 0 ? band.getWidth() - 1 : right;
    }

    /**
     * 带体右缘那圈「压暗的边线」有几列：从 {@code bodyRight} 往左数，
     * 蓝通道比带内纯色暗 3 以上的列。这段按原比例放大（不拉伸），
     * 带子右端那条边线才不会在长编组下被拉成一片、也不会被插值抹掉。
     */
    private static int edgeRun(final BufferedImage band, final int bodyRight) {
        final int y = band.getHeight() / 2;
        final int interior = band.getRGB(Math.max(0, bodyRight - 20), y) & 0xFF;   // 带内纯色的蓝
        int run = 1;
        for (int x = bodyRight - 1; x >= 0 && run < 8; x--) {
            if (Math.abs((band.getRGB(x, y) & 0xFF) - interior) <= 3) {
                break;
            }
            run++;
        }
        return run;
    }

    /** 底带的青色边（r 低、g/b 高）—— 用来划三段。 */
    private static boolean isCyan(final int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int gg = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        return r < 110 && gg > 150 && b > 150;
    }

    /** 底带的深蓝带体（r/g 低、b 高）—— 用来找带体右缘。 */
    private static boolean isNavy(final int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int gg = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        return b > 150 && r < 60 && gg < 60;
    }

    // ================================================================ 切片 keying

    /**
     * 切片预处理：把「深蓝底」变成透明，好让底带从卡片圆角外透出来。
     *
     * <p>底图的深蓝是 {@code (0,0,~228)}，底带的深蓝是 {@code (0,0,205..220)} —— 两者差 10~20，
     * 要是把切片整块不透明地盖上去，卡片四周会现出一圈「深蓝比底带亮一点」的方块。
     * 所以按<b>红/绿通道</b>做 alpha（蓝底 r=g=0，白卡 r=g≈230，雪花 g≈215）：
     * {@code alpha = min(255, max(r, g) × 255 / 96)} —— 纯底 0、抗锯齿边缘自然过渡、
     * 白卡 / 绿框 / 雪花满不透明。颜色本身一个字节都不动（keying 只动 alpha）。
     */
    private static BufferedImage keyed(final BufferedImage src, final boolean card) {
        final BufferedImage cached = card ? CACHED_CARD_KEYED : CACHED_FLAKE_KEYED;
        if (cached != null) {
            final BufferedImage cachedFor = card ? CACHED_CARD_SRC : CACHED_FLAKE_SRC;
            if (cachedFor == src) {
                return cached;
            }
        }
        final int w = src.getWidth();
        final int h = src.getHeight();
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int argb = src.getRGB(x, y);
                final int r = (argb >> 16) & 0xFF;
                final int g = (argb >> 8) & 0xFF;
                final int a = Math.min(255, Math.max(r, g) * 255 / KEY_FULL);
                out.setRGB(x, y, (a << 24) | (argb & 0x00FFFFFF));
            }
        }
        if (card) {
            CACHED_CARD_KEYED = out;
            CACHED_CARD_SRC = src;
        } else {
            CACHED_FLAKE_KEYED = out;
            CACHED_FLAKE_SRC = src;
        }
        return out;
    }

    private static BufferedImage CACHED_CARD_KEYED;
    private static BufferedImage CACHED_CARD_SRC;
    private static BufferedImage CACHED_FLAKE_KEYED;
    private static BufferedImage CACHED_FLAKE_SRC;
}
