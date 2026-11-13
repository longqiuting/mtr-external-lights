// 一个 eyecandy 物件的描述（描述文件里的一条）+ 它对应的方块 id。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.util.Identifier;

/**
 * 一个 eyecandy 物件的描述：中文名 + 模型 + 贴图 + 摆放参数。
 *
 * <p>现在有两份描述文件，格式完全相同（都是素材包 {@code mtrsteamloco} 的那一套字段）：
 * <table border="1">
 *   <caption>两套素材</caption>
 *   <tr><th>{@link Pack}</th><th>描述文件（classpath）</th><th>条数</th><th>进哪个页签</th></tr>
 *   <tr><td>{@code XIAMEN}（厦门风格物件包）</td><td>{@code assets/mtrlte/eyecandies/first.json}</td>
 *       <td>25</td><td>{@code mtrlte:core}「厦门风格物件」</td></tr>
 *   <tr><td>{@code NANPUTUO}（厦门南普陀相关装饰物件）</td><td>{@code assets/mtrlte/eyecandies/nanputuo.json}</td>
 *       <td>34</td><td>{@code mtrlte:nanputuo_decor}「南普陀装饰物件」</td></tr>
 * </table>
 * 两份的 {@code .obj} / {@code .png} 都放在同一个 {@code eyecandies/} 目录里（key、文件名都不冲突），
 * 所以只有描述文件是分开的 —— 一份描述 = 一个页签。
 *
 * <pre>
 * "xiamen_metro_100m_sign": {               // 顶层 key = 方块 id 去掉 mtrlte:eyecandy_ 前缀（中文名的英译）
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
 * <p>物体摆在哪基本由素材决定：模型烘进 {@code .obj} 就那一副样子，位置只靠这里的 {@code translation}
 * 平移 —— 桥墩（{@code bridge_pier_20}）就是靠 {@code [0,2,0]} 把素材里「挂在放置点以下」的模型
 * 抬到墩顶贴着放置格顶面，玩家贴着上方桥面的底面放，墩身自然往下挂。
 *
 * <p>{@code translation} 是在<b>转过之后</b>的方块坐标轴上生效的（{@code scale → rotation →
 * translation}，与 NTE 的 eyecandy 矩阵顺序一致：先转、再平移）。所以要把一件「转 90° 之后
 * 变成长条」的模型贴到格子的某条边，偏移量要写在<b>转完之后</b>贴边的那一轴上 —— 例如 BRT 栏杆
 * （{@code rotation} 90°、长条沿 x）写 {@code translation: [0,0,0.4531]} 才会贴到 z = 1 那条边；
 * 写 {@code [-0.45,0,0]} 只会在长条方向上平移，薄面仍然压在格子中线上。
 *
 * <p>朝向：<b>全部物件都有 {@code facing}</b>（{@link EyecandyBlock#FACING}，blockstate 四个变体），
 * 放置时按玩家朝向定一次（{@code facing = 玩家朝向的反方向}，见
 * {@link EyecandyBlock#getPlacementState}），模型与碰撞盒一起转；之后没有任何旋转手段（没有刷子）。
 *
 * <h2>命名空间改写</h2>
 * <p>素材是 MTR 的 Steam Loco 扩展（{@code mtrsteamloco}）的，路径 {@code eyecandies/*} 原样保留，
 * 只把命名空间换成 {@code mtrlte} —— 文件本身已经复制到本模组的
 * {@code assets/mtrlte/eyecandies/} 下，所以资源引用不用再跨模组。
 *
 * <p>{@code blockId} = {@code mtrlte:eyecandy_<key>}，key 就是<b>中文名的英译</b>，所以 id 一眼能读懂
 * （{@code xiamen_metro_200m_sign} → {@code eyecandy_xiamen_metro_200m_sign}，
 * {@code nanputuo_sign_stand_1} → {@code eyecandy_nanputuo_sign_stand_1}）。
 * 以前那种 {@code key1 / key1145 / qiaodun20} 的编号 key 已经全部废弃 —— 旧存档里放下的方块会变成
 * 未知方块（id 变了，没有迁移，需重放）。
 */
public record EyecandyEntry(Pack pack, String key, String displayName, Identifier modelId, Identifier texture,
                            boolean flipV, float[] scale, float[] rotation, float[] translation,
                            boolean[] mirror, Identifier blockId) {

    /** 两套素材：一份描述文件 = 一个页签。 */
    public enum Pack {
        /** 厦门风格物件包（{@code resourcepacks/厦门风格物件包}），进「厦门风格物件」页签。 */
        XIAMEN("/assets/mtrlte/eyecandies/first.json"),
        /** 厦门南普陀相关装饰物件（{@code resourcepacks/厦门南普陀相关装饰物件}），自己开一页。 */
        NANPUTUO("/assets/mtrlte/eyecandies/nanputuo.json");

        private final String descriptor;

        Pack(final String descriptor) {
            this.descriptor = descriptor;
        }

        /** 描述文件在 classpath 上的位置。 */
        public String descriptor() {
            return this.descriptor;
        }
    }

    /**
     * 读一份描述文件。
     *
     * <p>顺序 = 文件里的顺序（gson 的 {@link JsonObject} 是 {@code LinkedTreeMap}，插入序稳定），
     * 创造页签按这个顺序排。
     *
     * @return 全部条目；读不到时返回空表（调用方只记一行日志，不抛）
     */
    public static List<EyecandyEntry> loadAll(final Pack pack) {
        final List<EyecandyEntry> out = new ArrayList<>();
        try (InputStream in = EyecandyEntry.class.getResourceAsStream(pack.descriptor())) {
            if (in == null) {
                return out;
            }
            final JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (final var item : root.entrySet()) {
                final JsonObject json = item.getValue().getAsJsonObject();
                out.add(new EyecandyEntry(
                        pack,
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
                                "eyecandy_" + item.getKey().toLowerCase(Locale.ROOT))));
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] eyecandy: could not parse {} ({})", pack.descriptor(), t.toString());
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
