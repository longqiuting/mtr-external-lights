package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 两个<b>可见探针</b>与一条绑定诊断日志，用来把「看不到光柱 / 看不到水雾」拆成两种完全不同的
 * 故障：
 *
 * <ul>
 *   <li>{@code /mtr_lte debug cone on|off} - 玩家前方固定位置的一个巨大白色光锥（{@link
 *       ProtoBeamCone} 负责画）。它<b>不读任何列车 / 车型 / 灯位数据</b>，也不看配置开关，只走
 *       和真实光柱完全相同的加色壳层管道。看得见 = 管道是通的，问题在绑定；看不见 = 管道本身
 *       没画出来（mixin / 着色器 / GL 状态）。</li>
 *   <li>{@code /mtr_lte debug spray on|off} - <b>眼前 2 格、眼平高度</b>的一圈 {@code FLAME}
 *       （红黄色、自带发光、几乎不可能被环境光吞掉）+ 一颗贴在眼睛前 0.8 格的对照粒子，持续
 *       5 秒（100 tick）。四条通道同时计数，日志里一眼就能分开：
 *       <pre>
 *       flameEye   眼平 2 格的一圈 FLAME（主探针）
 *       flameSelf  眼睛前 0.8 格的 FLAME（一定在空气里、一定不被遮挡的对照）
 *       cloudOld   旧位置（脚底 +0.15 格、脚前 3 格）的 CLOUD（A/B 用）
 *       pmDirect   绕开 ClientWorld、直接塞进 ParticleManager 的一颗 FLAME
 *       airSkipped 因为落在非空气方块里被跳过的位置数
 *       pm         本次日志窗口末 ParticleManager 里实际持有的粒子数（getDebugString）
 *       </pre>
 *       结论怎么读：{@code pm > 0} 但屏幕上什么都没有 = <b>渲染层</b>（Sodium / 纹理 / 粒子档位）；
 *       {@code pm = 0} 而 {@code flameEye > 0} = 粒子<b>根本没进粒子系统</b>（坐标 / force 分支）；
 *       {@code flameSelf} 看得见而 {@code flameEye} 看不见 = <b>位置</b>问题（进了方块或车体）。
 *       这是「雨天水雾」那条路径的最小替身：探针看得见而水雾看不见，说明水雾自己的触发条件
 *       （在车厢里、下雨、速度）没满足，而不是粒子系统坏了。</li>
 * </ul>
 *
 * <h2>诊断日志</h2>
 * 每 5 秒（100 client tick）一行，<b>不受任何配置开关影响</b>（它自己就是用来诊断「为什么没
 * 东西」的，跟着开关一起停就没有意义了）：
 * <pre>
 *   [lte] debug: coneCalls=N coneQuads=Q probeQuads=P volBeams=V spraySpawned=S beams=B slots=K
 *                dmin=D range=R g1=a g2=b g3=c g4=d g5=e g6=f g7=g g8=h
 * </pre>
 * <ul>
 *   <li>{@code coneCalls} - 本窗口 {@link ProtoBeamCone#render} 被调用了几次（两个 pass 都算，
 *       所以 60 fps 下约 600/5s）。<b>0 = 渲染回调根本没跑</b>（mixin / 事件注册 / 版本问题），
 *       这是最先要看的一位数。</li>
 *   <li>{@code coneQuads} - 本窗口<b>车灯光柱</b>塞进顶点缓冲的四边形数（壳层锥那条路）。
 *       <b>默认路径就是壳层</b>（{@code beamConeVolume=false}），所以正常情况下它是 &gt;0 的；
 *       {@code 0} 而 {@code calls &gt; 0} = 这一窗口没有一辆车发出光束（看 {@code beams} / {@code g*}）。
 *       只有显式 {@code /mtr_lte cone volume on} 走 raymarch 时它才必然为 0，那时看 {@code volBeams}。</li>
 *   <li>{@code probeQuads} - 本窗口 <b>探针锥</b>（{@code /mtr_lte debug cone}）的四边形数。
 *       和 {@code coneQuads} 分开计：探针看得见只能证明管道通，不能证明灯位绑对了。</li>
 *   <li>{@code volBeams} - 本窗口交给 raymarch（{@code ConeVolumeRenderer}）的光束数。这条路
 *       没有四边形，{@code coneQuads=0 + volBeams&gt;0} 只在 {@code cone volume on} 时是正常的。</li>
 *   <li>{@code path} - 真实光束这一帧实际走的渲染路径：{@code shell}（默认）或 {@code volume}。
 *       它把"配置写了什么"和"实际用了什么"分开（shader pack / GL 失败会从 volume 退回 shell）。</li>
 *   <li>{@code spraySpawned} - 本窗口生成的水雾探针粒子数（探针没开就是 0）。</li>
 *   <li>{@code beams} - 上一帧真正发出去的车灯光束数（每盏灯一条，头/尾分开算）。</li>
 *   <li>{@code slots} - 当前数据里的列车一共解析出多少个车灯槽位（每列车 0-2：头 1 + 尾 1）。</li>
 *   <li>{@code dmin} - 本窗口观察到的<b>最近一条光束 apex</b> 到眼睛的距离（块，-1 = 一个候选都
 *       没有）。{@code dmin > range} 时 {@code beams=0} 是「射程内没有车」，不是管道坏了。</li>
 *   <li>{@code range} - 生效的距离闸门（块），来自 {@code beamDistance}（逐车可被侧线覆盖）。</li>
 *   <li>{@code g1..g8} - 逐道闸门的命中数，{@code beams>0} 时应全 0；不为 0 的那一道就是拦截点：
 *       <br>g1 {@code enabled=false}；g2 光柱开关全关；g3 没有可读车型；g4 解析不出头/尾灯位；
 *       <br>g5 这条侧线关了光柱；g6 位姿为空；g7 超出距离闸门；g8 名额满或参数退化。</li>
 * </ul>
 * <b>组合就是结论</b>：{@code slots=0} = 解析不出灯位（车型 / 资源包 / 组名问题）；
 * {@code slots>0 而 beams=0} 时看 {@code g*} —— g3/g4 是绑定，g5 是侧线开关，g7 是距离
 * （{@code dmin} 对 {@code range} 一读就懂），g1/g2 是总开关。{@code beams=0} 且 g3+g4&gt;0 时
 * 同一窗口还会补最多 3 行 {@code lights: model=… slots=… source=…}，直接点名是哪几个车型。
 */
public final class DebugProbe {
    /** 探针持续 5 秒 = 100 client tick（原来 10 秒 200 tick：太短看不出、太长又要等）。 */
    public static final int SPRAY_DURATION_TICKS = 100;
    /** 每 tick 生成的 FLAME 圈数（一圈 = 这么多颗，均分在半径 0.45–0.8 的圆周上）。 */
    public static final int SPRAY_PER_TICK = 8;
    /** 生成点在玩家<b>眼前</b>多少格（水平视线方向；高度 = 眼平）。 */
    public static final double SPRAY_AHEAD = 2.0D;
    /** 旧探针的生成距离（A/B 对照：脚前 3 格）。 */
    private static final double SPRAY_AHEAD_OLD = 3.0D;
    /** 诊断行周期：100 client tick = 5 s。 */
    private static final int LOG_TICKS = 100;
    /** beams=0 时补几行车型明细。 */
    private static final int MAX_MODEL_LINES = 3;

    /** 光锥探针开关；渲染线程读，命令线程写。 */
    private static volatile boolean cone;
    /**
     * 真实光柱的「提亮对照」开关（{@code /mtr_lte debug coneBoost on|off}）：打开后真实光束的
     * 峰值 alpha 被强制成探针那一档（0.45），并且纵向 / 径向 / 视角 / 地面四种衰减全部关掉，
     * 也就是把每一条真实光束画成和 {@code /mtr_lte debug cone} 探针完全一样的参数。
     *
     * <p>它只回答一个问题：<b>看不见是因为太淡，还是因为管道/几何不对</b>。开的瞬间就亮 =
     * 衰减太狠（去调 {@code beamConeAlpha} / 下限）；还是看不见 = 参数之外的问题。
     */
    private static volatile boolean coneBoost;
    private static boolean spray;
    private static int sprayTicksLeft;
    /** 单调累计值；日志里减去上一次的值得到"这 5 秒"的量。 */
    private static long sprayedTotal;
    private static long lastSprayed;
    private static long lastCalls;
    private static long lastQuads;
    private static long lastProbeQuads;
    private static long lastVolumeBeams;
    /** 探针分通道计数（单调）：眼平圈 / 眼前对照 / 旧位置 CLOUD / 直接进 ParticleManager。 */
    private static long flameEyeTotal;
    private static long flameSelfTotal;
    private static long cloudOldTotal;
    private static long pmDirectTotal;
    private static long airSkippedTotal;
    private static long lastFlameEye;
    private static long lastFlameSelf;
    private static long lastCloudOld;
    private static long lastPmDirect;
    private static long lastAirSkipped;
    /** 上一窗口的闸门单调累计值，索引 1..9（见 {@link ProtoBeamCone#gateTotal}）。 */
    private static final long[] lastGates = new long[10];
    private static int ticks;

    private DebugProbe() {
    }

    // --- switches -----------------------------------------------------------------------------

    public static boolean coneEnabled() {
        return cone;
    }

    public static void setCone(final boolean on) {
        cone = on;
        LteLog.LOGGER.info("[lte] debug: cone probe = {}", on);
    }

    /** 真实光束的提亮对照开关（见 {@link #coneBoost}）。渲染线程每帧读一次。 */
    public static boolean coneBoostEnabled() {
        return coneBoost;
    }

    public static void setConeBoost(final boolean on) {
        coneBoost = on;
        LteLog.LOGGER.info("[lte] debug: coneBoost = {} (0.45 flat alpha, no attenuation)", on);
    }

    public static boolean sprayEnabled() {
        return spray;
    }

    /**
     * 开/关水雾探针。打开时从零开始重新计时 10 秒；关闭时立刻停。
     *
     * @return 打开后还剩多少 tick（关闭时 0），给命令回显用
     */
    public static int setSpray(final boolean on) {
        spray = on;
        sprayTicksLeft = on ? SPRAY_DURATION_TICKS : 0;
        if (on) {
            // 每次开探针都从零计数：否则第二次看日志时 eye= 是两次之和，看着像"漏了 8 倍"。
            flameEyeTotal = 0L;
            flameSelfTotal = 0L;
            cloudOldTotal = 0L;
            pmDirectTotal = 0L;
            airSkippedTotal = 0L;
            lastFlameEye = 0L;
            lastFlameSelf = 0L;
            lastCloudOld = 0L;
            lastPmDirect = 0L;
            lastAirSkipped = 0L;
        }
        LteLog.LOGGER.info("[lte] debug spray: spray probe = {}", on);
        return sprayTicksLeft;
    }

    /** 当前剩余 tick（诊断/状态用）。 */
    public static int sprayTicksLeft() {
        return Math.max(0, sprayTicksLeft);
    }

    // --- tick ---------------------------------------------------------------------------------

    /**
     * 一个 client tick：先喷粒子（这样同一窗口里的计数立即可见），到点再打诊断行。
     *
     * <p>由版本侧的 {@code RenderHooks} 单独注册，<b>不挂在 LightController 上</b>：后者在
     * {@code enabled=false} 时提前 return，而探针必须在"整个模组看起来没工作"的时候也能跑。
     */
    public static void tick() {
        final MinecraftClient client = MinecraftClient.getInstance();
        ticks++;
        autoStart(client);
        if (spray) {
            spawn(client);
        }
        if (ticks % LOG_TICKS == 0) {
            log(client);
        }
    }

    /**
     * 自动开探针：{@code -Dlte.debugSpray=<延迟 tick>}（缺省 120）时，进世界后延迟若干 tick
     * 自动打开水雾探针。
     *
     * <p>只为一个场景存在：实机验证时没有输入自动化，命令敲不进去。它不影响默认行为
     * （属性不存在时一个字节都不多做），也不改配置、不落盘。
     */
    private static void autoStart(final MinecraftClient client) {
        if (autoChecked) {
            if (autoSprayAt <= 0) {
                return;
            }
            if (client == null || client.player == null || client.world == null) {
                return;
            }
            if (ticks < autoSprayAt) {
                return;
            }
            autoSprayAt = 0;
            setSpray(true);
            LteLog.LOGGER.info("[lte] debug spray: auto-started by -Dlte.debugSpray");
            return;
        }
        autoChecked = true;
        try {
            final String raw = System.getProperty("lte.debugSpray");
            if (raw == null || raw.isEmpty() || "0".equals(raw) || "off".equalsIgnoreCase(raw)) {
                return;
            }
            int delay;
            try {
                delay = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                delay = 120;
            }
            autoSprayAt = ticks + Math.max(20, delay);
            LteLog.LOGGER.info("[lte] debug spray: armed by -Dlte.debugSpray, fires in {} ticks",
                    Integer.valueOf(autoSprayAt - ticks));
        } catch (Throwable t) {
            // 安全属性读不到：当作没设。
        }
    }

    /** 自动探针的 tick；0 = 没排期。{@code autoChecked} 保证系统属性只读一次。 */
    private static int autoSprayAt;
    private static boolean autoChecked;

    /**
     * 一 tick 的探针：四条通道同时打，位置全部以<b>眼睛</b>为基准。
     *
     * <p>旧实现把粒子放在 {@code player.getPos().y + 0.15}（= 脚底往上 15 cm）再往脚前 3 格：
     * 站在站台/车厢里时那一点经常已经在方块里或车体内部，600 颗全部被挡住 —— 于是"生成了但一颗
     * 都看不到"。现在主探针在眼平高度、眼前 2 格，并且<ul>
     *   <li>落点先用 {@code isAir} 检查，不空气就跳过并计数（{@code airSkipped}），</li>
     *   <li>另有一颗 {@code FLAME} 贴在眼睛前 0.8 格（一定在空气里、一定不被遮挡），</li>
     *   <li>保留一颗旧位置的 {@code CLOUD} 做 A/B，</li>
     *   <li>还有一颗<b>绕开 {@code ClientWorld.addParticle}</b> 直接进 {@code ParticleManager}
     *       的 {@code FLAME}：如果它看得到而另外三条看不到，问题就在 {@code ClientWorld} 的分支
     *       （距离 / MINIMAL / force），不在渲染层。</li>
     * </ul>
     */
    private static void spawn(final MinecraftClient client) {
        final ClientPlayerEntity player = client.player;
        final ClientWorld world = client.world;
        if (player == null || world == null) {
            return;
        }
        final Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d flat = new Vec3d(look.x, 0.0D, look.z);
        flat = flat.lengthSquared() < 1.0E-6D ? new Vec3d(0.0D, 0.0D, 1.0D) : flat.normalize();

        // 主探针：眼平、眼前 2 格的一圈 FLAME（半径 0.45–0.80），每 tick 换一圈。
        final double cx = eye.x + flat.x * SPRAY_AHEAD;
        final double cy = eye.y;
        final double cz = eye.z + flat.z * SPRAY_AHEAD;
        for (int i = 0; i < SPRAY_PER_TICK; i++) {
            final double angle = Math.PI * 2.0D * i / SPRAY_PER_TICK
                    + tickPhase(world.random.nextDouble());
            final double radius = 0.45D + 0.35D * world.random.nextDouble();
            final double x = cx + Math.cos(angle) * radius;
            final double y = cy + (world.random.nextDouble() - 0.5D) * 0.7D;
            final double z = cz + Math.sin(angle) * radius;
            if (!isAir(world, x, y, z)) {
                airSkippedTotal++;
                continue;
            }
            world.addParticle(ParticleTypes.FLAME, true, x, y, z, 0.0D, 0.004D, 0.0D);
            flameEyeTotal++;
            sprayedTotal++;
        }

        // 对照 1：眼睛前 0.8 格的 FLAME —— 一定在空气里、一定在视野正中。
        final double sx = eye.x + flat.x * 0.8D;
        final double sy = eye.y - 0.05D;
        final double sz = eye.z + flat.z * 0.8D;
        world.addParticle(ParticleTypes.FLAME, true, sx, sy, sz, 0.0D, 0.0D, 0.0D);
        flameSelfTotal++;
        sprayedTotal++;

        // 对照 2：旧探针位置（脚底 +0.15、脚前 3 格）的 CLOUD，A/B 用。
        final double ox = player.getX() + flat.x * SPRAY_AHEAD_OLD;
        final double oy = player.getY() + 0.15D;
        final double oz = player.getZ() + flat.z * SPRAY_AHEAD_OLD;
        world.addParticle(ParticleTypes.CLOUD, true, ox, oy, oz, 0.0D, 0.02D, 0.0D);
        cloudOldTotal++;
        sprayedTotal++;

        // 对照 3：绕开 ClientWorld，直接进 ParticleManager（不看距离、不看粒子档位）。
        if (addDirect(client, ParticleTypes.FLAME, cx, cy, cz)) {
            pmDirectTotal++;
        }

        if (--sprayTicksLeft <= 0) {
            spray = false;
            sprayTicksLeft = 0;
            say(player, "[LTE] 水雾探针结束（5 秒）：FLAME 眼平圈 " + flameEyeTotal
                    + " 颗（空气跳过 " + airSkippedTotal + "）、眼前对照 " + flameSelfTotal
                    + "、旧位置 CLOUD " + cloudOldTotal + "、直插 ParticleManager " + pmDirectTotal);
            final String culled = ParticleCull.reason();
            if (culled != null) {
                // 这一句是「看得见/看不见」的分水岭：不是位置、不是纹理、不是渲染管道。
                say(player, "[LTE] " + culled);
            }
            LteLog.LOGGER.info("[lte] debug spray: done eye={} self={} cloudOld={} pmDirect={}"
                            + " airSkipped={} pm={} culled={}",
                    Long.valueOf(flameEyeTotal), Long.valueOf(flameSelfTotal),
                    Long.valueOf(cloudOldTotal), Long.valueOf(pmDirectTotal),
                    Long.valueOf(airSkippedTotal), particleManagerDebug(client),
                    ParticleCull.culled().isEmpty() ? "none" : ParticleCull.culled());
        }
    }

    /** 每 tick 转一点，看起来是一圈在转的火环而不是同一圈重复闪。 */
    private static double tickPhase(final double random) {
        return (ticks % 40) * 0.157D + random * 0.1D;
    }

    /** 落点是不是空气（拿不到世界时按"是"处理，宁可多打一颗）。 */
    private static boolean isAir(final ClientWorld world, final double x, final double y,
                                 final double z) {
        try {
            // BlockPos.ofFloored is 1.19.4+; the three Math.floor calls are the same thing and
            // keep this file compiling on every supported version.
            return world.isAir(new net.minecraft.util.math.BlockPos(
                    (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 直接往 {@code ParticleManager} 里塞一颗粒子 —— 绕开 {@code ClientWorld#addParticle} 的
     * 距离 / 粒子档位 / force 分支（但<b>绕不过</b> Sodium Extra 的逐类型屏蔽，见
     * {@link ParticleCull}）。
     *
     * <p>用反射且<b>只按方法名 + 形参个数匹配，不看返回类型</b>：1.20.1 的
     * {@code ParticleManager#addParticle(ParticleEffect, d,d,d,d,d,d)} 返回 {@code Particle}
     * （不是 void），按 void 去 {@code getMethod} 会抛 {@code NoSuchMethodException} ——
     * 第一版探针就是这么"统计到 0 次"的。
     *
     * @return 真的调进去了吗
     */
    private static boolean addDirect(final MinecraftClient client,
                                     final net.minecraft.particle.ParticleEffect effect,
                                     final double x, final double y, final double z) {
        if (client == null || client.particleManager == null) {
            return false;
        }
        final java.lang.reflect.Method m = findAddParticle(client.particleManager);
        if (m == null) {
            return false;
        }
        try {
            m.invoke(client.particleManager, effect, x, y, z, 0.0D, 0.01D, 0.0D);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 找 {@code addParticle(ParticleEffect, 6 × double)}，不看返回类型。 */
    private static java.lang.reflect.Method findAddParticle(final Object manager) {
        for (final java.lang.reflect.Method m : manager.getClass().getMethods()) {
            if (!"addParticle".equals(m.getName())) {
                continue;
            }
            final Class<?>[] p = m.getParameterTypes();
            if (p.length != 7 || p[0] != net.minecraft.particle.ParticleEffect.class) {
                continue;
            }
            boolean ok = true;
            for (int i = 1; i < 7; i++) {
                if (p[i] != double.class) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return m;
            }
        }
        return null;
    }

    /**
     * {@code ParticleManager} 现在持有多少粒子。
     *
     * <p>先试 {@code getDebugString()}；1.20.1 没有这个方法时退回读 {@code particles} 字段
     * （{@code Map<ParticleTextureSheet, Queue<Particle>>}）把各队列长度加起来。这是「粒子到底进没
     * 进粒子系统」的唯一硬证据：{@code pm>0} 而屏幕上什么都没有 = 渲染层；{@code pm=0} 而
     * {@code eye>0} = 在进入粒子系统时就被拦掉了（Sodium Extra 的逐类型开关就是这么干的）。
     */
    private static String particleManagerDebug(final MinecraftClient client) {
        if (client == null || client.particleManager == null) {
            return "-";
        }
        try {
            final java.lang.reflect.Method m = client.particleManager.getClass()
                    .getMethod("getDebugString");
            final Object value = m.invoke(client.particleManager);
            return value == null ? "-" : value.toString().replace(' ', '_');
        } catch (Throwable ignored) {
            // 没有这个方法：退回数队列。
        }
        try {
            final java.lang.reflect.Field f = client.particleManager.getClass()
                    .getField("particles");
            final Object value = f.get(client.particleManager);
            if (value instanceof java.util.Map<?, ?> map) {
                int total = 0;
                for (final Object queue : map.values()) {
                    if (queue instanceof java.util.Collection<?> c) {
                        total += c.size();
                    }
                }
                return Integer.toString(total);
            }
        } catch (Throwable ignored) {
            // 字段也读不到：只能报"?"。
        }
        return "?";
    }

    private static void log(final MinecraftClient client) {
        final long calls = ProtoBeamCone.callsTotal();
        final long quads = ProtoBeamCone.quadsTotal();
        final long probeQuads = ProtoBeamCone.probeQuadsTotal();
        final long volumeBeams = ProtoBeamCone.volumeBeamsTotal();
        final int beams = ProtoBeamCone.liveBeams();
        // 闸门按差值算：render() 在 g1/g2 上会整帧早退，那时它自己的 5 秒块永远不打印，
        // 单调累计 + 这里的差值才保证"被哪一道闸拦住"在任何情况下都有一行。
        final long[] gates = new long[10];
        for (int i = 1; i <= 9; i++) {
            gates[i] = ProtoBeamCone.gateTotal(i) - lastGates[i];
        }
        int slots = 0;
        final List<String> models = new ArrayList<>();
        final Set<String> seenModels = new LinkedHashSet<>();
        try {
            for (final TrainClient train : new ArrayList<>(ClientData.TRAINS)) {
                if (train == null) {
                    continue;
                }
                slots += VehicleLights.slotCount(train);
                if (models.size() < MAX_MODEL_LINES) {
                    final String id = train.baseTrainType == null ? "?" : train.baseTrainType;
                    if (seenModels.add(id)) {
                        models.add(VehicleLights.debugLine(train));
                    }
                }
            }
        } catch (Throwable t) {
            // 数据同步正在整批替换：这一窗口的数字少一点没关系，绝不能因为诊断把 tick 打断。
            LteLog.LOGGER.warn("[lte] debug: model scan failed", t);
        }
        LteLog.LOGGER.info("[lte] debug: coneCalls={} coneQuads={} probeQuads={} volBeams={}"
                        + " spraySpawned={} beams={} slots={} dmin={} range={}"
                        + " g1={} g2={} g3={} g4={} g5={} g6={} g7={} g8={} g9={} path={} source={}",
                calls - lastCalls, quads - lastQuads, probeQuads - lastProbeQuads,
                volumeBeams - lastVolumeBeams, sprayedTotal - lastSprayed, beams, slots,
                String.format(Locale.ROOT, "%.1f", ProtoBeamCone.nearestBeamDistance()),
                String.format(Locale.ROOT, "%.0f", ProtoBeamCone.beamRange()),
                gates[1], gates[2], gates[3], gates[4], gates[5], gates[6], gates[7], gates[8],
                gates[9], ProtoBeamCone.pathName(), ProtoBeamCone.sourceName());
        // 探针分通道一行：只有探针开过（或有历史计数）才打，平时不刷屏。
        if (spray || flameEyeTotal > 0 || flameSelfTotal > 0 || pmDirectTotal > 0) {
            LteLog.LOGGER.info("[lte] debug spray: eye={} self={} cloudOld={} pmDirect={}"
                            + " airSkipped={} pm={} culled={}",
                    Long.valueOf(flameEyeTotal - lastFlameEye),
                    Long.valueOf(flameSelfTotal - lastFlameSelf),
                    Long.valueOf(cloudOldTotal - lastCloudOld),
                    Long.valueOf(pmDirectTotal - lastPmDirect),
                    Long.valueOf(airSkippedTotal - lastAirSkipped),
                    particleManagerDebug(client),
                    ParticleCull.culled().isEmpty() ? "none" : ParticleCull.culled());
            lastFlameEye = flameEyeTotal;
            lastFlameSelf = flameSelfTotal;
            lastCloudOld = cloudOldTotal;
            lastPmDirect = pmDirectTotal;
            lastAirSkipped = airSkippedTotal;
        }
        lastCalls = calls;
        lastQuads = quads;
        lastProbeQuads = probeQuads;
        lastVolumeBeams = volumeBeams;
        lastSprayed = sprayedTotal;
        for (int i = 1; i <= 9; i++) {
            lastGates[i] = ProtoBeamCone.gateTotal(i);
        }
        // 只有"解析出来但没画"才有意义：这时逐车型点名，看是 slots=0（解析不出灯位）还是
        // slots>0（灯位有、几何没出去）。纯距离原因（g7）不刷这几行 —— 灯位是好的。
        if (beams == 0 && (gates[3] + gates[4] + gates[5]) > 0) {
            for (final String line : models) {
                LteLog.LOGGER.info("[lte] debug: {}", line);
            }
        }
    }

    /** 聊天栏一行（玩家可能正忙着，所以不用 action bar）。 */
    private static void say(final ClientPlayerEntity player, final String message) {
        try {
            player.sendMessage(Text.of(message), false);
        } catch (Throwable ignored) {
            // 玩家已下线 / 正在切换维度：探针自己停就行，不必再喊。
        }
    }

    /** 供命令回显的完整状态。 */
    public static String status() {
        return "cone=" + (cone ? "on" : "off")
                + " coneBoost=" + (coneBoost ? "on" : "off")
                + " spray=" + (spray ? "on(" + sprayTicksLeft + "t)" : "off")
                + " beams=" + ProtoBeamCone.liveBeams();
    }
}
