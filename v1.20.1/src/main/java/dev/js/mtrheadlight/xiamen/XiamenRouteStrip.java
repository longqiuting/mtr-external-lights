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
 *   <li><b>换乘站</b>：{@code stationDetails[i].interchangeRoutes}（{@code List<ColorNameTuple>}，
 *       颜色 + 线路名）—— MTR 自己画换乘圆环用的就是这一份；空时按站名兜底读
 *       {@code DATA_CACHE.stationIdToRoutes}（见 {@link #interchanges}）；</li>
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

    /** 已经打过日志的“换乘站 → 换乘线路”，避免刷屏。 */
    private static final Set<String> INTERCHANGE_LOGGED = ConcurrentHashMap.newKeySet();

    /** 已经打过日志的“方向面板站台”，避免刷屏。 */
    private static final Set<String> DIRECTION_LOGGED = ConcurrentHashMap.newKeySet();

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
            model.namesEn = new String[count];
            for (int i = 0; i < count; i++) {
                final ClientCache.PlatformRouteDetails.StationDetails station = stations.get(i);
                final String raw = station == null ? "" : station.stationName;
                model.names[i] = XiamenStripPainter.cjkPart(raw);
                model.namesEn[i] = XiamenStripPainter.enPart(raw);
            }
            model.currentIndex = details.currentStationIndex;
            model.lineRgb = lineRgb;
            model.lineNumber = XiamenRouteStyle.lineNumber(route, details.routeName);
            model.flip = flip;
            model.namesBelow = cfg.routeStripNamesBelow;
            model.aspectRatio = aspectRatio;
            model.interchanges = interchanges(cfg, stations, details.routeName);

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
     * 线上的换乘站（没有就 null —— 那时整条线画得和以前一模一样）。
     *
     * <h2>数据来源（MTR 自己的，不写死）</h2>
     * <p>主来源是 {@code PlatformRouteDetails.StationDetails#interchangeRoutes}：
     * MTR 在 {@code ClientCache#requestPlatformIdToRoutes} 里用
     * {@code lambda$requestPlatformIdToRoutes$5} 过滤出来的 {@code (color, name)} 列表 ——
     * 过滤条件是 {@code tuple.color != 当前线路 color}，也就是“这个站上别的线路”，
     * 和 MTR 自己画换乘圆环用的是同一份数据。</p>
     *
     * <p>兜底：列表为空时（或唯一那条被同色过滤掉），按<b>站名</b>去
     * {@code ClientData.STATIONS} 找车站，再读 {@code DATA_CACHE.stationIdToRoutes}
     * （{@code Map<stationId, Map<index, ColorNameTuple>>}，同样是缓存里的派生表），
     * 跳过本站自己的线路。</p>
     *
     * <p>两条路都拿不到 → 不画换乘标记（<b>不缺数据时绝不瞎猜</b>，退回普通圆环），
     * 并在日志里说明。</p>
     */
    private static XiamenStripPainter.Interchange[] interchanges(
            final HeadlightConfig cfg,
            final List<ClientCache.PlatformRouteDetails.StationDetails> stations,
            final String routeName) {
        final XiamenStripPainter.Interchange[] result = new XiamenStripPainter.Interchange[stations.size()];
        int count = 0;
        for (int i = 0; i < stations.size(); i++) {
            final ClientCache.PlatformRouteDetails.StationDetails station = stations.get(i);
            if (station == null) {
                continue;
            }
            final ClientCache.ColorNameTuple tuple = interchangeTuple(station, routeName);
            if (tuple == null) {
                continue;
            }
            final Route swapRoute = XiamenRouteStyle.findRouteByName(tuple.name);
            final Integer override = XiamenRouteStyle.overrideColor(cfg, swapRoute, tuple.name);
            final int rgb;
            if (override != null) {
                rgb = override.intValue();
            } else if ((tuple.color & 0xFFFFFF) != 0) {
                rgb = tuple.color & 0xFFFFFF;
            } else {
                rgb = XiamenRouteStyle.lineColor(cfg, swapRoute, tuple.name);
            }
            final String cjk = XiamenStripPainter.cjkPart(tuple.name);
            final String number = XiamenRouteStyle.lineNumber(swapRoute, tuple.name);
            result[i] = new XiamenStripPainter.Interchange(i, rgb, number, cjk);
            count++;
            logInterchange(cjk.isEmpty() ? tuple.name : cjk, number, rgb);
        }
        return count == 0 ? null : result;
    }

    /** 这个站的“别的线路”：先看 MTR 的 interchangeRoutes，再按站名兜底。 */
    private static ClientCache.ColorNameTuple interchangeTuple(
            final ClientCache.PlatformRouteDetails.StationDetails station, final String routeName) {
        final List<ClientCache.ColorNameTuple> list = station.interchangeRoutes;
        if (list != null) {
            for (final ClientCache.ColorNameTuple tuple : list) {
                if (tuple != null && tuple.name != null && !tuple.name.isEmpty()
                        && !sameLine(tuple.name, routeName)) {
                    return tuple;
                }
            }
        }
        return interchangeTupleFromStation(station.stationName, routeName);
    }

    /** 兜底：站名 → 车站 → {@code stationIdToRoutes} 里第一条别的线路。 */
    private static ClientCache.ColorNameTuple interchangeTupleFromStation(final String stationName,
                                                                        final String routeName) {
        if (stationName == null || stationName.isEmpty()) {
            return null;
        }
        try {
            for (final mtr.data.Station station : ClientData.STATIONS) {
                if (station == null || station.name == null || !sameStation(station.name, stationName)) {
                    continue;
                }
                final java.util.Map<Integer, ClientCache.ColorNameTuple> all =
                        ClientData.DATA_CACHE.stationIdToRoutes.get(Long.valueOf(station.id));
                if (all == null) {
                    return null;
                }
                for (final ClientCache.ColorNameTuple tuple : all.values()) {
                    if (tuple != null && tuple.name != null && !tuple.name.isEmpty()
                            && !sameLine(tuple.name, routeName)) {
                        return tuple;
                    }
                }
                return null;
            }
        } catch (Throwable used) {
            // 数据没同步 / 表被换掉：当作没有换乘数据。
        }
        return null;
    }

    /** 两条线路是不是同一条（比短名，也比中文段）。 */
    private static boolean sameLine(final String a, final String b) {
        if (a == null || b == null) {
            return false;
        }
        final String sa = XiamenRouteStyle.shortName(a);
        final String sb = XiamenRouteStyle.shortName(b);
        return sa.equals(sb) || XiamenStripPainter.cjkPart(a).equals(XiamenStripPainter.cjkPart(b));
    }

    /** 两个站名是不是同一个站（全名或中文段相等）。 */
    private static boolean sameStation(final String a, final String b) {
        return a != null && b != null
                && (a.equals(b) || XiamenStripPainter.cjkPart(a).equals(XiamenStripPainter.cjkPart(b)));
    }

    /** 每个“车站 + 换乘线路”只打一行：{@code [lte] strip: interchange station=<name> -> line=<id>}。 */
    private static void logInterchange(final String stationName, final String number, final int rgb) {
        final String key = stationName + "|" + number + "|" + Integer.toHexString(rgb & 0xFFFFFF);
        if (INTERCHANGE_LOGGED.size() > 256 || !INTERCHANGE_LOGGED.add(key)) {
            return;
        }
        LteLog.LOGGER.info("[lte] strip: interchange station={} -> line={} color=#{}",
                stationName, number.isEmpty() ? "?" : number + "号线", String.format("%06X", rgb & 0xFFFFFF));
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

    /**
     * 吊板 / 玻璃上那块“方向面板”（{@code RenderType.ARROW}，以及站名为空时的 {@code STATION_NAME}）
     * 的替代品：<b>去掉站台号码</b>，目的地中英文<b>两行水平居中</b>。
     *
     * <p>MTR 自己那张是 {@code RouteMapGenerator#generateDirectionArrow}：左边一个线路色圆 +
     * 圆内站台号，目的地从圆的右边左对齐。实拍吊板既没有站台号、文字也是居中的，所以整块重画
     * （{@link XiamenDirectionPanel}）；目的地文本仍走 MTR 的
     * {@code ClientCache#getFormattedRouteDestination}，不自己猜。</p>
     *
     * <p>返回 {@code null}（没有线路数据 / 数据没同步 / 配置关掉 / 任何异常）表示“这块不接管”，
     * 调用方随即让 MTR 画它自己那张。</p>
     */
    public static NativeImage directionArrow(final long platformId, final boolean hasLeft, final boolean hasRight,
                                             final boolean showToString, final float aspectRatio) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null || !cfg.xiamenRouteStrip || !(aspectRatio > 0.05f)) {
                return null;
            }
            final List<ClientCache.PlatformRouteDetails> all =
                    ClientData.DATA_CACHE.requestPlatformIdToRoutes(platformId);
            if (all == null || all.isEmpty()) {
                return null;
            }
            final StringBuilder cjk = new StringBuilder();
            final StringBuilder en = new StringBuilder();
            for (final ClientCache.PlatformRouteDetails details : all) {
                if (details == null || details.circularState != Route.CircularState.NONE) {
                    continue;
                }
                final Route route = XiamenRouteStyle.findRoute(platformId, details.routeName);
                if (route == null) {
                    continue;
                }
                final String destination = ClientData.DATA_CACHE.getFormattedRouteDestination(
                        route, details.currentStationIndex, "");
                if (destination == null || destination.trim().isEmpty()) {
                    continue;
                }
                final String dCjk = XiamenStripPainter.cjkPart(destination);
                final String dEn = XiamenStripPainter.enPart(destination);
                if (!dCjk.isEmpty()) {
                    if (cjk.length() > 0) {
                        cjk.append('／');
                    }
                    cjk.append(dCjk);
                }
                if (!dEn.isEmpty()) {
                    if (en.length() > 0) {
                        en.append(" / ");
                    }
                    en.append(dEn);
                }
            }
            if (cjk.length() == 0 && en.length() == 0) {
                return null;
            }
            final java.awt.image.BufferedImage image = XiamenDirectionPanel.paint(
                    cjk.toString(), en.toString(), hasLeft, hasRight, showToString, aspectRatio);
            if (image == null) {
                return null;
            }
            logDirection(platformId, cjk.toString(), en.toString(), image);
            return toNativeImage(image);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] direction panel: fell back to MTR's direction arrow", t);
            return null;
        }
    }

    /** 每个站台只打一行：{@code [lte] direction panel: platform=… "往XX" tex=WxH}。 */
    private static void logDirection(final long platformId, final String cjk, final String en,
                                     final java.awt.image.BufferedImage image) {
        final String key = "dir|" + platformId;
        if (DIRECTION_LOGGED.size() > 256 || !DIRECTION_LOGGED.add(key)) {
            return;
        }
        LteLog.LOGGER.info("[lte] direction panel: platform={} text=\"{}\" en=\"{}\" tex={}x{} cjk={}px font={}",
                Long.valueOf(platformId), cjk, en, Integer.valueOf(image.getWidth()),
                Integer.valueOf(image.getHeight()), Integer.valueOf(XiamenDirectionPanel.lastCjkSize),
                XiamenStripPainter.lastFontName);
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
                        + "band={} line={} dot={} current={} termRing={} termArrow={} swap={} ring={} plate={} "
                        + "font={} nameSize={}",
                Long.valueOf(platformId), XiamenRouteStyle.shortName(routeName),
                route == null ? "?" : Long.valueOf(route.id), String.format("%06X", lineRgb & 0xFFFFFF),
                Integer.valueOf(stations), Integer.valueOf(image.getWidth()), Integer.valueOf(image.getHeight()),
                Integer.valueOf(XiamenStripPainter.lastBandH), Integer.valueOf(XiamenStripPainter.lastLineW),
                Integer.valueOf(XiamenStripPainter.lastCircle), Integer.valueOf(XiamenStripPainter.lastCurrent),
                Integer.valueOf(XiamenStripPainter.lastTerminalRing),
                Integer.valueOf(XiamenStripPainter.lastTerminalArrow),
                Integer.valueOf(XiamenStripPainter.lastInterchangeCount),
                Integer.valueOf(XiamenStripPainter.lastSwapHead), Integer.valueOf(XiamenStripPainter.lastSwapPlate),
                XiamenStripPainter.lastFontName, Integer.valueOf(XiamenStripPainter.lastNameSize));
    }
}
