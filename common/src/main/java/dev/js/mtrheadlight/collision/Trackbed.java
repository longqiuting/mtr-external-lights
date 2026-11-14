package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.LteLog;
import mtr.client.ClientData;
import net.minecraft.client.world.ClientWorld;

import java.util.Locale;

/**
 * 道床几何的<b>客户端门面</b>：把 {@code ClientData.RAILS} 喂给 {@link RailBed}，
 * 并把「这一 tick 的判定过程」记下来给 {@code /mtr_lte trackbed status} 读。
 *
 * <p>几何、缓存、横向列投影、弧长插值全在 {@link RailBed}（纯计算，服务端也用同一份，见
 * {@code server.ServerTrackbed}）。这里只剩三件事：客户端从哪取轨道表、这一 tick 的取证数据、
 * 以及 {@code /mtr_lte trackbed} 那一行诊断。
 *
 * <p>客户端只处理<b>本地玩家</b>：{@code Entity#move} 注入把人「踩」在轨面上。
 * 其它实体（掉落物、生物、下落的方块）由服务端的 {@code server.ServerTrackbed} 接住。
 *
 * <h2>模型：横向列 + 弧长插值，不看 onGround</h2>
 * 判定拆成两半，各自是一个纯函数，都能单测：
 * <ul>
 *   <li><b>横向</b>（{@link RailBed} 的列投影）：把人的水平位置投影到最近的轨道折线段上，
 *       得到有符号横向偏移 {@code offset = (q - p) · N}，{@code N = T × UP}。{@code |offset| ≤ }
 *       {@link #HALF_WIDTH} 就算踩在道床上 —— 用的是法向而不是包围盒，弯道内侧外侧同时正确。</li>
 *   <li><b>纵向</b>（{@link RailBed#verdict}）：脚底与轨面的高差 {@code dy} 是否落在
 *       {@code [-STEP_UP, +CEIL]} 里，再按「正在起跳 / 从上方穿过 / 贴着往下走」分档。
 *       轨面高度是对<b>投影点弧长参数</b> {@code s} 解析求出来的（{@link RailBed#railY}），
 *       所以坡道上「脚下这一点」永远是对的，坡度自然跟随，不需要任何 y 阈值。</li>
 * </ul>
 *
 * <h2>{@code /mtr_lte trackbed status}</h2>
 * 用户报「站在轨道上、上坡/过弯就掉下去」时，这一行就是答案所在：
 * <pre>[lte] trackbed: pos=(x, y, z) rails=N hit=yes|no y=… dist=… off=… halfW=… dy=… vy=…
 *                 onGround=… moved=… drop=snap|dy|lat|nohit|jump|block|space|rescue
 *                 snapped=true|false drops=snap:57 dy:0 lat:0 nohit:0 …</pre>
 * 读法：
 * <ul>
 *   <li>{@code hit=no} + {@code dist=-} → {@code drop=nohit}：缓存里根本没有近处的轨道（人真的不在
 *       轨道上，或者轨道表没加载）；</li>
 *   <li>{@code drop=lat} → 轨道找到了，横向偏移超了半宽（{@code off} 会给出数值和正负）；</li>
 *   <li>{@code drop=dy} → 横向踩在道床上，但脚底与轨面的高差不在窗口里（{@code dy} 会给出数值，
 *       负得很多 = 上坡的轨面在脚底之上，正得很多 = 人在轨面上方腾空）；</li>
 *   <li>{@code drop=jump} → 起跳那一 tick，故意让开，不算掉；</li>
 *   <li>{@code drop=rescue} → 常规判定让开了，但<b>下落安全网</b>把人接在轨面上（见下）；</li>
 *   <li>{@code drop=block} / {@code drop=space} → 命中了，被真实方块 / 空间不足挡掉（旧版
 *       「snapped=false 而 hit=yes」就是这个形态）。</li>
 * </ul>
 * {@code drops=} 是<b>自上一行诊断以来</b>各结论的计数（只统计「上一个 tick 还踩在道床上」的 tick，
 * 所以站在野地里不会刷 {@code nohit}），走一遍坡道就能从计数上直接读出是哪一种，不用猜。
 *
 * <h2>{@code /mtr_lte trackbed on} —— 逐 tick 一行</h2>
 * 上面那行是每 5 秒的汇总，回答不了「紧挨着弯道的那段直道，候选集里到底有没有它」。
 * 取证打开时每 tick 打一行：
 * <pre>[lte] trackbed tick: pos=(x, y, z) rails=N cand=N best=(dist, offset, railY)
 *                        dy=… sdy=… vy=… verdict=… onGround=… moved=…
 *                        cand[0]=(dist,+off,railY,minY..maxY,why) … more=N straightN=a/b
 *                        tbl=(dist,+off,railY,minY..maxY) straight=yes|no along=s/len</pre>
 * <ul>
 *   <li>{@code rails=} 整张轨道表的条数（每秒数一次）—— 它突然变成 0 就是轨道表没加载；</li>
 *   <li>{@code cand=} 这次查询的<b>候选条数</b>。站在直道上跑几秒，如果 {@code cand=1} 而
 *       {@code best} 是那条弯道，说明<b>直道那条根本没进候选</b>（缓存 / 准入的问题）；
 *       {@code cand=0} 则是周围一条都没有；</li>
 *   <li>{@code best=(dist, offset, railY)} 被选中的那条候选：横向距离、有符号偏移、它的轨面高度；
 *       {@code best=(-, -, -)} 就是「候选里没有一条够近的」；</li>
 *   <li>{@code dy} 脚底 − 道床面、{@code sdy} 同一 tick <b>起点</b>的高差、{@code vy} 竖直速度、
 *       {@code verdict} 这一 tick 的结论。{@code dy} 负得很多 + {@code verdict=dy} 就是「人已经掉到
 *       轨面以下」，{@code sdy} 由正变负的那一行就是「穿过去了」的那一 tick；</li>
 *   <li>{@code verdict=rescue} 说明是<b>安全网</b>接住的（见下），不是常规判定。</li>
 * </ul>
 *
 * <h2>{@code cand[i]} —— {@code best=(-,-,-)} 时为谁挡掉的（{@link RailBed#detailText}）</h2>
 * {@code best=(-,-,-)} 只说明「四条全被丢了」，丢在哪一步必须逐条看。每条候选一行
 * {@code cand[i]=(dist, offset, railY, minY..maxY, why)}，按查询点到折线的真实距离升序、最多 4 条：
 * <ul>
 *   <li>{@code why=height} —— 被高度粗筛挡住（{@code y < minY-4 || y > maxY+4}）。拿 {@code railY}
 *       和行首 {@code pos} 里的 {@code y}（脚底）一比就是结论：{@code railY≈y} 却仍是 {@code height}，
 *       说明窗口本身有问题；{@code railY} 差十几格说明候选里根本不是脚下那条；</li>
 *   <li>{@code why=bbox} / {@code why=far} —— 轨道在，但横向 2.5 格外，候选里是<b>附近的其它轨道</b>；</li>
 *   <li>{@code why=column} —— 这条轨道的采样折线退化（顶点不足），数据问题；</li>
 *   <li>{@code why=ok} —— 过了全部过滤、参与了选择（{@code dist > halfW=1.3} 就是「差一点」）；</li>
 *   <li>{@code more=N} —— 还有 N 条候选没列（只留最近的 4 条）；{@code straightN=a/b} ——
 *       b 条候选里有 a 条是直道，{@code straightN=0/4} 就是「直道那条不在候选里」。</li>
 * </ul>
 * {@code tbl=} 是<b>整张表</b>里离脚下最近的那条（不看候选缓存、不看高度窗口，每秒算一次）：
 * 它和 {@code cand} 对着看，就能分开「缓存漏了」和「表里根本没有这条轨道」。
 *
 * <h2>下落安全网（{@link RailBed#rescues}）</h2>
 * 常规判定之外还有一条兜底，专门防「踩空」：横向 ≤ {@link RailBed#RESCUE_RADIUS}（2.5 格）、
 * {@code vy < 0}、这一 tick 的<b>起点</b>轨面在脚底下方（{@code 0 – }{@link RailBed#RESCUE_DROP} 格，
 * 慢慢下落时才限 3 格）、而<b>终点</b>已经穿到轨面之下 —— 就无条件按上去，<b>不看</b>
 * {@code verdict} 的纵向窗口（{@code dy ≥ -stepUp} 那个下界）也不要求
 * {@code |offset| ≤ }{@link #HALF_WIDTH}。它在 {@code verdict} <b>之后</b>、真实方块检查
 * <b>之前</b>生效：真实方块仍然优先，起跳（{@code vy > 0}）与「这一刻还没穿过轨面」都不受影响。
 */
