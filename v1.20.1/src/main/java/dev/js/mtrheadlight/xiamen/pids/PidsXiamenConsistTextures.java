// 编组条叠加层的 MC 胶水：底图 / 卡片素材 → PidsXiamenConsist（Java2D）→ 动态贴图。
//
// 分工：
//   · PidsXiamenConsist 只会画**图形**（背景 + 卡片 + 雪花），一个字的文字都没有；
//   · 这里负责「素材从哪来、节数变了怎么办、F3+T 之后怎么办、什么时候回收」；
//   · 「N车」的文字由 PidsXiamenRenderer 用 PidsXiamenText（= 旧版 PIDS / 天津同一套
//     TextRenderer 顶点级绘制）画，位置读 PidsXiamenConsist#layout。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * 「节数 → 一张动态贴图」。
 *
 * <h2>三条约束</h2>
 * <ol>
 *   <li><b>内容哈希去重</b>：节数没变就一直复用同一张 GL 贴图（{@link PidsXiamenConsist#hash}）。
 *       6 节 → 2 节才重画、重传；</li>
 *   <li><b>F3+T / 换资源包 → 重建</b>：{@link #invalidate()} 由 {@code PidsXiamenClient}
 *       的资源重载监听调用，把素材缓存清掉、代数 +1（新键 = 新贴图）；即便监听没挂上，
 *       {@link #stillRegistered} 也会发现 TextureManager 里那份已经不是我们的实例，
 *       于是重建（否则屏上会变成「找不到贴图」的紫黑格）；</li>
 *   <li><b>随 BE 回收</b>：键里不含方块实体（同节数的屏共用一张），30 秒没人用就注销 ——
 *       拆屏 / 走远 / 换维度都不会泄漏。</li>
 * </ol>
 *
 * <p><b>采样</b>：注册完显式 {@code setFilter(false, false)}（NEAREST / 无 mipmap）。
 * 叠加层本身就是 4× 直接绘制的，NEAREST 上屏不会再把边缘抹糊。
 */
final class PidsXiamenConsistTextures {

    /** 多久没人渲染就注销（ms）。 */
    private static final long TTL_MS = 30_000L;

    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    /** 素材代数：资源重载 +1，贴图键跟着换（旧的自然超时回收）。 */
    private static int generation;

    private static BufferedImage band;
    private static BufferedImage card;
    private static BufferedImage flake;

    private static boolean warned;

    private PidsXiamenConsistTextures() {
    }

    /** 一张动态贴图 + 它当前的内容哈希。 */
    private static final class Entry {
        Identifier id;
        NativeImageBackedTexture texture;
        long hash = Long.MIN_VALUE;
        long lastUse;
    }

    /**
     * 取（必要时画一张新的）这个节数对应的叠加层贴图。
     *
     * @param cars 实际车厢数；{@code <= 0} = 读不到编组 → 一张<b>全透明</b>的贴图
     *             （编组条整块空着，底图的青蓝条区原样透出）
     * @return 贴图 id，或 {@code null}（素材缺失 / 不在客户端线程 / GL 没就绪）
     */
    static Identifier texture(final int cars) {
        final int n = Math.max(0, Math.min(PidsXiamenConsist.MAX_CARS, cars));
        final BufferedImage bandImage = n > 0 ? asset(BAND_ID) : null;
        final BufferedImage cardImage = n > 0 ? asset(CARD_ID) : null;
        final BufferedImage flakeImage = n > 0 ? asset(FLAKE_ID) : null;
        if (n > 0 && (bandImage == null || cardImage == null || flakeImage == null)) {
            return null;
        }

        final String key = "consist|" + generation + "|" + n;
        final long hash = PidsXiamenConsist.hash(n, bandImage, cardImage, flakeImage);
        Entry entry = ENTRIES.get(key);
        if (entry != null && stillRegistered(entry) && entry.hash == hash) {
            entry.lastUse = System.currentTimeMillis();
            return entry.id;
        }
        if (entry != null) {
            release(key);
        }
        try {
            final int w = PidsXiamenConsist.overlayWidth();
            final int h = PidsXiamenConsist.overlayHeight();
            final BufferedImage image = PidsXiamenConsist.paint(bandImage, cardImage, flakeImage, n);
            if (image == null) {
                return null;
            }
            final NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, true);
            final NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);
            // NEAREST + 无 mipmap：叠加层是 4× 直接画的，上屏不要再被线性插值糊一层
            texture.setFilter(false, false);
            final Identifier id = MinecraftClient.getInstance().getTextureManager()
                    .registerDynamicTexture("pids_consist_" + generation + "_" + n, texture);

            final Entry created = new Entry();
            created.id = id;
            created.texture = texture;
            created.hash = hash;
            created.lastUse = System.currentTimeMillis();
            upload(created, image);
            ENTRIES.put(key, created);
            return id;
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.warn("[lte] xiamen pids consist: cannot build the overlay texture", t);
            }
            return null;
        }
    }

    /** 位图 → NativeImage（ABGR 打包，和 PsdLcdTextures 同一套字节序处理）。 */
    private static void upload(final Entry entry, final BufferedImage image) {
        final NativeImage target = entry.texture.getImage();
        if (target == null || target.getWidth() != image.getWidth()
                || target.getHeight() != image.getHeight()) {
            return;
        }
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            for (int x = 0; x < w; x++) {
                final int argb = pixels[row + x];
                // NativeImage 的 RGBA 是字节序 R,G,B,A → 小端整数正好是 ABGR
                target.setColor(x, y, ((argb & 0xFF00FF00)) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF));
            }
        }
        entry.texture.upload();
    }

    // ------------------------------------------------------------------ 素材

    private static final Identifier BAND_ID =
            new Identifier("mtrlte", "textures/gui/pids/consist_band.png");
    private static final Identifier CARD_ID =
            new Identifier("mtrlte", "textures/gui/pids/consist_card.png");
    private static final Identifier FLAKE_ID =
            new Identifier("mtrlte", "textures/gui/pids/consist_flake.png");

    /**
     * 读一份素材（按包栈里的实际文件，资源包可以覆盖）。
     *
     * <p>只读一次、长期持有 {@link BufferedImage}：底带 333×33、卡片 41×20、雪花 7×7，
     * 解码后一共十几 KB，但也不能每帧 {@code ImageIO.read}。{@link #invalidate()} 会把它们清空。
     */
    private static BufferedImage asset(final Identifier id) {
        if (id == BAND_ID) {
            if (band == null) {
                band = read(id);
            }
            return band;
        }
        if (id == CARD_ID) {
            if (card == null) {
                card = read(id);
            }
            return card;
        }
        if (flake == null) {
            flake = read(id);
        }
        return flake;
    }

    private static BufferedImage read(final Identifier id) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            final ResourceManager manager = client == null ? null : client.getResourceManager();
            if (manager == null) {
                return null;
            }
            final Optional<Resource> resource = manager.getResource(id);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream in = resource.get().getInputStream()) {
                return ImageIO.read(in);
            }
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.warn("[lte] xiamen pids consist: cannot read {}", id, t);
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ 生命周期

    /** 这张贴图还挂在 TextureManager 上吗（F3+T 会把动态贴图一起关掉）。 */
    private static boolean stillRegistered(final Entry entry) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            return client != null && client.getTextureManager().getTexture(entry.id) == entry.texture;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /** 回收长时间没人用的贴图（渲染器每帧调一次，很便宜）。 */
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

    /** 资源重载（F3+T / 换包）：素材缓存作废、代数 +1，下一帧按新素材重画。 */
    static void invalidate() {
        band = null;
        card = null;
        flake = null;
        generation++;
        for (final Entry entry : ENTRIES.values()) {
            destroy(entry);
        }
        ENTRIES.clear();
    }

    private static void release(final String key) {
        final Entry entry = ENTRIES.remove(key);
        if (entry != null) {
            destroy(entry);
        }
    }

    private static void destroy(final Entry entry) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (entry.id != null && client != null) {
                client.getTextureManager().destroyTexture(entry.id);
            }
        } catch (final Throwable ignored) {
            // 客户端已经在关：GL 上下文没了，交给进程回收。
        }
    }
}
