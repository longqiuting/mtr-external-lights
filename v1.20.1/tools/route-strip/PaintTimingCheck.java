// 只测 paint() 的耗时（端头留白预演的收敛循环会不会拖慢渲染）。
package dev.js.mtrheadlight.xiamen;

public final class PaintTimingCheck {
    public static void main(final String[] args) {
        final String[] names = {
            "镇海路|Zhenhai Road", "中山公园|Zhongshan Park", "将军祠|Jiangjunci", "文灶|Wenzao",
            "湖滨东路|Hubin East Road", "莲坂|Lianban", "莲花路口|Lianhua Road", "吕厝|Lucuo",
            "乌石浦|Wushipu", "塘边|Tangbian", "火炬园|Torch Hi-Tech Park", "高崎|Gaoqi",
            "集美学村|Jimei School Village", "园博苑|Yuanboyuan", "杏林村|Xinglin Village", "杏锦路|Xingjin Road",
            "官任|Guanren", "市民中心|Citizen Center", "诚毅广场|Chengyi Square", "集美软件园|Jimei Software Park",
            "集美大道|Jimei Avenue", "天水路|Tianshui Road", "厦门北站|Xiamen North Railway Station",
            "沉船博物馆|Shipwreck Museum"
        };
        final XiamenStripPainter.Model model = new XiamenStripPainter.Model();
        model.names = new String[names.length];
        model.namesEn = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            model.names[i] = XiamenStripPainter.cjkPart(names[i]);
            model.namesEn[i] = XiamenStripPainter.enPart(names[i]);
        }
        model.currentIndex = 3;
        model.lineRgb = 0xE60012;
        model.lineNumber = "4";
        model.aspectRatio = 8.5f;
        model.interchanges = new XiamenStripPainter.Interchange[]{new XiamenStripPainter.Interchange(
                names.length - 1, 0x00A651, "2", "2号线")};
        for (int i = 0; i < 5; i++) {
            XiamenStripPainter.paint(model);
        }
        final int n = 40;
        final long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            XiamenStripPainter.paint(model);
        }
        final long t1 = System.nanoTime();
        System.out.printf("paint() 24 站：%.2f ms/次（%d 次）%n", (t1 - t0) / 1e6 / n, n);

        // 正排（namesBelow）走另一条分支：端头留白必须为 0、不参与排版。
        model.namesBelow = true;
        final java.awt.image.BufferedImage below = XiamenStripPainter.paint(model);
        System.out.println("namesBelow=true: 图 " + below.getWidth() + "x" + below.getHeight()
                + "  端头留白=" + XiamenStripPainter.lastNameReserve
                + "  站名字号=" + XiamenStripPainter.lastNameSize
                + "  旋转=" + XiamenStripPainter.lastNameRotationDeg + "°");
    }

    private PaintTimingCheck() {
    }
}
