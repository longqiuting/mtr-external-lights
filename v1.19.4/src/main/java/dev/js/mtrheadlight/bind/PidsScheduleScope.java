package dev.js.mtrheadlight.bind;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端：<b>把「绑定过的站台」加进该玩家的到站数据范围</b>。
 *
 * <h2>问题（这才是「站一会就自动熄屏」的真正原因）</h2>
 * <p>MTR 每 tick 在 {@code RailwayData#simulateTrains} 里给玩家发
 * {@code PACKET_UPDATE_SCHEDULE}，包里的站台是被<b>过滤过的</b>（javap：
 * {@code lambda$simulateTrains$7}）：
 * <pre>{@code
 * platform.isCloseToSavedRail(playerBlockPos, 16, 16, 16)                 // 玩家 16 格以内
 *     || (platformIdToStation.get(platform.id) instanceof Station s && s.inArea(playerX, playerZ))
 * }</pre>
 * 而客户端收到之后是 {@code ClientData.SCHEDULES_FOR_PLATFORM} <b>整表替换</b>
 * （{@code lambda$updateSchedule$17} → {@code clearAndAddAll(SCHEDULES_FOR_PLATFORM, newMap)}）。
 * 也就是说：<b>不在上面那两个条件里的站台，它的到站数据会在下一次包里被删掉</b>，而且
 * {@code PACKET_UPDATE_SCHEDULE} 每 60 tick 就会给每个玩家发一次
 * （{@code SCHEDULE_UPDATE_TICKS = 60}），玩家一动（{@code PLAYER_MOVE_UPDATE_THRESHOLD = 16}）还会更早发。
 *
 * <p>站外投影机 / 站外 PIDS 正好落在这两个条件外面（它本来就不在任何车站矩形里），
 * 于是：走近 → 下一个包把数据带来 → 亮；站在原地 → 3 秒内下一个包把数据删掉 → 灭；走开再回来 →
 * 又带一次 → 亮。这就是玩家描述的循环。详见 {@code docs/pids-render-refresh.md}。
 *
 * <h2>这里做什么</h2>
 * 维护一张 {@code platformId -> 绑定了它的显示屏位置} 的索引（从 {@link PidsLinkStore} 与
 * {@link ProjectorLinkStore} 的落盘表 + 方块实体里的真实 {@code platformIds} 生成），
 * 由 {@code PidsScheduleScopeMixin} 在 {@code isCloseToSavedRail} 返回 {@code false} 时问一句：
 * 「玩家是不是就站在某个绑定到该站台的屏幕旁边？」
 *
 * <p>MTR 里 {@code isCloseToSavedRail} 只有这一个调用点（{@code RailwayData} 的
 * {@code simulateTrains} 过滤 lambda），所以放宽它不会波及别处。
 *
 * <p>索引每 {@value #REBUILD_TICKS} tick 重建一次（绑定表很小），重建时读方块实体要在已加载的区块里，
 * 没加载就跳过 —— 下一次重建会补上。
 */
public final class PidsScheduleScope {

    /** 玩家离显示屏多近才算「他在看这块屏」。屏本身渲染半径 16 格，48 已经很宽。 */
    private static final double RANGE_BLOCKS = 48.0D;
    private static final double RANGE_SQ = RANGE_BLOCKS * RANGE_BLOCKS;

    /** 索引重建间隔（tick）。 */
    private static final int REBUILD_TICKS = 20;

    private static volatile Map<Long, List<BlockPos>> index = Collections.emptyMap();
    private static int counter;
    private static int lastEntries = -1;
    private static boolean warned;
    /** {@link #covers} 被问过多少次 —— 用来确认 mixin 到底有没有应用上。 */
    private static int asks;
    private static int unappliedRebuilds;
    private static boolean reportedUnapplied;

    private PidsScheduleScope() {
    }

    /** 由 {@code LteServer} 的 {@code END_SERVER_TICK} 调用。 */
    public static void tick(final MinecraftServer server) {
        if (server == null) {
            return;
        }
        counter++;
        if (counter % REBUILD_TICKS != 0) {
            return;
        }
        try {
            rebuild(server);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.warn("[lte] schedule scope index unavailable", t);
            }
        }
    }

    /**
     * 这个站台是不是「玩家身边某块绑定屏」的目标站台。
     *
     * @param platformId MTR 的站台 id
     * @param pos        玩家所在方块位置（MTR 过滤时用的就是它）
     */
    public static boolean covers(final long platformId, final BlockPos pos) {
        asks++;
        if (platformId == 0L || pos == null) {
            return false;
        }
        final List<BlockPos> displays = index.get(Long.valueOf(platformId));
        if (displays == null || displays.isEmpty()) {
            return false;
        }
        for (int i = 0; i < displays.size(); i++) {
            final BlockPos display = displays.get(i);
            final double dx = display.getX() - pos.getX();
            final double dz = display.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= RANGE_SQ) {
                return true;
            }
        }
        return false;
    }

    /** 索引里有几个站台（诊断用）。 */
    public static int size() {
        return index.size();
    }

    /** 某个站台绑了几块屏（诊断用）。 */
    public static int displaysFor(final long platformId) {
        final List<BlockPos> displays = index.get(Long.valueOf(platformId));
        return displays == null ? 0 : displays.size();
    }

    // ------------------------------------------------------------------ 重建

    private static void rebuild(final MinecraftServer server) {
        final Map<Long, List<BlockPos>> next = new HashMap<>();
        for (final ServerWorld world : server.getWorlds()) {
            // PIDS：落盘表里直接记着 platformId，不用碰方块实体。
            try {
                for (final Map.Entry<BlockPos, PidsLinkStore.Link> entry
                        : PidsLinkStore.get(world).links().entrySet()) {
                    add(next, entry.getValue().platformId(), entry.getKey());
                }
            } catch (Throwable ignored) {
                // 某个维度读不出来就跳过，别让整张索引失效。
            }
            // 投影机：落盘表只记了车站，真实站台要读方块实体（绑定动作写进 MTR 的那一份）。
            try {
                for (final BlockPos pos : ProjectorLinkStore.get(world).links().keySet()) {
                    if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                    final Object tile = MtrTiles.tileAt(world, pos);
                    if (tile == null || !MtrTiles.isProjector(tile)) {
                        continue;
                    }
                    for (final Long id : MtrTiles.projectorPlatformIds(tile)) {
                        if (id != null) {
                            add(next, id.longValue(), pos);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 同上。
            }
        }
        index = next;
        if (next.size() != lastEntries) {
            lastEntries = next.size();
            if (next.size() > 0) {
                LteLog.LOGGER.info("[lte] schedule scope: {} platform(s) covered by bound displays",
                        Integer.valueOf(next.size()));
            }
        }
        // 索引非空却从没被问过一次 → PidsScheduleScopeMixin 没应用上（MTR 版本换了 / 目标类改名）。
        // 这时行为退回 MTR 原版：站外显示屏还是会周期性熄灭，日志里必须说清楚。
        if (!reportedUnapplied && next.size() > 0 && asks == 0) {
            unappliedRebuilds++;
            if (unappliedRebuilds >= 5) {
                reportedUnapplied = true;
                LteLog.LOGGER.warn("[lte] schedule scope: bound displays exist but MTR never asked "
                        + "(isCloseToSavedRail mixin not applied?) - outside-station PIDS may blank periodically");
            }
        } else if (asks > 0) {
            unappliedRebuilds = 0;
        }
    }

    private static void add(final Map<Long, List<BlockPos>> into, final long platformId, final BlockPos pos) {
        if (platformId == 0L || pos == null) {
            return;
        }
        final Long key = Long.valueOf(platformId);
        List<BlockPos> list = into.get(key);
        if (list == null) {
            list = new ArrayList<>(2);
            into.put(key, list);
        }
        final BlockPos immutable = pos.toImmutable();
        if (!list.contains(immutable)) {
            list.add(immutable);
        }
    }
}
