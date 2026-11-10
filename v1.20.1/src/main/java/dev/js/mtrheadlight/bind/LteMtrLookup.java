package dev.js.mtrheadlight.bind;

import mtr.data.DataCache;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Station;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MTR 3.6.4.2 的轨道数据查询：把「方块坐标」翻译成「车站 / 站台」。
 *
 * <h2>为什么全是反射</h2>
 * <p>MTR 的 jar 没有 remap，它自己方法的形参在字节码里是 intermediary 名
 * （{@code net/minecraft/class_2338}），而本工程用 named 映射编译 —— 直接写
 * {@code RailwayData.getStation(stations, cache, pos)} 是编译不过的（同
 * {@code SignalLinkNet#railwayData} 的处理方式）。运行期两者是同一个类，所以
 * {@code getMethod(..., BlockPos.class)} 找得到。
 *
 * <p>反过来，<b>字段</b>直接访问是安全的：{@code stations} / {@code platforms} /
 * {@code dataCache} 的声明类型是 {@code java.util.Set} / {@code mtr.data.DataCache}，
 * 不带任何 MC 类型，编译期就能过。
 *
 * <h2>用到的三条 MTR 事实（反编译 MTR-fabric-1.20.1-3.6.4.2 得到）</h2>
 * <ul>
 *   <li>{@code RailwayData.getStation(Set<Station>, DataCache, BlockPos)} 用
 *       {@code Station#inArea(x, z)} 做矩形包含判定 —— 这就是「车站范围内任意方块」；</li>
 *   <li>{@code RailwayData.getClosePlatformId(Set<Platform>, DataCache, BlockPos)} 是 MTR
 *       自己给 PIDS 找站台用的那一条（半径 5、向下 0、向上 4），PIDS 绑定直接复用，
 *       保证「我们算出来的站台」和「MTR 自己算出来的站台」是同一个；</li>
 *   <li>{@code DataCache.platformIdToStation} 是站台 → 车站的唯一映射，
 *       「某车站的所有站台」= 这张表里 value 等于该车站的那些 key。</li>
 * </ul>
 *
 * <p>任何一步失败（MTR 没装、版本换了、方法签名变了）都返回 {@code null} / {@code 0} /
 * 空表，调用方据此给玩家一句提示，绝不抛异常。
 */
public final class LteMtrLookup {

    private static boolean probed;
    private static Method getInstance;
    private static Method getStation;
    private static Method getClosePlatformId;

    private LteMtrLookup() {
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            getInstance = RailwayData.class.getMethod("getInstance", World.class);
        } catch (Throwable t) {
            getInstance = null;
        }
        try {
            getStation = RailwayData.class.getMethod("getStation", Set.class, DataCache.class, BlockPos.class);
        } catch (Throwable t) {
            getStation = null;
        }
        try {
            getClosePlatformId = RailwayData.class.getMethod(
                    "getClosePlatformId", Set.class, DataCache.class, BlockPos.class);
        } catch (Throwable t) {
            getClosePlatformId = null;
        }
    }

    /** {@code RailwayData#getInstance(World)}；没有 MTR 数据就是 {@code null}。 */
    public static RailwayData data(final World world) {
        probe();
        if (getInstance == null || world == null) {
            return null;
        }
        try {
            final Object value = getInstance.invoke(null, world);
            return value instanceof RailwayData ? (RailwayData) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这个坐标落在哪个车站的矩形范围里（MTR 的 {@code Station#inArea}）；不在任何车站里返回 {@code null}。 */
    public static Station stationAt(final World world, final BlockPos pos) {
        probe();
        final RailwayData data = data(world);
        if (data == null || getStation == null || pos == null) {
            return null;
        }
        try {
            final Object value = getStation.invoke(null, data.stations, data.dataCache, pos);
            return value instanceof Station ? (Station) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这个坐标最近的那个站台 id（MTR 的 {@code getClosePlatformId}，和 PIDS 自己的算法一致）。
     *
     * @return {@code 0} = 附近没有站台轨道
     */
    public static long platformIdAt(final World world, final BlockPos pos) {
        probe();
        final RailwayData data = data(world);
        if (data == null || getClosePlatformId == null || pos == null) {
            return 0L;
        }
        try {
            final Object value = getClosePlatformId.invoke(null, data.platforms, data.dataCache, pos);
            return value instanceof Long ? (Long) value : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 一个车站名下的所有站台 id（按 {@code DataCache.platformIdToStation} 反查）。 */
    public static List<Long> platformIdsOf(final World world, final long stationId) {
        return platformIdsOf(world, stationById(world, stationId));
    }

    /**
     * 一个车站名下的所有站台 id。
     *
     * <p>首选 {@code DataCache.platformIdToStation}（MTR 自己维护的站台 → 车站映射，{@code DataCache#sync}
     * 时建）。这张表在「刚划完站台、还没 sync」的瞬间是空的，所以查不到的站台会再走一次几何判定：
     * 站台中点落在车站矩形里就算（{@code Station#inArea} 和 MTR 的 {@code getClosePlatformId} 同一套判断）。
     */
    public static List<Long> platformIdsOf(final World world, final Station station) {
        final RailwayData data = data(world);
        if (data == null || station == null) {
            return Collections.emptyList();
        }
        final List<Long> out = new ArrayList<>();
        try {
            final Map<Long, Station> toStation = data.dataCache.platformIdToStation;
            for (final Platform platform : data.platforms) {
                final Station owner = toStation.get(platform.id);
                if (owner != null) {
                    if (owner.id == station.id) {
                        out.add(platform.id);
                    }
                    continue;
                }
                final BlockPos mid = midPos(platform);
                if (mid != null && station.inArea(mid.getX(), mid.getZ())) {
                    out.add(platform.id);
                }
            }
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        return out;
    }

    /** 车站 id → 车站对象（{@code DataCache.stationIdMap}）。 */
    public static Station stationById(final World world, final long stationId) {
        final RailwayData data = data(world);
        if (data == null || stationId == 0L) {
            return null;
        }
        try {
            return data.dataCache.stationIdMap.get(stationId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 站台中点（{@code SavedRailBase#getMidPos()}）。
     *
     * <p>反射的是 MTR 自己的方法名（不参与 remap），返回的是 {@code BlockPos} 的运行时类。
     */
    private static BlockPos midPos(final Platform platform) {
        try {
            final Object value = platform.getClass().getMethod("getMidPos").invoke(platform);
            return value instanceof BlockPos ? (BlockPos) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 站台属于哪个车站；不知道就 {@code null}。 */
    public static Station stationOfPlatform(final World world, final long platformId) {
        final RailwayData data = data(world);
        if (data == null || platformId == 0L) {
            return null;
        }
        try {
            return data.dataCache.platformIdToStation.get(platformId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 站台的显示名（MTR 里站台名默认就是 "1"、"2"……）。 */
    public static String platformName(final World world, final long platformId) {
        final RailwayData data = data(world);
        if (data == null || platformId == 0L) {
            return "";
        }
        try {
            for (final Platform platform : data.platforms) {
                if (platform.id == platformId) {
                    return platform.name == null ? "" : platform.name;
                }
            }
        } catch (Throwable t) {
            return "";
        }
        return "";
    }
}
