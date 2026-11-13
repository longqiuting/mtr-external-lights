/*
 * 弯道碰撞带覆盖率仿真 —— 纯计算，无 Minecraft / MTR 依赖。
 *
 * 目的：量出 v1.20.1 的 CarBand（沿轨道弧长采样 + 横向列 + 相邻段轴向重叠）在弯道上
 *       到底有没有「没被覆盖到的点」，并给出旧实现（每节车一条弦的直盒子 + 端盖）在同一弯道上的
 *       接缝空洞宽度作对照。
 *
 * 编译运行（JDK 17+ 都行；Windows 控制台要 UTF-8 输出）：
 *   javac -encoding UTF-8 -d "$LOCALAPPDATA/Temp/curveband" CurveBandSim.java
 *   java -Dstdout.encoding=UTF-8 -cp "$LOCALAPPDATA/Temp/curveband" CurveBandSim
 *
 * 模型：
 *   半径 R 的圆弧（R = ∞ 表示直道），n 节车首尾相接地铺在弧上（与 MTR TrainPose 一致：
 *   车厢面 = positionAt(progress − i·spacing)）。横向半宽 halfW，端盖外扩 endPad。
 *
 *   新（band）：每节车在 [s0, s1] 内按 step 采样，第 k 段 = 弦(P_k, P_k+1) 两端各外扩 ov。
 *   旧（chord）：每节车车身 = 沿弦的直盒子（0 ≤ 沿弦 ≤ 车长，|横向| ≤ halfW），两端加 endPad 端盖。
 *              这正好是旧代码的 roofAt（弦参数 + lateral 判定 + t ∈ [−0.02, 1.02]）。
 *
 * 两个度量（都对横向两列都扫：弯道内外侧不一样）：
 *   gapLen      某个横向列上「连续未被覆盖」的最长弧长（格）。
 *   holeWidth   某个弧长位置 s 上，「横向未被覆盖」的最宽连续宽度（格）。
 *               holeWidth = 0 表示这个 s 处车体足迹是完整的（玩家站哪儿都有支撑）。
 */
public final class CurveBandSim {

    // ---- 与 CarBand 一致的常量（改一处要改两处；这里是为了能离线跑） ------------------------
    static final double SAG_TOL = 0.05;
    static final double DS_MAX = 3.0;
    static final double DS_MIN = 0.4;
    static final double OVERLAP = 0.15;
    static final double OVERLAP_EPS = 0.02;
    static final int PIECE_MAX = 48;
    static final double STRAIGHT_ANGLE = 0.02;

    // ---- 仿真参数 --------------------------------------------------------------------------
    static final double CAR_LEN = 20.0;   // 车长 / 车距（格）
    static final double HALF_W = 1.25;    // CarGeometry.halfWidth → 1.3 − SIDE_INSET(0.05)
    static final double END_PAD = 1.0;    // 默认 trainCollisionEndPad
    static final int CARS = 4;            // 一段弧上铺几节车
    static final double SCAN = 0.01;      // 沿弧扫描步长（格）
    static final double LATERAL_STEP = 0.01;

    private CurveBandSim() {
    }

    // ---- 一段 OBB ---------------------------------------------------------------------------
    static final class Seg {
        final double cx, cz, ax, az, nx, nz, halfLen, halfW;
        final boolean cap;

        Seg(final double cx, final double cz, final double ax, final double az,
            final double halfLen, final double halfW, final boolean cap) {
            this.cx = cx;
            this.cz = cz;
            this.ax = ax;
            this.az = az;
            this.nx = -az;
            this.nz = ax;
            this.halfLen = halfLen;
            this.halfW = halfW;
            this.cap = cap;
        }

        boolean contains(final double x, final double z) {
            final double dx = x - cx;
            final double dz = z - cz;
            if (Math.abs(dx * ax + dz * az) > halfLen + 1.0E-9) {
                return false;
            }
            return Math.abs(dx * nx + dz * nz) <= halfW + 1.0E-9;
        }
    }

