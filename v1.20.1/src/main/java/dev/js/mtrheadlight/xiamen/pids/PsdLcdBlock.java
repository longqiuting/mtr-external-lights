// PSD-LCD 吊板方块：6px 厚、整块高的吊板（外形同 MTR psd_top），四向、任意位置可放；
// 横向连接靠「相邻同类方块」现算，不存状态。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.List;

import mtr.block.IBlock;
import mtr.mappings.BlockEntityMapper;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 厦门地铁「屏蔽门顶板 LCD 挂板」：{@code mtrlte:psd_lcd_xiamen}。
 *
 * <h2>形态</h2>
 * <ul>
 *   <li><b>吊板</b>：外形和 MTR 的普通吊板（{@code mtr:block/psd_top_left_base}）是同一套——
 *       6px 厚、整块高（y 0..16，从天花板吊下来）、底面一圈 1px 台阶 + 22.5° 倒角；我们把它拉满整格宽，
 *       并在前面挖一个 <b>1.6px 深的插屏凹槽</b>（y 1.5..8.5，见 {@code models/block/psd_lcd_xiamen.json}）；</li>
 *   <li><b>屏幕</b>：LCD 画面由 BER 画在凹槽底那张面上（离方块外面 1.584px，
 *       {@link PsdLcdLayout#WINDOW_DEPTH_BLOCKS}），碰撞箱是空的，框选就是那块板
 *       （{@link #OUTLINE_NORTH} 等四个方向各一份）;</li>
 *   <li><b>四向</b>：{@code FACING} = 屏幕的<b>法线</b>，放置时固定取「玩家朝向的反方向」，
 *       也就是<b>永远面朝放置的玩家</b>（见 {@link #getPlacementState}）；</li>
 *   <li><b>任意位置可放</b>：{@link #canPlaceAt} 恒真，拆掉支撑方块也不会自己掉；</li>
 *   <li><b>横向连接</b>：相邻同类方块沿 {@code FACING.rotateYClockwise()} 连成一整条宽屏，
 *       长度<b>每帧现算</b>（{@link #anchorOf} / {@link #lengthOf}），方块状态里没有任何
 *       link 位掩码 —— 和 MTR 的 {@code RenderStationNameTiled#getLength} 一个套路：<b>不存状态</b>。</li>
 * </ul>
 *
 * <h2>为什么是「一轮走到底」而不是 MTR PIDS 那种「两半」</h2>
 * <p>{@code BlockPIDSBaseHorizontal} 放下一半会<b>自动再放另一半</b>，一对两块、facing 互为反向；
 * 这里要的是「1~N 格随便接」，所以不走父类：每格都是独立方块，渲染器从
 * {@link #anchorOf anchor} 出发一次画出整条。
 *
 * <h2>谁存数据</h2>
 * <p>只有 anchor（链条里 {@code FACING.rotateYClockwise()} 方向走到头的那一格 = 视觉上的最左一格）
 * 这一格有真正的配置，刷子右键也只打开它的界面（{@link PidsXiamenNet#openScreenAt}）。
 * 其它格的方块实体是空的，渲染器也不画。
 */
public class PsdLcdBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** 方块 id（不带命名空间）。 */
    public static final String ID = "psd_lcd_xiamen";

    /** 一条链最多连多少格（防止某个方向绕地球一圈时死循环）。 */
    public static final int MAX_SEGMENTS = 32;

    /** 屏幕朝向。与 MTR / 本模组其它方块同一个属性。 */
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    /**
     * 面板沿「铺开轴」占多少：{@link Half#NONE} = 整格（16px），{@link Half#LEFT} / {@link Half#RIGHT} = 半格（8px）。
     *
     * <p><b>left / right 是观察者（站在屏幕正面看）的左右</b>，不是 model 空间的 ±x：
     * 基础朝向（facing=north）时观察者的右手边是 model 的 −x，所以
     * <ul>
     *   <li>{@code half=left} → model x <b>8…16</b>（模型文件 {@code psd_lcd_xiamen_half_left.json}）；</li>
     *   <li>{@code half=right} → model x <b>0…8</b>（{@code psd_lcd_xiamen_half_right.json}）。</li>
     * </ul>
     * 剪刀的切换顺序是 {@code none → left → right → none}（见 {@link #onUse}）：
     * 连点三下回到整格；中间两档可以拿来和相邻那一格拼成一段
     * （本格 right + 下一格 left 正好在格子边界对齐，见 {@link #panelSpan}）。
     */
    public static final EnumProperty<Half> HALF = EnumProperty.of("half", Half.class);

    /** {@link #HALF} 的三个值。{@code id} 就是 blockstate json 里的字符串。 */
    public enum Half implements StringIdentifiable {
        /** 整格。 */
        NONE("none"),
        /** 观察者左半（model x 8…16）。 */
        LEFT("left"),
        /** 观察者右半（model x 0…8）。 */
        RIGHT("right");

        private final String id;

        Half(final String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return this.id;
        }

        /** 剪刀切一下之后的那一档：none → left → right → none。 */
        public Half next() {
            return switch (this) {
                case NONE -> LEFT;
                case LEFT -> RIGHT;
                case RIGHT -> NONE;
            };
        }
    }

    /** 吊板的外形尺寸（16 分之几），全部来自 {@link PsdLcdLayout}（对齐 MTR 的 {@code psd_top}）。 */
    private static final float PANEL_Z0 = PsdLcdLayout.PANEL_Z0_PX;
    private static final float PANEL_Z1 = PANEL_Z0 + PsdLcdLayout.BOARD_THICKNESS_PX;

    /** 半格的宽度：8/16 格。 */
    public static final float HALF_WIDTH_PX = 8.0F;

    public PsdLcdBlock(final Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(HALF, Half.NONE));
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    // ------------------------------------------------------------------ 形状

    /** 碰撞箱：空的（挂板不挡路）。 */
    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return VoxelShapes.empty();
    }

    /**
     * 轮廓：那块吊板。<b>整格 / 半格各一份</b> —— 半格时只框住留下的那 8px，
     * 否则剪完以后选区还是一个整格，看着像没剪干净。
     *
     * <p>形状在 <b>model 空间</b>写（基础朝向 = 朝北，屏幕面在 z=0 那侧），再按 {@code FACING}
     * 转到世界坐标。四个映射与 blockstate 的 y 旋转、{@link PsdLcdLayout#windowQuad} 同源：
     * <pre>
     *   north (0°)    (x,z) → (  x  ,   z   )
     *   east  (90°)   (x,z) → (16-z ,   x   )
     *   south (180°)  (x,z) → (16-x , 16-z  )
     *   west  (270°)  (x,z) → (  z  , 16-x  )
     * </pre>
     */
    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        return outline(state.get(FACING), state.get(HALF));
    }

    /** 面板在 model 空间占的 x 区间（16 分之格）：left = 8…16，right = 0…8，none = 0…16。 */
    private static float[] modelXSpan(final Half half) {
        return switch (half) {
            case LEFT -> new float[]{HALF_WIDTH_PX, 16.0F};
            case RIGHT -> new float[]{0.0F, HALF_WIDTH_PX};
            default -> new float[]{0.0F, 16.0F};
        };
    }

    /** {@link #getOutlineShape} 的实际算法：model 空间的箱子按 facing 转到世界。 */
    private static VoxelShape outline(final Direction facing, final Half half) {
        final float[] span = modelXSpan(half == null ? Half.NONE : half);
        final float x0 = span[0];
        final float x1 = span[1];
        final float z0 = PANEL_Z0;
        final float z1 = PANEL_Z1;
        return switch (facing == null ? Direction.NORTH : facing) {
            case EAST -> Block.createCuboidShape(16.0D - z1, 0.0D, x0, 16.0D - z0, 16.0D, x1);
            case SOUTH -> Block.createCuboidShape(16.0D - x1, 0.0D, 16.0D - z1,
                    16.0D - x0, 16.0D, 16.0D - z0);
            case WEST -> Block.createCuboidShape(z0, 0.0D, 16.0D - x1, z1, 16.0D, 16.0D - x0);
            default -> Block.createCuboidShape(x0, 0.0D, z0, x1, 16.0D, z1);
        };
    }

    /**
     * 一格面板在「阅读轴」上占的区间（16 分之格）：{@code {起点, 终点}}，
     * 从这一格<b>观察者左边缘</b>往阅读方向量。
     *
     * <p>用来拼链条：第 i 格的区间是 {@code [16i + start, 16i + end]}。整格 = [0,16]，
     * 左半 = [0,8]（贴着本格左边缘），右半 = [8,16]（贴着右边的格子边界）——
     * 所以「本格 right + 下一格 left」正好接在边界上，拼回一整段，缝为 0
     * （最多受浮点影响一个像素以内）。
     */
    public static float[] panelSpan(final Half half) {
        return switch (half == null ? Half.NONE : half) {
            case LEFT -> new float[]{0.0F, HALF_WIDTH_PX};
            case RIGHT -> new float[]{HALF_WIDTH_PX, 16.0F};
            default -> new float[]{0.0F, 16.0F};
        };
    }

    /**
     * 不遮挡邻居：模型只是一块 6px 厚的吊板，如果把剔除形状设成满格，邻居朝着我们的那一面会被剔除，
     * 缝隙里就能看穿邻居（和 {@link PidsXiamenBlock#getCullingShape} 同一个原因）。
     */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    /** 任意位置可放（不要求背后有实心方块，也不会因为支撑消失而自己掉）。 */
    @Override
    public boolean canPlaceAt(final BlockState state, final net.minecraft.world.WorldView world,
                              final BlockPos pos) {
        return true;
    }

    /**
     * 放置朝向：<b>永远面朝放置的玩家</b>。
     *
     * <p>{@code FACING} 是屏幕的<b>法线</b>（= 屏幕朝外那一面）。玩家看向北放置时，
     * 他的朝向是 NORTH，方块的 facing 就要是 SOUTH —— 屏幕才朝着他。所以取反：
     * {@code ctx.getHorizontalPlayerFacing().getOpposite()}。
     *
     * <p>以前这里按「点哪一面就朝哪一面」（{@code ctx.getSide()}）算，点在顶上 / 地上时又退回
     * 玩家朝向的反方向 —— 结果是「站在屏幕正面放置，屏幕却背对着你」。吊板是挂在天花板下面看的，
     * 不需要贴墙，所以统一按「面朝玩家」处理，四个朝向都由 {@link PsdLcdLayout.Face} 保证
     * 模型、blockstate 的 {@code y} 旋转、BER 四边形三者一致（见 {@code tools/psd-lcd-facing-check.js}）。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
        return this.getDefaultState().with(FACING, facing);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public BlockEntityMapper createBlockEntity(final BlockPos pos, final BlockState state) {
        return new PsdLcdBlockEntity(pos, state);
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 刷子右键 = 打开现成的「厦门 PIDS」配置界面（{@link PidsXiamenConfigScreen}）：
     * 站台筛选、显示页码、广告分类、广告起点都在那一屏，不需要为挂板再写一个。
     *
     * <p>服务端发起（客户端读的是可能过期的副本），见 {@link PidsXiamenNet#openScreenAt}。
     * 非刷子仍然交回给父类（父类返回 PASS，什么都不会发生）。
     *
     * <h2>剪刀 = 把面板剪成半格</h2>
     * <p>{@link Items#SHEARS} 右键按 {@link Half#next()} 循环
     * {@code 整格 → 左半 → 右半 → 整格}（<b>不</b>和刷子抢：刷子先判，命中就开界面）。
     * <ul>
     *   <li><b>只服务端改状态</b>（{@code world.setBlockState}），客户端只回 {@code SUCCESS}
     *       让手有挥动动作 —— 客户端自己改会被下一次同步覆盖，还可能两下各改一次；</li>
     *   <li><b>不消耗工具</b>：只有 {@code ItemStack#damage} 才会掉耐久，这里一个字都不写，
     *       所以剪刀在生存里剪多少次都一样；</li>
     *   <li>播 {@code ENTITY_SHEEP_SHEAR}（剪刀的通用音效），音高 1.2 听上去更像「剪金属」；</li>
     *   <li>整条链的几何<b>每帧现算</b>（{@link #panelSpan} + 渲染器），所以改一格立刻重排，
     *       不需要同步任何方块实体数据。</li>
     * </ul>
     */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (player.isHolding(mtr.Items.BRUSH.get())) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }
            if (player instanceof ServerPlayerEntity serverPlayer
                    && ServerPlayNetworking.canSend(serverPlayer, PidsXiamenNet.ID)) {
                PidsXiamenNet.openScreenAt(serverPlayer, world, anchorOf(world, pos),
                        partnerOf(world, anchorOf(world, pos)));
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }
        if (player.isHolding(Items.SHEARS)) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }
            final Half next = state.get(HALF).next();
            world.setBlockState(pos, state.with(HALF, next), Block.NOTIFY_ALL);
            world.playSound(null, pos, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.BLOCKS,
                    1.0F, 1.2F);
            // 故意不对玩家手上那把剪刀做任何事：没有 damage() 就没有耐久消耗。
            return ActionResult.SUCCESS;
        }
        return super.onUse(state, world, pos, player, hand, hit);
    }

    // ------------------------------------------------------------------ 横向连接

    /** 这一格是不是我们的挂板。 */
    public static boolean isLcd(final BlockState state) {
        return state.getBlock() instanceof PsdLcdBlock;
    }

    /**
     * 链条方向：{@code FACING.rotateYClockwise()}。
     *
     * <p>从观察者那边看，这个方向是<b>左</b>；所以「沿它走到头」拿到的是链条最左一格，
     * 从那一格往反方向（{@code rotateYCounterclockwise}）走就是阅读方向。
     */
    public static Direction stepOf(final Direction facing) {
        return facing.rotateYClockwise();
    }

    /** 任意一格 → 链条的第一格（最左 / 存数据 / 负责画的那一格）。 */
    public static BlockPos anchorOf(final BlockView world, final BlockPos pos) {
        final Direction facing = IBlock.getStatePropertySafe(world, pos, FACING);
        if (facing == null) {
            return pos;
        }
        final Direction step = stepOf(facing);
        BlockPos cursor = pos;
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            final BlockPos next = cursor.offset(step);
            if (!isLcd(world.getBlockState(next))) {
                return cursor;
            }
            cursor = next;
        }
        return cursor;
    }

    /** 链条一共几格（单块 = 1）。 */
    public static int lengthOf(final BlockView world, final BlockPos anchor) {
        final Direction facing = IBlock.getStatePropertySafe(world, anchor, FACING);
        if (facing == null) {
            return 1;
        }
        final Direction back = stepOf(facing).getOpposite();
        int length = 1;
        BlockPos cursor = anchor;
        while (length < MAX_SEGMENTS) {
            final BlockPos next = cursor.offset(back);
            if (!isLcd(world.getBlockState(next))) {
                return length;
            }
            cursor = next;
            length++;
        }
        return length;
    }

    /** 这一格在链条里的下标（anchor = 0），调试用。 */
    public static int indexOf(final BlockView world, final BlockPos pos) {
        final BlockPos anchor = anchorOf(world, pos);
        final Direction facing = IBlock.getStatePropertySafe(world, pos, FACING);
        if (facing == null) {
            return 0;
        }
        final Direction back = stepOf(facing).getOpposite();
        BlockPos cursor = anchor;
        int index = 0;
        while (index < MAX_SEGMENTS && !cursor.equals(pos)) {
            cursor = cursor.offset(back);
            index++;
        }
        return index;
    }

    /** 链条里除 anchor 之外的任意一格（给 MTR 的 PIDS 配置包当 partner；单块时就是它自己）。 */
    public static BlockPos partnerOf(final BlockView world, final BlockPos anchor) {
        final Direction facing = IBlock.getStatePropertySafe(world, anchor, FACING);
        if (facing == null) {
            return anchor;
        }
        final BlockPos next = anchor.offset(stepOf(facing).getOpposite());
        return isLcd(world.getBlockState(next)) ? next : anchor;
    }

    /** 链条里每一格（anchor 在前）。 */
    public static List<BlockPos> run(final BlockView world, final BlockPos anchor) {
        final Direction facing = IBlock.getStatePropertySafe(world, anchor, FACING);
        if (facing == null) {
            return List.of(anchor);
        }
        final Direction back = stepOf(facing).getOpposite();
        final int length = lengthOf(world, anchor);
        final BlockPos[] out = new BlockPos[length];
        for (int i = 0; i < length; i++) {
            out[i] = anchor.offset(back, i);
        }
        return List.of(out);
    }
}
