import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import dev.js.mtrheadlight.xiamen.pids.PidsXiamenLayout;
import dev.js.mtrheadlight.xiamen.pids.PidsXiamenLayout.BrtRow;
import dev.js.mtrheadlight.xiamen.pids.PidsXiamenLayout.Clock;
import dev.js.mtrheadlight.xiamen.pids.PidsXiamenLayout.HAlign;
import dev.js.mtrheadlight.xiamen.pids.PidsXiamenLayout.Rect;
import dev.js.mtrheadlight.xiamen.pids.PidsXiamenLayout.Row;
import dev.js.mtrheadlight.xiamen.pids.PidsXiamenLayout.TextStyle;

/**
 * 布局预览：拿 PidsXiamenLayout 里的真常量，在底图上把字摆出来（用 AWT 近似 MC 字体宽度），
 * 用来核对「框内不叠字 / 不压边 / 位置和参考图一致」。
 *
 * <p>MC 位图字体的步进近似：ASCII ≈ 0.75 × 字高，CJK ≈ 1.0 × 字高。
 */
public final class LayoutPreview {

    private static final float FONT_UNIT_PX = PidsXiamenLayout.FONT_UNIT_PX;

    public static void main(String[] args) throws Exception {
        final String texDir = args[0];
        final String outDir = args[1];
        metro(texDir + "/pids_xiamen_metro.png", outDir + "/preview_metro.png", false);
        brt(texDir + "/pids_xiamen_brt.png", outDir + "/preview_brt.png", false);
        // 压力测试：长站名 / 长英文 / 很长的标语（检查自动缩放与逐字裁切）
        metro(texDir + "/pids_xiamen_metro.png", outDir + "/preview_metro_long.png", true);
        brt(texDir + "/pids_xiamen_brt.png", outDir + "/preview_brt_long.png", true);
        System.out.println("ok");
    }

    private static boolean LONG;

    // ---------------------------------------------------------------- 地铁

    private static void metro(String base, String out, boolean longText) throws Exception {
        LONG = longText;
        final BufferedImage image = ImageIO.read(new File(base));
        final Graphics2D g = graphics(image);
        final PidsXiamenLayout.Metro spec = PidsXiamenLayout.METRO;

        clock(g, spec.clock(), "2026-11-12", "18:42");

        final String[][] labels = {
                {"开往", "Bound for"}, {"下一班开往", "Next train"}, {"第三班开往", "Third train"}
        };
        final String[] dest = LONG
                ? new String[]{"厦门北站客运中心换乘枢纽", "Xiamen North Railway Station Interchange"}
                : new String[]{"滨港科创园", "Bingang Tech Park"};
        final String[] nums = {"3", "分钟"};
        for (int i = 0; i < spec.rows().length; i++) {
            final Row row = spec.rows()[i];
            final Rect card = row.card();
            flow(g, card, row.padRatio(), row.gapRatio(),
                    line(row.label(), card, labels[i][0]),
                    line(row.labelSub(), card, labels[i][1]));
            flow(g, card, row.padRatio(), row.gapRatio(),
                    line(row.dest(), card, dest[0]),
                    line(row.destSub(), card, dest[1]),
                    run(card, row.count(), row.unit(), nums[0], nums[1]));
        }

        placeholder(g, spec.ad(), "广告（可拉伸）");
        ticker(g, spec.ticker(), spec.tickerText(), LONG ? "欢迎乘坐厦门地铁，请勿倚靠车门，上下车时请注意脚下安全，照顾好老人小孩" : "欢迎乘坐厦门地铁，请勿倚靠车门");
        ImageIO.write(image, "png", new File(out));
    }

    // ---------------------------------------------------------------- BRT

    private static void brt(String base, String out, boolean longText) throws Exception {
        LONG = longText;
        final BufferedImage image = ImageIO.read(new File(base));
        final Graphics2D g = graphics(image);
        final PidsXiamenLayout.Brt spec = PidsXiamenLayout.BRT;

        clock(g, spec.clock(), "11月12日星期三", "18:42");

        for (int i = 0; i < spec.rows().length; i++) {
            final BrtRow row = spec.rows()[i];
            final Rect card = row.card();
            flow(g, card, row.padRatio(), row.gapRatio(),
                    line(row.dest(), card, LONG ? "开往厦门北站客运中心换乘枢纽站" : "开往滨港科创园站"),
                    brtRun(card, row, "1号线", "约", "3", "分钟"));
        }

        placeholder(g, spec.ad(), "广告图（可拉伸）");
        ticker(g, spec.ticker(), spec.tickerText(), LONG ? "请站在黄线以内候车，先下后上，车门关闭时请勿强行上下车" : "请站在黄线以内候车，先下后上");
        ImageIO.write(image, "png", new File(out));
    }

    // ---------------------------------------------------------------- 排版（与渲染器同一套算法）

    private record Seg(String text, float size, Color color) {
    }

    private static final Color WHITE = Color.WHITE;
    /** 预览里用的「线路色」（真机上取 MTR 的 route.color）。 */
    private static final Color ROUTE = new Color(0x00, 0x98, 0x46);

    private record Line(Seg[] segs, float height, float x, HAlign align, float maxWidth) {
    }

    private static Line line(TextStyle style, Rect card, String text) {
        final float size = Math.max(3.0F, card.h() * style.sizeRatio());
        return new Line(new Seg[]{new Seg(text, size, WHITE)}, size, card.x(style.xRatio()), style.align(),
                card.w() * style.maxWidthRatio());
    }

