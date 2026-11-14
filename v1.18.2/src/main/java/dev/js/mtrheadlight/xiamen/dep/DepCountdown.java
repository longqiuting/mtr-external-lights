// 发车倒数器的数值链路（挂墙 / 挂顶共用）：找站台 → 锁一条班次 → 算 mm:ss。
package dev.js.mtrheadlight.xiamen.dep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.ScheduleEntry;
import net.minecraft.util.math.BlockPos;

/**
 * 发车倒数器的<b>数值链路</b>，{@code dep_on_wall}（挂墙）与 {@code dep_on_roof}（挂顶）共用。
 *
 * <h2>为什么单独抽一个类</h2>
 * <p>两块牌子长得不一样（一块钉墙、一块吊顶），但<b>屏幕上是同一个东西</b>：一枚 96×64 的
 * 七段数字，读的也是同一套数据。如果各自抄一份「找站台 → 排序 → 锁一条 → 算秒数」，两份迟早会
 * 走岔（改了一边忘了另一边，症状就是「挂墙的倒数对了、挂顶的还在 2 变 5」）。
 * 所以数据链路只在这里写一遍，两个渲染器各自只负责<b>把 {@link Display} 画到自己那块屏上</b>。
 *
 * <h2>数值公式：JCM 的 {@code jsblock.render.RenderDepartureTimer}，一字不改</h2>
 * <p><b>不读列车。</b>不碰 {@code ClientData.TRAINS}、不看 {@code elapsedDwellTicks}、
 * 不看 {@code path}：
 * <pre>
 * 1. RailwayData.getClosePlatformId(ClientData.PLATFORMS, DATA_CACHE, pos, 5, 3, 3)
 *       → 离这块牌子最近的站台 id（0 = 附近没有站台 → 空屏）
 *       // 后三个数是「搜索半径 / x·z 外扩 / y 外扩」，按牌子种类取：挂墙 5/3/3（JCM 原值），
 *       // 挂顶 7/4/5（吊在天花板上比站台面高好几格，垂直余量必须更宽，见 WALL_SEARCH / ROOF_SEARCH）
 * 2. ClientData.SCHEDULES_FOR_PLATFORM.get(platformId)   // null = 没数据
 * 3. new ArrayList&lt;&gt;(entries) + Collections.sort         // 按 arrivalMillis（同刻再按 routeId）
 * 4. entry = 排序后第一条「还没走完」的班次            // 见下面「锁」
 * 5. arrivalMillis 还在未来 → 空屏（车还没到站，不是显示 0:00）
 * 6. 否则（JCM 的原式，一字不改）：
 *       已过秒 = (arrivalMillis - now) / 1000             // ≤ 0；先减再 /1000，向零截断
 *       dwellHalf = platform == null ? 0 : platform.getDwellTime() / 2   // 整数除法
 *       剩余   = |dwellHalf - |已过秒||
 * </pre>
 * <p>{@code dwell / 2} 是 <b>整数除法</b>，{@code Platform} 为 null 时 JCM 直接拿 0 当基准
 * （这里照抄，没有再垫一个「最少 1 秒」的下限）。{@code arrivalMillis} 是<b>到站</b>时刻 ——
 * JCM 的经验值 dwell/2 恰好就是 MTR 的实际停站时长（{@code Train#getTotalDwellTicks()} =
 * {@code dwellTime * 10} tick，而 20 tick = 1 秒），所以 0:00 落在发车点上。
 * <p>式子外面套了一层 {@code abs}，于是「到点之后」的数字会自己往回长（0 → 1 → 2 …），
 * 这个值本身就是「已经晚了几秒」，红字那一档直接用它，不需要另开一套计时。
 *
 * <h2>「锁一条」：唯一和 JCM 不一样的地方（也是「2 突然变 5」的修法）</h2>
 * <p>JCM 第 4 步是每帧现取 {@code list.get(0)}。MTR 这张表是服务端<b>周期性整表推送</b>的
 * （{@code updateSchedule} → {@code clearAndAddAll}），每次推送都可能重算 {@code arrivalMillis}、
 * 删掉刚发车的条目、或整个换掉集合 —— 于是倒数途中「第 1 条」会换人：绿字数到 2，
 * 推送把到站时刻往后挪了 3 秒，{@code |已过秒|} 变小，屏幕就<b>弹回 5</b> 接着数。
 *
 * <p>所以「用哪一条」锁在方块实体上（{@link Holder#depLock()}，类型 {@link DepOnWallLock}）：
 * <ul>
 *   <li>锁定键是 {@code arrivalMillis + routeId}（和 {@code ScheduleEntry#compareTo} 的排序键一致）；</li>
 *   <li>{@code dwellHalf} 一起冻在锁里 —— 站台数据被重推也不会换基准；</li>
 *   <li><b>一旦开始倒数就不换人</b>，服务端怎么推都只数这一条，直到它自己走完；</li>
 *   <li>屏幕还空着（车没到站）时没有可见读数，这时才允许跟着新的第一条改锁；</li>
 *   <li>选班时跳过已经过了显示窗口的条目，所以「刚放锁那条」不会被重新选回来。</li>
 * </ul>
 * <p>单调钳制（绿段只减不增、红段只增不减、开过车不回空屏）在 {@link DepOnWallLock#tick} 里，
 * 时钟倒退时读数是「停住」而不是往回跳。
 *
 * <p>{@code /mtr_lte dep debug on} 之后每块牌子每秒打一行 {@code [lte] dep: …}
 * （{@link DepOnWallDebug}），把这一帧的 {@code entries / firstArrival / sec / dwell /
 * lockArrival} 原样写出来 —— 再出「2 变 5」能直接看出是哪一环变了。
 */
