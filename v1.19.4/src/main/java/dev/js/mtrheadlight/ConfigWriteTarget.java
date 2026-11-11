package dev.js.mtrheadlight;

import java.util.function.Supplier;

/**
 * <b>The only place the config screens write configuration.</b>
 *
 * <p>A screen builds one of these in {@code init()}:
 * <ul>
 *   <li>{@link #global(HeadlightConfig)} - {@link ConfigScreen}, every write lands on a field of
 *       the global {@link HeadlightConfig};</li>
 *   <li>{@link #siding(HeadlightConfig, long)} - {@link CategoryScreen}, every write lands on
 *       that siding's {@link HeadlightConfig.SidingSettings} entry. <b>It has no code path that
 *       assigns a global field at all.</b></li>
 * </ul>
 *
 * <h2>Why this class exists</h2>
 * The old screens funnelled every control through one method with an {@code if (siding == null)}
 * branch. The siding half did write the override, but the two halves lived in the same method and
 * the same object, and a {@code null} siding - which {@link SidingScreen} used to hand out whenever
 * {@code ClientData.SIDINGS} contained a null entry - silently turned the detail screen into a
 * <em>global</em> editor: every slider and switch then wrote {@code HeadlightConfig} instead of
 * {@code sidingSettings[id]}. That is the "按侧线改配置，全局跟着变" report.
 *
 * <p>Splitting the two targets into two classes makes that failure mode unrepresentable:
 * {@link SidingTarget} never sees the global fields, {@link #siding} rejects id {@code 0}
 * (a casing id would silently mean "global" in {@code effectiveFor}), and
 * {@link SelfCheck#run()} proves the isolation on a real {@link HeadlightConfig}.
 *
 * <p>No Minecraft imports on purpose: this file is compiled and exercised by
 * {@code tools/verify-config-write.py} outside the game.
 */
abstract class ConfigWriteTarget {
    /** Numeric parameters, in the order the screens list them. */
    static final int DISTANCE = 0;
    static final int LUMINANCE = 1;
    static final int HEAD_LENGTH = 2;
    static final int BEAM_DISTANCE = 3;
    static final int BEAM_HEAD_LENGTH = 4;
    static final int BEAM_TAIL_LENGTH = 5;
    static final int BEAM_WIDTH = 6;
    static final int BEAM_HEAD_HEIGHT = 7;
    static final int BEAM_TAIL_HEIGHT = 8;
    /** 车头方向碰撞延长（格，0–4）—— 见 {@code CollisionSystem} 的端盖一节。 */
    static final int COLLISION_HEAD_EXTRA = 9;
    /** 车尾方向碰撞延长（格，0–4）。 */
    static final int COLLISION_TAIL_EXTRA = 10;
    /**
     * 雨天水雾的起始速度（km/h，0–200）。全局写 {@code rainSprayMinSpeedKmh}，侧线写同名覆盖。
     */
    static final int RAIN_SPRAY_MIN_SPEED = 11;
    /** 速度分档 + 指数伤害 + 冲量：全部<b>只有全局值</b>（不是一个按车厢量出来的几何参数）。 */
    static final int COLLISION_PUSH_MAX = 12;
    static final int COLLISION_LOW_MAX = 13;
    static final int COLLISION_HIGH_MAX = 14;
    static final int COLLISION_LAUNCH_MAX = 15;
    static final int COLLISION_BASE_RATE = 16;
    static final int COLLISION_EXP_K = 17;
    static final int COLLISION_RATE_SCALE = 18;
    static final int COLLISION_FLY_SCALE = 19;
    static final int COLLISION_LAUNCH_H = 20;
    static final int COLLISION_LAUNCH_V = 21;
    static final int COLLISION_FLY_H = 22;
    static final int COLLISION_FLY_V = 23;
    /** 同一实体的冲量结算冷却（tick，1–200）。 */
    static final int COLLISION_COOLDOWN = 24;
    /** 车体碰撞高度（格，1–8）。<b>只有全局值</b>：整车几何，不是按车厢量的参数。 */
    static final int COLLISION_HEIGHT = 25;
    /** 车顶载人掉落的速度阈值（km/h，0–300）。只有全局值。 */
    static final int ROOF_DROP_SPEED = 26;
    /** 车顶载人掉落的弯道半径（格，10–1000）。只有全局值。 */
    static final int ROOF_DROP_RADIUS = 27;
    /**
     * 车灯模式（0 = 自动 / 1 = 常亮 / 2 = 关闭）。只有全局值；界面上是整行一个循环按钮，
     * 不是滑块，但仍走 {@link #readValue}/{@link #writeValue} 这一条写入路径。
     */
    static final int HEADLIGHT_MODE = 28;
    /**
     * 伤害起点速度（km/h，0–300）：低于它只推开、不扣血。
     *
     * <p>全局写 {@link HeadlightConfig#trainCollisionPushOnlyMaxKmh}（那个名字是大重构前留下的），
     * 侧线写 {@link HeadlightConfig.SidingSettings#trainCollisionDamageMinSpeedKmh} —— 同一件事的
     * 两个入口，各自只碰自己那一层。
     */
    static final int DAMAGE_MIN_SPEED = 29;
    /**
     * 一次接触的累计伤害上限（hp，0–200，0 = 只受实体最大生命限制）。侧线可覆盖；全局页也显示
     * 这一行，否则侧线的「跟随」会指向一个界面上看不见的值。
     */
    static final int DAMAGE_MAX = 30;
    /**
     * 撞飞距离（格，1–80）的<b>基准值</b>：{@code d(v) = base · exp(k(v−v0)/50)} 里的 base。
     * 水平初速由它与竖直初速反推。只有全局值。
     *
     * <p>取代了原来两个「水平初速」行（{@code COLLISION_LAUNCH_H} / {@code COLLISION_FLY_H}）：
     * 那两个字段已废弃、界面上不再出现，但仍留在 {@link HeadlightConfig} 里让旧 JSON 能读。
     */
    static final int COLLISION_KNOCKBACK = 31;
    /** 撞飞距离的指数系数 k（0–3，默认 1.0）：每快 50 km/h，距离乘 e^k。只有全局值。 */
    static final int COLLISION_KNOCKBACK_K = 32;
    /** 撞飞距离上限（格，1–200，默认 80）：指数曲线的天花板。只有全局值。 */
    static final int COLLISION_KNOCKBACK_MAX = 33;
    static final int[] ALL_VALUES = {
            DISTANCE, LUMINANCE, HEAD_LENGTH, BEAM_DISTANCE, BEAM_HEAD_LENGTH,
            BEAM_TAIL_LENGTH, BEAM_WIDTH, BEAM_HEAD_HEIGHT, BEAM_TAIL_HEIGHT,
            COLLISION_HEAD_EXTRA, COLLISION_TAIL_EXTRA, RAIN_SPRAY_MIN_SPEED,
            COLLISION_PUSH_MAX, COLLISION_LOW_MAX, COLLISION_HIGH_MAX, COLLISION_LAUNCH_MAX,
            COLLISION_BASE_RATE, COLLISION_EXP_K, COLLISION_RATE_SCALE, COLLISION_FLY_SCALE,
            COLLISION_LAUNCH_H, COLLISION_LAUNCH_V, COLLISION_FLY_H, COLLISION_FLY_V,
            COLLISION_COOLDOWN, COLLISION_HEIGHT, ROOF_DROP_SPEED, ROOF_DROP_RADIUS,
            HEADLIGHT_MODE, DAMAGE_MIN_SPEED, DAMAGE_MAX, COLLISION_KNOCKBACK,
            COLLISION_KNOCKBACK_K, COLLISION_KNOCKBACK_MAX,
    };

