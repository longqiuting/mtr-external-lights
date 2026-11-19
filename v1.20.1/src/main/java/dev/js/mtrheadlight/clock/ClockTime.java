// 时钟的时间源：把「世界时间 / 现实时间」统一换成一天里的第几秒。
package dev.js.mtrheadlight.clock;

import net.minecraft.world.World;

import java.time.ZonedDateTime;

/**
 * 时钟的时间换算 —— <b>纯函数</b>，客户端与服务端共用同一份结果，所以两边指针位置天然一致，
 * 不需要任何同步包。
 *
 * <h2>为什么不用 {@code world.getTimeOfDay()} 直接当小时</h2>
 * <p>MC 的一天是 24000 tick：{@code timeOfDay} 0 = 06:00、6000 = 12:00、12000 = 18:00、
 * 18000 = 00:00。这里先换成「一天里的第几秒」再算时 / 分 / 秒，指针的走法就和现实钟表完全一样
 * （秒针一格一格走、分针连续爬）。
 *
 * <h2>部分 tick</h2>
 * <p>{@code tickDelta} 是渲染用的插值量（0..1）。MC 时间下 1 tick = 86400/24000 = 3.6 显示秒，
 * 所以 {@code tickDelta} 乘 3.6；现实时间下 1 tick = 50 ms。少了这一项，秒针会 20 Hz 地跳。
 *
 * <h2>现实时间</h2>
 * <p>用 {@link ZonedDateTime#now()} 的<b>墙钟</b>（服务器 / 客户端所在时区），与充电宝租金那套
 * {@code System.currentTimeMillis()} 同一个时间基准 —— 都不看游戏内时间。
 */
public final class ClockTime {

    /** 一天的秒数。 */
    public static final double DAY_SECONDS = 86400.0D;

    private ClockTime() {
    }

    /** 把秒数折回 {@code [0, 86400)}。 */
    public static double wrap(final double seconds) {
        double s = seconds % DAY_SECONDS;
        if (s < 0) {
            s += DAY_SECONDS;
        }
        return s;
    }

    /**
     * 当前时间源下「一天里的第几秒」（**不含**手动校准偏移）。
     *
     * @param world     世界（现实时间模式也不用它，只是签名统一）
     * @param source    时间源
     * @param tickDelta 渲染插值（0..1）；方块实体 tick 里传 0
     */
    public static double secondsOfDay(final World world, final ClockConfig.TimeSource source,
                                      final double tickDelta) {
        if (source == ClockConfig.TimeSource.REAL) {
            final ZonedDateTime now = ZonedDateTime.now();
            return now.getHour() * 3600.0D + now.getMinute() * 60.0D + now.getSecond()
                    + now.getNano() / 1.0E9D + tickDelta * 0.05D;
        }
        if (world == null) {
            return 0.0D;
        }
        final long tod = world.getTimeOfDay() % 24000L;
        return wrap(tod / 24000.0D * DAY_SECONDS + 6.0D * 3600.0D
                + tickDelta * (DAY_SECONDS / 24000.0D));
    }

    /** 加上手动校准偏移之后的一天内秒数（永远落在 {@code [0, 86400)}）。 */
    public static double displayTarget(final World world, final ClockConfig config, final double tickDelta) {
        return wrap(secondsOfDay(world, config.timeSource, tickDelta) + config.offsetMinutes * 60.0D);
    }

    /** 一天内秒数 → 小时（0..23）。 */
    public static int hour(final double secondsOfDay) {
        return (int) (wrap(secondsOfDay) / 3600.0D);
    }

    /** 一天内秒数 → 分钟（0..59）。 */
    public static int minute(final double secondsOfDay) {
        return (int) ((wrap(secondsOfDay) % 3600.0D) / 60.0D);
    }

    /** 一天内秒数 → 秒（0..59，带小数）。 */
    public static double second(final double secondsOfDay) {
        return wrap(secondsOfDay) % 60.0D;
    }

    /** 12 小时制的钟点数：0 点 / 12 点 → 12，13 点 → 1。 */
    public static int hour12(final double secondsOfDay) {
        int h = hour(secondsOfDay) % 12;
        return h == 0 ? 12 : h;
    }

    /** {@code HH:MM} 形状的显示串（界面里「当前显示」那一栏用）。 */
    public static String formatHhMm(final double secondsOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hour(secondsOfDay), minute(secondsOfDay));
    }

    /**
     * 三根指针相对 12 点方向的<b>顺时针</b>圈数（0..1）。
     *
     * <p>时针是「整点 + 分钟 + 秒」连续爬，不是整点跳 —— 所以
     * {@code 12:00 → 0.0}、{@code 3:00 → 0.25}、{@code 6:30 → 0.5417}。
     * 用户需求里写的 {@code ((h % 12) + 10) / 12} 想要的是「12 点 = 0」，但按那个式子算
     * 12:00 会落在 10/12 处，所以这里按需求的语义（12 点 = 0）实现成
     * {@code ((h % 12) + m/60 + s/3600) / 12}。
     */
    public static double hourFraction(final double secondsOfDay) {
        final double s = wrap(secondsOfDay);
        final double h12 = s / 3600.0D % 12.0D;
        return h12 / 12.0D;
    }

    /** 分针圈数：每分钟一圈的 1/60。 */
    public static double minuteFraction(final double secondsOfDay) {
        final double s = wrap(secondsOfDay);
        return (s % 3600.0D) / 3600.0D;
    }

    /** 秒针圈数：每 60 秒一圈。 */
    public static double secondFraction(final double secondsOfDay) {
        final double s = wrap(secondsOfDay);
        return (s % 60.0D) / 60.0D;
    }
}
