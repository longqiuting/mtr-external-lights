package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.TrainPose;
import mtr.data.Train;
import net.minecraft.util.math.Vec3d;

/**
 * 车厢碰撞带 —— <b>沿轨道弧长连续采样</b>（纯计算，客户端 / 服务端共用）。
 *
 * <h2>为什么不再是「弦 → 若干段」</h2>
 * 旧实现把一节车厢看成<b>一条弦</b>（{@code a} 面 → {@code b} 面），沿这条弦等分成若干短段。
 * 弦是直的，所以相邻两节车的碰撞体在接缝处是两个直盒子在同一个点上「对折」：
 * 弯道外侧留下一个张角为 {@code Δθ = 车长 / R} 的楔形缺口，横向宽度
 * <pre>
 * gap(接缝) ≈ 2 · halfW · tan(Δθ / 2)          // R=100、车长 20 → 0.26 格；R=20 → 1.1 格
 * </pre>
 * 玩家站在车顶 / 贴着车走过这个缺口时，弦盒判定（{@code roofAt} 的 {@code along}/{@code lateral}）
 * 落空 → 没有支撑 → 掉下去。沿线均分短段救不了它：短段仍然贴在同一条弦上，缺口在<b>车与车之间</b>。
 *
 * <h2>现在的做法：弧长采样 + 横向列</h2>
 * 一节车的碰撞体不再是弦上的等分片，而是<b>沿轨道路径按弧长采样出来的一条带</b>：
 *
 * <pre>
 * 采样点 P_k = positionAt(s0 + k·step)          k = 0..n        // 真·轨道位置（含坡道 y）
 * 第 k 段    = 弦(P_k, P_k+1)，两端各沿自身轴向外扩 ov
 *            半长 = |P_k P_k+1| / 2 + ov，半宽 = halfW（横向列）
 * </pre>
 *
 * 三件事保证「弯道没有缝」：
 * <ol>
 *   <li><b>采样点落在轨道上而不是弦上</b>：相邻两段共用同一个采样点，带的走向跟着弧走；车与车的
 *       接缝处两边采样的都是<b>同一条弧</b>上的点，所以接缝不再有「两个直盒子对折」的楔形缺口。</li>
 *   <li><b>轴向重叠 ov</b>：相邻两段共用一个采样点，两段方向差 {@code θ = step / R}，于是在离中心线
 *       {@code |n| ≤ halfW} 处两段之间会张开 {@code 2·halfW·sin(θ/2)} 的楔形。把每段两端各外扩
 *       {@code ov ≥ halfW·sin(θ/2)} 就把它填满 —— 见 {@link #overlapFor}。</li>
 *   <li><b>按矢高加宽</b>：折线是内接于弧的，段中点处弧比弦向外鼓出 {@code step²/(8R)}，只按 halfW
 *       铺会在弯道外侧留一道最宽 {@value #SAG_TOL} 的细缝（仿真里量的就是这一条）。每段半宽取
 *       {@code halfW + step²/(8R)}，正好贴住带的外缘。</li>
 *   <li><b>自适应步长</b>：{@code step = clamp(√(8·R·SAG_TOL), DS_MIN, DS_MAX)}。直道 R = ∞ 取
 *       {@value #DS_MAX} 格（比旧的 1.5–2.0 格更粗、段更少）；弯道按 R 变细，保证 {@code step²/(8R)
 *       ≤ SAG_TOL}。MTR 的半径基本都在 20 格以上，所以 step 在 R ≥ 22 时都触顶；R=20 → 2.83、
 *       R=10 → 2.0、R=5 → 1.41、R=2 → 0.87。</li>
 * </ol>
 *
 * <p>横向覆盖的证明（也是 {@code tools/CurveBandSim} 跑的那一套）：相邻两段在横向偏移 {@code n} 处
 * 的未覆盖长度
 * <pre>
 * gap(n) = max(0, 2·(|P_k P_k+1| / 2 + n·tan(θ/2)) − 2·(|P_k P_k+1| / 2 + ov))
 *        = max(0, 2·n·tan(θ/2) − 2·ov)                       → ov ≥ halfW·tan(θ/2) 时 gap ≡ 0
 * </pre>
 * 实现里取 {@code ov = halfW·sin(θ/2) + eps}（{@code sin ≤ tan}，保守方向）并对直道取
 * {@code max(ov, 0.15)}（与旧的 {@code SEG_OVERLAP/2} 一致）。仿真对 R = ∞/100/50/20/10/5 在
 * 内外两侧、所有横向列上量出的最大间隙与最大空洞宽度都是 <b>0</b>；同一把尺子量旧实现，接缝处
 * 有 0.10 / 0.18 / 0.37 / 0.55 格的横向空洞（曲率越大越宽）。
 *
 * <h2>代价</h2>
 * 带是「绕轨道中心线半径 {@code halfW} 的管子」，画出来的车厢是弦盒；两者在中段差一个矢高
 * {@code len²/(8R)}（20 格车、R=100 → 0.5 格），偏差落在<b>弯道外侧</b>（带略宽）与内侧（略窄）。
 * 这比留缝安全得多：多出来的那半格只是「稍微早一点被挡住」，缺口是「直接掉下去」。
 * {@code CollisionSystem} 的车顶判定因此保留弦盒（对得上画面）并<b>额外</b>接受带内命中
 * （{@link #inside}）—— 接缝的楔形缺口由带补上。
 *
 * <h2>性能</h2>
 * 每节车每 tick 只采样 {@code n + 4} 次轨道：3 次求曲率（两端 + 中点）、{@code n} 次位置。
 * 直道 8 段 ≈ 12 次；旧实现在直道是 10 段（0 次采样，纯弦），但每段都要建对象。总体上
 * 车厢数不变、每节车的段数略降、多了十来次 {@code Rail#getPosition}。
 */
