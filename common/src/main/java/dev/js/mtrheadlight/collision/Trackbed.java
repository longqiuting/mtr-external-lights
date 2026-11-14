package dev.js.mtrheadlight.collision;

import mtr.client.ClientData;
import net.minecraft.client.world.ClientWorld;

import java.util.Locale;

/**
 * 道床几何的<b>客户端门面</b>：把 {@code ClientData.RAILS} 喂给 {@link RailBed}。
 *
 * <p>几何、缓存、采样全在 {@link RailBed}（纯计算，服务端也用同一份，见
 * {@code server.ServerTrackbed}）。这里只剩「客户端从哪取轨道表」这一件事 —— 因为
 * {@code mtr.client.ClientData} 是客户端类，它不能出现在服务端可达的代码里。
 *
 * <p>客户端只处理<b>本地玩家</b>：{@code Entity#move} 注入把人「踩」在轨面上。
 * 其它实体（掉落物、生物、下落的方块）由服务端的 {@code server.ServerTrackbed} 接住。
 *
 * <h2>{@code /mtr_lte trackbed status}</h2>
 * 用户报「站在轨道上、过弯道就掉下去」时，这一行就是答案所在：
 * <pre>[lte] trackbed: rails=cached N hit=yes|no y=… dist=… halfW=… snapped=true|false</pre>
 * {@code hit=no} 且 {@code dist=-} 就是「缓存里没有近处的轨道」—— 掉下去的直接原因；
 * {@code dist} 有数字则说明轨道找到了、只是差一点（几何误差），两者的修法完全不同。
 */
public final class Trackbed {

    /** 可站立半宽（格）：轨道中心线两侧各这么宽都能站。 */
    public static final double HALF_WIDTH = RailBed.HALF_WIDTH;

    /** 查询点周围多远的轨道参与判定（格）。 */
    public static final double SEARCH_RADIUS = RailBed.SEARCH_RADIUS;

    /** 最近点的结果：轨道面 y（方块顶面格）与到轨道中心线的水平距离。 */
    public static final class Sample {
        /** 轨道面高度（= {@code Rail#getPosition} 的 y，与列车底板同一水平面）。 */
        public final double y;
        /** 查询点到最近点的水平距离（格）。 */
        public final double distance;

        Sample(final double y, final double distance) {
            this.y = y;
            this.distance = distance;
        }
    }

    private static final RailBed BED = new RailBed();

    /** 这一 tick 的道床有没有真的把人按上去（{@code CollisionSystem#trackbed} 写）。 */
    private static volatile boolean snappedThisTick;

    /** 上一个完整 tick 的按压状态 —— 命令/日志读到的是这个（同一个 tick 内的值才有意义）。 */
    private static volatile boolean snappedLastTick;

    /** 取证开关（{@code /mtr_lte trackbed on|off}）：只在内存里，退出游戏即失效。 */
    private static volatile boolean watch;

    private Trackbed() {
    }

    /** 世界切换 / 重载时丢掉缓存。 */
    public static void reset() {
        BED.reset();
        snappedThisTick = false;
        snappedLastTick = false;
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

    /**
     * tick 结束：把这一 tick 的按压状态转成「上一 tick」，返回它。
     *
     * <p>必须在 {@code END_CLIENT_TICK} 里调一次（{@code CollisionSystem#tick} 开头）：玩家的移动与
     * {@code Entity#move} 注入都在这一 tick 里已经跑完，所以这里拿到的是「刚过去的这一 tick」。
     */
    public static boolean endTick() {
        final boolean value = snappedThisTick;
        snappedLastTick = value;
        snappedThisTick = false;
        return value;
    }

    /** 取证日志开着没有。 */
    public static boolean watch() {
        return watch;
    }

    /** 开关取证日志。 */
    public static void setWatch(final boolean on) {
        watch = on;
    }

    /**
     * 诊断行（{@code [lte] trackbed: …}），命令与每 5 秒的日志共用：
     * {@code rails=cached} 缓存条数、{@code hit} 是否命中半宽、{@code y} 轨面高度、
     * {@code dist} 到最近候选的水平距离（超出半宽也照报）、{@code halfW} 半宽、
     * {@code snapped} 上一个完整 tick 有没有真被按上去。
     */
    public static String describe(final boolean isSnapped) {
        return "[lte] trackbed: rails=cached " + cached()
                + " hit=" + (hit() ? "yes" : "no")
                + " y=" + number(lastY())
                + " dist=" + number(lastDistance())
                + " halfW=" + String.format(Locale.ROOT, "%.2f", Double.valueOf(HALF_WIDTH))
                + " snapped=" + (isSnapped ? "true" : "false");
    }

    /** {@code 1.23}；{@code NaN} 打成 {@code -}（这一窗没有可用的读数）。 */
    private static String number(final double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.2f", Double.valueOf(value));
    }

    /**
     * 玩家脚下最近的一条轨道，找不到（或超出 {@link #HALF_WIDTH}）时返回 {@code null}。
     *
     * <p>调用方是 {@code Entity#move} 的 TAIL，每 tick 一次；缓存命中时这里只是一次线性扫描 +
     * 至多一条轨道的采样。
     */
    public static Sample nearest(final ClientWorld world, final long tick, final double x,
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
        final RailBed.Sample sample = BED.nearest(rails, tick, x, y, z);
        return sample == null ? null : new Sample(sample.y, sample.distance);
    }
}
