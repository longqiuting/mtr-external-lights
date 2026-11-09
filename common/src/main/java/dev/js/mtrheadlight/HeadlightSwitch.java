package dev.js.mtrheadlight;

import net.minecraft.client.world.ClientWorld;

import java.util.Locale;

/**
 * 车灯开关的<b>唯一信号源</b>：回答"这列车现在该不该亮"。
 *
 * <p>三个消费者都只问这里，不再各自读配置、各自判昼夜，所以它们不可能对同一列车给出不同答案：
 * <ol>
 *   <li>{@link BeamGlow}（每帧的光带）——不亮就不生成顶点；</li>
 *   <li>{@link LightController}（动态光源 / LambDynamicLights）——不亮就在<b>过渡那一 tick</b>
 *       把光源从引擎里摘掉（{@code removeLine}），而不是继续 {@code updateLine}；</li>
 *   <li>{@link RailRelightScheduler}（轨道烘焙的亮度）——不亮就 {@code release} 掉那列车的
 *       rail chunk，不再把它当作光照来源。</li>
 * </ol>
 *
 * <p>为什么要有这个类：光带和动态光源原来是两条各自判断的路（{@code LightController} 一处、
 * {@code BeamGlow} 一处），只要其中一处漏改或者读错字段，就会出现"光带灭了但地面上那盏点光源
 * 还在亮"——这正是 0.2.2 的现象。把判定收成一个方法后，漏改一处就编译不过，而不是在游戏里
 * 表现为半亮。
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
     */
    public static boolean isLit(final ClientWorld world, final TrainPose pose, final HeadlightConfig cfg) {
        try {
            if (cfg == null) {
                return true;
            }
            if (!cfg.autoHeadlights()) {
                // ALWAYS: 常亮；OFF: 不做自动关灯。两者都不让环境判定插手。
                return true;
            }
            return RailWeather.lightsNeeded(world, pose);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 诊断行：{@code auto light: mode=AUTO openSky=true night=true sky=0.20 lit=on}。
     *
     * <p>五个字段全部来自同一次判定，所以 {@code lit} 一定和 {@code isLit} 的答案一致——
     * 这一行是证据，不是另一套算法。只在 5 秒窗口那一 tick 调用（每个窗口最多 8 行）。
     */
    public static String diagnose(final ClientWorld world, final TrainPose pose,
                                  final HeadlightConfig cfg) {
        final String mode = cfg == null ? HeadlightConfig.MODE_AUTO : cfg.resolvedMode();
        final boolean lit = isLit(world, pose, cfg);
        final boolean openSky = RailWeather.openSky(world, pose);
        final boolean night = RailWeather.isNight(world);
        final float sky = RailWeather.skyBrightness(world);
        return String.format(Locale.ROOT,
                "auto light: mode=%s openSky=%s night=%s sky=%.2f lit=%s",
                mode, Boolean.valueOf(openSky), Boolean.valueOf(night), Float.valueOf(sky),
                lit ? "on" : "off");
    }
}
