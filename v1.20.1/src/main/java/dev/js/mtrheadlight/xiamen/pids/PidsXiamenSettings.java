// 厦门 PIDS 的全局开关（LTE 仪表板 → 「厦门 PIDS」）：本地时间 / 即将进站 / 广告。
package dev.js.mtrheadlight.xiamen.pids;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 厦门 PIDS 的<b>全局</b>开关：一份文件、对所有 {@code mtrlte:pids_xiamen_metro} / {@code pids_xiamen_brt}
 * 同时生效。
 *
 * <h2>为什么不是每块屏各存一份</h2>
 * <p>这几个开关是「这个模组怎么显示」而不是「这块屏绑哪个站台」：
 * <ul>
 *   <li>{@link #localTime} —— 时钟 / 日期用<b>现实时间</b>还是世界的第几天；</li>
 *   <li>{@link #arriving} —— 到站前一分钟内写「即将进站」，还是继续写「X 秒 / X 分钟」；</li>
 *   <li>{@link #advertisements} —— 右侧广告区要不要画图（关掉只留空白底图）。
 *       开着时默认只画一张静态海报；勾了多张广告的分类才轮播。</li>
 *   <li>{@link #adOverrideImage()} —— <b>全局广告覆盖</b>：选了一张就所有 PIDS（地铁 + BRT）都只播它，
 *       不轮播；空 = 不覆盖，各屏按自己勾的分类播。</li>
 * </ul>
 * 站台 / 页码 / 广告序号是<b>每块屏</b>的（在方块实体里，跟着世界存档走），
 * 由刷子打开的 {@link PidsXiamenConfigScreen} 改。
 *
 * <h2>为什么单独一份 JSON，而不是写进 {@code HeadlightConfig}</h2>
 * {@code HeadlightConfig} 在 {@code common/}，四个版本共用 —— 只给 1.20.1 加字段会让别的版本跟着变。
 * 这份开关只在 1.20.1 的 PIDS 渲染里读，所以放在本包自己的文件里：
 * <pre>{@code
 *   config/lte_xiamen_pids.json
 *   { "localTime": true, "arriving": true, "advertisements": true,
 *     "adOverride": "mtrlte:advertisements/scenery1.png" }
 * }</pre>
 * {@code adOverride} 缺省 / 空串 = 不覆盖。存的是<b>图片 id</b>而不是下标：资源包换了、广告表顺序变了
 * 也不会指到另一张图上；那张图不在了就自动退回「不覆盖」（见 {@link PidsXiamenAds#adWithImage}）。
 *
 * <p>读写都吞异常：配置坏了 / 目录只读时退回默认值，绝不因为一个开关让屏幕不画。
 * 默认值 = 用户要的那套：本地时间开、即将进站开、广告开、不覆盖。
 *
 * <h2>服务端要知道吗</h2>
 * <p>不用。广告是纯客户端渲染（服务端只存每块屏勾了哪些分类 / 播到第几张，那些走
 * {@link PidsXiamenNet} 的 C2S），所以这份覆盖开关只在本机读写，不进任何包。
 */
public final class PidsXiamenSettings {

    /** 配置文件名（{@code config/} 下）。 */
    private static final String FILE_NAME = "lte_xiamen_pids.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ------------------------------------------------------------------ 开关

    /** 时钟 / 日期用本地时间（{@code true}）还是世界时间（{@code false}）。 */
    private static volatile boolean localTime = true;

    /** 到站前一分钟内显示「即将进站」。 */
    private static volatile boolean arriving = true;

    /** 画广告（轮播 + 手动切换）。 */
    private static volatile boolean advertisements = true;

    /**
     * 全局广告覆盖：选中的那张图的 id（{@code null} = 不覆盖）。
     *
     * <p>存图片 id 而不是下标 / 分类 id：广告表是资源包给的，顺序和条数随时会变。
     */
    private static volatile String adOverride;

    /** 读过盘没有 —— 只在第一次问开关时碰文件。 */
    private static volatile boolean loaded;

    private PidsXiamenSettings() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    // ------------------------------------------------------------------ 读

    public static boolean localTime() {
        load();
        return localTime;
    }

    public static boolean arriving() {
        load();
        return arriving;
    }

    public static boolean advertisements() {
        load();
        return advertisements;
    }

    public static void setLocalTime(final boolean on) {
        localTime = on;
        save();
    }

    public static void setArriving(final boolean on) {
        arriving = on;
        save();
    }

    public static void setAdvertisements(final boolean on) {
        advertisements = on;
        save();
    }

    // ------------------------------------------------------------------ 全局广告覆盖

    /**
     * 全局覆盖选中的图片 id（{@code null} / 空串 = 不覆盖）。
     *
     * <p>界面里拿它比对「哪一行是当前选中的」；渲染里不要直接用它，用 {@link #adOverride()}。
     */
    public static String adOverrideImage() {
        load();
        return adOverride;
    }

    /** 设定全局覆盖；传 {@code null} / 空串 = 回到「不覆盖」。立刻落盘。 */
    public static void setAdOverrideImage(final String image) {
        adOverride = image == null || image.isEmpty() ? null : image;
        save();
    }

    /**
     * 全局覆盖的那张广告（{@code null} = 不覆盖 / 这张图已经不在资源包里）。
     *
     * <p>每次现查 {@link PidsXiamenAds#allAds()}（有缓存，代价是一次线性扫），所以换资源包、
     * 图被删掉都会立刻生效，不会指着一张不存在的贴图。
     */
    public static PidsXiamenAds.Ad adOverride() {
        load();
        return PidsXiamenAds.adWithImage(adOverride);
    }

    /**
     * 一块屏这一帧实际要播的广告表。
     *
     * <p>选了全局覆盖 → 只返回那一张（单张 = 轮播周期等于它自己的标语长度，等于不轮播）；
     * 没选 / 选中的图已经没了 → 原样返回这块屏自己的（{@code PidsXiamenBlockEntity#getAds()}）。
     */
    public static java.util.List<PidsXiamenAds.Ad> applyAdOverride(
            final java.util.List<PidsXiamenAds.Ad> own) {
        final PidsXiamenAds.Ad ad = adOverride();
        return ad == null ? own : java.util.Collections.singletonList(ad);
    }

    /** 一次性读盘（第一次问开关时）。 */
    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            final Path path = file();
            if (!Files.isRegularFile(path)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                final JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json == null) {
                    return;
                }
                localTime = bool(json, "localTime", localTime);
                arriving = bool(json, "arriving", arriving);
                advertisements = bool(json, "advertisements", advertisements);
                adOverride = string(json, "adOverride", adOverride);
            }
            LteLog.LOGGER.info("[lte] xiamen pids settings: localTime={} arriving={} ads={} adOverride={}",
                    Boolean.valueOf(localTime), Boolean.valueOf(arriving), Boolean.valueOf(advertisements),
                    adOverride == null ? "-" : adOverride);
        } catch (Throwable t) {
            // 文件写坏 / 没权限：保持默认值，别把屏幕拖垮。
            LteLog.LOGGER.warn("[lte] xiamen pids settings: could not read {}", FILE_NAME, t);
        }
    }

    private static boolean bool(final JsonObject json, final String key, final boolean fallback) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive()
                    ? json.get(key).getAsBoolean() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 字符串字段；缺 / null / 空串都算 {@code fallback}（这里是“不覆盖”）。 */
    private static String string(final JsonObject json, final String key, final String fallback) {
        try {
            if (!json.has(key) || json.get(key).isJsonNull()) {
                return fallback;
            }
            final String value = json.get(key).getAsString();
            return value == null || value.isBlank() ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ 写

    /** 立刻落盘（开关点一下就写，不等「完成」）。 */
    public static void save() {
        try {
            final Path path = file();
            final Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            final JsonObject json = new JsonObject();
            json.addProperty("localTime", localTime);
            json.addProperty("arriving", arriving);
            json.addProperty("advertisements", advertisements);
            if (adOverride != null && !adOverride.isBlank()) {
                json.addProperty("adOverride", adOverride);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids settings: could not write {}", FILE_NAME, t);
        }
    }
}
