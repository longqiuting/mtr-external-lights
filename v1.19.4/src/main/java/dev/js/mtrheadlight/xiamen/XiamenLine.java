// 厦门地铁线路：同一套屏蔽门几何，三种线路配色。
package dev.js.mtrheadlight.xiamen;

/**
 * 厦门地铁 1 / 2 / 3 号线 —— <b>屏蔽门几何完全一样，只有贴图（线路色 / 站名横幅）不同</b>。
 *
 * <p>这个枚举就是「一条线路」的全部差异，别处不再写 {@code "_l2"} 这种字面量：
 *
 * <table border="1">
 *   <caption>三个常量对应的资源命名</caption>
 *   <tr><th>常量</th><th>{@link #suffix}</th><th>方块 id</th><th>贴图文件</th></tr>
 *   <tr><td>{@link #L1}</td><td>{@code ""}</td><td>{@code psd_door_xiamen}</td>
 *       <td>{@code psd_door_top_left_1.png}（资源包原始命名）</td></tr>
 *   <tr><td>{@link #L2}</td><td>{@code "_l2"}</td><td>{@code psd_door_xiamen_l2}</td>
 *       <td>{@code psd_door_top_left_1_l2.png}</td></tr>
 *   <tr><td>{@link #L3}</td><td>{@code "_l3"}</td><td>{@code psd_door_xiamen_l3}</td>
 *       <td>{@code psd_door_top_left_1_l3.png}</td></tr>
 * </table>
 *
 * <p><b>为什么后缀要插在 {@code .png} 前面、而不是加目录</b>：MTR 的 {@code RenderPSDAPGDoor#render}
 * 里贴图 id 是写死的 {@code "mtr:textures/block/psd_door_%s_%s_%s.png"}，
 * {@link XiamenTextures} 只能在这个字符串后面动手；统一「路径 + 后缀 + .png」的规则，
 * 一份改写逻辑就能覆盖全高 PSD 与半高 APG 的所有贴图（含门灯）。
 *
 * <p>方块 id 的 {@code psd_} / {@code apg_} 前缀是硬要求：MZYC better MTR 靠它认出门方块。
 * 后缀加在<b>最后</b>（{@code psd_door_xiamen_l2}），前缀不受影响。
 */
public enum XiamenLine {

    /** 1 号线（也是最原始的那一套，贴图用不带后缀的文件名）。 */
    L1("", "1"),
    /** 2 号线。 */
    L2("_l2", "2"),
    /** 3 号线。 */
    L3("_l3", "3");

    /** id / 贴图文件名共用的后缀（1 号线为空串）。 */
    public final String suffix;

    /** 线路号（日志 / 语言键用）。 */
    public final String number;

    XiamenLine(final String suffix, final String number) {
        this.suffix = suffix;
        this.number = number;
    }

    /** 把后缀拼到基础 id 后面：{@code psd_door_xiamen} → {@code psd_door_xiamen_l2}。 */
    public String id(final String base) {
        return base + this.suffix;
    }

    /**
     * 贴图变体后缀，给 {@link XiamenTextures#withVariant} 用。
     *
     * <p>1 号线返回 {@code null}（= 用不带后缀的原始文件名，行为与加线路前逐字节一致）。
     */
    public String textureVariant() {
        return this.suffix.isEmpty() ? null : this.suffix;
    }

    @Override
    public String toString() {
        return "xiamen L" + this.number;
    }
}
