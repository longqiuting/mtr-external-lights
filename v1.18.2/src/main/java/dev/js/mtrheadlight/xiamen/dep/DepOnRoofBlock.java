// 挂顶发车倒数器：吊在站台上方的天花板上，屏幕和挂墙版是同一块 96×64 的七段显示。
package dev.js.mtrheadlight.xiamen.dep;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import net.minecraft.block.Material;

/**
 * 挂顶发车倒数器 {@code mtrlte:dep_on_roof}（模型 {@code mtrlte:block/dep_on_roof}）。
 *
 * <h2>它和挂墙版是同一块牌子，只是吊在顶上</h2>
 * <p>读数、颜色、状态机、{@code /mtr_lte dep} 方向开关<b>全部共用</b>
 * （数值链路在 {@link DepCountdown}，字形在 {@link DepOnWallDigits}）：同样是
 * 「车没到站 → 空屏；开门后亮绿字从 dwell/2 倒数到 0:00；到点没走变红字正计 5 秒；然后黑屏」。
 * 区别只有两处：<b>怎么挂</b>（天花板而不是墙）和<b>屏幕在哪</b>（模型里那块板的位置不同，
 * 见 {@link DepOnRoofRenderer}）。
 *
 * <h2>模型空间：吊杆 + 一块转了 45° 的牌子</h2>
 * <p>{@code dep_on_roof.json}（Blockbench 导出，16 个元素）在方块里占
 * <b>x 1.7..14.5、y 0..16、z 0.9..13.6</b>：
 * <ul>
 *   <li><b>吊杆</b>：模型正中一根 {@code 0.6 × 6 × 3.6} 的杆，从 {@code y=10} 一直顶到
 *       {@code y=16}（= 本格的上边界），十个元素各自绕杆轴扭一点角度，看起来像一条拧起来的
 *       吊索；</li>
 *   <li><b>牌身</b>：一块 {@code 14 × 10} 的灯箱（下横梁 {@code y 0..1}、上横梁
 *       {@code y 9..10}、左右边柱各 1 格宽、厚 4 格），牌子正中 {@code x 2..14 / y 1..9}
 *       是那块零厚度的发光板（贴图 {@code dep_on_wall.png} 的 uv 3..6 × 0..2，和挂墙版同一块）；</li>
 *   <li><b>整块牌子绕 Y 转了 45°</b>（Blockbench 里写的是 {@code y=135}，导出后是
 *       {@code -45}，见 {@link #MODEL_YAW_DEGREES}）。所以它在<b>俯视图里是个菱形</b>：
 *       牌子不是贴着方块某一面，而是斜着吊在方块中间。这不是笔误 —— 源模型 16 个元素
 *       全是这个角度，牌子正是为了「从斜前方看得最清楚」才这么做的。</li>
 * </ul>
 *
 * <h2>模型：rawmodels/dep_on_roof.json（素材原样），BER 直接画</h2>
 * <p>原版 1.20.1 的元素旋转<b>只认 {@code -45 / -22.5 / 0 / 22.5 / 45}</b>
 * （{@code ModelElement.Deserializer} 里与 22.5f、45.0f 比较），而 Blockbench 导出的却是
 * {@code 135 / 154 / -87.5 / -73.5 / -59 …}，还有 {@code {"x":180,"y":-11.75,"z":180}}
 * 这种三轴同时转。以前的 {@code tools/gen-dep-on-roof-model.js} 只能「把整 90° 折进盒子坐标 +
 * 零头吸附到最近的合法角」—— 牌框那六件是 135°（折完正好 -45，精确），但<b>挂杆那十节</b>
 * 是用来拧绳感的手工角度，一吸附就变成了另一根杆子（还有 0.11~0.27/16 格的错位）。
 *
 * <p>现在整份模型走 {@code rawmodels/dep_on_roof.json}（由 {@code tools/gen-dep-rawmodels.js}
 * 从素材原样拷进 jar，只改贴图名），运行时由 {@code RawModelJson} 解析、{@code DepOnRoofRenderer}
 * 画出来 —— 任意角度、任意多轴都原样，挂杆十节一根不少。
 * {@code models/block/dep_on_roof.json} 只剩一个空占位（原版那份不再参与渲染）。
 *
 * <h2>那 90° 是怎么来的（{@link #MODEL_YAW_DEGREES} 与渲染器的补偿角）</h2>
 * <p>素材里的 135° 是<b>数学正角</b>（Blockbench / 原版 {@code BakedQuadFactory#rotateVertex}
 * 用的是 {@code Quaternionf.rotationAxis(+angle)}，俯视逆时针），而本类 {@link #yawedAabb}
 * 与 {@code DepOnRoofRenderer} 的屏幕坐标用的是 blockstate 那套<b>俯视顺时针</b>。
 * 两套差 90°：素材的 135° 折回这套就是 45°，牌面从西南拧到东南 ——
 * 所以渲染器画模型时会先补 {@code MODEL_YAW_FIX_DEGREES = +90°}，让模型和
 * <b>碰撞箱、数字层认定的那一面重合</b>。少了它，就是玩家看到的
 * 「碰撞箱和数字都对、模型偏了 90°」。数值核对见 {@code tools/verify-dep-orient.js}。
 *
 * <h2>{@code facing} 的含义（和挂墙版不一样，看这里）</h2>
 * <p>挂墙版的 {@code facing} = 屏幕法线；这一版因为模型自带 45°，
 * <b>{@code facing} 只是「把整块牌子再转过 90° 的哪一档」</b>，屏幕真正朝的方向是
 * {@code facing} 顺时针 135°：
 * <pre>
 *   facing=north (y=0)   → 牌面朝东南（= 模型原样）
 *   facing=east  (y=90)  → 牌面朝西南
 *   facing=south (y=180) → 牌面朝西北
 *   facing=west  (y=270) → 牌面朝东北
 * </pre>
 * <p>放置时（{@link #getPlacementState}）按<b>你在哪边看它</b>自动挑最近的那一档 ——
 * 挂上去之后屏幕朝你，不用再拿调试棒转。四个值都能落地，blockstate 的 {@code y} 还是
 * {@link #blockRotation} 那一套（{@code north=0 / east=90 / south=180 / west=270}）。
 *
 * <h2>挂顶：上面没东西就吊不住</h2>
 * <ul>
 *   <li>{@link #getPlacementState}：<b>本格上方</b>（{@code pos.up()}）不是完整实心面 →
 *       返回 {@code null}，右键放不出来（吊杆顶端正好顶到 {@code y=16}，上面必须有一块天花板）；</li>
 *   <li>{@link #canPlaceAt}：{@code /setblock} / 活塞 / 结构方块绕过 {@code getPlacementState}
 *       的路径也挡一道；</li>
 *   <li>{@link #getStateForNeighborUpdate}：天花板被拆 → 自己变空气（原版火把 / 告示牌同一套）。</li>
 * </ul>
 * <p>注意和挂墙版的区别：挂墙版支撑在 {@code facing} 的背面，这里支撑永远在<b>上方</b>，
 * 和 {@code facing} 无关 —— 牌子可以朝四个斜向中的任意一个，但吊点只有一个。
 *
 * <h2>碰撞 / 轮廓：牌框那一圈，不是整格</h2>
 * <p>牌子是斜的，而 {@code VoxelShape} 只能拼轴对齐盒子，所以形状取<b>四根框料</b>
 * （下横梁 / 上横梁 / 左右边柱）沿牌宽切成 {@code ≤ 2.5} 格的小段、各自取偏转后的包围盒，
 * 再并成一个 {@link VoxelShapes#union}（见 {@link #frameBoxes()}）。段越细越贴合那块斜板；
 * 2.5 格时斜边的空腔误差约 1.7 格，站在牌子旁边不会被一道「看不见的墙」挡住，也不会走进牌子里。
 * <p>屏幕本身（零厚度）和吊杆都不参与碰撞：前者没有体积，后者在头顶以上。
 */
