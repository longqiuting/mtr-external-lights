package dev.js.mtrheadlight;

import dev.js.mtrheadlight.mixin.HostProbe;

/**
 * Raises the number of rail chunks the host may rebuild per frame, and counts what actually
 * happened.
 *
 * <p>The host (ANTE / NTE) rebuilds rail chunks inside {@code RailRenderDispatcher#drawRails} with
 * a hard-coded per-frame gate:
 *
 * <pre>{@code
 *   if (isDirty || !bufferBuilt) {
 *       if (isReplayMod || buffersRebuilt < 1) {   // &lt;-- the 1 is the bottleneck
 *           chunk.rebuildBuffer(world);
 *       }
 *       buffersRebuilt++;
 *   }
 * }</pre>
 *
 * <p>One chunk per frame is enough at 20-60 fps as long as a rebuild is a cheap instance-buffer
 * write (ANTE render level 3, "快速/最快"). At LDL's 华丽 (fancy) level the host falls back to
 * {@code MeshBuildingRailChunk}, where a rebuild is a full mesh bake + GPU upload, and the queue a
 * moving train fills is deeper than one entry per frame can drain - the light band then visibly
 * trails the train even at 80 km/h. The mixins raise that {@code 1} to
 * {@link HeadlightConfig#railChunkBuildPerFrame}.
 *
 * <p>Risk control, in order of importance:
 *
 * <ul>
 *   <li>the gate is the <em>existing</em> loop counter, so nothing here ever calls the host's
 *       {@code rebuildBuffer} itself - an empty queue stays exactly as idle as before (no extra
 *       calls, no spin);</li>
 *   <li>{@link HeadlightConfig#railChunkBuildBoost} off restores stock behaviour ({@code 1})
 *       without rebuilding the jar or even restarting: the value is read per evaluation;</li>
 *   <li>the value is clamped to 1..8, because the worst case is N full mesh bakes in one frame;</li>
 *   <li>both injectors are string-targeted with {@code require = 0}, so a host update that moves
 *       the loop simply loses the boost instead of crashing the client.</li>
 * </ul>
 *
 * <p>Diagnostics: one {@code railbuild: perFrame= calls= chunksPerSec=} line every five seconds
 * (gated by {@link HeadlightConfig#railRelightDiagnostics}). {@code calls} counts how often the
 * limit was consulted - it stays {@code 0} when the injection did not apply, which is the fastest
 * way to tell "boost active" from "host changed". {@code chunksPerSec} counts real
 * {@code rebuildBuffer} invocations in the window, i.e. the number to compare against the stock
 * ceiling of {@code perFrame 1 x fps}.
 */
public final class RailChunkBuildBoost {
    /** Five seconds at 20 client ticks/s. */
    private static final int WINDOW_TICKS = 100;
    /** Hard ceiling on the config value; also the guard for hand-edited JSON. */
    public static final int MAX_PER_FRAME = 8;

    /** Limit consultations (the {@code @ModifyConstant} handler ran). */
    private static long calls;
    /** Real {@code rebuildBuffer} invocations counted in {@code drawRails}. */
    private static long rebuilds;

    private static int ticksInWindow;
    private static long loggedCalls = -1L;
    private static long loggedRebuilds = -1L;
    /** Last printed {@code rail mixin: ...} line, so the report only prints when it changes. */
    private static volatile String lastMixinReport;
    /** Ticks spent with a world loaded; gates the "host present but no mixin" warning. */
    private static int hostWarnTicks;
    private static boolean hostWarned;

    private RailChunkBuildBoost() {
    }

