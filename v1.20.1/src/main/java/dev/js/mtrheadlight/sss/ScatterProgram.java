package dev.js.mtrheadlight.sss;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.js.mtrheadlight.MtrHeadlightClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.Optional;

/**
 * Our own core shader, compiled by hand, plus the uniform handles the scattering pass pushes.
 *
 * <h2>Why not {@code CoreShaderRegistrationCallback}</h2>
 * Fabric's callback is the normal way to add a core shader and it does work (it is what the removed
 * beam cone used). It is deliberately <b>not</b> used here, because it loads the program as part of
 * the vanilla {@code ProgramManager} reload: that couples our effect to the reload list, hides the
 * program behind a supplier that answers {@code null} for a whole session if one uniform is renamed,
 * and makes "load failed" indistinguishable from "not loaded yet".
 *
 * <h2>The 1.20.1 pitfall this class exists to work around</h2>
 * {@code new ShaderProgram(factory, name, format)} builds its own resource id as
 * {@code new Identifier("shaders/core/" + name + ".json")} - a <b>hard-coded</b> concatenation with
 * no namespace. Passing {@code "mtrlte:light_scatter"} therefore throws
 * {@code InvalidIdentifierException} (two colons: "Non [a-z0-9_.-] character in path"), and passing
 * a bare name resolves to {@code assets/minecraft/shaders/core/...}, which is not where a mod's
 * assets live. Fabric patches exactly that call sight-unseen in {@code ShaderMixin}, but only for
 * programs created through its own registration path (the mixin tests
 * {@code this instanceof FabricShader}) - a program we construct ourselves is untouched.
 *
 * <p>The fix is to pass a {@link ResourceFactory} that rewrites the resource id instead of the name:
 * every {@code minecraft:shaders/...} lookup becomes {@code mtrlte:shaders/...}. Vanilla's
 * identifier stays well-formed, our files stay where the mod layout says they belong
 * ({@code assets/mtrlte/shaders/core/light_scatter.*}), and the shader stages resolve through the
 * same rewrite because {@code ShaderProgram} loads {@code vertex}/{@code fragment} from the json
 * through the factory it was handed.
 *
 * <h2>Why the three lamp arrays are uploaded by hand, not through {@link GlUniform}</h2>
 * This is the bug that made the whole effect invisible in 1.0.62, and it is not a typo - the vanilla
 * json uniform path <b>cannot</b> express {@code uniform vec4 uLightPos[16]} in 1.20.1:
 * <ul>
 *   <li>{@code GlUniform.getTypeIndex} accepts exactly {@code int}, {@code float},
 *       {@code matrix2x2/3x3/4x4}. {@code "vec4"} is not a type it knows: it returns {@code -1} and
 *       {@code upload()} then logs "type value (-1) is not a valid type" and uploads nothing.</li>
 *   <li>Declaring it as {@code "type": "float", "count": 64} (what this mod did) makes the data type
 *       {@code float}, so {@code uploadFloats()} calls {@code glUniform1fv} - 64 scalars into a
 *       {@code vec4[16]} location. That is {@code GL_INVALID_OPERATION}; the array keeps its json
 *       default, where {@code uLightColor[i].w} (the intensity) is {@code 0}.</li>
 *   <li>The {@code "float" + count 1..4 = vec4} idiom cannot be stretched: the {@code count-1}
 *       promotion in {@code ShaderProgram.addUniform} only applies to {@code count <= 4}, i.e. to
 *       single (non-array) uniforms.</li>
 * </ul>
 * The visible consequence was the reported one: the fragment loop starts with
 * {@code if (power <= 1e-4) continue;}, every lamp was power 0, so the loop body never ran - the
 * surface term, the volume term <b>and</b> all three debug channels stayed at 0 while
 * {@code debug normal} (which needs no lamp at all) looked perfectly fine.
 *
 * <p>So the arrays are declared <b>nowhere</b> in the json, their locations are queried once here with
 * {@link GlUniform#getUniformLocation(int, CharSequence)} on {@code glRef}, and one
 * {@code glUniform4fv} per array (16 vec4s in a single call) pushes the whole lamp list after the
 * program is bound. That is also why {@link #uploadArrays()} must run with {@code program} bound -
 * it is called from {@code ScatterPass.draw()} right after {@code bind()}.
 *
 * <h2>Failure policy</h2>
 * One attempt per process. A compile error, a missing uniform (the driver deletes uniforms nothing
 * reads) or a missing file latches {@link #failure} and the pass is off for the session, with one
 * WARN - never a per-frame log, never an exception thrown into the render loop.
 */
final class ScatterProgram {

