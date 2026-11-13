// 一个 eyecandy 物件的方块本体：几何交给 BER 画，碰撞 / 轮廓用从几何推出来的那个盒子。
package dev.js.mtrheadlight.xiamen.eyecandy;

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
 * 26 个 eyecandy 共用的方块类：<b>一个类 + 一份数据</b>（{@link EyecandyEntry}），不做朝向、没有状态属性。
 *
 * <h2>几何 / 碰撞 / 轮廓</h2>
 * <ul>
 *   <li>几何：运行时由 {@link EyecandyRenderer} 画 {@code eyecandies/*.obj}，方块的 blockstate 模型是
 *       {@code mtrlte:block/empty}（空的 {@code elements}），所以世界里的碰撞与渲染互不影响；</li>
 *   <li>碰撞 / 轮廓：同一个 {@link VoxelShape}，由 {@link EyecandyBlocks#shapeOf} 从 OBJ 顶点算出来
 *       （<b>不用</b>素材 {@code first.json} 里的 {@code collisionShape} —— 那是随手写的，很多物件是
 *       {@code 0,0,0,0,0,0}，也就是「没有碰撞」）；</li>
 *   <li>{@link #getCullingShape} 返回空集 + {@code nonOpaque()}：物件大多不是满方块，
 *       不这样邻居朝我们的面会被当成满方块剔除掉，缝隙里能看穿邻居（{@code dep_on_wall} 踩过这个坑）。</li>
 * </ul>
 *
 * <h2>放置 / 掉落</h2>
 * <p>任意位置可放（没有 {@code facing} 之类会限制放置的属性），掉自己（
 * {@code data/mtrlte/loot_tables/blocks/eyecandy_*.json} 一张一个）。不继承任何 MTR 的方块类 ——
 * 这些是纯装饰，不参与线路 / 列车 / 屏蔽门逻辑。
 */
public class EyecandyBlock extends Block implements BlockEntityProvider {

    private final EyecandyEntry entry;

    /** 碰撞盒 = 轮廓盒 = 从几何推出来的那个 AABB（方块空间的 0..1 单位）。 */
    private final VoxelShape shape;

    public EyecandyBlock(final Settings settings, final EyecandyEntry entry, final VoxelShape shape) {
        super(settings);
        this.entry = entry;
        this.shape = shape;
    }

    /** 这一块用的是哪一条描述（渲染器按它挑模型 / 贴图）。 */
    public EyecandyEntry entry() {
        return this.entry;
    }

    /** 几何推出来的盒子（给 {@code tools/verify-eyecandies.js} 与调试命令用）。 */
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
        return this.shape;
    }

    /** 空集：不让邻居朝我们的面被剔除（物件不是满方块）。 */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }
}
