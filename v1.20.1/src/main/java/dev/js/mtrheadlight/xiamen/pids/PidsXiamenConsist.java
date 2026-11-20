// 厦门 PIDS（地铁）顶部编组条的**动态叠加层**：只重画那条编组，面板其余像素不动。
//
// 背景（2026-11 第二版）：`pids_xiamen_metro.png` 顶部那条编组（青色带 + 深蓝「车头」抽象
// 形状 + 一排 1车..6车 卡片）**是烘死在底图里的静态像素**，节数永远是 6。这一版把它换成
// 「底图照旧 + 在这块矩形上再贴一张小贴图 + 车号仍然走 TextRenderer」：
//
//   <b>贴图层（这里，4× 位图）只放图形，一个字的文字都没有：</b>
//   ① 底带 = 用户单独给的 `consist_band.png`（333×33，纯底带：深蓝圆角带 + 左端斜车头 +
//      两端露青色，**不带卡片也不带字**），按<b>三段</b>铺（{@link #paintBand}）：
//      左段（斜车头，35px）与右段（收边，21px）只按比例放大、绝不横向拉伸，
//      中段（277px 纯色）横向拉伸填满 —— 所以 2 节到 16 节车头形状都不变形、也没有接缝。
//      它是干净的底带，盖上去就把底图里那排烘死的卡片整片遮住，不需要「擦除」这一步；
//   ② 卡片 = <b>从底图里像素级切出来的那一张</b>（`gui/pids/consist_card.png`，41×20，
//      底图坐标 x 210..250 / y 3..22）**按原尺寸平铺 N 次** —— 4× 时是逐像素块复制
//      （最近邻、不插值、不重采样），所以卡片和底图里烘死的那排一模一样锐，
//      不会再有「缩放取样带进来的绿竖条」。
//      <b>长编组只压 x 方向</b>：n·41 + (n−1)·5.6 &gt; 274 时 {@code cw = (274 − (n−1)·gap)/n}
//      把卡片横向压窄（高度永远是 20，最近邻不插值），<b>绝不重叠、绝不越界</b>；
//      整行<b>左对齐</b>到 x=210，排完剩下的空留在右侧（见 {@link #layout}）
//   ③ 雪花 = 同样从底图标签区切出来的那一颗（`gui/pids/consist_flake.png`，7×7，
//      底图坐标 x 228..234 / y 23..29），首 / 尾车 1 颗、中间车<b>永远 2 颗</b>
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
// <h2>量出来的数（脚本：{@code v1.20.1/tools/pids-xiamen/scan-consist-strip.js}）</h2>
// <pre>
//   编组带        x 179..511   y 0..32     = 333 × 33 px     ← 叠加层就贴这块矩形
//                归一化 x 0.3496..1.0000    y 0.0000..0.1404（÷512 / ÷235）
//   底图里烘死的：6 张卡片 x 210..483、每张 41 宽、间隙 5..6（平均 5.6）；
//                卡片 y 3..22（20 行）——  雪花/车号在它下面 y 23..29 那 7 行
//   切片矩形      卡片 (210, 3, 41, 20)      雪花 (228, 23, 7, 7)
// </pre>
//
// <h2>垂直布局（面板 px，底带 0..32、内圈深蓝 2..31）</h2>
// <pre>
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
 * {@link #layout(int)} 给出的位置上；{@code cars <= 0}（读不到编组）时
 * {@code paint} 返回 {@code null}，调用方就<b>不画</b>叠加层 —— 底图里那排静态画原样留着，屏不会空。
 */
public final class PidsXiamenConsist {

    // ---------------------------------------------------------------- 量出来的常量

    /** 叠加层分辨率倍数（1 面板像素 → 4 贴图像素）。 */
    public static final int SCALE = 4;

    /** 编组带在底图上的矩形（含首末像素）：x 179..511、y 0..32 → 333 × 33。 */
    public static final int STRIP_X0 = 179;
    public static final int STRIP_Y0 = 0;
    public static final int STRIP_W = 333;
    public static final int STRIP_H = 33;

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

    /** 卡片之间的自然间隙（面板像素）= 底图实测 5..6（平均 5.6）。 */
    public static final float GAP = 5.6F;

    /** 卡片左 / 右的可排范围（面板像素）：底图里烘死的 6 张卡片就是 210..483。 */
    public static final float ROW_X0 = 210.0F;
    public static final float ROW_X1 = 483.0F;

    /** 行宽（面板 px）= ROW_X1 − ROW_X0 + 1 = <b>274</b> —— 整行从这里起、到 483 止。 */
    public static final float ROW_W = ROW_X1 - ROW_X0 + 1.0F;

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
     * 内容版本：改了绘制逻辑就 +1 —— 渲染器用它和节数一起算内容哈希，
     * 哈希没变就不重传贴图（见 {@code PidsXiamenConsistTextures}）。
     */
    public static final int CONTENT_VERSION = 4;

    /** 切片 keying（把深蓝底变成透明）时，alpha 到这个亮度就满不透明。 */
    private static final int KEY_FULL = 96;

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
     * 底带三段的宽度（源像素）：{@code 左（斜车头）, 中（纯色）, 右（收边）}。
     * <p>报告 / 预览用 —— 游戏里 {@link #paintBand} 用的是同一套量法。
     */
    public static int[] bandSegments(final BufferedImage band) {
        if (band == null) {
            return new int[]{0, 0, 0};
        }
        final int left = Math.min(band.getWidth() - 2, leftSegment(band));
        final int right = Math.min(band.getWidth() - left - 1, rightSegment(band));
        return new int[]{left, Math.max(1, band.getWidth() - left - right), right};
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
     * 一排卡片的排版（节数 → 每张卡片的几何）。<b>整行左对齐</b>到 {@link #ROW_X0}
     * （底图里第一张卡的左缘 x=210），排完剩下的空留在右边。
     *
     * <pre>
     *   avail = ROW_W = 274                     ← 行区 210..483
     *   gap   = 5.6（自然）→ 需要时往下收，下限 2、硬下限 1
     *   cw    = 41（切片原宽）；只有 n·41 + (n−1)·gap &gt; 274 时才压 x：
     *           cw = (274 − (n−1)·gap) / n     ← 只压宽、不压高（仍是 20px）
     *   总宽  = n·cw + (n−1)·gap ≤ 274          ← 永不出界，卡片永不重叠
     *   x(i)  = ROW_X0 + i·(cw + gap)           ← 左对齐，空在右侧
     *   雪花  = 首 / 尾车 1 颗、中间车 2 颗；边长 7 → 6 → 5 → 4 挑第一个塞得下的
     *           预算 = cw − 留白 1 − 文字间隙 1 − 文字最小宽 5（见 {@link #flakeSizeFor}）
     *           右对齐到卡片右缘 −1px；两颗之间 2px
     *   文字  = 右对齐到雪花左边 −1px，宽度 = 那一段（超宽就缩字号，永不与雪花重叠）
     * </pre>
     *
     * <p>6 节时 {@code gap = 5.6}、{@code cw = 41}，画出来和底图里那排同位置同大小；
     * 8 节 {@code cw ≈ 29.4}（雪花仍是 7px）、10 节 6px、11 节 5px、12 节起 4px，
     * 13 节 {@code gap ≈ 3.3 / cw ≈ 18}（雪花 4px、文字缩到 5.5px 宽）——
     * 卡片只是变窄，<b>不会</b>再互相覆盖成一条链。
     * 压到 {@link #MIN_CARD_W} 还放不下时先收 gap
     * （不低于 {@link #GAP_MIN}，再不行 {@link #GAP_HARD_MIN}），最后才允许 cw 比 18 更窄。
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
        if (n > 1 && n * cw + (n - 1) * gap > ROW_W) {
            cw = Math.max(1.0F, (ROW_W - (n - 1) * gap) / n);
        }

        final float step = cw + gap;
        final float x0 = ROW_X0;                       // ← 左对齐：空留在右侧

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
        return n <= 0 ? CARD_W : (ROW_W - (n - 1) * gap) / n;
    }

    /** 反解：想让 {@code n} 节每张卡都至少 {@code w} 宽，间隙最多能有多大（面板 px）。 */
    private static float gapFor(final int n, final float w) {
        return n <= 1 ? GAP : (ROW_W - n * w) / (n - 1);
    }

    /**
     * 卡片 / 雪花是否整块落在底带内圈里（面板 px）—— 预览和单测拿它做断言。
     *
     * @return {@code true} = 卡片、雪花组、车号字形盒都在 y 2..31、x 0..332 之内
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
     * 画整条编组带（{@code 1332 × 132}）—— <b>只有图形，没有文字</b>。
     *
     * @param band   底带素材（{@code consist_band.png}，333×33，纯底带，不带卡片与文字）
     * @param card   卡片切片（{@code consist_card.png}，41×20，从底图像素级切出来）
     * @param flake  雪花切片（{@code consist_flake.png}，7×7）
     * @param cars   实际车厢数；{@code <= 0} 或素材缺失 → 返回 {@code null}（= 不画叠加层）
     * @return 4× 的 ARGB 位图，或 {@code null}
     */
    public static BufferedImage paint(final BufferedImage band, final BufferedImage card,
                                      final BufferedImage flake, final int cars) {
        if (cars <= 0) {
            // 读不到编组：不画叠加层，底图里那排静态画原样留着（屏不会空）
            return null;
        }
        if (band == null || band.getWidth() < 8 || band.getHeight() < 4
                || card == null || card.getWidth() < 2 || card.getHeight() < 2
                || flake == null || flake.getWidth() < 2 || flake.getHeight() < 2) {
            return null;
        }
        try {
            return paintUnsafe(band, card, flake, Math.min(MAX_CARS, cars));
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

        paintBand(pixels, band);

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

    /** 底带左段（斜车头）/ 右段（收边）的源像素宽度缓存。 */
    private static int CACHED_LEFT_W = -1;
    private static int CACHED_RIGHT_W = -1;
    private static BufferedImage CACHED_BAND_FOR_SEG;

    /**
     * 底带按<b>三段</b>铺（9-slice 的横向版）：
     * <pre>
     *   左段  x 0..leftW-1          含斜车头，按原像素等比放大，绝不横向拉伸
     *   中段  leftW..srcW-rightW-1  纯深蓝 + 上下那两条浅色边，横向拉伸填满剩余宽度
     *   右段  末尾 rightW 列        右端收边 / 圆角，同样只按比例放大
     * </pre>
     * 三段用<b>同一个</b>缩放比（= 目标高 ÷ 底带高，这里正好 4），所以接缝处的行高、颜色完全对齐，
     * 2 节到 16 节都看不出接缝；车头形状在任何节数下都是原样。
     *
     * <p>采样一律最近邻（放大时就是 4×4 块复制）——底带是纯色块，插值只会把它抹软。
     */
    private static void paintBand(final int[] pixels, final BufferedImage band) {
        final int dstW = overlayWidth();
        final int dstH = overlayHeight();
        final int srcW = band.getWidth();
        final int srcH = band.getHeight();
        final float scale = dstH / (float) srcH;

        if (CACHED_BAND_FOR_SEG != band) {
            CACHED_LEFT_W = leftSegment(band);
            CACHED_RIGHT_W = rightSegment(band);
            CACHED_BAND_FOR_SEG = band;
        }
        final int leftW = Math.max(1, Math.min(srcW - 2, CACHED_LEFT_W));
        final int rightW = Math.max(1, Math.min(srcW - leftW - 1, CACHED_RIGHT_W));
        final int midSrc = srcW - leftW - rightW;

        final int dstLeft = Math.round(leftW * scale);
        final int dstRight = Math.round(rightW * scale);
        final int dstMid = Math.max(1, dstW - dstLeft - dstRight);

        for (int y = 0; y < dstH; y++) {
            final int sy = Math.min(srcH - 1, (int) (y / scale));
            final int row = y * dstW;
            for (int x = 0; x < dstW; x++) {
                final int sx;
                if (x < dstLeft) {
                    // 左段：只按比例放大（不拉伸）→ 车头形状不变
                    sx = Math.min(leftW - 1, (int) (x / scale));
                } else if (x >= dstW - dstRight) {
                    // 右段：同上，只按比例放大 → 右端收边不变
                    sx = srcW - rightW + Math.min(rightW - 1, (int) ((x - (dstW - dstRight)) / scale));
                } else {
                    // 中段：纯色，横向拉伸填满
                    sx = leftW + Math.min(midSrc - 1,
                            (int) ((x - dstLeft) * (float) midSrc / dstMid));
                }
                pixels[row + x] = band.getRGB(sx, sy);
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

    /** 右段宽度：逐行找「不是青色背景」的最右列，取最小值 → 从那一列起到末尾就是右段。 */
    private static int rightSegment(final BufferedImage band) {
        int minLast = band.getWidth() - 1;
        for (int y = 2; y < band.getHeight() - 2; y++) {
            for (int x = band.getWidth() - 1; x >= 0; x--) {
                if (!isCyan(band.getRGB(x, y))) {
                    minLast = Math.min(minLast, x);
                    break;
                }
            }
        }
        return Math.max(2, band.getWidth() - minLast);
    }

    /** 底带的青色边（r 低、g/b 高）—— 用来划三段。 */
    private static boolean isCyan(final int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int gg = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        return r < 110 && gg > 150 && b > 150;
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
