package dev.js.mtrheadlight.bind;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端：玩家<b>进入跟踪范围</b>时，把绑定过的 PIDS / 投影机再推一次。
 *
 * <h2>为什么需要</h2>
 * <p>绑定那一刻的推送（{@link MtrTiles#pushToClients}）只覆盖「当时在 64 格内的玩家」。
 * {@code setData} 自己的 {@code syncData()} 走的是 {@code ServerChunkManager#markForUpdate}
 * ——「下个 tick 广播给<b>正在跟踪该 chunk</b> 的玩家」，同样不保证把人补齐。于是有两种漏法：
 * <ul>
 *   <li>绑定时玩家不在附近（别人绑的、或者自己绑完走开了）；</li>
 *   <li>玩家走远 → 区块卸载 → 再回来时区块重新加载。按设计客户端会从 chunk 数据里读到
 *       {@code platform_ids}，但如果那份 chunk 数据是推送之前发出的（或者被别的模组改过，
 *       或者服务端 BE 与落盘数据不一致），客户端拿到的就是一份「没绑过」的方块实体 ——
 *       屏幕立刻变成自动模式 / 空屏，看起来就是「熄屏」。</li>
 * </ul>
 *
 * <h2>怎么做</h2>
 * 每 {@value #INTERVAL_TICKS} tick 扫一遍绑定表（很小），对每块屏维护「现在在 64 格内的玩家」
 * 集合；<b>新进集合的玩家</b>触发一次重推。离开的人从集合里删掉，下次再进来又会推一次。
 *
 * <p>只做补数据，不改任何渲染逻辑；{@code MtrTiles#pushToClients} 失败（方块实体不在、类型不对）
 * 就静默跳过，下一次 tick 再看。
 */
public final class DisplayPushTicker {

    /** 与 {@link MtrTiles} 的绑定推送半径一致。 */
    private static final double RADIUS = 64.0D;
    private static final double RADIUS_SQ = RADIUS * RADIUS;
    private static final int INTERVAL_TICKS = 20;

    /** 维度 -> (屏位置 -> 当前算在范围内的玩家)。 */
    private static final Map<net.minecraft.util.registry.RegistryKey<World>, Map<BlockPos, Set<UUID>>> IN_RANGE =
            new HashMap<>();

    private static int counter;

    private DisplayPushTicker() {
    }

    /** 由 {@code LteServer} 的 {@code END_SERVER_TICK} 调用。 */
    public static void tick(final MinecraftServer server) {
        if (server == null) {
            return;
        }
        counter++;
        if (counter % INTERVAL_TICKS != 0) {
            return;
        }
        for (final ServerWorld world : server.getWorlds()) {
            try {
                tickWorld(world);
            } catch (Throwable t) {
                // 推送只是补数据，绝不影响服务端 tick。
            }
        }
    }

    private static void tickWorld(final ServerWorld world) {
        final Set<BlockPos> bound = new LinkedHashSet<>();
        try {
            bound.addAll(PidsLinkStore.get(world).links().keySet());
        } catch (Throwable ignored) {
            // 读不出来就先只处理投影机。
        }
        try {
            bound.addAll(ProjectorLinkStore.get(world).links().keySet());
        } catch (Throwable ignored) {
            // 同上。
        }

        final Map<BlockPos, Set<UUID>> seen =
                IN_RANGE.computeIfAbsent(world.getRegistryKey(), key -> new HashMap<>());
        // 解绑 / 换存档之后别留着旧位置的记录。
        seen.keySet().retainAll(bound);
        if (bound.isEmpty()) {
            return;
        }

        final java.util.List<ServerPlayerEntity> players = world.getPlayers();
        for (final BlockPos pos : bound) {
            final Object tile = MtrTiles.tileAt(world, pos);
            if (tile == null) {
                // 区块没加载：不记状态，等它加载回来时重新算一遍。
                seen.remove(pos);
                continue;
            }
            Set<UUID> before = seen.get(pos);
            if (before == null) {
                before = java.util.Collections.emptySet();
            }
            final Set<UUID> now = new HashSet<>();
            boolean entered = false;
            for (int i = 0; i < players.size(); i++) {
                final PlayerEntity player = players.get(i);
                if (squaredDistanceXZ(player, pos) > RADIUS_SQ) {
                    continue;
                }
                now.add(player.getUuid());
                if (!before.contains(player.getUuid())) {
                    entered = true;
                }
            }
            if (entered && MtrTiles.pushToClients(world, tile, pos)) {
                LteLog.LOGGER.info("[lte] pids push: pos={} re-sent to {} player(s) entering range",
                        pos, Integer.valueOf(now.size()));
            }
            seen.put(pos, now);
        }
    }

    private static double squaredDistanceXZ(final PlayerEntity player, final BlockPos pos) {
        final double dx = player.getX() - (pos.getX() + 0.5D);
        final double dz = player.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }
}