    private static Line run(Rect card, TextStyle count, TextStyle unit, String number, String unitText) {
        final float countSize = Math.max(3.0F, card.h() * count.sizeRatio());
        final float unitSize = Math.max(3.0F, card.h() * unit.sizeRatio());
        return new Line(new Seg[]{new Seg(number, countSize, WHITE), new Seg(" " + unitText, unitSize, WHITE)},
                Math.max(countSize, unitSize), card.x(count.xRatio()), HAlign.LEFT, card.w() * 0.55F);
    }

    private static Line brtRun(Rect card, BrtRow row, String route, String about, String number, String unitText) {
        final float routeSize = Math.max(3.0F, card.h() * row.route().sizeRatio());
        final float aboutSize = Math.max(3.0F, card.h() * row.about().sizeRatio());
        final float countSize = Math.max(3.0F, card.h() * row.count().sizeRatio());
        final float unitSize = Math.max(3.0F, card.h() * row.unit().sizeRatio());
        return new Line(new Seg[]{
                // 线路号不带引号，用线路色
                new Seg(route, routeSize, ROUTE),
                new Seg(" " + about, aboutSize, WHITE),
                new Seg(" " + number, countSize, WHITE),
                new Seg(" " + unitText, unitSize, WHITE)
        }, Math.max(countSize, unitSize), card.x(row.route().xRatio()), HAlign.LEFT, card.w() * 0.88F);
    }

    private static void flow(Graphics2D g, Rect card, float padRatio, float gapRatio, Line... lines) {
        final float gap = card.h() * gapRatio;
        float total = gap * (lines.length - 1);
        for (final Line line : lines) {
            total += line.height();
        }
        float y = card.y0() + (card.h() - total) * 0.5F;
        y = Math.max(y, card.y0() + card.h() * padRatio);
        for (final Line line : lines) {
            drawRun(g, line, y);
            y += line.height() + gap;
        }
    }

    private static void drawRun(Graphics2D g, Line line, float boxTop) {
        float total = 0;
        for (final Seg seg : line.segs()) {
            total += width(seg);
        }
        float shrink = 1.0F;
        if (line.maxWidth() > 0 && total > line.maxWidth()) {
            shrink = line.maxWidth() / total;
        }
        total *= shrink;
        float x = line.x() + switch (line.align()) {
            case LEFT -> 0;
            case CENTER -> -total * 0.5F;
            case RIGHT -> -total;
        };
        for (final Seg seg : line.segs()) {
            final float size = seg.size() * shrink;
            text(g, seg.text(), x, boxTop + line.height() - size, size, seg.color());
            x += width(seg) * shrink;
        }
    }

    private static void text(Graphics2D g, String text, float x, float yTop, float size, Color color) {
        g.setColor(color);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, Math.round(size * 1.0F)));
        final FontMetrics fm = g.getFontMetrics();
        // MC 的字形盒顶到盒底 = size；AWT 的 ascent/descent 不一样，这里按 baseline 对齐
        g.drawString(text, x, yTop + size * 0.82F);
    }

    /** MC 字体步进近似：ASCII 0.75×字高，CJK 1.0×字高。 */
    private static float width(Seg seg) {
        float w = 0;
        for (int i = 0; i < seg.text().length(); i++) {
            final char c = seg.text().charAt(i);
            w += (c < 0x2E80 ? 0.75F : 1.0F) * seg.size();
        }
        return w;
    }

    // ---------------------------------------------------------------- 其它块

    private static void clock(Graphics2D g, Clock clock, String date, String time) {
        flow(g, clock.card(), clock.padRatio(), clock.gapRatio(),
                line(clock.date(), clock.card(), date),
                line(clock.time(), clock.card(), time));
    }

    private static void placeholder(Graphics2D g, Rect rect, String label) {
        g.setColor(new Color(255, 255, 255, 40));
        g.fillRect(Math.round(rect.x0()), Math.round(rect.y0()),
                Math.round(rect.w()), Math.round(rect.h()));
        g.setColor(new Color(255, 255, 255, 90));
        g.drawRect(Math.round(rect.x0()), Math.round(rect.y0()),
                Math.round(rect.w()), Math.round(rect.h()));
        text(g, label, rect.cx() - label.length() * 8.0F * 0.5F, rect.cy() - 8.0F, 16, new Color(255, 255, 255, 120));
    }

    private static void ticker(Graphics2D g, Rect bar, TextStyle style, String message) {
        final float size = bar.h() * style.sizeRatio();
        final float innerLeft = bar.x0() + PidsXiamenLayout.TICKER_INSET;
        final float innerRight = bar.x1() - PidsXiamenLayout.TICKER_INSET;
        // 不缩字（与 PidsXiamenTicker 一致）：长句保持原字号，只在标语条边缘被裁掉。
        // 取一个「滚到一半」的相位，检查是否在框内被裁掉
        final float x = innerLeft + 12;
        g.setClip(Math.round(innerLeft), Math.round(bar.y0()), Math.round(innerRight - innerLeft),
                Math.round(bar.h()));
        text(g, message, x, bar.cy() - size * 0.5F, size, Color.WHITE);
        g.setClip(null);
    }

    private static Graphics2D graphics(BufferedImage image) {
        final Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }
}
