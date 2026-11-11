import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/** Dump size + the most common fully-opaque colours of each PNG given on the command line. */
public class PngProbe {
    public static void main(String[] args) throws Exception {
        for (String a : args) {
            File f = new File(a);
            BufferedImage img = ImageIO.read(f);
            if (img == null) { System.out.println(a + " : NOT AN IMAGE"); continue; }
            Map<Integer, Integer> hist = new LinkedHashMap<>();
            int opaque = 0, transparent = 0, partial = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    int al = (argb >>> 24) & 0xFF;
                    if (al == 0) { transparent++; continue; }
                    if (al < 255) { partial++; continue; }
                    opaque++;
                    hist.merge(argb & 0xFFFFFF, 1, Integer::sum);
                }
            }
            System.out.println(f.getName() + " " + img.getWidth() + "x" + img.getHeight()
                    + " opaque=" + opaque + " partial=" + partial + " clear=" + transparent);
            hist.entrySet().stream()
                    .sorted((p, q) -> q.getValue() - p.getValue())
                    .limit(4)
                    .forEach(e -> System.out.printf("    #%06X x%d%n", e.getKey(), e.getValue()));
        }
    }
}
