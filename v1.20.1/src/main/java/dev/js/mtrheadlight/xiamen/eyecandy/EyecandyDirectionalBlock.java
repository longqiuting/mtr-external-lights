// eyecandy 里「放置时按玩家朝向转」的那一类（栏杆）：多一个 facing 属性，形状与模型跟着转。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

/**
 * 会按玩家朝向转的 eyecandy 方块 —— 目前只有 <b>BRT 栏杆</b> 那几段
 * （{@link EyecandyBlocks#DIRECTIONAL_KEYS}：{@code key4 / key5 / key6 / key7 / key13 / key14}）。
 *
 * <h2>为什么单独一个类</h2>
 * <p>{@code facing} 只有部分物件有。属性是在 {@code appendProperties} 里注册的，而
 * {@code AbstractBlock} 的构造器在 {@code super(settings)} 里就会回调它 —— 那时子类自己的
 * {@code boolean directional} 字段还是 {@code false}（字段赋值在 {@code super()} 之后），
 * 所以「用字段判断要不要加属性」会静默失效。分成两个类就没有这个问题。
 *
 * <h2>朝向语义（与 MTR 原版屏蔽门一致）</h2>
 * <p>{@code facing = 玩家看的方向}（{@code ItemPlacementContext#getHorizontalPlayerFacing()}，
 * <b>不取反</b>，字节码依据见 {@code FangsuPsdDoorBlock#getPlacementState}）。对栏杆来说这就是
 * 「背对玩家贴边」：栏杆正面朝玩家看的那一侧（玩家对面），走向横在玩家面前。
 * <p>以前 eyecandy 用的是 {@code getOpposite()}（物件正面朝你），用户判成「翻到内侧」；
 * 而且那套还带刷子右键循环 —— 现在<b>只在放置那一刻按玩家朝向定一次，没有刷子旋转</b>。
 *
 * <h2>模型与碰撞盒必须同一个角度</h2>
 * <ul>
 *   <li>碰撞 / 轮廓：{@link #shapeFor} 取 {@link EyecandyBlock#blockRotation} / 90 那一份
 *       （构造期按 {@code (x, z) → (16 − z, x)} 转好的）；</li>
 *   <li>模型：{@link EyecandyRenderer} 用 {@code RotationAxis.NEGATIVE_Y.rotationDegrees(同一个角度)}。</li>
 * </ul>
 */
public class EyecandyDirectionalBlock extends EyecandyBlock {

    /**
     * 朝向。语义与 MTR 原版屏蔽门的 {@code FACING} 一致：<b>玩家看的方向</b>
     * = 物件贴的那条边 / 正面朝的方向。
     */
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public EyecandyDirectionalBlock(final Settings settings, final EyecandyEntry entry,
                                    final List<double[]> boxes, final boolean collides) {
        super(settings, entry, boxes, collides);
        // 默认 = 北 = 模型原始朝向（DirectionProperty 的候选顺序也是 NORTH / EAST / SOUTH / WEST，
        // 这里显式写一次，免得哪天顺序变了默认态跟着漂）。
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    public boolean directional() {
        return true;
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(FACING);
    }

    /**
     * 放置：<b>和 MTR 原版屏蔽门同一套</b> —— {@code facing = 玩家看的方向}，不取反。
     * 玩家朝北放 → 栏杆贴北边（玩家对面那条边），正面朝北。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing());
    }

    /** 当前 {@code facing} 对应的形状（模型用同一个角度转，见类注释）。 */
    @Override
    public VoxelShape shapeFor(final BlockState state) {
        return super.shapeFor(state.get(FACING));
    }
}
