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
    private static final boolean ENABLED = true;
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

    /** Last values printed by {@link #railq}; -1 forces the first line. */
    private static long loggedQueued = -1L;
    private static long loggedRailSkips = -1L;
    private static long loggedRailRebuilds = -1L;
    private static int loggedStarved = -1;
    private static int loggedPending = -1;
    private static int loggedTrains = -1;

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

    /**
     * One line every five seconds describing the relight queue itself: how many chunks were
     * queued, how many dirty checks skipped, how many host calls were made, and how many requests
     * have been waiting for more than two seconds ({@code starved} - the direct measure of whether
     * the rebuild budget is too small for the number of trains in view).
     *
     * <p>Silent while none of the values move, like {@link #maybeLog}.
     */
    public static void railq(final long queuedCount, final long skippedCount, final long rebuiltCount,
                             final int starved, final int pending, final int trains) {
        if (!ENABLED) {
            return;
        }
        if (queuedCount == loggedQueued && skippedCount == loggedRailSkips
                && rebuiltCount == loggedRailRebuilds && starved == loggedStarved
                && pending == loggedPending && trains == loggedTrains) {
            return;
        }
        loggedQueued = queuedCount;
        loggedRailSkips = skippedCount;
        loggedRailRebuilds = rebuiltCount;
        loggedStarved = starved;
        loggedPending = pending;
        loggedTrains = trains;
        try {
            MtrHeadlightClient.LOGGER.info(
                    "[mtr-headlight] railq: queued={} skipped={} rebuilt={} starved={} pending={} trains={}",
                    queuedCount, skippedCount, rebuiltCount, starved, pending, trains);
        } catch (Throwable t) {
            // Logging must never break the tick.
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
                    "[mtr-headlight] rail bake: calls={} hits={} boost={} rebuilds={} skipped={} ownHits={} ownMax={}",
                    currentCalls, currentHits, currentBoosted, currentRebuilds, currentSkips,
                    OWN_HITS.get(), Math.round(ownMax * 10.0D) / 10.0D);
        } catch (Throwable t) {
            // Logging must never break the tick.
        }
    }
}
