package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Has this train actually departed?" - the gate for the beams and the dynamic lights.
 *
 * <p>A train standing in a depot / vehicle shed must not shine: no beam sheet, no dynamic light.
 * The signal for that is MTR's own {@code Train#getIsOnRoute()} flag, which is <b>synced to the
 * client</b> - see the javap evidence below.
 *
 * <h2>javap evidence (MTR-fabric-1.20.1-3.6.4.2.jar)</h2>
 * <pre>
 * mtr.data.Train
 *   protected boolean isOnRoute;                   // field
 *   public  final boolean getIsOnRoute();           // 0: getfield isOnRoute
 *   public  final boolean isOnRoute();
 *   public void writePacket(PacketByteBuf);         // 246: getfield isOnRoute
 *                                                   // 251: invokevirtual writeBoolean   &lt;- sent
 *   public Train(PacketByteBuf);                    // 358: readBoolean
 *                                                   // 363: putfield isOnRoute          &lt;- read back
 * mtr.data.TrainClient
 *   public TrainClient(PacketByteBuf);              // -> super(packet), nothing dropped
 *   private mtr.data.Depot depot;                   // no getter: depot membership is NOT readable
 *   public void copyFromTrain(Train);               // 127: getfield Train.isOnRoute
 *                                                   // 130: putfield isOnRoute
 * </pre>
 * So the client does receive a real, refreshed {@code isOnRoute} with every train sync
 * ({@code ClientData.TRAINS} is rebuilt from the packet), and {@code TrainClient} neither
 * overrides {@code writePacket} nor discards the flag. No field-name mismatch exists: the
 * serialised form is a plain {@code writeBoolean}/{@code readBoolean}; {@code is_on_route} is only
 * the msgpack/NBT key name.
 *
 * <h2>Why the old latch lit parked trains (the actual bug)</h2>
 * {@code Train.simulateTrain} parks a train whose flag is false, on the client too:
 * <pre>
 *  45: getfield isOnRoute ; ifne 161          // false -> park
 *  62: railProgress = (railLength + trainCars*spacing) / 2.0;
 *  97: speed = 0; 102: nextStoppingIndex = 0;
 * 105: world.isClient ; ifne 2304            // client: return right here
 * </pre>
 * i.e. {@code isOnRoute == false} pins the train to the <b>middle of its rail path</b> and never
 * moves it. The previous version also trusted a first-sighting heuristic
 * {@code railProgress > 0.5} as "already out of the shed" - and that parked position is half the
 * path length, always far above 0.5. Every shed train therefore latched {@code moved = true} on
 * the very first tick it was seen, which is exactly the reported symptom (a shed train that keeps
 * shining). The heuristic is meaningless: railProgress is a position along the path, not a
 * distance travelled out of the depot.
 *
 * <h2>The latch, tightened (second signal only)</h2>
 * The flag is authoritative; the latch exists only in case a client saw the train before its
 * departure packet arrived. It now requires <b>observed</b> motion, never a position:
 * <ul>
 *   <li>a step counts only when {@code 0.02 <= |step| <= 2.0} blocks per tick - below that is
 *       jitter, above that (2 blocks/tick = 144 km/h) is MTR re-parking the train by snapping
 *       {@code railProgress} to the middle of its path, or a teleport;</li>
 *   <li>the latch trips at {@code >= 2.0} blocks <b>cumulative</b> over <b>at least 10</b> moving
 *       ticks, so a single large step and a slow creep both fail;</li>
 *   <li>any implausible step clears the latch immediately (that is the "went back into the shed"
 *       snap), and 60 ticks (3 s) of no motion while off-route clears it as well;</li>
 *   <li>nothing is extrapolated, predicted or timed: only the deltas this client actually saw.</li>
 * </ul>
 *
 * <p>State is keyed like {@link LightController} keys its lights ({@code id:} when MTR sent a
 * stable train id, otherwise {@code tid:} + the train type id) so the two agree on identity.
 */
public final class DepartureState {
    /** Progress changes below this (blocks per tick) are jitter, not motion. */
    private static final double STEP_EPSILON = 0.02D;
    /** A single-tick step above this is a re-park / teleport, never driving (2 blocks/tick). */
    private static final double MAX_STEP = 2.0D;
    /** Cumulative observed distance (blocks) before the latch may trip. */
    private static final double DEPART_DISTANCE = 2.0D;
    /** ...and it must be spread over at least this many moving ticks. */
    private static final int DEPART_STEPS = 10;
    /** Off-route and motionless for this many ticks (3 s at 20 tps) drops the latch. */
    private static final int STILL_TICKS_TO_CLEAR = 60;

    /**
     * Temporary diagnostic, one INFO line every {@value #DIAG_PERIOD} ticks:
     * {@code depart: trains=N onRoute=M moved=K lit=L}. Turn it off with
     * {@code -Dmtrheadlight.departdiag=false} (or by flipping this constant).
     */
    private static final boolean DIAG =
            !"false".equalsIgnoreCase(System.getProperty("mtrheadlight.departdiag", "true"));
    private static final int DIAG_PERIOD = 100;

    private static final Map<String, Entry> TRACKED = new HashMap<>();
    private static ClientWorld lastWorld;
    private static long tickCounter;
    /** Last diagnostic numbers printed, so the line only appears when something changes. */
    private static String lastDiag = "";

    private DepartureState() {
    }

    public static void clear() {
        TRACKED.clear();
        lastDiag = "";
    }

