// 屏蔽门门灯颜色：按“门所在的站台属于哪条线路”决定（默认橙 = MTR 原版贴图色）。
package dev.js.mtrheadlight.xiamen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.MtrHeadlightClient;
import mtr.client.ClientCache;
import mtr.client.ClientData;
import mtr.data.RailwayData;
import mtr.data.Route;
import net.minecraft.util.math.BlockPos;

/**
 * 门灯颜色解析。
 *
 * <h2>为什么能“按线路”</h2>
 * <p>门方块本身没有任何线路字段（{@code TileEntityPSDAPGDoorBase} 只有开合状态），但站台有：
 * {@code RailwayData.getClosePlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE, pos)}
 * 从门的坐标找到最近的站台（MTR 自己也用这一条把站台和 PSD 方块对应起来），再由
 * {@code DATA_CACHE.requestPlatformIdToRoutes(platformId)} 拿到该站台所属线路，最后从线路名里抽
 * 线路号（{@code 1号线} → {@code 1}）。</p>
 *
 * <h2>配置</h2>
 * <ul>
 *   <li>{@link HeadlightConfig#doorLightColor}：默认值，{@code orange}（MTR 原版 {@code light_on.png}）；</li>
 *   <li>{@link HeadlightConfig#doorLightByLine}：按线路号覆盖，例如 {@code {"4": "green", "6": "green"}}
 *       —— 4/6 号线以后要切绿，加这一条就行（默认空）。</li>
 * </ul>
 *
 * <p>结果按站台缓存，并在 MTR 数据刷新（{@code DataCache#needsRefresh}）时整表清空，所以在线路颜色 /
 * 站台被改过之后下一帧就会跟上。</p>
 */
public final class XiamenDoorLights {

    /** pos → 线路号（已经是“这条线路的门灯该用哪个颜色”的上一步）。 */
    private static final Map<Long, String> LINE_BY_POS = new HashMap<>();
    private static long lastRefresh;

    private XiamenDoorLights() {
    }

    /** 这道门该用哪个颜色的门灯贴图：{@code orange} / {@code green} / {@code blue}。 */
    public static String colorFor(final BlockPos pos) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null) {
                return HeadlightConfig.DOOR_LIGHT_ORANGE;
            }
            // 没有按线路的配置时不必去查线路（绝大多数情况，零开销）。
            if (cfg.doorLightByLine == null || cfg.doorLightByLine.isEmpty()) {
                return HeadlightConfig.normalizeDoorLightColor(cfg.doorLightColor);
            }
            return XiamenRouteStyle.doorLightColor(cfg, lineNumberAt(pos));
        } catch (Throwable t) {
            return HeadlightConfig.DOOR_LIGHT_ORANGE;
        }
    }

    /** 门所在站台的线路号（找不到就是空串）。 */
    public static String lineNumberAt(final BlockPos pos) {
        if (pos == null) {
            return "";
        }
        final String cached = cachedLine(pos);
        if (cached != null) {
            return cached;
        }
        String number = "";
        try {
            final long platformId = RailwayData.getClosePlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE, pos);
            if (platformId != 0L) {
                final List<ClientCache.PlatformRouteDetails> routes =
                        ClientData.DATA_CACHE.requestPlatformIdToRoutes(platformId);
                if (routes != null) {
                    for (final ClientCache.PlatformRouteDetails details : routes) {
                        if (details == null) {
                            continue;
                        }
                        final Route route = XiamenRouteStyle.findRoute(platformId, details.routeName);
                        number = XiamenRouteStyle.lineNumber(route, details.routeName);
                        if (!number.isEmpty()) {
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            number = "";
        }
        synchronized (LINE_BY_POS) {
            if (LINE_BY_POS.size() > 4096) {
                LINE_BY_POS.clear();
            }
            LINE_BY_POS.put(Long.valueOf(pos.asLong()), number);
        }
        return number;
    }

    private static String cachedLine(final BlockPos pos) {
        try {
            if (ClientData.DATA_CACHE.needsRefresh(lastRefresh)) {
                synchronized (LINE_BY_POS) {
                    LINE_BY_POS.clear();
                }
                lastRefresh = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
            return null;
        }
        synchronized (LINE_BY_POS) {
            return LINE_BY_POS.get(Long.valueOf(pos.asLong()));
        }
    }
}
