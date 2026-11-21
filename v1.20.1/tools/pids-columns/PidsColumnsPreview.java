// 厦门 PIDS 到站行的「中英双语整屏预览」：512×235 底图 + 按列几何摆字，中文一张、英文一张，上下拼成一张。
//
//   ./tools/pids-columns/check.sh         # 断言 + 预览一起跑
//
// 为什么能离线跑：PidsXiamenLayout / PidsXiamenColumns / TextFit 全是纯 Java（零 MC 依赖），
// 预览用的字宽与游戏里 TextRenderer#getWidth 同一套口径（前进宽），所以**图上的间隙就是实机的间隙**。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/** 整屏预览：长标签（下一班开往 / Next train）+ 长站名（厦门北站附属客运中心）。 */
public final class PidsColumnsPreview {

    private static final int SCALE = 2;

    /** 一个像素与底图差异超过这个值就算「有墨」（文字抗锯齿的淡边不算）。 */
    private static final int INK_DIFF = 48;

    private PidsColumnsPreview() {
    }

    /** 一行要画的内容。 */
    private record Row(String label, String labelSub, String dest, String destSub,
                       String count, String unit) {
    }

    private static final Row[] ZH = {
            new Row("开往", "Bound for", "第一码头", "first warf", "3", "分钟"),
            new Row("下一班开往", "Next train", "厦门北站附属客运中心",
                    "xiamen north station annex passenger transport center", "12", "分钟"),
            new Row("第三班开往", "Third train", "第一码头", "first warf", "已到站", "")
    };

    private static final Row[] EN = {
            new Row("To", "Bound for", "First Wharf", "first wharf", "3", "min"),
            new Row("Next", "Next train", "Xiamen North Station Annex Passenger Transport Center",
                    "xiamen north station annex passenger transport centre", "12", "min"),
            new Row("Third", "Third train", "First Wharf", "first wharf", "Arrived", "")
    };

    public static void main(final String[] args) throws IOException {
        final File mod = new File(args.length > 0 ? args[0] : ".");
        final File out = new File(args.length > 1 ? args[1] : new File(mod, "temp").getPath());
        out.mkdirs();

        final BufferedImage bg = ImageIO.read(new File(mod,
                "src/main/resources/assets/mtrlte/textures/block/pids_xiamen_metro.png"));
        if (bg == null) {
            throw new IOException("读不到 pids_xiamen_metro.png");
        }

        final Font cjk = PsdLcdTypeface.cjk();
        final Font latin = PsdLcdTypeface.latin();

        final BufferedImage zh = panel(bg, cjk, latin, "zh_cn", ZH);
        final BufferedImage en = panel(bg, cjk, latin, "en_us", EN);
        final BufferedImage both = stack(zh, en,
                "中文（zh_cn）· 标签列 开往 / 下一班开往 / 第三班开往",
                "English (en_us) · labels To / Next / Third");

        final File file = new File(out, "pids_columns_zh_en.png");
        ImageIO.write(both, "png", file);
        System.out.println();
        System.out.println("预览：" + file.getAbsolutePath());
        System.out.println("  （上=中文，下=英文；三行共用同一套标签列宽，长站名缩到 62% 后尾部截断，"
                + "站名右缘不越过数字行那一侧的列右界）");
        if (pixelFailures > 0) {
            System.out.println("FAILED: 渲染图实测有 " + pixelFailures + " 处标签压到站名 / 站名越界");
            System.exit(1);
        }
    }

    private static int pixelFailures;

    // ------------------------------------------------------------------ 一张面板

