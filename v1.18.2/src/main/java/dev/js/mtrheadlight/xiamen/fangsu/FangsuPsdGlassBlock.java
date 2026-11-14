// 方速厦门屏蔽门的玻璃板 —— 一个方块 = 完整一格（格内左右两个半片），几何全部由 BER 画。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.ArrayList;
import java.util.List;

import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.text.TranslatableText;
import net.minecraft.text.LiteralText;
import net.minecraft.block.Material;

/**
 * 方速厦门屏蔽门的<b>玻璃板</b>（{@code fangsu_psd_screendoor_high_glass}）——
 * <b>一个方块 = 一整个格</b>，格内左右各半个格拼起来。
 *
 * <h2>一格，不是两格</h2>
 * <p>方速文档 {@code screendoor_glass} 写的是「<b>一个</b>玻璃方块是由左右两部分拼接而成的」，
 * 对照 {@code BlockEntityScreendoorGlass} 的公开接口，它也是一个方块实体同时持有
 * {@code subModelLeft} + {@code subModelRight}（{@code whenRendering} 里两遍 {@code drawModel}）
 * 和 {@code shapeLeft} + {@code shapeRight}（{@code reloadModelAndShape} 里
 * {@code addAll} 合成一个 {@code glassShape}，{@code getFinalShape()} 只返回它）。
 * 几何也是两半各 0.5 格：{@code glass_left_glass} x ∈ [-0.5, 0]，{@code glass_right_glass}
 * x ∈ [0, 0.5]，接缝在方块中心。
 *
 * <h2>形态：两半各一个下标 + 两半各一个「自动」开关</h2>
 * <p>{@code glass.left[]} / {@code glass.right[]} 是<b>两份独立的形态列表</b>（方速的
 * {@code getSubModelInfos()} 也是两个下拉框），所以状态里有四个属性：
 * <ul>
 *   <li>{@link #FORM_LEFT} / {@link #FORM_RIGHT}：0..3
 *       （{@code door} / {@code doorend} / {@code glass} / {@code longend}）——
 *       <b>已经求好的形态</b>，碰撞盒和渲染都直接用它；</li>
 *   <li>{@link #AUTO_LEFT} / {@link #AUTO_RIGHT}：这一半是不是「自动档」
 *       （= 方速下拉框里的 {@code auto}）。自动档的形态由
 *       {@code glass.auto[]} 规则按左右邻居算出来，写在上面那两个属性里；
 *       手动档（{@code false}）的形态玩家说了算，邻居怎么变都不动。</li>
 * </ul>
 * 默认两半都是自动档 —— <b>刚放下就自动和邻居连上</b>，这就是 MTR 原版屏蔽门的那个手感。
 *
 * <p>操作：<b>MTR 刷子右键</b>点左半换左半、点右半换右半
 * （循环顺序 = 下拉框顺序：{@code auto → door → doorend → glass → longend → auto}）；
 * <b>潜行 + 刷子</b>把这一排（连通的那一串玻璃）全部<b>退回自动档并重连</b>
 * —— 对方速就是 {@code whenUseWithBrush} 里的 {@code triggerAutoSync}。
 * 别的物品右键完全不响应。形态换了碰撞盒跟着换 —— 两件事都从同一个 {@code shape} 数组来，
 * 不会「看得见摸不着」。
 *
 * <h2>邻居自动连接（{@code glass.auto[]}）</h2>
 * <p>规则表在 {@link FangsuPsdGlassDef#auto()} 里（从素材 {@code glass.auto[]} 原样读出来的），
 * 求值见 {@link #relink}：正序找第一条命中、且写了这一侧的规则，
 * 邻居只看「沿屏蔽门这条线、面朝 {@code FACING} 时的左手 / 右手那个方块」是
 * 门扇 / 玻璃 / 没有（见 {@link #kindAt}）—— 和方速 {@code FacingBlockUtil#getLeftPos} 一致。
 *
 * <p>三处触发重算，凑齐「和 MTR 一样」的体验：
 * <ol>
 *   <li><b>放置</b>：{@link #getPlacementState} 里就按规则算一次；</li>
 *   <li><b>邻居变化</b>：{@link #getStateForNeighborUpdate} —— 左右邻居被放 / 拆 / 换，本块跟着重连，
 *       和 {@code BlockPSDAPGBase} 那套「放下去自己就连上」是同一个做法；</li>
 *   <li><b>刷子</b>：刷一下顺手把这块和左右相邻玻璃的自动档一起重算（
 *       {@link #refreshRun}，对方速 {@code notifyNeighborsForAuto}）。</li>
 * </ol>
 *
 * <p>素材这套规则里 {@code doorend} / {@code longend} <b>auto 永远选不到</b>（前 6 条规则覆盖了
 * 全部分支，{@code "if": "TRUE"} 那条只是兜底）—— 端头形态只能手动刷，和方速只能在下拉框里手选
 * 是同一个事实。邻居门扇不区分款式（方速的 {@code BlockRelation} 也只认类型）。
 *
 * <h2>为什么单独一个方块类（不继承 {@code BlockPSDAPGDoorBase}）</h2>
 * <ul>
 *   <li><b>不开合</b>：玻璃板是固定件。继承的话 MTR 的 {@code TrainServer#openDoors} 会把它当门扇，
 *       还会在「开门」时把碰撞设成空 —— 那就成了「列车一停玻璃就没了，人可以直接走出去」。
 *       所以这里是普通方块 + 一个空壳方块实体（只为挂 BER）；</li>
 *   <li><b>碰撞常开</b>：几何是 3 格高的板材（{@code y 0..48}），碰撞盒照素材的 {@code shape} 原值，
 *       <b>竖直方向不裁</b>（同一格里给出 3 格高的碰撞，和看到的板一样高），
 *       只把 x / z 裁到本格 {@code 0..16}（伸到邻居格的部分不归这块管）；</li>
 *   <li><b>四向</b>：{@code facing} 和门扇同一套（{@code north=0 / east=90 / south=180 / west=270}），
 *       放置时取玩家朝向（{@link FangsuPsdPlacement}）。形状按「左形态 × 右形态 × 四向」
 *       <b>注册期预烘</b>（见 {@link #buildShape}），渲染器用的是同一套角度，
 *       永远不会「模型转了碰撞没转」。</li>
 * </ul>
 *
 * <h2>低款没有玻璃</h2>
 * <p>{@code screendoor/xiamen/low.json} 的 {@code door_low.obj} 里只有 {@code o left} / {@code o right}
 * 两个门扇组、<b>没有玻璃组</b>（低款本来就是半高的），所以只有 high 那款出玻璃方块。
 */
