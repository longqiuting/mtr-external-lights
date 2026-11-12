package dev.js.mtrheadlight.server;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.TrainPose;
import dev.js.mtrheadlight.net.ServerAuthority;
import mtr.data.Train;
import mtr.data.TransportMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.js.mtrheadlight.collision.CarGeometry;
import dev.js.mtrheadlight.collision.CollisionImpact;
import dev.js.mtrheadlight.collision.DriverKeyBypass;
import dev.js.mtrheadlight.collision.TrainTickCapture;

/**
 * <b>服务端权威</b>的列车碰撞：每 tick 对服务端实体做与客户端相同的分段 OBB 检测，然后扣血 /
 * 撞飞 / 死亡消息全部由服务端执行。
 *
 * <h2>为什么必须有它</h2>
 * 血量、实体位置、死亡广播都是服务端权威的。纯客户端的路只能在单人（整合服务器）里把伤害打到
 * 同进程的服务端玩家上；多人服务器上客户端的 {@code damage()} 只会红一下，血量由服务器同步回来，
 * 于是「看得见、撞不疼、撞不死」。这个类补的就是多人服务器上缺的那一半。
 *
 * <h2>数据来源（只读 MTR，不 mixin 它的逻辑）</h2>
 * <ul>
 *   <li>列车清单：{@link TrainTickCapture} —— {@code TrainServer#simulateTrain} 的 TAIL 每 tick
 *       登记一次（MTR 3.6.4 没有任何公开的「取所有列车」入口，见该类的说明）。</li>
 *   <li>车厢几何：{@link TrainPose#of}（与灯光、客户端碰撞同一个采样函数）+ {@link CarGeometry}
 *       （从 {@code CollisionSystem} 抽出来的纯计算，分段规则逐字相同）。</li>
 *   <li>速度 / 车门 / 车宽：{@code Train#getSpeed()} / {@code getDoorValue()} / {@code width}。</li>
 *   <li>后果：{@link CollisionImpact}（分档、指数伤害、冲量、冷却、{@code TrainImpactDamageSource}
 *       的两段死亡消息）—— 与客户端那条路是同一份代码。</li>
 * </ul>
 *
 * <h2>谁真的会被推</h2>
 * <pre>
 * 玩家（ServerPlayerEntity）：<b>单人 / 局域网一律不接管位置</b> —— 本进程里有我们的客户端，
 *                            它每 tick 都在推；服务端再推一次就是两边抢同一个位置，画面来回跳。
 *                            多人专用服务器（没有我们的客户端在推）才在 pen ≥ 0.6 格时接管，
 *                            否则每 tick 覆盖客户端位置就是抖动。
 *                            扣血 / 撞飞（velocity + velocityModified）任何模式下都由服务端结算。
 * 其它 LivingEntity（dedicated server）：完全由服务端推，没有别人会推它们。
 * </pre>
 *
 * <h2>与客户端的协作</h2>
 * 客户端 {@code CollisionSystem} 在自己进程里有 LTE 服务端（单人 / 局域网主机）或收到
 * {@code mtrlte:hello} 时<b>不再下伤害</b>（{@link ServerAuthority}），只剩推出 / 车顶 / 受击
 * 表现。两份结算不存在重叠。
 *
 * <h2>防御</h2>
 * 每一个实体、每一列车、每一次 MTR 调用都单独 try/catch：MTR 版本不匹配、数据没就绪
 * （{@link TrainTickCapture} 空表）、类缺失，都只是「这一 tick 什么也没发生」。
 */
public final class ServerCollision {

    /** 只有这个距离内的车厢才参与判定（格）。与客户端 {@code CollisionSystem} 一致。 */
    private static final double SEARCH_RANGE = 48.0D;

    /** 玩家被服务端接管的穿透阈值（格）。 */
    private static final double PUSH_PLAYER_MIN_PEN = 0.6D;

    /** 车门放行的门值阈值（与客户端相同）。 */
    private static final double DOOR_VALUE_EPS = 0.02D;

    /** 诊断周期（server tick）。 */
    private static final int DIAG_PERIOD = 100;

    /** 多少 tick 之内没有任何列车被登记就提示一次（MTR 数据没就绪 / 注入没生效）。 */
    private static final long NO_DATA_WARN_TICK = 400L;