public final class CarBand {

    /** 采样折线相对轨道的最大矢高（格）：步长由它反推。 */
    public static final double SAG_TOL = 0.05D;

    /** 步长上下限（格）。直道取上限，急弯按 √R 变细。 */
    public static final double DS_MAX = 3.0D;
    public static final double DS_MIN = 0.4D;

    /** 相邻段的最小轴向重叠（格，单侧）：与旧的 {@code SEG_OVERLAP / 2} 一致。 */
    public static final double OVERLAP = 0.15D;

    /** 重叠的浮点余量：保证「刚好相等」也算重叠。 */
    public static final double OVERLAP_EPS = 0.02D;

    /** 每节车的采样段数上限：异常 pose（车厢长度离谱）时不至于生成几百个盒子。 */
    public static final int PIECE_MAX = 48;

    /** 三点曲率估算：转角小于它就算直道（弧度，约 1.1°）。 */
    public static final double STRAIGHT_ANGLE = 0.02D;

    /** 曲率探针的最短弦（格）：比这还短说明采样退化，按直道处理。 */
    private static final double MIN_PROBE = 0.5D;

    private static final double EPS = 1.0E-4D;

    private CarBand() {
    }

    // ---------------------------------------------------------------------------------------------
    // 结果
    // ---------------------------------------------------------------------------------------------

    /**
     * 一节车厢的碰撞带 + 端盖。
     *
     * <p>{@link #body} 是沿弧长采样出来的段（顺序 = 沿轨道顺序），{@link #caps} 是车头 / 车尾的
     * 轴向外扩段（仍沿弦轴 —— 端盖描述的是「车头正前方」，那里本来就是直的）。
     */
    public static final class Band {
        public final CarGeometry.Seg[] body;
        public final CarGeometry.Seg[] caps;
        /** 车身采样段数（= 采样点数 − 1）。 */
        public final int samples;
        /** 实际步长（弧长 / 段数，格）。 */
        public final double step;
        /** 估到的曲率半径（格）；直道为 {@link Double#POSITIVE_INFINITY}。 */
        public final double radius;
        /** 相邻段的实际单侧重叠（格）。 */
        public final double overlap;
        /** 弦-弧矢高（格）：带相对画出来的直车厢的最大偏差。 */
        public final double sag;
        /** 车厢在路径上的弧长（格）。 */
        public final double arc;
        /** {@code false} = 退化（路径缺失 / 车长为零），带退化成「整条弦一段」。 */
        public final boolean sampled;
        /** 车厢弦的两端与水平单位轴 / 长度（车顶插值、加窗、端盖都用它）。 */
        public final Vec3d a;
        public final Vec3d b;
        public final double ux;
        public final double uz;
        public final double len;

