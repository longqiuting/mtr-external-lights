package dev.js.mtrheadlight;

import mtr.data.TrainClient;
import net.minecraft.client.world.ClientWorld;

import java.util.Locale;

/**
 * 车灯开关的<b>唯一信号源</b>：回答"这列车现在该不该亮"。
 *
 * <p>四个消费者都只问这里，不再各自读配置、各自判昼夜，所以它们不可能对同一列车给出不同答案：
 * <ol>
 *   <li>{@link BeamGlow}（每帧的光带）——不亮就不生成顶点；</li>
 *   <li>{@link ProtoBeamCone}（每帧的光柱）——不亮就不发锥，和光带同一个答案；</li>
 *   <li>{@link LightController}（动态光源 / LambDynamicLights）——不亮就在<b>过渡那一 tick</b>
 *       把光源从引擎里摘掉（{@code removeLine}），而不是继续 {@code updateLine}；</li>
 *   <li>{@link RailRelightScheduler}（轨道烘焙的亮度）——不亮就 {@code release} 掉那列车的
 *       rail chunk，不再把它当作光照来源。</li>
 * </ol>
 * 1.20.1 的屏幕空间灯晕（{@code LightFlare}）与散射（{@code sss.ScatterLights}）也走这里。
 *
 * <p>为什么要有这个类：光带和动态光源原来是两条各自判断的路（{@code LightController} 一处、
 * {@code BeamGlow} 一处），只要其中一处漏改或者读错字段，就会出现"光带灭了但地面上那盏点光源
 * 还在亮"——这正是 0.2.2 的现象。把判定收成一个方法后，漏改一处就编译不过，而不是在游戏里
 * 表现为半亮。光柱是第三个偷偷自己判断的地方（只在 GLOW 源里查过一次），现在也走这里。
 *
 * <h2>三道闸（{@link #judge}，按顺序短路）</h2>
 * <ol>
 *   <li><b>在线路上</b> —— {@link DepartureState#departed}：MTR 同步下来的
 *       {@code Train#getIsOnRoute()}，加上"这个客户端亲眼见过它开动"的闩锁。车厂 / 回库 /
 *       根本没派车的列车在这里就被拦掉，<b>这就是"光晕在车厂也不关"的修复</b>——灯晕和散射原来
 *       只问昼夜，从来没问过这一条。</li>
 *   <li><b>环境</b>（只有 {@code AUTO}）—— {@link RailWeather#lightsNeeded}：夜晚 / 隧道 / 雨 / 雷。</li>
 *   <li><b>没停着</b>（只有 {@code AUTO}）—— {@link DepartureState#stopped}：MTR 的 speed 连续
 *       {@code parkedLightsOffTicks} 个 tick 为 0，即"停车灯灭"。</li>
 * </ol>
 * {@code ALWAYS} / {@code OFF} 只受第一道闸约束（常亮，但仍不给出库的车点亮）。
 *
 * <p>判定本身仍然是 {@link RailWeather#lightsNeeded}（暗 / 隧道 / 雨 / 雷），{@link #isLit} 只负责
 * 把 {@code headlightMode} 折叠进来：{@code ALWAYS} / {@code OFF} 直接为真（不自动关灯），
 * {@code AUTO} 才交给环境判定。
 */
public final class HeadlightSwitch {
    private HeadlightSwitch() {
    }

    /**
     * 一次判定的结果：亮，或者<b>为什么不亮</b>。词表固定为三个原因（指令里的
     * {@code skipped=offRoute|dark|parked} 就是它们），消费者拿它计数、写日志，用户因此能看到
     * "这列车为什么没亮"，而不是只看到一个空屏幕。
     */
    public enum Verdict {
        /** 亮。 */
        LIT("lit"),
        /** 不在运行线路上：车厂 / 车辆段 / 未派车（{@code Train#getIsOnRoute()} 为假）。 */
        OFF_ROUTE("offRoute"),
        /** {@code AUTO} 且此刻不需要灯（白天露天、没有雨/雷、不在隧道里）。 */
        DARK("dark"),
        /** {@code AUTO} 且列车已经停住（{@code speed ≈ 0} 超过停放阈值）：停车灯灭。 */
        PARKED("parked");

        /** 日志/指令里用的词，不带空格，直接可以拼进一行。 */
        public final String token;

        Verdict(final String token) {
            this.token = token;
        }

        /** {@code true} 只对 {@link #LIT}。 */
        public boolean lit() {
            return this == LIT;
        }
    }

