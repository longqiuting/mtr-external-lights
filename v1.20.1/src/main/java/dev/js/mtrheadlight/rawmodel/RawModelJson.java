// 解析器 #1：Blockbench 导出的 Minecraft 模型 JSON → RawMesh（只负责算几何与 uv，不画）。
package dev.js.mtrheadlight.rawmodel;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

/**
 * {@code .json}（Blockbench「Java Block/Item」导出）→ {@link RawMesh}。
 *
 * <h2>为什么不用原版 blockstate 模型</h2>
 * <p>厦门闸机 / 余额查询机的素材是 Blockbench 手做的，里面大量元素用了原版模型格式<b>表达不了</b>的东西：
 * <ul>
 *   <li>闸机的旋转角是 {@code -15 / -35 / 35 / -11.5 / 3.25 / 44 / ±45} 这些<b>非 45° 整数倍</b>的量；
 *       原版能吃任意单轴角度，但一个元素只能<b>一个轴</b>；</li>
 *   <li>BRT 闸机有 29 个元素是<b>三轴同时转</b>（{@code {"x":-45,"y":45,"z":0}}），原版格式根本没法写；</li>
 *   <li>两格高的余额查询机，上段模型局部坐标最高 16.91，跨越格边界。</li>
 * </ul>
 * 之前的做法是「角度吸附到 ±22.5/±45 + 丢掉三轴旋转」再烘成原版模型 —— 几何一定错。
 * 上海地铁模组的做法是 {@code *.bbmodel} + 运行时自定义模型；我们不去写 .bbmodel 解析器，
 * 而是<b>直接读 Blockbench 导出的 Minecraft JSON</b>（结构简单：{@code elements[].from/to/rotation/faces}），
 * 在运行时把每个元素算成 {@link RawMesh.Quad}。
 *
 * <h2>坐标与 UV（改这里之前先读这段）</h2>
 * <ul>
 *   <li>{@code from/to} 是模型空间的 <b>0..16</b>（1 格 = 16），与原版模型同一套语义；</li>
 *   <li>{@code faces.<dir>.uv} 是 {@code [u1,v1,u2,v2]}，<b>也在 0..16 空间</b>（16 = 整张贴图宽度），
 *       与原版模型一致，所以只需要 {@code /16} 变成 0..1。
 *       <b>不要</b>再按贴图的实际像素尺寸除一次 —— 实测：余额查询机贴图 4096²，
 *       素材里 uv 最大 10.75，而贴图不透明像素的包围盒正好到 {@code 2751/4096 = 0.6716 = 10.75/16}，
 *       两边对得上，说明 uv 就是 0..16 语义（上一版按 /4 处理，贴图只采到左上角 1/16，正是「贴图全错」的原因）；</li>
 *   <li>{@code rotation} 有两种写法，都要支持：
 *     <ul>
 *       <li>{@code {"axis":"x|y|z","angle":度,"origin":[x,y,z]}}（原版格式写法）；</li>
 *       <li>{@code {"x":度,"y":度,"z":度,"origin":[...]}}（自由格式，三轴同时转）。</li>
 *     </ul>
 *     旋转顺序取 three.js / Blockbench 的默认欧拉序 <b>XYZ</b>，即
 *     {@code v' = Rz · Ry · Rx · (v - origin) + origin}（先绕 X，再绕 Y，最后绕 Z），
 *     正方向是右手定则（见 {@link #rotate}）；</li>
 *   <li>{@code "shade": false} → {@link RawMesh.Quad#fullBright()}；其余按面朝向量出原版那套
 *       {@code down 0.5 / up 1.0 / 南北 0.8 / 东西 0.6}；</li>
 *   <li>{@code rescale} 一律忽略（Blockbench 导出的都是 false，它只影响 Blockbench 自己的预览）。</li>
 * </ul>
 *
 * <h2>旋转在哪一步做</h2>
 * <p>在这里（解析期）就按 {@code origin} 把顶点转好、连同法线一起烘进 {@link RawMesh.Quad}，
 * 渲染器只做「一次矩阵 + 画四顶点」。这和「每帧 push → translate(origin) → rotate →
 * translate(-origin) → 画 → pop」<b>完全等价</b>（矩阵作用在模型空间顶点上，和现在把同一组顶点
 * 转到同一位置是一回事），但省掉每帧 51~85 次 {@code MatrixStack} 压栈。
 *
 * <h2>面朝向与 UV 映射</h2>
 * <p>每个面按「从外面看屏幕右 = +u、屏幕下 = +v」定出 {@code R / D} 两个方向向量，
 * 顶点顺序固定为 {@code (u1,v1) → (u1,v2) → (u2,v2) → (u2,v1)}，
 * 叉积正好指向该面的外法线（绕序朝外、不镜像）。{@link #faceRight} / {@link #faceDown}
 * 里那六组 R/D 就是这么推出来的，六个方向逐个验算过。
 *
 * <h2>贴图</h2>
 * <p>这些模型每份只用一张贴图（{@code textures} 里除 {@code particle} 只有一个键）。
 * {@link RawMesh#texture()} 给出 {@code mtrlte:textures/block/xxx.png} 这种可直接喂给
 * {@code RenderLayer.getEntityCutoutNoCull(...)} 的 id。
 */
