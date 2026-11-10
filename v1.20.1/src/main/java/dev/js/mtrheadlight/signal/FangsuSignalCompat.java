package dev.js.mtrheadlight.signal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 方速（fangsu）信号灯的识别：这个方块是不是「交通灯」，以及能不能当信号灯绑。
 *
 * <h2>结论（先看这里）</h2>
 * <p>方速交通灯<b>可以</b>参与「节点 → 信号灯」绑定，但必须做两件事，缺一不可：
 * <ol>
 *   <li><b>切到 mtr 档</b>（{@code dispType="mtr"}）—— 由
 *       {@link FangsuTrafficLightMode} 在右键那次点击里完成；</li>
 *   <li><b>替它写 {@code lightState}</b> —— mtr 档在方速 1.3.2 里是空实现，红/黄/绿永远不会亮，
 *       由客户端 mixin {@code FangsuTrafficLightRenderMixin} →
 *       {@link FangsuSignalSync} 每帧按绑定节点的 aspect 补上。</li>
 * </ol>
 *
 * <h2>反编译证据（{@code mods/[方速MTR扩展] [MTR3]fangsu-mc1.20.1-FABRIC-1.3.2-build20261102090023.jar}，
 * 与 {@code temp/fangsu.jar} 同一文件，62016527 字节；CFR 0.152 全文）</h2>
 *
 * <p><b>1. 方块与方块实体</b>
 * <pre>
 * com.fangsu.blocks.BlockTrafficLight  extends com.fangsu.blocks.BaseObjBlock
 *     com.fangsu.blocks.BaseObjBlock   extends net.minecraft.class_2383   // HorizontalFacingBlock
 *     // 不是 mtr.block.BlockSignalLightBase / BlockSignalSemaphoreBase —— 继承链上没有任何 MTR 信号灯类型
 * com.fangsu.blockEntities.BlockEntityTrafficLight extends com.fangsu.blockEntities.FunctionalObjBlockEntity
 *     com.fangsu.blockEntities.BaseObjBlockEntity extends net.minecraft.class_2586  // BlockEntity
 * 方块 id：{@code assets/fangsu/blockstates/traffic_light.json} → {@code fangsu:traffic_light}
 *         （语言文件 {@code block.fangsu.traffic_light} = 交通灯；模块化家族另有 modular_traffic_light）
 * </pre>
 *
 * <p><b>2. 「MTR 信号联动」这一档存在，但 1.3.2 里没有实现</b>
 * <pre>
 * BlockEntityTrafficLight.getConfigs():
 *   EnumConfig(label=modular.fangsu.tl.dispType, spec="list",
 *              values=List.of("program", "mtr"),          // zh_cn: "程序控制" / "MTR信号联动"
 *              labels=List.of(dispProgram, dispMtr),
 *              getter = () -&gt; max(0, values.indexOf(this.dispType)),
 *              setter = idx -&gt; { this.dispType = values.get(idx);
 *                                extra.put("dispType", this.dispType);
 *                                setConfigChanged(); })
 *
 * BlockEntityTrafficLight.whenRendering():                // line 119
 *   120: if (markedError || !modelLoaded || content == null) return;
 *   123: if ("program".equals(this.dispType)) this.drive();     // &lt;&lt; 唯一的灯色驱动
 *   132: if (!alwaysOn &amp;&amp; (on == null || !on)) continue;      // on = lightState.get(label)
 *   133: ctx.drawModel(dmh, null);
 *
 * 全 jar 写 lightState 的只有三处：
 *   whenLoading()          → 每个非 main 的 subModel 置 false
 *   drive()                → player.getLightStates()（积木程序的 TYPE_SET_LIGHT）
 *   applyDisplayStates()   → 倒计时数字的段
 * </pre>
 * 所以 {@code dispType="mtr"} 的实际作用只是「别跑积木程序」：没有任何代码往 {@code lightState}
 * 写值，灯珠全灭。**这就是我们必须补驱动的原因**，也是上次「mtr 档不可用」结论的出处。
 *
 * <p><b>3. dispType 不参与 NBT 往返（顺带确认的一个方速 bug）</b>
 * <pre>
 * FunctionalObjBlockEntity.method_11007(writeNbt) → extraConfig 复合标签（含 dispType）
 * FunctionalObjBlockEntity.method_11014(readNbt)  → 只灌 extraConfigs map
 * 全 jar grep dispType 只命中 BlockEntityTrafficLight：字段声明 / getConfigs 的 lambda /
 * whenRendering 的判断 —— 没有任何一处从 extraConfigs 恢复字段
 * </pre>
 * 所以重进存档后 client 的 {@code dispType} 会回落成 {@code "program"}。{@link FangsuSignalSync}
 * 因此每帧强制写字段，而不是只在切换那一次写。
 *
 * <p><b>4. 方速自己那份 aspect 计算没有调用点</b>
 * <pre>
 * com.fangsu.scripting.MinecraftClientUtil#getOccupiedAspect(Vector3f pos, float facing, int aspects)
 *   // 是 MTR RenderSignalBase#getOccupiedAspect 的逐字拷贝，但起点写死成传进来的坐标、
 *   // 没有「节点」形参；grep 全 jar 无 Java 调用点（只给 JS 脚本层）。
 * </pre>
 * 我们不反射它：{@link FangsuSignalSync#aspect} 直接调 MTR 的公开 {@code ClientData}，编译期类型安全。
 *
 * <p>这个类本身只保留「认得出是方速交通灯」这件事（按 registry id，不引入编译期
 * {@code com.fangsu} 依赖），给绑定流程与渲染驱动共用。
 */
public final class FangsuSignalCompat {

    private FangsuSignalCompat() {
    }

    /**
     * @return 该方块是不是方速的交通灯（{@code fangsu:traffic_light}，或模块化家族
     *         {@code fangsu:modular_traffic_light}）。
     *
     * <p>用 registry id 判断而不是 {@code instanceof}：方速不在 {@code libs/} 里，本模组故意不产生
     * 对 {@code com.fangsu.*} 的编译期依赖（与 {@code mixin/FangsuMixinPlugin} 的 class 探针同一个原则）。
     * id 判断也不会有类加载风险。
     */
    public static boolean isFangsuTrafficLight(final BlockState state) {
        if (state == null) {
            return false;
        }
        try {
            return isFangsuTrafficLight(state.getBlock());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isFangsuTrafficLight(final Block block) {
        if (block == null) {
            return false;
        }
        try {
            final Identifier id = Registries.BLOCK.getId(block);
            if (id == null || !"fangsu".equals(id.getNamespace())) {
                return false;
            }
            final String path = id.getPath();
            // 1.3.2 的 id 是 traffic_light；模块化家族是 modular_traffic_light（TrafficLightProgramBlocks
            // 的 FAM_TRAFFIC_LIGHT）。用 contains 兜住以后可能出现的命名变体。
            return path.contains("traffic_light");
        } catch (Throwable t) {
            return false;
        }
    }
}
