package dev.js.mtrheadlight.signal;

import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import mtr.block.BlockNode;
import mtr.block.BlockSignalLightBase;
import mtr.block.BlockSignalSemaphoreBase;
import mtr.block.IBlock;
import mtr.data.Rail;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
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
import net.minecraft.util.math.Vec3d;
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

        // 1) 右键信号灯：完成 / 解除绑定
        //    方速交通灯走同一条路，只是先把它切到「MTR 信号联动」（它的 program 档不会读我们的绑定）。
        if (isLamp(clickedState)) {
            if (!player.isSneaking() && FangsuSignalCompat.isFangsuTrafficLight(clickedState)) {
                switchFangsuMode(player, world, clicked);
            }
            return onSignalClicked(world, player, context.getStack(), clicked);
        }

        // 2) 右键轨道节点（或者节点附近）：完成绑定 / 记为待连接
        final BlockPos node = findNode(world, clicked, context);
        if (node != null) {
            return onNodeClicked(world, player, context.getStack(), node);
        }

        // 3) 什么都绑不上：一条红字，不解释原因、不打日志。
        //    玩家不需要知道是哪一种「绑不了」，只要知道这一下没生效。
        say(player, "message.mtrlte.signal_link.not_linkable", Formatting.RED);
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
        if (!isLamp(lampState)) {
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
            if (!isLamp(sourceState)) {
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
        return findNode(world, clicked, context.getHitPos());
    }

    /**
     * 同 {@link #findNode(World, BlockPos, ItemUsageContext)}，命中点直接给 ——
     * 左键那条路（{@code AttackBlockCallback}）没有 {@code ItemUsageContext}，命中点只有方块中心。
     */
    private static BlockPos findNode(final World world, final BlockPos clicked, final Vec3d hitPos) {
        if (BlockNode.class.isInstance(world.getBlockState(clicked).getBlock())) {
            return clicked;
        }
        final double hitX = hitPos.x;
        final double hitY = hitPos.y;
        final double hitZ = hitPos.z;

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

    // ------------------------------------------------------------------ 解绑（左键 / 方块被拆）

    /**
     * 客户端判定：左键点这一格时该不该被信号连接器拦下来（这一格是不是「轨道节点或信号灯」）。
     *
     * <p><b>只读世界</b>：客户端手里没有服务端那两张表（只有 {@code SignalLinks} 镜像，而且不一定有
     * 这一维度的数据），所以「拦不拦」只看「点到的东西归不归这个连接器管」；到底有没有绑定，由服务端
     * 回答（有没有绑定决定的是提示文字，不是拦不拦）。
     *
     * <p>为什么要拦：不拦的话创造模式左键会直接把灯拆了 —— 玩家想解绑，结果把灯弄没了。
     */
    public static boolean isUnbindTarget(final World world, final BlockPos pos) {
        try {
            return targetOf(world, pos) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 左键解绑：点信号灯 → 解掉它<b>自己</b>的两条记录（→节点、→源灯），以及抄它的灯；
     * 点轨道节点 → 解掉所有「绑到它」的灯（节点是值那一端，见 {@link SignalLinkStore#removeInvolving}）。
     *
     * <p>提示：真的删掉了 = 绿字「已解绑」；一条都没有 = 黄字「这里没有绑定」。
     *
     * @return 这一下归不归信号连接器管（{@code false} = 什么也没做，什么都不提示）
     */
    public static boolean unlinkAt(final World world, final ServerPlayerEntity player, final BlockPos clicked) {
        if (!(world instanceof ServerWorld server)) {
            return false;
        }
        final BlockPos target = targetOf(world, clicked);
        if (target == null) {
            return false;
        }
        if (unlink(server, target)) {
            say(player, "message.mtrlte.link.unlinked", Formatting.GREEN);
        } else {
            say(player, "message.mtrlte.link.none", Formatting.YELLOW);
        }
        return true;
    }

    /**
     * 方块被拆：清掉这一格上的绑定，<b>不弹提示</b>。
     *
     * <p>拆掉的那一格可能是灯（键），也可能是节点（值）—— 两种都由
     * {@link SignalLinkStore#removeInvolving} 覆盖，且只动与这一格直接相关的条目。
     *
     * <p>双格灯（MTR 的 {@code *Semaphore2} / 灯柱那几种）再补一刀：绑定记的是<b>玩家当初右键的那一半</b>，
     * 拆掉另一半时这一格是空气、{@code HALF} 也读不到了，只能拿「拆之前」的 {@code state} 把另一半
     * 也清一遍 —— 否则原地重放，那盏灯会「继承」回旧绑定。
     */
    public static void unlinkBroken(final ServerWorld world, final BlockPos pos, final BlockState state) {
        unlink(world, pos);
        final BlockPos otherHalf = otherHalfOf(pos, state);
        if (otherHalf != null) {
            unlink(world, otherHalf);
        }
    }

    /** 这一格的上 / 下一格是不是同一盏双格灯的另一半；不是（没有 {@code HALF} 属性）→ {@code null}。 */
    private static BlockPos otherHalfOf(final BlockPos pos, final BlockState state) {
        if (state == null) {
            return null;
        }
        try {
            if (!state.contains(IBlock.HALF)) {
                return null;
            }
            return IBlock.getStatePropertySafe(state, IBlock.HALF) == DoubleBlockHalf.LOWER
                    ? pos.up() : pos.down();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这一格归谁：信号灯 → 它自己；轨道节点（或 3 格内接了轨道的节点）→ 那个节点；都不是 → {@code null}。 */
    private static BlockPos targetOf(final World world, final BlockPos clicked) {
        try {
            if (isLamp(world.getBlockState(clicked))) {
                return clicked;
            }
            return findNode(world, clicked, Vec3d.ofCenter(clicked));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 真删：两张表按「这一格是键、或是值」清，然后逐条广播给客户端。 */
    private static boolean unlink(final ServerWorld world, final BlockPos pos) {
        final SignalLinkStore.Removed removed = SignalLinkStore.get(world).removeInvolving(pos);
        if (!removed.any()) {
            return false;
        }
        final String dim = SignalLinkNet.dimensionOf(world);
        for (final BlockPos signal : removed.signals) {
            SignalLinkNet.broadcastRemove(world.getServer(), dim, signal);
        }
        for (final BlockPos lamp : removed.lamps) {
            SignalLinkNet.broadcastLampRemove(world.getServer(), dim, lamp);
        }
        return true;
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
     * 「可以当信号灯用」的方块：MTR 的两种信号灯 + 方速交通灯。
     *
     * <p>方速交通灯能进来是因为它的 mtr 档由 {@code FangsuSignalSync} 驱动（见那个类的类注释）：
     * 绑定表和 MTR 灯共用同一张，客户端渲染时按节点的 aspect 写它的 {@code lightState}。
     */
    private static boolean isLamp(final BlockState state) {
        return isSignal(state) || FangsuSignalCompat.isFangsuTrafficLight(state);
    }

    /**
     * 把方速交通灯切到「MTR 信号联动」档，失败只提示玩家自己切，绝不打断绑定流程。
     *
     * <p>三种结果都不影响后面的 {@code onSignalClicked}：切成功了绑定完就能亮；切不过去玩家手动
     * 在配置界面选「MTR信号联动」也一样（绑定表本身跟模式无关）。
     */
    private static void switchFangsuMode(final ServerPlayerEntity player, final World world,
                                         final BlockPos lamp) {
        final FangsuTrafficLightMode.Result result;
        try {
            result = FangsuTrafficLightMode.toMtr(world, lamp);
        } catch (Throwable t) {
            // toMtr 自己已经兜住异常，这里是最后一道保险。
            say(player, "message.mtrlte.signal_link.fangsu_mode_manual", Formatting.YELLOW);
            return;
        }
        switch (result) {
            case OK -> say(player, "message.mtrlte.signal_link.fangsu_mode_mtr", Formatting.GREEN);
            case ALREADY -> {
                // 已经在 mtr 档：不刷屏，绑定本身的提示够了。
            }
            case FAIL -> say(player, "message.mtrlte.signal_link.fangsu_mode_manual", Formatting.YELLOW);
            default -> {
                // NOT_FANGSU（方块实体不是方速交通灯，或者被别的模组换了实现）：
                // 同样给一条手动提示，绑定照旧。
                say(player, "message.mtrlte.signal_link.fangsu_mode_manual", Formatting.YELLOW);
            }
        }
    }

    /**
     * 物品 NBT 里记着的「待连接节点」，没有就 {@code null}。
     *
     * <p>给客户端渲染用（{@code ConnectorNodeHighlight} 把这一颗画成青色，其余白框），
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
        return new LiteralText(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    private static void say(final ServerPlayerEntity player, final String key,
                            final Formatting color, final Object... args) {
        player.sendMessage(new TranslatableText(key, args).formatted(color), true);
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void appendTooltip(final ItemStack stack, final World world,
                              final List<Text> tooltip, final TooltipContext context) {
        final NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains(TAG_LAMP)) {
            tooltip.add(new TranslatableText("tooltip.mtrlte.signal_link.selected_lamp",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_LAMP))))
                    .formatted(Formatting.AQUA));
        } else if (nbt != null && nbt.contains(TAG_NODE)) {
            tooltip.add(new TranslatableText("tooltip.mtrlte.signal_link.selected",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_NODE))))
                    .formatted(Formatting.AQUA));
        } else {
            tooltip.add(new TranslatableText("tooltip.mtrlte.signal_link.hint").formatted(Formatting.GRAY));
        }
        tooltip.add(new TranslatableText("tooltip.mtrlte.link.right_click").formatted(Formatting.GRAY));
        tooltip.add(new TranslatableText("tooltip.mtrlte.link.left_click").formatted(Formatting.DARK_GRAY));
        tooltip.add(new TranslatableText("tooltip.mtrlte.signal_link.node_marks").formatted(Formatting.DARK_GRAY));
    }
}
