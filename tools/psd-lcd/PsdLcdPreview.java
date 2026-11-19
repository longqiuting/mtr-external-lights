// 离线预览：把 PsdLcdPainter 的同一份绘制代码跑成 PNG。
//
// 只依赖 src/main/java/.../{PsdLcdPainter,PsdLcdTypeface}.java（这两个文件不 import 任何
// Minecraft 类），所以预览图和游戏里看到的是同一份排版 —— 不会各画各的。
//
// 用法：
//   java tools/psd-lcd/PsdLcdPreview.java <out.png> [宽/高比] [像素高] [字体目录]
// 例：
//   java tools/psd-lcd/PsdLcdPreview.java temp/psd-lcd-preview.png 6.91 320
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

/** {@code tools/preview-psd-lcd.js} 调的入口。 */
public final class PsdLcdPreview {

    /** 资源包里那三支字的相对路径（和 xmlcd/config.json 里写的一致）。 */
    private static final String[] PACK_FONTS = {"fonts/misans.otf", "fonts/roboto.ttf", "fonts/dg.ttf"};

    private PsdLcdPreview() {
    }

    public static void main(final String[] args) throws Exception {
        final File out = new File(args.length > 0 ? args[0] : "temp/psd-lcd-preview.png");
        final double ratio = args.length > 1 ? Double.parseDouble(args[1]) : 6.910D;
        final int height = args.length > 2 ? Integer.parseInt(args[2]) : 320;
        final File fontDir = args.length > 3 && !args[3].isBlank()
                ? new File(args[3]) : defaultFontDir();
        final int cars = args.length > 4 ? Integer.parseInt(args[4]) : 4;

        final PsdLcdPainter.Assets assets = assets(fontDir);
        final int width = (int) Math.round(height * ratio);
        final BufferedImage image = PsdLcdPainter.paint(sample(cars), assets, width, height);

        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        ImageIO.write(image, "PNG", out);
        System.out.println("wrote " + out.getCanonicalPath() + "  (" + width + "x" + height
                + ", ratio=" + String.format("%.3f", (double) width / height)
                + ", cars=" + cars
                + ", cjk=" + PsdLcdTypeface.name(assets.cjk)
                + ", latin=" + PsdLcdTypeface.name(assets.latin)
                + ", digits=" + PsdLcdTypeface.name(assets.digits) + ")");
    }

    /** 资源包里的字体目录（本机装了那个包就用它，换机器就退回系统字体）。 */
    private static File defaultFontDir() {
        final String[] candidates = {
                "C:/Users/AD/Desktop/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/resourcepacks/"
                        + "厦门地铁一号线车辆/assets/mtr/xmlcd/fonts",
                "../resourcepacks/厦门地铁一号线车辆/assets/mtr/xmlcd/fonts",
        };
        for (final String path : candidates) {
            final File dir = new File(path);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    /** 素材全部走模组自己的贴图（和游戏里是同一张），字体走资源包 / 系统。 */
    private static PsdLcdPainter.Assets assets(final File fontDir) throws Exception {
        final PsdLcdPainter.Assets assets = new PsdLcdPainter.Assets();
        final File dir = new File("v1.20.1/src/main/resources/assets/mtrlte/textures/gui/psd_lcd");
        assets.logo = read(dir, "xiamen_logo.png");
        assets.nose = read(dir, "train_nose.png");
        assets.carEnd = read(dir, "car_end.png");
        assets.carMiddle = read(dir, "car_middle.png");

        Font cjk = null;
        Font latin = null;
        if (fontDir != null && fontDir.isDirectory()) {
            cjk = PsdLcdTypeface.fromFile(new File(fontDir, "misans.otf"));
            latin = PsdLcdTypeface.fromFile(new File(fontDir, "roboto.ttf"));
        }
        // 大数字用系统细字重（dg.ttf 是七段数码管，和实拍里的大数字不是一回事）
        assets.cjk = cjk != null ? cjk : PsdLcdTypeface.systemCjk();
        assets.latin = latin != null ? latin : PsdLcdTypeface.systemLatin();
        assets.digits = PsdLcdTypeface.systemLight();
        return assets;
    }

    private static BufferedImage read(final File dir, final String name) throws Exception {
        final File file = new File(dir, name);
        return file.isFile() ? ImageIO.read(file) : null;
    }

    // ------------------------------------------------------------------ 示例内容

    /** 与实拍（{@code 扫描全能王 2026-11-19 19.07.jpg}）一致的数据。 */
    private static PsdLcdPainter.Content sample(final int cars) {
        final java.util.List<String> numbers = new java.util.ArrayList<>(cars);
        for (int i = 1; i <= cars; i++) {
            numbers.add(String.valueOf(i));
        }
        return new PsdLcdPainter.Content(
                "2026-09-23", "星期三", "Wednesday", "11:10",
                "开往", "Bound for", "华侨大学", "Huaqiao University", "7",
                "下一趟开往", "Next train", "华侨大学", "Huaqiao University", "17",
                "分钟", "min",
                "6号线", "Line 6", 0xFFA004F8,
                "董任", "Dongren",
                numbers,
                "此处放置广告",
                "草坪，让月光替你唱。  XM6—厦门电视台移动电视  【树洞投稿方式】搜索“厦门树洞”小程序，点击加号参与树",
                false);
    }
}
