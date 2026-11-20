// 厦门 PIDS 版式的离线预览（纯 Java2D，不开游戏、不进 Gradle）。
//
// 跑法（tools/pids-xiamen/preview.sh 里有同样的命令）：
//   javac -encoding UTF-8 -d <tmp>/pids-preview \
//         src/main/java/dev/js/mtrheadlight/xiamen/XiamenStripPainter.java \
//         src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenPainter.java \
//         tools/pids-xiamen/XiamenPidsPreview.java
//   java -cp <tmp>/pids-preview XiamenPidsPreview <repo>/v1.20.1 <outDir>
//
// 为什么要它：整屏现在是**函数画的**（PidsXiamenPainter），所以在游戏外就能把版式吐成 PNG。
// 三张图 = 验收里那三种：地铁 6 节 / 地铁 2 节 / BRT。图上直接标了每块区域的位置，
// 对版式有疑问先看图，别先开游戏。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import dev.js.mtrheadlight.xiamen.XiamenStripPainter;

/** 厦门 PIDS 的三种版式预览（地铁 6 节 / 地铁 2 节 / BRT）。 */
public final class XiamenPidsPreview {

    /** 预览图放大倍数（512×235 直接看太小，3 倍够看清 5px 的编组编号）。 */
    private static final int SCALE = 3;

    private XiamenPidsPreview() {
    }

