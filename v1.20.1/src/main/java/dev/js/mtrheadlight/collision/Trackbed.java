package dev.js.mtrheadlight.collision;

import mtr.client.ClientData;
import net.minecraft.client.world.ClientWorld;

/**
 * 道床几何的<b>客户端门面</b>：把 {@code ClientData.RAILS} 喂给 {@link RailBed}。
 *
 * <p>几何、缓存、采样全在 {@link RailBed}（纯计算，服务端也用同一份，见
 * {@code server.ServerTrackbed}）。这里只剩「客户端从哪取轨道表」这一件事 —— 因为
 * {@code mtr.client.ClientData} 是客户端类，它不能出现在服务端可达的代码里。
 *
 * <p>客户端只处理<b>本地玩家</b>：{@code Entity#move} 注入把人「踩」在轨面上。
 * 其它实体（掉落物、生物、下落的方块）由服务端的 {@code server.ServerTrackbed} 接住。
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

    private Trackbed() {
    }

    /** 世界切换 / 重载时丢掉缓存。 */
    public static void reset() {
        BED.reset();
    }

    /** 新世界里的第一条 warn 仍然值得看。 */
    public static void clearWarned() {
        BED.clearWarned();
    }

    /** 诊断用：当前缓存里有多少条候选轨道。 */
    public static int cached() {
        return BED.cached();
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
