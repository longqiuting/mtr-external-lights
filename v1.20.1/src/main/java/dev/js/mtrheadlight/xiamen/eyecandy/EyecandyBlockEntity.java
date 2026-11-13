// eyecandy 的方块实体：不带任何数据，纯粹是「这个位置要画一份 OBJ 网格」的锚点。
package dev.js.mtrheadlight.xiamen.eyecandy;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * eyecandy 的方块实体。<b>没有 NBT、没有状态</b>：模型、贴图、摆放参数全在方块的
 * {@link EyecandyEntry} 上，渲染器每次自己从方块实例读。
 *
 * <h2>为什么要有方块实体</h2>
 * <p>几何是运行时按 {@code eyecandies/*.obj} 画的（OBJ 里的网格原版模型格式表达不了），
 * 而 BER 只能挂在方块实体上，所以补一个空壳。
 *
 * <h2>构造器必须容忍 {@code null} BlockState</h2>
 * <p>{@code BlockItem} 在物品栏 / 手持渲染时会走 {@code createBlockEntity(pos, null)}，
 * 这里兜底成 {@code Blocks.AIR.getDefaultState()}，否则打开背包就 NPE（PIDS 那边踩过一次）。
 */
public class EyecandyBlockEntity extends BlockEntity {

    public EyecandyBlockEntity(final BlockPos pos, final BlockState state) {
        super(EyecandyBlocks.entityType(), pos, state == null ? Blocks.AIR.getDefaultState() : state);
    }
}
