// 一个 eyecandy 物件的描述（first.json 里的一条）+ 它对应的方块 id。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.util.Identifier;

/**
 * {@code assets/mtrlte/eyecandies/first.json} 里的一条：中文名 + 模型 + 贴图 + 摆放参数。
 *
 * <pre>
 * "key1": {
 *   "name":        "厦门地铁100米指示牌",   // 中文名，直接进 zh_cn.json
 *   "model":       "mtrsteamloco:eyecandies/sign.obj",
 *   "textureId":   "mtrsteamloco:eyecandies/texture.png",
 *   "flipV":       true,
 *   "shape"/"collisionShape": "…",          // <b>不用</b>，见 {@link EyecandyBlocks} 的碰撞箱说明
 *   "translation": [0, 0, 0],               // 格
 *   "rotation":    [0, 0, 0],               // 度，XYZ 欧拉
 *   "scale":       [1, 1, 1],
 *   "mirror":      [false, false, false]
 * }
 * </pre>
 *
 * <h2>命名空间改写</h2>
 * <p>素材是 MTR 的 Steam Loco 扩展（{@code mtrsteamloco}）的，路径 {@code eyecandies/*} 原样保留，
 * 只把命名空间换成 {@code mtrlte} —— 文件本身已经复制到本模组的
 * {@code assets/mtrlte/eyecandies/} 下，所以资源引用不用再跨模组。
 *
 * <p>{@code blockId} = {@code mtrlte:eyecandy_<key 小写>}（{@code key1145} → {@code eyecandy_key1145}，
 * {@code rolling_bottom_turning} → {@code eyecandy_rolling_bottom_turning}）。
 */
public record EyecandyEntry(String key, String displayName, Identifier modelId, Identifier texture,
                            boolean flipV, float[] scale, float[] rotation, float[] translation,
                            boolean[] mirror, Identifier blockId) {

    /** 描述文件在 classpath 上的位置。 */
    public static final String DESCRIPTOR = "/assets/mtrlte/eyecandies/first.json";

    /**
     * 读描述文件。
     *
     * <p>顺序 = 文件里的顺序（gson 的 {@link JsonObject} 是 {@code LinkedTreeMap}，插入序稳定），
     * 创造页签按这个顺序排。
     *
     * @return 全部条目；读不到时返回空表（调用方只记一行日志，不抛）
     */
    public static List<EyecandyEntry> loadAll() {
        final List<EyecandyEntry> out = new ArrayList<>();
        try (InputStream in = EyecandyEntry.class.getResourceAsStream(DESCRIPTOR)) {
            if (in == null) {
                return out;
            }
            final JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (final var item : root.entrySet()) {
                final JsonObject json = item.getValue().getAsJsonObject();
                out.add(new EyecandyEntry(
                        item.getKey(),
                        string(json, "name", item.getKey()),
                        modelId(string(json, "model", "")),
                        textureId(string(json, "textureId", "")),
                        bool(json, "flipV", true),
                        triple(json, "scale", 1.0F),
                        triple(json, "rotation", 0.0F),
                        triple(json, "translation", 0.0F),
                        bools(json, "mirror"),
                        new Identifier(EyecandyBlocks.NAMESPACE,
                                "eyecandy_" + item.getKey().toLowerCase(java.util.Locale.ROOT))));
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] eyecandy: could not parse {} ({})", DESCRIPTOR, t.toString());
            out.clear();
        }
        return out;
    }

    /** {@code mtrsteamloco:eyecandies/sign.obj} → {@code mtrlte:eyecandies/sign.obj}（路径原样，**命名空间强制换成我们的**）。 */
    private static Identifier modelId(final String raw) {
        return new Identifier(EyecandyBlocks.NAMESPACE, pathOnly(raw));
    }

    /** {@code mtrsteamloco:eyecandies/texture.png} → {@code mtrlte:textures/eyecandies/texture.png}。 */
    private static Identifier textureId(final String raw) {
        String path = pathOnly(raw);
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return new Identifier(EyecandyBlocks.NAMESPACE, path);
    }

    /** 去掉命名空间，只留路径（素材里写的 {@code mtrsteamloco:} 只是 NTE 的写法）。 */
    private static String pathOnly(final String raw) {
        final int colon = raw.indexOf(':');
        return colon < 0 ? raw : raw.substring(colon + 1);
    }

    /** {@code ns:path} → {@code {ns, path}}；没有命名空间时用 {@code fallback}。 */
    private static String[] splitNamespace(final String raw, final String fallback) {
        final int colon = raw.indexOf(':');
        if (colon < 0) {
            return new String[] {fallback, raw};
        }
        return new String[] {raw.substring(0, colon), raw.substring(colon + 1)};
    }

    private static String string(final JsonObject json, final String key, final String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static boolean bool(final JsonObject json, final String key, final boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
    }

    private static float[] triple(final JsonObject json, final String key, final float fallback) {
        final float[] out = {fallback, fallback, fallback};
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return out;
        }
        final JsonArray array = json.getAsJsonArray(key);
        for (int i = 0; i < 3 && i < array.size(); i++) {
            final JsonElement value = array.get(i);
            out[i] = value == null || value.isJsonNull() ? fallback : value.getAsFloat();
        }
        return out;
    }

    private static boolean[] bools(final JsonObject json, final String key) {
        final boolean[] out = {false, false, false};
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return out;
        }
        final JsonArray array = json.getAsJsonArray(key);
        for (int i = 0; i < 3 && i < array.size(); i++) {
            final JsonElement value = array.get(i);
            out[i] = value != null && !value.isJsonNull() && value.getAsBoolean();
        }
        return out;
    }
}
