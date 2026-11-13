// 一个方速（fangsu）屏蔽门变体的描述 —— 从素材包里的 .json 读出来（描述文件随 jar 一起走）。
package dev.js.mtrheadlight.xiamen.fangsu;

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
 * 方速资源包里一套屏蔽门的<b>一个变体</b>（= 一个方块）。
 *
 * <h2>来源</h2>
 * <p>用户资源包 {@code 厦门风格物件包} 的 {@code assets/fangsu/}：
 * <ul>
 *   <li><b>旋转式端门 × 5</b>（{@code duanmen/{brt,mtr,drl,shlow,shhigh}duanmen.json}）：
 *       每个描述里 {@code content[]} 有 right / left 两条，字段
 *       {@code doorPos}（铰链，<b>格</b>，相对方块中心）/ {@code doorAngle}（<b>π/2 的倍数</b>，±1 = ±90°）/
 *       {@code subModels{main,door}}（{@code o} 组名）/ {@code shape{close,open}}（1/16 格）。</li>
 *   <li><b>滑动式厦门屏蔽门 × 2</b>（{@code screendoor/xiamen/{low,high}.json}）：
 *       {@code door.left[] / door.right[]} 里每项 {@code doors[]{subModel, shape, step}}，
 *       {@code step} 是<b>格</b>为单位的滑动距离（0.95 ≈ 一格）。</li>
 * </ul>
 * 描述文件已经复制到本模组 {@code assets/mtrlte/fangsu_psd/}，{@code .obj/.mtl/.png} 同理
 * （贴图在 {@code assets/mtrlte/textures/fangsu_psd/}），命名空间 {@code fangsu:} 在复制时改写成
 * {@code mtrlte:fangsu_psd/}，所以运行期<b>不再需要装方速</b>。
 *
 * <h2>一个变体 = 静态部分 + 若干「动」的门扇</h2>
 * <ul>
 *   <li>静态：{@link #staticGroup()} 那个 {@code o} 组（端门的门框 {@code main_left/main_right}），
 *       不参与动画；滑动门没有静态部分（框是方速另一套 glass 方块，这次没搬）。</li>
 *   <li>动：{@link Motion}，一个组一个 —— 端门绕 {@code pivot} 转 {@code angle} 弧度，
 *       滑动门沿 x 平移 {@code step} 格。开合进度 0..1 由 MTR 的
 *       {@code TileEntityPSDAPGDoorBase#getOpen} 给（见 {@code FangsuPsdBlocks}）。</li>
 * </ul>
 *
 * <h2>碰撞</h2>
 * <p>{@link #closeBoxes()} 就是描述里的 {@code shape.close}（端门）或 {@code shape}（滑动门），
 * 单位 1/16 格、原点在方块西北下角，<b>逐字保留素材的数值</b>（方速自己也是这么判的，所以行为一致）。
 * 关门时这些盒子挡人，开门时碰撞变空 —— 见 {@code FangsuPsdDoorBlock#getCollisionShape}。
 *
 * @param id         方块 id 的路径部分（{@code fangsu_psd_<...>}）
 * @param nameZh     中文名（进 {@code lang/zh_cn.json}）
 * @param nameEn     英文名（进 {@code lang/en_us.json}）
 * @param model      {@code .obj} 的 id（{@code mtrlte:fangsu_psd/<dir>/<name>.obj}）
 * @param flipV      是否翻转 v 轴（素材里所有条目都是 {@code true}）
 * @param staticGroup 静态部分的 {@code o} 组名；{@code null} = 没有
 * @param motions    参与开合动画的门扇
 * @param closeBoxes 关门时的碰撞盒（1/16 格，可超出 0..16 —— 3 格高的门就是 y 到 48）
 */
public record FangsuPsdDef(String id, String nameZh, String nameEn, Identifier model, boolean flipV,
                           String staticGroup, List<Motion> motions, List<double[]> closeBoxes) {

    /**
     * 一个会动的门扇。
     *
     * @param group {@code o} 组名
     * @param pivot 铰链，<b>模型空间</b>（1/16 格，0..16、原点在方块西北下角）
     * @param angle 开到底时的旋转角，<b>弧度</b>。素材写的是 {@code doorAngle: ±1}，那是「π/2 的倍数」而不是弧度
     *              （方速是 {@code rotateY(0.5 * doorAngle * doorVal * π)}），读进来时已经乘成 ±π/2 = ±90°
     * @param step  开到底时的平移量，<b>格</b>（滑动门）。素材写的是 ±0.95；方速渲染时是
     *              {@code translate(-doorVal * step)}，读进来时已按它取负，所以渲染器里直接用 {@code +step}
     */
    public record Motion(String group, float[] pivot, float angle, float step) {

        /** 旋转式（端门）：绕竖直轴转 {@code angle} 弧度。 */
        public boolean rotating() {
            return this.angle != 0.0F;
        }
    }

    /** 描述文件所在目录（classpath）。 */
    public static final String DIR = "fangsu_psd/";

    /** 端门 5 款（{@code content[]} 两条 = 左 / 右）。 */
    private static final String[] DUANMEN = {"brtduanmen", "mtrduanmen", "drlduanmen",
            "shlowduanmen", "shhighduanmen"};

    /** 滑动门 2 款（{@code door.left / door.right}）。 */
    private static final String[] SCREENDOOR = {"low", "high"};

    /**
     * 有玻璃组的滑动门款式。
     *
     * <p>只有 {@code high}：{@code door_low.obj} 里只有 {@code o left} / {@code o right} 两个门扇组，
     * 没有玻璃（低款是半高的，边上没有固定玻璃板）。加新文件前先确认 {@code .obj} 里真有
     * {@code o glass_*} 组，否则 {@link #loadGlass()} 会跳过、页签里也不会出现新方块。
     */
    private static final String[] SCREENDOOR_GLASS = {"high"};

    /** 端门款式的中文 / 英文名（按文件名，不替素材改称呼）。 */
    private static String[] duanmenName(final String file) {
        return switch (file) {
            case "brtduanmen" -> new String[] {"方速端门 BRT", "Fangsu end door BRT"};
            case "mtrduanmen" -> new String[] {"方速端门 MTR", "Fangsu end door MTR"};
            case "drlduanmen" -> new String[] {"方速端门 DRL", "Fangsu end door DRL"};
            case "shlowduanmen" -> new String[] {"方速端门 SH 低", "Fangsu end door SH low"};
            case "shhighduanmen" -> new String[] {"方速端门 SH 高", "Fangsu end door SH high"};
            default -> new String[] {file, file};
        };
    }

    /**
     * 读完全部描述，按「先 5 款端门、再 2 款滑动门」的顺序返回 14 个变体。
     *
     * <p>读不到 / 解析失败的条目只记一行日志并跳过（少一个方块，不影响别的）。
     * 这个方法<b>两端都会调</b>（注册期），所以里面不碰任何客户端类型。
     */
    public static List<FangsuPsdDef> load() {
        final List<FangsuPsdDef> out = new ArrayList<>(14);
        for (final String file : DUANMEN) {
            try {
                loadDuanmen(file, out);
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] fangsu psd: could not read {}{}.json ({})", DIR, file, t.toString());
            }
        }
        for (final String file : SCREENDOOR) {
            try {
                loadScreendoor(file, out);
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] fangsu psd: could not read {}screendoor/xiamen/{}.json ({})",
                        DIR, file, t.toString());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 端门（旋转）

    private static void loadDuanmen(final String file, final List<FangsuPsdDef> out) {
        final JsonObject root = read(DIR + "duanmen/" + file + ".json");
        final String[] names = duanmenName(file);
        for (final JsonElement element : root.getAsJsonArray("content")) {
            final JsonObject entry = element.getAsJsonObject();
            final String rawId = entry.get("id").getAsString();          // mtrduanmen_right
            final boolean right = rawId.endsWith("_right");
            final JsonObject sub = entry.getAsJsonObject("subModels");
            final String mainGroup = sub.get("main").getAsString();
            final String doorGroup = sub.get("door").getAsString();

            final float[] doorPos = floats(entry.getAsJsonArray("doorPos"));
            // doorPos 是「格」、相对方块中心；模型空间是 0..16、原点在西北下角，所以 *16+8。
            final float[] pivot = {doorPos[0] * 16.0F + 8.0F, doorPos[1] * 16.0F, doorPos[2] * 16.0F + 8.0F};
            // 素材里 doorAngle 是 ±1，单位<b>不是</b>弧度：方速自己的
            // BlockEntityDuanmen#whenRendering 是 rotateY(0.5 * doorAngle * doorVal * π)，
            // 也就是「π/2 的倍数」——±1 = 开到底转 ±90°。不乘这一步只会转 1 弧度（57°），
            // 门扇会停在门洞里、看着像「没开到位」。
            final float angle = entry.get("doorAngle").getAsFloat() * (float) (Math.PI / 2.0);
            final List<double[]> close =
                    boxes(entry.getAsJsonObject("shape").getAsJsonArray("close"));

            out.add(new FangsuPsdDef(
                    "fangsu_psd_" + rawId,
                    names[0] + (right ? "（右）" : "（左）"),
                    names[1] + (right ? " (right)" : " (left)"),
                    new Identifier(entry.get("model").getAsString()),
                    !entry.has("flipV") || entry.get("flipV").getAsBoolean(),
                    mainGroup,
                    List.of(new Motion(doorGroup, pivot, angle, 0.0F)),
                    close));
        }
    }

    // ------------------------------------------------------------------ 滑动门（平移）

    private static void loadScreendoor(final String file, final List<FangsuPsdDef> out) {
        final JsonObject root = read(DIR + "screendoor/xiamen/" + file + ".json");
        final Identifier model = new Identifier(root.get("model").getAsString());
        final boolean flipV = !root.has("flipV") || root.get("flipV").getAsBoolean();
        final JsonObject doors = root.getAsJsonObject("door");
        final String label = file.equals("low") ? "低" : "高";
        final String labelEn = file.equals("low") ? "low" : "high";

        for (final String side : new String[] {"left", "right"}) {
            final List<Motion> motions = new ArrayList<>(2);
            final List<double[]> close = new ArrayList<>(2);
            for (final JsonElement entryElement : doors.getAsJsonArray(side)) {
                final JsonObject entry = entryElement.getAsJsonObject();
                if (!entry.has("doors")) {
                    continue;
                }
                for (final JsonElement doorElement : entry.getAsJsonArray("doors")) {
                    final JsonObject door = doorElement.getAsJsonObject();
                    // 素材的 step 是 0.95；方速渲染时是 translate(-doorVal * step, 0, 0)（注意负号），
                    // 所以这里先取负，渲染器里就统一用 +step —— 否则两扇门会朝反方向滑（镜像）。
                    motions.add(new Motion(door.get("subModel").getAsString(), new float[] {8.0F, 0.0F, 8.0F},
                            0.0F, -door.get("step").getAsFloat()));
                    close.addAll(boxes(door.getAsJsonArray("shape")));
                }
            }
            if (motions.isEmpty()) {
                continue;
            }
            out.add(new FangsuPsdDef(
                    "fangsu_psd_screendoor_" + file + "_" + side,
                    "方速屏蔽门 厦门" + label + "（" + (side.equals("left") ? "左" : "右") + "）",
                    "Fangsu platform screen door Xiamen " + labelEn
                            + (side.equals("left") ? " (left)" : " (right)"),
                    model, flipV, null, List.copyOf(motions), List.copyOf(close)));
        }
    }

    // ------------------------------------------------------------------ 玻璃板（静态）

    /**
     * 读滑动门的<b>玻璃板</b>（{@code glass.left[] / glass.right[]}），每个款式 × 左右各一个变体。
     *
     * <p>和 {@link #load()} 分开的原因：那些是<b>会动的门扇</b>（有 {@link Motion}），
     * 这些是<b>不动的板材</b>（{@code motions} 为空、只有 {@link #staticGroup()}），注册成
     * 另一个方块类（{@code FangsuPsdGlassBlock}），页签里排在一起。
     *
     * <p>只取 {@code id == "glass"} 那一条（{@code glass_left_glass} / {@code glass_right_glass}
     * 两个组，就是固定玻璃板）。同一份描述里还有 {@code door} / {@code doorend} / {@code longend}
     * —— 那是「带玻璃的门扇 / 端板 / 长端板」，属于门的另一种样式，这次没有单独搬。
     * {@code id == "auto"} 那条只是给方速自动选型用的规则，没有几何，跳过。
     *
     * <p>读不到 / 解析失败只记一行日志（少两个方块，不影响门）。
     */
    public static List<FangsuPsdDef> loadGlass() {
        final List<FangsuPsdDef> out = new ArrayList<>(SCREENDOOR_GLASS.length * 2);
        for (final String file : SCREENDOOR_GLASS) {
            try {
                loadScreendoorGlass(file, out);
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] fangsu psd glass: could not read {}screendoor/xiamen/{}.json ({})",
                        DIR, file, t.toString());
            }
        }
        return out;
    }

    private static void loadScreendoorGlass(final String file, final List<FangsuPsdDef> out) {
        final JsonObject root = read(DIR + "screendoor/xiamen/" + file + ".json");
        if (!root.has("glass")) {
            return;
        }
        final Identifier model = new Identifier(root.get("model").getAsString());
        final boolean flipV = !root.has("flipV") || root.get("flipV").getAsBoolean();
        final JsonObject glass = root.getAsJsonObject("glass");
        final String label = file.equals("low") ? "低" : "高";
        final String labelEn = file.equals("low") ? "low" : "high";

        for (final String side : new String[] {"left", "right"}) {
            if (!glass.has(side)) {
                continue;
            }
            for (final JsonElement element : glass.getAsJsonArray(side)) {
                final JsonObject entry = element.getAsJsonObject();
                // "auto" 那条是规则（没有 subModel / shape），跳过。
                if (!entry.has("subModel") || !entry.has("shape")) {
                    continue;
                }
                if (!"glass".equals(entry.get("id").getAsString())) {
                    continue;
                }
                final boolean isLeft = side.equals("left");
                out.add(new FangsuPsdDef(
                        "fangsu_psd_screendoor_" + file + "_glass_" + side,
                        "方速屏蔽门玻璃 厦门" + label + "（" + (isLeft ? "左" : "右") + "）",
                        "Fangsu platform screen door glass Xiamen " + labelEn
                                + (isLeft ? " (left)" : " (right)"),
                        model, flipV,
                        entry.get("subModel").getAsString(),
                        List.of(),
                        boxes(entry.getAsJsonArray("shape"))));
            }
        }
    }

    // ------------------------------------------------------------------ 读文件的小工具

    private static JsonObject read(final String path) {
        final String resource = "/assets/mtrlte/" + path;
        try (InputStream in = FangsuPsdDef.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    private static float[] floats(final JsonArray array) {
        final float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    /** {@code [[x1,y1,z1,x2,y2,z2], ...]}（1/16 格）→ {@code List<double[6]>}。 */
    private static List<double[]> boxes(final JsonArray array) {
        final List<double[]> out = new ArrayList<>(array.size());
        for (final JsonElement element : array) {
            final JsonArray box = element.getAsJsonArray();
            final double[] values = new double[6];
            for (int i = 0; i < 6; i++) {
                values[i] = box.get(i).getAsDouble();
            }
            // 素材里偶有「反着写」的盒子（x1 > x2），统一成 min/max。
            out.add(new double[] {
                    Math.min(values[0], values[3]), Math.min(values[1], values[4]), Math.min(values[2], values[5]),
                    Math.max(values[0], values[3]), Math.max(values[1], values[4]), Math.max(values[2], values[5])});
        }
        return out;
    }
}
