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
 *   <li>碰撞 / 轮廓：注册期按 {@link EyecandyBlocks} 给的盒子列表建成一个 {@link VoxelShape}；</li>
 *   <li>{@link #getCullingShape} 返回空集 + {@code nonOpaque()}：物件大多不是满方块，
 *       不这样邻居朝我们的面会被当成满方块剔除掉，缝隙里能看穿邻居（{@code dep_on_wall} 踩过这个坑）。</li>
 * </ul>
 *
 * <h2>没有朝向属性（也不吃刷子）</h2>
 * <p>这些物件<b>没有 {@code ROTATION} / {@code FACING} 之类的方块状态</b>：模型烘在 {@code .obj} 里
 * 是死的，方块的 blockstate 只有一个变体（{@code ""}），放置时不会因为玩家朝向而变样子 ——
 * 玩家放下时看到的就永远是素材里的那一副样子。以前这里有一个四向 {@code rotation} + MTR 刷子右键循环，
 * 用户实际用下来「刷子旋转没什么用，玩家放置的时候自然就转向了」，于是整套属性 / 刷子 / 碰撞盒四份
 * 都删掉了（现在只剩一份形状）。
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

    /** 轮廓 / 碰撞形状（模型空间 → 方块空间，注册期一次算好）。 */
    private final VoxelShape shape;

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
        this.shape = shapeOf(boxes);
    }

    /** 这一块用的是哪一条描述（渲染器按它挑模型 / 贴图）。 */
    public EyecandyEntry entry() {
        return this.entry;
    }

    /** 有没有碰撞（给 {@code tools/verify-eyecandies.js} 与调试命令用）。 */
    public boolean collides() {
        return this.collides;
    }

    /** 轮廓形状（调试用）。 */
    public VoxelShape shape() {
        return this.shape;
    }

    /** 空壳方块实体（BER 的锚点，见 {@link EyecandyBlockEntity}）。 */
    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new EyecandyBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return this.shape;
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return this.collides ? this.shape : VoxelShapes.empty();
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
        final List<VoxelShape> parts = new ArrayList<>(boxes.size());
        for (final double[] b : boxes) {
            parts.add(Block.createCuboidShape(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        VoxelShape shape = VoxelShapes.empty();
        for (final VoxelShape part : parts) {
            shape = VoxelShapes.union(shape, part);
        }
        return shape;
    }
}
