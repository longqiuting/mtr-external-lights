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
import dev.js.mtrheadlight.collision.OnboardToolBypass;
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

    // ---- 车顶（服务端这一侧）---------------------------------------------------------------
    //
    // 服务端只管三件事：站在车顶上时不算被撞、从车顶掉下来时不被撞飞、落地时按车速结算摔落
    // 伤害。车顶吸附 / 载人位移是客户端的事（位置是客户端权威的），这里不碰位置。
    //
    // 为什么必须有这一半：单人里客户端的撞击伤害是关掉的（{@link ServerAuthority}），伤害由这里
    // 结算；而「从车顶掉下来」必然穿过车厢的竖直范围，旧实现会把它当成一次撞击 —— 于是人会被
    // 侧向弹开、被 1.1 格/tick 的「跟着车走」拖着飞、50 km/h 以上还吃一记撞飞冲量。那正是
    // 「掉下车顶像瞬移」的另一半原因（客户端那一半见 CollisionSystem#roofEscape）。

    /** 「从车顶掉下来」的最低落差（格）：走上同高站台不算摔下来。 */
    private static final double ROOF_FALL_MIN_DROP = 1.2D;

    /** 掉落追踪时长（server tick，3 s）。 */
    private static final int ROOF_FALL_TICKS = 60;

    /** 每个实体一条：站的是哪节车顶 / 刚从哪节车顶掉下来。 */
    private static final class Roof {
        String key;
        int index = -1;
        double roofY = Double.NaN;
        double speedKmh;
        long standTick = -1L;
        String fallKey;
        int fallIndex = -1;
        double fallRoofY = Double.NaN;
        double fallSpeedKmh;
        long fallUntil = -1L;
        /** 最后一次被处理的 server tick（表清理用）。 */
        long seenTick = -1L;
    }

    private static final java.util.Map<java.util.UUID, Roof> ROOFS = new java.util.HashMap<>();

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
        synchronized (ROOFS) {
            ROOFS.clear();
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
        if (!LAST_HIT.isEmpty()) {
            synchronized (LAST_HIT) {
                LAST_HIT.entrySet().removeIf(e -> now - e.getValue().longValue() > 200L);
            }
        }
        // 车顶状态：100 tick 没被碰过的实体（走远了 / 下线了）直接丢掉。
        if (!ROOFS.isEmpty()) {
            synchronized (ROOFS) {
                ROOFS.entrySet().removeIf(e -> {
                    final Roof st = e.getValue();
                    return st != null && st.seenTick >= 0L && now - st.seenTick > 100L;
                });
            }
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
        // 手持 YUM「随时上车工具」的玩家：同上，列车碰撞整套让开（推出 / 伤害 / 撞飞 / 流血窗口）。
        // 判定与客户端 {@code CollisionSystem} / {@code CollisionBootstrap} 共用 {@link OnboardToolBypass}，
        // 所以不会出现「客户端没撞、服务端把你撞飞」。松手下一 tick 恢复。
        if (OnboardToolBypass.active(living)) {
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
        // 车顶：站在车顶上 / 刚从车顶掉下来 —— 与客户端 CollisionSystem#roofEscape 同一套规则。
        // 这里没有「吸附」（位置是客户端权威的），只做「不算被撞」和掉落伤害结算。
        final Roof roof = roofOf(living);
        final CarGeometry.Car standing = onRoof(living, cars, cfg.trainCollisionHeightClamped());
        roofTick(living, roof, standing, cfg, now);
        if (standing != null) {
            // 站在车顶上：车顶吸附 / 载人是客户端的事，服务端既不推也不扣。
            return;
        }
        for (final CarGeometry.Car car : cars) {
            if (roofEscape(roof, living, car)) {
                continue;
            }
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

    // ---------------------------------------------------------------------------------------------
    // 车顶（服务端这一侧）
    // ---------------------------------------------------------------------------------------------

    /** 这个实体的车顶状态（没有就建一条）。 */
    private static Roof roofOf(final LivingEntity living) {
        return ROOFS.computeIfAbsent(living.getUuid(), id -> new Roof());
    }

    /**
     * 这个实体现在站在哪节车厢的车顶上？{@code null} = 没站在车顶上。
     *
     * <p>判定与客户端 {@code CollisionSystem#standOnRoof} 同一套：脚底落在
     * {@code [roof − ROOF_TOL, roof + ROOF_ABOVE]}、水平投影在车厢里、并且不是在起跳
     * （玩家看 {@code isOnGround()} —— 它随移动包同步上来；其它实体看竖直速度）。
     *
     * <p>容差不放宽：漏判的后果只是「这一节车没被 latch 住」，那种情况下车身段与玩家仍然因为
     * <b>竖直分离</b>（脚底就在车顶平面上）不算相交，不会凭空扣血。
     */
    private static CarGeometry.Car onRoof(final LivingEntity living,
                                          final List<CarGeometry.Car> cars,
                                          final double height) {
        for (final CarGeometry.Car car : cars) {
            if (!near(living, car)) {
                continue;
            }
            final double roof = car.roofAt(living.getX(), living.getZ(), height);
            if (Double.isNaN(roof)) {
                continue;
            }
            final double feet = living.getY();
            if (feet < roof - CarGeometry.ROOF_TOL || feet > roof + CarGeometry.ROOF_ABOVE) {
                continue;
            }
            if (living instanceof PlayerEntity) {
                if (!living.isOnGround()) {
                    // 起跳 / 落回车顶的滞空：那是「离开车顶」，交给掉落追踪。
                    continue;
                }
            } else if (living.getVelocity().y > 0.08D) {
                continue;
            }
            return car;
        }
        return null;
    }

    /**
     * 车顶状态迁移 + 落地结算：站上去记账，离开的那一 tick 起开始追踪掉落，落地那一 tick 按
     * <b>离开车顶时的车速</b>结算摔落伤害（{@link CollisionImpact#roofFallHp}）。
     *
     * <p>与客户端 {@code CollisionSystem} 的 {@code roofState} / {@code settleRoofFall} 一一对应，
     * 数值也一致：单人与多人里「从车顶掉下来要掉多少血」是同一套公式。
     */
    private static void roofTick(final LivingEntity living, final Roof st,
                                 final CarGeometry.Car standing, final HeadlightConfig cfg,
                                 final long now) {
        st.seenTick = now;
        if (standing != null) {
            st.key = standing.key;
            st.index = standing.index;
            st.roofY = standing.roofAt(living.getX(), living.getZ(),
                    cfg.trainCollisionHeightClamped());
            st.speedKmh = standing.speedKmh;
            st.standTick = now;
            clearFall(st);
            return;
        }
        if (st.standTick == now - 1L) {
            // 上一 tick 还在车顶、这一 tick 不在了：追踪这次掉落（车速取还站在上面时的值）。
            st.fallKey = st.key;
            st.fallIndex = st.index;
            st.fallRoofY = st.roofY;
            st.fallSpeedKmh = st.speedKmh;
            st.fallUntil = now + ROOF_FALL_TICKS;
            st.standTick = -1L;
        }
        if (st.fallKey == null) {
            return;
        }
        if (now > st.fallUntil) {
            clearFall(st);
            return;
        }
        if (!living.isOnGround() || Double.isNaN(st.fallRoofY)) {
            return;
        }
        final double drop = st.fallRoofY - living.getY();
        final double speed = st.fallSpeedKmh;
        clearFall(st);
        if (!(drop >= ROOF_FALL_MIN_DROP) || !cfg.trainCollisionDamage) {
            return;
        }
        final float hp = CollisionImpact.roofFallHp(living, cfg, speed);
        if (!(hp > 0.0F)) {
            return;
        }
        try {
            LteLog.LOGGER.info("[lte] roof-fall: v={}km/h drop={} hp={} by=server",
                    String.format(Locale.ROOT, "%.1f", Double.valueOf(speed)),
                    String.format(Locale.ROOT, "%.2f", Double.valueOf(drop)),
                    String.format(Locale.ROOT, "%.2f", Float.valueOf(hp)));
        } catch (Throwable ignored) {
            // 日志永远不许影响 tick。
        }
        if (CollisionImpact.applyRoofFall(living, hp, cfg)) {
            diagHp += hp;
        }
    }

    /** 清掉这条实体的掉落追踪。 */
    private static void clearFall(final Roof st) {
        st.fallKey = null;
        st.fallIndex = -1;
        st.fallRoofY = Double.NaN;
        st.fallSpeedKmh = 0.0D;
        st.fallUntil = -1L;
    }

    /** 刚从这节车顶掉下来（还没落地）？是则这一节车这一 tick 不参与撞击判定。 */
    private static boolean roofEscape(final Roof st, final LivingEntity living,
                                      final CarGeometry.Car car) {
        if (st.fallKey == null || car.index != st.fallIndex || !st.fallKey.equals(car.key)) {
            return false;
        }
        if (Double.isNaN(st.fallRoofY)) {
            return false;
        }
        if (living.getY() >= st.fallRoofY - CarGeometry.ROOF_ABOVE) {
            // 还在车顶平面之上（起跳 / 落回车顶的滞空）：车顶上方不该被车撞。
            return true;
        }
        // 车顶平面以下：只在还悬空时放过；落地之后就恢复正常（否则人会从车体里穿过去）。
        return !living.isOnGround();
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
