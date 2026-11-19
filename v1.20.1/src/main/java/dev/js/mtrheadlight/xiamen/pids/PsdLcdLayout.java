// 厦门「屏蔽门顶板 LCD 挂板」（PSD-LCD）的**面板几何**：一块屏多大、贴在哪、缩放多少。
//
// 排版 / 绘制全部在 PsdLcdPainter（纯 Java2D，离线预览也用它），这里只剩下世界几何，
// 所以这个类不许 import 任何 MC 类（PsdLcdBlock 的碰撞箱也要用它）。
//
// 方块外形 = **MTR 普通吊板**（`mtr:block/psd_top_left_base`）的同一套轮廓，只是拉满整格宽：
//
//   箱体      z 0 … 6（厚 6px = 0.375 格），x 0 … 16（整格宽），y 1 … 16（从天花板吊下来）
//   底部台阶  y 0 … 1 往回缩 1px（z 1 … 6）—— MTR 的「Bottom part」
//   下沿倒角  前下角 z 0…1 / y 0…1 两段 22.5° 斜面 —— MTR 的「Bottom curve」
//   屏幕窗口  前面（z=0）挖出 y 1.5 … 8.5 的口子，凹进去 1.6px（z=1.6 是屏幕面）
//             ← 参考模组 `mtr_psd_lcd` 的 `psd_top_lcd14_groove_*` 模型：箱体 0…6、
//               屏幕面在 4.4（= 离外面 1.6px）、上下唇 1.6 / 15.1 的位置关系
//
// 屏幕画面由 BER 画在**凹槽底**（离方块外面 1.6px）那张面上，位置跟着模型走；
// 上下蒙皮（FrontSkinUpper / Lower）各压住窗口 0.4px，所以真正露出来的口子是 y 2.0 … 15.1。
// 但**贴图（位图）仍按 7px 整带**出图 —— 口子边缘被蒙皮遮住的那 0.4px 本来就是黑框，
// 这样 3 段 = 6.86:1 的设计稿比例不会因为模型改形而变形（3 段 = 1560×231）。
//
// 7px 高 × 1 格宽 ⇒ 每段 2.29:1；三段 = 6.86:1 ≈ 实拍/示意的 6.91:1，
// 四段 = 9.14:1（和 docs/mtr-psd-top-research.md §1.2 里「4 格 → aspect 9.11」一致）。
package dev.js.mtrheadlight.xiamen.pids;

/**
 * 面板几何：屏幕平面宽 {@code 520px × 段数}、高 {@value #HEIGHT_PX}px，原点左上。
 *
 * <pre>
 *   520 px = 1 格宽     231.1 px = 7/16 格高（= 7px of a 16px block）
 *   自带 3 段 = 1560×231 px = 6.75:1（设计稿 6.91:1）  4 段 = 9.14:1
 * </pre>
 *
 * 高度<b>不随段数变</b>：横向连接时只有宽度变宽，多出来的宽度由
 * {@link PsdLcdPainter} 分给「中间的空隙」（右边界始终跟着内容区右缘走）。
 */
public final class PsdLcdLayout {

    private PsdLcdLayout() {
    }

    /** 每格（= 每段）横向多少屏幕像素。同时也是世界缩放的倒数。 */
    public static final float PX_PER_BLOCK = 520.0F;

    // ================================================================ 块内几何（16 分之几）

    /** 屏幕窗口下沿（方块底面以上多少 16 分之格）：MTR 顶板面板的底边 1.5px。 */
    public static final float PANEL_Y0_PX = 1.5F;

    /** 屏幕窗口上沿：MTR 顶板面板的顶边 8.5px（高 7px）。 */
    public static final float PANEL_Y1_PX = 8.5F;

    /** 屏幕面到凹槽口（方块外面）的余量（16 分之格）：0.016px，只为了不 z-fighting。 */
    public static final float FACE_GAP_PX = 0.016F;

    /** 吊板厚度（16 分之格）：和 MTR {@code psd_top_left_base} 的箱体一样 6px。 */
    public static final float BOARD_THICKNESS_PX = 6.0F;

    /** 屏幕凹进去多深（16 分之格）：参考模组插屏凹槽的深度 1.6px。 */
    public static final float THICKNESS_PX = 1.6F;

    /** 屏幕在世界里的高度（格）= 7/16。 */
    public static final float HEIGHT_BLOCKS = (PANEL_Y1_PX - PANEL_Y0_PX) / 16.0F;

    /** 屏幕高度（px）。**不随段数变化**。 */
    public static final float HEIGHT_PX = PX_PER_BLOCK * HEIGHT_BLOCKS;

    /** 渲染缩放：{@code matrices.scale(1 / 520)} 之后 1px = 1/520 格。 */
    public static final float SCREEN_SCALE_DIVISOR = PX_PER_BLOCK;

    /**
     * 屏幕面在「朝外那一面」**里面**多少格：{@code 0.5 - 1.584/16 = 0.401}。
     *
     * <p>旧模型是一块贴在方块外面的薄板，这里原本是 {@code 0.5 + 余量}（0.516 = 探出半格外）。
     * 现在方块变成了 6px 厚的吊板，屏幕退到凹槽底（离外面 1.6px），所以改成减：
     * 0.5 − (1.6 − 0.016)/16 = 0.401 格。BER 只认这一个数，模型改了它跟着走。
     */
    public static final float ANCHOR_OUT = 0.5F - (THICKNESS_PX - FACE_GAP_PX) / 16.0F;

    /** 屏幕平面中线的世界高度（格，方块底面以上）：1.5 与 8.5 的中点 = 5px。 */
    public static final float CENTER_Y_BLOCKS = ((PANEL_Y0_PX + PANEL_Y1_PX) * 0.5F) / 16.0F;

    /**
     * 位图相对面板像素的倍率：Java2D 在位图空间画，贴图按 2 倍出（231px 高的屏上用
     * 33px 的中文字，缩到面板尺寸后边缘才不糊）。
     */
    public static final float BITMAP_SCALE = 2.0F;

    /** 这块屏的宽度（px）。 */
    public static float widthPx(final int segments) {
        return Math.max(1, segments) * PX_PER_BLOCK;
    }

    /** 位图宽度（px）。 */
    public static int bitmapWidth(final int segments) {
        return Math.max(1, Math.round(widthPx(segments) * BITMAP_SCALE));
    }

    /** 位图高度（px）。 */
    public static int bitmapHeight() {
        return Math.max(1, Math.round(HEIGHT_PX * BITMAP_SCALE));
    }
}
