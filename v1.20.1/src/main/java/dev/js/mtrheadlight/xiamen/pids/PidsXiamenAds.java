// 厦门 PIDS 的广告配置：结构、读取方式与「分类」语义全部照抄天津地铁。
package dev.js.mtrheadlight.xiamen.pids;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 厦门 PIDS 的广告表 —— <b>与天津地铁的 {@code pids_tianjin_ads.json} 完全同构</b>。
 *
 * <h2>文件位置（照抄天津）</h2>
 * <p>天津把广告表放在<b>资源包内</b>、<b>模组命名空间根下</b>：
 * <pre>
 *   assets/tjmetro/pids_tianjin_ads.json          ← 表
 *   assets/tjmetro/advertisements/*.png           ← 图
 * </pre>
 * 所以这里一一对应：
 * <pre>
 *   assets/mtrlte/pids_xiamen_ads.json
 *   assets/mtrlte/advertisements/scenery1.png … scenery3.png
 * </pre>
 *
 * <h2>JSON 结构（字段名与天津完全相同）</h2>
 * <pre>{@code
 * {
 *   "_comment_aspectRatio": 1.82,          // 图片宽高比，仅注释用
 *   "mtrlte": {                            // ← 顶层 key = 一个「广告分类」
 *     "name": "advertisement.mtrlte.name",         // 语言键
 *     "description": "advertisement.mtrlte.description",
 *     "color": "009845",                           // 6 位 hex，界面上用的分类色
 *     "advertisements": [
 *       { "image": "mtrlte:advertisements/scenery1.png",
 *         "text":  "advertisement.mtrlte.scenery1",  // 语言键（不是字面量）
 *         "url":   null }                            // 可空；非空时屏上画一个可点的小标
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <h2>「分类」= 顶层 key（和天津一样，可以有好几个）</h2>
 * <p>天津的 {@code CustomResourcesMixin} 在资源重载时用
 * {@code resourceManager.getAllResources(new Identifier("tjmetro", "pids_tianjin_ads.json"))}
 * 把<b>整个包栈</b>里的这份表都读一遍，每个顶层 key 变成一个 {@code Category}，
 * 分类 id = {@code key.hashCode()}（长整型，写进方块实体）；同名 key 在多个包里出现时
 * 广告会<b>累加</b>，名字/描述以先读到的为准。
 *
 * <p>这里逐行照搬：{@link #categories()} 也是 {@code getAllResources} + 累加，
 * 于是「装一个资源包 → 多几个可勾选的广告分类」这件事不用写任何额外代码。
 * 天津在方块实体的 {@code categories} 里存玩家勾了哪些；厦门同名字段见
 * {@link PidsXiamenBlockEntity#getAdCategories()}。
 *
 * <p>没勾过任何分类时落到<b>默认分类</b>（{@value #NAMESPACE}）：默认分类里只有<b>一张</b>
 * 静态海报（{@code xiamen_scenery.png}，标语「欢迎乘坐厦门地铁」）—— 一张 = 轮播下标恒为 0，
 * 所以默认不轮播。玩家在「选择广告」里勾上 {@code xiamen_ads}（三张风景图）以后才开始转。
 *
 * <h2>资源包适配：天津地铁的广告表也直接读</h2>
 * <p>除了自己的 {@link #CONFIG_ID}，还会读 {@link #TIANJIN_CONFIG_ID}
 * （{@code assets/tjmetro/pids_tianjin_ads.json}）。给天津地铁做的广告包（例如「南北组」那套）
 * 只带这张表，装上去就能在我们的「选择广告」里勾到，图片按它自己的命名空间采样。
 * 同 key 的分类会合并，并按「图片 + 文案」去重，所以资源包再补一份我们的表也不会重样。
 *
 * <h2>滚动标语</h2>
 * <p>天津<b>没有</b>单独的 ticker 字段：屏下那条滚动文字就是「当前这张广告的 {@code text}」。
 * 厦门自己有 {@code pids_xiamen_ticker.json}（见 {@link PidsXiamenTicker}），这里不掺和。
 */
public final class PidsXiamenAds {

    /** 广告表的资源 id：{@code assets/mtrlte/pids_xiamen_ads.json}。 */
    public static final Identifier CONFIG_ID = new Identifier("mtrlte", "pids_xiamen_ads.json");