public final class Trackbed {

    /** 可站立半宽（格）：轨道中心线两侧各这么宽都能站。 */
    public static final double HALF_WIDTH = RailBed.HALF_WIDTH;

    /** 查询点周围多远的轨道参与判定（格）。 */
    public static final double SEARCH_RADIUS = RailBed.SEARCH_RADIUS;

    /** 上坡可以抬升的最大高度（格）的默认值：一级台阶。 */
    public static final double DEFAULT_STEP_UP = RailBed.DEFAULT_STEP_UP;

    /** 横向列的查询结果（{@link RailBed.Sample} 的客户端视图）。 */
    public static final class Sample {
        /** 轨道面高度（= {@code Rail#getPosition} 在横向列处的 y，与列车底板同一水平面）。 */
        public final double y;
        /** 查询点到轨道中心线的水平距离（格）。 */
        public final double distance;
        /** 有符号横向偏移（格），{@code N = T × UP} 一侧为正。 */
        public final double offset;
        /** 横向列对应的弧长参数（格）。 */
        public final double along;
        /** 轨道全长（格）。 */
        public final double length;

        Sample(final double y, final double distance, final double offset, final double along,
               final double length) {
            this.y = y;
            this.distance = distance;
            this.offset = offset;
            this.along = along;
            this.length = length;
        }
    }