    /**
     * 这列车这一刻该不该亮——带原因。灯晕和散射都用这一个入口，所以"一遍亮一遍不亮"不可能发生。
     *
     * <p>抛异常时返回 {@link Verdict#LIT}：宁可多亮一盏，也不要因为一个异常让整张图的车灯全灭
     * （和 {@link #isLit} 的失败方向一致）。
     *
     * @param world 客户端世界（环境判定的输入）
     * @param pose  这一帧的姿态（露天探针要它）
     * @param cfg   活动配置
     * @param train 列车本体：{@code getIsOnRoute()} 与 {@code getSpeed()} 都从这里读
     */
    public static Verdict judge(final ClientWorld world, final TrainPose pose,
                                final HeadlightConfig cfg, final TrainClient train) {
        if (train == null || cfg == null) {
            return Verdict.LIT;
        }
        try {
            if (!DepartureState.departed(train)) {
                return Verdict.OFF_ROUTE;
            }
            if (cfg.autoHeadlights()) {
                // 环境闸复用 isLit（它把 headlightMode 折叠进 RailWeather），所以「暗 / 隧道 / 雨」
                // 只有一份实现；这里只是补上它不知道的在线与停车两条。
                if (!isLit(world, pose, cfg, train.id)) {
                    return Verdict.DARK;
                }
                if (DepartureState.stopped(train, cfg)) {
                    return Verdict.PARKED;
                }
            }
            return Verdict.LIT;
        } catch (Throwable t) {
            return Verdict.LIT;
        }
    }

    /**
     * 这列车这一刻该不该亮 —— <b>只管环境那一半</b>（{@code headlightMode} + 昼夜/隧道/雨）。
     *
     * <p>完整的判定是 {@link #judge}，它在环境这一半之外还要求「在线路上」和「没有停住」。
     * 新的消费者都应该用 {@code judge}；本方法保留是因为它是环境判定的规范入口，也是
     * {@code judge} 内部调用的那一个（这样"暗/隧道/雨"只有一份实现）。
     *
     * <p>配置读不到、世界读不到、判定抛异常时返回 {@code true}：宁可多亮一盏，也不要因为一个
     * 异常让整张图的车灯全灭。
     *
     * @param trainId 列车的稳定标识（{@code TrainClient#id}）。所有消费者必须传同一个值——它同时
     *                是露天判定 0.5 s 去抖状态的键。传 {@link RailWeather#NO_KEY} 表示不去抖
     *                （只给诊断/离线工具）。
     */
    public static boolean isLit(final ClientWorld world, final TrainPose pose, final HeadlightConfig cfg,
                                final long trainId) {
        try {
            if (cfg == null) {
                return true;
            }
            if (!cfg.autoHeadlights()) {
                // ALWAYS: 常亮；OFF: 不做自动关灯。两者都不让环境判定插手。
                return true;
            }
            return RailWeather.lightsNeeded(world, pose, trainId);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 诊断行：{@code auto light: mode=AUTO openSky=true night=true sky=15 skyMid=15 bright=1.00 lit=on}。
     *
     * <p>每个字段都来自同一次判定，所以 {@code lit} 一定和 {@link #isLit} 的答案一致——这一行是
     * 证据，不是另一套算法。只在 5 秒窗口那一 tick 调用（每个窗口最多 8 行）。
     *
     * <p>{@code sky} / {@code skyMid} 是<b>天空光照等级</b>（0–15，`{@code -1}` = 读不到）：车头
     * 探针与车中探针，两个都 {@code >= }{@value RailWeather#SKY_LIGHT_MIN} 才算露天。{@code sky=15}
     * 而 {@code openSky=false} 说明车中那一点在隧道里（或者刚翻转、去抖还在计时）；
     * {@code sky=13 skyMid=15} 说明车头钻进了棚子。{@code bright} 是天空亮度（0.20 午夜 …1.00
     * 正午），{@code night} 是世界时钟判定——白天露天却 {@code lit=on} 时，看这两个字段区分
     * 「下雨/雷」还是「时钟/亮度判错」。
     */
    public static String diagnose(final ClientWorld world, final TrainPose pose,
                                  final HeadlightConfig cfg, final long trainId) {
        final String mode = cfg == null ? HeadlightConfig.MODE_AUTO : cfg.resolvedMode();
        final boolean lit = isLit(world, pose, cfg, trainId);
        final boolean openSky = RailWeather.openSky(world, pose, trainId);
        final boolean night = RailWeather.isNight(world);
        final int sky = RailWeather.skyLevelNose(world, pose);
        final int skyMid = RailWeather.skyLevelMid(world, pose);
        final float bright = RailWeather.skyBrightness(world);
        return String.format(Locale.ROOT,
                "auto light: mode=%s openSky=%s night=%s sky=%d skyMid=%d bright=%.2f lit=%s",
                mode, Boolean.valueOf(openSky), Boolean.valueOf(night),
                Integer.valueOf(sky), Integer.valueOf(skyMid), Float.valueOf(bright),
                lit ? "on" : "off");
    }

    /**
     * The same line plus {@code why=lit|offRoute|dark|parked} - the full {@link #judge} verdict,
     * including the two gates the environment answer knows nothing about.
     *
     * <p>{@code lit=on why=offRoute} is a real, useful combination: the environment says "it is dark
     * enough", and the train is still dark because it is <b>not on a route</b> (a depot / yard train).
     * {@code lit=on why=parked} is a train standing still long enough to count as stopped while the
     * mode is {@code AUTO}. Read {@code why} as the answer to "why is there no light".
     */
    public static String diagnose(final ClientWorld world, final TrainPose pose,
                                  final HeadlightConfig cfg, final TrainClient train) {
        return diagnose(world, pose, cfg, train == null ? RailWeather.NO_KEY : train.id)
                + " why=" + judge(world, pose, cfg, train).token;
    }
}