    private static long ticks;
    private static long lastDiagTick = Long.MIN_VALUE / 2L;
    private static int diagTrains;
    private static int diagCars;
    private static int diagHits;
    private static double diagHp;
    private static boolean everCaptured;
    private static boolean warnedNoData;

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /**
     * 最近一次被列车撞到的 tick（按实体 UUID）。
     *
     * <p>只服务一件事：{@link ServerTrackbed} 用它给「刚被撞飞的实体」一段
     * {@link ServerTrackbed} 里的宽限期 —— 正在飞的实体不该在半空被轨面吸住。
     * 只保留 200 tick 内的条目。
     */
    private static final java.util.Map<java.util.UUID, Long> LAST_HIT =
            new java.util.HashMap<>();

    private ServerCollision() {
    }

    /** {@code END_SERVER_TICK}。 */
    public static void tick(final MinecraftServer server) {
        if (server == null) {
            return;
        }
        ticks++;
        final HeadlightConfig cfg = ServerConfig.get();
        // 道床是独立开关（总开关 trackbedCollision），列车碰撞关掉时它照样要跑。
        final boolean trainsOn = cfg.trainCollision;
        if (!trainsOn && !cfg.trackbedCollision) {
            return;
        }
        final boolean dedicated = server.isDedicated();
        final long now = server.getTicks();
        for (final ServerWorld world : server.getWorlds()) {
            try {
                final List<Train> trains = trainsOn
                        ? TrainTickCapture.trains(world, world.getTime()) : List.of();
                if (!trains.isEmpty()) {
                    everCaptured = true;
                    final List<CarGeometry.Car> cars = cars(trains, cfg);
                    if (!cars.isEmpty()) {
                        diagTrains = Math.max(diagTrains, trains.size());
                        diagCars = Math.max(diagCars, cars.size());
                        for (final Entity entity : world.iterateEntities()) {
                            if (!(entity instanceof LivingEntity living)) {
                                continue;
                            }
                            // 单人：生物由客户端的 MobCollision 结算（同进程、同一套 CollisionImpact），
                            // 这里只补玩家以外没人管的那半边 —— dedicated server。
                            if (!dedicated && !(living instanceof PlayerEntity)) {
                                continue;
                            }
                            try {
                                collide(world, living, trains, cars, cfg, now, dedicated);
                            } catch (Throwable ignored) {
                                // 单个实体算错不能影响其它实体。
                            }
                        }
                    }
                }
                // 道床：与列车无关，每 tick 都要跑（没有列车的地方掉落物同样会穿过轨道）。
                ServerTrackbed.tick(world, cfg, now);
            } catch (Throwable t) {
                warnOnce(t);
            }
        }
        try {
            CollisionImpact.sweep(now);
        } catch (Throwable ignored) {
            // 状态清理失败无所谓。
        }
        pruneHits(now);
        diag(now);
        noDataHint();
    }

    /** 世界卸载：列车表与撞击状态一起清掉。 */
    public static void reset() {
        TrainTickCapture.clear();
        CollisionImpact.clear();
        ServerTrackbed.reset();
        synchronized (LAST_HIT) {
            LAST_HIT.clear();
        }
        everCaptured = false;
        warnedNoData = false;
    }

