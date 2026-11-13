// 一个 eyecandy 物件的方块本体：几何交给 BER 画，碰撞 / 轮廓用注册期算好的盒子。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * 全部 eyecandy 共用的方块类：<b>一个类 + 一份数据</b>（{@link EyecandyEntry}）。
 *
 * <h2>几何 / 碰撞 / 轮廓</h2>
 * <ul>
 *   <li>几何：运行时由 {@link EyecandyRenderer} 画 {@code eyecandies/*.obj}，方块的 blockstate 模型是
 *       {@code mtrlte:block/empty}（空的 {@code elements}），所以世界里的碰撞与渲染互不影响；</li>
 *   <li>碰撞 / 轮廓：注册期按 {@link EyecandyBlocks} 给的盒子列表建成 {@link VoxelShape}
 *       （北向那一份，另外三个方向在构造期就转好，见 {@link #shapes}）；</li>
 *   <li>{@link #getCullingShape} 返回空集 + {@code nonOpaque()}：物件大多不是满方块，
 *       不这样邻居朝我们的面会被当成满方块剔除掉，缝隙里能看穿邻居（{@code dep_on_wall} 踩过这个坑）。</li>
 * </ul>
 *
 * <h2>朝向：两类物件，两个类</h2>
 * <ul>
 *   <li><b>本类（没有朝向属性）</b>：标牌 / 垃圾桶 / 单车 / 桥墩这类「正面朝人看」的独立摆件，
 *       模型烘在 {@code .obj} 里是死的，blockstate 只有一个变体（{@code ""}），放置时不会因为玩家
 *       朝向变样子。</li>
 *   <li><b>{@link EyecandyDirectionalBlock}（有 {@code facing}）</b>：BRT 屏蔽门左右两扇 + 栏杆那几段。
 *       长条几何 / 贴边的半扇单看模型分不出东西南北，放置时按<b>玩家朝向</b>定（和 MTR 原版屏蔽门
 *       同一个约定：{@code facing} = 玩家看的方向；{@code NORTH} = 模型原始朝向，0° 不动矩阵）。
 *       左 / 右半扇转的是<b>整扇</b>，不做镜像互换。</li>
 * </ul>
 * <p>两类都<b>没有刷子旋转</b>：用户明确说过「刷子旋转没什么用，玩家放置的时候自然就转向了」，
 * 所以朝向只在放置那一刻定一次，之后没有任何旋转手段（以前那套四向 {@code rotation} + MTR 刷子
 * 右键循环已经删掉）。
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

    private final EyecandyEntry entry;

    /**
     * 四个朝向的形状（下标 = {@link #blockRotation} / 90，{@code 0} = 北 = 模型原始朝向）。
     *
     * <p>没有 {@code facing} 的物件只用 {@code [0]}，但四个方向都在构造期一次算好 ——
     * 59 个方块 × 3 次 {@code VoxelShapes.union} 是注册期一次性的开销，换来子类
     * （{@link EyecandyDirectionalBlock}）不需要在构造器里再做形状工作。
     */
    private final VoxelShape[] shapes;

    /** 有没有碰撞（桥墩没有，只有轮廓）。 */
    private final boolean collides;

    /**
     * @param boxes   模型空间（1/16 格、{@code x/z ∈ 0..16}）里的盒子列表，{@code [x1, y1, z1, x2, y2, z2]}
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
    }

    /** 这一块用的是哪一条描述（渲染器按它挑模型 / 贴图）。 */
    public EyecandyEntry entry() {
        return this.entry;
    }

    /** 有没有碰撞（给 {@code tools/verify-eyecandies.js} 与调试命令用）。 */
    public boolean collides() {
        return this.collides;
    }

    /** 北向形状（调试 / 日志用）。 */
    public VoxelShape shape() {
        return this.shapes[0];
    }

    /** 这个物件会不会按玩家朝向转（只有栏杆，见 {@link EyecandyDirectionalBlock}）。 */
    public boolean directional() {
        return false;
    }

    /** 当前状态该用哪一份形状（没有 {@code facing} 的物件恒为北向那一份）。 */
    public VoxelShape shapeFor(final BlockState state) {
        return this.shapes[0];
    }

    /** 某个水平朝向该用哪一份形状（下标 = {@link #blockRotation} / 90）。 */
    protected VoxelShape shapeFor(final Direction facing) {
        return this.shapes[blockRotation(facing) / 90];
    }

    /** 空壳方块实体（BER 的锚点，见 {@link EyecandyBlockEntity}）。 */
    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new EyecandyBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return shapeFor(state);
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return this.collides ? shapeFor(state) : VoxelShapes.empty();
    }

    /** 空集：不让邻居朝我们的面被剔除（物件不是满方块）。 */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    // ------------------------------------------------------------------ 形状

    /**
     * 把模型空间（0..16）的一组盒子并成一个 {@link VoxelShape}。
     *
     * <p>盒子已经是轴对齐的（{@link EyecandyBlocks} 注册期算好的），所以直接并，不用细分。
     */
    static VoxelShape shapeOf(final List<double[]> boxes) {
        return shapeOf(boxes, 0);
    }

    /**
     * 把模型空间的一组盒子绕方块中心转 {@code yRotation} 度，并成一个 {@link VoxelShape}。
     *
     * <p>每 90° 一步 {@code (x, z) → (16 − z, x)}，与 {@code dep_on_wall} / 站外标志 / 墙上扶手
     * （{@code RailingWallBlock#rotate}）同一套约定 —— 四个方向都是精确的轴对齐盒子。
     * 渲染器用 {@code RotationAxis.NEGATIVE_Y.rotationDegrees(同一个角度)}，两份必须一致。
     */
    static VoxelShape shapeOf(final List<double[]> boxes, final int yRotation) {
        final int steps = (((yRotation % 360) + 360) % 360) / 90;
        final List<VoxelShape> parts = new ArrayList<>(boxes.size());
        for (final double[] b : boxes) {
            double x1 = b[0];
            double y1 = b[1];
            double z1 = b[2];
            double x2 = b[3];
            double y2 = b[4];
            double z2 = b[5];
            for (int i = 0; i < steps; i++) {
                final double nx1 = 16.0D - z2;
                final double nx2 = 16.0D - z1;
                z1 = x1;
                z2 = x2;
                x1 = nx1;
                x2 = nx2;
            }
            parts.add(Block.createCuboidShape(x1, y1, z1, x2, y2, z2));
        }
        VoxelShape shape = VoxelShapes.empty();
        for (final VoxelShape part : parts) {
            shape = VoxelShapes.union(shape, part);
        }
        return shape;
    }

    /**
     * {@code facing} → 绕 Y 的角度（{@code north=0 / east=90 / south=180 / west=270}）。
     *
     * <p>形状下标（{@link #shapes}）和渲染器（{@link EyecandyRenderer}）必须用同一个数，
     * 否则模型转了碰撞盒不转。
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
}
