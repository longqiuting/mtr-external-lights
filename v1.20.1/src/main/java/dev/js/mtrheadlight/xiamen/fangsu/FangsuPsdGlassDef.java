// 方速「厦门屏蔽门玻璃」的一个变体 —— 一个方块 + 多种形态（刷子循环）。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.util.Identifier;

/**
 * 方速厦门屏蔽门的<b>固定玻璃板</b>：一个方块，<b>多种形态</b>，刷子右键循环。
 *
 * <h2>为什么是「多形态」</h2>
 * <p>方速开发文档 {@code screendoor_glass}（{@code https://doc.fangsu.top/#/screendoor_glass}）写得很清楚：
 * 屏蔽门的玻璃和门放在<b>同一个 {@code .obj}</b> 里，靠 {@code o} 组区分；配置里
 * {@code glass.left[] / glass.right[]} 列的每一条就是<b>一种形态</b>：
 * <pre>
 *   { "id": "door",     "subModel": "glass_left_door",      "shape": [[15,0,12,16,48,14],[8,0,12,16,48,13]] }
 *   { "id": "doorend",  "subModel": "glass_left_door_end",  ... }
 *   { "id": "glass",    "subModel": "glass_left_glass",     ... }
 *   { "id": "longend",  "subModel": "glass_left_long_end",  ... }
 *   { "id": "auto",     "auto": "auto" }                       ← 规则，没有几何
 * </pre>
 * 文档对 {@code subModel} 的定义是「子模型的名称」= {@code .obj} 里的 {@code o} 组名，{@code shape}
 * 是这一形态的碰撞箱（1/16 格，3 格高的板就是 y 到 48）。所以「多种形态」不是贴图切换，而是
 * <b>同一个方块换一组几何 + 一组碰撞盒</b>。
 *
 * <h2>形态从哪来（枚举）</h2>
 * <p>{@code screendoor/xiamen/high.json} 里 {@code glass.left} 与 {@code glass.right} 各 4 条真形态
 * （{@code auto} 那条没有 {@code subModel} / {@code shape}，跳过）：
 * <table border="1">
 *   <caption>4 种形态（左右各一份，组名带 left_ / right_ 前缀）</caption>
 *   <tr><th>id</th><th>{@code o} 组（左）</th><th>是什么</th></tr>
 *   <tr><td>{@code door}</td><td>{@code glass_left_door}</td><td>带玻璃的门扇那一格</td></tr>
 *   <tr><td>{@code doorend}</td><td>{@code glass_left_door_end}</td><td>门旁边的端板</td></tr>
 *   <tr><td>{@code glass}</td><td>{@code glass_left_glass}</td><td>纯玻璃板（<b>默认</b>）</td></tr>
 *   <tr><td>{@code longend}</td><td>{@code glass_left_long_end}</td><td>长端板</td></tr>
 * </table>
 *
 * <h2>和方速的差别（有意的）</h2>
 * <p>方速那边一个玻璃方块<b>同时</b>画左半和右半（{@code subModelLeft} / {@code subModelRight}
 * 两个选项，界面里选），还带一套「按邻居自动选形态」的规则表（{@code glass.auto[]}）。我们没有配置界面，
 * 也把左右拆成了两个方块（{@code ..._glass_left} / {@code ..._glass_right}，和 14 块门扇同一个约定），
 * 所以：<b>每个方块只画自己那一半</b>，形态用<b>刷子右键循环</b>（和 eyecandy 以前的四向旋转同一个操作手势）。
 * {@code auto} 规则没有搬 —— 想要「自动」就得复刻邻居扫描，收益不大，手动循环更好预测。
 *
 * @param id          方块 id 的路径部分（{@code fangsu_psd_screendoor_high_glass_left}）
 * @param nameZh      中文名
 * @param nameEn      英文名
 * @param model       {@code .obj} 的 id
 * @param flipV       是否翻转 v 轴（素材写 {@code true}）
 * @param forms       形态列表（顺序 = 素材里的顺序 = 刷子循环顺序）
 * @param defaultForm 默认形态的下标（素材里 {@code id == "glass"} 那一条）
 */