    /**
     * The switch hierarchy: master -> (dynamic light | light band | beam cone), plus the
     * game-wide switches (built-in train filter) that only the global screen shows.
     */
    static final int MASTER = 0;
    static final int DYNAMIC_LIGHT = 1;
    /** 光带（{@link HeadlightConfig#beamGlow}）：贴地的加色光带。 */
    static final int LIGHT_BAND = 2;
    /** 光柱（{@link HeadlightConfig#beamCone}）：车头前方的体积光锥，和光带各自独立。侧线可覆盖。 */
    static final int BEAM_CONE = 3;
    /** 雨天水雾粒子（{@link HeadlightConfig#rainSpray}）；侧线可覆盖。 */
    static final int RAIN_SPRAY = 4;
    /** 隐藏 MTR 内置车型（{@link HeadlightConfig#disableBuiltInTrains}），全局开关。 */
    static final int BUILTIN_TRAINS = 5;
    /** 车体碰撞（{@link HeadlightConfig#trainCollision}），全局开关。 */
    static final int TRAIN_COLLISION = 6;
    /** 道床碰撞（{@link HeadlightConfig#trackbedCollision}），全局开关。 */
    static final int TRACKBED = 7;
    /** 列车碰撞伤害总开关（{@link HeadlightConfig#trainCollisionDamage}）；侧线可覆盖（只关扣血）。 */
    static final int TRAIN_DAMAGE = 8;
    /** 生物是否受列车碰撞影响（{@link HeadlightConfig#trainCollisionMobs}），全局开关。 */
    static final int TRAIN_MOBS = 9;
    /** 挡雨（{@link HeadlightConfig#rainHush}）；侧线可覆盖（按你坐在哪列车里判）。 */
    static final int RAIN_HUSH = 10;
    /**
     * 创造模式也吃列车碰撞伤害（{@link HeadlightConfig#damageInCreative}），全局开关：
     * 这是伤害规则的全局安全阀，不是按侧线量的参数，所以侧线页不生成、也不接受写入。
     */
    static final int DAMAGE_IN_CREATIVE = 11;
    static final int[] ALL_TOGGLES = {
            MASTER, DYNAMIC_LIGHT, LIGHT_BAND, BEAM_CONE, RAIN_SPRAY, BUILTIN_TRAINS,
            TRAIN_COLLISION, TRACKBED, TRAIN_DAMAGE, TRAIN_MOBS, RAIN_HUSH, DAMAGE_IN_CREATIVE,
    };

    /**
     * Resolved on every access, never cached: 取消 swaps the live config instance
     * ({@link MtrHeadlightClient#restoreConfig(String)}), and a screen that stayed open across that
     * swap must write the new instance instead of the dead one.
     */
    final Supplier<HeadlightConfig> cfg;

