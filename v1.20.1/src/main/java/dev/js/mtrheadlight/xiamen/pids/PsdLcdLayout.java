// 厦门「屏蔽门顶板 LCD 挂板」（PSD-LCD）的**面板几何**：一块屏多大、贴在哪、屏幕四角在哪、
// 线路色带画在哪一条面上。
//
// 排版 / 绘制全部在 PsdLcdPainter（纯 Java2D，离线预览也用它），这里只剩下世界几何，
// 所以这个类不许 import 任何 MC 类（PsdLcdBlock 的碰撞箱、离线几何自检也要用它）。
//
// ============================================================================================
// 几何来自用户给的 Blockbench 工程 `psd_lcd_xiamen.json`（mornal / half 两组），由
// tools/psd-lcd-models.js 做一次**确定性刚性变换**搬进我们的坐标系（工具里逐元素断言等距 +
// 包围盒 + 反算角点，见 tools/psd-lcd-facing-check.js）。搬完之后：
//
//   箱体      z 10 … 16（厚 6px = 0.375 格，占格子后半格、和 MTR 的 psd_top 一样贴着天花板）
//             正面 = z 10（朝观察者），背面 = z 16
//   x 0 … 16  整格宽（横向连接时每格接上，所以是一条连续的屏）
//   y 0 … 16  整块（从天花板吊下来）；剪刀剪过只剩 y 7.5 … 16
//
//   **屏幕凹槽只有 0.2px**（用户的新模型：正面那一层蒙皮在 y 8.5…16 上凸出到 z 10，
//   窗口那一带退到 z 10.2）。屏幕面 = 凹槽底 z = 10.2，BER 四边形再往前 0.016px = 10.184。
//   窗口带 y 1.5 … 8.5（整块）/ 9 … 16（剪后），两条都是 7px 高、整格宽。
//
//   **线路色带**画在用户模型留出来的那条 0.5px 前脸上（z = 10）：
//     整块  y 1 … 1.5   （element "Main particle"，窗口下沿那条唇边）
//     剪后  y 8.5 … 9   （同一个 element 在 half 组里的位置）
//   MTR 的 RenderPSDTop 把它画在 y 14.5…15（COLOR_STRIP_START/END = 0.90625/0.9375），
//   我们的模型在那条高度上没有 0.5px 的专用面（剪过以后整块都是屏幕），所以以用户的模型为准，
//   差值见 docs/psd-lcd-line-strip.md。
// ============================================================================================
//
// 屏幕画面由 BER 画在凹槽底那张面上（比模型面再往前 0.016px，只为了不 z-fighting）。
// 位图 / 四边形的宽高比严格等于窗口的 16:7，所以窗内内容不会被拉伸（见 tools/psd-lcd-fit-check.js）。
//
// 7px 高 × 1 格宽 ⇒ 每段 2.286:1；三段 = 6.857:1 ≈ 实拍/示意的 6.91:1，
// 四段 = 9.14:1（和 docs/mtr-psd-top-research.md §1.2 里「4 格 → aspect 9.11」一致）。
package dev.js.mtrheadlight.xiamen.pids;

/**
 * 面板几何：屏幕平面宽 {@code 520px × 段数}、高 {@value #HEIGHT_PX}px，原点左上。
 *
 * <pre>
 *   520 px = 1 格宽     227.5 px = 7/16 格高
 *   自带 3 段 = 1560×227.5 px = 6.857:1（设计稿 6.91:1）  4 段 = 9.143:1
 * </pre>
 *
 * <p>高度<b>不随段数变</b>：横向连接时只有宽度变宽。位图尺寸按「段数」现算
 * （{@link #bitmapWidth(int)}），所以每接一格都重新算一遍，不会拿旧宽度的位图拉伸。
 */
public final class PsdLcdLayout {

    private PsdLcdLayout() {
    }

    /** 每格（= 每段）横向多少屏幕像素。同时也是世界缩放的倒数。 */
    public static final float PX_PER_BLOCK = 520.0F;

    // ================================================================ 屏幕朝向（无 MC 依赖）

    /**
     * 屏幕朝向（法线方向）= 方块 {@code FACING} 的值。
     *
     * <p>用一个本地枚举而不是 {@code net.minecraft.util.math.Direction}：这个类要在离线自检
     * （{@code tools/psd-lcd-facing-check.js}）里跑，不能拖 MC 依赖。
     */
    public enum Face {
        NORTH(0, -1),
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0);

