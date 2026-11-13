package dev.js.mtrheadlight.sss;

import dev.js.mtrheadlight.MtrHeadlightClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

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

    private static ShaderProgram program;
    private static boolean attempted;
    private static String failure;

    private static GlUniform uInvViewProj;
    private static GlUniform uParams;
    private static GlUniform uCone;
    private static GlUniform uCount;
    private static GlUniform uTest;
    private static GlUniform uLightPos;
    private static GlUniform uLightDir;
    private static GlUniform uLightColor;

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
            final GlUniform params = require(loaded, "uParams");
            final GlUniform cone = require(loaded, "uCone");
            final GlUniform count = require(loaded, "uCount");
            final GlUniform test = require(loaded, "uTest");
            final GlUniform lightPos = require(loaded, "uLightPos");
            final GlUniform lightDir = require(loaded, "uLightDir");
            final GlUniform lightColor = require(loaded, "uLightColor");
            if (invViewProj == null || params == null || cone == null || count == null
                    || test == null
                    || lightPos == null || lightDir == null || lightColor == null) {
                // A uniform the compiler dropped (renamed, or no longer read) would make the pass
                // draw with stale values - that is exactly the "effect silently does nothing" bug.
                failure = "uniform-missing";
                closeQuietly(loaded);
                return false;
            }
            uInvViewProj = invViewProj;
            uParams = params;
            uCone = cone;
            uCount = count;
            uTest = test;
            uLightPos = lightPos;
            uLightDir = lightDir;
            uLightColor = lightColor;
            program = loaded;
            MtrHeadlightClient.LOGGER.info(
                    "[lte] scatter: program=ok id={}:{} format=POSITION (loaded from the "
                            + "resource manager by ShaderProgram)", NAMESPACE, NAME);
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

    /** Pushes one frame's worth of state. {@code count} lamps must already be in the arrays. */
    static void uniforms(final Matrix4f inverseViewProj, final float gain, final float extinction,
                         final float maxRay, final float phaseG, final float cosInner,
                         final float cosOuter, final int count, final float[] pos,
                         final float[] dir, final float[] color) {
        uInvViewProj.set(inverseViewProj);
        uParams.set(gain, extinction, maxRay, phaseG);
        uCone.set(cosInner, cosOuter, 0.0F, 0.0F);
        uCount.set(count);
        // 真实渲染永远回到「不强制纯色」—— 探针关掉之后不会留下一个红色的 pass。
        uTest.set(0);
        uLightPos.set(pos);
        uLightDir.set(dir);
        uLightColor.set(color);
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

    /** Drops the program (client shutdown, or a forced reload). GL context must be current. */
    static void close() {
        final ShaderProgram loaded = program;
        program = null;
        failure = null;
        attempted = false;
        uInvViewProj = null;
        uParams = null;
        uCone = null;
        uCount = null;
        uTest = null;
        uLightPos = null;
        uLightDir = null;
        uLightColor = null;
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
