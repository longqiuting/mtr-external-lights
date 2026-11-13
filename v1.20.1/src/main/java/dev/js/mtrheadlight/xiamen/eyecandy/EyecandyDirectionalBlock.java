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
 * 会按玩家朝向转的 eyecandy 方块 —— <b>BRT 屏蔽门左右两扇 + BRT 栏杆</b>
 * （{@link EyecandyBlocks#DIRECTIONAL_KEYS}：{@code key2 / key3 / key4 / key5 / key6 / key7 / key13 / key14}）。
 *
 * <h2>为什么单独一个类</h2>
 * <p>{@code facing} 只有部分物件有。属性是在 {@code appendProperties} 里注册的，而
 * {@code AbstractBlock} 的构造器在 {@code super(settings)} 里就会回调它 —— 那时子类自己的
 * {@code boolean directional} 字段还是 {@code false}（字段赋值在 {@code super()} 之后），
 * 所以「用字段判断要不要加属性」会静默失效。分成两个类就没有这个问题。
 *
 * <h2>朝向语义（与 MTR 原版屏蔽门一致）</h2>
 * <p>{@code facing = 玩家看的方向}（{@code ItemPlacementContext#getHorizontalPlayerFacing()}，
 * <b>不取反</b>，字节码依据见 {@code FangsuPsdDoorBlock#getPlacementState}）。
 *
 * <p>{@code facing = NORTH} 就是<b>模型原始朝向</b>（0°，矩阵一次都不动）。实测这些物件的原始几何：
 * BRT 屏蔽门两扇（{@code key2 / key3}）与 BRT 栏杆四段（{@code key4}~{@code key7}）贴在方块
 * <b>南边</b>（{@code z ≈ 0.9..1}，门扇 / 栏杆挤在最靠 +z 的那条面上），转角栏杆（{@code key13} /
 * {@code key14}）的拐角在西北角。每 90° 把整件绕方块中心转一步 —— 也就是「朝哪个方向放，
 * 就整件转到那个方向」，四向里左半扇仍然是左半扇（{@code left.obj}）、右半扇仍然是右半扇
 * （{@code right.obj}），不做镜像 / 互换。
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
     * 玩家朝北放 → 栏杆 / 门扇贴北边（玩家对面那条边），正面朝北。
     *
     * <p>BRT 屏蔽门的左 / 右半扇也走这里：它们转的是<b>整扇</b>（rigid rotation），
     * 四向里左仍然是左、右仍然是右，不做镜像 / 互换。
     * <p>没有 {@code onUse}：这里<b>不提供刷子右键循环</b>，朝向只在放置那一刻定一次。
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
