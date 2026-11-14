package dev.js.mtrheadlight.sss;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LightTest;
import dev.js.mtrheadlight.MtrHeadlightClient;
import dev.js.mtrheadlight.ShaderPackProbe;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import dev.js.mtrheadlight.clientgui.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.Locale;

/**
 * <b>Full-screen screen-space lamp lighting for the train lamps</b> - the light the lamps throw
 * <em>onto</em> the ground, walls and car bodies, which a billboard flare cannot do.
 *
 * <h2>Where the pass sits</h2>
 * {@code WorldRenderEvents.LAST}: every framebuffer write of the frame is finished (terrain,
 * entities, particles, weather, clouds) but the world has not been torn down, so the main
 * framebuffer is still bound and its depth + colour attachments still describe the frame we are
 * about to read. The GUI and the hand come later, which is what makes this a scene-wide effect
 * rather than an overlay.
 *
 * <h2>The one remaining term: surface</h2>
 * The lamps really light what is on screen. For the pixel's own surface point
 * {@code P} (rebuilt from the depth copy) the normal comes from {@code dFdx(P) x dFdy(P)}, and each
 * lamp contributes
 * {@code spot(smoothstep of the cone) * distance window * 1/(1+d^2/9) * max(N.L, 0)}. Multiplied by
 * the <b>albedo</b> of that pixel (the scene-colour copy) this is illumination, not a decal: a dark
 * platform answers darkly and a white wall answers brightly, and both follow the geometry, because
 * the normal is the real surface normal of the frame.
 *
 * <pre>
 *   per lamp:    P      = rd * tHit                       (from inverse(proj*view) + the depth copy)
 *                axis   = normalize(uLightDir[i].xyz)     (out of the lamp)
 *                spot   = smoothstep(cosOuter, cosInner, dot(axis, lamp -&gt; P))
 *                win    = 1 - smoothstep over the last 40% of the beam length
 *                att    = 1 / (1 + |lamp - P|^2 / attRef)
 *                surface += colour * power * spot * win * att * max(N.L, 0)
 *   composite:   added = surface * (scene * 8.0 + 0.05)
 *                lit   = scene + (1 - exp(-added * strength)) * (1 - 0.5 * scene)
 * </pre>
 *
 * <h2>The volume term is gone</h2>
 * Up to 1.0.63 this pass also integrated a <b>volume</b> term - the chord of each lamp's glowing
 * sphere along the view ray, gated by a Henyey-Greenstein phase and a transmittance exponential -
 * i.e. "light in the air". The user's verdict on the result was that the haze it drew looked worse
 * than no haze at all, so it was deleted outright (fragment stage, uniforms
 * {@code uParams.x/y/w}, and the {@code volumeSum} accumulator in the composite). What is left is
 * the surface term only, and {@code scatterStrength} is now purely the surface gain. Do not
 * reintroduce it as "just a little haze": a screen-space glow around a lamp that is already drawn as
 * a billboard flare double-counts the light and reads as fog.
 *
 * <h2>Why the colour attachment is copied, never sampled in place</h2>
 * The surface term needs the albedo of the pixel it lights, and that pixel is <b>in the attachment
 * this pass writes to</b> - sampling it directly is a read/write feedback loop on one texture. So the
 * colour is blitted once per frame into our own RGBA8 texture ( {@link ColorCopy} ), exactly like the
 * depth ({@link DepthCopy}): two different attachments, two different textures, and the shader only
 * ever reads the copies.
 *
 * <h2>The composite</h2>
 * Because the result depends on the scene colour, this is no longer an additive pass. The shader
 * writes the whole screen and the json deliberately has <b>no {@code blend} section</b>, which is how
 * a core shader says "blending disabled, dst = src" ( {@code GlBlendState#blendDisabled} ):
 *
 * <pre>
 *   added = surfaceSum * (scene * 8.0 + 0.05)
 *   lit   = scene + (1 - exp(-added * strength)) * (1 - 0.5 * scene)
 * </pre>
 *
 * The exponential keeps a bright lamp from clipping to a white card, the {@code 1 - 0.5*scene}
 * factor keeps already-bright pixels from being washed out twice, and {@code added == 0} reproduces
 * {@code scene} exactly - so an empty lamp list or a wrong reconstruction matrix can only leave the
 * frame as it was, never blank it.
 *
 * <h2>Cost</h2>
 * One full-screen quad, one depth blit, one colour blit, and at most {@value ScatterLights#MAX} lamps
 * in the fragment loop. No texture fetch inside the loop, no per-lamp draw. {@code scatterSurface}
 * off skips the whole per-lamp block (a uniform branch, so the normal/spot/N-dot-L work is not
 * executed at all) and the pass writes the scene back unchanged. Zero lamps means the method returns
 * before touching GL - no copies, no framebuffer, no program bind.
 *
 * <h2>Two timings: with and without a shader pack</h2>
 * Without a pack ({@link ShaderPackProbe#active()} false) the whole pass runs inside
 * {@code WorldRenderEvents.LAST}: depth copy, colour copy and the composite, one after the other -
 * that path was already proven in game on 1.18.2 too.
 *
 * <p>With a pack (Iris / OptiFine) {@code LAST} is <b>inside</b> the pack's own world rendering, so
 * anything written there is overwritten by the pack's composite. The pass therefore splits in two
 * ( {@code scatterUnderShaders}, on by default):
 *
 * <pre>
 *   LAST (in the pack's world pass)   depth blit only + record proj*view, lamp arrays,
 *                                     copy size.  No colour is written.
 *   GameRenderer#renderWorld RETURN   {@link #renderDeferred()} (called by
 *   (1.18.2 signature, see below)     GameRendererScatterMixin): the pack has composited, the main
 *                                     framebuffer IS the screen - colour blit + composite here.
 * </pre>
 *
 * <p>{@code renderWorld} on 1.18.2 is {@code (F J MatrixStack)V} - same shape as 1.20.1's
 * {@code renderLevel}, so the mixin target only differs in its remapped name. What does <b>not</b>
 * carry over from 1.20.1 is the matrix: {@link Matrix4f} here is the local shim
 * ({@code dev.js.mtrheadlight.clientgui.Matrix4f}), so the reconstruction matrix is built exactly
 * the way the non-pack path already builds it, is kept in the static {@link #INVERSE} field across
 * the two halves, and is never touched again in {@code renderDeferred()} (there is no
 * {@code WorldRenderContext} and no camera matrix stack at that point).
 *
 * <p>The colour copy deliberately happens in the second step, never in the first: inside the pack's
 * pass the colour attachment holds pre-composite (often HDR) values, which would make a terrible
 * albedo and would be thrown away anyway.
 *
 * <p>With {@code scatterUnderShaders = false} the old behaviour is back: under a pack the pass steps
 * aside entirely with one log line ({@code reason=shader-pack}).
 */
public final class ScatterPass {

    /** Longest view ray integrated, blocks. Also the cut for a sky pixel (depth == 1). */
    static final float MAX_RAY = 96.0F;
    /** Inner / outer cosine of the lamp's light cone (~20 deg / ~57 deg), the surface term's spot. */
    private static final float COS_INNER = 0.94F;
    private static final float COS_OUTER = 0.55F;
    /**
     * Surface attenuation reference, blocks squared: the surface term uses
     * {@code 1 / (1 + d^2 / 9)}, i.e. half strength at 3 blocks, a fifth at 6 - roughly the 1/d^2
     * irradiance a lamp has, softened so the near field does not explode.
     */
    private static final float ATTENUATION_REF = 9.0F;

    private static final long LOG_INTERVAL_MS = 5000L;

    private static final float[] LIGHT_POS = new float[ScatterLights.MAX * 4];
    private static final float[] LIGHT_DIR = new float[ScatterLights.MAX * 4];
    private static final float[] LIGHT_COLOR = new float[ScatterLights.MAX * 4];
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();