final class DepCountdown {

    /**
     * 挂墙版的找站台范围：{@code (搜索半径, 包围盒外扩x/z, 外扩y)} = {@code (5, 3, 3)}，
     * <b>和 JCM 的 {@code RenderDepartureTimer} 完全一致</b>。
     */
    private static final int SEARCH_RADIUS = 5;
    private static final int SEARCH_MARGIN = 3;

    /**
     * 一次「找站台」用的搜索范围（{@link RailwayData#getClosePlatformId} 的后三个参数）。
     *
     * @param radius           搜索半径（格）
     * @param horizontalMargin 站台包围盒在 x / z 上再外扩多少格
     * @param verticalMargin   站台包围盒在 y 上再外扩多少格
     */
    record Search(int radius, int horizontalMargin, int verticalMargin) {
    }

    /**
     * 挂墙版：JCM 原值 {@code (5, 3, 3)}。
     *
     * <p>牌子钉在站台头端<b>齐平台面</b>的墙上，离站台包围盒本来就只有一两格，
     * {@code 3} 格的垂直余量够用。
     */
    static final Search WALL_SEARCH = new Search(SEARCH_RADIUS, SEARCH_MARGIN, SEARCH_MARGIN);

    /**
     * 挂顶版：{@code (7, 4, 5)} —— 比挂墙版<b>每个方向都放宽一点</b>，垂直多给 2 格。
     *
     * <p>挂在天花板上时，牌子本身在楼板下面（{@code y} 比站台面高好几格），
     * 而站台包围盒是薄薄一片贴在地面上的，所以最容易卡住的是<b>垂直</b>那一维：
     * 只给 3 格时「天花板下 4~5 格」的位置就找不到站台，屏幕永远空着（这就是本次要修的症状）。
     * 水平方向顺带放宽 1 格（半径 5→7、x/z 外扩 3→4），免得牌子挂在站台边缘上方时也落空。
     * 放得再宽就有风险认到隔壁站台 —— 7 / 4 / 5 是这个权衡下的取值。
     */
    static final Search ROOF_SEARCH = new Search(7, 4, 5);

    private DepCountdown() {
    }

    /**
     * 一帧里算出来的内容：已经格式化好的 {@code mm:ss} + 是不是红灯那一档。
     * <p>颜色只取决于 {@code overdue}，不按剩余秒数分档 —— 绿就是绿，红就是红。
     */
    record Display(String text, boolean overdue) {
    }

    /**
     * 「身上能挂一把班次锁」的东西 —— 目前是两个方块实体（挂墙 / 挂顶）。
     *
     * <p>只要求这两件事，是因为数值链路唯一需要的状态就是那把锁：
     * 它天然跟着方块（区块卸载 / 方块被拆，状态一起没），不需要任何失效清理，
     * 也不落 NBT（{@code /setblock} 换位置之后下一帧重新锁一条即可）。
     */
    interface Holder {

        /** 当前锁定的班次（{@code null} = 没有）。 */
        DepOnWallLock depLock();

        /** 换一把锁；传 {@code null} 就是放锁。 */
        void setDepLock(DepOnWallLock lock);

        /**
         * 这块牌子「找站台」用的搜索范围。
         *
         * <p>默认是挂墙版（{@link #WALL_SEARCH}，JCM 原值）；挂顶版在
         * {@code DepOnRoofBlockEntity} 里覆盖成 {@link #ROOF_SEARCH} ——
         * 吊在天花板上比贴墙高好几格，垂直余量要宽一些（见 {@link #ROOF_SEARCH} 的说明）。
         */
        default Search search() {
            return WALL_SEARCH;
        }
    }

