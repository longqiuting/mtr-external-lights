// 厦门「屏蔽门顶板 LCD 挂板」（PSD-LCD）的**面板几何**：一块屏多大、贴在哪、缩放多少。
//
// 排版 / 绘制全部在 PsdLcdPainter（纯 Java2D，离线预览也用它），这里只剩下世界几何，
// 所以这个类不许 import 任何 MC 类（PsdLcdBlock 的碰撞箱也要用它）。
package dev.js.mtrheadlight.xiamen.pids;

/**
 * 面板几何：屏幕平面宽 {@code 520px × 段数}、高 {@value #HEIGHT_PX}px，原点左上。
 *
 * <pre>
 *   520 px = 1 格宽     160 px = 160/520 = 0.3077 格高（= 方块高度的 4.92/16）
 *   四段挂板 = 2080×160 px = 13:1；两段 = 1040×160 px ≈ 6.5:1
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

    /** 屏幕高度（px）。**不随段数变化**。 */
    public static final float HEIGHT_PX = 160.0F;

    /** 屏幕在世界里的高度（格）= 160/520。 */
    public static final float HEIGHT_BLOCKS = HEIGHT_PX / PX_PER_BLOCK;

    /** 渲染缩放：{@code matrices.scale(1 / 520)} 之后 1px = 1/520 格。 */
    public static final float SCREEN_SCALE_DIVISOR = PX_PER_BLOCK;

    /** 屏幕平面离开方块中心、朝外多少格（贴住方块外表面再让 4mm）。 */
    public static final float ANCHOR_OUT = 0.5F + 0.004F;

    /**
     * 位图相对面板像素的倍率：Java2D 在位图空间画，贴图按 2 倍出（160px 高的屏上用
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