    /**
     * The per-frame rail chunk rebuild limit the host should use: {@code 1} (stock) when the boost
     * is switched off, otherwise the configured value clamped to 1..8.
     */
    public static int perFrame(final HeadlightConfig cfg) {
        if (cfg == null || !cfg.railChunkBuildBoost) {
            return 1;
        }
        final int n = cfg.railChunkBuildPerFrame;
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_PER_FRAME);
    }

    /** The live config's value, for the mixins (never throws, always in range). */
    public static int perFrame() {
        return perFrame(MtrHeadlightClient.config());
    }

    /** One consultation of the limit inside {@code drawRails}. */
    public static void call() {
        calls++;
    }

    /** One actual {@code rebuildBuffer} call inside {@code drawRails}. */
    public static void rebuild() {
        rebuilds++;
    }

    /**
     * Prints one {@code rail mixin: ante=<applied|none> nte=<applied|none> boost=<N>} line when the
     * state changes.
     *
     * <p>This is the answer to "which host's rail mixins are live in <em>this</em> instance". It is
     * printed from client init (before any world loads) and again after the first host class is
     * transformed or the config value changes, so a build that silently stops matching one host can
     * be diagnosed from the log alone - {@code boost=3 ante=none nte=none} on an ANTE client is the
     * exact signature of the bug this line was added for (1.19.x builds shipped only the NTE config,
     * so an ANTE client applied nothing at all while {@code require = 0} kept it silent).
     *
     * <p>Called from the client tick as well as from init because the host class is usually loaded
     * by the other mod's own initializer, which may run after ours.
     */
    public static void report() {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            final String state = "rail mixin: ante=" + state(RailMixinFlags.anteApplied(), RailMixinFlags.anteMatched())
                    + " nte=" + state(RailMixinFlags.nteApplied(), RailMixinFlags.nteMatched())
                    + " boost=" + perFrame(cfg);
            if (state.equals(lastMixinReport)) {
                return;
            }
            lastMixinReport = state;
            MtrHeadlightClient.LOGGER.info("[lte] {}", state);
        } catch (Throwable t) {
            // A diagnostic must never break init or the client tick.
        }
    }

    /**
     * {@code applied} = the mixin was transformed into the host class <em>and</em> its
     * {@code iconst_1} was rewritten (verified against the transformed bytecode);
     * {@code no-match} = the mixin class landed but the injection did not, i.e. the host changed and
     * the feature is inactive; {@code none} = that host's config never applied (host absent, or the
     * wrong package family).
     */
    private static String state(final boolean applied, final boolean matched) {
        if (!applied) {
            return "none";
        }
        return matched ? "applied" : "no-match";
    }

    /**
     * Warns once when a rail host is installed but neither rail mixin was applied - the failure that
     * used to be completely silent ({@code require = 0} drops a non-matching injection without a
     * word). Delayed until the client has been in a world for a few seconds, because the host class
     * is loaded by the host mod's initializer and may legitimately land after ours.
     */
    private static void warnIfHostWithoutMixin() {
        if (hostWarned || RailMixinFlags.anyMatched()) {
            return;
        }
        if (++hostWarnTicks < 200) {
            return;
        }
        final boolean ante = HostProbe.ante();
        final boolean nte = HostProbe.nte();
        if (!ante && !nte) {
            return;
        }
        hostWarned = true;
        if (RailMixinFlags.appliedButNotMatched()) {
            MtrHeadlightClient.LOGGER.warn(
                    "[lte] rail mixin: applied to the host but the per-frame gate was not"
                            + " found (ante={} nte={}) - rebuild boost inactive, host bytecode changed",
                    Boolean.valueOf(ante), Boolean.valueOf(nte));
            return;
        }
        MtrHeadlightClient.LOGGER.warn(
                "[lte] rail mixin: host present (ante={} nte={}) but no rail mixin applied"
                        + " - rebuild boost and baked rail light are inactive",
                Boolean.valueOf(ante), Boolean.valueOf(nte));
    }

    /**
     * Called once per client tick. Prints one line every five seconds while the boost is on, and
     * resets its baseline while it is off so re-enabling cannot print a delta spanning the whole
     * disabled period.
     */
    public static void tick(final HeadlightConfig cfg) {
        try {
            // Cheap: both only log when something actually changed (and only warn once).
            report();
            warnIfHostWithoutMixin();
            if (cfg == null || !cfg.railChunkBuildBoost || !cfg.railRelightDiagnostics) {
                ticksInWindow = 0;
                loggedCalls = -1L;
                loggedRebuilds = -1L;
                return;
            }
            ticksInWindow++;
            if (ticksInWindow < WINDOW_TICKS) {
                return;
            }
            final long currentCalls = calls;
            final long currentRebuilds = rebuilds;
            final long deltaCalls = loggedCalls < 0L ? currentCalls : currentCalls - loggedCalls;
            final long deltaRebuilds =
                    loggedRebuilds < 0L ? currentRebuilds : currentRebuilds - loggedRebuilds;
            loggedCalls = currentCalls;
            loggedRebuilds = currentRebuilds;
            final double seconds = ticksInWindow / 20.0D;
            ticksInWindow = 0;
            MtrHeadlightClient.LOGGER.info(
                    "[lte] railbuild: perFrame={} calls={} chunksPerSec={}",
                    Integer.valueOf(perFrame(cfg)), Long.valueOf(deltaCalls),
                    Long.valueOf(Math.round(deltaRebuilds / seconds)));
        } catch (Throwable t) {
            // Diagnostics must never break the client tick.
        }
    }
}
