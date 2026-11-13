// 一个 eyecandy 物件的方块本体：几何交给 BER 画，碰撞 / 轮廓用注册期算好的盒子（四向各一份）。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.client.item.TooltipContext;

/**
 * 全部 eyecandy 共用的方块类：<b>一个类 + 一份数据</b>（{@link EyecandyEntry}）。
 *
 * <h2>几何 / 碰撞 / 轮廓</h2>
 * <ul>
 *   <li>几何：运行时由 {@link EyecandyRenderer} 画 {@code eyecandies/*.obj}，方块的 blockstate 模型是
 *       {@code mtrlte:block/empty}（空的 {@code elements}），所以世界里的碰撞与渲染互不影响；</li>
 *   <li>碰撞 / 轮廓：注册期按 {@link EyecandyBlocks} 给的盒子列表建成 {@link VoxelShape}，
 *       <b>四向各一份</b>（{@code ROTATION} 每转 90° 就把盒子绕方块中心转一次，见 {@link #shapeOf}）；</li>
 *   <li>{@link #getCullingShape} 返回空集 + {@code nonOpaque()}：物件大多不是满方块，
 *       不这样邻居朝我们的面会被当成满方块剔除掉，缝隙里能看穿邻居（{@code dep_on_wall} 踩过这个坑）。</li>
 * </ul>
 *
 * <h2>{@code ROTATION}：四向 + 刷子循环（这次新加的）</h2>
 * <p>素材里有几个物件天生方向不对（挂墙的告示牌、显示屏、窗户、卷帘门……），而模型本身烘在
 * {@code .obj} 里，改不了贴图的朝向。所以每个 eyecandy 方块都带一个四向属性
 * {@code rotation}（{@code north/east/south/west}，一个四值的 {@link EnumProperty}）：
 * <ul>
 *   <li><b>放置</b>：默认 {@code 玩家朝向的反向}（{@link #getPlacementState}）—— 物件正面朝你，
 *       和站外标志「杆上款」、挂顶倒数器同一个约定；</li>
 *   <li><b>调整</b>：手持 <b>MTR 的刷子</b>（{@code mtr:brush}，和 MTR 自己的方块一致）右键循环
 *       {@code 北 → 东 → 南 → 西}；</li>
 *   <li><b>渲染</b>：{@link EyecandyRenderer} 按同一个属性绕方块中心转（矩阵），<b>碰撞盒一起转</b>
 *       （{@link #shapeOf} 在注册期就把四个方向的形状都建好了），所以永远不会出现「模型转了、
 *       碰撞没转」。</li>
 * </ul>
 * <p>没有 {@code facing} 那样「只能朝某些方向放」的限制：物件是纯装饰，放哪都行。
 *
 * <h2>没有碰撞的物件（桥墩）</h2>
 * <p>{@code qiaodun20}（20 格高的桥墩）只给<b>轮廓</b>不给碰撞：轮廓是一个 1×1×1 的框（选中它看得见
 * 一格的范围，方便对齐），碰撞是空集 —— 玩家 / 列车的碰撞不该被一格方框挡住（真正的墩身在 20 格
 * 高的几何里，穿过去也不会卡住）。
 *
 * <h2>放置 / 掉落</h2>
 * <p>任意位置可放，掉自己（{@code data/mtrlte/loot_tables/blocks/eyecandy_*.json} 一张一个）。
 * 不继承任何 MTR 的方块类 —— 这些是纯装饰，不参与线路 / 列车 / 屏蔽门逻辑。
 */
public class EyecandyBlock extends Block implements BlockEntityProvider {

    /**
     * 四向朝向（{@code rotation} → {@code north / east / south / west}）。
     *
     * <p>名字特意用 {@code rotation} 而不是 {@code facing}：素材里那 26 件本来就在
     * {@code first.json} 里带一个叫 {@code rotation} 的摆放角，用同一个词不容易混
     * （那个是烘进模型的，这个是方块状态）。blockstate 里写作 {@code rotation=north} 等四变体。
     */
    public static final EnumProperty<Direction> ROTATION = EnumProperty.of("rotation", Direction.class,
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    private final EyecandyEntry entry;

    /** 四个朝向的轮廓 / 碰撞形状（索引 = {@link #blockRotation} / 90）。 */
    private final VoxelShape[] shapes;

    /** 有没有碰撞（桥墩没有，只有轮廓）。 */
    private final boolean collides;

    /**
     * @param boxes   模型空间（1/16 格、{@code x/z ∈ 0..16}）里的盒子列表，{@code [x1, y1, z1, x2, y2, z2]}；
     *                <b>只给一个朝向的量</b>，四个方向由 {@link #shapeOf} 转出来
     * @param collides {@code false} = 只画轮廓、不挡人（桥墩）
     */
    public EyecandyBlock(final Settings settings, final EyecandyEntry entry,
                         final List<double[]> boxes, final boolean collides) {
        super(settings);
        this.entry = entry;
        this.collides = collides;
        this.shapes = new VoxelShape[4];
        for (int i = 0; i < this.shapes.length; i++) {
            this.shapes[i] = shapeOf(boxes, i * 90);
        }
        setDefaultState(getStateManager().getDefaultState().with(ROTATION, Direction.NORTH));
    }

    /** 这一块用的是哪一条描述（渲染器按它挑模型 / 贴图）。 */
    public EyecandyEntry entry() {
        return this.entry;
    }

    /** 有没有碰撞（给 {@code tools/verify-eyecandies.js} 与调试命令用）。 */
    public boolean collides() {
        return this.collides;
    }

    /** {@code north} 那一份形状（调试用）。 */
    public VoxelShape shape() {
        return this.shapes[0];
    }

    /** 某个朝向的形状（{@code ROTATION} 已经转好的那一份）。 */
    public VoxelShape shapeFor(final Direction rotation) {
        return this.shapes[blockRotation(rotation) / 90];
    }

    /**
     * facing → blockstate 的 {@code y} 角（{@code north=0 / east=90 / south=180 / west=270}，顺时针）。
     *
     * <p>这里只用来给 {@link #shapes} 挑索引、给渲染器挑旋转角 —— 两处必须用同一个数，
     * 否则模型转了碰撞不转。
     */
    public static int blockRotation(final Direction direction) {
        return switch (direction) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            // 水平四向以外（UP / DOWN）兜底成北。
            case NORTH, UP, DOWN -> 0;
        };
    }

