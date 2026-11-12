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
 *   <li><b>换乘站</b>：{@code route.platformIds[i] → DATA_CACHE.platformIdToStation →
 *       DATA_CACHE.stationIdToRoutes}（key = 线路色），按 MTR 自己的判据排除本站台线路 ——
 *       和 MTR 画换乘环同一份数据；退化时才用 {@code stationDetails[i].interchangeRoutes}
 *       （见 {@link #interchanges}）—— <b>v6 删除了按站名找车站的兜底</b>，那是“吊板出现
 *       不存在的换乘线路”的来源；</li>
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
            // ★ 作用域闸门：只有“正在渲染本模组的吊板 / 玻璃”时才接管，MTR 原版屏蔽门一律走它自己那张。
            if (!XiamenRouteScope.active()) {
                return null;
            }
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null || !cfg.xiamenRouteStrip || vertical || !(aspectRatio > 0.05f)) {
                return null;
            }
            final List<ClientCache.PlatformRouteDetails> all =
                    ClientData.DATA_CACHE.requestPlatformIdToRoutes(platformId);
            final ClientCache.PlatformRouteDetails details = primaryDetails(all);
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
            // v7：镜像由「这一格方块上的 ARROW_DIRECTION」决定（0 = 不镜像，1/2/3 = 镜像）。
            // 刷子四个形态都要能翻，所以优先用作用域里记下的方向值；拿不到（0）再退回 MTR 的 flip。
            final int direction = XiamenRouteScope.arrowDirection();
            model.direction = direction != 0 ? direction : (flip ? 2 : 0);
            model.namesBelow = cfg.routeStripNamesBelow;
            model.aspectRatio = aspectRatio;
            model.interchanges = interchanges(cfg, all, stations, details.routeName, route);

            final BufferedImage image = XiamenStripPainter.paint(model);
            if (image == null) {
                return null;
            }
            logOnce(platformId, details.routeName, route, lineRgb, count, image, model.direction);
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
    private static ClientCache.PlatformRouteDetails primaryDetails(
            final List<ClientCache.PlatformRouteDetails> all) {
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
     * <h2>判断依据：与 MTR 自己画换乘环的那一份<b>逐条对齐</b></h2>
     * <p>MTR 的算法（{@code RouteMapGenerator#generateRouteMap}，CFR 源码 line 604–631 + javap 两处一致）：</p>
     * <pre>
     *   stationId = getStationId(route.platformIds.get(i).platformId);   // 站台 → 所属车站
     *   stationIdToRoutes.get(stationId)                                 // 该车站的全部线路（key = 线路色）
     *     .forEach((color, tuple) !currentRouteColors.contains(color)
     *                          &amp;&amp; !currentRouteNames.contains(tuple.name) → 换乘线路)
     * </pre>
     *
     * <p>v6 就照这个来：{@code route.platformIds[i]} → {@code DATA_CACHE.platformIdToStation} →
     * {@code DATA_CACHE.stationIdToRoutes}，再用「本站台所有线路的颜色 / 名称」把它排除干净。
     * 这样每块牌子都落在<b>这条线第 i 站真实所属车站</b>上，物理上不可能画出一条本站不存在的线路。</p>
     *
     * <h2>v6 删掉的“幽灵换乘”来源</h2>
     * <p>旧实现（v2–v5）在主路径为空时按<b>站名</b>去 {@code ClientData.STATIONS} 找第一个同名车站，
     * 再读它的 {@code stationIdToRoutes}。同名不同站（不同存档 / 维度的同名站、中文段相同的两条站名）
     * 会被当成同一个站 —— 于是吊板上冒出一条本线根本不经过的线路。这段兜底已整段删除；
     * 兜底只剩“MTR 已经算好的 {@code StationDetails#interchangeRoutes}”，同样过一遍本站台的排除规则。</p>
     *
     * <p>两条路都拿不到 → 不画换乘标记（<b>不缺数据时绝不猜</b>，退回普通圆环）。</p>
     */
    private static XiamenStripPainter.Interchange[] interchanges(
            final HeadlightConfig cfg,
            final List<ClientCache.PlatformRouteDetails> allDetails,
            final List<ClientCache.PlatformRouteDetails.StationDetails> stations,
            final String routeName,
            final Route route) {
        // 本站台自己的线路：颜色 + 名称（多线共站时是好几条）—— 全部不算换乘。
        final Set<Integer> ownColors = new java.util.HashSet<>();
        final Set<String> ownNames = new java.util.HashSet<>();
        if (allDetails != null) {
            for (final ClientCache.PlatformRouteDetails details : allDetails) {
                if (details == null) {
                    continue;
                }
                ownColors.add(Integer.valueOf(details.routeColor & 0xFFFFFF));
                ownNames.add(XiamenRouteStyle.shortName(details.routeName));
            }
        }
        // 再补一层：这条线自己的颜色（lineColors 覆盖过颜色时，缓存里那条“别的线路”可能同色）。
        ownColors.add(Integer.valueOf(XiamenRouteStyle.lineColor(cfg, route, routeName) & 0xFFFFFF));
        ownNames.add(XiamenRouteStyle.shortName(routeName));

        final XiamenStripPainter.Interchange[] result = new XiamenStripPainter.Interchange[stations.size()];
        int count = 0;
        for (int i = 0; i < stations.size(); i++) {
            final ClientCache.PlatformRouteDetails.StationDetails station = stations.get(i);
            // ① MTR 的权威来源：第 i 站所属车站的全部线路（站名要对得上，见方法内注释）。
            ClientCache.ColorNameTuple tuple = stationInterchangeTuple(route, i,
                    station == null ? null : station.stationName, ownColors, ownNames);
            // ② 退化：MTR 已经按“当前线路色”过滤好的清单（仍然过一遍本站台的排除规则）。
            if (tuple == null && station != null) {
                tuple = firstForeignTuple(station.interchangeRoutes, ownColors, ownNames);
            }
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
            logInterchange(station == null ? "" : XiamenStripPainter.cjkPart(station.stationName),
                    cjk.isEmpty() ? tuple.name : cjk, number, rgb);
        }
        return count == 0 ? null : result;
    }

    /**
     * 这条线路第 {@code index} 站所属车站上“别的线路”里的第一条（按线路色升序，和 MTR 的
     * {@code allColors.sort(Integer::compareTo)} 一致，保证同一存档每次画出来都是同一条）。
     *
     * <p><b>索引对齐校验</b>：{@code stationDetails[i]} 是 MTR 从 {@code route.platformIds} 逐项 map 出来的，
     * 正常情况下下标一一对应。但存两个同名线路（或 {@code findRoute} 拿到另一个 Route 实例）时，
     * 两边的站序可能不是同一条线 —— 那样“第 i 个站台”就不是条带上第 i 个站，牌子会挂到别的站上
     * （同样表现为“出现不该有的换乘线路”）。所以这里再比一次<b>站名</b>：对不上就当作没有（
     * 交给调用方的退化路径，那条是按 stationDetails 自己的站算的）。</p>
     */
    private static ClientCache.ColorNameTuple stationInterchangeTuple(
            final Route route, final int index, final String expectedStationName,
            final Set<Integer> ownColors, final Set<String> ownNames) {
        if (route == null || route.platformIds == null || index < 0 || index >= route.platformIds.size()) {
            return null;
        }
        final long platformId = route.platformIds.get(index).platformId;
        final mtr.data.Station station =
                ClientData.DATA_CACHE.platformIdToStation.get(Long.valueOf(platformId));
        if (station == null) {
            return null;
        }
        if (expectedStationName != null && !expectedStationName.isEmpty()
                && !XiamenStripPainter.cjkPart(expectedStationName).equals(XiamenStripPainter.cjkPart(station.name))) {
            return null;
        }
        final java.util.Map<Integer, ClientCache.ColorNameTuple> all =
                ClientData.DATA_CACHE.stationIdToRoutes.get(Long.valueOf(station.id));
        return firstForeignTuple(all == null ? null : all.values(), ownColors, ownNames);
    }

    /**
     * 在给定集合里挑一条“不是本站台自己的线路”：颜色和名称都要排除掉，
     * 多条时取线路色最小的那条（确定性）。没有就 null。
     */
    private static ClientCache.ColorNameTuple firstForeignTuple(
            final java.util.Collection<ClientCache.ColorNameTuple> tuples,
            final Set<Integer> ownColors, final Set<String> ownNames) {
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }
        ClientCache.ColorNameTuple best = null;
        for (final ClientCache.ColorNameTuple tuple : tuples) {
            if (tuple == null || tuple.name == null || tuple.name.isEmpty()) {
                continue;
            }
            if (ownColors.contains(Integer.valueOf(tuple.color & 0xFFFFFF))
                    || ownNames.contains(XiamenRouteStyle.shortName(tuple.name))) {
                continue;
            }
            if (best == null || (tuple.color & 0xFFFFFF) < (best.color & 0xFFFFFF)) {
                best = tuple;
            }
        }
        return best;
    }

    /** 每个“车站 + 换乘线路”只打一行：{@code [lte] strip: interchange station=<name> -> line=<id>}。 */
    private static void logInterchange(final String stationName, final String lineName, final String number,
                                       final int rgb) {
        final String key = stationName + "|" + lineName + "|" + Integer.toHexString(rgb & 0xFFFFFF);
        if (INTERCHANGE_LOGGED.size() > 256 || !INTERCHANGE_LOGGED.add(key)) {
            return;
        }
        LteLog.LOGGER.info("[lte] strip: interchange station={} -> line={} color=#{}",
                stationName, lineName, String.format("%06X", rgb & 0xFFFFFF));
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
            // 同上：MTR 原版屏蔽门的色带一点都不改。
            if (!XiamenRouteScope.active()) {
                return null;
            }
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
     * 的替代品：<b>写当前站名</b>（中英文两行、水平居中），并且<b>不画任何方向小箭头</b>。
     *
     * <p>MTR 自己那张是 {@code RouteMapGenerator#generateDirectionArrow}：左边一个线路色圆 +
     * 圆内站台号，目的地从圆的右边左对齐，两侧还有小黑箭头。实拍吊板三者都没有（v4 用户纠正：
     * 这块位置写的是<b>当前站</b>，不是“往 XX”），所以整块重画（{@link XiamenDirectionPanel}）。
     * 站名仍按 {@code PlatformRouteDetails} 的 {@code currentStationIndex} 索引
     * {@code stationDetails}，不自己拼字符串。</p>
     *
     * <p>返回 {@code null}（不是我们的方块 / 没有线路数据 / 数据没同步 / 配置关掉 / 任何异常）
     * 表示“这块不接管”，调用方随即让 MTR 画它自己那张。</p>
     */
    public static NativeImage directionArrow(final long platformId, final boolean hasLeft, final boolean hasRight,
                                             final boolean showToString, final float aspectRatio) {
        try {
            if (!XiamenRouteScope.active()) {
                return null;
            }
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
                if (details == null || details.stationDetails == null || details.stationDetails.isEmpty()) {
                    continue;
                }
                final int index = Math.max(0, Math.min(details.stationDetails.size() - 1,
                        details.currentStationIndex));
                final ClientCache.PlatformRouteDetails.StationDetails station = details.stationDetails.get(index);
                final String rawName = station == null ? "" : station.stationName;
                if (rawName == null || rawName.trim().isEmpty()) {
                    continue;
                }
                final String cjkName = XiamenStripPainter.cjkPart(rawName);
                final String enName = XiamenStripPainter.enPart(rawName);
                if (!cjkName.isEmpty() && cjk.indexOf(cjkName) < 0) {
                    if (cjk.length() > 0) {
                        cjk.append('／');
                    }
                    cjk.append(cjkName);
                }
                if (!enName.isEmpty() && en.indexOf(enName) < 0) {
                    if (en.length() > 0) {
                        en.append(" / ");
                    }
                    en.append(enName);
                }
            }
            if (cjk.length() == 0 && en.length() == 0) {
                return null;
            }
            final java.awt.image.BufferedImage image = XiamenDirectionPanel.paint(
                    cjk.toString(), en.toString(), aspectRatio, hasLeft, hasRight);
            if (image == null) {
                return null;
            }
            logDirection(platformId, cjk.toString(), en.toString(), hasLeft, hasRight, image);
            return toNativeImage(image);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] direction panel: fell back to MTR's direction arrow", t);
            return null;
        }
    }

    /** 每个站台只打一行：{@code [lte] direction panel: platform=… "XX" arrows=L/R}. */
    private static void logDirection(final long platformId, final String cjk, final String en,
                                     final boolean hasLeft, final boolean hasRight,
                                     final java.awt.image.BufferedImage image) {
        final String key = "dir|" + platformId + "|" + hasLeft + hasRight;
        if (DIRECTION_LOGGED.size() > 256 || !DIRECTION_LOGGED.add(key)) {
            return;
        }
        LteLog.LOGGER.info("[lte] direction panel: platform={} text=\"{}\" en=\"{}\" arrows={}/{} tex={}x{} "
                        + "cjk={}px font={}",
                Long.valueOf(platformId), cjk, en, hasLeft ? "L" : "-", hasRight ? "R" : "-",
                Integer.valueOf(image.getWidth()), Integer.valueOf(image.getHeight()),
                Integer.valueOf(XiamenDirectionPanel.lastCjkSize), XiamenStripPainter.lastFontName);
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
                                final int lineRgb, final int stations, final BufferedImage image,
                                final int direction) {
        final String key = platformId + "|" + routeName + "|" + direction;
        if (LOGGED.size() > 256 || !LOGGED.add(key)) {
            return;
        }
        LteLog.LOGGER.info("[lte] route strip: platform={} route={} id={} color=#{} stations={} dir={} tex={}x{} "
                        + "band={} line={} dot={} current={} termRing={} termArrow={} swap={} ring={} plate={} "
                        + "font={} nameSize={}",
                Long.valueOf(platformId), XiamenRouteStyle.shortName(routeName),
                route == null ? "?" : Long.valueOf(route.id), String.format("%06X", lineRgb & 0xFFFFFF),
                Integer.valueOf(stations), Integer.valueOf(direction),
                Integer.valueOf(image.getWidth()), Integer.valueOf(image.getHeight()),
                Integer.valueOf(XiamenStripPainter.lastBandH), Integer.valueOf(XiamenStripPainter.lastLineW),
                Integer.valueOf(XiamenStripPainter.lastCircle), Integer.valueOf(XiamenStripPainter.lastCurrent),
                Integer.valueOf(XiamenStripPainter.lastTerminalRing),
                Integer.valueOf(XiamenStripPainter.lastTerminalArrow),
                Integer.valueOf(XiamenStripPainter.lastInterchangeCount),
                Integer.valueOf(XiamenStripPainter.lastSwapHead), Integer.valueOf(XiamenStripPainter.lastSwapPlate),
                XiamenStripPainter.lastFontName, Integer.valueOf(XiamenStripPainter.lastNameSize));
    }
}
