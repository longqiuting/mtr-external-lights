package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * The head / tail <b>light beams</b>: a ground-hugging sheet of additive light leaving each end of
 * the train, drawn as our own geometry.
 *
 * <h2>Straight sheet, per-car heading (do not confuse the two)</h2>
 * <ul>
 *   <li><b>The sheet does not bend.</b> Along its own length it is a straight ray - no
 *       {@code samplePath} call, no projection onto the rails - exactly like a real head light,
 *       which leaves the rails as soon as the track curves away.</li>
 *   <li><b>Its origin and heading are per car.</b> They come from the car at that end of the
 *       train: {@link TrainPose#front()} / {@link TrainPose#carBodyForward(int)} for the head,
 *       {@link TrainPose#rear()} / {@link TrainPose#carBodyForward(int)} negated for the tail. On a
 *       curve the nose (already turned) and the tail (still on the straight) point in genuinely
 *       different directions.</li>
 *   <li><b>The heading is the car body's chord, not the rail tangent.</b> MTR rotates the car body
 *       by the chord between its own two faces ({@code Train#calculateCar}: yaw from
 *       {@code positions[car]}, {@code positions[car + 1]}), so the chord is the direction the
 *       player actually sees. The rail tangent at the car's midpoint is only a second-order
 *       approximation of it: on a tight curve, across a rail joint, or for a <b>single car</b> (where
 *       the two boundaries are the ends of one short arc) the tangent swings away from the body and
 *       the sheet visibly fishtails. That was the "光带在弯道乱晃" bug. The tangent survives only as
 *       the degenerate-chord fallback - see {@link #endDir}.</li>
 *   <li><b>One direction source for sheet and cone.</b> {@link #endDir} is the only place the
 *       heading is chosen, and {@link ProtoBeamCone}'s {@code GLOW} cone source calls it too (through
 *       {@link #headGeom} / {@link #tailGeom}), so on a curve the cone and the sheet cannot disagree.</li>
 *   <li><b>Pitch follows too.</b> The heading is the full 3D chord, so it already contains the
 *       grade. Flattening it (the obvious first version) makes the beam too short uphill and too
 *       long downhill, because the ground-projected length changes with the pitch.</li>
 * </ul>
 *
 * <h2>Geometry - x/z from the 3D advance, y from the ground</h2>
 * <pre>
 * dir   = the car's own body chord at that end      // 3D, includes the grade
 * right = normalize(dir x (0,1,0))                  // lateral, always horizontal
 * base  = that car face's rail point                // rail surface at THIS end (not max of both)
 * path(t)   = base + dir * t                        // straight ray; only x/z are used
 * ground(t) = heightmap topY at (path(t).x, path(t).z)
 * P(t,u)    = ( path(t).x + right.x * u*w/2,
 *               accept(ground(t)) ? ground(t) + lift + endHeight : base.y + lift + endHeight,
 *               path(t).z + right.z * u*w/2 )
 * </pre>
 * So the ray decides where along the ground the sheet runs and how long it is, while every vertex
 * takes its height from the surface under it. That is what keeps the sheet glued to a slope instead
 * of hanging in the air as a tilted 3D sheet (the previous iteration) or sinking into the ballast
 * (a flat sheet at the lamp's height).
 *
 * <p><b>Why the heightmap and not {@code Rail#getPosition}:</b> the beam is straight while the track
 * curves, so past the first few blocks its (x,z) is no longer over the rails at all - a rail query
 * could only tell us the height at the origin, which is what {@code base.y} already is. The
 * heightmap answers the question we actually have ("how high is the ground <em>there</em>") and is
 * an array lookup, not a block-state walk. The rail-based origin height is kept as the fallback,
 * which is also what answers "no block here" (a bridge, a tunnel mouth, an unloaded chunk).
 *
 * <p><b>Query budget:</b> {@value #SAMPLES} heightmap lookups per beam, i.e. at most
 * {@code 2 * 8 = 16} per train per frame, interpolated linearly between samples for the individual
 * segments. A sample is only used when it is within {@value #MAX_DEVIATION} blocks of the straight
 * ray's own height there - otherwise the surface belongs to something else (ground far below a
 * viaduct, a roof above a cutting) and the origin height is used instead.
 *
 * <h2>Falloff (vertex alpha, additive blending)</h2>
 * <pre>
 * alpha(t,u) = beamAlpha * exp(-2.8 * t/length) * (1 - (t/length)^3) * (1 - |u|)
 * </pre>
 * <ul>
 *   <li>longitudinal: exponential decay from the lamp (brightest) with a terminal factor that
 *       reaches exactly 0 at the tip, so the far edge is invisible instead of a cut line;</li>
 *   <li>lateral: 1.0 on the centre line and 0 at both rims - a light pool with soft edges. The
 *       sheet is split into {@value #LAT_SEGMENTS} quads across (5 vertex columns), so the ramp is
 *       actually interpolated instead of being one flat quad.</li>
 * </ul>
 *
 * <h2>Budget</h2>
 * At most {@value #MAX_LONG} segments along the beam and {@value #LAT_SEGMENTS} across, for two
 * ends: {@code 12 * 4 * 2 = 96} quads per train, the stated ceiling. The segment count adapts to
 * the configured length (about 1.6 blocks per segment). Vertices go into one reused scratch array
 * (and the per-sample heights into one reused scratch array), so nothing large is allocated per
 * frame.
 *
 * <h2>Coordinate space (the trap)</h2>
 * {@code WorldRenderContext.matrixStack()} already carries the camera translation, so vertices must
 * be <b>camera-relative</b> ({@code world - camera.getPos()}). Feeding world coordinates here puts
 * the sheet at the world origin.
 */
public final class BeamGlow {
    /** Quads across the beam; 4 gives 5 vertex columns, enough for a smooth centre-to-rim ramp. */
    private static final int LAT_SEGMENTS = 4;
    /** Ceiling on segments along one beam; with 4 across and two ends this is the 96-quad budget. */
    private static final int MAX_LONG = 12;
    private static final int MIN_LONG = 3;
    /** Target blocks per longitudinal segment. */
    private static final double SEGMENT_BLOCKS = 1.6D;
    /** Exponential decay constant along the beam (alpha at the tip is e^-2.8 = 6%). */
    private static final double LENGTH_DECAY = 2.8D;
    /**
     * Length of the <b>head beam's ramp-up</b>, in blocks: the sheet starts fully transparent at the
     * lamp and reaches its peak alpha after this distance (smoothstep, so the second derivative is
     * continuous too). Without it the first quad is already at full brightness and the beam reads as
     * a cut-off slab glued to the nose instead of light leaving a lamp.
     */
    private static final double HEAD_FADE_BLOCKS = 2.0D;
    /**
     * Ramp-up of the <b>tail</b> beam, in blocks. It used to be 0 ("hard start"), which made the two
     * ends read differently: the tail's first quad appeared at full brightness and looked pasted on,
     * while the head faded in. A shorter ramp than the head's keeps the intended contrast (the tail
     * is the shorter, dimmer sheet) and still leaves the soft entry; {@link #beam} additionally caps
     * it at half the beam's own length, so a 4-block tail gets at most a 2-block ramp.
     */
    private static final double TAIL_FADE_BLOCKS = 1.5D;
    /** Height samples per beam (2 ends * this = the per-train heightmap budget per frame). */
    private static final int SAMPLES = 8;
    /** A ground sample further than this from the straight ray is treated as "something else". */
    private static final double MAX_DEVIATION = 3.0D;
    /** Floats per vertex: x y z r g b a. */
    private static final int VERTEX_STRIDE = 7;
    /** Vertical axis, used only to derive the (always horizontal) lateral vector. */
    private static final Vec3d UP = new Vec3d(0.0D, 1.0D, 0.0D);

    /** Reused vertex scratch, grown on demand - the beams run every frame while enabled. */
    private static float[] vertices = new float[0];
    private static int vertexCount;
    /** Reused height scratch: world height of each of the {@value #SAMPLES} points. */
    private static final double[] sampleHeight = new double[SAMPLES];

    // --- TEMP DIAG ------------------------------------------------------------------------------
    // One line every five seconds that says exactly which gate stopped the beams. Without it
    // "nothing is drawn" is indistinguishable from "nothing reached the draw call".
    // (Temporary: drop this block, its `diag(...)` calls and `-Dmtrheadlight.diag` before release.)
    private static final boolean DIAG = false;
    private static long diagAt = System.currentTimeMillis();
    private static int dCalls;
    private static int dTrains;
    private static int dPoseOk;
    private static int dDrawn;
    private static int dSubmit;
    /** 本窗口参与几何的<b>车节</b>总数（每列车 {@link TrainPose#cars()} 之和）。 */
    private static int dCars;
    /** 本窗口真正写进顶点缓冲的光束数（每列车最多 2：头 + 尾）。 */
    private static int dBeams;
    /** 本窗口用<b>车体弦</b>代替轨道切线的端数（弦退化的那一端只能退回切线，不计入）。 */
    private static int dDirFix;
    /** 本窗口里光带开关是开着的 —— 决定 {@code [lte] glow:} 那一行要不要打印。 */
    private static boolean windowOn;
    private static int sCfg;
    private static int sProbe;
    private static int sPass;
    private static int sWorld;
    private static int sMatrix;
    private static int sNotDrawable;
    private static int sEff;
    private static int sParked;
    private static int sNoPose;
    private static int sFar;
    private static int sNoDir;
    /** 自动关灯挡下的列车（headlightMode = AUTO 且环境判定为"亮"）。 */
    private static int sAuto;

    private static void diag(final String gate) {
        // gate is null once per frame (end of render) - switching on it NPEs every single frame,
        // which used to spill a 12-line stack trace into the log ~100 times a second.
        if (DIAG && gate != null) {
            switch (gate) {
                case "cfg" -> sCfg++;
                case "probe" -> sProbe++;
                case "pass" -> sPass++;
                case "world" -> sWorld++;
                case "matrix" -> sMatrix++;
                case "drawable" -> sNotDrawable++;
                case "eff" -> sEff++;
                case "parked" -> sParked++;
                case "pose" -> sNoPose++;
                case "far" -> sFar++;
                case "dir" -> sNoDir++;
                case "auto" -> sAuto++;
                default -> {
                }
            }
        }
        final long now = System.currentTimeMillis();
        if (now - diagAt < 5000L) {
            return;
        }
        diagAt = now;
        // 光带自己的那一行，每 5 秒一条，<b>不受 DIAG 开关影响</b>（和光柱的 [lte] cone: 一样）：
        // cars=参与几何的车节数，beams=真的写进缓冲的光束数，dirFix=用车身弦代替轨道切线的端数。
        // 过弯时 dirFix 应当等于有效端数（每列车 2）；若为 0 而 beams>0，说明弦退化了、走的是
        // 切线兜底 —— 那正是"弯道乱晃"的老症状。光带开关整窗口都没开时这一行不打。
        if (windowOn) {
            MtrHeadlightClient.LOGGER.info("[lte] glow: cars={} beams={} dirFix={}",
                    dCars, dBeams, dDirFix);
        }
        windowOn = false;
        dCars = 0;
        dBeams = 0;
        dDirFix = 0;
        if (!DIAG) {
            return;
        }
        MtrHeadlightClient.LOGGER.info(
                "beam: calls={} trains={} poseOk={} drawn={} submit={} skip={}"
                        + " [cfg={} probe={} pass={} world={} matrix={} drawable={} eff={}"
                        + " parked={} pose={} auto={} far={} dir={}]",
                dCalls, dTrains, dPoseOk, dDrawn, dSubmit,
                sCfg + sProbe + sWorld + sMatrix + sNotDrawable + sEff + sParked
                        + sNoPose + sAuto + sFar + sNoDir,
                sCfg, sProbe, sPass, sWorld, sMatrix, sNotDrawable, sEff, sParked, sNoPose, sAuto,
                sFar, sNoDir);
        dCalls = 0;
        dTrains = 0;
        dPoseOk = 0;
        dDrawn = 0;
        dSubmit = 0;
        sCfg = 0;
        sProbe = 0;
        sPass = 0;
        sWorld = 0;
        sMatrix = 0;
        sNotDrawable = 0;
        sEff = 0;
        sParked = 0;
        sNoPose = 0;
        sAuto = 0;
        sFar = 0;
        sNoDir = 0;
    }

    private BeamGlow() {
    }

    /**
     * Draws both beams of every visible train that has left its depot.
     *
     * @param latePass true for the {@code AFTER_TRANSLUCENT} call (the one a shader pack still
     *                 composites), false for {@code AFTER_ENTITIES}
     */
    public static void render(final WorldRenderContext context, final boolean latePass) {
        dCalls++;
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        // beamAnySiding(): the global switch may be off while a single siding overrides it on.
        if (cfg == null || !cfg.enabled || !cfg.beamAnySiding() || cfg.beamAlpha <= 0.0D) {
            diag("cfg");
            return;
        }
        // 光带开着：这一窗口的 [lte] glow: 行要打印（见 diag()）。
        windowOn = true;
        final boolean shaders;
        try {
            shaders = ShaderPackProbe.active();
        } catch (Throwable t) {
            diag("probe");
            return;
        }
        // Exactly one of the two world-render events draws, so a frame never doubles the sheet.
        if (shaders != latePass) {
            diag("pass");
            return;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.gameRenderer == null) {
            diag("world");
            return;
        }
        final MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            // Never draw without the world matrix: that would land the sheet at the origin.
            diag("matrix");
            return;
        }
        final ClientWorld world = client.world;
        final Vec3d shift = client.gameRenderer.getCamera().getPos();
        final Vec3d eye = client.player.getPos();

        vertexCount = 0;
        for (final TrainClient train : snapshotTrains()) {
            dTrains++;
            if (!drawable(cfg, train)) {
                diag("drawable");
                continue;
            }
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
            if (!eff.enabled || !eff.beamGlow) {
                diag("eff");
                continue;
            }
            // A train that has not left the depot does not shine - the same gate the dynamic
            // lights use, so "parked" never means "half lit".
            if (!DepartureState.departed(train)) {
                diag("parked");
                continue;
            }

            // Direction and swapEnds collapse into one flag (see TrainPose): the ends come out of
            // the pose already oriented, and ofShared hands back the very pose the light tick built
            // for this frame - same endpoint, same heading, so sheet and light cannot disagree.
            final boolean reversed = DepartureState.reversed(train, train.isReversed()) ^ cfg.swapEnds;
            final TrainPose pose = TrainPose.ofShared(train, reversed);
            final Vec3d nose = pose.front();
            final Vec3d rear = pose.rear();
            if (nose == Vec3d.ZERO && rear == Vec3d.ZERO) {
                diag("pose");
                continue;
            }
            dPoseOk++;
            // 车灯开关模式与环境判定：唯一信号源是 HeadlightSwitch（动态光源、轨道烘焙问的是
            // 同一个方法），所以光带和灯永远一起亮灭。AUTO 才让环境接管；ALWAYS / OFF 一律画。
            // 放在距离闸之前：被自动关掉的列车不该在日志里显示成"太远"。
            if (!HeadlightSwitch.isLit(world, pose, cfg, train.id)) {
                diag("auto");
                continue;
            }
            // Own draw distance, independent of the dynamic-light range: a 16-block sheet is worth
            // seeing from further away than a 0.85-block light is worth computing.
            if (nose.distanceTo(eye) > eff.beamDistance && rear.distanceTo(eye) > eff.beamDistance) {
                diag("far");
                continue;
            }

            // 出灯方向：车体弦（MTR 旋转车体用的就是同一根弦），切线只在弦退化时兜底。两端各取
            // 自己那节车 —— 弯道上头与尾本来就可以不同。方向只在 endDir 里选，光柱的 GLOW 源
            // 调的是同一处（headGeom/tailGeom），所以光带与光柱在弯道上不会各指一个方向。
            final int cars = Math.max(1, pose.cars());
            final BeamGeom headGeom = headGeom(cfg, eff, pose);
            final BeamGeom tailGeom = tailGeom(cfg, eff, pose);
            final Vec3d headDir = headGeom.axis;
            final Vec3d tailDir = tailGeom.axis;
            if (headDir == Vec3d.ZERO && tailDir == Vec3d.ZERO) {
                diag("dir");
                continue;
            }
            dCars += cars;
            // dirFix = 这一端真的用弦代替了切线；弦退化的那一端只能退回切线，不计。
            if (pose.carBodyFromChord(0)) {
                dDirFix++;
            }
            if (pose.carBodyFromChord(cars - 1)) {
                dDirFix++;
            }

            final double halfWidth = Math.max(0.25D, eff.beamWidth) * 0.5D;
            final float peak = (float) Math.min(1.0D, cfg.beamAlpha);
            // Start where the lamp is: the same offsets the dynamic light uses, so the sheet and
            // the light it belongs to cannot disagree about where the front face is.
            // 几何只在这一处算（headGeom/tailGeom → BeamGeom），光柱的 GLOW 对照模式调的是同一个
            // 工厂、同一组 offset/length/height/colour，所以两者的 base/axis/length 逐位相同 ——
            // 见 BeamGeom 与 ProtoBeamCone 的 coneSource。

            // Head: out of the nose. Its base height is the rail point at that end, never the max
            // of both ends - on a gradient that would float the sheet at the downhill end.
            // The last argument is the ramp-up: 2 blocks from 0 to peak alpha (see HEAD_FADE_BLOCKS).
            final int before = vertexCount;
            beam(world, headGeom, halfWidth, peak, shift, HEAD_FADE_BLOCKS);
            if (vertexCount > before) {
                dBeams++;
            }
            // Tail: its own car's outward heading, its own (shorter, redder, lower) sheet. It gets a
            // short ramp too - shorter than the head's, so the two ends stay distinguishable, but
            // no longer a hard cut at the lamp.
            final int afterHead = vertexCount;
            beam(world, tailGeom, halfWidth, peak, shift, TAIL_FADE_BLOCKS);
            if (vertexCount > afterHead) {
                dBeams++;
            }
            if (vertexCount > before) {
                dDrawn++;
            }
        }

        if (vertexCount > 0) {
            GlowCompat.draw(vertices, vertexCount, matrices);
            dSubmit++;
            vertexCount = 0;
        }
        diag(null);
    }

    /**
     * 一端的出灯方向 —— <b>光带与光柱 GLOW 源唯一的取方向处</b>。
     *
     * <p>车体弦优先（{@link TrainPose#carBodyForward(int)}）：MTR 用同一根弦（车节两个端面的连线）
     * 旋转车体，所以它才是玩家真正看见的那辆车指向的方向。弦退化（两个端面重合，例如短到压在一点
     * 的路径）时才退回轨道切线 —— 切线以前是默认，正是弯道上光带乱晃的来源：车节中点的切线在急弯
     * 上偏离车体半段圆弧，过轨道接缝时还会跳，单节车时尤其明显。
     *
     * @param head {@code true} 车头向外，{@code false} 车尾向外（弦反向）
     * @return 单位向量；{@link Vec3d#ZERO} 表示弦与切线都不可用，这一端不画
     */
    static Vec3d endDir(final TrainPose pose, final int car, final boolean head) {
        Vec3d d = pose.carBodyForward(car);
        if (d == Vec3d.ZERO) {
            d = pose.carForward(car);            // 退化兜底：轨道切线
        }
        if (d == Vec3d.ZERO) {
            return Vec3d.ZERO;
        }
        return head ? d : d.multiply(-1.0D);
    }

    /**
     * 车头那束光的几何 —— {@link BeamGeom} 的唯一构造处。offset / length / height / colour 全部
     * 是光带这一组（{@link HeadlightConfig.Effective}），<b>光柱的 GLOW 源调的就是这个方法</b>，
     * 所以灯口、方向、长度、离地高度、颜色在两条路径上是同一个对象，不可能错开一层。
     */
    static BeamGeom headGeom(final HeadlightConfig cfg, final HeadlightConfig.Effective eff,
                             final TrainPose pose) {
        return BeamGeom.of(pose.front(), endDir(pose, 0, true), cfg.headOffsetClamped(),
                eff.beamHeadLength, Math.max(0.0D, cfg.beamLift) + eff.beamHeadHeight,
                eff.beamHeadColor);
    }

    /** 车尾那束光：最后那节车的弦反向，长度/颜色/高度仍取同一组配置。 */
    static BeamGeom tailGeom(final HeadlightConfig cfg, final HeadlightConfig.Effective eff,
                             final TrainPose pose) {
        final int last = Math.max(0, pose.cars() - 1);
        return BeamGeom.of(pose.rear(), endDir(pose, last, false), cfg.tailOffsetClamped(),
                eff.beamTailLength, Math.max(0.0D, cfg.beamLift) + eff.beamTailHeight,
                eff.beamTailColor);
    }

    /**
     * One end's sheet. {@code geom} carries the rail point at that end (already pushed out of the
     * car face) in world space, the unit heading that still carries the pitch, the length, the lift
     * plus that end's configured height, and the colour - the very same object shape the cone's
     * GLOW source consumes.
     */
    private static void beam(final ClientWorld world, final BeamGeom geom, final double halfWidth,
                             final float peak, final Vec3d shift, final double fadeInBlocks) {
        final Vec3d origin = geom.origin;
        final Vec3d dir = geom.axis;
        final double length = geom.length;
        final double extraHeight = geom.height;
        final int rgb = geom.rgb;
        if (length <= 0.05D) {
            return;
        }
        // Ramp-up distance, never more than half the beam: a fade longer than that would dim the
        // whole sheet instead of just softening where it leaves the lamp.
        final double fadeIn = Math.max(0.0D, Math.min(length * 0.5D, fadeInBlocks));
        // dir x up: horizontal for any dir with a horizontal component, so the sheet can only tilt
        // around that axis - it can never twist.
        Vec3d right = dir.crossProduct(UP);
        if (right.lengthSquared() < 1.0E-8D) {
            // dir is (near) vertical - a train can never point straight up; skip rather than
            // produce NaNs.
            return;
        }
        right = right.normalize().multiply(halfWidth);

        // Ground samples along the ray (x/z only; the ray's own y is the fallback plane).
        // k = 0 走 startHeight：光柱的 GLOW 对照模式取的是同一个方法，灯口高度必须一模一样。
        sampleHeight[0] = startHeight(world, origin);
        for (int k = 1; k < SAMPLES; k++) {
            final double f = (double) k / (double) (SAMPLES - 1);
            final double t = length * f;
            final double x = origin.x + dir.x * t;
            final double z = origin.z + dir.z * t;
            final double expected = origin.y + dir.y * t;
            final double ground = groundAt(world, x, z);
            sampleHeight[k] = !Double.isNaN(ground) && Math.abs(ground - expected) <= MAX_DEVIATION
                    ? ground : origin.y;
        }

        final int along = Math.max(MIN_LONG, Math.min(MAX_LONG,
                (int) Math.ceil(length / SEGMENT_BLOCKS)));
        final float red = ((rgb >> 16) & 0xFF) / 255.0F;
        final float green = ((rgb >> 8) & 0xFF) / 255.0F;
        final float blue = (rgb & 0xFF) / 255.0F;

        for (int i = 0; i < along; i++) {
            final double f0 = (double) i / (double) along;
            final double f1 = (double) (i + 1) / (double) along;
            final float a0 = peak * lengthAlpha(f0) * rampAlpha(length * f0, fadeIn);
            final float a1 = peak * lengthAlpha(f1) * rampAlpha(length * f1, fadeIn);
            if (a0 <= 0.001F && a1 <= 0.001F) {
                continue;
            }
            // Camera-relative: the matrix stack already contains the camera translation. Kept as
            // plain floats so the hot loop allocates nothing.
            final float x0 = (float) (origin.x + dir.x * length * f0 - shift.x);
            final float z0 = (float) (origin.z + dir.z * length * f0 - shift.z);
            final float y0 = (float) (interpolated(f0) + extraHeight - shift.y);
            final float x1 = (float) (origin.x + dir.x * length * f1 - shift.x);
            final float z1 = (float) (origin.z + dir.z * length * f1 - shift.z);
            final float y1 = (float) (interpolated(f1) + extraHeight - shift.y);

            for (int j = 0; j < LAT_SEGMENTS; j++) {
                final double u0 = -1.0D + 2.0D * (double) j / (double) LAT_SEGMENTS;
                final double u1 = -1.0D + 2.0D * (double) (j + 1) / (double) LAT_SEGMENTS;
                final float lat0 = lateralAlpha(u0);
                final float lat1 = lateralAlpha(u1);
                if ((a0 * lat0 <= 0.001F) && (a0 * lat1 <= 0.001F)
                        && (a1 * lat0 <= 0.001F) && (a1 * lat1 <= 0.001F)) {
                    continue;
                }
                // Lateral offsets are horizontal, so both rims share the centreline's height.
                final float ax = (float) (right.x * -u0);
                final float az = (float) (right.z * -u0);
                final float bx = (float) (right.x * -u1);
                final float bz = (float) (right.z * -u1);
                quad(x0 + ax, y0, z0 + az,
                        x0 + bx, y0, z0 + bz,
                        x1 + bx, y1, z1 + bz,
                        x1 + ax, y1, z1 + az,
                        a0 * lat0, a0 * lat1, a1 * lat1, a1 * lat0,
                        red, green, blue);
            }
        }
    }

    /**
     * 光带在灯口处的高度：脚下方块顶面（高度图），取不到或偏离射线超过
     * {@value #MAX_DEVIATION} 格时退回轨道点自身的高度。
     *
     * <p>这是光带第一排顶点的 y，也是 {@link BeamGeom#apex} 给光柱用的同一个 y。两边都调这一个
     * 方法，所以 {@code beamConeSource = GLOW} 时光柱的顶点和光带的灯口顶点逐位重合。
     */
    static double startHeight(final ClientWorld world, final Vec3d origin) {
        final double ground = groundAt(world, origin.x, origin.z);
        return !Double.isNaN(ground) && Math.abs(ground - origin.y) <= MAX_DEVIATION
                ? ground : origin.y;
    }

    /**
     * Surface height under {@code (x, z)} from the heightmap, or {@link Double#NaN} when it cannot
     * be read. The vertical counterpart of the beam is the ray's own height, so any exception here
     * simply means "use the fallback plane".
     */
    private static double groundAt(final ClientWorld world, final double x, final double z) {
        try {
            return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    MathHelper.floor(x), MathHelper.floor(z));
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** Height at fraction {@code f} of the beam: linear between the (at most 8) ground samples. */
    private static double interpolated(final double f) {
        final double scaled = Math.max(0.0D, Math.min(1.0D, f)) * (SAMPLES - 1);
        final int k = Math.min(SAMPLES - 2, (int) Math.floor(scaled));
        final double frac = Math.max(0.0D, Math.min(1.0D, scaled - k));
        return sampleHeight[k] + (sampleHeight[k + 1] - sampleHeight[k]) * frac;
    }

    /** Longitudinal falloff: exponential from the lamp, forced to exactly 0 at the tip. */
    private static float lengthAlpha(final double f) {
        final double clamped = Math.max(0.0D, Math.min(1.0D, f));
        return (float) (Math.exp(-LENGTH_DECAY * clamped) * (1.0D - clamped * clamped * clamped));
    }

    /** Lateral falloff: 1.0 on the centre line, 0 at the rim ({@code u} in [-1,1]). */
    private static float lateralAlpha(final double u) {
        return (float) Math.max(0.0D, 1.0D - Math.abs(u));
    }

    /**
     * Ramp-up: 0 at the lamp, 1 from {@code fadeIn} blocks on (smoothstep, so the slope is 0 at both
     * ends and the ramp leaves no visible crease). {@code fadeIn <= 0} means no ramp (neither beam
     * uses that any more; the parameter is kept because it is the natural "off" value).
     */
    private static float rampAlpha(final double t, final double fadeIn) {
        if (fadeIn <= 1.0E-4D) {
            return 1.0F;
        }
        final double x = Math.max(0.0D, Math.min(1.0D, t / fadeIn));
        return (float) (x * x * (3.0D - 2.0D * x));
    }

    /** Writes one quad (4 vertices, additive, one colour) into the shared scratch. */
    private static void quad(final float xa, final float ya, final float za,
                             final float xb, final float yb, final float zb,
                             final float xc, final float yc, final float zc,
                             final float xd, final float yd, final float zd,
                             final float alpha00, final float alpha01,
                             final float alpha11, final float alpha10,
                             final float red, final float green, final float blue) {
        final int needed = vertexCount + 4 * VERTEX_STRIDE;
        if (needed > vertices.length) {
            final float[] grown = new float[Math.max(needed, vertices.length * 2 + 64)];
            System.arraycopy(vertices, 0, grown, 0, vertexCount);
            vertices = grown;
        }
        vertexCount = put(vertices, vertexCount, xa, ya, za, red, green, blue, alpha00);
        vertexCount = put(vertices, vertexCount, xb, yb, zb, red, green, blue, alpha01);
        vertexCount = put(vertices, vertexCount, xc, yc, zc, red, green, blue, alpha11);
        vertexCount = put(vertices, vertexCount, xd, yd, zd, red, green, blue, alpha10);
    }

    private static int put(final float[] out, final int index, final float x, final float y,
                           final float z, final float red, final float green, final float blue,
                           final float alpha) {
        int i = index;
        out[i++] = x;
        out[i++] = y;
        out[i++] = z;
        out[i++] = red;
        out[i++] = green;
        out[i++] = blue;
        out[i++] = alpha;
        return i;
    }

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
}