    private static BufferedImage panel(final BufferedImage bg, final Font cjk, final Font latin,
                                       final String lang, final Row[] content) {
        final BufferedImage img = new BufferedImage((int) PidsXiamenLayout.SCREEN_WIDTH,
                (int) PidsXiamenLayout.SCREEN_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(bg, 0, 0, img.getWidth(), img.getHeight(), null);

        final TextFit.Metrics mcjk = metrics(cjk);
        final TextFit.Metrics mlat = metrics(latin);

        final PidsXiamenLayout.Row[] rows = PidsXiamenLayout.METRO.rows();
        // 三行共用的标签列宽（与渲染器同一份算法：中/英各自量，取最大）
        final List<PidsXiamenColumns.Label> all = new ArrayList<>();
        final List<TextFit.Metrics> each = new ArrayList<>();
        float glyph = 0.0F;
        for (int i = 0; i < rows.length; i++) {
            all.add(new PidsXiamenColumns.Label(content[i].label(), size(rows[i].label(), rows[i].card())));
            each.add(mcjk);
            all.add(new PidsXiamenColumns.Label(content[i].labelSub(), size(rows[i].labelSub(), rows[i].card())));
            each.add(mlat);
            glyph = Math.max(glyph, size(rows[i].dest(), rows[i].card()));
        }
        final float labelW = PidsXiamenColumns.labelWidth(all, each);
        final float gap = PidsXiamenColumns.gap(glyph);
        final float[] nameXs = new float[rows.length];
        final float[] nameRights = new float[rows.length];

        for (int i = 0; i < rows.length; i++) {
            final PidsXiamenLayout.Row row = rows[i];
            final PidsXiamenLayout.Rect card = row.card();
            final PidsXiamenColumns.Columns cols =
                    PidsXiamenColumns.of(card.x0() + row.labelXInset(), labelW, glyph, card.x1() - gap);
            nameXs[i] = cols.nameX();
            nameRights[i] = cols.nameRight();
            final Row c = content[i];

            // 左列：两行（大标签 + 小标签），整块在框里垂直居中 —— 与 drawFlow 同一套
            final float[] box = flowBox(card, row.padRatio(), row.gapRatio(),
                    size(row.label(), card), size(row.labelSub(), card));
            text(g, c.label(), cjk, size(row.label(), card), cols.labelX(), box[0]);
            text(g, c.labelSub(), latin, size(row.labelSub(), card), cols.labelX(), box[1]);

            // 右列：站名（只缩不放）+ 英文站名 + 倒计时
            final TextFit.Fitted zh = TextFit.fit(c.dest(), mcjk,
                    size(row.dest(), card), cols.nameW(), TextFit.MIN_SCALE);
            final TextFit.Fitted en = TextFit.fit(c.destSub(), mlat,
                    size(row.destSub(), card), cols.nameW(), TextFit.MIN_SCALE);
            final float countSize = size(row.count(), card);
            final float unitSize = size(row.unit(), card);
            final float[] box2 = flowBox(card, row.padRatio(), row.gapRatio(),
                    zh.size(), en.size(), Math.max(countSize, unitSize));

            text(g, zh.text(), cjk, zh.size(), cols.nameX(), box2[0]);
            text(g, en.text(), latin, en.size(), cols.nameX(), box2[1]);
            float x = cols.nameX();
            x += text(g, c.count(), cjk, countSize, x, box2[2]);
            if (!c.unit().isEmpty()) {
                text(g, " " + c.unit(), latin, unitSize, x, box2[2]);
            }
        }
        g.dispose();
        measure(img, bg, rows, nameXs, nameRights, lang);
        return img;
    }

    /**
     * 像素复核：直接在渲染出来的图上量「标签墨迹右缘 → 站名墨迹左缘」，以及站名墨迹右缘有没有越过列右界。
     *
     * <p>这是最硬的一条断言 —— 不信任任何算出来的数，只看画出来的像素。
     */
    private static void measure(final BufferedImage img, final BufferedImage bg,
                                final PidsXiamenLayout.Row[] rows, final float[] nameXs,
                                final float[] nameRights, final String lang) {
        for (int i = 0; i < rows.length; i++) {
            final PidsXiamenLayout.Rect card = rows[i].card();
            final int y0 = (int) Math.ceil(card.y0()) + 2;
            final int y1 = (int) Math.floor(card.y1()) - 2;
            final int split = (int) Math.floor(nameXs[i]);
            final int left = (int) Math.ceil(card.x0() + rows[i].labelXInset());
            final int right = (int) Math.floor(nameRights[i]);

            int labelInk = -1;
            int nameInk = Integer.MAX_VALUE;
            int overInk = -1;
            // 硬边界 = 框的右内沿（列右界 159.93 之外还余 9px；省略号的墨迹比前进宽多出 1~2px，
            // 所以「越界」按框算，不按列右界算）
            final int overLimit = (int) Math.floor(card.x1()) - 1;
            final int hardRight = overLimit + 6;
            for (int x = left; x <= hardRight && x < img.getWidth(); x++) {
                if (!columnHasInk(img, bg, x, y0, y1)) {
                    continue;
                }
                if (x > right) {
                    overInk = Math.max(overInk, x);
                }
                if (x < split) {
                    labelInk = x;
                } else {
                    nameInk = Math.min(nameInk, x);
                }
            }
            final float gap = nameInk == Integer.MAX_VALUE ? Float.NaN : nameInk - labelInk - 1.0F;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "  像素复核 [%s] row%d：标签墨迹右缘 %d → 站名墨迹左缘 %d（间隙 %.0fpx）%s",
                    lang, i + 1, labelInk, nameInk == Integer.MAX_VALUE ? -1 : nameInk, gap,
                    overInk >= overLimit ? "  ← 越过框右沿！" : ""));
            if (nameInk != Integer.MAX_VALUE && labelInk >= 0 && nameInk - labelInk - 1 < 0) {
                pixelFailures++;
            }
            if (overInk >= overLimit) {
                pixelFailures++;
            }
        }
    }

    /** 这一列（在 {@code [y0, y1]} 之间）有没有不属于底图的墨迹。 */
    private static boolean columnHasInk(final BufferedImage img, final BufferedImage bg,
                                        final int x, final int y0, final int y1) {
        for (int y = y0; y <= y1; y++) {
            final int a = img.getRGB(x, y);
            final int bx = Math.min(bg.getWidth() - 1, x * bg.getWidth() / img.getWidth());
            final int by = Math.min(bg.getHeight() - 1, y * bg.getHeight() / img.getHeight());
            if (diff(a, bg.getRGB(bx, by)) > INK_DIFF) {
                return true;
            }
        }
        return false;
    }

    private static int diff(final int p, final int q) {
        return Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF))
                + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                + Math.abs((p & 0xFF) - (q & 0xFF));
    }

    /**
     * {@code drawFlow} 的垂直排版：整块（各行字高 + 行间距）在框里居中，但不高于内边距。
     *
     * @return 每行的上沿 y（px）
     */
    private static float[] flowBox(final PidsXiamenLayout.Rect card, final float padRatio,
                                   final float gapRatio, final float... heights) {
        final float gap = card.h() * gapRatio;
        float total = gap * (heights.length - 1);
        for (final float height : heights) {
            total += height;
        }
        float y = card.y0() + (card.h() - total) * 0.5F;
        y = Math.max(y, card.y0() + card.h() * padRatio);
        final float[] tops = new float[heights.length];
        for (int i = 0; i < heights.length; i++) {
            tops[i] = y;
            y += heights[i] + gap;
        }
        return tops;
    }

    /** 画一段字（墨迹上沿 ≈ top），返回前进宽。 */
    private static float text(final Graphics2D g, final String text, final Font font, final float size,
                              final float x, final float top) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        final Font f = font.deriveFont(size);
        g.setFont(f);
        g.setColor(Color.WHITE);
        // 字形盒 = 目标字高：基线压到 top + size×0.82（和 MC 那边「字高 = 8px 盒」同一个观感）
        g.drawString(text, x, top + size * 0.82F);
        return (float) f.getStringBounds(text, g.getFontRenderContext()).getWidth();
    }

    // ------------------------------------------------------------------ 拼图

    private static BufferedImage stack(final BufferedImage top, final BufferedImage bottom,
                                       final String capTop, final String capBottom) {
        final int capH = 18;
        final int width = Math.max(top.getWidth(), bottom.getWidth());
        final int height = capH + top.getHeight() + capH + bottom.getHeight();
        final BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0x101418));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(0xE6EBF2));
        g.setFont(PsdLcdTypeface.latin().deriveFont(12.0F));
        g.drawString(capTop, 8, 13);
        g.drawImage(top, 0, capH, null);
        g.setColor(new Color(0xE6EBF2));
        g.drawString(capBottom, 8, capH + top.getHeight() + 13);
        g.drawImage(bottom, 0, capH + top.getHeight() + capH, null);
        g.dispose();
        // 2× 最近邻放大（看间隙用，不插值糊图）
        final BufferedImage big = new BufferedImage(width * SCALE, height * SCALE, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g2 = big.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(sheet, 0, 0, big.getWidth(), big.getHeight(), null);
        g2.dispose();
        return big;
    }

    // ------------------------------------------------------------------ 度量

    /** 前进宽（= 游戏里 TextRenderer#getWidth × size/8 的同一口径）。 */
    private static TextFit.Metrics metrics(final Font font) {
        final FontRenderContext frc = new FontRenderContext(null, true, true);
        return (text, size) -> text == null || text.isEmpty() ? 0.0F
                : (float) font.deriveFont(size).getStringBounds(text, frc).getWidth();
    }

    private static float size(final PidsXiamenLayout.TextStyle style, final PidsXiamenLayout.Rect card) {
        return Math.max(3.0F, card.h() * style.sizeRatio());
    }
}