    /**
     * MTR 调用失败只 warn 一次，之后静默（每 tick 刷屏没有意义）。
     */
    public static void warnOnce(final Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            LteLog.LOGGER.warn("[lte] server collision: MTR data unavailable - "
                    + "server-side collision disabled until it recovers", t);
        }
    }

    /**
     * 这个实体最近 {@code grace} tick 内被列车撞过吗？{@link ServerTrackbed} 的宽限期用它。
     *
     * <p>正在飞的实体不该在半空被轨面吸住 —— 撞飞 10 tick 内一律不吸附。
     */
    public static boolean recentlyHit(final Entity entity, final long now, final int grace) {
        if (entity == null) {
            return false;
        }
        final Long tick;
        synchronized (LAST_HIT) {
            tick = LAST_HIT.get(entity.getUuid());
        }
        return tick != null && now - tick.longValue() < grace;
    }

    /** 清掉 200 tick 前的撞击记录（表很小，每 tick 走一遍也无所谓）。 */
    private static void pruneHits(final long now) {
        if (LAST_HIT.isEmpty()) {
            return;
        }
        synchronized (LAST_HIT) {
            LAST_HIT.entrySet().removeIf(e -> now - e.getValue().longValue() > 200L);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 单个实体
    // ---------------------------------------------------------------------------------------------

    private static void collide(final ServerWorld world, final LivingEntity living,
                                final List<Train> trains, final List<CarGeometry.Car> cars,
                                final HeadlightConfig cfg, final long now,
                                final boolean dedicated) {
        // 死亡 / 0 血：不推、不扣、不飞。死后还被车顶着走就是「似死非死」的最后一环。
        if (!living.isAlive() || living.isDead() || living.getHealth() <= 0.0F) {
            CollisionImpact.forget(living.getUuid());
            return;
        }
        // 手持 MTR 驾驶钥匙的玩家：列车碰撞整套让开（推出 / 伤害 / 撞飞 / 流血窗口），
        // 与客户端 {@code CollisionSystem} 用同一个判定（DriverKeyBypass），两边行为一致。
        // 只在玩家手持时成立，松手下一 tick 就恢复；生物与其它玩家完全不受影响。
        if (DriverKeyBypass.active(living)) {
            return;
        }
        // 飞出阶段：已经不在车体里也要按撞击车速继续扣，直到死亡或状态过期。
        final float flightHp = CollisionImpact.flightDamage(living, cfg, now);
        if (flightHp > 0.0F) {
            // 死亡消息的分段用撞击时的车速快照 —— 飞出阶段车速早就拿不到了（与 MobCollision 一致）。
            CollisionImpact.applyDamage(living, flightHp, CollisionImpact.wasFast(living.getUuid()), cfg);
            diagHp += flightHp;
            return;
        }

        if (living.isSpectator() || living.isInvulnerable()) {
            return;
        }
        // 坐在车里 / 船上 / 矿车里的人不算（含 MTR 列车上的乘客）。
        if (living.hasVehicle() || living.getVehicle() != null) {
            return;
        }
        // MTR 的乘客不一定是原版意义上的 vehicle：它自己记 ridingEntities，所以再问一次列车。
        if (living instanceof PlayerEntity rider && ridingAny(trains, rider)) {
            return;
        }
        // 创造 / 旁观由 CollisionImpact 再挡一次，这里提前退出省一遍几何。
        // damageInCreative 打开时保留创造玩家（旁观仍免疫，上行已挡）。
        if (living instanceof PlayerEntity player
                && (player.isSpectator() || (!cfg.damageInCreative && player.isCreative()))) {
            return;
        }

        CarGeometry.Push hit = null;
        CarGeometry.Car hitCar = null;
        double hitSpeed = 0.0D;
        for (final CarGeometry.Car car : cars) {
            if (!near(living, car)) {
                continue;
            }
            final CarGeometry.Push push = firstHit(world, living, car);
            if (push == null) {
                continue;
            }
            hit = push;
            hitCar = car;
            hitSpeed = car.speedKmh;
            break;
        }
        if (hit == null) {
            // 不在接触中：撞击后的持续掉血窗口（默认 25 s）仍然要结算 —— 车早就开走了，
            // 人还在流血，这才可能 15–25 秒致死。
            final float hurtHp = CollisionImpact.hurtDamage(living, cfg, now);
            if (hurtHp > 0.0F) {
                CollisionImpact.applyDamage(living, hurtHp,
                        CollisionImpact.wasFast(living.getUuid()), cfg);
                diagHp += hurtHp;
            }
            return;
        }

        diagHits++;
        synchronized (LAST_HIT) {
            LAST_HIT.put(living.getUuid(), Long.valueOf(now));
        }
        // 玩家默认不接管位置（客户端自己在推），只在明显卡进车体时救一次。
        // 单人 / 局域网：本进程里有我们的客户端，它每 tick 都在推 —— 服务端再推一次就是两边抢
        // 同一个位置，客户端收到纠正包后画面来回跳（「被推着走时一直抖」）。
        final boolean player = living instanceof ServerPlayerEntity;
        final boolean takeOver = !player || (dedicated && hit.pen >= PUSH_PLAYER_MIN_PEN);
        if (takeOver) {
            CarGeometry.applyPush(world, living, hit.dirX, hit.dirZ, hit.pen);
        } else if (dedicated) {
            // 专用服务器上没有我们的客户端在推：服务端给一次速度推送（玩家位置仍是客户端权威，
            // 但 velocity 会被原版同步下去），否则玩家只会被"卡"在原地。
            velocityPush(living, hitCar);
        }

        // 伤害规则取「撞上的这列车」的侧线（服务端 Train 也带 sidingId）。
        final HeadlightConfig.Effective eff = cfg.effectiveFor(hitCar.sidingId);
        final float hp = CollisionImpact.tick(living, cfg, eff, hitSpeed, hit.dirX, hit.dirZ, now);
        if (hp > 0.0F) {
            // 死亡消息的分段用撞击时记下的车速快照（持续掉血可能发生在车开走之后）。
            CollisionImpact.applyDamage(living, hp, CollisionImpact.wasFast(living.getUuid()), cfg);
            diagHp += hp;
        }
    }

    /**
     * 服务端的速度推送（只在专用服务器上用）：沿行进方向给水平速度。
     *
     * <p>{@code CarGeometry.Car} 的 {@code a} 端是行进方向的前端，所以行进方向 = {@code -u}。
     * 车速从 km/h 换回格/tick（{@code ÷ 72}），上限 1.35 格/tick —— 与客户端
     * {@code CollisionSystem.velocityPush} 同一套数值，两边不会互相打架。
     */
    private static void velocityPush(final LivingEntity living, final CarGeometry.Car car) {
        final double speed = car.speedKmh / CarGeometry.KMH_PER_BLOCK_PER_TICK;
        if (!(speed > 0.02D)) {
            return;
        }
        if (!living.isAlive() || living.isDead() || !(living.getHealth() > 0.0F)) {
            return;
        }
        if (CollisionImpact.impulseActive(living)) {
            // 还在撞飞后的滞空阶段（锁到落地）：别把冲量压回「跟车走」。
            return;
        }
        final double carry = Math.min(speed, 1.35D);
        final Vec3d cur = living.getVelocity();
        final double vx = -car.ux * carry;
        final double vz = -car.uz * carry;
        if (Math.abs(cur.x - vx) <= 0.03D && Math.abs(cur.z - vz) <= 0.03D) {
            // 和「每 tick 无条件覆盖」说再见：数值没变就别改，省得客户端插值来回抢。
            return;
        }
        living.setVelocity(vx, cur.y, vz);
        living.velocityModified = true;
    }

    /**
     * 这个玩家是不是正坐在某列车里（MTR 自己记 {@code ridingEntities}，不一定走原版 vehicle）。
     *
     * <p>坐在车里被自己的车撞是最糟的误判：车体段与乘客的碰撞箱必然重叠。所以每 tick 对每个玩家
     * 问一次（列车数量个位数，开销可以忽略）。
     *
     * <p><b>为什么用反射</b>：MTR 的 jar 没有 remap，{@code Train#isPlayerRiding} 的形参在它的
     * 字节码里是 intermediary 名（{@code class_1657}），而本工程用 named 映射编译 —— 直接调用编译
     * 不过。运行期两者是同一个类，{@code getMethod(..., PlayerEntity.class)} 找得到；找不到（MTR
     * 换版本）就当没坐车，几何照常判定。
     */
    private static boolean ridingAny(final List<Train> trains, final PlayerEntity player) {
        final java.lang.reflect.Method check = ridingCheck();
        if (check == null) {
            return false;
        }
        for (final Train train : trains) {
            try {
                if (Boolean.TRUE.equals(check.invoke(train, player))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 反射失败：当作没坐车。
            }
        }
        return false;
    }

    /** 懒加载 + 缓存 {@code Train#isPlayerRiding(PlayerEntity)}；拿不到返回 null。 */
    private static java.lang.reflect.Method ridingCheck() {
        java.lang.reflect.Method cached = RIDING_CHECK;
        if (cached != null || RIDING_PROBED) {
            return cached;
        }
        RIDING_PROBED = true;
        try {
            cached = Train.class.getMethod("isPlayerRiding", PlayerEntity.class);
        } catch (Throwable t) {
            cached = null;
        }
        RIDING_CHECK = cached;
        return cached;
    }

    private static volatile java.lang.reflect.Method RIDING_CHECK;
    private static volatile boolean RIDING_PROBED;

    /** 粗筛：实体到车厢中心的距离（含车厢长度余量）。 */
    private static boolean near(final LivingEntity living, final CarGeometry.Car car) {
        final double dx = living.getX() - car.cx;
        final double dz = living.getZ() - car.cz;
        final double reach = SEARCH_RANGE + car.len;
        return dx * dx + dz * dz <= reach * reach;
    }

    /**
     * 一节车的第一次命中：先车身段（开门时整段跳过），再两个端盖。与客户端 {@code pushCar} 同序，
     * 所以「撞在哪儿」两边一致。
     */
    private static CarGeometry.Push firstHit(final ServerWorld world, final LivingEntity living,
                                             final CarGeometry.Car car) {
        if (!car.doorsOpen) {
            for (final CarGeometry.Seg seg : car.body) {
                final CarGeometry.Push push = CarGeometry.contact(living, seg);
                if (push.moved) {
                    return push;
                }
            }
        }
        for (final CarGeometry.Seg cap : car.caps) {
            final CarGeometry.Push push = CarGeometry.contact(living, cap);
            if (push.moved) {
                return push;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // 车厢
    // ---------------------------------------------------------------------------------------------

    /** 服务端列车 → 车厢（分段 OBB）。 */
    private static List<CarGeometry.Car> cars(final List<Train> trains, final HeadlightConfig cfg) {
        final List<CarGeometry.Car> out = new ArrayList<>();
        final double height = cfg.trainCollisionHeightClamped();
        final double endPad = cfg.trainCollisionEndPadClamped();
        for (final Train train : trains) {
            try {
                if (train == null || train.path == null || train.path.isEmpty()
                        || train.trainCars <= 0) {
                    continue;
                }
                // 船 / 缆车 / 飞机的位置语义不同，撞上去只会莫名其妙。
                if (train.transportMode != TransportMode.TRAIN) {
                    continue;
                }
                final TrainPose pose = TrainPose.of(train, train.isReversed());
                final int carCount = pose.cars();
                if (carCount <= 0) {
                    continue;
                }
                final double halfW = CarGeometry.halfWidth(train.width);
                final double speed = CarGeometry.speedKmh(train.getSpeed());
                final boolean doorsOpen = train.getDoorValue() > DOOR_VALUE_EPS;
                final String key = Long.toString(train.id);
                // 端部延长与伤害都按这列车所在侧线解析（与客户端 CollisionSystem 同一套），
                // 服务端权威时两边算出来的车体 / 伤害规则才不会打架。
                final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
                final double noseExtra = Math.max(endPad, eff.collisionHeadExtra);
                final double rearExtra = Math.max(endPad, eff.collisionTailExtra);
                for (int i = 0; i < carCount; i++) {
                    final Vec3d a = pose.carStart(i);
                    final Vec3d b = pose.carEnd(i);
                    double ax = b.x - a.x;
                    double az = b.z - a.z;
                    double len = Math.sqrt(ax * ax + az * az);
                    if (len < 1.0E-3D) {
                        // 竖直 / 退化的车厢：用自身朝向投影到水平面兜底。
                        final Vec3d fwd = pose.carForward(i);
                        ax = fwd.x;
                        az = fwd.z;
                        len = Math.sqrt(ax * ax + az * az);
                        if (len < 1.0E-4D) {
                            continue;
                        }
                    }
                    out.add(new CarGeometry.Car(key, i, a, b, ax / len, az / len, len, halfW,
                            height, doorsOpen, speed, Double.POSITIVE_INFINITY,
                            i == 0 ? noseExtra : endPad,
                            i == carCount - 1 ? rearExtra : endPad,
                            train.sidingId));
                }
            } catch (Throwable t) {
                // 单列车采样失败：跳过它，其它列车照常。
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // 诊断
    // ---------------------------------------------------------------------------------------------

    private static void diag(final long now) {
        if (now - lastDiagTick < DIAG_PERIOD) {
            return;
        }
        lastDiagTick = now;
        final int trains = diagTrains;
        final int cars = diagCars;
        final int hits = diagHits;
        final double hp = diagHp;
        final String bedLine = ServerTrackbed.diagDirty() ? ServerTrackbed.diagLine() : null;
        diagTrains = 0;
        diagCars = 0;
        diagHits = 0;
        diagHp = 0.0D;
        if (trains == 0 && hits == 0 && bedLine == null) {
            return;
        }
        try {
            if (trains > 0 || hits > 0) {
                LteLog.LOGGER.info("[lte] collision(server): trains={} cars={} hits={} damage={} creativeDmg={}",
                        Integer.valueOf(trains), Integer.valueOf(cars), Integer.valueOf(hits),
                        String.format(Locale.ROOT, "%.1f", Double.valueOf(hp)),
                        Boolean.valueOf(ServerConfig.get().damageInCreative));
            }
            if (bedLine != null) {
                LteLog.LOGGER.info("[lte] {}", bedLine);
            }
        } catch (Throwable ignored) {
            // 日志永远不许影响 tick。
        }
    }

    /** 服务端跑了一段时间还一列车都没登记到：多半是 MTR 注入没生效（版本不匹配）。 */
    private static void noDataHint() {
        if (warnedNoData || everCaptured || ticks < NO_DATA_WARN_TICK) {
            return;
        }
        warnedNoData = true;
        LteLog.LOGGER.warn("[lte] server collision: no MTR train was captured after {} ticks - "
                + "the MTR server injection did not apply (version mismatch?). "
                + "Client-side collision is unaffected.", Long.valueOf(ticks));
    }
}
