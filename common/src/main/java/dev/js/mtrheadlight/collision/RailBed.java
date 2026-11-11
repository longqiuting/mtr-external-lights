package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.LteLog;
import mtr.data.Rail;
import mtr.data.RailType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 这条轨道。所以这里维护一份「查询点附近」的轨道缓存：端点包围盒外扩 {@value #CURVE_SLACK} 格后
 * 命中的轨道全部留下，再逐条采样求最近点。
 *
 * <p>外扩而不是精确裁剪：一条曲线在两端点之间会鼓出弦外，鼓出量随半径增长（90° 弧鼓出
 * {@code r·(1-cos45°) ≈ 0.29r}）。MTR 的轨道是相邻节点之间的一段，节点间距通常几格到十几格，
 * {@value #CURVE_SLACK} 格足以覆盖；只有半径超过约 20 格的长弧才可能从缓存里漏掉，而那种弧的
 * 中段本来也不会落在 8 格搜索半径里。
 *
 * <h2>最近点怎么求</h2>
 * {@code Rail#getPosition(double)} 的参数是「沿轨道的距离（格）」，不是 0–1 的归一化值 —— 与
 * {@code TrainPose} 里的用法一致。先按 0.8 格一步粗扫（最多 {@value #MAX_STEPS} 步），再在最优点
 * 两侧各折半迭代 {@value #REFINE} 次，误差收敛到约 0.05 格。坡道的 y 由 {@code getPosition} 自己按
 * 参数插值，所以「坡道按参数取高」不需要第二套公式。
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

    /** 端点包围盒的外扩量，用来把「中段经过查询点附近」的弯道留在缓存里（见类注释）。 */
    private static final double CURVE_SLACK = 6.0D;

    /** 粗扫步长（格）与步数上限。 */
    private static final double COARSE_STEP = 0.8D;
    private static final int MAX_STEPS = 28;

    /** 粗扫之后的折半细化次数。 */
    private static final int REFINE = 4;

    /** 缓存重建条件：缓存超过这么多 tick（客户端锚在查询点，所以「走动」也算换锚）。 */
    private static final int REBUILD_TICKS = 20;

    /**
     * 按区块格子锚定时的外扩量（格）：查询点最多离格心 11.3 格（16×16 的半对角线），
     * 再加一点余量保证格子边角上的轨道不会漏。
     */
    private static final double CELL_MARGIN = 12.0D;

    /** 一条轨道的预计算量：长度、两端点高度、端点包围盒。 */
    private static final class Entry {
        final Rail rail;
        final double length;
        final double minX, minZ, maxX, maxZ;
        final double minY, maxY;

        Entry(final Rail rail, final double length, final Vec3d p0, final Vec3d p1) {
            this.rail = rail;
            this.length = length;
            this.minX = Math.min(p0.x, p1.x) - CURVE_SLACK;
            this.maxX = Math.max(p0.x, p1.x) + CURVE_SLACK;
            this.minZ = Math.min(p0.z, p1.z) - CURVE_SLACK;
            this.maxZ = Math.max(p0.z, p1.z) + CURVE_SLACK;
            this.minY = Math.min(p0.y, p1.y);
            this.maxY = Math.max(p0.y, p1.y);
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

    /** 丢掉缓存（换维度 / 换世界）。 */
    public void reset() {
        cache = List.of();
        cacheMap = null;
        cacheX = Double.NaN;
        cacheZ = Double.NaN;
        cacheTick = 0L;
    }

    /** 新世界里的第一条 warn 仍然值得看。 */
    public void clearWarned() {
        warned.set(false);
    }

    /** 缓存里有多少条候选轨道（诊断）。 */
    public int cached() {
        return cache.size();
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
            return null;
        }
        refresh(railsMap, tick, anchorX, anchorZ, margin);
        Sample best = null;
        double bestDistance = HALF_WIDTH;
        for (final Entry entry : cache) {
            // 高度先过一遍：坡道上同一条轨道两端高程不同，所以用 [minY, maxY] 的区间判定。
            if (y < entry.minY - 1.5D || y > entry.maxY + 1.5D) {
                continue;
            }
            if (x < entry.minX - SEARCH_RADIUS || x > entry.maxX + SEARCH_RADIUS
                    || z < entry.minZ - SEARCH_RADIUS || z > entry.maxZ + SEARCH_RADIUS) {
                continue;
            }
            final Sample sample = scan(entry, x, z);
            if (sample != null && sample.distance < bestDistance) {
                bestDistance = sample.distance;
                best = sample;
            }
        }
        return best;
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
                    // 粗筛用端点格子：外扩 CURVE_SLACK + 查询半径 + 缓存移动容差之后仍不相交的，
                    // 采样也不可能命中。
                    final double margin = CURVE_SLACK + SEARCH_RADIUS + queryMargin;
                    if (x < Math.min(start.getX(), end.getX()) - margin
                            || x > Math.max(start.getX(), end.getX()) + margin + 1
                            || z < Math.min(start.getZ(), end.getZ()) - margin
                            || z > Math.max(start.getZ(), end.getZ()) + margin + 1) {
                        continue;
                    }
                    final double length = rail.getLength();
                    if (!(length > 1.0E-3D)) {
                        continue;
                    }
                    final Vec3d p0 = position(rail, 0.0D);
                    final Vec3d p1 = position(rail, length);
                    if (p0 == null || p1 == null) {
                        continue;
                    }
                    out.add(new Entry(rail, length, p0, p1));
                }
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
        return out;
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

    /** 粗扫 + 折半细化，返回最近点。整条轨道都在搜索半径外时返回 {@code null}。 */
    private static Sample scan(final Entry entry, final double px, final double pz) {
        final int steps = (int) Math.max(6.0D, Math.min(MAX_STEPS,
                Math.ceil(entry.length / COARSE_STEP) + 1.0D));
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
        for (int i = 0; i < REFINE; i++) {
            final double t1 = clamp(bestT - half, entry.length);
            final double t2 = clamp(bestT + half, entry.length);
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

    private static double clamp(final double v, final double hi) {
        return v < 0.0D ? 0.0D : (v > hi ? hi : v);
    }

    /** MTR 调用失败只 warn 一次，之后静默（同一 tick 里刷屏没有意义）。 */
    private void warnOnce(final Throwable t) {
        if (warned.compareAndSet(false, true)) {
            LteLog.LOGGER.warn("[lte] collision: MTR rail data unavailable, "
                    + "trackbed collision disabled until it recovers", t);
        }
    }
}
