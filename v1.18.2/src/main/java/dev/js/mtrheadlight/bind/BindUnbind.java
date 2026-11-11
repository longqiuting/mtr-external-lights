package dev.js.mtrheadlight.bind;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.signal.SignalLinkItem;
import mtr.block.IBlock;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 连接器的「解绑」两条入口 —— 左键解绑 + 方块被拆自动解绑。
 *
 * <h2>左键：为什么一个 {@code AttackBlockCallback} 就够</h2>
 * <p>{@code Item#use} 只覆盖右键；左键在 Fabric 里就是 {@code AttackBlockCallback}。它<b>两端都发</b>，
 * 而且 Fabric API 自己的客户端 mixin 做了两件我们需要的事（{@code ClientPlayerInteractionManagerMixin}）：
 * <ol>
 *   <li>回调返回 {@code SUCCESS} → {@code attackBlock} 直接返回 true，<b>不打本地那一下</b>
 *       （创造模式不 {@code breakBlock}，生存模式不开始挖掘、不裂方块）；</li>
 *   <li>返回 {@code SUCCESS}（即 {@code ActionResult#isAccepted()}）时，Fabric 还会替我们发一个
 *       {@code START_DESTROY_BLOCK} 给服务端 —— 所以服务端一定会收到这次点击，不用自己另开包；</li>
 * </ol>
 * 服务端那一侧（{@code ServerPlayerInteractionManagerMixin}）在同一个回调返回 {@code SUCCESS} 时
 * {@code info.cancel()}：动作作废、并把这一格的方块状态与方块实体重新推给客户端（擦掉客户端的预测拆除）。
 * 于是「客户端不拆、服务端也不拆、服务端顺手解绑」用同一个回调就成立了，两端判定必然一致。
 *
 * <p>拦下来是必须的：不拦的话创造模式一左键就把信号灯 / PIDS / 投影机拆了 —— 玩家想解绑，
 * 结果把东西弄没了。
 *
 * <h2>方块被拆</h2>
 * <p>绑定表是按 {@code BlockPos} 存的，拆方块不会自动清 —— 于是「拆掉 → 原地再放一个」会自动继承
 * 旧绑定（方速信号机 / MTR 信号灯 / PIDS / 投影机都一样）。这里挂 {@code PlayerBlockBreakEvents.AFTER}
 * （方块已经从世界上消失、方块实体也清掉了的那个点），把<b>与这一格直接相关</b>的记录删掉并广播给客户端。
 *
 * <p>「直接相关」的定义在 {@link dev.js.mtrheadlight.signal.SignalLinkStore#removeInvolving} /
 * {@link PidsLinkStore#removeInvolving}：这一格是键（它自己的绑定）<b>或</b>是值（别人绑到它身上的记录）。
 * 同一张表里其它灯 / 其它屏 / 其它投影机的记录一条都不动 —— 不会因为拆一台机器把同站整片绑定清掉。
 *
 * <p><b>只覆盖玩家拆方块</b>（{@code PlayerBlockBreakEvents.AFTER}）。活塞推、爆炸、{@code /setblock}
 * 这类破坏不经过这个事件，那条路上的绑定会留成悬空记录（渲染时节点不存在就是灭灯 / 空屏，不会崩）。
 */
public final class BindUnbind {

    private BindUnbind() {
    }

    /**
     * 两端都要注册（{@code main} 入口）：客户端那半边决定「本地挖不挖」，服务端那半边决定
     * 「真拆不拆 + 解不解绑」。
     */
    public static void register() {
        try {
            AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
                if (player == null || world == null || pos == null || hand != Hand.MAIN_HAND) {
                    return ActionResult.PASS;
                }
                if (player.isSpectator()) {
                    // 旁观者不该改绑定（也不该拆方块）。
                    return ActionResult.PASS;
                }
                final Item item = player.getStackInHand(hand).getItem();
                if (!owns(item, world, pos)) {
                    // 不是这个连接器管的东西：一律放行，方块照常挖。
                    return ActionResult.PASS;
                }
                if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                    // 服务端权威：真删表 + 广播。客户端那一下只是「拦下挖掘」，不改任何数据。
                    onClick(serverPlayer, pos);
                }
                // 两端都返回 SUCCESS：客户端不本地挖（也不会把方块预测拆掉），服务端 cancel 掉这次
                // START_DESTROY_BLOCK。Fabric 在 SUCCESS 时会替客户端把这次点击发给服务端，见类注释。
                return ActionResult.SUCCESS;
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] left-click unbind unavailable", t);
        }

        try {
            PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
                if (!(world instanceof ServerWorld server) || pos == null || state == null) {
                    return;
                }
                try {
                    onBroken(server, pos, state);
                } catch (Throwable t) {
                    // 解绑失败只是留一条悬空记录，绝不影响拆方块本身。
                    LteLog.LOGGER.debug("[lte] auto-unbind on break failed at {}", pos, t);
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] auto-unbind on break unavailable", t);
        }
    }

    // ---------------------------------------------------------------- 左键

    /** 这一格归不归手里这个连接器管（三个连接器各有一套判定，见各自的 {@code isUnbindTarget}）。 */
    private static boolean owns(final Item item, final World world, final BlockPos pos) {
        if (item instanceof SignalLinkItem) {
            return SignalLinkItem.isUnbindTarget(world, pos);
        }
        if (item instanceof ProjectorConnectorItem) {
            return ProjectorConnectorItem.isUnbindTarget(world, pos);
        }
        if (item instanceof PidsConnectorItem) {
            return PidsConnectorItem.isUnbindTarget(world, pos);
        }
        return false;
    }

    /**
     * 服务端：按「手里拿的是哪个连接器」解绑这一格，提示由各连接器自己发
     * （绿字「已解绑」/ 黄字「这里没有绑定」）。
     */
    private static void onClick(final ServerPlayerEntity player, final BlockPos pos) {
        final World world = player.getWorld();
        if (world.isClient) {
            return;
        }
        final Item item = player.getMainHandStack().getItem();
        if (item instanceof SignalLinkItem) {
            SignalLinkItem.unlinkAt(world, player, pos);
        } else if (item instanceof ProjectorConnectorItem) {
            ProjectorConnectorItem.unlinkAt(world, player, pos);
        } else if (item instanceof PidsConnectorItem) {
            PidsConnectorItem.unlinkAt(world, player, pos);
        }
    }

    // ---------------------------------------------------------------- 方块被拆

    /**
     * 方块已经被拆掉（世界和方块实体都已经没了）：把这一格上的绑定清掉。
     *
     * <p>三种表各自判「这一格是不是我的」—— 互不干扰：拆一盏灯不会碰 PIDS 表，拆一块屏不会碰信号表。
     */
    private static void onBroken(final ServerWorld world, final BlockPos pos, final BlockState state) {
        SignalLinkItem.unlinkBroken(world, pos, state);
        ProjectorConnectorItem.unlinkBroken(world, pos);
        // 双格 PIDS：拆下半时数据格（表里的键）在上面那一格，而这时 HALF 已经读不到了，只能从
        // 「拆之前」的 state 拿。
        PidsConnectorItem.unlinkBroken(world, pos, isLowerHalf(state));
    }

    /** 被拆的方块是不是双格方块的<b>下半</b>（{@code IBlock.HALF}，MTR 自己的属性）。 */
    private static boolean isLowerHalf(final BlockState state) {
        try {
            return state.contains(IBlock.HALF)
                    && IBlock.getStatePropertySafe(state, IBlock.HALF) == DoubleBlockHalf.LOWER;
        } catch (Throwable t) {
            // 没有 HALF 属性（单格屏 / 普通方块）—— 不是下半。
            return false;
        }
    }
}
