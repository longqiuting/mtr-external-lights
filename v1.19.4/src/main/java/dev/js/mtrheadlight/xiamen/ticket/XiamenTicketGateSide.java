// 闸机的「侧壳」：柜体镜像那一半（JCM 闸机的 本体 + 侧壳 方案）；地铁 / BRT 各一款，四台本体共用。
package dev.js.mtrheadlight.xiamen.ticket;

import mtr.block.IBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 闸机的<b>侧壳</b>：素材里那份 {@code *_barrier_side.json}，也就是「柜体的镜像那一半」。
 *
 * <h2>为什么闸机要拆成两个方块</h2>
 * <p>素材的两份几何是<b>同一套局部坐标</b>里做的（原点都在本体的西北下角）：
 * <ul>
 *   <li>{@code *_barrier_closed / _open}：柜体在本地 <b>x 12..16</b>，挡杆从柜体横扫到 <b>x -4</b> ；</li>
 *   <li>{@code *_barrier_side}：镜像柜体在本地 <b>x -4..0</b>（同一柜体的左右镜像，深度 21.3、
 *       高 16 / 17.27 与本体一致）。</li>
 * </ul>
 * 也就是说「另一侧的柜子」在素材里就<b>摆在本体外面一格</b>（本地 x 为负 = 西边那一格）。所以按 JCM 的做法
 * 把它做成真正的一个方块：<b>本体占一格，侧壳占它旁边那一格</b>，两格同向、柜体贴着脸。
 * 一个方块硬塞两个柜体做不到 —— 碰撞 / 框选 / 破坏 / 光照都只能按格算。
 *
 * <p>侧壳本身<b>不分进 / 出</b>：它没有 {@code OPEN}、不参与判定、也不开合，进站闸机旁边和出站闸机
 * 旁边摆的是同一只柜子。所以只按素材分两款（地铁 {@code ticket_barrier_side} / BRT
 * {@code brt_barrier_side}），见 {@link XiamenTicketGates.Side}。
 *
 * <h2>位置与朝向</h2>
 * <p>侧壳是<b>独立方块 + 独立物品</b>（地铁 / BRT 各一款，四台本体共用，不分进 / 出）：摆在本体的
 * {@link XiamenTicketGate#sideOf(Direction)} 那一侧（= 本体 {@code FACING} 的逆时针 90°，推导见那个方法），
 * {@code FACING} 摆成与本体的 {@code FACING} 相同。渲染时 {@link XiamenTicketGateRenderer} 把侧壳的模型
 * 往本体那一格平移一格，于是两份素材回到同一个局部坐标系里严丝合缝 —— 两份素材本来就是同一套局部坐标。
 *
 * <h2>碰撞 / 框选</h2>
 * <p>都不是满方块（模型只占本地 x 12..16 那 4/16 的一条），所以：
 * <ul>
 *   <li>轮廓 = 柜体本身（{@code x 12..16}，高度按素材取 17）；</li>
 *   <li>碰撞 = 同样那条，高度取 {@code 24}（1.5 格）—— 与 JCM 侧柜（{@code TicketBarrier1Decor}，
 *       {@code getVoxelShapeByDirection(12, 0, 0, 16, 24, 16, facing)}）逐位一致，玩家跳不上柜顶；</li>
 *   <li>{@link #getCullingShape} 返回空集：不是满方块，否则邻居朝我们的面会被当成「被挡住」而剔除，
 *       缝隙里能看穿邻居（{@code eyecandy} 那边踩过这个坑）。</li>
 * </ul>
 * 挡杆（关门时横在通道上的那根）<b>不</b>在这里，它是本体的碰撞。
 *
 * <h2>破坏</h2>
 * <p>侧壳有自己的物品与掉落表（{@code data/mtrlte/loot_tables/blocks/ticket_gate_*_side.json}），
 * 拆下来就是它自己那件物品 —— 跟本体完全独立，两侧可以分别摆、分别拆。
 */
public class XiamenTicketGateSide extends Block implements BlockEntityProvider {

    /** 与本体的 {@code FACING} 同源（{@code HorizontalFacingBlock.FACING} == {@code Properties.HORIZONTAL_FACING}）。 */
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    /** 柜体在本地坐标里的范围（与素材一致）：x 12..16、z 整格；轮廓高 17（BRT 柜体顶 17.27），
     *  碰撞取 24（1.5 格）—— 与 JCM 侧柜 {@code TicketBarrier1Decor} 的 {@code 12,0,0 → 16,24,16} 一致。 */
    private static final double CABINET_X1 = 12.0D;

    private static final double OUTLINE_TOP = 17.0D;

    private static final double COLLISION_TOP = 24.0D;

    /** 用哪一份原始模型：{@code ticket_barrier_side} / {@code brt_barrier_side}。 */
    private final String rawModel;

    public XiamenTicketGateSide(final Settings settings, final String rawModel) {
        super(settings);
        this.rawModel = rawModel;
        // 有 FACING 就必须给一个默认状态，否则 registry 冻结时崩（本案 72a965f 踩过）。
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    /** {@code ticket_barrier_side} / {@code brt_barrier_side}（渲染器直接拼 rawmodels 的 id）。 */
    public String rawModel() {
        return this.rawModel;
    }

    /**
     * 本体在侧壳的哪一侧：与 {@link XiamenTicketGate#sideOf(Direction)} 互逆
     * （侧壳在北边 ⟺ 本体在南边）。
     */
    public static Direction mainOf(final Direction sideFacing) {
        return sideFacing.rotateYClockwise();
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(final ItemPlacementContext context) {
        // 正常不会走这里（侧壳跟着本体一起放，FACING 抄本体的），但 /setblock 之类的场合兜一个合理值。
        return this.getDefaultState().with(FACING, context.getHorizontalPlayerFacing());
    }

    /** 与本体同一个方块实体类型（渲染器按方块类型分辨「本体 / 侧壳」）。 */
    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new XiamenTicketGateBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return IBlock.getVoxelShapeByDirection(CABINET_X1, 0.0D, 0.0D, 16.0D, OUTLINE_TOP, 16.0D, state.get(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return IBlock.getVoxelShapeByDirection(CABINET_X1, 0.0D, 0.0D, 16.0D, COLLISION_TOP, 16.0D, state.get(FACING));
    }

    /** 空集：不是满方块，别让邻居朝我们的面被剔除。 */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }
}
