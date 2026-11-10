package dev.js.mtrheadlight.bind;

import dev.js.mtrheadlight.LteLog;
import mtr.data.DataCache;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Station;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
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
    private static Method getPlatformByPos;

    /** 顺轨道找站台时最多走多少个节点 / 最远多少格（防止沿着一条长线走遍全世界）。 */
    private static final int RAIL_WALK_NODES = 256;
    private static final int RAIL_WALK_RANGE = 256;
    /** 找「起点节点」时允许的搜索半径（玩家点到的往往是轨道下方的地面，不是节点方块）。 */
    private static final int NODE_SEARCH = 3;

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
        try {
            getPlatformByPos = RailwayData.class.getMethod("getPlatformByPos", Set.class, BlockPos.class);
        } catch (Throwable t) {
            getPlatformByPos = null;
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
     * 这一格是不是<b>站台轨道本身</b>（{@code RailwayData#getPlatformByPos}，即
     * {@code SavedRailBase#containsPos}，精确匹配站台两端之间的那串坐标）。
     *
     * @return {@code 0} = 这一格不在任何站台轨道上
     */
    public static long platformIdAtExact(final World world, final BlockPos pos) {
        probe();
        final RailwayData data = data(world);
        if (data == null || getPlatformByPos == null || pos == null) {
            return 0L;
        }
        try {
            final Object value = getPlatformByPos.invoke(null, data.platforms, pos);
            return value instanceof Platform ? ((Platform) value).id : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 「这个坐标属于哪个站台」—— 允许点在<b>车站范围外、离站台很远</b>的地方。
     *
     * <p>三级判定，从便宜到贵：
     * <ol>
     *   <li>{@link #platformIdAt} —— MTR 自己的 {@code getClosePlatformId}（站台包围盒 ±5 格），
     *       站台上 / 站台旁一格就命中；</li>
     *   <li>{@link #platformIdAtExact} —— 这一格就是站台轨道上的方块；</li>
     *   <li><b>顺着同一条轨道走</b>：从最近的轨道节点出发，在 {@code getRailsMap()} 上做 BFS，
     *       每走到一个节点就问一次「这里离哪个站台最近」，第一个命中就是要的站台。
     *       节点按距离由近到远（BFS 层序）出队，所以拿到的是<b>沿轨道最近</b>的那个站台。</li>
     * </ol>
     *
     * <p>这就是「连接器可以在站外使用」的关键：站台 id 不再只能从站区内的方块推出来，
     * 沿线的任意一个轨道节点都能顺着轨道反推到站台。
     *
     * @return {@code 0} = 附近没有站台，顺着轨道也走不到站台
     */
    public static long platformIdOnRail(final World world, final BlockPos pos) {
        return platformIdOnRail(world, pos, "default");
    }

    /**
     * 同 {@link #platformIdOnRail(World, BlockPos)}，但<b>逐级打日志</b>（{@code tag} 用来区分调用方：
     * {@code projector} / {@code pids} / …），失败时能一眼看出是三级判定里的哪一级没命中。
     *
     * <p>打印的字段：
     * <ul>
     *   <li>{@code data} —— {@code RailwayData.getInstance(world)} 是否拿到（服务端拿不到就直接全失败）；</li>
     *   <li>{@code s1} —— {@code getClosePlatformId}（±5 格）；</li>
     *   <li>{@code s2} —— {@code getPlatformByPos}（精确站台轨道）；</li>
     *   <li>{@code s3} —— 沿 {@code getRailsMap()} 的 BFS（起点节点 + 走了几个节点）。</li>
     * </ul>
     */
    public static long platformIdOnRail(final World world, final BlockPos pos, final String tag) {
        if (pos == null) {
            LteLog.LOGGER.info("[lte] rail lookup[{}]: pos=null -> 0", tag);
            return 0L;
        }
        final RailwayData data = data(world);
        if (data == null) {
            // 服务端 / 客户端都可能走到：拿不到 RailwayData 就没有轨道数据，三级判定全是 0。
            LteLog.LOGGER.info("[lte] rail lookup[{}]: pos={} railwayData=null -> 0", tag, pos);
            return 0L;
        }
        final long direct = platformIdAt(world, pos);
        if (direct != 0L) {
            LteLog.LOGGER.info("[lte] rail lookup[{}]: pos={} s1(getClosePlatformId)={} HIT", tag, pos, direct);
            return direct;
        }
        final long exact = platformIdAtExact(world, pos);
        if (exact != 0L) {
            LteLog.LOGGER.info("[lte] rail lookup[{}]: pos={} s1=0 s2(getPlatformByPos)={} HIT", tag, pos, exact);
            return exact;
        }
        final long walked = platformAlongRail(world, pos, tag);
        LteLog.LOGGER.info("[lte] rail lookup[{}]: pos={} s1=0 s2=0 s3(railWalk)={} {}",
                tag, pos, walked, walked == 0L ? "MISS" : "HIT");
        return walked;
    }

    /** {@link #platformIdOnRail} 的第三步：沿 {@code getRailsMap()} 做 BFS，见该方法的注释。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static long platformAlongRail(final World world, final BlockPos pos, final String tag) {
        final RailwayData data = data(world);
        if (data == null) {
            LteLog.LOGGER.info("[lte] rail walk[{}]: railwayData=null", tag);
            return 0L;
        }
        final Map rails;
        try {
            rails = (Map) data.getRailsMap();
        } catch (Throwable t) {
            LteLog.LOGGER.info("[lte] rail walk[{}]: getRailsMap threw {}", tag, t.toString());
            return 0L;
        }
        if (rails == null || rails.isEmpty()) {
            LteLog.LOGGER.info("[lte] rail walk[{}]: railsMap empty ({} entries)",
                    tag, rails == null ? "null" : Integer.valueOf(rails.size()));
            return 0L;
        }
        final BlockPos start = nodeNear(rails, pos);
        if (start == null) {
            LteLog.LOGGER.info("[lte] rail walk[{}]: pos={} no track node within {} blocks (railsMap={} nodes)",
                    tag, pos, Integer.valueOf(NODE_SEARCH), Integer.valueOf(rails.size()));
            return 0L;
        }
        final Set<BlockPos> seen = new HashSet<>();
        final Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        final double rangeSq = (double) RAIL_WALK_RANGE * RAIL_WALK_RANGE;
        while (!queue.isEmpty() && seen.size() <= RAIL_WALK_NODES) {
            final BlockPos node = queue.poll();
            final long hit = platformIdAt(world, node);
            if (hit != 0L) {
                LteLog.LOGGER.info("[lte] rail walk[{}]: start={} visited={} hit at node={} platform={}",
                        tag, start, Integer.valueOf(seen.size()), node, hit);
                return hit;
            }
            final Object neighbours;
            try {
                neighbours = rails.get(node);
            } catch (Throwable t) {
                continue;
            }
            if (!(neighbours instanceof Map)) {
                continue;
            }
            for (final Object key : ((Map) neighbours).keySet()) {
                if (!(key instanceof BlockPos)) {
                    continue;
                }
                final BlockPos next = (BlockPos) key;
                if (next.getSquaredDistance(pos) > rangeSq) {
                    continue;
                }
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        LteLog.LOGGER.info("[lte] rail walk[{}]: start={} visited={} no platform within {} nodes / {} blocks",
                tag, start, Integer.valueOf(seen.size()), Integer.valueOf(RAIL_WALK_NODES),
                Integer.valueOf(RAIL_WALK_RANGE));
        return 0L;
    }

    /**
     * 离 {@code pos} 最近的轨道节点（{@code getRailsMap()} 的键）。
     *
     * <p>先看这一格本身（以及正上方 / 正下方各一格：节点只有 1 像素高，玩家常常点到轨道下方的
     * 地面），再退到 {@code NODE_SEARCH} 半径的三维邻域。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockPos nodeNear(final Map rails, final BlockPos pos) {
        for (final BlockPos candidate : new BlockPos[]{pos, pos.up(), pos.down()}) {
            try {
                if (rails.containsKey(candidate)) {
                    return candidate;
                }
            } catch (Throwable ignored) {
                // 继续找
            }
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -NODE_SEARCH; dx <= NODE_SEARCH; dx++) {
            for (int dy = -NODE_SEARCH; dy <= NODE_SEARCH; dy++) {
                for (int dz = -NODE_SEARCH; dz <= NODE_SEARCH; dz++) {
                    final BlockPos cursor = pos.add(dx, dy, dz);
                    boolean present;
                    try {
                        present = rails.containsKey(cursor);
                    } catch (Throwable t) {
                        present = false;
                    }
                    if (!present) {
                        continue;
                    }
                    final double distance = cursor.getSquaredDistance(pos);
                    if (distance < bestDist) {
                        bestDist = distance;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    /**
     * 「这个坐标应该绑哪个车站」—— 允许点在车站范围外。
     *
     * <p>先看坐标本身在不在车站矩形里（{@code Station#inArea}，就是原来的唯一判定）；
     * 不在就由站台反推：{@link #platformIdOnRail} → {@link #stationOfPlatform}。
     * 于是「站在站外的轨道上点一下」也能绑到这条线所属的车站。
     *
     * @return {@code null} = 推不出车站（周围没有站台、也不在任何车站范围里）
     */
    public static Station stationNearRail(final World world, final BlockPos pos) {
        final Station inside = stationAt(world, pos);
        if (inside != null) {
            return inside;
        }
        final long platformId = platformIdOnRail(world, pos);
        return platformId == 0L ? null : stationOfPlatform(world, platformId);
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

    /**
     * 站台属于哪个车站；不知道就 {@code null}。
     *
     * <p>首选 {@code DataCache.platformIdToStation}；这张表是 {@code DataCache#sync} 建的，刚划完站台、
     * 还没 sync 的瞬间查不到，所以查不到时再按几何判一次：站台中点落在哪个车站矩形里就算哪个
     * （与 {@code DataCache#mapSavedRailIdToStation} 同一套判据）。
     */
    public static Station stationOfPlatform(final World world, final long platformId) {
        final RailwayData data = data(world);
        if (data == null || platformId == 0L) {
            return null;
        }
        try {
            final Station cached = data.dataCache.platformIdToStation.get(platformId);
            if (cached != null) {
                return cached;
            }
        } catch (Throwable ignored) {
            // 退到几何判定
        }
        try {
            for (final Platform platform : data.platforms) {
                if (platform.id != platformId) {
                    continue;
                }
                final BlockPos mid = midPos(platform);
                if (mid == null) {
                    return null;
                }
                for (final Station station : data.stations) {
                    if (station.inArea(mid.getX(), mid.getZ())) {
                        return station;
                    }
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
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
