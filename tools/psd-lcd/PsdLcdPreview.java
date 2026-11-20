// 离线预览：把 PsdLcdPainter 的同一份绘制代码跑成 PNG。
//
// 只依赖 src/main/java/.../{PsdLcdPainter,PsdLcdTypeface}.java + xiamen/XiamenStripPainter.java
// （这三个文件都不 import 任何 Minecraft 类），所以预览图和游戏里看到的是同一份排版 ——
// 不会各画各的。字体也是同一套：PsdLcdTypeface → XiamenStripPainter#font()（系统黑体）。
//
// 用法：
//   java tools/psd-lcd/PsdLcdPreview.java <out.png> [宽/高比] [像素高] [忽略] [车厢数]
//                                        [count1] [count2] [unitZh] [unitEn] [tickerPhase]
// 例：
//   java tools/psd-lcd/PsdLcdPreview.java temp/psd-lcd-preview.png 6.91 320 - 4
//   java tools/psd-lcd/PsdLcdPreview.java temp/p.png 6.91 455 - 8 0 17 分钟 min 0.35
//
// tickerPhase：0…1 的标语滚动进度（只对「字比框宽」的长句有效；短句永远居中静止）。
// 不传 = 长句不画（和游戏里一样：长句由渲染器每 tick 画在小贴图上）。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/** {@code tools/preview-psd-lcd.js} 调的入口。 */
public final class PsdLcdPreview {

    private PsdLcdPreview() {
    }

    public static void main(final String[] args) throws Exception {
        final File out = new File(args.length > 0 ? args[0] : "temp/psd-lcd-preview.png");
        final double ratio = args.length > 1 ? Double.parseDouble(args[1]) : 6.910D;
        final int height = args.length > 2 ? Integer.parseInt(args[2]) : 320;
        // args[3] 以前是「资源包字体目录」，字体改成和普通 PIDS 一致之后不再需要（保留位置免得改脚本）。
        final int cars = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        final String count1 = args.length > 5 ? args[5] : "7";
        final String count2 = args.length > 6 ? args[6] : "17";
        final String unitZh = args.length > 7 ? args[7] : "分钟";
        final String unitEn = args.length > 8 ? args[8] : "min";
        final Float phase = args.length > 9 ? Float.parseFloat(args[9]) : null;
        final String ticker = args.length > 10 ? args[10] : null;

        final PsdLcdPainter.Assets assets = assets();
        final int width = (int) Math.round(height * ratio);
        final BufferedImage image = PsdLcdPainter.paint(
                sample(cars, count1, count2, unitZh, unitEn, ticker), assets, null, width, height, phase);

        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        ImageIO.write(image, "PNG", out);
        System.out.println("wrote " + out.getCanonicalPath() + "  (" + width + "x" + height
                + ", ratio=" + String.format("%.3f", (double) width / height)
                + ", cars=" + cars
                + ", count=" + count1 + "/" + count2
                + ", unit=" + unitZh + "/" + unitEn
                + ", tickerPhase=" + phase
                + ", cjk=" + PsdLcdTypeface.name(assets.cjk)
                + ", latin=" + PsdLcdTypeface.name(assets.latin)
                + ", digits=" + PsdLcdTypeface.name(assets.digits) + ")");
    }

    /** 素材走模组自己的贴图（和游戏里是同一张）；字体走 PsdLcdTypeface（= 厦门 PIDS 那一套）。 */
    private static PsdLcdPainter.Assets assets() throws Exception {
        final PsdLcdPainter.Assets assets = new PsdLcdPainter.Assets();
        final File dir = new File("v1.20.1/src/main/resources/assets/mtrlte/textures/gui/psd_lcd");
        assets.logo = read(dir, "xiamen_logo.png");
        assets.nose = read(dir, "train_nose.png");
        assets.carEnd = read(dir, "car_end.png");
        assets.carMiddle = read(dir, "car_middle.png");
        assets.cjk = PsdLcdTypeface.cjk();
        assets.latin = PsdLcdTypeface.latin();
        assets.digits = PsdLcdTypeface.digits();
        return assets;
    }

    private static BufferedImage read(final File dir, final String name) throws Exception {
        final File file = new File(dir, name);
        return file.isFile() ? ImageIO.read(file) : null;
    }

    // ------------------------------------------------------------------ 示例内容

    /** 与实拍（{@code 扫描全能王 2026-11-19 19.07.jpg}）一致的数据。 */
    private static PsdLcdPainter.Content sample(final int cars, final String count1, final String count2,
                                                final String unitZh, final String unitEn,
                                                final String ticker) {
        final int n = Math.max(1, cars);
        final java.util.List<String> numbers = new java.util.ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            numbers.add(String.valueOf(i));
        }
        return new PsdLcdPainter.Content(
                "2026-09-23", "星期三", "Wednesday", "11:10",
                "开往", "Bound for", "华侨大学", "Huaqiao University", count1,
                "下一趟开往", "Next train", "华侨大学", "Huaqiao University", count2,
                unitZh, unitEn,
                unitZh, unitEn,
                "6号线", "Line 6", 0xFFA004F8,
                "董任", "Dongren",
                numbers,
                "注：编组按线路实际运营车辆读取",
                ticker == null ? "草坪，让月光替你唱。  XM6—厦门电视台移动电视  【树洞投稿方式】搜索“厦门树洞”小程序，点击加号参与树" : ticker,
                "广告位");
    }
}
