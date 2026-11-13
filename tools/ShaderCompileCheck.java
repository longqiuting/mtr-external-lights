// Compile the mod's core shader pair on the REAL driver, without launching the game.
//
// Why: a shader that fails to compile (or a uniform the compiler optimises away) only shows up as
// one WARN in a 3-minute launch, and a missing uniform silently disables the whole pass
// (ScatterProgram#require). This checks both in about one second.
//
//   CP=$(find ~/.gradle/caches/modules-2/files-2.1/org.lwjgl/{lwjgl,lwjgl-glfw,lwjgl-opengl}/3.3.2 \
//          -name '*.jar' | grep -E '3\.3\.2(-natives-windows)?\.jar$' | paste -sd';')
//   javac -cp "$CP" -d /tmp/scc tools/ShaderCompileCheck.java
//   java  -cp "$CP:/tmp/scc" ShaderCompileCheck \
//       v1.20.1/src/main/resources/assets/mtrlte/shaders/core/light_scatter.fsh \
//       v1.20.1/src/main/resources/assets/mtrlte/shaders/core/light_scatter.vsh
//
// It creates a hidden 3.3 core GLFW window, compiles + links, then prints the location of every
// uniform ScatterProgram requires plus the texture unit each sampler resolves to (which must match
// the order of the json's "samplers" array). Exit code 1 if anything is missing.
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Compiles the mod's light_scatter shader pair on the real driver, no game needed. */
public final class ShaderCompileCheck {

    private static String read(final Path p) throws Exception {
        return Files.readString(p);
    }

    private static int compile(final int type, final String src, final String label) {
        final int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        final int ok = glGetShaderi(id, GL_COMPILE_STATUS);
        final String log = glGetShaderInfoLog(id);
        System.out.println(label + " compile=" + (ok == GL_TRUE ? "ok" : "FAILED"));
        if (!log.isEmpty()) {
            System.out.println("  log: " + log.trim());
        }
        if (ok != GL_TRUE) {
            throw new IllegalStateException(label + " failed to compile");
        }
        return id;
    }

    public static void main(final String[] args) throws Exception {
        final Path fsh = Path.of(args[0]);
        final Path vsh = Path.of(args[1]);
        final List<String> expected = List.of(
                "uInvViewProj", "uParams", "uCone", "uSurface", "uCount", "uTest",
                "uLightPos", "uLightDir", "uLightColor");
        final List<String> samplers = List.of("Sampler0", "Sampler1");

        if (!glfwInit()) {
            throw new IllegalStateException("glfwInit failed");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        final long win = glfwCreateWindow(64, 64, "shadercheck", 0, 0);
        if (win == 0L) {
            throw new IllegalStateException("no window");
        }
        glfwMakeContextCurrent(win);
        GL.createCapabilities();
        System.out.println("GL " + glGetString(GL_VERSION) + " | GLSL " + glGetString(GL_SHADING_LANGUAGE_VERSION));

        final int vs = compile(GL_VERTEX_SHADER, read(vsh), "vertex");
        final int fs = compile(GL_FRAGMENT_SHADER, read(fsh), "fragment");
        final int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        // vanilla binds the VertexFormat attribute names to 0,1,2.. in order (POSITION = 0)
        glBindAttribLocation(prog, 0, "Position");
        glLinkProgram(prog);
        final int linked = glGetProgrami(prog, GL_LINK_STATUS);
        final String log = glGetProgramInfoLog(prog);
        System.out.println("link=" + (linked == GL_TRUE ? "ok" : "FAILED"));
        if (!log.isEmpty()) {
            System.out.println("  log: " + log.trim());
        }
        if (linked != GL_TRUE) {
            throw new IllegalStateException("link failed");
        }

        int missing = 0;
        System.out.println("\nuniforms the Java side requires:");
        for (final String name : expected) {
            final int loc = glGetUniformLocation(prog, name);
            System.out.printf("  %-14s %s%n", name, loc < 0 ? "MISSING (optimised out!)" : "ok @" + loc);
            if (loc < 0) {
                missing++;
            }
        }
        System.out.println("\nsamplers (unit must match json sampler order):");
        for (int i = 0; i < samplers.size(); i++) {
            final int loc = glGetUniformLocation(prog, samplers.get(i));
            if (loc < 0) {
                System.out.printf("  %-10s MISSING%n", samplers.get(i));
                missing++;
                continue;
            }
            // vanilla ShaderProgram assigns sampler i -> texture unit i
            glUseProgram(prog);
            glUniform1i(loc, i);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final var buf = stack.mallocInt(1);
                glGetUniformiv(prog, loc, buf);
                System.out.printf("  %-10s @%d unit=%d%n", samplers.get(i), loc, buf.get(0));
            }
        }
        final int pos = glGetAttribLocation(prog, "Position");
        System.out.println("\nattribute Position @" + pos);

        glUseProgram(prog);
        glDeleteProgram(prog);
        glfwDestroyWindow(win);
        glfwTerminate();
        System.out.println(missing == 0 ? "\nRESULT: all names present" : "\nRESULT: " + missing + " MISSING");
        if (missing != 0) {
            System.exit(1);
        }
    }
}