    private static final RailBed BED = new RailBed();

    /** 这一 tick 的道床有没有真的把人按上去（{@code CollisionSystem#trackbed} 写）。 */
    private static volatile boolean snappedThisTick;

    /** 上一个完整 tick 的按压状态 —— 命令/日志读到的是这个（同一个 tick 内的值才有意义）。 */
    private static volatile boolean snappedLastTick;

    /** 取证开关（{@code /mtr_lte trackbed on|off}）：只在内存里，退出游戏即失效。 */
    private static volatile boolean watch;

    // 上一 tick 的取证输入输出（诊断行读这些）。
    private static volatile double lastFeet = Double.NaN;
    private static volatile double lastX = Double.NaN;
    private static volatile double lastZ = Double.NaN;
    private static volatile double lastRailY = Double.NaN;
    private static volatile double lastOffset = Double.NaN;
    private static volatile double lastDy = Double.NaN;
    private static volatile double lastVy = Double.NaN;
    private static volatile boolean lastOnGround;
    private static volatile double lastMoved = Double.NaN;
    private static volatile RailBed.Verdict lastVerdict = RailBed.Verdict.NONE;

    /** 连续「宽幅救回」（横向 > {@link #HALF_WIDTH}）的 tick 数，见 {@link RailBed#RESCUE_GRACE}。 */
    private static int wideRescues;

    /** 轨道表总条数的缓存（逐 tick 诊断的 {@code rails=}）：每 20 tick 数一次就够。 */
    private static int railsTotal;
    private static Object railsTotalMap;
    private static long railsTotalTick = Long.MIN_VALUE;

    /**
     * 整表最近轨道（{@code tbl=}）的缓存：全表扫描太重，一秒一次就够。
     * {@code ""} = 还没算过（{@link #setWatch} 打开/关闭时清掉）。
     */
    private static volatile String tableText = "";
    private static volatile long tableTick = 0L;

