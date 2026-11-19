// 字体：资源包里的字体优先，读不到退回系统字体。纯 AWT，不 import 任何 MC 类 ——
// 渲染器和离线预览因此拿到的是同一套字体决策。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 字体解析：{@link #fromBytes(byte[])} 给资源包（OTF/TTF）用，{@link #systemCjk()} 等给兜底用。 */
public final class PsdLcdTypeface {

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final Map<String, Font> CACHE = new HashMap<>();
    private static Font cachedCjk;
    private static Font cachedLatin;
    private static Font cachedLight;

    private PsdLcdTypeface() {
    }

    /** 从字节读一个字体（OTF/TTF 都走 TrueType 通道）。失败返回 null，不抛。 */
    public static Font fromBytes(final byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            final Font font = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(data));
            return font.canDisplay('厦') || font.canDisplay('A') ? font : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从磁盘读一个字体文件（离线预览用）。 */
    public static Font fromFile(final File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return fromBytes(java.nio.file.Files.readAllBytes(file.toPath()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 中文字体：黑体类（实拍是较粗的黑体），退回任意能显示「厦 / 线」的字体。 */
    public static Font systemCjk() {
        if (cachedCjk != null) {
            return cachedCjk;
        }
        Font best = null;
        int bestScore = Integer.MAX_VALUE;
        try {
            for (final Font f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
                if (!f.canDisplay('厦') || !f.canDisplay('线')) {
                    continue;
                }
                final String key = (f.getPSName() + "|" + f.getFontName(Locale.ENGLISH) + "|"
                        + f.getFamily(Locale.ENGLISH)).toLowerCase(Locale.ROOT);
                int score;
                if (key.contains("misans")) {
                    score = 0;
                } else if (key.contains("simhei")) {
                    score = 1;
                } else if (key.contains("yahei")) {
                    score = key.contains("light") ? 6 : 2;
                } else if (key.contains("source han sans") || key.contains("noto sans")) {
                    score = 4;
                } else if (key.contains("simsun") || key.contains("dengxian")) {
                    score = 5;
                } else {
                    score = 8;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
        } catch (Throwable ignored) {
            best = null;
        }
        cachedCjk = best != null ? best.deriveFont(Font.PLAIN, 100.0F)
                : new Font(Font.SANS_SERIF, Font.BOLD, 100);
        return cachedCjk;
    }

    /** 拉丁 / 数字（常规字重）：Segoe UI 一类的现代无衬线，退回逻辑无衬线。 */
    public static Font systemLatin() {
        if (cachedLatin != null) {
            return cachedLatin;
        }
        cachedLatin = pick(new String[]{"Roboto", "Segoe UI", "Helvetica Neue", "Arial"}, Font.PLAIN);
        return cachedLatin;
    }

    /**
     * 大数字用的<b>细</b>字重：实拍里 7 / 17 的笔画只有字高的 6~7%，
     * 常规字重偏粗，所以优先挑名字里带 Light / Semilight 的家族（Semilight 最接近实拍）。
     */
    public static Font systemLight() {
        if (cachedLight != null) {
            return cachedLight;
        }
        Font semilight = null;
        Font light = null;
        try {
            for (final Font f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
                final String key = (f.getPSName() + "|" + f.getFontName(Locale.ENGLISH) + "|"
                        + f.getFamily(Locale.ENGLISH)).toLowerCase(Locale.ROOT);
                if (!key.contains("light") || !f.canDisplay('7')) {
                    continue;
                }
                if (!key.contains("segoe") && !key.contains("yahei") && !key.contains("dengxian")
                        && !key.contains("roboto") && !key.contains("noto") && !key.contains("source")) {
                    continue;
                }
                if (key.contains("semilight")) {
                    if (semilight == null || (key.contains("segoe") && !semilight.getPSName()
                            .toLowerCase(Locale.ROOT).contains("segoe"))) {
                        semilight = f;
                    }
                } else if (light == null) {
                    light = f;
                }
            }
        } catch (Throwable ignored) {
            semilight = null;
        }
        final Font chosen = semilight != null ? semilight : light;
        cachedLight = chosen != null ? chosen.deriveFont(Font.PLAIN, 100.0F) : systemLatin();
        return cachedLight;
    }

    private static Font pick(final String[] families, final int style) {
        try {
            final java.util.List<String> available = java.util.Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (final String family : families) {
                if (available.contains(family)) {
                    return new Font(family, style, 100);
                }
            }
        } catch (Throwable ignored) {
            // headless / 字体表拿不到：用逻辑字体。
        }
        return new Font(Font.SANS_SERIF, style, 100);
    }

    /** 字体名（日志 / 预览输出用）。 */
    public static String name(final Font font) {
        return font == null ? "-" : font.getPSName() + "@" + Math.round(font.getSize2D());
    }
}
