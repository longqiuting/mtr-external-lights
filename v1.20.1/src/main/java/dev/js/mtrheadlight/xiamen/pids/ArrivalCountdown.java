// 到站倒计时的「档位判定」：到站写「已到站」，绝不写「0秒 / 0分钟」。
//
// 纯函数（不 import 任何 MC 类）：判定 + 语言键都在这里，PIDS 与 PSD-LCD 共用一份，
// 离线断言（tools/xiamen-text-fit/FitCheck.java）直接调 state()/key() 并读语言文件核对文案。
package dev.js.mtrheadlight.xiamen.pids;

/**
 * 到站行里那个「X 秒 / X 分钟 / 即将进站 / 已到站」的档位。
 *
 * <h2>四条规则（按顺序，先命中先返回）</h2>
 * <pre>
 *   1. remaining ≤ 0                      → ARRIVED   已到站（中）/ Arrived（英）
 *   2. arriving 开关开 &amp;&amp; remaining ≤ 60  → ARRIVING  即将进站
 *   3. remaining &gt; 60                     → MINUTES   X 分钟 / X min
 *   4. 其余                                → SECONDS   X 秒 / X s
 * </pre>
 *
 * <p><b>第 1 条不看 {@code arriving} 开关</b>：那个开关只管「一分钟以内要不要写即将进站」，
 * 不管「已经到了」这件事。以前第 1 条并在这条 if 里，于是秒数模式（{@code arriving=off}）
 * 一到站就显示 {@code 0秒}、分钟模式会显示 {@code 0分钟} —— 读起来像坏了。
 * 现在到站这一档是<b>两种模式共用</b>的，而且判断在取数字之前，所以「0」这个数字永远出不来。
 *
 * <p>「已停稳」不另取信号：MTR 的到站表里，这条班次在发车之前一直留着，
 * {@code arrivalMillis} 一直在过去 —— 也就是说从进站到发车之间它恒为 {@code ARRIVED}，
 * 整段停站时间都在显示「已到站」（发车后这条被下一班替换）。
 */
public final class ArrivalCountdown {

    private ArrivalCountdown() {
    }

    /** 「即将进站」的阈值（秒）：一分钟以内不再写「X 秒」，直接写「即将进站」。 */
    public static final long ARRIVING_SECONDS = 60L;

    /** 语言键：PIDS 屏的「已到站」。 */
    public static final String KEY_ARRIVED_PIDS = "gui.mtrlte.pids.arrived";
    /** 语言键：PSD-LCD 挂板的「已到站」（两套屏各留各的键，措辞可以分开改）。 */
    public static final String KEY_ARRIVED_PSD_LCD = "gui.mtrlte.psd_lcd.arrived";
    /** 语言键：即将进站。 */
    public static final String KEY_ARRIVING = "gui.mtrlte.pids.arriving";
    /** 语言键：「分钟」/「min」。 */
    public static final String KEY_MINUTE = "gui.mtrlte.pids.minute";
    /** 语言键：「秒」/「s」。 */
    public static final String KEY_SECOND = "gui.mtrlte.pids.second";

    /** 倒计时处在哪一档。 */
    public enum State {
        /** 已到站（剩余 ≤ 0）：文案取 {@link #KEY_ARRIVED_PIDS} / {@link #KEY_ARRIVED_PSD_LCD}，没有单位。 */
        ARRIVED,
        /** 一分钟以内：文案取 {@link #KEY_ARRIVING}，没有单位。 */
        ARRIVING,
        /** 一分钟以上：数字取分钟，单位取 {@link #KEY_MINUTE}。 */
        MINUTES,
        /** 一分钟以内且没开「即将进站」：数字取秒，单位取 {@link #KEY_SECOND}。 */
        SECONDS
    }

    /**
     * 判档。
     *
     * @param remainingSeconds 到站还有多少秒（{@code (arrivalMillis − now) / 1000}，向零截断）
     * @param arriving         {@code PidsXiamenSettings#arriving()}：只影响「一分钟以内写不写即将进站」
     */
    public static State state(final long remainingSeconds, final boolean arriving) {
        // 1) 到站：两种模式共用，且先于取数字 —— 「0 秒 / 0 分钟」不可能出现。
        if (remainingSeconds <= 0L) {
            return State.ARRIVED;
        }
        if (arriving && remainingSeconds <= ARRIVING_SECONDS) {
            return State.ARRIVING;
        }
        return remainingSeconds > 60L ? State.MINUTES : State.SECONDS;
    }

    /**
     * 这一档要写出来的数字（纯数字串）。
     *
     * @return {@link State#ARRIVED} / {@link State#ARRIVING} → {@code ""}（这两档是文字，不是数字）；
     *         其余按档取分钟 / 秒，且<b>永远 ≥ 1</b>（档位判定已经保证了 remaining ≥ 1）
     */
    public static String digits(final State state, final long remainingSeconds) {
        return switch (state) {
            case ARRIVED, ARRIVING -> "";
            case MINUTES -> String.valueOf(Math.max(1L, remainingSeconds / 60L));
            case SECONDS -> String.valueOf(Math.max(1L, remainingSeconds));
        };
    }

    /** 这一档的单位语言键（没有单位 = {@code null}）。 */
    public static String unitKey(final State state) {
        return switch (state) {
            case ARRIVED, ARRIVING -> null;
            case MINUTES -> KEY_MINUTE;
            case SECONDS -> KEY_SECOND;
        };
    }

    /**
     * 这一档的状态文案语言键（只有 {@link State#ARRIVED} / {@link State#ARRIVING} 有）。
     *
     * @param psdLcd {@code true} = PSD-LCD 挂板（用自己的「已到站」键）；{@code false} = PIDS
     */
    public static String stateKey(final State state, final boolean psdLcd) {
        return switch (state) {
            case ARRIVED -> psdLcd ? KEY_ARRIVED_PSD_LCD : KEY_ARRIVED_PIDS;
            case ARRIVING -> KEY_ARRIVING;
            case MINUTES, SECONDS -> null;
        };
    }
}
