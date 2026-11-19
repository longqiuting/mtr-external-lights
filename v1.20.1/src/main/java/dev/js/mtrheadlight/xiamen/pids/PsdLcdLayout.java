// 厦门「屏蔽门顶板 LCD 挂板」（PSD-LCD）的**面板几何**：一块屏多大、贴在哪、屏幕四角在哪。
//
// 排版 / 绘制全部在 PsdLcdPainter（纯 Java2D，离线预览也用它），这里只剩下世界几何，
// 所以这个类不许 import 任何 MC 类（PsdLcdBlock 的碰撞箱、离线几何自检也要用它）。
//
// 方块外形 = 参考模组 mtr_psd_lcd 1.2.8 的 `psd_top_lcd14_groove_*_base`（MIT，见 NOTICE §9）
// 的横向版：箱体 6px 厚、前面挖 1.6px 深的插屏凹槽、窗口底那块面就是屏幕面。
//
//   箱体      z 0 … 6（厚 6px = 0.375 格），x 0 … 16（整格宽），y 1 … 16（从天花板吊下来）
//   底板       y 0 … 1 往回缩 1px（z 1 … 6）+ 下沿两段 22.5° 倒角 —— MTR psd_top 的 Bottom part / curve
//   顶板       y 15.5 … 16（0.5px 薄片，贴天花板）
//   屏幕窗口  前面（z=0）y 1.5 … 8.5 的整格宽口子，凹进去 1.6px → 屏幕面在 z = 1.6
//
// 剪刀（PsdLcdBlock#onUse + HALF 属性）可以把**一格**剪成半格：模型换成
// psd_lcd_xiamen_half_left.json（model x 8…16）/ _half_right.json（0…8），
// 世界几何改用 windowQuad(Face, startPx, endPx) 的「区间」版本 ——
// 高度、凹槽、板厚都不变，只沿铺开轴切一半，所以位图宽高比仍然等于窗口宽高比。
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
     * （{@code tools/psd-lcd/PsdLcdGeometryCheck.java}）里跑，不能拖 MC 依赖。
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

    // ================================================================ 块内几何（16 分之几）

    /** 屏幕窗口下沿（方块底面以上多少 16 分之格）：1.5px。同模型 ScreenPlane 的 {@code from.y}。 */
    public static final float WINDOW_Y0_PX = 1.5F;

    /** 屏幕窗口上沿：8.5px（高 7px）。同模型 ScreenPlane 的 {@code to.y}。 */
    public static final float WINDOW_Y1_PX = 8.5F;

    /** 屏幕窗口左沿 / 右沿：整格宽（横向连接时每格都接上，所以是连续的）。 */
    public static final float WINDOW_X0_PX = 0.0F;
    public static final float WINDOW_X1_PX = 16.0F;

    /** 屏幕窗口的宽高比（= 位图必须满足的宽高比，按 1 段算）。 */
    public static final float WINDOW_ASPECT = (WINDOW_X1_PX - WINDOW_X0_PX) / (WINDOW_Y1_PX - WINDOW_Y0_PX);

    /** 屏幕面离凹槽口（方块外面）的余量（16 分之格）：0.016px，只为了不和模型面 z-fighting。 */
    public static final float FACE_GAP_PX = 0.016F;

    /** 吊板前面离格子外侧多远（16 分之格）：MTR {@code psd_top} 的箱体在 z 8..16，
     *  前脸就在 8 —— 我们的板必须占同一个后半格，否则会从隔壁原版吊板前面凸出来。 */
    public static final float PANEL_Z0_PX = 0.0F;

    /** 吊板厚度（16 分之格）：z 0..6，与 MTR 吊板同厚度、外面齐平。 */
    public static final float BOARD_THICKNESS_PX = 6.0F;

    /** 屏幕凹进去多深（16 分之格）：极浅槽 0.5px = 1/32 格，只留一道刻线。 */
    public static final float THICKNESS_PX = 0.5F;

    /** 屏幕窗口在世界里的高度（格）= 7/16。 */
    public static final float HEIGHT_BLOCKS = (WINDOW_Y1_PX - WINDOW_Y0_PX) / 16.0F;

    /** 屏幕高度（px）。**不随段数变化**。 */
    public static final float HEIGHT_PX = PX_PER_BLOCK * HEIGHT_BLOCKS;

    /** 屏幕窗口下沿 / 上沿的世界高度（格，方块底面以上）。 */
    public static final float WINDOW_Y0_BLOCKS = WINDOW_Y0_PX / 16.0F;
    public static final float WINDOW_Y1_BLOCKS = WINDOW_Y1_PX / 16.0F;

    /**
     * 屏幕面离方块外面多远（格）= 1.584/16 = 0.099。BER 只认这一个数，模型改了它跟着走。
     *
     * <p>旧版这里是 {@code 0.5 - 0.401} 那种「0.5 减一半」的算法（屏幕面在凹槽底），
     * 但 BER 把它当成了屏幕**中线**，于是整块画面从 y=5px 往下长、溢出到吊板下面 ——
     * 现在改成「窗口上沿 + 四角坐标」，见 {@link #windowQuad(Face, int)}。
     */
    public static final float WINDOW_DEPTH_BLOCKS = (PANEL_Z0_PX + THICKNESS_PX - FACE_GAP_PX) / 16.0F;

    /** 屏幕平面中线的世界高度（格，方块底面以上）：1.5 与 8.5 的中点 = 5px（只有调试用）。 */
    public static final float CENTER_Y_BLOCKS = ((WINDOW_Y0_PX + WINDOW_Y1_PX) * 0.5F) / 16.0F;

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
     * 位图宽度（px），按面板在阅读轴上实际占的像素数算（半格 = 8px、整格 = 16px，
     * 一条链可以是 8+16+16 这种和）。
     *
     * <p>和 {@link #windowQuad(Face, float, float)} 用的是同一个 {@code spanPx}，
     * 所以「位图多宽」和「四边形多宽」永远是同一个数 → 画面不会被拉伸。
     */
    public static int bitmapWidthFor(final float spanPx) {
        return Math.max(1, Math.round(Math.max(1.0F, spanPx) / 16.0F * PX_PER_BLOCK * BITMAP_SCALE));
    }

    /** 位图高度（px）。**不随段数变化**。 */
    public static int bitmapHeight() {
        return Math.max(1, Math.round(HEIGHT_PX * BITMAP_SCALE));
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
     * 模型里 ScreenPlane 那块面。
     *
     * @return {@code float[4][3]}：{{x,y,z} × 4}
     */
    public static float[][] windowQuad(final Face facing, final int segments) {
        return windowQuad(facing, 0.0F, Math.max(1, segments) * 16.0F);
    }

    /**
     * 只画链条里的<b>一段</b>：{@code [startPx, endPx]} 是这段面板在阅读轴上的位置
     * （单位 = 16 分之格，从 anchor 那一格<b>观察者左边缘</b>量起，可以超过 16 = 伸进下一格）。
     *
     * <p>半格剪裁就靠这个重载：整格传 {@code (0,16)}，左半 {@code (0,8)}，右半 {@code (8,16)}；
     * 相邻的「本格右半 + 下一格左半」是 {@code (8,16)} 与 {@code (16,24)}，首尾相接、不重叠。
     * 老的四参数版本 {@code (0, segments*16)} 与旧行为逐顶点相同。
     */
    public static float[][] windowQuad(final Face facing, final float startPx, final float endPx) {
        final Face f = facing == null ? Face.NORTH : facing;
        final Face right = f.right();
        final float from = Math.min(startPx, endPx) / 16.0F;
        final float to = Math.max(startPx, endPx) / 16.0F;
        final float y0 = WINDOW_Y0_BLOCKS;
        final float y1 = WINDOW_Y1_BLOCKS;
        // 屏幕面在法线方向上的坐标：north/west 从 0 往内量，south/east 从 1 往内量。
        final float plane = f.negativeAxis() ? WINDOW_DEPTH_BLOCKS : 1.0F - WINDOW_DEPTH_BLOCKS;
        // 起点 = anchor 那一格「背对 right」的那条边（= 观察者看来的左边缘）。
        final float startX = 0.5F - right.dx * 0.5F;
        final float startZ = 0.5F - right.dz * 0.5F;

        if (right.dx != 0) {
            // facing 是 north / south：x 是铺开的方向，z 固定在屏幕面上
            final float x0 = startX + right.dx * from;
            final float x1 = startX + right.dx * to;
            return new float[][]{
                    {x0, y1, plane},
                    {x0, y0, plane},
                    {x1, y0, plane},
                    {x1, y1, plane}
            };
        }
        // facing 是 east / west：z 是铺开的方向，x 固定在屏幕面上
        final float z0 = startZ + right.dz * from;
        final float z1 = startZ + right.dz * to;
        return new float[][]{
                {plane, y1, z0},
                {plane, y0, z0},
                {plane, y0, z1},
                {plane, y1, z1}
        };
    }

    /** 屏幕法线（世界坐标单位向量）。 */
    public static float[] screenNormal(final Face facing) {
        final Face f = facing == null ? Face.NORTH : facing;
        return new float[]{f.dx, 0.0F, f.dz};
    }
}