    // ---- 圆弧参数化（R = ∞ 时退化成直线）----------------------------------------------------
    static final class Arc {
        final double radius;

        Arc(final double radius) {
            this.radius = radius;
        }

        double[] at(final double s) {   // pos.x, pos.z, tan.x, tan.z
            if (!Double.isFinite(radius)) {
                return new double[]{s, 0.0, 1.0, 0.0};
            }
            final double th = s / radius;
            return new double[]{radius * Math.sin(th), radius * (1.0 - Math.cos(th)),
                    Math.cos(th), Math.sin(th)};
        }

        /** 弧长 s 处、沿 +法向偏 n 格的点（+n 永远指向圆心一侧 = 弯道内侧）。 */
        double[] offset(final double s, final double n) {
            final double[] p = at(s);
            return new double[]{p[0] - p[3] * n, p[1] + p[2] * n};
        }
    }

    // ---- 生成 ------------------------------------------------------------------------------

    static Seg[] band(final Arc arc, final double s0, final double s1, final double halfW) {
        final double length = s1 - s0;
        final double step = stepFor(arc.radius, length);
        final int n = (int) Math.max(1L, Math.min((long) PIECE_MAX, (long) Math.ceil(length / step)));
        final double ds = length / n;
        final double ov = overlapFor(halfW, ds, arc.radius);
        // 折线内接于弧：段中点处弧比弦外鼓 step²/(8R)，按这个矢高加宽才盖住整条带。
        final double bulge = Double.isFinite(arc.radius) ? ds * ds / (8.0 * arc.radius) : 0.0;
        final double bandHalfW = halfW + bulge;
        final Seg[] out = new Seg[n];
        double[] prev = arc.at(s0);
        for (int k = 0; k < n; k++) {
            final double[] next = arc.at(s0 + (k + 1) * ds);
            double dx = next[0] - prev[0];
            double dz = next[1] - prev[1];
            double len = Math.sqrt(dx * dx + dz * dz);
            final double ax;
            final double az;
            if (len > 1.0E-4) {
                ax = dx / len;
                az = dz / len;
            } else {
                ax = prev[2];
                az = prev[3];
                len = 0.0;
            }
            out[k] = new Seg((prev[0] + next[0]) * 0.5, (prev[1] + next[1]) * 0.5, ax, az,
                    len * 0.5 + ov, bandHalfW, false);
            prev = next;
        }
        return out;
    }

    static Seg[] chordBox(final Arc arc, final double s0, final double s1, final double halfW,
                          final double endPad) {
        final double[] a = arc.at(s0);
        final double[] b = arc.at(s1);
        final double dx = b[0] - a[0];
        final double dz = b[1] - a[1];
        final double len = Math.sqrt(dx * dx + dz * dz);
        final double ax = dx / len;
        final double az = dz / len;
        return new Seg[]{
                new Seg((a[0] + b[0]) * 0.5, (a[1] + b[1]) * 0.5, ax, az, len * 0.5, halfW, false),
                new Seg(a[0] - ax * endPad * 0.5, a[1] - az * endPad * 0.5, ax, az,
                        endPad * 0.5, halfW, true),
                new Seg(b[0] + ax * endPad * 0.5, b[1] + az * endPad * 0.5, ax, az,
                        endPad * 0.5, halfW, true)
        };
    }

    static double stepFor(final double radius, final double length) {
        if (!Double.isFinite(radius) || !(radius > 0.0)) {
            return DS_MAX;
        }
        double step = Math.sqrt(8.0 * radius * SAG_TOL);
        if (!(step > DS_MIN)) {
            step = DS_MIN;
        } else if (step > DS_MAX) {
            step = DS_MAX;
        }
        return length > 0.0 && step > length ? Math.max(DS_MIN, length) : step;
    }