    /** Program name; the files are {@code assets/mtrlte/shaders/core/light_scatter.{json,vsh,fsh}}. */
    static final String NAME = "light_scatter";
    /** The namespace our shaders actually live in. */
    private static final String NAMESPACE = "mtrlte";
    /** Lamp slots the fragment shader loops over ({@code uLightPos[16]}), 4 floats each. */
    static final int MAX_LAMPS = 16;
    /** Floats per lamp slot: xyz + w. */
    private static final int STRIDE = 4;
    /** Floats the shader's arrays hold. */
    private static final int ARRAY_FLOATS = MAX_LAMPS * STRIDE;

    private static ShaderProgram program;
    private static boolean attempted;
    private static String failure;

    private static GlUniform uInvViewProj;
    private static GlUniform uMaxRay;
    private static GlUniform uCone;
    private static GlUniform uSurface;
    private static GlUniform uCount;
    private static GlUniform uTest;
    /**
     * {@code /mtr_lte scatter debug surface|normal|cone} 用的通道选择；<b>可选</b>：着色器里没有
     * 这个名字时只是 debug 可视化不可用，pass 本身照常跑（不像别的 uniform 缺失那样整条关掉）。
     */
    private static GlUniform uDebug;

    /** {@code uLightPos[0]} / {@code uLightDir[0]} / {@code uLightColor[0]} 的 GL 位置；-1 = 没有。 */
    private static int posLoc = -1;
    private static int dirLoc = -1;
    private static int colorLoc = -1;
    /** 三个数组的手工上传缓冲（16 × vec4），常驻复用，热路径零分配。 */
    private static FloatBuffer posBuf;
    private static FloatBuffer dirBuf;
    private static FloatBuffer colorBuf;
    /** 回读缓冲：3 次 {@code glGetUniformfv}，每 5 秒一次。 */
    private static FloatBuffer readBuf;
    /** 上传异常只 WARN 一次。 */
    private static boolean uploadWarned;

    /** 下一次 {@link #uploadArrays()} 顺手把 GL 里的真实值读回来（{@code ScatterPass} 的 5 秒行）。 */
    private static boolean readbackWanted;
    /** 最近一次回读：pos.xyzw / dir.xyzw / color.xyzw，共 12 个 float；{@code null} = 还没读过。 */
    private static float[] lastReadback;

    private ScatterProgram() {
    }

    /** True when a program is compiled and no failure has been latched. */
    static boolean ready() {
        return program != null && failure == null;
    }

    /** {@code null} while nothing went wrong, otherwise {@code where:what} for the log/command. */
    static String failure() {
        return failure;
    }

    static String status() {
        if (failure != null) {
            return "failed(" + failure + ")";
        }
        if (program != null) {
            return "ok";
        }
        return attempted ? "missing" : "not-loaded";
    }

