// 方速「厦门屏蔽门」的玻璃板 —— 静物：不参与开合，只补上原来漏搬的那半套几何。
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
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * 方速厦门屏蔽门的<b>玻璃板</b>（{@code fangsu_psd_screendoor_high_glass_left / _right}）。
 *
 * <h2>为什么单独一个方块类</h2>
 * <p>方速的滑动屏蔽门在 {@code screendoor/xiamen/high.json} 里其实有两套东西：
 * {@code door.left/right}（会滑动的门扇，已经搬了，见 {@link FangsuPsdDoorBlock}）和
 * {@code glass.left/right}（<b>不动的玻璃板 / 端板</b>）。后者当初没搬 —— 结果门扇一开，
 * 后面本该有一块固定玻璃挡着的地方是空的，玩家得拿别的方块硬凑。
 * 这一版把那半套几何补上：{@code glass} 那一条（{@code o glass_left_glass} /
 * {@code o glass_right_glass} 两个组）各注册成一个方块。
 *
 * <h2>和门扇方块的区别（故意不一样）</h2>
 * <ul>
 *   <li><b>不继承 {@code BlockPSDAPGDoorBase}</b>：玻璃板不会开、也不该被列车当成门扇。
 *       继承的话 MTR 的 {@code TrainServer#openDoors} 会把它当门，还会在「开门」时把碰撞设成空 ——
 *       那就成了「列车一停，玻璃就没了，人可以直接走出去」。所以这里是普通方块 +
 *       一个空壳方块实体（只为挂 BER）；</li>
 *   <li><b>碰撞常开</b>：几何是 3 格高的板材（{@code y 0..48}），碰撞盒照素材的
 *       {@code shape} 原值，<b>竖直方向不裁</b>（同一格里给出 3 格高的碰撞，和看到的板一样高），
 *       只把 x / z 裁到本格 {@code 0..16}（伸到邻居格的部分不归这块管）；</li>
 *   <li><b>四向</b>：{@code facing} 和门扇同一套（{@code north=0 / east=90 / south=180 / west=270}），
 *       放置时取玩家朝向的反面 —— 板子的正面朝你。形状按四向<b>注册期预烘</b>
 *       （见 {@link #buildShape}），渲染器用的是同一套角度，永远不会「模型转了碰撞没转」。</li>
 * </ul>
 *
 * <h2>低款没有玻璃</h2>
 * <p>{@code screendoor/xiamen/low.json} 的 {@code door_low.obj} 里只有
 * {@code o left} / {@code o right} 两个门扇组、<b>没有玻璃组</b>（低款本来就是半高的，
 * 边上没有固定玻璃），所以只有 high 那款出玻璃方块。
 */
public class FangsuPsdGlassBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** 水平四向的 4 份形状（索引 = {@code facing} 的 90° 倍数 / 90）。 */
    private static final int FACINGS = 4;

    private final FangsuPsdDef def;

    /** 四向的碰撞 / 轮廓盒（同一份，玻璃板不需要「开门后能穿过去」）。 */
    private final VoxelShape[] shapes = new VoxelShape[FACINGS];

    private Item item;

    public FangsuPsdGlassBlock(final FangsuPsdDef def) {
        // 玻璃：半透明感由渲染器的 cutout 层给（贴图自己带 alpha），方块本身不发光。
        super(AbstractBlock.Settings.create()
                .mapColor(MapColor.CLEAR)
                .strength(1.0F)
                .sounds(BlockSoundGroup.GLASS)
                .nonOpaque());
        this.def = def;
        // 四向的形状：索引和 {@link FangsuPsdDoorBlock#shapeQuarterTurns} 是同一张表
        // （方速的 shape 比模型多要 180°，玻璃板同样如此 —— 左右两块本来就是偏在一侧的，
        // 少了这 180° 会变成「模型在左、碰撞在右」）。
        for (int rotation = 0; rotation < FACINGS; rotation++) {
            this.shapes[rotation] = buildShape(def.closeBoxes(), rotation);
        }
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    /** 这个方块对应的描述（渲染器 / 网格缓存都从这里取）。 */
    public FangsuPsdDef def() {
        return this.def;
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
     * 把描述里的盒子（1/16 格，1/16 = 1 px）烘成某个朝向的 {@link VoxelShape}。
     *
     * <p>两件事：<b>按 90° 转 x/z</b>（每一步 {@code (x, z) → (16 − z, x)}，与
     * {@link FangsuPsdDoorBlock} / {@code EyecandyBlock} 同一套约定，也和渲染器里
     * {@code NEGATIVE_Y} 那个矩阵是同一个方向）；<b>把 x / z 裁到本格</b>。
     * <b>y 不裁</b>：素材里玻璃板是 {@code y 0..48}，一块方块给出 3 格高的碰撞
     * （轮廓也一起高，选中的框和板一样高）。
     *
     * @param quarters 转几个 90°（不是角度 —— 取值见 {@link #shapeQuarterTurns}）
     */
    private static VoxelShape buildShape(final List<double[]> boxes, final int quarters) {
        final List<VoxelShape> parts = new ArrayList<>(boxes.size());
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
        return parts.isEmpty() ? VoxelShapes.empty()
                : VoxelShapes.union(VoxelShapes.empty(), parts.toArray(new VoxelShape[0]));
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
        return this.shapes[shapeQuarterTurns(state)];
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
     * 板子的正面朝<b>放置的玩家</b>（和门扇方块 {@code getHorizontalPlayerFacing().getOpposite()}
     * 同一个约定）。
     *
     * <p>不需要支撑：玻璃板是独立件，方速那边也只要求「和门放在同一条线上」，没有贴墙约束。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
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
            // 什么都不存：拆掉再放回来不需要记住任何东西。
        }
    }
}
