// 厦门售票机 / 余额查询机的共同底座：两格高、四向、拆一半 = 拆整块。
package dev.js.mtrheadlight.xiamen.machine;

import java.util.List;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * 厦门售票机 / 余额查询机的基类：<b>一个方块 id、两格高</b>（{@code half=lower|upper}），
 * 下段是物品形态、上段没有物品。
 *
 * <h2>两段各画各的</h2>
 * <ul>
 *   <li><b>售票机</b>：两段的几何完全不同，靠 blockstate 的 {@code half} 各指一份原版模型
 *       （{@code block/ticket_machine_xiamen_bottom} / {@code _top}）；</li>
 *   <li><b>余额查询机</b>：同样是两段各一份，但改由 {@code XiamenBalanceMachineRenderer} 在运行时画
 *       {@code rawmodels/balance_machine_{bottom,top}.json}（素材里屏幕是 {@code x:-12.5} 的斜面，
 *       原版模型格式的一个元素只能单轴旋转，而且上段局部坐标最高 16.91）。</li>
 * </ul>
 *
 * <h2>为什么是「一个 id + HALF」而不是「两个 id」</h2>
 * <p>这既和 MTR 的 {@code BlockTicketMachine} / 方速的 {@code com.fangsu.blocks.BlockTicketMachine}
 * 一致（它们都是 {@code FACING} + {@code HALF}），也让上段的模型 / 碰撞 / 拆解全部写在同一个类里，
 * 玩家手里只有一件物品，中键选取、掉落、刷子切换都只需要看一个方块。
 *
 * <h2>模型空间与 {@code facing}</h2>
 * <p>两份 Blockbench 模型都是<b>正面朝 +Z（south）</b>导出的（售票机的斜操作台、查询机的斜屏都在
 * z 正方向那一侧）。所以 blockstate 的旋转是
 * <pre>
 *   facing=south → y=0     facing=west  → y=90
 *   facing=north → y=180   facing=east  → y=270
 * </pre>
 * 与 {@link #blockRotation} 严格一致；{@code facing} 的语义是「<b>正面朝哪</b>」，放置时正面朝玩家
 * （{@code getHorizontalPlayerFacing().getOpposite()}），和站外标志的杆上款同一个规矩。
 *
 * <h2>拆解：拆哪一半都只掉 1 个</h2>
 * <p>战利品表是空池（{@code pools: []}），掉落<b>只在 {@link #onBreak} 里产生</b>：
 * <ul>
 *   <li>拆下段 → 掉 1 个在下段的位置，上段被静默清掉（{@code setBlockState}，不走掉落）；</li>
 *   <li>拆上段 → 掉 1 个在下段的位置（物品出现在机器脚下，而不是半空中），下段同样静默清掉。</li>
 * </ul>
 * 之所以不用「战利品表 + {@code half=lower} 条件」那一套：上段被拆时邻居更新会先把下段变成空气，
 * 那条路径要么不掉、要么和 {@code breakBlock} 的掉落叠成 2 个。手写一处掉落可以保证恒为 1 个。
 *
 * <h2>不遮挡邻居</h2>
 * <p>{@code nonOpaque()} + {@link #getCullingShape} 返回空集：模型不是满方块（售票机下段正面还有
 * 一块斜出去的操作台），不这样邻居朝我们的面会被按满方块剔除，缝隙里能看穿邻居。
 *
 * <h2>物品图标是「整机」</h2>
 * <p>世界里两段各用各的模型（{@code block/<id>_bottom} / {@code block/<id>_top}），
 * 但物品栏里给的是一台完整的机器：{@code block/<id>_item} 把两份模型的 elements 合到同一个模型里
 * （上段的 y 已经是<b>自己那一格的局部坐标</b> 0..16，所以合并时整体 +16，旋转的 origin 也跟着 +16），
 * 物品模型只挂 {@code parent} + {@code display}。
 */
public abstract class XiamenMachineBlock extends HorizontalFacingBlock {

    /** 上下两段（与 MTR / 方速的售票机同名同义）。 */
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

    /** 四个朝向的下段 / 上段形状（已经按 blockstate 的 {@code y} 转好）。 */
    private final VoxelShape[] lowerShapes = new VoxelShape[4];
    private final VoxelShape[] upperShapes = new VoxelShape[4];

    /** 物品提示的翻译键（{@code tooltip.mtrlte.<id>}），{@code null} = 不显示。 */
    private final String tooltipKey;

    /**
     * @param settings  方块设置（硬度 / 声音 / nonOpaque 这些，见 {@link XiamenMachines}）
     * @param lowerBox  下段模型的包围盒 {@code {minX, maxX, minZ, maxZ}}（1/16 格，模型空间）
     * @param upperBox  上段模型的包围盒，同上
     * @param tooltipKey 物品提示的翻译键
     */
    protected XiamenMachineBlock(final AbstractBlock.Settings settings,
                                 final double[] lowerBox, final double[] upperBox,
                                 final String tooltipKey) {
        super(settings);
        for (int i = 0; i < 4; i++) {
            this.lowerShapes[i] = rotatedBox(lowerBox, i * 90);
            this.upperShapes[i] = rotatedBox(upperBox, i * 90);
        }
        this.tooltipKey = tooltipKey;
    }

    // ------------------------------------------------------------------ 形状

    /** 轴对齐盒子（y 由调用方给）。 */
    private static VoxelShape box(final double x1, final double x2, final double y1, final double y2,
                                  final double z1, final double z2) {
        return Block.createCuboidShape(x1, y1, z1, x2, y2, z2);
    }

    /**
     * 把模型空间的包围盒按 blockstate 的 {@code y} 转成世界坐标。
     * 每 90° 都是 {@code (x, z) → (16 - z, x)}，与 blockstate 的 {@code y} 同一套约定。
     */
    private static VoxelShape rotatedBox(final double[] b, final int yRotation) {
        final double minX = b[0];
        final double maxX = b[1];
        final double minZ = b[2];
        final double maxZ = b[3];
        return switch (((yRotation % 360) + 360) % 360) {
            case 90 -> box(16.0D - maxZ, 16.0D - minZ, 0.0D, 16.0D, minX, maxX);
            case 180 -> box(16.0D - maxX, 16.0D - minX, 0.0D, 16.0D, 16.0D - maxZ, 16.0D - minZ);
            case 270 -> box(minZ, maxZ, 0.0D, 16.0D, 16.0D - maxX, 16.0D - minX);
            default -> box(minX, maxX, 0.0D, 16.0D, minZ, maxZ);
        };
    }

    /**
     * facing → blockstate {@code y}。
     *
     * <p>售票机仍然是原版 blockstate 模型（{@code assets/mtrlte/blockstates/ticket_machine_xiamen.json}），
     * 那边的四个变体必须与这里一模一样；
     * 余额查询机改走方块实体渲染器（{@code XiamenBalanceMachineRenderer}），它用的是同一套映射
     * （{@code rotationDegrees(-FACING.asRotation())} = 原版 {@code "y"} 的等价写法）。
     */
    public static int blockRotation(final Direction facing) {
        return switch (facing) {
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0; // SOUTH：模型原生朝向，不旋转
        };
    }

    private VoxelShape shapeFor(final BlockState state) {
        final int index = blockRotation(state.get(FACING)) / 90;
        return state.get(HALF) == DoubleBlockHalf.UPPER ? this.upperShapes[index] : this.lowerShapes[index];
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        // HorizontalFacingBlock 的属性在 1.20.1 是由它自己的 appendProperties（静态工具方法）加的，
        // 走 super 调用不一定落上，这里显式补一次，否则状态里没有 facing，
        // 放置时 `state.with(FACING, …)` 直接抛
        // IllegalArgumentException: Cannot set property facing as it does not exist。
        builder.add(FACING);
        builder.add(HALF);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        return shapeFor(state);
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return shapeFor(state);
    }

    /** 空集：不让邻居朝我们的面被剔除（模型不是满方块）。 */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    // ------------------------------------------------------------------ 放置 / 拆解

    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final BlockPos pos = ctx.getBlockPos();
        final World world = ctx.getWorld();
        if (pos.getY() >= world.getTopY() - 1) {
            return null;
        }
        if (!world.getBlockState(pos.up()).canReplace(ctx)) {
            return null;
        }
        return getDefaultState()
                .with(FACING, ctx.getHorizontalPlayerFacing().getOpposite())
                .with(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void onPlaced(final World world, final BlockPos pos, final BlockState state,
                         final LivingEntity placer, final ItemStack itemStack) {
        // 上段照抄下段的全部属性（facing / ui …），只改 half。
        world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
    }

    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        if (state.get(HALF) == DoubleBlockHalf.UPPER) {
            return isHalf(world.getBlockState(pos.down()), DoubleBlockHalf.LOWER);
        }
        return pos.getY() < world.getTopY() - 1;
    }

    /**
     * 只认「另一半那一格」的变化：下段看上面、上段看下面。
     *
     * <p>注意<b>不能</b>反过来去校验别的方向 —— 比如在上段头顶再放一块方块时，收到更新的是上段
     * （方向 UP），跟这里判断的 DOWN 无关；否则机器会被随便一块相邻方块连带拆掉。
     */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        if (state.get(HALF) == DoubleBlockHalf.UPPER) {
            if (direction == Direction.DOWN && !isHalf(neighborState, DoubleBlockHalf.LOWER)) {
                return Blocks.AIR.getDefaultState();
            }
        } else if (direction == Direction.UP && !isHalf(neighborState, DoubleBlockHalf.UPPER)) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    /**
     * 拆一半 = 拆整块，掉落恒为 1 个（战利品表是空池，见类注释）。
     *
     * <p>另一半用 {@code setBlockState} 清掉而不是 {@code breakBlock}：那条路径不会跑战利品表，
     * 也就不会和这里的 {@code dropStack} 叠成两份。
     */
    @Override
    public void onBreak(final World world, final BlockPos pos, final BlockState state,
                        final PlayerEntity player) {
        if (!world.isClient) {
            final boolean lower = state.get(HALF) == DoubleBlockHalf.LOWER;
            final BlockPos partnerPos = lower ? pos.up() : pos.down();
            if (isHalf(world.getBlockState(partnerPos), lower ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER)) {
                world.setBlockState(partnerPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            if (player == null || !player.isCreative()) {
                // 物品永远从「下半」那格冒出来，机器不会在半空中掉东西。
                dropStack(world, lower ? pos : partnerPos, new ItemStack(this));
            }
        }
        super.onBreak(world, pos, state, player);
    }

    /** 这一格是不是本方块的那一半。 */
    private boolean isHalf(final BlockState state, final DoubleBlockHalf half) {
        return state.isOf(this) && state.get(HALF) == half;
    }

    /** 下半的坐标（右键 / 切换属性都以下半为准）。 */
    protected static BlockPos lowerPos(final BlockState state, final BlockPos pos) {
        return state.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void appendTooltip(final ItemStack stack, final BlockView world,
                              final List<Text> tooltip, final TooltipContext context) {
        if (this.tooltipKey != null) {
            tooltip.add(Text.translatable(this.tooltipKey).formatted(Formatting.GRAY));
        }
    }
}
