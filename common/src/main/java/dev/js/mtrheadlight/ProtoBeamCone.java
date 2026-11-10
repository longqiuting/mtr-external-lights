package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.client.DynamicTrainModel;
import mtr.data.TrainClient;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The volumetric head/tail light cone: one beam from the lamp the binding found, plus a fixed
 * "probe" cone in front of the camera when explicitly enabled. The probe exists so that
 * "nothing drawn" can be told apart from "no train / no model / no slot" (see the
 * render-diagnosis skill: existence probe first, parameters second).
 *
 * <p>Where the lamp is comes from {@link VehicleLights}: a group matched by name and checked
 * against the geometry, or - when the model names no lamp at all - an estimate from the car size.
 * This class only turns that binding into world space, per car, with the car's own face and the
 * car's own tangent.
 *
 * <p><b>Geometry source (control mode).</b> {@code beamConeSource = GLOW} replaces that binding
 * entirely: the cone takes the <em>same</em> {@link BeamGeom} the additive sheet
 * ({@link BeamGlow}) draws ({@code base / axis / length / height / colour}, built by the same
 * factory and the same {@link BeamGlow#startHeight} rule) and never reads {@link VehicleLights} or
 * the dynamic train model. The two then have to coincide at the lamp, so "cone not on the train"
 * splits into two testable cases: GLOW visible = the binding was wrong; GLOW still invisible =
 * the cone's own render path cannot draw a real beam. Switch live with
 * {@code /mtr_lte debug coneSource model|glow|status}.
 *
 * <p>Draw path: {@link GlowCompat#draw} - additive blend ({@code SRC_ALPHA, ONE}), depth test on,
 * depth write off, no culling, camera-relative coordinates. Same path as the existing beam sheet,
 * which is known to work under Iris on 1.20.1.
 *
 * <h2>Why it is four nested shells and not one cone</h2>
 * A single cone surface can only carry one alpha per ring, so the beam is a uniformly bright
 * shell - which is exactly what reads as "a sheet of plastic". Density, on the other hand, is
 * highest on the <em>axis</em> and zero at the rim: a sight line through the middle crosses every
 * shell, a sight line at the rim crosses only the outermost one. So the cone is built as four
 * concentric shells and the radial profile {@code (1 - r^2)^2} is split into per-shell weights
 * (see {@link #shellWeights}) - the sum of the shells a ray crosses then reproduces the profile.
 * The outermost shell carries only ~7% of the alpha, which is what makes the silhouette soft
 * instead of a drawn-on polygon edge.
 *
 * <p>Three attenuations multiply, all evaluated per vertex:
 * <ol>
 *   <li><b>longitudinal</b> {@code exp(-2.5 t) * (1 - t)}, t = 0 at the lamp, 1 at the tip: reaches
 *       exactly 0 at the tip, so the beam dissolves instead of ending on a line;</li>
 *   <li><b>radial</b> {@code (1 - r^2)^2}, expressed through the shell weights (r = 0 axis, 1 rim);</li>
 *   <li><b>view angle</b> {@code 0.45 + 0.55 * |n.v|^1.5}: a surface seen edge-on is faint, one
 *       facing the camera is not. This is what gives the fake-volume read - the middle of the beam
 *       is bright and the silhouette fades out on its own.</li>
 * </ol>
 *
 * <p><b>Floor (why the beam used to be invisible):</b> radial × view × ground multiply to less than
 * 0.01 at the darkest vertex (outermost shell 0.068, grazing view 0.22, ground clearance 0.12), so
 * with a 0.16 peak the drawn alpha was ~0.0026 - numerically present, visually black. The product is
 * now floored at {@link #MIN_ATTENUATION} (0.25) and the peak default is 0.30, so the darkest vertex
 * is ~0.075 and still reaches 0 at the tip via the longitudinal term alone.
 * {@code /mtr_lte debug coneBoost on} replaces all four factors with the probe's (0.45 flat, no
 * attenuation) to prove in one frame whether "invisible" means "too faint".
 *
 * <p><b>Ground soft intersection:</b> every vertex is faded with smoothstep over the clearance to
 * the surface below it (1 block), so the beam sinks into the deck / ground instead of being sliced
 * off by it. The surface comes from a short downward block scan, not from the world heightmap (the
 * heightmap inside a tunnel is the roof above the beam) and not from the depth attachment: it costs
 * a few block lookups per cone, works identically with and without a shader pack, and touches no
 * Iris internals. Under a pack the beam therefore also ends via the longitudinal term - the "no
 * depth texture" degradation the prototype is allowed.
 */
public final class ProtoBeamCone {
    /** Cone half angle. 13 deg, inside the requested 12-15 deg band. */
    private static final double HALF_ANGLE_DEG = 13.0D;
    /** Radial segments; 14 is enough for a smooth silhouette at these sizes. */
    private static final int SEGMENTS = 14;
    /**
     * Rings along the cone. Seven (six bands) instead of six: an exponential ramp is steep near the
     * lamp, and a coarse ring spacing turns that into visible horizontal banding.
     */
    private static final double[] RINGS = {0.03D, 0.14D, 0.30D, 0.48D, 0.68D, 0.85D, 1.0D};
    /** Nested shells as fractions of the cone radius, outermost first. */
    private static final double[] SHELLS = {1.0D, 0.72D, 0.45D, 0.18D};
    /** Per-shell alpha weights that make the shells sum to the radial profile (see shellWeights). */
    private static final float[] SHELL_ALPHA = shellWeights(SHELLS);
    /** Longitudinal falloff rate in {@code exp(-LENGTH_FALLOFF * t)}. */
    private static final double LENGTH_FALLOFF = 2.5D;
    /** Exponent of the view-angle softening; 1.5 keeps it soft without flattening the core. */
    private static final double VIEW_POWER = 1.5D;
    /** Floor of the view-angle term, so a beam pointing at the camera does not vanish. */
    private static final double VIEW_FLOOR = 0.45D;
    /**
     * 径向 × 视角 × 地面 的<b>乘积下限</b>：任何顶点的有效权重不低于峰值预算的这个比例。
     *
     * <p>以前三者相乘没有任何下限：最外层壳只有 0.068 的径向份额，掠射时视角项 0.22，贴着
     * 地面时地面项 0.12 —— 0.16 × 0.172 × 0.9 × 0.22 × 0.12 ≈ 0.0026，加色叠加出来仍然是黑的。
     * 0.25 的下限保证"最暗处也可辨"，但仍然让纵向项（{@link #fade}）一路衰减到 0，所以光柱
     * 还是会在尖端化掉，而不是变成一个硬边的管子。
     *
     * <p>代价：外两层壳被抬到 0.25，横截面比纯 {@code (1 - r^2)^2} 平一些；锥的边缘柔和度由视角
     * 项（0.45–1.0）和纵向项承担。
     */
    private static final float MIN_ATTENUATION = 0.25F;
    /** Blocks of clearance below which a vertex fades into the ground. */
    private static final double GROUND_FADE_BLOCKS = 1.0D;
    /** How many blocks below a ring to look for the surface it fades into. */
    private static final int GROUND_SCAN_BLOCKS = 3;
    private static final int VERTEX_STRIDE = 7;
    /**
     * Draw the fixed probe cone in front of the camera. Off by default: it is a diagnostic for
     * "nothing is drawn at all", not part of the look. Enable with {@code -Dlte.coneprobe=1} when
     * a render report needs to tell "no train / no model" from "pipeline broken".
     */
    private static final boolean PROBE = Boolean.getBoolean("lte.coneprobe");
    /** Length of the probe cone, blocks. */
    private static final double PROBE_LENGTH = 7.0D;
    /**
     * 运行时探针（{@code /mtr_lte debug cone on}）的固定参数：玩家水平前方 6 格、与眼平齐的
     * 顶点，轴 = 水平视线向下 12°，长度 12，半角 20°（末端半径 ≈ 4.37），每层壳固定 alpha 0.45、
     * 纯白。这些数字刻意写死、不读配置，也不读列车/车型/灯位：它只回答"这条画光的管道通不通"。
     */
    private static final double DEBUG_CONE_AHEAD = 6.0D;
    private static final double DEBUG_CONE_LENGTH = 12.0D;
    private static final double DEBUG_CONE_HALF_ANGLE_DEG = 20.0D;
    private static final double DEBUG_CONE_TILT_DEG = 12.0D;
    private static final float DEBUG_CONE_ALPHA = 0.45F;
    /** Beams handed to the depth-aware renderer; reused, never reallocated per frame. */
    private static final List<ConeVolumeBeam> BEAMS = new ArrayList<>();
    /**
     * 距离闸门的<b>兜底</b>值（块）。真正生效的距离是 {@code cfg.beamDistance}
     * （逐车取 {@link HeadlightConfig.Effective#beamDistance}），和 {@code BeamGlow} 用的是
     * 同一个数 —— 这里以前写死 64，于是配置 128 时 64~128 格之间的车有光带、没有光柱。
     */
    private static final double MAX_BEAM_DISTANCE = 64.0D;
    /** 距离闸门的上下限（块）：配置值先钳在这里再比较。 */
    private static final double MIN_BEAM_DISTANCE = 16.0D;
    private static final double MAX_BEAM_DISTANCE_LIMIT = 256.0D;
    /** Hard cap on beams handed to the depth-aware renderer per frame (probe included). */
    private static final int MAX_CONE_BEAMS = 8;
    /** Collection bound before the renderer keeps the nearest {@link #MAX_CONE_BEAMS}. */
    private static final int MAX_BEAM_CANDIDATES = 64;

    private static float[] vertices = new float[0];
    private static int vertexCount;
    /** Reused ground-height scratch, one entry per ring; grown once, never per frame. */
    private static double[] groundScratch = new double[0];
    /** Reused block position for the ground scan - no allocation per ring per frame. */
    private static final BlockPos.Mutable GROUND_POS = new BlockPos.Mutable();

    /** Parsed layouts live in {@link VehicleLights} and are cached there by model identity. */


    private static long lastLog = System.currentTimeMillis();
    private static int cCalls;
    private static int cTrains;
    private static int cModels;
    private static int cNoModel;
    private static int cNoSlot;
    private static int cDrawn;
    private static int cQuads;
    private static int cProbe;
    private static int cProbeQuads;
    private static int cVolumeBeams;
    private static int cNear;
    /** 本窗口最近的一条光束 apex 到眼睛的距离（块）；一个候选都没有时是 MAX_VALUE。 */
    private static double cDmin = Double.MAX_VALUE;
    /** 本窗口生效的距离闸门（块），日志里的 {@code range=}。 */
    private static double cRange;
    /**
     * GLOW 源下已经打过 {@code [lte] coneSource glow:} 那一行的列车。每列车只打一次（首帧），
     * 切回 MODEL / 再切进 GLOW 时由 {@link #clearGlowLogged()} 清空。
     */
    private static final java.util.Set<Long> GLOW_LOGGED = new java.util.HashSet<>();
    /** 上一帧是否 GLOW 源：变化时清空 {@link #GLOW_LOGGED}，与命令/界面/JSON 的改法无关。 */
    private static boolean glowLast;
    /**
     * 已经打过 {@code [lte] cone beam:} 那一行的光束（key = 列车|端|灯序号）。每束只打一次，
     * 打的就是它这一帧真实的参数（alphaPeak / len / radius / apex / axis），用来一次性看死
     * "画了 10080 个四边形却看不见"到底是几何退化还是 alpha 太小。
     */
    private static final java.util.Set<String> BEAM_LOGGED = new java.util.HashSet<>();
    /** 上一帧是否 coneBoost：切换时清空 {@link #BEAM_LOGGED}，切进/切出都重新打一遍。 */
    private static boolean boostLast;

    /**
     * 逐道闸门的命中数，索引 1..9（0 不用），窗口内计数，5 秒线打印后清零：
     * <ol>
     *   <li><b>g1</b> {@code cfg.enabled == false} —— 总开关关着，一条都不画（帧级 return）；</li>
     *   <li><b>g2</b> 光柱开关全关（{@code !beamCone && !beamConeAnySiding()}，帧级 return）；</li>
     *   <li><b>g3</b> 这列车没有可读的动态车型（{@code modelOf == null}）；</li>
     *   <li><b>g4</b> 车型解析不出头/尾灯位（{@code layout == null} 或 head/tail 为 null）；</li>
     *   <li><b>g5</b> 这条侧线把光柱关了（{@code eff.beamCone == false}）；</li>
     *   <li><b>g6</b> 位姿为空（{@code cars <= 0} 或车节切线退化成零向量）；</li>
     *   <li><b>g7</b> 光束 apex 超出距离闸门（{@code range}，见 {@link #beamRange}）；</li>
     *   <li><b>g8</b> 名额已满 / 参数退化（length、radius、alpha 小到画不出东西）；</li>
     *   <li><b>g9</b> 这条车自己不发车灯（未出库 / 自动关灯）—— 光带本来就没画，光柱也不画。
     *       <b>两种源都过这道闸</b>（{@code coneSource} 是 MODEL 还是 GLOW 无关），判定与
     *       {@link BeamGlow} / {@link LightController} 是同一个 {@link HeadlightSwitch#isLit}
     *       加同一个 {@code train.id} 去抖键，所以光带、动态光源、光柱不会出现「一半亮」。</li>
     * </ol>
     * {@code beams>0} 时它们应全为 0；{@code beams==0} 时哪一个不为 0，问题就在哪一道闸。
     * {@code GLOW} 源下 g3 / g4 一定为 0（那条路根本不读车型灯位）。
     */
    private static final int[] WC = new int[10];
    /** 同上，单调累计版：{@link DebugProbe} 用自己的节拍差值打印，不受 render 早退影响。 */
    private static final long[] GATE = new long[10];

    /** 单调累计：诊断行（{@link DebugProbe} 每 5 秒）用差值，和上面按窗口清零的计数互不干扰。 */
    private static long callsTotal;
    private static long quadsTotal;
    /** 探针锥写进顶点缓冲的四边形数（单独计，不再混进 {@link #quadsTotal}）。 */
    private static long probeQuadsTotal;
    /** 交给 raymarch 渲染器的光束数（这条路上本来就没有四边形，所以 coneQuads 恒 0）。 */
    private static long volumeBeamsTotal;
    /** 上一帧真正发出去的车灯光束数（每盏灯一条），诊断行里的 {@code beams=}。 */
    private static int liveBeams;

    /** 渲染回调被调用的总次数（含不画的那个 pass），诊断行里的 coneCalls。 */
    public static long callsTotal() {
        return callsTotal;
    }

    /** 真正写进顶点缓冲的四边形总数（<b>只算车灯光柱</b>），诊断行里的 coneQuads。 */
    public static long quadsTotal() {
        return quadsTotal;
    }

    /** 探针锥的四边形总数，诊断行里的 probeQuads。 */
    public static long probeQuadsTotal() {
        return probeQuadsTotal;
    }

    /** 交给 raymarch 的光束总数，诊断行里的 volumeBeams（这条路上没有四边形）。 */
    public static long volumeBeamsTotal() {
        return volumeBeamsTotal;
    }

    /** 闸门 {@code index}（1..8）的单调命中数，见 {@link #WC} 的说明。 */
    public static long gateTotal(final int index) {
        return index >= 1 && index < GATE.length ? GATE[index] : 0L;
    }

    /** 上一帧发出的光束数（头/尾各算一条，探针不算）。 */
    public static int liveBeams() {
        return liveBeams;
    }

    /** 本窗口最近一条光束 apex 到眼睛的距离（块，已开方）；没有候选时是 {@code -1}。 */
    public static double nearestBeamDistance() {
        return cDmin == Double.MAX_VALUE ? -1.0D : cDmin;
    }

    /** 本窗口生效的距离闸门（块）。 */
    public static double beamRange() {
        return cRange;
    }

    /** 记一次闸门命中：窗口计数 + 单调计数各一份，{@code index} 见 {@link #WC}。 */
    private static void gate(final int index) {
        GATE[index]++;
        WC[index]++;
    }

    private ProtoBeamCone() {
    }

    public static void render(final WorldRenderContext context, final boolean latePass) {
        cCalls++;
        callsTotal++;
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.gameRenderer == null) {
            return;
        }
        // Exactly one pass draws, same rule as BeamGlow: Iris composites after translucent.
        final boolean shaders;
        try {
            shaders = ShaderPackProbe.active();
        } catch (Throwable t) {
            return;
        }
        if (shaders != latePass) {
            return;
        }
        // 这个挂载点在不同渲染器下可能没有 matrixStack（Iris / ImmediatelyFast 下见过）。
        // 我们的顶点本来就是"相机相对坐标"（下面每处都减了 camera 位移），所以自建一个
        // 单位矩阵即可，不能再 return —— 之前 debug cone 画不出来就是死在这一次 return。
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            matrices = new MatrixStack();
        }
        final Vec3d shift = client.gameRenderer.getCamera().getPos();
        final Vec3d eye = client.player.getEyePos();
        final ClientWorld world = client.world;
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        // --- 运行时可见探针（/mtr_lte debug cone on），故意放在配置闸之前 ------------------
        // 它存在的意义就是在"整个模组看起来没工作"的时候画出一条光：所以不看 enabled /
        // beamCone / 侧线开关，也不读任何列车或车型数据。固定走加色壳层，而不是 depth-aware
        // 的 raymarch（那条路自己就可能是坏的那一段）。
        if (DebugProbe.coneEnabled()) {
            debugCone(matrices, shift, eye, client.player.getRotationVec(1.0F));
        }
        // 每帧重置；被配置闸挡住时 beams=0 就是事实（这一帧一条都没画）。
        liveBeams = 0;
        // 光柱自己的开关（与光带 beamGlow 独立）：关掉 = 一条锥都不画，也不做 raymarch 提交；
        // 总开关仍然在它之上，所以 enabled=false 时两者一起停。某条侧线单独打开时不能在这里
        // 提前 return，否则那条侧线的光柱永远画不出来（逐车过滤在下面的循环里）。
        if (cfg != null && !cfg.enabled) {
            gate(1);
            return;
        }
        if (cfg != null && !cfg.beamCone && !cfg.beamConeAnySiding()) {
            gate(2);
            return;
        }
        // 距离闸门：和光带 BeamGlow 用同一个配置（cfg.beamDistance / 逐车 eff.beamDistance），
        // 不再写死 64 —— 写死的时候配置 128 的车在 64~128 格上只有光带没有光柱。
        final double range = beamRange(cfg);
        /**
         * 尾灯光柱的显式开关（默认关）：车辆模型上的尾灯位仍然解析出来（动态光与地面光带要用），
         * 但<b>不再自动</b>画光柱 —— 用户看到的是「尾灯也被同步画了光柱」，而真正的尾灯只在刹车/
         * 驻车时亮，常亮的尾灯光柱本身就是错的。
         */
        final boolean tailCones = cfg != null && cfg.coneOnTail;
        // Prototype peak: its own knob, clamped hard. Default 0.30 after the "invisible at 0.16"
        // report: the attenuators below multiply it down by two orders of magnitude, so the budget
        // has to start high enough that the darkest vertex is still on screen.
        // coneBoost (/mtr_lte debug coneBoost on) replaces the whole model with the probe's: 0.45,
        // no longitudinal / radial / view / ground attenuation - the "is it just too faint" test.
        final boolean boost = DebugProbe.coneBoostEnabled();
        if (boost != boostLast) {
            boostLast = boost;
            BEAM_LOGGED.clear();
        }
        final float peak = boost ? DEBUG_CONE_ALPHA
                : cfg == null ? 0.30F
                : (float) Math.max(0.02D, Math.min(0.5D, cfg.beamConeAlpha));
        final double length = cfg == null ? 16.0D : Math.max(4.0D, cfg.beamHeadLength);
        /** 没有侧线覆盖时的采样数（也是诊断探针用的那一档）。 */
        final int defaultSteps = cfg == null ? 10 : cfg.beamConeSteps();

        // Depth-aware path: when it is available, beams are handed to the version-local
        // renderer (a raymarched volume on 1.20.1) instead of being expanded into shell quads.
        // Under a shader pack / with occlusion switched off / on the older versions it answers
        // false and everything below stays exactly as it was.
        // 渲染路径：默认壳层锥（{@code beamConeVolume=false}），和 {@code /mtr_lte debug cone}
        // 探针走同一条已被用户实机验证可见的管道。raymarch 只有在配置显式打开时才跑，而且它自己
        // 依旧可能在 shader pack / GL 失败时退回壳层（那时 volume=false，下面逐车判定）。
        final boolean volume = cfg != null && cfg.beamConeVolume
                && ConeVolumeRenderer.ready(cfg);
        BEAMS.clear();

        vertexCount = 0;

        // --- probe cone: exists regardless of train data -------------------------------------
        if (PROBE) {
            final Vec3d look = client.player.getRotationVec(1.0F).normalize();
            // Point the probe ACROSS the view, not away from the camera: a cone whose axis is the
            // view axis is seen end-on and reads as a fuzzy disc, which proves nothing about shape.
            final Vec3d right = look.crossProduct(new Vec3d(0.0D, 1.0D, 0.0D)).normalize();
            final Vec3d apex = eye.add(look.multiply(1.6D)).subtract(right.multiply(1.3D));
            final Vec3d dir = right.multiply(0.92D).add(look.multiply(0.4D))
                    .add(0.0D, -0.18D, 0.0D).normalize();
            // Slightly above the train peak on purpose: the probe is a diagnostic, and a beam that
            // is too faint to find would prove nothing about depth test / blending.
            emit(volume, true, apex, dir, PROBE_LENGTH, 1.3D, 0xFFE9C0,
                    Math.min(0.5F, peak * 1.4F), defaultSteps, range, false, null, shift, eye, world);
            cProbe++;
        }

        // --- real cones ----------------------------------------------------------------------
        // GLOW 对照模式：整条路不读 VehicleLights / DynamicTrainModel，几何直接来自光带
        // （BeamGeom + BeamGlow.startHeight），所以光柱的灯口必然和光带重合。
        final boolean glowSource = cfg != null && cfg.coneSourceGlow();
        // 不管来源是怎么变的（命令、配置界面、手改 JSON 后 reload），切进/切出 GLOW 都重打一遍:
        if (glowSource != glowLast) {
            glowLast = glowSource;
            GLOW_LOGGED.clear();
        }
        for (final TrainClient train : snapshotTrains()) {
            cTrains++;
            if (train == null || train.isRemoved || train.path.isEmpty()) {
                continue;
            }
            final HeadlightConfig.Effective eff = cfg == null ? null : cfg.effectiveFor(train.sidingId);
            VehicleLights.Layout layout = null;
            if (!glowSource) {
                final DynamicTrainModel model = VehicleLights.modelOf(train);
                if (model == null) {
                    // Built-in trains have no DynamicTrainModel; the hardcoded table may still
                    // provide their lamp slots.
                    layout = VehicleLights.layoutFor(train);
                    if (layout == null) {
                        cNoModel++;
                        gate(3);
                        continue;
                    }
                } else {
                    cModels++;
                    layout = VehicleLights.layoutFor(train);
                }
                if (layout == null || layout.head == null || layout.tail == null) {
                    cNoSlot++;
                    gate(4);
                    continue;
                }
            }
            // 这条侧线关掉光柱：这一列车一条锥都不发（全局那一层已经在上面放行）。
            if (eff != null && !eff.beamCone) {
                gate(5);
                continue;
            }
            // 距离闸门逐车解析：侧线可以自己覆盖 beamDistance，和 BeamGlow 的判定同源。
            final double trainRange = eff == null ? range : clampRange(eff.beamDistance);
            final float trainPeak = boost ? DEBUG_CONE_ALPHA
                    : eff == null ? peak : (float) eff.beamConeAlpha;
            final int steps = eff == null ? defaultSteps : eff.beamConeSteps;
            // 逐车决定走哪条渲染路径：raymarch 需要这辆车的侧线允许遮挡，否则退回加色壳层锥。
            // 同一帧里两条路可以并存（volume=true 时 BEAMS 走 raymarch，壳层顶点单独画）。
            final boolean trainVolume = volume && (eff == null || eff.beamConeOcclusion);
            final double headLength = eff == null ? length : Math.max(4.0D, eff.beamHeadLength);
            final double tailLength = eff == null ? 4.0D : Math.max(2.0D, eff.beamTailLength);
            final int headColor = eff == null ? 0xFFF3D6 : eff.beamHeadColor;
            final int tailColor = eff == null ? 0xFF2A1A : eff.beamTailColor;

            // Same direction fold as LightController (reversal XOR swapEnds), so the drawn beam and
            // the dynamic light can never disagree about which end is the nose.
            final boolean reversed = DepartureState.reversed(train, train.isReversed())
                    ^ (cfg != null && cfg.swapEnds);
            final TrainPose pose = TrainPose.ofShared(train, reversed);
            final Vec3d nose = pose.front();
            final Vec3d rear = pose.rear();
            if (nose == Vec3d.ZERO && rear == Vec3d.ZERO) {
                gate(6);
                continue;
            }
            final int cars = pose.cars();
            if (cars <= 0) {
                gate(6);
                continue;
            }
            // 最近距离按光束 apex 判定的同一个点算（车头边界），日志里的 dmin 就是它：
            // dmin > range 时 beams=0 是"这条线上一列车都不在射程内"，不是管道坏了。
            final double noseDist = Math.sqrt(nose.squaredDistanceTo(eye));
            if (noseDist < cDmin) {
                cDmin = noseDist;
            }
            if (noseDist <= trainRange) {
                cNear++;
            }

            // 车灯没亮就不画光柱 —— 与 BeamGlow / LightController / 轨道烘焙问的是同一个
            // HeadlightSwitch.isLit，并且带同一个 train.id 去抖键，所以光带、动态光源、轨道
            // 烘焙、光柱对"这列车现在亮不亮"只能是同一个答案。
            //
            // 这条闸以前只写在下面的 GLOW 源分支里，而 coneSource 默认是 MODEL：于是白天露天
            // 自动关灯后光带灭了、动态光源摘了，光柱还挂在车头上 —— 就是"光柱没有跟着同步"。
            // 现在提到分支外，两条源共用（诊断行里记在 g9）。
            if (!DepartureState.departed(train)
                    || !HeadlightSwitch.isLit(world, pose, cfg, train.id)) {
                gate(9);
                continue;
            }
            final int before = vertexCount;
            final int beamsBefore = BEAMS.size();
            if (glowSource) {
                // 与 BeamGlow 完全相同的输入：同一个 headGeom/tailGeom 工厂（同一个 BeamGeom），
                // 同一个 endDir（车身弦优先，切线只作退化兜底）+ 同一个 startHeight，offset /
                // length / height / colour 也全部是光带那一组 —— 所以 GLOW 源下光柱与光带是
                // 同一个几何对象，灯口和方向逐位重合（glowSource 蕴含 cfg != null，故 eff 非空）。
                final BeamGeom headGeom = BeamGlow.headGeom(cfg, eff, pose);
                final BeamGeom tailGeom = BeamGlow.tailGeom(cfg, eff, pose);
                final Vec3d headApex = headGeom.apex(world);
                final Vec3d tailApex = tailGeom.apex(world);
                logGlowOnce(train.id, headGeom, headApex, tailGeom, tailApex);
                liveBeams += emit(trainVolume, false, headApex, headGeom.axis, headGeom.length,
                        coneRadius(headGeom.length, true), headGeom.rgb, trainPeak, steps,
                        trainRange, boost, "train=" + train.id + " end=head lamp=0",
                        shift, eye, world) ? 1 : 0;
                if (tailCones) {
                    liveBeams += emit(trainVolume, false, tailApex, tailGeom.axis, tailGeom.length,
                            coneRadius(tailGeom.length, false), tailGeom.rgb, trainPeak * 0.55F,
                            steps, trainRange, boost, "train=" + train.id + " end=tail lamp=0",
                            shift, eye, world) ? 1 : 0;
                }
            } else {
                // Per car: the head beam leaves the leading face of the leading car, the tail beam
                // the trailing face of the trailing car, each along THAT car's own body pose. The
                // lamp offsets come from the car-local section of the model, never from the model
                // bbox. The tail end only draws when coneOnTail is explicitly on.
                //
                // 如果这个车型有一份完整的光束表（资源包里的 assets/<ns>/beam3D/<id>.json，
                // 或编辑器刚改的运行时层），就照表画：每束自带位置 / 方向 / 长度 / 粗细 /
                // 颜色 / 透明度。没有才退回「自动识别的一头一尾」——两条路互斥，否则
                // 「文件里 4 束」会变成 6 束（自动那一对还在）。
                final List<VehicleLightOverrides.Resolved> declared =
                        VehicleLightOverrides.resolvedDeclared(vehicleIdOf(train), layout);
                if (!declared.isEmpty()) {
                    liveBeams += emitDeclared(trainVolume, pose, cars, declared, reversed, steps,
                            trainRange, train.id, boost, tailCones, shift, eye, world);
                } else {
                    liveBeams += emitSlot(trainVolume, pose, 0, true, layout.head, reversed, headLength,
                            headColor, trainPeak, steps, trainRange, train.id, boost,
                            shift, eye, world);
                    if (tailCones) {
                        liveBeams += emitSlot(trainVolume, pose, cars - 1, false, layout.tail, reversed,
                                tailLength, tailColor, trainPeak * 0.55F, steps, trainRange, train.id,
                                boost, shift, eye, world);
                    }
                }
            }
            if (vertexCount > before || BEAMS.size() > beamsBefore) {
                cDrawn++;
            }
        }

        if (volume) {
            try {
                ConeVolumeRenderer.render(BEAMS, matrices, cfg);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] cone volume failed", t);
            }
        }
        // 关闭了深度遮挡的侧线这一帧走的是壳层锥：它们的顶点在 volume=true 时也会被收集，
        // 所以两条路要在同一帧里各画各的（以前这里是 else if，壳层顶点会被丢掉）。
        if (vertexCount > 0) {
            try {
                GlowCompat.draw(vertices, vertexCount, matrices);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] cone draw failed", t);
            }
            vertexCount = 0;
        }

        final long now = System.currentTimeMillis();
        if (now - lastLog >= 5000L) {
            lastLog = now;
            // Covers frames where nothing was drawn: render() never ran, so its own timer never
            // fired. Either way exactly one line per window comes out.
            ConeVolumeRenderer.logIfDue();
            String depth = "n/a";
            try {
                depth = String.valueOf(client.getFramebuffer().getDepthAttachment());
            } catch (Throwable t) {
                depth = "err:" + t.getClass().getSimpleName();
            }
            MtrHeadlightClient.LOGGER.info(
                    "[lte] cone: calls={} trains={} models={} noModel={} noSlot={} drawn={}"
                            + " quads={} probeQuads={} volBeams={} probe={} near={} dmin={}"
                            + " range={} g1={} g2={} g3={} g4={} g5={} g6={} g7={} g8={} g9={}"
                            + " path={} source={} shaders={} peak={} attMin={} boost={} depthTex={}"
                            + " volume={}",
                    cCalls, cTrains, cModels, cNoModel, cNoSlot, cDrawn, cQuads, cProbeQuads,
                    cVolumeBeams, cProbe, cNear,
                    String.format(Locale.ROOT, "%.1f",
                            cDmin == Double.MAX_VALUE ? -1.0D : cDmin),
                    String.format(Locale.ROOT, "%.0f", cRange),
                    WC[1], WC[2], WC[3], WC[4], WC[5], WC[6], WC[7], WC[8], WC[9],
                    pathName(), sourceName(), shaders, String.format(Locale.ROOT, "%.3f", peak),
                    String.format(Locale.ROOT, "%.2f", peak * MIN_ATTENUATION),
                    boost ? "on(0.45 flat)" : "off",
                    depth, ConeVolumeRenderer.status(cfg));
            cCalls = 0;
            cTrains = 0;
            cModels = 0;
            cNoModel = 0;
            cNoSlot = 0;
            cDrawn = 0;
            cQuads = 0;
            cProbe = 0;
            cProbeQuads = 0;
            cVolumeBeams = 0;
            cNear = 0;
            cDmin = Double.MAX_VALUE;
            java.util.Arrays.fill(WC, 0);
        }
    }

    /**
     * 距离闸门的钳制：{@code cfg.beamDistance}（默认 128）先钳到
     * [{@value #MIN_BEAM_DISTANCE}, {@value #MAX_BEAM_DISTANCE_LIMIT}]。
     *
     * <p>配置缺失（{@code cfg == null}，例如界面还没加载完）时退回
     * {@value #MAX_BEAM_DISTANCE}，既不放大也不把光柱关死。
     */
    private static double beamRange(final HeadlightConfig cfg) {
        final double value = cfg == null ? MAX_BEAM_DISTANCE : cfg.beamDistance;
        final double clamped = clampRange(value);
        cRange = clamped;
        return clamped;
    }

    private static double clampRange(final double value) {
        if (!(value > 0.0D)) {
            return MAX_BEAM_DISTANCE;
        }
        return Math.max(MIN_BEAM_DISTANCE, Math.min(MAX_BEAM_DISTANCE_LIMIT, value));
    }

    /**
     * Emits the beam(s) of one end of one car.
     *
     * <p>The anchor is the car's own face ({@link TrainPose#carStart(int)} / {@link
     * TrainPose#carEnd(int)}) and the direction is the car's own tangent, so a long train on a
     * curve gets its nose beam along car 0 and its tail beam along the last car instead of both
     * following the chord of the whole consist.
     *
     * <p>A slot may carry one lateral offset per lamp - a mirrored pair is two - in which case each
     * lamp gets its own cone at a share of the alpha budget, so two lamps are not twice as bright
     * as one.
     */
    private static int emitSlot(final boolean volume, final TrainPose pose, final int car,
                                final boolean head, final VehicleLights.Slot slot,
                                final boolean reversed, final double length, final int rgb,
                                final float alpha, final int steps, final double range,
                                final long trainId, final boolean boost,
                                final Vec3d shift, final Vec3d eye, final ClientWorld world) {
        if (slot == null) {
            gate(4);
            return 0;
        }
        final Vec3d base = head ? pose.carStart(car) : pose.carEnd(car);
        // Body pose, not the rail tangent: MTR rotates the car body by the chord of its own two
        // faces (Train#calculateCar), so a beam built on the tangent points off the visible box on a
        // curve - worst for a single car on a sharp one. The tangent is only the fallback for a
        // degenerate chord.
        Vec3d dir = pose.carBodyForward(car);
        if (dir == Vec3d.ZERO) {
            dir = pose.carForward(car);
        }
        if (dir == Vec3d.ZERO) {
            gate(6);
            return 0;
        }
        if (!head) {
            dir = dir.multiply(-1.0D);
        }
        final Vec3d flat = new Vec3d(dir.x, 0.0D, dir.z);
        final Vec3d right = flat.lengthSquared() < 1.0E-6D
                ? new Vec3d(1.0D, 0.0D, 0.0D)
                : flat.normalize().crossProduct(new Vec3d(0.0D, 1.0D, 0.0D));
        // Model +lateral maps to world -right when the car is drawn against its model axis.
        final double flip = reversed ? -1.0D : 1.0D;
        final int lamps = slot.lateral.length;
        final float each = lamps <= 1 ? alpha : (float) (alpha / Math.sqrt(lamps));
        // Out of the end face by FACE_GAP, back in by the measured recess of the lamp.
        final double push = VehicleLights.FACE_GAP - slot.inset;
        int emitted = 0;
        for (int i = 0; i < lamps; i++) {
            final Vec3d apex = base.add(dir.multiply(push))
                    .add(right.multiply(slot.lateral[i] * flip))
                    .add(0.0D, slot.height, 0.0D);
            // 逐盏一个 BeamGeom：位置（含横向偏移与高度）、方向、长度、颜色都装在这一个对象里，
            // 壳层绘制只从它取字段。GLOW 源用的是同一个类型的 {@link BeamGeom#of}，所以两个几何源
            // 到壳层这一层已经是同一种输入，差别只剩"灯位从哪来"。
            final BeamGeom geom = BeamGeom.at(apex, dir, length, rgb);
            if (emit(volume, false, geom.apex(world), geom.axis, geom.length,
                    coneRadius(geom.length, head), geom.rgb, each, steps, range, boost,
                    "train=" + trainId + " end=" + (head ? "head" : "tail") + " lamp=" + i
                            + " car=" + car, shift, eye, world)) {
                emitted++;
            }
        }
        return emitted;
    }

    /**
     * 资源包 / 编辑器给的「整张光束表」：一束一条，全部数值自带。
     *
     * <p>几何只借用 {@link TrainPose} 的车身坐标系（车头端面 + 车身朝向 + 世界右方向），
     * 位置用 {@link VehicleLightOverrides.Resolved} 已经换算好的
     * {@code lateral / height / inset}（模型空间 → 车体局部），方向和编辑器
     * {@code TrainPreview#direction} 用同一条公式：零旋转 = 车头 -Z / 车尾 +Z，
     * {@code rot = [yaw, pitch, roll]} 度。
     *
     * <p>尾端的束受 {@code coneOnTail} 管，和自动那一对同一个开关；其余类型一律画。
     */
    private static int emitDeclared(final boolean volume, final TrainPose pose, final int cars,
                                    final List<VehicleLightOverrides.Resolved> beams,
                                    final boolean reversed, final int steps, final double range,
                                    final long trainId, final boolean boost, final boolean tailCones,
                                    final Vec3d shift, final Vec3d eye, final ClientWorld world) {
        final double flip = reversed ? -1.0D : 1.0D;
        int emitted = 0;
        for (final VehicleLightOverrides.Resolved r : beams) {
            if (r == null) {
                continue;
            }
            final boolean head = r.isHead();
            if (!head && !tailCones) {
                gate(5);
                continue;
            }
            final int car = head ? 0 : Math.max(0, cars - 1);
            final Vec3d base = head ? pose.carStart(car) : pose.carEnd(car);
            Vec3d forward = pose.carBodyForward(car);
            if (forward == Vec3d.ZERO) {
                forward = pose.carForward(car);
            }
            if (forward == Vec3d.ZERO) {
                gate(6);
                continue;
            }
            final Vec3d flat = new Vec3d(forward.x, 0.0D, forward.z);
            final Vec3d right = flat.lengthSquared() < 1.0E-6D
                    ? new Vec3d(1.0D, 0.0D, 0.0D)
                    : flat.normalize().crossProduct(new Vec3d(0.0D, 1.0D, 0.0D));
            // Same anchor as emitSlot: out of the end face by FACE_GAP, back in by the beam's
            // own recess. lateral is already "from the model's centreline".
            final double push = VehicleLights.FACE_GAP - r.inset;
            final Vec3d apex = base.add(forward.multiply(push))
                    .add(right.multiply(r.lateral * flip))
                    .add(0.0D, r.height, 0.0D);
            final Vec3d axis = declaredAxis(forward, right, r.rot, head);
            final double radius = r.width > 0.02D ? r.width * 0.5D : coneRadius(r.length, head);
            final float alpha = (float) Math.max(0.0D, Math.min(1.0D, r.alpha));
            if (emit(volume, false, apex, axis, r.length, radius, r.color, alpha, steps, range, boost,
                    "train=" + trainId + " end=" + r.type + " pack car=" + car,
                    shift, eye, world)) {
                emitted++;
            }
        }
        return emitted;
    }

    /**
     * 一束声明光束的方向：车头 {@code -Z} 为基准，{@code rot = [yaw, pitch, roll]} 度，
     * 与 {@code TrainPreview#direction} 逐位一致（尾灯整条取反，不是「yaw+180」）。
     */
    private static Vec3d declaredAxis(final Vec3d forward, final Vec3d right,
                                      final double[] rot, final boolean head) {
        final double yaw = Math.toRadians(rot == null ? 0.0D : rot[0]);
        final double pitch = Math.toRadians(rot == null ? 0.0D : rot[1]);
        final double cosPitch = Math.cos(pitch);
        Vec3d dir = forward.multiply(Math.cos(yaw) * cosPitch)
                .add(right.multiply(-Math.sin(yaw) * cosPitch))
                .add(0.0D, -Math.sin(pitch), 0.0D);
        if (!head) {
            dir = dir.multiply(-1.0D);
        }
        return dir.lengthSquared() < 1.0E-9D ? forward : dir.normalize();
    }

    /** 车型 id：MTR 报给渲染器的那个（{@code baseTrainType}），退回 {@code trainId}。 */
    private static String vehicleIdOf(final TrainClient train) {
        try {
            if (train.baseTrainType != null && !train.baseTrainType.isBlank()) {
                return train.baseTrainType;
            }
        } catch (Throwable ignored) {
            // Older MTR builds: fall through to the train id.
        }
        try {
            return train.trainId;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 末端半径：半角 {@value #HALF_ANGLE_DEG}°，头灯乘 0.6、尾灯乘 0.5 的收束系数。
     *
     * <p>MODEL 与 GLOW 两个几何源共用它：对照模式只允许"灯位来源"不同，锥的粗细不能变。
     */
    private static double coneRadius(final double length, final boolean head) {
        return Math.tan(Math.toRadians(HALF_ANGLE_DEG)) * length * (head ? 0.6D : 0.5D);
    }

    /**
     * GLOW 对照模式下每列车首帧一行：
     * {@code [lte] coneSource glow: train=… base=(x,y,z) axis=(x,y,z) len=L tailBase=(…) tailLen=…}。
     *
     * <p>这几个数就是光带自己用的那一组（同一个 {@link BeamGeom}），所以拿它和光带的灯口位置
     * 对一眼就能判断"绑定错了"还是"光柱没画出来"。切回 MODEL 或重新切进 GLOW 会清空，下一帧
     * 重新逐车打印（{@link #clearGlowLogged()}）。
     */
    private static void logGlowOnce(final long trainId, final BeamGeom head, final Vec3d headApex,
                                    final BeamGeom tail, final Vec3d tailApex) {
        if (GLOW_LOGGED.size() > 512) {
            GLOW_LOGGED.clear();
        }
        if (!GLOW_LOGGED.add(Long.valueOf(trainId))) {
            return;
        }
        MtrHeadlightClient.LOGGER.info(String.format(Locale.ROOT,
                "[lte] coneSource glow: train=%d base=(%.2f, %.2f, %.2f)"
                        + " axis=(%.3f, %.3f, %.3f) len=%.2f"
                        + " tailBase=(%.2f, %.2f, %.2f) tailLen=%.2f",
                trainId, headApex.x, headApex.y, headApex.z,
                head.axis.x, head.axis.y, head.axis.z, head.length,
                tailApex.x, tailApex.y, tailApex.z, tail.length));
    }

    /** 切换 {@code beamConeSource} 时清掉已打印过的列车，让下一帧重新逐车打对照行。 */
    static void clearGlowLogged() {
        GLOW_LOGGED.clear();
    }

    /** 当前光柱的几何来源（{@code MODEL} / {@code GLOW}），诊断行和命令回显用。 */
    public static String sourceName() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        return cfg == null ? HeadlightConfig.CONE_SOURCE_MODEL : cfg.resolvedConeSource();
    }

    /**
     * 真实光束这一帧实际走的渲染路径：{@code shell}（默认，加色壳层锥）或 {@code volume}
     * （raymarch）。诊断行里的 {@code path=}，命令回显用同一个值，聊天栏和日志不会各说一套。
     *
     * <p>{@code volume} 要求配置开关打开 <b>且</b> {@link ConeVolumeRenderer#ready} 为真
     * （shader 已载入、没有 shader pack 抢管道、没有 GL 失败、遮挡开关允许）。任何一条不满足都是
     * {@code shell} —— 这正是"看到什么"与"配置写了什么"之间的差。
     */
    public static String pathName() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (cfg != null && cfg.beamConeVolume && ConeVolumeRenderer.ready(cfg)) {
            return "volume";
        }
        return "shell";
    }

    /**
     * Splits the radial profile {@code f(d) = (1 - d^2)^2} over the shells.
     *
     * <p>A sight line at normalised distance {@code d} from the axis crosses exactly the shells
     * whose radius is {@code >= d}, so its accumulated alpha is {@code sum(w[i] for radius[i] >= d)}
     * - a step function of d. Setting each weight to the profile's value at the middle of its own
     * band ({@code f(mid[i]) - f(mid[i-1])}) makes that step function trace {@code f}, and the
     * weights come out positive on their own because {@code f} is monotone.
     */
    private static float[] shellWeights(final double[] radii) {
        final float[] weights = new float[radii.length];
        double outer = 0.0D;
        for (int i = 0; i < radii.length; i++) {
            final double inner = i + 1 < radii.length ? radii[i + 1] : 0.0D;
            final double mid = (radii[i] + inner) * 0.5D;
            final double value = radial(mid);
            weights[i] = (float) Math.max(0.0D, value - outer);
            outer = value;
        }
        return weights;
    }

    /** Radial density profile, 1 on the axis and 0 at the rim. */
    private static double radial(final double d) {
        final double rest = 1.0D - d * d;
        return rest * rest;
    }

    /**
     * Routes one beam either to the depth-aware volume renderer (as plain beam data) or to the
     * legacy shell builder. Both paths get the same apex/axis/length/radius, so the two can be
     * swapped at run time (config toggle, shader pack, GL failure) without any visual jump other
     * than the missing occlusion.
     */
    private static boolean emit(final boolean volume, final boolean probe, final Vec3d apex,
                                final Vec3d dir, final double length, final double radius,
                                final int rgb, final float alpha, final int steps,
                                final double range, final boolean boost, final String beamKey,
                                final Vec3d shift, final Vec3d eye,
                                final ClientWorld world) {
        // Range gate on the beam's own apex, not on the train's nose: on a multi-car train the
        // nose can be a hundred blocks away while the car in front of you is right there, and a
        // beam whose lamp has left the range should not cost a march either. The shell path gets
        // the same gate (it used to have none, so a beam 300 blocks out still cost 4*6*14 quads).
        final double gateRange = range > 0.0D && !Double.isNaN(range) ? range : MAX_BEAM_DISTANCE;
        if (apex.squaredDistanceTo(eye) > gateRange * gateRange) {
            gate(7);
            return false;
        }
        // boost = 探针参数：每一条真实光束都拿满 0.45，不受调用方的尾灯 0.55 收束或灯位对半分的
        // 折减影响。否则"提亮对照"就不是对照，而只是"亮一点的同一条公式"。
        final float effAlpha = boost && !probe ? DEBUG_CONE_ALPHA : alpha;
        if (length <= 0.1D || radius <= 0.01D || effAlpha <= 1.0E-4F) {
            gate(8);
            return false;
        }
        // 每束一次的首帧参数行：alphaPeak / len / radius / apex / axis + 这一帧真正生效的衰减
        // 模型（boost=on 时全部为 1）。"drawn=15 quads=10080 却看不见"就靠这一行区分"
        // 几何退化"（len/radius ≈ 0、apex 在原点）和"alpha 太小"。
        logBeamOnce(beamKey, effAlpha, length, radius, apex, dir, boost,
                coneEffectiveMinAlpha(effAlpha, boost));
        if (!volume) {
            if (!cone(apex, dir, length, radius, rgb, effAlpha, shift, eye, world, probe, boost)) {
                gate(8);
                return false;
            }
            return true;
        }
        if (BEAMS.size() >= MAX_BEAM_CANDIDATES) {
            gate(8);
            return false;
        }
        BEAMS.add(new ConeVolumeBeam(apex, dir, length, radius, rgb, effAlpha, steps));
        // 这条路没有四边形（一个覆盖代理 + 一条 raymarch），所以单独计数：否则
        // coneQuads=0 会被读成"一条都没画"，而实际是"画了，但不是用四边形画的"。
        volumeBeamsTotal++;
        cVolumeBeams++;
        return true;
    }

    /**
     * Appends one beam volume (four nested shells, {@link #RINGS} rings each) to {@link #vertices}.
     * {@code radius} is the radius at the far end; {@code alpha} is the beam's alpha budget - the
     * shell weights sum to 1.0, so a sight line through the axis at the lamp accumulates this value
     * per wall, while any single vertex stays far below it.
     */
    private static boolean cone(final Vec3d apex, final Vec3d dir, final double length,
                                final double radius, final int rgb, final float alpha,
                                final Vec3d shift, final Vec3d eye, final ClientWorld world,
                                final boolean probe, final boolean boost) {
        if (length <= 0.1D || radius <= 0.01D || alpha <= 1.0E-4F) {
            return false;
        }
        final Vec3d axis = dir.normalize();
        Vec3d up = Math.abs(axis.y) > 0.99D ? new Vec3d(1.0D, 0.0D, 0.0D) : new Vec3d(0.0D, 1.0D, 0.0D);
        final Vec3d right = axis.crossProduct(up).normalize();
        up = right.crossProduct(axis).normalize();

        final float red = ((rgb >> 16) & 0xFF) / 255.0F;
        final float green = ((rgb >> 8) & 0xFF) / 255.0F;
        final float blue = (rgb & 0xFF) / 255.0F;
        // Cone slope: the surface normal of a cone is (radial - axis * radius/length), and it is
        // the same for every shell because radius and distance scale together.
        final double slope = radius / length;
        final double invNorm = 1.0D / Math.sqrt(1.0D + slope * slope);

        // Ground height under each ring, sampled once on the axis: one scan per ring instead of one
        // per vertex, and the shells differ by less than a block sideways.
        if (groundScratch.length < RINGS.length) {
            groundScratch = new double[RINGS.length];
        }
        for (int r = 0; r < RINGS.length; r++) {
            final double d = length * RINGS[r];
            groundScratch[r] = groundY(world, apex.x + axis.x * d, apex.y + axis.y * d,
                    apex.z + axis.z * d);
        }

        // boost / 静态探针：alpha 就是峰值本身，纵向 / 视角 / 地面全部不乘（探针同参数）。
        final boolean plain = boost || probe;
        for (int shell = 0; shell < SHELLS.length; shell++) {
            final double scale = SHELLS[shell];
            final float share = SHELL_ALPHA[shell];
            for (int r = 0; r + 1 < RINGS.length; r++) {
                final double t0 = RINGS[r];
                final double t1 = RINGS[r + 1];
                final double rad0 = radius * t0 * scale;
                final double rad1 = radius * t1 * scale;
                // Longitudinal term, per ring end (shared by every segment of the band).
                final float fade0 = plain ? 1.0F : fade(t0);
                final float fade1 = plain ? 1.0F : fade(t1);
                final double d0 = length * t0;
                final double d1 = length * t1;
                final double gy0 = groundScratch[r];
                final double gy1 = groundScratch[r + 1];
                for (int s = 0; s < SEGMENTS; s++) {
                    final double a = 2.0D * Math.PI * s / SEGMENTS;
                    final double b = 2.0D * Math.PI * (s + 1) / SEGMENTS;
                    final double ca = Math.cos(a);
                    final double sa = Math.sin(a);
                    final double cb = Math.cos(b);
                    final double sb = Math.sin(b);

                    // Surface normals at the two angular positions of this quad, tilted by the cone
                    // slope. Constant along the axis, so one pair per quad serves all rings.
                    final double nax = (right.x * ca + up.x * sa - axis.x * slope) * invNorm;
                    final double nay = (right.y * ca + up.y * sa - axis.y * slope) * invNorm;
                    final double naz = (right.z * ca + up.z * sa - axis.z * slope) * invNorm;
                    final double nbx = (right.x * cb + up.x * sb - axis.x * slope) * invNorm;
                    final double nby = (right.y * cb + up.y * sb - axis.y * slope) * invNorm;
                    final double nbz = (right.z * cb + up.z * sb - axis.z * slope) * invNorm;

                    // Camera-relative vertices: the matrix stack already holds the camera translation.
                    final double wx0 = apex.x + axis.x * d0 + right.x * ca * rad0 + up.x * sa * rad0;
                    final double wy0 = apex.y + axis.y * d0 + right.y * ca * rad0 + up.y * sa * rad0;
                    final double wz0 = apex.z + axis.z * d0 + right.z * ca * rad0 + up.z * sa * rad0;
                    final double wx1 = apex.x + axis.x * d0 + right.x * cb * rad0 + up.x * sb * rad0;
                    final double wy1 = apex.y + axis.y * d0 + right.y * cb * rad0 + up.y * sb * rad0;
                    final double wz1 = apex.z + axis.z * d0 + right.z * cb * rad0 + up.z * sb * rad0;
                    final double wx2 = apex.x + axis.x * d1 + right.x * cb * rad1 + up.x * sb * rad1;
                    final double wy2 = apex.y + axis.y * d1 + right.y * cb * rad1 + up.y * sb * rad1;
                    final double wz2 = apex.z + axis.z * d1 + right.z * cb * rad1 + up.z * sb * rad1;
                    final double wx3 = apex.x + axis.x * d1 + right.x * ca * rad1 + up.x * sa * rad1;
                    final double wy3 = apex.y + axis.y * d1 + right.y * ca * rad1 + up.y * sa * rad1;
                    final double wz3 = apex.z + axis.z * d1 + right.z * ca * rad1 + up.z * sa * rad1;

                    quad((float) (wx0 - shift.x), (float) (wy0 - shift.y), (float) (wz0 - shift.z),
                            (float) (wx1 - shift.x), (float) (wy1 - shift.y), (float) (wz1 - shift.z),
                            (float) (wx2 - shift.x), (float) (wy2 - shift.y), (float) (wz2 - shift.z),
                            (float) (wx3 - shift.x), (float) (wy3 - shift.y), (float) (wz3 - shift.z),
                            plain ? alpha : alpha * weight(share,
                                    soften(nax, nay, naz, wx0, wy0, wz0, eye) * groundFade(wy0, gy0))
                                    * fade0,
                            plain ? alpha : alpha * weight(share,
                                    soften(nbx, nby, nbz, wx1, wy1, wz1, eye) * groundFade(wy1, gy0))
                                    * fade0,
                            plain ? alpha : alpha * weight(share,
                                    soften(nbx, nby, nbz, wx2, wy2, wz2, eye) * groundFade(wy2, gy1))
                                    * fade1,
                            plain ? alpha : alpha * weight(share,
                                    soften(nax, nay, naz, wx3, wy3, wz3, eye) * groundFade(wy3, gy1))
                                    * fade1,
                            red, green, blue, probe);
                }
            }
        }
        return true;
    }

    /**
     * 画运行时探针（{@code /mtr_lte debug cone on}）：一个固定位置、固定参数的纯白大光锥。
     *
     * <p>和真实光柱共用 {@link #quad} + {@link GlowCompat#draw}（加色混合、深度测试开、深度写
     * 关、无剔除、相机相对坐标），但<b>不做</b>纵向衰减 / 视角柔化 / 地面淡出：探针必须是"只要
     * 这条管道通就一定看得见"的东西，任何衰减都会让"完全没画"和"画了但太淡"重新混成一团，
     * 那正是这次要分开的两件事。
     *
     * <p>固定几何：顶点（apex）在玩家水平前方 {@value #DEBUG_CONE_AHEAD} 格、与眼平齐；轴 = 水平
     * 视线向下 12°；长 {@value #DEBUG_CONE_LENGTH}；半角 20°（末端半径 ≈ 4.37）；四层同心壳，
     * 每层 alpha 固定 0.45，纯白 —— 加色叠加后接近纯白实心，刻意做到不可能看漏。
     */
    private static void debugCone(final MatrixStack matrices, final Vec3d shift, final Vec3d eye,
                                  final Vec3d look) {
        Vec3d flat = new Vec3d(look.x, 0.0D, look.z);
        flat = flat.lengthSquared() < 1.0E-6D ? new Vec3d(0.0D, 0.0D, 1.0D) : flat.normalize();
        final Vec3d apex = new Vec3d(eye.x + flat.x * DEBUG_CONE_AHEAD, eye.y,
                eye.z + flat.z * DEBUG_CONE_AHEAD);
        final double tilt = Math.toRadians(DEBUG_CONE_TILT_DEG);
        final Vec3d axis = new Vec3d(flat.x * Math.cos(tilt), -Math.sin(tilt),
                flat.z * Math.cos(tilt)).normalize();
        final double radius = Math.tan(Math.toRadians(DEBUG_CONE_HALF_ANGLE_DEG))
                * DEBUG_CONE_LENGTH;

        Vec3d up = Math.abs(axis.y) > 0.99D ? new Vec3d(1.0D, 0.0D, 0.0D)
                : new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d right = axis.crossProduct(up).normalize();
        up = right.crossProduct(axis).normalize();

        vertexCount = 0;
        for (final double shell : SHELLS) {
            for (int r = 0; r + 1 < RINGS.length; r++) {
                final double t0 = RINGS[r];
                final double t1 = RINGS[r + 1];
                final double rad0 = radius * t0 * shell;
                final double rad1 = radius * t1 * shell;
                final double d0 = DEBUG_CONE_LENGTH * t0;
                final double d1 = DEBUG_CONE_LENGTH * t1;
                for (int s = 0; s < SEGMENTS; s++) {
                    final double a = 2.0D * Math.PI * s / SEGMENTS;
                    final double b = 2.0D * Math.PI * (s + 1) / SEGMENTS;
                    final double ca = Math.cos(a);
                    final double sa = Math.sin(a);
                    final double cb = Math.cos(b);
                    final double sb = Math.sin(b);
                    quad((float) (apex.x + axis.x * d0 + right.x * ca * rad0 + up.x * sa * rad0 - shift.x),
                            (float) (apex.y + axis.y * d0 + right.y * ca * rad0 + up.y * sa * rad0 - shift.y),
                            (float) (apex.z + axis.z * d0 + right.z * ca * rad0 + up.z * sa * rad0 - shift.z),
                            (float) (apex.x + axis.x * d0 + right.x * cb * rad0 + up.x * sb * rad0 - shift.x),
                            (float) (apex.y + axis.y * d0 + right.y * cb * rad0 + up.y * sb * rad0 - shift.y),
                            (float) (apex.z + axis.z * d0 + right.z * cb * rad0 + up.z * sb * rad0 - shift.z),
                            (float) (apex.x + axis.x * d1 + right.x * cb * rad1 + up.x * sb * rad1 - shift.x),
                            (float) (apex.y + axis.y * d1 + right.y * cb * rad1 + up.y * sb * rad1 - shift.y),
                            (float) (apex.z + axis.z * d1 + right.z * cb * rad1 + up.z * sb * rad1 - shift.z),
                            (float) (apex.x + axis.x * d1 + right.x * ca * rad1 + up.x * sa * rad1 - shift.x),
                            (float) (apex.y + axis.y * d1 + right.y * ca * rad1 + up.y * sa * rad1 - shift.y),
                            (float) (apex.z + axis.z * d1 + right.z * ca * rad1 + up.z * sa * rad1 - shift.z),
                            DEBUG_CONE_ALPHA, DEBUG_CONE_ALPHA, DEBUG_CONE_ALPHA, DEBUG_CONE_ALPHA,
                            1.0F, 1.0F, 1.0F, true);
                }
            }
        }
        try {
            if (vertexCount > 0) {
                GlowCompat.draw(vertices, vertexCount, matrices);
            }
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.error("[lte] debug cone draw failed", t);
        } finally {
            vertexCount = 0;
        }
    }

    /**
     * 单顶点的有效权重：径向份额 × (视角 × 地面)，下限 {@link #MIN_ATTENUATION}。
     *
     * <p>纵向项不在这里（它必须一路到 0，光柱才会在尖端化掉）；其余三者相乘后最常见的量级是
     * 0.01 以下，加色叠加出来就是"看不见"。这个下限把最暗处钉在峰值的 25%。
     */
    private static float weight(final float share, final float softenGround) {
        return Math.max(MIN_ATTENUATION, share * softenGround);
    }

    /**
     * 一束光在<b>最暗处</b>（第一圈、最不朝向相机的那个顶点）的单顶点 alpha，只给日志用：
     * 它和 {@code alphaPeak=} 放在一起，就能直接看出"是几何退化（len/radius 小）"还是
     * "alpha 被乘没了"。
     */
    private static float coneEffectiveMinAlpha(final float alpha, final boolean boost) {
        return boost ? alpha : alpha * MIN_ATTENUATION * fade(RINGS[0]);
    }

    /**
     * 每束一次、首帧一行的参数日志：{@code [lte] cone beam: ...}。
     *
     * <p>打的是这一帧真实传给壳层的数据（不是配置值）：{@code alphaPeak} 是这束光的预算，
     * {@code minVertex} 是它乘完衰减后最暗的顶点，{@code len}/{@code radius} 判断几何是否退化，
     * {@code apex}/{@code axis} 判断位置是否跑到原点或方向为零。切 {@code coneBoost} 会清空记录，
     * 所以开/关各打一遍，能直接对比。
     */
    private static void logBeamOnce(final String key, final float alpha, final double length,
                                    final double radius, final Vec3d apex, final Vec3d axis,
                                    final boolean boost, final float minVertex) {
        if (key == null) {
            return;
        }
        if (BEAM_LOGGED.size() > 512) {
            BEAM_LOGGED.clear();
        }
        if (!BEAM_LOGGED.add(key)) {
            return;
        }
        MtrHeadlightClient.LOGGER.info(String.format(Locale.ROOT,
                "[lte] cone beam: %s alphaPeak=%.3f minVertex=%.4f len=%.2f radius=%.2f"
                        + " apex=(%.2f, %.2f, %.2f) axis=(%.3f, %.3f, %.3f) boost=%s",
                key, alpha, minVertex, length, radius,
                apex.x, apex.y, apex.z, axis.x, axis.y, axis.z, boost));
    }

    /**
     * Longitudinal alpha ramp: {@code exp(-LENGTH_FALLOFF * t) * (1 - t)}.
     *
     * <p>The exponential is the falloff the eye expects from a light cone (most of the drop happens
     * in the first third); the linear factor is what makes the value reach <b>exactly</b> 0 at
     * {@code t = 1} instead of leaving a 5% step at the tip - the "sudden cut" the old
     * {@code (1 - t)^1.6 * 1.15} ramp had left visible once the beam got long.
     */
    private static float fade(final double t) {
        final double clamped = Math.max(0.0D, Math.min(1.0D, t));
        return (float) (Math.exp(-LENGTH_FALLOFF * clamped) * (1.0D - clamped));
    }

    /**
     * View-angle softening: {@code VIEW_FLOOR + (1 - VIEW_FLOOR) * |n.v|^VIEW_POWER}.
     *
     * <p>{@code |n.v|} is 1 where the shell faces the camera and 0 at the silhouette. Using the
     * absolute value is deliberate: the near and the far wall of the volume are both seen, so both
     * should glow - that is what turns a hollow tube into something that looks filled. The floor
     * keeps a beam that points straight at the camera from disappearing (there the whole surface is
     * grazing).
     */
    private static float soften(final double nx, final double ny, final double nz,
                                final double px, final double py, final double pz,
                                final Vec3d eye) {
        final double vx = px - eye.x;
        final double vy = py - eye.y;
        final double vz = pz - eye.z;
        final double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1.0E-4D) {
            return 1.0F;
        }
        double facing = Math.abs((nx * vx + ny * vy + nz * vz) / len);
        if (facing > 1.0D) {
            facing = 1.0D;
        }
        return (float) (VIEW_FLOOR + (1.0D - VIEW_FLOOR) * Math.pow(facing, VIEW_POWER));
    }

    /**
     * Ground soft intersection: smoothstep over the clearance to the surface below the vertex, so
     * the beam thins out and disappears as it reaches the ground instead of ending on a hard line.
     */
    private static float groundFade(final double y, final double groundY) {
        if (!(groundY > -1.0E30D)) {
            return 1.0F;                       // no ground sample (unloaded chunk / void): no fade
        }
        final double clearance = y - groundY;
        if (clearance <= 0.0D) {
            return 0.0F;                       // under the surface: contributes nothing
        }
        if (clearance >= GROUND_FADE_BLOCKS) {
            return 1.0F;
        }
        final double x = clearance / GROUND_FADE_BLOCKS;
        return (float) (x * x * (3.0D - 2.0D * x));   // smoothstep(0, 1, x)
    }

    /**
     * Y of the top face of the first non-air block at or below {@code y}, or -inf when the column
     * is open within {@link #GROUND_SCAN_BLOCKS} (nothing to fade into).
     *
     * <p>A short downward scan rather than the world heightmap on purpose: the heightmap answers
     * "highest solid block in this column", which inside a tunnel or under a viaduct canopy is the
     * <em>roof above the beam</em>. Using it would have cleared the beam exactly where a head light
     * matters most. The scan is bounded, so the cost is a few block lookups per ring; an unloaded
     * chunk reads as air, which simply means "no ground fade".
     */
    private static double groundY(final ClientWorld world, final double x, final double y, final double z) {
        if (world == null) {
            return Double.NEGATIVE_INFINITY;
        }
        final int bx = MathHelper.floor(x);
        final int bz = MathHelper.floor(z);
        final int by = MathHelper.floor(y);
        try {
            final int bottom = Math.max(world.getBottomY(), by - GROUND_SCAN_BLOCKS);
            for (int yy = by; yy >= bottom; yy--) {
                GROUND_POS.set(bx, yy, bz);
                final BlockState state = world.getBlockState(GROUND_POS);
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    return yy + 1.0D;
                }
            }
        } catch (Throwable t) {
            return Double.NEGATIVE_INFINITY;
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static void quad(final float xa, final float ya, final float za,
                             final float xb, final float yb, final float zb,
                             final float xc, final float yc, final float zc,
                             final float xd, final float yd, final float zd,
                             final float a00, final float a01, final float a11, final float a10,
                             final float red, final float green, final float blue,
                             final boolean probe) {
        // 两种锥分开计数：coneQuads 只算车灯的真实光柱，probeQuads 只算 /mtr_lte debug cone
        // 的探针。合在一起的时候"探针看得见、日志里 quads=0"根本无法解释，反过来也一样。
        if (probe) {
            probeQuadsTotal++;
            cProbeQuads++;
        } else {
            quadsTotal++;
            cQuads++;
        }
        final int needed = vertexCount + 4 * VERTEX_STRIDE;
        if (needed > vertices.length) {
            final float[] grown = new float[Math.max(needed, vertices.length * 2 + 512)];
            System.arraycopy(vertices, 0, grown, 0, vertexCount);
            vertices = grown;
        }
        vertexCount = put(xa, ya, za, red, green, blue, a00);
        vertexCount = put(xb, yb, zb, red, green, blue, a01);
        vertexCount = put(xc, yc, zc, red, green, blue, a11);
        vertexCount = put(xd, yd, zd, red, green, blue, a10);
    }

    private static int put(final float x, final float y, final float z,
                           final float red, final float green, final float blue, final float alpha) {
        int i = vertexCount;
        vertices[i++] = x;
        vertices[i++] = y;
        vertices[i++] = z;
        vertices[i++] = red;
        vertices[i++] = green;
        vertices[i++] = blue;
        vertices[i++] = alpha;
        return i;
    }

    private static List<TrainClient> snapshotTrains() {
        try {
            return new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Startup fingerprint: proves the running jar contains this prototype. */
    public static void announce() {
        try {
            ConeVolumeRenderer.init();
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] cone volume init failed", t);
        }
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        MtrHeadlightClient.LOGGER.info(String.format(Locale.ROOT,
                "[lte] cone active: halfAngle=%.0fdeg segments=%d rings=%d shells=%d"
                        + " probe=%s blend=additive path=%s volume=%s steps=%d source=%s",
                HALF_ANGLE_DEG, SEGMENTS, RINGS.length, SHELLS.length, PROBE,
                pathName(), ConeVolumeRenderer.status(cfg), cfg == null ? 10 : cfg.beamConeSteps(),
                sourceName()));
    }
}