        Band(final CarGeometry.Seg[] body, final CarGeometry.Seg[] caps, final int samples,
             final double step, final double radius, final double overlap, final double sag,
             final double arc, final boolean sampled, final Vec3d a, final Vec3d b,
             final double ux, final double uz, final double len) {
            this.body = body;
            this.caps = caps;
            this.samples = samples;
            this.step = step;
            this.radius = radius;
            this.overlap = overlap;
            this.sag = sag;
            this.arc = arc;
            this.sampled = sampled;
            this.a = a;
            this.b = b;
            this.ux = ux;
            this.uz = uz;
            this.len = len;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 生成
    // ---------------------------------------------------------------------------------------------

    /**
     * 生成一节车的碰撞带。
     *
     * @param train     MTR 列车（客户端 {@code TrainClient} 也是它）
     * @param carIndex  行进顺序里的车厢序号（与 {@link TrainPose#carStart(int)} 同一编号）
     * @param pose      这一 tick 的车厢位姿（端面位置）
     * @param reversed  是否逆着路径方向跑（与 {@code TrainPose#of} 同一个值）
     * @param halfW     半车宽（已内缩，见 {@link CarGeometry#halfWidth(int)}）
     * @param height    车顶高度（{@code trainCollisionHeight}）
     * @param capA      车头外扩量（格，可为 0）
     * @param capB      车尾外扩量（格，可为 0）
     * @return 碰撞带；车厢几何退化（两端面重合且没有可用朝向）时返回 {@code null}
     */
    public static Band build(final Train train, final int carIndex, final TrainPose pose,
                             final boolean reversed, final double halfW, final double height,
                             final double capA, final double capB) {
        final Vec3d a = pose.carStart(carIndex);
        final Vec3d b = pose.carEnd(carIndex);
        double ax = b.x - a.x;
        double az = b.z - a.z;
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1.0E-3D) {
            // 竖直方向或退化的车厢：用自身的朝向投影到水平面兜底。
            final Vec3d fwd = pose.carForward(carIndex);
            ax = fwd.x;
            az = fwd.z;
            len = Math.sqrt(ax * ax + az * az);
            if (len < EPS) {
                return null;
            }
        }
        final double ux = ax / len;
        final double uz = az / len;
        final CarGeometry.Seg[] caps = caps(a, ux, uz, len, a.y, b.y, halfW, height, capA, capB);

        final double total = TrainPose.pathLength(train);
        if (!(total > EPS)) {
            return straight(a, b, ux, uz, len, halfW, height, caps);
        }
        final int cars = Math.max(0, train.trainCars);
        final double spacing = Math.max(0.0D, (double) train.spacing);
        final double progress = train.getRailProgress();
        // 与 TrainPose#of 完全同一套映射：boundaries[i] = progress − (reversed ? cars−i : i)·spacing。
        final double fa = clamp(progress
                - (reversed ? (double) (cars - carIndex) : (double) carIndex) * spacing, total);
        final double fb = clamp(progress
                - (reversed ? (double) (cars - carIndex - 1) : (double) (carIndex + 1)) * spacing,
                total);
        final double s0 = Math.min(fa, fb);
        final double s1 = Math.max(fa, fb);
        final double arc = s1 - s0;
        if (!(arc > 1.0E-3D)) {
            // 车厢被钳在路径端点（列车头/尾挂在路径外）：退化成弦。
            return straight(a, b, ux, uz, len, halfW, height, caps);
        }

        final double radius = radius(train, s0, s1, total);
        final double step = stepFor(radius, arc);
        final int n = (int) Math.max(1L, Math.min((long) PIECE_MAX, (long) Math.ceil(arc / step)));
        final double ds = arc / n;
        final double overlap = overlapFor(halfW, ds, radius);
        // 采样折线是内接于弧的：段中点处弧比弦向外鼓出 step²/(8R)，只按 halfW 铺会在弯道
        // <b>外侧</b>留下一道最宽 SAG_TOL 的细缝（仿真里就是这一条）。把每段按这个矢高加宽。
        final double bulge = Double.isFinite(radius) ? ds * ds / (8.0D * radius) : 0.0D;
        final double bandHalfW = halfW + bulge;

        final CarGeometry.Seg[] body = new CarGeometry.Seg[n];
        Vec3d prev = at(train, s0, total);
        for (int k = 0; k < n; k++) {
            final Vec3d next = at(train, s0 + (double) (k + 1) * ds, total);
            double dx = next.x - prev.x;
            double dz = next.z - prev.z;
            double chord = Math.sqrt(dx * dx + dz * dz);
            final double px;
            final double pz;
            if (chord > EPS) {
                px = dx / chord;
                pz = dz / chord;
            } else {
                // 两个采样点重合（路径抖动 / 极端坡度）：沿用车厢弦的方向，别造一个零面积的盒子。
                px = ux;
                pz = uz;
            }
            body[k] = new CarGeometry.Seg((prev.x + next.x) * 0.5D, (prev.z + next.z) * 0.5D,
                    px, pz, chord * 0.5D + overlap, bandHalfW,
                    Math.min(prev.y, next.y), Math.max(prev.y, next.y) + height, false, 0);
            prev = next;
        }
        final double sag = Double.isFinite(radius) ? arc * arc / (8.0D * radius) : 0.0D;
        return new Band(body, caps, n, ds, radius, overlap, sag, arc, true, a, b, ux, uz, len);
    }