    /**
     * 天津地铁的广告表：{@code assets/tjmetro/pids_tianjin_ads.json}。
     *
     * <p><b>资源包适配</b>：市面上给天津地铁做的广告资源包（例如「南北组 …」那套）只带这张表，
     * 里面的分类 / 图片 / 文案一应俱全。这里把整张表也读一遍并进同一个分类表 ——
     * 于是<b>不用改资源包</b>，装上去在「选择广告」里就能勾到那些广告，图片直接按它自己的
     * {@code tjmetro:advertisements/*.png} 采样。同名分类（key 相同）按图片 + 文案去重，
     * 所以就算资源包以后又补了一份 {@code assets/mtrlte/pids_xiamen_ads.json} 也不会出现两遍。
     */
    public static final Identifier TIANJIN_CONFIG_ID = new Identifier("tjmetro", "pids_tianjin_ads.json");

    /** 默认分类的 key（= 命名空间，也是广告图 id 的命名空间）。 */
    public static final String NAMESPACE = "mtrlte";

    /** 默认分类的 id（{@code key.hashCode()}，与天津同一套算法）。 */
    public static final long DEFAULT_CATEGORY = (long) NAMESPACE.hashCode();

    /** 一条广告。{@code text} 是已经解析好的语言组件（{@code getString()} 得到译文）。 */
    public record Ad(Identifier image, Text text, String url) {
    }

    /**
     * 一个广告分类（= 广告表里的一个顶层 key）。
     *
     * @param id          分类 id（{@code key.hashCode()}，方块实体里存的就是它）
     * @param key         顶层 key（命名空间名，日志 / 查重用）
     * @param name        显示名（语言键解析结果）
     * @param description 描述（语言键解析结果）
     * @param color       分类色（ARGB）
     * @param ads         这个分类下的广告，按文件里的顺序
     */
    public record Category(long id, String key, Text name, Text description, int color, List<Ad> ads) {
    }

    /** 资源重载后置空，下次渲染重新读 —— 换资源包立刻生效。 */
    private static volatile List<Category> cache;

    /** 配置里的 {@code _comment_aspectRatio}（默认 1.82，与天津那份一致）。 */
    private static volatile float aspectRatio = 1.82F;

    private PidsXiamenAds() {
    }

    /** 广告图的宽高比（只给 {@link PidsXiamenLayout#AD_KEEP_ASPECT} 用）。 */
    public static float aspectRatio() {
        return aspectRatio;
    }

    /** 资源重载时调（{@link PidsXiamenClient} 注册的重载监听器）。 */
    public static void invalidate() {
        cache = null;
    }

    // ------------------------------------------------------------------ 分类

    /** 包栈里所有广告分类（低优先级在前，同名 key 已合并）。 */
    public static List<Category> categories() {
        List<Category> local = cache;
        if (local != null) {
            return local;
        }
        local = load();
        cache = local;
        return local;
    }

    /** 按 id 找分类；找不到返回 {@code null}。 */
    public static Category category(final long id) {
        for (final Category category : categories()) {
            if (category.id() == id) {
                return category;
            }
        }
        return null;
    }

    /** 默认分类（{@code mtrlte}）；表里没有这个 key 时退回第一项。 */
    public static Category defaultCategory() {
        final List<Category> list = categories();
        if (list.isEmpty()) {
            return null;
        }
        for (final Category category : list) {
            if (category.id() == DEFAULT_CATEGORY) {
                return category;
            }
        }
        return list.get(0);
    }

    /**
     * 这块屏要播的广告：勾选的分类按顺序拼接。
     *
     * @param selected 方块实体里存的分类 id；{@code null} / 空 = 默认分类那一套
     */
    public static List<Ad> adsFor(final long[] selected) {
        if (selected == null || selected.length == 0) {
            final Category fallback = defaultCategory();
            return fallback == null ? Collections.emptyList() : fallback.ads();
        }
        final List<Ad> out = new ArrayList<>();
        for (final long id : selected) {
            final Category category = category(id);
            if (category != null) {
                out.addAll(category.ads());
            }
        }
        if (out.isEmpty()) {
            // 勾的都在资源包里消失了（换了包）：回到默认那一套，别让屏上一片空白。
            final Category fallback = defaultCategory();
            return fallback == null ? Collections.emptyList() : fallback.ads();
        }
        return Collections.unmodifiableList(out);
    }

    /** 默认那一套广告（等价于 {@code adsFor(null)}）。 */
    public static List<Ad> ads() {
        return adsFor(null);
    }

    public static int count() {
        return ads().size();
    }

