package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「在车厢里就不画雨」—— 判定 + 计数 + 调试开关。
 *
 * <h2>为什么是「不画」而不是「不生成」</h2>
 * 1.20.1 的雨是 {@code WorldRenderer#renderWeather(LightmapTextureManager, float, double, double,
 * double)} 里现场建的：它按相机周围 ±10 格逐列查高度图、把雨/雪画进一个四边形批次，<b>没有实体、
 * 没有粒子</b>。所以「少画一点」只能整段跳掉 —— 我们注入的就是这个方法（intermediary
 * {@code method_22714}）的 HEAD，命中时直接 {@code ci.cancel()}。这样做的代价是一次 GL 状态切换
 * 也不用做，比逐粒子剔除便宜得多。
 *
 * <p>同一个方法里还画雪：原版对每一列取 {@code Biome#getPrecipitation}，SNOW 用雪贴图、RAIN 用雨
 * 贴图。HEAD 取消因此<s>顺带</s>把雪也一起关掉 —— 这是我们<b>有意接受</b>的行为（车里看窗外下雪同样
 * 不该有雪花糊在镜头上），但它确实不是「只关雨」。见 {@code docs/rain-hush-verify.md}。
 *
 * <p>地面水花走的是另一个入口 {@code tickRainSplashing(Camera)}（intermediary
 * {@code method_22713}），它是粒子而不是绘制，所以单独注一个 HEAD，受 {@link HeadlightConfig#rainHushSplash}
 * 控制。忘掉这一处的话，天上看不到雨、地上却一直冒水花，比不关还假。
 *
 * <p><b>注意这个方法还管雨声</b>：1.20.1 的 {@code tickRainSplashing} 里除了
 * {@code ParticleTypes.RAIN} 还播 {@code SoundEvents.WEATHER_RAIN} /
 * {@code WEATHER_RAIN_ABOVE}（字段 {@code rainSoundCounter} 就在它里面，见 javap）。所以
 * {@code rainHushSplash=true} 会<b>连雨声一起停</b>——坐在关门的车厢里听不到雨声，通常正是想要
 * 的；想留着雨声就把这个开关设成 false。
 *
 * <h2>「在车内」怎么判</h2>
 * 用与 {@code CollisionSystem} 完全相同的车厢几何（{@code TrainPose} 每节车两端点 + 配置高度），
 * 但判的是<b>包含</b>而不是推出：玩家整个 AABB（不是脚点）必须落在车厢盒内部，并且
 * <ul>
 *   <li>竖直：脚底在轨面之上（{@code +0.10}）、头顶在车顶之下（{@code -0.10}）——站车顶上的人
 *       头顶必然超过车顶，所以「站车顶」天然不算；</li>
 *   <li>横向：整块 AABB 在 {@code ±(halfW - 0.05)} 以内；</li>
 *   <li>纵向：距两端各留 {@value #END_INSET} 格，避开车厢连接处的贯通道。</li>
 * </ul>
 *
 * <h2>误差（写在这里，报告里照抄）</h2>
 * <ul>
 *   <li><b>车门</b>：门开着时侧墙是通的，玩家站在门框平面上（|lat| ≈ halfW）会被判成「不在车内」，
 *       按 ±{@code halfW - 0.05} 与玩家半宽 0.3 算，模糊带约 <b>0.35 格</b>宽（≈ 门洞边缘往里
 *       一步）。方向和「站在门口淋雨」的直觉一致。</li>
 *   <li><b>车厢连接处</b>：MTR 的两节车之间是真实间隙（spacing − 车长），贯通道模型不参与我们的
 *       几何。两端各留 {@value #END_INSET} 格后，中间那条 <b>0.6–1.0 格</b>的缝会被判成「不在
 *       车内」→ 站在贯通道正中间时雨会回来。这是一眼能看出来的边界，但只在人站在车厢接头正中间
 *       时才出现。</li>
 *   <li><b>车体模型 vs 碰撞盒</b>：判定用的是 MTR 的端点 + 配置高度 3.8，与碰撞盒同一套数；模型上
 *       鼓出来的裙板/空调罩不在里面。人身高 1.8，离这两头都远，实际影响为 0。</li>
 * </ul>
 *
 * <h2>连带影响（实测结论见 docs/rain-hush-verify.md）</h2>
 * <ul>
 *   <li>雨声：{@code renderWeather} 被 cancel <b>不影响</b>雨声；但地面水花那条
 *       {@code tickRainSplashing} 里同时播着 {@code WEATHER_RAIN}，所以
 *       {@code rainHushSplash=true} 会把雨声一起停掉（车厢里安静，通常正是想要的）。</li>
 *   <li>雪花：一起关（见上）。</li>
 *   <li>屏幕湿滑/镜头水滴：<b>原版没有这个东西</b>，是光影包（Iris + 部分 shaderpack）自己按
 *       {@code getRainGradient} 做的后处理，我们没碰雨量，所以它照旧。</li>
 *   <li>水下效果、雨雾（fog）、天空压暗：都不在 {@code renderWeather} 里，照旧。</li>
 * </ul>
 */
public final class RainHush {

    /** 纵向两端各留的余量（格）：车厢连接处的贯通道不算「车内」。 */
    private static final double END_INSET = 0.3D;

    /** 横向/竖直离车壳的余量（格）。 */
    private static final double SHELL_INSET = 0.05D;

    /** 脚底离轨面的最小高度（格）：低于它就是趴在车底/穿模，不算。 */
    private static final double FLOOR_INSET = 0.10D;

    /** 头顶离车顶的最小余量（格）。 */
    private static final double ROOF_INSET = 0.10D;

    /** 只找这个距离内的车厢（格）。比碰撞系统的 48 松一点，够用且便宜。 */
    private static final double SEARCH_RANGE = 48.0D;

    /** 诊断行间隔（毫秒）。 */
    private static final long DIAG_PERIOD_MS = 5000L;

    /** 调试：强制「在车内」—— 用于在没有任何列车的地方验证渲染路径。 */
    private static volatile boolean forceInside;

    // ---- 每 tick 缓存 ------------------------------------------------------------------------

    private static int cacheTick = Integer.MIN_VALUE;
    private static ClientPlayerEntity cachePlayer;
    private static boolean cacheInside;

    /** 上一帧的世界：进/退世界与维度切换时把计数与缓存清掉。 */
    private static ClientWorld lastWorld;

    // ---- 计数（诊断四分法里最小的一组：调用 / 命中） ------------------------------------------

    private static long frames;
    private static long suppressed;
    private static long splashSuppressed;
    private static long lastLogMs;
    private static boolean lastInside;

    private RainHush() {
    }

    /** 调试开关：/mtr_lte rain force on|off。 */
    public static void setForceInside(final boolean value) {
        forceInside = value;
        cacheTick = Integer.MIN_VALUE;
        MtrHeadlightClient.LOGGER.info("[lte] rain: forceInside={}", Boolean.valueOf(value));
    }

    public static boolean forceInside() {
        return forceInside;
    }

    /**
     * mixin 的判定入口（每帧调用一次，渲染线程）。
     *
     * @return true = 这一帧不画雨（调用方 {@code ci.cancel()}）
     */
    public static boolean shouldSuppress() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != lastWorld) {
            lastWorld = client.world;
            reset();
        }
        frames++;
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        // 全局关掉、某条侧线打开时同样要往下走：挡雨是按「你坐在哪列车里」判的。
        if (!cfg.rainHush && !cfg.rainHushAnySiding()) {
            diag(cfg, false);
            return false;
        }
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        final boolean inside = forceInside || playerInsideCabin(client);
        diag(cfg, inside);
        if (inside) {
            suppressed++;
        }
        return inside;
    }

    /** {@code tickRainSplashing} 的判定入口（地面水花粒子）。 */
    public static boolean shouldSuppressSplash() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if ((!cfg.rainHush && !cfg.rainHushAnySiding()) || !cfg.rainHushSplash) {
            return false;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        if (!(forceInside || playerInsideCabin(client))) {
            return false;
        }
        splashSuppressed++;
        return true;
    }

    /**
     * 玩家是否在某一节车厢内部（整块 AABB 被车厢盒包住）。
     *
     * <p>结果按 {@code player.tickCount} 缓存：一帧里雨与地面水花两处入口各问一次，渲染帧率远高于
     * 20 Hz，没必要每帧重算一遍列车几何。
     */
    public static boolean playerInsideCabin(final MinecraftClient client) {
        final ClientPlayerEntity player = client.player;
        final ClientWorld world = client.world;
        if (player == null || world == null) {
            return false;
        }
        final int tick = player.age;
        if (cachePlayer == player && cacheTick == tick) {
            return cacheInside;
        }
        cachePlayer = player;
        cacheTick = tick;
        cacheInside = computeInside(player, world);
        return cacheInside;
    }

    private static boolean computeInside(final ClientPlayerEntity player, final ClientWorld world) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            final double height = cfg.trainCollisionHeightClamped();
            final Box box = player.getBoundingBox();
            final double hx = (box.maxX - box.minX) * 0.5D;
            final double hz = (box.maxZ - box.minZ) * 0.5D;
            final double pcx = (box.minX + box.maxX) * 0.5D;
            final double pcz = (box.minZ + box.maxZ) * 0.5D;

            final List<TrainClient> trains;
            try {
                trains = new ArrayList<>(ClientData.TRAINS);
            } catch (Throwable t) {
                return false;
            }
            for (final TrainClient train : trains) {
                if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
                    continue;
                }
                if (train.transportMode != TransportMode.TRAIN) {
                    continue;
                }
                // 挡雨按这列车所在的侧线解析：某条侧线关掉挡雨时，坐在它的车里仍然看得到雨。
                if (!cfg.effectiveFor(train.sidingId).rainHush) {
                    continue;
                }
                final TrainPose pose;
                try {
                    final boolean reversed = DepartureState.reversed(train, train.isReversed())
                            ^ cfg.swapEnds;
                    pose = TrainPose.ofShared(train, reversed);
                } catch (Throwable t) {
                    continue;
                }
                final int cars = pose.cars();
                if (cars <= 0) {
                    continue;
                }
                final double halfW = halfWidth(train);
                for (int i = 0; i < cars; i++) {
                    final Vec3d a = pose.carStart(i);
                    final Vec3d b = pose.carEnd(i);
                    final double ax = b.x - a.x;
                    final double az = b.z - a.z;
                    final double len = Math.sqrt(ax * ax + az * az);
                    if (len < 0.5D) {
                        continue;
                    }
                    // 粗筛：车厢中点离玩家超过 SEARCH_RANGE 就不看这节车。
                    final double cx = (a.x + b.x) * 0.5D;
                    final double cz = (a.z + b.z) * 0.5D;
                    final double dx = cx - player.getX();
                    final double dz = cz - player.getZ();
                    if (dx * dx + dz * dz > SEARCH_RANGE * SEARCH_RANGE) {
                        continue;
                    }
                    final double ux = ax / len;
                    final double uz = az / len;
                    // 水平局部坐标：along = 0..len（a→b），lat 以车厢中心线为 0。
                    final double along = (pcx - a.x) * ux + (pcz - a.z) * uz;
                    final double lat = (pcx - a.x) * (-uz) + (pcz - a.z) * ux;
                    // AABB 在两个局部轴上的半投影。
                    final double halfAlong = hx * Math.abs(ux) + hz * Math.abs(uz);
                    final double halfLat = hx * Math.abs(uz) + hz * Math.abs(ux);
                    if (along - halfAlong < END_INSET
                            || along + halfAlong > len - END_INSET) {
                        continue;
                    }
                    if (Math.abs(lat) + halfLat > halfW - SHELL_INSET) {
                        continue;
                    }
                    // 竖直：轨面高度沿车厢线性插值（坡道）。
                    final double t = along / len;
                    final double floor = a.y + (b.y - a.y) * t;
                    if (box.minY < floor + FLOOR_INSET) {
                        continue;
                    }
                    if (box.maxY > floor + height - ROOF_INSET) {
                        continue;
                    }
                    trace(player, train, i, along, len, lat, floor, height);
                    return true;
                }
            }
        } catch (Throwable t) {
            // 渲染线程上的判定永远不许抛。
            return false;
        }
        return false;
    }

    /** 命中时打一行（进/出各一次），方便在日志里确认「哪节车、差多少」。 */
    private static void trace(final ClientPlayerEntity player, final TrainClient train,
                              final int car, final double along, final double len,
                              final double lat, final double floor, final double height) {
        final boolean now = true;
        if (lastInside == now) {
            return;
        }
        lastInside = now;
        MtrHeadlightClient.LOGGER.info(
                "[lte] rain: inside=true train={} car={} along={}/{} lat={} feet={} floor={} roof={}",
                train.trainId, Integer.valueOf(car),
                fmt(along), fmt(len), fmt(lat), fmt(player.getY()), fmt(floor),
                fmt(floor + height));
    }

    /** 每 5 s 一行：帧数 / 命中数 / 当前判定，以及一次「出车」的转换。 */
    private static void diag(final HeadlightConfig cfg, final boolean inside) {
        if (inside != lastInside && !inside) {
            lastInside = false;
            if (cfg.rainHushDiagnostics) {
                MtrHeadlightClient.LOGGER.info("[lte] rain: inside=false");
            }
        }
        if (!cfg.rainHushDiagnostics) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (lastLogMs != 0L && now - lastLogMs < DIAG_PERIOD_MS) {
            return;
        }
        lastLogMs = now;
        MtrHeadlightClient.LOGGER.info(
                "[lte] rain: frames={} suppressed={} splash={} inside={} force={} switch={}",
                Long.valueOf(frames), Long.valueOf(suppressed), Long.valueOf(splashSuppressed),
                Boolean.valueOf(inside), Boolean.valueOf(forceInside),
                Boolean.valueOf(cfg.rainHush));
    }

    /** 世界/维度切换：缓存与计数归零（计数归零是为了诊断行能自证「这一局真的在跑」）。 */
    public static void reset() {
        cachePlayer = null;
        cacheTick = Integer.MIN_VALUE;
        cacheInside = false;
        frames = 0L;
        suppressed = 0L;
        splashSuppressed = 0L;
        lastLogMs = 0L;
        lastInside = false;
    }

    /** 给命令回显用的一行状态。 */
    public static String status() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        return "rainHush=" + cfg.rainHush + " splash=" + cfg.rainHushSplash
                + " force=" + forceInside + " inside=" + cacheInside
                + " frames=" + frames + " suppressed=" + suppressed;
    }

    /** 与 {@code CollisionSystem#halfWidth} 同一套折算（那边是 private，原型里先复制一份）。 */
    private static double halfWidth(final TrainClient train) {
        double half;
        try {
            half = ((double) train.width) * 0.5D;
        } catch (Throwable t) {
            return 1.25D;
        }
        if (!(half > 0.0D)) {
            return 1.25D;
        }
        return Math.max(1.3D, Math.min(4.0D, half));
    }

    private static String fmt(final double v) {
        return String.format(Locale.ROOT, "%.2f", Double.valueOf(v));
    }
}
