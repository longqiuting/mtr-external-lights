package dev.js.mtrheadlight;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflective bridge to the rail render dispatcher of whichever host mod is installed.
 *
 * <p>Two mods provide the same API under different packages:
 *
 * <ul>
 *   <li>ANTE ({@code fabric.cn.zbx1425.mtrsteamloco}) - the 1.20.1 line;</li>
 *   <li>NTE ({@code cn.zbx1425.mtrsteamloco}) - the 1.18.2/1.19.x line.</li>
 * </ul>
 *
 * <p>Either may be absent. Reflection keeps one implementation for every version and makes the
 * absence a plain "no dispatcher" instead of a {@code NoClassDefFoundError}; every failure path
 * degrades silently because rail relighting is cosmetic.
 */
public final class RailRelightDispatcher {
    /** Host entry points, newest naming first. Both expose {@code railRenderDispatcher}. */
    private static final String[] MAIN_CLASSES = {
            "fabric.cn.zbx1425.mtrsteamloco.MainClient",
            "cn.zbx1425.mtrsteamloco.MainClient"
    };

    private static boolean resolved;
    private static Field dispatcherField;
    private static Method registerLightUpdate;

    private RailRelightDispatcher() {
    }

    /** True when a host mod is present and exposes the dispatcher API. */
    public static synchronized boolean available() {
        resolve();
        return dispatcherField != null && registerLightUpdate != null;
    }

    /**
     * Drops every rail chunk cache in the host ({@code RailRenderDispatcher#clearRail}), forcing it to
     * rebuild the geometry from scratch on the next frame.
     *
     * <p>Needed when the render path changes (turning a shader pack on/off): the host swaps chunk
     * implementations there, and section-level light updates no longer match the chunks that are now
     * on screen, so without this the freshly switched path keeps whatever light it was built with.
     */
    public static synchronized boolean clearAll() {
        resolve();
        final Field field = dispatcherField;
        if (field == null) {
            return false;
        }
        try {
            final Object dispatcher = field.get(null);
            if (dispatcher == null) {
                return false;
            }
            dispatcher.getClass().getMethod("clearRail").invoke(dispatcher);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Asks the host to rebuild the rail chunk that covers a section.
     *
     * <p>The host's signature is
     * {@code registerLightUpdate(int x, int ySectionFrom, int ySectionTo, int z)} - all four are
     * <em>section</em> coordinates (block {@code >> 4}), not blocks and not a radius. This mirrors
     * what the host passes from its own {@code setSectionDirty(sectionX, sectionY, sectionZ)}.
     * Passing block coordinates silently marks an unrelated chunk.
     *
     * @return false when no host is installed, it is not initialised yet, or the call failed;
     *         callers retry on the next scheduled tick.
     */
    public static boolean register(final int sectionX, final int ySectionFrom,
                                   final int ySectionTo, final int sectionZ) {
        final Field field = dispatcherField;
        final Method method = registerLightUpdate;
        if (field == null || method == null) {
            return false;
        }
        try {
            final Object dispatcher = field.get(null);
            if (dispatcher == null) {
                return false;
            }
            method.invoke(dispatcher, sectionX, ySectionFrom, ySectionTo, sectionZ);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        for (final String mainClass : MAIN_CLASSES) {
            try {
                final Class<?> main = Class.forName(mainClass, false, RailRelightDispatcher.class.getClassLoader());
                final Field field = main.getField("railRenderDispatcher");
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                final Method method = field.getType().getMethod(
                        "registerLightUpdate", int.class, int.class, int.class, int.class);
                if (method.getReturnType() != void.class) {
                    continue;
                }
                dispatcherField = field;
                registerLightUpdate = method;
                return;
            } catch (Throwable t) {
                // Class, field or method missing in this host version: try the next name.
            }
        }
    }
}
