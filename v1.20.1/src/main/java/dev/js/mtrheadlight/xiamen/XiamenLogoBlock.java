package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * 厦门地铁标志：贴墙上的一面 2px 厚的标志牌，四面朝向（{@code facing}）。
 *
 * <h2>照抄了 MTR 原版 logo 方块（{@code mtr:logo}）的哪几处</h2>
 * <p>反编译证据（MTR 3.6.4.2，{@code mtr/Blocks.class}）：
 * <pre>{@code
 * public static final RegistryObject<Block> LOGO = new RegistryObject(() -> new Block(
 *         AbstractBlock.Settings.create()   // method_9637
 *                 .requiresTool()           // method_29292
 *                 .hardness(2.0f)           // method_9632
 *                 .luminance(state -> 10)));// method_9631
 * }</pre>
 * <ul>
 *   <li><b>普通方块 + 方块模型</b>：MTR 的 logo 就是一个 {@code net.minecraft.block.Block}，
 *       <b>没有 BlockEntity、没有 BER</b>（{@code BlockEntityTypes} 里没有 logo）。
 *       所以本模组这块也一样——纯 JSON 模型，不写渲染器。</li>
 *   <li><b>贴图是静态的</b>：{@code assets/mtr/textures/block/sign/logo.png}（128×128，
 *       背景 alpha=0）。不是字体、不是动态文本，所以可以原样换成厦门地铁的标志图。</li>
 *   <li><b>自发光</b>：{@code luminance(state -> 10)} —— 方块自己发 10 级光；
 *       叠出来的那一层 quad 用 {@code "shade": false}（见 {@code mtr:block/block_overlay_glowing_base}），
 *       于是标志那层不受方向明暗影响，看起来是亮的。本类把 10 这个数字原样照抄，
 *       模型里也照抄了「底板 + 0.05 外扩的无阴影覆盖层」这个结构。</li>
 *   <li><b>渲染层</b>：MTR 在 {@code MTRClient} 里给 {@code Blocks.LOGO} 注册
 *       {@code RenderLayer.getCutout()}（{@code RegistryClient.registerBlockRenderType}），
 *       因为 logo 贴图外面一圈是透明的。本模组在 {@link XiamenClient} 里同样处理。</li>
 * </ul>
 *
 * <h2>和 MTR 原版不一样的一处（故意的）</h2>
 * <p>MTR 的 logo 是 <b>整格立方体</b>（模型 0..16 满格，四面都贴 logo，blockstate 只有一个
 * 变体、没有 {@code facing}）。本方块按需求做成 <b>贴墙的 2px 薄牌 + 四向朝向</b>：
 * 只能挂在实心方块侧面，支撑块没了就掉。除了「薄」和「有 facing」，别的做法都跟着 MTR。
 *
 * <h2>朝向 / 几何</h2>
 * <p>模型按 MTR 的惯例写：<b>默认（y=0）= facing=west</b>，板在 x∈[0,2]、标志面朝 −X。
 * 旋转按 MTR 的 blockstate：north → y=90、east → y=180、south → y=270
 * （即 y=90 把「西边缘」转到「北边缘」，和 {@link XiamenPsdBaseBlock} 用的同一套映射）。
 */
public class XiamenLogoBlock extends Block {

    /** 朝向 = 标志<b>面向</b>的方向；背后（{@code facing.getOpposite()}）必须是实心方块。 */
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    /**
     * 四面各自的板体形状（厚度 2px），下标 = {@link #shapeIndex(Direction)}。
     * 与 blockstate 的 y 旋转一一对应：
     * <ul>
     *   <li>west（y=0）：板在 x∈[0,2]</li>
     *   <li>north（y=90）：{@code x' = 16-z, z' = x} → 板在 z∈[0,2]</li>
     *   <li>east（y=180）：板在 x∈[14,16]</li>
     *   <li>south（y=270）：板在 z∈[14,16]</li>
     * </ul>
     */
    private static final VoxelShape[] SHAPES = {
            createCuboidShape(0.0D, 0.0D, 0.0D, 2.0D, 16.0D, 16.0D),   // west
            createCuboidShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 2.0D),   // north
            createCuboidShape(14.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D), // east
            createCuboidShape(0.0D, 0.0D, 14.0D, 16.0D, 16.0D, 16.0D)  // south
    };

    public XiamenLogoBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 放置时标志正对玩家（墙上的挂法，和告示牌一致）。 */
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState state = this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        return state.canPlaceAt(ctx.getWorld(), ctx.getBlockPos()) ? state : null;
    }

    /** 背后必须有实心面撑着（贴墙）。 */
    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        Direction back = state.get(FACING).getOpposite();
        return world.getBlockState(pos.offset(back)).isSideSolidFullSquare(world, pos, state.get(FACING));
    }

    /** 背后的支撑块被拆掉时自己掉下来。 */
    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return direction.getOpposite() == state.get(FACING) && !state.canPlaceAt(world, pos)
                ? Blocks.AIR.getDefaultState()
                : state;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[shapeIndex(state.get(FACING))];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[shapeIndex(state.get(FACING))];
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.with(FACING, mirror.apply(state.get(FACING)));
    }

    private static int shapeIndex(Direction facing) {
        return switch (facing) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 3;
            default -> 0; // WEST
        };
    }
}
