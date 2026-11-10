package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Time-based rail relighting, shared by every version and both host mods.
 *
 * <p>ANTE/NTE bakes block light into their rail mesh once per chunk rebuild and only rebuilds when
 * the vanilla light data around a rail changes - moving trains and LambDynamicLights sources are
 * invisible to it. So we poll the light around the player and around every visible train head and
 * ask the host to rebuild the rail chunk that covers it.
 *
 * <p>A rebuild is expensive (32x32 rail mesh: model sampling, vertex bake, GPU re-upload), and the
 * light almost never changes between two polls. Every request therefore goes through a
 * <em>dirty check</em> before it reaches the host:
 *
 * <ol>
 *   <li>the light is sampled on an 8x8 grid over the <b>rail chunk</b> ({@link #SAMPLE_GRID}, one
 *       sample per 4 blocks) - the chunk, not the 16-block section, because that is the unit the
 *       host rebuilds ({@code BakedRail.chunkIdFromSectPos} shifts the section x/z by one);</li>
 *   <li>the grid is compared with the one that produced the geometry currently on screen;</li>
 *   <li>only when a cell moved by at least {@code railLightDelta} is the host asked to rebuild,
 *       so a steady scene costs <b>zero</b> host calls per second.</li>
 * </ol>
 *
 * <p><b>Sharing the host's build slot.</b> {@code RailRenderDispatcher#drawRails} rebuilds at most
 * one rail chunk per frame ({@code if (buffersRebuilt >= 1) skip}) and walks the chunks sorted by
 * distance to the camera, so a chunk next to the player always beats a chunk of newly loaded
 * terrain - the two share <em>one</em> queue. Four gates keep our share small enough that a region
 * still streams at its own speed:
 *
 * <ol>
 *   <li><b>load grace</b> ({@code railRelightLoadGraceSec}, default 8 s after the world object
 *       changes): nothing is queued, sampled or asked for, so ANTE's queue is entirely its own
 *       while the join streams. Polling continues, and those chunk builds bake our light through
 *       {@link RailLightInject}, so the rails that stream in come out lit anyway;</li>
 *   <li><b>distance + view filter</b> ({@code railRelightMaxBlockDist} 64, {@code
 *       railRelightFrustumCos} 0.25): a chunk whose centre is farther than 64 blocks, or more than
 *       ~75 degrees off the player's yaw, is never asked for;</li>
 *   <li><b>per-chunk cooldown</b> ({@code railRelightCooldownMs} 500): one chunk at most twice a
 *       second, no matter how fast the train's own cadence is;</li>
 *   <li><b>per-second quota</b> ({@code railRelightMaxPerSec} 12): a hard ceiling over every train
 *       plus the player chunk, so the host keeps at least 40% of a 20 fps build slot.</li>
 * </ol>
 *
 * <p>The queue is drained once every {@code railRelightEveryTicks} (default 3) client ticks, at
 * most {@code railRelightBudget} (default 2) host calls per drain. Before the gates above existed
 * the ceiling was {@code 20 / 3 * 2 = 13.3} rebuilds per second regardless of how many trains were
 * in view; the quota is what actually binds now.
 *
 * <p><b>Rate follows the speed, not the train count.</b> Each train has its own relight cadence,
 * {@code ceil(railRelightMaxMove / speed)} ticks clamped to
 * {@code [2, railRelightMaxInterval]} - so the band is never further behind than
 * {@code railRelightMaxMove} blocks at any speed. 25 blocks/s means every poll (2 ticks), 10
 * blocks/s every 4, 5 blocks/s every 8, and a standing train only every
 * {@code railRelightIdleInterval} (24 ticks, 1.2 s) - it has not moved, so all that cadence has to
 * catch is a held torch changing. A cheap scene therefore costs a handful of 8x8 samples, and no
 * host calls at all when the signature is unchanged.
 *
 * <p><b>Priority.</b> The queue is kept in five buckets, drained lowest number first: the chunk in
 * front of a fast train, its head chunk, the chunks of slow/standing trains, the player's own
 * chunk, and finally the chunks a beam has just left. Both the drain order and the overflow rule
 * (drop the least urgent, oldest entry) use that order, so a stale trail clear can never push out
 * the chunk a train is about to drive into.
 *
 * <p><b>Following a fast train.</b> One request covers a whole 32x32 rail chunk, and the queue and
 * the signature cache are keyed by that chunk, so the four sections inside a chunk collapse into
 * one request. For each train the scheduler now queues the chunk under the <em>start</em> of the
 * head beam and the chunk under its <em>end plus a speed-dependent lead</em>. That is the fix for
 * the dark gap at a chunk border: the beam reaches up to {@code headLength} (11) blocks ahead, so
 * without the second request the rails in front of the train stayed baked dark until the nose
 * itself crossed the border.
 *
 * <p><b>Clearing the trail.</b> When a train's beam start moves into another chunk, the chunk it
 * came from is queued again so the host bakes it <em>without</em> the beam. Only the last chunk of
 * each train is remembered ({@link Track}); a train that is removed, muted or leaves
 * {@code maxDistance} is pruned after {@link #PRUNE_TICKS} and its chunk is cleared the same way.
 *
 * <p>Nothing here predicts a train's path: the lead is a pure function of the beam length and the
 * train's current {@code getSpeed()}, and the only per-train state is "chunk it was last seen in".
 *
 * <p>Installed from the version's {@code RenderHooks} through {@link #install()}; the injection
 * itself lives in the per-host mixins plus {@link RailLightInject}.
 */
public final class RailRelightScheduler implements RailRelightHooks.Hook {
    private static final int BEAM_STRIDE = 7;
    /** One rail chunk is 32x32 blocks; that is the unit {@code registerLightUpdate} rebuilds. */
    private static final int CHUNK_BLOCKS = 32;
    /** Within this many blocks the view filter is skipped: the chunk is underfoot and on screen. */
    private static final double NEAR_BLOCKS = 16.0D;
    /** Light sample spacing inside a chunk; 4 blocks means an 8x8 grid per chunk. */
    private static final int SAMPLE_STEP = 4;
    private static final int SAMPLE_GRID = CHUNK_BLOCKS / SAMPLE_STEP;
    /** Requests waiting for the per-opportunity host budget. */
    private static final int MAX_QUEUE = 24;
    /** Upper bound on remembered chunk signatures; a long flight must not grow this without end. */
    private static final int MAX_CACHE = 512;
    /** Poll period while the player is still and nothing is tracked (no sampling, no host calls). */
    private static final int IDLE_POLL_TICKS = 16;
    /** Ticks a tracked train may be missing before its chunk is handed back for clearing. */
    private static final int PRUNE_TICKS = 40;

    /** Blocks per tick below which a train counts as standing still (1 block/s). */
    private static final double SLOW_SPEED = 0.05D;
    /** Blocks per tick from which a train counts as "fast" and gets the urgent queue (8 blocks/s). */
    private static final double FAST_SPEED = 0.4D;

    /*
     * Queue priorities. The drain takes the lowest number first. The head chunk comes first: that
     * is where the light actually is, so a stale one is visible immediately, while an unlit
     * prefetch chunk only shows up as a gap once the train gets there. The chunks a beam just left
     * only have to go dark again, so they are the first thing dropped when the frame budget is
     * short.
     */
    /** The chunk a fast train's beam starts in - where the light is brightest. */
    private static final int PRIO_HEAD = 0;
    /** The chunk a fast train's beam will reach (queued once per chunk, see {@link #track}). */
    private static final int PRIO_AHEAD = 1;
    /** Head and prefetch chunks of a slow or standing train. */
    private static final int PRIO_SLOW = 2;
    /** The player's own chunk: held torch / any LambDynamicLights source. */
    private static final int PRIO_PLAYER = 3;
    /** A chunk a beam has left (or a train that vanished); it only has to be baked dark again. */
    private static final int PRIO_TRAIL = 4;
    private static final int PRIO_COUNT = 5;

    private final ArrayDeque<Request>[] queues = new ArrayDeque[PRIO_COUNT];
    /** Chunk key -> the queued request, so a chunk is queued once and can be re-prioritised. */
    private final Map<Long, Request> pendingByChunk = new HashMap<>();
    private int pendingCount;

    /** Rail chunk key -> light grid that is currently baked into that chunk's rail mesh. */
    private final Map<Long, byte[]> baked = new HashMap<>();
    /** Rail chunk key -> wall-clock ms of the last host call for it (per-chunk cooldown). */
    private final Map<Long, Long> lastBuildMs = new HashMap<>();
    /** Train id -> the chunk its beam started in last time we looked. */
    private final Map<Long, Track> tracked = new HashMap<>();
    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

    /* Camera state for the distance + view filter, captured once per poll (never per request). */
    private double camX;
    private double camY;
    private double camZ;
    /** Horizontal look direction (unit vector); yaw only, so looking down does not blind the filter. */
    private double lookX = -1.0D;
    private double lookZ;
    private boolean camValid;

    /**
     * Wall-clock ms until which every request is withheld (load grace, see
     * {@code railRelightLoadGraceSec}); {@code 0} means "no grace".
     */
    private long loadGraceUntilMs;
    /** {@link System#currentTimeMillis()} of the current tick: cooldown and quota are time based. */
    private long nowMs;
    /** Set while the tick is inside the load grace, so {@link #offerChunk} can refuse everything. */
    private boolean suppressOffers;

    /* Per-second host-call quota (all trains + the player chunk together). */
    private int quotaUsed;
    private long quotaWindowStartMs;
    /** Siding id -> host calls made for it in the current window (per-siding share of the quota). */
    private final Map<Long, Integer> sidingQuota = new HashMap<>();

    private ClientWorld lastWorld;
    /** Last observed shader-pack state; a change invalidates every cached signature. */
    private boolean lastShaderActive;

    /** Client tick of the current call, so queue entries can carry their age. */
    private long now = Long.MIN_VALUE;
    private long lastPollTick = Long.MIN_VALUE;
    private long lastDrainTick = Long.MIN_VALUE;
    /** Tick the player's own chunk was last queued (it is refreshed on its own slow cadence). */
    private long lastPlayerOfferTick = Long.MIN_VALUE;
    /** Fast trains seen at the last poll; decides whether the frame budget can be spent fully. */
    private int fastTrains;
    /** Eye block position at the last poll; the idle gate needs it, nothing else does. */
    private int lastEyeX = Integer.MIN_VALUE;
    private int lastEyeY = Integer.MIN_VALUE;
    private int lastEyeZ = Integer.MIN_VALUE;

    /** Diagnostic counters, printed once per five seconds by {@link RailDiag#railq}. */
    private long queued;
    private long skipped;
    private long rebuilt;
    /** Offers/requests refused by the distance/view filter, the cooldown or the quota. */
    private long deferred;
    /** Client ticks spent in the load grace. */
    private long pausedTicks;

    private RailRelightScheduler() {
        for (int i = 0; i < PRIO_COUNT; i++) {
            queues[i] = new ArrayDeque<>();
        }
    }

    /** The installed instance, so config changes can drop every cached "already baked" signature. */
    private static volatile RailRelightScheduler installed;

    /**
     * Forgets every cached light signature, so the next poll rebuilds each chunk once.
     *
     * <p>Needed when a config value changes something that ends up in the vertices (currently
     * {@code railLightShaderBoost}): the geometry on screen still matches the old signature, so the
     * dirty check would otherwise skip the rebuild and the change would not show up until the pile
     * is unloaded.
     */
    public static void invalidate() {
        final RailRelightScheduler current = installed;
        if (current != null) {
            current.reset();
        }
    }

    /**
     * Installs the scheduler when a host mod is present. A no-op without ANTE/NTE, which is the
     * normal case for the plain (host-less) install.
     */
    public static void install() {
        if (!RailRelightDispatcher.available()) {
            MtrHeadlightClient.LOGGER.warn("[lte] rail relight: no ANTE/NTE dispatcher found, skipping");
            return;
        }
        MtrHeadlightClient.LOGGER.info("[lte] rail relight scheduler installed");
        final RailRelightScheduler scheduler = new RailRelightScheduler();
        installed = scheduler;
        RailRelightHooks.install(scheduler);
    }

    @Override
    public void tick(final HeadlightConfig cfg, final long tick) {
        now = tick;
        nowMs = System.currentTimeMillis();
        RailDiag.maybeLog(tick);

        // railLightAnySiding(): the global switch may be off while a siding overrides it on, in
        // which case the scheduler still has work to do for those trains only.
        if (!cfg.enabled || !cfg.dynamicLight || !cfg.railLightAnySiding()) {
            clearQueue();
            tracked.clear();
            return;
        }

        // One line per pack-state change, so a pack that renders the baked light too dark is at
        // least identifiable from the log.
        ShaderPackProbe.announce(cfg);
        final boolean shadersNow = ShaderPackProbe.active();
        if (shadersNow != lastShaderActive) {
            // Switching a pack makes the host clear and re-create its rail chunks on a different
            // render path. Our cached signatures would still claim "already baked with this light"
            // and skip the rebuild, so the dynamic light would never come back.
            lastShaderActive = shadersNow;
            RailRelightDispatcher.clearAll();
            reset();
        }
        if (!cfg.railLightUnderShaders && shadersNow) {
            clearQueue();
            return;
        }

        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return;
        }
        if (world != lastWorld) {
            lastWorld = world;
            reset();
            // A new world object is a join, a respawn or a dimension change - the only reliable
            // signal a client mod has for "ANTE is now streaming a region it has never built".
            // Start the load grace here rather than in reset(), which the shader-pack switch also
            // calls: turning a pack on must not cost another eight seconds of dark rails.
            loadGraceUntilMs = nowMs
                    + Math.round(Math.max(0.0D, Math.min(60.0D, cfg.railRelightLoadGraceSec)) * 1000.0D);
        }
        captureCamera(client);

        // Load grace: no host call, no sampling, no queue. ANTE's queue belongs entirely to the
        // chunks it is building around the player. Polling (and therefore publishing the beam
        // state) continues, because those very chunk builds bake our light through the mixin -
        // so the rails that stream in during the grace come out lit and need no rebuild later.
        final boolean paused = loadGraceUntilMs != 0L && nowMs < loadGraceUntilMs;
        suppressOffers = paused;
        if (paused) {
            pausedTicks++;
            // A request queued at second 0 would be a whole region stale by second 8.
            clearQueue();
        }

        // Always make progress on the queue, even between polls; drain self-throttles.
        if (!paused) {
            drain(cfg, tick);
        }

        final Vec3d eye = client.player.getPos();
        // Idle means: nothing queued, no train tracked anywhere near us, and the player has not
        // left the block they were in. The poll then backs off from 5 Hz to 1 Hz. The per-train
        // scan still runs (that is the only way an approaching train gets noticed without any
        // remembered state) and the player's own chunk is still examined - that is the held-torch
        // / LambDynamicLights path - but a still scene produces one 8x8 light sample per second
        // and, because the signature is unchanged, not a single host call.
        final boolean idle = pendingCount == 0 && tracked.isEmpty() && !eyeMoved(eye);
        final int pollInterval = idle ? IDLE_POLL_TICKS : Math.max(1, cfg.railRelightTicks);
        if (lastPollTick != Long.MIN_VALUE && tick - lastPollTick < pollInterval) {
            maybeLogQueue(tick, cfg);
            return;
        }

        lastPollTick = tick;
        poll(cfg, client, eye);
        maybeLogQueue(tick, cfg);
    }

    private void poll(final HeadlightConfig cfg, final MinecraftClient client, final Vec3d eye) {
        final boolean eyeMoved = eyeMoved(eye);
        lastEyeX = block(eye.x);
        lastEyeY = block(eye.y);
        lastEyeZ = block(eye.z);

        // A request covers a whole 32x32 rail chunk, which is exactly what the host rebuilds, so
        // there is no radius any more: asking for one section already marks its whole chunk.
        // (railRelightRadius stays in the config file so old JSON keeps loading.)

        // Everything around the player: covers a held torch / any LambDynamicLights source, which
        // the host mod cannot observe by itself. This does not need to run at the train poll rate -
        // when the player stands still nothing in the rails changes, so it is queued on its own
        // slow cadence (and immediately whenever the player actually moves).
        if (eyeMoved || lastPlayerOfferTick == Long.MIN_VALUE
                || now - lastPlayerOfferTick >= cfg.railRelightIdleInterval) {
            // No view test for the player's own chunk: at a chunk corner its centre can be behind
            // the camera, yet the rails a held torch lights are literally under the player.
            offerArea(eye.x, eye.y - 1.0D, eye.z, PRIO_PLAYER, cfg.effectiveFor(0L), false);
            lastPlayerOfferTick = now;
        }

        final int maxSegments = Math.max(1, cfg.railRelightMaxTrains) * 2;
        final double[] beams = new double[maxSegments * BEAM_STRIDE];
        int segments = 0;
        int trains = 0;
        int fast = 0;

        for (final TrainClient train : snapshotTrains()) {
            if (trains >= cfg.railRelightMaxTrains) {
                break;
            }
            if (train == null) {
                continue;
            }
            if (train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
                // Deleted or momentarily empty: hand its last chunk back so the beam does not stay
                // baked into a chunk the train will never visit again.
                release(cfg, train.id);
                continue;
            }
            if (cfg.onlyTrains && train.transportMode != TransportMode.TRAIN) {
                continue;
            }
            // Per-siding: the siding's own master switch and its rail-light switch both gate the
            // bake, so a siding with railLight off is never queued even when the global is on.
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
            if (!eff.enabled || !eff.dynamicLight || !eff.railLight) {
                release(cfg, train.id);
                continue;
            }

            // Same per-car pose the dynamic light and the beam sheet use: travel direction (with
            // swapEnds folded in) picks which car is the nose, and each car carries its own tangent.
            final TrainPose pose = TrainPose.ofShared(train,
                    DepartureState.reversed(train, train.isReversed()) ^ cfg.swapEnds);
            final Vec3d front = pose.front();
            final Vec3d rear = pose.rear();
            if (front == Vec3d.ZERO && rear == Vec3d.ZERO) {
                continue;
            }
            if (front.add(rear).multiply(0.5D).distanceTo(eye) > eff.maxDistance) {
                continue;
            }
            // Third consumer of the one switch: a train the auto gate turned off is not a light
            // source any more, so its rail chunks must stop being sampled/queued - and the chunk
            // it last lit is handed back (release) exactly like a despawned train, otherwise the
            // rails keep the brightness that was baked there on the last lit frame.
            if (!HeadlightSwitch.isLit(client.world, pose, cfg, train.id)) {
                release(cfg, train.id);
                continue;
            }
            if (segments + 1 > maxSegments) {
                break;
            }

            final double speed = speedOf(train);
            final boolean isFast = Math.abs(speed) >= FAST_SPEED;
            if (isFast) {
                fast++;
            }
            final Track entry = tracked.get(train.id);
            final int interval = relightInterval(cfg, speed);
            // The relight cadence is per train: it is derived from how far the beam travels in that
            // many ticks, so a slow train is refreshed rarely and a fast one on every poll.
            final boolean due = entry == null || now - entry.lastQueuedTick >= interval;

            final int offset = segments * BEAM_STRIDE;
            if (addBeam(beams, offset, pose, cfg, eff, true) == 1) {
                segments++;
                final double startX = beams[offset];
                final double startY = beams[offset + 1];
                final double startZ = beams[offset + 2];
                final double endX = beams[offset + 3];
                final double endZ = beams[offset + 5];

                if (due) {
                    // The chunk the light starts in - this one is refreshed on the train's own
                    // cadence, because it is what the player actually sees.
                    offerArea(startX, startY, startZ, isFast ? PRIO_HEAD : PRIO_SLOW, eff, true);

                    // The chunk the beam will reach. Queued **once per chunk**, not once per poll:
                    // re-queuing it every poll only re-bakes the handful of sample cells at its
                    // edge (the beam tip), and measured against a simulated 2x25 blocks/s scene
                    // that ate 10 of the 13 host calls per second while the head chunk - the one
                    // that matters visually - starved. One early bake per chunk is all the prefetch
                    // is for: it closes the dark gap at the border.
                    final double lead = leadBlocks(speed);
                    final double dx = endX - startX;
                    final double dz = endZ - startZ;
                    final double length = Math.sqrt(dx * dx + dz * dz);
                    final double reachX = length > 1.0E-4D ? endX + dx / length * lead : endX;
                    final double reachZ = length > 1.0E-4D ? endZ + dz / length * lead : endZ;
                    final int aheadCx = Math.floorDiv(block(reachX), CHUNK_BLOCKS);
                    final int aheadCz = Math.floorDiv(block(reachZ), CHUNK_BLOCKS);
                    final Track current = tracked.get(train.id);
                    if (current == null || current.aheadCx != aheadCx || current.aheadCz != aheadCz) {
                        offerArea(reachX, startY, reachZ, isFast ? PRIO_AHEAD : PRIO_SLOW, eff, true);
                    }

                    track(train.id, eff, startX, startY, startZ, aheadCx, aheadCz);
                } else if (entry != null) {
                    // Still there, just not due: keep it from being pruned as "vanished".
                    entry.seenTick = now;
                }
            }
            if (segments + 1 <= maxSegments) {
                segments += addBeam(beams, segments * BEAM_STRIDE, pose, cfg, eff, false);
            }
            trains++;
        }

        fastTrains = fast;
        prune(cfg);
        RailLightState.publish(trim(beams, segments * BEAM_STRIDE));
    }

    /**
     * How far past the end of the beam to keep the rails ready, in blocks.
     *
     * <p>{@code Train#getSpeed()} is blocks per tick (MTR advances {@code railProgress} by
     * {@code speed * delta} each tick), so eight ticks of travel is one poll plus one drain
     * opportunity. That is exactly the distance the train can cover before the request we are
     * queueing now can reach the host, which makes the prefetch distance track the speed without
     * remembering any previous position.
     */
    private static double leadBlocks(final double blocksPerTick) {
        return Math.min(24.0D, Math.max(4.0D, Math.abs(blocksPerTick) * 8.0D));
    }

    /** {@code Train#getSpeed()} in blocks per tick, or 0 when the host cannot answer. */
    private static double speedOf(final TrainClient train) {
        try {
            return train.getSpeed();
        } catch (Throwable t) {
            // A host that cannot answer costs us the constant lead / the slowest cadence only.
            return 0.0D;
        }
    }

    /**
     * How often one train's chunks are re-queued, in ticks.
     *
     * <p>Derived from the distance the beam covers, not from a fixed rate:
     * {@code ceil(railRelightMaxMove / speed)} keeps the worst-case position error at
     * {@code railRelightMaxMove} blocks (2 by default) at <em>every</em> speed. That is why a slow
     * train can be refreshed every 8-10 ticks without looking laggy - it has only moved 2 blocks -
     * while a 25 blocks/s train needs every 2 ticks. Below 1 block/s nothing moves at all, so the
     * interval falls back to {@code railRelightIdleInterval} (1.2 s), which still catches a
     * held/placed light source changing.
     */
    private static int relightInterval(final HeadlightConfig cfg, final double blocksPerTick) {
        final double speed = Math.abs(blocksPerTick);
        if (speed < SLOW_SPEED) {
            return Math.max(2, cfg.railRelightIdleInterval);
        }
        final int byMove = (int) Math.ceil(cfg.railRelightMaxMove / speed);
        final int max = Math.max(2, cfg.railRelightMaxInterval);
        return Math.max(2, Math.min(max, byMove));
    }

    /**
     * Captures the camera position and horizontal look direction once per tick. Both filters read
     * these fields, so a poll decides "where are we looking" exactly once instead of per request.
     */
    private void captureCamera(final MinecraftClient client) {
        final Vec3d eyePos = client.player.getEyePos();
        camX = eyePos.x;
        camY = eyePos.y;
        camZ = eyePos.z;
        // Yarn yaw: 0 looks towards +Z (south), +90 towards -X (west) - the same convention as
        // Vec3d.fromPolar, which is what Entity#getRotationVec uses.
        final double yaw = Math.toRadians(client.player.getYaw());
        lookX = -Math.sin(yaw);
        lookZ = Math.cos(yaw);
        camValid = true;
    }

    /**
     * Distance + horizontal view test for one rail chunk.
     *
     * <p>Two independent gates, both cheap enough to run per offer (a few multiplications):
     * <ol>
     *   <li><b>distance</b>: {@code |chunkCentre - camera| <= maxDist} (squared compare, no sqrt
     *       unless it passes). A chunk 32 blocks wide is judged by its centre, so the effective
     *       cut-off wobbles by at most ~23 blocks at the corner - irrelevant for a 64-block cap;</li>
     *   <li><b>view</b>: {@code dot(normalize(dx, dz), look) >= cosMin}, i.e. the chunk must be
     *       within {@code acos(cosMin)} of the yaw direction. The horizontal projection is on
     *       purpose: a 3D cone would drop the rails in front of a player who is looking down at
     *       them. Inside {@link #NEAR_BLOCKS} the test is skipped entirely.</li>
     * </ol>
     */
    private boolean inView(final double x, final double y, final double z,
                           final double maxDist, final double cosMin, final boolean frustum) {
        if (!camValid) {
            return true;
        }
        final double dx = x - camX;
        final double dy = y - camY;
        final double dz = z - camZ;
        final double flatSq = dx * dx + dz * dz;
        final double limit = Math.max(1.0D, maxDist);
        if (flatSq + dy * dy > limit * limit) {
            return false;
        }
        if (!frustum || flatSq <= NEAR_BLOCKS * NEAR_BLOCKS) {
            return true;
        }
        final double flat = Math.sqrt(flatSq);
        return (dx * lookX + dz * lookZ) / flat >= cosMin;
    }

    /**
     * Queues the rail chunk covering a position. Queueing is free; whether the chunk is actually
     * rebuilt is decided in {@link #drain}.
     *
     * @param frustum false for the player's own chunk and for trail clears. Both are correctness
     *                work rather than "catch the moving light": the rails under a held torch cannot
     *                be off-screen, and a beam left baked into a chunk behind the camera never goes
     *                away. Only the distance cap applies to them.
     */
    private void offerArea(final double x, final double y, final double z,
                           final int priority, final HeadlightConfig.Effective eff,
                           final boolean frustum) {
        final int cx = Math.floorDiv(block(x), CHUNK_BLOCKS);
        final int cz = Math.floorDiv(block(z), CHUNK_BLOCKS);
        offerChunk(cx, cz, block(y), priority, eff, frustum);
    }

    /**
     * Queues one chunk. A chunk that is already queued is moved up when the new request is more
     * urgent (the same chunk can be both "ahead of a fast train" and "the chunk a beam left").
     *
     * <p>This is where the distance/view filter runs: refusing here costs nothing (no sample, no
     * host call, no queue slot) and the request is simply re-offered on the next poll of that
     * train or of the player.
     */
    private void offerChunk(final int cx, final int cz, final int y,
                            final int priority, final HeadlightConfig.Effective eff,
                            final boolean frustum) {
        if (suppressOffers) {
            return;
        }
        final double centreX = (cx << 5) + CHUNK_BLOCKS * 0.5D;
        final double centreZ = (cz << 5) + CHUNK_BLOCKS * 0.5D;
        if (!inView(centreX, y, centreZ, eff.railRelightMaxBlockDist, eff.railRelightFrustumCos,
                frustum)) {
            deferred++;
            return;
        }

        final long key = chunkKey(cx, cz);
        final Request existing = pendingByChunk.get(key);
        if (existing != null) {
            if (existing.priority <= priority) {
                return;
            }
            removePending(existing);
        }
        while (pendingCount >= MAX_QUEUE) {
            // Drop the least urgent / oldest entry: a stale trail clear must never push out the
            // chunk that is about to be driven into.
            if (!dropLowest()) {
                break;
            }
        }
        final Request request = new Request(cx, cz, y, key, now, priority, eff.sidingId,
                Math.max(0, eff.railRelightCooldownMs), Math.max(1, eff.railRelightMaxPerSec));
        queues[priority].addLast(request);
        pendingByChunk.put(key, request);
        pendingCount++;
        queued++;
    }

    /** Puts a request back where the drain found it (per-chunk cooldown / quota blocked). */
    private void requeue(final Request request) {
        queues[request.priority].addLast(request);
        pendingByChunk.put(request.chunkKey, request);
        pendingCount++;
    }

    /** Removes the least urgent queued chunk (oldest of the lowest-priority non-empty queue). */
    private boolean dropLowest() {
        for (int p = PRIO_COUNT - 1; p >= 0; p--) {
            final Request dropped = queues[p].pollLast();
            if (dropped != null) {
                pendingByChunk.remove(dropped.chunkKey);
                pendingCount--;
                return true;
            }
        }
        return false;
    }

    private void removePending(final Request request) {
        queues[request.priority].remove(request);
        pendingByChunk.remove(request.chunkKey);
        pendingCount--;
    }

    /** Takes the most urgent request, oldest first within one priority. */
    private Request pollNext() {
        for (int p = 0; p < PRIO_COUNT; p++) {
            final Request request = queues[p].pollFirst();
            if (request != null) {
                pendingByChunk.remove(request.chunkKey);
                pendingCount--;
                return request;
            }
        }
        return null;
    }

    /**
     * Drains the queue at most once per {@code railRelightEveryTicks}, within one opportunity makes
     * at most {@code railRelightBudget} host calls, and never more than
     * {@code railRelightMaxPerSec} in any rolling second.
     *
     * <p>Three gates sit in front of a host call:
     * <ol>
     *   <li><b>per-chunk cooldown</b> - a chunk that was rebuilt less than
     *       {@code railRelightCooldownMs} ago goes back to the tail of its own bucket and another
     *       chunk gets the slot instead; if nothing else is ready the drain simply ends. This is
     *       what turns "10 requests/s from one fast train" into 2;</li>
     *   <li><b>per-second quota</b> - the whole scheduler (every train plus the player chunk) may
     *       make at most {@code railRelightMaxPerSec} host calls in a one-second window, so raising
     *       the opportunity budget cannot eat ANTE's build slot;</li>
     *   <li><b>dirty check</b> - a chunk whose sampled 8x8 light equals what is already baked costs
     *       nothing and is dropped for free.</li>
     * </ol>
     */
    private void drain(final HeadlightConfig cfg, final long tick) {
        if (pendingCount == 0) {
            return;
        }
        final long every = Math.max(1L, Math.min(20L, cfg.railRelightEveryTicks));
        if (lastDrainTick != Long.MIN_VALUE && tick - lastDrainTick < every) {
            return;
        }

        final int cap = Math.max(1, Math.min(40, cfg.railRelightMaxPerSec));
        rollQuotaWindow();
        if (quotaUsed >= cap) {
            // Spent: the rest of the queue waits for the next one-second window. Not counted as
            // deferred here - this branch is re-entered every drain until the window rolls, and
            // counting would turn one stuck queue into hundreds of phantom deferrals.
            return;
        }

        int budget = budget(cfg);
        int attempts = pendingCount;
        lastDrainTick = tick;

        while (budget > 0 && pendingCount > 0 && attempts-- > 0) {
            final Request request = pollNext();
            if (request == null) {
                break;
            }

            final Long built = lastBuildMs.get(Long.valueOf(request.chunkKey));
            if (built != null && nowMs - built.longValue() < request.cooldownMs) {
                requeue(request);
                deferred++;
                continue;
            }
            // A siding's own cap can only lower its share, never exceed the global one.
            final int sidingCap = Math.min(cap, request.perSecCap);
            if (sidingUsed(request.sidingId) >= sidingCap) {
                requeue(request);
                deferred++;
                continue;
            }
            if (quotaUsed >= cap) {
                requeue(request);
                deferred += pendingCount;
                break;
            }

            final byte[] signature = sample(request.cx, request.y, request.cz);
            final byte[] previous = baked.get(Long.valueOf(request.chunkKey));
            if (previous != null && !changed(previous, signature, cfg.railLightDelta)) {
                skipped++;
                RailDiag.skip();
                continue;
            }

            if (RailRelightDispatcher.register(request.cx << 1, section(request.y) - 1,
                    section(request.y) + 1, request.cz << 1)) {
                rebuilt++;
                quotaUsed++;
                noteSidingCall(request.sidingId);
                noteBuilt(request.chunkKey);
                RailDiag.rebuild();
                // Remember the signature we just handed to the host, not the one we may compute
                // next tick - that is what the geometry on screen now represents.
                remember(request.chunkKey, signature);
            }
            budget--;
        }
    }

    /** Starts a new one-second quota window when the current one has expired. */
    private void rollQuotaWindow() {
        if (quotaWindowStartMs != 0L && nowMs - quotaWindowStartMs < 1000L) {
            return;
        }
        quotaWindowStartMs = nowMs == 0L ? 1L : nowMs;
        quotaUsed = 0;
        sidingQuota.clear();
    }

    private int sidingUsed(final long sidingId) {
        final Integer used = sidingQuota.get(Long.valueOf(sidingId));
        return used == null ? 0 : used.intValue();
    }

    private void noteSidingCall(final long sidingId) {
        sidingQuota.put(Long.valueOf(sidingId), Integer.valueOf(sidingUsed(sidingId) + 1));
    }

    /** Records the wall-clock time of a host call for one chunk (per-chunk cooldown). */
    private void noteBuilt(final long key) {
        if (lastBuildMs.size() >= MAX_CACHE && !lastBuildMs.containsKey(Long.valueOf(key))) {
            lastBuildMs.clear();
        }
        lastBuildMs.put(Long.valueOf(key), Long.valueOf(nowMs));
    }

    /**
     * Host calls allowed in one opportunity.
     *
     * <p>Two flat-out trains need roughly 8-9 host calls per second together (each head chunk and
     * its prefetch chunk every 2 ticks, minus the polls where the signature did not move). Two per
     * opportunity at every 3 ticks (13.3/s) covers that and still leaves a third of ANTE's 20 fps
     * build slot for its own chunk rebuilds. With more than two fast trains the full budget is
     * still not enough - no queue discipline creates 20 fps out of 20 fps - so it is dropped to one
     * per opportunity instead of taking the whole slot and stalling the terrain.
     */
    private int budget(final HeadlightConfig cfg) {
        int budget = Math.max(1, Math.min(4, cfg.railRelightBudget));
        if (fastTrains > 2) {
            budget = Math.max(1, budget - 1);
        }
        return budget;
    }

    /** Samples the dynamic light on a coarse grid over one rail chunk. */
    private byte[] sample(final int chunkX, final int y, final int chunkZ) {
        final byte[] levels = new byte[SAMPLE_GRID * SAMPLE_GRID];
        final int baseX = chunkX << 5;
        final int baseZ = chunkZ << 5;
        int index = 0;
        for (int dz = 0; dz < CHUNK_BLOCKS; dz += SAMPLE_STEP) {
            for (int dx = 0; dx < CHUNK_BLOCKS; dx += SAMPLE_STEP) {
                samplePos.set(baseX + dx, y, baseZ + dz);
                levels[index++] = (byte) RailDynamicLight.level(samplePos);
            }
        }
        return levels;
    }

    private static boolean changed(final byte[] before, final byte[] after, final int delta) {
        final int threshold = Math.max(1, delta);
        for (int i = 0; i < before.length; i++) {
            if (Math.abs(before[i] - after[i]) >= threshold) {
                return true;
            }
        }
        return false;
    }

    private void remember(final long key, final byte[] signature) {
        if (baked.size() >= MAX_CACHE && !baked.containsKey(key)) {
            baked.clear();
        }
        baked.put(key, signature);
    }

    /** Remembers the chunk a train's beam started in and queues the one it just left. */
    private void track(final long trainId, final HeadlightConfig.Effective eff,
                       final double x, final double y, final double z,
                       final int aheadCx, final int aheadCz) {
        final int cx = Math.floorDiv(block(x), CHUNK_BLOCKS);
        final int cz = Math.floorDiv(block(z), CHUNK_BLOCKS);
        final int blockY = block(y);
        final Track entry = tracked.get(Long.valueOf(trainId));
        if (entry == null) {
            tracked.put(Long.valueOf(trainId),
                    new Track(cx, cz, blockY, now, now, aheadCx, aheadCz, eff.sidingId));
            return;
        }
        if (entry.cx != cx || entry.cz != cz) {
            // The beam start left this chunk: rebuild it without the beam so the trail goes dark.
            // Only the distance cap applies - see the `frustum` flag on offerChunk.
            offerChunk(entry.cx, entry.cz, entry.blockY, PRIO_TRAIL, eff, false);
            entry.cx = cx;
            entry.cz = cz;
            entry.blockY = blockY;
        }
        entry.aheadCx = aheadCx;
        entry.aheadCz = aheadCz;
        entry.seenTick = now;
        entry.lastQueuedTick = now;
    }

    /** Forgets one train and clears the chunk it was last seen in. */
    private void release(final HeadlightConfig cfg, final long trainId) {
        final Track entry = tracked.remove(Long.valueOf(trainId));
        if (entry != null) {
            offerChunk(entry.cx, entry.cz, entry.blockY, PRIO_TRAIL,
                    cfg.effectiveFor(entry.sidingId), false);
        }
    }

    /** Forgets every train that has not been seen for {@link #PRUNE_TICKS}, clearing its chunk. */
    private void prune(final HeadlightConfig cfg) {
        final long oldest = now - PRUNE_TICKS;
        for (final Iterator<Map.Entry<Long, Track>> it = tracked.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Long, Track> entry = it.next();
            if (entry.getValue().seenTick > oldest) {
                continue;
            }
            final Track track = entry.getValue();
            offerChunk(track.cx, track.cz, track.blockY, PRIO_TRAIL,
                    cfg.effectiveFor(track.sidingId), false);
            it.remove();
        }
    }

    private boolean eyeMoved(final Vec3d eye) {
        return block(eye.x) != lastEyeX || block(eye.y) != lastEyeY || block(eye.z) != lastEyeZ;
    }

    private void maybeLogQueue(final long tick, final HeadlightConfig cfg) {
        if (tick % 100L != 0L) {
            return;
        }
        RailDiag.railq(cfg.railRelightDiagnostics, queued, rebuilt, skipped, deferred, pausedTicks,
                quotaUsed, Math.max(1, Math.min(40, cfg.railRelightMaxPerSec)));
    }

    private void clearQueue() {
        for (final ArrayDeque<Request> queue : queues) {
            queue.clear();
        }
        pendingByChunk.clear();
        pendingCount = 0;
    }

    private void reset() {
        clearQueue();
        baked.clear();
        // The cooldown is keyed by chunk and the host forgot those chunks too: a stale timestamp
        // would only delay the first rebuild of a chunk we are about to ask for again.
        lastBuildMs.clear();
        tracked.clear();
        fastTrains = 0;
        lastPollTick = Long.MIN_VALUE;
        lastDrainTick = Long.MIN_VALUE;
        lastPlayerOfferTick = Long.MIN_VALUE;
        lastEyeX = Integer.MIN_VALUE;
        lastEyeY = Integer.MIN_VALUE;
        lastEyeZ = Integer.MIN_VALUE;
        quotaUsed = 0;
        quotaWindowStartMs = 0L;
        sidingQuota.clear();
        RailLightState.clear();
    }

    /** Rail chunk key: the 32-block chunk the host actually rebuilds. */
    private static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int section(final int blockY) {
        return Math.floorDiv(blockY, 16);
    }

    private static int block(final double coord) {
        return (int) Math.floor(coord);
    }

    /** Writes one beam segment into {@code out}; returns the number of segments written (0 or 1). */
    private static int addBeam(final double[] out, final int offset, final TrainPose pose,
                               final HeadlightConfig cfg, final HeadlightConfig.Effective eff,
                               final boolean head) {
        // Per-car geometry, and the very same EndLight the LambDynamicLights line uses: lamp on the
        // car face at that end, beam along THAT car's tangent (the tail's is negated by
        // TrainPose#backward).
        final Vec3d base = head ? pose.front() : pose.rear();
        final Vec3d forward = head ? pose.forward() : pose.backward();
        if (forward == Vec3d.ZERO) {
            return 0;
        }
        final EndLight light = EndLight.of(base, forward,
                head ? cfg.headOffsetClamped() : cfg.tailOffsetClamped(),
                head ? eff.headLength : eff.tailLength,
                head ? HeadlightConfig.HEAD_TIP_DROP : HeadlightConfig.TAIL_TIP_DROP,
                cfg.heightOffset);
        out[offset] = light.start.x;
        out[offset + 1] = light.start.y;
        out[offset + 2] = light.start.z;
        out[offset + 3] = light.end.x;
        out[offset + 4] = light.end.y;
        out[offset + 5] = light.end.z;
        out[offset + 6] = head ? eff.headLuminance : eff.tailLuminance;
        return 1;
    }

    private static double[] trim(final double[] values, final int length) {
        if (length == values.length) {
            return values;
        }
        final double[] out = new double[length];
        System.arraycopy(values, 0, out, 0, length);
        return out;
    }

    private static List<TrainClient> snapshotTrains() {
        try {
            return new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** One "re-examine this chunk" request. Allocation is bounded by {@link #MAX_QUEUE}. */
    private static final class Request {
        final int cx;
        final int cz;
        final int y;
        final long chunkKey;
        final long enqueuedTick;
        final int priority;
        /** Siding the request was made for ({@code 0} = the player / globals). */
        final long sidingId;
        /** Minimum ms between two host calls for this chunk, resolved from the siding. */
        final int cooldownMs;
        /** This siding's share of the per-second quota, resolved from the siding. */
        final int perSecCap;

        Request(final int cx, final int cz, final int y, final long chunkKey, final long enqueuedTick,
                final int priority, final long sidingId, final int cooldownMs, final int perSecCap) {
            this.cx = cx;
            this.cz = cz;
            this.y = y;
            this.chunkKey = chunkKey;
            this.enqueuedTick = enqueuedTick;
            this.priority = priority;
            this.sidingId = sidingId;
            this.cooldownMs = cooldownMs;
            this.perSecCap = perSecCap;
        }
    }

    /** The only per-train state: the chunk its beam started in, and when it was last seen/queued. */
    private static final class Track {
        int cx;
        int cz;
        int blockY;
        /** The chunk the beam's reach (end + lead) was in when it was last prefetched. */
        int aheadCx;
        int aheadCz;
        long seenTick;
        /** Last tick this train's chunks were queued; drives the per-train relight interval. */
        long lastQueuedTick;
        /** The siding the train was dispatched from, so a trail clear resolves its own overrides. */
        final long sidingId;

        Track(final int cx, final int cz, final int blockY, final long seenTick,
              final long lastQueuedTick, final int aheadCx, final int aheadCz, final long sidingId) {
            this.cx = cx;
            this.cz = cz;
            this.blockY = blockY;
            this.seenTick = seenTick;
            this.lastQueuedTick = lastQueuedTick;
            this.aheadCx = aheadCx;
            this.aheadCz = aheadCz;
            this.sidingId = sidingId;
        }
    }
}
