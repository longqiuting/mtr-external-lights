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
 * distance to the camera, so a chunk next to the player always wins over a chunk that is being
 * newly loaded. The queue is drained once every {@code railRelightEveryTicks} (default 3) client
 * ticks, at most {@code railRelightBudget} (default 2) host calls per drain, so the ceiling is
 * {@code 20 / 3 * 2 = 13.3} rebuilds per second regardless of how many trains are in view.
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
    /** An entry that has waited this long is reported as starved (2 s). */
    private static final long STARVED_TICKS = 40L;

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
    /** Train id -> the chunk its beam started in last time we looked. */
    private final Map<Long, Track> tracked = new HashMap<>();
    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

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
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] rail relight: no ANTE/NTE dispatcher found, skipping");
            return;
        }
        MtrHeadlightClient.LOGGER.info("[mtr-headlight] rail relight scheduler installed");
        final RailRelightScheduler scheduler = new RailRelightScheduler();
        installed = scheduler;
        RailRelightHooks.install(scheduler);
    }

    @Override
    public void tick(final HeadlightConfig cfg, final long tick) {
        now = tick;
        RailDiag.maybeLog(tick);

        // railLightAnySiding(): the global switch may be off while a siding overrides it on, in
        // which case the scheduler still has work to do for those trains only.
        if (!cfg.enabled || !cfg.railLightAnySiding()) {
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
        }

        // Always make progress on the queue, even between polls; drain self-throttles.
        drain(cfg, tick);

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
            maybeLogQueue(tick);
            return;
        }

        lastPollTick = tick;
        poll(cfg, client, eye);
        maybeLogQueue(tick);
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
            offerArea(eye.x, eye.y - 1.0D, eye.z, PRIO_PLAYER);
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
                release(train.id);
                continue;
            }
            if (cfg.onlyTrains && train.transportMode != TransportMode.TRAIN) {
                continue;
            }
            // Per-siding: the siding's own master switch and its rail-light switch both gate the
            // bake, so a siding with railLight off is never queued even when the global is on.
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
            if (!eff.enabled || !eff.railLight) {
                release(train.id);
                continue;
            }

            final TrainPose pose = TrainPose.of(train);
            final Vec3d front = pose.front();
            final Vec3d rear = pose.rear();
            if (front == Vec3d.ZERO && rear == Vec3d.ZERO) {
                continue;
            }
            if (front.add(rear).multiply(0.5D).distanceTo(eye) > eff.maxDistance) {
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
                    offerArea(startX, startY, startZ, isFast ? PRIO_HEAD : PRIO_SLOW);

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
                        offerArea(reachX, startY, reachZ, isFast ? PRIO_AHEAD : PRIO_SLOW);
                    }

                    track(train.id, startX, startY, startZ, aheadCx, aheadCz);
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
        prune();
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
     * Queues the rail chunk covering a position. Queueing is free; whether the chunk is actually
     * rebuilt is decided in {@link #drain}.
     */
    private void offerArea(final double x, final double y, final double z, final int priority) {
        final int cx = Math.floorDiv(block(x), CHUNK_BLOCKS);
        final int cz = Math.floorDiv(block(z), CHUNK_BLOCKS);
        offerChunk(cx, cz, block(y), priority);
    }

    /**
     * Queues one chunk. A chunk that is already queued is moved up when the new request is more
     * urgent (the same chunk can be both "ahead of a fast train" and "the chunk a beam left").
     */
    private void offerChunk(final int cx, final int cz, final int y, final int priority) {
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
        final Request request = new Request(cx, cz, y, key, now, priority);
        queues[priority].addLast(request);
        pendingByChunk.put(key, request);
        pendingCount++;
        queued++;
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
     * Drains the queue at most once per {@code railRelightEveryTicks}, and within one opportunity
     * makes at most {@code railRelightBudget} host calls. Entries whose light did not move cost
     * nothing, so they are dropped for free and the loop continues to the next one.
     */
    private void drain(final HeadlightConfig cfg, final long tick) {
        if (pendingCount == 0) {
            return;
        }
        final long every = Math.max(1L, Math.min(20L, cfg.railRelightEveryTicks));
        if (lastDrainTick != Long.MIN_VALUE && tick - lastDrainTick < every) {
            return;
        }

        int budget = budget(cfg);

        while (budget > 0 && pendingCount > 0) {
            final Request request = pollNext();
            if (request == null) {
                break;
            }

            final byte[] signature = sample(request.cx, request.y, request.cz);
            final byte[] previous = baked.get(request.chunkKey);
            if (previous != null && !changed(previous, signature, cfg.railLightDelta)) {
                skipped++;
                RailDiag.skip();
                continue;
            }

            // One host call per budget unit; the rest of the queue waits for the next opportunity.
            lastDrainTick = tick;
            if (RailRelightDispatcher.register(request.cx << 1, section(request.y) - 1,
                    section(request.y) + 1, request.cz << 1)) {
                rebuilt++;
                RailDiag.rebuild();
                // Remember the signature we just handed to the host, not the one we may compute
                // next tick - that is what the geometry on screen now represents.
                remember(request.chunkKey, signature);
            }
            budget--;
        }
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
    private void track(final long trainId, final double x, final double y, final double z,
                       final int aheadCx, final int aheadCz) {
        final int cx = Math.floorDiv(block(x), CHUNK_BLOCKS);
        final int cz = Math.floorDiv(block(z), CHUNK_BLOCKS);
        final int blockY = block(y);
        final Track entry = tracked.get(trainId);
        if (entry == null) {
            tracked.put(trainId, new Track(cx, cz, blockY, now, now, aheadCx, aheadCz));
            return;
        }
        if (entry.cx != cx || entry.cz != cz) {
            // The beam start left this chunk: rebuild it without the beam so the trail goes dark.
            offerChunk(entry.cx, entry.cz, entry.blockY, PRIO_TRAIL);
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
    private void release(final long trainId) {
        final Track entry = tracked.remove(trainId);
        if (entry != null) {
            offerChunk(entry.cx, entry.cz, entry.blockY, PRIO_TRAIL);
        }
    }

    /** Forgets every train that has not been seen for {@link #PRUNE_TICKS}, clearing its chunk. */
    private void prune() {
        final long oldest = now - PRUNE_TICKS;
        for (final Iterator<Map.Entry<Long, Track>> it = tracked.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Long, Track> entry = it.next();
            if (entry.getValue().seenTick > oldest) {
                continue;
            }
            offerChunk(entry.getValue().cx, entry.getValue().cz, entry.getValue().blockY, PRIO_TRAIL);
            it.remove();
        }
    }

    private boolean eyeMoved(final Vec3d eye) {
        return block(eye.x) != lastEyeX || block(eye.y) != lastEyeY || block(eye.z) != lastEyeZ;
    }

    private void maybeLogQueue(final long tick) {
        if (tick % 100L != 0L) {
            return;
        }
        int starved = 0;
        for (final ArrayDeque<Request> queue : queues) {
            for (final Request request : queue) {
                if (now - request.enqueuedTick > STARVED_TICKS) {
                    starved++;
                }
            }
        }
        RailDiag.railq(queued, skipped, rebuilt, starved, pendingCount, tracked.size());
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
        tracked.clear();
        fastTrains = 0;
        lastPollTick = Long.MIN_VALUE;
        lastDrainTick = Long.MIN_VALUE;
        lastPlayerOfferTick = Long.MIN_VALUE;
        lastEyeX = Integer.MIN_VALUE;
        lastEyeY = Integer.MIN_VALUE;
        lastEyeZ = Integer.MIN_VALUE;
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
        Vec3d forward = pose.forward();
        if (forward == Vec3d.ZERO) {
            return 0;
        }
        if (cfg.swapEnds) {
            forward = forward.multiply(-1.0D);
        }
        final Vec3d front = cfg.swapEnds ? pose.rear() : pose.front();
        final Vec3d rear = cfg.swapEnds ? pose.front() : pose.rear();
        final double y = Math.max(front.y, rear.y) + cfg.heightOffset;
        if (head) {
            final Vec3d start = new Vec3d(front.x + forward.x * cfg.headOffset, y,
                    front.z + forward.z * cfg.headOffset);
            final Vec3d end = new Vec3d(start.x + forward.x * eff.headLength, start.y - 0.60D,
                    start.z + forward.z * eff.headLength);
            out[offset] = start.x;
            out[offset + 1] = start.y;
            out[offset + 2] = start.z;
            out[offset + 3] = end.x;
            out[offset + 4] = end.y;
            out[offset + 5] = end.z;
            out[offset + 6] = eff.headLuminance;
        } else {
            final Vec3d back = forward.multiply(-1.0D);
            final Vec3d start = new Vec3d(rear.x + back.x * cfg.tailOffset, y,
                    rear.z + back.z * cfg.tailOffset);
            final Vec3d end = new Vec3d(start.x + back.x * eff.tailLength, start.y - 0.35D,
                    start.z + back.z * eff.tailLength);
            out[offset] = start.x;
            out[offset + 1] = start.y;
            out[offset + 2] = start.z;
            out[offset + 3] = end.x;
            out[offset + 4] = end.y;
            out[offset + 5] = end.z;
            out[offset + 6] = eff.tailLuminance;
        }
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

        Request(final int cx, final int cz, final int y, final long chunkKey, final long enqueuedTick,
                final int priority) {
            this.cx = cx;
            this.cz = cz;
            this.y = y;
            this.chunkKey = chunkKey;
            this.enqueuedTick = enqueuedTick;
            this.priority = priority;
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

        Track(final int cx, final int cz, final int blockY, final long seenTick,
              final long lastQueuedTick, final int aheadCx, final int aheadCz) {
            this.cx = cx;
            this.cz = cz;
            this.blockY = blockY;
            this.seenTick = seenTick;
            this.lastQueuedTick = lastQueuedTick;
            this.aheadCx = aheadCx;
            this.aheadCz = aheadCz;
        }
    }
}