    /**
     * Loads the shader once. Safe to call from the client tick (that thread owns the GL context) and
     * from the render thread; every failure is logged exactly once.
     *
     * @return true when the program can be used
     */
    static boolean ensure(final MinecraftClient client) {
        if (program != null) {
            return true;
        }
        if (attempted) {
            return false;
        }
        attempted = true;
        if (client == null || client.getResourceManager() == null) {
            failure = "no-resource-manager";
            return false;
        }
        try {
            final ResourceFactory remap = remapped(client.getResourceManager());
            final ShaderProgram loaded = new ShaderProgram(remap, NAME, VertexFormats.POSITION);
            final GlUniform invViewProj = require(loaded, "uInvViewProj");
            final GlUniform maxRay = require(loaded, "uMaxRay");
            final GlUniform cone = require(loaded, "uCone");
            final GlUniform surface = require(loaded, "uSurface");
            final GlUniform count = require(loaded, "uCount");
            final GlUniform test = require(loaded, "uTest");
            if (invViewProj == null || maxRay == null || cone == null || surface == null
                    || count == null
                    || test == null) {
                // A uniform the compiler dropped (renamed, or no longer read) would make the pass
                // draw with stale values - that is exactly the "effect silently does nothing" bug.
                failure = "uniform-missing";
                closeQuietly(loaded);
                return false;
            }
            /*
             * 三个灯阵：不在 json 里声明（见类注释——1.20.1 的 json 类型表根本没有 vec4，声明成
             * float 会被发成 glUniform1fv，整条数组永远停在 0）。位置必须在链接之后自己查，
             * 而且只能用数组名（不带 [0]）。
             */
            final int glRef = loaded.getGlRef();
            final int pLoc = GlUniform.getUniformLocation(glRef, "uLightPos");
            final int dLoc = GlUniform.getUniformLocation(glRef, "uLightDir");
            final int cLoc = GlUniform.getUniformLocation(glRef, "uLightColor");
            if (pLoc < 0 || dLoc < 0 || cLoc < 0) {
                failure = "uniform-array-missing:pos=" + pLoc + ",dir=" + dLoc + ",color=" + cLoc;
                MtrHeadlightClient.LOGGER.warn("[lte] scatter: the shader has no readable lamp array "
                        + "(uLightPos={} uLightDir={} uLightColor={}) - the per-lamp loop cannot be "
                        + "fed, so the pass is disabled", Integer.valueOf(pLoc), Integer.valueOf(dLoc),
                        Integer.valueOf(cLoc));
                closeQuietly(loaded);
                return false;
            }
            uInvViewProj = invViewProj;
            uMaxRay = maxRay;
            uCone = cone;
            uSurface = surface;
            uCount = count;
            uTest = test;
            // 可选的调试通道：缺了只是 debug 可视化不可用，不关 pass。
            uDebug = loaded.getUniform("uDebug");
            if (uDebug == null) {
                MtrHeadlightClient.LOGGER.warn("[lte] scatter: shader has no uniform uDebug - "
                        + "/mtr_lte scatter debug will do nothing (the pass itself is unaffected)");
            }
            posLoc = pLoc;
            dirLoc = dLoc;
            colorLoc = cLoc;
            posBuf = BufferUtils.createFloatBuffer(ARRAY_FLOATS);
            dirBuf = BufferUtils.createFloatBuffer(ARRAY_FLOATS);
            colorBuf = BufferUtils.createFloatBuffer(ARRAY_FLOATS);
            readBuf = BufferUtils.createFloatBuffer(STRIDE);
            readbackWanted = false;
            lastReadback = null;
            program = loaded;
            logUniforms();
            return true;
        } catch (Throwable t) {
            failure = t.getClass().getSimpleName() + ":" + firstLine(t);
            MtrHeadlightClient.LOGGER.warn("[lte] scatter: could not load shader {}:{} - the "
                    + "screen-space pass is disabled for this session", NAMESPACE, NAME, t);
            program = null;
            return false;
        }
    }

    /**
     * One line with <b>every uniform name and the location GL actually gave us</b>. A {@code -1} is
     * the signature of "the compiler dropped it, or the json and the GLSL disagree about the name",
     * and it is the field that would have explained 1.0.62 in one glance: every lamp array was
     * declared {@code "type": "float"} and never reached the GPU.
     *
     * <pre>
     *   [lte] scatter uniforms: uLightPos[0]=38 uLightDir[0]=22 uLightColor[0]=6 uInvViewProj=5
     *                           uMaxRay=54 uCone=2 uSurface=55 uCount=3 uTest=56 uDebug=4 (lamp arrays are 16x vec4 via glUniform4fv)
     * </pre>
     * (Real numbers for this shader on an NVIDIA 621.24 / GL 3.3 core driver, where
     * {@code glGetActiveUniform} reports {@code uLightPos[0] size=16 loc=38}, {@code uLightDir[0]
     * size=16 loc=22}, {@code uLightColor[0] size=16 loc=6}. Locations differ per driver and per
     * shader edit, which is exactly why they are logged.)
     */
    private static void logUniforms() {
        MtrHeadlightClient.LOGGER.info("[lte] scatter uniforms: uLightPos[0]={} uLightDir[0]={} "
                        + "uLightColor[0]={} uInvViewProj={} uMaxRay={} uCone={} uSurface={} "
                        + "uCount={} uTest={} uDebug={} (lamp arrays are 16x vec4, uploaded by "
                        + "glUniform4fv after bind; json declares none of them)",
                Integer.valueOf(posLoc), Integer.valueOf(dirLoc), Integer.valueOf(colorLoc),
                Integer.valueOf(locOf(uInvViewProj)), Integer.valueOf(locOf(uMaxRay)),
                Integer.valueOf(locOf(uCone)), Integer.valueOf(locOf(uSurface)),
                Integer.valueOf(locOf(uCount)), Integer.valueOf(locOf(uTest)),
                Integer.valueOf(locOf(uDebug)));
    }

    private static int locOf(final GlUniform uniform) {
        return uniform == null ? -1 : uniform.getLocation();
    }

