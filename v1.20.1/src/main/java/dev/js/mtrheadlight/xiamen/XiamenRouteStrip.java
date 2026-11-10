// 线路条带的 MTR 胶水层：从 MTR 数据取出站序 / 线路色，交给 XiamenStripPainter 画，再转成 NativeImage。
package dev.js.mtrheadlight.xiamen;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.MtrHeadlightClient;
import mtr.client.ClientCache;
import mtr.client.ClientData;
import mtr.data.Route;
import net.minecraft.client.texture.NativeImage;

/**
 * 吊板（{@code BlockPSDTop}）与半高 APG 玻璃（{@code BlockAPGGlass}）上那条线路图的<b>重绘</b>：
 * 只负责“拿数据 + 换位图”，版式全部在 {@link XiamenStripPainter}（纯 AWT，可离线预览）。
 *
 * <h2>为什么拦位图而不是另写一个渲染器</h2>
 * <p>MTR 的画法：{@code RenderRouteBase#render}（<b>final</b>，改不了）只负责把一张纹理贴到面板上，
 * 纹理来自 {@code ClientData.DATA_CACHE.getRouteMap(platformId, vertical, flip, aspectRatio,
 * transparentWhite)} —— 一个 {@code ClientCache.DynamicResource}，内容是
 * {@code RouteMapGenerator.generateRouteMap(...)} 当场画的 {@link NativeImage}。</p>
 *
 * <p>所以「不碰 MTR 的类」：{@code RouteMapArtMixin} 在 {@code generateRouteMap} 的 HEAD 拦一下，
 * 把这里画好的图交回去；缓存键、纹理注册、面板几何、色带（{@code generateColorStrip}）、
 * 方向箭头仍旧是 MTR 原来那条路。画不出来（纵向、环线、数据没到、配置关掉、任何异常）就返回
 * {@code null} → 不取消注入 → MTR 自己画原来那张，绝不会变成紫黑格。</p>
 *
 * <h2>数据来源（全是 MTR 的公开 API，与 MTR 自己的线路图同一份数据）</h2>
 * <ul>
 *   <li>站台 → 线路：{@code ClientData.DATA_CACHE.requestPlatformIdToRoutes(platformId)} →
 *       {@code PlatformRouteDetails.routeName / routeColor / circularState / currentStationIndex /
 *       stationDetails[i].stationName}；</li>
 *   <li>线路对象（拿 id / color / lightRailRouteNumber）：{@code ClientData.ROUTES} 里
 *       {@code containsPlatformId(platformId)} 且短名相同的那一条；</li>
 *   <li>线路色：{@link XiamenRouteStyle#lineColor}（MTR 的 {@code Route#color}，
 *       {@code lineColors} 只是可选覆盖，默认空）；</li>
 *   <li>色带 / 站名 / 尺寸：MTR 的 {@code getColorStrip}、{@code aspectRatio} 原样沿用。</li>
 * </ul>
 */
public final class XiamenRouteStrip {