    private static boolean packWarned;
    private static boolean depthWarned;
    private static boolean colorWarned;
    private static boolean startupLogged;
    /** 只打一次「scatter 是关的」那一行，避免关掉之后日志彻底安静被读成「日志坏了」。 */
    private static boolean offAnnounced;
    private static String viewSource;
    private static long windowStart = System.currentTimeMillis();
    private static int frames;
    private static int lastLights;
    private static int maxLights;
    private static double msSum;
    private static int msCount;
    /**
     * The lit-pixel probe: {@code /mtr_lte scatter status} arms it, the next frame writes the
     * per-pixel coverage into the output alpha and reads back a downsampled copy. Off by default so
     * a normal frame never pays for a readback. Client tick and render share the thread, so a plain
     * field is enough.
     */
    private static boolean probeRequested;
    private static int lastLitPixels = -1;
    private static long lastProbeMs;
    /**
     * 5 秒窗口到点时置位：下一次 {@code draw()} 之后（程序刚绑过、GL 里就是这一帧真实的值）打一行
     * {@code [lte] scatter upload:}，并把 {@code uLightPos/uLightDir/uLightColor} 的首槽从 GPU 回读出来
     * 对照。这是「uniform 到底传没传上去」唯一能自己回答的证据 —— 1.0.62 里这三个数组根本没到 GPU，
     * 而日志里每个字段看起来都正常。
     */
    private static boolean uploadLogWanted;

    /* ------------------------------------------------------------------------------------------
     * 每 5 秒一行的自诊断。见 {@link #heartbeat}。
     *
     * 这些计数要能回答「pass 到底跑没跑」，所以装在<b>每一个出口</b>上：进世界以后一条日志都没有
     * 的那次反馈，恰恰是因为原来那行藏在 collect() 之后的三道闸后面（光影包 / 程序没编译 / 没灯），
     * 任何一道拦下就整行消失 —— 「没有日志」和「没有效果」看起来就一模一样。
     * ---------------------------------------------------------------------------------------- */

    /** 渲染回调被调到的帧数（含所有提前 return 的帧）。 */
    private static int calls;
    /** 这个窗口里最后一次离开 {@link #render} 的原因，一个词，见各处 {@code lastReason = …}。 */
    private static String lastReason = "no-frame";
    /** 深度重建矩阵的来源：{@code context} / {@code render} / {@code rebuilt}。 */
    private static String lastMatrix = "unknown";
    /** 最近一次深度拷贝的 {@code glGetError()}；-1 = 还没试过，0 = 成功。 */
    private static int lastDepthError = -1;
    /** 第一盏灯的一行描述（pos / dir / rgb / power / radius / beam），{@code 5} 秒一行的第二行。 */
    private static String light0 = "(no lamp)";
    /** {@code 0} 关、{@code 1} surface、{@code 2} normal、{@code 3} cone。只在内存里。 */
    private static int debugMode;
    /** 一组 debug 说明用的名字表，下标就是 {@link #debugMode}。 */
    private static final String[] DEBUG_NAMES = {"off", "surface", "normal", "cone"};
    /** draw 之后从 GL 读回的混合状态（-1 = 这一帧没画）。 */
    private static boolean diagBlendEnabled;
    private static int diagBlendSrc = -1;
    private static int diagBlendDst = -1;

    /* ------------------------------------------------------------------------------------------
     * 光影（Iris / OptiFine）下的「两段式」：LAST 采深度、renderWorld 返回之后合成。
     * 见 {@link #renderDeferred()}。
     * ---------------------------------------------------------------------------------------- */

    /**
     * 延迟合成里两份拷贝与当前帧的一致程度，{@code depth=} 那一格打的就是它：
     * {@code none}（还没采过）/ {@code fresh}（这一帧采的）/ {@code stale…}（上一帧的、没人取的、
     * 或者采集时 glGetError 非 0）。<b>「光影下看不到」时先看这一格。</b>
     */
    private static String depthState = "none";
    /** LAST 里已经采好深度、等着 {@code renderWorld} 返回后合成。每帧进 {@link #render} 时清。 */
    private static boolean deferredArmed;
    /** 采样时刻（纳秒）：延迟合成用它判「这份深度是不是这一帧的」。 */
    private static long deferredStamp;
    /** 采集时的灯数 / 覆盖探针 / 拷贝尺寸。 */
    private static int deferredLights;
    private static boolean deferredCoverage;
    private static int deferredWidth = -1;
    private static int deferredHeight = -1;
    /** 这个窗口里延迟合成成功了几帧（{@code deferred=} 那一格）。 */
    private static int deferredFrames;
    /** 最近一次延迟合成的结果，一个词；{@code defer=} 那一格。 */
    private static String deferredReason = "idle(no deferred attempt yet)";
    /** mixin 到底有没有调进来过（没有 = 注入没插上，或者根本没开光影）。 */
    private static boolean deferredCalledOnce;
    /** 尺寸不一致 / 失败各只提示一次。 */
    private static boolean deferredSizeWarned;
    private static boolean deferredWarned;
    /**
     * 深度算「这一帧的」的窗口：{@code LAST} 与 {@code renderWorld} 的 RETURN 之间只隔几个毫秒，
     * 200 ms 已经宽到能容忍一次卡顿，又窄到不可能跨帧。
     */
    private static final long DEPTH_FRESH_NANOS = 200_000_000L;

    /**
     * 深度重建矩阵的健康度：{@code inverseErr = |inv(proj*view) * (proj*view) - I|} 的最大元素偏差。
     *
     * <p>1.18.2 上这条链曾经整段静默坏死（替身矩阵从原版矩阵拷贝时每帧抛
     * {@code BufferUnderflowException}），而它不会在任何别的地方留下痕迹 —— 画面只是「没有光」。
     * 这个数字把「矩阵到底是不是对的」变成一个能看的格子（{@code inverse=ok|bad(0.99)}），
     * 与 1.19.2 / 1.19.4 那两份的诊断行保持同一套字段。
     */
    private static boolean inverseOk;
    private static float inverseErr = Float.POSITIVE_INFINITY;
    /** {@code cfg == null} 的那一行只打一次（启动头几帧）。 */
    private static boolean noConfigAnnounced;

    private ScatterPass() {
    }

    /**
     * {@code /mtr_lte scatter debug off|surface|normal|cone}：把整屏换成一项的纯色可视化。
     *
     * <table border="1">
     *   <caption>三种图案怎么读</caption>
     *   <tr><th>模式</th><th>屏幕</th><th>读到什么</th></tr>
     *   <tr><td>{@code surface}</td><td>R = max(N·L)，G = max(锥内权重)，B = max(距离衰减 × 窗口)</td>
     *       <td>三色都在 = 三项都通过；只有红 = 法线对但灯照不到这里；只有绿 = 锥对但法线朝下（N·L 被
     *           max(·,0) 截成 0）；只有蓝 = 距离对但锥/法线有一项是 0</td></tr>
     *   <tr><td>{@code normal}</td><td>RGB = 法线编码（地面朝上 → 绿），黑 = 天空，洋红 = 轮廓伪影
     *       （normalValid=false，这些像素永远没有表面项）</td>
     *       <td>整屏洋红 = 法线判定把有效像素全否掉了；整屏黑 = 深度重建出来的 P 是错的（没有表面）</td></tr>
     *   <tr><td>{@code cone}</td><td>R = 灯轴与灯→像素方向的余弦，G = smoothstep 后的锥内权重，
     *       B = 至少被一盏灯看到</td>
     *       <td>B=0 = 灯列表空/像素在灯后面（与锥角无关）；B=1 且 G=0 = 余弦低于 cosOuter，
     *           灯确实指向别处（朝向反了）</td></tr>
     * </table>
     *
     * <p>状态只在内存里，和 {@code /mtr_lte lighttest} 一样：它不是玩家的设置，退出游戏即失效。
     *
     * @return 一行给玩家的回答（指令层直接转述）
     */
    public static String setDebug(final String what) {
        final String key = what == null ? "" : what.trim().toLowerCase(Locale.ROOT);
        int mode = -1;
        for (int i = 0; i < DEBUG_NAMES.length; i++) {
            if (DEBUG_NAMES[i].equals(key)) {
                mode = i;
            }
        }
        if (mode < 0) {
            return "scatter debug " + DEBUG_NAMES[debugMode] + "（可选：off / surface / normal / cone）";
        }
        debugMode = mode;
        if (!ScatterProgram.debugAvailable() && mode > 0) {
            return "scatter debug=" + DEBUG_NAMES[mode] + " 但着色器里没有 uDebug：画不出来"
                    + "（pass 本身不受影响，见日志里的 uniform-missing 警告）";
        }
        return "scatter debug=" + DEBUG_NAMES[mode] + (mode == 0 ? "" : switch (mode) {
            case 1 -> "（R = N·L，G = 锥内，B = 距离衰减）";
            case 2 -> "（RGB = 法线，洋红 = 轮廓伪影，黑 = 天空）";
            default -> "（R = 灯轴余弦，G = 锥内权重，B = 有灯）";
        });
    }