    /**
     * 算出这块牌子这一帧该显示什么；{@code null} = 空屏。
     *
     * @param holder 挂着锁的方块实体（{@code dep_on_wall} / {@code dep_on_roof}）
     * @param pos    方块坐标（找站台用）
     */
    static Display display(final Holder holder, final BlockPos pos) {
        final long now = System.currentTimeMillis();

        // 取证用的现场（不管走哪个分支都要能打出来）。
        long platformId = 0L;
        int entries = 0;
        ScheduleEntry head = null;
        Long sec = null;
        int dwellRaw = 0;
        int dwellHalf = 0;
        Integer remain = null;
        String phase = "blank";
        Long lockArrival = null;
        Display result = null;

        try {
            // 搜索范围按牌子种类走：挂墙 5/3/3（JCM 原值），挂顶 7/4/5（见 ROOF_SEARCH）。
            final Search search = holder.search();
            platformId = RailwayData.getClosePlatformId(
                    ClientData.PLATFORMS, ClientData.DATA_CACHE, pos,
                    search.radius(), search.horizontalMargin(), search.verticalMargin());
            if (platformId == 0L) {
                // 附近没站台：牌子旁边什么也没有，把锁也放掉。
                holder.setDepLock(null);
            } else {
                final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformId);
                // 整数除法，dwell 20 → 10。Platform 缺数据时 JCM 用 0，照抄。
                dwellRaw = platform == null ? 0 : platform.getDwellTime();
                dwellHalf = dwellRaw / 2;

                final List<ScheduleEntry> list = schedules(platformId);
                entries = list == null ? 0 : list.size();
                // 第一条「还没走完」的班次 = 这一帧服务端认为的下一班。
                head = firstLive(list, dwellHalf, now);

                DepOnWallLock lock = holder.depLock();
                if (lock != null && (!lock.samePlatform(platformId) || lock.finished(now))) {
                    // 牌子被挪到了别的站台旁边，或者锁着的那条早就走完了：放锁重选。
                    lock = null;
                }
                if (lock != null && !lock.started() && head != null && !lock.matches(head)) {
                    // 还没开始倒数（屏幕是空的）→ 换锁不会有可见跳变，跟着新的一条走。
                    lock = null;
                }
                if (lock == null && head != null) {
                    // 锁定：dwellHalf 一起冻在锁里，之后站台数据被重推也不动基准。
                    lock = DepOnWallLock.of(platformId, head, dwellHalf);
                }

                if (lock != null) {
                    lockArrival = Long.valueOf(lock.arrivalMillis());
                    sec = Long.valueOf((lock.arrivalMillis() - now) / 1000L);
                    final DepOnWallLock.Reading reading = lock.tick(now);
                    if (reading != null) {
                        remain = Integer.valueOf(reading.remain());
                        phase = reading.overdue() ? "red" : "green";
                        result = new Display(format(reading.remain()), reading.overdue());
                    }
                    if (lock.done()) {
                        // 红灯 5 秒数完了：放锁，下一帧重新选班（这条已经被 finished 过滤掉）。
                        lock = null;
                    }
                }
                holder.setDepLock(lock);
            }
        } catch (Throwable t) {
            // 数据没同步好（刚进服 / 站台被删）不该让整块方块不画。
            result = null;
        }

        if (DepOnWallDebug.enabled()) {
            DepOnWallDebug.log(pos, now, "[lte] dep: pos=(" + pos.getX() + "," + pos.getY() + ","
                    + pos.getZ() + ") platform=" + platformId
                    + " entries=" + entries
                    + " firstArrival=" + (head == null ? "none" : Long.toString(head.arrivalMillis))
                    + " now=" + now
                    + " sec=" + (sec == null ? "none" : sec.toString())
                    + " dwell=" + dwellRaw
                    + " dwellHalf=" + dwellHalf
                    + " remain=" + (remain == null ? "none" : remain.toString())
                    + " phase=" + phase
                    + " lockArrival=" + (lockArrival == null ? "none" : lockArrival.toString()));
        }
        return result;
    }

    /**
     * 这一帧的 {@code SCHEDULES_FOR_PLATFORM}（排序后的副本）；平台没有表 = {@code null}。
     *
     * <p>表是服务端<b>整表替换</b>推过来的（{@code clearAndAddAll}），所以这里每帧重新拿、
     * 重新拷一份再排序，绝不缓存 —— 缓存反而会让「换人了」这件事看不见。锁定与否由
     * {@link DepOnWallLock} 决定，不靠这份列表的稳定性。
     */
    private static List<ScheduleEntry> schedules(final long platformId) {
        final Set<ScheduleEntry> schedules = ClientData.SCHEDULES_FOR_PLATFORM.get(platformId);
        if (schedules == null) {
            return null;
        }
        final List<ScheduleEntry> list = new ArrayList<>(schedules);
        // JCM 先拷一份再排序（不改调用方那个集合）。
        Collections.sort(list);
        return list;
    }

    /**
     * 列表里第一条「还没走完」的班次；没有 = {@code null}（空屏）。
     *
     * <p>跳过已经过了显示窗口的条目，是为了让「刚放锁的那条」不会被重新选回来（否则放锁之后
     * 会立刻再锁定它、再立刻放锁，成了每帧一次的循环）。
     */
    private static ScheduleEntry firstLive(final List<ScheduleEntry> list, final int dwellHalf,
                                           final long now) {
        if (list == null) {
            return null;
        }
        for (final ScheduleEntry entry : list) {
            if (!DepOnWallLock.finished(entry.arrivalMillis, dwellHalf, now)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 秒 → {@code mm:ss}（不足两位补零）。
     * <p>分钟<b>不</b>折成小时：站台停站最多几分钟，{@code 12:05} 比 {@code 0:12:05} 好读；
     * 真要超过 99 分钟就让它自然变宽（{@code 120:00}），排版会自己缩。
     */
    private static String format(final long seconds) {
        final long s = Math.max(0L, seconds);
        return String.format("%02d:%02d", s / 60L, s % 60L);
    }
}