    /** 已经打过日志的 platformId|routeName，避免刷屏（纹理按 key 缓存，正常每个面板只画一次）。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private XiamenRouteStrip() {
    }

    /**
     * 画一张条带。<b>返回 null 表示“这段我不画”</b>（调用方随即让 MTR 画它自己那张）。
     *
     * @param platformId  面板所属站台（{@code TileEntityRouteBase#getPlatformId}）
     * @param vertical    {@code true} = 竖向线路图（高杆牌那种），本类不接管
     * @param flip        {@code true} = 行进方向朝左（MTR 的 {@code arrowDirection == 2}）
     * @param aspectRatio 面板的 宽/高（MTR 用它定图片尺寸）
     */
    public static NativeImage paint(final long platformId, final boolean vertical, final boolean flip,
                                    final float aspectRatio) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null || !cfg.xiamenRouteStrip || vertical || !(aspectRatio > 0.05f)) {
                return null;
            }
            final ClientCache.PlatformRouteDetails details = primaryDetails(platformId);
            if (details == null) {
                return null;
            }
            final List<ClientCache.PlatformRouteDetails.StationDetails> stations = details.stationDetails;
            final int count = stations == null ? 0 : stations.size();
            if (count < 2) {
                return null;
            }
            final Route route = XiamenRouteStyle.findRoute(platformId, details.routeName);
            final int lineRgb = XiamenRouteStyle.lineColor(cfg, route, details.routeName);

            final XiamenStripPainter.Model model = new XiamenStripPainter.Model();
            model.names = new String[count];
            for (int i = 0; i < count; i++) {
                final ClientCache.PlatformRouteDetails.StationDetails station = stations.get(i);
                model.names[i] = XiamenStripPainter.cjkPart(station == null ? "" : station.stationName);
            }
            model.currentIndex = details.currentStationIndex;
            model.lineRgb = lineRgb;
            model.lineNumber = XiamenRouteStyle.lineNumber(route, details.routeName);
            model.flip = flip;
            model.namesBelow = cfg.routeStripNamesBelow;
            model.aspectRatio = aspectRatio;

            final BufferedImage image = XiamenStripPainter.paint(model);
            if (image == null) {
                return null;
            }
            logOnce(platformId, details.routeName, route, lineRgb, count, image);
            return toNativeImage(image);
        } catch (Throwable t) {
            // 任何异常都不许影响渲染：直接让 MTR 画原来那张。
            LteLog.LOGGER.warn("[lte] route strip: fell back to MTR's route map", t);
            return null;
        }
    }

    /**
     * 这个站台的主线路。多线共站时取<b>第一条</b>（MTR 数据里就是站台所属线路的顺序，
     * 与线路牌 / 色带同一顺序），这样条带和下方色带永远说的是同一条线。
     */
    private static ClientCache.PlatformRouteDetails primaryDetails(final long platformId) {
        final List<ClientCache.PlatformRouteDetails> all = ClientData.DATA_CACHE.requestPlatformIdToRoutes(platformId);
        if (all == null || all.isEmpty()) {
            return null;
        }
        for (final ClientCache.PlatformRouteDetails details : all) {
            if (details == null) {
                continue;
            }
            // 环线在 MTR 里画的是一圈胶囊图，条带画不下（硬画会丢掉“环”这个信息）→ 交回 MTR。
            if (details.circularState != Route.CircularState.NONE) {
                continue;
            }
            if (details.stationDetails != null && details.stationDetails.size() >= 2) {
                return details;
            }
        }
        return null;
    }

    /**
     * 面板下沿那条 1px 色带的替代品：<b>只在 {@link HeadlightConfig#lineColors} 手工覆盖了颜色时</b>
     * 才接管（没有覆盖就返回 null → MTR 原样画）。
     *
     * <p>MTR 的 {@code generateColorStrip} 是对每个“本站台所属线路”写 1xN 像素，颜色是
     * {@code Route#color}；这里换成同样的布局、同样的顺序，只把颜色过一遍 {@link XiamenRouteStyle}，
     * 于是条带和色带说的永远是同一个颜色。</p>
     */
    public static NativeImage colorStrip(final long platformId) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null || cfg.lineColors == null || cfg.lineColors.isEmpty()) {
                return null;
            }
            final List<ClientCache.PlatformRouteDetails> all = ClientData.DATA_CACHE.requestPlatformIdToRoutes(platformId);
            if (all == null || all.isEmpty()) {
                return null;
            }
            boolean overridden = false;
            for (final ClientCache.PlatformRouteDetails details : all) {
                if (details == null) {
                    continue;
                }
                final Route route = XiamenRouteStyle.findRoute(platformId, details.routeName);
                if (XiamenRouteStyle.overrideColor(cfg, route, details.routeName) != null) {
                    overridden = true;
                    break;
                }
            }
            if (!overridden) {
                return null;
            }
            final NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, all.size(), false);
            int row = 0;
            for (final ClientCache.PlatformRouteDetails details : all) {
                if (details == null) {
                    continue;
                }
                final Route route = XiamenRouteStyle.findRoute(platformId, details.routeName);
                image.setColor(0, row++, abgr(0xFF000000 | XiamenRouteStyle.lineColor(cfg, route, details.routeName)));
            }
            return image;
        } catch (Throwable t) {
            return null;
        }
    }

    /** ARGB → {@code NativeImage#setColor} 要的 ABGR 字节序（MTR 的 {@code invertColor} 等价物）。 */
    private static int abgr(final int argb) {
        return (argb & 0xFF00FF00)
                | ((argb & 0x00FF0000) >>> 16)
                | ((argb & 0x000000FF) << 16);
    }

    /**
     * {@link BufferedImage} → {@link NativeImage}（RGBA）。
     *
     * <p>alpha 保留，最后按 “&lt; 64 丢弃、其余不透明” 量化 —— 面板走 cutout 渲染层，
     * 半透明边缘会被当成实心像素，量化后文字边缘才干净。</p>
     */
    private static NativeImage toNativeImage(final BufferedImage image) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        final NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            for (int x = 0; x < w; x++) {
                final int color = argb[row + x];
                final int a = (color >>> 24) & 0xFF;
                final int quantized = a < 64 ? 0 : 0xFF000000 | (color & 0xFFFFFF);
                nativeImage.setColor(x, y, abgr(quantized));
            }
        }
        return nativeImage;
    }

    private static void logOnce(final long platformId, final String routeName, final Route route,
                                final int lineRgb, final int stations, final BufferedImage image) {
        final String key = platformId + "|" + routeName;
        if (LOGGED.size() > 256 || !LOGGED.add(key)) {
            return;
        }
        LteLog.LOGGER.info("[lte] route strip: platform={} route={} id={} color=#{} stations={} tex={}x{} "
                        + "band={} line={} dot={} current={} font={} nameSize={}",
                Long.valueOf(platformId), XiamenRouteStyle.shortName(routeName),
                route == null ? "?" : Long.valueOf(route.id), String.format("%06X", lineRgb & 0xFFFFFF),
                Integer.valueOf(stations), Integer.valueOf(image.getWidth()), Integer.valueOf(image.getHeight()),
                Integer.valueOf(XiamenStripPainter.lastBandH), Integer.valueOf(XiamenStripPainter.lastLineW),
                Integer.valueOf(XiamenStripPainter.lastCircle), Integer.valueOf(XiamenStripPainter.lastCurrent),
                XiamenStripPainter.lastFontName, Integer.valueOf(XiamenStripPainter.lastNameSize));
    }
}