    ConfigWriteTarget(final Supplier<HeadlightConfig> cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg == null");
        }
        this.cfg = cfg;
    }

    /** The live config, resolved fresh. */
    final HeadlightConfig config() {
        final HeadlightConfig live = this.cfg.get();
        if (live == null) {
            throw new IllegalStateException("no live config");
        }
        return live;
    }

    static ConfigWriteTarget global(final Supplier<HeadlightConfig> cfg) {
        return new GlobalTarget(cfg);
    }

    static ConfigWriteTarget siding(final Supplier<HeadlightConfig> cfg, final long sidingId) {
        return new SidingTarget(cfg, sidingId);
    }

    /**
     * A row that exists on the global screen only (车体开关 / 道床 / 内置车型屏蔽 / 生物伤害).
     * The siding page never builds these rows, so reaching one through {@link SidingTarget} is a
     * programming error.
     *
     * <p><b>It used to throw</b> ({@code IllegalStateException: global-only row 3 on a siding
     * screen}), which turned one stray row into a client crash on click. A wrong row must never be
     * able to take the screen down: it is ignored (reads return a neutral value, writes are dropped)
     * and logged once at debug level. The rendering side is fixed as well - {@code ParameterScreen}
     * does not generate these rows for a siding.
     */
    private static void ignored(final int which) {
        LteLog.LOGGER.debug("[lte] config: row {} is global-only, ignored on a siding screen",
                Integer.valueOf(which));
    }

    /** Written to the log by the self-check; also handy in a crash report. */
    abstract String describe();

    abstract HeadlightConfig.Effective effective();

    abstract double readValue(int which);

    abstract void writeValue(int which, double value);

    abstract boolean readToggle(int which);

    abstract void writeToggle(int which, boolean on);

    abstract int readColor(boolean head);

    abstract void writeColor(boolean head, int rgb);

    abstract boolean followsValue(int which);

    abstract boolean followsToggle(int which);

    abstract boolean followsColor(boolean head);

    /** True while this target has a row that can be reset (detail screen only). */
    abstract boolean hasOverrides();

    /**
     * One row's ↺ button - a single parameter. <b>The two targets mean different things by it</b>,
     * which is the whole point of routing it through this class:
     * <ul>
     *   <li>{@link GlobalTarget}: write that one field back to {@link HeadlightConfig#defaults()},
     *       leaving every other global field alone;</li>
     *   <li>{@link SidingTarget}: drop that one override ({@code field = null}), so the row follows
     *       the global value again. It deliberately does <b>not</b> copy the global default into the
     *       override - that would pin a value the player never chose and stop following a later
     *       change of the global one.</li>
     * </ul>
     */
    abstract void resetValue(int which);

    /** Per-row ↺ for one of the three switches; same global-default / drop-override split. */
    abstract void resetToggle(int which);

    /** Per-row ↺ for one of the two beam colours; same global-default / drop-override split. */
    abstract void resetColor(boolean head);

    /** "恢复为跟随全局": drops every override of this target. No-op for the global target. */
    abstract void reset();

    /**
     * 全部恢复默认 (footer, behind a confirmation screen):
     * <ul>
     *   <li>{@link GlobalTarget}: every global field the screens expose goes back to
     *       {@link HeadlightConfig#defaults()}, <b>and every siding override is dropped</b> - "reset
     *       everything" has to include the per-siding entries, or a siding would keep overriding the
     *       defaults that were just restored;</li>
     *   <li>{@link SidingTarget}: only this siding's entry is dropped (all of its overrides), the
     *       global values are untouched.</li>
     * </ul>
     */
    abstract void resetAll();

    /** Writes the live config to disk. Called only by 完成 / 保存并返回. */
    final void commit() {
        config().save();
    }

    // ---------------------------------------------------------------------------------------------
    // Global target
    // ---------------------------------------------------------------------------------------------

    private static final class GlobalTarget extends ConfigWriteTarget {
        GlobalTarget(final Supplier<HeadlightConfig> cfg) {
            super(cfg);
        }

        @Override
        String describe() {
            return "global";
        }

        @Override
        HeadlightConfig.Effective effective() {
            return config().effectiveFor(0L);
        }

        @Override
        double readValue(final int which) {
            final HeadlightConfig.Effective eff = effective();
            switch (which) {
                case DISTANCE:
                    return eff.maxDistance;
                case LUMINANCE:
                    return eff.headLuminance;
                case HEAD_LENGTH:
                    return eff.headLength;
                case BEAM_DISTANCE:
                    return eff.beamDistance;
                case BEAM_HEAD_LENGTH:
                    return eff.beamHeadLength;
                case BEAM_TAIL_LENGTH:
                    return eff.beamTailLength;
                case BEAM_WIDTH:
                    return eff.beamWidth;
                case BEAM_HEAD_HEIGHT:
                    return eff.beamHeadHeight;
                case COLLISION_HEAD_EXTRA:
                    return eff.collisionHeadExtra;
                case COLLISION_TAIL_EXTRA:
                    return eff.collisionTailExtra;
                case RAIN_SPRAY_MIN_SPEED:
                    // 全局行：水雾不是一个按车厢量出来的几何量，侧线覆盖没有意义。
                    return config().rainSprayMinSpeedKmh;
                case DAMAGE_MIN_SPEED:
                    // 全局的伤害起点就是旧的「推开车速上限」字段，两个入口只碰自己那层。
                    return config().trainCollisionPushOnlyMaxKmh;
                case DAMAGE_MAX:
                    return config().trainCollisionDamageMax;
                case COLLISION_KNOCKBACK:
                    return config().knockbackDistance;
                case COLLISION_KNOCKBACK_K:
                    return config().knockbackExpK;
                case COLLISION_KNOCKBACK_MAX:
                    return config().knockbackMax;
                case COLLISION_PUSH_MAX:
                    return config().trainCollisionPushOnlyMaxKmh;
                case COLLISION_LOW_MAX:
                    return config().trainCollisionLowDamageMaxKmh;
                case COLLISION_HIGH_MAX:
                    return config().trainCollisionHighDamageMaxKmh;
                case COLLISION_LAUNCH_MAX:
                    return config().trainCollisionLaunchMaxKmh;
                case COLLISION_BASE_RATE:
                    return config().trainCollisionDamageBasePerSecond;
                case COLLISION_EXP_K:
                    return config().trainCollisionDamageExpK;
                case COLLISION_RATE_SCALE:
                    return config().trainCollisionDamageSpeedScaleKmh;
                case COLLISION_FLY_SCALE:
                    return config().trainCollisionFlyDamageScale;
                case COLLISION_LAUNCH_H:
                    return config().trainCollisionLaunchHorizontal;
                case COLLISION_LAUNCH_V:
                    return config().trainCollisionLaunchVertical;
                case COLLISION_FLY_H:
                    return config().trainCollisionFlyHorizontal;
                case COLLISION_FLY_V:
                    return config().trainCollisionFlyVertical;
                case COLLISION_COOLDOWN:
                    return config().trainCollisionImpactCooldownTicks;
                case COLLISION_HEIGHT:
                    return config().trainCollisionHeightClamped();
                case ROOF_DROP_SPEED:
                    return config().roofDropMinSpeedKmh;
                case ROOF_DROP_RADIUS:
                    return config().roofDropMaxRadiusMClamped();
                case HEADLIGHT_MODE:
                    // 0 自动 / 1 常亮 / 2 关闭：和 HeadlightConfig.MODE_* 一一对应。
                    final String mode = config().resolvedMode();
                    if (HeadlightConfig.MODE_ALWAYS.equals(mode)) {
                        return 1.0D;
                    }
                    return HeadlightConfig.MODE_OFF.equals(mode) ? 2.0D : 0.0D;
                default:
                    return eff.beamTailHeight;
            }
        }

        @Override
        void writeValue(final int which, final double value) {
            switch (which) {
                case DISTANCE:
                    config().maxDistance = value;
                    break;
                case LUMINANCE:
                    config().headLuminance = (int) Math.round(value);
                    break;
                case HEAD_LENGTH:
                    config().headLength = value;
                    break;
                case BEAM_DISTANCE:
                    config().beamDistance = value;
                    break;
                case BEAM_HEAD_LENGTH:
                    config().beamHeadLength = value;
                    break;
                case BEAM_TAIL_LENGTH:
                    config().beamTailLength = value;
                    break;
                case BEAM_WIDTH:
                    config().beamWidth = value;
                    break;
                case BEAM_HEAD_HEIGHT:
                    config().beamHeadHeight = value;
                    break;
                case COLLISION_HEAD_EXTRA:
                    config().collisionHeadExtra = value;
                    break;
                case COLLISION_TAIL_EXTRA:
                    config().collisionTailExtra = value;
                    break;
                case RAIN_SPRAY_MIN_SPEED:
                    config().rainSprayMinSpeedKmh = (int) Math.round(value);
                    break;
                case DAMAGE_MIN_SPEED:
                    config().trainCollisionPushOnlyMaxKmh = (int) Math.round(value);
                    break;
                case DAMAGE_MAX:
                    config().trainCollisionDamageMax = value;
                    break;
                case COLLISION_KNOCKBACK:
                    config().knockbackDistance = value;
                    break;
                case COLLISION_KNOCKBACK_K:
                    config().knockbackExpK = value;
                    break;
                case COLLISION_KNOCKBACK_MAX:
                    config().knockbackMax = value;
                    break;
                case COLLISION_PUSH_MAX:
                    config().trainCollisionPushOnlyMaxKmh = (int) Math.round(value);
                    break;
                case COLLISION_LOW_MAX:
                    config().trainCollisionLowDamageMaxKmh = (int) Math.round(value);
                    break;
                case COLLISION_HIGH_MAX:
                    config().trainCollisionHighDamageMaxKmh = (int) Math.round(value);
                    break;
                case COLLISION_LAUNCH_MAX:
                    config().trainCollisionLaunchMaxKmh = (int) Math.round(value);
                    break;
                case COLLISION_BASE_RATE:
                    config().trainCollisionDamageBasePerSecond = value;
                    break;
                case COLLISION_EXP_K:
                    config().trainCollisionDamageExpK = value;
                    break;
                case COLLISION_RATE_SCALE:
                    config().trainCollisionDamageSpeedScaleKmh = value;
                    break;
                case COLLISION_FLY_SCALE:
                    config().trainCollisionFlyDamageScale = value;
                    break;
                case COLLISION_LAUNCH_H:
                    config().trainCollisionLaunchHorizontal = value;
                    break;
                case COLLISION_LAUNCH_V:
                    config().trainCollisionLaunchVertical = value;
                    break;
                case COLLISION_FLY_H:
                    config().trainCollisionFlyHorizontal = value;
                    break;
                case COLLISION_FLY_V:
                    config().trainCollisionFlyVertical = value;
                    break;
                case COLLISION_COOLDOWN:
                    config().trainCollisionImpactCooldownTicks = (int) Math.round(value);
                    break;
                case COLLISION_HEIGHT:
                    config().trainCollisionHeight = value;
                    break;
                case ROOF_DROP_SPEED:
                    config().roofDropMinSpeedKmh = (int) Math.round(value);
                    break;
                case ROOF_DROP_RADIUS:
                    config().roofDropMaxRadiusM = value;
                    break;
                case HEADLIGHT_MODE: {
                    final int index = (int) Math.round(value);
                    config().headlightMode = index == 1
                            ? HeadlightConfig.MODE_ALWAYS
                            : (index == 2 ? HeadlightConfig.MODE_OFF : HeadlightConfig.MODE_AUTO);
                    break;
                }
                default:
                    config().beamTailHeight = value;
                    break;
            }
        }

        @Override
        boolean readToggle(final int which) {
            final HeadlightConfig.Effective eff = effective();
            switch (which) {
                case MASTER:
                    return eff.enabled;
                case DYNAMIC_LIGHT:
                    return eff.dynamicLight;
                case BEAM_CONE:
                    return eff.beamCone;
                case RAIN_SPRAY:
                    return eff.rainSpray;
                case BUILTIN_TRAINS:
                    return config().disableBuiltInTrains;
                case TRAIN_COLLISION:
                    return config().trainCollision;
                case TRACKBED:
                    return config().trackbedCollision;
                case TRAIN_DAMAGE:
                    return eff.trainCollisionDamage;
                case TRAIN_MOBS:
                    return config().trainCollisionMobs;
                case DAMAGE_IN_CREATIVE:
                    return config().damageInCreative;
                case RAIN_HUSH:
                    return eff.rainHush;
                default:
                    return eff.beamGlow;
            }
        }

        @Override
        void writeToggle(final int which, final boolean on) {
            switch (which) {
                case MASTER:
                    config().enabled = on;
                    break;
                case DYNAMIC_LIGHT:
                    config().dynamicLight = on;
                    break;
                case BEAM_CONE:
                    config().beamCone = on;
                    break;
                case RAIN_SPRAY:
                    config().rainSpray = on;
                    break;
                case BUILTIN_TRAINS:
                    config().disableBuiltInTrains = on;
                    break;
                case TRAIN_COLLISION:
                    config().trainCollision = on;
                    break;
                case TRACKBED:
                    config().trackbedCollision = on;
                    break;
                case TRAIN_DAMAGE:
                    config().trainCollisionDamage = on;
                    break;
                case TRAIN_MOBS:
                    config().trainCollisionMobs = on;
                    break;
                case DAMAGE_IN_CREATIVE:
                    config().damageInCreative = on;
                    break;
                case RAIN_HUSH:
                    config().rainHush = on;
                    break;
                default:
                    config().beamGlow = on;
                    break;
            }
        }

        @Override
        int readColor(final boolean head) {
            return head ? effective().beamHeadColor : effective().beamTailColor;
        }

        @Override
        void writeColor(final boolean head, final int rgb) {
            final String text = HeadlightConfig.formatColor(rgb);
            if (head) {
                config().beamHeadColor = text;
            } else {
                config().beamTailColor = text;
            }
        }

        @Override
        boolean followsValue(final int which) {
            return false;
        }

        @Override
        boolean followsToggle(final int which) {
            return false;
        }

        @Override
        boolean followsColor(final boolean head) {
            return false;
        }

        @Override
        boolean hasOverrides() {
            return false;
        }

        /**
         * The one field this row shows goes back to the shipped default; every other field, and the
         * siding overrides, are left exactly as they are.
         */
        @Override
        void resetValue(final int which) {
            final HeadlightConfig d = HeadlightConfig.defaults();
            final HeadlightConfig c = config();
            switch (which) {
                case DISTANCE:
                    c.maxDistance = d.maxDistance;
                    break;
                case LUMINANCE:
                    c.headLuminance = d.headLuminance;
                    break;
                case HEAD_LENGTH:
                    c.headLength = d.headLength;
                    break;
                case BEAM_DISTANCE:
                    c.beamDistance = d.beamDistance;
                    break;
                case BEAM_HEAD_LENGTH:
                    c.beamHeadLength = d.beamHeadLength;
                    break;
                case BEAM_TAIL_LENGTH:
                    c.beamTailLength = d.beamTailLength;
                    break;
                case BEAM_WIDTH:
                    c.beamWidth = d.beamWidth;
                    break;
                case BEAM_HEAD_HEIGHT:
                    c.beamHeadHeight = d.beamHeadHeight;
                    break;
                case COLLISION_HEAD_EXTRA:
                    c.collisionHeadExtra = d.collisionHeadExtra;
                    break;
                case COLLISION_TAIL_EXTRA:
                    c.collisionTailExtra = d.collisionTailExtra;
                    break;
                case RAIN_SPRAY_MIN_SPEED:
                    c.rainSprayMinSpeedKmh = d.rainSprayMinSpeedKmh;
                    break;
                case DAMAGE_MIN_SPEED:
                    c.trainCollisionPushOnlyMaxKmh = d.trainCollisionPushOnlyMaxKmh;
                    break;
                case DAMAGE_MAX:
                    c.trainCollisionDamageMax = d.trainCollisionDamageMax;
                    break;
                case COLLISION_KNOCKBACK:
                    c.knockbackDistance = d.knockbackDistance;
                    break;
                case COLLISION_KNOCKBACK_K:
                    c.knockbackExpK = d.knockbackExpK;
                    break;
                case COLLISION_KNOCKBACK_MAX:
                    c.knockbackMax = d.knockbackMax;
                    break;
                case COLLISION_PUSH_MAX:
                    c.trainCollisionPushOnlyMaxKmh = d.trainCollisionPushOnlyMaxKmh;
                    break;
                case COLLISION_LOW_MAX:
                    c.trainCollisionLowDamageMaxKmh = d.trainCollisionLowDamageMaxKmh;
                    break;
                case COLLISION_HIGH_MAX:
                    c.trainCollisionHighDamageMaxKmh = d.trainCollisionHighDamageMaxKmh;
                    break;
                case COLLISION_LAUNCH_MAX:
                    c.trainCollisionLaunchMaxKmh = d.trainCollisionLaunchMaxKmh;
                    break;
                case COLLISION_BASE_RATE:
                    c.trainCollisionDamageBasePerSecond = d.trainCollisionDamageBasePerSecond;
                    break;
                case COLLISION_EXP_K:
                    c.trainCollisionDamageExpK = d.trainCollisionDamageExpK;
                    break;
                case COLLISION_RATE_SCALE:
                    c.trainCollisionDamageSpeedScaleKmh = d.trainCollisionDamageSpeedScaleKmh;
                    break;
                case COLLISION_FLY_SCALE:
                    c.trainCollisionFlyDamageScale = d.trainCollisionFlyDamageScale;
                    break;
                case COLLISION_LAUNCH_H:
                    c.trainCollisionLaunchHorizontal = d.trainCollisionLaunchHorizontal;
                    break;
                case COLLISION_LAUNCH_V:
                    c.trainCollisionLaunchVertical = d.trainCollisionLaunchVertical;
                    break;
                case COLLISION_FLY_H:
                    c.trainCollisionFlyHorizontal = d.trainCollisionFlyHorizontal;
                    break;
                case COLLISION_FLY_V:
                    c.trainCollisionFlyVertical = d.trainCollisionFlyVertical;
                    break;
                case COLLISION_COOLDOWN:
                    c.trainCollisionImpactCooldownTicks = d.trainCollisionImpactCooldownTicks;
                    break;
                case COLLISION_HEIGHT:
                    c.trainCollisionHeight = d.trainCollisionHeight;
                    break;
                case ROOF_DROP_SPEED:
                    c.roofDropMinSpeedKmh = d.roofDropMinSpeedKmh;
                    break;
                case ROOF_DROP_RADIUS:
                    c.roofDropMaxRadiusM = d.roofDropMaxRadiusM;
                    break;
                case HEADLIGHT_MODE:
                    c.headlightMode = d.headlightMode;
                    break;
                default:
                    c.beamTailHeight = d.beamTailHeight;
                    break;
            }
        }

        @Override
        void resetToggle(final int which) {
            final HeadlightConfig d = HeadlightConfig.defaults();
            final HeadlightConfig c = config();
            switch (which) {
                case MASTER:
                    c.enabled = d.enabled;
                    break;
                case DYNAMIC_LIGHT:
                    c.dynamicLight = d.dynamicLight;
                    break;
                case BEAM_CONE:
                    c.beamCone = d.beamCone;
                    break;
                case RAIN_SPRAY:
                    c.rainSpray = d.rainSpray;
                    break;
                case BUILTIN_TRAINS:
                    c.disableBuiltInTrains = d.disableBuiltInTrains;
                    break;
                case TRAIN_COLLISION:
                    c.trainCollision = d.trainCollision;
                    break;
                case TRACKBED:
                    c.trackbedCollision = d.trackbedCollision;
                    break;
                case TRAIN_DAMAGE:
                    c.trainCollisionDamage = d.trainCollisionDamage;
                    break;
                case TRAIN_MOBS:
                    c.trainCollisionMobs = d.trainCollisionMobs;
                    break;
                case DAMAGE_IN_CREATIVE:
                    c.damageInCreative = d.damageInCreative;
                    break;
                case RAIN_HUSH:
                    c.rainHush = d.rainHush;
                    break;
                default:
                    c.beamGlow = d.beamGlow;
                    break;
            }
        }

        @Override
        void resetColor(final boolean head) {
            final HeadlightConfig d = HeadlightConfig.defaults();
            final HeadlightConfig c = config();
            if (head) {
                c.beamHeadColor = d.beamHeadColor;
            } else {
                c.beamTailColor = d.beamTailColor;
            }
        }

        @Override
        void reset() {
            // Nothing to reset: the global target is the thing a siding follows.
        }

        /** Every global field the screens show, plus every siding override (see {@link #resetAll()}). */
        @Override
        void resetAll() {
            final HeadlightConfig d = HeadlightConfig.defaults();
            final HeadlightConfig c = config();
            c.enabled = d.enabled;
            c.dynamicLight = d.dynamicLight;
            c.beamGlow = d.beamGlow;
            c.beamCone = d.beamCone;
            c.rainSpray = d.rainSpray;
            c.rainSprayMinSpeedKmh = d.rainSprayMinSpeedKmh;
            c.disableBuiltInTrains = d.disableBuiltInTrains;
            c.maxDistance = d.maxDistance;
            c.headLuminance = d.headLuminance;
            c.headLength = d.headLength;
            c.beamDistance = d.beamDistance;
            c.beamHeadLength = d.beamHeadLength;
            c.beamTailLength = d.beamTailLength;
            c.beamWidth = d.beamWidth;
            c.beamHeadColor = d.beamHeadColor;
            c.beamTailColor = d.beamTailColor;
            c.beamHeadHeight = d.beamHeadHeight;
            c.beamTailHeight = d.beamTailHeight;
            c.collisionHeadExtra = d.collisionHeadExtra;
            c.collisionTailExtra = d.collisionTailExtra;
            c.trainCollisionPushOnlyMaxKmh = d.trainCollisionPushOnlyMaxKmh;
            c.trainCollisionLowDamageMaxKmh = d.trainCollisionLowDamageMaxKmh;
            c.trainCollisionHighDamageMaxKmh = d.trainCollisionHighDamageMaxKmh;
            c.trainCollisionLaunchMaxKmh = d.trainCollisionLaunchMaxKmh;
            c.trainCollisionDamageBasePerSecond = d.trainCollisionDamageBasePerSecond;
            c.trainCollisionDamageExpK = d.trainCollisionDamageExpK;
            c.trainCollisionDamageSpeedScaleKmh = d.trainCollisionDamageSpeedScaleKmh;
            c.trainCollisionFlyDamageScale = d.trainCollisionFlyDamageScale;
            c.knockbackDistance = d.knockbackDistance;
            c.knockbackExpK = d.knockbackExpK;
            c.knockbackMax = d.knockbackMax;
            c.trainCollisionLaunchHorizontal = d.trainCollisionLaunchHorizontal;
            c.trainCollisionLaunchVertical = d.trainCollisionLaunchVertical;
            c.trainCollisionFlyHorizontal = d.trainCollisionFlyHorizontal;
            c.trainCollisionFlyVertical = d.trainCollisionFlyVertical;
            c.trainCollisionImpactCooldownTicks = d.trainCollisionImpactCooldownTicks;
            // 分区重构后新加进界面的字段，同样要跟着「全部恢复默认」一起回去。
            c.trainCollision = d.trainCollision;
            c.trainCollisionHeight = d.trainCollisionHeight;
            c.trainCollisionDamage = d.trainCollisionDamage;
            c.trainCollisionDamageMax = d.trainCollisionDamageMax;
            c.trainCollisionMobs = d.trainCollisionMobs;
            c.damageInCreative = d.damageInCreative;
            c.trackbedCollision = d.trackbedCollision;
            c.roofDropMinSpeedKmh = d.roofDropMinSpeedKmh;
            c.roofDropMaxRadiusM = d.roofDropMaxRadiusM;
            c.rainHush = d.rainHush;
            c.headlightMode = d.headlightMode;
            // Restoring the globals while a siding still overrode them would leave the world on the
            // old numbers - "全部恢复默认" means the overrides go too.
            if (c.sidingSettings != null) {
                c.sidingSettings.clear();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Per-siding target
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes {@code sidingSettings[id]} and nothing else. The global fields of
     * {@link HeadlightConfig} are never assigned in this class - grep it, there is no
     * {@code cfg.<field> =} statement here.
     */
    private static final class SidingTarget extends ConfigWriteTarget {
        private final long sidingId;

        SidingTarget(final Supplier<HeadlightConfig> cfg, final long sidingId) {
            super(cfg);
            if (sidingId == 0L) {
                // effectiveFor(0) is the global config by definition, so an id of 0 would make
                // every read fall back to the globals and every write land in an entry nothing
                // reads - the exact "I changed this siding and the globals moved" confusion.
                throw new IllegalArgumentException("sidingId 0 means the global config");
            }
            this.sidingId = sidingId;
        }

        @Override
        String describe() {
            return "siding #" + this.sidingId;
        }

        @Override
        HeadlightConfig.Effective effective() {
            return config().effectiveFor(this.sidingId);
        }

        /** Get-or-create this siding's entry - the only object this class ever writes. */
        private HeadlightConfig.SidingSettings entry() {
            return config().settingsFor(this.sidingId);
        }

        @Override
        double readValue(final int which) {
            final HeadlightConfig.Effective eff = effective();
            switch (which) {
                case DISTANCE:
                    return eff.maxDistance;
                case LUMINANCE:
                    return eff.headLuminance;
                case HEAD_LENGTH:
                    return eff.headLength;
                case BEAM_DISTANCE:
                    return eff.beamDistance;
                case BEAM_HEAD_LENGTH:
                    return eff.beamHeadLength;
                case BEAM_TAIL_LENGTH:
                    return eff.beamTailLength;
                case BEAM_WIDTH:
                    return eff.beamWidth;
                case BEAM_HEAD_HEIGHT:
                    return eff.beamHeadHeight;
                case COLLISION_HEAD_EXTRA:
                    return eff.collisionHeadExtra;
                case COLLISION_TAIL_EXTRA:
                    return eff.collisionTailExtra;
                case RAIN_SPRAY_MIN_SPEED:
                    return eff.rainSprayMinSpeedKmh;
                case DAMAGE_MIN_SPEED:
                    return eff.trainCollisionDamageMinSpeedKmh;
                case DAMAGE_MAX:
                    return eff.trainCollisionDamageMax;
                case COLLISION_PUSH_MAX:
                case COLLISION_LOW_MAX:
                case COLLISION_HIGH_MAX:
                case COLLISION_LAUNCH_MAX:
                case COLLISION_BASE_RATE:
                case COLLISION_EXP_K:
                case COLLISION_RATE_SCALE:
                case COLLISION_FLY_SCALE:
                case COLLISION_LAUNCH_H:
                case COLLISION_LAUNCH_V:
                case COLLISION_FLY_H:
                case COLLISION_FLY_V:
                case COLLISION_COOLDOWN:
                case COLLISION_KNOCKBACK:
                case COLLISION_KNOCKBACK_K:
                case COLLISION_KNOCKBACK_MAX:
                case COLLISION_HEIGHT:
                case ROOF_DROP_SPEED:
                case ROOF_DROP_RADIUS:
                case HEADLIGHT_MODE:
                    ignored(which);
                    return 0.0D;
                default:
                    return eff.beamTailHeight;
            }
        }

        @Override
        void writeValue(final int which, final double value) {
            final HeadlightConfig.SidingSettings o = entry();
            switch (which) {
                case DISTANCE:
                    o.maxDistance = Double.valueOf(value);
                    break;
                case LUMINANCE:
                    o.headLuminance = Integer.valueOf((int) Math.round(value));
                    break;
                case HEAD_LENGTH:
                    o.headLength = Double.valueOf(value);
                    break;
                case BEAM_DISTANCE:
                    o.beamDistance = Double.valueOf(value);
                    break;
                case BEAM_HEAD_LENGTH:
                    o.beamHeadLength = Double.valueOf(value);
                    break;
                case BEAM_TAIL_LENGTH:
                    o.beamTailLength = Double.valueOf(value);
                    break;
                case BEAM_WIDTH:
                    o.beamWidth = Double.valueOf(value);
                    break;
                case BEAM_HEAD_HEIGHT:
                    o.beamHeadHeight = Double.valueOf(value);
                    break;
                case COLLISION_HEAD_EXTRA:
                    o.collisionHeadExtra = Double.valueOf(value);
                    break;
                case COLLISION_TAIL_EXTRA:
                    o.collisionTailExtra = Double.valueOf(value);
                    break;
                case RAIN_SPRAY_MIN_SPEED:
                    o.rainSprayMinSpeedKmh = Double.valueOf(value);
                    break;
                case DAMAGE_MIN_SPEED:
                    o.trainCollisionDamageMinSpeedKmh = Double.valueOf(value);
                    break;
                case DAMAGE_MAX:
                    o.trainCollisionDamageMax = Double.valueOf(value);
                    break;
                case COLLISION_PUSH_MAX:
                case COLLISION_LOW_MAX:
                case COLLISION_HIGH_MAX:
                case COLLISION_LAUNCH_MAX:
                case COLLISION_BASE_RATE:
                case COLLISION_EXP_K:
                case COLLISION_RATE_SCALE:
                case COLLISION_FLY_SCALE:
                case COLLISION_LAUNCH_H:
                case COLLISION_LAUNCH_V:
                case COLLISION_FLY_H:
                case COLLISION_FLY_V:
                case COLLISION_COOLDOWN:
                case COLLISION_KNOCKBACK:
                case COLLISION_KNOCKBACK_K:
                case COLLISION_KNOCKBACK_MAX:
                case COLLISION_HEIGHT:
                case ROOF_DROP_SPEED:
                case ROOF_DROP_RADIUS:
                case HEADLIGHT_MODE:
                    ignored(which);
                    break;
                default:
                    o.beamTailHeight = Double.valueOf(value);
                    break;
            }
        }

        @Override
        boolean readToggle(final int which) {
            final HeadlightConfig.Effective eff = effective();
            switch (which) {
                case MASTER:
                    return eff.enabled;
                case DYNAMIC_LIGHT:
                    return eff.dynamicLight;
                case BEAM_CONE:
                    return eff.beamCone;
                case RAIN_SPRAY:
                    return eff.rainSpray;
                case TRAIN_DAMAGE:
                    return eff.trainCollisionDamage;
                case RAIN_HUSH:
                    return eff.rainHush;
                case BUILTIN_TRAINS:
                case TRAIN_COLLISION:
                case TRACKBED:
                case TRAIN_MOBS:
                case DAMAGE_IN_CREATIVE:
                    ignored(which);
                    return false;
                default:
                    return eff.beamGlow;
            }
        }

        @Override
        void writeToggle(final int which, final boolean on) {
            final HeadlightConfig.SidingSettings o = entry();
            switch (which) {
                case MASTER:
                    o.enabled = Boolean.valueOf(on);
                    break;
                case DYNAMIC_LIGHT:
                    o.dynamicLight = Boolean.valueOf(on);
                    break;
                case BEAM_CONE:
                    o.beamCone = Boolean.valueOf(on);
                    break;
                case RAIN_SPRAY:
                    o.rainSpray = Boolean.valueOf(on);
                    break;
                case TRAIN_DAMAGE:
                    o.trainCollisionDamage = Boolean.valueOf(on);
                    break;
                case RAIN_HUSH:
                    o.rainHush = Boolean.valueOf(on);
                    break;
                case BUILTIN_TRAINS:
                case TRAIN_COLLISION:
                case TRACKBED:
                case TRAIN_MOBS:
                case DAMAGE_IN_CREATIVE:
                    ignored(which);
                    break;
                default:
                    o.beamGlow = Boolean.valueOf(on);
                    break;
            }
        }

        @Override
        int readColor(final boolean head) {
            return head ? effective().beamHeadColor : effective().beamTailColor;
        }

        @Override
        void writeColor(final boolean head, final int rgb) {
            final HeadlightConfig.SidingSettings o = entry();
            final String text = HeadlightConfig.formatColor(rgb);
            if (head) {
                o.beamHeadColor = text;
            } else {
                o.beamTailColor = text;
            }
        }

        @Override
        boolean followsValue(final int which) {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            if (o == null) {
                return true;
            }
            switch (which) {
                case DISTANCE:
                    return o.maxDistance == null;
                case LUMINANCE:
                    return o.headLuminance == null;
                case HEAD_LENGTH:
                    return o.headLength == null;
                case BEAM_DISTANCE:
                    return o.beamDistance == null;
                case BEAM_HEAD_LENGTH:
                    return o.beamHeadLength == null;
                case BEAM_TAIL_LENGTH:
                    return o.beamTailLength == null;
                case BEAM_WIDTH:
                    return o.beamWidth == null;
                case BEAM_HEAD_HEIGHT:
                    return o.beamHeadHeight == null;
                case COLLISION_HEAD_EXTRA:
                    return o.collisionHeadExtra == null;
                case COLLISION_TAIL_EXTRA:
                    return o.collisionTailExtra == null;
                case RAIN_SPRAY_MIN_SPEED:
                    return o.rainSprayMinSpeedKmh == null;
                case DAMAGE_MIN_SPEED:
                    return o.trainCollisionDamageMinSpeedKmh == null;
                case DAMAGE_MAX:
                    return o.trainCollisionDamageMax == null;
                case COLLISION_PUSH_MAX:
                case COLLISION_LOW_MAX:
                case COLLISION_HIGH_MAX:
                case COLLISION_LAUNCH_MAX:
                case COLLISION_BASE_RATE:
                case COLLISION_EXP_K:
                case COLLISION_RATE_SCALE:
                case COLLISION_FLY_SCALE:
                case COLLISION_LAUNCH_H:
                case COLLISION_LAUNCH_V:
                case COLLISION_FLY_H:
                case COLLISION_FLY_V:
                case COLLISION_COOLDOWN:
                case COLLISION_KNOCKBACK:
                case COLLISION_KNOCKBACK_K:
                case COLLISION_KNOCKBACK_MAX:
                case COLLISION_HEIGHT:
                case ROOF_DROP_SPEED:
                case ROOF_DROP_RADIUS:
                case HEADLIGHT_MODE:
                    ignored(which);
                    return true;
                default:
                    return o.beamTailHeight == null;
            }
        }

        @Override
        boolean followsToggle(final int which) {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            if (o == null) {
                return true;
            }
            switch (which) {
                case MASTER:
                    return o.enabled == null;
                case DYNAMIC_LIGHT:
                    return o.dynamicLight == null;
                case BEAM_CONE:
                    return o.beamCone == null;
                case RAIN_SPRAY:
                    return o.rainSpray == null;
                case TRAIN_DAMAGE:
                    return o.trainCollisionDamage == null;
                case RAIN_HUSH:
                    return o.rainHush == null;
                case BUILTIN_TRAINS:
                case TRAIN_COLLISION:
                case TRACKBED:
                case TRAIN_MOBS:
                case DAMAGE_IN_CREATIVE:
                    ignored(which);
                    return true;
                default:
                    return o.beamGlow == null;
            }
        }

        @Override
        boolean followsColor(final boolean head) {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            if (o == null) {
                return true;
            }
            return head ? o.beamHeadColor == null : o.beamTailColor == null;
        }

        @Override
        boolean hasOverrides() {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            return o != null && !o.isEmpty();
        }

        /** The entry to clear fields on, or null when this siding follows everything anyway. */
        private HeadlightConfig.SidingSettings dropEntry() {
            return config().overrides(this.sidingId);
        }

        /** Drops the entry once nothing overrides anything, so the JSON stays sparse. */
        private void prune() {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            if (o != null && o.isEmpty()) {
                config().clearSiding(this.sidingId);
            }
        }

        @Override
        void resetValue(final int which) {
            final HeadlightConfig.SidingSettings o = dropEntry();
            if (o == null) {
                return;
            }
            switch (which) {
                case DISTANCE:
                    o.maxDistance = null;
                    break;
                case LUMINANCE:
                    o.headLuminance = null;
                    break;
                case HEAD_LENGTH:
                    o.headLength = null;
                    break;
                case BEAM_DISTANCE:
                    o.beamDistance = null;
                    break;
                case BEAM_HEAD_LENGTH:
                    o.beamHeadLength = null;
                    break;
                case BEAM_TAIL_LENGTH:
                    o.beamTailLength = null;
                    break;
                case BEAM_WIDTH:
                    o.beamWidth = null;
                    break;
                case BEAM_HEAD_HEIGHT:
                    o.beamHeadHeight = null;
                    break;
                case COLLISION_HEAD_EXTRA:
                    o.collisionHeadExtra = null;
                    break;
                case COLLISION_TAIL_EXTRA:
                    o.collisionTailExtra = null;
                    break;
                case RAIN_SPRAY_MIN_SPEED:
                    o.rainSprayMinSpeedKmh = null;
                    break;
                case DAMAGE_MIN_SPEED:
                    o.trainCollisionDamageMinSpeedKmh = null;
                    break;
                case DAMAGE_MAX:
                    o.trainCollisionDamageMax = null;
                    break;
                case COLLISION_PUSH_MAX:
                case COLLISION_LOW_MAX:
                case COLLISION_HIGH_MAX:
                case COLLISION_LAUNCH_MAX:
                case COLLISION_BASE_RATE:
                case COLLISION_EXP_K:
                case COLLISION_RATE_SCALE:
                case COLLISION_FLY_SCALE:
                case COLLISION_LAUNCH_H:
                case COLLISION_LAUNCH_V:
                case COLLISION_FLY_H:
                case COLLISION_FLY_V:
                case COLLISION_COOLDOWN:
                case COLLISION_KNOCKBACK:
                case COLLISION_KNOCKBACK_K:
                case COLLISION_KNOCKBACK_MAX:
                case COLLISION_HEIGHT:
                case ROOF_DROP_SPEED:
                case ROOF_DROP_RADIUS:
                case HEADLIGHT_MODE:
                    ignored(which);
                    break;
                default:
                    o.beamTailHeight = null;
                    break;
            }
            prune();
        }

        @Override
        void resetToggle(final int which) {
            final HeadlightConfig.SidingSettings o = dropEntry();
            if (o == null) {
                return;
            }
            switch (which) {
                case MASTER:
                    o.enabled = null;
                    break;
                case DYNAMIC_LIGHT:
                    o.dynamicLight = null;
                    break;
                case BEAM_CONE:
                    o.beamCone = null;
                    break;
                case RAIN_SPRAY:
                    o.rainSpray = null;
                    break;
                case TRAIN_DAMAGE:
                    o.trainCollisionDamage = null;
                    break;
                case RAIN_HUSH:
                    o.rainHush = null;
                    break;
                case BUILTIN_TRAINS:
                case TRAIN_COLLISION:
                case TRACKBED:
                case TRAIN_MOBS:
                case DAMAGE_IN_CREATIVE:
                    ignored(which);
                    break;
                default:
                    o.beamGlow = null;
                    break;
            }
            prune();
        }

        @Override
        void resetColor(final boolean head) {
            final HeadlightConfig.SidingSettings o = dropEntry();
            if (o == null) {
                return;
            }
            if (head) {
                o.beamHeadColor = null;
            } else {
                o.beamTailColor = null;
            }
            prune();
        }

        @Override
        void reset() {
            config().clearSiding(this.sidingId);
        }

        /** 全部恢复默认 on a detail page: delete this siding's entry, leave the globals alone. */
        @Override
        void resetAll() {
            config().clearSiding(this.sidingId);
        }
    }
}
