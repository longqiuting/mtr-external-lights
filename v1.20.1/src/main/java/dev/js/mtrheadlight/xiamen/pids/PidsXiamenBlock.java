// 厦门 PIDS 方块：地铁 / BRT 两块共用一个类，只是屏幕贴图与默认线路不同。
package dev.js.mtrheadlight.xiamen.pids;

import mtr.block.BlockPIDSBaseHorizontal;
import mtr.block.IBlock;
import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Identifier;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 厦门 PIDS（横向、两块宽）：{@code mtrlte:pids_xiamen_metro} 与 {@code mtrlte:pids_xiamen_brt}。
 *
 * <h2>为什么继承 MTR 的 {@code BlockPIDSBaseHorizontal}</h2>
 * <p>它一手包办了三件事，抄一遍没意义：
 * <ol>
 *   <li>{@code getPlacementState} —— 只在玩家面前那一格放得下（{@code IBlock.isReplaceable(..., 2)}）；</li>
 *   <li>{@code onPlaced} —— 放下之后<b>自动在 facing 方向再放半块</b>（同一方块类、facing 反过来），
 *       于是 MTR 的横向 PIDS 天生是 <b>2 格宽</b>；</li>
 *   <li>{@code onUse} —— 拿刷子右键的交互（{@code IBlock.checkHoldingBrush}）。</li>
 * </ol>
 * 天津地铁也是同一手（{@code BlockPIDSTianjin extends BlockPIDSBaseHorizontal}）。
 *
 * <h2>两块宽，但只有一半画东西</h2>
 * <p>一对儿方块的 facing 一定是「SOUTH/WEST 之一」+「NORTH/EAST 之一」。天津的做法是：
 * facing 是 <b>SOUTH 或 WEST</b> 的那一半才画（{@link #canStoreData}），它一帧画出横跨
 * 两格的整块屏（1.96 格宽），另一半什么都不画。这里照抄这套判定 —— 否则一整块屏会被画两遍，
 * 而且是镜像的。
 */
public class PidsXiamenBlock extends BlockPIDSBaseHorizontal {

    /** 地铁 / BRT。差别只有屏幕底图与默认线路。 */
    public enum Kind {

        METRO("pids_xiamen_metro", "block/pids_xiamen_metro"),
        BRT("pids_xiamen_brt", "block/pids_xiamen_brt");

        private final String id;
        private final String texturePath;

        Kind(final String id, final String texturePath) {
            this.id = id;
            this.texturePath = texturePath;
        }

        /** 注册用 id（不带命名空间）。 */
        public String id() {
            return this.id;
        }

        /** 屏幕底图的贴图 id（渲染器每帧交给 {@code MoreRenderLayers.getLight}）。 */
        public Identifier screenTexture() {
            return new Identifier(PidsXiamenObjects.NAMESPACE, "textures/" + this.texturePath + ".png");
        }

        /** 物品贴图。 */
        public Identifier itemTexture() {
            return new Identifier(PidsXiamenObjects.NAMESPACE, this.texturePath.replace("block/", "item/"));
        }
    }

    private final Kind kind;

    public PidsXiamenBlock(final Kind kind) {
        super();
        this.kind = kind;
    }

    public Kind kind() {
        return this.kind;
    }

    @Override
    public BlockEntityMapper createBlockEntity(final BlockPos pos, final BlockState state) {
        // state 可能是 null（物品栏渲染走 BlockItem），给个默认态，否则 BE 里取 kind 会 NPE。
        return new PidsXiamenBlockEntity(pos, state == null ? getDefaultState() : state);
    }

    /**
     * 不遮挡邻居（关键）：MTR 的 PIDS 基类只设了 {@code requiresTool/strength/luminance}，
     * <b>没有</b> {@code nonOpaque}，所以方块默认是「满格实体」。于是区块网格化时，邻居面朝我们
     * 的那一面会被剔除；而我们的模型只是一块悬空的薄屏，缝隙里就能直接看穿邻居 ——
     * 表现就是「PIDS 下面和周围的方块变透明」。
     *
     * <p>把剔除形状设成空集，邻居的所有面都会正常生成，不再有洞。</p>
     */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    /**
     * 刷子右键 = 打开本模组的「厦门 PIDS」界面（{@link PidsXiamenConfigScreen}）：
     * 站台筛选、显示页码、广告上一张 / 下一张。
     *
     * <h2>为什么不再走 MTR 的 {@code super.onUse}</h2>
     * <p>MTR 的 {@code BlockPIDSBaseHorizontal#onUse} 会调
     * {@code PacketTrainDataGuiServer.openPIDSConfigScreenS2C} 打开<b>它自己的</b> {@code PIDSConfigScreen}
     * —— 那个界面是「每行一条自定义文字 + 隐藏到站」，厦门的版式（固定三行 / 两行标签）用不上。
     * 所以刷子这一支自己接管：服务端先 {@code syncData()} 两半，再发包让玩家开我们的界面
     * （见 {@link PidsXiamenNet#openScreen}）。
     *
     * <p>其它物品 / 空手仍然交给 MTR 的判定（不是刷子时它返回 {@code FAIL}，
     * 于是右键 PIDS 不会误开任何界面）。
     */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (player.isHolding(mtr.Items.BRUSH.get())) {
            if (world.isClient) {
                // 客户端只负责「已处理」：界面是服务端发包叫开的，客户端自己开只会读到旧数据。
                return ActionResult.SUCCESS;
            }
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer
                    && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(
                            serverPlayer, PidsXiamenNet.ID)) {
                PidsXiamenNet.openScreen(serverPlayer, world, pos);
                return ActionResult.SUCCESS;
            }
            // 客户端没装 LTE（通道不认）：退回 MTR 自己的 PIDS 界面，而不是让刷子点了没反应。
            return super.onUse(state, world, pos, player, hand, hit);
        }
        return super.onUse(state, world, pos, player, hand, hit);
    }

    // ------------------------------------------------------------------ 拆一半 = 拆整块

    /**
     * 拆掉任意一半时，把另一半一起清掉，并且<b>只掉 1 个</b>。
     *
     * <h2>为什么必须自己拆</h2>
     * <p>MTR 的 {@code BlockPIDSBaseHorizontal.getStateForNeighborUpdate} 是：
     * <pre>
     *   if (direction == FACING &amp;&amp; !neighborState.isOf(this)) return Blocks.AIR.getDefaultState();
     * </pre>
     * 也就是「我这个方向的邻居没了，我自己变空气」。这一句在<b>邻居更新的回调里</b>执行，
     * 走的是 {@code NeighborUpdater.replaceWithStateForNeighborUpdate} —— 那条路径会顺带
     * {@code dropStacks}，于是另一半<b>变成掉落物</b>：一次拆解掉两个（就是「拆一半另一半掉落」）。
     * MTR 自己的 {@code onBreak} 只在 {@code FACING ∈ {SOUTH, WEST}} 时调 {@code IBlock.onBreakCreative}，
     * 而它<b>只对创造模式生效</b>，生存模式完全不管。
     *
     * <p>所以在<b>拆之前</b>先自己把另一半拆掉：{@code World.breakBlock(pos, false, player, 512)}
     * 带 {@code drop = false}（内部就是 {@code SKIP_DROPS}），只放破坏粒子和音效、不掉物品。
     * 两半的 facing 互为反向，所以 {@code pos.offset(facing)} 对<b>两份</b>都成立。
     *
     * <p>先清另一半还有一个好处：等到本格被移除时，邻居更新看到的那一格已经是空气，
     * {@code getStateForNeighborUpdate} 那条「掉落」路径根本不会触发。
     */
    @Override
    public void onBreak(final World world, final BlockPos pos, final BlockState state,
                        final PlayerEntity player) {
        if (!world.isClient) {
            // 掉落**只在这一处**产生（玩家拆）：掉落表已清空（pools: []），拆哪一半都掉 1 个。
            // 记一下「这次是玩家拆的」，让随后触发的 onStateReplaced 不要再掉一次。
            PLAYER_BREAK.set(Boolean.TRUE);
            dropOne(world, pos, player);
            removePartner(world, pos, player);
        }
        super.onBreak(world, pos, state, player);
    }

    /** 本次移除是玩家挖的：onStateReplaced 里不要再掉一次。 */
    private static final ThreadLocal<Boolean> PLAYER_BREAK = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 掉 1 个本方块（创造模式不掉）。 */
    private void dropOne(final World world, final BlockPos pos, final PlayerEntity player) {
        if (player != null && player.getAbilities().creativeMode) {
            return;
        }
        Block.dropStack(world, pos, new ItemStack(this));
    }

    /**
     * 兜底：这块屏是被别的东西弄掉的（活塞推、爆炸、{@code /setblock}）时，另一半也一起清掉。
     *
     * <p>{@code onBreak} 只覆盖「玩家挖掉」；其它移除路径不会调它，另一半就会走邻居更新变空气
     * （带掉落）。这里在方块被换掉的时候把另一半一起收掉 —— 深度只有 1 层：
     * 清另一半时那次 {@code onStateReplaced} 回头看本格，本格已经是空气，不会再往下递归。
     */
    @Override
    public void onStateReplaced(final BlockState state, final World world, final BlockPos pos,
                                final BlockState newState, final boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (world.isClient || newState.isOf(this)) {
            return;
        }
        // 玩家挖的那次：onBreak 已经掉过 1 个，这里只清另一半。
        if (PLAYER_BREAK.get()) {
            PLAYER_BREAK.set(Boolean.FALSE);
            removePartner(world, pos, null);
            return;
        }
        // 非玩家移除（活塞 / 爆炸 / setblock）：掉落表是空的，这里补掉 1 个；
        // 只由「数据半」负责掉，避免爆炸同时移除两半时掉两个。
        if (!clearingPartner && canStoreData(world, pos)) {
            Block.dropStack(world, pos, new ItemStack(this));
        }
        removePartner(world, pos, null);
    }

    /** 正在收另一半：防止 {@link #onStateReplaced} 反过来把本格也拆了（会让玩家一个都不掉）。 */
    private static boolean clearingPartner;

    /** 把 {@code pos.offset(facing)} 那一半（如果还是我们的 PIDS）清掉，不掉物品。 */
    private void removePartner(final World world, final BlockPos pos, final PlayerEntity player) {
        if (clearingPartner) {
            return;
        }
        final Direction facing = IBlock.getStatePropertySafe(world, pos, HorizontalFacingBlock.FACING);
        if (facing == null) {
            return;
        }
        final BlockPos partnerPos = pos.offset(facing);
        if (world.getBlockState(partnerPos).getBlock() != this) {
            return;
        }
        clearingPartner = true;
        try {
            world.breakBlock(partnerPos, false, player, 512);
        } catch (Throwable t) {
            // breakBlock 失败也不能让拆方块本身抛异常（否则本格留在世界上、另一半还在）。
            world.setBlockState(partnerPos, Blocks.AIR.getDefaultState(),
                    Block.NOTIFY_ALL | Block.SKIP_DROPS);
        } finally {
            clearingPartner = false;
        }
    }

    // ------------------------------------------------------------------ 两格宽的一半

    /**
     * 这一格是不是「存数据 / 负责画」的那一半。
     *
     * <p>判定与天津完全一致：{@code facing == SOUTH || facing == WEST}。
     * 一对 PIDS 的两格 facing 互为反向，所以必然正好命中一格。
     */
    public static boolean canStoreData(final BlockView world, final BlockPos pos) {
        final Direction facing = IBlock.getStatePropertySafe(world, pos, HorizontalFacingBlock.FACING);
        return facing == Direction.SOUTH || facing == Direction.WEST;
    }

    /** 从任意一半拿到负责画的那一格的坐标（{@code MtrTiles} / 绑定逻辑要用）。 */
    public static BlockPos dataPos(final BlockView world, final BlockPos pos) {
        if (canStoreData(world, pos)) {
            return pos;
        }
        final Direction facing = IBlock.getStatePropertySafe(world, pos, HorizontalFacingBlock.FACING);
        return facing == null ? pos : pos.offset(facing);
    }
}
