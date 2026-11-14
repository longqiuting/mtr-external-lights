// 充电宝租借机的方块实体：纯锚点（几何由 BER 画），上下两块各一个。
package dev.js.mtrheadlight.xiamen.charger;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 充电宝租借机的方块实体。<b>没有数据、没有 NBT</b>：朝向在 {@code BlockState} 上，
 * 「画上半还是下半」由方块实例自己给（{@link ChargerBlock#rawModel()}），渲染器每次现读。
 *
 * <p>存在的原因只有一条：世界里的几何是 {@link ChargerRenderer} 运行时画的
 * （{@code rawmodels/charger_{top,bottom}.json}），而方块实体渲染器必须挂在一个方块实体上。
 *
 * <p>构造器容忍 {@code null} BlockState（{@code BlockItem} 在物品栏里也会问一次）。
 */
public class ChargerBlockEntity extends BlockEntity {

    public ChargerBlockEntity(final BlockPos pos, final BlockState state) {
        super(ChargerObjects.blockEntityType(), pos, state == null ? Blocks.AIR.getDefaultState() : state);
    }
}
