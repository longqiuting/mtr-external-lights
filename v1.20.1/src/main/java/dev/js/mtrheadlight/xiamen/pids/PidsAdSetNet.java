// 「全局广告集」的通道 mtrlte:pids_ad_set：快照（S2C）/ 保存（C2S）。
package dev.js.mtrheadlight.xiamen.pids;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 全局广告集的通道，做法和 {@code ClockGlobalNet}（「全局配置 → 时钟全局设置」）一模一样：
 *
 * <ul>
 *   <li>{@link #KIND_SNAPSHOT} <b>S2C</b> —— 服务端把「开关 + 整份有序图片表」推下来，
 *       客户端写进 {@link PidsAdSetCache}；</li>
 *   <li>{@link #KIND_SAVE} <b>C2S</b> —— 仪表板里点一下（开关 / 加 / 删 / 清空）就整份写回来，
 *       要求权限等级 ≥ {@value #MIN_PERMISSION}。</li>
 * </ul>
 *
 * <h2>什么时候推快照</h2>
 * <ol>
 *   <li>玩家进服（{@link ServerPlayConnectionEvents.JOIN}）；</li>
 *   <li>每次保存成功之后广播给所有人（{@link #applySave}）。</li>
 * </ol>
 *
 * <h2>为什么不用方块实体同步</h2>
 * <p>这是<b>世界级</b>数据，没有对应的方块实体 —— 所以不适用
 * {@code toUpdatePacket} / {@code toInitialChunkDataNbt} / {@code updateListeners} 那一套；
 * 走的是和全局时钟配置同一个「PersistentState + 快照包」的路（见 {@link PidsAdSetState}）。
 * 每块屏的广告分类 / 轮播起点仍然存在各自的方块实体里，那条路本来就靠
 * {@code syncData()}（MTR 的 {@code BlockEntityClientSerializableMapper}）同步。
 *
 * <h2>权限</h2>
 * <p>改全局 = 改所有屏上的广告，所以只认权限等级 ≥ 2（OP / 命令方块级）；
 * <b>单人 / 局域网房主例外</b>（{@code !server.isDedicated()}）—— 那个「全局」就是他自己的世界，
 * 没开作弊的单人存档也应该能配。拒绝时<b>回一份快照</b>，让被拒绝的客户端把乐观改过的界面
 * 立刻改回服务端的值。
 */
public final class PidsAdSetNet {

    /** 通道 id。 */
    public static final Identifier ID = new Identifier(PidsXiamenObjects.NAMESPACE, "pids_ad_set");

    /** S2C：快照（enabled + 整份有序表）。 */
    public static final byte KIND_SNAPSHOT = 0;
    /** C2S：保存（enabled + 整份有序表）。 */
    public static final byte KIND_SAVE = 1;

    /** 改全局广告集需要的最低权限等级（2 = OP / 命令方块允许的等级）。 */
    public static final int MIN_PERMISSION = 2;

    private static boolean registered;

    private PidsAdSetNet() {
    }

    /** 服务端接收器 + 进服快照。由 {@code LteServer} 调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        try {
            ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, sender) -> {
                final byte kind;
                final PidsAdSet set;
                try {
                    kind = buf.readByte();
                    if (kind != KIND_SAVE) {
                        return; // 客户端只会发保存，别的类型不认。
                    }
                    set = PidsAdSet.readBuf(buf);
                } catch (Throwable bad) {
                    return; // 坏包：当没收到。
                }
                server.execute(() -> applySave(server, player, set));
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids ad set channel unavailable", t);
        }

        try {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                try {
                    send(handler.player, server);
                } catch (Throwable ignored) {
                    // 快照发不出去只是这一轮用回原行为，进服照常。
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the xiamen pids ad set join sync", t);
        }
    }

    // ---------------------------------------------------------------- S2C

    /** 打包一份快照。 */
    public static PacketByteBuf snapshotPacket(final PidsAdSet set) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_SNAPSHOT);
        (set == null ? new PidsAdSet() : set).writeBuf(buf);
        return buf;
    }

    /** 推一份快照给一个玩家。 */
    public static void send(final ServerPlayerEntity player, final MinecraftServer server) {
        if (player == null || !ServerPlayNetworking.canSend(player, ID)) {
            return;
        }
        final PidsAdSet set = PidsAdSetState.get(server).get();
        ServerPlayNetworking.send(player, ID, snapshotPacket(set));
    }

    /** 配置变了：推给所有装了 LTE 的玩家（全局配置就一份，无关维度）。 */
    public static void broadcast(final MinecraftServer server, final PidsAdSet set) {
        if (server == null) {
            return;
        }
        final PidsAdSet copy = set == null ? new PidsAdSet() : set.copy();
        for (final ServerPlayerEntity player : PlayerLookup.all(server)) {
            if (ServerPlayNetworking.canSend(player, ID)) {
                ServerPlayNetworking.send(player, ID, snapshotPacket(copy));
            }
        }
    }

    // ---------------------------------------------------------------- 写

    /** 打包 C2S「保存」（客户端调）。 */
    public static PacketByteBuf savePacket(final PidsAdSet set) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_SAVE);
        (set == null ? new PidsAdSet() : set).writeBuf(buf);
        return buf;
    }

    /**
     * 服务端：校验权限 → 落盘 → 广播。
     *
     * <p>先判权限再碰存档：不满足权限时配置一个字都不动，并且回一份快照把客户端界面改回去。
     */
    private static void applySave(final MinecraftServer server, final ServerPlayerEntity player,
                                  final PidsAdSet set) {
        if (server == null || player == null || set == null) {
            return;
        }
        final boolean allowed = player.hasPermissionLevel(MIN_PERMISSION)
                || (server != null && !server.isDedicated());
        if (!allowed) {
            player.sendMessage(Text.translatable("screen.mtrlte.pids_ad_set.denied"), false);
            send(player, server);
            LteLog.LOGGER.info("[lte] xiamen pids ad set save refused for {}", player.getEntityName());
            return;
        }
        PidsAdSetState.get(server).set(set);
        broadcast(server, set);
        LteLog.LOGGER.debug("[lte] xiamen pids ad set saved: enabled={} count={}",
                Boolean.valueOf(set.enabled()), Integer.valueOf(set.size()));
    }
}
