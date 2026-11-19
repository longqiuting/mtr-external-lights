// 字体：和厦门普通 PIDS / 线路条带**完全同一套**（XiamenStripPainter#font()）。
// 纯 AWT，不 import 任何 MC 类 —— 渲染器和离线预览因此拿到的是同一套字体决策。
//
// 这里刻意**不再**读资源包里的字体（旧版 mtr:xmlcd/fonts/{misans.otf,roboto.ttf}）：
// 那块屏和普通的厦门 PIDS 挂在一起，两处字体不一样最扎眼；而普通 PIDS 用的是系统黑体
// （SimHei → 微软雅黑 → Noto Sans SC，见 XiamenStripPainter#resolveFont），这里照抄即可。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Font;

import dev.js.mtrheadlight.xiamen.XiamenStripPainter;

/**
 * 字体解析：全部委托给 {@link XiamenStripPainter#font()}（线路条带 / 方向板的同一个入口）。
 *
 * <p>三支字的定义（全部来自同一族，只有字重不同 —— 实拍里中文比英文粗一档）：
 * <ul>
 *   <li>{@link #cjk()}    —— 中文，<b>BOLD</b>（= 线路条带的站名字重）；</li>
 *   <li>{@link #latin()}  —— 英文行（Bound for / min / Wednesday），<b>PLAIN</b>；</li>
 *   <li>{@link #digits()} —— 大数字（7 / 17），同样是那一族的 <b>BOLD</b>。
 *       旧版单独挑一个 "…Light" 细字重，在屏上是另一种笔画（数字明显比中文细），
 *       和普通 PIDS 的数字对不上；现在统一。</li>
 * </ul>
 */
public final class PsdLcdTypeface {

    private static Font cachedCjk;
    private static Font cachedLatin;

    private PsdLcdTypeface() {
    }

    /** 中文：线路条带那支黑体（BOLD）。 */
    public static Font cjk() {
        if (cachedCjk == null) {
            final Font base = XiamenStripPainter.font();
            cachedCjk = base == null ? new Font(Font.SANS_SERIF, Font.BOLD, 100)
                    : base.deriveFont(Font.BOLD, 100.0F);
        }
        return cachedCjk;
    }

    /** 英文 / 拉丁行：同一族的常规字重。 */
    public static Font latin() {
        if (cachedLatin == null) {
            final Font base = XiamenStripPainter.font();
            cachedLatin = base == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 100)
                    : base.deriveFont(Font.PLAIN, 100.0F);
        }
        return cachedLatin;
    }

    /** 大数字：同一族的粗体（和普通 PIDS 的数字一致）。 */
    public static Font digits() {
        return cjk();
    }

    /** 字体名（日志 / 预览输出用）。 */
    public static String name(final Font font) {
        if (font == null) {
            return "-";
        }
        return font.getPSName() + "@" + Math.round(font.getSize2D())
                + (font.isBold() ? "/bold" : "/plain");
    }
}