public class FangsuPsdGlassBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /**
     * <b>左半</b>（格内 x 8..16 那半）的当前形态，下标进 {@link FangsuPsdGlassDef#left()}
     * （{@code glass.left[]} 的顺序）。
     *
     * <p>上限写死 4：素材里 {@code glass.left} 有 4 条真形态
     * （{@code door / doorend / glass / longend}）。加更多形态时把这里和 {@link #MAX_FORMS} 一起改。
     * <b>自动档也写在这个属性里</b>（由规则算出来的那个形态），另见 {@link #AUTO_LEFT}。
     */
    public static final IntProperty FORM_LEFT = IntProperty.of("form_left", 0, 3);

    /** <b>右半</b>（格内 x 0..8 那半）的当前形态，下标进 {@link FangsuPsdGlassDef#right()}。 */
    public static final IntProperty FORM_RIGHT = IntProperty.of("form_right", 0, 3);

    /**
     * 左半是不是<b>自动档</b>（方速下拉框里的 {@code auto}）。
     *
     * <p>{@code true} = 形态由 {@code glass.auto[]} 按邻居算，邻居一变就重算；
     * {@code false} = 玩家手动指定的形态，邻居怎么变都不动。
     */
    public static final BooleanProperty AUTO_LEFT = BooleanProperty.of("auto_left");

    /** 右半是不是自动档（同 {@link #AUTO_LEFT}）。 */
    public static final BooleanProperty AUTO_RIGHT = BooleanProperty.of("auto_right");

    /** {@link #FORM_LEFT} / {@link #FORM_RIGHT} 的上限 + 1。 */
    private static final int MAX_FORMS = 4;

    /** 水平四向的 4 份形状（索引 = {@code facing} 的 90° 倍数 / 90）。 */
    private static final int FACINGS = 4;

    /** 「潜行 + 刷子」整排重连时最多走多少格（防止在超大结构上一个右键卡住）。 */
    private static final int MAX_RUN = 64;

    private final FangsuPsdGlassDef def;

    /**
     * 碰撞 / 轮廓盒：{@code [左形态][右形态][朝向]}。
     *
     * <p>一份形状 = 左半的盒子 ∪ 右半的盒子（{@link #buildShape}）—— 和方速
     * {@code glassShape = shapeLeft ∪ shapeRight} 同一个意思，只是我们把这 4×4 种组合在注册期就全烘好。
     */
    private final VoxelShape[][][] shapes;

    private Item item;

    public FangsuPsdGlassBlock(final FangsuPsdGlassDef def) {
        // 玻璃：半透明感由渲染器的 cutout 层给（贴图自己带 alpha），方块本身不发光。
        super(AbstractBlock.Settings.of(Material.STONE)
                .mapColor(MapColor.CLEAR)
                .strength(1.0F)
                .sounds(BlockSoundGroup.GLASS)
                .nonOpaque());
        this.def = def;
        final int leftForms = Math.min(MAX_FORMS, def.left().size());
        final int rightForms = Math.min(MAX_FORMS, def.right().size());
        this.shapes = new VoxelShape[leftForms][rightForms][FACINGS];
        for (int left = 0; left < leftForms; left++) {
            for (int right = 0; right < rightForms; right++) {
                // 四向的形状索引和 {@link FangsuPsdDoorBlock#shapeQuarterTurns} 是同一张表
                // （方速的 shape 比模型多要 180° —— 方速自己渲染模型时用了 rotateY(... + PI)，
                // 而 shape 只转 -toRadians(facing.getHorizontal())，少了这 180° 会变成
                // 「模型在左、碰撞在右」；玻璃两半各 0.5 格，偏了立刻看得出来）。
                for (int rotation = 0; rotation < FACINGS; rotation++) {
                    this.shapes[left][right][rotation] = buildShape(
                            def.leftForm(left).shape(), def.rightForm(right).shape(), rotation);
                }
            }
        }
        setDefaultState(getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(FORM_LEFT, Math.min(MAX_FORMS - 1, def.defaultLeft()))
                .with(FORM_RIGHT, Math.min(MAX_FORMS - 1, def.defaultRight()))
                .with(AUTO_LEFT, true)
                .with(AUTO_RIGHT, true));
    }

    /** 这个方块对应的描述（渲染器 / 网格缓存都从这里取）。 */
    public FangsuPsdGlassDef def() {
        return this.def;
    }

    /** 左半现在是第几种形态（越界夹到合法范围）。自动档读到的是规则算好的结果。 */
    public int leftFormOf(final BlockState state) {
        return clampForm(state.contains(FORM_LEFT) ? state.get(FORM_LEFT) : this.def.defaultLeft(),
                this.def.left().size());
    }

    /** 右半现在是第几种形态。 */
    public int rightFormOf(final BlockState state) {
        return clampForm(state.contains(FORM_RIGHT) ? state.get(FORM_RIGHT) : this.def.defaultRight(),
                this.def.right().size());
    }

    /** 左半是不是自动档。 */
    public static boolean isAutoLeft(final BlockState state) {
        return state.contains(AUTO_LEFT) && state.get(AUTO_LEFT);
    }

    /** 右半是不是自动档。 */
    public static boolean isAutoRight(final BlockState state) {
        return state.contains(AUTO_RIGHT) && state.get(AUTO_RIGHT);
    }

    /** 左半用的形态 id（动作栏提示 / 调试用）。 */
    public String leftKeyOf(final BlockState state) {
        return isAutoLeft(state) ? FangsuPsdGlassDef.AUTO_KEY
                : this.def.leftForm(leftFormOf(state)).key();
    }

    /** 右半用的形态 id。 */
    public String rightKeyOf(final BlockState state) {
        return isAutoRight(state) ? FangsuPsdGlassDef.AUTO_KEY
                : this.def.rightForm(rightFormOf(state)).key();
    }

    private static int clampForm(final int stored, final int forms) {
        return Math.max(0, Math.min(Math.min(MAX_FORMS, forms) - 1, stored));
    }

    /** 物品（注册时回填）。 */
    public void setItem(final Item item) {
        this.item = item;
    }

    @Override
    public Item asItem() {
        return this.item == null ? Items.AIR : this.item;
    }

    // ------------------------------------------------------------------ 形状

    /**
     * 把左右两半的盒子（1/16 格，1/16 = 1 px）烘成某个朝向的<b>一份</b> {@link VoxelShape}。
     *
     * <p>三件事：<b>两半合并</b>（对方速就是 {@code glassShape = shapeLeft ∪ shapeRight}，
     * 一格里的碰撞是两半的并集，不是两块方块各自一份）；<b>按 90° 转 x/z</b>
     * （每一步 {@code (x, z) → (16 − z, x)}，与 {@link FangsuPsdDoorBlock} / {@code EyecandyBlock}
     * 同一套约定，也和渲染器里 {@code NEGATIVE_Y} 那个矩阵是同一个方向）；<b>把 x / z 裁到本格</b>。
     * <b>y 不裁</b>：素材里玻璃板是 {@code y 0..48}，一块方块给出 3 格高的碰撞
     * （轮廓也一起高，选中的框和板一样高）。
     *
     * <p>形态自己的盒子决定「哪里是空的」——{@code doorend} 这种只占 {@code z 11..13} 的端板
     * 就只挡那 2 px，另一半该空的地方真的空着（{@code longend} 反而横跨 {@code z 0..14}）。
     *
     * @param quarters 转几个 90°（不是角度 —— 取值见 {@link #shapeQuarterTurns}）
     */
    private static VoxelShape buildShape(final List<double[]> left, final List<double[]> right,
                                         final int quarters) {
        final List<VoxelShape> parts = new ArrayList<>(left.size() + right.size());
        addBoxes(parts, left, quarters);
        addBoxes(parts, right, quarters);
        return parts.isEmpty() ? VoxelShapes.empty()
                : VoxelShapes.union(VoxelShapes.empty(), parts.toArray(new VoxelShape[0]));
    }

    private static void addBoxes(final List<VoxelShape> parts, final List<double[]> boxes, final int quarters) {
        for (final double[] box : boxes) {
            double x1 = box[0];
            double y1 = box[1];
            double z1 = box[2];
            double x2 = box[3];
            double y2 = box[4];
            double z2 = box[5];
            for (int i = 0; i < quarters; i++) {
                final double nx1 = 16.0D - z2;
                final double nx2 = 16.0D - z1;
                z1 = x1;
                z2 = x2;
                x1 = nx1;
                x2 = nx2;
            }
            x1 = Math.max(0.0D, x1);
            x2 = Math.min(16.0D, x2);
            z1 = Math.max(0.0D, z1);
            z2 = Math.min(16.0D, z2);
            if (x2 - x1 < 0.01D || z2 - z1 < 0.01D || y2 - y1 < 0.01D) {
                continue;
            }
            parts.add(VoxelShapes.cuboid(x1 / 16.0D, y1 / 16.0D, z1 / 16.0D,
                    x2 / 16.0D, y2 / 16.0D, z2 / 16.0D));
        }
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return shapeFor(state);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return shapeFor(state);
    }

    /**
     * 不让邻居朝我们的面被剔除。
     *
     * <p>默认 {@code getCullingShape} 是整格立方体，玻璃只有 1 px 厚，邻居的贴面会被判成「看不见」
     * 而不画，墙上就出现一个方块大小的洞（{@code dep_on_wall} / eyecandy 都踩过这个坑）。
     */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    private VoxelShape shapeFor(final BlockState state) {
        final int left = Math.min(this.shapes.length - 1, leftFormOf(state));
        final int right = Math.min(this.shapes[left].length - 1, rightFormOf(state));
        return this.shapes[left][right][shapeQuarterTurns(state)];
    }

    /**
     * 形状要转几个 90°（作用在 {@link #buildShape} 的 {@code (x, z) → (16 − z, x)} 上）。
     *
     * <p><b>和模型差 180°</b>：方速自己画的模型是
     * {@code rotateY(-toRadians(facing.getHorizontal()) + PI)}，而它的 {@code shape} 用的是
     * {@code -toRadians(facing.getHorizontal())}（没有 {@code + PI}）——
     * 所以 north 时模型是 0°、碰撞盒是 180°。玻璃板照抄这张表，才能和
     * {@link FangsuPsdDoorBlock#shapeQuarterTurns} 一致（详细反证见那条注释）。
     */
    private static int shapeQuarterTurns(final BlockState state) {
        return switch (state.get(FACING)) {
            case EAST -> 3;
            case SOUTH -> 0;
            case WEST -> 1;
            default -> 2; // NORTH
        };
    }

    // ------------------------------------------------------------------ 邻居自动连接（glass.auto）

    /** 「朝左」= {@code facing.rotateYCounterclockwise()}（方速 {@code getLeftDirection}，north 时是西）。 */
    private static Direction leftOf(final BlockState state) {
        return state.get(FACING).rotateYCounterclockwise();
    }

    /** 「朝右」= {@code facing.rotateYClockwise()}（方速 {@code getRightDirection}）。 */
    private static Direction rightOf(final BlockState state) {
        return state.get(FACING).rotateYClockwise();
    }

    /**
     * 沿屏蔽门这条线，那个方向上是什么 —— 方速 {@code BlockRelation} 的
     * {@code LEFT_BLOCK} / {@code RIGHT_BLOCK} 取值。
     *
     * <p>三种取值：
     * <ul>
     *   <li>{@link FangsuPsdGlassDef#SCREENDOOR_GLASS} 另一块同向玻璃；</li>
     *   <li>{@link FangsuPsdGlassDef#SCREENDOOR_DOOR} 我们自己的滑动门扇（{@link FangsuPsdDoorBlock}）；</li>
     *   <li>{@link FangsuPsdGlassDef#NULL} 没有 / 朝向不同（方速不查朝向，这里查 ——
     *       一堵垂直的玻璃墙不该把这一排连歪，转角处玩家手动刷一下就好）。</li>
     * </ul>
     */
    private static String kindAt(final BlockView world, final BlockPos pos, final Direction facing) {
        final BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof FangsuPsdGlassBlock) {
            return state.contains(FACING) && state.get(FACING) == facing
                    ? FangsuPsdGlassDef.SCREENDOOR_GLASS : FangsuPsdGlassDef.NULL;
        }
        if (state.getBlock() instanceof FangsuPsdDoorBlock) {
            // 门扇没有 FACING（不该发生）时按「是门」算，宁可连上也不要断开。
            return !state.contains(FACING) || state.get(FACING) == facing
                    ? FangsuPsdGlassDef.SCREENDOOR_DOOR : FangsuPsdGlassDef.NULL;
        }
        return FangsuPsdGlassDef.NULL;
    }

    /**
     * 按 {@code glass.auto[]} 求某一半的形态下标。
     *
     * <p>方速 {@code recomputeAuto} 是<b>倒序</b>遍历规则、每条命中的都覆盖一次 →
     * 等价于「正序取第一条命中、且写了这一侧的规则」（先出现的优先级高）。
     * 一条都没命中时退回素材里的默认形态（{@code id == "glass"}）。
     *
     * @param left 求左半（{@code true}）还是右半（{@code false}）
     */
    private int resolveAuto(final BlockState state, final BlockView world, final BlockPos pos,
                            final boolean left) {
        final Direction facing = state.get(FACING);
        final String leftBlock = kindAt(world, pos.offset(facing.rotateYCounterclockwise()), facing);
        final String rightBlock = kindAt(world, pos.offset(facing.rotateYClockwise()), facing);
        for (final FangsuPsdGlassDef.AutoRule rule : this.def.auto()) {
            if (!rule.matches(leftBlock, rightBlock)) {
                continue;
            }
            final String key = left ? rule.left() : rule.right();
            if (key == null) {
                continue; // 这条规则不管这一侧
            }
            final int index = left ? this.def.leftIndexOf(key) : this.def.rightIndexOf(key);
            if (index >= 0) {
                return index;
            }
        }
        return left ? this.def.defaultLeft() : this.def.defaultRight();
    }

    /**
     * 把自动档的那一半按规则重算一遍（手动档原样保留）。
     *
     * <p>没有 {@code world} 的时候就原样返回，调用方（渲染 / 碰撞）不会因为世界拿不到而炸。
     */
    BlockState relink(final BlockState state, final BlockView world, final BlockPos pos) {
        if (world == null || (!isAutoLeft(state) && !isAutoRight(state))) {
            return state;
        }
        BlockState out = state;
        if (isAutoLeft(state)) {
            out = out.with(FORM_LEFT, clampForm(resolveAuto(state, world, pos, true), this.def.left().size()));
        }
        if (isAutoRight(state)) {
            out = out.with(FORM_RIGHT, clampForm(resolveAuto(state, world, pos, false), this.def.right().size()));
        }
        return out;
    }

    /**
     * 邻居变了就把自动档重算一遍 —— 和 {@code BlockPSDAPGBase} 那套「放下去自己就连上」同一个做法。
     *
     * <p>只有<b>左右</b>（沿门线那两个方向）的邻居会影响形态，上下 / 前后一律不管。
     * 返回值变了会让原版自己再写一次方块状态，渲染器（BER）和碰撞盒随之刷新。
     */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        if (direction != leftOf(state) && direction != rightOf(state)) {
            return state;
        }
        return relink(state, world, pos);
    }

    /**
     * 把这一块（以及 {@code forceAuto} 时的整排）的自动档重算一遍，变化的写回世界。
     *
     * @param forceAuto {@code true} = 先把两半都打回自动档（「潜行 + 刷子」的整排重连，
     *                  对应方速 {@code triggerAutoSync(true, true)} + 左右邻居各一半）
     * @return 真正被改动的方块数
     */
    private static int refreshRun(final World world, final BlockPos pos, final boolean forceAuto) {
        final BlockState start = world.getBlockState(pos);
        if (!(start.getBlock() instanceof FangsuPsdGlassBlock)) {
            return 0;
        }
        final Direction facing = start.get(FACING);
        int changed = applyRelink(world, pos, forceAuto) ? 1 : 0;
        for (final Direction side : new Direction[] {leftOf(start), rightOf(start)}) {
            BlockPos at = pos;
            for (int i = 0; i < MAX_RUN; i++) {
                final BlockPos next = at.offset(side);
                final BlockState nextState = world.getBlockState(next);
                if (!(nextState.getBlock() instanceof FangsuPsdGlassBlock)
                        || !nextState.contains(FACING) || nextState.get(FACING) != facing) {
                    break;
                }
                if (applyRelink(world, next, forceAuto)) {
                    changed++;
                }
                at = next;
            }
        }
        return changed;
    }

    private static boolean applyRelink(final World world, final BlockPos pos, final boolean forceAuto) {
        final BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof FangsuPsdGlassBlock glass)) {
            return false;
        }
        final BlockState base = forceAuto ? state.with(AUTO_LEFT, true).with(AUTO_RIGHT, true) : state;
        final BlockState relinked = glass.relink(base, world, pos);
        if (relinked == state) {
            return false;
        }
        world.setBlockState(pos, relinked, Block.NOTIFY_ALL);
        return true;
    }

    // ------------------------------------------------------------------ 放置

    /**
     * 板子的朝向：<b>和 MTR 原版屏蔽门同一套</b> —— {@code FACING = 玩家看的方向}，几何贴在那条边上
     * （门扇方块 {@link FangsuPsdDoorBlock#getPlacementState} 有完整的 javap 依据）。
     *
     * <p>放置时两半都是自动档，并且<b>当场按邻居算一次</b> —— 摆到门扇旁边的那一块直接就是
     * {@code door / glass}，不用先刷一下。
     *
     * <p>不需要支撑：玻璃板是独立件，方速那边也只要求「和门放在同一条线上」，没有贴墙约束。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final BlockState placed = getDefaultState()
                .with(FACING, FangsuPsdPlacement.facingOf(ctx))
                .with(AUTO_LEFT, true)
                .with(AUTO_RIGHT, true);
        return relink(placed, ctx.getWorld(), ctx.getBlockPos());
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, FORM_LEFT, FORM_RIGHT, AUTO_LEFT, AUTO_RIGHT);
    }

    // ------------------------------------------------------------------ 右键：刷子换形态（点哪半换哪半）

    /**
     * 刷子右键 = <b>换命中的那一半</b>：点左半换左半、点右半换右半，循环顺序就是方速那个下拉框
     * （{@code auto → door → doorend → glass → longend → auto}）。<b>潜行 + 刷子</b> = 整排退回自动档
     * 重连（= 方速 {@code whenUseWithBrush}）。别的物品一律不响应。
     *
     * <p>为什么按「点中哪半」而不是潜行区分左右：格内两半各 0.5 格、都看得见，点哪半换哪半最直接，
     * 也不依赖「潜行时客户端会不会吞掉方块交互」这种平台细节。
     *
     * <p>为什么是循环而不是像方速那样只能开界面选：我们没有配置界面，循环 + 动作栏提示
     * （显示「哪一半 / 现在是什么形态」，自动档显示成「自动」）。{@code auto} 就在循环里 ——
     * 转一圈回到自动档，和下拉框里选中 {@code auto} 是同一件事。
     */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (!FangsuPsdInteract.isBrush(player.getStackInHand(hand))) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (player.isSneaking()) {
            // 整排重连：这一串玻璃全部退回自动档，再让自动规则把它连起来。
            final int changed = refreshRun(world, pos, true);
            world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.4F, 1.4F);
            player.sendMessage(new TranslatableText("block.mtrlte.fangsu_psd_glass_auto.reconnect", changed),
                    true);
            return ActionResult.CONSUME;
        }
        final boolean leftHalf = hitLeftHalf(state, hit, pos);
        final BlockState next = cycleHalf(state, leftHalf);
        world.setBlockState(pos, next, Block.NOTIFY_ALL);
        // 顺手把这块（其余自动档的一半）和左右相邻玻璃重算一遍：刷一下整排跟着连。
        refreshRun(world, pos, false);
        world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.4F,
                1.0F + (leftHalf ? 0.0F : 0.1F));
        player.sendMessage(new TranslatableText(leftHalf ? "block.mtrlte.fangsu_psd_glass_half.left"
                        : "block.mtrlte.fangsu_psd_glass_half.right")
                .append(new LiteralText(": "))
                .append(new TranslatableText(FangsuPsdGlassDef.langKey(
                        leftHalf ? leftKeyOf(next) : rightKeyOf(next)))), true);
        return ActionResult.CONSUME;
    }

    /**
     * 命中点在方块的哪一半：把命中位置投影到「朝左」那条轴上，正的就是左半。
     *
     * <p>朝左 = {@code facing.rotateYCounterclockwise()}，和模型里 {@code glass_left_*} 那 0.5 格
     * 是同一侧（north 时都在西半）；渲染器和碰撞盒用同一套角度，所以「点哪半」和「看到哪半」一致。
     */
    private static boolean hitLeftHalf(final BlockState state, final BlockHitResult hit, final BlockPos pos) {
        final Direction left = leftOf(state);
        final Vec3d at = hit.getPos();
        final double along = (at.x - (pos.getX() + 0.5D)) * left.getOffsetX()
                + (at.z - (pos.getZ() + 0.5D)) * left.getOffsetZ();
        return along >= 0.0D;
    }

    /**
     * 某一半的下一个状态：自动档 → 第一种手动态（{@code door}）→ … → 最后一种 → 自动档。
     *
     * <p>回到自动档后由随后的 {@link #refreshRun} 立刻按规则算一次，动作栏直接显示「自动」，
     * 玩家不用再刷一遍才看到连接。
     */
    private BlockState cycleHalf(final BlockState state, final boolean leftHalf) {
        final int count = Math.min(MAX_FORMS,
                (leftHalf ? this.def.left() : this.def.right()).size());
        final IntProperty form = leftHalf ? FORM_LEFT : FORM_RIGHT;
        final BooleanProperty auto = leftHalf ? AUTO_LEFT : AUTO_RIGHT;
        if (count <= 0) {
            return state;
        }
        final boolean currentlyAuto = leftHalf ? isAutoLeft(state) : isAutoRight(state);
        if (currentlyAuto) {
            // 自动 → 第一种手动态
            return state.with(auto, false).with(form, 0);
        }
        final int current = leftHalf ? leftFormOf(state) : rightFormOf(state);
        if (current + 1 < count) {
            return state.with(auto, false).with(form, current + 1);
        }
        // 最后一种 → 回到自动档；具体形态由随后的 refreshRun 按规则重算。
        return state.with(auto, true);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new FangsuPsdGlassEntity(pos, state);
    }

    /**
     * 玻璃板的方块实体：<b>没有 NBT、没有状态</b>，唯一的作用是给
     * {@link FangsuPsdGlassRenderer} 一个可以挂 BER 的位置（几何在 {@code door.obj} 里，
     * 原版方块模型表达不了）。
     *
     * <p>{@code state} 允许为 {@code null}：{@code BlockItem} 在物品栏 / 手持渲染时会传 null，
     * 兜底成空气（PIDS 那边踩过一次 NPE）。
     */
    public static class FangsuPsdGlassEntity extends BlockEntityMapper {

        public FangsuPsdGlassEntity(final BlockPos pos, final BlockState state) {
            super(FangsuPsdBlocks.glassEntityType(), pos,
                    state == null ? Blocks.AIR.getDefaultState() : state);
        }

        @Override
        public void writeCompoundTag(final net.minecraft.nbt.NbtCompound nbt) {
            // 什么都不存：形态和自动档都在 blockstate 的 FORM_* / AUTO_* 里，拆掉再放回来不需要记住任何东西。
        }
    }
}
