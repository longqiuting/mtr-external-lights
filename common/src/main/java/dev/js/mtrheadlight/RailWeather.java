package dev.js.mtrheadlight;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 露天 / 昼夜 / 降雨判定 —— 车灯自动开关（{@link LightController}、{@link BeamGlow}）和
 * 雨天水花（1.20.1 的 {@code RainSpray}）共用的<b>唯一</b>信号源，所以车灯和水花对"这列车现在
 * 是不是在隧道里"永远给出同一个答案。
 *
 * <h2>露天判定用什么 API（旧实现为什么会闪）</h2>
 * 旧实现用的是 {@code WorldView#isSkyVisibleAllowingSea(BlockPos)}：它拿
 * {@code MOTION_BLOCKING} 高度图与 {@code pos.getY()} 比较，回答的是<b>「这一列方块里有没有
 * 任何碰撞体挡在头顶」</b>。接触网支架 / 吊柱 / 雨棚边梁都是全方块碰撞体，列车从下面开过时
 * 这一列就被判成「看不见天空」——于是白天露天行驶时车灯每隔几米开一次关一次。这不是判定
 * 写错，而是<b>问错了问题</b>：车灯关心的是「现在这个位置有多亮」，不是「这一列有没有方块」。
 *
 * <p>所以改读<b>天空光照</b>：{@code world.getLightLevel(LightType.SKY, probe)}。
 * 天空光在竖直方向不衰减、只被方块从上方遮蔽，横向每格 -1，所以：
 * <ul>
 *   <li>露天轨道 = 15；</li>
 *   <li>一根 1 格宽的接触网柱子正上方 = 14（横向从邻列借光），仍然算露天；</li>
 *   <li>隧道 / 长棚 / 两格以上的实体顶盖 = 13 以下 → 判隧道，开灯。</li>
 * </ul>
 * 阈值 {@value #SKY_LIGHT_MIN}，即「离露天只差一格以内」算露天。这个量是连续的：柱子经过时
 * 光照是从 15 掉到 14 再回来，而不是像高度图那样在 true/false 之间跳。
 *
 * <p>剩下的临界抖动由<b>去抖</b>兜住：{@link #openSky(ClientWorld, TrainPose, long)} 对每列车
 * 记住已提交的答案，原始采样要连续 {@value #DEBOUNCE_TICKS} tick（= 0.5 s，{@link #DEBOUNCE_MS}）
 * 保持另一个值才翻转。0.5 s 比一列 6 节车以 60 km/h 通过一根柱子（约 0.36 s）长，所以「开过
 * 一根柱子」不会被看见，而「真的进隧道」最多晚半秒开灯。
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
 * 两点<b>都</b>够亮才算露天。一列长车横跨隧道口时车头在外面、车中在里面：按隧道处理
 * （开灯），这也是两种错误里更不刺眼的那一种——白天多亮一盏灯，比列车钻进隧道口那半秒突然
 * 灭灯要好。
 *
 * <p>采样点抬高 {@value #PROBE_LIFT} 格：轨面/枕木就在脚下，原位取整会落在轨枕那一层，光照
 * 是 0，于是每条地面线路都会被判成"有遮挡"，白天灯永远关不掉。
 */
public final class RailWeather {
    /**
     * 采样点相对车厢坐标的抬高量（格）。1.0 落在车厢内部（车顶之下、轨面之上）：轨面方块本身
     * 会把车底那一格的光照压到 0，直接拿车厢坐标取整会让每条地面线路都判成隧道。
     */
    public static final double PROBE_LIFT = 1.0D;

    /**
     * 露天判定的天空光照阈值（0–15）。{@code >= } 它算露天。
     *
     * <p>15 = 头顶没有方块；14 = 正上方有 1 格宽的东西（接触网柱子 / 吊柱 / 电线杆）时借着邻列
     * 的光；13 及以下只有「头顶有连续实体遮蔽」才出现（隧道、雨棚、桥面下）。所以 14 是「离
     * 露天只差一格」的边界，正好把柱子从「隧道」里摘出去。
     */
    public static final int SKY_LIGHT_MIN = 14;

    /**
     * 去抖窗口：原始采样要连续这么多 tick（{@value #DEBOUNCE_TICKS} × 50 ms = 0.5 s）保持
     * 另一个值，状态才翻转。一列 6 节车以 60 km/h 通过一根柱子约 0.36 s &lt; 0.5 s，所以看不见；
     * 真的进隧道最多晚半秒开灯。
     */
    public static final int DEBOUNCE_TICKS = 10;
    /** {@link #DEBOUNCE_TICKS} 的毫秒形式：判定在渲染线程也会被调用，用墙钟比自带 tick 简单。 */
    public static final long DEBOUNCE_MS = DEBOUNCE_TICKS * 50L;

    /** {@link #openSky(ClientWorld, TrainPose, long)} 的「不做去抖」键（诊断 / 单点采样用）。 */
    public static final long NO_KEY = Long.MIN_VALUE;

    /** 去抖状态表的上限：超过就整表清空重建，比无界增长安全。 */
    private static final int DEBOUNCE_MAX_ENTRIES = 256;

    /**
     * 每列车的去抖状态（键 = {@code TrainClient.id}）。光带与光柱在渲染线程、动态光源在 client
     * tick、轨道烘焙可能在区块构建线程，所以用并发表。
     */
    private static final Map<Long, Debounce> DEBOUNCE = new ConcurrentHashMap<>();

    /**
     * 一列车的去抖状态：{@link #value} 是<b>已提交</b>的答案（消费方看到的永远是它），
     * {@link #candidate} 是正在计时的原始采样，连续 {@link #DEBOUNCE_MS} 不变才提交。
     */
    private static final class Debounce {
        boolean value;
        boolean candidate;
        long candidateSince;
    }

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
     * 单点天空光照（0–15），露天判定的<b>唯一</b>原始读数。{@code -1} = 读不到（未加载区块 /
     * API 异常），调用方按"露天"处理：读不到的区块里列车本来也不会被渲染，让它白天保持关灯。
     *
     * <p>{@code pos} 是车厢的世界坐标（轨面高度），探针抬 {@value #PROBE_LIFT} 格。
     * {@code getLightLevel(LightType.SKY, BlockPos)} 在四个支持版本的 {@code World} 上同名同
     * 签名，所以这段代码放在 {@code common} 里每版都能编译。读不到时退回高度图，再抛异常才
     * 放弃（-1）。
     */
    public static int skyLevel(final ClientWorld world, final Vec3d pos) {
        if (world == null || pos == null) {
            return -1;
        }
        final int x = (int) Math.floor(pos.x);
        final int z = (int) Math.floor(pos.z);
        try {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return -1;
            }
        } catch (Throwable t) {
            // 读不到区块状态就继续往下问一次光照：不做判断比判断错更安全。
        }
        // 三个坐标自己 floor 再 new BlockPos(int,int,int)：BlockPos.ofFloored 在 1.18.2/1.19.2 的
        // yarn 里没有，而 (double,double,double) 构造器在 1.19.4+ 又被删了——这段代码在 common
        // 里，四个版本都得能编译。
        final int py = (int) Math.floor(pos.y + PROBE_LIFT);
        final BlockPos probe = new BlockPos(x, py, z);
        try {
            return world.getLightLevel(LightType.SKY, probe);
        } catch (Throwable t) {
            try {
                return probe.getY() >= world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) ? 15 : 0;
            } catch (Throwable second) {
                return -1;
            }
        }
    }

    /**
     * 单点是否露天：{@code skyLevel >= }{@value #SKY_LIGHT_MIN}。{@code pos} 是车厢的世界坐标
     * （轨面高度）。这个重载<b>不去抖</b>——水花按每节车厢逐点采样，要的是逐点结论。
     */
    public static boolean openSky(final ClientWorld world, final Vec3d pos) {
        final int level = skyLevel(world, pos);
        return level < 0 || level >= SKY_LIGHT_MIN;
    }

    /** 车中那节车厢的中点探针；退化姿态（{@code cars <= 0}）返回 null。 */
    private static Vec3d midProbe(final TrainPose pose) {
        final int cars = pose.cars();
        if (cars <= 0) {
            return null;
        }
        final int mid = cars / 2;
        return pose.carStart(mid).add(pose.carEnd(mid)).multiply(0.5D);
    }

    /** 车头探针的光照（光束就是从这里射出去的，车灯最关心它）。 */
    public static int skyLevelNose(final ClientWorld world, final TrainPose pose) {
        return pose == null ? -1 : skyLevel(world, pose.front());
    }

    /** 车中探针的光照；退化姿态或没有这个点时返回 {@code -1}。 */
    public static int skyLevelMid(final ClientWorld world, final TrainPose pose) {
        final Vec3d centre = pose == null ? null : midProbe(pose);
        return centre == null ? -1 : skyLevel(world, centre);
    }

    /**
     * 整列车的<b>原始</b>露天结论（无去抖）：车头与车中两点都要 {@code >= }{@value #SKY_LIGHT_MIN}。
     *
     * <p>两点<b>都</b>够亮才算露天。一列长车横跨隧道口时车头在外面、车中在里面：按隧道处理
     * （开灯），这也是两种错误里更不刺眼的那一种——白天多亮一盏灯，比列车钻进隧道口那半秒突然
     * 灭灯要好。
     */
    public static boolean openSkyNow(final ClientWorld world, final TrainPose pose) {
        if (world == null || pose == null) {
            return true;
        }
        final int nose = skyLevelNose(world, pose);
        if (nose >= 0 && nose < SKY_LIGHT_MIN) {
            return false;
        }
        final int mid = skyLevelMid(world, pose);
        // mid == -1 有两种含义：退化姿态（没有这个点）或读不到 —— 两种都按「露天」处理。
        return mid < 0 || mid >= SKY_LIGHT_MIN;
    }

    /**
     * 整列车这一刻是否露天 —— <b>带 {@value #DEBOUNCE_TICKS} tick (0.5 s) 去抖</b>，所有车灯
     * 消费方（{@link HeadlightSwitch}）都走这个重载。
     *
     * @param trainId 列车的稳定标识（{@code TrainClient#id}）。同一列车在光带、动态光源、轨道
     *                烘焙、光柱四处必须传同一个值，否则它们各自去抖就会出现「光带灭了光柱还亮」。
     *                {@link #NO_KEY} = 不去抖（只给诊断用）。
     */
    public static boolean openSky(final ClientWorld world, final TrainPose pose, final long trainId) {
        final boolean raw = openSkyNow(world, pose);
        if (trainId == NO_KEY) {
            return raw;
        }
        final long now = System.currentTimeMillis();
        Debounce state = DEBOUNCE.get(Long.valueOf(trainId));
        if (state == null) {
            if (DEBOUNCE.size() >= DEBOUNCE_MAX_ENTRIES) {
                DEBOUNCE.clear();
            }
            state = new Debounce();
            // 第一次见到这列车：直接采用原始值，不等待（否则进场后 0.5 s 内灯是猜的）。
            state.value = raw;
            state.candidate = raw;
            state.candidateSince = now;
            DEBOUNCE.put(Long.valueOf(trainId), state);
            return raw;
        }
        if (raw == state.value) {
            // 回到已提交的状态：撤掉候选，下一次翻转要重新等满窗口。
            state.candidate = raw;
            state.candidateSince = now;
            return state.value;
        }
        if (raw != state.candidate) {
            // 原始值又变了（临界处来回跳）：候选重置，计时从头开始 —— 这正是去抖要压掉的抖动。
            state.candidate = raw;
            state.candidateSince = now;
            return state.value;
        }
        if (now - state.candidateSince >= DEBOUNCE_MS) {
            state.value = raw;
        }
        return state.value;
    }

    /** 清空去抖状态（换世界 / 重载配置时调用，避免旧世界的答案留在表里）。 */
    public static void reset() {
        DEBOUNCE.clear();
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
     * 省掉两次光照查询（这时露天与否不影响结论）。
     *
     * <p>露天那一半是<b>带 0.5 s 去抖</b>的 {@link #openSky(ClientWorld, TrainPose, long)}，
     * {@code trainId} 必须与其它消费方传的是同一个值。
     */
    public static boolean lightsNeeded(final ClientWorld world, final TrainPose pose, final long trainId) {
        if (dark(world)) {
            return true;
        }
        return !openSky(world, pose, trainId);
    }
}
