// PSD-LCD 挂板的动态贴图缓存：Java2D 画好的整屏 → NativeImage → 注册成动态贴图。
//
// 为什么要有这一层：整屏是一张 Java2D 位图（见 PsdLcdPainter），游戏里要把它当贴图贴在
// 面板上。这里负责「一个方块实体一张动态贴图」，并且做到：
//   · 内容没变就不重画（时钟 / 倒计时都是分钟级，一分钟一次就够）；
//   · 重画时逐像素**只写变化的像素**（4 段屏 4160×320 = 133 万像素，全写会掉帧）；
//   · 30 秒没人用的贴图自动注销，拆屏 / 换维度不会泄漏。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** 方块实体 ↔ 动态贴图。键是「维度 + 锚点坐标 + 段数」。 */
final class PsdLcdTextures {

    /** 多久没人渲染就注销（ms）。拆屏 / 走远之后贴图自然回收。 */
    private static final long TTL_MS = 30_000L;

    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    private static boolean warned;

    private PsdLcdTextures() {
    }

    /** 一张动态贴图 + 上一帧的位图（用来做像素差分）。 */
    static final class Entry {
        Identifier id;
        NativeImageBackedTexture texture;
        int width;
        int height;
        String hash = "";
        BufferedImage last;
        /** 滚动标语那张小贴图上一帧画到第几像素（用它决定「这一帧要不要重画 / 重传」）。 */
        int lastPhasePx = Integer.MIN_VALUE;
        long lastUse;
    }

    /**
     * 取（或新建）这个键对应的动态贴图。尺寸变了就重建。
     *
     * @return null = 这次拿不到（不在客户端线程 / GL 没就绪），调用方直接跳过绘制
     */
    static Entry acquire(final String key, final int width, final int height) {
        final long now = System.currentTimeMillis();
        final Entry existing = ENTRIES.get(key);
        if (existing != null && existing.width == width && existing.height == height
                && stillRegistered(existing)) {
            existing.lastUse = now;
            return existing;
        }
        if (existing != null) {
            release(key);
        }
        try {
            final NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, true);
            final NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
            final Identifier id = MinecraftClient.getInstance().getTextureManager()
                    .registerDynamicTexture("psd_lcd_" + key.replaceAll("[^A-Za-z0-9_]", "_"), texture);
            final Entry entry = new Entry();
            entry.id = id;
            entry.texture = texture;
            entry.width = width;
            entry.height = height;
            entry.lastUse = now;
            ENTRIES.put(key, entry);
            return entry;
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.warn("[lte] psd lcd: cannot create dynamic texture", t);
            }
            return null;
        }
    }

    /**
     * 把位图刷进贴图并上传。只写和上一帧不同的像素 —— 静止画面（只换了分钟数字）
     * 每次只有几千个像素要写。
     */
    static void upload(final Entry entry, final BufferedImage image) {
        if (entry == null || image == null || entry.texture == null) {
            return;
        }
        final NativeImage target = entry.texture.getImage();
        if (target == null || target.getWidth() != image.getWidth()
                || target.getHeight() != image.getHeight()) {
            return;
        }
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        final BufferedImage previous = entry.last;
        final boolean full = previous == null || previous.getWidth() != w || previous.getHeight() != h;
        final int[] old = full ? null : previous.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            for (int x = 0; x < w; x++) {
                final int argb = pixels[row + x];
                if (!full && old[row + x] == argb) {
                    continue;
                }
                // NativeImage 的 RGBA 是字节序 R,G,B,A → 小端整数正好是 ABGR
                target.setColor(x, y, ((argb & 0xFF00FF00)) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF));
            }
        }
        entry.texture.upload();
        entry.last = image;
    }

    /**
     * 这张贴图还挂在 TextureManager 上吗？F3+T 重新加载资源时管理器会把动态贴图一起关掉，
     * 那时旧的 GL id 已经失效，必须重建，否则整块屏变成「找不到贴图」的紫色。
     */
    private static boolean stillRegistered(final Entry entry) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            return client != null && client.getTextureManager().getTexture(entry.id) == entry.texture;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /**
     * 内容变了（换了广告 / 换了标语）：滚动小贴图必须重画一次。
     *
     * <p>为什么需要它：小贴图平时靠「相位像素变了」才重画，换了标语但相位恰好没变
     * （例如都停在 phase≈0）时就会继续显示上一句。
     */
    static void invalidateTicker(final String key) {
        final Entry entry = ENTRIES.get(key + "|ticker");
        if (entry != null) {
            entry.lastPhasePx = Integer.MIN_VALUE;
        }
    }

    /** 回收长时间没人用的贴图（每帧调一次也没关系，很便宜）。 */
    static void sweep() {
        if (ENTRIES.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            final Entry entry = it.next().getValue();
            if (now - entry.lastUse > TTL_MS) {
                it.remove();
                destroy(entry);
            }
        }
    }

    /** 注销一条（尺寸变了 / 超时）。 */
    private static void release(final String key) {
        final Entry entry = ENTRIES.remove(key);
        if (entry != null) {
            destroy(entry);
        }
    }

    private static void destroy(final Entry entry) {
        try {
            if (entry.id != null && MinecraftClient.getInstance() != null) {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(entry.id);
            }
        } catch (final Throwable ignored) {
            // 客户端已经在关：GL 上下文没了，交给进程回收。
        }
    }

    /** 断线 / 切世界时清干净（渲染器在客户端初始化时挂一次）。 */
    static void clear() {
        for (final Entry entry : ENTRIES.values()) {
            destroy(entry);
        }
        ENTRIES.clear();
    }
}
