package dev.js.mtrheadlight;

/**
 * Low-noise diagnostics for the rail bake chain.
 *
 * <p>{@code calls} counts every intercepted block-light query (proves the {@code @Redirect} applied
 * and is being called), {@code hits} the ones that actually raised the level, {@code rebuilds} the
 * {@code registerLightUpdate} calls we made and {@code skips} the sections whose light turned out to
 * be identical to what is already baked.
 *
 * <p>One INFO line every five seconds, and <b>only when one of the counters moved</b>: a steady
 * scene (nothing changed, nothing rebuilt) is silent, which is exactly the state the dirty check
 * aims for.
 */
public final class RailDiag {
    private static final boolean ENABLED = false;
    private static final int LOG_EVERY_TICKS = 100;

    private static long calls;
    private static long hits;
    private static long boosted;
    private static long rebuilds;
    private static long skips;

    private static long loggedCalls = -1L;
    private static long loggedHits = -1L;
    private static long loggedBoosted = -1L;
    private static long loggedRebuilds = -1L;
    private static long loggedSkips = -1L;

    /** Last absolute values handed to {@link #railq}; -1 forces a fresh baseline. */
    private static long loggedRailqReq = -1L;
    private static long loggedRailqRebuilt = -1L;
    private static long loggedRailqSkipped = -1L;
    private static long loggedRailqDeferred = -1L;
    private static long loggedRailqPaused = -1L;

    /**
     * One line every five seconds describing the rail relight queue and the gates in front of it:
     * {@code railq: req= rebuilt= skipped= deferred= paused= quota=}.
     *
     * <p>Every counter is a <b>per-window delta</b> (the five seconds since the previous line), so
     * {@code rebuilt} can be compared directly with {@code railRelightMaxPerSec}; {@code quota} is
     * the current one-second window as {@code used/cap}. {@code paused} counts client ticks that
     * were spent in the load grace (20 ticks = one second), which makes "did the join grace
     * actually happen" visible without a debugger.
     *
     * <p>Unconditional while {@code railRelightDiagnostics} is on: a silent line would be
     * indistinguishable from "the scheduler is not running", which is the bug this exists to
     * catch. Turning the flag off resets the baseline, so re-enabling it cannot print a delta
     * spanning the whole disabled period.
     */
    public static void railq(final boolean enabled, final long req, final long rebuilt,
                             final long skipped, final long deferred, final long paused,
                             final int quotaUsed, final int quotaCap) {
        if (!enabled) {
            loggedRailqReq = -1L;
            loggedRailqRebuilt = -1L;
            loggedRailqSkipped = -1L;
            loggedRailqDeferred = -1L;
            loggedRailqPaused = -1L;
            return;
        }
        final long dReq = loggedRailqReq < 0L ? req : req - loggedRailqReq;
        final long dRebuilt = loggedRailqRebuilt < 0L ? rebuilt : rebuilt - loggedRailqRebuilt;
        final long dSkipped = loggedRailqSkipped < 0L ? skipped : skipped - loggedRailqSkipped;
        final long dDeferred = loggedRailqDeferred < 0L ? deferred : deferred - loggedRailqDeferred;
        final long dPaused = loggedRailqPaused < 0L ? paused : paused - loggedRailqPaused;
        loggedRailqReq = req;
        loggedRailqRebuilt = rebuilt;
        loggedRailqSkipped = skipped;
        loggedRailqDeferred = deferred;
        loggedRailqPaused = paused;
        try {
            MtrHeadlightClient.LOGGER.info(
                    "[lte] railq: req={} rebuilt={} skipped={} deferred={} paused={} quota={}/{}",
                    Long.valueOf(dReq), Long.valueOf(dRebuilt), Long.valueOf(dSkipped),
                    Long.valueOf(dDeferred), Long.valueOf(dPaused),
                    Integer.valueOf(quotaUsed), Integer.valueOf(quotaCap));
        } catch (Throwable t) {
            // Logging must never break the tick.
        }
    }

