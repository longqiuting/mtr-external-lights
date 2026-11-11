package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;

/**
 * 厦门地铁标志方块 {@code mtrlte:xiamen_logo} —— <b>与 MTR 原版 {@code mtr:logo} 同构</b>。
 *
 * <h2>反编译证据（MTR 3.6.4.2）</h2>
 * <p>{@code mtr/Blocks.java} 第 117 行，一字不改：
 * <pre>{@code
 * public static final RegistryObject<Block> LOGO = new RegistryObject<>(() -> new Block(
 *         AbstractBlock.Settings.create()   // method_9637
 *                 .requiresTool()           // method_29292
 *                 .hardness(2.0f)           // method_9632
 *                 .luminance(state -> 10)));// method_9631
 * }</pre>
 * <p>注意这里是 <b>{@code net.minecraft.block.Block} 本体</b>，不是 {@code BlockStationName*}
 * 那一族（{@code STATION_NAME_WALL_WHITE / GRAY / BLACK} 才是贴墙薄牌：有 facing、
 * 背后要支撑、掉支撑就掉）。旧版本本模组误照了站名牌，这里改回 logo。
 *
 * <h2>MTR logo 的三个关键属性（本类一一照抄）</h2>
 * <ul>
 *   <li><b>满方块</b>：{@code assets/mtr/models/block/block_overlay_glowing_base.json} 的底板是
 *       {@code 0..16} 的整格立方体，六面 {@code cullface}；标志层是 {@code -0.05..16.05}
 *       外扩 0.05 的覆盖层，只有四个侧面、{@code "shade": false}。</li>
 *   <li><b>无朝向</b>：{@code blockstates/logo.json} 只有一个空变体
 *       {@code "": { "model": "mtr:block/logo" }} —— 没有 {@code facing}，也没有旋转，
 *       四面贴同一张图。</li>
 *   <li><b>任意位置可放</b>：没有 {@code canPlaceAt}、没有 {@code getStateForNeighborUpdate}、
 *       没有 {@code getPlacementState}、没有自定义 {@code VoxelShape}（碰撞/轮廓走满格默认值），
 *       所以天花板、地面、半空、贴着别的方块，放哪都行，也不会因为邻居变化而掉落。</li>
 *   <li><b>自发光 10</b>：{@code luminance(state -> 10)}，与 MTR 同值。</li>
 * </ul>
 *
 * <h2>为什么不改成 {@code new Block(settings)}</h2>
 * <p>行为上和 MTR 的 {@code new Block(...)} 完全等价 —— 本类没有覆写任何方法、没有状态属性、
 * 没有 BlockEntity。留一个空子类只是为了让这个方块的文档和注册点在工程里有个位置，
 * {@code XiamenObjects.logo()} 的返回类型也不必改动。
 *
 * <h2>渲染</h2>
 * <p>MTR 在 {@code MTRClient} 里给 {@code Blocks.LOGO} 挂 {@code RenderLayer.getCutout()}
 * （{@code class_1921.method_23581()}，见 {@code MTRClient.java} 第 109 行），
 * 因为标志贴图外圈是 alpha=0、方块本身又是不透明的满方块。本模组在 {@link XiamenClient} 里同样处理。
 */
public class XiamenLogoBlock extends Block {

    public XiamenLogoBlock(AbstractBlock.Settings settings) {
        super(settings);
    }
}
