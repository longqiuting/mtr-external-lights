// 厦门吊板 / APG 玻璃的线路条带：颜色与线路号解析（全部来自 MTR 数据，不写死）。
package dev.js.mtrheadlight.xiamen;

import java.awt.Color;
import java.util.Locale;

import dev.js.mtrheadlight.HeadlightConfig;
import mtr.client.ClientData;
import mtr.data.Route;

/**
 * 条带 / 门灯要用的“线路”信息：<b>主线颜色、线路号、文字对比色</b>。
 *
 * <h2>颜色从哪来</h2>
 * <ol>
 *   <li>{@link HeadlightConfig#lineColors} 里有这条线路的手工覆盖 → 用覆盖色
 *       （键匹配顺序：线路 id → 线路全名 → 线路号 {@code 1}）；</li>
 *   <li>否则用 MTR 里这条线路自己的 {@code Route#color}（厦门 1 号线是绿的、4 号线是红的，
 *       <b>自动跟随</b>，本模组不预设任何线路色）；</li>
 *   <li>线路色是 0（MTR 里没设色）→ {@link #FALLBACK_RGB} 灰白。</li>
 * </ol>
 *
 * <p>文字对比色按 {@code 0.299R + 0.587G + 0.114B} 的亮度判黑/白 —— 与 LCD 资源包
 * {@code lib/color_util.js#isLightColor} 完全同一套阈值（{@code > 128} 算亮色），
 * 所以模组和资源包的观感一致。
 */
public final class XiamenRouteStyle {

    /** 线路没设色时的 fallback（灰白，不是红）。 */
    public static final int FALLBACK_RGB = 0x808080;

    /** 站名正排时，非当前站用的 65% 灰。 */
    public static final int NAME_GREY = XiamenStripPainter.NAME_GREY;

    /** 深色文字（近黑，不用纯黑：小字号下纯黑发毛）。 */
    public static final int TEXT_DARK = XiamenStripPainter.TEXT_DARK;

    private XiamenRouteStyle() {
    }

    /** MTR 的线路名形如 {@code 1号线||Line 1}：取第一段。 */
    public static String shortName(final String raw) {
        if (raw == null) {
            return "";
        }
        final int cut = raw.indexOf("||");
        final String head = cut >= 0 ? raw.substring(0, cut) : raw;
        return head.trim();
    }

    /**
     * 线路号：线路名里第一串数字（{@code 1号线} → {@code 1}，{@code Line 12} → {@code 12}）。
     *
     * <p>线路名里没有数字时退回 MTR 的 {@code lightRailRouteNumber}（{@code K3} 这类轻轨编号），
     * 再没有就返回空串 —— 那时左侧色块不画数字，只留色块。
     */
    public static String lineNumber(final Route route, final String routeName) {
        final String digits = firstDigits(shortName(routeName));
        if (!digits.isEmpty()) {
            return digits;
        }
        if (route != null && route.lightRailRouteNumber != null) {
            final String trimmed = route.lightRailRouteNumber.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return digits;
    }

    private static String firstDigits(final String text) {
        if (text == null) {
            return "";
        }
        int start = -1;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            final boolean digit = cp >= '0' && cp <= '9';
            if (digit && start < 0) {
                start = i;
            } else if (!digit && start >= 0) {
                return text.substring(start, i);
            }
            i += Character.charCount(cp);
        }
        return start >= 0 ? text.substring(start) : "";
    }

    /** 这个站台上属于 {@code routeName} 的那条线路（找不到就 null —— 颜色仍能画）。 */
    public static Route findRoute(final long platformId, final String routeName) {
        final String wanted = shortName(routeName);
        try {
            for (final Route route : ClientData.ROUTES) {
                if (route == null || route.isHidden || route.name == null) {
                    continue;
                }
                if (!wanted.equals(shortName(route.name))) {
                    continue;
                }
                if (route.containsPlatformId(platformId)) {
                    return route;
                }
            }
        } catch (Throwable ignored) {
            // 数据还没同步 / 被别的模组换掉：当作没有，用 fallback 色。
        }
        return null;
    }

