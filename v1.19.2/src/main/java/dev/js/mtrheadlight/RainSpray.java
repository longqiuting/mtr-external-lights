package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.ParticlesMode;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 雨天露天行驶的水雾 / 水花（1.20.1 only，无自定义贴图）。
 *
 * <h2>触发（三条同时成立，缺一不可）</h2>
 * <ol>
 *   <li><b>下雨</b>：{@link RailWeather#raining(ClientWorld)} —— {@code isRaining()} 加雨量渐变
 *       阈值，跳过雨刚起 / 刚停那半秒。雷暴不排除（{@code isRaining()} 对雷雨同样为 true）。</li>
 *   <li><b>速度</b>：{@code Train#getSpeed()} 的绝对值 &gt; 配置的
 *       {@code rainSprayMinSpeedKmh}（低速渐入开着时闸门落到 {@value #LOW_SPEED_KMH}，见下面的
 *       {@link #lowFactor}）。MTR 这个字段是 <b>blocks/tick</b>，乘
 *       {@value #KMH_PER_BLOCK_PER_TICK} 得到 km/h —— 这正是 MTR 自己在
 *       {@code mtr.render.RenderDrivingOverlay} 里对同一字段做的换算
 *       （{@code speed * 20.0f} 再 {@code * 3.6d}，javap 已核对），所以我们的阈值和玩家在驾驶台上
 *       看到的速度表是同一个数。诊断行里还会打出 {@code rail=} 一列：用
 *       {@code getRailProgress()} 的每 tick 差分独立算一遍 km/h，两个数应当接近；对不上就说明
 *       速度源本身有问题，不用怀疑阈值。</li>
 *   <li><b>露天</b>：每节车厢取自身中点，{@link RailWeather#openSky(ClientWorld, Vec3d)} 必须为
 *       true。隧道、高架下的棚、车库顶棚全部为 false，判定与车灯"进隧道开灯"完全同源。
 *       例外只有一条：{@code /mtr_lte debug spray on} 的 5 秒内（{@link DebugProbe#sprayEnabled()}）
 *       强制忽略这一条，方便在隧道里验证粒子系统本身；正常雨天水雾不受影响。</li>
 * </ol>
 *
 * <h2>粒子放在哪（这是"看得见"的关键）</h2>
 * 粒子必须落在<b>车体轮廓之外</b>。车身半宽取 {@code max(1.30, train.width / 2)}（与
 * {@code CollisionSystem} 同一个折算），粒子的横向偏移从
 * {@code 半宽 + 0.20} 起算（两侧都撒，{@code side = ±1}），纵向从轨面 {@code +0.30} 到 {@code +1.00} 格（裙板到车窗下沿）。
 * 旧实现用的是 {@code 1.30 - 0.15 + rnd * 0.45} = 1.15–1.60 格，其中大半<b>落在 1.5 格宽的
 * 车体里面</b>、高度又贴着轨面，于是粒子明明生成了却被车身挡得看不见——这就是"spawned&gt;0
 * 但屏幕上什么都没有"的原因。
 *
 * <h2>限流（这是本类的主要工作）</h2>
 * 粒子是「每 tick 撒一点」而不是「一次撒一片」，三层硬上限，任何一层都不看帧率：
 * <ul>
 *   <li>每列车每 tick ≤ {@value #MAX_PER_TRAIN_PER_TICK} 个；</li>
 *   <li>全局每 tick ≤ {@value #MAX_PER_TICK} 个（无论玩家身边有多少列车）；</li>
 *   <li>全局每秒约 {@value #TOKENS_PER_TICK}×20 = 640 个：令牌桶每 tick 补
 *       {@value #TOKENS_PER_TICK} 枚、桶上限 {@value #TOKEN_CAP} 枚。</li>
 * </ul>
 *
 * <p>水雾粒子的<b>形状</b>仍是从原版 {@code rain.png} 的 64 格雨滴图集里裁出的真雨丝，
 * 0.08–0.18 的基础尺寸再在竖直方向拉长 {@code 2.2×}（见 {@link SprayParticle}），单根约
 * 0.18–0.40 格高、0.02–0.05 格宽；但<b>颜色已经不是原版</b>：贴图 RGB 清成纯白，tint 用
 * {@code (0.94,0.95,0.97)} 的灰白，alpha 从 0.35–0.55 提到 0.55–0.85 —— 这才是「看得出是我们自己的
 * 水雾、而不是原版雨」的那一半，另一半是密度。
 *
 * <p>密度靠配额 + 每事件根数堆：每 tick 24/80/1200（每列车/全局/令牌桶），每事件 2 根
 * （{@link #MIST_PER_EVENT}），寿命 5–9 tick。和上一版（14/48/640、每事件 1 根、寿命 8–14）相比
 * 同时挂着的雨丝多约 1.3 倍，而单根的存活时间更短 —— 画面是「更密的细流」，不会积成一层白霜。
 *
 * <h2>速度：车尾涡流，不是垂直雨</h2>
 * 初速 = <b>车速反向 × {@link #speedFactor(double)} + 向下 + 一点点外散</b>，即
 * {@code (-forward) * (0.10..0.30) * clamp(kmh/80, 0.5, 1.5)} 再叠 {@code vy = -(0.05..0.15)}；
 * 粒子自身还带 {@link SprayParticle} 的少量重力。旧实现的竖直分量恒为正（0.01–0.02 上浮）而水平分量
 * 很小，画面上就退化成"原版雨在原地落"，和列车没有关系 —— 这正是"方向不对"的根因。
 * 诊断行的 {@code vdir=} 是平均水平速度方向与车速方向的点积，<b>必须为负</b>。
 *
 * <h2>粒子设置（最少档也看得见）</h2>
 * 1.20.1 的 {@code WorldRenderer#spawnParticle} 对非强制粒子有两道硬闸：距相机 &gt; 32 格丢弃、
 * {@code MINIMAL} 档丢弃（不是抽稀，是全丢）。所以粒子设为「最少」时旧实现一颗都发不出来，日志里
 * 却 {@code spawned>0}。现在每 tick 读一次 {@code options.getParticles()}：最小档走 8 参重载的
 * {@code force=true}，并把「更多次、每次一颗」换成「更少次、每次一整团」；华丽 / 适中档保持原样，
 * 不会爆量。诊断行的 {@code particles=} 就是这一档。
 *
 * <h2>粒子类型（Sodium Extra 兼容）</h2>
 * 水雾用 LTE 自己注册的 {@code mtrlte:spray}（{@link SprayType} + {@link SprayParticle}），
 * 不再是 {@code minecraft:cloud}。原因见 {@link SprayType} 的类注释：Sodium Extra 按类型 id 查
 * 它自己的开关表，{@code getOrDefault(id, TRUE)} 对<b>表里没有的 id 默认放行</b>，所以用户关掉
 * 原版粒子开关也屏蔽不到这条水雾。注册失败 / 将来被显式屏蔽时，{@link SprayType#effect()} 自己
 * 退回 {@link ParticleTypes#CLOUD}，诊断行的 {@code sprayType=self|vanilla} 说的是实际用了哪种。
 *
 * <p>{@link ParticleTypes#SPLASH}（水花颗粒，有重力，会落回轨面）从 {@value #SPLASH_MIN_KMH} km/h
 * 起按概率点缀 —— 80 km/h 的常速车就能看到水花，不用等到 90。它仍是原版类型，可能被
 * Sodium Extra 屏蔽；屏蔽时水雾本体照常可见（诊断行的 {@code culled=} 会点出来）。
 */
public final class RainSpray {
    /** 玩家的水平距离上限，超过就不生成（题目要求的 64 格）。 */
    public static final double MAX_PLAYER_DISTANCE = 64.0D;

    /** 每列车每 tick 的粒子上限（雨丝需要更多根才成片，但仍是硬上限）。 */
    public static final int MAX_PER_TRAIN_PER_TICK = 24;

    /** 全局每 tick 的粒子上限。 */
    private static final int MAX_PER_TICK = 80;

    /**
     * 每列车每 tick 最多检查几节车厢的"露天"（实际取 {@code min(cars, 本值)}）。
     *
     * <p>一列车最多只能出 {@value #MAX_PER_TRAIN_PER_TICK} 个粒子，车厢循环本来就从
     * {@code tick % cars} 轮转起步，16 节/ tick 覆盖 MTR 绝大多数编组（含长编组地铁），同时把隧道里
     * 整列车"全查一遍再说"的成本压成常数。
     */
    private static final int MAX_CARS_SCANNED = 16;

    /** 令牌桶：每 tick 补充量与桶容量（约 1200 个/秒的长期均值）。 */
    private static final double TOKENS_PER_TICK = 60.0D;
    private static final double TOKEN_CAP = 240.0D;

    /** 速度换算：blocks/tick → km/h（20 t/s × 3.6）。与 MTR 驾驶台速度表一致。 */
    private static final double KMH_PER_BLOCK_PER_TICK = 20.0D * 3.6D;

    /** 达到这个速度时水雾"明显"；{@code rainSprayMinSpeedKmh} 到它之间线性插值。 */
    private static final double FULL_INTENSITY_KMH = 120.0D;

    /**
     * 低速渐入段的起点（km/h）。低于它<b>一颗都不生成</b>；{@code [LOW_SPEED_KMH, minSpeed]}
     * 之间按 {@link #lowFactor} 的曲线把密度从 {@link #LOW_SPEED_DENSITY_FLOOR} 抬到 1.0。
     *
     * <p>为什么是 20：MTR 的城轨/地铁在站内蠕动、进站对位都在 10–25 km/h 之间，20 以上才是
     * 「真的在走」，那时候裙板卷起的水花才看得见；再往下就是停车溜车，不该有水。
     */
    private static final double LOW_SPEED_KMH = 20.0D;

    /**
     * 低速渐入段最低处的密度乘数：20 km/h 时抽签概率是满档的 35%，到
     * {@code rainSprayMinSpeedKmh} 处并回 100%。
     *
     * <p>这个数只乘在<b>抽签概率</b>上，不碰令牌桶、不碰每列车/全局每 tick 上限 —— 低速段是「更稀」，
     * 不是「多花预算」。0.35 是肉眼可辨的下限：再低（0.1 量级）在 5–9 tick 的寿命下几乎永远抽不到，
     * 就又回到「50 才突然出现」的观感。
     */
    private static final double LOW_SPEED_DENSITY_FLOOR = 0.35D;

    /** 车厢半宽下限（格）。实际值取 {@code max(本值, train.width / 2)}。 */
    private static final double CAR_HALF_WIDTH = 1.30D;

    /**
     * 粒子横向偏移相对车体半宽的外扩量（格）：{@code 半宽 + [MIN, MAX]}。
     *
     * <p>起点 {@value #LATERAL_PAD_MIN} &gt; 0 是硬要求：{@code 半宽 + 0.2} 是"车体外侧"的
     * 最小保证，再小就钻回车体里（旧实现 {@code 1.30 - 0.15 + rnd*0.45} 有一大半落在车体内，
     * 于是"spawned&gt;0 却一颗也看不见"）。
     */
    private static final double LATERAL_PAD_MIN = 0.20D;
    private static final double LATERAL_PAD_MAX = 0.70D;

    /** 水花的横向偏移（贴着轮对/裙板，同样在车体之外）。 */
    private static final double SPLASH_PAD_MIN = 0.02D;
    private static final double SPLASH_PAD_MAX = 0.40D;

    /** 水雾的纵向范围（轨面之上，格）：裙板下沿到车窗下沿。 */
    private static final double MIST_Y_MIN = 0.30D;
    private static final double MIST_Y_MAX = 1.00D;

    /**
     * 车尾涡流：水雾的水平初速沿<b>车速的反向</b>（blocks/tick，区间取自下面的 MIN/MAX），
     * 再乘 {@link #speedFactor(double)}。
     *
     * <p>真实世界里，列车以 80–120 km/h 冲过积水时，轮对与裙板把水甩出去的速度**大体上等于车速**
     * （相对车体是向后，相对地面是"几乎跟着车走、略微掉队"）。粒子不需要也不应该真的等于车速 ——
     * 那样雨丝一 tick 就飞出视野；取 0.10–0.30 这个量级，配合 5–9 tick 的寿命，屏幕上留下的
     * 是一条 0.5–2 格的向后拖尾，这才是"被车卷起来"的读法。
     *
     * <p>旧实现用的是 {@code 0.10 + 0.35 * intensity} 的向后分量、而竖直方向<b>恒为正</b>
     * （0.01–0.02 上浮），两者一比，视觉上就只剩"竖直"分量：雨丝看起来像原版雨在原地往下掉，
     * 与列车无关。现在反过来 —— 水平向后是主项，竖直向下是次项。
     */
    private static final double WAKE_BACK_MIN = 0.10D;
    private static final double WAKE_BACK_MAX = 0.30D;

    /**
     * 水雾的竖直初速（blocks/tick，<b>负值 = 向下</b>，区间取绝对值）。
     *
     * <p>只有 {@link #WAKE_BACK_MIN}～{@link #WAKE_BACK_MAX} 的 1/3 左右，所以轨迹是一条
     * "向后为主、略向下"的斜线（约 20°–45° 偏离水平），而不是 90° 的垂直雨。这一项加上
     * {@code gravityStrength}（见 {@link SprayParticle}）就是"涡流把水汽甩到车后下方"的全部来源。
     */
    private static final double MIST_DOWN_MIN = 0.05D;
    private static final double MIST_DOWN_MAX = 0.15D;

    /** 速度归一化基准（km/h）：{@link #speedFactor} 以它为单位，慢车弱、快车强。 */
    private static final double SPEED_FACTOR_REF_KMH = 80.0D;
    private static final double SPEED_FACTOR_MIN = 0.5D;
    private static final double SPEED_FACTOR_MAX = 1.5D;

    /**
     * 水平初速的个体扩散（blocks/tick，± 该值）。
     *
     * <p>细密雨丝如果每颗速度都一样，会像一排整齐的虚线；扩散让它们各走各的。从 0.08 收到 0.05，
     * 因为现在水平分量是有方向的（车尾涡流）：抖动幅度必须小于 {@link #WAKE_BACK_MIN}，
     * 否则慢车时会有雨丝被抖成"往前飞"，方向读起来就乱了。
     */
    private static final double MIST_SPREAD = 0.05D;
    /** 水花的纵向范围（更贴地，靠自身重力落回轨面）。 */
    private static final double SPLASH_Y_MIN = 0.12D;
    private static final double SPLASH_Y_MAX = 0.45D;

    /** SPLASH 点缀的最低速度与概率基数（80 km/h 的常规车速就能看到）。 */
    private static final double SPLASH_MIN_KMH = 60.0D;
    private static final double SPLASH_CHANCE_BASE = 0.25D;
    private static final double SPLASH_CHANCE_RANGE = 0.50D;

    /** 诊断窗口：100 client ticks = 5 s。 */
    private static final int DIAG_PERIOD_TICKS = 100;

    /**
     * 粒子设置的快照（{@code options.getParticles()}）。
     *
     * <p><b>为什么必须看它</b>：1.20.1 的 {@code WorldRenderer#spawnParticle} 对<b>非强制</b>粒子
     * 有两道硬闸 —— 距相机 &gt; 32 格直接丢、{@code MINIMAL} 档直接丢（不是抽稀，是全丢）。
     * 玩家把粒子调到最小时，水雾一个都出不来，日志却显示 {@code spawned>0}（{@code addParticle}
     * 早就返回了）。所以最小档改走 8 参重载的 {@code force=true}，并换成「次数更少、每次更大」的
     * 一团雾；华丽档完全不变，避免刷新版时爆量。
     */
    private static ParticlesMode mode = ParticlesMode.ALL;

    /** 最小档：抽签概率乘数（事件更少）。 */
    private static final double MINIMAL_CHANCE_SCALE = 0.55D;
    /** 最小档：一次事件里的雾团数（更多、重叠 → 看起来更大更亮）。 */
    private static final int MINIMAL_PUFFS = 4;

    /**
     * 一般档一次事件里发几根雨丝。
     *
     * <p><b>为什么不是 1</b>：{@value #MAX_PER_TRAIN_PER_TICK} 是「每列车每 tick」的上限，而真正的
     * 产量是 {@code min(车厢数, }{@value #MAX_CARS_SCANNED}{@code ) × 每事件根数 × 抽签}——MTR 常见的
     * 4–8 节编组每 tick 最多只轮到 4–8 次事件，光把上限从 14 提到 24 根本吃不到，密度不会变。
     * 所以把「每事件 2 根」作为真正的密度旋钮：一列车 6 节 = 12 根/tick，配合 5–9 tick 的短寿命，
     * 同时挂着的雨丝约 12×7×0.55 ≈ 46 根（旧版是 6×11×0.55 ≈ 36 根），密了约 1.3 倍，
     * 但每根仍是 0.08–0.18 的细丝、且消失得更快，所以是「流得更密」而不是「糊成一片」。
     */
    private static final int MIST_PER_EVENT = 2;
    /** 最小档：雾团内部的抖动半径（格）。粒子已经很小，抖动跟着收紧，免得一团散成一圈。 */
    private static final double MINIMAL_JITTER = 0.30D;


    /** 自己数 tick：不受 LightController 的计数器影响（那条 tick 可能因为主开关提前 return）。 */
    private static long tick;

    /** 最近一次调试探针的中心坐标（{@code x,y,z}，已格式化）与当时的 tick。 */
    private static String probeAt;
    private static long probeTick = Long.MIN_VALUE;

    /** 令牌桶（全局，跨列车共享）。 */
    private static double tokens = TOKEN_CAP;

    /** 本窗口的统计。窗口结束打印并清零。 */
    private static final Set<String> windowTrains = new HashSet<>();
    private static int windowSpawned;
    private static int windowSkipped;
    /** 本窗口内因为车厢中点不露天（隧道 / 棚顶 / 未加载）而被跳过的车厢数。 */
    private static int windowRoofSkipped;

    /** 每列车本窗口的诊断（id → 最后一次观察到的状态 + 累计生成数）。 */
    private static final Map<String, Diag> WINDOW = new LinkedHashMap<>();

    /** 上一 tick 的 {@code getRailProgress()}，用来独立核对速度（id → {progress, tick}）。 */
    private static final Map<String, double[]> LAST_PROGRESS = new HashMap<>();

    private RainSpray() {
    }

    /** 客户端 tick 钩子（{@code END_CLIENT_TICK}，在主线程）。 */
    public static void tick() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientWorld world = client.world;

        tick++;
        tokens = Math.min(TOKEN_CAP, tokens + TOKENS_PER_TICK);
        mode = particleMode(client);

        if (world == null || client.player == null || cfg == null
                || !cfg.enabled || (!cfg.rainSpray && !cfg.rainSprayAnySiding())) {
            // 关掉的时候完全静默：一条 "spray: trains=0 ..." 每 5 秒刷屏只会让人以为它还在跑。
            resetWindow();
            return;
        }
        final boolean raining;
        try {
            raining = raining(world);
        } catch (Throwable t) {
            resetWindow();
            return;
        }
        try {
            probeAtPlayer(client, world);
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.error("[lte] spray probe failed", t);
        }
        try {
            emit(cfg, client, world, raining);
        } catch (Throwable t) {
            // 粒子永远不该把客户端带崩。
            MtrHeadlightClient.LOGGER.error("[lte] rain spray failed", t);
        }
        report(cfg, world, raining);
    }

    /**
     * 调试探针：{@code /mtr_lte debug spray on} 的 5 秒内，在玩家眼前 2.5 格、眼平高度
     * 撒一圈<b>我们自己的</b>粒子，完全不依赖“附近有没有列车 / 下没下雨 / 露不露天”。
     *
     * <p>之前探针只放宽了露天判定，仍然要求有列车经过 —— 站在空站台开探针什么都看不到，
     * 于是每次都被误判成“粒子不显示”。这里把前提条件全部去掉，只回答一个问题：
     * <b>粒子系统本身能不能出像素</b>。
     */
    private static void probeAtPlayer(final MinecraftClient client, final ClientWorld world) {
        if (!DebugProbe.sprayEnabled()) {
            return;
        }
        final net.minecraft.entity.player.PlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        // getRotationVec 就是前方向量（yaw 0 → +Z，水平分量 (-sin yaw, cos yaw)），和
        // Vec3d.fromPolar(pitch, yaw) 完全同一个约定。这里必须 <b>加</b>：旧实现减了一次，
        // 于是那一圈粒子正好撒在玩家<b>背后</b> 2.5 格 —— 第一人称看不见，切第三人称才露出来。
        final net.minecraft.util.math.Vec3d look = player.getRotationVec(1.0F);
        double lx = look.x;
        double lz = look.z;
        final double llen = Math.sqrt(lx * lx + lz * lz);
        if (llen < 1.0E-4D) {
            // 垂直朝上/朝下看：水平朝向退化，用一个固定值，别让 2.5 格缩成 0。
            lx = 0.0D;
            lz = 1.0D;
        } else {
            lx /= llen;
            lz /= llen;
        }
        final double cx = player.getX() + lx * 2.5D;
        final double cy = player.getEyeY();
        final double cz = player.getZ() + lz * 2.5D;
        // 诊断用：探针中心是"眼前还是背后"只有坐标能回答（第三人称下肉眼判断不出来）。
        probeAt = fmtCoord(cx) + "," + fmtCoord(cy) + "," + fmtCoord(cz);
        probeTick = tick;
        final net.minecraft.particle.ParticleEffect effect = SprayType.effect();
        for (int i = 0; i < 12; i++) {
            final double a = Math.PI * 2.0D * i / 12.0D
                    + world.random.nextDouble() * 0.5D;
            final double r = 0.25D + 0.75D * world.random.nextDouble();
            world.addParticle(effect, true,
                    cx + Math.cos(a) * r,
                    cy + (world.random.nextDouble() - 0.5D) * 0.8D,
                    cz + Math.sin(a) * r,
                    0.0D, 0.01D, 0.0D);
        }
    }

    /** 世界层面唯一的下雨判定，和车灯共用 {@link RailWeather#raining(ClientWorld)}。 */
    private static boolean raining(final ClientWorld world) {
        return RailWeather.raining(world);
    }

    /** 一 tick 的生成：先筛列车，再筛车厢，最后过预算。不生成时也把"为什么"记进诊断。 */
    private static void emit(final HeadlightConfig cfg, final MinecraftClient client,
                             final ClientWorld world, final boolean raining) {
        final List<TrainClient> trains;
        try {
            trains = new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return;
        }
        if (trains.isEmpty()) {
            return;
        }

        final Vec3d eye = client.player.getPos();
        final double limitSq = MAX_PLAYER_DISTANCE * MAX_PLAYER_DISTANCE;
        int tickBudget = MAX_PER_TICK;
        // 调试探针（/mtr_lte debug spray on）期间忽略露天限制：方便在隧道 / 车库里验证
        // 「粒子系统本身是不是通的」。正常雨天水雾完全不受影响 —— 探针 5 秒后自动关。
        final boolean forceSky = DebugProbe.sprayEnabled();

        for (final TrainClient train : trains) {
            if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
                continue;
            }
            if (train.transportMode != TransportMode.TRAIN) {
                continue;
            }
            // 水雾的开关与起始速度按「这列车所在的侧线」解析（Train.sidingId），不是玩家位置。
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
            final String key = DepartureState.keyOf(train) + "#" + System.identityHashCode(train);
            final double kmh = kmh(train);
            final Diag diag = diag(key, train);
            diag.kmh = kmh;
            diag.railKmh = railKmh(train, key);
            diag.raining = raining;
            diag.ticks++;

            if (!eff.rainSpray) {
                // 这条侧线关掉了水雾：直接记原因，不花轨道采样。
                diag.reason = "off";
                continue;
            }
            final double minKmh = eff.rainSprayMinSpeedKmh;
            if (!raining) {
                // 没下雨时仍然每 5 秒把速度和"dry"打出来：否则"没下雨"和"坏了"在日志里长得一样。
                diag.reason = "dry";
                continue;
            }
            if (tickBudget <= 0) {
                diag.reason = "budget";
                continue;
            }
            // 速度闸门放最前面：这是最便宜的检查，也让绝大多数停车/慢车在采样轨道之前就被丢掉。
            // 低速渐入（rainSprayLowSpeedBoost）开着时，闸门落到 LOW_SPEED_KMH，闸门与
            // minSpeed 之间不再直接丢弃，而是按 lowFactor 把抽签概率调稀（见下面的 lowFactor）。
            final boolean lowBoost = cfg.rainSprayLowSpeedBoost && minKmh > LOW_SPEED_KMH;
            final double gateKmh = lowBoost ? LOW_SPEED_KMH : minKmh;
            if (kmh <= gateKmh) {
                diag.reason = "slow(" + Math.round(kmh) + "<=" + Math.round(gateKmh) + ")";
                continue;
            }
            final double intensity = intensity(kmh, minKmh);
            final double lowFactor = lowFactor(kmh, minKmh, lowBoost);
            diag.lowFactor = lowFactor;

            // 与 LightController 完全相同的方向表达式，保证 TrainPose 缓存命中（同一 progress →
            // 同一 TrainPose 实例），不额外采样 rail.getPosition。
            final boolean reversed = DepartureState.reversed(train, train.isReversed()) ^ cfg.swapEnds;
            final TrainPose pose = TrainPose.ofShared(train, reversed);
            final int cars = pose.cars();
            if (cars <= 0) {
                diag.reason = "nopose";
                continue;
            }

            // 车厢轮转起点：一列车连续几 tick 不会只从同一节车厢冒水。
            final int start = (int) (tick % cars);
            final int scan = Math.min(cars, MAX_CARS_SCANNED);
            int spawnedForTrain = 0;
            boolean sawFar = false;
            boolean sawRoofed = false;
            boolean sawThrottle = false;
            boolean sawChance = false;
            boolean sawCandidate = false;
            boolean skyAny = false;
            boolean skySampled = false;
            double nearest = Double.MAX_VALUE;

            for (int k = 0; k < scan
                    && spawnedForTrain < MAX_PER_TRAIN_PER_TICK
                    && tickBudget > 0; k++) {
                final int car = (start + k) % cars;
                final Vec3d a = pose.carStart(car);
                final Vec3d b = pose.carEnd(car);
                final Vec3d center = a.add(b).multiply(0.5D);

                final double distSq = center.squaredDistanceTo(eye);
                nearest = Math.min(nearest, Math.sqrt(distSq));
                if (distSq > limitSq) {
                    sawFar = true;
                    continue;
                }
                final Boolean sampled = skyState(world, center);
                if (sampled != null) {
                    skySampled = true;
                    skyAny |= sampled.booleanValue();
                }
                if (sampled == null) {
                    // 区块未加载：不是"隧道"，不冒充 roofSkipped，直接跳过这一节。
                    if (!forceSky) {
                        continue;
                    }
                } else if (!sampled.booleanValue()) {
                    if (!forceSky) {
                        sawRoofed = true;
                        windowRoofSkipped++;
                        continue;
                    }
                    // 探针强制：隧道里也照常生成，只是 sky 仍记为 roof（诊断里看得见）。
                }
                sawCandidate = true;

                // 三层预算：令牌桶（≈每秒）、全局每 tick、每列车每 tick。任何一层用完就算 skipped，
                // 这样诊断行里的 skipped 直接读作"被限流挡下的候选数"。
                if (tokens < 1.0D) {
                    windowSkipped++;
                    sawThrottle = true;
                    continue;
                }
                if (world.random.nextDouble() > spawnChance(intensity) * chanceScale() * lowFactor) {
                    windowSkipped++;
                    sawChance = true;
                    continue;
                }
                final int made = spawnOne(world, train, pose, car, a, b, intensity, kmh, diag);
                if (made <= 0) {
                    continue;
                }
                // 最小档下 forced 粒子不再受原版 32 格 / MINIMAL 闸门限制，所以自报的"预算"要按
                // 真正发出去的个数算（一次事件可能是一整团）。
                tokens = Math.max(0.0D, tokens - Math.min(made, 4));
                tickBudget -= made;
                spawnedForTrain += made;
                windowSpawned += made;
                diag.spawned += made;
            }

            diag.sky = skySampled ? Boolean.valueOf(skyAny) : null;
            diag.dist = nearest == Double.MAX_VALUE ? -1.0D : nearest;
            if (spawnedForTrain > 0) {
                diag.reason = "ok";
                windowTrains.add(key);
            } else if (sawThrottle) {
                diag.reason = "throttled";
            } else if (sawChance) {
                diag.reason = "chance";
            } else if (sawCandidate) {
                diag.reason = "ok";
                windowTrains.add(key);
            } else if (sawRoofed) {
                diag.reason = "roofed";
            } else if (sawFar) {
                diag.reason = "far(" + Math.round(nearest) + ">64)";
            } else {
                diag.reason = "none";
            }
        }
    }

    /**
     * 实际发粒子：横向一定落在车体轮廓之外，纵向在裙板到车窗下沿之间。
     *
     * @return 真正发出的粒子数（0–2）
     */
    private static int spawnOne(final ClientWorld world, final TrainClient train, final TrainPose pose,
                                final int car, final Vec3d a, final Vec3d b,
                                final double intensity, final double kmh, final Diag diag) {
        final Vec3d dir = pose.carForward(car);
        double fx = dir.x;
        double fz = dir.z;
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1.0E-4D) {
            // 竖直方向（电梯式轨道）或退化切线：用一个稳定的水平默认值，避免除零。
            fx = 0.0D;
            fz = 1.0D;
            len = 1.0D;
        }
        fx /= len;
        fz /= len;
        // 水平右向量（MC 的 yaw 约定：f = (0,0,1)（朝南）时右手边是 (-1,0,0)（西））：
        // cross(f, up) = (-fz, 0, fx)。注意这不是"前"也不是"后"，而是一条垂直于车体的轴；
        // 横向落点 = 中线 + side * (半宽 + pad) * 右向量，side 取 ±1 所以两侧都撒，
        // 不存在"符号取反就只往一侧偏"的问题 —— 唯一能出错的是 pad 变成负数（钻回车体里）。
        final double rx = -fz;
        final double rz = fx;

        // 车身半宽：与碰撞箱同一个折算（train.width 是整车宽，格）。
        final double halfW = Math.max(CAR_HALF_WIDTH, train.width * 0.5D);

        final double side = world.random.nextBoolean() ? 1.0D : -1.0D;
        final double along = 0.12D + world.random.nextDouble() * 0.76D;
        final Vec3d base = a.add(b.subtract(a).multiply(along));
        final double railY = Math.min(a.y, b.y);

        // 车尾涡流：主项是 -forward × (0.10..0.30) × speedFactor（沿车速的反方向甩出去），
        // 副项只有一点点向车外侧的散开，竖直方向在循环里各自取 -(0.05..0.15)。
        final double speedFactor = speedFactor(kmh);
        final double back = (WAKE_BACK_MIN
                + (WAKE_BACK_MAX - WAKE_BACK_MIN) * world.random.nextDouble()) * speedFactor;
        // 外散限制在后向分量的 15%–35%：两侧仍然都看得见，但水平合速度一定仍指向车尾 ——
        // 诊断行 vdir= 恒为负就是这条约束在生效（旧实现外散是常量 0.05–0.17，慢车时能盖过后向分量）。
        final double out = back * (0.15D + 0.20D * world.random.nextDouble());
        // 水平初速对一颗雾团是常量（只有位置抖动），所以提到循环外：水花也复用它。
        final double mistVx = -fx * back + rx * side * out;
        final double mistVz = -fz * back + rz * side * out;

        int made = 0;

        // 1) 水雾：车体侧面之外的一团白雾，沿车速反方向甩向车后下方（见上面的涡流初速）。最小档是一整团（重叠的几颗，看起来更大更亮），
        //    并且强制生成 —— 不然原版 MINIMAL 闸门会把它们全部丢掉。
        final boolean minimal = mode == ParticlesMode.MINIMAL;
        final int puffs = minimal ? MINIMAL_PUFFS : MIST_PER_EVENT;
        for (int i = 0; i < puffs; i++) {
            final double jx = minimal ? (world.random.nextDouble() - 0.5D) * 2.0D * MINIMAL_JITTER : 0.0D;
            final double jy = minimal ? (world.random.nextDouble() - 0.5D) * 2.0D * 0.25D : 0.0D;
            final double jz = minimal ? (world.random.nextDouble() - 0.5D) * 2.0D * MINIMAL_JITTER : 0.0D;
            // 每根雨丝各占一个沿车长的位置、各上一侧：两根挤在同一点会叠成一颗更亮的，
            // 反而看不出「数量变多」。i=0 沿用事件抽到的那一侧，i=1 走另一侧，两侧同时有水雾。
            final double alongPuff = 0.12D + world.random.nextDouble() * 0.76D;
            final Vec3d puffBase = a.add(b.subtract(a).multiply(alongPuff));
            final double puffSide = (i & 1) == 0 ? side : -side;
            final double mistPad = LATERAL_PAD_MIN
                    + world.random.nextDouble() * (LATERAL_PAD_MAX - LATERAL_PAD_MIN);
            // 带符号的横向偏移：±(半宽 + pad)，pad ≥ 0.20，所以落点永远在车体外侧 ≥ 0.2 格。
            // 车体中心 → 外侧的偏移量就是 here；rx/rz 只负责把它转到世界方向。
            final double mistLateral = puffSide * (halfW + mistPad);
            final double mistX = puffBase.x + rx * mistLateral + jx;
            final double mistZ = puffBase.z + rz * mistLateral + jz;
            final double mistY = airY(world, mistX, railY + MIST_Y_MIN
                    + world.random.nextDouble() * (MIST_Y_MAX - MIST_Y_MIN) + jy, mistZ);
            if (Double.isNaN(mistY)) {
                // 落点整列都是方块（站台墙 / 车体内部 / 地形）：这一颗不发，但记进诊断 ——
                // 「spawned=0 而 candidates>0」要能区分「被方块挡住」和「粒子系统坏了」。
                diag.blocked++;
                continue;
            }
            final double mistVy = -(MIST_DOWN_MIN
                    + world.random.nextDouble() * (MIST_DOWN_MAX - MIST_DOWN_MIN));
            // 水平方向各自 ±MIST_SPREAD 扩散：每根雨丝都是一颗独立的小水滴，不排成整齐的虚线；
            // 幅度小于后向分量的下限（0.05），所以再怎么抖也不会把方向抖成"往前飞"。
            final double mistSx = mistVx + (world.random.nextDouble() - 0.5D) * 2.0D * MIST_SPREAD;
            final double mistSz = mistVz + (world.random.nextDouble() - 0.5D) * 2.0D * MIST_SPREAD;
            // 诊断 vdir：水平速度方向（归一化）与车速方向的点积。向后就是负值 —— 这一列是
            // "水雾到底往哪边走"唯一可量化的证据，正数就说明符号反了或外散盖过了后向分量。
            final double hlen = Math.sqrt(mistSx * mistSx + mistSz * mistSz);
            if (hlen > 1.0E-6D) {
                diag.vdirSum += (mistSx * fx + mistSz * fz) / hlen;
                diag.vdirN++;
            }
            // 自注册类型 mtrlte:spray（Sodium Extra 的逐类型屏蔽表里没有这个 id，默认放行）；
            // 注册失败或被屏蔽时 SprayType 内部已经退回 ParticleTypes.CLOUD。
            add(world, SprayType.effect(), minimal, mistX, mistY, mistZ, mistSx, mistVy, mistSz);
            made++;

            // 诊断用几何：横向偏移、车体半宽、高出轨面的高度（dy 必须为正、|lat| 必须 < halfW 才
            // 说明粒子在车体外面——这正是旧实现出错的地方）。
            diag.halfW = halfW;
            diag.lat = mistLateral;
            diag.dy = mistY - railY;
            diag.at = fmtCoord(mistX) + "," + fmtCoord(mistY) + "," + fmtCoord(mistZ);
        }
        // 最小档：一整团重叠的雾（MINIMAL_PUFFS 颗，位置抖动过）已经比原版 CLOUD 的单像素点厚得多，
        // 不再需要额外补一颗 LARGE_SMOKE（那是 Sodium Extra 会屏蔽的已知类型）。

        // 2) 水花：60 km/h 起按概率点缀，贴地、向外抛、靠重力落回轨面。
        if (kmh >= SPLASH_MIN_KMH
                && world.random.nextDouble() < SPLASH_CHANCE_BASE + SPLASH_CHANCE_RANGE * intensity) {
            final double splashPad = SPLASH_PAD_MIN + world.random.nextDouble() * (SPLASH_PAD_MAX - SPLASH_PAD_MIN);
            final double splashLateral = side * (halfW + splashPad);
            final double splashX = base.x + rx * splashLateral;
            final double splashZ = base.z + rz * splashLateral;
            final double splashY = railY + SPLASH_Y_MIN
                    + world.random.nextDouble() * (SPLASH_Y_MAX - SPLASH_Y_MIN);
            add(world, ParticleTypes.SPLASH, minimal, splashX, splashY, splashZ,
                    mistVx * 1.6D, 0.16D + world.random.nextDouble() * 0.14D, mistVz * 1.6D);
            made++;
        }
        return made;
    }

    /**
     * 唯一的粒子出口：客户端本地粒子，绝不发包。
     *
     * <p>{@code force} 走的是 8 参重载（{@code ClientWorld#addParticle(effect, force, ...)}）：
     * {@code true} 时原版直接进 {@code ParticleManager}，跳过「距相机 &gt; 32 格」和
     * {@code MINIMAL} 两条裁剪。最小档必须 force，否则一颗都看不到 —— 那正是「粒子设最小后水雾
     * 消失」的原因。
     */
    private static void add(final ClientWorld world, final ParticleEffect particle, final boolean force,
                            final double x, final double y, final double z,
                            final double vx, final double vy, final double vz) {
        world.addParticle(particle, force, x, y, z, vx, vy, vz);
    }

    /** 当前粒子档位；读不到就当 ALL（老行为）。 */
    private static ParticlesMode particleMode(final MinecraftClient client) {
        try {
            final ParticlesMode value = client == null ? null : client.options.getParticles().getValue();
            return value == null ? ParticlesMode.ALL : value;
        } catch (Throwable t) {
            return ParticlesMode.ALL;
        }
    }

    /** 最小档抽签概率的乘数：事件更少，但每次是一整团（见 {@link #MINIMAL_PUFFS}）。 */
    private static double chanceScale() {
        return mode == ParticlesMode.MINIMAL ? MINIMAL_CHANCE_SCALE : 1.0D;
    }

    /**
     * 把粒子的落点抬到空气里：从 {@code y} 起向上找最多 4 格，找到空气就返回那个 y，
     * 整列都不是空气就返回 {@code NaN}（调用方跳过这一颗并记 {@code blocked}）。
     *
     * <p>为什么需要：水雾的 y 是「轨面 + 0.3~1.05」，在站台边缘、隧道口、地形凹处那一点很容易正好
     * 落在实心方块里（尤其是半砖/台阶/站台墙）。粒子在方块内部是完全不可见的 —— 这正是"生成了
     * 600 颗一颗也看不到"这一类故障的一半原因。向上找而不是向下找：落点本身就在轨面上方 0.3–1.0 格，
     * 向上是唯一不会把粒子推进地板的方向（粒子自身的运动仍然向后下方）。
     */
    private static double airY(final ClientWorld world, final double x, final double y,
                               final double z) {
        try {
            for (int i = 0; i <= 4; i++) {
                final double probe = y + i;
                if (world.isAir(new BlockPos(MathHelper.floor(x), MathHelper.floor(probe), MathHelper.floor(z)))) {
                    return probe;
                }
            }
            return Double.NaN;
        } catch (Throwable t) {
            return y;
        }
    }

    /**
     * 车厢中点的露天状态：{@code TRUE} 露天、{@code FALSE} 有顶（隧道 / 棚 / 车库）、
     * {@code null} 区块未加载（既不算露天也不算隧道，调用方直接跳过且不记 {@code roofSkipped}）。
     * 判定本体与车灯共用 {@link RailWeather#openSky(ClientWorld, Vec3d)}。
     */
    private static Boolean skyState(final ClientWorld world, final Vec3d pos) {
        final int bx = (int) Math.floor(pos.x);
        final int bz = (int) Math.floor(pos.z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return null;
        }
        return Boolean.valueOf(RailWeather.openSky(world, pos));
    }

    /** {@code Train#getSpeed()}（blocks/tick）→ km/h。 */
    private static double kmh(final TrainClient train) {
        try {
            return Math.abs(train.getSpeed()) * KMH_PER_BLOCK_PER_TICK;
        } catch (RuntimeException e) {
            return 0.0D;
        }
    }

    /**
     * 用 {@code getRailProgress()} 的每 tick 差分独立算一遍 km/h，只用于诊断（{@code rail=}）。
     *
     * <p>两条独立来源给出同一个数量级，才能说明 {@code getSpeed() * 72} 这个换算是对的；
     * 只靠一行 {@code speed=} 无法区分"换算错了"和"车真的慢"。
     *
     * @return km/h；第一次见到这列车（没有上一 tick 的采样）时返回 {@code -1}
     */
    private static double railKmh(final TrainClient train, final String key) {
        try {
            final double progress = train.getRailProgress();
            final double[] last = LAST_PROGRESS.get(key);
            LAST_PROGRESS.put(key, new double[]{progress, tick});
            if (LAST_PROGRESS.size() > 128) {
                LAST_PROGRESS.clear();
            }
            if (last == null) {
                return -1.0D;
            }
            final double dtTicks = Math.max(1.0D, tick - last[1]);
            return Math.abs(progress - last[0]) / dtTicks * 20.0D * 3.6D;
        } catch (RuntimeException e) {
            return -1.0D;
        }
    }

    /**
     * 阈值以上线性 0→1：{@code minKmh} 处为 0（零星），{@link #FULL_INTENSITY_KMH} 处为 1（明显），
     * 再快也封顶 1。{@code minKmh >= 120} 时直接按 1 处理（配置允许 0–200）。
     */
    private static double intensity(final double kmh, final double minKmh) {
        if (kmh <= minKmh) {
            return 0.0D;
        }
        final double span = FULL_INTENSITY_KMH - minKmh;
        if (span <= 0.0D) {
            return 1.0D;
        }
        return Math.min(1.0D, (kmh - minKmh) / span);
    }

    /** 每节车厢每 tick 的抽签概率：刚过阈值约 25%，120 km/h 及以上 100%。 */
    private static double spawnChance(final double intensity) {
        return 0.25D + 0.75D * intensity;
    }

    /**
     * 低速渐入的密度乘数：{@code 1.0} = 不降档。
     *
     * <p>曲线：{@code factor = 0.35 + 0.65 * clamp((kmh - 20) / (minKmh - 20), 0, 1)}。
     *
     * <pre>
     *   km/h :  &lt;20   20    25    30    35    40    45    50(min)  60   80  120
     *   factor:   0   0.35  0.46  0.57  0.68  0.79  0.89   1.00    1.00 1.00 1.00
     * </pre>
     *
     * <p>和 {@link #intensity} 的接点：{@code minKmh} 处两边都是 1.0，所以阈值上下的抽签概率
     * （{@code spawnChance(0) = 0.25}）连续，不会在 50 km/h 跳一下。{@code minKmh <= 20} 时整段
     * 退化掉（没有可渐入的区间），返回 1.0；{@code lowBoost = false} 时也返回 1.0，即旧的硬闸门。
     */
    private static double lowFactor(final double kmh, final double minKmh, final boolean lowBoost) {
        if (!lowBoost || !(kmh < minKmh)) {
            return 1.0D;
        }
        final double span = minKmh - LOW_SPEED_KMH;
        if (span <= 0.0D) {
            return 1.0D;
        }
        double t = (kmh - LOW_SPEED_KMH) / span;
        if (t < 0.0D) {
            t = 0.0D;
        } else if (t > 1.0D) {
            t = 1.0D;
        }
        return LOW_SPEED_DENSITY_FLOOR + (1.0D - LOW_SPEED_DENSITY_FLOOR) * t;
    }

    /**
     * 水雾初速的速度系数：{@code clamp(kmh / 80, 0.5, 1.5)}。
     *
     * <p>车越快，卷起来的水甩得越急 —— 40 km/h 的车只有 0.5 倍，120 km/h 以上封顶 1.5 倍。
     * 上下限是为了不出现两种极端：慢车时水雾几乎不动（看起来像静止白点），快车时一 tick 就飞出
     * 视野（看起来像跳帧）。注意这<b>不是</b>密度系数，密度仍由 {@link #intensity} 那套抽签决定。
     */
    private static double speedFactor(final double kmh) {
        if (!(kmh > 0.0D)) {
            return SPEED_FACTOR_MIN;
        }
        return Math.max(SPEED_FACTOR_MIN, Math.min(SPEED_FACTOR_MAX, kmh / SPEED_FACTOR_REF_KMH));
    }

    /** 诊断用：下沉初速区间的下限/上限（{@link SprayParticle#paramsLabel()} 要打出来，避免日志与代码对不上）。 */
    static double mistDownMin() {
        return MIST_DOWN_MIN;
    }

    static double mistDownMax() {
        return MIST_DOWN_MAX;
    }

    private static Diag diag(final String key, final TrainClient train) {
        Diag d = WINDOW.get(key);
        if (d == null) {
            d = new Diag();
            try {
                d.id = train.trainId == null ? key : train.trainId;
            } catch (RuntimeException e) {
                d.id = key;
            }
            WINDOW.put(key, d);
        }
        return d;
    }

    /**
     * 每 5 秒一行 {@code spray: trains=N spawned=M skipped=K roofSkipped=R cap=C ...}，诊断开着时
     * 再补每列车一行 {@code spray-diag: id=... speed=... rail=... raining=... sky=open|roof ...}。
     *
     * <p>{@code trains} 按列车去重（这一窗内有多少列车真的满足三个条件），{@code spawned} 是
     * 真正发出的粒子总数，{@code skipped} 是被三层预算或概率抽签挡下的候选数，
     * {@code roofSkipped} 是因为车厢中点不露天（隧道 / 棚顶 / 未加载）被整节跳过的车厢数 ——
     * 后者 &gt;0 而 {@code spawned=0} 就是"在隧道里，本来就不该有水雾"的硬证据。
     * {@code cap} 是令牌桶容量（本窗口的长期配额 = 60/tick × 20 = 1200 个/秒）。
     * {@code lowBoost=} 是低速渐入的全局状态（{@code off} / {@code on(20-50 0.35..1.00)}）；
     * 某一列车这一 tick 实际乘到的密度系数看 {@code spray-diag} 的 {@code low=}（1.00 = 满档，
     * 0.35–1.00 = 正处在 20–50 的渐入段）。
     * {@code density} 是本窗口的配额占用率 {@code spawned/(80×100)}：分子是真正发出去的粒子数，
     * 分母是全局每 tick 上限在 100 tick 窗口里能给出的最多个数 —— 调「密不密」时先看这个数。
     * {@code tint=} 是 {@link SprayParticle} 实际用的顶点色 tint / 寿命 / alpha 区间（直接读那边的
     * 常量，日志和画面不会对不上）。
     * 窗口内没有任何候选也会照常打印 0 —— 静默窗口和"没在跑"在日志里必须能区分开。
     */
    private static void report(final HeadlightConfig cfg, final ClientWorld world,
                               final boolean raining) {
        if (!cfg.rainSprayDiagnostics) {
            resetWindow();
            return;
        }
        if (tick % DIAG_PERIOD_TICKS != 0L) {
            return;
        }
        final int trains = windowTrains.size();
        final int spawned = windowSpawned;
        final int skipped = windowSkipped;
        final int roofSkipped = windowRoofSkipped;
        final List<Diag> lines = new ArrayList<>(WINDOW.values());
        // 本窗口里自注册粒子实抽到的起始尺寸均值（由 SprayParticle 自己在构造时记账）。
        // 走 vanilla 回退时样本数为 0，打 "-"：这种情况 avgSize 无从谈起，不能编一个 0.20 出来。
        final double[] sizes = SprayParticle.drainScaleStats();
        final String avgSize = sizes[0] < 0.0D ? "-"
                : String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(sizes[0]));
        // 密度：本窗口真正发出的粒子数 / 全局上限能给出的最多个数（48 个/tick × 100 tick）。
        // 分母是硬上限，所以这是「配额占用率」而不是「应该值与实际值之比」—— 抽签（25%–100%）
        // 和露天判定都会让它低于 1，读的时候要连 skipped/reason 一起看。
        final int expected = DIAG_PERIOD_TICKS * MAX_PER_TICK;
        final String density = String.format(java.util.Locale.ROOT, "%.3f(%d/%d)",
                Double.valueOf(expected <= 0 ? 0.0D : (double) spawned / (double) expected),
                Integer.valueOf(spawned), Integer.valueOf(expected));
        resetWindow();
        try {
            // weather= 是 isRaining() 本体，grad= 是 raining() 的另一半。两条一起看就能把
            // "/weather rain 没生效" / "雨量渐变一直是 0" / "判定写错" 三种情况分开：
            // weather=false grad=0.00 才是真的没下雨（reason=dry）。
            // sprayType=self 表示用的是自注册的 mtrlte:spray（Sodium Extra 屏不掉），
            // vanilla 表示已经回退到 minecraft:cloud（原因见启动日志里的 "spray particle type=" 一行）。
            // avgSize= 是本窗口新生粒子的平均起始尺寸（格）：调参后先看它，再看画面。
            MtrHeadlightClient.LOGGER.info(
                    "[lte] spray: trains={} spawned={} density={} skipped={} roofSkipped={} cap={} lowBoost={} avgSize={} {}"
                            + " raining={} weather={} grad={} particles={} sprayType={} culled={} probeAt={}",
                    Integer.valueOf(trains), Integer.valueOf(spawned), density,
                    Integer.valueOf(skipped),
                    Integer.valueOf(roofSkipped), Integer.valueOf((int) TOKEN_CAP),
                    lowBoostLabel(cfg), avgSize, SprayParticle.paramsLabel(),
                    Boolean.valueOf(raining), Boolean.valueOf(RailWeather.weatherRaining(world)),
                    fmt(RailWeather.rainGradient(world)), mode, SprayType.label(),
                    ParticleCull.culled().isEmpty() ? "none" : ParticleCull.culled(),
                    probeAt == null || tick - probeTick > DIAG_PERIOD_TICKS ? "-" : probeAt);
            for (final Diag d : lines) {
                MtrHeadlightClient.LOGGER.info(
                        "spray-diag: id={} speed={} rail={} low={} raining={} sky={} dist={} lat={} halfW={} dy={} vdir={} at={} spawned={} blocked={} reason={} probeAt={}",
                        d.id, fmt(d.kmh), fmt(d.railKmh),
                        String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(d.lowFactor)),
                        Boolean.valueOf(d.raining),
                        d.sky == null ? "-" : skyName(d.sky),
                        d.dist < 0.0D ? "-" : fmt(d.dist),
                        d.lat == Double.MAX_VALUE ? "-" : fmt(d.lat),
                        d.halfW < 0.0D ? "-" : fmt(d.halfW),
                        d.dy == Double.MAX_VALUE ? "-" : fmt(d.dy),
                        d.vdirN == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.2f",
                                Double.valueOf(d.vdirSum / d.vdirN)),
                        d.at == null ? "-" : d.at,
                        Integer.valueOf(d.spawned), Integer.valueOf(d.blocked), d.reason,
                        probeAt == null || tick - probeTick > DIAG_PERIOD_TICKS ? "-" : probeAt);
            }
        } catch (Throwable t) {
            // 日志永远不许影响 tick。
        }
    }

    private static String fmt(final double value) {
        if (value < 0.0D) {
            return "-";
        }
        return String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(value));
    }

    /** 露天判定的人读写法：{@code open} / {@code roof}（{@code null} = 还没采样）。 */
    private static String skyName(final Boolean sky) {
        if (sky == null) {
            return "-";
        }
        return sky.booleanValue() ? "open" : "roof";
    }

    /**
     * 诊断用的低速渐入状态：{@code off}，或 {@code on(20-50 0.35..1.00)}（起点 - 全局阈值，密度区间）。
     *
     * <p>这里打的是<b>全局</b>阈值；某条侧线覆盖了 {@code rainSprayMinSpeedKmh} 时，那列车的实际
     * 区间与乘数看 {@code spray-diag} 的 {@code low=} —— 两者对不上就说明覆盖在生效，不是算错了。
     * 低速段整段退化（阈值 ≤ 20）时同样打 {@code off}：没有可渐入的区间，行为与关闭一致。
     */
    private static String lowBoostLabel(final HeadlightConfig cfg) {
        if (cfg == null || !cfg.rainSprayLowSpeedBoost) {
            return "off";
        }
        final double minKmh = cfg.rainSprayMinSpeedKmh;
        if (!(minKmh > LOW_SPEED_KMH)) {
            return "off";
        }
        return "on(" + Math.round(LOW_SPEED_KMH) + "-" + Math.round(minKmh) + " "
                + String.format(java.util.Locale.ROOT, "%.2f..1.00",
                        Double.valueOf(LOW_SPEED_DENSITY_FLOOR)) + ")";
    }

    /** 坐标格式化：负坐标是合法值，不能走 {@link #fmt} 的 "-" 哨兵。 */
    private static String fmtCoord(final double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(value));
    }

    private static void resetWindow() {
        windowTrains.clear();
        windowSpawned = 0;
        windowSkipped = 0;
        windowRoofSkipped = 0;
        WINDOW.clear();
        // 诊断关掉时 report() 每 tick 都会走到这里：顺手把粒子的尺寸累计清掉，别让它一直涨。
        SprayParticle.drainScaleStats();
    }

    /** 一列车在一个诊断窗口里的状态。 */
    private static final class Diag {
        String id = "?";
        double kmh;
        double railKmh = -1.0D;
        boolean raining;
        Boolean sky;
        double dist = -1.0D;
        int spawned;
        /** 因为落点在方块里（抬 4 格仍不是空气）而跳过的水雾数。 */
        int blocked;
        int ticks;
        String reason = "-";
        /** 本 tick 的密度乘数（低速渐入）：1.00 = 满档，0.35–1.00 = 低速段被调稀。 */
        double lowFactor = 1.0D;
        /** 最近一个水雾粒子的横向偏移（带符号）与当时的车体半宽、高出轨面的高度。 */
        double lat = Double.MAX_VALUE;
        double halfW = -1.0D;
        double dy = Double.MAX_VALUE;
        String at;
        /**
         * {@code vdir=} 的累计：本窗口内每颗水雾水平速度方向与车速方向的点积之和 / 样本数。
         * 负值才正确（向后），越接近 -1 越"纯向后"。
         */
        double vdirSum;
        int vdirN;
    }
}