    /** 空壳方块实体（BER 的锚点，见 {@link EyecandyBlockEntity}）。 */
    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new EyecandyBlockEntity(pos, state);
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(ROTATION);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return shapeFor(state.get(ROTATION));
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return this.collides ? shapeFor(state.get(ROTATION)) : VoxelShapes.empty();
    }

    /** 空集：不让邻居朝我们的面被剔除（物件不是满方块）。 */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    // ------------------------------------------------------------------ 放置 / 旋转 / 掉落

    /**
     * 放下来时默认<b>正面朝玩家</b>：{@code 玩家朝向的反向}（和站外标志、挂顶倒数器同一个约定）。
     * <p>碰到方向不对的，用刷子右键转 —— 见类注释。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        return getDefaultState().with(ROTATION, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    /** 刷子右键：{@code 北 → 东 → 南 → 西} 循环。别的物品照常走原逻辑（放方块、用方块）。 */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (!isBrush(player.getStackInHand(hand))) {
            return super.onUse(state, world, pos, player, hand, hit);
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        world.setBlockState(pos, state.with(ROTATION, state.get(ROTATION).rotateYClockwise()));
        return ActionResult.CONSUME;
    }

    /** MTR 的刷子（{@code mtr:brush}）。1.20.1 没有原版刷子，所以只认这一把。 */
    private static boolean isBrush(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final Item brush;
        try {
            brush = mtr.Items.BRUSH.get();
        } catch (Throwable t) {
            return false; // MTR 没装 / API 变了：旋转功能静默失效，别把右键打挂
        }
        return brush != Items.AIR && stack.getItem() == brush;
    }

    /** 物品栏 / 手持时提示怎么转向（中文见 {@code lang/zh_cn.json}）。 */
    @Override
    public void appendTooltip(final ItemStack stack, final BlockView world, final List<Text> tooltip,
                              final TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.mtrlte.eyecandy_rotation"));
    }

    // ------------------------------------------------------------------ 形状

    /**
     * 把模型空间的一组盒子按 blockstate 的 {@code y} 转到世界，并成一个 {@link VoxelShape}。
     *
     * <p>每 90° 都是 {@code (x, z) → (16 − z, x)}（与 {@code dep_on_wall} / {@code dep_on_roof} /
     * 站外标志同一套约定），所以四个方向都是精确的轴对齐盒子，不用细分。
     */
    static VoxelShape shapeOf(final List<double[]> boxes, final int yRotation) {
        final List<VoxelShape> parts = new ArrayList<>(boxes.size());
        for (final double[] b : boxes) {
            double x1 = b[0];
            double x2 = b[3];
            double z1 = b[2];
            double z2 = b[5];
            switch (((yRotation % 360) + 360) % 360) {
                case 90 -> {
                    final double nx1 = 16.0D - z2;
                    final double nx2 = 16.0D - z1;
                    z1 = x1;
                    z2 = x2;
                    x1 = nx1;
                    x2 = nx2;
                }
                case 180 -> {
                    final double nx1 = 16.0D - x2;
                    final double nx2 = 16.0D - x1;
                    final double nz1 = 16.0D - z2;
                    final double nz2 = 16.0D - z1;
                    x1 = nx1;
                    x2 = nx2;
                    z1 = nz1;
                    z2 = nz2;
                }
                case 270 -> {
                    final double nx1 = z1;
                    final double nx2 = z2;
                    z1 = 16.0D - x2;
                    z2 = 16.0D - x1;
                    x1 = nx1;
                    x2 = nx2;
                }
                default -> {
                    // north：模型空间就是世界空间。
                }
            }
            parts.add(Block.createCuboidShape(x1, b[1], z1, x2, b[4], z2));
        }
        VoxelShape shape = VoxelShapes.empty();
        for (final VoxelShape part : parts) {
            shape = VoxelShapes.union(shape, part);
        }
        return shape;
    }
}
