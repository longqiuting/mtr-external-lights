package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.bind.MtrTiles;
import dev.js.mtrheadlight.bind.PidsStationIndex;
import mtr.data.DataCache;
import mtr.data.RailwayData;
import mtr.data.Station;
import mtr.render.RenderPIDS;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 让<b>站在车站范围外</b>的到站投影机 / PIDS 也能显示到站 —— 换掉
 * {@code RenderPIDS#getSchedules} 里那次「按方块自己的位置找车站」的调用。
 *
 * <h2>MTR 原来的行为（javap 证据，3.6.4.2 / 1.20.1）</h2>
 * <pre>
 * RenderPIDS.getSchedules(T be, BlockPos pos, Map&lt;Long,String&gt; stationIdToName):
 *   0: RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)
 *  12: if (station == null) return new ArrayList&lt;&gt;();        // ← 一句话都不显示
 *  25: stationIdToPlatforms = DATA_CACHE.requestStationIdToPlatforms(station.id)
 *  38: if (stationIdToPlatforms.isEmpty()) return new ArrayList&lt;&gt;()
 *  96: platformIds = ((TileEntityArrivalProjectorBase) be).getPlatformIds()   // 投影机分支
 * 254: 遍历 stationIdToPlatforms.values()，按 platformIds 过滤 → 到站列表
 * </pre>
 * <b>{@code getSchedules} 自己没有任何缓存</b>（javap：方法首尾没有字段读写，只有
 * {@code ClientData.STATIONS} / {@code DATA_CACHE} 的静态读取），{@code RenderPIDS#render} 每帧
 * 调它一次。所以「这一帧算不出来」＝「这一帧屏幕是黑的」。
 *
 * <h2>这里做什么</h2>
 * 只替换那一次 {@code getStation} 调用：
 * <ol>
 *   <li>投影机 / PIDS 的 {@code platformIds} 里，有站台属于「方块所在车站」→ 用 MTR 的结果，
 *       一行都不改；</li>
 *   <li>方块在站外（{@code getStation} 返回 null）→ 用 {@code platformIds} 反查车站；</li>
 *   <li>方块在 A 车站里、但绑的是 B 车站的站台 → 用 B（否则 MTR 用 A 的站台列表过滤，
 *       一个都对不上，屏幕永远是空的）；</li>
 *   <li>其它情况 → {@code null}（保持 MTR 的行为）。</li>
 * </ol>
 *
 * <h2>为什么反查不能直接用 {@code DataCache.platformIdToStation}</h2>
 * 那是一张 <b>{@code sync()} 里先 clear 再重建</b>的派生表（{@code mapSavedRailIdToStation} 第一条
 * 指令就是 {@code Map.clear}），而 {@code sync()} 会被服务端的任意一次数据广播触发。每帧都去查它，
 * 就会出现「过一会儿自己灭了、过一会儿又好了」。现在改走 {@link PidsStationIndex}：查到就记住
 * （粘性），派生表没有时自己按「站台中点 ∈ 车站矩形」用权威集合算一遍。
 *
 * <p>日志只在<b>变化</b>时打一行：
 * <pre>{@code [lte] pids render: pos=... station=<id|null> src=mtr|ours platformIds=[...]}</pre>
 * 于是「灭掉的那一刻」到底是没车站、没站台，还是 MTR 把数据换了，日志里一眼能看出来。
 *
 * <p>{@code remap = false}：MTR 的 jar 不含 yarn 映射，方法名就是源码名（{@code getSchedules}、
 * {@code getStation}）。注入找不到方法时由 {@code mtrlte.mtr.mixins.json} 的
 * {@code defaultRequire: 0} + {@code required: false} 兜底，最坏情况退回 MTR 原行为。
 */
@Mixin(value = RenderPIDS.class, remap = false)
public abstract class RenderPIDSStationMixin {

    /** 日志节流：posKey -> 上一次打出去的那一行（只在变化时打）。 */
    private static final Map<String, String> LTE$LAST_LINE = new java.util.HashMap<>();
    private static final int LTE$LOG_LIMIT = 256;
    /** 换世界（换存档 / 换维度）时把站台索引清掉：站台 id 只在同一份 MTR 数据里成立。 */
    private static ClientWorld lte$lastWorld;

    @Redirect(
            method = "getSchedules",
            at = @At(value = "INVOKE", target = "Lmtr/data/RailwayData;getStation"),
            remap = false)
    private static Station lte$stationOutsideStation(final Set<Station> stations, final DataCache dataCache,
                                                     final BlockPos pos) {
        final Station inside = RailwayData.getStation(stations, dataCache, pos);
        if (pos == null) {
            return inside;
        }

        Set<Long> platformIds = Collections.emptySet();
        Station ours = null;
        String src = PidsStationIndex.SRC_MTR;
        Object tile = null;
        try {
            final ClientWorld world = MinecraftClient.getInstance().world;
            if (world != lte$lastWorld) {
                lte$lastWorld = world;
                PidsStationIndex.reset();
                LTE$LAST_LINE.clear();
            }
            if (world != null) {
                tile = world.getBlockEntity(pos);
            }
            if (tile != null && (MtrTiles.isProjector(tile) || MtrTiles.isPids(tile))) {
                platformIds = new LinkedHashSet<>(MtrTiles.isProjector(tile)
                        ? MtrTiles.projectorPlatformIds(tile)
                        : MtrTiles.pidsPlatformIds(tile));
                if (!platformIds.isEmpty()) {
                    final Station found = PidsStationIndex.firstResolvable(dataCache, platformIds);
                    if (found != null) {
                        ours = found;
                        for (final Long id : platformIds) {
                            final String why = PidsStationIndex.sourceOf(id.longValue());
                            if (why != null) {
                                src = why;
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // 渲染线程上任何异常都不能抛出去：抛出去就是整块 PIDS 不画 + 日志刷屏。
            lte$log(pos, inside, "error", platformIds);
            return inside;
        }

        Station result;
        if (inside == null) {
            // 站外：只剩「platformIds 反查」这一条路。
            result = ours;
            src = (ours == null) ? "none" : src;
        } else if (ours != null && ours.id != inside.id && !lte$anyBelongsTo(platformIds, inside, dataCache)) {
            // 方块在 A 车站里、绑的却全是 B 车站的站台：MTR 会拿 A 的站台列表过滤，必然是空屏，
            // 所以这里改成 B。只要还有任何一个绑定站台属于 A，就说明玩家想混着显示，保持 MTR 行为。
            result = ours;
        } else {
            result = inside;
            src = PidsStationIndex.SRC_MTR;
        }

        lte$log(pos, result, result == null ? "none" : src, platformIds);
        return result;
    }

    /** {@code platformIds} 里有没有站台属于 {@code station}。 */
    private static boolean lte$anyBelongsTo(final Set<Long> platformIds, final Station station,
                                            final DataCache dataCache) {
        for (final Long id : platformIds) {
            final Station owner = PidsStationIndex.stationOf(dataCache, id.longValue());
            if (owner != null && owner.id == station.id) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只在<b>变化</b>时打一行（pos + 车站 + 来源 + 站台集合 有任何变化才打）。
     *
     * <p>这条日志的意义：「熄屏的那一刻」是被哪一环掐掉的 ——
     * {@code station=null} 是反查失败，{@code platformIds=[]} 是客户端方块实体丢了绑定数据，
     * 两者都正常却还是黑屏，那就是到站数据（{@code ClientData.SCHEDULES_FOR_PLATFORM}）的问题。
     */
    private static void lte$log(final BlockPos pos, final Station station, final String src,
                                final Set<Long> platformIds) {
        final String ids = platformIds.isEmpty() ? "[]" : new TreeSet<>(platformIds).toString();
        final String stationText = station == null ? "null" : (station.id + "/" + station.name);
        final String line = "pos=" + pos + " station=" + stationText + " src=" + src
                + " platformIds=" + ids;
        final String key = pos.toString();
        if (line.equals(LTE$LAST_LINE.get(key))) {
            return;
        }
        if (LTE$LAST_LINE.size() > LTE$LOG_LIMIT) {
            LTE$LAST_LINE.clear();
        }
        LTE$LAST_LINE.put(key, line);
        LteLog.LOGGER.info("[lte] pids render: {}", line);
    }
}
