// 屏蔽门门灯颜色：按「门方块属于哪条线路」决定（1/2/3 橙，4/6 绿），配置可逐线覆盖。
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
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/**
 * 门灯颜色解析。
 *
 * <h2>为什么「按方块」就够了</h2>
 * <p>门方块本身没有线路字段（{@code TileEntityPSDAPGDoorBase} 只有开合状态），但<b>方块实例</b>有：
 * 每条线路的 7 个方块是分别 {@code new} 出来的（{@link XiamenPsdBlocks.LineAware#line()}），
 * 渲染器每帧都能从 {@code entity.getCachedState().getBlock()} 反查线路。
 * 门灯颜色是线路的规矩（1/2/3 橙、4/6 绿），所以直接由
 * {@link XiamenLine#doorLight()} 给出 —— 确定、无状态、不用查世界。
 *
 * <p>以前那套「门所在站台属于哪条线路 → 线路号 → 查配置」的链路（
 * {@code RailwayData.getClosePlatformId} → {@code requestPlatformIdToRoutes}）保留在
 * {@link #colorFor(BlockPos)} 里，只作为<b>拿不到方块实例时的兜底</b>：
 * 门灯不再依赖站台数据，站台没连线路、线路没起名、数据还没同步，都不会把 4/6 号线画成橙色。
 *
 * <h2>优先级</h2>
 * <ol>
 *   <li>{@link HeadlightConfig#doorLightByLine}（键 = {@code 4} / {@code 4号线}）—— 逐线手工覆盖；</li>
 *   <li>{@link HeadlightConfig#doorLightColor} <b>不是默认的 {@code orange} 时</b> —— 全局手工覆盖；</li>
 *   <li>{@link XiamenLine#doorLight()} —— 内置线路色（1/2/3 橙、4/6 绿）。</li>
 * </ol>
 *
 * <p>结果按站台 / 坐标缓存（只有兜底链路用），并在 MTR 数据刷新
 * （{@code DataCache#needsRefresh}）时整表清空，所以在线路颜色 / 站台被改过之后下一帧就会跟上。
 */
public final class XiamenDoorLights {

    /** pos → 线路号（已经是“这条线路的门灯该用哪个颜色”的上一步）。 */
    private static final Map<Long, String> LINE_BY_POS = new HashMap<>();
    private static long lastRefresh;

    private XiamenDoorLights() {
    }

    /**
     * 这道门该用哪个颜色的门灯贴图：{@code orange} / {@code green} / {@code blue}。
     *
     * <p>主入口：{@code block} 是门 / 玻璃 / 端部方块本身，线路从方块实例读，
     * 所以同一条站台上混放 3 号线和 4 号线的门也会各画各的颜色。
     */
    public static String colorFor(final Block block, final BlockPos pos) {
        try {
            final XiamenLine line = XiamenObjects.lineOf(block);
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null) {
                return line.doorLight();
            }
            // ① 逐线手工覆盖（最具体）。
            final String byLine = lookup(cfg.doorLightByLine, line.number);
            if (byLine != null) {
                return HeadlightConfig.normalizeDoorLightColor(byLine);
            }
            // ② 全局手工覆盖：只有“不是默认橙”才算数（默认值不算手工设置，
            //    否则 4/6 号线永远被默认值压回橙色）。
            final String global = HeadlightConfig.normalizeDoorLightColor(cfg.doorLightColor);
            if (!HeadlightConfig.DOOR_LIGHT_ORANGE.equals(global)) {
                return global;
            }
            // ③ 内置线路色。
            return line.doorLight();
        } catch (Throwable t) {
            return HeadlightConfig.DOOR_LIGHT_ORANGE;
        }
    }

    /**
     * 兜底入口（拿不到方块实例时）：按「门所在站台属于哪条线路」查。
     *
     * <p>原来的唯一链路，现在只在 {@link #colorFor(Block, BlockPos)} 拿不到方块时使用。
     */
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

    /** 配置里这个线路号的覆盖值（键允许写成 {@code 4} / {@code 4号线} / {@code 号线} 大小写混写）。 */
    private static String lookup(final Map<String, String> map, final String number) {
        if (map == null || map.isEmpty() || number == null || number.isEmpty()) {
            return null;
        }
        String value = map.get(number);
        if (value == null) {
            value = map.get(number + "号线");
        }
        if (value == null) {
            final String lower = number.toLowerCase(java.util.Locale.ROOT);
            for (final Map.Entry<String, String> entry : map.entrySet()) {
                final String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(java.util.Locale.ROOT);
                if (key.equals(lower) || key.equals(lower + "号线")) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        return value;
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
