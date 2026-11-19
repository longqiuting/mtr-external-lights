// 厦门「屏蔽门顶板 LCD 挂板」（PSD-LCD）的**面板几何**：一块屏多大、贴在哪、缩放多少。
//
// 排版 / 绘制全部在 PsdLcdPainter（纯 Java2D，离线预览也用它），这里只剩下世界几何，
// 所以这个类不许 import 任何 MC 类（PsdLcdBlock 的碰撞箱也要用它）。
//
// 面板几何是**照着参考模组 `mtr_psd_lcd` + MTR 自己的顶板面板量出来的**
// （见 docs/psd-lcd-geometry.md，报告里也复述了数值）：
//
//   高度带   y 1.5 … 8.5（块内 16 分之几，高 7px = 0.4375 格）
//            ← MTR `RenderPSDTop` 的面板带：底边在方块底面上 1.5px、顶边 8.5px
//              （docs/mtr-psd-top-research.md §1.2 已实测）；参考模组的
//              `psd_top_lcd14_groove_*` 模型把这条带做成了插屏凹槽（上下唇 1.6 / 15.1）
//   厚度     1.6px（0.1 格）—— 参考模组凹槽 x 4.4…6.0 的深度
//   前面     与「朝外那一面」平齐（参考凹槽的面在 x=6 = 箱体外面）；
//            实际绘制时让 0.01px 的描边余量，避免和模型面 z-fighting
//   每段宽   1 格（16px）；整排两头各让 0.125px（MTR `sidePadding`，肉眼不可见，忽略）
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

    /** 面板下沿（方块底面以上多少 16 分之格）：MTR 顶板面板的底边 1.5px。 */
    public static final float PANEL_Y0_PX = 1.5F;

    /** 面板上沿：MTR 顶板面板的顶边 8.5px（高 7px）。 */
    public static final float PANEL_Y1_PX = 8.5F;

    /** 面板前面到「朝外那一面」的余量（16 分之格）：0.016px，只为了不 z-fighting。 */
    public static final float FACE_GAP_PX = 0.016F;

    /** 面板厚度（16 分之格）：参考模组插屏凹槽的深度 1.6px。 */
    public static final float THICKNESS_PX = 1.6F;

    /** 屏幕在世界里的高度（格）= 7/16。 */
    public static final float HEIGHT_BLOCKS = (PANEL_Y1_PX - PANEL_Y0_PX) / 16.0F;

    /** 屏幕高度（px）。**不随段数变化**。 */
    public static final float HEIGHT_PX = PX_PER_BLOCK * HEIGHT_BLOCKS;

    /** 渲染缩放：{@code matrices.scale(1 / 520)} 之后 1px = 1/520 格。 */
    public static final float SCREEN_SCALE_DIVISOR = PX_PER_BLOCK;

    /** 屏幕平面离开方块中心、朝外多少格（贴住朝外那一面，只留 0.01px 余量）。 */
    public static final float ANCHOR_OUT = 0.5F + FACE_GAP_PX / 16.0F;

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