    /**
     * 自适应步长：{@code step = clamp(√(8·R·SAG_TOL), DS_MIN, DS_MAX)}。
     *
     * <pre>
     * 采样折线与弧的矢高 = step² / (8R) ≤ SAG_TOL   →   step ≤ √(8·R·SAG_TOL)
     * R = ∞（直道）      → DS_MAX（3.0 格：20 格车厢 7 段，旧的弦等分是 10 段）
     * R ≥ 22.5           → 3.0（上限钳住：3 格的矢高只有 0.045 格）
     * R = 20 / 10 / 5 / 2 → 2.83 / 2.0 / 1.41 / 0.89（弯道自动变细）
     * </pre>
     */
    public static double stepFor(final double radius, final double arc) {
        if (!Double.isFinite(radius) || !(radius > 0.0D)) {
            return DS_MAX;
        }
        double step = Math.sqrt(8.0D * radius * SAG_TOL);
        if (!(step > DS_MIN)) {
            step = DS_MIN;
        } else if (step > DS_MAX) {
            step = DS_MAX;
        }
        // 再短的车厢也不必切成比它更短的段。
        return arc > 0.0D && step > arc ? Math.max(DS_MIN, arc) : step;
    }

    /**
     * 相邻段的单侧轴向重叠（格）：{@code ov = max(OVERLAP, halfW·sin(θ/2) + eps)}，
     * {@code θ = step / R}。直道 R = ∞ 时只剩 {@link #OVERLAP}。
     *
     * <p>为什么是这个量：两段在同一个采样点相接、方向差 θ，离中心线 {@code n} 处的楔形缺口宽度是
     * {@code 2·n·tan(θ/2)}（{@code n ≤ halfW}），两端各外扩 ov 正好把它填掉。
     */
    public static double overlapFor(final double halfW, final double step, final double radius) {
        if (!Double.isFinite(radius) || !(radius > 0.0D)) {
            return OVERLAP;
        }
        final double theta = Math.min(Math.PI * 0.5D, step / radius);
        final double need = halfW * Math.sin(theta * 0.5D) + OVERLAP_EPS;
        return need > OVERLAP ? need : OVERLAP;
    }

    /**
     * 三点曲率半径（格）：两端与中点各取一个轨道位置，用两段弦的水平转角求 R。
     * 转角小于 {@link #STRAIGHT_ANGLE}、或采样退化 → {@link Double#POSITIVE_INFINITY}（直道）。
     *
     * <pre>
     * R = (|P(mid) − P(s0)| + |P(s1) − P(mid)|) / θ
     * </pre>
     */
    public static double radius(final Train train, final double s0, final double s1,
                                final double total) {
        try {
            final Vec3d p0 = at(train, s0, total);
            final Vec3d pm = at(train, (s0 + s1) * 0.5D, total);
            final Vec3d p1 = at(train, s1, total);
            final double l1 = horizontal(pm.x - p0.x, pm.z - p0.z);
            final double l2 = horizontal(p1.x - pm.x, p1.z - pm.z);
            if (l1 < MIN_PROBE || l2 < MIN_PROBE) {
                return Double.POSITIVE_INFINITY;
            }
            double cos = ((pm.x - p0.x) * (p1.x - pm.x) + (pm.z - p0.z) * (p1.z - pm.z))
                    / (l1 * l2);
            if (cos > 1.0D) {
                cos = 1.0D;
            } else if (cos < -1.0D) {
                cos = -1.0D;
            }
            final double theta = Math.acos(cos);
            if (!(theta > STRAIGHT_ANGLE)) {
                return Double.POSITIVE_INFINITY;
            }
            return (l1 + l2) / theta;
        } catch (Throwable t) {
            return Double.POSITIVE_INFINITY;
        }
    }

