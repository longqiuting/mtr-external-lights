// 方速「厦门屏蔽门玻璃」—— 一个方块 = 完整一格，左右两个半片各一份形态。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.util.Identifier;

/**
 * 方速厦门屏蔽门的<b>固定玻璃板</b>：<b>一个方块 = 一整格</b>，格内左右各半个格（0.5 格）拼起来。
 *
 * <h2>「一格」还是「两格」—— 文档 + 反编译的双重依据</h2>
 * <p>方速开发文档 {@code screendoor_glass}（{@code https://doc.fangsu.top/#/screendoor_glass}）原文：
 * <pre>
 * 与其他物件不同, 一个屏蔽门玻璃是由左右两部分拼接而成的, 这意味着更大的用户自由度.
 * ...
 * - `left` 和 `right` 一个玻璃方块是由左右两部分拼接而成的, 用于区分左右.
 * </pre>
 * 注意它是<b>一个玻璃方块</b>由左右两部分拼接而成，不是「左方块 + 右方块」。
 *
 * <p>反编译 {@code com.fangsu.blockEntities.BlockEntityScreendoorGlass}（{@code javap -p}）里，
 * 一个方块实体同时持有两份子模型 + 两份碰撞：
 * <pre>
 *   protected String subModelLeft;      protected String subModelRight;
 *   DynamicModelHolder dhmLeft;         DynamicModelHolder dhmRight;
 *   ShapeCollection shapeLeft;          ShapeCollection shapeRight;
 *   private ShapeCollection glassShape;          ← 两份合并后的最终碰撞
 * </pre>
 * {@code whenRendering()} 依次 {@code drawModel(dhmLeft)} 再 {@code drawModel(dhmRight)}；
 * {@code reloadModelAndShape()} 末尾把 {@code shapeLeft} 与 {@code shapeRight} 用
 * {@code ShapeCollection.addAll(...)} 合成 {@code glassShape}，{@code getFinalShape()} 只返回
 * {@code glassShape}，{@code setCollisionShape}/{@code setShape} 都走它 ——
 * <b>一个方块的碰撞 = 左半 ∪ 右半</b>。方块形态只有一个 {@code fangsu:screendoor_glass}
 * （blockstate 只有 {@code ""} 一个 variant，没有 left/right 变体）。
 *
 * <p>几何也印证了这一点（{@code door.obj} 顶点包围盒，单位 = 格，方块中心在原点）：
 * <pre>
 *   glass_left_glass    min=(-0.50,0,-0.31) max=(0.00,3,-0.25)   ← 左半，x 宽 0.5
 *   glass_right_glass   min=( 0.00,0,-0.31) max=(0.50,3,-0.25)   ← 右半，x 宽 0.5
 * </pre>
 * 两半各 0.5 格、正好在 x = 0 处接缝，合起来才是 1 格。配置里的 {@code shape} 同理：
 * {@code glass.left} 的纯玻璃是 {@code [8,0,12,16,48,13]}，{@code glass.right} 是
 * {@code [0,0,12,8,48,13]} —— 同一格里的 x 8..16 与 0..8，正好拼满 0..16。
 *
 * <h2>形态（{@code glass.left[]} / {@code glass.right[]}）</h2>
 * <p>每条带 {@code subModel} / {@code shape} 的条目 = 一种形态（{@code .obj} 里的一个 {@code o} 组）：
 * <table border="1">
 *   <caption>4 种形态（左右各一份，组名带 left_ / right_ 前缀）</caption>
 *   <tr><th>id</th><th>{@code o} 组（左 / 右）</th><th>是什么</th></tr>
 *   <tr><td>{@code door}</td><td>{@code glass_left_door} / {@code glass_right_door}</td><td>紧挨门扇那一半（往门那边加厚）</td></tr>
 *   <tr><td>{@code doorend}</td><td>{@code glass_left_door_end} / {@code glass_right_door_end}</td><td>门端板</td></tr>
 *   <tr><td>{@code glass}</td><td>{@code glass_left_glass} / {@code glass_right_glass}</td><td>纯玻璃（<b>默认</b>）</td></tr>
 *   <tr><td>{@code longend}</td><td>{@code glass_left_long_end} / {@code glass_right_long_end}</td><td>长端板</td></tr>
 * </table>
 * {@code glass.auto[]} 里那些 {@code "id": "auto"} 的条目是「按邻居自动选形态」的规则表
 * （{@code "left": "door", "right": "glass"} —— 也是<b>同一条规则同时给左右两半</b>，
 * 再次说明两半属于同一个方块），没有 {@code subModel} / {@code shape}，不当作形态。
 *
 * <h2>{@code glass.auto[]}：邻居规则表（这一版真的搬过来了）</h2>
 * <p>素材 {@code high.json} 里的规则表原文（顺序即优先级，<b>先匹配先赢</b>）：
 * <pre>
 *   [{"if": "LEFT_BLOCK",  "is":  "SCREENDOOR_DOOR",   "left": "door"},
 *    {"if": "RIGHT_BLOCK", "is":  "SCREENDOOR_DOOR",   "right": "door"},
 *    {"if": {"LEFT_BLOCK": {"is": "SCREENDOOR_GLASS"}, "RIGHT_BLOCK": {"is": "SCREENDOOR_GLASS"}},
 *                                             "left": "glass", "right": "glass"},
 *    {"if": {"LEFT_BLOCK": {"not": "SCREENDOOR_GLASS"}, "RIGHT_BLOCK": {"not": "SCREENDOOR_GLASS"}},
 *                                             "left": "glass", "right": "glass"},
 *    {"if": "LEFT_BLOCK",  "not": "SCREENDOOR_GLASS", "left": "door",  "right": "glass"},
 *    {"if": "RIGHT_BLOCK", "not": "SCREENDOOR_GLASS", "left": "glass", "right": "door"},
 *    {"if": "TRUE",                                   "left": "doorend", "right": "doorend"}]
 * </pre>
 * {@code LEFT_BLOCK} / {@code RIGHT_BLOCK} 是「沿屏蔽门这条线、站在方块面朝 {@code FACING}
 * 时的左手 / 右手那一个方块」——方速 {@code FacingBlockUtil#getLeftDirection} =
 * {@code facing.rotateYCounterclockwise()}（north 时是西），取值只有
 * {@code SCREENDOOR_DOOR} / {@code SCREENDOOR_GLASS} / {@code NULL}。
 *
 * <p>方速的求值在 {@code BlockEntityScreendoorGlass#recomputeAuto}：规则表<b>倒序</b>遍历、
 * 每条命中的规则都覆盖一次，所以等价于「按正序取第一条命中、且写了这一侧的规则」。
 * 这只对「自动」的那一侧生效（{@code auto} 是下拉框里的一项，可以只让某一侧自动）。
 * 这里把规则表原样解析成 {@link AutoRule}，求值交给
 * {@link FangsuPsdGlassBlock#relink}，改 JSON 就能改规则、代码里不写死。
 *
 * <h2>和方速的差别（有意的）</h2>
 * <p>方速靠配置界面选左右子模型、刷子只做「按邻居重连」；我们没有界面，改成
 * <b>刷子右键点哪半就换哪半</b>：左半 {@link FangsuPsdGlassBlock#FORM_LEFT}、右半
 * {@link FangsuPsdGlassBlock#FORM_RIGHT}，两半各有自己的形态下标（所以「左边靠门、右边纯玻璃」
 * 这种真实摆法能做出来）。下拉框里的 {@code auto} 那一项保留成<b>每一侧的自动档</b>
 * （{@link FangsuPsdGlassBlock#AUTO_LEFT} / {@link FangsuPsdGlassBlock#AUTO_RIGHT}），
 * 循环顺序就是下拉框顺序：{@code auto → door → doorend → glass → longend → auto}。
 * 默认（刚放下）两侧都是 auto，所以摆一排自动就连上了，和 MTR 原版屏蔽门的手感一致。
 *
 * @param id           方块 id 的路径部分（{@code fangsu_psd_screendoor_high_glass}）
 * @param nameZh       中文名
 * @param nameEn       英文名
 * @param model        {@code .obj} 的 id
 * @param flipV        是否翻转 v 轴（素材写 {@code true}）
 * @param left         左半的形态列表（顺序 = 素材里的顺序 = 刷子循环顺序）
 * @param right        右半的形态列表
 * @param defaultLeft  左半「自动」求不出来时的兜底形态下标（素材里 {@code id == "glass"} 那一条）
 * @param defaultRight 右半兜底形态下标
 * @param auto         {@code glass.auto[]} 的邻居规则表（顺序 = 优先级）
 */
