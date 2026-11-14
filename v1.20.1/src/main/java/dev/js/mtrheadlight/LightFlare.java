package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The visible train light: <b>one camera-facing quad per physical lamp</b>, at the position
 * {@link TrainLamps} computed from the model's own lamp boxes.
 *
 * <h2>What this replaced, and why</h2>
 * The old look was a volumetric cone ({@code ProtoBeamCone}, four additive shells, later a
 * raymarched volume in {@code ConeVolumeRenderer}) plus a ground-hugging sheet ({@code BeamGlow}).
 * Both were "fake light geometry": they had to be tuned per car shape, they poked through walls
 * whenever the depth path was unavailable, and - measured over real packs - most trains simply had
 * no beam, because the geometry needed a lamp slot and the slot lookup failed for every built-in
 * model. The light is now split the way it actually behaves:
 * <ul>
 *   <li><b>here</b> - a small additive glow at the lamp. Cheap, cannot intersect anything, and a
 *       lamp 150 blocks away still reads as a lamp.</li>
 *   <li><b>{@code BeamBlockLight}</b> - the light that lands on the world. It is real block light
 *       baked into the section mesh, so it obeys walls, tunnels and slopes by construction.</li>
 * </ul>
 * Nothing is drawn for the "beam" itself. That is deliberate: a cone that is visible in mid-air but
 * does not light anything is exactly the thing the user asked to remove.
 *
 * <h2>Size on screen</h2>
 * <pre>
 * facing   = -dot(lamp.dir, normalize(lamp - camera))      1 = the lamp looks at you
 *            （lamp.dir = 灯面朝向 = -out，见 TrainLamps.place；f≈+1 才是"正对着你"）
 * world    = base * (0.45 + 1.4 * facing³)                 base = config beamFlareSize
 * pixels   = world / (2 * d * tan(fov/2)) * screenHeight
 * world   *= max(1, minPx / pixels)                        the minimum-size rule
 * </pre>
 * The {@code facing³} term is what makes a lamp dim and small when it points away and grow when it
 * turns towards you - the same cue the old cone tried to give, for one quad instead of 1890.
 * The minimum-size rule is the fix for "the far end of the platform has no lights": a 9-pixel floor
 * keeps a lamp visible out to the edge of the beam distance instead of letting it shrink to nothing
 * at 40 blocks.
 *
 * <h2>Depth and blending</h2>
 * Additive ({@code SRC_ALPHA, ONE}), depth comparison <b>forced to {@code GL_LEQUAL}</b> (so the glow
 * is occluded by cars, platforms and walls, like any other piece of geometry), depth write off, cull
 * off. Each quad is pulled at most {@value #MAX_PULL} blocks towards the camera -
 * {@code min(0.6, 5% * distance)} - just enough to clear the lamp housing.
 *
 * <p><b>Why not {@code GL_ALWAYS} any more</b> (1.0.62 did that): {@code TrainLamps} already places the
 * lamp <b>outside</b> the car's end face - {@code back = inset + FLARE_GAP} is pushed along the outward
 * direction, so a lamp is at least {@code FLARE_GAP} (8 cm) in front of the face and further out by
 * whatever inset the model measurement found - and the quad is pulled towards the camera from there, so
 * the depth test no longer eats it. An additive quad that skips the depth test, on the other hand, is
 * visible <em>through</em> blocks, rails and hills, which is exactly the report 1.0.63 answers ("the
 * glow is back, but I can see it through the track"). Occlusion is solved by <b>position</b> (outside
 * the car face + a small pull), never by defeating the depth buffer. The comparison is still forced
 * with {@link GlowCompat#forceDepthFunc} — a direct {@code glDepthFunc} call — because 1.20.1's
 * {@code GlStateManager._depthFunc} is <b>cached</b> and silently skips the GL call when its cached
 * value already matches: anything that touches GL depth outside {@code GlStateManager} (Sodium,
 * Embeddium, a debug overlay) desyncs that cache, and the depth function we inherit is whatever the
 * last mod left behind.
 *
 * <p><b>Only the late pass draws</b> ({@code AFTER_TRANSLUCENT}), never both: the early callback
 * ({@code AFTER_ENTITIES}) still has half the frame behind it — translucents, particles, weather and
 * MTR's own late train geometry — and nothing stops later opaque geometry from painting over an
 * additive quad. Depth testing does not beat draw order. That is exactly the
 * "the glow is only visible through rails/gaps, invisible when I look straight at the train, and
 * fine with a shader pack" report: with a pack the old code already drew in the late pass. See the
 * {@code [lte] flare draw:} line, which prints which pass actually drew and the depth function read
 * back from GL.
 *
 * <h2>「灯晕看不见」的两个真凶（本版本已修 / 已加取证）</h2>
 * <ol>
 *   <li><b>坐标空间。</b>上一版把<b>世界绝对坐标</b>直接喂进 {@code buffer.vertex(matrix, …)}。世界渲染
 *       的 {@code MatrixStack} 只带相机<b>旋转</b>，不含平移（原版把相机位移烘在区块网格和实体位置上），
 *       于是几百格以外的灯被旋转后落到视锥外：{@code quads} 照涨，屏幕上一个像素都没有。现在顶点
 *       一律先减相机坐标（相机相对），和 {@link BeamGlow} 完全一致 —— 那个减法就是两条路径唯一、
 *       也是决定性的差别。见 {@link GlowCompat} 的类注释。</li>
 *   <li><b>绘制路径。</b>旧版自己 `begin/end` 共享 Tessellator 再 {@code drawWithGlobalProgram}；
 *       现在顶点先攒进一个复用的 float 暂存区，最后一次 {@link GlowCompat#drawTextured} —— 与光带
 *       {@link GlowCompat#draw} 同一份 shim、同一串 GL 状态与复位，区别只有 vertex format 与贴图。
 *       顺带修掉「quads=0 时 buffer 必须 endNullable」那条半开缓冲的分支。</li>
 * </ol>
 *
 * <h2>取证：{@code /mtr_lte lighttest}</h2>
 * 打开后这里不再画贴图灯晕，而是给每列车的<b>两端各画一块 3 格宽的洋红四边形</b>（车头/车尾灯口前方
 * 2 格），跳过朝向、距离、透明度、{@code lightStyle}、{@code isLit} 全部判定，并故意用<b>不带贴图</b>
 * 的 {@code position_color} 程序 —— 也就是光带自己那条已验证可见的路。判读方式见 {@link LightTest}。
 */
public final class LightFlare {
    /** Our own texture; generated by {@code tools/gen-light-flare.js}, never downloaded. */
    private static final Identifier TEXTURE =
            new Identifier("mtrlte", "textures/vehicle/light_flare.png");
    /** Push towards the camera, blocks, so the quad clears the lamp housing - and no further. */
    private static final double MAX_PULL = 0.6D;
    /**
     * Where the pull starts: 5% of the distance, saturating at {@link #MAX_PULL}. Small on purpose:
     * the lamp already sits {@code FLARE_GAP} outside the car face, so the pull only has to clear the
     * housing, and every extra block of pull is a block the glow can poke through a wall it should
     * have been hidden behind.
     */
    private static final double PULL_FRACTION = 0.05D;
    /** Below this facing the lamp is pointing away from the camera - not drawn. */
    private static final double MIN_FACING = -0.15D;
    /**
     * Alpha floor as a share of the configured peak: a lamp that passed every gate is drawn at no
     * less than {@code beamAlpha * this}. See the note in {@link #quad}.
     */
    private static final float MIN_ALPHA_SHARE = 0.35F;
    /** Distance fade starts at this share of the beam distance. */
    private static final double FADE_FROM = 0.70D;
    private static final Vec3d UP = new Vec3d(0.0D, 1.0D, 0.0D);

    /** Floats per flare vertex: x y z u v r g b a. */
    private static final int FLARE_STRIDE = 9;
    /** Floats per lighttest vertex: x y z r g b a. */
    private static final int TEST_STRIDE = 7;
    /** lighttest 四边形：3 格宽 / 3 格高（半边长 1.5）。 */
    private static final double TEST_HALF = 1.5D;
    /** lighttest 四边形离灯口/车头端面的距离，格。 */
    private static final double TEST_AHEAD = 2.0D;

    /**
     * 相机相对的顶点暂存区，按需增长。每帧清零复用，热路径上不分配对象 —— 和 {@code BeamGlow}
     * 的 {@code vertices} 用法相同。
     */
    private static float[] flareVerts = new float[0];
    private static int flareCount;
    private static float[] testVerts = new float[0];
    private static int testCount;

    /* Diagnostics, one line per 5 s while the feature is on. */
    private static long diagAt = System.currentTimeMillis();
    private static int dTrains;
    private static int dLamps;
    private static int dQuads;
    private static int dNoLamp;
    private static boolean windowOn;

    /*
     * 「为什么这一列车没亮」的三档计数，每 5 秒一行、打完清零。
     *
     * 灯晕和散射都走 {@link HeadlightSwitch#judge}，所以这三档就是那条判定的全部否决理由：
     *   offRoute —— MTR 说它不在线路上（车厂 / 车辆段 / 未派车），见 {@link DepartureState#departed}
     *   dark     —— AUTO 且此刻不需要灯（白天露天 / 无雨 / 不在隧道），见 RailWeather#lightsNeeded
     *   parked   —— AUTO 且速度连续为 0 超过停放阈值（停车灯灭），见 {@link DepartureState#stopped}
     *
     * 计数用<b>去重的列车键</b>而不是「列车 × 帧」的累加：帧数随机器好坏变（一台 300 帧、一台
     * 600 帧），而玩家要读的是「这 5 秒里有几列车因为什么没亮」。所以 {@code skipped=offRoute=2
     * dark=5 parked=1} 就是 2 列车在车厂、5 列车在白天露天、1 列车停着 —— 一句话对得上画面。
     */
    private static final Set<Long> SKIP_OFF_ROUTE = new HashSet<>();
    private static final Set<Long> SKIP_DARK = new HashSet<>();
    private static final Set<Long> SKIP_PARKED = new HashSet<>();
    /** 上一次 5 秒窗口的 {@code skipped=…} 文本，给 {@link #skipText()}（指令）用。 */
    private static String lastSkipText = "skipped=offRoute=0 dark=0 parked=0";

    /** How many nearest lamps the {@code [lte] flare near:} line prints per window. */
    private static final int NEAR_REPORT = 3;
    /** Reported near-lamp slots, sorted by camera distance (index 0 = the closest lamp). */
    private static double[] nearDist = new double[NEAR_REPORT];
    private static double[] nearFacing = new double[NEAR_REPORT];
    private static double[] nearWorld = new double[NEAR_REPORT];
    private static double[] nearPx = new double[NEAR_REPORT];
    private static double[] nearAlpha = new double[NEAR_REPORT];
    private static double[] nearFade = new double[NEAR_REPORT];
    private static String[] nearWhere = new String[NEAR_REPORT];
    /** Did the textured draw actually run this window, and why not. */
    private static boolean dDrawn;
    private static String dDrawError;
    /** {@code ok} / {@code missing} / {@code error:X} - the flare texture's state, sampled once. */
    private static String dTexture = "unknown";
    /** Was the last rendered frame the {@code lighttest} probe? Then the near table is not updated. */
    private static boolean dTestWindow;

    /*
     * 「这条绘制到底用了什么 GL 状态」的取证，5 秒一行的 {@code [lte] flare draw:}。
     *
     * 数值全部来自 {@link GlowCompat#diagDepthFunc} 那一组 —— draw 之后从 GL <b>读回来</b>的，
     * 不是我们以为设了什么。这一行存在的理由：用户报「不开光影时灯晕只有隔着轨道/方块才看得到」，
     * 而「深度真的仍然是 LEQUAL」与「深度没问题、是被后画的东西盖上」在截图里一模一样。
     */
    private static int dDrawQuads;
    private static int dDrawDepthFunc = -1;
    private static int dDrawDepthEntry = -1;
    private static int dDrawBlendSrc = -1;
    private static int dDrawBlendDst = -1;
    private static boolean dDrawBlend;
    private static boolean dDrawCull;
    private static int dDrawTextureId = -1;
    private static boolean dDrawForceOcclusion;
    private static boolean dDrawLatePass;
    private static boolean dPackActive;
    /** 两趟回调各自被调了几次 / 画了几次 —— 「谁在画」的唯一答案。 */
    private static int dEarlyOffers;
    private static int dEarlyDraws;
    private static int dLateOffers;
    private static int dLateDraws;

    private LightFlare() {
    }

    /**
     * 上一次 5 秒窗口的「为什么没亮」计数，形如
     * {@code skipped=offRoute=2 dark=3 parked=0}，给 {@code /mtr_lte flare status} 用。
     *
     * <p>三档都来自 {@link HeadlightSwitch#Verdict}，也就是灯晕和散射共用的那一个判定：车不在
     * 线路上 / 环境不需要灯（AUTO）/ 停着（AUTO）。记的是<b>去重的列车数</b>（最近一个 5 秒
     * 窗口），所以 {@code offRoute=2} 就是「两列车在车厂/回库，所以没亮」。
     */
    public static String skipText() {
        return lastSkipText;
    }

    /**
     * 一列车的去重键，只给上面三个 {@code skipped} 集合用：MTR 的稳定 id 优先，没有 id 时用
     * 对象身份哈希并打上高位标记，免得和真实 id 撞号。
     */
    private static long skipKey(final TrainClient train) {
        if (train.id != 0L) {
            return train.id;
        }
        return 0x100000000L | (System.identityHashCode(train) & 0xFFFFFFFFL);
    }

    /**
     * Draws every lamp of every visible train that has left its depot.
     *
     * @param latePass {@code true} for the {@code AFTER_TRANSLUCENT} call
     */
    public static void render(final WorldRenderContext context, final boolean latePass) {
        // 先刷日志窗口：两个回调都会调到这里，5 秒一行的判读在关掉探针后自然安静。
        LightTest.report();
        // 「谁被叫过」先记，再谈任何闸门 —— 只有这条计数能回答「两趟 latePass 各是什么」。
        if (latePass) {
            dLateOffers++;
        } else {
            dEarlyOffers++;
        }

        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (cfg == null) {
            return;
        }
        final boolean test = LightTest.enabled();
        // 探针绕开全部配置闸（光样式 / 灯晕开关 / 总开关）：它测的是渲染路径，不是玩家的设置。
        if (!test && (!cfg.enabled || !cfg.flareAnySiding())) {
            return;
        }
        windowOn = true;
        try {
            dPackActive = ShaderPackProbe.active();
        } catch (Throwable t) {
            return;
        }
        /*
         * 只由晚趟（AFTER_TRANSLUCENT）画，绝不两趟都画。
         *
         * 这一条就是「不开光影时灯晕只有隔着轨道/方块才看得到、开了光影反而正常」的答案：早趟
         * （AFTER_ENTITIES）之后世界还要画一大段 —— 半透明层、粒子、天气，以及 MTR 自己的车体
         * 后段几何。灯晕是加色四边形，<b>挡不住被后画的几何覆盖</b>：
         * 一个不透明三角形在它之后画上来，那块像素就是那个三角形的颜色。于是「灯就在眼前，屏幕上
         * 是车体」而「透过轨道缝隙、隔着别的方块看，反而看得到那一圈光」—— 用户描述的现象逐字
         * 对应。光影包路径本来就只有晚趟在画（旧代码 shaders==latePass 时只有 latePass=true 才
         * 过闸），而那条路用户看到是正常的，所以这里把它无条件化：两趟都收，只有晚趟画。
         */
        if (!latePass) {
            return;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.gameRenderer == null) {
            return;
        }
        final MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;                             // never draw without the world matrix
        }
        final Vec3d camera = client.gameRenderer.getCamera().getPos();
        final Vec3d eye = client.player.getPos();

        flareCount = 0;
        testCount = 0;
        int quads = 0;

        if (test) {
            quads = lightTest(cfg, camera);
        } else {
            quads = lamps(cfg, client, camera, eye);
            dQuads += quads;
        }
        dTestWindow = test;

        if (quads > 0) {
            // 两条绘制都在这里发生，都走 GlowCompat —— 参数只是 format 与贴图，GL 状态不在这里碰。
            // 灯晕那一条打开 forceOcclusion：深度函数强制 LEQUAL，光照常被车体/方块挡住（不再穿墙）。
            try {
                if (testCount > 0) {
                    GlowCompat.draw(testVerts, testCount, matrices);
                } else if (flareCount > 0) {
                    GlowCompat.drawTextured(flareVerts, flareCount, matrices, TEXTURE, true);
                    dDrawn = true;
                    captureDraw(quads, latePass);
                }
            } catch (Throwable t) {
                // 探针模式下这一句就是「程序/贴图没加载」的答案，直接进 [lte] lighttest: 那一行。
                dDrawError = t.getClass().getSimpleName() + ":" + firstLine(t);
                LightTest.flareFailure(dDrawError);
                if (!test) {
                    MtrHeadlightClient.LOGGER.error("[lte] light flare draw failed", t);
                }
            }
        }
        if (!test && flareCount > 0 && "unknown".equals(dTexture)) {
            // 采样一次就够：贴图状态不会在会话中途变（除非换了资源包，那时重载会重建管理器）。
            // 它是「几何在、贴图没了」这一种失败的唯一名字 —— 探针那条路不贴图，证明不了它。
            dTexture = textureState(client);
        }
        diag();
    }

    /** The normal path: one textured quad per lamp. Returns the number of quads written. */
    private static int lamps(final HeadlightConfig cfg, final MinecraftClient client,
                             final Vec3d camera, final Vec3d eye) {
        final double base = cfg.flareSizeClamped();
        final int screenH = Math.max(1, client.getWindow().getScaledHeight());
        final int minPx = cfg.beamFlareMinPxFor(screenH);
        final float peak = (float) clamp(cfg.beamAlpha, 0.0D, 1.0D);
        final double fov = verticalFov(client);
        nearClear();
        int quads = 0;

        for (final TrainClient train : snapshotTrains()) {
            dTrains++;
            if (!drawable(cfg, train)) {
                continue;
            }
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
            if (!eff.enabled || !eff.beamFlare) {
                continue;
            }
            // 灯亮不亮由 HeadlightSwitch.judge 一个入口决定（在线路 / 环境 / 停着），见下面的三道闸。

            final boolean reversed = DepartureState.reversed(train, train.isReversed()) ^ cfg.swapEnds;
            final TrainPose pose = TrainPose.ofShared(train, reversed);
            if (pose.cars() <= 0) {
                continue;
            }
            /*
             * 自动开关：一个入口，三个原因（见 {@link HeadlightSwitch#Verdict}）。灯晕原来只问
             * 「现在暗吗」，所以停在车辆段 / 回库的车照样亮着——用户在车厂看到的就是这一条。
             * 现在 offRoute / dark / parked 三档各自计数，5 秒一行的 {@code skipped=} 和
             * {@code /mtr_lte flare status} 里都能读到，不用再猜「为什么没亮」。
             */
            final HeadlightSwitch.Verdict verdict = HeadlightSwitch.judge(client.world, pose, cfg, train);
            if (!verdict.lit()) {
                final Long key = Long.valueOf(skipKey(train));
                switch (verdict) {
                    case OFF_ROUTE -> SKIP_OFF_ROUTE.add(key);
                    case DARK -> SKIP_DARK.add(key);
                    case PARKED -> SKIP_PARKED.add(key);
                    default -> {
                    }
                }
                continue;
            }
            final List<TrainLamps.Lamp> lamps = TrainLamps.of(train, pose, cfg, eff);
            if (lamps.isEmpty()) {
                dNoLamp++;
                continue;
            }
            dLamps += lamps.size();
            for (final TrainLamps.Lamp lamp : lamps) {
                if (quad(lamp, camera, eye, eff.beamDistance, base, minPx, peak, fov, screenH)) {
                    quads++;
                }
            }
        }
        return quads;
    }

    /**
     * {@code /mtr_lte lighttest} 的几何：每列车两端各一块 3 格见方的洋红四边形，画在端面外
     * {@value #TEST_AHEAD} 格。没有任何判定 —— 朝向、距离、透明度、灯位、{@code lightStyle} 全部不参与。
     *
     * <p>两端都画是有意的：玩家可能站在任意一头，只画车头会出现「其实画了、只是在他背后」这种
     * 又一次说不清的反馈。
     *
     * @return 写进 {@link #testVerts} 的四边形数
     */
    private static int lightTest(final HeadlightConfig cfg, final Vec3d camera) {
        int quads = 0;
        for (final TrainClient train : snapshotTrains()) {
            if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
                continue;
            }
            final boolean reversed = DepartureState.reversed(train, train.isReversed()) ^ cfg.swapEnds;
            final TrainPose pose = TrainPose.ofShared(train, reversed);
            if (pose.cars() <= 0) {
                continue;
            }
            quads += testQuad(pose.front(), endDir(pose, 0, true), camera);
            quads += testQuad(pose.rear(), endDir(pose, Math.max(0, pose.cars() - 1), false), camera);
        }
        return quads;
    }

    /** One magenta billboard, 3 blocks across, in front of {@code anchor} along {@code dir}. */
    private static int testQuad(final Vec3d anchor, final Vec3d dir, final Vec3d camera) {
        // 方向不可用（退化弦 + 退化切线）时退回「朝相机」：探针不能被方向判定挡住。
        final Vec3d toCam = camera.subtract(anchor);
        Vec3d view = toCam.lengthSquared() < 1.0E-6D ? new Vec3d(0.0D, 0.0D, 1.0D) : toCam.normalize();
        final Vec3d axis = dir == null || dir.lengthSquared() < 1.0E-8D ? view : dir.normalize();
        final Vec3d center = anchor.add(axis.multiply(TEST_AHEAD));

        Vec3d right = view.crossProduct(UP);
        if (right.lengthSquared() < 1.0E-8D) {
            right = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        right = right.normalize().multiply(TEST_HALF);
        final Vec3d up = right.crossProduct(view).normalize().multiply(TEST_HALF);
        return testBillboard(center, right, up, camera, 1.0F, 0.0F, 1.0F, 0.85F);
    }

    /**
     * One lamp's quad. Returns {@code false} when the lamp is behind the camera, points away, or is
     * beyond the beam distance - i.e. when nothing was written.
     *
     * <p>Every value the size / alpha / depth questions depend on is computed <b>before</b> any gate
     * is applied and reported through {@link #nearAdd}, so a rejected lamp still shows up in the
     * {@code [lte] flare near:} line with the reason it was rejected ("far" / "back" / "dim" /
     * "ok"). That is the difference between "no light" and "no idea why no light".
     */
    private static boolean quad(final TrainLamps.Lamp lamp, final Vec3d camera, final Vec3d eye,
                                final double distance, final double base, final int minPx,
                                final float peakAlpha, final double fov, final int screenH) {
        // Camera-relative: the matrix stack already carries the camera rotation only, so the
        // subtraction below (inside billboard) is what puts the quad in front of the camera.
        final Vec3d toCam = camera.subtract(lamp.pos);
        final double camDist = toCam.length();
        if (camDist < 1.0E-3D) {
            return false;
        }
        final Vec3d view = toCam.multiply(1.0D / camDist);
        // 1 when the lamp looks straight at the camera, 0 edge-on, negative when it faces away.
        final double facing = -lamp.dir.dotProduct(view);
        final double facing3 = facing * facing * facing;
        double world = base * (0.45D + 1.4D * facing3);
        // Minimum on-screen size: scale the quad up until it covers minPx pixels of height.
        final double tanHalf = Math.tan(Math.toRadians(clamp(fov, 10.0D, 110.0D)) * 0.5D);
        double pixels = 0.0D;
        if (tanHalf > 1.0E-3D) {
            pixels = world / (2.0D * camDist * tanHalf) * screenH;
            if (pixels > 1.0E-6D && pixels < minPx) {
                world *= (double) minPx / pixels;
                pixels = minPx;
            }
        }
        final double d = lamp.pos.distanceTo(eye);
        final double fade = distanceFade(d, distance);
        // 亮度（朝向与灯的档位）先算，距离淡出最后乘：下限只抬"朝向/档位"这一项，
        // 绝不越过距离淡出 —— 否则灯晕会在灯火距离的边缘突然被截断成一条硬边。
        final double brightness = clamp(peakAlpha * lamp.alpha * (0.35D + 0.65D * facing3),
                0.0D, 1.0D);
        float alpha = (float) clamp(brightness * fade, 0.0D, 1.0D);

        String why;
        if (d > distance) {
            why = "far";
        } else if (facing < MIN_FACING) {
            why = "back";
        } else if (brightness <= 0.004D) {
            why = "dim";
        } else {
            why = "ok";
        }
        // 可见性下限：任何一盏通过朝向/距离/暗度判定的灯，都不允许以一个「数学上有、屏幕上没有」
        // 的透明度出场。0.55 * 0.35 = 0.19 的加色叠在亮地上仍然几乎看不出，这才是「灯晕看不见」
        // 里属于透明度的那一半。下限只抬「朝向 × 灯的档位」这一项，距离淡出照旧最后乘，
        // 所以灯火距离的边缘不会出现一条硬边。
        final boolean gateOk = "ok".equals(why);
        alpha = (float) clamp((gateOk ? Math.max(brightness, peakAlpha * MIN_ALPHA_SHARE) : brightness)
                * fade, 0.0D, 1.0D);
        if (gateOk && alpha <= 0.004F) {
            why = "faded";      // 距离淡出已经把它压到不可见，不必占一个四边形
        }
        final boolean ok = "ok".equals(why);
        // 诊断里报的是**最终写进顶点**的那个 alpha，不是中间值。
        nearAdd(camDist, facing, world, pixels, alpha, fade, why + "/" + lamp.source
                + (lamp.head ? ":head" : ":tail"));

        if (!ok) {
            return false;
        }

        // Billboard basis: screen-up is the world up, so a flare never rolls.
        final double pull = Math.min(MAX_PULL, camDist * PULL_FRACTION);
        final double cx = lamp.pos.x + view.x * pull;
        final double cy = lamp.pos.y + view.y * pull;
        final double cz = lamp.pos.z + view.z * pull;
        Vec3d right = view.crossProduct(UP);
        if (right.lengthSquared() < 1.0E-8D) {
            right = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        right = right.normalize().multiply(world * 0.5D);
        final Vec3d up = right.crossProduct(view).normalize().multiply(world * 0.5D);

        final float red = ((lamp.rgb >> 16) & 0xFF) / 255.0F;
        final float green = ((lamp.rgb >> 8) & 0xFF) / 255.0F;
        final float blue = (lamp.rgb & 0xFF) / 255.0F;
        billboard(new Vec3d(cx, cy, cz), right, up, camera, red, green, blue, alpha);
        return true;
    }

    /**
     * Keeps the {@value #NEAR_REPORT} closest lamps of this frame (by camera distance), rewritten
     * in place so nothing is allocated per frame.
     */
    private static void nearAdd(final double camDist, final double facing, final double world,
                                final double pixels, final float alpha, final double fade,
                                final String where) {
        if (nearWhere.length < NEAR_REPORT) {
            nearClear();
        }
        int slot = -1;
        for (int i = 0; i < NEAR_REPORT; i++) {
            if (nearWhere[i] == null || camDist < nearDist[i]) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        // shift the tail down so the table stays sorted by distance
        for (int i = NEAR_REPORT - 1; i > slot; i--) {
            nearDist[i] = nearDist[i - 1];
            nearFacing[i] = nearFacing[i - 1];
            nearWorld[i] = nearWorld[i - 1];
            nearPx[i] = nearPx[i - 1];
            nearAlpha[i] = nearAlpha[i - 1];
            nearFade[i] = nearFade[i - 1];
            nearWhere[i] = nearWhere[i - 1];
        }
        nearDist[slot] = camDist;
        nearFacing[slot] = facing;
        nearWorld[slot] = world;
        nearPx[slot] = pixels;
        nearAlpha[slot] = alpha;
        nearFade[slot] = fade;
        nearWhere[slot] = where;
    }

    /** Empties the near-lamp table (allocating it on first use); called once per frame. */
    private static void nearClear() {
        if (nearWhere == null || nearWhere.length < NEAR_REPORT
                || nearFacing.length < NEAR_REPORT || nearWorld.length < NEAR_REPORT
                || nearPx.length < NEAR_REPORT || nearAlpha.length < NEAR_REPORT
                || nearFade.length < NEAR_REPORT) {
            nearDist = new double[NEAR_REPORT];
            nearFacing = new double[NEAR_REPORT];
            nearWorld = new double[NEAR_REPORT];
            nearPx = new double[NEAR_REPORT];
            nearAlpha = new double[NEAR_REPORT];
            nearFade = new double[NEAR_REPORT];
            nearWhere = new String[NEAR_REPORT];
            return;
        }
        Arrays.fill(nearWhere, null);
    }

    /**
     * 把一块 billboard 的四个角写进 {@link #flareVerts}（相机相对）。
     */
    private static void billboard(final Vec3d center, final Vec3d right, final Vec3d up,
                                  final Vec3d camera, final float r, final float g, final float b,
                                  final float a) {
        final double rx = right.x;
        final double ry = right.y;
        final double rz = right.z;
        final double ux = up.x;
        final double uy = up.y;
        final double uz = up.z;
        // 相机相对：世界坐标减相机坐标。世界矩阵只带旋转，所以这一步是必须的，不是优化。
        final double px = center.x - camera.x;
        final double py = center.y - camera.y;
        final double pz = center.z - camera.z;

        pushFlare(px - rx - ux, py - ry - uy, pz - rz - uz, 0.0F, 1.0F, r, g, b, a);
        pushFlare(px - rx + ux, py - ry + uy, pz - rz + uz, 0.0F, 0.0F, r, g, b, a);
        pushFlare(px + rx + ux, py + ry + uy, pz + rz + uz, 1.0F, 0.0F, r, g, b, a);
        pushFlare(px + rx - ux, py + ry - uy, pz + rz - uz, 1.0F, 1.0F, r, g, b, a);
    }

    private static void pushFlare(final double x, final double y, final double z,
                                  final float u, final float v, final float r, final float g,
                                  final float b, final float a) {
        if (flareCount + FLARE_STRIDE > flareVerts.length) {
            flareVerts = Arrays.copyOf(flareVerts,
                    Math.max(FLARE_STRIDE * 4, flareVerts.length * 2 + FLARE_STRIDE));
        }
        int i = flareCount;
        final float[] out = flareVerts;
        out[i] = (float) x;
        out[i + 1] = (float) y;
        out[i + 2] = (float) z;
        out[i + 3] = u;
        out[i + 4] = v;
        out[i + 5] = r;
        out[i + 6] = g;
        out[i + 7] = b;
        out[i + 8] = a;
        flareCount = i + FLARE_STRIDE;
    }

    private static void pushTest(final double x, final double y, final double z,
                                 final float r, final float g, final float b, final float a) {
        if (testCount + TEST_STRIDE > testVerts.length) {
            testVerts = Arrays.copyOf(testVerts,
                    Math.max(TEST_STRIDE * 4, testVerts.length * 2 + TEST_STRIDE));
        }
        int i = testCount;
        final float[] out = testVerts;
        out[i] = (float) x;
        out[i + 1] = (float) y;
        out[i + 2] = (float) z;
        out[i + 3] = r;
        out[i + 4] = g;
        out[i + 5] = b;
        out[i + 6] = a;
        testCount = i + TEST_STRIDE;
    }

    /**
     * 探针版 {@link #billboard}：同样的四边形，写进 {@link #testVerts}（7 float / 顶点、无贴图），
     * 并且不计入灯晕的 {@code quads} 统计 —— {@code [lte] lighttest:} 那一行自己报数。
     */
    private static int testBillboard(final Vec3d center, final Vec3d right, final Vec3d up,
                                     final Vec3d camera, final float r, final float g,
                                     final float b, final float a) {
        final double px = center.x - camera.x;
        final double py = center.y - camera.y;
        final double pz = center.z - camera.z;
        pushTest(px - right.x - up.x, py - right.y - up.y, pz - right.z - up.z, r, g, b, a);
        pushTest(px - right.x + up.x, py - right.y + up.y, pz - right.z + up.z, r, g, b, a);
        pushTest(px + right.x + up.x, py + right.y + up.y, pz + right.z + up.z, r, g, b, a);
        pushTest(px + right.x - up.x, py + right.y - up.y, pz + right.z - up.z, r, g, b, a);
        LightTest.flareQuad();
        return 1;
    }

    /** 1 until {@link #FADE_FROM} of the beam distance, then smoothly down to 0 at the limit. */
    private static double distanceFade(final double d, final double limit) {
        if (limit <= 0.0D) {
            return 1.0D;
        }
        final double from = limit * FADE_FROM;
        if (d <= from) {
            return 1.0D;
        }
        if (d >= limit) {
            return 0.0D;
        }
        final double t = (d - from) / (limit - from);
        final double inv = 1.0D - t;
        return inv * inv;
    }

    /** The same beam-end heading rule the light bands use (body chord, tangent only as fallback). */
    private static Vec3d endDir(final TrainPose pose, final int car, final boolean head) {
        Vec3d d = pose.carBodyForward(car);
        if (d == Vec3d.ZERO) {
            d = pose.carForward(car);
        }
        if (d == Vec3d.ZERO) {
            return Vec3d.ZERO;
        }
        return head ? d : d.multiply(-1.0D);
    }

    /** Vertical field of view in degrees (the vanilla FOV option is vertical on 1.20.1). */
    private static double verticalFov(final MinecraftClient client) {
        try {
            final Object value = client.options.getFov().getValue();
            return value instanceof Number number ? number.doubleValue() : 70.0D;
        } catch (Throwable t) {
            return 70.0D;
        }
    }

    /** The same visibility gates the beam sheet used, so "no flare" always has a named reason. */
    private static boolean drawable(final HeadlightConfig cfg, final TrainClient train) {
        if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
            return false;
        }
        return !cfg.onlyTrains || train.transportMode == TransportMode.TRAIN;
    }

    private static List<TrainClient> snapshotTrains() {
        try {
            return new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * {@code [lte] flare: trains=… lamps=… quads=… noLamp=…} every 5 s, and only while the feature is on,
     * followed by one {@code [lte] flare near:} line for the closest lamps of the last frame.
     *
     * <p>{@code trains>0 lamps=0} is the interesting failure: the trains are there but no lamp could be
     * placed (see the {@code [lte] lights:} line from {@link VehicleLights#announce}). A non-zero
     * {@code quads} with nothing on screen is answered by the second line: {@code d} (camera distance),
     * {@code f} (facing), {@code w} (world size, blocks), {@code px} (screen pixels after the minimum-
     * size rule), {@code a} (final alpha) and the verdict ({@code ok} / {@code far} / {@code back} /
     * {@code dim}). {@code drawn=…} says whether the textured draw actually ran, {@code tex=…} whether
     * the flare texture exists and is uploaded, {@code err=…} the exception that stopped it.
     */
    private static void diag() {
        final long now = System.currentTimeMillis();
        if (now - diagAt < 5000L) {
            return;
        }
        diagAt = now;
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        // 先定格本窗口的 skipped=，无论下面打不打日志都要能回答指令；随后清零。
        lastSkipText = "skipped=offRoute=" + SKIP_OFF_ROUTE.size() + " dark=" + SKIP_DARK.size()
                + " parked=" + SKIP_PARKED.size();
        if (windowOn && cfg != null) {
            // style=… band=… flare=… 是这一窗口渲染层实际用的三个值（band/flare 就是
            // bandGlobal()/flareGlobal()，与帧级门槛同一次调用）。它能当场回答"切了样式为什么
            // 看不出区别"：数字变了 = 写入与判定都通，问题在几何（facing / 距离 / alpha）；
            // 数字没变 = 写入或同步那一步没生效。
            MtrHeadlightClient.LOGGER.info("[lte] flare: style={} band={} flare={} trains={}"
                            + " lamps={} quads={} noLamp={} {} drawn={} tex={}{}",
                    cfg.style(), Boolean.valueOf(cfg.bandGlobal()), Boolean.valueOf(cfg.flareGlobal()),
                    Integer.valueOf(dTrains), Integer.valueOf(dLamps), Integer.valueOf(dQuads),
                    Integer.valueOf(dNoLamp), lastSkipText, Boolean.valueOf(dDrawn), dTexture,
                    dDrawError == null ? "" : " err=" + dDrawError);
            /*
             * 一条「这条绘制真正用了什么 GL 状态」的取证行。每个字段都是 draw 之后从 GL 读回来的
             * （{@code glGetInteger}/{@code glIsEnabled}），不是我们调用过什么：
             *   quads      —— 本窗口最后一次 draw 写进去的四边形数；
             *   depthFunc  —— 光栅化那一刻的 GL_DEPTH_FUNC。LEQUAL 才说明「深度真的在挡」
             *                 （也就是不再穿墙）；出现 ALWAYS 就是别的模组把我们的强制覆盖回去了；
             *   blend      —— 真实混合：ADD = (SRC_ALPHA, ONE)；ALPHA = (SRC_ALPHA, 1-SRC_ALPHA)
             *                 （position_tex_color.json 自带的那个，bind 时盖掉我们的加色）；
             *                 off = 混合根本没开；
             *   tex        —— 灯晕贴图的状态（见 {@link #textureState}），后面跟着实际绑上去的
             *                 GL 纹理 id，0 表示槽位是空的；
             *   latePass   —— 这次 draw 是晚趟（AFTER_TRANSLUCENT）吗；
             *   passes     —— 两趟回调各自「被调 / 真的画了」几次，回答「谁在画」。
             */
            MtrHeadlightClient.LOGGER.info("[lte] flare draw: quads={} depthFunc={} blend={} cull={}"
                            + " tex={} latePass={} passes=early {}/{} late {}/{} texId={} entered={}"
                            + " pack={}{}",
                    Integer.valueOf(dDrawQuads), funcName(dDrawDepthFunc), blendName(),
                    dDrawCull ? "on" : "off", dTexture, Boolean.valueOf(dDrawLatePass),
                    Integer.valueOf(dEarlyOffers), Integer.valueOf(dEarlyDraws),
                    Integer.valueOf(dLateOffers), Integer.valueOf(dLateDraws),
                    Integer.valueOf(dDrawTextureId), funcName(dDrawDepthEntry),
                    Boolean.valueOf(dPackActive),
                    dDrawForceOcclusion ? "" : " (forceOcclusion=false: the depth func is whatever "
                            + "the previous caller left!)");
            // 探针（lighttest）走的是 lightTest()，near 表这一帧没更新；那条线自己报 flareQuads，
            // 这里不重复打一行过期的数。
            if (!dTestWindow) {
                final StringBuilder near = new StringBuilder();
                for (int i = 0; i < NEAR_REPORT; i++) {
                    if (nearWhere[i] == null) {
                        continue;
                    }
                    if (near.length() > 0) {
                        near.append(" | ");
                    }
                    near.append('#').append(i)
                            .append(" d=").append(fmt(nearDist[i]))
                            .append(" f=").append(fmt(nearFacing[i]))
                            .append(" w=").append(fmt(nearWorld[i]))
                            .append(" px=").append(fmt(nearPx[i]))
                            .append(" a=").append(fmt(nearAlpha[i]))
                            .append(" fade=").append(fmt(nearFade[i]))
                            .append(' ').append(nearWhere[i]);
                }
                MtrHeadlightClient.LOGGER.info("[lte] flare near: {}", near.length() == 0
                        ? "(no lamp reached the size/alpha stage)" : near.toString());
            }
        }
        windowOn = false;
        dTrains = 0;
        dLamps = 0;
        dQuads = 0;
        dNoLamp = 0;
        SKIP_OFF_ROUTE.clear();
        SKIP_DARK.clear();
        SKIP_PARKED.clear();
        dDrawn = false;
        dDrawError = null;
        dDrawQuads = 0;
        dDrawDepthFunc = -1;
        dDrawDepthEntry = -1;
        dDrawBlendSrc = -1;
        dDrawBlendDst = -1;
        dDrawBlend = false;
        dDrawCull = false;
        dDrawTextureId = -1;
        dDrawLatePass = false;
        dEarlyOffers = 0;
        dEarlyDraws = 0;
        dLateOffers = 0;
        dLateDraws = 0;
    }

    /** 把 {@link GlowCompat} 读回的 GL 状态抄进本窗口的取证字段（一次 draw 一组）。 */
    private static void captureDraw(final int quads, final boolean latePass) {
        dDrawQuads = quads;
        dDrawDepthFunc = GlowCompat.diagDepthFunc;
        dDrawDepthEntry = GlowCompat.diagDepthFuncOnEntry;
        dDrawBlendSrc = GlowCompat.diagBlendSrc;
        dDrawBlendDst = GlowCompat.diagBlendDst;
        dDrawBlend = GlowCompat.diagBlendEnabled;
        dDrawCull = GlowCompat.diagCullEnabled;
        dDrawTextureId = GlowCompat.diagTextureId;
        dDrawForceOcclusion = GlowCompat.diagForceOcclusion;
        dDrawLatePass = latePass;
        if (latePass) {
            dLateDraws++;
        } else {
            dEarlyDraws++;
        }
    }

    /** {@code GL_ALWAYS} / {@code GL_LEQUAL} / … / {@code 0x…}，-1 是「这一帧没画」。 */
    private static String funcName(final int func) {
        switch (func) {
            case -1:
                return "none";
            case 0x0207:
                return "ALWAYS";
            case 0x0201:
                return "LEQUAL";
            case 0x0203:
                return "LESS";
            case 0x0204:
                return "GREATER";
            case 0x0205:
                return "GEQUAL";
            case 0x0200:
                return "NEVER";
            case 0x0202:
                return "EQUAL";
            case 0x0206:
                return "NOTEQUAL";
            default:
                return "0x" + Integer.toHexString(func);
        }
    }

    /** 真实混合 = (0x302 SRC_ALPHA, 0x1 ONE) → ADD；= (0x302, 0x303) → ALPHA；没开 → off。 */
    private static String blendName() {
        if (!dDrawBlend) {
            return "off";
        }
        if (dDrawBlendSrc == 0x0302 && dDrawBlendDst == 0x0001) {
            return "ADD";
        }
        if (dDrawBlendSrc == 0x0302 && dDrawBlendDst == 0x0303) {
            return "ALPHA";
        }
        return "0x" + Integer.toHexString(dDrawBlendSrc) + ":0x" + Integer.toHexString(dDrawBlendDst);
    }

    /** {@code ok} / {@code missing} / {@code notuploaded} / {@code error:X} for the flare texture. */
    private static String textureState(final MinecraftClient client) {
        try {
            if (client.getResourceManager().getResource(TEXTURE).isEmpty()) {
                return "missing";
            }
            final net.minecraft.client.texture.AbstractTexture texture =
                    client.getTextureManager().getTexture(TEXTURE);
            return texture != null && texture.getGlId() != -1 ? "ok" : "notuploaded";
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    private static String fmt(final double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(value));
    }

    private static double clamp(final double value, final double min, final double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return value < min ? min : (value > max ? max : value);
    }

    private static String firstLine(final Throwable t) {
        final String text = t == null ? "unknown" : String.valueOf(t.getMessage());
        final int newline = text.indexOf('\n');
        final String head = newline < 0 ? text : text.substring(0, newline);
        return head.length() > 120 ? head.substring(0, 120) : head;
    }
}
