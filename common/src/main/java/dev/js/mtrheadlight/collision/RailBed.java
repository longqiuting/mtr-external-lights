package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.LteLog;
import mtr.data.Rail;
import mtr.data.RailType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「这个水平位置落在哪根轨道的道床上，那里的轨面有多高」—— 道床几何的<b>纯计算</b>部分，两端共用。
 *
 * <p>调用方负责给一份「端点 → 轨道」表：
 * <ul>
 *   <li>客户端：{@code mtr.client.ClientData.RAILS}（{@link Trackbed} 传进来）；</li>
 *   <li>服务端：{@code mtr.data.RailwayData#getRailsMap()}（{@code server.MtrServerData} 传进来）。</li>
 * </ul>
 * 两张表的结构相同（{@code Map<BlockPos, Map<BlockPos, Rail>>}），所以几何、缓存、采样只有这一份。
 * 本类<b>不引用</b> {@code ClientData} / {@code ClientWorld} / 任何客户端类，否则服务端就用不了它。
 *
 * <h2>模型：横向列 + 弧长插值（照 RTE 的轨道段碰撞）</h2>
 * 旧模型是「最近点 + y 阈值」：求到中心线的最近采样点，再拿那个点的 y 去和脚底比。它在坡道上必然出错，
 * 因为「最近采样点」的 y 是<b>离散</b>的 —— 人往前走一步，轨面已经抬高，采样点还是上一个。RTE 的做法是
 * 把轨道当成一串<b>沿弧长的列</b>，这里照抄它的三条：
 *
 * <ol>
 *   <li><b>横向列</b>：把查询点投影到轨道折线上，得到<b>有符号横向偏移</b>
 *       {@code offset = (q - p) · N}，{@code N = T × UP}（轨道的法向，水平面内
 *       {@code N = (-tz, tx)}）。{@code |offset| ≤ }{@link #HALF_WIDTH} 才算踩在道床上。
 *       这是<b>法向</b>判定，不是包围盒：弯道内侧和外侧用的是同一条法线，所以两边都不会漏。</li>
 *   <li><b>弧长插值</b>：投影点给出沿轨道的弧长参数 {@code s}，轨面高度用 {@code Rail} 自己的
 *       高程曲线<b>解析</b>算出来（{@link #railY}，与 {@code Rail#getPosition} 的 y 完全同式）。
 *       所以坡道上是「脚下这一点的高度」，不是「最近采样点的高度」，坡度自然跟随。</li>
 *   <li><b>不拿 y 做阈值</b>：纵向只在调用方判「脚底落在可站立窗口内」，本类只负责给出轨面 y。
 *       因此 <b>onGround / vy / 包围盒</b> 都不参与几何命中，坡道、弯道、起坡点一视同仁。</li>
 * </ol>
 *
 * <h2>为什么不用表的端点直接查</h2>
 * 表只按端点索引。实体站在一条 20 格长的弯道中段时，两个端点都在 10 格以外，按端点取格子会漏掉
 * 这条轨道。所以这里维护一份「查询点附近」的轨道缓存：首次命中时把整条弧<b>采样成折线</b>，用折线
 * 的真实包围盒当命中范围，查询点落在「包围盒 + {@link #HALF_WIDTH} + 0.25」里才逐条求最近点。
 *
 * <p>旧做法是「端点包围盒外扩 {@code CURVE_SLACK=6} 格」。那是个<b>上界很松、下界错得离谱</b>的
 * 近似：一条 90° 弧从弦中间鼓出 {@code r·(1-cos45°) ≈ 0.293r} 格，半径 R 只要超过约 20 格，
 * 弧中段就连同「站在上面的人」一起被挡在缓存外面 —— 逐条采样根本不会发生，人一路掉下去。
 * 采样折线包围盒没有这个问题：R=5 和 R=50 的 90° 弧都保证覆盖（R=50 的 90° 弧中点鼓出弦外
 * 14.6 格，旧方案 6 格外扩完全够不着，新方案照样命中）。
 *
 * <h2>接缝</h2>
 * 两条相邻轨道共用一个端点时，缓存里通常两条都在（各自的折线包围盒都覆盖自己的端点），而
 * {@link #nearest} 是「全部候选里取 |横向偏移| 最小的那个」，所以接缝处取的是两段各自最近点中更近的
 * 一个 —— 不需要额外的「按端点配对」逻辑。只有一侧被缓存漏掉时才会偏，而采样包围盒让这种情况
 * 不会发生（见上）。
 *
 * <p>实例持有缓存：客户端与服务端各用一个实例（各自的轨道表、各自的缓存中心），互不干扰 ——
 * 单人里两边会同时调用，共用一个缓存槽会互相把对方的缓存冲掉。
 */
public final class RailBed {

    /**
     * 可站立半宽（格）：轨道中心线两侧各这么宽都能站 / 都能接住掉落物。
     *
     * <p>MTR 只暴露 {@code Train#width}，<b>不暴露轨道模型的宽度</b>（{@code Rail#railType} 是
     * {@code WOODEN/STONE/PLATFORM/...} 这类枚举，没有尺寸字段），所以这里按视觉宽度兜底：MTR 的
     * 轨道床约 <b>2.6 格宽</b>，半宽 1.3。旧值是 1.0（2.0 格宽），掉在枕木边缘、离中心线 1.0–1.3
     * 格的物品会被判成「轨外」而漏接。
     */
    public static final double HALF_WIDTH = 1.3D;

    /** 查询点周围多远的轨道参与判定（格）。 */
    public static final double SEARCH_RADIUS = 8.0D;

    /**
     * 缓存命中 / 采样判定的外扩量（格）：查询点到折线包围盒的水平距离不超过这个值才值得求最近点。
     * 比 {@link #HALF_WIDTH} 多 0.25 是给「刚好在边缘」的查询点留余量，也让诊断行能报出
     * {@code dist=1.4} 这种「差一点点」的读数，而不是直接 {@code -}。
     */
    private static final double CACHE_MARGIN = HALF_WIDTH + 0.25D;

    /**
     * 高度粗筛余量（格）：坡道上同一条轨道的 y 随参数变化，判定用采样得到的 [minY, maxY] 区间。
     * 取值要盖住调用方的纵向窗口（上坡抬升最多 {@code STEP_UP}，从上方落下的扫掠最多 2.0），
     * 这里都不接近，一个宽余量只影响「要不要算这一条」，不影响命中结果本身。
     */
    private static final double HEIGHT_SLACK = 4.0D;

    /** 折线采样步长上限（格）与最少细分数：{@code step = min(0.5, len/64)}。 */
    private static final double SAMPLE_MAX_STEP = 0.5D;
    private static final int SAMPLE_DIVISIONS = 64;

    /** 单条轨道采样点数上限（防病态数据把缓存构建拖死）。 */
    private static final int MAX_SAMPLES = 256;

    /** 几何复用表的大小上限：超了整表清空（键是弱引用，正常地图不会到）。 */
    private static final int GEOMETRY_MAX = 8192;

    /** 缓存重建条件：缓存超过这么多 tick（客户端锚在查询点，所以「走动」也算换锚）。 */
    private static final int REBUILD_TICKS = 20;

    /**
     * 按区块格子锚定时的外扩量（格）：查询点最多离格心 11.3 格（16×16 的半对角线），
     * 再加一点余量保证格子边角上的轨道不会漏。
     */
    private static final double CELL_MARGIN = 12.0D;

    /**
     * 一条轨道的预计算量：沿弧长等距的<b>折线</b>（x / z / 弧长 s）、高程曲线的两端、包围盒。
     *
     * <p>折线只用来做水平投影（{@code step ≤ 0.5} 保证弦误差毫米级）；<b>y 不插值折线</b>，
     * 而是用 {@link #railY} 按弧长解析算 —— 折线插值在坡道中段会低于真实轨面（MTR 的高程是
     * 两端平、中间陡的二次曲线，线性插值总是偏低）。
     */
    private static final class Entry {
        final Rail rail;
        final double length;
        /** 折线顶点（格），按弧长升序。 */
        final double[] xs;
        final double[] zs;
        /** 每个顶点对应的弧长参数（格），与 {@code Rail#getPosition} 的参数同刻度。 */
        final double[] ss;
        /** 采样折线的真实包围盒（格），不是端点包围盒 —— 这是弯道不漏的关键。 */
        final double minX, minZ, maxX, maxZ;
        final double minY, maxY;
        /** 高程曲线：{@code getPosition(0).y} / {@code getPosition(length).y}。 */
        final double yStart, yEnd;
        /** 缆车风格（两头各 0.5 格平台 + 中间线性 + 下垂），高程公式与普通轨道不同。 */
        final boolean cable;

        Entry(final Rail rail, final double length, final double[] xs, final double[] zs,
              final double[] ss, final double minX, final double minZ, final double maxX,
              final double maxZ, final double minY, final double maxY, final double yStart,
              final double yEnd, final boolean cable) {
            this.rail = rail;
            this.length = length;
            this.xs = xs;
            this.zs = zs;
            this.ss = ss;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.minY = minY;
            this.maxY = maxY;
            this.yStart = yStart;
            this.yEnd = yEnd;
            this.cable = cable;
        }

        /** 点 {@code (qx, qz)} 到包围盒的水平距离是否不超过 {@code margin}（缓存保留判定）。 */
        boolean within(final double qx, final double qz, final double margin) {
            final double dx = Math.max(0.0D, Math.max(minX - qx, qx - maxX));
            final double dz = Math.max(0.0D, Math.max(minZ - qz, qz - maxZ));
            return dx * dx + dz * dz <= margin * margin;
        }
    }

    /**
     * 一个「横向列」的查询结果。
     *
     * <p>{@link #offset} 是<b>有符号</b>横向偏移（沿轨道法向 {@code N = T × UP}），
     * {@link #distance} 是它的绝对值（旧字段，服务端与日志还在用）；{@link #along} 是该列对应的
     * 弧长参数，{@link #y} 就是 {@code railY(along)} —— 坡道上「脚下这一点」的轨面高度。
     */
    public static final class Sample {
        /** 轨道面高度（= {@code Rail#getPosition} 的 y，与列车底板同一水平面）。 */
        public final double y;
        /** 查询点到轨道中心线的水平距离（格），= {@code |offset|}。 */
        public final double distance;
        /** 有符号横向偏移（格）：正值在法向 {@code T × UP} 一侧，负值在另一侧。 */
        public final double offset;
        /** 该列在轨道上的弧长参数（格，0 – {@link #length}）。 */
        public final double along;
        /** 这条轨道的全长（格）。 */
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

    /**
     * 纵向窗口（格）：脚底可以比轨面低这么多仍然被抬上去 —— 上坡时轨面在脚底之上，这一格就是
     * 「一步能跨上去」的高度。默认 1.0（一格台阶），可配置（{@code trackbedStepUp}）。
     */
    public static final double DEFAULT_STEP_UP = 1.0D;

    /** 齐平容差（格）：脚底与轨面差在这个范围内就算「正踩在轨面上」。 */
    public static final double LEVEL_TOL = 0.03D;

    /** 「贴着轨面往下走」的吸附带（格）：下坡时脚底落在轨面上方这么多以内就贴回去。 */
    public static final double STICK = 0.6D;

    /**
     * 纵向窗口的上限（格）：脚底最多在轨面上方这么多才参与判定。原版跳跃高度是 1.25 格，所以
     * 「上方 2.0 以内一律吸附」不能无条件用（跳到最高点会被拽下来，跳不起来）；真正把上方这一半
     * 兑现的是 {@link #verdict} 里的「扫掠穿过」与 {@link #STICK} 两档。
     */
    public static final double CEIL = 2.0D;

    /**
     * 一次接触判定的结论（取证用）。几何命中之后，剩下的就是「脚底与轨面的关系」这一件事。
     */
    public enum Verdict {
        /** 没要求判定（关闭 / 不在候选里）。 */
        NONE,
        /** 吸到轨面上。 */
        SNAP,
        /** 横向列之外：缓存里有轨道，但横向偏移超过半宽。 */
        LATERAL,
        /** 纵向窗口外：横向在道床上，脚底与轨面的高差不在可站立窗口里。 */
        DY,
        /** 正在起跳：脚底在轨面附近且竖直速度向上，这一 tick 让开。 */
        JUMP,
        /** 缓存里没有近处的轨道（真掉下去的第一嫌疑人）。 */
        NO_HIT,
        /** 脚下真实方块的顶面不低于轨面：交回原版。 */
        BLOCK,
        /** 抬起来的落点被方块占了。 */
        SPACE;

        /** 诊断行里用的短名：{@code dy}/{@code lat}/{@code nohit}/{@code jump}/{@code block}/{@code space}/{@code snap}。 */
        public String shortName() {
            switch (this) {
                case SNAP:
                    return "snap";
                case LATERAL:
                    return "lat";
                case DY:
                    return "dy";
                case JUMP:
                    return "jump";
                case NO_HIT:
                    return "nohit";
                case BLOCK:
                    return "block";
                case SPACE:
                    return "space";
                default:
                    return "-";
            }
        }
    }

    /**
     * <b>纵向判定</b>（纯函数，单元测试直接验它）：给定脚底与轨面的高差 {@code dy}、竖直速度
     * {@code vy}、这一 tick 起点的高差 {@code startDy}，决定要不要把人按到轨面上。
     *
     * <p><b>不看 onGround</b>：坡道上行时原版常常在「贴地走」和「一 tick 腾空」之间来回，onGround
     * 一旦为假就把人漏掉，这正是老实现「上坡走两步就掉下去」的直接原因。这里只用三个量：
     * <ul>
     *   <li>{@code dy ∈ [-stepUp, +0.03]} —— 正踩在 / 略低于轨面（上坡、跨台阶）→ 抬上去；</li>
     *   <li>起点在上、终点在下（扫掠穿过）→ 多快的下落都接住，不受 {@link #CEIL} 限制；</li>
     *   <li>轨面上方 0–0.6 格且竖直速度向下 → 贴着走（下坡不用每一步都自由落体）。</li>
     * </ul>
     * 「正在起跳」（{@code vy > 0.02} 且脚底不在轨面之下）一律让开，否则跳不起来。
     */
    public static Verdict verdict(final double dy, final double vy, final double startDy,
                                  final double stepUp) {
        // 起跳：脚底已在轨面附近还在往上走 → 让开这一 tick（这一步与 onGround 无关）。
        if (vy > 0.02D && dy > -LEVEL_TOL) {
            return Verdict.JUMP;
        }
        // 从上方穿过轨面：起点在轨面之上、终点在轨面之下。高速下落一 tick 跨过的深度可以远超窗口。
        if (vy < -1.0E-3D && !Double.isNaN(startDy) && startDy >= -LEVEL_TOL
                && dy < -LEVEL_TOL) {
            return Verdict.SNAP;
        }
        // 纵向窗口（下界 STEP_UP，上界 CEIL）之外：横向踩着轨道也不该被拉上去/拉下来。
        if (dy < -stepUp || dy > CEIL) {
            return Verdict.DY;
        }
        // 正踩在轨面上，或脚底比轨面低不超过一级台阶 —— 上坡就是这个分支，slope 越大 dy 越负。
        if (dy <= LEVEL_TOL) {
            return Verdict.SNAP;
        }
        // 轨面之上：只有「往下贴」才收；跳起来的人在上方腾空，等扫掠那一步再接。
        if (vy <= 0.02D && dy <= STICK) {
            return Verdict.SNAP;
        }
        return Verdict.DY;
    }

    private List<Entry> cache = List.of();
    private Object cacheMap;
    private double cacheX = Double.NaN;
    private double cacheZ = Double.NaN;
    private long cacheTick;
    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * 每条轨道的几何（折线包围盒 + 高程端点）与查询点无关，所以只算一次、跨缓存重建复用。
     * 弱引用键：MTR 重载轨道表 / 换世界之后，旧 {@code Rail} 对象自己会被回收，不用手工清。
     */
    private final Map<Rail, Entry> geometry = new WeakHashMap<>();

    /** 上一次查询的取证数据（{@code /mtr_lte trackbed status} 读它）。 */
    private boolean lastHit;
    private double lastY = Double.NaN;
    private double lastDistance = Double.NaN;
    private double lastOffset = Double.NaN;
    private double lastAlong = Double.NaN;

    /** 丢掉缓存（换维度 / 换世界）。 */
    public void reset() {
        cache = List.of();
        cacheMap = null;
        cacheX = Double.NaN;
        cacheZ = Double.NaN;
        cacheTick = 0L;
        geometry.clear();
        clearLast();
    }

    /** 新世界里的第一条 warn 仍然值得看。 */
    public void clearWarned() {
        warned.set(false);
    }

    /** 缓存里有多少条候选轨道（诊断）。 */
    public int cached() {
        return cache.size();
    }

    /** 上一次查询有没有在 {@link #HALF_WIDTH} 内找到轨面（诊断）。 */
    public boolean lastHit() {
        return lastHit;
    }

    /** 上一次查询命中的轨面 y；没命中时是 {@code NaN}（诊断）。 */
    public double lastY() {
        return lastY;
    }

    /**
     * 上一次查询到<b>最近那条候选轨道</b>的水平距离，即便它超过了 {@link #HALF_WIDTH} 也照报
     * （诊断）：{@code -} 表示缓存里根本没有足够近的候选（缓存漏 / 人真的不在轨道上），
     * {@code 1.4} 这种则表示「轨道找到了，只是差一点」。
     */
    public double lastDistance() {
        return lastDistance;
    }

    /** 上一次查询的<b>有符号</b>横向偏移（诊断）：正负号只表示在法向哪一侧。 */
    public double lastOffset() {
        return lastOffset;
    }

    /** 上一次查询命中的弧长参数（诊断）：上坡时它随人往前走，y 也跟着走。 */
    public double lastAlong() {
        return lastAlong;
    }

    /**
     * 查询点脚下最近的一条轨道；找不到（或水平距离超过 {@link #HALF_WIDTH}）时返回 {@code null}。
     *
     * @param railsMap {@code Map<BlockPos, Map<BlockPos, Rail>>}（两端各自的来源，可为 {@code null}）
     */
    public Sample nearest(final Object railsMap, final long tick, final double x,
                          final double y, final double z) {
        // 锚点按 2 格量化：玩家在一个格子里小步走动不该每 tick 重建整张表（旧行为是
        // 「移动超过 2 格才重建」，量化到 2 格得到同样的重建频率）。
        return nearest(railsMap, tick, x, y, z,
                Math.floor(x / 2.0D) * 2.0D, Math.floor(z / 2.0D) * 2.0D,
                SEARCH_RADIUS + 2.0D);
    }

    /**
     * 和 {@link #nearest} 相同，但缓存锚在<b>区块格子中心</b>而不是查询点。
     *
     * <p>服务端要对「玩家 64 格内的所有实体」查询：如果每个实体都把缓存挪到自己脚下，200 个掉落物
     * 就是 200 次整表重建。按 16 格格子锚定之后，同一格里的一堆实体共用一份缓存，重建频率降到
     * 「每格每 20 tick 一次」。
     *
     * @param cellX 区块格 x（{@code Math.floor(x / 16)}）
     * @param cellZ 区块格 z
     */
    public Sample nearestCell(final Object railsMap, final long tick, final int cellX,
                              final int cellZ, final double x, final double y, final double z) {
        return nearest(railsMap, tick, x, y, z,
                cellX * 16.0D + 8.0D, cellZ * 16.0D + 8.0D, CELL_MARGIN);
    }

    /** 锚点 + 粗筛外扩量：查询点最多离锚点半个格子对角线（≈11.3 格）。 */
    private Sample nearest(final Object railsMap, final long tick, final double x, final double y,
                           final double z, final double anchorX, final double anchorZ,
                           final double margin) {
        final Sample best = probe(railsMap, tick, x, y, z, anchorX, anchorZ, margin);
        return lastHit ? best : null;
    }

    /**
     * 和 {@link #nearest} 同一次查询，但<b>不过滤半宽</b>：横向偏移超了也把最近那条轨道返回来。
     *
     * <p>诊断需要它：{@code drop=lat}（「轨道找到了、只是横向没踩上」）和 {@code drop=nohit}
     * （「缓存里根本没有近处的轨道」）的修法完全不同，如果这里也过滤掉，两种就会混成一种。
     */
    public Sample probe(final Object railsMap, final long tick, final double x, final double y,
                        final double z) {
        return probe(railsMap, tick, x, y, z,
                Math.floor(x / 2.0D) * 2.0D, Math.floor(z / 2.0D) * 2.0D,
                SEARCH_RADIUS + 2.0D);
    }

    private Sample probe(final Object railsMap, final long tick, final double x, final double y,
                         final double z, final double anchorX, final double anchorZ,
                         final double margin) {
        if (railsMap == null) {
            clearLast();
            return null;
        }
        refresh(railsMap, tick, anchorX, anchorZ, margin);
        Sample best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Entry entry : cache) {
            // 高度先过一遍：坡道上同一条轨道两端高程不同，所以用 [minY, maxY] 的区间判定。
            if (y < entry.minY - HEIGHT_SLACK || y > entry.maxY + HEIGHT_SLACK) {
                continue;
            }
            // 采样折线包围盒 + CACHE_MARGIN：包围盒是这条弧本体的真实范围，弯道中段也在里面。
            if (x < entry.minX - CACHE_MARGIN || x > entry.maxX + CACHE_MARGIN
                    || z < entry.minZ - CACHE_MARGIN || z > entry.maxZ + CACHE_MARGIN) {
                continue;
            }
            final Sample sample = column(entry, x, z);
            if (sample != null && sample.distance < bestDistance) {
                bestDistance = sample.distance;
                best = sample;
            }
        }
        // 取证值先落下来（可能超出 HALF_WIDTH，诊断行需要「差一点」这种读数），再决定是否命中。
        lastY = best == null ? Double.NaN : best.y;
        lastDistance = best == null ? Double.NaN : best.distance;
        lastOffset = best == null ? Double.NaN : best.offset;
        lastAlong = best == null ? Double.NaN : best.along;
        lastHit = best != null && best.distance <= HALF_WIDTH;
        return best;
    }

    private void clearLast() {
        lastHit = false;
        lastY = Double.NaN;
        lastDistance = Double.NaN;
        lastOffset = Double.NaN;
        lastAlong = Double.NaN;
    }

    /**
     * <b>横向列判定</b>：把 {@code (qx, qz)} 投影到这条轨道的折线上，返回最近的列。
     *
     * <p>逐段投影（不是逐采样点比距离）：段的两端是折线顶点，段方向就是该处的切向 {@code T}，
     * 法向 {@code N = (-Tz, Tx)}。投影参数 {@code f ∈ [0,1]} 给出弧长参数
     * {@code s = s_i + f·(s_{i+1} - s_i)}，横向偏移 {@code offset = (q - p) · N}。
     * 这样<b>每一段都有一个连续解</b>，不会出现「最近采样点跳了一格、y 突然变了半格」。
     */
    private static Sample column(final Entry entry, final double qx, final double qz) {
        final int last = entry.xs.length - 1;
        if (last < 1) {
            return null;
        }
        double bestSq = Double.MAX_VALUE;
        double bestOffset = 0.0D;
        double bestAlong = 0.0D;
        for (int i = 0; i < last; i++) {
            final double x1 = entry.xs[i];
            final double z1 = entry.zs[i];
            final double ex = entry.xs[i + 1] - x1;
            final double ez = entry.zs[i + 1] - z1;
            final double len2 = ex * ex + ez * ez;
            final double dx = qx - x1;
            final double dz = qz - z1;
            // 段内参数：水平投影（折线段是直线，水平投影与三维投影的分数相同）。
            double f = 0.0D;
            if (len2 > 1.0E-12D) {
                f = clamp((dx * ex + dz * ez) / len2, 0.0D, 1.0D);
            }
            final double px = x1 + ex * f;
            final double pz = z1 + ez * f;
            final double ox = qx - px;
            final double oz = qz - pz;
            final double sq = ox * ox + oz * oz;
            if (sq >= bestSq) {
                continue;
            }
            bestSq = sq;
            bestAlong = entry.ss[i] + (entry.ss[i + 1] - entry.ss[i]) * f;
            // 法向 N = T × UP，水平面内就是 (-ez, ex) 归一化（右手系：切向 × 上 = 左法向）。
            if (len2 > 1.0E-12D) {
                final double inv = 1.0D / Math.sqrt(len2);
                bestOffset = ox * (-ez * inv) + oz * (ex * inv);
            } else {
                // 退化段（水平长度 ≈ 0，MTR 正常不会出现）：给不了符号，只报距离。
                bestOffset = Math.sqrt(sq);
            }
        }
        if (bestSq == Double.MAX_VALUE) {
            return null;
        }
        final double y = railY(bestAlong, entry.length, entry.yStart, entry.yEnd, entry.cable);
        return new Sample(y, Math.sqrt(bestSq), bestOffset, bestAlong, entry.length);
    }

    /**
     * <b>沿弧长的轨面高度</b>（纯函数，单元测试直接验它）。
     *
     * <p>与 MTR {@code Rail#getPositionY} 逐项同式：普通轨道是一条两端平、中间陡的二次曲线
     * （{@code y = yStart + (yEnd-yStart)/2 · (2s/L)²}，{@code s < L/2} 时），所以坡道中点的坡度是
     * 平均坡度的 <b>两倍</b>；缆车风格是「两头各 0.5 格平台 + 中间线性 + 下垂」。
     * 用<b>弧长参数</b>而不是最近采样点，坡道上脚底与轨面的高差才是连续的。
     *
     * @param s      弧长参数（格），内部会钳到 {@code [0, length]}
     * @param length 轨道全长（格）
     * @param yStart {@code getPosition(0).y}
     * @param yEnd   {@code getPosition(length).y}
     * @param cable  缆车风格（{@code RailType.RailSlopeStyle.CABLE}）
     */
    public static double railY(final double s, final double length, final double yStart,
                               final double yEnd, final boolean cable) {
        if (!(length > 1.0E-6D)) {
            return yStart;
        }
        final double v = clamp(s, 0.0D, length);
        final double rise = yEnd - yStart;
        if (cable) {
            if (v < 0.5D) {
                return yStart;
            }
            if (v > length - 0.5D) {
                return yEnd;
            }
            final double offset = v - 0.5D;
            final double offsetLength = length - 1.0D;
            if (!(offsetLength > 1.0E-6D)) {
                return yStart;
            }
            final double posY = yStart + rise * offset / offsetLength;
            final double dip = offsetLength * offsetLength / 4.0D / 1000.0D;
            final double shape = dip > 8.0D ? 8.0D / dip : 1.0D;
            return posY + shape * (offset - offsetLength) * offset / 1000.0D;
        }
        final double intercept = length / 2.0D;
        final double yChange;
        final double yInitial;
        final double offset;
        if (v < intercept) {
            yChange = rise / 2.0D;
            yInitial = yStart;
            offset = v;
        } else {
            yChange = -rise / 2.0D;
            yInitial = yEnd;
            offset = length - v;
        }
        return yChange * offset * offset / (intercept * intercept) + yInitial;
    }

    /** 重建缓存（表变了 / 锚点换了 / 缓存过期）。 */
    private void refresh(final Object railsMap, final long tick, final double anchorX,
                         final double anchorZ, final double margin) {
        final boolean sameMap = railsMap == cacheMap;
        final boolean sameAnchor = sameMap && !Double.isNaN(cacheX)
                && cacheX == anchorX && cacheZ == anchorZ;
        final boolean fresh = sameMap && tick - cacheTick < REBUILD_TICKS;
        if (sameAnchor && fresh) {
            return;
        }
        cacheMap = railsMap;
        cacheX = anchorX;
        cacheZ = anchorZ;
        cacheTick = tick;
        cache = build(railsMap, anchorX, anchorZ, margin);
    }

    private List<Entry> build(final Object railsMap, final double x, final double z,
                              final double queryMargin) {
        final List<Entry> out = new ArrayList<>();
        if (!(railsMap instanceof Map)) {
            return out;
        }
        // 缓存保留 / 查询判定共用的水平余量：锚点 + 查询点可能离锚点最远 queryMargin。
        final double reach = queryMargin + CACHE_MARGIN;
        // MTR 未 remap：表的泛型实参是 intermediary 名（class_2338），编译期解析不到具名类型，
        // 所以整条链路按 Object/Map<?, ?> 走，运行时就是 Map<BlockPos, Map<BlockPos, Rail>>。
        try {
            for (final Map.Entry<?, ?> outer : ((Map<?, ?>) railsMap).entrySet()) {
                final Object endsRaw = outer.getValue();
                if (!(endsRaw instanceof Map)) {
                    continue;
                }
                final Object startRaw = outer.getKey();
                if (!(startRaw instanceof BlockPos)) {
                    continue;
                }
                final BlockPos start = (BlockPos) (Object) startRaw;
                for (final Map.Entry<?, ?> inner : ((Map<?, ?>) endsRaw).entrySet()) {
                    final Object railRaw = inner.getValue();
                    final Object endRaw = inner.getKey();
                    if (!(railRaw instanceof Rail) || !(endRaw instanceof BlockPos)) {
                        continue;
                    }
                    final Rail rail = (Rail) railRaw;
                    if (!physical(rail)) {
                        // NONE / AIRPLANE_DUMMY 是数据占位，没有真正的轨面，不该接住任何东西。
                        continue;
                    }
                    final BlockPos end = (BlockPos) (Object) endRaw;
                    final double length = rail.getLength();
                    if (!(length > 1.0E-3D)) {
                        continue;
                    }
                    // 采样前的保守粗筛：曲线上任一点到两端点中较近那个的距离不超过弧长的一半
                    // （三角不等式），所以「端点包围盒 + len/2 + 余量」不会漏掉任何真在附近的弧。
                    // 精确范围由下面的采样折线包围盒给出，这一步只是省掉远处的 getPosition。
                    final double slack = reach + length * 0.5D + 1.0D;
                    if (x < Math.min(start.getX(), end.getX()) - slack
                            || x > Math.max(start.getX(), end.getX()) + slack + 1.0D
                            || z < Math.min(start.getZ(), end.getZ()) - slack
                            || z > Math.max(start.getZ(), end.getZ()) + slack + 1.0D) {
                        continue;
                    }
                    final Entry entry = geometry(rail, length);
                    if (entry == null || !entry.within(x, z, reach)) {
                        continue;
                    }
                    out.add(entry);
                }
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
        return out;
    }

    /**
     * 采样缓存的入口：命中就复用，没有才算。采样失败（MTR 抛异常 / 长度病态）返回 {@code null}
     * 并不入表 —— 下一条轨道、下一次重建会再试。
     */
    private Entry geometry(final Rail rail, final double length) {
        Entry entry = geometry.get(rail);
        if (entry != null) {
            return entry;
        }
        entry = entry(rail, length);
        if (entry == null) {
            return null;
        }
        if (geometry.size() >= GEOMETRY_MAX) {
            // 病态的大地图：宁可丢一次命中率，也不让这张表无界增长（键是弱引用，正常不会走到这里）。
            geometry.clear();
        }
        geometry.put(rail, entry);
        return entry;
    }

    /**
     * 把一条轨道按 {@code min(0.5, len/64)} 的步长<b>整条弧</b>采样成折线（弧长等距），求出真实包围盒，
     * 并记下高程曲线的两端。采样失败（MTR 抛异常 / 长度病态）返回 {@code null}。
     */
    private static Entry entry(final Rail rail, final double length) {
        final double sampleStep = Math.min(SAMPLE_MAX_STEP, length / SAMPLE_DIVISIONS);
        final int steps = (int) Math.min(MAX_SAMPLES, Math.ceil(length / sampleStep));
        if (steps < 2) {
            return null;
        }
        final double[] xs = new double[steps + 1];
        final double[] zs = new double[steps + 1];
        final double[] ss = new double[steps + 1];
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i <= steps; i++) {
            final double s = length * i / steps;
            final Vec3d p = position(rail, s);
            if (p == null) {
                return null;
            }
            xs[i] = p.x;
            zs[i] = p.z;
            ss[i] = s;
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        // 高程曲线：两端采样点就是 getPosition(0)/(length) 的 y，与 MTR 的 yStart/yEnd 同值
        // （yStart/yEnd 是私有字段，从 getPosition 反推比反射稳）。
        final double yStart = sampleY(rail, 0.0D, minY, maxY);
        final double yEnd = sampleY(rail, length, minY, maxY);
        return new Entry(rail, length, xs, zs, ss, minX, minZ, maxX, maxZ, minY, maxY,
                yStart, yEnd, cable(rail));
    }

    /** 端点 y：{@code getPosition} 拿不到时退回采样区间（宁可给个保守值，也不要 NaN）。 */
    private static double sampleY(final Rail rail, final double s, final double fallback,
                                  final double fallbackHigh) {
        final Vec3d p = position(rail, s);
        return p == null ? (s <= 0.0D ? fallback : fallbackHigh) : p.y;
    }

    /**
     * 这条轨道有没有真正的轨面。MTR 的 {@code Rail#railType} 枚举里 {@code NONE} /
     * {@code AIRPLANE_DUMMY} 只是数据占位（飞机航线的假轨道），没有可站的床面；其余类型
     * （WOODEN / STONE / PLATFORM / …）都是实体轨道。读不到类型时按「是」处理，与旧行为一致。
     */
    private static boolean physical(final Rail rail) {
        try {
            final RailType type = rail.railType;
            return type != RailType.NONE && type != RailType.AIRPLANE_DUMMY;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 缆车风格的高程公式不同（两头平台 + 中间线性 + 下垂），读不到就按普通轨道算。 */
    private static boolean cable(final Rail rail) {
        try {
            return rail.railType.railSlopeStyle == RailType.RailSlopeStyle.CABLE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code Rail#getPosition} 的唯一出口：MTR 未 remap，返回类型在编译期是 intermediary 名。 */
    private static Vec3d position(final Rail rail, final double t) {
        try {
            return (Vec3d) (Object) rail.getPosition(t);
        } catch (Throwable e) {
            return null;
        }
    }

    private static double clamp(final double v, final double lo, final double hi) {
        final double above = v < lo ? lo : v;
        return above > hi ? hi : above;
    }

    /** MTR 调用失败只 warn 一次，之后静默（同一 tick 里刷屏没有意义）。 */
    private void warnOnce(final Throwable t) {
        if (warned.compareAndSet(false, true)) {
            LteLog.LOGGER.warn("[lte] collision: MTR rail data unavailable, "
                    + "trackbed collision disabled until it recovers", t);
        }
    }
}
