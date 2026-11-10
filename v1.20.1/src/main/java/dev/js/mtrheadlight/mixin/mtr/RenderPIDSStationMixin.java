package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.bind.MtrTiles;
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

import java.util.Set;

/**
 * 让<b>站在车站范围外</b>的到站投影机也能显示到站 —— 换掉 {@code RenderPIDS#getSchedules} 里那次
 * 「按方块自己的位置找车站」的调用。
 *
 * <h2>MTR 原来的行为（javap 证据，3.6.4.2 / 1.20.1）</h2>
 * <pre>
 * RenderPIDS.getSchedules(T be, BlockPos pos, Map&lt;Long,String&gt; stationIdToName):
 *   0: RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)
 *  12: if (station == null) return new ArrayList&lt;&gt;();       // ← 一句话都不显示
 *  25: stationIdToPlatforms = DATA_CACHE.requestStationIdToPlatforms(station.id)
 *  38: if (stationIdToPlatforms.isEmpty()) return new ArrayList&lt;&gt;()
 *  96: platformIds = ((TileEntityArrivalProjectorBase) be).getPlatformIds()   // 投影机分支
 * 254: 遍历 stationIdToPlatforms.values()，按 platformIds 过滤 → 到站列表
 * </pre>
 *
 * <p>所以 MTR 的投影机数据模型其实是「<b>一组站台 id</b>」，但<b>渲染</b>又要求「投影机自己站在
 * 某个车站矩形里」，然后只在这座车站的站台里过滤。投影机放在站外（隧道口、站前广场）时
 * {@code getStation} 返回 null，第一行就 {@code return}，写得再对也是黑屏 —— 这不是写入失败，
 * {@code setData} 确实写进去了（{@link MtrTiles#projectorSetPlatforms} 之后回读非空）。
 *
 * <h2>这里做什么</h2>
 * 只替换那一次 {@code getStation} 调用：
 * <ol>
 *   <li>真在车站里 → 原样返回（MTR 行为完全不变，PIDS 也一样）；</li>
 *   <li>站外、而且这一格是<b>投影机</b>、而且它的 {@code platformIds} 非空 →
 *       用 {@code DataCache.platformIdToStation} 把第一个能反查到车站的站台 id 换成那座车站；</li>
 *   <li>其它情况 → {@code null}（保持 MTR 的原行为）。</li>
 * </ol>
 * 之后 MTR 自己的 {@code requestStationIdToPlatforms(station.id)} + {@code platformIds} 过滤照常跑，
 * 于是「站外投影机」显示的就是它绑定的那座车站的到站 —— 不需要重写渲染，也不需要自己算时刻表。
 *
 * <p>{@code remap = false}：MTR 的 jar 不含 yarn 映射，方法名就是源码名（{@code getSchedules}、
 * {@code getStation}）。注入找不到方法时由 {@code mtrlte.mtr.mixins.json} 的
 * {@code defaultRequire: 0} + {@code required: false} 兜底，最坏情况退回 MTR 原行为，不会让客户端起不来。
 */
@Mixin(value = RenderPIDS.class, remap = false)
public abstract class RenderPIDSStationMixin {

    /** 这条日志每帧都会走到，节流到 2 秒一条。 */
    private static long lte$lastLogAt;
    private static String lte$lastLogKey = "";

    @Redirect(
            method = "getSchedules",
            at = @At(value = "INVOKE", target = "Lmtr/data/RailwayData;getStation"),
            remap = false)
    private static Station lte$stationOutsideStation(final Set<Station> stations, final DataCache dataCache,
                                                     final BlockPos pos) {
        // 1) MTR 自己的判定优先，绝不改变「投影机在车站里」时的行为。
        final Station inside = RailwayData.getStation(stations, dataCache, pos);
        if (inside != null) {
            return inside;
        }
        if (pos == null || dataCache == null) {
            return null;
        }
        try {
            // 2) 站外：只有投影机才做兜底（PIDS 保持原样）。
            final ClientWorld world = MinecraftClient.getInstance().world;
            if (world == null) {
                return null;
            }
            final Object tile = world.getBlockEntity(pos);
            if (!MtrTiles.isProjector(tile)) {
                return null;
            }
            final Set<Long> platformIds = MtrTiles.projectorPlatformIds(tile);
            if (platformIds.isEmpty()) {
                return null;
            }
            for (final Long platformId : platformIds) {
                final Station station = dataCache.platformIdToStation.get(platformId);
                if (station != null) {
                    lte$log(pos, station, platformId, platformIds.size());
                    return station;
                }
            }
            lte$log(pos, null, null, platformIds.size());
        } catch (Throwable t) {
            // 渲染线程上任何异常都不能抛出去：抛出去就是整块 PIDS 不画 + 日志刷屏。
            lte$log(pos, null, null, -1);
        }
        return null;
    }

    private static void lte$log(final BlockPos pos, final Station station, final Long platformId,
                                final int platformCount) {
        final long now = System.currentTimeMillis();
        final String key = pos + "/" + (station == null ? "none" : station.id);
        if (now - lte$lastLogAt < 2000L && key.equals(lte$lastLogKey)) {
            return;
        }
        lte$lastLogAt = now;
        lte$lastLogKey = key;
        if (station == null) {
            LteLog.LOGGER.info(
                    "[lte] render pids: pos={} outside any station, platformIds={} -> no station resolvable "
                            + "(platforms must belong to a station known to the client)",
                    pos, Integer.valueOf(platformCount));
        } else {
            LteLog.LOGGER.info(
                    "[lte] render pids: pos={} outside any station -> using station {}/{} from platform {} "
                            + "of {} bound ids",
                    pos, Long.valueOf(station.id), station.name, platformId, Integer.valueOf(platformCount));
        }
    }
}
