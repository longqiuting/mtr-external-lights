package dev.js.mtrheadlight.collision;

import mtr.data.Train;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 服务端每 tick 的列车清单 —— 由 {@code dev.js.mtrheadlight.mixin.mtrserver.TrainSimulateCaptureMixin}
 * 在 {@code TrainServer#simulateTrain} 的 TAIL 处登记。
 *
 * <h2>为什么需要这张表</h2>
 * 生物的位置与血量是服务端权威的，客户端改了没用（{@code LivingEntity#damage} 在
 * {@code world.isClient} 时直接返回 false）。所以生物碰撞必须在整合服务器上算，而 MTR 3.6.4 的
 * {@code RailwayData} 没有任何公开的「取当前所有列车」入口（{@code Siding#trains} 是私有字段，
 * {@code updateNearbyTrains} 也是私有的）。唯一稳定、每 tick 每列车都会经过的公开点是
 * {@code TrainServer#simulateTrain(...)}，在那里登记一次即可。
 *
 * <p>表按 {@link World} 分桶（同一个存档的不同维度各算各的），列车按<b>身份</b>去重 —— MTR 的
 * {@code Train} 没重写 equals/hashCode，用 {@link IdentityHashMap} 才不会被 id 相同的桥接对象搅乱。
 * 超过 {@link #TTL} tick 没再出现的列车（车厂回收、线路删除）会被丢掉，不需要额外通知。
 */
public final class TrainTickCapture {

    /** 一列车多久没被 simulate 过就认为没了（tick）。留两 tick 余量给 MTR / 我们的 tick 顺序差。 */
    private static final long TTL = 20L;

    /** 世界 → 列车 → 最后一次登记的 tick。 */
    private static final Map<World, Map<Train, Long>> BY_WORLD = new IdentityHashMap<>();

    private TrainTickCapture() {
    }

    /** 由 mixin 调用：这一 tick 这列车在这个世界里跑过了。 */
    public static void capture(final World world, final Train train) {
        if (world == null || train == null) {
            return;
        }
        final long now = world.getTime();
        Map<Train, Long> trains = BY_WORLD.get(world);
        if (trains == null) {
            trains = new IdentityHashMap<>();
            BY_WORLD.put(world, trains);
        }
        trains.put(train, Long.valueOf(now));
    }

    /** 这个世界里最近还在跑的列车（调用方不能改这个 List）。 */
    public static List<Train> trains(final World world, final long now) {
        final Map<Train, Long> trains = BY_WORLD.get(world);
        if (trains == null || trains.isEmpty()) {
            return List.of();
        }
        final List<Train> out = new ArrayList<>(trains.size());
        final Iterator<Map.Entry<Train, Long>> it = trains.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Train, Long> entry = it.next();
            final Train train = entry.getKey();
            if (train == null) {
                it.remove();
                continue;
            }
            if (now - entry.getValue().longValue() > TTL) {
                it.remove();
                continue;
            }
            out.add(train);
        }
        return out;
    }

    /** 世界卸载 / 客户端重载时清空。 */
    public static void clear() {
        BY_WORLD.clear();
    }
}