    /**
     * Vanilla core-shader ids are always {@code minecraft:shaders/core/...}; ours live in the mod's
     * namespace. Rewriting here (rather than naming the program {@code mtrlte:...}) is what makes the
     * hand-built program work at all - see the class comment.
     */
    private static ResourceFactory remapped(final ResourceFactory base) {
        return id -> {
            if (id != null && "minecraft".equals(id.getNamespace())
                    && id.getPath().startsWith("shaders/")) {
                final Optional<Resource> own =
                        base.getResource(new Identifier(NAMESPACE, id.getPath()));
                if (own.isPresent()) {
                    return own;
                }
            }
            return base.getResource(id);
        };
    }

    private static GlUniform require(final ShaderProgram loaded, final String name) {
        final GlUniform uniform = loaded.getUniform(name);
        if (uniform == null) {
            MtrHeadlightClient.LOGGER.warn("[lte] scatter: shader {}:{} has no uniform {} - "
                    + "the json and the GLSL disagree", NAMESPACE, NAME, name);
        }
        return uniform;
    }

    /**
     * Pushes one frame's worth of state. {@code count} lamps must already be in the arrays.
     *
     * <p>The scalar uniforms go through {@link GlUniform} (staged, uploaded by
     * {@code ShaderProgram.bind()}); the three lamp arrays are only <b>staged</b> into their
     * {@link FloatBuffer}s here and pushed by {@link #uploadArrays()} once the program is bound.
     *
     * <p>The volume term (and with it {@code uParams.x/y/w}: gain, extinction, phase g) is gone; the
     * only remaining per-beam uniform pair is {@code uCone} + {@code uSurface}.
     *
     * @param strength      the user's strength ({@code uSurface.x}); the composite gain of the surface term
     * @param attenuationRef squared distance (blocks^2) at which the surface term halves
     * @param maxRay        {@code uMaxRay}: longest view ray, blocks
     * @param surface       {@code uSurface.z}: compile-block switch for the surface term
     * @param coverage      {@code uSurface.w}: write the lit-pixel probe into the output alpha
     * @param cosInner      {@code uCone.x}; the beam axis is {@code uLightDir}, pointing <b>out of the lamp</b>
     * @param cosOuter      {@code uCone.y}
     */
    static void uniforms(final Matrix4f inverseViewProj, final float maxRay,
                         final float cosInner, final float cosOuter, final float strength,
                         final float attenuationRef, final boolean surface, final boolean coverage,
                         final int debug, final int count, final float[] pos, final float[] dir,
                         final float[] color) {
        if (program == null || failure != null) {
            return;
        }
        uInvViewProj.set(inverseViewProj);
        uMaxRay.set(maxRay);
        uCone.set(cosInner, cosOuter, 0.0F, 0.0F);
        uSurface.set(strength, attenuationRef, surface ? 1.0F : 0.0F, coverage ? 1.0F : 0.0F);
        uCount.set(count);
        // 真实渲染永远回到「不强制纯色」—— 探针关掉之后不会留下一个红色的 pass。
        uTest.set(0);
        if (uDebug != null) {
            uDebug.set(debug);
        }
        stage(posBuf, pos);
        stage(dirBuf, dir);
        stage(colorBuf, color);
    }

    /** Copies a {@code 16*4} float array into a resident buffer, position back at 0 for upload. */
    private static void stage(final FloatBuffer buffer, final float[] values) {
        if (buffer == null || values == null) {
            return;
        }
        final int n = Math.min(values.length, buffer.capacity());
        buffer.clear();
        buffer.put(values, 0, n);
        buffer.position(0);
    }

    /**
     * Uploads the three lamp arrays, <b>one GL call each</b> ({@code glUniform4fv} with all 16 vec4s),
     * from the location of element 0 that {@code glGetUniformLocation("uLightPos")} returned.
     *
     * <p>Must be called with {@code program} bound: a uniform write goes to the currently bound
     * program, and a write into the wrong program is silent. {@code ScatterPass.draw()} calls
     * {@code bind()} first for exactly that reason, and the {@code ShaderProgram.apply()} inside
     * {@code BufferRenderer.drawWithGlobalProgram} only re-binds the same program - it re-uploads the
     * json uniforms and never touches these three, so the values survive into the draw.
     */
    static void uploadArrays() {
        if (program == null || failure != null) {
            return;
        }
        try {
            RenderSystem.glUniform4(posLoc, rewind(posBuf));
            RenderSystem.glUniform4(dirLoc, rewind(dirBuf));
            RenderSystem.glUniform4(colorLoc, rewind(colorBuf));
            if (readbackWanted) {
                readbackWanted = false;
                readback();
            }
        } catch (Throwable t) {
            // A uniform call cannot break the frame; warn once and let the 5 s line show the
            // readback as absent rather than pretending the upload worked.
            if (!uploadWarned) {
                uploadWarned = true;
                MtrHeadlightClient.LOGGER.warn("[lte] scatter: lamp array upload failed: {}",
                        firstLine(t));
            }
        }
    }

