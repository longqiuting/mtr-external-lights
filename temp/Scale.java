import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Tab-icon converter: crop to the content bounding box, then alpha-weighted box
 * (area) downsample into a 16x16 canvas, aspect preserved and centred.
 *
 * <pre>
 *   java Scale.java in.png out.png [--band=largest|all] [--nearest]
 * </pre>
 *
 * --band=largest keeps only the biggest run of non-empty rows (drops a subtitle
 * line that would become a 1px smudge at 16x16).  --nearest does point sampling
 * instead of area averaging.  Alpha is never premultiplied away: colour channels
 * are averaged weighted by alpha and the output keeps the source alpha coverage.
 */
public final class Scale {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java Scale.java in.png out.png [--band=largest|all] [--nearest]");
            System.exit(2);
        }
        boolean largestBand = false;
        boolean nearest = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--band=largest")) largestBand = true;
            if (args[i].equals("--nearest")) nearest = true;
        }
        BufferedImage src = ImageIO.read(new File(args[0]));
        int w = src.getWidth(), h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);

        // 1) content bbox (alpha >= 8)
        int minX = w, minY = h, maxX = -1, maxY = -1;
        int[] rowMass = new int[h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((px[y * w + x] >>> 24) & 0xFF) < 8) continue;
                rowMass[y]++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) throw new IllegalStateException("image is fully transparent");

        // 2) optional: keep only the heaviest contiguous row band
        if (largestBand) {
            int bestStart = -1, bestEnd = -1;
            long bestMass = -1;
            int start = -1;
            long mass = 0;
            for (int y = 0; y <= h; y++) {
                boolean filled = y < h && rowMass[y] > 0;
                if (filled && start < 0) { start = y; mass = 0; }
                if (filled) mass += rowMass[y];
                if (!filled && start >= 0) {
                    if (mass > bestMass) { bestMass = mass; bestStart = start; bestEnd = y - 1; }
                    start = -1;
                }
            }
            minY = bestStart;
            maxY = bestEnd;
            // re-tighten the horizontal extent to the kept band
            minX = w; maxX = -1;
            for (int y = minY; y <= maxY; y++) {
                for (int x = 0; x < w; x++) {
                    if (((px[y * w + x] >>> 24) & 0xFF) < 8) continue;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                }
            }
        }

        final int cw = maxX - minX + 1;
        final int ch = maxY - minY + 1;

        // 3) fit into 16x16, aspect preserved
        final int size = 16;
        double s = Math.min((double) size / cw, (double) size / ch);
        int dw = Math.max(1, (int) Math.round(cw * s));
        int dh = Math.max(1, (int) Math.round(ch * s));
        int offX = (size - dw) / 2;
        int offY = (size - dh) / 2;

        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int dy = 0; dy < dh; dy++) {
            for (int dx = 0; dx < dw; dx++) {
                int argb;
                if (nearest) {
                    int sx = minX + Math.min(cw - 1, (int) ((dx + 0.5) * cw / dw));
                    int sy = minY + Math.min(ch - 1, (int) ((dy + 0.5) * ch / dh));
                    argb = px[sy * w + sx];
                } else {
                    int x0 = minX + (int) Math.floor((double) dx * cw / dw);
                    int x1 = minX + (int) Math.ceil((double) (dx + 1) * cw / dw);
                    int y0 = minY + (int) Math.floor((double) dy * ch / dh);
                    int y1 = minY + (int) Math.ceil((double) (dy + 1) * ch / dh);
                    if (x1 <= x0) x1 = x0 + 1;
                    if (y1 <= y0) y1 = y0 + 1;
                    double aSum = 0, rSum = 0, gSum = 0, bSum = 0;
                    int n = 0;
                    for (int sy = y0; sy < y1 && sy <= maxY; sy++) {
                        for (int sx = x0; sx < x1 && sx <= maxX; sx++) {
                            int p = px[sy * w + sx];
                            int a = (p >>> 24) & 0xFF;
                            aSum += a;
                            // premultiplied average: colour is weighted by its own alpha
                            rSum += ((p >> 16) & 0xFF) * a;
                            gSum += ((p >> 8) & 0xFF) * a;
                            bSum += (p & 0xFF) * a;
                            n++;
                        }
                    }
                    int a = (int) Math.round(aSum / n);
                    int r = aSum == 0 ? 0 : (int) Math.round(rSum / aSum);
                    int g = aSum == 0 ? 0 : (int) Math.round(gSum / aSum);
                    int b = aSum == 0 ? 0 : (int) Math.round(bSum / aSum);
                    argb = (a << 24) | (r << 16) | (g << 8) | b;
                }
                dst.setRGB(offX + dx, offY + dy, argb);
            }
        }
        ImageIO.write(dst, "png", new File(args[1]));
        System.out.println("src " + w + "x" + h + " crop " + cw + "x" + ch + " at " + minX + "," + minY
                + " -> drawn " + dw + "x" + dh + " at " + offX + "," + offY
                + (nearest ? " [nearest]" : " [area]") + (largestBand ? " [largest band]" : ""));
    }
}
