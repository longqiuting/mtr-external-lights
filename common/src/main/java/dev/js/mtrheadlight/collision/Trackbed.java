package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.LteLog;
import mtr.client.ClientData;
import net.minecraft.client.world.ClientWorld;

import java.util.Locale;

/**
 * 道床几何的<b>客户端门面</b>：把 {@code ClientData.RAILS} 喂给 {@link RailBed}，
 * 并把「这一 tick 的判定过程」记下来给 {@code /mtr_lte trackbed status} 读。
 *
 * <p>几何、缓存、横向列投影、弧长插值全在 {@link RailBed}（纯计算，服务端也用同一份，见
 * {@code server.ServerTrackbed}）。这里只剩三件事：客户端从哪取轨道表、这一 tick 的取证数据、
 * 以及 {@code /mtr_lte trackbed} 那一行诊断。
 *
 * <p>客户端只处理<b>本地玩家</b>：{@code Entity#move} 注入把人「踩」在轨面上。
 * 其它实体（掉落物、生物、下落的方块）由服务端的 {@code server.ServerTrackbed} 接住。
 *
 * <h2>模型：横向列 + 弧长插值，不看 onGround</h2>
 * 判定拆成两半，各自是一个纯函数，都能单测：
 * <ul>
 *   <li><b>横向</b>（{@link RailBed} 的列投影）：把人的水平位置投影到最近的轨道折线段上，
 *       得到有符号横向偏移 {@code offset = (q - p) · N}，{@code N = T × UP}。{@code |offset| ≤ }
 *       {@link #HALF_WIDTH} 就算踩在道床上 —— 用的是法向而不是包围盒，弯道内侧外侧同时正确。</li>
 *   <li><b>纵向</b>（{@link RailBed#verdict}）：脚底与轨面的高差 {@code dy} 是否落在
 *       {@code [-STEP_UP, +CEIL]} 里，再按「正在起跳 / 从上方穿过 / 贴着往下走」分档。
 *       轨面高度是对<b>投影点弧长参数</b> {@code s} 解析求出来的（{@link RailBed#railY}），
 *       所以坡道上「脚下这一点」永远是对的，坡度自然跟随，不需要任何 y 阈值。</li>
 * </ul>
 *
 * <h2>{@code /mtr_lte trackbed status}</h2>
 * 用户报「站在轨道上、上坡/过弯就掉下去」时，这一行就是答案所在：
 * <pre>[lte] trackbed: rails=N hit=yes|no y=… dist=… off=… halfW=… dy=… onGround=… moved=…
 *                 drop=snap|dy|lat|nohit|jump|block|space snapped=true|false
 *                 drops=snap:57 dy:0 lat:0 nohit:0 …</pre>
 * 读法：
 * <ul>
 *   <li>{@code hit=no} + {@code dist=-} → {@code drop=nohit}：缓存里根本没有近处的轨道（人真的不在
 *       轨道上，或者轨道表没加载）；</li>
 *   <li>{@code drop=lat} → 轨道找到了，横向偏移超了半宽（{@code off} 会给出数值和正负）；</li>
 *   <li>{@code drop=dy} → 横向踩在道床上，但脚底与轨面的高差不在窗口里（{@code dy} 会给出数值，
 *       负得很多 = 上坡的轨面在脚底之上，正得很多 = 人在轨面上方腾空）；</li>
 *   <li>{@code drop=jump} → 起跳那一 tick，故意让开，不算掉；</li>
 *   <li>{@code drop=block} / {@code drop=space} → 命中了，被真实方块 / 空间不足挡掉（旧版
 *       「snapped=false 而 hit=yes」就是这个形态）。</li>
 * </ul>
 * {@code drops=} 是<b>自上一行诊断以来</b>各结论的计数（只统计「上一个 tick 还踩在道床上」的 tick，
 * 所以站在野地里不会刷 {@code nohit}），走一遍坡道就能从计数上直接读出是哪一种，不用猜。
 */
public final class Trackbed {

    /** 可站立半宽（格）：轨道中心线两侧各这么宽都能站。 */
    public static final double HALF_WIDTH = RailBed.HALF_WIDTH;

    /** 查询点周围多远的轨道参与判定（格）。 */
    public static final double SEARCH_RADIUS = RailBed.SEARCH_RADIUS;

    /** 上坡可以抬升的最大高度（格）的默认值：一级台阶。 */
    public static final double DEFAULT_STEP_UP = RailBed.DEFAULT_STEP_UP;

    /** 横向列的查询结果（{@link RailBed.Sample} 的客户端视图）。 */
    public static final class Sample {
        /** 轨道面高度（= {@code Rail#getPosition} 在横向列处的 y，与列车底板同一水平面）。 */
        public final double y;
        /** 查询点到轨道中心线的水平距离（格）。 */
        public final double distance;
        /** 有符号横向偏移（格），{@code N = T × UP} 一侧为正。 */
        public final double offset;
        /** 横向列对应的弧长参数（格）。 */
        public final double along;
        /** 轨道全长（格）。 */
        public final double length;

