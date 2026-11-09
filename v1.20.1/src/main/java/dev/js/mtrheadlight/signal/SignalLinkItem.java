package dev.js.mtrheadlight.signal;

import mtr.block.BlockNode;
import mtr.block.BlockSignalLightBase;
import mtr.block.BlockSignalSemaphoreBase;
import mtr.data.Rail;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.List;

/**
 * 信号连接器（{@code mtrlte:signal_linker}）：两次右键完成「节点 → 信号灯」绑定。
 *
 * <ol>
 *   <li>第一次右键<b>轨道节点</b>（{@code mtr.block.BlockNode}，它的 outline shape 是整块 1px 薄板
 *       —— 见 BlockNode#getOutlineShape：IS_CONNECTED 时 {@code cuboid(0,0,0,16,1,16)}，否则空）
 *       → 把节点坐标写进物品 NBT；</li>
 *   <li>第二次右键<b>信号灯</b>（{@code BlockSignalLightBase} / {@code BlockSignalSemaphoreBase}）
 *       → 写绑定表 + 同步给所有客户端。</li>
 * </ol>
 *
 * <p>为什么要物品 NBT 存第一步：{@code ItemUsageContext} 只给「这一次点击」的方块，
 * 跨两次点击的状态必须挂在玩家身上；MTR 自己的 {@code ItemBlockClickingBase} 就是这么干的
 * （{@code ItemBlockClickingBase#useOnBlock} 里读 {@code stack.getOrCreateNbt()} 的 {@code "pos"}）。
 * 物品 NBT 是服务端写的（本类在 {@code world.isClient} 时直接返回），所以不会被客户端伪造。
 *
 * <p>绑定只改「这个信号灯去哪儿找节点」，不改 MTR 的信号计算：真正的亮灯逻辑仍然是
 * {@code RenderSignalBase#getOccupiedAspect}（节点前后几段轨道是否被列车 / 信号区间占用）。
 */
public final class SignalLinkItem extends Item {

    private static final String TAG_NODE = "lte_node";
    private static final String TAG_DIM = "lte_dim";

    /** 找节点时允许的搜索半径（点歪了也能选中附近的节点）。 */
    private static final int NODE_SEARCH = 3;