    /** 线路色（见类注释的优先级）。返回 {@code 0xRRGGBB}。 */
    public static int lineColor(final HeadlightConfig cfg, final Route route, final String routeName) {
        final Integer override = overrideColor(cfg, route, routeName);
        if (override != null) {
            return override.intValue();
        }
        if (route != null && (route.color & 0xFFFFFF) != 0) {
            return route.color & 0xFFFFFF;
        }
        return FALLBACK_RGB;
    }

    /** {@code lineColors} 里这条线路的手工覆盖，没有就 null。 */
    public static Integer overrideColor(final HeadlightConfig cfg, final Route route, final String routeName) {
        if (cfg == null || cfg.lineColors == null || cfg.lineColors.isEmpty()) {
            return null;
        }
        final String name = shortName(routeName);
        final String number = lineNumber(route, routeName);
        if (route != null) {
            final Integer byId = lookup(cfg, String.valueOf(route.id));
            if (byId != null) {
                return byId;
            }
        }
        final Integer byName = lookup(cfg, name);
        if (byName != null) {
            return byName;
        }
        if (!number.isEmpty()) {
            final Integer byNumber = lookup(cfg, number);
            if (byNumber != null) {
                return byNumber;
            }
            // “1号线” 这种键也能命中线路号 “1” 的查询（反之亦然）。
            final Integer bySuffix = lookup(cfg, number + "号线");
            if (bySuffix != null) {
                return bySuffix;
            }
        }
        return null;
    }

    private static Integer lookup(final HeadlightConfig cfg, final String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        final String value = cfg.lineColors.get(key);
        if (value == null) {
            return null;
        }
        final int rgb = HeadlightConfig.parseColor(value, -1);
        return rgb >= 0 ? Integer.valueOf(rgb) : null;
    }

    /** 亮色（透亮度 &gt; 128）=&gt; 上面该写黑字。同 LCD 的 {@code isLightColor}。 */
    public static boolean isLight(final int rgb) {
        return XiamenStripPainter.isLight(rgb);
    }

    /** 画在线路色底上的文字颜色（{@code 0xFF000000} / {@code 0xFFFFFFFF}）。 */
    public static int contrastText(final int rgb) {
        return XiamenStripPainter.contrastText(rgb);
    }

    /**
     * 画在<b>白底</b>上的文字颜色：线路色够深就用线路色（条带看起来“跟线走”），
     * 太浅（黄/浅绿）就退回近黑，保证在白底上读得清。
     */
    public static int onWhiteText(final int rgb) {
        return XiamenStripPainter.onWhiteText(rgb);
    }

    /** 当前站站名（白底、加粗）：深色线路用加深后的线路色，浅色线路用近黑。 */
    public static int currentNameText(final int rgb) {
        return XiamenStripPainter.currentNameText(rgb);
    }

    /** 线路色加深一点（当前站名/终点名用，避免亮色线路在白底上发飘）。 */
    public static int darken(final int rgb, final double factor) {
        return XiamenStripPainter.darken(rgb, factor);
    }

    /**
     * 门灯颜色：先查 {@link HeadlightConfig#doorLightByLine}（键 = 线路号，{@code 4} / {@code 4号线}），
     * 再退回 {@link HeadlightConfig#doorLightColor}。
     */
    public static String doorLightColor(final HeadlightConfig cfg, final String lineNumber) {
        if (cfg == null) {
            return HeadlightConfig.DOOR_LIGHT_ORANGE;
        }
        if (lineNumber != null && cfg.doorLightByLine != null && !cfg.doorLightByLine.isEmpty()) {
            final String key = lineNumber.trim();
            String value = cfg.doorLightByLine.get(key);
            if (value == null && !key.isEmpty()) {
                value = cfg.doorLightByLine.get(key + "号线");
            }
            if (value != null) {
                return HeadlightConfig.normalizeDoorLightColor(value);
            }
            final String lower = key.toLowerCase(Locale.ROOT);
            if (cfg.doorLightByLine.containsKey(lower)) {
                return HeadlightConfig.normalizeDoorLightColor(cfg.doorLightByLine.get(lower));
            }
        }
        return HeadlightConfig.normalizeDoorLightColor(cfg.doorLightColor);
    }

    /** {@code 0xRRGGBB} 的 {@link Color}（AWT 用）。 */
    public static Color awt(final int rgb) {
        return new Color(rgb & 0xFFFFFF);
    }
}