    /** {@code off} / {@code surface} / {@code normal} / {@code cone}，日志和 {@code status} 用。 */
    public static String debugName() {
        return DEBUG_NAMES[debugMode];
    }

    /**
     * Compiles the shader as early as possible (the client tick runs on the render thread, and the
     * title screen has a live GL context): a shader that does not compile is then visible in the log
     * before the player is in a world, and the cost is paid once.
     */
    public static void tick(final MinecraftClient client) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null || !cfg.enabled || !cfg.beamScatter) {
                return;
            }
            if (client == null || client.getWindow() == null) {
                return;
            }
            if (ScatterProgram.ensure(client) && !startupLogged) {
                startupLogged = true;
                // Same shape as the per-window line, so one grep over a log answers "did the shader
                // ever compile" even on a run that never entered a world (this is the evidence the
                // title-screen probe produces).
                MtrHeadlightClient.LOGGER.info("[lte] scatter: lights=0/{} program=ok depth=none "
                        + "surface={} frames=0 candidates=0 (shader compiled; the pass runs once a "
                        + "world with lit lamps is drawn)", Integer.valueOf(ScatterLights.MAX),
                        cfg.scatterSurface ? "on" : "off");
            }
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] scatter: shader probe failed", t);
        }
    }

    /** The pass itself. Never throws: a broken pass must not take the frame down. */
    public static void render(final WorldRenderContext context) {
        // 让 [lte] lighttest: 那一行始终有窗口在推进（灯晕那一侧也会调，谁先到谁打）。
        LightTest.report();
        /*
         * 每 5 秒的那一行在<b>这里</b>打，也就是在任何闸门之前：被光影包拦下、程序没编译、这局没有
         * 一盏灯，全都会在日志里留下 frames / reason，而不是让玩家对着一个安静的日志猜。
         */
        calls++;
        /*
         * 上一帧「等合成」的标记如果还挂着，说明 {@code renderWorld} 的 RETURN 没有来取它（mixin 没
         * 插上，或者两趟之间游戏早退了）。那份深度对新的一帧已经过期，绝不能再拿去合成：记成 stale
         * 并把原因写进日志，{@code defer=} 那一格就是「注入到底有没有生效」的答案。
         *
         * <p>放在 heartbeat 之前：这一行日志要报的是「上一帧那趟合成成没成」，而不是马上要被这次
         * 采集覆盖掉的新状态。
         */
        if (deferredArmed) {
            deferredArmed = false;
            depthState = "stale(unconsumed)";
            deferredReason = "not-consumed";
        }
        heartbeat(MtrHeadlightClient.config());
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) {
                lastReason = "no-world";
                return;
            }
            /*
             * 取证探针（/mtr_lte lighttest）：一次同屏两块纯色，都不采灯、不拷深度，也不看
             * beamScatter / shader 包判定。
             *   左半屏红 = 本 pass 自己的 light_scatter 程序（uTest=1 时整段 main() 只写纯红）；
             *   右半屏蓝 = 原版 position_color 程序（GlowCompat.drawScreenQuad），完全不碰我们的
             *              程序，只证明「LAST 这个时机本来就能往主 framebuffer 写」。
             * 于是「只有蓝没有红」= 断在我们这条程序上；「两半都没有」= 整条 LAST 写入链路在别处；
             * 「有红而光雾仍然没有」= 程序与附件都对，问题在 shader 的深度重建与灯参数。
             * 见 {@link dev.js.mtrheadlight.LightTest}。
             */
            if (LightTest.enabled()) {
                lastReason = "lighttest";
                // 左半屏：本程序自己输出纯红（uTest=1）—— 「散射 pass 真在写颜色」的直接证据。
                tintThroughProgram(client);
                // 右半屏：原版程序画的蓝，作为「这个时机本来就能写」的对照。
                LightTest.drawFallbackTint();
                return;
            }
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            /*
             * 帧级唯一门。drawScatter() = 总开关 && beamScatter。
             *
             * 散射是三件可见光效里独立的那一件（光带 / 灯晕 / 散射各有自己的开关，互不影响），
             * 所以这里只问它自己的开关，任何"样式"都不再参与判断。
             */
            if (cfg == null || !cfg.drawScatter()) {
                lastReason = cfg == null ? "no-config"
                        : (!cfg.enabled ? "off(master)" : "off(beamScatter=false)");
                return;
            }
            /*
             * 光影包（Iris / OptiFine）接管管线时走<b>两段式</b>：LAST 只采深度（下面的 DepthCopy），
             * 颜色合成推迟到 GameRenderer#renderWorld 返回之后 —— 见 {@link #renderDeferred()}。
             *
             * <p>scatterUnderShaders=false 时退回旧行为：光影下整条 pass 让路，只留一行日志。
             */
            final boolean pack = shaderPackActive();
            if (pack && !cfg.scatterUnderShaders) {
                lastReason = "shader-pack(scatterUnderShaders=false)";
                if (!packWarned) {
                    packWarned = true;
                    MtrHeadlightClient.LOGGER.info(
                            "[lte] scatter: shader pack active and scatterUnderShaders=false - the "
                                    + "screen-space scattering pass stays out of the pipeline (the "
                                    + "pack owns the framebuffer and the depth buffer); turn "
                                    + "\"air scatter under shaders\" on to composite after the pack");
                }
                return;
            }
            if (!ScatterProgram.ensure(client)) {
                lastReason = "program=" + ScatterProgram.status();
                return;
            }
            // 表面项开着的时候每 5 秒自动量一次 litPx，让那条周期行自己带上数字（一次 20 KB 回读）。
            lastMatrix = viewSource == null ? "unknown" : viewSource;

            final int lights = ScatterLights.collect(client, cfg, LIGHT_POS, LIGHT_DIR, LIGHT_COLOR);
            if (lights == 0) {
                // Zero lamps: no depth copy, no framebuffer, nothing bound. This is the common case
                // on a map without trains and it has to be free.
                lastLights = 0;
                light0 = "(no lamp this frame; candidates=" + ScatterLights.candidates() + ")";
                lastReason = "no-lights";
                return;
            }
            describeFirstLight(lights);

            final Framebuffer framebuffer = client.getFramebuffer();
            if (framebuffer == null) {
                lastReason = "no-framebuffer";
                return;
            }
            final long started = System.nanoTime();
            if (!DepthCopy.update(framebuffer)) {
                lastDepthError = DepthCopy.lastError();
                lastReason = "depth-copy-failed(err=0x"
                        + Integer.toHexString(Math.max(0, lastDepthError)) + ")";
                if (!depthWarned) {
                    depthWarned = true;
                    MtrHeadlightClient.LOGGER.warn("[lte] scatter: the scene depth could not be "
                            + "copied this frame - the pass stays off (it never draws against a "
                            + "stale or empty depth buffer)");
                }
                return;
            }
            lastDepthError = DepthCopy.lastError();
            // 这一帧的深度是真的拷到了 —— `depth=` 那一格由这一行负责（见 depthState 的注释）。
            depthState = "fresh";

            /*
             * Depth reconstruction matrix: inverse(projection * view). Everything stays
             * camera-relative - the lamp positions are already relative to the camera - so no large
             * world coordinates ever reach a float in the shader.
             *
             * <p>光影路径下这一份矩阵在 LAST 里算、在 renderDeferred() 里用：那时已经没有
             * WorldRenderContext（也没有相机矩阵栈），而建矩阵要用到 context 的视图矩阵。
             * {@link Matrix4f} 是 1.18.2 的替身（没有 JOML），所以也就是非光影路径一直在用的那套
             * 算法与自检，一行都不用改。
             */
            final Matrix4f view = viewMatrix(context, client);
            VIEW_PROJ.set(RenderSystem.getProjectionMatrix()).mul(view);
            INVERSE.set(VIEW_PROJ).invert();
            /*
             * 求完逆立刻自证一次：{@code INVERSE * VIEW_PROJ} 必须等于单位阵。
             * 替身矩阵的 invert() 是标准余子式展开（不是 1.19.2 那份会把投影矩阵缩放掉的
             * 原版实现），所以这里正常情况下应该是个 1e-6 量级的数；一旦它是 0.99 这种，
             * 就别再去 shader 里找原因了。
             */
            inverseErr = inverseError(INVERSE, VIEW_PROJ);
            inverseOk = inverseErr < 1.0E-3F;
            lastMatrix = viewSource == null ? "unknown" : viewSource;

            /*
             * 覆盖探针：命令点名的下一次，或者「距离上次测量已经 5 秒」。后者让周期行里的 litPx
             * 始终是一个真实的数（一次 9600 像素的降采样回读，5 秒一次，代价可以忽略）。
             * debug 模式下 alpha 被 debug 输出占满，量了也没意义，所以那一刻不量。
             */
            final boolean coverage = debugMode == 0
                    && (probeRequested || (cfg.scatterSurface && litProbeDue()));

            if (pack) {
                /*
                 * 光影路径 #1（在 LAST 里）：到这里为止 —— 深度已在上面拷好，重建矩阵与灯阵都在
                 * 静态字段里，尺寸记下来。
                 *
                 * <b>一个颜色像素都不写</b>：此刻的颜色附件是光影合成<b>之前</b>的内容（很多包是
                 * HDR 中间结果，值可以 &gt; 1），既不能当 albedo，写进去也会被它自己的 composite
                 * 盖掉。颜色拷贝与整屏合成交给 {@link #renderDeferred()}（renderWorld 返回之后）。
                 */
                deferredLights = lights;
                deferredCoverage = coverage;
                deferredWidth = DepthCopy.width();
                deferredHeight = DepthCopy.height();
                deferredStamp = System.nanoTime();
                deferredArmed = true;
                deferredReason = "armed(waiting for renderWorld return)";
                lastLights = lights;
                maxLights = Math.max(maxLights, lights);
                lastReason = "pack(deferred)";
                return;
            }

            /*
             * The colour copy is not optional any more: the composite writes
             * scene + light * albedo, so without a copy of the scene there is nothing to write back
             * (the pass would have to sample the attachment it is drawing into). Failing here means
             * no pass at all - not a black screen. 光影路径下这一份拷贝在 renderDeferred() 里做
             * （那时颜色附件才是屏幕上真正看到的内容）。
             */
            if (!ColorCopy.update(framebuffer)) {
                lastReason = "color-copy-failed";
                if (!colorWarned) {
                    colorWarned = true;
                    MtrHeadlightClient.LOGGER.warn("[lte] scatter: the scene colour could not be "
                            + "copied this frame - the pass stays off (there is no albedo to light)");
                }
                return;
            }

            ScatterProgram.uniforms(INVERSE, MAX_RAY, COS_INNER, COS_OUTER, strength(cfg),
                    ATTENUATION_REF, cfg.scatterSurface, coverage, debugMode, lights,
                    LIGHT_POS, LIGHT_DIR, LIGHT_COLOR);

            draw();
            if (uploadLogWanted) {
                // 5 秒行里「这一次到底传上去的是什么」：先是我们填进去的，再是 GPU 回读的。
                uploadLogWanted = false;
                logUpload(lights);
            }

            if (coverage) {
                // Blit + 20 KB readback, once - only on the frame the status command asked for.
                probeRequested = false;
                lastLitPixels = ColorCopy.probeLitPixels(framebuffer);
                lastProbeMs = System.currentTimeMillis();
            }
            lastLights = lights;
            maxLights = Math.max(maxLights, lights);
            frames++;
            msSum += (System.nanoTime() - started) / 1.0E6D;
            msCount++;
            lastReason = "ok";
        } catch (Throwable t) {
            lastReason = "exception:" + t.getClass().getSimpleName();
            MtrHeadlightClient.LOGGER.error("[lte] scatter: pass failed", t);
        }
    }

    /** 5 秒一次自动量 litPx 的时间窗（和日志窗口同频）。 */
    private static boolean litProbeDue() {
        return System.currentTimeMillis() - lastProbeMs >= LOG_INTERVAL_MS;
    }

    /**
     * <b>光影下真正写颜色的那一趟</b>：由 {@code GameRendererScatterMixin} 在
     * {@code GameRenderer#renderWorld(float, long, MatrixStack)}<b>返回之后</b>调用。
     *
     * <p>那时 Iris / OptiFine 已经把自己的 composite 走完，主帧缓冲就是屏幕上那一帧，所以我们
     * 写的颜色不会被谁覆盖。这里做三件事，顺序不能换：
     * <ol>
     *   <li><b>校验</b>：这一帧在 LAST 里采过深度（{@link #deferredArmed} 由 {@code render}
     *       置位、这里消费）、深度纹理 id 与尺寸有效、{@code glGetError()} 为 0、采样到现在不超过
     *       {@link #DEPTH_FRESH_NANOS}。任何一项不满足就<b>安静跳过</b>（一次性 WARN），绝不拿一份
     *       过期或空的深度去重建画面；</li>
     *   <li><b>颜色拷贝</b>：{@link ColorCopy#update} 在<b>这一刻</b>做（不是 LAST 里），此刻的颜色
     *       附件才是玩家真正看到的画面；</li>
     *   <li><b>合成</b>：{@link #drawInto} 自己绑帧缓冲、自己设视口、画完把两者都放回原样。</li>
     * </ol>
     *
     * <p>重建矩阵不在这里算：它是 LAST 里用替身 {@link Matrix4f} 算好的 {@link #INVERSE}，此刻
     * 既没有 {@code WorldRenderContext} 也没有相机矩阵栈可用。<b>不要</b>在这里重建 —— 1.18.2 的
     * 替身矩阵曾经在「从原版矩阵拷贝」这一步每帧抛异常，那种错误在渲染返回路径上会吃掉整帧。
     *
     * <p>没有光影包时这里什么都不做 —— 那条路已经在 LAST 里画完了，再画一遍就是把光叠两次。
     *
     * <p>方法体里 catch 一切：世界渲染的这条返回路径上，一个异常就够让整个画面停住。
     */
    public static void renderDeferred() {
        if (!deferredCalledOnce) {
            deferredCalledOnce = true;
            MtrHeadlightClient.LOGGER.info("[lte] scatter: deferred hook reached (renderWorld "
                    + "return) - the shader-pack path composites after the pack, not in LAST");
        }
        if (!deferredArmed) {
            // 每帧都会被调到；只有 LAST 采过深度的帧才有活干（无光影时永远没活干）。
            return;
        }
        deferredArmed = false;
        final long started = System.nanoTime();
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (cfg == null || !cfg.drawScatter()) {
                deferredReason = "off(beamScatter)";
                return;
            }
            if (!cfg.scatterUnderShaders) {
                deferredReason = "off(scatterUnderShaders=false)";
                return;
            }
            if (!shaderPackActive()) {
                // 无光影：LAST 已经把整屏合成完了，这里再画一遍就是把光叠两次。
                deferredReason = "no-pack(already drawn in LAST)";
                return;
            }
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null) {
                deferredReason = "no-world";
                return;
            }
            if (!ScatterProgram.ready()) {
                deferredReason = "program=" + ScatterProgram.status();
                return;
            }
            /*
             * 深度校验。`id != -1 / 尺寸 > 0 / glGetError()==0 / 采样不过 200 ms` 四条全过才画：
             * 一份空的或上一帧的深度会让整个屏幕的法线全是垃圾 —— 那比"这一帧没有光"糟糕得多。
             */
            final long age = System.nanoTime() - deferredStamp;
            if (DepthCopy.texture() == -1 || DepthCopy.width() <= 0 || DepthCopy.height() <= 0
                    || DepthCopy.lastError() != 0 || age < 0L || age > DEPTH_FRESH_NANOS) {
                depthState = "stale(" + (age < 0L ? "clock" : "age=" + (age / 1_000_000L) + "ms")
                        + (DepthCopy.texture() == -1 ? ",no-texture" : "")
                        + (DepthCopy.lastError() != 0 ? ",err=0x"
                                + Integer.toHexString(Math.max(0, DepthCopy.lastError())) : "")
                        + ")";
                deferredReason = "depth-stale";
                warnDeferredOnce("the depth copy for the deferred composite was stale");
                return;
            }
            final Framebuffer framebuffer = client.getFramebuffer();
            if (framebuffer == null) {
                deferredReason = "no-framebuffer";
                return;
            }
            /*
             * Iris 会自己搞多重缓冲，最终帧缓冲的尺寸可以和 LAST 里采样的那个不一样。全屏四边形是
             * NDC 的（顶点是 -1..1），深度/颜色纹理按 UV 采样，所以尺寸不同只会把光"拉伸"到新
             * 分辨率上，不会画空 —— 只是值得在日志里说一次。
             */
            if (!deferredSizeWarned
                    && (deferredWidth != framebuffer.viewportWidth
                            || deferredHeight != framebuffer.viewportHeight)) {
                deferredSizeWarned = true;
                MtrHeadlightClient.LOGGER.info("[lte] scatter: deferred draw at {}x{} but the depth "
                                + "copy is {}x{} - the quad is NDC so this only stretches the light "
                                + "(Iris resized its framebuffer after LAST)",
                        Integer.valueOf(framebuffer.viewportWidth),
                        Integer.valueOf(framebuffer.viewportHeight),
                        Integer.valueOf(deferredWidth), Integer.valueOf(deferredHeight));
            }
            if (!ColorCopy.update(framebuffer)) {
                deferredReason = "color-copy-failed";
                warnDeferredOnce("the scene colour could not be copied after the shader pack "
                        + "composited");
                return;
            }
            ScatterProgram.uniforms(INVERSE, MAX_RAY, COS_INNER, COS_OUTER, strength(cfg),
                    ATTENUATION_REF, cfg.scatterSurface, deferredCoverage, debugMode,
                    deferredLights, LIGHT_POS, LIGHT_DIR, LIGHT_COLOR);
            if (!drawInto(framebuffer)) {
                deferredReason = "framebuffer-not-writable";
                warnDeferredOnce("the framebuffer the shader pack left bound cannot be drawn into");
                return;
            }
            if (deferredCoverage) {
                probeRequested = false;
                lastLitPixels = ColorCopy.probeLitPixels(framebuffer);
                lastProbeMs = System.currentTimeMillis();
            }
            deferredFrames++;
            frames++;
            msSum += (System.nanoTime() - started) / 1.0E6D;
            msCount++;
            deferredReason = "ok";
            lastReason = "ok(deferred)";
        } catch (Throwable t) {
            deferredReason = "exception:" + t.getClass().getSimpleName();
            warnDeferredOnce("the deferred composite failed (the world render itself is untouched)");
        }
    }

    /** 延迟路径的失败只记一次：一帧一次会把日志刷满，而这些原因一晚上不会变。 */
    private static void warnDeferredOnce(final String what) {
        if (deferredWarned) {
            return;
        }
        deferredWarned = true;
        MtrHeadlightClient.LOGGER.warn("[lte] scatter: {} - the deferred pass stays off until it can "
                + "be done safely (see the 5 s \"defer=\" field for the current reason)", what);
    }

    /** 清掉挂起的 GL 错误，让紧接着的一次查询只可能是我们自己那条调用产生的。 */
    private static void drainErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // drain
        }
    }

    /**
     * 在<b>已经不属于我们</b>的时刻画这一屏：自己绑帧缓冲、自己设视口，画完把两者都放回原样。
     *
     * <p>{@code renderWorld} 返回时原版刚刚走完自己的帧缓冲收尾，绑定与视口都不一定还是我们以为
     * 的那一套（Iris 更是一定换过）。所以这里不走 {@code beginWrite/endWrite} 那对语义，而是：
     * 记下当前 {@code GL_FRAMEBUFFER_BINDING} 与 {@code GL_VIEWPORT} → 绑主帧缓冲、设整屏视口 →
     * 画 → 原样恢复。任何一步失败都只是"这一帧没有光"，不影响谁。
     *
     * <p>1.18.2 的 {@code GlStateManager} 已经有 {@code _glBindFramebuffer} / {@code _viewport} /
     * {@code glCheckFramebufferStatus}（GL 3.2 core 的路径），所以这里不需要退回裸 GL 调用；两份
     * 状态的恢复放在 {@code finally} 里，异常路径上 HUD 和手也不会拿到一个陌生的视口。
     *
     * @return {@code false} 表示目标帧缓冲不完整（Iris 的特殊 FBO），调用方跳过即可
     */
    private static boolean drawInto(final Framebuffer framebuffer) {
        final int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        final int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        final int width = Math.max(1, framebuffer.viewportWidth);
        final int height = Math.max(1, framebuffer.viewportHeight);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer.fbo);
            if (GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                    != GL30.GL_FRAMEBUFFER_COMPLETE) {
                return false;
            }
            GlStateManager._viewport(0, 0, width, height);
            // 先清掉别人留下的错误，再画：这样 `glGetError()` 读到的就一定是<b>我们这一笔</b>的
            // 结果（不可写的颜色附件在这里报 GL_INVALID_FRAMEBUFFER_OPERATION）。
            drainErrors();
            draw();
            return GL11.glGetError() == GL11.GL_NO_ERROR;
        } finally {
            /*
             * 恢复的是「别人的」状态：视口尺寸、帧缓冲绑定。留在我们身上就等于把后半帧交给一个
             * 别人不知道的视口（手、HUD、光影下一帧的 composite 全都会画错）。
             */
            try {
                GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            } catch (Throwable ignored) {
                // Lost context: nothing to restore.
            }
        }
    }

    /**
     * 第一盏灯的一行描述。整条 pass 的每一个数都可能是对的，而<b>灯本身</b>送错了（位置在世界坐标
     * 而不是相机相对、rgb 全 0、锥角算反）—— 那就永远查不到 shader 里去。这一行把它变成可读的：
     * <pre>
     *   [lte] scatter light0: pos=(1.23,-0.50,4.00) dir=(0.00,0.00,-1.00) rgb=(1.00,0.95,0.85)
     *                         power=0.62 radius=6.0 beam=32.0 coneDeg=20.0/56.6
     * </pre>
     * {@code coneDeg} 是 {@code cosInner/cosOuter} 反算出来的角度（内角必须<b>小于</b>外角，内角
     * 那片是满强度，外角之外是 0）。
     */
    private static void describeFirstLight(final int lights) {
        try {
            final float r = LIGHT_POS[3];
            final float beam = LIGHT_DIR[3];
            light0 = String.format(Locale.ROOT,
                    "pos=(%.2f,%.2f,%.2f) dir=(%.2f,%.2f,%.2f) rgb=(%.2f,%.2f,%.2f) "
                            + "power=%.2f radius=%.1f beam=%.1f coneDeg=%.1f/%.1f lamps=%d",
                    Float.valueOf(LIGHT_POS[0]), Float.valueOf(LIGHT_POS[1]),
                    Float.valueOf(LIGHT_POS[2]),
                    Float.valueOf(LIGHT_DIR[0]), Float.valueOf(LIGHT_DIR[1]),
                    Float.valueOf(LIGHT_DIR[2]),
                    Float.valueOf(LIGHT_COLOR[0]), Float.valueOf(LIGHT_COLOR[1]),
                    Float.valueOf(LIGHT_COLOR[2]), Float.valueOf(LIGHT_COLOR[3]),
                    Float.valueOf(r), Float.valueOf(beam),
                    Double.valueOf(Math.toDegrees(Math.acos(Math.max(-1.0D,
                            Math.min(1.0D, COS_INNER))))),
                    Double.valueOf(Math.toDegrees(Math.acos(Math.max(-1.0D,
                            Math.min(1.0D, COS_OUTER))))),
                    Integer.valueOf(lights));
        } catch (Throwable t) {
            light0 = "(unreadable:" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 每 5 秒<b>无条件</b>一行（只要 {@code beamScatter=true}）：pass 有没有跑、跑了几帧、
     * 每帧几盏灯、两份拷贝成不成、litPx、深度重建矩阵来自哪个候选。
     *
     * <pre>
     *   [lte] scatter: frames=300 lights=4 program=ok depth=1920x1080 ok color=1920x1080 ok
     *                  litPx≈15234 inverse=ok blend=replace matrix=context calls=301 maxLights=4
     *                  avgMs=0.42 surface=on debug=off pack=false reason=ok
     *   [lte] scatter light0: pos=(…) dir=(…) rgb=(…) power=… radius=… beam=… coneDeg=20.0/56.6 depthErr=ok
     * </pre>
     *
     * {@code frames=0 reason=…} 就是「为什么看不到效果」的答案，一行之内给全：
     * <ul>
     *   <li>{@code no-lights} —— collect() 一盏灯都没拿到（车没点亮 / 太远 / 灯晕开关关着）；</li>
     *   <li>{@code shader-pack(…)} —— 光影包接管了 framebuffer，我们主动让路；</li>
     *   <li>{@code depth-copy-failed(err=0x…)} / {@code color-copy-failed} —— 拷贝这一步没成；</li>
     *   <li>{@code program=failed(…)} —— 程序没编译（原因就是 json/GLSL 的那个字符串）；</li>
     *   <li>{@code off(master)} / {@code off(beamScatter=false)} —— 开关没开。</li>
     * </ul>
     */
    private static void heartbeat(final HeadlightConfig cfg) {
        final long now = System.currentTimeMillis();
        if (cfg == null) {
            // 配置还没建出来（启动头几帧）：也照打一行，否则「一行都没有」会被读成「pass 没被调用」。
            if (!noConfigAnnounced) {
                noConfigAnnounced = true;
                MtrHeadlightClient.LOGGER.info("[lte] scatter: frames=0 lights=0 program=none "
                        + "depth=none color=none litPx=none inverse=none pack=false "
                        + "deferred=false defer=never-called reason=no-config");
            }
            return;
        }
        if (!cfg.beamScatter) {
            // 关着的时候只提醒一次：否则「没日志」会被读成「日志坏了」。
            if (!offAnnounced) {
                offAnnounced = true;
                MtrHeadlightClient.LOGGER.info("[lte] scatter: off (beamScatter=false) - nothing is "
                        + "drawn and nothing is measured; /mtr_lte scatter on 打开");
            }
            windowStart = now;
            calls = 0;
            return;
        }
        if (now - windowStart < LOG_INTERVAL_MS) {
            return;
        }
        windowStart = now;
        final double avg = msCount == 0 ? 0.0D : msSum / msCount;
        // 这一窗口结束，下一次 pass 画完时把「上传了什么 / GPU 里是什么」打一行。
        uploadLogWanted = true;
        ScatterProgram.requestReadback();
        MtrHeadlightClient.LOGGER.info(String.format(Locale.ROOT,
                "[lte] scatter: frames=%d pack=%s deferred=%s depth=%s lights=%d program=%s "
                        + "color=%s litPx=%s inverse=%s blend=%s matrix=%s calls=%d maxLights=%d "
                        + "avgMs=%.2f surface=%s shaders=%s debug=%s defer=%s reason=%s",
                Integer.valueOf(frames), Boolean.valueOf(shaderPackActive()),
                Boolean.valueOf(deferredFrames > 0), depthText(), Integer.valueOf(lastLights),
                ScatterProgram.status(), colorText(), litPixels(cfg), inverseText(), blendText(),
                lastMatrix, Integer.valueOf(calls), Integer.valueOf(maxLights), Double.valueOf(avg),
                cfg.scatterSurface ? "on" : "off", cfg.scatterUnderShaders ? "on" : "off",
                debugName(), deferredCalledOnce ? deferredReason : "never-called", lastReason));
        MtrHeadlightClient.LOGGER.info("[lte] scatter light0: {} depthErr={}",
                light0, lastDepthError < 0 ? "none"
                        : (lastDepthError == 0 ? "ok" : "0x" + Integer.toHexString(lastDepthError)));
        frames = 0;
        calls = 0;
        maxLights = 0;
        msSum = 0.0D;
        msCount = 0;
        deferredFrames = 0;
    }

    /**
     * 每 5 秒一行：<b>这一帧真正推给 GPU 的灯参数</b>，外加 GPU 回读的首槽做对照。
     *
     * <pre>
     *   [lte] scatter upload: pos=(1.23,-0.50,4.00) dir=(0.00,0.00,-1.00) rgb=(1.00,0.95,0.85)
     *                         cosIn=0.94 cosOut=0.55 power=0.62 radius=6.0 beam=32.0 lamps=4
     *                         gl[0] pos=(1.23,-0.50,4.00) dir=(0.00,0.00,-1.00) rgb=(1.00,0.95,0.85) power=0.62
     * </pre>
     *
     * 两段必须一致。{@code gl[0]} 是 {@code glGetUniformfv} 从<b>程序里</b>读回来的，不是我们以为
     * 传进去的：1.0.62 的那次故障里第一段一直是好的（{@code TrainLamps} 的数是真值），第二段是
     * {@code power=0} —— 因为 json 里把 {@code vec4[16]} 声明成 {@code float}, 驱动用
     * {@code glUniform1fv} 收了它，{@code GL_INVALID_OPERATION}，数组停在默认值 0。于是
     * {@code if (power <= 1e-4) continue;} 把整个循环跳空，表面项、体积项、三个 debug 通道全是 0，
     * 而 {@code debug normal} 照常（它一盏灯都不需要）。
     *
     * <p>{@code dir} 的语义只有一种，与 {@code docs/screen-space-scatter.md} 的约定一致：
     * <b>{@code uLightDir = 灯照射方向（朝车外，单位向量）}</b>。着色器里扇面轴就是
     * {@code +dir}：{@code dot(axis, lamp -> P)}。这一行打印的 {@code dir} 应该指向车头前方
     * （车尾灯则指向车后），而不是指回车厢。
     */
    private static void logUpload(final int lights) {
        final String staged = String.format(Locale.ROOT,
                "pos=(%.2f,%.2f,%.2f) dir=(%.2f,%.2f,%.2f) rgb=(%.2f,%.2f,%.2f) cosIn=%.2f "
                        + "cosOut=%.2f power=%.2f radius=%.1f beam=%.1f lamps=%d",
                Float.valueOf(LIGHT_POS[0]), Float.valueOf(LIGHT_POS[1]), Float.valueOf(LIGHT_POS[2]),
                Float.valueOf(LIGHT_DIR[0]), Float.valueOf(LIGHT_DIR[1]), Float.valueOf(LIGHT_DIR[2]),
                Float.valueOf(LIGHT_COLOR[0]), Float.valueOf(LIGHT_COLOR[1]),
                Float.valueOf(LIGHT_COLOR[2]), Float.valueOf(COS_INNER), Float.valueOf(COS_OUTER),
                Float.valueOf(LIGHT_COLOR[3]), Float.valueOf(LIGHT_POS[3]),
                Float.valueOf(LIGHT_DIR[3]), Integer.valueOf(lights));
        final float[] gl = ScatterProgram.lastReadback();
        String back;
        if (gl == null) {
            back = "gl[0]=missing(readback not taken)";
        } else {
            back = String.format(Locale.ROOT,
                    "gl[0] pos=(%.2f,%.2f,%.2f) dir=(%.2f,%.2f,%.2f) rgb=(%.2f,%.2f,%.2f) power=%.2f "
                            + "%s",
                    Float.valueOf(gl[0]), Float.valueOf(gl[1]), Float.valueOf(gl[2]),
                    Float.valueOf(gl[4]), Float.valueOf(gl[5]), Float.valueOf(gl[6]),
                    Float.valueOf(gl[8]), Float.valueOf(gl[9]), Float.valueOf(gl[10]),
                    Float.valueOf(gl[11]),
                    gl[11] > 1.0E-4F ? ""
                            : " <- the GPU never got the lamps: power is still 0, the per-lamp loop "
                                    + "is skipped entirely");
        }
        MtrHeadlightClient.LOGGER.info("[lte] scatter upload: {} {}", staged, back);
    }

    /**
     * {@code |a * b - I|} 的最大元素偏差：给 5 秒行里 {@code inverse=} 这一格当「逆矩阵到底对不对」的证据。
     * 只在对角线上应当是 1、其余是 0，所以一次乘加和取绝对值就够了。
     */
    private static float inverseError(final Matrix4f a, final Matrix4f b) {
        if (a == null || b == null) {
            return Float.POSITIVE_INFINITY;
        }
        final float[] ea = a.columnMajor();
        final float[] eb = b.columnMajor();
        float worst = 0.0F;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                float sum = 0.0F;
                for (int k = 0; k < 4; k++) {
                    // 列主序：元素 (row, col) 在 col * 4 + row。
                    sum += ea[k * 4 + row] * eb[col * 4 + k];
                }
                final float want = row == col ? 1.0F : 0.0F;
                worst = Math.max(worst, Math.abs(sum - want));
            }
        }
        return worst;
    }

    /** {@code inverse=} 那一格（{@code none} = 本窗口还没算过矩阵）。 */
    private static String inverseText() {
        if (Float.isInfinite(inverseErr)) {
            return "none";
        }
        return inverseOk ? "ok"
                : String.format(Locale.ROOT, "bad(%.3f)", Float.valueOf(inverseErr));
    }

    /**
     * {@code replace} / {@code add} / {@code alpha} / {@code off}：draw 之后从 GL 读回的混合状态。
     * 这条 pass 必须是 {@code replace}（dst = src，着色器写整屏颜色）；如果这里出现别的值，说明
     * 程序 bind 时把混合开回来了 —— 那会让「照在墙上的光」变成一层叠加的半透明色。
     */
    private static String blendText() {
        if (diagBlendSrc == -1) {
            return "none";                 // 本窗口一次都没画过
        }
        if (!diagBlendEnabled) {
            return "replace";
        }
        if (diagBlendSrc == 0x0302 && diagBlendDst == 0x0001) {
            return "add";
        }
        if (diagBlendSrc == 0x0302 && diagBlendDst == 0x0303) {
            return "alpha";
        }
        return "on(0x" + Integer.toHexString(diagBlendSrc) + ":0x"
                + Integer.toHexString(diagBlendDst) + ")";
    }

    /** {@code fresh 1920x1080 ok} / {@code stale(unconsumed) 1920x1080 ok} / {@code none}：拷贝的
     * <b>新鲜度</b>和<b>尺寸/成败</b>是三件事，一起报 —— 光影下"看不到"的第一步就是看这里。 */
    private static String depthText() {
        if (DepthCopy.texture() == -1) {
            return depthState.equals("none") ? "none" : depthState + " none";
        }
        return depthState + " " + DepthCopy.width() + "x" + DepthCopy.height()
                + (lastDepthError == 0 ? " ok" : " err=0x" + Integer.toHexString(
                        Math.max(0, lastDepthError)));
    }

    private static String colorText() {
        if (ColorCopy.texture() == -1) {
            return "none";
        }
        return ColorCopy.width() + "x" + ColorCopy.height() + " ok";
    }

    /** One full-screen quad in NDC. The shader builds its own rays; no geometry is involved. */
    private static void draw() {
        RenderSystem.setShader(ScatterProgram::program);
        // -1 = 这一帧还没有拷贝（只有 lighttest 会走到），绑 0 而不是把一个非法 id 交给 GL。
        final int depth = DepthCopy.texture();
        RenderSystem.setShaderTexture(0, depth == -1 ? 0 : depth);
        final int color = ColorCopy.texture();
        RenderSystem.setShaderTexture(1, color == -1 ? 0 : color);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        /*
         * Bind now, upload the lamp arrays, then draw. The json cannot declare a vec4[] array in
         * 1.20.1 (see ScatterProgram), so uLightPos/uLightDir/uLightColor are pushed by hand with one
         * glUniform4fv each - and a uniform write lands in whichever program is *currently* bound, so
         * the program has to be bound first. The apply() inside drawWithGlobalProgram below re-binds
         * the same program and re-uploads the json uniforms; it never touches these three.
         */
        final Shader program = ScatterProgram.program();
        if (program == null) {
            return;
        }
        program.bind();
        ScatterProgram.uploadArrays();
        /*
         * Blending off, dst = src: the shader writes the *whole* screen colour (scene + lamp light),
         * so it must replace the pixel, not add to it. The json has no "blend" section for the same
         * reason - a core program applies its own GlBlendState on bind(), which would silently
         * override anything set here. Disabling it here as well keeps the two in agreement.
         */
        RenderSystem.disableBlend();
        // The glow is already a function of the scene depth, so the depth buffer itself must neither
        // reject the quad nor be written by it.
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        final Tessellator tessellator = Tessellator.getInstance();
        try {
            /*
             * 1.18.2 的 buffer 生命周期：{@code begin()} 只是开始记录，{@code end()} 才把这一段
             * 归档成一条 draw-parameters 记录，而 {@code BufferRenderer.draw(BufferBuilder)}
             * 只做 {@code popData()} —— 取那条<b>已经归档好</b>的记录去上传，它从不替你 end()
             * （1.20.1 的 {@code drawWithGlobalProgram} 才会）。少了 end()，
             * {@code popData()} 读 {@code parameters.get(0)} 时列表长度是 0 →
             * {@code IndexOutOfBoundsException: Index 0 out of bounds for length 0}，
             * 而且 {@code building} 留在 true，下一次任何人 {@code begin()} 都抛
             * {@code Already building!}。所以 begin / end / draw 三个调用写在同一段 try 里。
             */
            final BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
            buffer.vertex(-1.0F, -1.0F, 0.0F).next();
            buffer.vertex(-1.0F, 1.0F, 0.0F).next();
            buffer.vertex(1.0F, 1.0F, 0.0F).next();
            buffer.vertex(1.0F, -1.0F, 0.0F).next();
            buffer.end();
            BufferRenderer.draw(buffer);
            /*
             * 读回真实的混合状态（5 秒行里的 {@code blend=}）。这条 pass 声称自己是 dst = src
             * （json 没有 blend 段），而 {@code Shader.bind()} 的 GlBlendState 有自己的一份
             * 静态缓存，所以我们「关了混合」这件事也值得从 GL 里确认一次，而不是假设。
             */
            diagBlendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
            diagBlendSrc = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            diagBlendDst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        } finally {
            /*
             * 异常路径（顶点写出或上传时抛了）：共享的 Tessellator buffer 必须在这里收尾，
             * 否则 building 一直是 true，下一次任何人 begin() 立刻 Already building!。
             * 正常路径上 end() 已经跑过、isBuilding() 是 false，这里是空操作。
             */
            final BufferBuilder buffer = tessellator.getBuffer();
            if (buffer.isBuilding()) {
                buffer.end();
                BufferRenderer.draw(buffer);
            }
            /*
             * Restore every piece of global state we touched. Leaving our program bound, our depth
             * texture on unit 0, or the depth mask off is exactly what made particles disappear in
             * the earlier beam-cone work: the next thing drawn (particles, weather, the hand) keeps
             * our sampler and our depth rules and silently renders nothing.
             */
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            // Back to what the rest of the frame expects: blending on, the vanilla additive-ish
            // function, nothing of ours left on either texture unit.
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.setShaderTexture(0, 0);
            RenderSystem.setShaderTexture(1, 0);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * {@code /mtr_lte lighttest}：让<b>本 pass 自己</b>把左半屏涂成纯红。
     *
     * <p>{@code uTest=1} 时片元着色器整段 {@code main()} 只写 {@code vec4(1,0,0,1)}：不读深度、
     * 不看灯数、不做任何积分。所以这块红是这条完整链路的产物 ——
     * <b>程序编译 + uniform 上传 + 采样器绑定 + 全屏四边形 + 主 framebuffer 的颜色附件 + 加色混合</b>，
     * 一个都不少，唯独把「shader 里的数学」排除在外。
     *
     * <p>只画左半屏用视口实现（全屏 NDC 四边形 + 半宽视口），右半屏留给原版程序画的那块蓝
     * （{@link LightTest#drawFallbackTint()}），一次同屏就能把「我们的程序」和「这个时机」分开看。
     *
     * <p>走 {@link #draw()} 而不是另写一份：状态设置与复位（blend / depthMask / cull / shader）
     * 和真实 pass 逐字相同，否则探针的回答就不代表真实 pass。
     */
    private static void tintThroughProgram(final MinecraftClient client) {
        try {
            final Framebuffer framebuffer = client.getFramebuffer();
            if (framebuffer == null) {
                LightTest.tintFailure("no-framebuffer");
                return;
            }
            if (!ScatterProgram.ensure(client)) {
                LightTest.tintFailure("program=" + ScatterProgram.status());
                return;
            }
            if (!ScatterProgram.testTint(true)) {
                LightTest.tintFailure("uniform-missing:uTest");
                return;
            }
            final int width = Math.max(2, framebuffer.viewportWidth);
            final int height = Math.max(1, framebuffer.viewportHeight);
            GlStateManager._viewport(0, 0, Math.max(1, width / 2), height);
            try {
                draw();
                LightTest.programTintSubmitted();
            } finally {
                GlStateManager._viewport(0, 0, width, height);
                ScatterProgram.testTint(false);
            }
        } catch (Throwable t) {
            LightTest.tintFailure(t.getClass().getSimpleName() + ":" + firstLine(t));
        }
    }

    /**
     * The user's {@code scatterStrength}, clamped, so a hand-edited json cannot turn the screen into
     * a white card. It is the {@code k} of the composite {@code 1 - exp(-added * k)}, and with the
     * volume term gone it scales the surface light and nothing else.
     */
    private static float strength(final HeadlightConfig cfg) {
        return (float) cfg.scatterStrengthClamped();
    }

    /**
     * The view matrix the depth buffer was produced with.
     *
     * <p>Three sources, most authoritative first: the context's matrix stack (Fabric documents it as
     * camera-transformed at {@code LAST}), {@code RenderSystem.getModelViewMatrix()} (what vanilla
     * applied when it drew the world), and a matrix rebuilt from the camera's pitch/yaw.
     *
     * <p>Each candidate is checked for being <b>rigid</b> - rotation plus translation, no scale,
     * bottom row {@code (0,0,0,1)}. A degenerate candidate (an identity left behind by a mod that
     * reset the stack, a matrix with the camera applied twice) is skipped, because a wrong view
     * matrix never fails loudly: it silently reconstructs the frame in the wrong place and the glow
     * misses the lamp. The fallback is a plain Rx(pitch) * Ry(yaw + 180), which is what vanilla
     * builds; it differs from the real matrix only by view bobbing (a few centimetres).
     */
    private static Matrix4f viewMatrix(final WorldRenderContext context,
                                       final MinecraftClient client) {
        Matrix4f candidate = null;
        try {
            if (context != null && context.matrixStack() != null) {
                candidate = new Matrix4f(context.matrixStack().peek().getPositionMatrix());
            }
        } catch (Throwable ignored) {
            candidate = null;
        }
        if (rigid(candidate)) {
            noteViewSource("context");
            return candidate;
        }
        /*
         * 第二条来源也必须包在 try 里：1.18.2 的替身矩阵曾经在「从原版矩阵拷贝」这一步每帧
         * 抛 BufferUnderflowException（见 dev.js.mtrheadlight.clientgui.Matrix4f#readVanilla），
         * 而这里原来是<b>裸调用</b> —— 一个坏掉的导出会让整条 pass 在拿到任何矩阵之前就抛出去，
         * 于是「散射不工作」在日志里只剩一句 exception:BufferUnderflowException。
         * 现在三条来源任意一条坏掉都只是被跳过，最后总有 rebuilt 兜底。
         */
        Matrix4f renderSystem = null;
        try {
            renderSystem = new Matrix4f(RenderSystem.getModelViewMatrix());
        } catch (Throwable ignored) {
            renderSystem = null;
        }
        if (rigid(renderSystem)) {
            noteViewSource("render");
            return renderSystem;
        }
        noteViewSource("rebuilt");
        final net.minecraft.client.render.Camera camera = client.gameRenderer.getCamera();
        return new Matrix4f()
                .rotateX((float) Math.toRadians(camera.getPitch()))
                .rotateY((float) Math.toRadians(camera.getYaw() + 180.0F));
    }

    /** Rotation + translation only: |det(R)| = 1, bottom row (0,0,0,1). */
    private static boolean rigid(final Matrix4f m) {
        if (m == null) {
            return false;
        }
        if (Math.abs(m.m30()) > 1.0E-3F || Math.abs(m.m31()) > 1.0E-3F
                || Math.abs(m.m32()) > 1.0E-3F || Math.abs(m.m33() - 1.0F) > 1.0E-3F) {
            return false;
        }
        final float det = m.m00() * (m.m11() * m.m22() - m.m12() * m.m21())
                - m.m01() * (m.m10() * m.m22() - m.m12() * m.m20())
                + m.m02() * (m.m10() * m.m21() - m.m11() * m.m20());
        return Math.abs(Math.abs(det) - 1.0F) < 0.05F;
    }

    /** One line per session: which source the reconstruction matrix came from. */
    private static void noteViewSource(final String source) {
        if (viewSource != null) {
            return;
        }
        viewSource = source;
        MtrHeadlightClient.LOGGER.info("[lte] scatter: depth reconstruction uses "
                + "inverse(ProjMat * {}-derived view)", source);
    }

    private static boolean shaderPackActive() {
        try {
            return ShaderPackProbe.active();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Status for {@code /mtr_lte scatter status}. Arms the lit-pixel probe for the next frame. */
    public static String status(final HeadlightConfig cfg) {
        final StringBuilder sb = new StringBuilder(224);
        sb.append("scatter ").append(cfg.beamScatter ? "on" : "off");
        // draws= 是渲染器自己的门（见 HeadlightConfig#drawScatter）：开着却画不出来时，这一格
        // 就是答案，不用去翻配置文件。
        sb.append(" draws=").append(cfg.drawScatter() ? "yes" : "no");
        sb.append(" program=").append(ScatterProgram.status());
        sb.append(" strength=").append(String.format(Locale.ROOT, "%.2f",
                Double.valueOf(cfg.scatterStrengthClamped())));
        sb.append(" radius=").append(String.format(Locale.ROOT, "%.1f",
                Double.valueOf(cfg.scatterRadiusClamped())));
        sb.append(" lights=").append(lastLights).append('/').append(ScatterLights.MAX);
        sb.append(" candidates=").append(ScatterLights.candidates());
        sb.append(" depth=").append(depthText());
        sb.append(" surface=").append(cfg.scatterSurface ? "on" : "off");
        // 光影下跑不跑，以及延迟合成此刻走到哪一步 —— /mtr_lte scatter status 一眼答完。
        sb.append(" shaders=").append(cfg.scatterUnderShaders ? "on" : "off");
        sb.append(" pack=").append(shaderPackActive() ? "on" : "off");
        sb.append(" defer=").append(deferredCalledOnce ? deferredReason : "never-called");
        sb.append(" deferredFrames=").append(deferredFrames);
        sb.append(" color=").append(colorText());
        sb.append(" litPx=").append(litPixels(cfg));
        sb.append(" matrix=").append(lastMatrix);
        sb.append(" debug=").append(debugName());
        sb.append(" uniforms[").append(ScatterProgram.locations()).append(']');
        sb.append(" reason=").append(lastReason);
        if (ScatterProgram.failure() != null) {
            sb.append(" failure=").append(ScatterProgram.failure());
        }
        if (shaderPackActive()) {
            sb.append(cfg.scatterUnderShaders
                    ? " (shader pack active: composited after the pack, see defer=)"
                    : " (shader pack active: pass skipped, scatterUnderShaders=false)");
        }
        return sb.toString();
    }

    /**
     * The lit-pixel figure, and the reason it can lag by one frame.
     *
     * <p>Nothing can answer "how many pixels got surface light" without either a GPU counter or a
     * readback, so the shader writes the per-pixel coverage into the output alpha and this arms a
     * one-shot downsample + readback for the <b>next</b> frame ({@link ColorCopy#probeLitPixels}).
     * A status command therefore fills the number for the next one; the alternative - reading back
     * 8 MB on every frame - is not worth a diagnostic.
     *
     * <p>这条周期行要的正是这个数，所以 5 秒窗口到点时 pass 自己会量一次
     * （{@link #litProbeDue}）：{@code litPx} 不只在命令之后才有值。
     */
    private static String litPixels(final HeadlightConfig cfg) {
        if (debugMode != 0) {
            // debug 输出把 alpha 占满了，量出来的「点亮像素」恒等于整屏，没有意义。
            return "n/a(debug " + debugName() + ")";
        }
        if (!cfg.scatterSurface) {
            return "n/a(surface off)";
        }
        if (lastLights == 0) {
            // No lamps in the last drawn frame: there is nothing to measure, and the probe would
            // stay armed until a frame with lamps exists.
            return "n/a(no lit lamps)";
        }
        if (probeRequested) {
            return "measuring(run again)";
        }
        if (lastLitPixels < 0) {
            return "n/a(no frame)";
        }
        return "≈" + lastLitPixels;
    }

    /** {@code /mtr_lte scatter status}: asks the next frame to measure the lit-pixel count. */
    public static void requestLitPixelProbe() {
        probeRequested = true;
    }

    /** First line of a throwable's message, for a one-line log field. */
    private static String firstLine(final Throwable t) {
        final String text = t == null ? "unknown" : String.valueOf(t.getMessage());
        final int newline = text.indexOf('\n');
        final String head = newline < 0 ? text : text.substring(0, newline);
        return head.length() > 120 ? head.substring(0, 120) : head;
    }

    /** Releases the program and both copy targets; called when the client stops. */
    public static void shutdown() {
        deferredArmed = false;
        deferredStamp = 0L;
        deferredWidth = -1;
        deferredHeight = -1;
        deferredLights = 0;
        depthState = "none";
        DepthCopy.release();
        ColorCopy.release();
        ScatterProgram.close();
    }
}