    static double overlapFor(final double halfW, final double step, final double radius) {
        if (!Double.isFinite(radius) || !(radius > 0.0)) {
            return OVERLAP;
        }
        final double theta = Math.min(Math.PI * 0.5, step / radius);
        final double need = halfW * Math.sin(theta * 0.5) + OVERLAP_EPS;
        return need > OVERLAP ? need : OVERLAP;
    }

    // ---- 度量 ------------------------------------------------------------------------------

    static boolean covered(final Seg[] segs, final double x, final double z) {
        for (final Seg seg : segs) {
            if (seg.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** 横向列 n（带符号）上，连续未覆盖的最长弧长。 */
    static double gapLen(final Arc arc, final Seg[] segs, final double s0, final double s1,
                         final double n) {
        double gap = 0.0;
        double run = 0.0;
        for (double s = s0; s <= s1; s += SCAN) {
            final double[] q = arc.offset(s, n);
            if (covered(segs, q[0], q[1])) {
                run = 0.0;
            } else {
                run += SCAN;
                if (run > gap) {
                    gap = run;
                }
            }
        }
        return gap;
    }

    /** 弧长位置 s 处，横向 [−halfW, +halfW] 里最宽的连续未覆盖宽度。 */
    static double holeWidth(final Arc arc, final Seg[] segs, final double s, final double halfW) {
        double worst = 0.0;
        double run = 0.0;
        for (double n = -halfW; n <= halfW + 1.0E-9; n += LATERAL_STEP) {
            final double[] q = arc.offset(s, n);
            if (covered(segs, q[0], q[1])) {
                run = 0.0;
            } else {
                run += LATERAL_STEP;
                if (run > worst) {
                    worst = run;
                }
            }
        }
        return worst;
    }

    /** 在弧长区间 [s0, s1] 上扫 holeWidth 的最大值。 */
    static double worstHole(final Arc arc, final Seg[] segs, final double s0, final double s1,
                            final double halfW) {
        double worst = 0.0;
        for (double s = s0; s <= s1; s += SCAN) {
            final double h = holeWidth(arc, segs, s, halfW);
            if (h > worst) {
                worst = h;
            }
        }
        return worst;
    }

    /** 所有横向列（两侧）上的最大 gapLen。 */
    static double worstGap(final Arc arc, final Seg[] segs, final double s0, final double s1,
                           final double halfW) {
        double worst = 0.0;
        for (int i = -25; i <= 25; i++) {
            final double n = halfW * 0.98 * i / 25.0;
            final double g = gapLen(arc, segs, s0, s1, n);
            if (g > worst) {
                worst = g;
            }
        }
        return worst;
    }

    // ---- main -------------------------------------------------------------------------------

    public static void main(final String[] args) {
        final double[] radii = {Double.POSITIVE_INFINITY, 100.0, 50.0, 20.0, 10.0, 5.0};
        final String line = pad('=', 108);
        System.out.println("弯道碰撞带覆盖率仿真   车长 " + fmt(CAR_LEN, 1) + " 格 · 半宽 "
                + fmt(HALF_W, 2) + " 格 · 端盖 " + fmt(END_PAD, 1) + " 格 · " + CARS + " 节车");
        System.out.println(line);
        System.out.printf("%-7s %-7s %-7s %-7s %-8s %-8s %-8s %-10s %-10s%n",
                "R(格)", "step", "θ=step/R", "sag", "需要ov", "实际ov",
                "带:最大间隙", "带:最大空洞", "旧:接缝空洞");
        System.out.println(line);

        boolean ok = true;
        for (final double r : radii) {
            final Arc arc = new Arc(r);
            final double total = CAR_LEN * CARS;
            final double step = stepFor(r, CAR_LEN);
            final int n = (int) Math.max(1L, Math.min((long) PIECE_MAX,
                    (long) Math.ceil(CAR_LEN / step)));
            final double ds = CAR_LEN / n;
            final double ov = overlapFor(HALF_W, ds, r);
            final double sag = Double.isFinite(r) ? ds * ds / (8.0 * r) : 0.0;
            final double need = Double.isFinite(r) ? HALF_W * Math.tan(ds / (2.0 * r)) : 0.0;

            final Seg[] band = new Seg[CARS * PIECE_MAX];
            final Seg[] old = new Seg[CARS * 3];
            int at = 0;
            int oldAt = 0;
            double seam = 0.0;
            for (int car = 0; car < CARS; car++) {
                final double s0 = car * CAR_LEN;
                final double s1 = s0 + CAR_LEN;
                for (final Seg seg : band(arc, s0, s1, HALF_W)) {
                    band[at++] = seg;
                }
                for (final Seg seg : chordBox(arc, s0, s1, HALF_W, END_PAD)) {
                    old[oldAt++] = seg;
                }
                if (car > 0) {
                    // 接缝：只看两端盖覆盖得到的那一段（|s − seam| ≤ endPad）。
                    final double h = worstHole(arc, java.util.Arrays.copyOf(old, oldAt),
                            s0 - END_PAD, s0 + END_PAD, HALF_W);
                    seam = Math.max(seam, h);
                }
            }
            final Seg[] bandExact = java.util.Arrays.copyOf(band, at);
            final Seg[] oldExact = java.util.Arrays.copyOf(old, oldAt);
            final double gapNew = worstGap(arc, bandExact, 0.0, total, HALF_W);
            final double holeNew = worstHole(arc, bandExact, 0.0, total, HALF_W);
            final boolean pass = gapNew <= 1.0E-9 && holeNew <= 1.0E-9;
            if (!pass) {
                ok = false;
            }
            System.out.printf("%-7s %-7.3f %-7.4f %-7.4f %-8.4f %-8.4f %-8s %-10s %-10s%n",
                    Double.isFinite(r) ? fmt(r, 0) : "∞", ds, Double.isFinite(r) ? ds / r : 0.0,
                    sag, need, ov,
                    fmt(gapNew, 4) + (pass ? " ✓" : " ✗"),
                    fmt(holeNew, 4) + (pass ? " ✓" : " ✗"),
                    fmt(seam, 3) + (seam > 0.0 ? " ✗" : " ✓"));
        }
        System.out.println(line);
        System.out.println("带:最大间隙 / 最大空洞 = 0 → 采样带在弯道内外两侧都没有缝（远小于半宽 "
                + fmt(HALF_W, 2) + " 格）。");
        System.out.println("旧:接缝空洞 = 弦盒在接缝处（端盖覆盖范围内）横向缺掉的那一块 —— 端盖只能沿轴"
                + "加长，不能加宽，");
        System.out.println("            所以外缘永远是缺口，曲率越大越宽；玩家中心走过那里就没有支撑。");
        System.out.println(ok ? "PASS" : "FAIL");

        System.out.println();
        System.out.println("自适应步长（车长 " + fmt(CAR_LEN, 1) + " 格）：");
        System.out.printf("%-10s %-10s %-8s %-10s %-10s%n", "R(格)", "step(格)", "段数", "θ", "ov(格)");
        for (final double r : new double[]{Double.POSITIVE_INFINITY, 100, 50, 20, 15, 10, 5, 2}) {
            final double st = stepFor(r, CAR_LEN);
            final int n = (int) Math.max(1L, Math.min((long) PIECE_MAX,
                    (long) Math.ceil(CAR_LEN / st)));
            final double ds = CAR_LEN / n;
            System.out.printf("%-10s %-10.3f %-8d %-10s %-10.3f%n",
                    Double.isFinite(r) ? fmt(r, 0) : "∞", ds, n,
                    Double.isFinite(r) ? fmt(ds / r, 4) : "0", overlapFor(HALF_W, ds, r));
        }
        if (!ok) {
            System.exit(1);
        }
    }

    static String fmt(final double v, final int digits) {
        return String.format("%." + digits + "f", v);
    }

    static String pad(final char c, final int n) {
        final StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
