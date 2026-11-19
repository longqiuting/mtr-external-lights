// PSD-LCD 挂板方块：薄板、四向、任意位置可放；横向连接靠「相邻同类方块」现算，不存状态。
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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
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
 *   <li><b>薄板</b>：模型只占方块中间那一条（y 5.5~10.5 / 16），厚度 1px，贴在
 *       {@code FACING} 那一面上；碰撞箱是空的，轮廓就是那块板
 *       （{@link #OUTLINE_NORTH} 等四个方向各一份）；</li>
 *   <li><b>四向</b>：{@code FACING} = 屏幕朝外的一面（贴墙放时点哪面就朝哪面）；</li>
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

    /** 板子的上下沿（16 分之几），和 {@link PsdLcdLayout#HEIGHT_BLOCKS} 对得上。 */
    private static final float PANEL_Y0 = 8.0F - PsdLcdLayout.HEIGHT_BLOCKS * 16.0F * 0.5F;
    private static final float PANEL_Y1 = 8.0F + PsdLcdLayout.HEIGHT_BLOCKS * 16.0F * 0.5F;

    /** 四个方向的轮廓：一块 1px 厚的板，贴着朝外那一面。 */
    private static final VoxelShape OUTLINE_NORTH =
            Block.createCuboidShape(0.0D, PANEL_Y0, 0.0D, 16.0D, PANEL_Y1, 1.0D);
    private static final VoxelShape OUTLINE_SOUTH =
            Block.createCuboidShape(0.0D, PANEL_Y0, 15.0D, 16.0D, PANEL_Y1, 16.0D);
    private static final VoxelShape OUTLINE_WEST =
            Block.createCuboidShape(0.0D, PANEL_Y0, 0.0D, 1.0D, PANEL_Y1, 16.0D);
    private static final VoxelShape OUTLINE_EAST =
            Block.createCuboidShape(15.0D, PANEL_Y0, 0.0D, 16.0D, PANEL_Y1, 16.0D);

    public PsdLcdBlock(final Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // ------------------------------------------------------------------ 形状

    /** 碰撞箱：空的（挂板不挡路）。 */
    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return VoxelShapes.empty();
    }

    /** 轮廓：那块薄板。 */
    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        return switch (state.get(FACING)) {
            case SOUTH -> OUTLINE_SOUTH;
            case WEST -> OUTLINE_WEST;
            case EAST -> OUTLINE_EAST;
            default -> OUTLINE_NORTH;
        };
    }

    /**
     * 不遮挡邻居：模型只是一块薄板，如果把剔除形状设成满格，邻居朝着我们的那一面会被剔除，
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

    /** 放置：点墙面 → 朝那一面；点在顶上 / 地上 → 朝玩家。 */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final Direction side = ctx.getSide();
        final Direction facing = side.getAxis().isHorizontal()
                ? side : ctx.getHorizontalPlayerFacing().getOpposite();
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
     */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (!player.isHolding(mtr.Items.BRUSH.get())) {
            return super.onUse(state, world, pos, player, hand, hit);
        }
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
