import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Reports alpha/white stats and content row bands for the two tab-icon sources. */
public final class Probe {
    public static void main(String[] args) throws Exception {
        for (String a : args) {
            BufferedImage img = ImageIO.read(new File(a));
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            int transparent = 0, nearWhite = 0;
            int minX = w, minY = h, maxX = -1, maxY = -1;
            int[] rowMass = new int[h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = px[y * w + x];
                    int alpha = (p >>> 24) & 0xFF;
                    if (alpha < 8) { transparent++; continue; }
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                    if (r >= 245 && g >= 245 && b >= 245) nearWhite++;
                    else {
                        rowMass[y]++;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            System.out.println("== " + a);
            System.out.println("   size " + w + "x" + h + " transparent=" + transparent
                    + " nearWhite=" + nearWhite);
            System.out.println("   corner=" + Integer.toHexString(px[0]) + " center="
                    + Integer.toHexString(px[(h / 2) * w + w / 2]));
            System.out.println("   content bbox x[" + minX + ".." + maxX + "] y[" + minY + ".." + maxY + "]");
            // print row bands (runs of non-empty rows)
            int start = -1;
            for (int y = 0; y <= h; y++) {
                boolean filled = y < h && rowMass[y] > 0;
                if (filled && start < 0) start = y;
                if (!filled && start >= 0) {
                    long mass = 0;
                    for (int yy = start; yy < y; yy++) mass += rowMass[yy];
                    System.out.println("   row band " + start + ".." + (y - 1) + " mass=" + mass);
                    start = -1;
                }
            }
        }
    }
}