    // 自上一行诊断以来的计数（{@code drops=}）。按结论分桶，一眼看出是哪一种。
    private static final int[] COUNTS = new int[RailBed.Verdict.values().length];

    private Trackbed() {
    }

    /** 世界切换 / 重载时丢掉缓存。 */
    public static void reset() {
        BED.reset();
        snappedThisTick = false;
        snappedLastTick = false;
        wideRescues = 0;
        railsTotal = 0;
        railsTotalMap = null;
        railsTotalTick = Long.MIN_VALUE;
        tableText = "";
        tableTick = 0L;
        clearStep();
        clearCounters();
    }

    /** 新世界里的第一条 warn 仍然值得看。 */
    public static void clearWarned() {
        BED.clearWarned();
    }

    /** 诊断用：当前缓存里有多少条候选轨道。 */
    public static int cached() {
        return BED.cached();
    }

    /** 诊断用：上一次查询有没有在 {@link #HALF_WIDTH} 内找到轨面。 */
    public static boolean hit() {
        return BED.lastHit();
    }

    /** 诊断用：上一次查询命中的轨面 y（没命中是 {@code NaN}）。 */
    public static double lastY() {
        return BED.lastY();
    }

    /** 诊断用：上一次查询到最近候选轨道的水平距离（没候选是 {@code NaN}）。 */
    public static double lastDistance() {
        return BED.lastDistance();
    }

    /** 上一个完整 tick 里道床有没有真的把人按上去。 */
    public static boolean snapped() {
        return snappedLastTick;
    }

    /** {@code CollisionSystem#trackbed} 真的把人按上去了。 */
    public static void noteSnapped() {
        snappedThisTick = true;
    }

    /** 取证开关有没有打开。 */
    public static boolean watch() {
        return watch;
    }

    /** 开关取证日志。 */
    public static void setWatch(final boolean on) {
        watch = on;
        BED.setCapture(on);
        if (!on) {
            tableText = "";
            tableTick = 0L;
        }
    }

