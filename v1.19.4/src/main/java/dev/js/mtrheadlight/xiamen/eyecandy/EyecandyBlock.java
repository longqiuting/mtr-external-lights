// 一个 eyecandy 物件的方块本体：几何交给 BER 画，碰撞 / 轮廓用注册期算好的盒子（四向各一份）。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 全部 eyecandy 共用的方块类：<b>一个类 + 一份数据</b>（{@link EyecandyEntry}）。
 *
 * <h2>几何 / 碰撞 / 轮廓</h2>
 * <ul>
 *   <li>几何：运行时由 {@link EyecandyRenderer} 画 {@code eyecandies/*.obj}，方块的 blockstate 模型是
 *       {@code mtrlte:block/empty}（空的 {@code elements}），所以世界里的碰撞与渲染互不影响；</li>
 *   <li>碰撞 / 轮廓：注册期按 {@link EyecandyBlocks#boxesOf} 给的盒子列表建成 {@link VoxelShape}，
 *       <b>四个朝向在构造期一次转好</b>（{@link #shapes}），{@link #shapeFor} 按 {@code facing} 取；</li>
 *   <li>{@link #getCullingShape} 返回空集 + {@code nonOpaque()}：物件大多不是满方块，
 *       不这样邻居朝我们的面会被当成满方块剔除掉，缝隙里能看穿邻居（{@code dep_on_wall} 踩过这个坑）。</li>
 * </ul>
 *
 * <h2>朝向：全部 59 件都有 {@code facing}</h2>
 * <p>每一件都有 {@code facing}（{@link Properties#HORIZONTAL_FACING}），<b>放置时按玩家朝向定一次</b>：
 * {@link #getPlacementState} 取 {@code getHorizontalPlayerFacing().getOpposite()}，
 * 也就是「物件落到玩家对面那条边」，与屏蔽门 / 栏杆那套手感一致（obj 原始几何贴在方块 +z（南）面上，
 * 取反后玩家朝北放 → {@code facing=SOUTH} → 绕方块中心 180° → 几何落到北边）。
 * <b>没有刷子交互</b>：用户明确说过「刷子旋转没什么用，玩家放置的时候自然就转向了」，
 * 所以朝向只在放置那一刻定一次，之后没有任何旋转手段（以前那套四向 {@code rotation} + MTR 刷子
 * 右键循环已经删掉，也不要再加回来）。
 *
 * <p>模型与碰撞盒用<b>同一个角度</b>：碰撞走 {@link #shapeFor}（构造期按 {@code (x, z) → (16 − z, x)}
 * 转好的四份），模型走 {@link EyecandyRenderer} 的 {@code RotationAxis.NEGATIVE_Y.rotationDegrees}。
 * 转角栏杆（{@code msd_corner_railing} / {@code xiamen_brt_railing_corner}）的 L 形占位格盒子也一样
 * 是在北向算好再整组转 —— 不是「只剩一份北向形状」。
 *
 * <h2>没有碰撞的物件（桥墩）</h2>
 * <p>{@code bridge_pier_20}（20 格高的桥墩）只给<b>轮廓</b>不给碰撞：轮廓是一个 1×1×1 的框（选中它看得见
 * 一格的范围，方便对齐），碰撞是空集 —— 玩家 / 列车的碰撞不该被一格方框挡住（真正的墩身在 20 格
 * 高的几何里，穿过去也不会卡住）。它<b>照样有 {@code facing}、照样按玩家朝向转</b>（整格框转完还是
 * 整格框，看不出差别但路径一致，不做例外）。
 *
 * <h2>放置 / 掉落</h2>
 * <p>任意位置可放，掉自己（{@code data/mtrlte/loot_tables/blocks/eyecandy_*.json} 一张一个）。
 * 不继承任何 MTR 的方块类 —— 这些是纯装饰，不参与线路 / 列车 / 屏蔽门逻辑。
 *
 * <h2>方块微光（{@link EyecandyEntry#glow()}）</h2>
 * <p>描述文件里可选的 {@code "glow": n} 是<b>方块自发光等级</b>（0..15，不写 = 0）：构造时
 * {@code settings.luminance(state -> entry.glow())}，所以整格方块自己去照亮周围（地铁站里带屏幕的
 * 机器夜里是亮的）。目前只有 {@code xiamen_metro_pm_kiosk}（P+M 查询机）写了 7 级，其余 58 件一个
 * 字段都没有 → 不碰 settings → 和以前一样不发光。
 */
public class EyecandyBlock extends Block implements BlockEntityProvider {

    /**
     * 朝向。语义 = <b>绕方块中心转过去的那个方向</b>（{@link #blockRotation} 取它算角度），
     * 不是「玩家看的方向」字面值：放置时取的是玩家的<b>反方向</b>，见 {@link #getPlacementState}。
     * {@code facing=NORTH} 就是模型原始朝向（0°，矩阵一次都不动）。
     */
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    private final EyecandyEntry entry;

    /**
     * 四个朝向的形状（下标 = {@link #blockRotation} / 90，{@code 0} = 北 = 模型原始朝向）。
     *
     * <p>四个方向都在构造期一次算好：59 个方块 × 3 次 {@code VoxelShapes.union} 是注册期
     * 一次性的开销，换来 {@link #shapeFor} 只做一次数组下标。
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
        // 方块微光：描述文件里写了 "glow" 的物件（目前只有 P+M 查询机）注册期就把 luminance 塞进
        // settings —— 也就是它自己发方块光照。没写的 58 件不碰 settings，走原版「不发光」的路径。
        super(entry.glow() > 0 ? settings.luminance(state -> entry.glow()) : settings);
        this.entry = entry;
        this.collides = collides;
        this.shapes = new VoxelShape[4];
        for (int i = 0; i < this.shapes.length; i++) {
            this.shapes[i] = shapeOf(boxes, i * 90);
        }
        // 默认 = 北 = 模型原始朝向（DirectionProperty 的候选顺序也是 NORTH / EAST / SOUTH / WEST，
        // 这里显式写一次，免得哪天顺序变了默认态跟着漂）。
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
    }

    /** 这一块用的是哪一条描述（渲染器按它挑模型 / 贴图）。 */
    public EyecandyEntry entry() {
        return this.entry;
    }

    /** 有没有碰撞（给 {@code tools/verify-eyecandies.js} 与调试命令用）。 */
    public boolean collides() {
        return this.collides;
    }

    /**
     * 这一件是不是「带警示灯的屏蔽门收纳机」（BRT psd 左 / 右）。
     *
     * <p>是的话渲染器会把它拆成「底座 + 一盏灯」画，并且方块实体开始跑
     * {@link EyecandyBlockEntity} 的状态机（见 {@link #getTicker}）。
     */
    public boolean hasWarningLight() {
        return EyecandyBlocks.isWarningLight(this.entry.key());
    }

    /**
     * 方块实体的 ticker：<b>只有警示灯那两件</b>返回（其它 57 件返回 {@code null}，
     * 也就是它们根本不 tick，一个空壳都不多跑）。
     *
     * <p>{@code type} 必须是本模组那个共享的 {@code mtrlte:eyecandy} —— 原版 {@code BlockEntityProvider}
     * 的约定：类型对不上就返回 {@code null}（免得「方块与方块实体错位」时崩）。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final World world, final BlockState state,
                                                                  final BlockEntityType<T> type) {
        if (!hasWarningLight() || (EyecandyBlocks.entityType() != null && type != EyecandyBlocks.entityType())) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<EyecandyBlockEntity>) EyecandyBlockEntity::tick;
    }

    /** 北向形状（调试 / 日志用）。 */
    public VoxelShape shape() {
        return this.shapes[0];
    }

    /** 当前状态该用哪一份形状（全部物件都有 {@code facing}，四份都用得上）。 */
    public VoxelShape shapeFor(final BlockState state) {
        return this.shapes[blockRotation(state.get(FACING)) / 90];
    }

    /** 某个水平朝向该用哪一份形状（下标 = {@link #blockRotation} / 90）。 */
    protected VoxelShape shapeFor(final Direction facing) {
        return this.shapes[blockRotation(facing) / 90];
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(FACING);
    }

    /**
     * 放置：<b>取玩家朝向的反方向</b> {@code getHorizontalPlayerFacing().getOpposite()} ——
     * 玩家朝北放 → {@code facing=SOUTH} → 整件绕中心转到北边（<b>玩家对面</b>那条边），背对玩家。
     * 这些 obj 的原始几何贴在方块 +z（南）那条边，不取反的话整件会落在玩家这一侧（贴脸）。
     *
     * <p>没有 {@code onUse}：这里<b>不提供刷子右键循环</b>，朝向只在放置那一刻定一次。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
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
            final double[] r = rotateBox(b, steps);
            parts.add(Block.createCuboidShape(r[0], r[1], r[2], r[3], r[4], r[5]));
        }
        VoxelShape shape = VoxelShapes.empty();
        for (final VoxelShape part : parts) {
            shape = VoxelShapes.union(shape, part);
        }
        return shape;
    }

    /**
     * 一个盒子转 {@code steps} 个 90°：{@code (x, z) → (16 − z, x)}（y 不动）。
     *
     * <p>{@code tools/verify-eyecandies.js} 用同一条公式独立重算四向碰撞，改这里要一起改。
     */
    static double[] rotateBox(final double[] box, final int steps) {
        double x1 = box[0];
        double y1 = box[1];
        double z1 = box[2];
        double x2 = box[3];
        double y2 = box[4];
        double z2 = box[5];
        for (int i = 0; i < steps; i++) {
            final double nx1 = 16.0D - z2;
            final double nx2 = 16.0D - z1;
            z1 = x1;
            z2 = x2;
            x1 = nx1;
            x2 = nx2;
        }
        return new double[] {x1, y1, z1, x2, y2, z2};
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
