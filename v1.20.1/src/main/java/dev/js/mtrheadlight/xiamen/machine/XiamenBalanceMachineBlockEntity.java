// 余额查询机的方块实体：上下两段各一个，不带数据，只当「这里要画一份原始模型」的锚点。
package dev.js.mtrheadlight.xiamen.machine;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 余额查询机的方块实体。<b>没有数据、没有 NBT</b>：上下段（{@code half=lower|upper}）与朝向都在
 * {@code BlockState} 上，渲染器每次自己从世界读。
 *
 * <h2>为什么要方块实体</h2>
 * <p>几何来自 {@code assets/mtrlte/rawmodels/balance_machine_{bottom,top}.json}
 * （Blockbench 原样导出，屏幕是 {@code x:-12.5} 的斜面），原版模型格式的一个元素只能单轴、
 * 而且上段局部坐标最高 16.91；走 {@link XiamenBalanceMachineRenderer} 就没有这些限制。
 *
 * <p>上下两段是<b>两个方块实体</b>（两个方块位置），各画各的一半 —— 也就是
 * 「上段的模型只画在上段那一格」，下段同理，两格高的机器因此能跨格。
 *
 * <p>构造器容忍 {@code null} BlockState（{@code BlockItem} 会传 null，见 PIDS 那边的注解）。
 */
public class XiamenBalanceMachineBlockEntity extends BlockEntity {

    public XiamenBalanceMachineBlockEntity(final BlockPos pos, final BlockState state) {
        super(XiamenMachines.balanceEntityType(), pos, state == null ? Blocks.AIR.getDefaultState() : state);
    }
}