    /** 第 {@code index} 张默认分类的广告（自动取模，负数也能用）。 */
    public static Ad get(final int index) {
        final List<Ad> list = ads();
        if (list.isEmpty()) {
            return null;
        }
        final int i = ((index % list.size()) + list.size()) % list.size();
        return list.get(i);
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 读整个包栈里的每一份广告表（天津的 {@code getAllResources} 做法）。
     *
     * <p>读两张：自己的 {@link #CONFIG_ID}，以及天津地铁的 {@link #TIANJIN_CONFIG_ID}
     * （见后者的注释：这就是「南北组」那类资源包不用改就能用的原因）。
     *
     * <p>{@code getAllResources} 在「一份都没有」时会抛 {@code FileNotFoundException}，
     * 所以每个 id 各自 try/catch：读不到就跳过，两张都读不到才退回内置的兜底。
     */
    private static List<Category> load() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return fallback();
        }
        final Map<Long, Category> merged = new LinkedHashMap<>();
        int files = 0;
        for (final Identifier id : new Identifier[]{CONFIG_ID, TIANJIN_CONFIG_ID}) {
            try {
                for (final var resource : client.getResourceManager().getAllResources(id)) {
                    files++;
                    try (InputStream in = resource.getInputStream()) {
                        parseInto(merged, JsonParser.parseReader(
                                new InputStreamReader(in, StandardCharsets.UTF_8)));
                    } catch (Throwable t) {
                        LteLog.LOGGER.warn("[lte] xiamen pids ads: bad file inside package ({}){}",
                                resource.getResourcePackName(), t);
                    }
                }
            } catch (Throwable t) {
                // 这个包栈里没有这张表（或写坏了）不是错误：另一张表 / 兜底接着用。
                LteLog.LOGGER.debug("[lte] could not read {} - skipping", id, t);
            }
        }
        if (merged.isEmpty()) {
            return fallback();
        }
        final List<Category> out = Collections.unmodifiableList(new ArrayList<>(merged.values()));
        LteLog.LOGGER.info("[lte] xiamen pids ads: {} categor(ies) from {} file(s){}",
                out.size(), files, describe(out));
        return out;
    }

    /**
     * 一次性诊断：每个分类有几张、每张图在资源包里找不找得到。
     *
     * <p>广告图走 {@code MoreRenderLayers.getLight(id, false)}（= {@code RenderLayer.getBeaconBeam}），
     * 那一层把 id 直接交给 {@code ResourceTexture}，也就是说 <b>id 要写全 {@code assets/<ns>/} 之后的路径</b>
     * （天津的 {@code tjmetro:advertisements/banner.png} 对应 {@code assets/tjmetro/advertisements/banner.png}，
     * 我们同构）。资源找不到时 MC 只会绑一个坏贴图、不会抛异常，屏上就是一块黑 ——
     * 所以这里主动打一行，省得以后再靠猜。
     */
    private static String describe(final List<Category> categories) {
        final MinecraftClient client = MinecraftClient.getInstance();
        final StringBuilder out = new StringBuilder();
        for (final Category category : categories) {
            out.append(' ').append(category.key()).append('(').append(category.ads().size()).append(')');
            for (final Ad ad : category.ads()) {
                String state = "?";
                try {
                    if (client != null) {
                        state = client.getResourceManager().getResource(ad.image()).isPresent()
                                ? "ok" : "MISSING";
                    }
                } catch (Throwable ignored) {
                    state = "?";
                }
                out.append(' ').append(ad.image()).append('[').append(state).append(']');
            }
        }
        return out.toString();
    }