public record FangsuPsdGlassDef(String id, String nameZh, String nameEn, Identifier model, boolean flipV,
                                List<Form> left, List<Form> right, int defaultLeft, int defaultRight,
                                List<AutoRule> auto) {

    /**
     * 半个格的一种形态。
     *
     * @param key  素材里的 {@code id}（{@code door} / {@code doorend} / {@code glass} / {@code longend}）
     * @param group {@code .obj} 里的 {@code o} 组名（{@code glass_left_door} / {@code glass_right_door} …）
     * @param shape 这半个格的碰撞盒（1/16 格、x 只会落在自己那半边；y 可超过 16 —— 3 格高的板到 48）
     */
    public record Form(String key, String group, List<double[]> shape) {
    }

    /** {@code auto} 在素材里的 id（下拉框第一项）：表示「这一侧交给邻居规则」。 */
    public static final String AUTO_KEY = "auto";

    /**
     * {@code glass.auto[]} 的一条规则。
     *
     * <p>条件 = 若干条 {@link Condition}（<b>全部</b>满足才算命中）+ 一个 {@code TRUE} 标记
     * （对应 {@code "if": "TRUE"}，恒命中，用来做兜底）；命中后给 {@code left} / {@code right}
     * 指定的那一侧换成 {@code formKey} 那个形态。没写的那一侧不动。
     *
     * @param conditions 全部要满足的邻居条件
     * @param always     {@code "if": "TRUE"} 的兜底规则
     * @param left       命中后左半要用的形态 id（可为 {@code null} = 不改左半）
     * @param right      命中后右半要用的形态 id（可为 {@code null} = 不改右半）
     */
    public record AutoRule(List<Condition> conditions, boolean always, String left, String right) {

        /**
         * 这条规则命中吗。
         *
         * @param leftBlock  {@code LEFT_BLOCK} 的取值（{@link #SCREENDOOR_DOOR} / {@link #SCREENDOOR_GLASS} / {@link #NULL}）
         * @param rightBlock {@code RIGHT_BLOCK} 的取值
         */
        public boolean matches(final String leftBlock, final String rightBlock) {
            if (this.always) {
                return true;
            }
            if (this.conditions.isEmpty()) {
                return false;
            }
            for (final Condition condition : this.conditions) {
                final String actual = value(condition.key(), leftBlock, rightBlock);
                if (condition.negate() ? condition.value().equals(actual)
                        : !condition.value().equals(actual)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 一条邻居条件（{@code "LEFT_BLOCK": {"is": "SCREENDOOR_GLASS"}} 这种）。
     *
     * @param key    关系键（{@code LEFT_BLOCK} / {@code RIGHT_BLOCK}；别的键这里一律当 NULL）
     * @param value  比较值
     * @param negate {@code true} = 素材写的是 {@code "not"}，要求「不等于」value
     */
    public record Condition(String key, String value, boolean negate) {
    }

    /** 邻居是「屏蔽门门扇」。 */
    public static final String SCREENDOOR_DOOR = "SCREENDOOR_DOOR";

    /** 邻居是「屏蔽门玻璃」（同一条线上的另一块玻璃）。 */
    public static final String SCREENDOOR_GLASS = "SCREENDOOR_GLASS";

    /** 那个方向没有屏蔽门方块（或不是同一条线上的）。方速的默认值就是这个字符串。 */
    public static final String NULL = "NULL";

    /** 关系键 → 取值。方速 {@code BlockRelation#get} 同一张表；我们不认识的键一律 NULL。 */
    public static String value(final String key, final String leftBlock, final String rightBlock) {
        return switch (key) {
            case "LEFT_BLOCK" -> leftBlock;
            case "RIGHT_BLOCK" -> rightBlock;
            // 玻璃邻居自己的左右子模型（LEFT_GLASS_LEFT / RIGHT_DOOR …）：我们没有需要它的规则，
            // 但键要认得出来，返回 NULL 而不是抛异常。
            default -> NULL;
        };
    }

    /** 有玻璃组的滑动门款式。只有 {@code high}：{@code door_low.obj} 里没有 {@code glass_*} 组。 */
    private static final String[] FILES = {"high"};

    /** 默认形态：素材里的纯玻璃板。 */
    private static final String DEFAULT_KEY = "glass";

    /** 形态 id → 显示名的 lang key（{@code block.mtrlte.fangsu_psd_glass_form.<id>}）。 */
    public static String langKey(final String formKey) {
        return "block.mtrlte.fangsu_psd_glass_form." + formKey;
    }

    /** 左半第 {@code index} 种形态（越界夹到合法范围，调用方不用自己判空）。 */
    public Form leftForm(final int index) {
        return at(this.left, index);
    }

    /** 右半第 {@code index} 种形态。 */
    public Form rightForm(final int index) {
        return at(this.right, index);
    }

    /** 形态 id → 左半的下标（{@code auto} 或素材里没有的 id 返回 -1）。 */
    public int leftIndexOf(final String key) {
        return indexOf(this.left, key);
    }

    /** 形态 id → 右半的下标（{@code auto} 或素材里没有的 id 返回 -1）。 */
    public int rightIndexOf(final String key) {
        return indexOf(this.right, key);
    }

    private static int indexOf(final List<Form> forms, final String key) {
        if (key == null) {
            return -1;
        }
        for (int i = 0; i < forms.size(); i++) {
            if (forms.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static Form at(final List<Form> forms, final int index) {
        return forms.get(Math.max(0, Math.min(forms.size() - 1, index)));
    }

    /**
     * 读全部玻璃板变体（现在只有「厦门高」一款 = <b>1 个方块</b>）。
     *
     * <p>读不到 / 解析失败只记一行日志并跳过 —— 少一块玻璃，不影响门扇。
     */
    public static List<FangsuPsdGlassDef> load() {
        final List<FangsuPsdGlassDef> out = new ArrayList<>(FILES.length);
        for (final String file : FILES) {
            try {
                final FangsuPsdGlassDef def = loadOne(file);
                if (def != null) {
                    out.add(def);
                }
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] fangsu psd glass: could not read {}screendoor/xiamen/{}.json ({})",
                        FangsuPsdDef.DIR, file, t.toString());
            }
        }
        return out;
    }

    private static FangsuPsdGlassDef loadOne(final String file) {
        final JsonObject root = FangsuPsdDef.read(FangsuPsdDef.DIR + "screendoor/xiamen/" + file + ".json");
        if (!root.has("glass")) {
            return null;
        }
        final JsonObject glass = root.getAsJsonObject("glass");
        final List<Form> left = forms(glass, "left");
        final List<Form> right = forms(glass, "right");
        if (left.isEmpty() || right.isEmpty()) {
            return null;
        }
        final Identifier model = new Identifier(root.get("model").getAsString());
        final boolean flipV = !root.has("flipV") || root.get("flipV").getAsBoolean();
        final String label = file.equals("low") ? "低" : "高";
        final String labelEn = file.equals("low") ? "low" : "high";
        return new FangsuPsdGlassDef(
                "fangsu_psd_screendoor_" + file + "_glass",
                "方速屏蔽门玻璃 厦门" + label,
                "Fangsu platform screen door glass Xiamen " + labelEn,
                model, flipV, left, right, defaultOf(left), defaultOf(right), autoRules(glass));
    }

    /**
     * 读 {@code glass.auto[]}（邻居规则表）。
     *
     * <p>两种写法都认，和方速 {@code recomputeAuto} 的解析逐条对应：
     * <pre>
     *   {"if": "LEFT_BLOCK", "is": "SCREENDOOR_DOOR", ...}     ← if 是字符串 + 同级的 is / not
     *   {"if": {"LEFT_BLOCK": {"is": "…"}, "RIGHT_BLOCK": …}}  ← if 是对象，多个条件「与」
     *   {"if": "TRUE", ...}                                    ← 恒命中（兜底）
     * </pre>
     * 认不出的条目直接跳过：规则少一条只会让 {@code auto} 落到兜底形态，不会炸。
     */
    private static List<AutoRule> autoRules(final JsonObject glass) {
        final List<AutoRule> rules = new ArrayList<>(8);
        if (!glass.has("auto") || !glass.get("auto").isJsonArray()) {
            return List.copyOf(rules);
        }
        for (final JsonElement element : glass.getAsJsonArray("auto")) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject entry = element.getAsJsonObject();
            if (!entry.has("if")) {
                continue;
            }
            final List<Condition> conditions = new ArrayList<>(2);
            boolean always = false;
            final JsonElement condition = entry.get("if");
            if (condition.isJsonPrimitive()) {
                final String key = condition.getAsString();
                if ("TRUE".equals(key)) {
                    always = true;
                } else if (entry.has("is")) {
                    conditions.add(new Condition(key, entry.get("is").getAsString(), false));
                } else if (entry.has("not")) {
                    conditions.add(new Condition(key, entry.get("not").getAsString(), true));
                } else {
                    // 只写了关系键、没有比较值：方速那边是「取出来当布尔用」，等于恒真。
                    always = true;
                }
            } else if (condition.isJsonObject()) {
                for (final Map.Entry<String, JsonElement> test : condition.getAsJsonObject().entrySet()) {
                    if (!test.getValue().isJsonObject()) {
                        continue;
                    }
                    final JsonObject value = test.getValue().getAsJsonObject();
                    if (value.has("is")) {
                        conditions.add(new Condition(test.getKey(), value.get("is").getAsString(), false));
                    } else if (value.has("not")) {
                        conditions.add(new Condition(test.getKey(), value.get("not").getAsString(), true));
                    }
                }
            } else {
                continue;
            }
            rules.add(new AutoRule(List.copyOf(conditions), always,
                    entry.has("left") ? entry.get("left").getAsString() : null,
                    entry.has("right") ? entry.get("right").getAsString() : null));
        }
        return List.copyOf(rules);
    }

    /** 读一侧（{@code glass.left} 或 {@code glass.right}）的形态列表。 */
    private static List<Form> forms(final JsonObject glass, final String side) {
        final List<Form> forms = new ArrayList<>(4);
        if (!glass.has(side)) {
            return forms;
        }
        for (final JsonElement element : glass.getAsJsonArray(side)) {
            final JsonObject entry = element.getAsJsonObject();
            // "auto" 那条是「按邻居自动选形态」的规则，没有 subModel / shape —— 跳过。
            if (!entry.has("id") || !entry.has("subModel") || !entry.has("shape")) {
                continue;
            }
            forms.add(new Form(entry.get("id").getAsString(), entry.get("subModel").getAsString(),
                    FangsuPsdDef.boxes(entry.getAsJsonArray("shape"))));
        }
        return List.copyOf(forms);
    }

    /** 默认形态下标（{@code id == "glass"} 那一条；没有就用第 0 条）。 */
    private static int defaultOf(final List<Form> forms) {
        for (int i = 0; i < forms.size(); i++) {
            if (DEFAULT_KEY.equals(forms.get(i).key())) {
                return i;
            }
        }
        return 0;
    }
}