        Sample(final double y, final double distance, final double offset, final double along,
               final double length) {
            this.y = y;
            this.distance = distance;
            this.offset = offset;
            this.along = along;
            this.length = length;
        }
    }

    private static final RailBed BED = new RailBed();

    /** 这一 tick 的道床有没有真的把人按上去（{@code CollisionSystem#trackbed} 写）。 */
    private static volatile boolean snappedThisTick;

    /** 上一个完整 tick 的按压状态 —— 命令/日志读到的是这个（同一个 tick 内的值才有意义）。 */
    private static volatile boolean snappedLastTick;

    /** 取证开关（{@code /mtr_lte trackbed on|off}）：只在内存里，退出游戏即失效。 */
    private static volatile boolean watch;

    // 上一 tick 的取证输入输出（诊断行读这些）。
    private static volatile double lastFeet = Double.NaN;
    private static volatile double lastRailY = Double.NaN;
    private static volatile double lastOffset = Double.NaN;
    private static volatile double lastDy = Double.NaN;
    private static volatile boolean lastOnGround;
    private static volatile double lastMoved = Double.NaN;
    private static volatile RailBed.Verdict lastVerdict = RailBed.Verdict.NONE;

    // 自上一行诊断以来的计数（{@code drops=}）。按结论分桶，一眼看出是哪一种。
    private static final int[] COUNTS = new int[RailBed.Verdict.values().length];

    private Trackbed() {
    }

    /** 世界切换 / 重载时丢掉缓存。 */
    public static void reset() {
        BED.reset();
        snappedThisTick = false;
        snappedLastTick = false;
        clearStep();
        clearCounters();
    }

    /** 新世界里的第一条 warn 仍然值得看。 */
    public static void clearWarned() {
        BED.clearWarned();
    }

    /** 诊断用：当前缓存里有多少条候选轨道。 */
    public static int cached() {
        return BED.cached();
    }

    /** 诊断用：上一次查询有没有在 {@link #HALF_WIDTH} 内找到轨面。 */
    public static boolean hit() {
        return BED.lastHit();
    }

    /** 诊断用：上一次查询命中的轨面 y（没命中是 {@code NaN}）。 */
    public static double lastY() {
        return BED.lastY();
    }

    /** 诊断用：上一次查询到最近候选轨道的水平距离（没候选是 {@code NaN}）。 */
    public static double lastDistance() {
        return BED.lastDistance();
    }

    /** 上一个完整 tick 里道床有没有真的把人按上去。 */
    public static boolean snapped() {
        return snappedLastTick;
    }

    /** {@code CollisionSystem#trackbed} 真的把人按上去了。 */
    public static void noteSnapped() {
        snappedThisTick = true;
    }

    /** 取证开关有没有打开。 */
    public static boolean watch() {
        return watch;
    }

    /** 开关取证日志。 */
    public static void setWatch(final boolean on) {
        watch = on;
    }

    /**
     * 记下这一 tick 的判定过程（每 tick 一次，无论结论是什么）。
     *
     * <p>{@code railY} / {@code offset} 在没命中轨道时是 {@code NaN}；{@code moved} 是这一 tick
     * 的位移长度（含竖直），用来区分「站着不动」和「走着走着掉了」。
     */
    public static void noteStep(final double feet, final double railY, final double offset,
                                final double dy, final boolean onGround, final double moved,
                                final RailBed.Verdict verdict) {
        // 只在「上一个 tick 还踩在道床上」时计一次失败。站在野地里每 tick 都是 nohit，
        // 全算进去的话计数就没法读「是不是在坡道上掉的」了。
        final boolean wasOnBed = lastVerdict == RailBed.Verdict.SNAP
                || lastVerdict == RailBed.Verdict.BLOCK
                || lastVerdict == RailBed.Verdict.SPACE
                || lastVerdict == RailBed.Verdict.JUMP;
        lastFeet = feet;
        lastRailY = railY;
        lastOffset = offset;
        lastDy = dy;
        lastOnGround = onGround;
        lastMoved = moved;
        lastVerdict = verdict;
        final int index = verdict.ordinal();
        if (index >= 0 && index < COUNTS.length && (wasOnBed || verdict == RailBed.Verdict.SNAP)) {
            COUNTS[index]++;
        }
        if (verdict == RailBed.Verdict.SNAP) {
            noteSnapped();
        }
        if (watch) {
            LteLog.LOGGER.info("[lte] trackbed step: y={} railY={} dist={} dy={} onGround={}"
                            + " moved={} verdict={}",
                    number(feet), number(railY),
                    number(Double.isNaN(offset) ? Double.NaN : Math.abs(offset)),
                    number(dy), Boolean.valueOf(onGround), number(moved), verdict.shortName());
        }
    }

