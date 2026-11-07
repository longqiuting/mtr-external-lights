package dev.js.mtrheadlight;

/**
 * Diagnostics for the mass-clear fast path in {@link LightController}.
 *
 * <p>One INFO line every five seconds:
 * {@code clear: tracked=N missing=M massClear=K}, where {@code tracked} is how many trains we hold
 * lights for right now, {@code missing} how many of those are absent from this tick's
 * {@code ClientData.TRAINS} snapshot (i.e. what the jitter/despawn staging is judging), and
 * {@code massClear} how many trains' lights the fast path detached during the window
 * ({@code 0} = it never fired, so "刷新线路" did not reach it).
 *
 * <p>The line is unconditional while the flag is on - a silent window is indistinguishable from
 * "the tick is not running", which is exactly the failure this has to catch. Turn it off with
 * {@code "massClearDiagnostics": false} in {@code config/mtr-headlight.json}; the window counter is
 * reset while it is off, so re-enabling it cannot print a total spanning the disabled period.
 */
public final class ClearDiag {
    private static final int PERIOD_TICKS = 100;

    /** Trains detached by a mass clear since the last printed line. */
    private static int clearedInWindow;

    private ClearDiag() {
    }

    /** One train whose lights a mass clear removed; folded into the next line. */
    public static void cleared(final int trains) {
        clearedInWindow += trains;
    }

    /**
     * Called once per client tick, from the tick thread (never from inside an engine callback).
     *
     * @param enabled current {@code massClearDiagnostics}
     * @param tick    the controller's tick counter
     * @param tracked trains we hold lights for this tick
     * @param missing how many of those are absent from this tick's snapshot
     */
    public static void tick(final boolean enabled, final long tick, final int tracked, final int missing) {
        if (!enabled) {
            clearedInWindow = 0;
            return;
        }
        if (tick % PERIOD_TICKS != 0L) {
            return;
        }
        final int cleared = clearedInWindow;
        clearedInWindow = 0;
        try {
            MtrHeadlightClient.LOGGER.info("[mtr-headlight] clear: tracked={} missing={} massClear={}",
                    Integer.valueOf(tracked), Integer.valueOf(missing), Integer.valueOf(cleared));
        } catch (Throwable t) {
            // Logging must never break the tick.
        }
    }
}