        /** 法线在 x 轴上的分量（-1 / 0 / 1）。 */
        public final int dx;
        /** 法线在 z 轴上的分量（-1 / 0 / 1）。 */
        public final int dz;

        Face(final int dx, final int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        /** 反方向。 */
        public Face opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }

        /**
         * 观察者站在屏幕正面看时，他<b>右手</b>指的方向（= 屏幕 +x / 阅读方向）。
         *
         * <p>推导：观察者面朝 {@code this.opposite()}（正对屏幕），right = forward × up。
         * 例：facing=NORTH 时观察者在北侧朝南看，右手朝西 → WEST。
         */
        public Face right() {
            return switch (this) {
                case NORTH -> WEST;
                case WEST -> SOUTH;
                case SOUTH -> EAST;
                case EAST -> NORTH;
            };
        }

        /**
         * 方块状态里 {@code facing=<this>} 需要的 {@code y} 旋转角（度）。
         *
         * <p>模型本身朝北（前面 = z 0 那一面），所以 north=0 / east=90 / south=180 / west=270 ——
         * 和 vanilla 熔炉那种「模型朝北、facing 表朝向」的约定一致，也和
         * {@code blockstates/psd_lcd_xiamen.json} 里写死的四个值一致。
         * {@code tools/psd-lcd-facing-check.js} 会把这两处对一遍。
         */
        public int blockstateY() {
            return switch (this) {
                case NORTH -> 0;
                case EAST -> 90;
                case SOUTH -> 180;
                case WEST -> 270;
            };
        }