public class DepOnRoofBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /**
     * 模型自带的偏转：牌子绕 Y 轴转了 {@code -45}°（Blockbench 里是 {@code y=135}，
     * 因为 +180° 已经被折进盒子的坐标里了，见 {@code models/block/dep_on_roof.json}）。
     * <p>这个角只有「模型本来长什么样」的含义，改它等于换模型。
     */
    static final float MODEL_YAW_DEGREES = -45.0F;

    /** 偏转中心（模型空间 1/16 格）：Blockbench 里那 16 个元素的 {@code origin}。 */
    static final double MODEL_PIVOT_X = 8.0D;
    static final double MODEL_PIVOT_Z = 7.15D;

    /**
     * 牌面法线相对 {@code facing} 的偏移（度，顺时针）：正的 90° 是「方块正面」，
     * 再加模型自带的 45°，一共 135°。{@link #facingForNormal} 反过来解这个式子。
     */
    static final int NORMAL_OFFSET_DEGREES = 135;

    /**
     * 牌框四根（<b>偏转前</b>的模型空间，1/16 格）：{@code x1, y1, z1, x2, y2, z2}。
     * <p>就是 {@code dep_on_roof.json} 里那四根梁 / 柱的 {@code from / to} ——
     * 那块零厚度的发光板（{@code z=9}）和背板不在里面，它们没有体积。
     */
    private static final double[][] FRAME = {
            // 下横梁 y 0..1、上横梁 y 9..10
            {1.0D, 0.0D, 5.3D, 15.0D, 1.0D, 9.3D},
            {1.0D, 9.0D, 5.3D, 15.0D, 10.0D, 9.3D},
            // 左边柱 x 1..2、右边柱 x 14..15
            {1.0D, 1.0D, 5.3D, 2.0D, 9.0D, 9.3D},
            {14.0D, 1.0D, 5.3D, 15.0D, 9.0D, 9.3D},
    };

    /** 牌框切段的宽度（1/16 格）。 */
    private static final double SLICE = 2.5D;

    /** 四个朝向的形状（已经把 45° 偏转和 blockstate 的 {@code y} 都算进去）。 */
    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static {
        final List<double[]> boxes = frameBoxes();
        for (int i = 0; i < SHAPES.length; i++) {
            SHAPES[i] = shapeOf(boxes, i * 90);
        }
    }

    public DepOnRoofBlock() {
        // nonOpaque()：牌子是斜的、只占方块一部分，不这样邻居朝我们的那一面会被按满方块剔除。
        super(AbstractBlock.Settings.of(Material.STONE)
                .mapColor(MapColor.IRON_GRAY)
                .strength(1.2F)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque());
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    // ------------------------------------------------------------------ 形状

    /**
     * 牌框四根沿牌宽切成小段、各自绕 {@link #MODEL_YAW_DEGREES} 偏转后的轴对齐包围盒
     * （模型空间 1/16 格）。
     *
     * <p>盒子先偏转、再交给 {@link #shapeOf} 按 blockstate 的 {@code y} 转 —— 顺序不能反：
     * 偏转不是 90° 的倍数，只能在「模型自己的空间」里做，做完才有包围盒。
     */
    private static List<double[]> frameBoxes() {
        final List<double[]> out = new ArrayList<>();
        for (final double[] b : FRAME) {
            final double width = b[3] - b[0];
            final int slices = Math.max(1, (int) Math.ceil(width / SLICE));
            for (int i = 0; i < slices; i++) {
                final double x1 = b[0] + width * i / slices;
                final double x2 = b[0] + width * (i + 1) / slices;
                out.add(yawedAabb(x1, b[1], b[2], x2, b[4], b[5]));
            }
        }
        return out;
    }

    /**
     * 一个盒子的四个水平角绕 ({@link #MODEL_PIVOT_X}, {@link #MODEL_PIVOT_Z}) 转
     * {@link #MODEL_YAW_DEGREES} 之后，取包围盒。
     *
     * <p>旋转约定与 blockstate / 元素 rotation 同一套：{@code x' = x·cos − z·sin}、
     * {@code z' = x·sin + z·cos}（正角 = 从上往下看顺时针）。
     */
    private static double[] yawedAabb(final double x1, final double y1, final double z1,
                                      final double x2, final double y2, final double z2) {
        final double rad = Math.toRadians(MODEL_YAW_DEGREES);
        final double cos = Math.cos(rad);
        final double sin = Math.sin(rad);
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (final double x : new double[]{x1, x2}) {
            for (final double z : new double[]{z1, z2}) {
                final double dx = x - MODEL_PIVOT_X;
                final double dz = z - MODEL_PIVOT_Z;
                final double rx = MODEL_PIVOT_X + dx * cos - dz * sin;
                final double rz = MODEL_PIVOT_Z + dx * sin + dz * cos;
                minX = Math.min(minX, rx);
                maxX = Math.max(maxX, rx);
                minZ = Math.min(minZ, rz);
                maxZ = Math.max(maxZ, rz);
            }
        }
        return new double[]{minX, y1, minZ, maxX, y2, maxZ};
    }

    /**
     * 把模型空间里的一组盒子按 blockstate 的 {@code y} 转到世界（每 90° 都是
     * {@code (x, z) → (16 − z, x)}，和 {@code dep_on_wall} / 站外标志同一套）。
     */
    private static VoxelShape shapeOf(final List<double[]> boxes, final int yRotation) {
        final List<VoxelShape> parts = new ArrayList<>(boxes.size());
        for (final double[] b : boxes) {
            double x1 = b[0];
            double x2 = b[3];
            double z1 = b[2];
            double z2 = b[5];
            switch (((yRotation % 360) + 360) % 360) {
                case 90 -> {
                    final double nx1 = 16.0D - z2;
                    final double nx2 = 16.0D - z1;
                    z1 = x1;
                    z2 = x2;
                    x1 = nx1;
                    x2 = nx2;
                }
                case 180 -> {
                    final double nx1 = 16.0D - x2;
                    final double nx2 = 16.0D - x1;
                    final double nz1 = 16.0D - z2;
                    final double nz2 = 16.0D - z1;
                    x1 = nx1;
                    x2 = nx2;
                    z1 = nz1;
                    z2 = nz2;
                }
                case 270 -> {
                    final double nx1 = z1;
                    final double nx2 = z2;
                    z1 = 16.0D - x2;
                    z2 = 16.0D - x1;
                    x1 = nx1;
                    x2 = nx2;
                }
                default -> {
                    // north：模型空间就是世界空间。
                }
            }
            parts.add(Block.createCuboidShape(x1, b[1], z1, x2, b[4], z2));
        }
        // union(VoxelShape, VoxelShape) 一个一个并：初始化时算一次，14 个盒子不值得按数组拼。
        VoxelShape shape = VoxelShapes.empty();
        for (final VoxelShape part : parts) {
            shape = VoxelShapes.union(shape, part);
        }
        return shape;
    }

    /**
     * facing → blockstate {@code y}。必须与
     * {@code assets/mtrlte/blockstates/dep_on_roof.json} 里的四个变体一模一样，
     * 否则模型转了、碰撞箱没转（或者反过来）。
     */
    public static int blockRotation(final Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            // 只会是水平方向（FACING 就是 HORIZONTAL_FACING），UP/DOWN 在这里兜底成北。
            case NORTH, UP, DOWN -> 0;
        };
    }

    /** 水平方向的「MC 角度」：{@code north=0 / east=90 / south=180 / west=270}（顺时针）。 */
    private static int angleOf(final Direction direction) {
        return blockRotation(direction);
    }

    /**
     * 让<b>牌面</b>朝 {@code wanted} 的 {@code facing}。
     *
     * <p>牌面法线 = {@code facing} 顺时针 {@code 135°}（模型自带 45° 偏转），所以这里解
     * {@code 135 + blockRotation(facing) ≈ wanted}，取最近的那一档 90°。牌子是斜的，
     * 永远只能「尽量」朝某个正方向 —— 最近的那一档差 45°，这是模型本身决定的。
     */
    public static Direction facingForNormal(final Direction wanted) {
        final int wantedAngle = angleOf(wanted);
        int rotation = Math.round((wantedAngle - NORMAL_OFFSET_DEGREES) / 90.0F) * 90;
        rotation = ((rotation % 360) + 360) % 360;
        return switch (rotation) {
            case 90 -> Direction.EAST;
            case 180 -> Direction.SOUTH;
            case 270 -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    private static VoxelShape shapeFor(final Direction facing) {
        return SHAPES[blockRotation(facing) / 90];
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        return shapeFor(state.get(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return shapeFor(state.get(FACING));
    }

    // ------------------------------------------------------------------ 放置 / 挂顶

    /** 吊点：本格上方那一格的天花板。 */
    public static BlockPos supportPos(final BlockPos pos) {
        return pos.up();
    }

    /** 上面有没有一块完整的实心底面朝着我们。和 {@code facing} 无关。 */
    private static boolean hasSupport(final WorldView world, final BlockPos pos) {
        final BlockPos support = supportPos(pos);
        return world.getBlockState(support).isSideSolidFullSquare(world, support, Direction.DOWN);
    }

    /**
     * 挂上去时屏幕朝<b>放置的玩家</b>：取玩家视线方向的反向，再落到最近的那一档
     * （见 {@link #facingForNormal}）。
     *
     * <p>和站外标志的「杆上款」同一个约定（{@code getHorizontalPlayerFacing().getOpposite()}）：
     * 你把它挂在头顶，抬头就该看见读数，而不是看见背面。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final Direction facing = facingForNormal(ctx.getPlayerFacing().getOpposite());
        // 任意位置可放：不再要求上方有天花板。
        return getDefaultState().with(FACING, facing);
    }

    /**
     * 任意位置可放：不再要求背后 / 上方有支撑（{@link #getStateForNeighborUpdate} 里那句
     * 「支撑没了变空气」也因此永远不会触发 —— 它问的就是这个方法）。
     */
    // 任意位置可放
    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        return true;
    }

    /** 上面的天花板没了 → 自己变空气（走邻居更新；玩家直接挖本体照常掉物品）。 */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        if (direction == Direction.UP && !canPlaceAt(state, world, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        // 物品栏渲染 / 结构方块可能给不到真实 state，用默认态兜底（MTR 的方块也照传 null）。
        return new DepOnRoofBlockEntity(pos, state == null ? getDefaultState() : state);
    }
}