    /**
     * Updates the latch for every train the client currently knows about. Called once per client
     * tick, before {@link LightController#tick()} and before any beam is drawn, so both consumers
     * read the same state.
     */
    public static void tick() {
        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientWorld world = client.world;
        if (world != lastWorld) {
            // Train ids are per world; keeping the map would leak (and could match a new train).
            TRACKED.clear();
            lastDiag = "";
            lastWorld = world;
        }
        if (world == null || client.player == null) {
            if (!TRACKED.isEmpty()) {
                TRACKED.clear();
            }
            return;
        }
        tickCounter++;

        int trains = 0;
        int onRoute = 0;
        int moved = 0;
        int lit = 0;
        final Set<String> seen = new HashSet<>();
        for (final TrainClient train : snapshotTrains()) {
            if (train == null || train.isRemoved || train.path.isEmpty()) {
                continue;
            }
            trains++;
            final String key = keyOf(train);
            seen.add(key);
            final double progress = train.getRailProgress();
            final boolean routed = isOnRoute(train);
            if (routed) {
                onRoute++;
            }

            Entry entry = TRACKED.get(key);
            if (entry == null) {
                // First sighting: no motion has been observed yet, whatever the position says.
                // A parked train sits at the middle of its path, so its progress is meaningless.
                entry = new Entry(train.isReversed(), progress);
                TRACKED.put(key, entry);
            } else {
                entry.observe(progress - entry.lastProgress);
                entry.lastProgress = progress;
            }
            if (!routed) {
                entry.settle();
            } else {
                entry.stillTicks = 0;
            }
            if (entry.moved) {
                moved++;
            }
            if (routed || entry.moved) {
                lit++;
            }
        }
        // A train that is gone is forgotten; when it comes back the latch starts over. No timeout
        // state to leak.
        TRACKED.keySet().retainAll(seen);

        if (DIAG && tickCounter % DIAG_PERIOD == 0L) {
            final String line = "depart: trains=" + trains + " onRoute=" + onRoute
                    + " moved=" + moved + " lit=" + lit;
            // "moved" is the latch alone; "lit" is the gate's verdict before the siding switch.
            if (!line.equals(lastDiag)) {
                MtrHeadlightClient.LOGGER.info("[lte] {}", line);
                lastDiag = line;
            }
        }
    }

    /**
     * True when this train may shine: MTR says it is on a route, or this client has actually seen
     * it drive. Unknown trains (no entry yet, e.g. the very first frame after a world load) are
     * "not departed" unless MTR flags them, so a shed full of new trains never flashes.
     */
    public static boolean departed(final TrainClient train) {
        if (train == null) {
            return false;
        }
        if (isOnRoute(train)) {
            return true;
        }
        final Entry entry = TRACKED.get(keyOf(train));
        return entry != null && entry.moved;
    }

    /**
     * Travel direction as MTR's {@code isReversed()} companion: {@code true} when the train runs
     * against its path, i.e. its nose is at the low-progress end. Follows the sign of the observed
     * progress delta, so a terminus turnaround swaps the ends.
     *
     * @param fallback value used before the first movement was observed
     */
    public static boolean reversed(final TrainClient train, final boolean fallback) {
        if (train == null) {
            return fallback;
        }
        final Entry entry = TRACKED.get(keyOf(train));
        return entry == null ? fallback : entry.reversed;
    }

    /** Identity of a train, matching the keys {@link LightController} uses for its lights. */
    public static String keyOf(final TrainClient train) {
        return train.id != 0L ? ("id:" + train.id) : ("tid:" + train.trainId);
    }

    /**
     * MTR's own "on a route" flag. Guarded: it is public API in every supported MTR generation,
     * but a lighting mod must never take the client down over a missing method in some fork.
     */
    private static boolean isOnRoute(final TrainClient train) {
        try {
            return train.getIsOnRoute();
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<TrainClient> snapshotTrains() {
        try {
            return new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** One train's minimal state: where it was, how far it visibly drove, which way it faces. */
    private static final class Entry {
        double lastProgress;
        /** Cumulative distance (blocks) this client saw the train travel with plausible steps. */
        double travelled;
        /** Ticks with a plausible movement step. */
        int movingTicks;
        /** Ticks without any movement. */
        int stillTicks;
        boolean moved;
        boolean reversed;

        Entry(final boolean reversedInitially, final double progress) {
            this.reversed = reversedInitially;
            this.lastProgress = progress;
        }

        /** Feeds one tick's rail-progress delta. Never looks at the absolute position. */
        void observe(final double delta) {
            final double step = Math.abs(delta);
            if (step < STEP_EPSILON) {
                stillTicks++;
                return;
            }
            if (step > MAX_STEP) {
                // MTR re-parks a train by snapping railProgress to the middle of its path (and a
                // teleport looks the same). That is not driving: drop the latch.
                reset();
                stillTicks = 0;
                return;
            }
            travelled += step;
            movingTicks++;
            stillTicks = 0;
            reversed = delta < 0.0D;
            if (travelled >= DEPART_DISTANCE && movingTicks >= DEPART_STEPS) {
                moved = true;
            }
        }

        /** Off-route: drop the latch again once the train has stood still long enough. */
        void settle() {
            if (stillTicks >= STILL_TICKS_TO_CLEAR) {
                reset();
            }
        }

        void reset() {
            travelled = 0.0D;
            movingTicks = 0;
            moved = false;
        }
    }
}
