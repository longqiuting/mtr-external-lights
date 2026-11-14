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
 * 「离这个点最近的那条 MTR 轨道」—— 道床几何的<b>纯计算</b>部分，两端共用。
 *
 * <p>调用方负责给一份「端点 → 轨道」表：
 * <ul>
 *   <li>客户端：{@code mtr.client.ClientData.RAILS}（{@link Trackbed} 传进来）；</li>
 *   <li>服务端：{@code mtr.data.RailwayData#getRailsMap()}（{@code server.MtrServerData} 传进来）。</li>
 * </ul>
 * 两张表的结构相同（{@code Map<BlockPos, Map<BlockPos, Rail>>}），所以几何、缓存、采样只有这一份。
 * 本类<b>不引用</b> {@code ClientData} / {@code ClientWorld} / 任何客户端类，否则服务端就用不了它。
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
 * <h2>最近点怎么求</h2>
 * {@code Rail#getPosition(double)} 的参数是「沿轨道的距离（格）」—— 不是 0–1 的归一化值，也不是
 * 弦长（{@code getPosition} 内部把参数钳到两段弧长之和）—— 与 {@code TrainPose} 里的用法一致。
 * 步长按局部半径自适应：由采样折线上三个点估外接圆半径 R，取
 * {@code step = clamp(sqrt(8·R·0.01), 0.15, 0.8)}（弦误差 {@code s²/(8R)} 不超过 1 厘米）；
 * 估不出半径（点太少 / 退化）时用 0.4，纯直线（三点共线，R=∞）用 0.8。
 * 粗扫之后在最优点两侧折半细化，直到探测间距小于 1 厘米，所以最终误差是「厘米级」而不是「半格级」。
 * 坡道的 y 由 {@code getPosition} 自己按参数插值，所以「坡道按参数取高」不需要第二套公式。
 *
 * <h2>接缝</h2>
 * 两条相邻轨道共用一个端点时，缓存里通常两条都在（各自的折线包围盒都覆盖自己的端点），而
 * {@link #nearest} 是「全部候选里取水平距离最小的那个」，所以接缝处取的是两段各自最近点中更近的
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

    /** 高度粗筛余量（格）：坡道上同一条轨道的 y 随参数变化，判定用采样得到的 [minY, maxY] 区间。 */
    private static final double HEIGHT_SLACK = 1.5D;

    /** 折线采样步长上限（格）与最少细分数：{@code step = min(0.5, len/64)}。 */
    private static final double SAMPLE_MAX_STEP = 0.5D;
    private static final int SAMPLE_DIVISIONS = 64;

    /** 单条轨道采样点数上限（防病态数据把缓存构建拖死）。 */
    private static final int MAX_SAMPLES = 256;

    /** 几何复用表的大小上限：超了整表清空（键是弱引用，正常地图不会到）。 */
    private static final int GEOMETRY_MAX = 8192;

    /** 自适应步长的弦误差目标（格）与步长上下限、兜底值。 */
    private static final double CHORD_TOLERANCE = 0.01D;
    private static final double STEP_MIN = 0.15D;
    private static final double STEP_MAX = 0.8D;
    private static final double STEP_FALLBACK = 0.4D;

    /** 单次最近点粗扫的步数上限。 */
    private static final int MAX_SCAN_STEPS = 160;

    /** 粗扫之后的折半细化：一直折到探测间距小于 {@value #REFINE_TOLERANCE} 格（约 1 厘米）。 */
    private static final double REFINE_TOLERANCE = 0.01D;
    private static final int REFINE_MAX = 8;

    /** 缓存重建条件：缓存超过这么多 tick（客户端锚在查询点，所以「走动」也算换锚）。 */
    private static final int REBUILD_TICKS = 20;

    /**
     * 按区块格子锚定时的外扩量（格）：查询点最多离格心 11.3 格（16×16 的半对角线），
     * 再加一点余量保证格子边角上的轨道不会漏。
     */
    private static final double CELL_MARGIN = 12.0D;

    /** 一条轨道的预计算量：长度、采样折线的包围盒、自适应粗扫步长。 */
    private static final class Entry {
        final Rail rail;
        final double length;
        /** 采样折线的真实包围盒（格），不是端点包围盒 —— 这是弯道不漏的关键。 */
        final double minX, minZ, maxX, maxZ;
        final double minY, maxY;
        /** 沿弧参数的粗扫步长（格），由局部半径算出。 */
        final double step;

        Entry(final Rail rail, final double length, final double minX, final double minZ,
              final double maxX, final double maxZ, final double minY, final double maxY,
              final double step) {
            this.rail = rail;
            this.length = length;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.minY = minY;
            this.maxY = maxY;
            this.step = step;
        }

        /** 点 {@code (qx, qz)} 到包围盒的水平距离是否不超过 {@code margin}（缓存保留判定）。 */
        boolean within(final double qx, final double qz, final double margin) {
            final double dx = Math.max(0.0D, Math.max(minX - qx, qx - maxX));
            final double dz = Math.max(0.0D, Math.max(minZ - qz, qz - maxZ));
            return dx * dx + dz * dz <= margin * margin;
        }
    }

    /** 最近点的结果：轨道面 y（方块顶面格）与到轨道中心线的水平距离。 */
    public static final class Sample {
        /** 轨道面高度（= {@code Rail#getPosition} 的 y，与列车底板同一水平面）。 */
        public final double y;
        /** 查询点到最近点的水平距离（格）。 */
        public final double distance;

        Sample(final double y, final double distance) {
            this.y = y;
            this.distance = distance;
        }
    }

    private List<Entry> cache = List.of();
    private Object cacheMap;
    private double cacheX = Double.NaN;
    private double cacheZ = Double.NaN;
    private long cacheTick;
    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * 每条轨道的几何（折线包围盒 + 自适应步长）与查询点无关，所以只算一次、跨缓存重建复用。
     * 弱引用键：MTR 重载轨道表 / 换世界之后，旧 {@code Rail} 对象自己会被回收，不用手工清。
     */
    private final Map<Rail, Entry> geometry = new WeakHashMap<>();

    /** 上一次查询的取证数据（{@code /mtr_lte trackbed status} 读它）。 */
    private boolean lastHit;
    private double lastY = Double.NaN;
    private double lastDistance = Double.NaN;

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
            final Sample sample = scan(entry, x, z);
            if (sample != null && sample.distance < bestDistance) {
                bestDistance = sample.distance;
                best = sample;
            }
        }
        // 取证值先落下来（可能超出 HALF_WIDTH，诊断行需要「差一点」这种读数），再决定是否命中。
        lastY = best == null ? Double.NaN : best.y;
        lastDistance = best == null ? Double.NaN : best.distance;
        lastHit = best != null && best.distance <= HALF_WIDTH;
        return lastHit ? best : null;
    }

    private void clearLast() {
        lastHit = false;
        lastY = Double.NaN;
        lastDistance = Double.NaN;
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
     * 把一条轨道按 {@code min(0.5, len/64)} 的步长<b>整条弧</b>采样成折线，求出真实包围盒，
     * 再由折线上三点估局部半径定自适应粗扫步长。采样失败（MTR 抛异常 / 长度病态）返回 {@code null}。
     */
    private static Entry entry(final Rail rail, final double length) {
        final double sampleStep = Math.min(SAMPLE_MAX_STEP, length / SAMPLE_DIVISIONS);
        final int steps = (int) Math.min(MAX_SAMPLES, Math.ceil(length / sampleStep));
        if (steps < 2) {
            return null;
        }
        final double[] xs = new double[steps + 1];
        final double[] zs = new double[steps + 1];
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i <= steps; i++) {
            final Vec3d p = position(rail, length * i / steps);
            if (p == null) {
                return null;
            }
            xs[i] = p.x;
            zs[i] = p.z;
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        return new Entry(rail, length, minX, minZ, maxX, maxZ, minY, maxY,
                scanStep(estimateRadius(xs, zs)));
    }

    /**
     * 由采样折线估局部半径（格）：三个点的外接圆半径。半程三元组在 180° 弧上会退化成共线，
     * 所以同时试四分之一和四分之三跨度，取<b>最小的有效半径</b>（最紧的曲率 → 最细的步长）。
     *
     * @return 半径；三点共线（直线 / 180° 弧的半程）时是 {@code +∞}；完全估不出时是 {@code NaN}
     */
    private static double estimateRadius(final double[] xs, final double[] zs) {
        final int last = xs.length - 1;
        if (last < 4) {
            return Double.NaN;
        }
        double best = Double.POSITIVE_INFINITY;
        boolean any = false;
        final int[][] triples = {
            {0, last / 2, last},
            {0, last / 4, last / 2},
            {last / 2, last * 3 / 4, last},
        };
        for (final int[] t : triples) {
            final double r = circumradius(xs[t[0]], zs[t[0]], xs[t[1]], zs[t[1]],
                    xs[t[2]], zs[t[2]]);
            if (!Double.isNaN(r)) {
                any = true;
                best = Math.min(best, r);
            }
        }
        return any ? best : Double.NaN;
    }

    /** 三点外接圆半径（水平面）；共线或退化时返回 {@code +∞}。 */
    private static double circumradius(final double ax, final double az, final double bx,
                                       final double bz, final double cx, final double cz) {
        final double a = Math.hypot(bx - ax, bz - az);
        final double b = Math.hypot(cx - bx, cz - bz);
        final double c = Math.hypot(cx - ax, cz - az);
        if (a < 1.0E-6D || b < 1.0E-6D) {
            return Double.POSITIVE_INFINITY;
        }
        final double cross = Math.abs((bx - ax) * (cz - az) - (bz - az) * (cx - ax));
        if (cross < 1.0E-6D) {
            return Double.POSITIVE_INFINITY;
        }
        return a * b * c / (2.0D * cross);
    }

    /**
     * 弧参数上的粗扫步长：弦误差 {@code s²/(8R)} 控制在 {@link #CHORD_TOLERANCE} 以内，所以
     * {@code s = sqrt(8·R·tolerance)}，再钳到 [0.15, 0.8]。直线（R=∞）用 0.8，估不出半径用 0.4。
     */
    private static double scanStep(final double radius) {
        if (Double.isNaN(radius)) {
            return STEP_FALLBACK;
        }
        if (Double.isInfinite(radius)) {
            return STEP_MAX;
        }
        return clamp(Math.sqrt(8.0D * radius * CHORD_TOLERANCE), STEP_MIN, STEP_MAX);
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

    /** 自适应步长粗扫 + 折半细化，返回最近点。整条轨道都在搜索半径外时返回 {@code null}。 */
    private static Sample scan(final Entry entry, final double px, final double pz) {
        final int steps = (int) Math.max(3.0D, Math.min(MAX_SCAN_STEPS,
                Math.ceil(entry.length / entry.step) + 1.0D));
        final double step = entry.length / (steps - 1);
        double bestT = 0.0D;
        double bestSq = Double.MAX_VALUE;
        Vec3d best = null;
        for (int i = 0; i < steps; i++) {
            final double t = i * step;
            final Vec3d p = position(entry.rail, t);
            if (p == null) {
                return null;
            }
            final double dx = p.x - px;
            final double dz = p.z - pz;
            final double sq = dx * dx + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                bestT = t;
                best = p;
            }
        }
        if (best == null) {
            return null;
        }
        double half = step * 0.5D;
        for (int i = 0; i < REFINE_MAX && half > REFINE_TOLERANCE; i++) {
            final double t1 = clamp(bestT - half, 0.0D, entry.length);
            final double t2 = clamp(bestT + half, 0.0D, entry.length);
            final Vec3d a = position(entry.rail, t1);
            final Vec3d b = position(entry.rail, t2);
            if (a != null) {
                final double dx = a.x - px;
                final double dz = a.z - pz;
                final double sq = dx * dx + dz * dz;
                if (sq < bestSq) {
                    bestSq = sq;
                    bestT = t1;
                    best = a;
                }
            }
            if (b != null) {
                final double dx = b.x - px;
                final double dz = b.z - pz;
                final double sq = dx * dx + dz * dz;
                if (sq < bestSq) {
                    bestSq = sq;
                    bestT = t2;
                    best = b;
                }
            }
            half *= 0.5D;
        }
        return new Sample(best.y, Math.sqrt(bestSq));
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
