// 离线预览：把 PsdLcdLayout.plan() 的同一张指令表交给 Graphics2D 画成 PNG。
// 只依赖 src/main/java/.../PsdLcdLayout.java（那一份文件不 import 任何 Minecraft 类），
// 所以预览图和游戏里看到的排版是同一份代码出来的，不会各画各的。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/** {@code tools/preview-psd-lcd.js} 调的入口：{@code args[0] = 输出的 png}，{@code args[1] = 段数}。 */
public final class PsdLcdPreview {

    /** 字体：优先微软雅黑（和实拍上的思源黑体最接近），没有就退回对话框字体。 */
    private static final String[] FONT_CANDIDATES = {
            "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC",
            "SimHei", "Dialog",
    };

    private static String fontFamily = "Dialog";

    private PsdLcdPreview() {
    }

    public static void main(final String[] args) throws Exception {
        final File out = new File(args.length > 0 ? args[0] : "temp/psd-lcd-preview.png");
        final int segments = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        fontFamily = pickFont();

        final int w = (int) PsdLcdLayout.widthPx(segments);
        final int h = (int) PsdLcdLayout.HEIGHT_PX;
        final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setComposite(AlphaComposite.Src);

        final List<PsdLcdLayout.Op> ops = PsdLcdLayout.plan(sample(), segments);
        for (final PsdLcdLayout.Op op : ops) {
            draw(g, op);
        }
        g.dispose();

        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        ImageIO.write(image, "PNG", out);
        System.out.println("wrote " + out.getCanonicalPath() + "  (" + w + "x" + h + ", segments="
                + segments + ", font=" + fontFamily + ")");
    }

    // ------------------------------------------------------------------ 画