        /** 法线是不是指向负半轴（north / west）—— 屏幕面离方块外表面就按这个方向量。 */
        public boolean negativeAxis() {
            return this.dx < 0 || this.dz < 0;
        }
    }

    // ================================================================ 板子在格子里的位置

    /**
     * 吊板正面（朝观察者那一面）在格子里的 z（16 分之格）：10。
     *
     * <p>6px 的板子整个占格子的<b>后半格</b>（z 10…16），和 MTR 吊板在它自己朝向上贴着方块
     * 背侧那一层是同一个意思 —— 正面落在 10，才不会从隔壁原版吊板前面凸出来。
     */
    public static final float PANEL_Z0_PX = 10.0F;

    /** 吊板背面（格子后半格的外缘）在格子里的 z。 */
    public static final float PANEL_Z1_PX = 16.0F;

    /** 吊板厚度（16 分之格）：z 10…16，6px，与 MTR 吊板同厚度。 */
    public static final float BOARD_THICKNESS_PX = 6.0F;

    /**
     * 屏幕凹槽深度（16 分之格）：<b>0.2px</b>。
     *
     * <p>来自用户的新模型：正面那层蒙皮（mornal 组的第 5 个元素、half 组没有）凸出到 z = 10，
     * 窗口那一带退到 z = 10.2。<b>别按 MTR 的 1.6px 写</b> —— 上一版就是这么来的，
     * 屏幕面因此落在 10.5，和现在的模型差 0.3px。
     */
    public static final float SCREEN_RECESS_PX = 0.2F;

    /** 屏幕面（凹槽底）在格子里的 z：{@code 10 + 0.2 = 10.2}。 */
    public static final float SCREEN_FACE_Z_PX = PANEL_Z0_PX + SCREEN_RECESS_PX;

    /** 屏幕面离凹槽口（方块外面）的余量（16 分之格）：0.016px，只为了不和模型面 z-fighting。 */
    public static final float FACE_GAP_PX = 0.016F;

    // ================================================================ 屏幕窗口（16 分之几）

    /**
     * 屏幕窗口下沿（整块）= 1.5px。
     *
     * <p>= 窗口上方那条 0.5px 唇边（element 0，y 1…1.5）的上沿；模型里 y 1.5 以下就是正面
     * 那一层（z 10），1.5 以上退到凹槽底（z 10.2）。
     */
    public static final float WINDOW_Y0_PX = 1.5F;

    /** 屏幕窗口上沿（整块）= 8.5px（高 7px）= 上半块蒙皮的下沿。 */
    public static final float WINDOW_Y1_PX = 8.5F;

    /**
     * 剪过（{@code half_cut=true}）以后的窗口下沿：<b>9px</b> —— half 组里板子只占 y 7.5…16：
     * 7.5…8.5 是底板台阶、8.5…9 是那条 0.5px 唇边，9 以上才是凹槽底。
     */
    public static final float CUT_WINDOW_Y0_PX = 9.0F;

    /** 剪过以后的窗口上沿：16px（贴方块顶），窗口仍然是 7px 高。 */
    public static final float CUT_WINDOW_Y1_PX = 16.0F;

    /** 屏幕窗口左沿 / 右沿：整格宽（横向连接时每格都接上，所以是连续的）。 */
    public static final float WINDOW_X0_PX = 0.0F;
    public static final float WINDOW_X1_PX = 16.0F;

    /**
     * 屏幕窗口的 y 带（16 分之格）：{@code {下沿, 上沿}}。
     *
     * <p>剪刀只改<b>高度方向的那一格带</b>：整块 1.5…8.5，剪后 9…16（两条都是 7px 高，
     * 所以位图高度不变，但下沿整体上移 —— 板子下半截被剪掉了）。
     */
    public static float[] windowBandPx(final boolean cut) {
        return cut
                ? new float[]{CUT_WINDOW_Y0_PX, CUT_WINDOW_Y1_PX}
                : new float[]{WINDOW_Y0_PX, WINDOW_Y1_PX};
    }

    /** 屏幕窗口的 y 带（格，方块底面以上）：{@code {下沿, 上沿}}。 */
    public static float[] windowBandBlocks(final boolean cut) {
        final float[] px = windowBandPx(cut);
        return new float[]{px[0] / 16.0F, px[1] / 16.0F};
    }

    /**
     * 屏幕窗口的宽高比（= 位图必须满足的宽高比，按 1 段算）。两种状态都是 16:7。
     */
    public static final float WINDOW_ASPECT = (WINDOW_X1_PX - WINDOW_X0_PX) / (WINDOW_Y1_PX - WINDOW_Y0_PX);

    /** 屏幕窗口在世界里的高度（格）= 7/16（整块；剪后按 {@link #windowBandPx(boolean)} 算）。 */
    public static final float HEIGHT_BLOCKS = (WINDOW_Y1_PX - WINDOW_Y0_PX) / 16.0F;

    /** 屏幕高度（px）。**不随段数变化**，也不随剪不剪变（两条窗口带都是 7px）。 */
    public static final float HEIGHT_PX = PX_PER_BLOCK * HEIGHT_BLOCKS;

    /** 屏幕窗口下沿 / 上沿的世界高度（格，方块底面以上）——整块；剪后用 {@link #windowBandBlocks(boolean)}。 */
    public static final float WINDOW_Y0_BLOCKS = WINDOW_Y0_PX / 16.0F;
    public static final float WINDOW_Y1_BLOCKS = WINDOW_Y1_PX / 16.0F;

    /** 剪后屏幕窗口下沿 / 上沿的世界高度（格）。 */
    public static final float CUT_WINDOW_Y0_BLOCKS = CUT_WINDOW_Y0_PX / 16.0F;
    public static final float CUT_WINDOW_Y1_BLOCKS = CUT_WINDOW_Y1_PX / 16.0F;

    /**
     * 屏幕面离方块外面多远（格）= 10.184/16 = 0.6365。BER 只认这一个数，模型改了它跟着走。
     */
    public static final float WINDOW_DEPTH_BLOCKS = (SCREEN_FACE_Z_PX - FACE_GAP_PX) / 16.0F;

    /** 屏幕平面中线的世界高度（格，方块底面以上）：1.5 与 8.5 的中点 = 5px（只有调试用）。 */
    public static final float CENTER_Y_BLOCKS = ((WINDOW_Y0_PX + WINDOW_Y1_PX) * 0.5F) / 16.0F;

    // ================================================================ 线路色带

    /**
     * 线路色带（整块那一档）的 y 带：<b>1 … 1.5px</b>。
     *
     * <p>就是用户模型里那条 0.5px 唇边（element 0 "Main particle"，z 10…15 的前脸）。
     * MTR 的 {@code RenderPSDTop} 把它画在 14.5…15（{@code COLOR_STRIP_START=0.90625}、
     * {@code COLOR_STRIP_END=0.9375}，都在「从顶往下量」的局部坐标里）；我们的模型在那条高度上
     * 没有 0.5px 的专用面（y 8.5…16 整片都是 z=10 的大面），所以以用户的模型为准：
     * 整块画在 1…1.5（比 MTR 低 13px）。
     */
    public static final float STRIP_Y0_PX = 1.0F;

    /** 线路色带上沿（整块）：1.5px —— 正好是窗口下沿，色带不会压到屏幕上。 */
    public static final float STRIP_Y1_PX = 1.5F;

    /**
     * 线路色带（剪过那一档）的 y 带：<b>8.5 … 9px</b>。
     *
     * <p>同一个元素在 half 组里的位置（y 8.5…9 的唇边）。剪过以后 y 9…16 整块都是屏幕，
     * 只有这一条 0.5px 的前脸还在，所以色带跟着下移到那里（比 MTR 低 6px）。
     */
    public static final float CUT_STRIP_Y0_PX = 8.5F;

    /** 线路色带上沿（剪过）：9px = 剪后窗口下沿。 */
    public static final float CUT_STRIP_Y1_PX = 9.0F;

    /** 色带高度（px）：两种状态都是 0.5px（模型里那条唇边的厚度）。 */
    public static final float STRIP_HEIGHT_PX = STRIP_Y1_PX - STRIP_Y0_PX;

    /**
     * 色带画在哪个 z 上（格）：板子正面（z=10）再往前一个 {@link #FACE_GAP_PX}，
     * 和屏幕四边形同一个防 z-fighting 的做法。两种状态一样（唇边都在 z = 10）。
     */
    public static final float STRIP_DEPTH_BLOCKS = (PANEL_Z0_PX - FACE_GAP_PX) / 16.0F;

    /** 色带的 y 带（16 分之格）：{@code {下沿, 上沿}}。 */
    public static float[] stripBandPx(final boolean cut) {
        return cut
                ? new float[]{CUT_STRIP_Y0_PX, CUT_STRIP_Y1_PX}
                : new float[]{STRIP_Y0_PX, STRIP_Y1_PX};
    }

    /** 色带的 y 带（格）：{@code {下沿, 上沿}}。 */
    public static float[] stripBandBlocks(final boolean cut) {
        final float[] px = stripBandPx(cut);
        return new float[]{px[0] / 16.0F, px[1] / 16.0F};
    }

    /**
     * 色带的一条（或一段）四边形，顶点顺序和 {@link #windowQuad} 一样：
     * <b>左上 → 左下 → 右下 → 右上</b>（站在屏幕正面看），v 从上到下。
     *
     * @param startPx / endPx 这一段在阅读轴上的位置（16 分之格，从 anchor 那一格的左缘量起）
     * @param cut             这一格剪过没有（决定 y 带）
     */
    public static float[][] stripQuad(final Face facing, final float startPx, final float endPx,
                                      final boolean cut) {
        final float[] band = stripBandPx(cut);
        return planeQuad(facing, startPx, endPx, band[0], band[1], STRIP_DEPTH_BLOCKS);
    }

    /**
     * 色带的一条四边形，y 带用<b>相对量</b>给（0…1 = 整条色带从下到上）——
     * 一个站台有多条线路时，把 0.5px 高的色带按颜色数均分成几条横带
     * （和 MTR 把 1×N 的色带纹理拉满整条带子一个意思）。
     */
    public static float[][] stripQuad(final Face facing, final float startPx, final float endPx,
                                      final boolean cut, final float v0, final float v1) {
        final float[] band = stripBandPx(cut);
        final float h = band[1] - band[0];
        return planeQuad(facing, startPx, endPx, band[0] + h * Math.min(v0, v1),
                band[0] + h * Math.max(v0, v1), STRIP_DEPTH_BLOCKS);
    }

    // ================================================================ 位图尺寸

    /**
     * 位图相对面板像素的倍率：Java2D 在位图空间画，贴图按 2 倍出（227.5px 高的屏上用
     * 33px 的中文字，缩到面板尺寸后边缘才不糊）。
     */
    public static final float BITMAP_SCALE = 2.0F;

    /** 这块屏的宽度（px，世界单位）。 */
    public static float widthPx(final int segments) {
        return Math.max(1, segments) * PX_PER_BLOCK;
    }

    /** 位图宽度（px）。段数变了就跟着变 —— 每段固定 {@code 520 × BITMAP_SCALE}。 */
    public static int bitmapWidth(final int segments) {
        return Math.max(1, Math.round(widthPx(segments) * BITMAP_SCALE));
    }

    /**
     * 位图宽度（px），按面板在阅读轴上实际占的像素数算（一格 = 16px，一条链可以是 n × 16）。
     *
     * <p>和 {@link #windowQuad(Face, float, float)} 用的是同一个 {@code spanPx}，
     * 所以「位图多宽」和「四边形多宽」永远是同一个数 → 画面不会被拉伸。
     */
    public static int bitmapWidthFor(final float spanPx) {
        return Math.max(1, Math.round(Math.max(1.0F, spanPx) / 16.0F * PX_PER_BLOCK * BITMAP_SCALE));
    }

    /**
     * 位图高度（px）：**不随段数变化**，但跟着窗口高度走 —— 剪过以后窗口带是 9…16
     * （仍然 7px，所以数值和整块一样，但这里是按 {@link #windowBandPx(boolean)} 现算的，
     * 哪天 half 组换了带高，位图自动跟上，不会拉伸）。
     */
    public static int bitmapHeight(final boolean cut) {
        final float[] band = windowBandPx(cut);
        final float blocks = (band[1] - band[0]) / 16.0F;
        return Math.max(1, Math.round(blocks * PX_PER_BLOCK * BITMAP_SCALE));
    }

    /** 位图高度（px），整块那一档（= {@code bitmapHeight(false)}）。 */
    public static int bitmapHeight() {
        return bitmapHeight(false);
    }

    // ================================================================ 屏幕四角（世界坐标）

    /**
     * 屏幕四角的世界坐标（相对 anchor 那一格的最小角 {@code (0,0,0)}）。
     *
     * <p>顺序固定为<b>左上 → 左下 → 右下 → 右上</b>（站在屏幕正面看），正好对上贴图的
     * {@code (0,0) (0,1) (1,1) (1,0)} —— BER 直接拿这四行发顶点，不做任何矩阵旋转，
     * 所以「画到哪」和「模型窗口在哪」不可能再走岔（旧版是 translate 到中线 + rotY/rotZ，
     * 少一个 0.4375 格的高度就整块掉到吊板下面去了）。
     *
     * <p>整条链从 anchor（观察者看来的最左一格）出发，沿 {@link Face#right()} 铺
     * {@code segments} 格；宽度 = {@code segments × 1 格}，高度 = 7/16 格，严丝合缝贴住
     * 模型里凹槽底那一层（z = 10.2）。
     *
     * @return {@code float[4][3]}：{{x,y,z} × 4}
     */
    public static float[][] windowQuad(final Face facing, final int segments) {
        return windowQuad(facing, 0, segments, false);
    }

    /** 整条链（{@code segments} 格）的四个顶点，使用整块那一档的窗口带。 */
    public static float[][] windowQuad(final Face facing, final float startPx, final float endPx) {
        return windowQuad(facing, startPx, endPx, false);
    }

    /** {@link #windowQuad(Face, int)} 的 y 带可控版本：一格一段，剪不剪由调用方给。 */
    public static float[][] windowQuad(final Face facing, final int segments, final boolean cut) {
        return windowQuad(facing, 0, segments, cut);
    }

    /**
     * 只画链条里的<b>一段</b>：{@code [startPx, endPx]} 是这段面板在阅读轴上的位置
     * （单位 = 16 分之格，从 anchor 那一格<b>观察者左边缘</b>量起，可以超过 16 = 伸进下一格）。
     *
     * <p>y 带按「剪不剪」取：整块 1.5…8.5，剪后 9…16（{@link #windowBandPx(boolean)}）；z 两种状态
     * 都是 {@code WINDOW_DEPTH_BLOCKS}（= 10.184/16）—— 两套模型的凹槽底都在 z = 10.2。
     * 剪刀不再沿铺开轴切，所以正常调用就是「一格 = 一整段」{@code [16i, 16i+16)}；
     * 区间重载留着让相邻两段的接缝用同一份算术算出来。
     */
    public static float[][] windowQuad(final Face facing, final float startPx, final float endPx,
                                       final boolean cut) {
        final float[] band = windowBandPx(cut);
        return planeQuad(facing, startPx, endPx, band[0], band[1],
                (SCREEN_FACE_Z_PX - FACE_GAP_PX) / 16.0F);
    }

    /** 屏幕法线（世界坐标单位向量）。 */
    public static float[] screenNormal(final Face facing) {
        final Face f = facing == null ? Face.NORTH : facing;
        return new float[]{f.dx, 0.0F, f.dz};
    }

    /**
     * 位图的**子矩形** → 世界四点（左上 → 左下 → 右下 → 右上），顶点顺序与
     * {@link #windowQuad(Face, float, float, boolean)} 完全一致。
     *
     * <p>给「压在屏幕面上的第二条内容」用（滚动标语那张小贴图）：它占位图里的一块矩形，
     * 必须落在同一个平面上，而且和窗口四边形的铺开轴 / 朝向翻转走同一份算术 —— 否则大屏、
     * 半格、四个 facing 里总有一档会画偏。
     *
     * @param totalPx 整条链在阅读轴上的总宽（16 分之格，= 段数 × 16）
     * @param fx0/fx1 子矩形在整块位图上的横向比例（0 = 左缘、1 = 右缘）
     * @param fy0/fy1 纵向比例（0 = 上缘、1 = 下缘；位图 v 从上到下）
     * @param cut     这一格剪过没有（决定 y 带是 1.5…8.5 还是 9…16）
     * @param liftPx  再沿法线往外推多少（16 分之格），只为了「压在屏幕面上」时别共面
     */
    public static float[][] bandQuad(final Face facing, final float totalPx,
                                     final float fx0, final float fx1,
                                     final float fy0, final float fy1,
                                     final boolean cut, final float liftPx) {
        final float[] band = windowBandPx(cut);
        final float startPx = Math.min(fx0, fx1) * totalPx;
        final float endPx = Math.max(fx0, fx1) * totalPx;
        final float span = band[1] - band[0];
        final float top = band[1] - Math.min(fy0, fy1) * span;
        final float bottom = band[1] - Math.max(fy0, fy1) * span;
        return planeQuad(facing, startPx, endPx, bottom, top,
                (SCREEN_FACE_Z_PX - FACE_GAP_PX - liftPx) / 16.0F);
    }

    // ================================================================ 一块面板上的四边形

    /**
     * 面板上任意一条横带的世界四点：左上 → 左下 → 右下 → 右上（站在屏幕正面看）。
     *
     * <p>屏幕四边形和线路色带共用这一段算术 —— 两者唯一的差别是「y 带」和「z 平面」，
     * 铺开轴、左右起点、朝向翻转全在同一个地方算，所以不可能出现「色带画到了屏幕背面」
     * 那种错位。
     *
     * @param y0Px / y1Px 这一条横带的上下沿（16 分之格，方块底面以上）
     * @param zBlocks     画在哪个 z 上（格；<b>从方块外面往里的距离</b>）
     */
    private static float[][] planeQuad(final Face facing, final float startPx, final float endPx,
                                       final float y0Px, final float y1Px, final float zBlocks) {
        final Face f = facing == null ? Face.NORTH : facing;
        final Face right = f.right();
        final float from = Math.min(startPx, endPx) / 16.0F;
        final float to = Math.max(startPx, endPx) / 16.0F;
        final float y0 = Math.min(y0Px, y1Px) / 16.0F;
        final float y1 = Math.max(y0Px, y1Px) / 16.0F;
        // 面在法线方向上的坐标：north/west 从 0 往内量，south/east 从 1 往内量。
        final float plane = f.negativeAxis() ? zBlocks : 1.0F - zBlocks;
        // 起点 = anchor 那一格「背对 right」的那条边（= 观察者看来的左边缘）。
        final float startX = 0.5F - right.dx * 0.5F;
        final float startZ = 0.5F - right.dz * 0.5F;

        if (right.dx != 0) {
            // facing 是 north / south：x 是铺开的方向，z 固定在那个面上
            final float x0 = startX + right.dx * from;
            final float x1 = startX + right.dx * to;
            return new float[][]{
                    {x0, y1, plane},
                    {x0, y0, plane},
                    {x1, y0, plane},
                    {x1, y1, plane}
            };
        }
        // facing 是 east / west：z 是铺开的方向，x 固定在那个面上
        final float z0 = startZ + right.dz * from;
        final float z1 = startZ + right.dz * to;
        return new float[][]{
                {plane, y1, z0},
                {plane, y0, z0},
                {plane, y0, z1},
                {plane, y1, z1}
        };
    }
}
