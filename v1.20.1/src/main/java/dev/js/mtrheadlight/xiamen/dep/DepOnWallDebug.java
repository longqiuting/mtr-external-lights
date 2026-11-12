// 挂墙发车倒数器的取证开关：/mtr_lte dep debug on|off，一行/秒。
package dev.js.mtrheadlight.xiamen.dep;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.util.math.BlockPos;

/**
 * 挂墙发车倒数器的<b>取证日志</b>（{@code /mtr_lte dep debug on|off}，默认关，只在内存里）。
 *
 * <h2>为什么光看屏幕不够</h2>
 * <p>「倒到 2 突然又变成 5」可以有很多来源：服务端推了一份新表（条目换了 / {@code arrivalMillis}
 * 被重算）、{@code dwellTime} 变了、时钟跳了、锁没生效。屏幕上只看得到结果，看不到原因。
 * 打开这个开关之后，每块牌子<b>每秒一行</b>：
 * <pre>
 * [lte] dep: pos=(12,64,-30) platform=47 entries=3 firstArrival=1733891234000 now=1733891235123
 *            sec=-12 dwell=20 dwellHalf=10 remain=0 phase=red lockArrival=1733891234000
 * </pre>
 * <ul>
 *   <li>{@code firstArrival} = 这一帧排序后的第 1 条班次（服务端刚推来的那张表）；</li>
 *   <li>{@code lockArrival} = 这块牌子<b>实际锁定</b>的那条班次；两者不一样就说明表已经换人了，
 *       而屏幕还在按老的那条数 —— 这正是「锁」在起作用；</li>
 *   <li>{@code sec} = 锁定班次的 {@code (arrivalMillis - now) / 1000}（JCM 原式，红段里是负数）；</li>
 *   <li>{@code phase} = {@code green / red / blank}。{@code entries} 和 {@code firstArrival}
 *       在没数据时是 {@code 0} / {@code none}。</li>
 * </ul>
 *
 * <h2>限流</h2>
 * <p>渲染器每帧都会调这里，所以按<b>方块坐标</b>限流：同一块牌子 1 秒最多一行（不同牌子各算各的，
 * 站台两头的牌子可以同时开着看）。开关关闭时连字符串都不拼（见 {@link #enabled()}），渲染路径上
 * 的开销只有一次 {@code volatile} 读。表里最多留 {@value #MAX_POSITIONS} 块牌子的时间戳，超了整表清掉
 * —— 它只是限流用的，丢了最多少打一行。
 */
public final class DepOnWallDebug {

    /** 同一块牌子的最小打印间隔。 */
    private static final long INTERVAL_MILLIS = 1000L;

    /** 限流表最多记多少块牌子（超过就整表清掉，不按 LRU 慢慢腾）。 */
    private static final int MAX_POSITIONS = 256;

    /** 开关；{@code volatile} 是因为指令线程写、渲染线程读。 */
    private static volatile boolean enabled;

    /** 每块牌子上次打印的时刻（渲染线程读、指令线程的 {@code off} 会清）。 */
    private static final Map<BlockPos, Long> LAST = new ConcurrentHashMap<>();

    private DepOnWallDebug() {
    }

    /** 开关状态。 */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * 打开 / 关闭。
     *
     * <p>切换时清掉限流表：{@code off} 之后立刻再 {@code on}，第一行马上就能打出来，
     * 不用等上一轮的 1 秒窗口。
     */
    public static void set(final boolean on) {
        enabled = on;
        LAST.clear();
        LteLog.LOGGER.info("[lte] dep debug = {}", on ? "on" : "off");
    }

    /** 给 {@code /mtr_lte dep debug} 的一行状态。 */
    public static String statusLine() {
        return "LTE：倒数器取证 = " + (enabled ? "on" : "off")
                + (enabled
                        ? "（每块牌子每秒一行 [lte] dep: …，带上 platform/entries/firstArrival/sec/dwell/phase）"
                        : "（默认关）")
                + " — /mtr_lte dep debug on|off";
    }

    /**
     * 打一行（限流之后）。{@code message} 由调用方拼好 —— 关闭时调用方根本不该调这里。
     *
     * @param pos 方块坐标，用作限流的 key
     * @param now 这一帧读到的墙钟（和日志里的 {@code now=} 是同一个数）
     */
    static void log(final BlockPos pos, final long now, final String message) {
        if (!enabled) {
            return;
        }
        try {
            final BlockPos key = pos.toImmutable();
            final Long last = LAST.get(key);
            if (last != null && now >= last && now - last < INTERVAL_MILLIS) {
                return;
            }
            LAST.put(key, now);
            if (LAST.size() > MAX_POSITIONS) {
                LAST.clear();
            }
            LteLog.LOGGER.info(message);
        } catch (Throwable t) {
            // 取证不能反过来把渲染搞挂：最多丢一行。
            try {
                LteLog.LOGGER.warn("[lte] dep debug: could not log", t);
            } catch (Throwable ignored) {
                // 日志系统本身挂了，那就什么都不做。
            }
        }
    }
}