    private static void draw(final Graphics2D g, final PsdLcdLayout.Op op) {
        if (op instanceof PsdLcdLayout.Fill fill) {
            g.setColor(color(fill.color()));
            g.fill(new java.awt.geom.Rectangle2D.Float(fill.x0(), fill.y0(),
                    fill.x1() - fill.x0(), fill.y1() - fill.y0()));
        } else if (op instanceof PsdLcdLayout.Quad q) {
            final Path2D.Float path = new Path2D.Float();
            path.moveTo(q.x0(), q.y0());
            path.lineTo(q.x1(), q.y1());
            path.lineTo(q.x2(), q.y2());
            path.lineTo(q.x3(), q.y3());
            path.closePath();
            g.setColor(color(q.color()));
            g.fill(path);
        } else if (op instanceof PsdLcdLayout.RoundFill r) {
            g.setColor(color(r.color()));
            g.fill(new RoundRectangle2D.Float(r.x0(), r.y0(), r.x1() - r.x0(), r.y1() - r.y0(),
                    r.radius() * 2.0F, r.radius() * 2.0F));
        } else if (op instanceof PsdLcdLayout.RoundStroke s) {
            final float t = s.thickness();
            g.setColor(color(s.color()));
            g.setStroke(new BasicStroke(t, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Float(s.x0() + t * 0.5F, s.y0() + t * 0.5F,
                    (s.x1() - s.x0()) - t, (s.y1() - s.y0()) - t,
                    s.radius() * 2.0F, s.radius() * 2.0F));
        } else if (op instanceof PsdLcdLayout.Disc d) {
            g.setColor(color(d.color()));
            g.fill(new java.awt.geom.Ellipse2D.Float(d.cx() - d.radius(), d.cy() - d.radius(),
                    d.radius() * 2.0F, d.radius() * 2.0F));
        } else if (op instanceof PsdLcdLayout.Text text) {
            drawText(g, text);
        } else if (op instanceof PsdLcdLayout.AdSlot slot) {
            // 预览永远走「没有广告图」那一支（plan 会换成色块 + 文字），这里只是兜底。
            g.setColor(color(PsdLcdLayout.COLOR_AD_BG));
            g.fillRect((int) slot.x0(), (int) slot.y0(),
                    (int) (slot.x1() - slot.x0()), (int) (slot.y1() - slot.y0()));
        } else if (op instanceof PsdLcdLayout.Ticker ticker) {
            drawTicker(g, ticker);
        }
    }

    private static void drawText(final Graphics2D g, final PsdLcdLayout.Text text) {
        if (text.text() == null || text.text().isEmpty()) {
            return;
        }
        float size = Math.max(1.0F, text.size());
        Font font = new Font(fontFamily, Font.PLAIN, Math.max(1, Math.round(size)));
        float width = width(g, font, text.text(), size);
        if (text.maxWidth() > 0.0F && width > text.maxWidth()) {
            final float shrink = text.maxWidth() / width;
            size *= shrink;
            font = new Font(fontFamily, Font.PLAIN, Math.max(1, Math.round(size)));
            width = width(g, font, text.text(), size);
        }
        final float x = switch (text.align()) {
            case CENTER -> text.x() - width * 0.5F;
            case RIGHT -> text.x() - width;
            default -> text.x();
        };
        g.setFont(font);
        g.setColor(color(text.color()));
        // 基线：MC 里 yTop 是字形上沿、字形高 = size；AWT 用 ascent 对齐，
        // 再乘 0.95 让 CJK 的字身正好落在 [yTop, yTop + size] 里。
        final FontMetrics metrics = g.getFontMetrics(font);
        g.drawString(text.text(), x, text.yTop() + metrics.getAscent() * 0.95F);
    }

    /** 标语条：真实屏幕上它在滚，静态预览就画成「刚滚进来」的样子（左对齐 + 条内裁切）。 */
    private static void drawTicker(final Graphics2D g, final PsdLcdLayout.Ticker ticker) {
        final String sample = ticker.sample() == null || ticker.sample().isEmpty()
                ? "此处滚动标语" : ticker.sample();
        final float sizePx = (ticker.y1() - ticker.y0()) * ticker.sizeRatio();
        final Font font = new Font(fontFamily, Font.PLAIN, Math.max(1, Math.round(sizePx)));
        g.setFont(font);
        g.setColor(color(PsdLcdLayout.COLOR_WHITE));
        final Shape old = g.getClip();
        g.clip(new java.awt.geom.Rectangle2D.Float(ticker.x0() + 5.0F, ticker.y0(),
                (ticker.x1() - ticker.x0()) - 10.0F, ticker.y1() - ticker.y0()));
        final float baseline = (ticker.y0() + ticker.y1()) * 0.5F + sizePx * 0.36F;
        g.drawString(sample, ticker.x0() + 6.0F, baseline);
        g.setClip(old);
    }

    private static float width(final Graphics2D g, final Font font, final String text, final float size) {
        final FontMetrics fm = g.getFontMetrics(font);
        return fm.stringWidth(text);
    }

    private static Color color(final int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    private static String pickFont() {
        try {
            final List<String> available = Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (final String candidate : FONT_CANDIDATES) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // headless 环境拿不到字体表：退回 Dialog。
        }
        return "Dialog";
    }

    // ------------------------------------------------------------------ 示例内容

    /** 与实拍（{@code 扫描全能王 2026-11-19 19.07.jpg}）一致的示例数据。 */
    private static PsdLcdLayout.Content sample() {
        return new PsdLcdLayout.Content(
                "2026-09-23", "星期三", "Wednesday", "11:10",
                "开往", "Bound for", "华侨大学", "Huaqiao University", "7",
                "下一趟开往", "Next train", "华侨大学", "Huaqiao University", "17",
                "分钟", "min",
                "6号线", "Line 6", 0xFFC21FE0,
                "董任", "Dongren", "当前站 / This station",
                4, List.of("1车", "2车", "3车", "4车"),
                "此处放置广告",
                "草坪，让月光替你唱。  XM6—厦门电视台移动电视  【树洞投稿方式】搜索“厦门树洞”小程序，点击加号参与树",
                false);
    }
}
