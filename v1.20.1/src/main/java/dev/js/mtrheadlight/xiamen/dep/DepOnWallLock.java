// 挂墙发车倒数器：当前锁定的那一条班次 + 单调状态机。
package dev.js.mtrheadlight.xiamen.dep;

import mtr.data.ScheduleEntry;

/**
 * 一块挂墙倒数器<b>当前锁定的那一条班次</b>，以及把它走完的那台小状态机。
 *
 * <h2>为什么需要「锁」</h2>
 * <p>JCM 的 {@code RenderDepartureTimer} 每帧现取 {@code SCHEDULES_FOR_PLATFORM} 排序后的第 1 条，
 * 数值只由 {@code (arrivalMillis - now) / 1000} 决定。MTR 的这张表是<b>服务端周期性整表推送</b>的
 * （{@code updateSchedule} → {@code clearAndAddAll}），每次推送都可能：
 * <ul>
 *   <li>把同一条班次的 {@code arrivalMillis} 重算成新的值（列车晚点 / 停站时间重估）；</li>
 *   <li>删掉刚发车的条目，于是「第 1 条」直接换成下一班；</li>
 *   <li>换掉整个集合，{@code Collections.sort} 之后的顺序也随之变化。</li>
 * </ul>
 * 每帧重取第 1 条的后果就是实机看到的那一幕：<b>绿字数到 2 突然弹回 5 接着数</b>
 * （arrivalMillis 被推后了几秒，{@code |sec|} 变小），下一次推送再弹一次 —— 屏幕上没有任何一次
 * 倒数是完整的。
 *
 * <p>所以这里把「用哪一条班次」从每帧的 {@code list.get(0)} 变成<b>一块牌子自己记住的一个
 * {@code arrivalMillis}</b>（{@link #matches} 同时比 {@code routeId}），一旦这条班次<b>开始倒数</b>
 * 就不再换人，直到它自己走完（{@link #finished}）。服务端再怎么推、排序再怎么变，这块牌子数
 * 的都是同一条班次。
 *
 * <h2>为什么还需要单调保护</h2>
 * <p>锁住了 {@code arrivalMillis} 还不够：{@code System.currentTimeMillis()} 在同一个 tick 里被
 * 多次读取、或系统对时 / 回档时<b>可能倒退</b>。数值一旦由时间直接算出来，倒退就会让绿字往回
 * 跳（甚至弹回「车还没到」的空屏）。所以显示值在这里过一道状态机：
 * <pre>
 *   blank ──(sec ≤ 0)──▶ green ──(remain 到 0)──▶ red ──(remain &gt; 5)──▶ 放锁
 *                           只减不增              只增不减
 * </pre>
 * 三个相位<b>只能单向走</b>：绿段 {@code min}、红段 {@code max}，时钟倒退时读数是「停住」而不是
 * 往回跳；开过车以后 {@code sec} 也被夹到 ≤ 0，不会因为时间倒退弹回空屏。
 *
 * <p>数值公式仍然是 JCM 的原式，一个字没动：
 * {@code remain = |dwellHalf - |(arrivalMillis - now) / 1000||}（先减再除，向零截断）。
 * 单调钳制只会让它在时钟异常时停住，正常走时它<b>本来就</b>是单调的。
 */
final class DepOnWallLock {

    /** {@code 00:00} 之后红灯正计多少秒（数到它就黑屏，等下一班）。 */
    static final int OVERTIME_SECONDS = 5;

    /** 锁定的站台 id：不然牌子旁边换了站台还在拿旧班次倒数。 */
    private final long platformId;
    /** 锁定班次的到站时刻 —— <b>这</b>就是「锁」的本体。 */
    private final long arrivalMillis;
    private final long routeId;
    /** 锁定那一刻站台的 dwell / 2（整数除法）。之后站台数据被重推也不改，保证基准不动。 */
    private final int dwellHalf;

    /** 是否已经进入可见倒数（{@code sec <= 0} 过）。之前屏幕是空的，改锁不会有可见跳变。 */
    private boolean started;
    /** 是否已经进入红段（递增段）。 */
    private boolean overdue;
    /** 红段数完 5 秒：这一帧之后就放锁。 */
    private boolean done;
    /** 上一帧显示的值，单调钳制的锚点。 */
    private int lastRemain;

