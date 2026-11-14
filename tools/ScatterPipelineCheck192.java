// Prove (or disprove) the 1.19.2 screen-space scatter chain WITHOUT launching Minecraft.
//
// Why this exists: on 1.19.2 the pass failed inside a helper (see
// docs/report-2026-11-14-sss-1.19.2-columnmajor.md) and the only visible symptom was
// "there is no light" - the shader compiled, the GL calls were legal, and every number in the
// 5 s log looked plausible except frames=0. This harness exercises the two halves separately on
// the real driver:
//
//   1. net.minecraft.client.render.Shader loads assets/mtrlte/shaders/core/light_scatter.* through
//      Minecraft's OWN loader (same resource-id remap the mod installs) and reports every uniform,
//      array, sampler and attribute location ScatterProgram needs.
//   2. the same matrix chain ScatterPass builds (proj * view -> inverse) is fed to that program
//      together with one lamp, against a synthetic depth ramp + mid-grey colour buffer, and the
//      output is read back: does the pass actually ADD LIGHT?
//
// Usage:
//   CP="<mc 1.19.2 mapped jar>;<lwjgl 3.3.2 + natives>;<gson,guava,fastutil,brigadier,
//       datafixerupper,icu4j,commons-io,commons-lang3,authlib,jopt-simple,jsr305,slf4j,
//       log4j,com.mojang:logging>;v1.19.2/build/classes/java/main"
//   javac -cp "$CP" -d /tmp/spc tools/ScatterPipelineCheck192.java
//   java  -cp "$CP:/tmp/spc" ScatterPipelineCheck192 v1.19.2/src/main/resources/assets [mode]
//
//   mode: fixed (default, Matrices.invert) | legacy (Matrix4f.invert) | ident
//
// Exit code 0 when the program loads AND light is drawn.
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.client.util.math.MatrixStack;
import dev.js.mtrheadlight.clientgui.RotationAxis;
import dev.js.mtrheadlight.compat.Matrices;
import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public final class ScatterPipelineCheck192 {

    private static final int W = 64;
    private static final int H = 64;

    /** ResourceFactory over a directory tree: {@code <root>/<namespace>/<path>}. */
    private static final class DirPack implements ResourceFactory {
        private final Path root;

        DirPack(final Path root) {
            this.root = root;
        }

        @Override
        public Optional<Resource> getResource(final Identifier id) {
            final Path p = root.resolve(id.getNamespace()).resolve(id.getPath());
            if (!Files.isRegularFile(p)) {
                return Optional.empty();
            }
            return Optional.of(new Resource("harness", () -> {
                try {
                    return Files.newInputStream(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
    }

    private static int colorTex(final float r, final float g, final float b) {
        final int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        final ByteBuffer buf = ByteBuffer.allocateDirect(W * H * 4);
        for (int i = 0; i < W * H; i++) {
            buf.put((byte) (r * 255)).put((byte) (g * 255)).put((byte) (b * 255)).put((byte) 255);
        }
        buf.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return t;
    }

    /** A depth texture holding a receding plane (values 0.94 .. 0.995 top to bottom). */
    private static int depthTex() {
        final int fb = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        final int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        final FloatBuffer buf = BufferUtils.createFloatBuffer(W * H);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                buf.put(0.94F + 0.055F * (y / (float) (H - 1)));
            }
        }
        buf.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, W, H, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, t, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("depth fbo incomplete");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return t;
    }

    public static void main(final String[] args) throws Exception {
        final Path resRoot = Path.of(args[0]);
        final String mode = args.length > 1 ? args[1] : "fixed";

        GLFW.glfwInit();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        final long win = GLFW.glfwCreateWindow(W, H, "scatter-pipeline", 0, 0);
        if (win == 0) {
            throw new IllegalStateException("no GLFW window");
        }
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();
        RenderSystem.initRenderThread();
        System.out.println("GL " + glGetString(GL_VERSION));

        // ---- 1. load through Minecraft's own loader, with the mod's id remap ----
        final ResourceFactory base = new DirPack(resRoot);
        final ResourceFactory remap = id -> {
            if (id != null && "minecraft".equals(id.getNamespace())
                    && id.getPath().startsWith("shaders/")) {
                final Optional<Resource> own =
                        base.getResource(new Identifier("mtrlte", id.getPath()));
                if (own.isPresent()) {
                    return own;
                }
            }
            return base.getResource(id);
        };
        final Shader shader = new Shader(remap, "light_scatter", VertexFormats.POSITION);
        final int prog = shader.getProgramRef();
        System.out.println("LOAD OK programRef=" + prog);
        for (final String u : new String[] {"uInvViewProj", "uMaxRay", "uCone", "uSurface",
                "uCount", "uTest", "uDebug"}) {
            final GlUniform uniform = shader.getUniform(u);
            System.out.println("  uniform " + u + " -> "
                    + (uniform == null ? "MISSING" : "loc " + uniform.getLocation()));
        }
        for (final String a : new String[] {"uLightPos", "uLightDir", "uLightColor"}) {
            System.out.println("  array   " + a + " -> loc " + GlUniform.getUniformLocation(prog, a));
        }
        System.out.println("  attrib  Position -> " + GlUniform.getAttribLocation(prog, "Position"));

        // ---- 2. the matrix chain of ScatterPass.render() ----
        final MatrixStack st = new MatrixStack();
        st.multiply(RotationAxis.POSITIVE_X.rotationDegrees(0.0F));
        st.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F));
        final Matrix4f view = st.peek().getPositionMatrix();

        final float fov = 70F;
        final float aspect = (float) W / H;
        final float near = 0.05F;
        final float far = 256F;
        final float f = (float) (1.0 / Math.tan(Math.toRadians(fov) / 2.0));
        final FloatBuffer pb = BufferUtils.createFloatBuffer(16);
        pb.put(new float[] {
                f / aspect, 0, 0, 0,
                0, f, 0, 0,
                0, 0, (far + near) / (near - far), -1,
                0, 0, 2 * far * near / (near - far), 0,
        }).flip();
        final Matrix4f proj = new Matrix4f();
        proj.readColumnMajor(pb);

        final Matrix4f vp = new Matrix4f();
        vp.load(proj);
        vp.multiply(view);

        final Matrix4f inv = new Matrix4f();
        final boolean ok;
        if ("legacy".equals(mode)) {
            inv.load(vp);
            ok = inv.invert();
            System.out.println("mode: legacy Matrix4f.invert()");
        } else if ("ident".equals(mode)) {
            inv.loadIdentity();
            ok = true;
            System.out.println("mode: identity (control - light must NOT appear)");
        } else {
            ok = Matrices.invert(vp, inv);
            System.out.println("mode: Matrices.invert");
        }
        final float err = Matrices.inverseError(inv, vp);
        System.out.println("inverse ok=" + ok + " |inv*vp - I|=" + err + " (want < 1e-3)");

        // ---- 3. draw ----
        final int depth = depthTex();
        final int colour = colorTex(0.25F, 0.25F, 0.25F);
        final int outTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, outTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        final int outFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, outFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("out fbo incomplete");
        }
        glViewport(0, 0, W, H);

        glUseProgram(prog);
        final FloatBuffer m = BufferUtils.createFloatBuffer(16);
        m.put(Matrices.columnMajor(inv)).flip();
        glUniformMatrix4fv(GlUniform.getUniformLocation(prog, "uInvViewProj"), false, m);
        glUniform1f(GlUniform.getUniformLocation(prog, "uMaxRay"), 96.0F);
        glUniform4f(GlUniform.getUniformLocation(prog, "uCone"), 0.94F, 0.55F, 0.0F, 0.0F);
        glUniform4f(GlUniform.getUniformLocation(prog, "uSurface"), 1.0F, 9.0F, 1.0F, 0.0F);
        glUniform1i(GlUniform.getUniformLocation(prog, "uCount"), 1);
        glUniform1i(GlUniform.getUniformLocation(prog, "uTest"), "test".equals(mode) ? 1 : 0);
        glUniform1i(GlUniform.getUniformLocation(prog, "uDebug"), "dbg".equals(mode) ? 1 : 0);

        // one lamp two blocks ahead of the camera, shining down-range at the floor
        final FloatBuffer pos = BufferUtils.createFloatBuffer(16);
        pos.put(new float[] {0F, 1F, 2.0F, 16F}).position(0);
        glUniform4fv(GlUniform.getUniformLocation(prog, "uLightPos"), pos);
        final FloatBuffer dir = BufferUtils.createFloatBuffer(16);
        dir.put(new float[] {0F, -0.35F, 1F, 24F}).position(0);
        glUniform4fv(GlUniform.getUniformLocation(prog, "uLightDir"), dir);
        final FloatBuffer tint = BufferUtils.createFloatBuffer(16);
        tint.put(new float[] {1F, 0.95F, 0.85F, 0.9F}).position(0);
        glUniform4fv(GlUniform.getUniformLocation(prog, "uLightColor"), tint);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, depth);
        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D, colour);
        glUseProgram(prog);
        glUniform1i(GlUniform.getUniformLocation(prog, "Sampler0"), 0);
        glUniform1i(GlUniform.getUniformLocation(prog, "Sampler1"), 1);

        final int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        final int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        final FloatBuffer quad = BufferUtils.createFloatBuffer(12);
        quad.put(new float[] {-1, -1, 0, -1, 1, 0, 1, 1, 0, 1, -1, 0}).flip();
        glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0);

        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        System.out.println("glGetError after draw = 0x" + Integer.toHexString(glGetError()));

        final ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
        int brighter = 0;
        long sum = 0;
        for (int i = 0; i < W * H; i++) {
            final int r = px.get(i * 4) & 0xFF;
            final int g = px.get(i * 4 + 1) & 0xFF;
            final int b = px.get(i * 4 + 2) & 0xFF;
            sum += r + g + b;
            if (r > 66 || g > 66 || b > 66) {
                brighter++;
            }
        }
        System.out.println("pixels brighter than the 0.25 scene grey: " + brighter + " / " + (W * H)
                + " (avg " + String.format("%.1f", sum / (double) (W * H * 3)) + " vs base 64)");
        final int centre = ((W / 2) + (H / 2) * W) * 4;
        System.out.println("centre pixel = " + (px.get(centre) & 0xFF) + ","
                + (px.get(centre + 1) & 0xFF) + "," + (px.get(centre + 2) & 0xFF)
                + " a=" + (px.get(centre + 3) & 0xFF));

        final boolean lit = brighter > 100;
        System.out.println(lit ? "RESULT: light is drawn" : "RESULT: NO LIGHT");
        shader.close();
        GLFW.glfwTerminate();
        System.exit(lit ? 0 : 1);
    }
}
