// 厦门地铁线路：同一套屏蔽门几何，五种线路配色（外加每种线路自己的门灯颜色）。
package dev.js.mtrheadlight.xiamen;

/**
 * 厦门地铁 1 / 2 / 3 / 4 / 6 号线 —— <b>屏蔽门几何完全一样，只有贴图（线路色 / 站名横幅）不同</b>。
 *
 * <p>这个枚举就是「一条线路」的全部差异，别处不再写 {@code "_l4"} 这种字面量：
 *
 * <table border="1">
 *   <caption>五个常量对应的资源命名</caption>
 *   <tr><th>常量</th><th>{@link #suffix}</th><th>方块 id</th><th>贴图文件</th><th>门灯</th></tr>
 *   <tr><td>{@link #L1}</td><td>{@code ""}</td><td>{@code psd_door_xiamen}</td>
 *       <td>{@code psd_door_top_left_1.png}（资源包原始命名）</td><td>橙</td></tr>
 *   <tr><td>{@link #L2}</td><td>{@code "_l2"}</td><td>{@code psd_door_xiamen_l2}</td>
 *       <td>{@code psd_door_top_left_1_l2.png}</td><td>橙</td></tr>
 *   <tr><td>{@link #L3}</td><td>{@code "_l3"}</td><td>{@code psd_door_xiamen_l3}</td>
 *       <td>{@code psd_door_top_left_1_l3.png}</td><td>橙</td></tr>
 *   <tr><td>{@link #L4}</td><td>{@code "_l4"}</td><td>{@code psd_door_xiamen_l4}</td>
 *       <td>{@code psd_door_top_left_1_l4.png}</td><td><b>绿</b></td></tr>
 *   <tr><td>{@link #L6}</td><td>{@code "_l6"}</td><td>{@code psd_door_xiamen_l6}</td>
 *       <td>{@code psd_door_top_left_1_l6.png}</td><td><b>绿</b></td></tr>
 * </table>
 *
 * <p><b>为什么后缀要插在 {@code .png} 前面、而不是加目录</b>：MTR 的 {@code RenderPSDAPGDoor#render}
 * 里贴图 id 是写死的 {@code "mtr:textures/block/psd_door_%s_%s_%s.png"}，
 * {@link XiamenTextures} 只能在这个字符串后面动手；统一「路径 + 后缀 + .png」的规则，
 * 一份改写逻辑就能覆盖全高 PSD 与半高 APG 的所有贴图（含门灯）。
 *
 * <p>方块 id 的 {@code psd_} / {@code apg_} 前缀是硬要求：MZYC better MTR 靠它认出门方块。
 * 后缀加在<b>最后</b>（{@code psd_door_xiamen_l4}），前缀不受影响。
 *
 * <p><b>门灯为什么写在枚举里</b>：门灯颜色是「这条线路的规矩」（1/2/3 橙、4/6 绿），
 * 而且门方块实例本身就知道自己属于哪条线路（{@link XiamenObjects#lineOf}），
 * 所以按<b>方块</b>取颜色是确定性的，不需要再去查「门边上最近的站台属于哪条线」。
 * 配置 {@code doorLightByLine} / {@code doorLightColor} 仍然可以覆盖它，
 * 优先级见 {@link XiamenDoorLights#colorFor(net.minecraft.block.Block, net.minecraft.util.math.BlockPos)}。
 */
public enum XiamenLine {

    /** 1 号线（也是最原始的那一套，贴图用不带后缀的文件名）。 */
    L1("", "1", "orange"),
    /** 2 号线。 */
    L2("_l2", "2", "orange"),
    /** 3 号线。 */
    L3("_l3", "3", "orange"),
    /** 4 号线（门灯绿）。 */
    L4("_l4", "4", "green"),
    /** 6 号线（门灯绿）。 */
    L6("_l6", "6", "green");

    /** id / 贴图文件名共用的后缀（1 号线为空串）。 */
    public final String suffix;

    /** 线路号（日志 / 语言键 / 门灯配置键用）。 */
    public final String number;

    /** 这条线路门灯的内置颜色：{@code orange} / {@code green}（配置可覆盖）。 */
    private final String doorLight;

    XiamenLine(final String suffix, final String number, final String doorLight) {
        this.suffix = suffix;
        this.number = number;
        this.doorLight = doorLight;
    }

    /** 把后缀拼到基础 id 后面：{@code psd_door_xiamen} → {@code psd_door_xiamen_l4}。 */
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

    /** 这条线路门灯的内置颜色（{@code orange} / {@code green}），配置未覆盖时用它。 */
    public String doorLight() {
        return this.doorLight;
    }

    @Override
    public String toString() {
        return "xiamen L" + this.number;
    }
}
