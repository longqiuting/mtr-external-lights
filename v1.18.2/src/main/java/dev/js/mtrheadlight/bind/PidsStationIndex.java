package dev.js.mtrheadlight.bind;

import mtr.client.ClientData;
import mtr.data.DataCache;
import mtr.data.Platform;
import mtr.data.Station;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端：{@code platformId -> Station} 的<b>粘性</b>索引（站外投影机 / 站外 PIDS 的渲染要用）。
 *
 * <h2>为什么不能每次渲染都去查 {@code DataCache.platformIdToStation}</h2>
 * <p>那张表不是常驻数据，是 {@code DataCache#sync()} 里用
 * {@code mapSavedRailIdToStation(platformIdToStation, platforms, stations)} <b>先 clear 再重建</b>的
 * 派生表（javap 证据：{@code mapSavedRailIdToStation} 的第一条指令就是 {@code Map.clear}）。
 * {@code sync()} 由 {@code ClientData#receivePacket}（服务端任何一次数据广播）与
 * {@code DataUpdateBroadcast#broadcastDataUpdate} 触发。也就是说：
 * <ul>
 *   <li>刚 sync 完、或者 {@code mapSavedRailIdToStation} 中途抛出（该方法外层 try/catch 只
 *       {@code printStackTrace}）时，这张表可以是空的；</li>
 *   <li>{@code RenderPIDS#getSchedules} 是<b>每帧</b>调用的（javap 确认它自己没有任何缓存），
 *       所以「这一帧查不到」直接等于「这一帧整块屏幕空」。</li>
 * </ul>
 * 于是表现就是「过一会儿自己灭了，过一会儿又好了」。
 *
 * <h2>这里怎么做</h2>
 * <ol>
 *   <li>查到就<b>永久记住</b>（粘性）：车站 id 不会变，重建时查不到不能倒着把已知答案抹掉；</li>
 *   <li>先问 MTR 自己的派生表（便宜，也是 MTR 渲染时用的同一份判断）——
 *       命中记 {@code src=mtr}；</li>
 *   <li>派生表没有时，用<b>权威数据</b>自己算一遍：{@code DataCache.platformIdMap} 拿站台，
 *       站台中点落在哪个 {@code ClientData.STATIONS} 矩形里 —— 记 {@code src=ours}。
 *       这条路径不依赖任何派生缓存，只在拿不到 {@code DataCache} 时才会失败；</li>
 *   <li>都没算出来的站台 id 有 500 ms 的重试节流，避免每帧扫一遍车站集合。</li>
 * </ol>
 *
 * <p>只在客户端渲染线程使用（{@code RenderPIDSStationMixin}），所以不做并发处理。
 *
 * <h2>键是 platformId，不是「屏」</h2>
 * <p>这张表的键是<b>站台 id</b> —— {@code platformId -> 它属于哪个车站} 与屏无关，是全局事实，
 * 所以在这里缓存不会让「新放的屏读到别的屏的车站」。读的一侧（{@link PidsStationResolver}）
 * 每帧只问「<b>这一格</b>方块实体的 platformIds 是什么」，再用这里的表把站台翻成车站。
 */
public final class PidsStationIndex {

    /** 答案来自 MTR 的派生表 {@code DataCache.platformIdToStation}。 */
    public static final String SRC_MTR = "mtr";
    /** 答案来自我们自己按站台中点 + 车站矩形算出来的（不经派生表）。 */
    public static final String SRC_OURS = "ours";

    /** platformId -> 车站（粘性，永不回退成 null）。 */
    private static final Map<Long, Station> STATIONS = new HashMap<>();
    /** platformId -> 当时是怎么算出来的。 */
    private static final Map<Long, String> SOURCES = new HashMap<>();
    /** platformId -> 下次允许重新尝试的时间（毫秒）。 */
    private static final Map<Long, Long> RETRY_AT = new HashMap<>();

    private static final long RETRY_MS = 500L;
    /** 日志用：解析失败的次数（mixin 直接读，不做额外统计）。 */
    private static int misses;

    private PidsStationIndex() {
    }

    /**
     * 站台所属车站（可能 {@code null}：站台不在任何车站范围内，或者客户端数据还没到）。
     *
     * @param dataCache 渲染时拿到的 {@code ClientData.DATA_CACHE}，可以为 {@code null}
     */
    public static Station stationOf(final DataCache dataCache, final long platformId) {
        if (platformId == 0L) {
            return null;
        }
        final Station known = STATIONS.get(platformId);
        if (known != null) {
            return known;
        }
        final long now = System.currentTimeMillis();
        final Long retryAt = RETRY_AT.get(platformId);
        if (retryAt != null && now < retryAt.longValue()) {
            return null;
        }
        final Station found = resolve(dataCache, platformId);
        if (found == null) {
            RETRY_AT.put(Long.valueOf(platformId), Long.valueOf(now + RETRY_MS));
            misses++;
            return null;
        }
        STATIONS.put(Long.valueOf(platformId), found);
        RETRY_AT.remove(Long.valueOf(platformId));
        return found;
    }

    /** 上一次成功的答案来自哪（{@link #SRC_MTR} / {@link #SRC_OURS}），没有就是 {@code null}。 */
    public static String sourceOf(final long platformId) {
        return SOURCES.get(Long.valueOf(platformId));
    }

    /** 已经记住了多少个站台（诊断用）。 */
    public static int size() {
        return STATIONS.size();
    }

    /** 解析失败的累计次数（诊断用）。 */
    public static int misses() {
        return misses;
    }

    /** 换世界 / 断开连接时清空：站台 id 只在同一个存档里成立。 */
    public static void reset() {
        STATIONS.clear();
        SOURCES.clear();
        RETRY_AT.clear();
        misses = 0;
    }

    /**
     * 真解析：MTR 的派生表优先，其次用权威数据自己算。
     *
     * <p>返回 {@code null} 时<b>不</b>记入 {@link #STATIONS}——下次还会再试（有节流）。
     */
    private static Station resolve(final DataCache dataCache, final long platformId) {
        if (dataCache == null) {
            return null;
        }
        // 1) MTR 自己的答案（RenderPIDS 的原路径，等价于「车内视角」时的行为）。
        try {
            final Station byCache = dataCache.platformIdToStation.get(Long.valueOf(platformId));
            if (byCache != null) {
                SOURCES.put(Long.valueOf(platformId), SRC_MTR);
                return byCache;
            }
        } catch (Throwable ignored) {
            // 派生表正被重建（理论上同线程，防御一下）。
        }
        // 2) 自己算：站台中点落在哪个车站矩形里。只用权威集合（platformIdMap / STATIONS），
        //    不碰任何派生缓存，所以不会因为某次 sync 而拿不到答案。
        try {
            final Platform platform = dataCache.platformIdMap.get(Long.valueOf(platformId));
            if (platform == null) {
                return null;
            }
            final BlockPos mid = platform.getMidPos();
            if (mid == null) {
                return null;
            }
            final Station byArea = stationContaining(mid.getX(), mid.getZ());
            if (byArea != null) {
                SOURCES.put(Long.valueOf(platformId), SRC_OURS);
                return byArea;
            }
        } catch (Throwable ignored) {
            // 数据半途替换（clear + addAll）时可能抛，交给下一次重试。
        }
        return null;
    }

    /** 车站矩形命中查询（{@code ClientData.STATIONS} 是权威集合，不经过派生表）。 */
    private static Station stationContaining(final int x, final int z) {
        try {
            for (final Station station : ClientData.STATIONS) {
                if (station != null && station.inArea(x, z)) {
                    return station;
                }
            }
        } catch (Throwable ignored) {
            // 集合正在被 receivePacket 整体替换：这一帧先放弃，下一次再试。
        }
        return null;
    }

    /** 一组站台 id 里第一个能反查到车站的。 */
    public static Station firstResolvable(final DataCache dataCache, final Iterable<Long> platformIds) {
        if (platformIds == null) {
            return null;
        }
        for (final Long id : platformIds) {
            if (id == null) {
                continue;
            }
            final Station station = stationOf(dataCache, id.longValue());
            if (station != null) {
                return station;
            }
        }
        return null;
    }
}
