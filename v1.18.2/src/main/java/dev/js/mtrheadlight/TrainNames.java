package dev.js.mtrheadlight;

import net.minecraft.text.TranslatableText;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 车型的「游戏内名字」。
 *
 * <h2>名字从哪来（实证）</h2>
 * MTR 只从资源包的 <b>{@code assets/mtr/mtr_custom_resources.json}</b> 读自定义车
 * （{@code CustomResources#reload} 里写死的资源 id 是 {@code mtr:mtr_custom_resources.json}，
 * 没有第二个路径）。文件结构：
 *
 * <pre>
 * {
 *   "custom_trains": {
 *     "sp1900": {                      &lt;- 这里的 key 是包内车型名
 *       "name": "九广 SP1900",          &lt;- 车厂里显示的那一行
 *       "base_train_type": "sp1900",
 *       "texture_id": "mtr:textures/entity/sp1900",
 *       "model": "mtr:models/train/sp1900.json",
 *       "model_properties": "..."
 *     }
 *   }
 * }
 * </pre>
 *
 * <ol>
 *   <li>MTR 给每个条目注册的 <b>id = {@code "mtr_custom_train_" + key}</b>
 *       （{@code CustomResources} 的 {@code makeConcatWithConstants} 常量就是
 *       {@code mtr_custom_train_\u0001}）。所以编辑器列表里的 id 长这样，而名字来自 {@code name}。</li>
 *   <li>{@code name} 缺失时 MTR 用条目 key 当默认值（{@code getOrDefault(obj, "name", key, ...)}）。</li>
 *   <li>MTR 把 {@code name} 塞进 {@code new TranslatableText(name)}（{@code TrainClientRegistry#register} 里
 *       对 {@code TrainProperties} 的构造），所以包里写的既可以是<b>字面名字</b>
 *       （语言表查不到 → 界面上原样显示，等于字面量），也可以是<b>语言键</b>
 *       （包里 {@code assets/<ns>/lang/*.json} 给出译文 → 显示译文）。
 *       因此「游戏里看到的名字」必须<b>先解析可翻译文本</b>：这里用
 *       {@link Language#get(String)} 查当前语言表，查不到才退回原文，
 *       和游戏内渲染的结果逐字一致。</li>
 *   <li>注册表拿不到（内置车型被 {@code disableBuiltInTrains} 裁掉、或包刚导入还没注册）时，
 *       直接读上面那份 json 的 {@code name} 兜底 —— 这就是导入资源包后还能显示真名的原因。</li>
 * </ol>
 *
 * <p>整份 json 只解析一次并缓存（几 KB，包重载后由 {@link #refresh()} 作废）。
 */
final class TrainNames {

    /** MTR 唯一读取的自定义车描述文件（资源路径，namespace {@code mtr}）。 */
    static final String RESOURCE_PATH = "mtr_custom_resources.json";
    /** MTR 给自定义车注册 id 时加的前缀。 */
    static final String ID_PREFIX = "mtr_custom_train_";

    /** {@code mtr_custom_train_xxx -> name}，来自资源包 json；null = 还没读 / 读失败。 */
    private static Map<String, String> raw;
    private static boolean attempted;

    private TrainNames() {
    }

    /** 作废缓存（资源包重载、导入新包后调用）。 */
    static synchronized void refresh() {
        raw = null;
        attempted = false;
    }

    /**
     * 车型 id → 游戏里显示的名字。永远不返回 null（最差退回 id 本身）。
     */
    static String of(final String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        // 1) 注册表里的 TrainProperties#name —— 游戏里车厂显示的就是它（已合并语言表）。
        try {
            final TrainProperties properties = TrainClientRegistry.getTrainProperties(id);
            if (properties != null && properties.name != null) {
                // MTR 的 jar 是按 intermediary 名编译的，TrainProperties#name 的静态类型是
                // class_2561；先转 Object 再转成 named 的 Text（运行期两者是同一个类）。
                final String resolved = resolve((Text) (Object) properties.name);
                if (useful(resolved)) {
                    return resolved;
                }
            }
        } catch (Throwable ignored) {
            // 注册表还没建好
        }
        // 2) 包里 json 的原始 name（导入的包还没注册，或内置车型被裁掉时走这条）。
        final String fromJson = rawNames().get(id);
        if (useful(fromJson)) {
            return fromJson;
        }
        // 3) 实在没有名字：去掉 MTR 的前缀，比一串 mtr_custom_train_ 好认。
        return id.startsWith(ID_PREFIX) ? id.substring(ID_PREFIX.length()) : id;
    }

    /**
     * 可翻译文本 → 当前语言下的字符串。{@code Text#getString()} 对可翻译文本只返回键，
     * 所以必须先过一遍 {@link Language}（这正是游戏内渲染的那一步）。
     *
     * @param rawText {@code net.minecraft.text.Text}，用 {@code Object} 接收是因为 MTR 的
     *                {@code TrainProperties#name} 在 class 文件里的静态类型是 intermediary 的
     *                {@code class_2561}（同一个类的两个名字，转一次 Object 就能对上）。
     */
    static String resolve(final Object rawText) {
        if (rawText == null) {
            return null;
        }
        final Text text;
        try {
            text = (Text) (Object) rawText;
        } catch (Throwable t) {
            return null;
        }
        try {
            // 1.18.2 没有 TextContent / TranslatableTextContent 这层（1.19 才拆出来）：
            // 可翻译文本就是 {@link TranslatableText} 本身，键直接问它。
            if (text instanceof TranslatableText translatable) {
                final String key = translatable.getKey();
                if (key != null && !key.isEmpty()) {
                    final String translated = Language.getInstance().get(key);
                    if (translated != null && !translated.isEmpty() && !translated.equals(key)) {
                        return translated;
                    }
                    // 1.19.2 的 TranslatableTextContent 没有 getFallback()（1.19.4 才加），
                    // 取不到翻译就直接用 key 本身。
                    return key;
                }
            }
        } catch (Throwable ignored) {
            // Language 还没装好（极早期的 init）：退回下面的原文
        }
        try {
            return text.getString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 包里 json 的原始 name 表：{@code mtr_custom_train_<key> -> name}。 */
    static synchronized Map<String, String> rawNames() {
        if (attempted) {
            return raw == null ? Collections.emptyMap() : raw;
        }
        attempted = true;
        final Map<String, String> out = new LinkedHashMap<>();
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            final ResourceManager manager = client == null ? null : client.getResourceManager();
            if (manager != null && manager.containsResource(new Identifier("mtr", RESOURCE_PATH))) {
                // 1.18.2: getResource 返回 Resource 本体（没有 Optional 包装）。
                final Resource resource = manager.getResource(
                        new Identifier("mtr", RESOURCE_PATH));
                if (resource != null) {
                    final String json;
                    try (InputStream in = resource.getInputStream()) {
                        json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    collect(json, out);
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam3d: cannot read {}", RESOURCE_PATH, t);
        }
        raw = out;
        LteLog.LOGGER.info("[lte] beam3d: {} custom train name(s) from {}", Integer.valueOf(out.size()),
                RESOURCE_PATH);
        return out;
    }

    /** 把一份 {@code mtr_custom_resources.json} 里的 {@code custom_trains.*.name} 收进 {@code out}。 */
    static void collect(final String json, final Map<String, String> out) {
        try {
            final JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) {
                return;
            }
            final JsonElement trains = root.getAsJsonObject().get("custom_trains");
            if (trains == null || !trains.isJsonObject()) {
                return;
            }
            for (final Map.Entry<String, JsonElement> entry : trains.getAsJsonObject().entrySet()) {
                final String key = entry.getKey();
                if (key == null || key.isBlank() || entry.getValue() == null
                        || !entry.getValue().isJsonObject()) {
                    continue;
                }
                final JsonObject train = entry.getValue().getAsJsonObject();
                final JsonElement name = train.get("name");
                final String raw = name != null && name.isJsonPrimitive() ? name.getAsString() : key;
                final String resolved = resolveRaw(raw);
                if (useful(resolved)) {
                    out.put(ID_PREFIX + key, resolved);
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam3d: cannot parse {}", RESOURCE_PATH, t);
        }
    }

    /**
     * json 里的 name 是字符串：可能是字面名字，也可能是语言键 —— 和 {@link #resolve(Text)} 同一个规则。
     */
    static String resolveRaw(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            final String translated = Language.getInstance().get(name);
            if (translated != null && !translated.isEmpty() && !translated.equals(name)) {
                return translated;
            }
        } catch (Throwable ignored) {
            // 语言表不可用
        }
        return name;
    }

    private static boolean useful(final String value) {
        return value != null && !value.isBlank();
    }
}