    /**
     * 记下这一 tick 的判定过程（每 tick 一次，无论结论是什么）。
     *
     * <p>{@code railY} / {@code distance} / {@code offset} 在没命中轨道时是 {@code NaN}；
     * {@code moved} 是这一 tick 的位移长度（含竖直），用来区分「站着不动」和「走着走着掉了」。
     *
     * <p>取证打开时（{@code /mtr_lte trackbed on}）这里打<b>逐 tick</b> 一行：
     * <pre>[lte] trackbed tick: pos=(x, y, z) rails=N cand=N best=(dist, offset, railY)
     *                        dy=… sdy=… vy=… verdict=… onGround=… moved=…
     *                        cand[0]=(dist,+off,railY,minY..maxY,why) … straightN=a/b
     *                        tbl=(dist,+off,railY,minY..maxY) straight=yes along=s/len</pre>
     * 用户站到「没有碰撞箱」的那段直道上跑几秒，这一串行就能回答三件事：直道那条轨道到底在不在
     * {@code cand} 里（{@code cand=0} 就是缓存漏了）、{@code best} 是不是它（{@code dist} 是多少）、
     * {@code dy}/{@code sdy}/{@code vy} 是不是被判出了窗口（{@code sdy} 是这一 tick <b>起点</b>的高差，
     * 安全网就是按它判「起点在轨面之上下」的）。{@code rails} 是整张轨道表的条数（每秒数一次）。
     *
     * <p>{@code best=(-,-,-)}（候选全被丢）时，后面那串 {@code cand[i]} 就是答案：
     * {@code (dist, offset, railY, minY..maxY, why)} 逐条给出「离脚底多远、投影在那条轨道的哪一侧、
     * 那里的轨面多高、这条轨道的高程区间、以及它被哪一步挡掉」——{@code why ∈
     * height|bbox|column|far|ok}（见 {@link RailBed.Why}）。{@code tbl=} 再补一刀：整张表里最近的
     * 那条（不看缓存、不看高度窗口），用来分辨「候选缓存漏了」和「表里根本没有这条轨道」。
     */
    public static void noteStep(final Sample sample, final double x, final double feet,
                                final double z, final double startDy, final double dy,
                                final double vy, final boolean onGround, final double moved,
                                final RailBed.Verdict verdict) {
        // 只在「上一个 tick 还踩在道床上」时计一次失败。站在野地里每 tick 都是 nohit，
        // 全算进去的话计数就没法读「是不是在坡道上掉的」了。
        final boolean wasOnBed = lastVerdict.snaps()
                || lastVerdict == RailBed.Verdict.BLOCK
                || lastVerdict == RailBed.Verdict.SPACE
                || lastVerdict == RailBed.Verdict.JUMP;
        final double railY = sample == null ? Double.NaN : sample.y;
        final double distance = sample == null ? Double.NaN : sample.distance;
        final double offset = sample == null ? Double.NaN : sample.offset;
        lastX = x;
        lastZ = z;
        lastFeet = feet;
        lastRailY = railY;
        lastOffset = offset;
        lastDy = dy;
        lastVy = vy;
        lastOnGround = onGround;
        lastMoved = moved;
        lastVerdict = verdict;
        noteWideRescue(distance, verdict);
        final int index = verdict.ordinal();
        if (index >= 0 && index < COUNTS.length && (wasOnBed || verdict.snaps())) {
            COUNTS[index]++;
        }
        if (verdict.snaps()) {
            noteSnapped();
        }
        if (watch && LteLog.LOGGER.isInfoEnabled()) {
            final String candidates = BED.detailText();
            final String table = tableNearest();
            LteLog.LOGGER.info("[lte] trackbed tick: pos=({}, {}, {}) rails={} cand={}"
                            + " best=({}, {}, {}) dy={} sdy={} vy={} verdict={} onGround={} moved={}"
                            + "{}{}",
                    number(x), number(feet), number(z),
                    Integer.valueOf(railsTotal()), Integer.valueOf(cached()),
                    number(distance), signed(offset), number(railY),
                    signed(dy), signed(startDy), signed(vy), verdict.shortName(),
                    Boolean.valueOf(onGround), number(moved),
                    candidates.isEmpty() ? "" : " " + candidates,
                    table.isEmpty() ? "" : " " + table);
        }
    }

    /**
     * 整表最近轨道（{@code tbl=}）那一节：一秒算一次。
     *
     * <p>它<b>不看候选缓存、也不看高度窗口</b>，所以在 {@code cand} 全部被挡掉时仍然能回答
     * 「整张表里脚下最近的是哪条、多远、多高」。和 {@code cand[i]} 对着看：
     * {@code tbl} 里有 {@code dist≈0} 而 {@code cand} 里没有 → 候选缓存漏了；
     * {@code tbl} 的 {@code dist} 也是好几格 → 表里根本没有脚下这条。
     */
    private static String tableNearest() {
        final Object rails = rails();
        final long now = System.currentTimeMillis();
        if (!tableText.isEmpty() && now - tableTick < 1000L) {
            return tableText;
        }
        tableTick = now;
        if (rails == null || Double.isNaN(lastX) || Double.isNaN(lastZ)) {
            return tableText;
        }
        tableText = BED.tableNearestText(rails, lastX, lastZ);
        return tableText;
    }

    /**
     * 宽幅救回的记账：横向超出 {@link #HALF_WIDTH} 的救回连续做满 {@link RailBed#RESCUE_GRACE} 次就
     * 放手（否则人会浮在轨道旁边的空气里）；真正踩回半宽内、或者连候选都没了，计数归零。
     */
    private static void noteWideRescue(final double distance, final RailBed.Verdict verdict) {
        if (verdict == RailBed.Verdict.RESCUE) {
            wideRescues = distance > HALF_WIDTH ? wideRescues + 1 : 0;
            return;
        }
        if (verdict == RailBed.Verdict.SNAP || verdict == RailBed.Verdict.NO_HIT
                || Double.isNaN(distance) || distance > RailBed.RESCUE_RADIUS) {
            wideRescues = 0;
        }
    }

