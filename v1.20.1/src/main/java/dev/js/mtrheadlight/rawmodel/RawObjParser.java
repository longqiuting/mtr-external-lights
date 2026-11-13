// 解析器 #2：Wavefront .obj → RawMesh（只负责算几何与 uv，不画）。
package dev.js.mtrheadlight.rawmodel;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.util.Identifier;

/**
 * {@code .obj}（Blockbench / 3D 软件导出）→ {@link RawMesh}。
 *
 * <h2>为什么又写一个解析器</h2>
 * <p>两套 eyecandy 素材（厦门风格物件包 26 件 + 南普陀 34 件）是 <b>OBJ</b>（不是 Blockbench 的 Minecraft JSON）：
 * 它们绕开了「元素盒 + 单轴旋转」那套限制，里面是任意网格（圆柱、倒角盒、整辆共享单车）。
 * 渲染层只认 {@link RawMesh}，所以这里按 {@link RawMesh} 类注释里那套坐标约定把 OBJ 算成四边形，
 * 渲染器一行都不用改 —— 这就是当初把「格式」和「绘制」拆开的用处。
 *
 * <h2>支持到哪一步</h2>
 * <table border="1">
 *   <caption>行类型</caption>
 *   <tr><th>行</th><th>处理</th></tr>
 *   <tr><td>{@code v x y z [w]}</td><td>顶点；{@code w} 忽略（不做齐次除法，素材里没有）</td></tr>
 *   <tr><td>{@code vt u [v] [w]}</td><td>贴图坐标，见下面「UV」</td></tr>
 *   <tr><td>{@code vn}</td><td><b>读但不信</b>：法线一律用三角形叉积现算（OBJ 里的 vn 经常与绕序不一致）</td></tr>
 *   <tr><td>{@code f}</td><td>支持 {@code v}、{@code v/vt}、{@code v//vn}、{@code v/vt/vn} 四种写法与<b>负索引</b>；
 *       三角形原样出，四边形原样出，{@code n>4} 的多边形按<b>扇形</b>拆成 {@code n-2} 个三角形</td></tr>
 *   <tr><td>{@code o} / {@code g}</td><td>记进 {@link RawMesh.Quad#group()}（渲染层不用，留给后续按组隐藏）</td></tr>
 *   <tr><td>{@code mtllib} / {@code usemtl} / {@code s} / {@code #…}</td><td>忽略 —— 贴图由 {@code first.json} 的
 *       {@code textureId} 直接给，.mtl 一概不读</td></tr>
 * </table>
 *
 * <h2>坐标：OBJ 的 1 单位 = 1 格（16 模型单位）</h2>
 * <p>素材是 Blockbench 直接导出的：<b>Y 向上</b>、水平方向以方块中心为原点（{@code x, z ∈ [-0.5, 0.5]} 是整格），
 * 所以换算到 {@link RawMesh} 的模型空间（0..16、原点在方块西北下角）是
 * <pre>  x_mc = x*16 + 8     y_mc = y*16     z_mc = z*16 + 8  </pre>
 * <b>不要</b>再乘 MTR 的 {@code scale} 或除以 16 —— 数值对过：{@code right.obj} 的一扇 BRT 屏蔽门
 * {@code y ∈ [-0.125, 1.5813]}，换过来是 -2..25.3 单位，与 {@code first.json} 里 {@code shape} 的 24 高对得上。
 *
 * <h2>变换顺序：scale → rotation → translation</h2>
 * <p>{@code first.json} 每个条目带 {@code scale} / {@code rotation}（度数，XYZ 欧拉）/ {@code translation}（单位是格）
 * / {@code mirror}。这一步在<b>解析期</b>烘进顶点（和 {@link RawModelJson} 把元素旋转烘进顶点是同一个道理，
 * 渲染器每帧只做一次矩阵 + 画顶点）。顺序：
 * <ol>
 *   <li>{@code v *= scale}，{@code mirror[axis]} 为真时该轴再取反；</li>
 *   <li>按 XYZ 欧拉序旋转（{@code Rz·Ry·Rx}，右手定则，与 {@link RawModelJson#rotate} 同一套公式）；</li>
 *   <li>加 {@code translation}（格）；</li>
 *   <li>最后才换算成模型空间 0..16（见上）。</li>
 * </ol>
 *
 * <p><b>别把顺序改成「先平移后旋转」</b>：这个顺序与 NTE（{@code cn.zbx1425.mtrsteamloco.render.block.
 * BlockEntityEyeCandyRenderer}）逐条对过 —— 它把 {@code PoseStack} 的矩阵链写成
 * {@code translate(0.5,0,0.5) → translate(translation) → rotateX → rotateY → rotateZ →
 * rotateY(facing)}，而 {@code cn.zbx1425.sowcer.math.Matrix4f} 的 {@code translate / rotateX / rotateY /
 * rotateZ} 全是直接转调 JOML（<b>后乘</b> {@code M = M·T}），所以顶点实际经历的正是
 * 「先绕模型原点转、再沿方块轴平移」（后乘意味着列表里越靠后的调用对顶点越先生效，于是第 2 步
 * 先于第 3 步）。
 * <p>后果：{@code translation} 的轴是<b>转完之后</b>的方块轴。一件 {@code rotation: [0,90,0]} 的模型
 * 想贴格子边，偏移要写在转完之后的贴边轴上（BRT 栏杆那四件就是这么修的：{@code [0,0,0.4531]}，
 * 见 {@code EyecandyEntry}）。写成模型自己的轴（{@code [-0.45,0,0]}）只会在长条方向上平移，
 * 薄面照旧压在格子中线上。
 *
 * <h2>UV</h2>
 * <ul>
 *   <li>{@code flipV}（两份描述里所有物件都是 {@code true}）→ {@code v' = 1 - v}：
 *       OBJ 的 v 轴朝上，Minecraft 的贴图 v 轴朝下；</li>
 *   <li>u / v 一律<b>按 GL_REPEAT 折进 [0,1)</b>：素材里确实有负数与大于 1 的 uv
 *       （{@code barr.obj} 的 {@code u ∈ [-0.875, 2.25]}，那是横向平铺 4 格的石墩贴图）。
 *       折回去与「纹理重复」等价，不折的话那几格会整片采样到边缘像素。</li>
 * </ul>
 *
 * <h2>法线与明暗</h2>
 * <p>法线用三角形叉积算（保证与绕序一致、背面剔除不会画反）。{@code shade} 按法线的主轴取原版那套
 * {@code down 0.5 / up 1.0 / 南北 0.8 / 东西 0.6} —— OBJ 里没有「自发光」信息，{@code fullBright} 恒为 {@code false}。
 *
 * <h2>整体在放置点以下的模型（qiaodun20，不抬到地面）</h2>
 * <p>{@code stand.obj}（20 格高的桥墩）的 y 是 {@code -21..-1}：在素材里它是<b>从放置点往下挂</b>的。
 * 这里<b>原样保留</b> —— 不做任何「抬到贴地」的兜底，玩家贴着上方桥面的底面放，桥墩就往下挂，
 * 墩顶正好落在放置格的顶面（{@code first.json} 的 {@code qiaodun20} 用 {@code "translation":[0,2,0]}
 * 把这 2 格的错位补上：素材的墩顶在放置格底面下 1 格，抬 2 格之后就是放置格顶面 = 桥面底面）。
 *
 * <p>以前这里有一条「整个网格都在 {@code y ≤ 0} 就抬到贴地、再抬 {@code raise} 格」的兜底规则，
 * 结果是桥墩从地面往上长 20 格（玩家得反着找位置）。已删除：素材怎么摆就怎么画，
 * 唯一的调整入口是描述里明明白白的 {@code translation}（格）。
 */
