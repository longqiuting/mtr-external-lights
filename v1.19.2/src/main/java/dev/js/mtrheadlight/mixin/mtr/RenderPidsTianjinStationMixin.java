package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.bind.PidsStationResolver;
import mtr.data.DataCache;
import mtr.data.RailwayData;
import mtr.data.Station;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * 天津地铁（{@code ziyue.tjmetro}）的 PIDS <b>不走 MTR 的 {@code RenderPIDS}</b>：它自带一份渲染器
 * {@code ziyue.tjmetro.render.RenderPIDSTianjin}，里面抄了一份 {@code getSchedules}
 * （javap 证据，TianjinMetro-fabric-1.20.1-1.1.3）：
 *
 * <pre>
 * ziyue.tjmetro.render.RenderPIDSTianjin.getSchedules(T, BlockPos, Map):
 *   0: RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)
 *  12: if (station == null) return new ArrayList&lt;&gt;();     // ← 站外 = 空屏
 *  25: requestStationIdToPlatforms(station.id)
 *  56: platformIds = be.getPlatformIds()                     // 读的是 BE 自己
 *  62: platformIds.isEmpty() ? 自动模式(就近站台) : platformIds
 * </pre>
 *
 * <p>也就是说：连接器把 {@code platformIds} 写进天津 PIDS 之后，<b>站内同站台</b>能显示，但
 * 「站外 / 跨站绑定」这条功能对天津屏完全无效（{@code station == null} → 直接返回空表）。
 * {@link RenderPIDSStationMixin} 只打在 {@code mtr.render.RenderPIDS} 上，永远碰不到这个类。
 *
 * <p>所以这里把<b>同一套判定</b>（{@link PidsStationResolver}，唯一输入是这一格方块实体里的
 * {@code platformIds}）也接到天津的渲染器上。{@code RenderPIDSTianjinSingle extends
 * RenderPIDSTianjin}，注入基类即覆盖两种型号。
 *
 * <p>目标类只在装了天津地铁时存在：{@code MtrMixinPlugin} 用 {@code HostProbe.tjPidsRenderer()}
 * 探测，没装就整条 mixin 不应用（{@code @Mixin(targets = ...)} 也不会去加载它）。
 */
@Mixin(targets = "ziyue.tjmetro.render.RenderPIDSTianjin", remap = false)
public abstract class RenderPidsTianjinStationMixin {

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
            PidsStationResolver.log("tj", pos, result);
            return result.station();
        } catch (Throwable t) {
            try {
                return RailwayData.getStation(stations, dataCache, pos);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
