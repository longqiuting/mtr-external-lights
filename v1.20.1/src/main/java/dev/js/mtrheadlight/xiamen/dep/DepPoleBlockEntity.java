// 挂顶 DEP 挂杆的方块实体：几何由 BER 画，这里只是个锚点，不存任何数据。
package dev.js.mtrheadlight.xiamen.dep;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 挂顶 DEP 挂杆 {@code mtrlte:dep_pole} 的方块实体。
 *
 * <p>不存任何数据（杆没有状态、没有配置、没有屏幕）：它存在的唯一原因是
 * {@link DepPoleRenderer} 要一个 BER 锚点 —— 几何是运行时从
 * {@code assets/mtrlte/rawmodels/dep_pole.json} 解析出来的，方块本身没有 blockstate 模型。
 *
 * <p>构造器容忍 {@code null} BlockState（{@code BlockItem} 在物品栏里也会问一次）。
 */
public class DepPoleBlockEntity extends BlockEntity {

    public DepPoleBlockEntity(final BlockPos pos, final BlockState state) {
        super(DepPoleObjects.entityType(), pos, state == null ? Blocks.AIR.getDefaultState() : state);
    }
}
