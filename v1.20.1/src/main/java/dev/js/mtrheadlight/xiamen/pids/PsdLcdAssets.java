// PSD-LCD 的素材与字体：从资源管理器读（模组自己的贴图 + 厦门资源包里的字体）。
//
// 字体优先级：资源包 mtr:xmlcd/fonts/{misans.otf,roboto.ttf} → 系统黑体 / 无衬线。
// 大数字固定用系统细字重（实拍里 7 / 17 的笔画只有字高的 6~7%，常规字重太粗）。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** 一次性加载、长期缓存（素材是模组自带的，不随资源包变）。 */
final class PsdLcdAssets {

    private static final String DIR = "textures/gui/psd_lcd/";
    /** 资源包里的三支字（厦门 LCD 那个包；不在就退回系统字体）。 */
    private static final String[] FONT_CANDIDATES = {"fonts/misans.otf", "fonts/roboto.ttf"};

    private static PsdLcdPainter.Assets cached;

    private PsdLcdAssets() {
    }

    static PsdLcdPainter.Assets get() {
        final PsdLcdPainter.Assets local = cached;
        if (local != null) {
            return local;
        }
        final PsdLcdPainter.Assets loaded = new PsdLcdPainter.Assets();
        loaded.logo = image("xiamen_logo.png");
        loaded.nose = image("train_nose.png");
        loaded.carEnd = image("car_end.png");
        loaded.carMiddle = image("car_middle.png");
        loaded.cjk = packFont(0);
        loaded.latin = packFont(1);
        if (loaded.cjk == null) {
            loaded.cjk = PsdLcdTypeface.systemCjk();
        }
        if (loaded.latin == null) {
            loaded.latin = PsdLcdTypeface.systemLatin();
        }
        loaded.digits = PsdLcdTypeface.systemLight();
        cached = loaded;
        LteLog.LOGGER.info("[lte] psd lcd: cjk={} latin={} digits={} logo={}x{}", PsdLcdTypeface.name(loaded.cjk),
                PsdLcdTypeface.name(loaded.latin), PsdLcdTypeface.name(loaded.digits),
                loaded.logo == null ? "?" : String.valueOf(loaded.logo.getWidth()),
                loaded.logo == null ? "?" : String.valueOf(loaded.logo.getHeight()));
        return loaded;
    }

    private static BufferedImage image(final String name) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return null;
            }
            final Identifier id = new Identifier(PsdLcdObjects.NAMESPACE, DIR + name);
            final var resource = client.getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream in = resource.get().getInputStream()) {
                return ImageIO.read(in);
            }
        } catch (final Throwable t) {
            LteLog.LOGGER.warn("[lte] psd lcd: cannot load texture {}", name, t);
            return null;
        }
    }

    /** 资源包里的字体（mtr:xmlcd/fonts/…）。没有就 null。 */
    private static Font packFont(final int index) {
        if (index < 0 || index >= FONT_CANDIDATES.length) {
            return null;
        }
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return null;
            }
            final Identifier id = new Identifier("mtr", "xmlcd/" + FONT_CANDIDATES[index]);
            final var resource = client.getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream in = resource.get().getInputStream()) {
                return PsdLcdTypeface.fromBytes(in.readAllBytes());
            }
        } catch (final Throwable ignored) {
            return null;
        }
    }
}
