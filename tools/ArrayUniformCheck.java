// Proves — on the real driver, in about a second, without launching the game — that the lamp arrays
// of light_scatter.fsh are fed correctly, and what happens when they are not.
//
// Why this exists: in 1.0.62 every lamp array was declared in light_scatter.json as
// {"type":"float","count":64}. 1.20.1's GlUniform then uploads it with glUniform1fv, which the
// driver rejects for a vec4[16] location (GL_INVALID_OPERATION), so the arrays kept their json
// defaults - uLightColor[i].w (the intensity) 0 - and the fragment loop's first statement
// `if (power <= 1e-4) continue;` skipped every lamp. Symptom: /mtr_lte scatter debug surface|cone
// black everywhere, debug normal fine, no lighting at all, while every logged field looked sane.
//
//   CP=$(find ~/.gradle/caches/modules-2/files-2.1/org.lwjgl/{lwjgl,lwjgl-glfw,lwjgl-opengl}/3.3.2 \
//          -name '*.jar' | grep -E '3\.3\.2(-natives-windows)?\.jar$' | paste -sd';')
//   javac -cp "$CP" -d /tmp/auc tools/ArrayUniformCheck.java
//   java  -cp "$CP:/tmp/auc" ArrayUniformCheck \
//       v1.20.1/src/main/resources/assets/mtrlte/shaders/core/light_scatter.fsh \
//       v1.20.1/src/main/resources/assets/mtrlte/shaders/core/light_scatter.vsh
//
// Expected (NVIDIA 621.24 / GL 3.3 core):
//   uLightPos[0] @38  uLightDir[0] @22  uLightColor[0] @6
//   A) glUniform1fv  -> glGetError=0x502, readback (0,0,0,0)      = the bug
//   B) glUniform4fv  -> glGetError=0x0,   readback = what we sent = the fix
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.memAllocFloat;

/** glUniform1fv vs glUniform4fv on the mod's real {@code vec4 uLightPos[16]} uniform. */
public final class ArrayUniformCheck {

    public static void main(final String[] args) throws Exception {
        final String fsh = Files.readString(Path.of(args[0]));
        final String vsh = Files.readString(Path.of(args[1]));
        glfwInit();
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        final long win = glfwCreateWindow(64, 64, "arr", 0, 0);
        glfwMakeContextCurrent(win);
        GL.createCapabilities();
        System.out.println("GL " + glGetString(GL_VERSION));

        final int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, vsh);
        glCompileShader(vs);
        final int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, fsh);
        glCompileShader(fs);
        final int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glBindAttribLocation(prog, 0, "Position");
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException(glGetProgramInfoLog(prog));
        }
        glUseProgram(prog);

        // The array name WITHOUT [0] is what ScatterProgram queries - prove it resolves.
        final int pos = glGetUniformLocation(prog, "uLightPos");
        final int dir = glGetUniformLocation(prog, "uLightDir");
        final int col = glGetUniformLocation(prog, "uLightColor");
        System.out.println("uLightPos[0] @" + pos + "  uLightDir[0] @" + dir
                + "  uLightColor[0] @" + col);

        // What ScatterLights hands over: camera-relative lamp + radius 6, unit heading + beam 32,
        // colour + intensity 0.62, all 16 slots filled.
        final float[] p = new float[64];
        final float[] d = new float[64];
        final float[] c = new float[64];
        for (int i = 0; i < 16; i++) {
            p[i * 4] = 1.25F;
            p[i * 4 + 1] = -0.5F;
            p[i * 4 + 2] = 4.0F;
            p[i * 4 + 3] = 6.0F;
            d[i * 4 + 1] = 0.0F;
            d[i * 4 + 2] = -1.0F;
            d[i * 4 + 3] = 32.0F;
            c[i * 4] = 1.0F;
            c[i * 4 + 1] = 0.95F;
            c[i * 4 + 2] = 0.85F;
            c[i * 4 + 3] = 0.62F;
        }

        System.out.println("\n--- A) the 1.0.62 path: glUniform1fv with 64 floats "
                + "(json \"float\", count 64)");
        while (glGetError() != GL_NO_ERROR) { }
        final FloatBuffer flat = memAllocFloat(64);
        flat.put(c).position(0);
        glUniform1fv(col, flat);
        final int errA = glGetError();
        System.out.println("  glUniform1fv -> glGetError=0x" + Integer.toHexString(errA)
                + (errA == GL_NO_ERROR ? " (no error reported)" : " (rejected)"));
        System.out.println("  readback uLightColor[0] = " + readback(prog, col)
                + "   <- w is the intensity the shader tests with 'if (power <= 1e-4) continue;'");

        System.out.println("\n--- B) the fix: glUniform4fv, one call, 16 vec4s");
        while (glGetError() != GL_NO_ERROR) { }
        final FloatBuffer pb = memAllocFloat(64);
        pb.put(p).position(0);
        final FloatBuffer db = memAllocFloat(64);
        db.put(d).position(0);
        final FloatBuffer cb = memAllocFloat(64);
        cb.put(c).position(0);
        glUniform4fv(pos, pb);
        glUniform4fv(dir, db);
        glUniform4fv(col, cb);
        final int errB = glGetError();
        System.out.println("  glUniform4fv -> glGetError=0x" + Integer.toHexString(errB));
        System.out.println("  readback uLightPos[0]   = " + readback(prog, pos));
        System.out.println("  readback uLightDir[0]   = " + readback(prog, dir));
        System.out.println("  readback uLightColor[0] = " + readback(prog, col));
        System.out.println("  readback uLightDir[15]  = " + readback(prog, dir + 15));

        glfwDestroyWindow(win);
        glfwTerminate();
    }

    private static String readback(final int prog, final int loc) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final var buf = stack.mallocFloat(4);
            glGetUniformfv(prog, loc, buf);
            return "(" + buf.get(0) + ", " + buf.get(1) + ", " + buf.get(2) + ", " + buf.get(3) + ")";
        }
    }
}
