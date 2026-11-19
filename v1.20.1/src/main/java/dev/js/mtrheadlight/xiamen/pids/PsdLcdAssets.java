// PSD-LCD 的素材与字体：贴图从模组自己的资源里读，字体走 PsdLcdTypeface（= 厦门 PIDS 那一套）。
//
// **不再读资源包里的字体**（旧的 mtr:xmlcd/fonts/{misans.otf,roboto.ttf} 已删除）：
// 这块屏和普通 PIDS 挂在同一个站台上，字体必须一致，见 PsdLcdTypeface 的类注释。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** 一次性加载、长期缓存（素材是模组自带的，不随资源包变）。 */
final class PsdLcdAssets {

    private static final String DIR = "textures/gui/psd_lcd/";

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
        // 字体：和厦门普通 PIDS 完全同一套（系统黑体，中文 BOLD / 英文 PLAIN）
        loaded.cjk = PsdLcdTypeface.cjk();
        loaded.latin = PsdLcdTypeface.latin();
        loaded.digits = PsdLcdTypeface.digits();
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
            return load(id);
        } catch (final Throwable t) {
            LteLog.LOGGER.warn("[lte] psd lcd: cannot load texture {}", name, t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 广告图（PIDS 那套）

    /**
     * 广告图缓存：键 = 图片 Identifier，最多留 8 张。
     *
     * <p>广告区要画的是**同一套**厦门 PIDS 广告（{@link PidsXiamenAds}），但这里是 Java2D 离屏绘制，
     * 拿不到 MC 的 {@code ResourceTexture}，所以按 id 把 PNG 读成 {@link BufferedImage}。
     * 读不到（资源包缺图 / 不是 PNG）返回 {@code null}，画家那边退回洋红占位块。
     */
    private static final java.util.Map<String, BufferedImage> AD_CACHE_MAP =
            new java.util.LinkedHashMap<String, BufferedImage>(8, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(final java.util.Map.Entry<String, BufferedImage> eldest) {
                    return size() > 8;
                }
            };

    /** 读一张广告图（按 id 缓存）；{@code id} 为 null 或读不到返回 null。 */
    static BufferedImage ad(final Identifier id) {
        if (id == null) {
            return null;
        }
        final String key = id.toString();
        synchronized (AD_CACHE_MAP) {
            if (AD_CACHE_MAP.containsKey(key)) {
                return AD_CACHE_MAP.get(key);
            }
            final BufferedImage loaded = load(id);
            AD_CACHE_MAP.put(key, loaded);
            return loaded;
        }
    }

    /** 资源重载时清空广告图缓存（换资源包立刻生效）。 */
    static void invalidateAds() {
        synchronized (AD_CACHE_MAP) {
            AD_CACHE_MAP.clear();
        }
    }

    private static BufferedImage load(final Identifier id) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return null;
            }
            final var resource = client.getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                LteLog.LOGGER.debug("[lte] psd lcd: ad image missing {}", id);
                return null;
            }
            try (InputStream in = resource.get().getInputStream()) {
                return ImageIO.read(in);
            }
        } catch (final Throwable t) {
            LteLog.LOGGER.warn("[lte] psd lcd: cannot load ad image {}", id, t);
            return null;
        }
    }
}
