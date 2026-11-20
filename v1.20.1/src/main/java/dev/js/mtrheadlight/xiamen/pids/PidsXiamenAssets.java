// 厦门 PIDS 的素材加载：底图底板 / M 标志 / 编组卡片，从模组自己的资源里读成 BufferedImage。
//
// 为什么走 PNG → BufferedImage（而不是直接贴 MC 贴图）：整屏是 Java2D 画进一张位图的
// （见 PidsXiamenPainter），画家不认识 ResourceManager，所以这里负责「读一次、长期缓存」。
// 资源重载（F3+T）后由 PidsXiamenClient 的监听器调 invalidate() 丢缓存 —— 换了资源包立刻生效。
//
// 字体不在这里读：统一走 PsdLcdTypeface（= XiamenStripPainter#font() 那一套系统黑体），
// 和线路条带 / PSD-LCD 吊板完全一致，见 PidsXiamenPainter 的类注释。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** 一次性加载、长期缓存（素材是模组自带的，不随资源包变；换包时 invalidate）。 */
final class PidsXiamenAssets {

    /** 编组卡片 / 其它 PIDS 专用 GUI 素材。 */
    private static final String GUI = "textures/gui/pids/";
    /** M 标志与 PSD-LCD 吊板**共用同一张**（用户明确要求）。 */
    private static final Identifier LOGO =
            new Identifier(PsdLcdObjects.NAMESPACE, "textures/gui/psd_lcd/xiamen_logo.png");

    private static PidsXiamenPainter.Assets cached;

    /** 底图丢失 / 读不到时只 warning 一次（每帧都会走到这里）。 */
    private static boolean warned;

    private PidsXiamenAssets() {
    }

    /** 素材 + 字体（第一次调用时加载，之后直接返回缓存）。 */
    static PidsXiamenPainter.Assets get() {
        final PidsXiamenPainter.Assets local = cached;
        if (local != null) {
            return local;
        }
        final PidsXiamenPainter.Assets loaded = new PidsXiamenPainter.Assets();
        // 底图 = 用户新给的**纯版式底板**（只有色块 / 描边框，没有文字）
        loaded.metroBase = blockTexture(PidsXiamenBlock.Kind.METRO);
        loaded.brtBase = blockTexture(PidsXiamenBlock.Kind.BRT);
        loaded.logo = image(LOGO);
        loaded.carEnd = image(new Identifier(PsdLcdObjects.NAMESPACE, GUI + "consist_car_end.png"));
        loaded.carMiddle = image(new Identifier(PsdLcdObjects.NAMESPACE, GUI + "consist_car_middle.png"));
        // 字体：与线路条带 / PSD-LCD 同一支（系统黑体）
        loaded.cjk = PsdLcdTypeface.cjk();
        loaded.latin = PsdLcdTypeface.latin();
        loaded.digits = PsdLcdTypeface.digits();
        cached = loaded;
        LteLog.LOGGER.info("[lte] xiamen pids: base metro={} brt={} logo={} cards={}/{} font={}",
                loaded.metroBase == null ? "MISSING" : loaded.metroBase.getWidth() + "x"
                        + loaded.metroBase.getHeight(),
                loaded.brtBase == null ? "MISSING" : loaded.brtBase.getWidth() + "x"
                        + loaded.brtBase.getHeight(),
                loaded.logo == null ? "MISSING" : "ok",
                loaded.carEnd == null ? "MISSING" : "ok",
                loaded.carMiddle == null ? "MISSING" : "ok",
                PsdLcdTypeface.name(loaded.cjk));
        return loaded;
    }

    /** 资源重载 → 下次渲染重新读（换资源包能立刻换掉底图 / 标志）。 */
    static void invalidate() {
        cached = null;
    }

    private static BufferedImage blockTexture(final PidsXiamenBlock.Kind kind) {
        return image(kind.screenTexture());
    }

    private static BufferedImage image(final Identifier id) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return null;
            }
            final var resource = client.getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                if (!warned) {
                    warned = true;
                    LteLog.LOGGER.warn("[lte] xiamen pids: texture missing {}", id);
                }
                return null;
            }
            try (InputStream in = resource.get().getInputStream()) {
                return ImageIO.read(in);
            }
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.warn("[lte] xiamen pids: cannot load texture {}", id, t);
            }
            return null;
        }
    }
}
