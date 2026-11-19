// 挂顶 DEP 挂杆：一根竖直的细杆（Blockbench 模型里 10 个任意角旋转的叶片），几何由 BER 画。
package dev.js.mtrheadlight.xiamen.dep;

import dev.js.mtrheadlight.rawmodel.RawModelJson;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * 挂顶 DEP 挂杆 {@code mtrlte:dep_pole}：吊在站台上方的天花板上，用来挂「挂顶发车倒数器」那一类的杆。
 *
 * <h2>为什么几何必须走 {@code rawmodel}</h2>
 * <p>素材（{@code assets/mtrlte/rawmodels/dep_pole.json}，Blockbench 原样导出）里 10 个元素的
 * {@code rotation.y} 是 {@code 135 / 154 / -11.75 / 7.25 / -87.5 / …} 这些<b>任意角</b> ——
 * 原版方块模型只吃 22.5° 的整数倍，写进 blockstate 模型会被吸附成错的形状。
 * 所以和 {@code eyecandy} / 饮料机 / 闸机同一套做法：blockstate 指向 {@code mtrlte:block/empty}，
 * 几何由 {@link DepPoleRenderer} 在运行时用 {@link RawModelJson} 解析 {@code rawmodels/dep_pole.json} 画。
 *
 * <h2>放置：任意位置 + 面朝玩家</h2>
 * <ul>
 *   <li><b>任意位置可放</b>：{@link #canPlaceAt} 恒真 —— 不要求上方有天花板，也不要求下面有东西
 *       （纯粹是一根杆，玩家想吊在哪就吊在哪）；拆掉邻居也不会自己掉。</li>
 *   <li><b>位置</b>：就是 {@code ctx.getBlockPos()}（原版已经算成「你点的那面方块的相邻格」，
 *       这里不搜附近空格）。</li>
 *   <li><b>朝向</b>：{@code facing = getHorizontalPlayerFacing().getOpposite()}（面朝玩家）；
 *       模型按 {@code facing} 绕方块中心转 −{@link #blockRotation} 度，四个 blockstate 变体各自一份。</li>
 * </ul>
 *
 * <h2>碰撞 / 轮廓</h2>
 * <p><b>碰撞是空集</b>（{@link #getCollisionShape}）：这是挂在天花板上的装饰杆，不该挡人也不该挡车。
 * 轮廓（射线检测 / 框选）按模型顶点包围盒推出来（{@link RawModelJson#bounds}，元素旋转已经烘进顶点），
 * 再按四个朝向各转一份（{@link #rotatedBox}，与 {@code EyecandyBlock#shapeFor} 同一套
 * {@code (x, z) → (16 − z, x)}）—— 杆只有 0.23 格粗，正对着才好点中。
 * {@link #getCullingShape} 返回空集：杆不是满方块，不然邻居朝我们的面会被按满方块剔除，缝隙里能看穿邻居。
 */
public class DepPoleBlock extends Block implements BlockEntityProvider {

    /** 朝向：模型绕方块中心转过去的那个方向（{@code north} = 模型原始朝向 = 0°）。 */
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    /** 模型读不到时的兜底包围盒（模型空间 0..16）：整格。 */
    private static final float[] FALLBACK_BOX = {0.0F, 0.0F, 0.0F, 16.0F, 16.0F, 16.0F};

    /** 四个朝向的轮廓（构造期按模型包围盒转好，下标 = {@link #blockRotation} / 90）。 */
    private final VoxelShape[] outlines = new VoxelShape[4];

    public DepPoleBlock() {
        // nonOpaque()：杆不是满方块，邻居的面要正常生成。
        super(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .strength(0.8F)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque());
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));

        // 两端都要这个形状（服务端也要做碰撞 / 射线检测），所以注册期就把 rawmodel 读出来算包围盒。
        final float[] bounds = RawModelJson.bounds(RawModelJson.id(DepPoleObjects.MODEL));
        final float[] box = bounds == null ? FALLBACK_BOX : bounds;
        for (int i = 0; i < this.outlines.length; i++) {
            this.outlines[i] = rotatedBox(box, i);
        }
    }

    // ------------------------------------------------------------------ 形状

    /**
     * 模型空间包围盒按 {@code quarterTurns} 个 90° 转成世界坐标的轮廓。
     *
     * <p>每 90° 一步 {@code (x, z) → (16 − z, x)}，与 {@link #blockRotation} 和
     * {@link DepPoleRenderer} 的 {@code RotationAxis.NEGATIVE_Y} 是同一个方向。
     */
    private static VoxelShape rotatedBox(final float[] b, final int quarterTurns) {
        double x1 = b[0];
        double y1 = b[1];
        double z1 = b[2];
        double x2 = b[3];
        double y2 = b[4];
        double z2 = b[5];
        for (int i = 0; i < (quarterTurns & 3); i++) {
            final double nx1 = 16.0D - z2;
            final double nx2 = 16.0D - z1;
            z1 = x1;
            z2 = x2;
            x1 = nx1;
            x2 = nx2;
        }
        return Block.createCuboidShape(x1, y1, z1, x2, y2, z2);
    }

    /** {@code facing} → 绕 Y 的角度（{@code north=0 / east=90 / south=180 / west=270}）。 */
    public static int blockRotation(final Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            case NORTH, UP, DOWN -> 0;
        };
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        return this.outlines[blockRotation(state.get(FACING)) / 90];
    }

    /** 空集：装饰挂杆不挡人、不挡车。 */
    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return VoxelShapes.empty();
    }

    /** 空集：不让邻居朝我们的面被剔除（杆不是满方块）。 */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    // ------------------------------------------------------------------ 放置

    /** 位置 = 你点的那面方块的相邻格；朝向 = 面朝玩家。 */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    /** 任意位置可放：不要求天花板 / 地面，半空里也放得下。 */
    @Override
    public boolean canPlaceAt(final BlockState state, final net.minecraft.world.WorldView world,
                              final BlockPos pos) {
        return true;
    }

    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new DepPoleBlockEntity(pos, state == null ? getDefaultState() : state);
    }
}