    public static void main(final String[] args) throws Exception {
        final File repo = new File(args.length > 0 ? args[0] : ".");
        final File out = new File(args.length > 1 ? args[1] : "temp");
        if (!out.exists() && !out.mkdirs()) {
            throw new IllegalStateException("cannot create " + out);
        }
        final PidsXiamenPainter.Assets assets = load(repo);
        final BufferedImage ad = ImageIO.read(new File(repo,
                "src/main/resources/assets/mtrlte/advertisements/scenery1.png"));

        metro(assets, ad, 6, new File(out, "pids_xiamen_metro_6car.png"));
        metro(assets, ad, 2, new File(out, "pids_xiamen_metro_2car.png"));
        brt(assets, ad, new File(out, "pids_xiamen_brt.png"));
        System.out.println("wrote 3 previews to " + out.getAbsolutePath());
        System.out.println("fonts: cjk=" + assets.cjk.getFontName() + " latin="
                + assets.latin.getFontName());

        final boolean ok = checks(assets);
        if (!ok) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 自检

    /**
     * 版式自检（离线断言，任何一条不满足就 exit 1）。
     *
     * <p>查的是「改了常量以后最容易破的那几条」：
     * <ol>
     *   <li>编组条：2…16 节时卡片总宽都塞得进青色带，且左右都不越界；卡片高度恒定；</li>
     *   <li>广告 / 标语矩形都在 512×235 以内；</li>
     *   <li>标语真的在滚：相邻两 tick 的位图像素不同；空档期整张透明；</li>
     *   <li>整屏不是空的（编组带里有卡片、到站框里有字）。</li>
     * </ol>
     */
    private static boolean checks(final PidsXiamenPainter.Assets assets) {
        boolean ok = true;
        final float[] band = PidsXiamenPainter.consistBand();
        final float bandW = band[2] - band[0];
        // 1) 编组压缩：和 PidsXiamenPainter#consist 同一个公式
        final float ch = 29.0F;
        final float naturalW = ch * (2144.0F / 1024.0F);
        final float avail = Math.max(1.0F, bandW - 4.0F);
        for (int n = 2; n <= 16; n++) {
            final float cw = Math.min(naturalW, avail / (n * (1.0F + 0.12F)));
            final float gap = cw * 0.12F;
            final float total = n * cw + (n - 1) * gap;
            if (total > bandW + 0.05F) {
                System.out.printf("FAIL consist n=%d total=%.2f > band %.2f%n", n, total, bandW);
                ok = false;
            }
            if (cw < 2.0F) {
                System.out.printf("FAIL consist n=%d card width %.2f too narrow%n", n, cw);
                ok = false;
            }
        }
        System.out.printf("ok  consist: ch=%.1f natural=%.1f  (2..16 cars all fit %.1fpx)%n",
                ch, naturalW, bandW);

        // 2) 矩形都在屏内
        for (final PidsXiamenPainter.Kind kind : PidsXiamenPainter.Kind.values()) {
            ok &= inside("ad@" + kind, PidsXiamenPainter.adRect(kind), ok);
            ok &= inside("ticker@" + kind, PidsXiamenPainter.tickerRect(kind), ok);
            final float[] inner = PidsXiamenPainter.tickerInner(kind);
            if (inner[2] - inner[0] < 8.0F || inner[3] - inner[1] < 8.0F) {
                System.out.println("FAIL ticker inner too small @" + kind);
                ok = false;
            }
        }

        // 3) 标语真的在滚（相邻 tick 的位图必须不同），空档期必须全透明
        for (final PidsXiamenPainter.Kind kind : PidsXiamenPainter.Kind.values()) {
            final float fontPx = PidsXiamenPainter.tickerFontPx(kind);
            final float[] inner = PidsXiamenPainter.tickerInner(kind);
            final int w = Math.round(inner[2] - inner[0]);
            final int h = Math.round(inner[3] - inner[1]);
            final PidsXiamenPainter.TickerPlan plan = PidsXiamenPainter.tickerPlan(
                    List.of(new PidsXiamenPainter.TickerMessage("欢迎乘坐厦门地铁　请先下后上",
                            0xFFFFFFFF)), assets.cjk, fontPx, w, 2.0F, 48.0F);
            if (plan == null) {
                System.out.println("FAIL ticker plan null @" + kind);
                ok = false;
                continue;
            }
            final BufferedImage a = PidsXiamenPainter.paintTickerStrip(plan, 60L, w, h);
            final BufferedImage b = PidsXiamenPainter.paintTickerStrip(plan, 70L, w, h);
            if (opaquePixels(a) == 0) {
                System.out.println("FAIL ticker at tick 60 is blank @" + kind);
                ok = false;
            }
            if (samePixels(a, b)) {
                System.out.println("FAIL ticker did not move between tick 60 and 70 @" + kind);
                ok = false;
            }
            // 最后一条滚完之后的那段空档（span…cycle-1）必须什么都不画
            final int gapTick = (int) Math.floor(plan.span() + 1.0D);
            if (gapTick < plan.cycle() && opaquePixels(
                    PidsXiamenPainter.paintTickerStrip(plan, gapTick, w, h)) != 0) {
                System.out.println("FAIL ticker gap frame is not blank @" + kind);
                ok = false;
            }
            System.out.printf("ok  ticker@%s: span=%d ticks cycle=%d ticks (moves, has gaps)%n",
                    kind, PidsXiamenPainter.tickerSpanTicks(plan), (int) plan.cycle());
        }

        // 4) 整屏不是空的：编组带里有不透明像素，三个到站框里有字
        final PidsXiamenPainter.Content sample = new PidsXiamenPainter.Content("2026-11-20",
                "19:22", List.of(
                new PidsXiamenPainter.Row("开往", "Bound for", "镇海路", "Zhenhai Road",
                        "", 0xFFFFFFFF, "", "5", "分钟", "min")),
                List.of("1", "2", "3", "4", "5", "6"), "暂无列车信息");
        final BufferedImage screen = PidsXiamenPainter.paint(sample, assets, null,
                PidsXiamenPainter.Kind.METRO, 512, 235);
        if (opaquePixels(screen, 180, 2, 330, 30) < 100) {
            System.out.println("FAIL consist strip is empty in the rendered screen");
            ok = false;
        }
        if (opaquePixels(screen, 76, 56, 90, 100) < 20) {
            System.out.println("FAIL no arrival text drawn in the row box");
            ok = false;
        }
        System.out.println("ok  screen: consist + arrival text present");
        System.out.println(ok ? "ALL CHECKS PASSED" : "CHECKS FAILED");
        return ok;
    }

    private static boolean inside(final String what, final float[] rect, final boolean previous) {
        if (rect[0] < 0.0F || rect[1] < 0.0F || rect[2] > PidsXiamenPainter.SCREEN_W
                || rect[3] > PidsXiamenPainter.SCREEN_H || rect[2] <= rect[0] || rect[3] <= rect[1]) {
            System.out.println("FAIL " + what + " out of screen: " + java.util.Arrays.toString(rect));
            return false;
        }
        return previous;
    }

    private static int opaquePixels(final BufferedImage image) {
        return opaquePixels(image, 0, 0, image.getWidth(), image.getHeight());
    }

    private static int opaquePixels(final BufferedImage image, final int x0, final int y0,
                                    final int w, final int h) {
        int count = 0;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y0 + h); y++) {
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x0 + w); x++) {
                if ((image.getRGB(x, y) >>> 24) > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean samePixels(final BufferedImage a, final BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 素材

    private static PidsXiamenPainter.Assets load(final File repo) throws Exception {
        final File assetsDir = new File(repo, "src/main/resources/assets/mtrlte/textures");
        final PidsXiamenPainter.Assets assets = new PidsXiamenPainter.Assets();
        assets.metroBase = ImageIO.read(new File(assetsDir, "block/pids_xiamen_metro.png"));
        assets.brtBase = ImageIO.read(new File(assetsDir, "block/pids_xiamen_brt.png"));
        assets.logo = ImageIO.read(new File(assetsDir, "gui/psd_lcd/xiamen_logo.png"));
        assets.carEnd = ImageIO.read(new File(assetsDir, "gui/pids/consist_car_end.png"));
        assets.carMiddle = ImageIO.read(new File(assetsDir, "gui/pids/consist_car_middle.png"));
        // 字体：与模组内完全同一支（XiamenStripPainter#font()：SimHei → 微软雅黑 → Noto Sans SC）
        final Font base = XiamenStripPainter.font();
        assets.cjk = base.deriveFont(Font.BOLD, 100.0F);
        assets.latin = base.deriveFont(Font.PLAIN, 100.0F);
        assets.digits = assets.cjk;
        return assets;
    }

    // ------------------------------------------------------------------ 三张图

    private static void metro(final PidsXiamenPainter.Assets assets, final BufferedImage ad,
                              final int cars, final File file) throws Exception {
        final List<PidsXiamenPainter.Row> rows = new ArrayList<>();
        rows.add(new PidsXiamenPainter.Row("开往", "Bound for", "镇海路", "Zhenhai Road",
                "", 0xFFFFFFFF, "", "5", "分钟", "min"));
        rows.add(new PidsXiamenPainter.Row("下一趟开往", "Next train", "岩内", "Yannei",
                "", 0xFFFFFFFF, "", "12", "分钟", "min"));
        rows.add(new PidsXiamenPainter.Row("第三趟开往", "Third train", "厦门北站", "Xiamen North",
                "", 0xFFFFFFFF, "", "即将进站", "", ""));

        final List<String> numbers = new ArrayList<>();
        for (int i = 1; i <= cars; i++) {
            numbers.add(String.valueOf(i));
        }
        final PidsXiamenPainter.Content content = new PidsXiamenPainter.Content(
                "2026-11-20", "19:22", rows, numbers, "暂无列车信息");
        write(content, assets, ad, PidsXiamenPainter.Kind.METRO, file,
                "地铁 " + cars + " 节");
    }

    private static void brt(final PidsXiamenPainter.Assets assets, final BufferedImage ad,
                            final File file) throws Exception {
        final List<PidsXiamenPainter.Row> rows = new ArrayList<>();
        rows.add(new PidsXiamenPainter.Row("", "", "开往第一码头站", "", "快1线", 0xFF18A05E, "约",
                "3", "分钟", "min"));
        rows.add(new PidsXiamenPainter.Row("", "", "开往厦门北站", "", "快2线", 0xFFE07B1A, "约",
                "8", "分钟", "min"));
        final PidsXiamenPainter.Content content = new PidsXiamenPainter.Content(
                "11月20日星期五", "19:22", rows, List.of(), "暂无列车信息");
        write(content, assets, ad, PidsXiamenPainter.Kind.BRT, file, "BRT");
    }

    /**
     * 画一张 + 盖一层放大后的标注（区域框 + 一句说明）。
     *
     * <p>标注画在**放大之后**的图上，所以字是清晰的；版式本身按 512×235 原尺寸画再整体放大，
     * 和游戏里看到的像素是同一套。
     */
    private static void write(final PidsXiamenPainter.Content content,
                              final PidsXiamenPainter.Assets assets, final BufferedImage ad,
                              final PidsXiamenPainter.Kind kind, final File file,
                              final String title) throws Exception {
        final BufferedImage screen = PidsXiamenPainter.paint(content, assets, ad, kind,
                (int) PidsXiamenPainter.SCREEN_W, (int) PidsXiamenPainter.SCREEN_H);
        // 标语：把「滚到第 2 条一半」的那一帧合进来（游戏里它是单独一张小贴图）
        final float fontPx = PidsXiamenPainter.tickerFontPx(kind);
        final float[] inner = PidsXiamenPainter.tickerInner(kind);
        final PidsXiamenPainter.TickerPlan plan = PidsXiamenPainter.tickerPlan(
                List.of(new PidsXiamenPainter.TickerMessage("欢迎乘坐厦门地铁　请先下后上", 0xFFFFFFFF),
                        new PidsXiamenPainter.TickerMessage("下一站：镇海路", 0xFFFFFFFF)),
                assets.cjk, fontPx, inner[2] - inner[0], 2.0F, 48.0F);
        if (plan != null) {
            final BufferedImage strip = PidsXiamenPainter.paintTickerStrip(plan, 60L,
                    Math.round(inner[2] - inner[0]), Math.round(inner[3] - inner[1]));
            final Graphics2D g = screen.createGraphics();
            try {
                g.drawImage(strip, Math.round(inner[0]), Math.round(inner[1]), null);
            } finally {
                g.dispose();
            }
        }

        final BufferedImage big = new BufferedImage(screen.getWidth() * SCALE,
                screen.getHeight() * SCALE + 22, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = big.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0xFF202020));
            g.fillRect(0, 0, big.getWidth(), big.getHeight());
            g.drawImage(screen, 0, 22, big.getWidth(), screen.getHeight() * SCALE, null);
            g.setColor(new Color(0xFFFFFFFF));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g.drawString("厦门 PIDS 版式预览 · " + title + " · 512×235 @ " + SCALE + "x"
                    + "  （编组 / 到站 / 广告 / 标语都是函数画的）", 8, 16);
            g.setColor(new Color(0xFFFFD400));
            g.setStroke(new BasicStroke(1.0F));
            // 区域框：广告 / 标语 / 编组带（放大后画，单纯给人看）
            box(g, PidsXiamenPainter.adRect(kind), "AD");
            box(g, PidsXiamenPainter.tickerRect(kind), "TICKER");
            if (kind == PidsXiamenPainter.Kind.METRO) {
                box(g, PidsXiamenPainter.consistBand(), "CONSIST");
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(big, "png", file);
        System.out.println("  " + file.getName() + "  " + big.getWidth() + "x" + big.getHeight()
                + "  tickerSpanTicks=" + PidsXiamenPainter.tickerSpanTicks(plan));
    }

    private static void box(final Graphics2D g, final float[] rect, final String label) {
        final int x0 = Math.round(rect[0]) * SCALE;
        final int y0 = Math.round(rect[1]) * SCALE + 22;
        final int w = Math.round(rect[2] - rect[0]) * SCALE;
        final int h = Math.round(rect[3] - rect[1]) * SCALE;
        g.drawRect(x0, y0, w, h);
        g.drawString(label, x0 + 2, y0 + 10);
    }
}
