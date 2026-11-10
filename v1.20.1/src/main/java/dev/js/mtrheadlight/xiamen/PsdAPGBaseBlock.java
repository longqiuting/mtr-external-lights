// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * MTR {@code mtr.block.BlockPSDAPGBase} 的移植 —— 屏蔽门 / 玻璃 / 端部的公共几何。
 *
 * <p>设置逐字照抄 MTR 的构造器：{@code create().nonOpaque().strength(2.0f).dropsNothing()}
 * （所以打掉不掉落，和 MTR 一致；要收回物品请用中键选取）。
 *
 * <p>选择框按 facing 旋转，厚度 4px（MTR 用的就是这个数，不是板子的真实 2px）。
 * 相机碰撞是空的（{@code getCameraCollisionShape}），所以贴着屏蔽门不会把镜头推开。
 */
public abstract class PsdAPGBaseBlock extends PsdDoubleBlock {

    protected PsdAPGBaseBlock() {
        super(AbstractBlock.Settings.create()
                .nonOpaque()
                .strength(2.0F)
                .dropsNothing());
    }

    @Override
    public ItemStack getPickStack(BlockView world, BlockPos pos, BlockState state) {
        return new ItemStack(this.asItem());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        int height = this.isAPG() && PsdProps.prop(state, PsdProps.HALF) == DoubleBlockHalf.UPPER ? 9 : 16;
        return PsdProps.shapeByDirection(0.0D, 0.0D, 0.0D, 16.0D, height, 4.0D,
                PsdProps.prop(state, FACING));
    }

    @Override
    public VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    /** MTR 的 {@code isAPG()}：APG 系列的上段比下段矮（9px）。厦门这套全是 PSD，所以恒为 false。 */
    protected boolean isAPG() {
        return false;
    }

    static Direction facing(BlockState state) {
        return PsdProps.prop(state, FACING);
    }
}