public final class RawObjParser {

    /** 模型资源目录（classpath 下的 {@code assets/<ns>/eyecandies/*.obj}）。 */
    public static final String DIRECTORY = "eyecandies/";

    /** OBJ 单位 → 模型空间的格数：1 单位 = 1 格 = 16 模型单位。 */
    private static final float UNITS_PER_BLOCK = 16.0F;

    /** 水平居中：OBJ 的 x/z 以方块中心为原点，模型空间的 x/z 以西北角为原点。 */
    private static final float HALF_BLOCK = 8.0F;

    /** 解析成功的网格（按 obj id 缓存；classpath 资源，换资源包不影响）。 */
    private static final Map<Identifier, RawMesh> CACHE = new ConcurrentHashMap<>();

    /** 加载失败的 id：只警告一次，之后每帧直接跳过。 */
    private static final Set<Identifier> FAILED = ConcurrentHashMap.newKeySet();

    /** 多材质入口的缓存（一个 obj → 若干按材质分开的网格）。 */
    private static final Map<Identifier, Map<String, RawMesh>> MATERIAL_CACHE = new ConcurrentHashMap<>();

    private RawObjParser() {
    }

    /** {@code mtrlte:eyecandies/<name>.obj}。 */
    public static Identifier id(final String name) {
        return new Identifier("mtrlte", DIRECTORY + name + ".obj");
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 取一份网格：第一次调用从 classpath 读，之后走缓存。
     *
     * @param id        {@code <ns>:eyecandies/<name>.obj}（用 {@link #id} 拼）
     * @param texture   贴图 id（带 {@code textures/} 前缀与 {@code .png}），直接用 {@code first.json} 的 textureId
     * @param flipV     是否把 v 轴翻过来（素材包写了 {@code true}）
     * @param scale     逐轴缩放（{@code {1,1,1}} 为原样）
     * @param rotation  逐轴旋转，度数，XYZ 欧拉序
     * @param translation 逐轴平移，单位：格
     * @param mirror    逐轴镜像（{@code true} = 该轴取反）
     * @return 网格；读不到 / 解析失败时 {@code null}（并且只警告一次）
     */
    public static RawMesh get(final Identifier id, final Identifier texture, final boolean flipV,
                              final float[] scale, final float[] rotation, final float[] translation,
                              final boolean[] mirror) {
        final RawMesh cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(id)) {
            return null;
        }
        final String path = "/assets/" + id.getNamespace() + "/" + id.getPath();
        try (InputStream in = RawObjParser.class.getResourceAsStream(path)) {
            if (in == null) {
                FAILED.add(id);
                LteLog.LOGGER.warn("[lte] obj model: {} is missing from the classpath", path);
                return null;
            }
            final RawMesh mesh = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    texture, flipV, scale, rotation, translation, mirror);
            CACHE.put(id, mesh);
            LteLog.LOGGER.info("[lte] obj model: loaded {} ({} quads, texture {})",
                    id, mesh.quadCount(), mesh.texture());
            return mesh;
        } catch (Throwable t) {
            FAILED.add(id);
            LteLog.LOGGER.warn("[lte] obj model: could not load {} ({})", path, t.toString());
            return null;
        }
    }

