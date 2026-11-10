package dev.js.mtrheadlight.signal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 方速（fangsu）信号灯的识别与「能不能绑」的判定依据。
 *
 * <h2>结论（先看这里）</h2>
 * <p><b>方速交通灯不能参与「节点 → 信号灯」绑定。</b>不是没努力，是它的信号来源和 MTR 的
 * {@code RenderSignalBase} 完全不同：它不按节点取 aspect，而是<b>拿自己方块坐标 + 自己的朝向角</b>
 * 现场算一次「这段轨道有没有车」，而这个算法在方速里是给 JS 脚本层用的
 * （{@code com.fangsu.scripting.MinecraftClientUtil#getOccupiedAspect}），全 jar 无 Java 调用点。
 *
 * <h2>反编译证据（本机 {@code mods/[方速MTR扩展] [MTR3]fangsu-mc1.20.1-FABRIC-1.3.2-build20261102090023.jar}，
 * 与 {@code temp/fangsu.jar} 同一文件，62016527 字节）</h2>
 *
 * <p><b>1. 方块与方块实体</b>
 * <pre>
 * com.fangsu.blocks.BlockTrafficLight  extends com.fangsu.blocks.BaseObjBlock
 *     com.fangsu.blocks.BaseObjBlock   extends net.minecraft.class_2383   // HorizontalFacingBlock
 *     // 不是 mtr.block.BlockSignalLightBase / BlockSignalSemaphoreBase —— 继承链上没有任何 MTR 信号灯类型
 * com.fangsu.blockEntities.BlockEntityTrafficLight extends com.fangsu.blockEntities.FunctionalObjBlockEntity
 *     com.fangsu.blockEntities.BaseObjBlockEntity extends net.minecraft.class_2586  // BlockEntity
 *     // 没有 TileEntitySignalLightXAspectY 那套「空壳」，也没有对 BlockNode 的扫描
 * 方块 id：{@code assets/fangsu/blockstates/traffic_light.json} → {@code fangsu:traffic_light}
 *         （语言文件 {@code block.fangsu.traffic_light} = 交通灯）
 * </pre>
 *
 * <p><b>2. 「MTR 信号联动」这一档存在，但 1.3.2 里没有实现</b>
 * <pre>
 * BlockEntityTrafficLight.getConfigs():
 *   EnumConfig(label=modular.fangsu.tl.dispType, spec="list",
 *              values=List.of("program", "mtr"),
 *              labels=List.of(modular.fangsu.tl.dispProgram, modular.fangsu.tl.dispMtr))
 *   // zh_cn: "程序控制" / "MTR信号联动"
 *
 * BlockEntityTrafficLight.whenRendering():
 *   22: ldc "program"                     // 只有这一档会驱动灯色
 *   25: getfield dispType
 *   28: String.equals  →  ifeq 38         // 不相等（即 mtr）直接跳过
 *   35: invokevirtual drive()             // drive(): TrafficLightProgram.tick +
 *                                         //          getLightStates() → 写 this.lightState
 *   38: … 只负责把 subModelDmhs 按 lightState 画出来
 * </pre>
 * 也就是说 {@code dispType="mtr"} 只是把「积木程序」关掉：没有任何代码往 {@code lightState} 里写值，
 * 而 {@code lightState.get(subModel) == null} 的灯珠在渲染循环里被跳过（offset 123：{@code ifnull}
 * → 跳过），所以该档位下红/黄/绿灯珠一律不亮。全 jar 里 {@code setLight} 的唯一调用者在
 * {@code TrafficLightProgram}（积木语句 {@code TYPE_SET_LIGHT}）里。
 *
 * <p><b>3. 方速内部唯一的「MTR 信号」接口，签名里没有节点</b>
 * <pre>
 * com.fangsu.scripting.MinecraftClientUtil#getOccupiedAspect(
 *         com.fangsu.render.sowcer.math.Vector3f pos, float angleDeg, int depth) : int
 *   0: Vector3f.toBlockPos()              // 起点 = 调用方给的方块坐标（交通灯自己）
 *   14: map.put(blockPos, angleDeg)       // 起点 + 朝向角
 *   33..294: while (dist &lt; depth)
 *     getstatic mtr/client/ClientData.RAILS
 *       rail.facingStart.similarFacing(angle)
 *       ClientData.SIGNAL_BLOCKS.isOccupied(PathData.getRailProduct(pos, railPos)) → return dist
 *       ClientData.OCCUPIED_RAILS.get(...) → return dist
 *       next.put(railPos, rail.facingEnd.getOpposite().angleDegrees)
 * </pre>
 * 这就是 MTR {@code RenderSignalBase#getOccupiedAspect} 的复制品，但<b>起点固定是传进来的那个
 * BlockPos</b>，没有「节点」形参，也没有「绑定表」概念；而且
 * {@code grep -ral getOccupiedAspect com/fangsu/} 只命中它自己的定义 —— 没有调用点。
 * 所以既没有可以替换坐标的地方（我们改不了它的扫描起点），也没有「每帧会走到」的入口可供 mixin。
 *
 * <h2>因此这里的做法</h2>
 * <p>仍然认得出这个方块（按 registry id，不引入编译期 {@code com.fangsu} 依赖），这样右键它时能给出
 * <b>具体原因</b>，而不是让玩家以为「点歪了」。绑定表里不会写入任何方速交通灯：写进去也没有消费者，
 * 那才是真正的假功能。
 */
public final class FangsuSignalCompat {

    private FangsuSignalCompat() {
    }

    /**
     * @return 该方块是不是方速的交通灯（{@code fangsu:traffic_light}，或模块化家族
     *         {@code fangsu:modular_traffic_light}）。
     *
     * <p>用 registry id 判断而不是 {@code instanceof}：方速不在 {@code libs/} 里，本模组故意不产生
     * 对 {@code com.fangsu.*} 的编译期依赖（与 {@code mixin/FangsuMixinPlugin} 的 {@code Class.forName}
     * 探针同一个原则）。id 判断也不会有类加载风险。
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
