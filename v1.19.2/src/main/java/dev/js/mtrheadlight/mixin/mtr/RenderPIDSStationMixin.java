package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.bind.PidsStationResolver;
import mtr.data.DataCache;
import mtr.data.RailwayData;
import mtr.data.Station;
import mtr.render.RenderPIDS;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

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
 *  96 / 127 / 158: platformIds = be.getPlatformIds()           // 投影机 / PIDS 分支（读的是 be 自己）
 * 254: 遍历该车站的站台，按 platformIds 过滤 → 到站列表
 * </pre>
 *
 * <h2>这里做什么 / 不做什么</h2>
 * <p>只替换那一次 {@code getStation}：判定逻辑全部在 {@link PidsStationResolver} 里，
 * <b>唯一的输入是 {@code pos} 这一格的方块实体里的 {@code platformIds}</b>（每帧现读，没有任何
 * 「上次绑定 / 上次渲染的车站」缓存）。{@code platformIds} 为空（= 没绑过）时原样返回 MTR 的答案，
 * 也就是<b>自动模式</b>：新放的屏只受它自己的位置支配，不会被别的屏带跑。
 *
 * <p>日志只在变化时打一行（{@code tag} 用 {@code mtr} 区分本渲染器）：
 * <pre>{@code [lte] pids station: pos=... be=[...] -> station=<id|名字|null> src=be|mtr}</pre>
 *
 * <p>{@code remap = false}：MTR 的 jar 不含 yarn 映射，方法名就是源码名。注入找不到方法时由
 * {@code mtrlte.mtr.mixins.json} 的 {@code defaultRequire: 0} + {@code required: false} 兜底，
 * 最坏情况退回 MTR 原行为。
 */
@Mixin(value = RenderPIDS.class, remap = false)
public abstract class RenderPIDSStationMixin {

    @Redirect(
            method = "getSchedules",
            at = @At(value = "INVOKE", target = "Lmtr/data/RailwayData;getStation"),
            remap = false)
    private static Station lte$stationOfThisScreen(final Set<Station> stations,
                                                   final DataCache dataCache,
                                                   final BlockPos pos) {
        try {
            final PidsStationResolver.Result result =
                    PidsStationResolver.resolve(stations, dataCache, pos);
            PidsStationResolver.log("mtr", pos, result);
            return result.station();
        } catch (Throwable t) {
            // 渲染线程上任何异常都不能抛出去：抛出去就是整块 PIDS 不画。
            try {
                return RailwayData.getStation(stations, dataCache, pos);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
