package dev.js.mtrheadlight.collision;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 车厢碰撞几何 —— <b>纯计算，两端通用</b>。
 *
 * <p>这个类里没有任何 {@code net.minecraft.client.*} / {@code mtr.client.*} /
 * {@code mtr.data.Train*, mtr.data.ClientData} 的引用，也不读全局状态：输入是一节车厢的两个端点
 * （{@link Vec3d}）、车宽、车高，输出是分段 OBB（{@link Seg}）与推出量。
 *
 * <p>它同时被两条路径使用：
 * <ul>
 *   <li><b>客户端</b> {@code CollisionSystem}（{@code Entity#move} 注入）—— 走
 *       {@link #pushSegment} / {@link #applyPush}，把本地玩家推出车体；</li>
 *   <li><b>服务端</b> {@code server.ServerCollision}（MTR 服务端列车 + 服务端实体）——
 *       同一套公式算出「撞上了没有、往哪推、推多远」，然后由服务端扣血 / 撞飞 / 生成死亡消息。</li>
 * </ul>
 * 两边必须用同一个几何，否则单人与多人里「车头在哪」会不一致（一边推得动、一边穿模）。
 *
 * <h2>分段近似 OBB</h2>
 * 见 {@link Seg} 与 {@link Car#body}：每节车厢沿轴线切成若干 1.5–2.0 格的短段，段间重叠
 * 0.3 格；两端的端盖（车头 / 车尾）额外外扩 {@code endPad} 格，只有端盖允许轴向推出。
 *
 * <h2>体素/世界交互</h2>
 * {@link #applyPush} 会先问 {@code world.isSpaceEmpty(entity, moved)}：推出去站不下人就不推
 * （被夹在车与墙之间时宁可原地不动，也不把人推进方块）。
 */
public final class CarGeometry {

    /** 侧面碰撞面内缩量（格）：车门边缘不再卡人。 */
    public static final double SIDE_INSET = 0.05D;

    /** 推出时多给的一点余量，避免浮点数贴着边界反复相交。 */
    public static final double PUSH_EPS = 1.0E-3D;

    /** 相邻分段之间的重叠量（格），前后各分一半。 */
    public static final double SEG_OVERLAP = 0.3D;

    /** 目标段长（格）：段数 = ceil(len / 2)，但段长不低于 1.5 格。 */
    public static final double SEG_TARGET_LEN = 2.0D;
    public static final double SEG_MIN_LEN = 1.5D;

    /** 段数上限：异常 pose（车厢长度离谱）时不至于生成几百个盒子。 */
    public static final int SEG_MAX = 32;

    /** 车顶认定的脚底下容差 / 上容差（格）。 */
    public static final double ROOF_TOL = 0.45D;
    public static final double ROOF_ABOVE = 0.06D;

    /** 车宽缺失时的半宽兜底（格）与下限。 */
    public static final double DEFAULT_HALF_WIDTH = 1.3D;
    public static final double MIN_HALF_WIDTH = 1.3D;
    public static final double MIN_HALF_WIDTH_ABS = 0.6D;

    /** MTR {@code Train#getSpeed()}（blocks/tick）→ km/h。 */
    public static final double KMH_PER_BLOCK_PER_TICK = 72.0D;

    private CarGeometry() {
    }

    // ---------------------------------------------------------------------------------------------
    // 一段车厢（车身段或端盖）
    // ---------------------------------------------------------------------------------------------

    /** 一段小 OBB + 粗筛用的 AABB。 */
    public static final class Seg {
        /** 中心（水平）。 */
        public final double cx;
        public final double cz;
        /** 轴向单位向量（a → b）与法向单位向量（水平左手侧）。 */
        public final double ux;
        public final double uz;
        public final double nx;
        public final double nz;
        /** 半长（沿轴）与半宽（沿法向）。 */
        public final double halfLen;
        public final double halfW;
        /** 竖直范围：本段轨面最低点 / 车顶最高点。 */
        public final double yLo;
        public final double yHi;
        /** 粗筛 AABB（包含本段 OBB）。 */
        public final double minX;
        public final double maxX;
        public final double minZ;
        public final double maxZ;
        /** 是不是端盖（车头/车尾外扩段）。只有端盖允许轴向推出。 */
        public final boolean cap;
        /** 端盖的向外方向在轴上的符号：车头 -1、车尾 +1；车身段为 0。 */
        public final int side;

        public Seg(final double cx, final double cz, final double ux, final double uz,
                   final double halfLen, final double halfW, final double yLo, final double yHi,
                   final boolean cap, final int side) {
            this.cx = cx;
            this.cz = cz;
            this.ux = ux;
            this.uz = uz;
            this.nx = -uz;
            this.nz = ux;
            this.halfLen = halfLen;
            this.halfW = halfW;
            this.yLo = yLo;
            this.yHi = yHi;
            final double ex = Math.abs(ux) * halfLen + Math.abs(this.nx) * halfW;
            final double ez = Math.abs(uz) * halfLen + Math.abs(this.nz) * halfW;
            this.minX = cx - ex;
            this.maxX = cx + ex;
            this.minZ = cz - ez;
            this.maxZ = cz + ez;
            this.cap = cap;
            this.side = side;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 一节车厢
    // ---------------------------------------------------------------------------------------------

    /** 一节车厢的碰撞几何 + 这一 tick 的位移。 */
    public static class Car {
        public final String key;
        public final int index;
        /** 车厢在轨面上的两个端点，a = 行进方向的前端。 */
        public final Vec3d a;
        public final Vec3d b;
        /** 水平单位轴 u（a → b）与半车宽（已内缩 {@link #SIDE_INSET} 格）。 */
        public final double ux;
        public final double uz;
        public final double len;
        public final double halfW;
        public final double cx;
        public final double cz;
        /** 车顶平面两端的 y（a.y + height，b.y + height）与轨面两端的 y。 */
        public final double roofA;
        public final double roofB;
        public final double floorA;
        public final double floorB;
        /** 门是否开着（列车级：任意一节车的门在动）。 */
        public final boolean doorsOpen;
        /** 这一节车所属列车的速度（km/h）。 */
        public final double speedKmh;
        /**
         * 这节车所属列车的侧线 id（{@code Train.sidingId}，0 = 没有侧线）。服务端两条路
         * （{@code ServerCollision} / {@code MobCollision}）都用它解析伤害的侧线覆盖 ——
         * 不看玩家 / 生物站在哪儿。
         */
        public final long sidingId;
        /** 这节车所在轨道的曲率半径（格）；直道 / 拿不到轨道 = 无穷大。 */
        public final double radiusM;
        /** 车身分段（按轴向顺序）。 */
        public final Seg[] body;
        /** 车头 / 车尾外扩段，[0] = 车头、[1] = 车尾。 */
        public final Seg[] caps;
        /** 这一 tick 的水平位移（由上一 tick 的中心算出）。 */
        public double deltaX;
        public double deltaZ;

        public Car(final String key, final int index, final Vec3d a, final Vec3d b,
                   final double ux, final double uz, final double len, final double halfW,
                   final double height, final boolean doorsOpen, final double speedKmh,
                   final double radiusM, final double capA, final double capB,
                   final long sidingId) {
            this.key = key;
            this.index = index;
            this.a = a;
            this.b = b;
            this.ux = ux;
            this.uz = uz;
            this.len = len;
            this.halfW = halfW;
            this.roofA = a.y + height;
            this.roofB = b.y + height;
            this.floorA = a.y;
            this.floorB = b.y;
            this.doorsOpen = doorsOpen;
            this.speedKmh = speedKmh;
            this.radiusM = radiusM;
            this.sidingId = sidingId;
            this.cx = (a.x + b.x) * 0.5D;
            this.cz = (a.z + b.z) * 0.5D;
            this.body = buildBody(height);
            this.caps = buildCaps(height, capA, capB);
        }

        /** 车身分段：段长 = len / segCount（1.5–2.0 格），相邻段各重叠 0.15 格。 */
        private Seg[] buildBody(final double height) {
            int count = (int) Math.ceil(len / SEG_TARGET_LEN);
            final int upper = Math.max(1, (int) Math.floor(len / SEG_MIN_LEN));
            count = Math.max(1, Math.min(Math.min(count, upper), SEG_MAX));
            final double segLen = len / count;
            final Seg[] out = new Seg[count];
            final double half = SEG_OVERLAP * 0.5D;
            for (int i = 0; i < count; i++) {
                double s0 = i * segLen - half;
                double s1 = (i + 1) * segLen + half;
                if (i == 0) {
                    s0 = 0.0D;
                }
                if (i == count - 1) {
                    s1 = len;
                }
                out[i] = seg(s0, s1, height, false, 0);
            }
            return out;
        }

        /** 车头 / 车尾外扩段：只加长轴向长度，宽度不变。某一端为 0 就只留另一端。 */
        private Seg[] buildCaps(final double height, final double capA, final double capB) {
            final boolean head = capA > 0.0D;
            final boolean tail = capB > 0.0D;
            if (head && tail) {
                return new Seg[]{
                        seg(-capA, 0.0D, height, true, -1),
                        seg(len, len + capB, height, true, 1)
                };
            }
            if (head) {
                return new Seg[]{seg(-capA, 0.0D, height, true, -1)};
            }
            if (tail) {
                return new Seg[]{seg(len, len + capB, height, true, 1)};
            }
            return new Seg[0];
        }

        /** 在轴向距离区间 {@code [s0, s1]}（从 a 端起算，格）上生成一段。 */
        private Seg seg(final double s0, final double s1, final double height,
                        final boolean cap, final int side) {
            final double mid = (s0 + s1) * 0.5D;
            final double halfLen = Math.max(1.0E-4D, (s1 - s0) * 0.5D);
            final double t0 = len < 1.0E-4D ? 0.0D : s0 / len;
            final double t1 = len < 1.0E-4D ? 1.0D : s1 / len;
            final double y0 = floorA + (floorB - floorA) * t0;
            final double y1 = floorA + (floorB - floorA) * t1;
            return new Seg(a.x + ux * mid, a.z + uz * mid, ux, uz, halfLen, halfW,
                    Math.min(y0, y1), Math.max(y0, y1) + height, cap, side);
        }

        /** 点在车厢轴上的投影，0 = a 端，1 = b 端。 */
        public double along(final double x, final double z) {
            if (len < 1.0E-4D) {
                return 0.5D;
            }
            return ((x - a.x) * ux + (z - a.z) * uz) / len;
        }

        /** 点在车厢轴上的投影，单位格（0 = a 端，len = b 端）。 */
        public double alongDistance(final double x, final double z) {
            return (x - a.x) * ux + (z - a.z) * uz;
        }

        /** 点到车厢中心线的水平有符号距离（右为正）。 */
        public double lateral(final double x, final double z) {
            return (x - a.x) * (-uz) + (z - a.z) * ux;
        }

        /** 该点的车顶高度；不在车厢水平投影内（含一点余量）时返回 NaN。 */
        public double roofAt(final double x, final double z, final double height) {
            final double t = along(x, z);
            if (t < -0.02D || t > 1.02D) {
                return Double.NaN;
            }
            if (Math.abs(lateral(x, z)) > halfW + SIDE_INSET + 0.08D) {
                return Double.NaN;
            }
            final double tc = t < 0.0D ? 0.0D : (t > 1.0D ? 1.0D : t);
            return floorA + (floorB - floorA) * tc + height;
        }

        /** 该点的车底板（轨面）高度；不在车厢水平投影内时返回 NaN。 */
        public double floorAt(final double x, final double z) {
            final double t = along(x, z);
            if (t < -0.02D || t > 1.02D) {
                return Double.NaN;
            }
            if (Math.abs(lateral(x, z)) > halfW + SIDE_INSET + 0.08D) {
                return Double.NaN;
            }
            final double tc = t < 0.0D ? 0.0D : (t > 1.0D ? 1.0D : t);
            return floorA + (floorB - floorA) * tc;
        }

        /** 高速急弯才把车顶上的人甩下去（其余情况继续载人）。 */
        public boolean slidesOff(final double minSpeedKmh, final double maxRadiusM) {
            return speedKmh > minSpeedKmh && radiusM < maxRadiusM;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 推出
    // ---------------------------------------------------------------------------------------------

    /** 一次成功的推出：方向（单位向量）与穿透量（格）。 */
    public static final class Push {
        public static final Push NONE = new Push(0.0D, 0.0D, 0.0D, false);

        public final double dirX;
        public final double dirZ;
        public final double pen;
        public final boolean moved;

        public Push(final double dirX, final double dirZ, final double pen, final boolean moved) {
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.pen = pen;
            this.moved = moved;
        }
    }

    /**
     * 单段推出：2D 分离轴判定（段的轴向 u 与法向 n），沿穿透更小的那个方向推出去。
     *
     * <p>车身段<b>只推法向</b>：段与段在轴向上是重叠的，沿轴向推会让实体在相邻段之间来回弹。
     * 端盖则允许轴向推，且方向固定为「向外」。
     */
    public static Push pushSegment(final World world, final Entity entity, final Seg seg) {
        final Push push = resolve(world, entity, seg);
        if (push.moved) {
            applyPush(world, entity, push.dirX, push.dirZ, push.pen);
        }
        return push;
    }

    /**
     * 只算「撞上了没有、该往哪推、穿透多少」——<b>不查 {@code isSpaceEmpty}</b>。
     *
     * <p>被夹在车与墙之间时位置一格都推不动，但那仍然是「撞上了」：伤害、冲量、速度推送都该照旧
     * 生效。服务端用这个做接触判定，再用 {@link #applyPush} 做「能推多少推多少」的位置修正。
     */
    public static Push contact(final Entity entity, final Seg seg) {
        return resolve(null, entity, seg, true);
    }

    /**
     * 只算不推：这一 tick 这个实体与这一段怎么相交、该往哪推多远。
     *
     * <p>服务端用它先「探」出撞击方向与穿透量（拿去做伤害 / 撞飞），再决定要不要真的改位置 ——
     * 玩家位置是客户端权威的，服务端只在明显卡进车体时（{@code pen} 很大）才接管，
     * 否则每 tick 和客户端的推出互相覆盖就是抖动。
     */
    public static Push resolve(final World world, final Entity entity, final Seg seg) {
        return resolve(world, entity, seg, false);
    }

    private static Push resolve(final World world, final Entity entity, final Seg seg,
                                final boolean ignoreSpace) {
        final Box pb = entity.getBoundingBox();
        // 粗筛：段的 AABB 包含段的 OBB，不与它相交就肯定不与 OBB 相交。
        if (pb.maxX <= seg.minX || pb.minX >= seg.maxX
                || pb.maxZ <= seg.minZ || pb.minZ >= seg.maxZ) {
            return Push.NONE;
        }
        // 竖直分离：整个人在车底板以下或整个人在车顶以上。
        if (pb.maxY <= seg.yLo + 0.02D || pb.minY >= seg.yHi - 0.02D) {
            return Push.NONE;
        }
        final double px = (pb.minX + pb.maxX) * 0.5D;
        final double pz = (pb.minZ + pb.maxZ) * 0.5D;
        final double dx = px - seg.cx;
        final double dz = pz - seg.cz;
        final double along = dx * seg.ux + dz * seg.uz;
        final double lat = dx * seg.nx + dz * seg.nz;
        final double hx = (pb.maxX - pb.minX) * 0.5D;
        final double hz = (pb.maxZ - pb.minZ) * 0.5D;
        final double projU = hx * Math.abs(seg.ux) + hz * Math.abs(seg.uz);
        final double projN = hx * Math.abs(seg.nx) + hz * Math.abs(seg.nz);
        final double penN = seg.halfW + projN - Math.abs(lat);
        final double penU = seg.halfLen + projU - Math.abs(along);
        if (penN <= 0.0D || penU <= 0.0D) {
            // 存在分离轴 → 这一 tick 不碰。
            return Push.NONE;
        }
        // 侧向：往实体已经在的那一侧推。
        final double sideN = lat >= 0.0D ? 1.0D : -1.0D;
        if (seg.cap && penU < penN) {
            final Push axial = probe(world, entity, seg.ux * seg.side, seg.uz * seg.side, penU,
                    ignoreSpace);
            if (axial.moved) {
                return axial;
            }
        }
        return probe(world, entity, seg.nx * sideN, seg.nz * sideN, penN, ignoreSpace);
    }

    /** 试算一次推出：方向合法、推完站得下人才算「会动」。{@code ignoreSpace} 时只算几何。 */
    private static Push probe(final World world, final Entity entity,
                              final double dirX, final double dirZ, final double pen,
                              final boolean ignoreSpace) {
        if (!(pen > 0.0D) || pen > 8.0D) {
            return Push.NONE;
        }
        if (ignoreSpace) {
            return new Push(dirX, dirZ, pen, true);
        }
        final double amount = pen + PUSH_EPS;
        final Box moved = entity.getBoundingBox().offset(dirX * amount, 0.0D, dirZ * amount);
        if (!world.isSpaceEmpty(entity, moved)) {
            return Push.NONE;
        }
        return new Push(dirX, dirZ, pen, true);
    }

    /**
     * 执行一次推出：沿单位方向 {@code (dirX, dirZ)} 推出去。<b>能推多少推多少</b>：整段推不出去
     * （被夹在车与墙之间）时退让到「二分出来的最大可行距离」，一格都推不动就只削掉朝车里的速度，
     * 绝不再"什么都不做"（旧实现 {@code isSpaceEmpty} 一失败就 return NONE，于是实体卡住、车穿过去）。
     *
     * <p>服务端玩家会被显式传送（{@code requestTeleport}）：服务端 {@code setPosition} 本身不会
     * 通知客户端，而原版 {@code ServerPlayNetworkHandler} 的「移动过快」检查会在下一个移动包把
     * 玩家拉回服务端位置 —— 两个位移互相打架，表现为抖动。传送包让客户端直接落到正确位置。
     */
    public static Push applyPush(final World world, final Entity entity,
                                 final double dirX, final double dirZ, final double pen) {
        if (!(pen > 0.0D) || pen > 8.0D) {
            return Push.NONE;
        }
        double amount = pen + PUSH_EPS;
        if (!fits(world, entity, dirX, dirZ, amount)) {
            amount = maxFree(world, entity, dirX, dirZ, pen);
        }
        if (amount > 0.0D) {
            final double nx = entity.getX() + dirX * amount;
            final double nz = entity.getZ() + dirZ * amount;
            if (entity instanceof ServerPlayerEntity serverPlayer) {
                serverPlayer.requestTeleport(nx, entity.getY(), nz);
            } else {
                entity.setPosition(nx, entity.getY(), nz);
            }
        }
        final Vec3d v = entity.getVelocity();
        final double inward = v.x * dirX + v.z * dirZ;
        if (inward < 0.0D) {
            // 只去掉「朝车里」的那一份速度：贴着车走不会被粘住，也不会额外获得速度。
            entity.setVelocity(v.x - inward * dirX, v.y, v.z - inward * dirZ);
            entity.velocityModified = true;
        }
        return new Push(dirX, dirZ, pen, amount > 0.0D);
    }

    /** 推出去之后站得下吗？世界拿不到时按"可以"处理（宁可推，也别卡住）。 */
    private static boolean fits(final World world, final Entity entity,
                                final double dirX, final double dirZ, final double amount) {
        if (world == null) {
            return true;
        }
        final Box moved = entity.getBoundingBox().offset(dirX * amount, 0.0D, dirZ * amount);
        return world.isSpaceEmpty(entity, moved);
    }

    /** 沿 {@code (dirX, dirZ)} 最多能推多远（二分 4 次 ≈ 穿透量的 6% 精度）。 */
    private static double maxFree(final World world, final Entity entity,
                                  final double dirX, final double dirZ, final double pen) {
        if (!fits(world, entity, dirX, dirZ, 0.0D)) {
            return 0.0D;
        }
        double lo = 0.0D;
        double hi = pen;
        for (int i = 0; i < 4; i++) {
            final double mid = (lo + hi) * 0.5D;
            if (fits(world, entity, dirX, dirZ, mid)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo > 0.05D ? lo : 0.0D;
    }

    // ---------------------------------------------------------------------------------------------
    // 共用换算
    // ---------------------------------------------------------------------------------------------

    /**
     * MTR 车型宽度的一半（格），减去侧面内缩，下限 {@link #MIN_HALF_WIDTH} 格、上限 4 格。
     *
     * <p>{@code Train#width} 不是量出来的格数，而是车型字符串里的一个小整数因子，所以
     * {@code width / 2} 只能当参考：真按 0.5 格算，玩家能直接走进车体。
     */
    public static double halfWidth(final int width) {
        double half = ((double) width) * 0.5D;
        if (!(half > 0.0D)) {
            return DEFAULT_HALF_WIDTH - SIDE_INSET;
        }
        half = Math.max(MIN_HALF_WIDTH, Math.min(4.0D, half));
        return Math.max(MIN_HALF_WIDTH_ABS, half - SIDE_INSET);
    }

    /** {@code Train#getSpeed()}（blocks/tick）→ km/h（取绝对值）。 */
    public static double speedKmh(final float speed) {
        return Math.abs(speed) * KMH_PER_BLOCK_PER_TICK;
    }
}