public record FangsuPsdGlassDef(String id, String nameZh, String nameEn, Identifier model, boolean flipV,
                                List<Form> forms, int defaultForm) {

    /**
     * 一种形态。
     *
     * @param key  素材里的 {@code id}（{@code door} / {@code doorend} / {@code glass} / {@code longend}）
     * @param group {@code .obj} 里的 {@code o} 组名（{@code glass_left_door} …）
     * @param shape 这一形态的碰撞盒（1/16 格、可超过 16 —— 3 格高的板 y 到 48）
     */
    public record Form(String key, String group, List<double[]> shape) {
    }

    /** 有玻璃组的滑动门款式。只有 {@code high}：{@code door_low.obj} 里没有 {@code glass_*} 组。 */
    private static final String[] FILES = {"high"};

    private static final String[] SIDES = {"left", "right"};

    /** 默认形态：素材里的纯玻璃板。 */
    private static final String DEFAULT_KEY = "glass";

    /** 形态 id → 显示名的 lang key（{@code block.mtrlte.fangsu_psd_glass_form.<id>}）。 */
    public static String langKey(final String formKey) {
        return "block.mtrlte.fangsu_psd_glass_form." + formKey;
    }

    /** 第 {@code index} 种形态（越界时夹到合法范围，调用方不用自己判空）。 */
    public Form form(final int index) {
        return this.forms.get(Math.max(0, Math.min(this.forms.size() - 1, index)));
    }

    /**
     * 读全部玻璃板变体（现在只有「厦门高」一款 × 左右 = 2 个方块）。
     *
     * <p>读不到 / 解析失败只记一行日志并跳过 —— 少两块板，不影响门扇。
     */
    public static List<FangsuPsdGlassDef> load() {
        final List<FangsuPsdGlassDef> out = new ArrayList<>(FILES.length * SIDES.length);
        for (final String file : FILES) {
            try {
                loadOne(file, out);
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] fangsu psd glass: could not read {}screendoor/xiamen/{}.json ({})",
                        FangsuPsdDef.DIR, file, t.toString());
            }
        }
        return out;
    }

    private static void loadOne(final String file, final List<FangsuPsdGlassDef> out) {
        final JsonObject root = FangsuPsdDef.read(FangsuPsdDef.DIR + "screendoor/xiamen/" + file + ".json");
        if (!root.has("glass")) {
            return;
        }
        final Identifier model = new Identifier(root.get("model").getAsString());
        final boolean flipV = !root.has("flipV") || root.get("flipV").getAsBoolean();
        final JsonObject glass = root.getAsJsonObject("glass");
        final String label = file.equals("low") ? "低" : "高";
        final String labelEn = file.equals("low") ? "low" : "high";

        for (final String side : SIDES) {
            if (!glass.has(side)) {
                continue;
            }
            final List<Form> forms = new ArrayList<>(4);
            int defaultForm = 0;
            for (final JsonElement element : glass.getAsJsonArray(side)) {
                final JsonObject entry = element.getAsJsonObject();
                // "auto" 那条是「按邻居自动选形态」的规则，没有 subModel / shape —— 跳过。
                if (!entry.has("id") || !entry.has("subModel") || !entry.has("shape")) {
                    continue;
                }
                final String key = entry.get("id").getAsString();
                if (DEFAULT_KEY.equals(key)) {
                    defaultForm = forms.size();
                }
                forms.add(new Form(key, entry.get("subModel").getAsString(),
                        FangsuPsdDef.boxes(entry.getAsJsonArray("shape"))));
            }
            if (forms.isEmpty()) {
                continue;
            }
            final boolean isLeft = side.equals("left");
            out.add(new FangsuPsdGlassDef(
                    "fangsu_psd_screendoor_" + file + "_glass_" + side,
                    "方速屏蔽门玻璃 厦门" + label + "（" + (isLeft ? "左" : "右") + "）",
                    "Fangsu platform screen door glass Xiamen " + labelEn
                            + (isLeft ? " (left)" : " (right)"),
                    model, flipV, List.copyOf(forms), defaultForm));
        }
    }
}
