package dev.js.mtrheadlight.bind;

import dev.js.mtrheadlight.LteLog;
import mtr.data.DataCache;
import mtr.data.RailwayData;
import mtr.data.Station;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 客户端：一块到站屏（MTR PIDS / 天津地铁 PIDS / JCM PIDS / 到站投影机）<b>这一帧该按哪个车站出到站</b>
 * 的唯一判定。
 *
 * <h2>唯一的输入是「这一格自己的方块实体」</h2>
 * <p>{@link #resolve} 只做三件事：
 * <ol>
 *   <li>{@code ids = 这一格（pos）上的方块实体的 platformIds} —— 通过
 *       {@code world.getBlockEntity(pos)} 现读，<b>不经过任何「上次绑定 / 上次渲染」的缓存</b>；</li>
 *   <li>{@code inside = RailwayData.getStation(stations, dataCache, pos)} —— MTR 原来的判定（按位置）；</li>
 *   <li>两者结合，见下表。</li>
 * </ol>
 *
 * <table border="1">
 *   <caption>判定表（{@code ids} = 这块屏自己的 platformIds）</caption>
 *   <tr><th>{@code ids}</th><th>{@code inside}</th><th>结果</th><th>{@code src}</th></tr>
 *   <tr><td>空（没绑过）</td><td>任意</td><td>{@code inside} —— <b>完全不干预，自动模式</b></td><td>{@code mtr}</td></tr>
 *   <tr><td>有，反查出 B</td><td>null（站在站外）</td><td>B</td><td>{@code be}</td></tr>
 *   <tr><td>有，反查出 B</td><td>A，且 ids 里没有一个属于 A</td><td>B（否则 MTR 拿 A 的站台表过滤，必然空屏）</td><td>{@code be}</td></tr>
 *   <tr><td>有，反查出 B</td><td>A，且 ids 里有属于 A 的</td><td>A（混绑，保持 MTR 行为）</td><td>{@code be}</td></tr>
 * </table>
 *
 * <h2>为什么这张表能保证「新放的屏不会被别的屏带跑」</h2>
 * <p>新放的屏 {@code ids} 一定是空的（MTR 的 {@code TileEntityPIDS} 里 {@code platformIds} 是
 * <b>实例字段</b>，javap：{@code private final Set<Long> platformIds}，没有任何 static 版本），
 * 于是第一行走 {@code src=mtr}，结果只取决于这块屏自己站在哪。要有非空 ids，只能是有人对
 * <b>这一格</b>调过 {@code setData(...)}（我们的连接器，或 MTR 自己的 PIDS 界面）。
 *
 * <p>{@link PidsStationIndex} 的粘性缓存键是 <b>platformId</b>（站台 → 车站是全局事实，
 * 与屏无关），不是屏；这里不引入任何以「上次绑定的屏」为键的东西。
 *
 * <p>只在客户端渲染线程使用，所以不加锁。任何异常都退回 MTR 的原答案，绝不因为这里抛异常而整块屏不画。
 */
public final class PidsStationResolver {

    /** 答案来自这块屏自己的 platformIds。 */
    public static final String SRC_BE = "be";
    /** 答案来自 MTR 自己的「按方块位置找车站」（也就是没绑过时的自动模式）。 */
    public static final String SRC_MTR = "mtr";

    /** 一次判定的结果。 */
    public record Result(Station station, String src, Set<Long> platformIds) {
    }

    /** 日志：{@code tag@pos -> 上一次打出去的那一行}（只在变化时打）。 */
    private static final Map<String, String> LAST_LINE = new HashMap<>();
    private static final int LOG_LIMIT = 512;
    /** 换世界（换存档 / 换维度）时把站台索引清掉：站台 id 只在同一份 MTR 数据里成立。 */
    private static ClientWorld lastWorld;

    private PidsStationResolver() {
    }

    /**
     * 这块屏这一帧该按哪个车站出到站。
     *
     * @param stations  MTR 传进 {@code getSchedules} 的车站集合（{@code ClientData.STATIONS}）
     * @param dataCache MTR 传进来的 {@code DataCache}，可以为 {@code null}
     * @param pos       屏自己的方块位置（{@code RenderPIDS.getSchedules} 的第二个参数）
     */
    public static Result resolve(final Set<Station> stations, final DataCache dataCache,
                                 final BlockPos pos) {
        lte$resetIfWorldChanged();
        if (pos == null) {
            return new Result(null, SRC_MTR, Collections.emptySet());
        }
        final Station inside = RailwayData.getStation(stations, dataCache, pos);
        final Set<Long> ids = lte$idsAt(pos);
        if (ids.isEmpty()) {
            // 没绑过：一个字都不改，交给 MTR 的自动模式（就近站台）。
            return new Result(inside, SRC_MTR, ids);
        }
        final Station bound = PidsStationIndex.firstResolvable(dataCache, ids);
        if (bound == null) {
            // 绑了，但站台还反查不出来（客户端数据没到）：先按 MTR 的答案，下一帧再试。
            return new Result(inside, SRC_MTR, ids);
        }
        if (inside == null) {
            return new Result(bound, SRC_BE, ids);
        }
        if (bound.id == inside.id) {
            return new Result(inside, SRC_BE, ids);
        }
        if (lte$anyBelongsTo(dataCache, ids, inside)) {
            // 混绑：有站台属于「屏所在的车站」，保持 MTR 的行为（那一站的站台照常显示）。
            return new Result(inside, SRC_BE, ids);
        }
        // 屏在 A 站里，绑的全是 B 站的站台：MTR 会拿 A 的站台列表过滤，必然空屏 → 改成 B。
        return new Result(bound, SRC_BE, ids);
    }

    /** {@code ids} 里有没有站台属于 {@code station}。 */
    private static boolean lte$anyBelongsTo(final DataCache dataCache, final Set<Long> ids,
                                            final Station station) {
        for (final Long id : ids) {
            if (id == null) {
                continue;
            }
            final Station owner = PidsStationIndex.stationOf(dataCache, id.longValue());
            if (owner != null && owner.id == station.id) {
                return true;
            }
        }
        return false;
    }

    /** <b>这一格</b>方块实体里的 platformIds（读一份拷贝）；不是屏 / 没有方块实体 → 空集。 */
    private static Set<Long> lte$idsAt(final BlockPos pos) {
        try {
            final ClientWorld world = MinecraftClient.getInstance().world;
            if (world == null) {
                return Collections.emptySet();
            }
            final Object tile = world.getBlockEntity(pos);
            if (tile == null) {
                return Collections.emptySet();
            }
            if (MtrTiles.isProjector(tile)) {
                return new LinkedHashSet<>(MtrTiles.projectorPlatformIds(tile));
            }
            if (MtrTiles.isPids(tile)) {
                return new LinkedHashSet<>(MtrTiles.pidsPlatformIds(tile));
            }
        } catch (Throwable ignored) {
            // 渲染线程上读方块实体失败（区块刚好在换）：这一帧当「没绑过」处理。
        }
        return Collections.emptySet();
    }

    private static void lte$resetIfWorldChanged() {
        try {
            final ClientWorld world = MinecraftClient.getInstance().world;
            if (world != lastWorld) {
                lastWorld = world;
                PidsStationIndex.reset();
                LAST_LINE.clear();
            }
        } catch (Throwable ignored) {
            // 极端情况下（初始化中）拿不到客户端：不清就不清，下一次再清。
        }
    }

    /**
     * 只在<b>变化</b>时打一行：
     * <pre>{@code [lte] pids station: pos=... be=[...] -> station=<id/名字|null> src=be|mtr}</pre>
     *
     * <p>这条日志就是「新放的屏到底读了谁」的答案：{@code be=[]} + {@code src=mtr} = 没绑过、走自动模式；
     * {@code be=[...]} + {@code src=be} = 按这块屏自己的 platformIds 出的站。两块屏打出同一个
     * {@code be}，就说明<b>写入端</b>把它们写成了一样（读的这一侧每次只看 {@code pos} 那一格）。
     */
    public static void log(final String tag, final BlockPos pos, final Result result) {
        final Set<Long> ids = result.platformIds();
        final String idsText = ids.isEmpty() ? "[]" : new TreeSet<>(ids).toString();
        final Station station = result.station();
        final String stationText = station == null ? "null" : (station.id + "/" + station.name);
        final String line = "pos=" + pos + " be=" + idsText + " -> station=" + stationText
                + " src=" + result.src();
        final String key = tag + "@" + pos;
        if (line.equals(LAST_LINE.get(key))) {
            return;
        }
        if (LAST_LINE.size() > LOG_LIMIT) {
            LAST_LINE.clear();
        }
        LAST_LINE.put(key, line);
        LteLog.LOGGER.info("[lte] pids station: {}", line);
    }
}
