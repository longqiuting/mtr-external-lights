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
 * 信号连接器（{@code mtrlte:signal_linker}）：两次右键完成一次绑定。三种顺序都认，先点的那个是
 * 「源」，后点的那个是「要显示它的那个」：
 *
 * <table border="1">
 *   <caption>两次右键的语义</caption>
 *   <tr><th>第一次</th><th>第二次</th><th>结果</th></tr>
 *   <tr><td>轨道节点</td><td>信号灯 S</td><td>S 显示该节点出去的那一段</td></tr>
 *   <tr><td>信号灯 A</td><td>轨道节点</td><td>同上（只是顺序反过来）</td></tr>
 *   <tr><td>信号灯 A</td><td>信号灯 B</td><td>B 显示 A 的状态（A 若自己绑了节点，B 就显示那个节点）</td></tr>
 * </table>
 *
 * <p>第一次点击的状态挂在物品 NBT 上（{@code lte_node} / {@code lte_lamp} + {@code lte_dim}）：
 * {@code ItemUsageContext} 只给「这一次点击」的方块，跨两次点击的状态必须挂在玩家手上；MTR 自己的
 * {@code ItemBlockClickingBase} 也是这么干的（它读 {@code stack.getOrCreateNbt()} 的 {@code "pos"}）。
 * 物品 NBT 由服务端写（本类在 {@code world.isClient} 时直接返回），客户端伪造不了。
 *
 * <h2>不碰方块（这是修掉「绑完灯自己转一下」的关键）</h2>
 * 老版本在绑定成功后会调用 {@code world.setBlockState(...)} 把信号灯的 {@code HORIZONTAL_FACING}
 * 扭到能匹配节点轨道的那一面 —— 那既改了世界（存档里的方块朝向变了、旁边看着的人会看到灯突然转
 * 90°），也让「绑定」这个动作带上了一个和玩家预期无关的副作用。现在这一条彻底没有了：
 * <b>本类不写任何 BlockState</b>。朝向对不上时只在聊天栏提示玩家「灯可能不亮，请自己转一下」，
 * 判定走 {@link #facingMismatch}，只读世界。
 *
 * <p>绑定只改「这个信号灯去哪儿找节点 / 抄哪盏灯」，不改 MTR 的信号计算：真正的亮灯逻辑仍然是
 * {@code RenderSignalBase#getOccupiedAspect}（节点前后几段轨道是否被列车 / 信号区间占用）。
 */
public final class SignalLinkItem extends Item {

    private static final String TAG_NODE = "lte_node";
    private static final String TAG_LAMP = "lte_lamp";
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

        // 0) 方速交通灯：认得出来，但绑不了（原因见 FangsuSignalCompat 的类注释）。
        //    单独一条提示，否则玩家会看到「请右键轨道节点或信号灯」，误以为是自己点歪了。
        if (FangsuSignalCompat.isFangsuTrafficLight(clickedState)) {
            say(player, "message.mtrlte.signal_link.fangsu_unsupported", Formatting.GOLD);
            return ActionResult.SUCCESS;
        }

        // 1) 右键信号灯：完成 / 解除绑定
        if (isSignal(clickedState)) {
            return onSignalClicked(world, player, context.getStack(), clicked);
        }

        // 2) 右键轨道节点（或者节点附近）：完成绑定 / 记为待连接
        final BlockPos node = findNode(world, clicked, context);
        if (node != null) {
            return onNodeClicked(world, player, context.getStack(), node);
        }

        say(player, "message.mtrlte.signal_link.not_signal", Formatting.GRAY);
        return ActionResult.PASS;
    }

    // ------------------------------------------------------------------ 节点这一侧

    private ActionResult onNodeClicked(final World world, final ServerPlayerEntity player,
                                       final ItemStack stack, final BlockPos node) {
        if (!SignalLinkNet.hasRail(world, node)) {
            say(player, "message.mtrlte.signal_link.no_rail", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        final NbtCompound nbt = stack.getOrCreateNbt();

        // 反向顺序：上一步点的是信号灯 → 这里就是「灯 → 节点」的第二次点击。
        if (nbt.contains(TAG_LAMP)) {
            return finishLampToNode(world, player, stack, nbt, node);
        }

        nbt.putLong(TAG_NODE, node.asLong());
        nbt.putString(TAG_DIM, SignalLinkNet.dimensionOf(world));
        say(player, "message.mtrlte.signal_link.node_set", Formatting.AQUA, posText(node));
        return ActionResult.SUCCESS;
    }

    /** 灯 → 节点：第一次点灯、这一次点节点（与「节点 → 灯」的落盘结果完全一样）。 */
    private ActionResult finishLampToNode(final World world, final ServerPlayerEntity player,
                                          final ItemStack stack, final NbtCompound nbt,
                                          final BlockPos node) {
        final String dim = SignalLinkNet.dimensionOf(world);
        if (!dim.equals(nbt.getString(TAG_DIM))) {
            say(player, "message.mtrlte.signal_link.wrong_dim", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        final BlockPos lamp = BlockPos.fromLong(nbt.getLong(TAG_LAMP));
        final BlockState lampState = world.getBlockState(lamp);
        if (!isSignal(lampState)) {
            say(player, "message.mtrlte.signal_link.lamp_gone", Formatting.RED);
            clearPending(nbt);
            return ActionResult.SUCCESS;
        }

        final SignalLinkStore store = SignalLinkStore.get((ServerWorld) world);
        store.put(lamp, node);
        SignalLinkNet.broadcastPut(world.getServer(), dim, lamp, node);
        // 灯 → 节点 会顶掉这盏灯原来的 lamp 源（两张表互斥），客户端也要跟着掉。
        SignalLinkNet.broadcastLampRemove(world.getServer(), dim, lamp);

        clearPending(nbt);
        warnFacing(player, world, lampState, node);
        say(player, "message.mtrlte.signal_link.linked", Formatting.GREEN, posText(node));
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 信号灯这一侧

    private ActionResult onSignalClicked(final World world, final ServerPlayerEntity player,
                                         final ItemStack stack, final BlockPos signal) {
        final NbtCompound nbt = stack.getOrCreateNbt();
        final SignalLinkStore store = SignalLinkStore.get((ServerWorld) world);
        final String dim = SignalLinkNet.dimensionOf(world);

        // 潜行右键 = 解绑（节点绑定和 lamp 绑定一起清）
        if (player.isSneaking()) {
            if (store.remove(signal)) {
                SignalLinkNet.broadcastRemove(world.getServer(), dim, signal);
                SignalLinkNet.broadcastLampRemove(world.getServer(), dim, signal);
                say(player, "message.mtrlte.signal_link.unlinked", Formatting.YELLOW);
            } else {
                say(player, "message.mtrlte.signal_link.not_linked", Formatting.GRAY);
            }
            clearPending(nbt);
            return ActionResult.SUCCESS;
        }

        // 灯 → 灯：上一步点的是另一盏灯
        if (nbt.contains(TAG_LAMP)) {
            final BlockPos source = BlockPos.fromLong(nbt.getLong(TAG_LAMP));
            if (source.equals(signal)) {
                // 同一盏灯点了两次：刷新提示，不当作绑定。
                say(player, "message.mtrlte.signal_link.lamp_set", Formatting.AQUA, posText(signal));
                return ActionResult.SUCCESS;
            }
            if (!dim.equals(nbt.getString(TAG_DIM))) {
                say(player, "message.mtrlte.signal_link.wrong_dim", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            final BlockState sourceState = world.getBlockState(source);
            if (!isSignal(sourceState)) {
                say(player, "message.mtrlte.signal_link.lamp_gone", Formatting.RED);
                clearPending(nbt);
                return ActionResult.SUCCESS;
            }
            if (!store.putLamp(signal, source)) {
                // 自指或成环（A→B 之后又点 B→A）
                say(player, "message.mtrlte.signal_link.lamp_cycle", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            SignalLinkNet.broadcastLampPut(world.getServer(), dim, signal, source);
            // 这条 lamp 链接顶掉了 signal 原来的节点绑定，客户端那张表也要掉。
            SignalLinkNet.broadcastRemove(world.getServer(), dim, signal);

            clearPending(nbt);
            say(player, "message.mtrlte.signal_link.lamp_linked", Formatting.GREEN, posText(source));
            return ActionResult.SUCCESS;
        }

        // 节点 → 灯：上一步点的是节点
        if (nbt.contains(TAG_NODE)) {
            if (!dim.equals(nbt.getString(TAG_DIM))) {
                say(player, "message.mtrlte.signal_link.wrong_dim", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            final BlockPos node = BlockPos.fromLong(nbt.getLong(TAG_NODE));
            if (!BlockNode.class.isInstance(world.getBlockState(node).getBlock())
                    || !SignalLinkNet.hasRail(world, node)) {
                say(player, "message.mtrlte.signal_link.node_gone", Formatting.RED);
                nbt.remove(TAG_NODE);
                nbt.remove(TAG_DIM);
                return ActionResult.SUCCESS;
            }

            store.put(signal, node);
            SignalLinkNet.broadcastPut(world.getServer(), dim, signal, node);
            SignalLinkNet.broadcastLampRemove(world.getServer(), dim, signal);

            clearPending(nbt);
            warnFacing(player, world, world.getBlockState(signal), node);
            say(player, "message.mtrlte.signal_link.linked", Formatting.GREEN, posText(node));
            return ActionResult.SUCCESS;
        }

        // 什么都没选 → 这盏灯成为「源」，下一次点节点或另一盏灯
        nbt.putLong(TAG_LAMP, signal.asLong());
        nbt.putString(TAG_DIM, dim);
        say(player, "message.mtrlte.signal_link.lamp_set", Formatting.AQUA, posText(signal));
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

    // ------------------------------------------------------------------ 朝向（只读）

    /**
     * 这盏灯的朝向是不是<b>一段轨道都对不上</b>。
     *
     * <p>{@code RenderSignalBase#render} 把方块自己的朝向换算成轨道角度
     * （{@code facing.asRotation() + 90}，asRotation: S=0/W=90/N=180/E=270 ⇒ E=0 与
     * {@code RailAngle.E} 一致），再拿它去挑「这个节点上朝这个方向的那一段轨道」；差 90° 以上
     * （{@code RailAngle.similarFacing} 的阈值就是 90°）就一段都挑不中，信号灯会一直不亮。
     *
     * <p>这里只做判定，<b>不修方块</b>：返回 {@code true} 时由调用方提示玩家自己转灯。
     * 老版本会在这一步直接 {@code setBlockState} 把灯拧过去 —— 那就是「绑完灯自己转一下」的来源。
     */
    private static boolean facingMismatch(final World world, final BlockState state, final BlockPos node) {
        if (!state.contains(Properties.HORIZONTAL_FACING)) {
            return false;
        }
        final List<Rail> rails = SignalLinkNet.railsAt(world, node);
        if (rails.isEmpty()) {
            return false;
        }
        return !matches(rails, state.get(Properties.HORIZONTAL_FACING));
    }

    private static void warnFacing(final ServerPlayerEntity player, final World world,
                                   final BlockState lampState, final BlockPos node) {
        if (facingMismatch(world, lampState, node)) {
            say(player, "message.mtrlte.signal_link.facing_hint", Formatting.YELLOW);
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

    // ------------------------------------------------------------------ 杂项

    private static void clearPending(final NbtCompound nbt) {
        nbt.remove(TAG_NODE);
        nbt.remove(TAG_LAMP);
        nbt.remove(TAG_DIM);
    }

    private static boolean isSignal(final BlockState state) {
        return BlockSignalLightBase.class.isInstance(state.getBlock())
                || BlockSignalSemaphoreBase.class.isInstance(state.getBlock());
    }

    /**
     * 物品 NBT 里记着的「待连接节点」，没有就 {@code null}。
     *
     * <p>给客户端渲染用（{@code SignalNodeHighlight} 把这一颗画成青色，其余白框），
     * 所以必须是只读的：不 {@code getOrCreateNbt()}，不写任何东西。
     */
    public static BlockPos selectedNode(final ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            final NbtCompound nbt = stack.getNbt();
            if (nbt == null || !nbt.contains(TAG_NODE)) {
                return null;
            }
            return BlockPos.fromLong(nbt.getLong(TAG_NODE));
        } catch (Throwable t) {
            return null;
        }
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
        if (nbt != null && nbt.contains(TAG_LAMP)) {
            tooltip.add(Text.translatable("tooltip.mtrlte.signal_link.selected_lamp",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_LAMP))))
                    .formatted(Formatting.AQUA));
        } else if (nbt != null && nbt.contains(TAG_NODE)) {
            tooltip.add(Text.translatable("tooltip.mtrlte.signal_link.selected",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_NODE))))
                    .formatted(Formatting.AQUA));
        } else {
            tooltip.add(Text.translatable("tooltip.mtrlte.signal_link.hint").formatted(Formatting.GRAY));
        }
        tooltip.add(Text.translatable("tooltip.mtrlte.signal_link.node_marks").formatted(Formatting.DARK_GRAY));
    }
}