    private RailDiag() {
    }

    /** One intercepted block-light query: proves the redirect is applied and being called. */
    public static void call() {
        if (ENABLED) {
            calls++;
        }
    }

    public static void hit() {
        if (ENABLED) {
            hits++;
        }
    }

    /** A sky-component duplicate under a shader pack (see {@code railLightShaderBoost}). */
    public static void boost() {
        if (ENABLED) {
            boosted++;
        }
    }

    public static void rebuild() {
        if (ENABLED) {
            rebuilds++;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger OWN_HITS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile double ownMax;

    /** Records the own-beam luminance seen while baking, so "train light is 0" is provable. */
    public static void observeOwn(final double own) {
        if (!ENABLED || own <= 0.0D) {
            return;
        }
        OWN_HITS.incrementAndGet();
        if (own > ownMax) {
            ownMax = own;
        }
    }

    /** A queued section whose sampled light matched what is already baked - no rebuild needed. */
    public static void skip() {
        if (ENABLED) {
            skips++;
        }
    }

    private static int loggedLdl2Sources = -1;
    private static int loggedLdl2Moved = -1;
    private static int loggedLdl2Relight = -1;

    /**
     * Ldl2Backend (MC 1.18.2 / 1.19.2 / 1.19.4) proof-of-life line:
     * {@code sources} is how many point lights are registered with LambDynamicLights,
     * {@code moved} how many reported a position or luminance change in the window, and
     * {@code relight} how many chunk-section rebuilds we asked LDL for. A train that is visibly
     * moving must show {@code moved > 0} and {@code relight > 0}; if {@code relight} stays 0 the
     * light can never appear, which is the bug this counter exists to catch.
     *
     * <p>Called at most once per five seconds from the render thread; silent when nothing moved.
     * Turn the whole diagnostics block off by setting {@link #ENABLED} to {@code false}.
     */
    public static void ldl2(final int sources, final int moved, final int relight) {
        if (!ENABLED) {
            return;
        }
        if (sources == loggedLdl2Sources && moved == loggedLdl2Moved && relight == loggedLdl2Relight) {
            return;
        }
        loggedLdl2Sources = sources;
        loggedLdl2Moved = moved;
        loggedLdl2Relight = relight;
        try {
            MtrHeadlightClient.LOGGER.info("[lte] ldl2: sources={} moved={} relight={}",
                    sources, moved, relight);
        } catch (Throwable t) {
            // Logging must never break the render pass.
        }
    }

    public static void maybeLog(final long tick) {
        if (!ENABLED) {
            return;
        }
        // Never diff against a sentinel here: tick - Long.MIN_VALUE overflows to a negative
        // value and the log would silently never fire (this cost us a debugging round).
        if (tick % LOG_EVERY_TICKS != 0) {
            return;
        }
        final long currentCalls = calls;
        final long currentHits = hits;
        final long currentBoosted = boosted;
        final long currentRebuilds = rebuilds;
        final long currentSkips = skips;
        if (currentCalls == loggedCalls && currentHits == loggedHits
                && currentBoosted == loggedBoosted
                && currentRebuilds == loggedRebuilds && currentSkips == loggedSkips) {
            return;
        }
        loggedCalls = currentCalls;
        loggedHits = currentHits;
        loggedBoosted = currentBoosted;
        loggedRebuilds = currentRebuilds;
        loggedSkips = currentSkips;
        try {
            MtrHeadlightClient.LOGGER.info(
                    "[lte] rail bake: calls={} hits={} boost={} rebuilds={} skipped={} ownHits={} ownMax={}",
                    currentCalls, currentHits, currentBoosted, currentRebuilds, currentSkips,
                    OWN_HITS.get(), Math.round(ownMax * 10.0D) / 10.0D);
        } catch (Throwable t) {
            // Logging must never break the tick.
        }
    }
}
