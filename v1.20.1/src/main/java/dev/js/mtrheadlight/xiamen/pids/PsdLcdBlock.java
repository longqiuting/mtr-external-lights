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
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
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
 * 厦门地铁「屏蔽门顶板 LCD 挂板」：{@code mtrlte:psd_lcd_xiamen}。
 *
 * <h2>形态</h2>
 * <ul>
 *   <li><b>吊板（整块，{@code half_cut=false}）</b>：外形就是用户给的 Blockbench 模型（{@code mornal} 组）
 *       搬进我们的坐标系：6px 厚、整块高（y 0..16，从天花板吊下来）、底面一圈 1px 台阶 +
 *       两段 22.5° 倒角、正面挖一个 <b>0.2px 深的插屏凹槽</b>（窗口 y 1.5..8.5，
 *       见 {@code models/block/psd_lcd_xiamen.json} 与 {@code tools/psd-lcd-models.js}）；</li>
 *   <li><b>吊板（剪过，{@code half_cut=true}）</b>：<b>只剩上半格</b>——剪刀剪掉板子下半截，
 *       板子只占 y 7.5..16，屏幕口跟着上移到 y 9..16（见 {@code models/block/psd_lcd_xiamen_half.json}，
 *       就是用户 json 的 {@code half} 组）。注意这<b>不是</b>「沿宽度切一半」：宽度永远是整格；</li>
 *   <li><b>屏幕</b>：LCD 画面由 BER 画在凹槽底那张面上（离方块外面 10.184px，
 *       {@link PsdLcdLayout#WINDOW_DEPTH_BLOCKS}），碰撞箱是空的，框选就是那块板
 *       （{@link #outline} 两个档各一份）;</li>
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
     * 剪刀剪过没有：{@code false} = 整块吊板（y 0..16，屏幕窗口 y 1.5..8.5），
     * {@code true} = <b>只剩上半格</b>（板子 y 7.5..16，屏幕窗口 y 9..16）。
     *
     * <p>blockstate json 里的键就是 {@code half_cut=false|true}，剪后换模型
     * {@code mtrlte:block/psd_lcd_xiamen_half}。剪刀右键切换，不损工具（见 {@link #onUse}）。
     *
     * <p>以前这里是三档枚举 {@code half = none|left|right}（沿宽度切一半）—— 那是误解：
     * 用户要的是「剪完只剩上半格」，宽度永远整格。
     */
    public static final BooleanProperty HALF_CUT = BooleanProperty.of("half_cut");

    /** 吊板的外形尺寸（16 分之几），全部来自 {@link PsdLcdLayout}（对齐 MTR 的 {@code psd_top}）。 */
    private static final float PANEL_Z0 = PsdLcdLayout.PANEL_Z0_PX;
    private static final float PANEL_Z1 = PANEL_Z0 + PsdLcdLayout.BOARD_THICKNESS_PX;

    /** 剪后板子的下沿（16 分之格）= half 组 BottomStep 的 {@code from.y}：板子只占 7.5..16。 */
    public static final float CUT_BOTTOM_Y_PX = 7.5F;

    public PsdLcdBlock(final Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(HALF_CUT, false));
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF_CUT);
    }

    // ------------------------------------------------------------------ 形状

    /** 碰撞箱：空的（挂板不挡路）。 */
    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return VoxelShapes.empty();
    }

    /** 这一格剪过没有（方块状态里没有这个属性时按「整块」处理）。 */
    public static boolean isCut(final BlockState state) {
        return state != null && state.contains(HALF_CUT) && state.get(HALF_CUT);
    }

    /**
     * 轮廓：那块吊板。<b>整块 / 剪后各一份</b> —— 整块 = 后面那 6px、y 0..16；
     * 剪后 = 同一块 6px、y <b>7.5..16</b>（下半截没了，选区也不能还占着整格）。
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
        return outline(state.get(FACING), isCut(state));
    }

    /** {@link #getOutlineShape} 的实际算法：model 空间的箱子按 facing 转到世界。 */
    private static VoxelShape outline(final Direction facing, final boolean cut) {
        // 宽度永远是整格（0..16），剪只剪高度方向的下半截。
        final float y0 = cut ? CUT_BOTTOM_Y_PX : 0.0F;
        final float y1 = 16.0F;
        final float z0 = PANEL_Z0;
        final float z1 = PANEL_Z1;
        return switch (facing == null ? Direction.NORTH : facing) {
            case EAST -> Block.createCuboidShape(16.0D - z1, y0, 0.0D, 16.0D - z0, y1, 16.0D);
            case SOUTH -> Block.createCuboidShape(0.0D, y0, 16.0D - z1, 16.0D, y1, 16.0D - z0);
            case WEST -> Block.createCuboidShape(z0, y0, 0.0D, z1, y1, 16.0D);
            default -> Block.createCuboidShape(0.0D, y0, z0, 16.0D, y1, z1);
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
     * <h2>剪刀 = 剪掉下半截（只剩上半格）</h2>
     * <p>{@link Items#SHEARS} 右键切换 {@link #HALF_CUT}：{@code false → true → false}
     * （「剪 / 复原」，<b>不</b>和刷子抢：刷子先判，命中就开界面）。
     * <ul>
     *   <li><b>只服务端改状态</b>（{@code world.setBlockState}），客户端只回 {@code SUCCESS}
     *       让手有挥动动作 —— 客户端自己改会被下一次同步覆盖，还可能两下各改一次；</li>
     *   <li><b>不消耗工具</b>：只有 {@code ItemStack#damage} 才会掉耐久，这里一个字都不写，
     *       所以剪刀在生存里剪多少次都一样；</li>
     *   <li>播 {@code ENTITY_SHEEP_SHEAR}（剪刀的通用音效），音高 1.2 听上去更像「剪金属」；</li>
     *   <li>整条链的几何<b>每帧现算</b>（渲染器 + {@link PsdLcdLayout#windowBandPx(boolean)}），
     *       所以改一格立刻重排，不需要同步任何方块实体数据。</li>
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
            final boolean cut = !isCut(state);
            world.setBlockState(pos, state.with(HALF_CUT, cut), Block.NOTIFY_ALL);
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
