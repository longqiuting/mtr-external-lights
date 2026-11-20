// PSD-LCD 挂板下沿那条线路色带的**颜色**：这个站台到底该画什么色（几条线就有几段）。
//
// 抄的是 MTR 自己的 `RouteMapGenerator#generateColorStrip` + `#getRouteStream`
// （见 docs/psd-lcd-line-strip.md）：MTR 生成一张 1×N 的位图，每个像素是「经过这个站台的
// 一条线路」的颜色，然后把这张图拉满吊板上那条 0.5px 的带子。我们不生成位图（纯色 quad
// 更省事、也不用往 MTR 的纹理缓存里塞东西），但**取色的顺序和判据逐条照搬**：
//
//   1. 只看 {@code ClientData.ROUTES} 里 {@code containsPlatformId(platformId)} 且没被隐藏的线路；
//   2. 按「颜色 → 线路名」排序（MTR 也是这么排的，保证同一存档每次都一样）；
//   3. 站台<b>不是</b>这条线的终点站 → 进主表；是终点站 → 先进「终点色」表；
//   4. 主表空（所有线路都在这里终到）才用终点色表；
//   5. 同色去重。
//
// 颜色本身过一遍 {@link XiamenRouteStyle#lineColor}（和厦门 PIDS 的线路徽标、线路条带同一个入口：
// 先看 {@code lineColors} 手工覆盖，再退回 MTR 的 {@code Route#color}）。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.ArrayList;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.MtrHeadlightClient;
import dev.js.mtrheadlight.xiamen.XiamenRouteStyle;
import mtr.client.ClientData;
import mtr.data.Route;

/** 线路色带的取色。全部走客户端那份 MTR 数据，不碰服务端。 */
public final class PsdLcdStrip {

    /** 白色：拿不到任何线路（单机存档还没同步 / 这块屏没绑站台）时的退化色。 */
    private static final int WHITE = 0xFFFFFFFF;

    /** 「退化到白色」这条 warning 只打一次（每帧都会走到这里）。 */
    private static boolean warned;

    /** 已经打过日志的 {@code platformId|颜色}，避免刷屏。 */
    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 上一次算出来的结果（渲染器每帧、每块板都问，别每帧都去扫 ClientData.ROUTES 并排序）。 */
    private static long cachedPlatform = Long.MIN_VALUE;

    private static int[] cachedColors;

    private static long cachedAt;

    /** 缓存有效期（毫秒）：线路色的改动（MTR 里改线色 / 加线）最多半秒后生效。 */
    private static final long CACHE_MS = 500L;

    private PsdLcdStrip() {
    }

    /**
     * 这个站台的色带颜色（从上到下的顺序，ARGB）。
     *
     * <p>空数组<b>不会</b>出现 —— 拿不到线路就返回一个白色像素，调用方不用再判空；
     * 拿不到的时候会 warning 一次（渲染器每帧都问，不能刷日志）。
     */
    public static int[] colors(final long platformId) {
        final long now = System.currentTimeMillis();
        final int[] cached = cachedColors;
        if (cached != null && cachedPlatform == platformId && now - cachedAt < CACHE_MS) {
            return cached;
        }
        final int[] out = compute(platformId);
        cachedPlatform = platformId;
        cachedColors = out;
        cachedAt = now;
        return out;
    }

    /** 真正算一次（不含缓存）。 */
    private static int[] compute(final long platformId) {
        final List<Integer> colors = resolve(platformId);
        if (!colors.isEmpty()) {
            final int[] out = new int[colors.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = colors.get(i).intValue();
            }
            logOnce(platformId, out);
            return out;
        }
        if (!warned) {
            warned = true;
            LteLog.LOGGER.warn("[lte] psd lcd: no route colour for platform {} "
                    + "(no route contains it / MTR data not synced yet) — drawing the line strip white",
                    Long.valueOf(platformId));
        }
        return new int[]{WHITE};
    }

    /** 取色主体（可能为空 = 没有线路）。 */
    private static List<Integer> resolve(final long platformId) {
        final List<Integer> primary = new ArrayList<>();
        final List<Integer> terminating = new ArrayList<>();
        if (platformId == 0L) {
            return primary;
        }
        try {
            final List<Route> routes = new ArrayList<>();
            for (final Route route : ClientData.ROUTES) {
                if (route != null && !route.isHidden && route.containsPlatformId(platformId)) {
                    routes.add(route);
                }
            }
            // MTR 的排序：颜色升序，同色按名字（保证确定性）。
            routes.sort((a, b) -> {
                final int ca = a.color & 0xFFFFFF;
                final int cb = b.color & 0xFFFFFF;
                if (ca != cb) {
                    return Integer.compare(ca, cb);
                }
                final String na = a.name == null ? "" : a.name;
                final String nb = b.name == null ? "" : b.name;
                return na.compareTo(nb);
            });
            for (final Route route : routes) {
                final int rgb = rgbOf(route);
                if (rgb == 0) {
                    continue;
                }
                final int index = route.getPlatformIdIndex(platformId);
                final boolean terminatesHere = index < 0
                        || index >= route.platformIds.size() - 1;
                final List<Integer> target = terminatesHere ? terminating : primary;
                if (!target.contains(Integer.valueOf(rgb))) {
                    target.add(Integer.valueOf(rgb));
                }
            }
            if (primary.isEmpty()) {
                primary.addAll(terminating);
            }
        } catch (final Throwable t) {
            // 数据正在被服务端包改写：这一帧按「没有线路」处理，下一帧再试。
            LteLog.LOGGER.debug("[lte] psd lcd: could not resolve the line colours", t);
        }
        return primary;
    }

    /** 一条线路的 ARGB（0 = 这条线没有颜色）。 */
    private static int rgbOf(final Route route) {
        final int rgb;
        try {
            rgb = XiamenRouteStyle.lineColor(MtrHeadlightClient.config(), route, route.name) & 0xFFFFFF;
        } catch (final Throwable t) {
            return (route.color & 0xFFFFFF) == 0 ? 0 : 0xFF000000 | (route.color & 0xFFFFFF);
        }
        // XiamenRouteStyle 找不到颜色时给的是灰色 FALLBACK_RGB —— 那条线当作「有颜色」，照画。
        return 0xFF000000 | rgb;
    }

    /** 每个站台只打一行：{@code [lte] psd lcd: strip platform=… colors=#RRGGBB,#RRGGBB}。 */
    private static void logOnce(final long platformId, final int[] colors) {
        final StringBuilder key = new StringBuilder().append(platformId);
        final StringBuilder text = new StringBuilder();
        for (final int c : colors) {
            key.append('|').append(Integer.toHexString(c));
            if (text.length() > 0) {
                text.append(',');
            }
            text.append('#').append(String.format("%06X", Integer.valueOf(c & 0xFFFFFF)));
        }
        if (LOGGED.size() > 256 || !LOGGED.add(key.toString())) {
            return;
        }
        LteLog.LOGGER.info("[lte] psd lcd: strip platform={} colors={}",
                Long.valueOf(platformId), text);
    }
}
