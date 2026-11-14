// 墙上扶手 —— 每个几何段一个独立方块：只有 facing 一个属性，没有邻居推导、没有模型切换。
// 任意位置可放（不再要求背后有墙），碰撞 / 框选照 MSD 的做法：每段一两个手写粗包围盒。
package dev.js.mtrheadlight.railing;

import java.util.Arrays;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * 墙上扶手 {@code mtrlte:railing_wall_<段名>} —— <b>一个方块 = 一个模型文件</b>。
 *
 * <h2>为什么是多个方块</h2>
 * <p>几何素材（{@code 素材/墙上扶手/}）本来就是 11 个互相独立的 {@code railing_stair_*_on_wall.json}：
 * 直段 / 斜段（含下坡镜像）/ 斜段起点 / 端部收口 / 内转角 / 外转角（素材里的 {@code corner_2}），
 * 每种再有一份沿走向镜像的版本。新版 MSD 也是这么切模型的（{@code assets/msd/models/block/railing_stair*.json}
 * 一个几何段一个文件），只是它把这 20 多个模型塞进<b>一个</b>方块的 {@code type} 属性里切换。
 * <b>这里不要那套切换逻辑</b>：每段各注册一个方块 + 一个物品，玩家自己挑；方块上只有一个 {@code facing}。
 *
 * <h2>facing 的含义</h2>
 * <p>{@code facing} = <b>扶手贴在哪一侧</b>（= 扶手背面靠的那面墙的外法线方向），与 MSD 的语义、
 * 以及模型文件的 {@code y} 旋转表一致：
 * <pre>
 *   facing=west  → y=0    （模型原样：贴西侧，x=0..3）
 *   facing=north → y=90
 *   facing=east  → y=180
 *   facing=south → y=270
 * </pre>
 *
 * <h2>放置 / 拆除</h2>
 * <p>与 MSD 一样<b>任意位置可放</b>：不做 {@code isSideSolidFullSquare} 之类的支撑判断，
 * {@code canPlaceAt} 直接用默认值（恒真），也没有 {@code getStateForNeighborUpdate} ——
 * 拆墙、拆邻居、<b>拆掉自己脚下</b>都不会让方块掉落或换样子。悬空、贴墙、塞进斜段上方都只是建模问题。
 * <p>落位时 {@code facing}：点的是侧面（想贴哪面墙就点那面墙）→ 取<b>点击面的对侧</b>，扶手就贴在那面墙上；
 * 点的是顶面 / 底面 → 取玩家水平朝向（扶手贴在身前那一侧）。两种情况都不再询问世界。
 *
 * <h2>轮廓 / 碰撞（照参考模组的做法）</h2>
 * <p>参考模组（MSD 的 {@code BlockRailingStair}，字节码见 {@code getOutlineShape2}：盒子一律
 * {@code y=0..24}）不给每个模型元素算形状，而是按 {@code type} 挑一两个<b>手写的粗包围盒</b>
 * （横杆薄片，外转角再加一条垂直的臂），再交给 {@code IBlock.getVoxelShapeByDirection} 按 {@code facing} 旋转。
 * 这里照抄这个思路：{@code RailingWallBlocks} 里每段只写一两条 {@code box(...)}（模型空间：贴墙侧在
 * {@code x=0..3}、走向沿 {@code z}），由 {@link #rotate} 转到世界空间再 union。
 *
 * <h2>两种栏杆的碰撞高度统一成「地面 → 几何顶部」</h2>
 * <p><b>框选盒与碰撞盒用同一份（{@link #stretchToFloor} 拉过的那份）</b>：
 * <pre>
 *   墙上扶手  y = 0 .. 几何顶部（平直段 20、斜段 31、端部 20.5、内转角 21）
 *   玻璃扶手  y = 0 .. <b>同上</b>（2026-11-12 起不再取玻璃自己的 17.5/29.5，见下）
 * </pre>
 * 上一版把碰撞盒只留在几何那一层（墙上扶手 {@code y=18..31}），结果是<b>脚能直接走过去</b>：
 * 方块所在格子的 {@code y=0..18} 没有任何碰撞，人站着不动就在「栏杆里面」，只有胸口那一小片被挡。
 * 实机上就是「栏杆的碰撞高度不对」——要么穿过去、要么被一片悬空的薄片卡住。改成从地面起之后，
 * 栏杆与直觉一致：<b>整段挡人，和玻璃栏杆、和参考模组的栏杆同一种手感</b>。
 * <p>代价是贴着墙的一段薄片（{@code x=0..3}，3/16 格厚）也挡人 —— 那本来就是栏杆该有的行为；
 * 想要「能靠着走」就不该在这里放扶手。
 * <p><b>玻璃扶手（{@code RailingGlassBlocks}）不取自己的几何顶，而是照抄上表同一段的高度</b>：
 * 平直 / 外转角 {@code 20}、端部 {@code 20.5}、内转角 {@code 21}、斜段 {@code 31}。玻璃平直段的几何只到
 * {@code 17.5}（1.09 格），小于玩家跳跃顶点 {@code 1.2522} 格，原来<b>一跳就过去</b>；{@code 20}（1.25 格）
 * 正好把顶点压在下面，两种栏杆现在挡人高度一致。
 *
 * <h2>为什么框选盒要往下拉到 y=0（选不上的根因）</h2>
 * <p>这 11 个模型的几何都画在 {@code y≈14..31} —— 也就是说肉眼看到的扶手悬在<b>本方块格子的上方</b>。
 * 而 {@code BlockView.raycast} 是逐格推进的：一条射线只会去问它<b>穿过的那几格</b>里的方块要形状。
 * 站在平台上平视扶手时，射线可能整条都走在上面那一格（{@code y>16} 的空间）里，压根不会进到本方块所在的格子，
 * 于是框选盒再准也<u>一次都问不到</u>——这就是「有时候选不上」的真正原因。
 * <p>解法是把形状做成<b>从方块底面（{@code y=0}）一直到几何顶部</b>的连续薄片，于是本格子的下半部分也属于
 * 形状体，射线只要往下扫一点就会进格命中。{@link #stretchToFloor} 把 {@code RailingWallBlocks} 里每个盒子的
 * {@code y1} 压到 {@code 0}（{@code y2} 保持几何顶部），其余不动。11 段里斜段能顶到 {@code y≈30.7}，
 * 所以盒子允许超出 0..16 的方块高度，这与实际看到的几何一致；水平方向基本收在 0..16 内（只有 45° 斜杆的杆尖
 * 会探出 0.36 格，那几段的盒子写到 {@code -0.5..16.5} 把它裹住）。
 * <p><b>2026-11-12 起碰撞盒也用这份形状</b>（上一版只给框选用，碰撞留在几何那一层，见上一节）。
 */
public class RailingWallBlock extends Block {

    /** 扶手贴在哪一侧（见类注释的旋转表）。 */
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    /** 四个水平朝向的形状（从地面拉到几何顶部）：<b>框选与碰撞共用</b>，下标见 {@link Direction#getHorizontal()}。 */
    private final VoxelShape[] shapes = new VoxelShape[4];

    /**
     * @param settings   方块属性
     * @param modelBoxes 该段的粗包围盒，每项 {@code {x1, y1, z1, x2, y2, z2}}（模型空间坐标，单位 1/16 格）
     */
    public RailingWallBlock(AbstractBlock.Settings settings, double[][] modelBoxes) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
        final double[][] floorBoxes = stretchToFloor(modelBoxes);
        for (final Direction face : Direction.Type.HORIZONTAL) {
            shapes[face.getHorizontal()] = rotate(floorBoxes, face);
        }
    }

    /**
     * 框选用：把每个盒子的底面压到 {@code y=0}，顶面保持几何顶部 —— 参考模组的「一整条从地到顶的贴墙薄片」。
     *
     * <p>两个作用：① 本方块格子的下半部分也属于形状体，射线只要往下扫一点就会进格命中（几何悬在
     * {@code y≈14..31} 时，光靠贴着几何那一层的盒子会「隔着格子选不上」）；
     * ② 碰撞也从地面起，栏杆才是「挡人的一段」而不是「胸口一片悬空的薄片」。
     */
    private static double[][] stretchToFloor(double[][] boxes) {
        final double[][] out = new double[boxes.length][];
        for (int i = 0; i < boxes.length; i++) {
            final double[] b = boxes[i];
            out[i] = new double[]{b[0], 0, b[2], b[3], b[4], b[5]};
        }
        return out;
    }

    /** 把模型空间（贴墙侧在西、走向沿 z）的一组包围盒转到 {@code facing} 对应的世界空间。 */
    private static VoxelShape rotate(double[][] boxes, Direction facing) {
        final VoxelShape[] parts = new VoxelShape[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            final double[] b = boxes[i];
            final double x1 = b[0], y1 = b[1], z1 = b[2];
            final double x2 = b[3], y2 = b[4], z2 = b[5];
            parts[i] = switch (facing) {
                // y=90：模型 +z → 世界 -x，模型 +x → 世界 +z（贴北侧，z=0..3）
                case NORTH -> Block.createCuboidShape(16 - z2, y1, x1, 16 - z1, y2, x2);
                // y=180：模型 +x → 世界 -x，模型 +z → 世界 -z（贴东侧，x=13..16）
                case EAST -> Block.createCuboidShape(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
                // y=270：模型 +z → 世界 +x，模型 +x → 世界 -z（贴南侧，z=13..16）
                case SOUTH -> Block.createCuboidShape(z1, y1, 16 - x2, z2, y2, 16 - x1);
                // y=0：原样（贴西侧）
                default -> Block.createCuboidShape(x1, y1, z1, x2, y2, z2);
            };
        }
        return parts.length == 1
                ? parts[0]
                : VoxelShapes.union(parts[0], Arrays.copyOfRange(parts, 1, parts.length));
    }

    // ------------------------------------------------------------------ 方块行为

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        final Direction side = ctx.getSide();
        // 点侧面 = 想贴那面墙，扶手贴在对侧；点顶面 / 底面 = 取玩家朝向（身前那一侧）。
        final Direction facing = side.getAxis().isHorizontal()
                ? side.getOpposite()
                : ctx.getHorizontalPlayerFacing();
        return getDefaultState().with(FACING, facing);
    }

    // ------------------------------------------------------------------ 形状

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[state.get(FACING).getHorizontal()];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // 与框选同一份：从地面拉到几何顶部（两种栏杆统一，见类注释「碰撞高度」一节）。
        return shapes[state.get(FACING).getHorizontal()];
    }
}
