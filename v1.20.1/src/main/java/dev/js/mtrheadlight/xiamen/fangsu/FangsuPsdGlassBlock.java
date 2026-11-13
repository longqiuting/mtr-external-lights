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
import net.minecraft.state.property.IntProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 方速厦门屏蔽门的<b>玻璃板</b>（{@code fangsu_psd_screendoor_high_glass}）——
 * <b>一个方块 = 一整个格</b>，格内左右各半个格拼起来。
 *
 * <h2>一格，不是两格</h2>
 * <p>方速文档 {@code screendoor_glass} 写的是「<b>一个</b>玻璃方块是由左右两部分拼接而成的」，
 * 反编译 {@code BlockEntityScreendoorGlass} 也是一个方块实体同时持有
 * {@code subModelLeft} + {@code subModelRight}（{@code whenRendering} 里两遍 {@code drawModel}）
 * 和 {@code shapeLeft} + {@code shapeRight}（{@code reloadModelAndShape} 里
 * {@code addAll} 合成一个 {@code glassShape}，{@code getFinalShape()} 只返回它）。
 * 几何也是两半各 0.5 格：{@code glass_left_glass} x ∈ [-0.5, 0]，{@code glass_right_glass}
 * x ∈ [0, 0.5]，接缝在方块中心。
 *
 * <p>所以这里<b>不再</b>有 left / right 两个方块（那是上一版的错误做法）：一块方块同时画
 * {@code glass_left_*} 与 {@code glass_right_*} 两组，碰撞盒是两半 {@code shape} 的<b>并集</b>
 * （{@link #buildShape}）。完整依据见 {@link FangsuPsdGlassDef} 的类注释。
 *
 * <h2>形态：两半各一个下标</h2>
 * <p>{@code glass.left[]} / {@code glass.right[]} 是<b>两份独立的形态列表</b>（方速的
 * {@code getSubModelInfos()} 也是两个下拉框），所以状态里有两个属性：
 * {@link #FORM_LEFT} 与 {@link #FORM_RIGHT}，各自 0..3
 * （{@code door} / {@code doorend} / {@code glass} / {@code longend}）。
 * 「左边紧挨门扇、右边纯玻璃」这种真实摆法就靠它。
 *
 * <p>操作：<b>MTR 刷子右键</b>循环左半形态，<b>潜行 + 刷子右键</b>循环右半形态（动作栏给出当前形态）。
 * 别的物品右键完全不响应。形态换了碰撞盒跟着换 —— 两件事都从同一个 {@code shape} 数组来，
 * 不会「看得见摸不着」。
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
     */
    public static final IntProperty FORM_LEFT = IntProperty.of("form_left", 0, 3);

    /** <b>右半</b>（格内 x 0..8 那半）的当前形态，下标进 {@link FangsuPsdGlassDef#right()}。 */
    public static final IntProperty FORM_RIGHT = IntProperty.of("form_right", 0, 3);

    /** {@link #FORM_LEFT} / {@link #FORM_RIGHT} 的上限 + 1。 */
    private static final int MAX_FORMS = 4;

    /** 水平四向的 4 份形状（索引 = {@code facing} 的 90° 倍数 / 90）。 */
    private static final int FACINGS = 4;

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
        super(AbstractBlock.Settings.create()
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
                .with(FORM_RIGHT, Math.min(MAX_FORMS - 1, def.defaultRight())));
    }

    /** 这个方块对应的描述（渲染器 / 网格缓存都从这里取）。 */
    public FangsuPsdGlassDef def() {
        return this.def;
    }

    /** 左半现在是第几种形态（越界夹到合法范围）。 */
    public int leftFormOf(final BlockState state) {
        return clampForm(state.contains(FORM_LEFT) ? state.get(FORM_LEFT) : this.def.defaultLeft(),
                this.def.left().size());
    }

    /** 右半现在是第几种形态。 */
    public int rightFormOf(final BlockState state) {
        return clampForm(state.contains(FORM_RIGHT) ? state.get(FORM_RIGHT) : this.def.defaultRight(),
                this.def.right().size());
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

    // ------------------------------------------------------------------ 放置

    /**
     * 板子的朝向：<b>和 MTR 原版屏蔽门同一套</b> —— {@code FACING = 玩家看的方向}，几何贴在那条边上
     * （门扇方块 {@link FangsuPsdDoorBlock#getPlacementState} 有完整的 javap 依据）。
     *
     * <p>原来这里写 {@code getOpposite()}（正面朝玩家），效果就是整块板子翻到玩家这一侧的内侧，
     * 和门扇一起偏了 180°。现在两者都跟 MTR 一样「贴玩家对面那条边」。
     *
     * <p>不需要支撑：玻璃板是独立件，方速那边也只要求「和门放在同一条线上」，没有贴墙约束。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, FangsuPsdPlacement.facingOf(ctx));
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, FORM_LEFT, FORM_RIGHT);
    }

    // ------------------------------------------------------------------ 右键：刷子循环（左半 / 右半）

    /**
     * MTR 刷子右键 = 换<b>左半</b>的下一种形态（循环）；<b>潜行</b> + 刷子 = 换<b>右半</b>。
     * 别的物品一律不响应。
     *
     * <p>为什么不是「整格一起换」：方速的两半是两份独立的形态列表（{@code glass.left[]} /
     * {@code glass.right[]}，界面里也是两个下拉框），实际摆放时经常只有靠门那一半是
     * {@code door} / {@code doorend}，另一半是纯玻璃。整格一起换就做不出这种。
     *
     * <p>为什么是循环而不是方速那套「按邻居自动选形态」：我们没有配置界面，智能选型反而让玩家
     * 猜不到下一次会变成什么；循环 + 动作栏提示最直接（和 eyecandy 以前那套刷子四向旋转同一个手势）。
     */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (!FangsuPsdInteract.isBrush(player.getStackInHand(hand))) {
            return ActionResult.PASS;
        }
        final boolean rightHalf = player.isSneaking();
        final List<FangsuPsdGlassDef.Form> forms = rightHalf ? this.def.right() : this.def.left();
        final int count = Math.min(MAX_FORMS, forms.size());
        if (count <= 1) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        final int current = rightHalf ? rightFormOf(state) : leftFormOf(state);
        final int next = (current + 1) % count;
        world.setBlockState(pos, state.with(rightHalf ? FORM_RIGHT : FORM_LEFT, next), Block.NOTIFY_ALL);
        world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.4F,
                1.0F + next * 0.1F);
        // 动作栏提示「哪一半 + 现在是什么形态」：刷子是循环的，不给反馈玩家不知道转到第几个了。
        player.sendMessage(Text.translatable(rightHalf ? "block.mtrlte.fangsu_psd_glass_half.right"
                        : "block.mtrlte.fangsu_psd_glass_half.left")
                .append(Text.literal(": "))
                .append(Text.translatable(FangsuPsdGlassDef.langKey(forms.get(next).key()))), true);
        return ActionResult.CONSUME;
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
            // 什么都不存：形态在 blockstate 的 FORM_LEFT / FORM_RIGHT 里，拆掉再放回来不需要记住任何东西。
        }
    }
}