    public SignalLinkItem(final Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(final ItemUsageContext context) {
        final World world = context.getWorld();
        if (world.isClient) {
            // 服务端权威：客户端只负责摆手。
            return ActionResult.SUCCESS;
        }
        final ServerPlayerEntity player = context.getPlayer() instanceof ServerPlayerEntity p ? p : null;
        if (player == null) {
            return ActionResult.PASS;
        }

        final BlockPos clicked = context.getBlockPos();
        final BlockState clickedState = world.getBlockState(clicked);

        // 1) 右键信号灯：完成 / 解除绑定
        if (isSignal(clickedState)) {
            return onSignalClicked(world, player, context.getStack(), clicked);
        }

        // 2) 右键节点（或者节点附近）：记为待连接
        final BlockPos node = findNode(world, clicked, context);
        if (node != null) {
            return onNodeClicked(world, player, context.getStack(), node);
        }

        say(player, "message.mtrlte.signal_link.not_signal", Formatting.GRAY);
        return ActionResult.PASS;
    }

    // ------------------------------------------------------------------ 两次点击

    private ActionResult onNodeClicked(final World world, final ServerPlayerEntity player,
                                       final ItemStack stack, final BlockPos node) {
        if (!SignalLinkNet.hasRail(world, node)) {
            say(player, "message.mtrlte.signal_link.no_rail", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        final NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putLong(TAG_NODE, node.asLong());
        nbt.putString(TAG_DIM, SignalLinkNet.dimensionOf(world));
        say(player, "message.mtrlte.signal_link.node_set", Formatting.AQUA, posText(node));
        return ActionResult.SUCCESS;
    }

    private ActionResult onSignalClicked(final World world, final ServerPlayerEntity player,
                                         final ItemStack stack, final BlockPos signal) {
        final NbtCompound nbt = stack.getOrCreateNbt();
        final SignalLinkStore store = SignalLinkStore.get((ServerWorld) world);

        // 潜行右键 = 解绑
        if (player.isSneaking()) {
            if (store.remove(signal) != null) {
                SignalLinkNet.broadcastRemove(world.getServer(), SignalLinkNet.dimensionOf(world), signal);
                say(player, "message.mtrlte.signal_link.unlinked", Formatting.YELLOW);
            } else {
                say(player, "message.mtrlte.signal_link.not_linked", Formatting.GRAY);
            }
            return ActionResult.SUCCESS;
        }

        if (!nbt.contains(TAG_NODE)) {
            say(player, "message.mtrlte.signal_link.need_node", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        if (!SignalLinkNet.dimensionOf(world).equals(nbt.getString(TAG_DIM))) {
            say(player, "message.mtrlte.signal_link.wrong_dim", Formatting.RED);
            return ActionResult.SUCCESS;
        }

        final BlockPos node = BlockPos.fromLong(nbt.getLong(TAG_NODE));
        if (!BlockNode.class.isInstance(world.getBlockState(node).getBlock()) || !SignalLinkNet.hasRail(world, node)) {
            say(player, "message.mtrlte.signal_link.node_gone", Formatting.RED);
            nbt.remove(TAG_NODE);
            return ActionResult.SUCCESS;
        }

        store.put(signal, node);
        alignFacing(world, signal, world.getBlockState(signal), node);
        SignalLinkNet.broadcastPut(world.getServer(), SignalLinkNet.dimensionOf(world), signal, node);

        nbt.remove(TAG_NODE);
        nbt.remove(TAG_DIM);
        say(player, "message.mtrlte.signal_link.linked", Formatting.GREEN, posText(node));
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 找节点

    /**
     * 目标方块本身就是节点就用它；否则在点击位置附近找最近的、接了轨道的节点。
     *
     * <p>「点歪」很常见：节点只有 1px 高，而且弯道上的轨道是画出来的、不是方块，
     * 玩家往往点的是轨道下方 / 旁边的地面。MTR 自己的节点工具不做这个兜底，这里做一层。
     */
    private static BlockPos findNode(final World world, final BlockPos clicked, final ItemUsageContext context) {
        if (BlockNode.class.isInstance(world.getBlockState(clicked).getBlock())) {
            return clicked;
        }
        final double hitX = context.getHitPos().x;
        final double hitY = context.getHitPos().y;
        final double hitZ = context.getHitPos().z;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        final BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -NODE_SEARCH; dx <= NODE_SEARCH; dx++) {
            for (int dy = -NODE_SEARCH; dy <= NODE_SEARCH; dy++) {
                for (int dz = -NODE_SEARCH; dz <= NODE_SEARCH; dz++) {
                    cursor.set(clicked.getX() + dx, clicked.getY() + dy, clicked.getZ() + dz);
                    if (!BlockNode.class.isInstance(world.getBlockState(cursor).getBlock())) {
                        continue;
                    }
                    if (!SignalLinkNet.hasRail(world, cursor)) {
                        continue;
                    }
                    final double d = cursor.getSquaredDistance(hitX, hitY, hitZ);
                    if (d < bestDist) {
                        bestDist = d;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 朝向

    /**
     * 让信号灯的 {@code HORIZONTAL_FACING} 与节点上某条轨道的方向对得上。
     *
     * <p>必须做：{@code RenderSignalBase#render} 把 <b>方块自己的朝向</b>换算成轨道角度
     * （{@code facing.asRotation() + 90}，asRotation: S=0/W=90/N=180/E=270 ⇒ E=0 与
     * {@code RailAngle.E} 一致），再拿它去挑「这个节点上朝这个方向的那一段轨道」。
     * 朝向和轨道方向差 90° 以上（{@code RailAngle.similarFacing} 的阈值就是 90°）就一段都挑不中，
     * 信号灯会一直不亮。原版行为里这天然成立（灯就放在节点上、玩家顺着轨道放）；
     * 绑定到远处的节点后必须替玩家调正。
     */
    private static void alignFacing(final World world, final BlockPos signal, final BlockState state,
                                    final BlockPos node) {
        if (!state.contains(Properties.HORIZONTAL_FACING)) {
            return;
        }
        final List<Rail> rails = SignalLinkNet.railsAt(world, node);
        if (rails.isEmpty()) {
            return;
        }
        if (matches(rails, state.get(Properties.HORIZONTAL_FACING))) {
            return;
        }
        for (final Direction dir : Direction.Type.HORIZONTAL) {
            if (matches(rails, dir)) {
                world.setBlockState(signal, state.with(Properties.HORIZONTAL_FACING, dir), 3);
                return;
            }
        }
    }

    private static boolean matches(final List<Rail> rails, final Direction dir) {
        final float angle = dir.asRotation() + 90.0F;
        for (final Rail rail : rails) {
            if (rail.facingStart.similarFacing(angle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSignal(final BlockState state) {
        return BlockSignalLightBase.class.isInstance(state.getBlock())
                || BlockSignalSemaphoreBase.class.isInstance(state.getBlock());
    }

    private static Text posText(final BlockPos pos) {
        return Text.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    private static void say(final ServerPlayerEntity player, final String key,
                            final Formatting color, final Object... args) {
        player.sendMessage(Text.translatable(key, args).formatted(color), true);
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void appendTooltip(final ItemStack stack, final World world,
                              final List<Text> tooltip, final TooltipContext context) {
        final NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains(TAG_NODE)) {
            tooltip.add(Text.translatable("tooltip.mtrlte.signal_link.selected",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_NODE))))
                    .formatted(Formatting.AQUA));
        } else {
            tooltip.add(Text.translatable("tooltip.mtrlte.signal_link.hint").formatted(Formatting.GRAY));
        }
    }
}