    private static FloatBuffer rewind(final FloatBuffer buffer) {
        if (buffer != null) {
            buffer.position(0);
        }
        return buffer;
    }

    /** Asks the next {@link #uploadArrays()} to read the first lamp slot back out of GL. */
    static void requestReadback() {
        readbackWanted = true;
    }

    /**
     * Reads what the <b>GPU</b> holds for lamp slot 0 right after the upload - the only honest answer
     * to "did the uniform actually land". GL returns exactly what was last accepted, so a
     * {@code pos=(0,0,0) power=0} here means the upload did not reach the program (wrong type, wrong
     * uniform, failed bind), while a real lamp means it did.
     */
    private static void readback() {
        if (readBuf == null) {
            return;
        }
        try {
            final int glRef = program.getGlRef();
            final float[] out = lastReadback == null ? new float[12] : lastReadback;
            readVec(glRef, posLoc, out, 0);
            readVec(glRef, dirLoc, out, 4);
            readVec(glRef, colorLoc, out, 8);
            lastReadback = out;
        } catch (Throwable t) {
            lastReadback = null;
        }
    }

    private static void readVec(final int glRef, final int location, final float[] out, final int at) {
        readBuf.clear();
        GL20.glGetUniformfv(glRef, location, readBuf);
        for (int i = 0; i < STRIDE; i++) {
            out[at + i] = readBuf.get(i);
        }
    }

    /**
     * The most recent GPU-side readback of lamp slot 0, as {@code pos.xyzw dir.xyzw color.xyzw}, or
     * {@code null} when it has not been taken yet. Diagnostics only - never feeds the render.
     */
    static float[] lastReadback() {
        return lastReadback;
    }

    /** {@code false} = 这个着色器里没有 {@code uDebug}，debug 可视化不可用（pass 本身不受影响）。 */
    static boolean debugAvailable() {
        return uDebug != null;
    }

    /**
     * {@code /mtr_lte lighttest} 用：把整个 pass 切成「固定纯红输出」，不读深度、不看灯数。
     *
     * @return {@code false} 表示程序里没有这个 uniform（探针不能假装自己做了事）
     */
    static boolean testTint(final boolean on) {
        if (uTest == null) {
            return false;
        }
        uTest.set(on ? 1 : 0);
        return true;
    }

    /** The program to draw with; only valid while {@link #ready()}. */
    static ShaderProgram program() {
        return program;
    }

    /** One-line inventory for {@code /mtr_lte scatter status}: every uniform name -> GL location. */
    static String locations() {
        if (program == null) {
            return "program=" + status();
        }
        return String.format(Locale.ROOT,
                "uLightPos[0]=%d uLightDir[0]=%d uLightColor[0]=%d uInvViewProj=%d uMaxRay=%d "
                        + "uCone=%d uSurface=%d uCount=%d uTest=%d uDebug=%d",
                Integer.valueOf(posLoc), Integer.valueOf(dirLoc), Integer.valueOf(colorLoc),
                Integer.valueOf(locOf(uInvViewProj)), Integer.valueOf(locOf(uMaxRay)),
                Integer.valueOf(locOf(uCone)), Integer.valueOf(locOf(uSurface)),
                Integer.valueOf(locOf(uCount)), Integer.valueOf(locOf(uTest)),
                Integer.valueOf(locOf(uDebug)));
    }

    /** Drops the program (client shutdown, or a forced reload). GL context must be current. */
    static void close() {
        final ShaderProgram loaded = program;
        program = null;
        failure = null;
        attempted = false;
        uInvViewProj = null;
        uMaxRay = null;
        uCone = null;
        uSurface = null;
        uCount = null;
        uTest = null;
        uDebug = null;
        posLoc = -1;
        dirLoc = -1;
        colorLoc = -1;
        posBuf = null;
        dirBuf = null;
        colorBuf = null;
        readBuf = null;
        uploadWarned = false;
        readbackWanted = false;
        lastReadback = null;
        closeQuietly(loaded);
    }

    private static void closeQuietly(final ShaderProgram loaded) {
        if (loaded == null) {
            return;
        }
        try {
            loaded.close();
        } catch (Throwable ignored) {
            // A lost context has nothing to release.
        }
    }

    private static String firstLine(final Throwable t) {
        final String text = t == null ? "unknown" : String.valueOf(t.getMessage());
        final int newline = text.indexOf('\n');
        final String head = newline < 0 ? text : text.substring(0, newline);
        return head.length() > 160 ? head.substring(0, 160) : head;
    }
}
