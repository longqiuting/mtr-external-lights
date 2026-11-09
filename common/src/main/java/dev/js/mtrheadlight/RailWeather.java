package dev.js.mtrheadlight;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * 露天 / 昼夜 / 降雨判定 —— 车灯自动开关（{@link LightController}、{@link BeamGlow}）和
 * 雨天水花（1.20.1 的 {@code RainSpray}）共用的<b>唯一</b>信号源，所以车灯和水花对"这列车现在
 * 是不是在隧道里"永远给出同一个答案。
 *
 * <h2>露天判定用什么 API</h2>
 * 1.20.1 的 yarn 里没有 {@code World#canSeeSky}，能看到天空的公开入口是
 * {@code WorldView#isSkyVisibleAllowingSea(BlockPos)}（一个 default 方法，内部拿
 * {@code MOTION_BLOCKING} 高度图与 {@code pos.getY()} 比较）。它在四个支持版本
 * （1.18.2 / 1.19.2 / 1.19.4 / 1.20.1）的 yarn 里同名同签名，所以这段代码放在 {@code common}
 * 里对每个版本都能编译。读不到时退回
 * {@code pos.getY() >= getTopY(MOTION_BLOCKING, x, z)}，两者都抛异常才按"露天"处理。
 *
 * <h2>昼夜判定：不要用 {@code World#isDay()}（曾经的 bug）</h2>
 * 1.20.1 的 {@code World#isDay()} 字节码是
 * {@code !getDimension().hasFixedTime() && ambientDarkness < 4} —— <b>完全不看世界时钟</b>，
 * 而 {@code ambientDarkness} 在客户端只在 {@code ClientWorld} 的构造器里
 * （{@code calculateAmbientDarkness()}）算一次，之后整局游戏都不再更新。也就是说
 * {@code ClientWorld#isDay()} 返回的是"进世界那一刻是白天还是黑夜"，并且一直停在那个答案上：
 * 白天进服、玩到半夜，它仍然回答"白天"，于是露天段的灯整夜不亮 —— 这就是
 * "夜晚露天不开灯"的根因。
 *
 * <p>所以这里改成读<b>世界时钟</b>：{@code world.getTimeOfDay() % 24000} 落在
 * [{@value #NIGHT_START}, {@value #NIGHT_END}) 视为夜（13000 是"该睡了"，23000 天开始亮），
 * 同时把<b>天空亮度</b>也作为独立信号：{@code < }{@value #DARK_SKY} 视为暗。两者任成立即暗，
 * 再加上雨/雷。云端同步的时间（MTR 的列车永远在服务器时间里跑）与客户端渲染的日月位置
 * 走的是同一个 {@code getSkyAngle}，所以判定和玩家肉眼看到的黑天不会错位。
 *
 * <h2>天空亮度为什么自己算</h2>
 * {@code ClientWorld#getSkyBrightness(float)} 只在 1.19.4 / 1.20.1 的 {@code ClientWorld}
 * 上存在，1.18.2 / 1.19.2 没有，而这段代码在 {@code common} 里要四个版本都能编译。
 * {@link #skyBrightness} 逐行复刻那个方法的原版公式（值域 0.2 = 午夜 … 1.0 = 正午，雨/雷会
 * 进一步压暗），输入只有 {@code getSkyAngleRadians} + 雨量/雷量渐变 —— 这三个在四个版本的
 * {@code World} 上都有。数值与 1.19.4+ 的 {@code getSkyBrightness(1.0F)} 完全一致。
 *
 * <h2>采样点：车头 + 车中，最多两点</h2>
 * 车厢坐标来自 {@link TrainPose}（每节车厢有各自的两端与切线）：
 * <ul>
 *   <li>车头：{@code pose.front()} —— 车灯光束就是从这一点射出去的，它头顶有没有遮挡是车灯最
 *       关心的；</li>
 *   <li>车中：取中间那节车厢 {@code carStart/ carEnd} 的中点。</li>
 * </ul>
 * 两点<b>都</b>能看到天空才算露天。一列长车横跨隧道口时车头在外面、车中在里面：按隧道处理
 * （开灯），这也是两种错误里更不刺眼的那一种——白天多亮一盏灯，比列车钻进隧道口那半秒突然
 * 灭灯要好。
 *
 * <p>采样点抬高 {@value #PROBE_LIFT} 格：轨面/枕木本身就在高度图里，直接拿车厢坐标取整很可能
 * 落在轨枕那一层，于是每条地面线路都会被判成"有遮挡"，白天灯永远关不掉。
 */
public final class RailWeather {
    /**
     * 采样点相对车厢坐标的抬高量（格）。1.0 落在车厢内部（车顶之下、轨面之上），隧道顶棚仍然
     * 在它上方，所以既能跳过轨枕又不会穿透隧道。
     */
    public static final double PROBE_LIFT = 1.0D;

    /**
     * 夜晚区间起点（一天内 tick）。13000 是原版"可以睡觉"的时刻，天光开始明显变暗；到
     * {@link #NIGHT_END} 之前都算夜。用世界时钟而不是 {@code World#isDay()}，原因见类注释。
     */
    public static final long NIGHT_START = 13000L;
    /** 夜晚区间终点（不含）。23000 = 23000 tick 之后天开始亮。 */
    public static final long NIGHT_END = 23000L;
    /** 一个游戏日的长度，{@code getTimeOfDay()} 取模用。 */
    public static final long DAY_LENGTH = 24000L;

    /**
     * 天空亮度阈值：{@code < } 它就算暗。值域是 0.2（午夜）…1.0（正午），0.5 大约落在
     * 13600 / 22400 两个时刻上，比 {@link #NIGHT_START} 稍晚一点——两条件取或，所以实际"变暗"
     * 时刻就是 13000；这一条负责的是<b>时间没到而天已经黑</b>的场合（雷暴、维度天气、
     * 服务端调过时间）。
     */
    public static final float DARK_SKY = 0.5F;

    /** 雨量渐变超过它才算"真在下雨"（雨刚起/刚停的那几帧不算，避免灯闪）。 */
    public static final float RAIN_GRADIENT = 0.2F;

    private RailWeather() {
    }

    /**
     * 单点是否露天。{@code pos} 是车厢的世界坐标（轨面高度）。未加载的区块按"露天"处理：
     * 那个区块里的列车本来也不会被渲染，让它白天保持关灯即可。
     */
    public static boolean openSky(final ClientWorld world, final Vec3d pos) {
        if (world == null || pos == null) {
            return true;
        }
        final int x = (int) Math.floor(pos.x);
        final int z = (int) Math.floor(pos.z);
        try {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return true;
            }
        } catch (Throwable t) {
            // 读不到区块状态就继续往下问一次天空：不做判断比判断错更安全。
        }
        // +1 格：轨面/枕木自己在高度图里，原位取整会落到地形方块上。
        // 三个坐标自己 floor 再 new BlockPos(int,int,int)：BlockPos.ofFloored 在 1.18.2/1.19.2 的
        // yarn 里没有，而 (double,double,double) 构造器在 1.19.4+ 又被删了——这段代码在 common
        // 里，四个版本都得能编译。
        final int py = (int) Math.floor(pos.y + PROBE_LIFT);
        final BlockPos probe = new BlockPos(x, py, z);
        try {
            return world.isSkyVisibleAllowingSea(probe);
        } catch (Throwable t) {
            try {
                return probe.getY() >= world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            } catch (Throwable second) {
                return true;
            }
        }
    }

    /**
     * 整列车是否露天：车头（{@code pose.front()}）与车中那节车厢的中点都要看到天空。
     *
     * @param pose 本 tick 的列车几何，必须与车灯用的是同一个实例（{@link TrainPose#ofShared}）
     */
    public static boolean openSky(final ClientWorld world, final TrainPose pose) {
        if (world == null || pose == null) {
            return true;
        }
        if (!openSky(world, pose.front())) {
            return false;
        }
        final int cars = pose.cars();
        if (cars <= 0) {
            // 只有车头一个点可用（退化姿态），它的结论就是整列的结论。
            return true;
        }
        final int mid = cars / 2;
        final Vec3d centre = pose.carStart(mid).add(pose.carEnd(mid)).multiply(0.5D);
        return openSky(world, centre);
    }

    /**
     * 当前是不是夜晚，按<b>世界时钟</b>：{@code getTimeOfDay() % 24000} 落在
     * [{@value #NIGHT_START}, {@value #NIGHT_END})。
     *
     * <p>读不到时钟按"不是夜晚"处理（少开灯比整张图常亮更不刺眼），此时仍有可能被
     * {@link #skyBrightness} 判暗。
     */
    public static boolean isNight(final ClientWorld world) {
        try {
            if (world == null) {
                return false;
            }
            long tod = world.getTimeOfDay() % DAY_LENGTH;
            if (tod < 0L) {
                // 负时间（自定义维度/调试）先绕回 [0, 24000)。
                tod += DAY_LENGTH;
            }
            return tod >= NIGHT_START && tod < NIGHT_END;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 天空亮度，0.2（午夜）…1.0（正午），雨/雷时更低。逐行复刻
     * {@code ClientWorld#getSkyBrightness(float)} 的原版公式——那个方法只有 1.19.4+ 的
     * {@code ClientWorld} 上有，而本类在 {@code common} 里要编译 1.18.2/1.19.2。
     *
     * <p>输入只用到 {@code getSkyAngleRadians}（= {@code getSkyAngle} × 2π）与雨量/雷量渐变，
     * 三者在四个版本的 {@code World} 上都存在，所以数值与 1.19.4+ 的原版方法完全一致。
     */
    public static float skyBrightness(final ClientWorld world) {
        try {
            if (world == null) {
                return 1.0F;
            }
            // getSkyAngleRadians = getSkyAngle * 2π，原版取的是 cos(skyAngle * 2π)。
            final float angle = world.getSkyAngleRadians(1.0F);
            float brightness = 1.0F - (MathHelper.cos(angle) * 2.0F + 0.2F);
            brightness = MathHelper.clamp(brightness, 0.0F, 1.0F);
            brightness = 1.0F - brightness;
            brightness *= 1.0F - world.getRainGradient(1.0F) * 5.0F / 16.0F;
            brightness *= 1.0F - world.getThunderGradient(1.0F) * 5.0F / 16.0F;
            return brightness * 0.8F + 0.2F;
        } catch (Throwable t) {
            // 读不到就按"亮"处理：不额外开灯，露天判定照旧生效。
            return 1.0F;
        }
    }

    /** 世界层面唯一的下雨判定：天气 + 雨量渐变（雨刚起/刚停的过渡不算）。 */
    public static boolean raining(final ClientWorld world) {
        try {
            return world != null && world.isRaining() && world.getRainGradient(1.0F) > RAIN_GRADIENT;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 诊断用的雨量渐变（0–1）：和 {@link #raining} 用的是同一个读数，{@code -1} = 读不到。
     *
     * <p>{@code /weather rain} 之后这个值应该在 1 秒内爬到 1.0；水雾诊断行里的 {@code grad=}
     * 因此能直接把「没下雨」和「雨判定写错了」分开 —— 后者会表现为 {@code isRaining=true} 而
     * {@code grad} 一直是 0（或者一直读不到）。
     */
    public static float rainGradient(final ClientWorld world) {
        try {
            return world == null ? 0.0F : world.getRainGradient(1.0F);
        } catch (Throwable t) {
            return -1.0F;
        }
    }

    /** 诊断用：{@code isRaining()} 本体，{@code raining()} 的第二半。 */
    public static boolean weatherRaining(final ClientWorld world) {
        try {
            return world != null && world.isRaining();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 世界层面唯一的雷暴判定（打雷比下雨更暗，风挡/车灯都该跟上）。 */
    public static boolean thundering(final ClientWorld world) {
        try {
            return world != null && world.isThundering() && world.getThunderGradient(1.0F) > RAIN_GRADIENT;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 现在这一刻算不算"暗"：夜晚时钟区间、天空亮度低于阈值、下雨、打雷，<b>任一</b>成立即暗。
     *
     * <p>这四条是并联的，不是串联的：任意一条单独成立就足以点灯——时钟管正常日落，天空亮度管
     * 时间没到而天已经黑（雷暴 / 服务端改时间 / 维度天气），雨雷两者管白天阴雨。
     */
    public static boolean dark(final ClientWorld world) {
        return isNight(world) || skyBrightness(world) < DARK_SKY
                || raining(world) || thundering(world);
    }

    /**
     * 自动开关灯的结论：{@code true} = 这个位置/时刻该开灯（{@code headlightMode = AUTO} 时
     * 才由调用方采用，见 {@link HeadlightConfig#autoHeadlights()}）。
     *
     * <p>暗（夜 / 天黑 / 雨 / 雷）或隧道内 → 开；白天 + 露天 + 无雨 → 关。暗的时候直接短路，
     * 省掉两次高度图查询（这时露天与否不影响结论）。
     */
    public static boolean lightsNeeded(final ClientWorld world, final TrainPose pose) {
        if (dark(world)) {
            return true;
        }
        return !openSky(world, pose);
    }
}
