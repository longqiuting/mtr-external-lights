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
 * （{@link EyecandyBlocks#DIRECTIONAL_KEYS}：{@code xiamen_brt_psd_right / xiamen_brt_psd_left /
 * xiamen_brt_railing_middle / xiamen_brt_railing_left / xiamen_brt_railing_right /
 * xiamen_brt_railing_middle_plain / msd_corner_railing / xiamen_brt_railing_corner}）。
 *
 * <h2>为什么单独一个类</h2>
 * <p>{@code facing} 只有部分物件有。属性是在 {@code appendProperties} 里注册的，而
 * {@code AbstractBlock} 的构造器在 {@code super(settings)} 里就会回调它 —— 那时子类自己的
 * {@code boolean directional} 字段还是 {@code false}（字段赋值在 {@code super()} 之后），
 * 所以「用字段判断要不要加属性」会静默失效。分成两个类就没有这个问题。
 *
 * <h2>朝向语义</h2>
 * <p>{@code facing = 玩家看的方向的反方向}（{@code getHorizontalPlayerFacing().getOpposite()}）——
 * 这几个 obj 的原始几何贴在方块 <b>+z（南）</b>那个面上，只有取反，整件才会落到<b>玩家对面</b>那条边：
 * 玩家朝北放 → {@code facing=SOUTH} → 绕中心 180° → 几何落到北边（z ≈ 0..0.2），背对玩家。
 *
 * <p>注意别拿 MTR 屏蔽门当字节码依据：{@code BlockDirectionalDoubleBlockBase#getPlacementState}
 * 确实<b>不取反</b>（见 {@code FangsuPsdDoorBlock}），但它家的门扇几何原始朝北，不取反就落在北边；
 * 我们的 obj 原始朝南，同一手感要求取反。结论按「几何落在玩家对面」核对，不按 {@code facing} 的字面值。
 *
 * <p>{@code facing = NORTH} 就是<b>模型原始朝向</b>（0°，矩阵一次都不动）。实测这些物件的原始几何：
 * BRT 屏蔽门两扇（{@code xiamen_brt_psd_right / xiamen_brt_psd_left}）与 BRT 栏杆四段
 * （{@code xiamen_brt_railing_middle / left / right / middle_plain}）贴在方块
 * <b>南边</b>（{@code z ≈ 0.9..1}，门扇 / 栏杆挤在最靠 +z 的那条面上），转角栏杆（{@code msd_corner_railing} /
 * {@code xiamen_brt_railing_corner}）的拐角在西北角。每 90° 把整件绕方块中心转一步 —— 也就是「朝哪个方向放，
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
     * 朝向。语义 = <b>绕方块中心转过去的那个方向</b>（{@code blockRotation} 取它算角度），
     * 不是「玩家看的方向」字面值：放置时取的是玩家的<b>反方向</b>，见 {@link #getPlacementState}。
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
     * 放置：<b>取玩家朝向的反方向</b> {@code getHorizontalPlayerFacing().getOpposite()} ——
     * 玩家朝北放 → {@code facing=SOUTH} → 整件绕中心转到北边（<b>玩家对面</b>那条边），背对玩家。
     * 不取反的话整件会落在玩家这一侧（贴着玩家），用户报的「放置时没有朝向玩家对面」就是那一步。
     *
     * <p>BRT 屏蔽门的左 / 右半扇也走这里：它们转的是<b>整扇</b>（rigid rotation），
     * 四向里左仍然是左、右仍然是右，不做镜像 / 互换。
     * <p>没有 {@code onUse}：这里<b>不提供刷子右键循环</b>，朝向只在放置那一刻定一次。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        // 取反：这些 obj 的原始几何贴在方块 +z（南）那条边 —— 直接不取反的话，玩家朝北放，
        // 门扇 / 栏杆就落在玩家这一侧（贴脸），与 MTR 屏蔽门「整件落到玩家对面」的手感相反。
        // 取反后：玩家朝北 → facing=SOUTH → 绕中心 180° → 几何落到北边 = 玩家对面。
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    /** 当前 {@code facing} 对应的形状（模型用同一个角度转，见类注释）。 */
    @Override
    public VoxelShape shapeFor(final BlockState state) {
        return super.shapeFor(state.get(FACING));
    }
}
