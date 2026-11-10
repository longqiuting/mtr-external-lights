// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

/**
 * MTR {@code mtr.block.BlockPSDAPGGlassEndBase} 的移植 —— 一条屏蔽门的最外侧收口。
 *
 * <p>两块属性 {@code touching_left} / {@code touching_right} 记录「这一侧外面是什么」：
 * {@code air}（空的，会补一块转角板）/ {@code door}（接门扇）/ {@code none}（接玻璃）。
 * 转角板由 {@link #getEndOutlineShape} 往选择框里并一块 4px 厚的板，和 MTR 一样。
 */
public abstract class PsdGlassEndBlock extends PsdGlassBlock {

    public static final EnumProperty<GlassEndSide> TOUCHING_LEFT =
            EnumProperty.of("touching_left", GlassEndSide.class);
    public static final EnumProperty<GlassEndSide> TOUCHING_RIGHT =
            EnumProperty.of("touching_right", GlassEndSide.class);

    /** MTR 的 {@code EnumPSDAPGGlassEndSide}。 */
    public enum GlassEndSide implements StringIdentifiable {
        AIR("air"),
        DOOR("door"),
        NONE("none");

        private final String name;

        GlassEndSide(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        BlockState superState = super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
        if (superState.getBlock() == net.minecraft.block.Blocks.AIR) {
            return superState;
        }
        Direction facing = PsdProps.prop(state, FACING);
        GlassEndSide touchingLeft = this.getSideEnd(world, pos, facing.rotateYCounterclockwise());
        GlassEndSide touchingRight = this.getSideEnd(world, pos, facing.rotateYClockwise());
        return superState.with(TOUCHING_LEFT, touchingLeft).with(TOUCHING_RIGHT, touchingRight);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        VoxelShape superShape = super.getOutlineShape(state, world, pos, context);
        int height = this.isAPG() && PsdProps.prop(state, PsdProps.HALF) == DoubleBlockHalf.UPPER ? 9 : 16;
        boolean leftAir = PsdProps.prop(state, TOUCHING_LEFT) == GlassEndSide.AIR;
        boolean rightAir = PsdProps.prop(state, TOUCHING_RIGHT) == GlassEndSide.AIR;
        return getEndOutlineShape(superShape, state, height, 4, leftAir, rightAir);
    }

    /** 门扇 / 吊板也复用它（MTR 里这是 static 的）。 */
    public static VoxelShape getEndOutlineShape(VoxelShape baseShape, BlockState state,
                                                int height, int thickness, boolean leftAir, boolean rightAir) {
        Direction facing = PsdProps.prop(state, FACING);
        if (facing == Direction.NORTH && leftAir || facing == Direction.SOUTH && rightAir) {
            baseShape = VoxelShapes.union(baseShape,
                    Block.createCuboidShape(0.0D, 0.0D, 0.0D, thickness, height, 16.0D));
        }
        if (facing == Direction.EAST && leftAir || facing == Direction.WEST && rightAir) {
            baseShape = VoxelShapes.union(baseShape,
                    Block.createCuboidShape(0.0D, 0.0D, 0.0D, 16.0D, height, thickness));
        }
        if (facing == Direction.SOUTH && leftAir || facing == Direction.NORTH && rightAir) {
            baseShape = VoxelShapes.union(baseShape,
                    Block.createCuboidShape(16 - thickness, 0.0D, 0.0D, 16.0D, height, 16.0D));
        }
        if (facing == Direction.WEST && leftAir || facing == Direction.EAST && rightAir) {
            baseShape = VoxelShapes.union(baseShape,
                    Block.createCuboidShape(0.0D, 0.0D, 16 - thickness, 16.0D, height, 16.0D));
        }
        return baseShape;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, PsdProps.HALF, PsdProps.SIDE_EXTENDED, TOUCHING_LEFT, TOUCHING_RIGHT);
    }

    private GlassEndSide getSideEnd(BlockView world, BlockPos pos, Direction offset) {
        BlockPos checkPos = pos.offset(offset);
        Block block = world.getBlockState(checkPos).getBlock();
        if (block instanceof PsdDoorBlock) {
            return GlassEndSide.DOOR;
        }
        if (block instanceof PsdAPGBaseBlock) {
            return GlassEndSide.NONE;
        }
        return GlassEndSide.AIR;
    }
}