    private DepOnWallLock(final long platformId, final ScheduleEntry entry, final int dwellHalf) {
        this.platformId = platformId;
        this.arrivalMillis = entry.arrivalMillis;
        this.routeId = entry.routeId;
        this.dwellHalf = Math.max(0, dwellHalf);
    }

    /** 锁一条班次。 */
    static DepOnWallLock of(final long platformId, final ScheduleEntry entry, final int dwellHalf) {
        return new DepOnWallLock(platformId, entry, dwellHalf);
    }

    // ------------------------------------------------------------------ 身份 / 生命周期

    /** 是不是同一条班次：到站时刻 + 线路（和 {@link ScheduleEntry#compareTo} 的排序键一致）。 */
    boolean matches(final ScheduleEntry entry) {
        return entry != null && entry.arrivalMillis == arrivalMillis && entry.routeId == routeId;
    }

    boolean samePlatform(final long platformId) {
        return platformId == this.platformId;
    }

    long arrivalMillis() {
        return arrivalMillis;
    }

    int dwellHalf() {
        return dwellHalf;
    }

    /** 已经进入可见倒数（绿段或红段）。之前换锁不会有可见跳变。 */
    boolean started() {
        return started;
    }

    /** 这一帧之后该放锁了（红灯 5 秒走完）。 */
    boolean done() {
        return done;
    }

    /** 这条班次的显示窗口是不是已经走完了（绿 dwellHalf 秒 + 红 {@value #OVERTIME_SECONDS} 秒）。 */
    boolean finished(final long now) {
        return finished(arrivalMillis, dwellHalf, now);
    }

    /**
     * 这条班次的显示窗口是不是已经走完了。
     *
     * <p>和 {@link #tick} 里的 {@code remain > OVERTIME_SECONDS} 是<b>同一个判据</b>
     * （同一套截断、同一个公式），所以「放锁」之后这条班次一定会被选班的循环跳过，
     * 不会出现「刚放锁又被选回来、立刻再放锁」的每帧循环。
     */
    static boolean finished(final long arrivalMillis, final int dwellHalf, final long now) {
        final long sec = (arrivalMillis - now) / 1000L;
        // 还没到站：永远不算走完（那只是「离得还远」）。
        return sec <= 0L && Math.abs(sec) > (long) Math.max(0, dwellHalf) + OVERTIME_SECONDS;
    }

    // ------------------------------------------------------------------ 数值 / 单调状态机

    /** 这一帧该显示什么：{@code null} = 空屏（车没到站，或者红灯已经数完）。 */
    Reading tick(final long now) {
        if (done) {
            return null;
        }
        final long secRaw = (arrivalMillis - now) / 1000L;

        if (!started) {
            // 车还没到站 → 空屏（JCM 就是这里 return；不是显示 0:00）。
            if (secRaw > 0L) {
                return null;
            }
            started = true;
            final long abs = Math.abs(secRaw);
            // 挂上牌子时车早就到点 / 已经开走 → 直接进红段（dwellHalf = 0 时也走这里）。
            overdue = abs >= dwellHalf;
            lastRemain = remain(abs);
        } else {
            // 开过车就不再回到「车还没到站」：时钟倒退把 sec 夹在 0，读数停住而不是弹回空屏。
            final long sec = Math.min(0L, secRaw);
            final long abs = Math.abs(sec);
            if (!overdue) {
                if (abs >= dwellHalf) {
                    // 这一帧恰好跨过 0:00（或时钟往前跳）：绿段收在 00:00，下一帧起是红段 0。
                    overdue = true;
                    lastRemain = 0;
                } else {
                    // 绿段只减不增。
                    lastRemain = Math.min(remain(abs), lastRemain);
                }
            } else {
                // 红段只增不减。
                lastRemain = Math.max(remain(abs), lastRemain);
            }
        }

        if (!overdue) {
            return new Reading(lastRemain, false);
        }
        if (lastRemain > OVERTIME_SECONDS) {
            done = true;
            return null;
        }
        return new Reading(lastRemain, true);
    }

    /** {@code |dwellHalf - |sec||}，JCM 的原式；异常大的值夹到 {@code int} 上限（那一定已经到了「走完」）。 */
    private int remain(final long absSec) {
        final long remain = Math.abs((long) dwellHalf - absSec);
        return (int) Math.min(Integer.MAX_VALUE, remain);
    }

    /** 一帧的读数：剩下的秒数 + 是不是红段（到点还没走）。 */
    record Reading(int remain, boolean overdue) {
    }
}