public final class RawModelJson {

    /** 模型资源目录（classpath 下的 {@code assets/<ns>/rawmodels/*.json}）。 */
    public static final String DIRECTORY = "rawmodels/";

    /** uv 是 0..16（整张贴图 = 16）。 */
    private static final float UV_TO_UNIT = 1.0F / 16.0F;

    /** 解析成功的网格（按模型 id 缓存；换资源包不影响，这里是 classpath 资源）。 */
    private static final Map<Identifier, RawMesh> CACHE = new ConcurrentHashMap<>();

    /** 加载失败的 id：只警告一次，之后每帧直接跳过（不刷日志、不反复读文件）。 */
    private static final Set<Identifier> FAILED = ConcurrentHashMap.newKeySet();

    private RawModelJson() {
    }

    /** {@code mtrlte:rawmodels/<name>}。 */
    public static Identifier id(final String name) {
        return new Identifier("mtrlte", DIRECTORY + name);
    }

    /**
     * 取一份网格：第一次调用从 classpath 读，之后走缓存。
     *
     * @param id {@code <ns>:rawmodels/<name>}（用 {@link #id} 拼）
     * @return 网格；读不到 / 解析失败时 {@code null}（并且只警告一次）
     */
    public static RawMesh get(final Identifier id) {
        final RawMesh cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(id)) {
            return null;
        }
        final String path = "/assets/" + id.getNamespace() + "/" + id.getPath() + ".json";
        try (InputStream in = RawModelJson.class.getResourceAsStream(path)) {
            if (in == null) {
                FAILED.add(id);
                LteLog.LOGGER.warn("[lte] rawmodel: {} is missing from the classpath", path);
                return null;
            }
            final RawMesh mesh = parse(id, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            CACHE.put(id, mesh);
            LteLog.LOGGER.info("[lte] rawmodel: loaded {} ({} quads, texture {})",
                    id, mesh.quadCount(), mesh.texture());
            return mesh;
        } catch (Throwable t) {
            FAILED.add(id);
            LteLog.LOGGER.warn("[lte] rawmodel: could not load {} ({})", path, t.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 从 JSON 文本解析（包内可见，方便单独检查）。 */
    public static RawMesh parse(final Identifier id, final String json) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final JsonObject textures = root.has("textures") ? root.getAsJsonObject("textures") : new JsonObject();

        final Identifier texture = resolveTexture(id.getNamespace(), firstTextureName(textures));

        final List<RawMesh.Quad> quads = new ArrayList<>();
        final JsonArray array = root.has("elements") ? root.getAsJsonArray("elements") : new JsonArray();
        for (final JsonElement raw : array) {
            parseElement(raw.getAsJsonObject(), quads);
        }
        return new RawMesh(texture, quads.toArray(new RawMesh.Quad[0]));
    }

    /** {@code textures} 里第一个不是 {@code particle} 的键的值（没有就退回 {@code particle}）。 */
    private static String firstTextureName(final JsonObject textures) {
        String fallback = null;
        for (final Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            final String name = entry.getValue().getAsString();
            if (!"particle".equals(entry.getKey())) {
                return name;
            }
            fallback = name;
        }
        return fallback;
    }

    /** {@code ticket_barrier} / {@code block/ticket_barrier} / {@code mtrlte:block/ticket_barrier} → 贴图 id。 */
    private static Identifier resolveTexture(final String namespace, final String name) {
        if (name == null) {
            return new Identifier(namespace, "textures/block/missing.png");
        }
        String ns = namespace;
        String path = name;
        final int colon = name.indexOf(':');
        if (colon >= 0) {
            ns = name.substring(0, colon);
            path = name.substring(colon + 1);
        }
        if (!path.startsWith("textures/")) {
            path = "textures/block/" + path;
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return new Identifier(ns, path);
    }

    private static void parseElement(final JsonObject raw, final List<RawMesh.Quad> out) {
        if (!raw.has("from") || !raw.has("to") || !raw.has("faces")) {
            return;
        }
        final float[] from = readTriple(raw.getAsJsonArray("from"));
        final float[] to = readTriple(raw.getAsJsonArray("to"));

        // from > to 时按 min/max 收口（Blockbench 偶尔会写出反向盒）。
        final float x1 = Math.min(from[0], to[0]);
        final float y1 = Math.min(from[1], to[1]);
        final float z1 = Math.min(from[2], to[2]);
        final float x2 = Math.max(from[0], to[0]);
        final float y2 = Math.max(from[1], to[1]);
        final float z2 = Math.max(from[2], to[2]);

        final float[] origin = new float[3];
        final float[] angles = new float[3];
        readRotation(raw.getAsJsonObject("rotation"), origin, angles);
        final boolean rotated = angles[0] != 0.0F || angles[1] != 0.0F || angles[2] != 0.0F;

        // 原版 "shade": false = 自发光（闸机的灯组、查询机的屏幕）。
        final boolean fullBright = raw.has("shade") && !raw.get("shade").getAsBoolean();

        final JsonObject faces = raw.getAsJsonObject("faces");
        for (final Direction face : Direction.values()) {
            final String key = face.getName(); // north / south / east / west / up / down
            if (!faces.has(key)) {
                continue;
            }
            final JsonObject faceJson = faces.getAsJsonObject(key);
            final float[] uv = readQuad(faceJson.getAsJsonArray("uv"));
            final RawMesh.Quad quad = buildQuad(face, x1, y1, z1, x2, y2, z2, uv, origin, angles, rotated, fullBright);
            if (quad != null) {
                out.add(quad);
            }
        }
    }

    /** {@code rotation} 的两种写法都读进来（角度制，轴顺序 XYZ）。 */
    private static void readRotation(final JsonObject rotation, final float[] origin, final float[] angles) {
        if (rotation == null) {
            return;
        }
        if (rotation.has("origin")) {
            final float[] o = readTriple(rotation.getAsJsonArray("origin"));
            origin[0] = o[0];
            origin[1] = o[1];
            origin[2] = o[2];
        }
        if (rotation.has("axis")) {
            // 原版格式：单轴
            final String axis = rotation.get("axis").getAsString();
            final float angle = rotation.has("angle") ? rotation.get("angle").getAsFloat() : 0.0F;
            switch (axis) {
                case "x" -> angles[0] = angle;
                case "y" -> angles[1] = angle;
                case "z" -> angles[2] = angle;
                default -> {
                }
            }
        } else {
            // 自由格式：三轴
            angles[0] = rotation.has("x") ? rotation.get("x").getAsFloat() : 0.0F;
            angles[1] = rotation.has("y") ? rotation.get("y").getAsFloat() : 0.0F;
            angles[2] = rotation.has("z") ? rotation.get("z").getAsFloat() : 0.0F;
        }
    }

    /**
     * 一个面 → 一个 {@link RawMesh.Quad}；<b>面积为零的面返回 {@code null}</b>
     * （素材里 12 个「零厚度卡片」元素把六个面都写上了，其中四个侧面退化成一个点/一条线，
     * 画出来永远不可见 —— 直接扔掉，省顶点）。
     *
     * <p>顺序固定 {@code A(u1,v1) → B(u1,v2) → C(u2,v2) → D(u2,v1)}，R/D 见
     * {@link #faceRight} / {@link #faceDown}，这样绕序的叉积 = 外法线。
     */
    private static RawMesh.Quad buildQuad(final Direction face, final float x1, final float y1, final float z1,
                                          final float x2, final float y2, final float z2, final float[] uv,
                                          final float[] origin, final float[] angles, final boolean rotated,
                                          final boolean fullBright) {
        final float[] r = faceRight(face);
        final float[] d = faceDown(face);

        final float[] center = faceCenter(face, x1, y1, z1, x2, y2, z2);
        final float halfW = faceWidth(face, x1, y1, z1, x2, y2, z2) * 0.5F;
        final float halfH = faceHeight(face, x1, y1, z1, x2, y2, z2) * 0.5F;

        final float[][] pos = new float[4][3];
        final float[][] uvOut = new float[4][2];
        final float[][] corners = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}}; // (u1,v1) (u1,v2) (u2,v2) (u2,v1)
        final float[] uus = {uv[0], uv[0], uv[2], uv[2]};
        final float[] vvs = {uv[1], uv[3], uv[3], uv[1]};

        for (int i = 0; i < 4; i++) {
            final float sx = corners[i][0];
            final float sy = corners[i][1];
            pos[i][0] = center[0] + sx * halfW * r[0] + sy * halfH * d[0];
            pos[i][1] = center[1] + sx * halfW * r[1] + sy * halfH * d[1];
            pos[i][2] = center[2] + sx * halfW * r[2] + sy * halfH * d[2];

            // 元素旋转：v' = Rz·Ry·Rx·(v - origin) + origin
            if (rotated) {
                rotate(pos[i], origin, angles);
            }

            uvOut[i][0] = uus[i] * UV_TO_UNIT;
            uvOut[i][1] = vvs[i] * UV_TO_UNIT;
        }

        final float[] normal = {face.getOffsetX(), face.getOffsetY(), face.getOffsetZ()};
        if (rotated) {
            rotate(normal, new float[3], angles);
        }

        // 零面积的面（零厚度元素的侧面）画出来永远不可见，丢弃。
        final float[] e1 = {pos[1][0] - pos[0][0], pos[1][1] - pos[0][1], pos[1][2] - pos[0][2]};
        final float[] e2 = {pos[2][0] - pos[0][0], pos[2][1] - pos[0][1], pos[2][2] - pos[0][2]};
        final double area = Math.sqrt(Math.pow(e1[1] * e2[2] - e1[2] * e2[1], 2)
                + Math.pow(e1[2] * e2[0] - e1[0] * e2[2], 2)
                + Math.pow(e1[0] * e2[1] - e1[1] * e2[0], 2));
        if (area < 1.0E-6D) {
            return null;
        }
        return new RawMesh.Quad(pos, uvOut, normal, shadeOf(face), fullBright);
    }

    /** 原版 {@code BlockModelRenderer} 的方向明暗系数（DOWN 0.5 / UP 1.0 / 南北 0.8 / 东西 0.6）。 */
    public static float shadeOf(final Direction face) {
        return switch (face) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }

    /** 绕 origin 按 XYZ 欧拉序（右手定则，角度制）旋转一个点，原地改。 */
    static void rotate(final float[] p, final float[] origin, final float[] angles) {
        float x = p[0] - origin[0];
        float y = p[1] - origin[1];
        float z = p[2] - origin[2];

        if (angles[0] != 0.0F) {
            final double a = Math.toRadians(angles[0]);
            final float c = (float) Math.cos(a);
            final float s = (float) Math.sin(a);
            final float ny = y * c - z * s;
            final float nz = y * s + z * c;
            y = ny;
            z = nz;
        }
        if (angles[1] != 0.0F) {
            final double a = Math.toRadians(angles[1]);
            final float c = (float) Math.cos(a);
            final float s = (float) Math.sin(a);
            final float nx = x * c + z * s;
            final float nz = -x * s + z * c;
            x = nx;
            z = nz;
        }
        if (angles[2] != 0.0F) {
            final double a = Math.toRadians(angles[2]);
            final float c = (float) Math.cos(a);
            final float s = (float) Math.sin(a);
            final float nx = x * c - y * s;
            final float ny = x * s + y * c;
            x = nx;
            y = ny;
        }

        p[0] = x + origin[0];
        p[1] = y + origin[1];
        p[2] = z + origin[2];
    }

    // ------------------------------------------------------------------ 六个面的 R / D

    /** 从外面看这个面时，屏幕<b>向右</b>是世界坐标的哪个方向（+u）。 */
    private static float[] faceRight(final Direction face) {
        return switch (face) {
            case NORTH -> new float[] {-1, 0, 0};
            case SOUTH -> new float[] {1, 0, 0};
            case EAST -> new float[] {0, 0, -1};
            case WEST -> new float[] {0, 0, 1};
            case UP -> new float[] {1, 0, 0};
            case DOWN -> new float[] {1, 0, 0};
        };
    }

    /** 从外面看这个面时，屏幕<b>向下</b>是世界坐标的哪个方向（+v）。 */
    private static float[] faceDown(final Direction face) {
        return switch (face) {
            case NORTH, SOUTH, EAST, WEST -> new float[] {0, -1, 0};
            case UP -> new float[] {0, 0, 1};
            case DOWN -> new float[] {0, 0, -1};
        };
    }

    private static float[] faceCenter(final Direction face, final float x1, final float y1, final float z1,
                                      final float x2, final float y2, final float z2) {
        final float mx = (x1 + x2) * 0.5F;
        final float my = (y1 + y2) * 0.5F;
        final float mz = (z1 + z2) * 0.5F;
        return switch (face) {
            case NORTH -> new float[] {mx, my, z1};
            case SOUTH -> new float[] {mx, my, z2};
            case EAST -> new float[] {x2, my, mz};
            case WEST -> new float[] {x1, my, mz};
            case UP -> new float[] {mx, y2, mz};
            case DOWN -> new float[] {mx, y1, mz};
        };
    }

    /** 面沿 +u 方向的长度（模型单位 1/16 格）。 */
    private static float faceWidth(final Direction face, final float x1, final float y1, final float z1,
                                   final float x2, final float y2, final float z2) {
        return switch (face) {
            case NORTH, SOUTH, UP, DOWN -> x2 - x1;
            case EAST, WEST -> z2 - z1;
        };
    }

    /** 面沿 +v 方向的长度。 */
    private static float faceHeight(final Direction face, final float x1, final float y1, final float z1,
                                    final float x2, final float y2, final float z2) {
        return switch (face) {
            case NORTH, SOUTH, EAST, WEST -> y2 - y1;
            case UP, DOWN -> z2 - z1;
        };
    }

    // ------------------------------------------------------------------ JSON 小工具

    private static float[] readTriple(final JsonArray array) {
        return new float[] {array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float[] readQuad(final JsonArray array) {
        return new float[] {
                array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }
}