    /**
     * 点是否落在这一串采样段的水平投影内（含 {@code pad} 容差）。
     *
     * <p>车顶 / 车底的「还有支撑吗」就靠它：弦盒（{@code roofAt} 的 {@code along}/{@code lateral}）
     * 描述的是画出来的直车厢，弯道接缝处会落空；带是连续覆盖的，落空时由它兜住。
     */
    public static boolean inside(final CarGeometry.Seg[] body, final double x, final double z,
                                 final double pad) {
        if (body == null) {
            return false;
        }
        for (final CarGeometry.Seg seg : body) {
            final double dx = x - seg.cx;
            final double dz = z - seg.cz;
            final double reach = seg.halfLen + seg.halfW + pad + 0.01D;
            if (Math.abs(dx) > reach && Math.abs(dz) > reach) {
                // 粗筛：两个轴上都比「半长 + 半宽」远，肯定不在这一段里。
                continue;
            }
            final double along = dx * seg.ux + dz * seg.uz;
            final double lat = dx * seg.nx + dz * seg.nz;
            if (Math.abs(along) <= seg.halfLen + pad && Math.abs(lat) <= seg.halfW + pad) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------------------------------

    /** 退化兜底：整条弦一段（旧行为的「1 段」）。 */
    private static Band straight(final Vec3d a, final Vec3d b, final double ux, final double uz,
                                 final double len, final double halfW, final double height,
                                 final CarGeometry.Seg[] caps) {
        final CarGeometry.Seg[] body = new CarGeometry.Seg[]{
                new CarGeometry.Seg((a.x + b.x) * 0.5D, (a.z + b.z) * 0.5D, ux, uz,
                        len * 0.5D, halfW, Math.min(a.y, b.y), Math.max(a.y, b.y) + height,
                        false, 0)
        };
        return new Band(body, caps, 1, len, Double.POSITIVE_INFINITY, 0.0D, 0.0D, len,
                false, a, b, ux, uz, len);
    }

    /** 车头 / 车尾外扩段：只加长轴向长度，宽度不变。某一端为 0 就只留另一端。 */
    private static CarGeometry.Seg[] caps(final Vec3d a, final double ux, final double uz,
                                          final double len, final double floorA,
                                          final double floorB, final double halfW,
                                          final double height, final double capA,
                                          final double capB) {
        final boolean head = capA > 0.0D;
        final boolean tail = capB > 0.0D;
        if (head && tail) {
            return new CarGeometry.Seg[]{
                    cap(a, ux, uz, -capA * 0.5D, capA * 0.5D, halfW, floorA, height, -1),
                    cap(a, ux, uz, len + capB * 0.5D, capB * 0.5D, halfW, floorB, height, 1)
            };
        }
        if (head) {
            return new CarGeometry.Seg[]{
                    cap(a, ux, uz, -capA * 0.5D, capA * 0.5D, halfW, floorA, height, -1)
            };
        }
        if (tail) {
            return new CarGeometry.Seg[]{
                    cap(a, ux, uz, len + capB * 0.5D, capB * 0.5D, halfW, floorB, height, 1)
            };
        }
        return new CarGeometry.Seg[0];
    }

    private static CarGeometry.Seg cap(final Vec3d a, final double ux, final double uz,
                                       final double mid, final double halfLen, final double halfW,
                                       final double floorY, final double height, final int side) {
        return new CarGeometry.Seg(a.x + ux * mid, a.z + uz * mid, ux, uz, halfLen, halfW,
                floorY, floorY + height, true, side);
    }

    private static Vec3d at(final Train train, final double progress, final double total) {
        return TrainPose.samplePath(train, clamp(progress, total));
    }

    private static double clamp(final double v, final double hi) {
        if (!(v > 0.0D)) {
            return 0.0D;
        }
        return v > hi ? hi : v;
    }

    private static double horizontal(final double dx, final double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }
}