    /** tick 结束：把这一 tick 的按压状态转成「上一 tick」，返回它。 */
    public static boolean endTick() {
        final boolean value = snappedThisTick;
        snappedLastTick = value;
        snappedThisTick = false;
        return value;
    }

    /** 诊断行打完就清计数（{@code drops=} 是「自上一行以来」）。 */
    public static void clearCounters() {
        synchronized (COUNTS) {
            java.util.Arrays.fill(COUNTS, 0);
        }
    }

    /** 清掉上一 tick 的取证输入（换世界 / 关掉道床时）。 */
    public static void clearStep() {
        lastFeet = Double.NaN;
        lastRailY = Double.NaN;
        lastOffset = Double.NaN;
        lastDy = Double.NaN;
        lastOnGround = false;
        lastMoved = Double.NaN;
        lastVerdict = RailBed.Verdict.NONE;
    }

    /**
     * 诊断行（{@code [lte] trackbed: …}），命令与每 5 秒的日志共用：
     * {@code rails} 缓存条数、{@code hit} 是否命中半宽、{@code y} 轨面高度、{@code dist}
     * 横向距离、{@code off} 有符号横向偏移、{@code halfW} 半宽、{@code dy} 脚底 − 轨面、
     * {@code onGround}/{@code moved} 这一 tick 的状态、{@code drop} 最新结论、
     * {@code snapped} 上一个完整 tick 有没有真被按上去、{@code drops} 各结论计数。
     */
    public static String describe(final boolean isSnapped) {
        return "[lte] trackbed: rails=" + cached()
                + " hit=" + (hit() ? "yes" : "no")
                + " y=" + number(BED.lastY())
                + " dist=" + number(BED.lastDistance())
                + " off=" + signed(BED.lastOffset())
                + " halfW=" + String.format(Locale.ROOT, "%.2f", Double.valueOf(HALF_WIDTH))
                + " dy=" + signed(lastDy)
                + " onGround=" + (lastOnGround ? "true" : "false")
                + " moved=" + number(lastMoved)
                + " drop=" + lastVerdict.shortName()
                + " snapped=" + (isSnapped ? "true" : "false")
                + " drops=" + counts();
    }

    /** {@code snap:57 dy:0 lat:0 nohit:0 jump:2 block:0 space:0} —— 自上一行以来的各结论计数。 */
    private static String counts() {
        final StringBuilder out = new StringBuilder();
        synchronized (COUNTS) {
            for (final RailBed.Verdict verdict : RailBed.Verdict.values()) {
                if (verdict == RailBed.Verdict.NONE) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(verdict.shortName()).append(':').append(COUNTS[verdict.ordinal()]);
            }
        }
        return out.toString();
    }

    /** {@code 1.23}；{@code NaN} 打成 {@code -}（这一窗没有可用的读数）。 */
    private static String number(final double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.2f", Double.valueOf(value));
    }

    /** 带符号的读数（{@code +0.31} / {@code -0.42}），正负号本身是信息。 */
    private static String signed(final double value) {
        return Double.isNaN(value) ? "-"
                : String.format(Locale.ROOT, "%+.2f", Double.valueOf(value));
    }

    /**
     * 玩家脚下最近的一条轨道（横向列），找不到（或横向偏移超出 {@link #HALF_WIDTH}）时返回
     * {@code null}。
     *
     * <p>调用方是 {@code Entity#move} 的 TAIL，每 tick 一次；缓存命中时这里只是一次线性扫描 +
     * 至多几条轨道的折线投影，不再对 {@code getPosition} 做粗扫细化。
     */
    public static Sample nearest(final ClientWorld world, final long tick, final double x,
                                 final double y, final double z) {
        final Sample sample = probe(world, tick, x, y, z);
        if (sample == null || sample.distance > HALF_WIDTH) {
            return null;
        }
        return sample;
    }

    /**
     * 和 {@link #nearest} 同一次查询，但<b>不过滤半宽</b>：横向偏移超了也把最近那条轨道返回来。
     *
     * <p>给诊断用（{@code drop=lat} 与 {@code drop=nohit} 必须分得开），判定里再自己比
     * {@link #HALF_WIDTH}。
     */
    public static Sample probe(final ClientWorld world, final long tick, final double x,
                               final double y, final double z) {
        if (world == null) {
            return null;
        }
        Object rails;
        try {
            rails = ClientData.RAILS;
        } catch (Throwable t) {
            return null;
        }
        final RailBed.Sample sample = BED.probe(rails, tick, x, y, z);
        return sample == null ? null
                : new Sample(sample.y, sample.distance, sample.offset, sample.along,
                        sample.length);
    }
}