    /** 从 OBJ 文本解析出完整网格（顶点已按 {@code scale/rotation/translation} 摆好）。 */
    public static RawMesh parse(final String text, final Identifier texture, final boolean flipV,
                                final float[] scale, final float[] rotation, final float[] translation,
                                final boolean[] mirror) {
        final Geometry geometry = read(text, true, flipV);
        final float[][] verts = place(geometry.positions, scale, rotation, translation, mirror);
        final List<RawMesh.Quad> quads = new ArrayList<>(geometry.faces.size());
        for (final Face face : geometry.faces) {
            emit(face, verts, geometry.texCoords, quads);
        }
        return new RawMesh(texture, quads.toArray(new RawMesh.Quad[0]));
    }

    // ------------------------------------------------------------------ 多材质（.mtl）

    /**
     * 一次读一整个 OBJ，<b>按 {@code usemtl} 材质拆成若干 {@link RawMesh}</b>，每个材质的贴图
     * 由同目录的 {@code .mtl}（{@code mtllib} 行 → {@code map_Kd} 行）决定。
     *
     * <h2>为什么需要它</h2>
     * <p>{@link RawMesh} 是「一张贴图 + 一堆四边形」，而方速的屏蔽门素材不是单贴图：
     * {@code brtduanmen.obj} 的门扇有三套材质（{@code dkg.png} 门体 / {@code ltg.png} 灯带 /
     * {@code blk.png} 黑框），{@code screendoor/xiamen/door.obj} 更多。单贴图入口
     * （{@link #get}）只能画出其中一个材质那一层，所以这里按材质拆开、各自绑定自己的贴图，
     * 渲染时对每份分别 {@code draw}（同一组矩阵，几份几何拼起来才是完整的门）。
     *
     * <h2>贴图路径规则</h2>
     * <p>素材包里的 {@code map_Kd} 只写文件名（相对 OBJ 所在目录），而 Minecraft 的贴图 id 必须带
     * {@code textures/} 前缀（{@code RenderLayer.getEntityCutout(id)} 直接按
     * {@code assets/<ns>/<path>} 取文件，不会自动补前缀）。所以：
     * <pre>  mtrlte:fangsu_psd/duanmen/brtduanmen.obj + map_Kd blk.png
     *   →  mtrlte:textures/fangsu_psd/duanmen/blk.png</pre>
     *
     * @return 材质名 → 网格（按文件里首次出现的顺序，{@code LinkedHashMap}）；读不到返回空表
     */
    public static Map<String, RawMesh> materials(final Identifier objId, final boolean flipV,
                                                 final float[] scale, final float[] rotation,
                                                 final float[] translation, final boolean[] mirror) {
        final Map<String, RawMesh> cached = MATERIAL_CACHE.get(objId);
        if (cached != null) {
            return cached;
        }
        final Map<String, RawMesh> result = new LinkedHashMap<>();
        final String path = "/assets/" + objId.getNamespace() + "/" + objId.getPath();
        try (InputStream in = RawObjParser.class.getResourceAsStream(path)) {
            if (in == null) {
                LteLog.LOGGER.warn("[lte] obj materials: {} is missing from the classpath", path);
                return result;
            }
            final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            final String dir = directoryOf(objId.getPath());
            final Map<String, String> maps = mtlTextures(text, objId.getNamespace(), dir);
            final Geometry geometry = read(text, true, flipV);
            final float[][] verts = place(geometry.positions, scale, rotation, translation, mirror);
            final Map<String, List<RawMesh.Quad>> byMaterial = new LinkedHashMap<>();
            for (final Face face : geometry.faces) {
                emit(face, verts, geometry.texCoords,
                        byMaterial.computeIfAbsent(face.material() == null ? "" : face.material(),
                                key -> new ArrayList<>()));
            }
            for (final Map.Entry<String, List<RawMesh.Quad>> entry : byMaterial.entrySet()) {
                final String png = maps.get(entry.getKey());
                if (png == null) {
                    LteLog.LOGGER.warn("[lte] obj materials: {}/{} has no map_Kd in the mtl, skipped",
                            objId, entry.getKey());
                    continue;
                }
                result.put(entry.getKey(), new RawMesh(
                        new Identifier(objId.getNamespace(), "textures/" + dir + png),
                        entry.getValue().toArray(new RawMesh.Quad[0])));
            }
            MATERIAL_CACHE.put(objId, result);
            LteLog.LOGGER.info("[lte] obj materials: loaded {} ({} materials, {} quads)", objId,
                    result.size(), result.values().stream().mapToInt(RawMesh::quadCount).sum());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] obj materials: could not load {} ({})", path, t.toString());
        }
        return result;
    }

    /** 一个资源路径的目录部分（带结尾斜杠）；没有目录时返回空串。 */
    static String directoryOf(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    /**
     * 读 OBJ 文本里的 {@code mtllib} 指向的 {@code .mtl}（与 OBJ 同目录），
     * 返回 {@code newmtl → map_Kd}。读不到时返回空表（调用方会把对应材质跳过）。
     */
    static Map<String, String> mtlTextures(final String objText, final String namespace, final String dir) {
        final Map<String, String> maps = new LinkedHashMap<>();
        String mtlName = null;
        for (final String rawLine : objText.split("\n")) {
            final String line = rawLine.strip();
            if (line.startsWith("mtllib ")) {
                mtlName = line.substring("mtllib ".length()).strip();
                break;
            }
        }
        if (mtlName == null || mtlName.isEmpty()) {
            return maps;
        }
        final String path = "/assets/" + namespace + "/" + dir + mtlName;
        try (InputStream in = RawObjParser.class.getResourceAsStream(path)) {
            if (in == null) {
                LteLog.LOGGER.warn("[lte] obj materials: mtl {} is missing from the classpath", path);
                return maps;
            }
            String material = null;
            for (final String rawLine : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                final String line = rawLine.strip();
                if (line.startsWith("newmtl ")) {
                    material = line.substring("newmtl ".length()).strip();
                } else if (line.startsWith("map_Kd ") && material != null) {
                    maps.put(material, line.substring("map_Kd ".length()).strip());
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] obj materials: could not read {} ({})", path, t.toString());
        }
        return maps;
    }

    /**
     * 只算包围盒（{@code [minX, minY, minZ, maxX, maxY, maxZ]}，模型空间 0..16）。
     *
     * <p>注册方块时（主线程、两端）只要这个，不需要四边形 —— 于是这里只读 {@code v} 行，
     * 60 个物件合计 4 万多行，几十毫秒就把全部碰撞箱算完了。
     */
    public static double[] bounds(final String text, final float[] scale, final float[] rotation,
                                  final float[] translation, final boolean[] mirror) {
        final Geometry geometry = read(text, false, false);
        final float[][] verts = place(geometry.positions, scale, rotation, translation, mirror);
        if (verts.length == 0) {
            return new double[6];
        }
        final double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (final float[] v : verts) {
            for (int axis = 0; axis < 3; axis++) {
                box[axis] = Math.min(box[axis], v[axis]);
                box[axis + 3] = Math.max(box[axis + 3], v[axis]);
            }
        }
        return box;
    }

    // ------------------------------------------------------------------ 顶点摆放

    /**
     * OBJ 顶点 → 模型空间顶点：{@code scale → mirror → rotation → translation → 单位换算}。
     *
     * <p>素材里的模型怎么摆就怎么画（桥墩那种「挂在放置点以下」的模型也原样保留），
     * 要挪位置只有一个入口：描述里的 {@code translation}（格）。
     */
    public static float[][] place(final List<float[]> positions, final float[] scale, final float[] rotation,
                                  final float[] translation, final boolean[] mirror) {
        final float[] s = scale == null ? new float[] {1, 1, 1} : scale;
        final float[] a = rotation == null ? new float[] {0, 0, 0} : rotation;
        final float[] t = translation == null ? new float[] {0, 0, 0} : translation;
        final boolean[] m = mirror == null ? new boolean[] {false, false, false} : mirror;
        final boolean rotated = a[0] != 0.0F || a[1] != 0.0F || a[2] != 0.0F;

        final float[][] out = new float[positions.size()][];
        for (int i = 0; i < out.length; i++) {
            final float[] v = positions.get(i);
            final float[] p = {v[0] * s[0] * (m[0] ? -1.0F : 1.0F),
                    v[1] * s[1] * (m[1] ? -1.0F : 1.0F),
                    v[2] * s[2] * (m[2] ? -1.0F : 1.0F)};
            if (rotated) {
                rotate(p, a);
            }
            p[0] += t[0];
            p[1] += t[1];
            p[2] += t[2];
            out[i] = p;
        }

        for (final float[] p : out) {
            p[0] = p[0] * UNITS_PER_BLOCK + HALF_BLOCK;
            p[1] = p[1] * UNITS_PER_BLOCK;
            p[2] = p[2] * UNITS_PER_BLOCK + HALF_BLOCK;
        }
        return out;
    }

    // ------------------------------------------------------------------ 占位格

    /** 占位格的分辨率：一格 = 1 模型单位 = 1/16 格（与碰撞盒的最小刻度一致）。 */
    public static final int FOOTPRINT_SIZE = 16;

    /** 采样步长的目标值（模型单位）：0.4 单位一个样点，1 单位宽的栏杆也漏不掉。 */
    private static final double FOOTPRINT_SAMPLE = 0.4D;

    /**
     * 模型在方块内 {@code xz} 平面上的占位格（{@code 16 × 16}，索引 {@code z * 16 + x}，1 = 有几何）。
     *
     * <p>用来给「转角栏杆」这类<b>不是一整块</b>的物件拼碰撞箱：把每个三角形按顶点坐标做重心
     * 采样，落进哪一格就标哪一格 —— 直栏杆于是变成一条线、L 形栏杆变成两条线，取盒子时再
     * 按行合并（见 {@code EyecandyBlocks.cornerBoxes}）。
     *
     * <p>只统计方块内的那部分（{@code 0..16} 单位）：转角模型常常伸到邻居格里（BRT 转角栏杆
     * 一个模型 4.5 格长），伸出去的部分本来就不该由一个方块的 AABB 去挡。
     *
     * @return 长度 {@code 256} 的 0/1 数组；没有几何时全 0
     */
    public static int[] footprint(final String text, final float[] scale, final float[] rotation,
                                  final float[] translation, final boolean[] mirror) {
        final Geometry geometry = read(text, true, false);
        final float[][] verts = place(geometry.positions, scale, rotation, translation, mirror);
        final int[] grid = new int[FOOTPRINT_SIZE * FOOTPRINT_SIZE];
        for (final Face face : geometry.faces) {
            final int n = face.vertex.length;
            for (int i = 1; i + 1 < n; i++) {
                sample(verts[face.vertex[0]], verts[face.vertex[i]], verts[face.vertex[i + 1]], grid);
            }
        }
        return grid;
    }

    /** 一个三角形在 xz 上的重心采样：步数按最长边自适应（约 {@link #FOOTPRINT_SAMPLE} 单位一采样）。 */
    private static void sample(final float[] p0, final float[] p1, final float[] p2, final int[] grid) {
        final double edge = Math.max(Math.max(span(p0, p1), span(p1, p2)), span(p0, p2));
        final int steps = (int) Math.max(3.0D, Math.min(48.0D, Math.ceil(edge / FOOTPRINT_SAMPLE)));
        for (int a = 0; a <= steps; a++) {
            for (int b = 0; a + b <= steps; b++) {
                final double wa = (double) a / steps;
                final double wb = (double) b / steps;
                final double wc = 1.0D - wa - wb;
                final double x = p0[0] * wa + p1[0] * wb + p2[0] * wc;
                final double z = p0[2] * wa + p1[2] * wb + p2[2] * wc;
                if (x < 0.0D || z < 0.0D || x >= FOOTPRINT_SIZE || z >= FOOTPRINT_SIZE) {
                    continue;
                }
                grid[((int) z) * FOOTPRINT_SIZE + (int) x] = 1;
            }
        }
    }

    /** 两点在 xz 平面上的距离。 */
    private static double span(final float[] a, final float[] b) {
        final double dx = a[0] - b[0];
        final double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 绕原点按 XYZ 欧拉序（右手定则、角度制）旋转一个点，原地改 —— 与 {@link RawModelJson#rotate} 同一套公式。 */
    static void rotate(final float[] p, final float[] angles) {
        float x = p[0];
        float y = p[1];
        float z = p[2];
        if (angles[0] != 0.0F) {
            final double r = Math.toRadians(angles[0]);
            final float c = (float) Math.cos(r);
            final float s = (float) Math.sin(r);
            final float ny = y * c - z * s;
            final float nz = y * s + z * c;
            y = ny;
            z = nz;
        }
        if (angles[1] != 0.0F) {
            final double r = Math.toRadians(angles[1]);
            final float c = (float) Math.cos(r);
            final float s = (float) Math.sin(r);
            final float nx = x * c + z * s;
            final float nz = -x * s + z * c;
            x = nx;
            z = nz;
        }
        if (angles[2] != 0.0F) {
            final double r = Math.toRadians(angles[2]);
            final float c = (float) Math.cos(r);
            final float s = (float) Math.sin(r);
            final float nx = x * c - y * s;
            final float ny = x * s + y * c;
            x = nx;
            y = ny;
        }
        p[0] = x;
        p[1] = y;
        p[2] = z;
    }

    // ------------------------------------------------------------------ 四边形

    /** 一个面：逐角度的顶点 / uv 索引（已解析成 0 基），它所在的 {@code o} / {@code g} 组名，以及 {@code usemtl} 材质名。 */
    private record Face(int[] vertex, int[] texCoord, String group, String material) {
    }

    /** 一个面的三角形 → 一个 {@link RawMesh.Quad}（三角形补一个重复顶点凑成 4 个）。 */
    private static void emit(final Face face, final float[][] positions, final List<float[]> texCoords,
                             final List<RawMesh.Quad> out) {
        final int n = face.vertex.length;
        if (n < 3) {
            return;
        }
        if (n == 4) {
            emitCorners(face, positions, texCoords, out, 0, 1, 2, 3, 3);
            return;
        }
        // 三角形与多边形都按扇形拆：0-1-2, 0-2-3, 0-3-4 …
        for (int i = 1; i + 1 < n; i++) {
            emitCorners(face, positions, texCoords, out, 0, i, i + 1, i + 1, i + 1);
        }
    }

    /** {@code a,b,c,d} 四个角（{@code d == c} 时是三角形，退化成一个零面积三角形）。 */
    private static void emitCorners(final Face face, final float[][] positions, final List<float[]> texCoords,
                                    final List<RawMesh.Quad> out, final int a, final int b, final int c, final int d,
                                    final int uvD) {
        final float[][] pos = {
                positions[face.vertex[a]], positions[face.vertex[b]],
                positions[face.vertex[c]], positions[face.vertex[d]]};
        final float[][] uv = {
                texCoords.get(face.texCoord[a]), texCoords.get(face.texCoord[b]),
                texCoords.get(face.texCoord[c]), texCoords.get(face.texCoord[uvD])};

        final float[] normal = normalOf(pos[0], pos[1], pos[2]);
        if (normal == null) {
            return; // 零面积（重复顶点 / 退化的多边形角），画出来也不可见
        }
        out.add(new RawMesh.Quad(pos, uv, normal, shadeOf(normal), false, face.group));
    }

    /** 三角形的外法线（单位向量）；面积为零返回 {@code null}。 */
    private static float[] normalOf(final float[] p0, final float[] p1, final float[] p2) {
        final double ux = p1[0] - p0[0];
        final double uy = p1[1] - p0[1];
        final double uz = p1[2] - p0[2];
        final double vx = p2[0] - p0[0];
        final double vy = p2[1] - p0[1];
        final double vz = p2[2] - p0[2];
        final double nx = uy * vz - uz * vy;
        final double ny = uz * vx - ux * vz;
        final double nz = ux * vy - uy * vx;
        final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-9D) {
            return null;
        }
        return new float[] {(float) (nx / len), (float) (ny / len), (float) (nz / len)};
    }

    /**
     * 按法线主轴取原版那套方向明暗（{@code down 0.5 / up 1.0 / 南北 0.8 / 东西 0.6}）。
     *
     * <p>OBJ 不带「朝上还是朝下」这种元信息，只能由法线反推；用主轴而不是内积，是为了让斜面
     * 也落在原版那三档里（斜得再厉害也不会出现「比顶面还亮」的面）。
     */
    private static float shadeOf(final float[] normal) {
        final float ax = Math.abs(normal[0]);
        final float ay = Math.abs(normal[1]);
        final float az = Math.abs(normal[2]);
        if (ay >= ax && ay >= az) {
            return normal[1] >= 0.0F ? 1.0F : 0.5F;
        }
        if (ax >= az) {
            return 0.6F; // 东西
        }
        return 0.8F; // 南北
    }

    // ------------------------------------------------------------------ 文本解析

    /** 一次读文件的结果：顶点（OBJ 原始单位）、uv（已 flipV + 折进 0..1）、面。 */
    private static final class Geometry {
        final List<float[]> positions = new ArrayList<>();
        final List<float[]> texCoords = new ArrayList<>();
        final List<Face> faces = new ArrayList<>();
    }

    /**
     * 扫一遍 OBJ 文本。
     *
     * @param withFaces {@code false} 时跳过 {@code f} 行（只要包围盒的场合，省一半时间）
     * @param flipV     是否把 v 轴翻过来（{@code true} = {@code v' = 1 - v}）
     */
    private static Geometry read(final String text, final boolean withFaces, final boolean flipV) {
        final Geometry g = new Geometry();
        // uv 的 flipV 在这里统一做（两份素材的物件都是 flipV=true），折进 [0,1) 见类注释。
        String group = "";
        String material = "";
        for (final String rawLine : text.split("\n")) {
            final String line = rawLine.strip();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            final int space = indexOfWhitespace(line);
            final String keyword = space < 0 ? line : line.substring(0, space);
            if (space < 0) {
                continue;
            }
            final String rest = line.substring(space + 1).strip();
            switch (keyword) {
                case "v" -> g.positions.add(parseTriple(rest));
                case "vt" -> {
                    final float[] uv = parsePair(rest);
                    g.texCoords.add(new float[] {wrap(uv[0]), wrap(flipV ? 1.0F - uv[1] : uv[1])});
                }
                case "o", "g" -> group = rest;
                case "usemtl" -> material = rest;
                case "f" -> {
                    if (withFaces) {
                        final Face face = parseFace(rest, g, group, material);
                        if (face != null) {
                            g.faces.add(face);
                        }
                    }
                }
                default -> {
                    // mtllib / s / vn / l / p / vp …：不关心（法线现算，贴图由调用方给）
                }
            }
        }
        return g;
    }

    private static int indexOfWhitespace(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static float[] parseTriple(final String rest) {
        final String[] parts = rest.split("\\s+");
        return new float[] {Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                parts.length > 2 ? Float.parseFloat(parts[2]) : 0.0F};
    }

    private static float[] parsePair(final String rest) {
        final String[] parts = rest.split("\\s+");
        return new float[] {Float.parseFloat(parts[0]), parts.length > 1 ? Float.parseFloat(parts[1]) : 0.0F};
    }

    /** 折进 [0,1)（等价于 GL_REPEAT）。 */
    private static float wrap(final float value) {
        final float wrapped = value - (float) Math.floor(value);
        // 浮点误差可能把 0.9999999 折成 1.0，再兜一次
        return wrapped >= 1.0F ? 0.0F : wrapped;
    }

    /**
     * {@code f 1/1/1 2/2/2 3/3/3 …} → 索引数组。
     *
     * <p>四种写法都要吃：{@code v}、{@code v/vt}、{@code v//vn}、{@code v/vt/vn}；
     * 负数索引按 OBJ 规范相对「当前已读到的个数」解析。
     */
    private static Face parseFace(final String rest, final Geometry g, final String group,
                                 final String material) {
        final String[] corners = rest.split("\\s+");
        final List<Integer> vi = new ArrayList<>(corners.length);
        final List<Integer> ti = new ArrayList<>(corners.length);
        for (final String corner : corners) {
            if (corner.isEmpty()) {
                continue;
            }
            final String[] parts = corner.split("/", -1);
            final int vertex = resolve(parts[0], g.positions.size());
            if (vertex < 0 || vertex >= g.positions.size()) {
                return null; // 坏面：整面丢掉（素材里没有，防御而已）
            }
            vi.add(vertex);
            // 没有 vt 时退化成第 0 个 uv（贴图不可见的部分，比抛异常强）；vn 一律忽略（法线现算）。
            int texCoord = g.texCoords.isEmpty() ? -1 : 0;
            if (parts.length > 1 && !parts[1].isEmpty()) {
                final int resolved = resolve(parts[1], g.texCoords.size());
                if (resolved >= 0 && resolved < g.texCoords.size()) {
                    texCoord = resolved;
                }
            }
            ti.add(texCoord);
        }
        if (vi.size() < 3) {
            return null;
        }
        return new Face(toArray(vi), toArray(ti), group, material);
    }

    /** OBJ 的 1 基 / 负索引 → 0 基；越界返回 -1。 */
    private static int resolve(final String token, final int count) {
        try {
            final int index = Integer.parseInt(token);
            return index < 0 ? count + index : index - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int[] toArray(final List<Integer> values) {
        final int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
