package dev.js.mtrheadlight;

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
 *
 * <p>为什么要有这个类：光带和动态光源原来是两条各自判断的路（{@code LightController} 一处、
 * {@code BeamGlow} 一处），只要其中一处漏改或者读错字段，就会出现"光带灭了但地面上那盏点光源
 * 还在亮"——这正是 0.2.2 的现象。把判定收成一个方法后，漏改一处就编译不过，而不是在游戏里
 * 表现为半亮。光柱是第三个偷偷自己判断的地方（只在 GLOW 源里查过一次），现在也走这里。
 *
 * <p>判定本身仍然是 {@link RailWeather#lightsNeeded}（暗 / 隧道 / 雨 / 雷），本类只负责把
 * {@code headlightMode} 折叠进来：
 * {@code ALWAYS} / {@code OFF} 直接为真（不自动关灯），{@code AUTO} 才交给环境判定。
 */
public final class HeadlightSwitch {
    private HeadlightSwitch() {
    }

    /**
     * 这列车这一刻该不该亮。
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
}
