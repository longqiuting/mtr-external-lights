import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Builds a magnified preview sheet (10x) of 16x16 icons over a dark checkerboard. */
public final class Preview {
    public static void main(String[] args) throws Exception {
        int scale = Integer.getInteger("s", 30), cell = 16 * scale + 8;
        BufferedImage out = new BufferedImage(cell * args.length, cell, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < args.length; i++) {
            BufferedImage img = ImageIO.read(new File(args[i]));
            for (int cy = 0; cy < cell; cy++) {
                for (int cx = 0; cx < cell; cx++) {
                    boolean dark = ((cx / 10) + (cy / 10)) % 2 == 0;
                    out.setRGB(i * cell + cx, cy, dark ? 0xFF303030 : 0xFF505050);
                }
            }
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = img.getRGB(x, y);
                    int a = (p >>> 24) & 0xFF;
                    if (a == 0) continue;
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            out.setRGB(i * cell + 4 + x * scale + dx, 4 + y * scale + dy, p);
                        }
                    }
                }
            }
        }
        ImageIO.write(out, "png", new File("icons/preview.png"));
        System.out.println("wrote icons/preview.png " + out.getWidth() + "x" + out.getHeight());
    }
}
