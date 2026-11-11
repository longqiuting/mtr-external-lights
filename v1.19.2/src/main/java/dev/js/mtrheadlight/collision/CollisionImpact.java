package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 列车撞击的<b>后果</b>：按车速分档的推开 / 扣血 / 撞飞 / 飞出，指数伤害与冲量。
 *
 * <p>几何判定（谁被撞、从哪个方向推出去）留在 {@link CollisionSystem}（本地玩家）与
 * {@link MobCollision}（其它生物）。这里只拿「实体 + 车速 + 推出方向」算后果，于是玩家和生物走的是
 * <b>同一套</b>公式、同一套冷却、同一套死亡消息。
 *
 * <h2>分档（全部可在 HeadlightConfig 里改，默认值见括号）</h2>
 *
 * <pre>
 * v &lt;  pushOnlyMax (5)                  PUSH    只推出，不扣血、不撞飞
 * v &lt;  lowDamageMax (20)                LOW     推出 + 低速率扣血
 * v &lt;  highDamageMax (50)               HIGH    推出 + 较高速率扣血
 * v &lt;  launchMax (80)                   LAUNCH  同上 + 撞飞冲量（水平远离车体，向上分量很小）
 * v &gt;= launchMax (80)                   FLY     更大的冲量，并且飞行/落地中持续扣血直到死亡
 * </pre>
 *
 * <h2>伤害：按 tick 积分，每 10 tick 用 {@code damage()} 结算一次</h2>
 *
 * <pre>
 * v0     = pushOnlyMaxKmh                       （伤害起点，默认 5 km/h）
 * scale  = damageSpeedScaleKmh                  （指数尺度，默认 100 km/h）
 * rate(v)= base · exp(k · (v − v0) / scale) · (v ≥ launchMax ? flyScale : 1)       [hp/s]
 * perTick= rate(v) / 20                                                             [hp/tick]
 *
 * 累积  : 每 tick 把 perTick 加进 pending（<b>不动血量、不动 timeUntilRegen</b>）
 * 结算  : 每 {@link #DAMAGE_INTERVAL_TICKS}（10）tick，一次
 *         {@code entity.damage(source, pending)}，致死那一笔也走它
 * </pre>
 *
 * <p>所以档位内不是一个固定值：车速每高一点，每秒扣的血按指数涨。{@code scale} 取 100 而不是
 * {@code v0}：{@code v0 = 5} 时 {@code exp(k(v−5)/5)} 在 50 km/h 已经是 {@code e^18}，玩家会瞬死；
 * 想要需求里那个「{@code base·exp(k(v−v0)/v0)}」的形式，把 {@code damageSpeedScaleKmh} 调成 5 即可。
 *
 * <p><b>为什么要攒着打</b>：旧实现每 tick 都 {@code timeUntilRegen = 0} 再 {@code damage()}，
 * 原版的受击状态（{@code hurtTime} —— 镜头倾斜、红色受伤叠层都来自它）于是每 tick 被重置一次：
 * 被车拖着走时画面一刻不停地在抖，而那点「碎伤害」又永远凑不到致死判定，死亡屏不弹，人就卡在
 * 「似死非死」。现在一秒最多结算两次（正好用满原版 10 tick 的无敌帧），镜头晃一下就停。
 *
 * <p><b>撞飞先于致死</b>：撞飞冲量写进 velocity 的同一 tick 把下一次结算窗口推到 +10 tick ——
 * 玩家先飞出去至少 0.5 秒，之后才吃第一笔伤害。
 *
 * <p>累积的上限是<b>实体最大生命</b>（{@link #OVERKILL_SCALE} = 1.5 倍余量，用来吃掉流血窗口里的
 * 自然回血；原版 {@code damage()} 到 0 血就停，多出来的不会变成溢出伤害）：一次接触/一次飞出最多
 * 扣掉这么多，之后不再加。显式配了 {@code trainCollisionDamageMax} 的侧线严格按配置。
 *
 * <h2>冲量</h2>
 *
 * <pre>
 * 距离    : d(v) = knockbackDistance · exp(knockbackExpK · (v − 50) / 50)   (格)
 *           50 = 参考车速（基准距离就是这个车速下的距离），结果钳制 1 … knockbackMax（默认 80）
 *           50 km/h ≈ 5.5 格、80 ≈ 10 格、120 ≈ 22 格、160 ≈ 50 格
 * 竖直    : launchVertical (0.45 格/tick) / flyVertical (0.60 格/tick)
 * 水平    : 由距离反推 —— 竖直初速先定滞空 n tick，再用平抛公式
 *           vx = 距离 / Σ(0.91^k, k = 0..n−1)        （空气摩擦 0.91/tick）
 * 方向    : 推出方向（车体侧面法向 / 端盖向外），水平归一化后乘以上面的水平值
 * 冷却    : 同一实体 impactCooldownTicks (20 tick = 1 s) 只结算一次冲量
 * </pre>
 *
 * <p>水平初速不再手填：{@link HeadlightConfig#trainCollisionLaunchHorizontal} 已废弃，只由
 * {@link #knockbackDistanceAt} 反推，所以「公式算 22 格」落地时就是 22 格
 * （离线逐 tick 积分见 {@code tools/knockback-curve.js}）。撞飞锁（{@link #impulseActive}）覆盖<b>整个滞空
 * 阶段直到落地</b>（{@link #grounded}：{@code isOnGround()} 或脚底离地 &lt; 0.1 格），否则下一 tick
 * 的「跟着车走」速度推送会把冲量压回去 —— 那正是「撞飞看着只挪了一格」的另一半原因。锁到落地
 * 还有个副作用是好的：落地后再被同一列车重复施加冲量/推送不会发生（除了 20 tick 冷却之外的第二次
 * 撞击）。
 *
 * <p><b>落地结算</b>：撞飞（真的施加了冲量）之后第一次落地，把「本次撞击还没扣完的预算」一次
 * 结算完（{@link #settleOnLanding}，上限 = {@code trainCollisionDamageMax}，未配时为
 * {@link #OVERKILL_SCALE} 倍最大生命）。否则 6 s 窗口下来人还在半血，落地也不死 —— 那就是
 * 「撞飞以后抖了很久才死亡」。
 *
 * <p>冲量与伤害分开：关掉 {@link HeadlightConfig#trainCollisionDamage} 只是不扣血，撞飞还在。
 */
public final class CollisionImpact {

    /** 分档。{@link #launches} = 这一档要不要施加撞飞冲量。 */
    public enum Tier {
        PUSH(false),
        LOW(false),
        HIGH(false),
        LAUNCH(true),
        FLY(true);

        private final boolean launches;

        Tier(final boolean launches) {
            this.launches = launches;
        }

        public boolean launches() {
            return this.launches;
        }
    }

    /** 速率上限（hp/s）：配置被手改成离谱数字时不至于把 exp 算成 Infinity。 */
    private static final double MAX_RATE = 200.0D;

    /**
     * 伤害结算周期（tick）：每这么多个 tick 才真正调用一次 {@code entity.damage()}。
     *
     * <p>必须是 10 的整数倍并且 <b>不小于 10</b>：原版受击后 {@code timeUntilRegen = 20}，在它
     * {@code > 10} 的那 10 tick 里只结算「超出上次伤害的部分」。取 10 正好卡在这一窗口之后，
     * 既不浪费（每笔都全额生效），也不会每 tick 重置受击状态（那正是镜头抖动的来源）。
     */
    public static final int DAMAGE_INTERVAL_TICKS = 10;

    /** 一次接触结束后多久清掉累积量（tick）：留一点余量给「被弹开又贴回来」。 */
    private static final long SESSION_GRACE = 10L;

    /**
     * 没显式配「本次接触上限」时，累计伤害允许打到最大生命的 1.5 倍。
     *
     * <p>多出来的那 50% <b>不会</b>变成溢出伤害（原版 {@code damage()} 到 0 血就停），它只用来抵消
     * 流血窗口里玩家的自然回血：卡在正好 {@code maxHealth} 时，回到 1 hp 就再也扣不动了，人卡在
     * 「似死非死」的另一个版本。显式配了 {@code trainCollisionDamageMax} 时仍严格按配置。
     */
    private static final double OVERKILL_SCALE = 1.5D;

    /** 状态表上限，防止异常实体把内存撑大。 */
    private static final int MAX_STATES = 512;

    /** 「没有这个时间戳」的哨兵（不取 0，0 是合法 tick 计数）。 */
    private static final long NEVER = Long.MIN_VALUE / 4L;

    /** 每个实体一条：冲量冷却、本次接触累计伤害、飞出标记。 */
    private static final class State {
        long lastTouch = NEVER;
        long lastImpulse = NEVER;
        /** 本 tick 已经累积过伤害（{@link #tick} / {@link #hurtDamage} / {@link #flightDamage} 不能各算一次）。 */
        long lastAccrue = NEVER;
        /**
         * 下一次真正调用 {@code damage()} 的 tick。哨兵 {@link #NEVER} = 「欠着一笔，下一 tick 就结算」
         * （上一次 {@code damage()} 被原版无敌帧吞掉时用）。
         */
        long nextFlush = NEVER;
        /** 已按速率累积、还没真正打到实体上的血量（hp）。 */
        double pending;
        /** 上一笔 {@code damage()} 没打进去：下一 tick 立刻重打，不再等一个窗口。 */
        boolean flushNow;
        /**
         * 撞飞冲量锁定（墙钟）。锁内不做「跟着车走」的速度推送，否则 1.8 格/tick
         * 的冲量会被压回 1.1 —— 撞飞就完全看不出来。用墙钟是因为写冲量的是服务端线程、
         * 判推送的是客户端 tick 计数。
         *
         * <p>锁的解除条件不是固定时长，而是<b>落地</b>（{@link #grounded}）；{@link #impulseMaxMs}
         * 只是兜底（掉进虚空 / 落在非方块上时别把推送永久关掉）。
         */
        boolean impulseLock;
        /** 冲量写进 velocity 的墙钟时间（ms）。落地宽限从这里起算。 */
        long impulseSinceMs;
        /** 冲量锁的兜底截止（ms）。 */
        long impulseMaxMs;
        /**
         * 持续掉血窗口的截止 tick：撞击那一刻 = {@code now + trainCollisionHurtTicks}。
         * 窗口内不碰到车也继续按 {@link #speed} 扣血，见 {@link #hurtDamage}。
         */
        long hurtUntil = NEVER;
        /**
         * 同一个窗口的墙钟截止（ms）。{@link #hurtUntil} 用的是写入者的 tick 计数，客户端
         * （{@link #suppressHurtTilt}）与服务端的计数相差几万，所以镜头抑制用这条看。
         */
        long hurtUntilMs;
        /** 撞飞冲量写下去的那一刻（写入者的 tick 轴），落地判定用它跳过「还没起飞」的几 tick。 */
        long launchTick = NEVER;
        /** 撞飞之后已经落地结算过（一次撞击只结算一次）。 */
        boolean landed;
        /**
         * 我们上一次<b>真的</b>把撞击伤害打上去的墙钟时间（ms）。
         *
         * <p>只服务一件事：客户端据此判断「这一下 hurtTime 是我们造成的」，把它清掉（见
         * {@link #suppressHurtTilt}）。用墙钟是因为写它的是服务端线程、读它的是客户端 tick。
         */
        long lastDamageMs;
        /** 本次接触/本次飞出累计已经扣掉的血量（hp）。 */
        double applied;
        /** 撞击时的车速（飞出阶段继续用它算速率）。 */
        double speed;
        /** 死亡消息走高速那一段。 */
        boolean fast;
        /** &gt;= launchMax 撞的：落地/飞行中持续扣血直到死亡。 */
        boolean launched;
        /**
         * 已经打出过「必死那一击」（见 {@link #damage}）：raw 伤害已经花掉一整条血还是一点血都
         * 没掉（护甲减伤 / 抗性 / 吸收 / 外部 mod 取消），于是补一记足够大的伤害确保真的死。
         * 只打一次，避免每 10 tick 一记 40 倍伤害。
         */
        boolean finishing;
        /** 这一次冲量用的水平初速（格/tick），只给日志/诊断看。 */
        double launchVx;
        /** 「为什么不吃伤害」已经打过一行日志（每个实体一次，避免刷屏）。 */
        boolean skipLogged;
        /**
         * 撞击那一刻解析出来的伤害规则（按列车所属侧线）：飞行阶段的持续伤害沿用它，
         * 中途改配置 / 列车换侧线不会改写一次接触的结果。
         */
        boolean damageOn = true;
        /** 本次接触的累计伤害上限（hp）；0 = 只受实体最大生命限制。 */
        double damageMax;
        /** 伤害起点 v0（km/h）。 */
        double v0 = 5.0D;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private CollisionImpact() {
    }

    // ---------------------------------------------------------------------------------------------
    // 档位与公式
    // ---------------------------------------------------------------------------------------------

    /** 车速落在哪一档（全局值）。 */
    public static Tier tierOf(final HeadlightConfig cfg, final double speedKmh) {
        return tierOf(cfg, cfg.trainCollisionPushOnlyMaxKmhClamped(), speedKmh);
    }

    /**
     * 车速落在哪一档，伤害起点 {@code v0} 取这列车所在侧线的解析值
     * （{@link HeadlightConfig.Effective#trainCollisionDamageMinSpeedKmh}）。
     */
    public static Tier tierOf(final HeadlightConfig cfg, final HeadlightConfig.Effective eff,
                              final double speedKmh) {
        return tierOf(cfg, eff == null ? cfg.trainCollisionPushOnlyMaxKmhClamped()
                : eff.trainCollisionDamageMinSpeedKmh, speedKmh);
    }

    private static Tier tierOf(final HeadlightConfig cfg, final double v0, final double speedKmh) {
        if (speedKmh < v0) {
            return Tier.PUSH;
        }
        // 上界都不低于 v0：侧线把 v0 调到比某个档位还高时，档位不会反着落回去。
        if (speedKmh < Math.max(v0, cfg.trainCollisionLowDamageMaxKmhClamped())) {
            return Tier.LOW;
        }
        if (speedKmh < Math.max(v0, cfg.trainCollisionHighDamageMaxKmhClamped())) {
            return Tier.HIGH;
        }
        if (speedKmh < Math.max(v0, cfg.trainCollisionLaunchMaxKmhClamped())) {
            return Tier.LAUNCH;
        }
        return Tier.FLY;
    }

    /**
     * 指数伤害速率（hp/s）。见类注释里的公式；车速不到 {@code v0} 时为 0。
     */
    public static double damagePerSecond(final HeadlightConfig cfg, final double speedKmh) {
        return damagePerSecond(cfg, cfg.trainCollisionPushOnlyMaxKmhClamped(), speedKmh);
    }

    /** 同上，{@code v0} 由调用方给（侧线覆盖的伤害起点）。 */
    private static double damagePerSecond(final HeadlightConfig cfg, final double v0,
                                          final double speedKmh) {
        if (speedKmh < v0) {
            return 0.0D;
        }
        final double base = cfg.trainCollisionDamageBasePerSecondClamped();
        if (!(base > 0.0D)) {
            return 0.0D;
        }
        final double k = cfg.trainCollisionDamageExpKClamped();
        final double scale = cfg.trainCollisionDamageSpeedScaleKmhClamped();
        double rate = base * Math.exp(k * (speedKmh - v0) / scale);
        if (speedKmh >= cfg.trainCollisionLaunchMaxKmhClamped()) {
            rate *= cfg.trainCollisionFlyDamageScaleClamped();
        }
        if (!(rate > 0.0D)) {
            return 0.0D;
        }
        return Math.min(MAX_RATE, rate);
    }

    /**
     * 撞飞档的水平初速（格/tick）。
     *
     * @deprecated 已被 {@link #knockbackHorizontal(HeadlightConfig, Tier, double)} 取代；
     *             保留这个重载只为旧调用点，车速未知时按 {@code v0 + 50}（约 10 格）估算。
     */
    @Deprecated
    public static double launchHorizontal(final HeadlightConfig cfg, final Tier tier) {
        return knockbackHorizontal(cfg, tier, cfg.trainCollisionPushOnlyMaxKmhClamped() + 50.0D);
    }

    /**
     * 撞飞档的竖直初速（格/tick）。
     */
    public static double launchVertical(final HeadlightConfig cfg, final Tier tier) {
        return tier == Tier.FLY
                ? cfg.trainCollisionFlyVerticalClamped()
                : cfg.trainCollisionLaunchVerticalClamped();
    }

    /**
     * 撞飞距离的速度尺度与参考速度（km/h）：
     * {@code d(v) = base · exp(k · (v − 50) / 50)}。
     *
     * <p>取固定的 50 而不是伤害起点 {@code trainCollisionPushOnlyMaxKmh}（默认 5）或侧线的覆盖值：
     * 那个值可以低到 0、也可以高到 300，直接当 {@code v0} 用会让 {@code exp} 在同一档位内
     * 差出几十倍（{@code v0 = 10} 时 100 km/h 就吃掉 {@code e^9}，人直接被扔出区块）。
     * 固定的 50 让「每快 50 km/h 距离乘 {@code e^k}」成为一条与档位、侧线都无关的手感规则，
     * 也让默认值正好满足「50 km/h = 基准距离」。
     */
    public static final double KNOCKBACK_EXP_SCALE_KMH = 50.0D;

    /** 基准距离对应的车速（km/h），见 {@link #KNOCKBACK_EXP_SCALE_KMH}。 */
    public static final double KNOCKBACK_REF_KMH = 50.0D;

    /**
     * 这个车速要飞多远（格）。
     *
     * <p>{@code d(v) = knockbackDistance · exp(knockbackExpK · (v − 50) / 50)}，钳制
     * 1 … {@code knockbackMax}。车速低于 50 时曲线照样算（只是更短），下限 1 格保证
     * 「被撞飞」至少离开原来的位置；真正会不会飞由 {@link Tier#launches()} 决定。
     *
     * <p>{@code exp} 的指数先做上界判断：{@code k} 上限 3、车速 400 时指数是 21，{@code exp(21)}
     * 还有效但已经远大于任何上限，直接返回 {@code knockbackMax} 更省事，也顺手挡掉
     * {@code exp} 溢出成 {@code Infinity} 之后 {@code min} 的比较。
     */
    public static double knockbackDistanceAt(final HeadlightConfig cfg, final double speedKmh) {
        final double base = cfg.knockbackDistanceClamped();
        final double k = cfg.knockbackExpKClamped();
        final double max = cfg.knockbackMaxClamped();
        final double exponent = k * (speedKmh - KNOCKBACK_REF_KMH) / KNOCKBACK_EXP_SCALE_KMH;
        if (!(exponent < 20.0D)) {
            // exp(20) ≈ 4.9e8，早已越界；也让 NaN / Infinity 走同一条路（!(x < 20) 对 NaN 为真）。
            return max;
        }
        final double d = base * Math.exp(exponent);
        if (!(d > 0.0D)) {
            return 1.0D;
        }
        return Math.min(max, Math.max(1.0D, d));
    }

    /** 重力（格/tick²），与 {@code LivingEntity#travel} 的 0.08 一致。 */
    private static final double GRAVITY = 0.08D;

    /** 竖直阻尼（每 tick），与 {@code LivingEntity#travel} 的 0.98 一致。 */
    private static final double VERTICAL_DRAG = 0.98D;

    /** 空中水平阻尼（每 tick），与 {@code LivingEntity#travel} 的 0.91 一致。 */
    private static final double HORIZONTAL_DRAG = 0.91D;

    /**
     * 竖直初速 {@code vy} 对应的滞空 tick 数：逐 tick 积分
     * {@code vy = (vy − 0.08)·0.98}，到 {@code y ≤ 0} 为止。
     *
     * <p>这就是「平抛公式」里的滞空 {@code t}；0.08 / 0.98 是原版 {@code LivingEntity#travel}
     * 的常数，所以离线算出来的 tick 数和实机一致（0.45 → 10 tick，0.60 → 14 tick）。落地那一 tick
     * 也算：原版是<b>先 {@code move(velocity)} 再</b>衰减速度，所以最后一段水平位移照样发生。
     */
    public static int flightTicks(final double vy) {
        if (!(vy > 0.0D)) {
            return 1;
        }
        double y = 0.0D;
        double v = vy;
        for (int t = 1; t <= 200; t++) {
            v -= GRAVITY;
            y += v;
            if (y <= 0.0D && t > 1) {
                return t;
            }
            v *= VERTICAL_DRAG;
        }
        return 200;
    }

    /**
     * 单位水平初速（1 格/tick）能飞多远：{@code Σ(0.91^k, k = 0..n−1)}。
     *
     * <p>位移用「衰减前」的速度 —— 原版每 tick 先 {@code move(velocity)} 再把水平速度乘 0.91。
     */
    public static double horizontalReach(final double vy) {
        final int n = flightTicks(vy);
        double sum = 0.0D;
        double k = 1.0D;
        for (int i = 0; i < n; i++) {
            sum += k;
            k *= HORIZONTAL_DRAG;
        }
        return sum;
    }

    /**
     * 撞飞/飞出的水平初速（格/tick）—— 由 {@link #knockbackDistanceAt} 反推：
     * {@code vx = d(v) / Σ(0.91^k)}。
     *
     * <p>竖直初速越小，滞空越短，同样的距离需要越大的水平速度；所以这个反推对配置里的竖直初速
     * 完全自适应，改竖直不改距离。反推之后再用同一套系数逐 tick 积分，落地位移就等于公式值
     * （{@code tools/knockback-curve.js} 里对四档都验过）。
     *
     * <p>上限 8 / 12 格/tick 只是防御：默认配置下 160 km/h 反推约 6.7 格/tick（80 格上限时
     * 10.8），只有把竖直改成 0.01 这种离谱值才会撞上限（那时人也飞不出公式值，属于配置自找的）。
     */
    public static double knockbackHorizontal(final HeadlightConfig cfg, final Tier tier,
                                             final double speedKmh) {
        final double vy = launchVertical(cfg, tier);
        final double reach = Math.max(1.0E-3D, horizontalReach(vy));
        final double vx = knockbackDistanceAt(cfg, speedKmh) / reach;
        final double cap = tier == Tier.FLY ? 12.0D : 8.0D;
        return Math.min(cap, Math.max(0.0D, vx));
    }

    /** 这个实体的接触是不是还在「本次接触」里（诊断/测试用）。 */
    public static double applied(final UUID id) {
        final State s = STATES.get(id);
        return s == null ? 0.0D : s.applied;
    }

    /** 这个实体是否处于「飞出后持续扣血直到死亡」的状态。 */
    public static boolean launched(final UUID id) {
        final State s = STATES.get(id);
        return s != null && s.launched;
    }

    /**
     * 该实体是不是<b>还在被撞飞后的滞空阶段</b>（冲量锁到落地为止）。
     *
     * <p>{@code CollisionSystem} / {@code ServerCollision} 用它决定「要不要再做速度推送」：撞飞冲量
     * （1.0 / 1.8 格/tick + 竖直分量）刚写进 velocity，如果同一 tick 又被「跟着车走」的推送覆盖掉，
     * 人就飞不出去，只是贴着车头滑行 —— 这正是「撞飞看不出效果」的一半原因。
     *
     * <p><b>解锁条件是落地</b>（见 {@link #grounded}），不是固定时长：滞空多久就锁多久，落地立刻
     * 解除，落地后就不会再被同一列车反复推着「抖」。{@link State#impulseMaxMs} 和
     * {@link #IMPULSE_GROUND_GRACE_MS} 只是兜底与起飞快照。
     */
    public static boolean impulseActive(final LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        final State s = STATES.get(entity.getUuid());
        if (s == null || !s.impulseLock) {
            return false;
        }
        final long nowMs = System.currentTimeMillis();
        if (nowMs - s.impulseSinceMs < IMPULSE_GROUND_GRACE_MS) {
            // 冲量写下去的那一 tick 人还踩在地上（速度下一 tick 才生效）：别刚起飞就解锁。
            return true;
        }
        if (nowMs >= s.impulseMaxMs) {
            // 兜底：滞空太久（掉虚空 / 落在半砖边缘反复判定）就放行，别把推送永久关掉。
            s.impulseLock = false;
            return false;
        }
        if (grounded(entity)) {
            s.impulseLock = false;
            return false;
        }
        return true;
    }

    /** 落地宽限（ms）：冲量写入后的这一小段不判落地（那时人还站在原来的地面上）。 */
    private static final long IMPULSE_GROUND_GRACE_MS = 250L;

    /** 落地探针高度（格）：脚底往下这么多格内碰到方块就算落地。 */
    private static final double GROUND_PROBE = 0.1D;

    /** 冲量锁的最短时长（tick）：滞空 tick + 4 还不够时兜底（防止竖直初速被配成 0）。 */
    private static final long IMPULSE_LOCKOUT_MIN = 8L;

    /** 冲量锁的兜底时长（tick）：再久也要解锁，防止异常状态把速度推送永久关掉。 */
    private static final long IMPULSE_LOCKOUT_MAX = 100L;

    /**
     * 落地了吗：{@code isOnGround()}，或者脚底往下 {@value #GROUND_PROBE} 格内已经有方块
     * （原版 {@code onGround} 在部分台阶 / 车顶 / 水面边缘会迟一 tick，用高度探针补齐）。
     */
    private static boolean grounded(final LivingEntity entity) {
        if (entity.isOnGround()) {
            return true;
        }
        try {
            final Box box = entity.getBoundingBox().offset(0.0D, -GROUND_PROBE, 0.0D);
            return !entity.getWorld().isSpaceEmpty(entity, box);
        } catch (Throwable ignored) {
            // 拿不到世界（实体正在被移除）：当作还在空中，交给兜底时长。
            return false;
        }
    }

    /**
     * 把冲量<b>同步到客户端</b>的本地玩家实例。
     *
     * <p>为什么需要：单人里玩家的位置是<b>客户端权威</b>的。服务端 {@code ServerPlayerEntity}
     * 上的 {@code setVelocity} 只会随实体跟踪包发给<b>其他</b>玩家，本人收不到 —— 于是配置里写
     * 10 格，画面上只被「跟着车走」的推送挪了一两格。这里把同一组速度写进本进程的
     * {@code ClientPlayerEntity}，位移才真的发生。多人（没有本进程客户端）时 hook 为 null，
     * 那一路由服务端 velocity 包负责。
     */
    public interface ImpulseMirror {
        void push(UUID id, double x, double y, double z);
    }

    private static volatile ImpulseMirror mirror;

    /** 客户端入口（{@code CollisionBootstrap}）装一次。 */
    public static void installImpulseMirror(final ImpulseMirror hook) {
        mirror = hook;
    }

    /**
     * 这个实体的这次撞击算不算「高速」（死亡消息走 {@code trainHigh} 那一段）。
     *
     * <p>必须用<b>撞击时</b>记下的车速快照，不能拿当前 tick 的车速重新比：致死那一击往往发生在
     * 车早就开走之后（持续掉血窗口），那时 {@code hitSpeed} 根本拿不到，死亡消息就会退回低速段。
     */
    public static boolean wasFast(final UUID id) {
        final State s = STATES.get(id);
        return s != null && s.fast;
    }

    /** 进程内累计施加过多少次撞飞冲量（诊断行 {@code impulses=}）。 */
    public static long impulsesTotal() {
        return IMPULSES;
    }

    private static long IMPULSES;

    // ---------------------------------------------------------------------------------------------
    // 每 tick 的结算
    // ---------------------------------------------------------------------------------------------

    /**
     * 一 tick 的撞击后果：档位 → （可选）冲量 → 累积伤害（到结算窗口才真正打出去）。
     *
     * <p><b>冲量与伤害是两件事</b>：{@code eff.trainCollisionDamage == false} 只让
     * {@link #damage} 攒不起伤害，档位判定与 {@link #impulse} 完全不受影响 —— 伤害关掉时人仍然会被
     * 推开 / 撞飞，车不会直接穿过人。这条规则在侧线覆盖下同样成立（侧线关掉伤害 ≠ 关掉碰撞）。
     *
     * <p><b>顺序</b>：冲量在伤害之前。撞飞档这一 tick 只给速度，伤害结算窗口被推到
     * {@code now + DAMAGE_INTERVAL_TICKS}，于是玩家先飞出去 ≥0.5 s，才吃第一笔血。
     *
     * @param dirX 推出方向（水平，不必归一化；全零则只扣血不撞飞）
     * @param eff  这列车所在侧线解析出来的伤害规则；{@code null} = 全局
     * @return 本 tick <b>要真正打到实体上</b>的血量（hp）；0 = 这一 tick 只累积 / 没伤害
     */
    public static float tick(final LivingEntity entity, final HeadlightConfig cfg,
                             final HeadlightConfig.Effective eff, final double speedKmh,
                             final double dirX, final double dirZ, final long now) {
        if (entity == null) {
            return 0.0F;
        }
        if (dead(entity)) {
            // 死亡后停止一切：不推、不撞、不扣。旧版死后下一 tick 还会推人、还会清无敌帧，
            // 于是「人已经死了，画面却还在抖」。
            forget(entity.getUuid());
            return 0.0F;
        }
        final Tier tier = tierOf(cfg, eff, speedKmh);
        if (tier == Tier.PUSH) {
            // 只推开：几何那边已经推过了，这里什么也不做。
            return 0.0F;
        }

        final State s = state(entity);
        if (!s.launched && now > s.hurtUntil && now - s.lastTouch > SESSION_GRACE) {
            // 上一次接触已经结束（车速掉下去 / 被弹开）：重新开始积分，不然「一次性上限 =
            // 最大生命」会把第二次撞击直接免掉。持续掉血窗口还没走完时不清零 ——
            // 车开走之后人在流血，那一段的累计必须接着算。
            s.applied = 0.0D;
            s.pending = 0.0D;
            s.nextFlush = now + DAMAGE_INTERVAL_TICKS;
        }
        s.lastTouch = now;
        s.speed = speedKmh;
        s.fast = tier == Tier.FLY || speedKmh >= cfg.trainCollisionHighDamageMaxKmhClamped();
        // 「飞出阶段」（launched）只在<b>真的施加了冲量</b>时打开（见下面的 impulse 分支）：它决定
        // 落地结算与飞行伤害，没飞起来的一次擦碰不该走那条路。
        // 撞击后的持续掉血窗口：只要这一档要扣血，就延续窗口（窗口内不碰车也继续扣）。
        final int hurtTicks = cfg.trainCollisionHurtTicksClamped();
        if (hurtTicks > 0) {
            final long until = now + hurtTicks;
            if (until > s.hurtUntil) {
                s.hurtUntil = until;
                // 镜头抑制读的是墙钟（客户端 tick 与服务端 tick 不同源），所以窗口同时记两份。
                s.hurtUntilMs = System.currentTimeMillis() + hurtTicks * 50L;
            }
        }
        // 伤害规则快照：飞行阶段的持续伤害要用它，而不是下一 tick 重新读一遍配置。
        s.damageOn = eff == null || eff.trainCollisionDamage;
        s.damageMax = eff == null ? cfg.trainCollisionDamageMaxClamped() : eff.trainCollisionDamageMax;
        s.v0 = eff == null ? cfg.trainCollisionPushOnlyMaxKmhClamped()
                : eff.trainCollisionDamageMinSpeedKmh;

        boolean pushed = false;
        // 冲量：一次滞空只有一发。锁还在（空中）时不再重复施加 —— 以前撞飞后每 20 tick 冷却一到就
        // 又推一次，人被同一列车反复「弹」，落地后还在抖。用 impulseActive 而不是裸标志：它顺带
        // 做落地判定并解锁（地面 / 脚底离地 < 0.1 格），落地后立刻恢复正常推送。
        if (tier.launches()
                && !impulseActive(entity)
                && now - s.lastImpulse >= cfg.trainCollisionImpactCooldownTicksClamped()) {
            s.lastImpulse = now;
            // 不看 s.damageOn：伤害关掉时撞飞照旧，否则高速车会「穿过」人。
            final double vx = impulse(entity, cfg, tier, speedKmh, dirX, dirZ);
            if (vx > 0.0D) {
                pushed = true;
                s.launchVx = vx;
                // 撞飞：这一次接触转成「飞出阶段」，落地时一次结算完剩下的伤害预算。
                s.launched = true;
                s.landed = false;
                s.launchTick = now;
                // 冲量锁：<b>到落地为止</b>（不是固定 tick 数）谁也别再把「跟车走」的速度写进来。
                // 落地判定见 CollisionImpact#grounded：isOnGround() 或脚底离地 < 0.1 格。
                s.impulseLock = true;
                s.impulseSinceMs = System.currentTimeMillis();
                final int air = (int) Math.max(IMPULSE_LOCKOUT_MIN,
                        flightTicks(launchVertical(cfg, tier)) + 4);
                s.impulseMaxMs = s.impulseSinceMs
                        + Math.min(IMPULSE_LOCKOUT_MAX, Math.max(air, 40)) * 50L;
            }
            // 撞飞先于致死：这一 tick 不给伤害，下一次结算窗口从「落地」附近开始。
            s.nextFlush = now + DAMAGE_INTERVAL_TICKS;
        }

        final float hp = damage(entity, cfg, s, now);
        logImpact(cfg, now, speedKmh, tier, s, pushed);
        return hp;
    }

    /** 死亡 = 不可再碰。{@code isAlive()} 之外再挡 {@code isDead()} 与 0 血（死亡动画期间两者短暂不一致）。 */
    private static boolean dead(final LivingEntity entity) {
        return !entity.isAlive() || entity.isDead() || !(entity.getHealth() > 0.0F);
    }

    /**
     * 飞出阶段的持续伤害：不管这一 tick 还碰没碰到车，都按撞击车速继续扣，直到<b>落地结算</b>、窗口
     * 结束或死亡。
     *
     * <p>落地那一 tick 把「本次撞击还没扣完的预算」一次打出去（{@link #settleOnLanding}）——
     * 撞飞之后人已经飞出去几格、处在自由落体里，没有必要再按 hp/s 磨 6 秒；一次结算到位，死亡屏
     * 在落地那一刻就弹出来，镜头也不会再抖。
     *
     * @return 本次结算窗口该真正打到实体上的血量（hp）；0 = 只累积 / 没到窗口 / 已经结清
     */
    public static float flightDamage(final LivingEntity entity, final HeadlightConfig cfg,
                                     final long now) {
        if (entity == null) {
            return 0.0F;
        }
        final State s = STATES.get(entity.getUuid());
        if (s == null || !s.launched) {
            return 0.0F;
        }
        if (dead(entity)) {
            forget(entity.getUuid());
            return 0.0F;
        }
        s.lastTouch = now;
        // 第一次落地：一次结算到位。跳过起飞的几 tick（冲量写下去时人还站在地面上）。
        if (!s.landed && s.launchTick != NEVER && now - s.launchTick > LANDING_GRACE_TICKS
                && grounded(entity)) {
            s.landed = true;
            s.launched = false;
            return settleOnLanding(entity, cfg, s, now);
        }
        // 6 s 窗口内既没落地也没死（掉虚空 / 挂在半空）：收手，不再一路磨下去。
        if (s.hurtUntil != NEVER && now > s.hurtUntil) {
            s.launched = false;
            closeWindow(s);
            return 0.0F;
        }
        return damage(entity, cfg, s, now);
    }

    /** 落地判定的宽限（tick）：起飞后的头几 tick 人还贴着地面，不能算落地。 */
    private static final long LANDING_GRACE_TICKS = 4L;

    /**
     * 落地一次结算：把本次撞击「还没扣完的预算」整笔打出去。
     *
     * <pre>
     * 预算 = trainCollisionDamageMax &gt; 0 ? min(最大生命, trainCollisionDamageMax) : 最大生命 × 1.5
     * 本次落地伤害 = 预算 − 已经扣掉的（&lt;= trainCollisionDamageMax，永远不会超）
     * </pre>
     *
     * <p>比按 hp/s 磨的好处：落地那一 tick 就出结果。伤害仍然是正规的原版 {@code damage()}（调用方
     * 走 {@link #applyDamage}），致死消息与其它撞击伤害完全一致。
     */
    private static float settleOnLanding(final LivingEntity entity, final HeadlightConfig cfg,
                                         final State s, final long now) {
        // 窗口只再留两笔，给「减伤把这一笔吃掉」留一次「必死那一击」的机会（见 damage()）。
        s.hurtUntil = now + DAMAGE_INTERVAL_TICKS * 2L;
        s.hurtUntilMs = System.currentTimeMillis() + DAMAGE_INTERVAL_TICKS * 100L;
        if (!s.damageOn || !damageable(entity, cfg)) {
            closeWindow(s);
            return 0.0F;
        }
        final double cap = damageCap(entity, s);
        if (!(cap > 0.0D)) {
            closeWindow(s);
            return 0.0F;
        }
        final double remaining = cap - s.applied;
        if (!(remaining > 0.0D)) {
            closeWindow(s);
            return 0.0F;
        }
        s.applied = cap;
        s.pending = 0.0D;
        s.lastAccrue = now;
        s.nextFlush = now + DAMAGE_INTERVAL_TICKS;
        s.flushNow = false;
        LteLog.LOGGER.info("[lte] landing: settle={} hp={} max={} cap={}",
                format2((float) remaining), format2(entity.getHealth()),
                format2(entity.getMaxHealth()), format2((float) cap));
        return (float) remaining;
    }

    /** 本次接触的累计伤害预算（hp）：侧线显式配了 {@code trainCollisionDamageMax} 就按它，否则 1.5 倍最大生命。 */
    private static double damageCap(final LivingEntity entity, final State s) {
        double cap = Math.max(0.0D, entity.getMaxHealth()) * OVERKILL_SCALE;
        if (s.damageMax > 0.0D) {
            cap = Math.min(Math.max(0.0D, entity.getMaxHealth()), s.damageMax);
        }
        return cap;
    }

    /** 关掉持续掉血窗口（伤害预算已花完 / 已经结算过）：镜头抑制随之停止，不再影响别的伤害源。 */
    private static void closeWindow(final State s) {
        s.hurtUntil = NEVER;
        s.hurtUntilMs = 0L;
        s.launched = false;
        s.pending = 0.0D;
    }

    /**
     * 撞击后的<b>持续掉血</b>：窗口（{@code trainCollisionHurtTicks}）内即使没再碰到车，
     * 也按撞击车速继续累积，直到窗口结束或死亡。
     *
     * <p>没有它「15–25 秒致死」不可能成立：接触只持续几 tick，接触期内的伤害总共只有 2–3 hp。
     * 它和 {@link #flightDamage} 的区别只是条件 —— 飞出档要 {@code launched}，其它档只要能扣血的
     * 撞击都开窗口。两者可以叠加（飞出档同时满足），所以用 {@link State#lastAccrue} 保证一 tick
     * 只累积一次。
     *
     * @return 本次结算窗口该真正打到实体上的血量（hp），0 = 没到窗口 / 已经结束 / 这一 tick 已累积过
     */
    public static float hurtDamage(final LivingEntity entity, final HeadlightConfig cfg,
                                   final long now) {
        if (entity == null) {
            return 0.0F;
        }
        final State s = STATES.get(entity.getUuid());
        if (s == null || now > s.hurtUntil) {
            return 0.0F;
        }
        if (dead(entity)) {
            forget(entity.getUuid());
            return 0.0F;
        }
        return damage(entity, cfg, s, now);
    }

    /**
     * 状态表兜底：只做<b>容量</b>清理，不按时钟删。
     *
     * <p>为什么不做时间清理：状态可能由三条路径写（客户端 tick、整合服务器 tick、dedicated
     * server tick），三者的 tick 计数起点不同（{@code ClientTickEvents} 的计数和
     * {@code MinecraftServer#getTicks()} 差着几万），用一个计数去判断另一个写的时间戳会把活着的
     * 状态误删。接触是否结束改由 {@link #tick} 在<b>同一个写入者</b>的时间轴上判断（
     * {@code now - lastTouch > SESSION_GRACE} 时重置累积量）。
     */
    public static void sweep(final long now) {
        if (STATES.size() <= MAX_STATES) {
            return;
        }
        STATES.entrySet().removeIf(entry -> !entry.getValue().launched);
    }

    /** 世界切换 / 实体消失时清掉它。 */
    public static void forget(final UUID id) {
        if (id != null) {
            STATES.remove(id);
        }
    }

    /** 清空全部状态（换世界）。 */
    public static void clear() {
        STATES.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // 伤害 / 冲量
    // ---------------------------------------------------------------------------------------------

    private static State state(final LivingEntity entity) {
        return STATES.computeIfAbsent(entity.getUuid(), id -> new State());
    }

    /**
     * 本 tick 的伤害：<b>只累积</b>，攒到结算窗口（{@link #DAMAGE_INTERVAL_TICKS}）才返回要打出去的量。
     *
     * <p>累积上限是实体最大生命（{@link #OVERKILL_SCALE} 倍余量，或侧线显式配的「本次接触上限」）：
     * 一次接触最多扣掉这么多，之后不再加。真正的 {@code damage()} 由调用方在返回值 &gt; 0 时执行
     * （{@link #applyDamage}），所以致死那一笔也一定是一次正规的原版伤害。
     *
     * <p><b>「必死那一击」兜底</b>：raw 预算已经花掉<b>一整条血</b>（{@code applied >= maxHealth}）
     * 而实体还活着，说明这一条血的 raw 伤害被减伤（保护附魔 / 抗性 / 吸收）或外部 mod 吃掉了一部分 ——
     * 再按 0.6 hp/10 tick 磨下去，死亡屏永远不会出现。这时补一记足够大的伤害（当前血量 ×8 + 20，
     * 保护 IV 的 64% 减伤也压不住），只打一次（{@link State#finishing}）。创造 / 旁观 / 无敌模式 /
     * 无敌标志在 {@link #damageable} 已经挡掉，所以这一击不会「强行杀死无敌的人」。
     *
     * @return 本次结算窗口该打到实体上的血量（hp）；0 = 这一 tick 只累积
     */
    private static float damage(final LivingEntity entity, final HeadlightConfig cfg,
                                final State s, final long now) {
        if (!s.damageOn) {
            return 0.0F;
        }
        if (!damageable(entity, cfg)) {
            if (!s.skipLogged) {
                s.skipLogged = true;
                LteLog.LOGGER.info("[lte] damage: skipped - {} (v={} tier-state launched={})",
                        skipReason(entity, cfg), format2((float) s.speed),
                        Boolean.valueOf(s.launched));
            }
            return 0.0F;
        }
        if (s.lastAccrue == now) {
            // 已经被另一条路径（contact / hurt / flight）累积过这一 tick 了。
            return 0.0F;
        }
        double cap = damageCap(entity, s);
        if (!(cap > 0.0D)) {
            return 0.0F;
        }
        if (s.applied >= entity.getMaxHealth() && !s.finishing) {
            // 一整条血都打过了人还没死：减伤/吸收/外部取消。补一记必死伤害（只一次）。
            s.finishing = true;
            s.lastAccrue = now;
            final double finish = Math.max(4.0D, entity.getHealth()) * 8.0D + 20.0D;
            LteLog.LOGGER.info("[lte] damage: finishing blow (raw spent={} hp={} max={} cap={})",
                    format2((float) s.applied), format2(entity.getHealth()),
                    format2(entity.getMaxHealth()), format2((float) cap));
            return (float) finish;
        }
        if (s.applied >= cap) {
            // 预算花完：窗口也一起关掉（镜头抑制只在真的有伤害要来的窗口里生效）。
            closeWindow(s);
            return 0.0F;
        }
        final double perTick = damagePerSecond(cfg, s.v0, s.speed) / 20.0D;
        if (!(perTick > 0.0D)) {
            return 0.0F;
        }
        final double amount = Math.min(perTick, cap - s.applied);
        if (!(amount > 0.0D)) {
            return 0.0F;
        }
        s.applied += amount;
        s.pending += amount;
        s.lastAccrue = now;
        if (s.nextFlush == NEVER) {
            // 本次接触的第一笔：从这一 tick 起算一个完整窗口（撞飞档由 tick() 推到冲量之后）。
            s.nextFlush = now + DAMAGE_INTERVAL_TICKS;
            return 0.0F;
        }
        if (!s.flushNow && now < s.nextFlush) {
            return 0.0F;
        }
        // 到点了：把这段时间攒下的一整笔交出去。窗口按 tick 对齐（跳过的 tick 不会挤成一次超大伤害）。
        s.flushNow = false;
        s.nextFlush = Math.max(now + DAMAGE_INTERVAL_TICKS, s.nextFlush + DAMAGE_INTERVAL_TICKS);
        final float out = (float) s.pending;
        s.pending = 0.0D;
        return out;
    }

    /**
     * 上一笔 {@code damage()} 没打进去（原版无敌帧 / 事件取消）：把血量退回去，下一 tick 立刻重试。
     * 这样「每 10 tick 一笔」不会因为边界上的一个 tick 差异丢掉那半滴血。
     */
    public static void requeue(final LivingEntity entity, final float amount) {
        if (entity == null || !(amount > 0.0F)) {
            return;
        }
        final State s = STATES.get(entity.getUuid());
        if (s == null) {
            return;
        }
        s.pending += amount;
        s.applied = Math.max(0.0D, s.applied - amount);
        s.flushNow = true;
    }

    /**
     * 创造 / 旁观 / 无敌 / 已死 都不吃撞击伤害。
     *
     * <p><b>无敌模式</b>也有两种，必须都挡：{@link net.minecraft.entity.Entity#isInvulnerable()} 是
     * 实体 NBT 上那个 {@code invulnerable} 标志；玩家的「神明模式 / god mode」则是
     * {@code PlayerAbilities#invulnerable} —— {@code PlayerEntity#damage()} 第一行就因为后者
     * {@code return false}，而 {@code isInvulnerable()} 看不见它。旧代码漏了这一条，于是「伤害一直在
     * 累计、血却一滴不掉、死亡屏永远不弹」。这里挡掉之后不再空攒 {@code applied}，日志也会说明原因。
     *
     * <p>{@code cfg.damageInCreative} 打开时跳过创造 / abilities.invulnerable 这一条，
     * 让创造模式玩家也能被撞伤；旁观仍在最前面挡掉，NBT 无敌标志也照旧。
     */
    private static boolean damageable(final LivingEntity entity, final HeadlightConfig cfg) {
        if (dead(entity) || entity.isInvulnerable()) {
            return false;
        }
        if (entity instanceof PlayerEntity player) {
            if (player.isSpectator()) {
                return false;
            }
            return cfg.damageInCreative
                    || (!player.isCreative() && !player.getAbilities().invulnerable);
        }
        return true;
    }

    /**
     * 为什么这个实体不吃伤害（诊断用，{@code null} = 能吃）。
     */
    private static String skipReason(final LivingEntity entity, final HeadlightConfig cfg) {
        if (entity == null) {
            return "null";
        }
        if (!entity.isAlive() || entity.isDead() || !(entity.getHealth() > 0.0F)) {
            return "dead";
        }
        if (entity.isInvulnerable()) {
            return "invulnerable-flag";
        }
        if (entity instanceof PlayerEntity player) {
            if (player.isSpectator()) {
                return "spectator";
            }
            if (!cfg.damageInCreative && player.isCreative()) {
                return "creative";
            }
            if (!cfg.damageInCreative && player.getAbilities().invulnerable) {
                return "abilities.invulnerable(god)";
            }
        }
        return null;
    }

    /**
     * 冲量：水平远离车体（方向由几何那边给），水平速度由 {@link #knockbackHorizontal} 按
     * {@code d(v) = base·exp(k(v−50)/50)} 反推，竖直给一个够滞空的向上分量（0.45 / 0.60）。
     *
     * @param speedKmh 撞击时的车速 —— 距离就是它算出来的，撞得越快飞得越远（指数）
     * @return 实际施加的水平初速（格/tick）；0 = 没有方向，没飞
     */
    private static double impulse(final LivingEntity entity, final HeadlightConfig cfg,
                                  final Tier tier, final double speedKmh,
                                  final double dirX, final double dirZ) {
        double x = dirX;
        double z = dirZ;
        double len = Math.sqrt(x * x + z * z);
        if (!(len > 1.0E-6D)) {
            return 0.0D;
        }
        x /= len;
        z /= len;
        final double horizontal = knockbackHorizontal(cfg, tier, speedKmh);
        final double vertical = launchVertical(cfg, tier);
        try {
            entity.setVelocity(x * horizontal, vertical, z * horizontal);
            // 服务端实体要把速度同步给客户端，否则生物只在服务端飞、画面上不动。
            entity.velocityModified = true;
            // 被撞飞不是摔下来：清掉自由落体距离，免得落地时多一条「摔死了」。
            entity.fallDistance = 0.0F;
            IMPULSES++;
            // 单人里玩家位置是客户端权威的：服务端写的速度到不了本人，必须再写一份到本地实例。
            final ImpulseMirror hook = mirror;
            if (hook != null) {
                try {
                    hook.push(entity.getUuid(), x * horizontal, vertical, z * horizontal);
                } catch (Throwable ignored) {
                    // 镜像失败只影响手感，不影响伤害。
                }
            }
            LteLog.LOGGER.info("[lte] knockback: tier={} v={}km/h base={} k={} max={} dist={} vx={} vy={} air={}t",
                    tier.name(),
                    String.format(Locale.ROOT, "%.0f", Double.valueOf(speedKmh)),
                    String.format(Locale.ROOT, "%.2f", Double.valueOf(cfg.knockbackDistanceClamped())),
                    String.format(Locale.ROOT, "%.2f", Double.valueOf(cfg.knockbackExpKClamped())),
                    String.format(Locale.ROOT, "%.0f", Double.valueOf(cfg.knockbackMaxClamped())),
                    String.format(Locale.ROOT, "%.2f",
                            Double.valueOf(knockbackDistanceAt(cfg, speedKmh))),
                    String.format(Locale.ROOT, "%.3f", Double.valueOf(horizontal)),
                    String.format(Locale.ROOT, "%.2f", Double.valueOf(vertical)),
                    Integer.valueOf(flightTicks(vertical)));
            return horizontal;
        } catch (Throwable ignored) {
            // 冲量只是表现，失败不影响伤害。
            return 0.0D;
        }
    }

    /**
     * 把一次伤害真正打到实体上 —— <b>正规的原版 {@code LivingEntity#damage()}</b>。
     *
     * <p>结算周期 {@link #DAMAGE_INTERVAL_TICKS} = 10 tick 正好落在原版 10 tick 无敌帧之后，所以
     * 平时的碎伤害不会被吞。但<b>致死那一笔</b>例外地先把 {@code timeUntilRegen} 清零：别的来源
     * （摔落、生物、mod）恰好在窗口边界上打了一下时，原版只结算「超出上次伤害的部分」，
     * 0.6 hp 的致死笔会被 {@code amount <= lastDamageTaken} 判成 {@code return false} —— 血停在 0
     * 以上，死亡屏永远不弹。清零只发生在「这一笔足以致死」时，所以镜头抖动抑制不受影响。
     *
     * <p>每笔都打一行 {@code [lte] damage:}：源类名、请求血量、血前→血后、是否结算、无敌/创造/
     * 无敌模式/无敌帧状态、以及一层调用栈。死亡时再打一行 {@code [lte] death:}，把那句死亡文案的
     * <b>实际翻译结果</b>抄下来 —— 「日志里没有 death.attack」从此有据可查。
     *
     * @param high true = 高速死亡消息（&gt;= highDamageMaxKmh）
     * @param cfg  伤害配置；{@code damageInCreative} 打开时会在这一笔里临时关掉创造玩家的
     *             {@code abilities.invulnerable}（原版 {@code damage()} 第一行就因此返回 false）
     * @return true = 伤害真的结算了；false 时已经 {@link #requeue}，下一 tick 重试
     */
    public static boolean applyDamage(final LivingEntity target, final float amount,
                                      final boolean high, final HeadlightConfig cfg) {
        if (target == null || !(amount > 0.0F) || dead(target)) {
            return false;
        }
        final float hpBefore = target.getHealth();
        final boolean lethal = amount >= hpBefore;
        if (lethal) {
            // 致死笔不能被别人的无敌帧吞掉（只清窗口，不动 hurtTime —— 镜头抖动由 suppressHurtTilt 管）。
            target.timeUntilRegen = 0;
        }
        final DamageSource source = sourceFor(target, high);
        boolean applied;
        try {
            applied = damageIgnoringCreativeInvuln(target, source, amount,
                    cfg != null && cfg.damageInCreative);
        } catch (Throwable t) {
            applied = false;
            LteLog.LOGGER.warn("[lte] damage: threw " + t + " src="
                    + source.getClass().getSimpleName(), t);
        }
        logDamage(target, source, amount, hpBefore, applied, lethal);
        if (!applied) {
            // 被原版无敌帧 / 事件取消吞掉：退回去下一 tick 立刻重打，别丢这半滴血。
            requeue(target, amount);
            return false;
        }
        final State s = STATES.get(target.getUuid());
        if (s != null) {
            // 记下「这一下是我们打的」，客户端据此把自己的受击镜头晃动按掉。
            s.lastDamageMs = System.currentTimeMillis();
        }
        suppressHurtTilt(target);
        return true;
    }

    /**
     * 真正调用原版 {@code damage()}，并在 {@code damageInCreative} 打开时绕过创造玩家的
     * {@code abilities.invulnerable}。
     *
     * <p>为什么还要临时改 abilities：1.20.1 的 {@code PlayerEntity#damage()} 在方法最前面就有
     * {@code if (abilities.invulnerable && !source.isIn(BYPASSES_INVULNERABILITY)) return false;}。
     * 创造模式默认把这个标志设为 true，所以只在 {@link #damageable} 里放行还不够 —— 那一笔会被
     * 原版直接吞掉。这里在<b>这一笔</b>的调用期间把它设为 false，finally 里立刻恢复，玩家之后
     * 仍然是无敌的创造模式（只是这一下能打进去）。
     */
    private static boolean damageIgnoringCreativeInvuln(final LivingEntity target,
                                                        final DamageSource source,
                                                        final float amount,
                                                        final boolean allowCreative) {
        if (!allowCreative || !(target instanceof PlayerEntity player)
                || player.isSpectator() || !player.getAbilities().invulnerable) {
            return target.damage(source, amount);
        }
        player.getAbilities().invulnerable = false;
        try {
            return target.damage(source, amount);
        } finally {
            player.getAbilities().invulnerable = true;
        }
    }

    /**
     * 一行 {@code [lte] damage:} + 死亡时的 {@code [lte] death:}。
     *
     * <p>用 {@link StackWalker} 取<b>一层</b>调用者（{@code skip(2)}：跳过本方法自己和
     * {@link #applyDamage}），因为「谁打的这一下」正是排查时最先要看的东西。
     */
    private static void logDamage(final LivingEntity target, final DamageSource source,
                                  final float requested, final float hpBefore, final boolean applied,
                                  final boolean lethal) {
        try {
            final float hpAfter = target.getHealth();
            final String caller = StackWalker.getInstance().walk(frames -> frames.skip(2)
                    .findFirst().map(StackWalker.StackFrame::toString).orElse("?"));
            LteLog.LOGGER.info("[lte] damage: src={} want={} hp={}->{} landed={} iframes={} reset={}"
                            + " inv={} god={} creative={} at {}",
                    source.getClass().getSimpleName(),
                    format2(requested),
                    format2(hpBefore), format2(hpAfter),
                    Boolean.valueOf(applied),
                    Integer.valueOf(target.timeUntilRegen),
                    Boolean.valueOf(lethal),
                    Boolean.valueOf(target.isInvulnerable()),
                    Boolean.valueOf(target instanceof PlayerEntity p && p.getAbilities().invulnerable),
                    Boolean.valueOf(target instanceof PlayerEntity p2 && p2.isCreative()),
                    caller);
            if (!target.isAlive() || target.getHealth() <= 0.0F) {
                logDeath(target, source);
            }
        } catch (Throwable ignored) {
            // 日志永远不许影响 tick。
        }
    }

    /** 死亡文案的实际翻译结果（{@code death.attack.mtrlte.trainHigh/Low} 会出现在这一行）。 */
    private static void logDeath(final LivingEntity target, final DamageSource source) {
        String message = "?";
        String key = "?";
        try {
            message = target.getDamageTracker().getDeathMessage().getString();
        } catch (Throwable ignored) {
            // 拿不到就算了。
        }
        try {
            // 语言文件里的键：从我们自己那两个常量反查，和 source 的实际类名一起打，便于对照。
            key = TrainImpactDamageSource.KEY_SLOW;
            if (source instanceof TrainImpactDamageSource impact) {
                key = impact.high() ? TrainImpactDamageSource.KEY_FAST : TrainImpactDamageSource.KEY_SLOW;
            }
        } catch (Throwable ignored) {
            // 忽略。
        }
        LteLog.LOGGER.info("[lte] death: target={} src={} key={} msg=\"{}\"",
                target.getName().getString(), source.getClass().getSimpleName(), key, message);
    }

    private static String format2(final float value) {
        return String.format(Locale.ROOT, "%.2f", Float.valueOf(value));
    }

    /**
     * {@code /mtr_lte debug killself}：用<b>本模组自己的伤害源</b>对目标打一记必死伤害，
     * 验证「死亡屏 + 死亡文案」这条链路本身是通的。
     *
     * <p>为什么用它：实机里撞死要凑齐「车速够 / 接触够久 / 没在无敌模式」，排查时变量太多。这里把
     * 变量降到零 —— 源固定是 {@link TrainImpactDamageSource}，血量固定是「够杀死任何减伤」，
     * 无敌帧清零，然后返回一行可以直接贴进报告的结果（血前、血后、是否结算、死亡文案）。
     *
     * <p>不会强行杀死无敌的人：创造 / 旁观 / 无敌标志 / 无敌模式会原样报告出来（{@code landed=false}），
     * 这本身就是最有用的诊断。
     *
     * @param high true = 高速那段文案（{@code death.attack.mtrlte.trainHigh}）
     * @param cfg  当前配置（{@code damageInCreative} 决定创造玩家是否也能被这一记杀掉）
     * @return 一行中英文混排的结果，交给命令回显
     */
    public static String debugKill(final LivingEntity target, final boolean high,
                                   final HeadlightConfig cfg) {
        if (target == null) {
            return "LTE：killself 需要单人存档（找不到服务端玩家实例）";
        }
        forget(target.getUuid());
        final String skip = skipReason(target, cfg);
        final float before = target.getHealth();
        final DamageSource source = sourceFor(target, high);
        final float want = Math.max(4.0F, before) * 8.0F + 20.0F;
        boolean landed;
        try {
            target.timeUntilRegen = 0;
            target.fallDistance = 0.0F;
            landed = damageIgnoringCreativeInvuln(target, source, want,
                    cfg != null && cfg.damageInCreative);
        } catch (Throwable t) {
            landed = false;
            LteLog.LOGGER.warn("[lte] killself: threw " + t, t);
        }
        logDamage(target, source, want, before, landed, true);
        final float after = target.getHealth();
        String message = "?";
        try {
            message = target.getDamageTracker().getDeathMessage().getString();
        } catch (Throwable ignored) {
            // 拿不到文案不影响结论。
        }
        final String key = high ? TrainImpactDamageSource.KEY_FAST : TrainImpactDamageSource.KEY_SLOW;
        return "LTE：killself — src=" + source.getClass().getSimpleName()
                + " key=" + key
                + " hp=" + format2(before) + "->" + format2(after)
                + " landed=" + landed
                + " alive=" + target.isAlive()
                + (skip == null ? "" : " blocked=" + skip)
                + " msg=\"" + message + "\"";
    }

    /**
     * 压掉受击镜头晃动：{@code hurtTime} 驱动 {@code GameRenderer#tiltViewWhenHurt}（画面那一歪），
     * 红色受伤叠层也读它。流血窗口里每 10 tick 一笔伤害会把它反复重置成 10 tick，连续倾斜看起来就是
     * 「一直被推着抖」。
     *
     * <p>抑制范围是<b>整个持续掉血窗口</b>（{@link State#hurtUntilMs}，默认 6 s；撞飞的是从冲量
     * 写下去到落地结算为止），窗口内每 tick 清零 —— 不是以前那种「最后一击之后 1.5 s」。
     * 窗口一结束（伤害预算花完 / 落地结算完 / 6 s 到点）立刻停手，玩家挨僵尸、摔落时的受击表现
     * 完全照旧。
     *
     * <p>客户端 / 服务端各有一份实例，受击状态是 {@code EntityStatus} 包同步过去的，所以两边都要清；
     * 这里每次只清传入的这一个实例，{@code CollisionSystem#tick} 每 tick 对本地玩家调一次。
     */
    public static void suppressHurtTilt(final LivingEntity entity) {
        if (!(entity instanceof PlayerEntity player)) {
            return;
        }
        final State s = STATES.get(player.getUuid());
        if (s == null) {
            return;
        }
        final long nowMs = System.currentTimeMillis();
        final boolean inWindow = s.launched || nowMs < s.hurtUntilMs;
        if (!inWindow && (s.lastDamageMs <= 0L || nowMs - s.lastDamageMs > TILT_SUPPRESS_MS)) {
            return;
        }
        if (player.hurtTime == 0 && player.maxHurtTime == 0) {
            return;
        }
        player.hurtTime = 0;
        player.maxHurtTime = 0;
    }

    /** 受击镜头抑制的兜底有效期（ms）：覆盖「服务端打伤害 → 客户端收到受击包」的那一两个 tick。 */
    private static final long TILT_SUPPRESS_MS = 1500L;

    // ---------------------------------------------------------------------------------------------
    // 诊断
    // ---------------------------------------------------------------------------------------------

    /** 上次打 {@code impact:} 行的 tick（全局节流）。 */
    private static long lastImpactLog = NEVER;

    /** 诊断节流周期（tick）：{@value} 内最多一行。 */
    private static final long IMPACT_LOG_TICKS = 20L;

    /**
     * 诊断行 {@code impact: v= tier= dmg= window= left=}，每 {@value #IMPACT_LOG_TICKS} tick 最多一行。
     *
     * <p>{@code dmg=} 是<b>一个结算窗口</b>（{@value #DAMAGE_INTERVAL_TICKS} tick）会打出去的血量，
     * 不是每 tick 的碎值 —— 那才是玩家实际感觉到的那一下。{@code window=} 是配置的持续掉血窗口总长
     * （tick），{@code left=} 是这一 tick 还剩多少 tick（0 = 窗口已经结束）。
     */
    private static void logImpact(final HeadlightConfig cfg, final long now, final double speedKmh,
                                  final Tier tier, final State s, final boolean pushed) {
        if (now - lastImpactLog < IMPACT_LOG_TICKS) {
            return;
        }
        lastImpactLog = now;
        try {
            final double perWindow = damagePerSecond(cfg, s.v0, speedKmh)
                    * DAMAGE_INTERVAL_TICKS / 20.0D;
            final int window = cfg.trainCollisionHurtTicksClamped();
            final long left = s.hurtUntil == NEVER ? 0L : Math.max(0L, s.hurtUntil - now);
            LteLog.LOGGER.info("[lte] impact: v={} tier={} dmg={} window={} left={} pushed={}",
                    String.format(Locale.ROOT, "%.1f", Double.valueOf(speedKmh)),
                    tier.name(),
                    String.format(Locale.ROOT, "%.2f", Double.valueOf(perWindow)),
                    Integer.valueOf(window),
                    Long.valueOf(left),
                    Boolean.valueOf(pushed));
        } catch (Throwable ignored) {
            // 日志永远不许影响 tick。
        }
    }

    /** 原版 {@code generic} 类型 + 我们自己的死亡消息。 */
    private static DamageSource sourceFor(final LivingEntity target, final boolean high) {
        // 1.19.2 没有 DamageType 注册表：伤害源直接是我们的 DamageSource 子类
        // （死亡消息在 getDeathMessage 里覆写，效果与 1.19.4 传 generic 类型一致）。
        return new TrainImpactDamageSource(high);
    }
}