    /**
     * 解析一份表并并进 {@code out}：顶层 key（跳过 {@code _} 开头的注释字段）= 一个分类。
     *
     * <p>同名 key 在别的包里已经出现过时<b>只累加广告</b>（名字 / 描述 / 颜色以先读到的为准）——
     * 与天津 {@code CustomResourcesMixin} 的 {@code CATEGORIES.containsKey(id)} 分支一模一样。
     */
    static void parseInto(final Map<Long, Category> out, final JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return;
        }
        final JsonObject top = root.getAsJsonObject();
        readAspectRatio(top);
        for (final Map.Entry<String, JsonElement> entry : top.entrySet()) {
            final String key = entry.getKey();
            if (key == null || key.isBlank() || key.startsWith("_")) {
                continue;
            }
            final JsonElement sectionElement = entry.getValue();
            if (sectionElement == null || !sectionElement.isJsonObject()) {
                continue;
            }
            final JsonObject section = sectionElement.getAsJsonObject();
            final JsonElement arrayElement = section.get("advertisements");
            if (arrayElement == null || !arrayElement.isJsonArray()) {
                LteLog.LOGGER.warn("[lte] xiamen pids ads: bad category {} in {}: missing advertisement array. Skipping!",
                        key, CONFIG_ID);
                continue;
            }
            final long id = (long) key.hashCode();
            final Category existing = out.get(id);
            final List<Ad> ads = existing == null ? new ArrayList<>() : new ArrayList<>(existing.ads());
            // 同名分类（另一张表 / 另一个包）只追加「没见过」的广告：图片 + 文案都相同就跳过。
            // 这样资源包同时带 tjmetro 表和 mtrlte 表、或者自己的表被两个包各写一份，都不会重复。
            for (final Ad ad : parseAds(key, arrayElement.getAsJsonArray())) {
                boolean seen = false;
                for (final Ad kept : ads) {
                    if (sameAd(kept, ad)) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    ads.add(ad);
                }
            }
            out.put(id, new Category(id, key,
                    existing == null ? translatable(section, "name", key) : existing.name(),
                    existing == null ? translatable(section, "description", key) : existing.description(),
                    existing == null ? color(section) : existing.color(),
                    Collections.unmodifiableList(ads)));
        }
    }

    private static void readAspectRatio(final JsonObject top) {
        try {
            final JsonElement aspect = top.get("_comment_aspectRatio");
            if (aspect != null && aspect.getAsJsonPrimitive().isNumber()) {
                aspectRatio = aspect.getAsFloat();
            }
        } catch (Throwable ignored) {
            // 注释字段写坏了不影响广告表。
        }
    }

    private static List<Ad> parseAds(final String key, final JsonArray array) {
        final List<Ad> out = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            try {
                final JsonElement entryElement = array.get(i);
                if (entryElement == null || !entryElement.isJsonObject()) {
                    continue;
                }
                final JsonObject entry = entryElement.getAsJsonObject();
                final JsonElement imageElement = entry.get("image");
                if (imageElement == null || imageElement.isJsonNull()) {
                    continue;
                }
                final Identifier image = new Identifier(imageElement.getAsString());
                final JsonElement textElement = entry.get("text");
                final Text text = textElement == null || textElement.isJsonNull()
                        ? Text.empty() : Text.translatable(textElement.getAsString());
                final JsonElement urlElement = entry.get("url");
                final String url = urlElement == null || urlElement.isJsonNull()
                        || urlElement.getAsString().isEmpty() ? null : urlElement.getAsString();
                out.add(new Ad(image, text, url));
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] xiamen pids ads: bad advertisement #{} in category {} of {}",
                        i, key, CONFIG_ID);
            }
        }
        return out;
    }

    /** {@code name} / {@code description}：语言键 → 文本；缺字段就用 key 本身（天津的写法）。 */
    private static Text translatable(final JsonObject section, final String field, final String fallback) {
        try {
            final JsonElement element = section.get(field);
            if (element != null && element.isJsonPrimitive()) {
                return Text.translatable(element.getAsString());
            }
        } catch (Throwable ignored) {
            // 落到 fallback
        }
        return Text.literal(fallback);
    }

    /** {@code color}：6 位 hex → ARGB；写坏 / 缺字段用默认绿。 */
    private static int color(final JsonObject section) {
        try {
            final JsonElement element = section.get("color");
            if (element != null && element.isJsonPrimitive()) {
                final String hex = element.getAsString().replace("#", "").trim();
                return 0xFF000000 | (Integer.parseInt(hex, 16) & 0xFFFFFF);
            }
        } catch (Throwable ignored) {
            // 落到默认色
        }
        return 0xFF009845;
    }

    /** 两张广告是不是同一张（图片 + 文案），用于跨表去重。 */
    private static boolean sameAd(final Ad a, final Ad b) {
        if (a == null || b == null || a.image() == null || b.image() == null) {
            return a == b;
        }
        if (!a.image().equals(b.image())) {
            return false;
        }
        final String ta = a.text() == null ? "" : a.text().getString();
        final String tb = b.text() == null ? "" : b.text().getString();
        return ta.equals(tb);
    }

    /** 资源包里的表彻底不可用时的兜底：默认那张厦门风景图 + 欢迎语。 */
    private static List<Category> fallback() {
        final List<Ad> ads = new ArrayList<>(1);
        ads.add(new Ad(new Identifier(NAMESPACE, "advertisements/xiamen_scenery.png"),
                Text.translatable("ticker.mtrlte.ad_welcome"), null));
        final Category only = new Category(DEFAULT_CATEGORY, NAMESPACE,
                Text.translatable("advertisement.mtrlte.name"),
                Text.translatable("advertisement.mtrlte.description"),
                0xFF009845, Collections.unmodifiableList(ads));
        return Collections.singletonList(only);
    }
}
