// 闸机的方块实体：不带任何数据，纯粹是「这个位置要画一份原始模型」的锚点。
package dev.js.mtrheadlight.xiamen.ticket;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 闸机的方块实体。<b>没有任何数据、没有 NBT</b>：开 / 关、朝向全都在 {@code BlockState} 上
 * （{@code OPEN} / {@code FACING}），渲染器每次自己从世界读。
 *
 * <h2>为什么还是要有方块实体</h2>
 * <p>闸机的几何来自 {@code assets/mtrlte/rawmodels/*.json}（Blockbench 原样导出，含
 * {@code -11.5 / 3.25 / 44} 这类任意角与三轴旋转），原版 blockstate 模型画不出来 ——
 * 必须走 {@link XiamenTicketGateRenderer} 这个 BER。BER 只能挂在方块实体上，所以补一个空壳。
 * 状态不放这里的好处是：门一开一关 {@code setBlockState} 立刻生效，不会出现「方块实体里的
 * 状态和 BlockState 不一致」的经典 bug。
 *
 * <h2>构造器必须容忍 {@code null} BlockState</h2>
 * <p>{@code BlockItem} 在物品栏 / 手持渲染时会走 {@code createBlockEntity(pos, null)}，
 * 这里直接兜底成 {@code Blocks.AIR.getDefaultState()}，否则打开背包就 NPE（PIDS 那边踩过一次）。
 */
public class XiamenTicketGateBlockEntity extends BlockEntity {

    public XiamenTicketGateBlockEntity(final BlockPos pos, final BlockState state) {
        super(XiamenTicketGates.entityType(), pos, state == null ? Blocks.AIR.getDefaultState() : state);
    }
}