    /**
     * <b>下落安全网</b>要不要接住这一 tick：横向 ≤ {@link RailBed#RESCUE_RADIUS}，这一 tick 从道床面
     * 上方真的掉了下去（几何条件见 {@link RailBed#rescues}）。
     *
     * <p>横向在 {@link #HALF_WIDTH} 以内的救回<b>无条件</b>；超出的（1.3 – 2.5）只给
     * {@link RailBed#RESCUE_GRACE} tick 的宽限 —— 那是给「横向列本身算偏了」的数据误差兜底，不是
     * 让人站在道床外面。
     */
    public static boolean rescue(final double distance, final double startDy, final double dy,
                                 final double vy) {
        if (!(distance <= RailBed.RESCUE_RADIUS) || !RailBed.rescues(startDy, dy, vy)) {
            return false;
        }
        return distance <= HALF_WIDTH || wideRescues < RailBed.RESCUE_GRACE;
    }

    /** 整张轨道表有多少条轨道（逐 tick 诊断的 {@code rails=}）：每 20 tick 数一次，其余读缓存。 */
    private static int railsTotal() {
        final Object map = rails();
        final long tick = System.currentTimeMillis();
        if (map == railsTotalMap && tick - railsTotalTick < 1000L) {
            return railsTotal;
        }
        railsTotalMap = map;
        railsTotalTick = tick;
        railsTotal = map == null ? 0 : RailBed.tableSize(map);
        return railsTotal;
    }

    /** tick 结束：把这一 tick 的按压状态转成「上一 tick」，返回它。 */
    public static boolean endTick() {
        final boolean value = snappedThisTick;
        snappedLastTick = value;
        snappedThisTick = false;
        return value;
    }

    /** 诊断行打完就清计数（{@code drops=} 是「自上一行以来」）。 */
    public static void clearCounters() {
        synchronized (COUNTS) {
            java.util.Arrays.fill(COUNTS, 0);
        }
    }

    /** 清掉上一 tick 的取证输入（换世界 / 关掉道床时）。 */
    public static void clearStep() {
        lastX = Double.NaN;
        lastZ = Double.NaN;
        lastFeet = Double.NaN;
        lastRailY = Double.NaN;
        lastOffset = Double.NaN;
        lastDy = Double.NaN;
        lastVy = Double.NaN;
        lastOnGround = false;
        lastMoved = Double.NaN;
        lastVerdict = RailBed.Verdict.NONE;
        wideRescues = 0;
    }

    /**
     * 诊断行（{@code [lte] trackbed: …}），命令与每 5 秒的日志共用：
     * {@code rails} 缓存候选条数、{@code scanned} 这次建缓存扫过多少条轨道、{@code skipped} 有多少条
     * 因为是单条异常被跳过（不为 0 就说明数据里有坏轨道）、{@code hit} 是否命中半宽、{@code y} 轨面高度、{@code dist}
     * 横向距离、{@code off} 有符号横向偏移、{@code halfW} 半宽、{@code dy} 脚底 − 轨面、
     * {@code onGround}/{@code moved} 这一 tick 的状态、{@code drop} 最新结论、
     * {@code snapped} 上一个完整 tick 有没有真被按上去、{@code drops} 各结论计数。
     */
    public static String describe(final boolean isSnapped) {
        return "[lte] trackbed: pos=(" + number(lastX) + ", " + number(lastFeet) + ", "
                + number(lastZ) + ")"
                + " rails=" + cached()
                + " scanned=" + BED.scanned()
                + " skipped=" + BED.skipped()
                + " hit=" + (hit() ? "yes" : "no")
                + " y=" + number(BED.lastY())
                + " dist=" + number(BED.lastDistance())
                + " off=" + signed(BED.lastOffset())
                + " halfW=" + String.format(Locale.ROOT, "%.2f", Double.valueOf(HALF_WIDTH))
                + " dy=" + signed(lastDy)
                + " vy=" + signed(lastVy)
                + " onGround=" + (lastOnGround ? "true" : "false")
                + " moved=" + number(lastMoved)
                + " drop=" + lastVerdict.shortName()
                + " snapped=" + (isSnapped ? "true" : "false")
                + " drops=" + counts();
    }

