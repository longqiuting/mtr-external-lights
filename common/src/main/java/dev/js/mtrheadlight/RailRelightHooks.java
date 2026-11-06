package dev.js.mtrheadlight;

/**
 * Version-neutral hook that lets a version-local scheduler piggyback on {@link LightController#tick()}.
 *
 * <p>The rail relight path only exists where ATE/ANTE is mixed into (1.20.1 today). The other
 * versions simply never install a hook, so this stays a no-op for them and their builds keep
 * working against the same {@code common} sources.
 */
public final class RailRelightHooks {
    /** Called once per client tick, after the lights themselves have been updated. */
    public interface Hook {
        void tick(HeadlightConfig cfg, long tick);
    }

    private static volatile Hook hook;

    private RailRelightHooks() {
    }

    /** Installs the version-local scheduler; repeated calls replace the previous one. */
    public static void install(final Hook newHook) {
        hook = newHook;
    }

    public static void tick(final HeadlightConfig cfg, final long tick) {
        final Hook current = hook;
        if (current == null) {
            return;
        }
        try {
            current.tick(cfg, tick);
        } catch (Throwable t) {
            // Rail relighting is cosmetic: stay silent and keep the client tick alive.
        }
    }
}