    /** {@code snap:57 dy:0 lat:0 nohit:0 jump:2 block:0 space:0} —— 自上一行以来的各结论计数。 */
    private static String counts() {
        final StringBuilder out = new StringBuilder();
        synchronized (COUNTS) {
            for (final RailBed.Verdict verdict : RailBed.Verdict.values()) {
                if (verdict == RailBed.Verdict.NONE) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(verdict.shortName()).append(':').append(COUNTS[verdict.ordinal()]);
            }
        }
        return out.toString();
    }

    /** {@code 1.23}；{@code NaN} 打成 {@code -}（这一窗没有可用的读数）。 */
    private static String number(final double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.2f", Double.valueOf(value));
    }

    /** 带符号的读数（{@code +0.31} / {@code -0.42}），正负号本身是信息。 */
    private static String signed(final double value) {
        return Double.isNaN(value) ? "-"
                : String.format(Locale.ROOT, "%+.2f", Double.valueOf(value));
    }

    /**
     * 玩家脚下最近的一条轨道（横向列），找不到（或横向偏移超出 {@link #HALF_WIDTH}）时返回
     * {@code null}。
     *
     * <p>调用方是 {@code Entity#move} 的 TAIL，每 tick 一次；缓存命中时这里只是一次线性扫描 +
     * 至多几条轨道的折线投影，不再对 {@code getPosition} 做粗扫细化。
     */
    public static Sample nearest(final ClientWorld world, final long tick, final double x,
                                 final double y, final double z) {
        final Sample sample = probe(world, tick, x, y, z);
        if (sample == null || sample.distance > HALF_WIDTH) {
            return null;
        }
        return sample;
    }

    /**
     * 和 {@link #nearest} 同一次查询，但<b>不过滤半宽</b>：横向偏移超了也把最近那条轨道返回来。
     *
     * <p>给诊断用（{@code drop=lat} 与 {@code drop=nohit} 必须分得开），判定里再自己比
     * {@link #HALF_WIDTH}。
     */
    public static Sample probe(final ClientWorld world, final long tick, final double x,
                               final double y, final double z) {
        if (world == null) {
            return null;
        }
        Object rails = rails();
        if (rails == null) {
            return null;
        }
        final RailBed.Sample sample = BED.probe(rails, tick, x, y, z);
        return sample == null ? null
                : new Sample(sample.y, sample.distance, sample.offset, sample.along,
                        sample.length);
    }

    /**
     * <b>{@code /mtr_lte trackbed where}</b> —— 在这个位置把<b>候选集本身</b>逐条打出来。
     *
     * <p>{@code status} 回答「结论是什么」（{@code drop=nohit}/{@code lat}/{@code dy}），{@code where}
     * 回答「为什么」：直道那条轨道到底在不在候选里、它的 {@code dist} 是多少、{@code along} 是不是
     * 已经贴在端点（接缝的典型形态）、{@code straight=yes} 的是不是直道那条。站在出问题的位置上
     * 跑一次，就不需要再靠猜。
     *
     * <p>返回的是若干行（第一行是表头）。轨道表没加载时只有一行说明。
     */
    public static String[] where(final ClientWorld world, final long tick, final double x,
                                 final double y, final double z) {
        final Object rails = world == null ? null : rails();
        final String text = BED.explain(rails, tick, x, y, z);
        return text.split("\\R", -1);
    }

    /** {@code ClientData.RAILS}（{@code Map<BlockPos, Map<BlockPos, Rail>>}），拿不到时 {@code null}。 */
    private static Object rails() {
        try {
            return ClientData.RAILS;
        } catch (Throwable t) {
            return null;
        }
    }
}
