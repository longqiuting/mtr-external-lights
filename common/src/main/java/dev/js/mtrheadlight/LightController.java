package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-train light placement, shared by every supported Minecraft version.
 *
 * <p>Nothing here writes to the world, to blocks, or to MTR: MTR is only read
 * ({@code ClientData.TRAINS} plus the public pose accessors in {@link TrainPose}). The actual light
 * injection is delegated to {@link LightBackend}, which differs per LambDynamicLights generation.
 *
 * <p>Each end is judged on its own: the front beam follows the front car, the tail light the rear
 * car, and each is capped at the configured distance. A single train-level distance made the tail
 * light stay lit on an unrendered car once the front car was out of range.
 *
 * <p>Lifecycle: {@code ClientData.TRAINS} is replaced wholesale on every client data sync, so a
 * train can be missing for a while while it is really still there. Absence is handled in stages:
 * short gaps are ignored, longer ones dim the light, and only a real despawn detaches it.
 *
 * <p>That staging is for <em>jitter</em> - one train dropping out of a range-based sync. A
 * wholesale clear (MTR's "refresh routes" / "clear vehicles", or a re-sync after a dimension
 * change) is not jitter: every train leaves at once, and holding their lights for the full grace
 * leaves the whole fleet's beams hanging in the air. When a large share of what we track vanishes
 * in the same tick, the lights are detached immediately instead (see {@link #massClearRatio} and
 * the mass-clear block in {@link #tick()}).
 */
public final class LightController {
    /**
     * Lower bound of the mass-clear threshold, in trains. One train leaving the data set is an
     * ordinary despawn or a single sync gap and must keep going through the staged grace; only two
     * or more vanishing in the same tick is evidence of a batch.
     */
    private static final int MASS_CLEAR_MIN = 2;

    /**
     * Ticks a train may stay in {@code ClientData.TRAINS} without a resolvable car position before
     * its lights are put out (3 ticks, 150 ms). MTR keeps a train in the data set while its rail
     * data / chunks are not available, and the pose then comes back as zero: without this the light
     * simply stops being updated and stays lit where the train was last drawn. Long enough that a
     * one-tick hiccup cannot make the beam blink.
     */
    private static final int POSE_LOST_TICKS = 3;

    /**
     * Longest a light may stay fully lit while its train is <b>missing from the data set</b>, in
     * ticks (2 s), no matter how close its last known position is to the player.
     *
     * <p>MTR syncs trains by range, so a train that drove out of the sync radius is missing from
     * {@code ClientData.TRAINS} long before it is out of sight - the {@code near()} escape hatch then
     * keeps its light <em>fully lit at the position it was last seen at</em>, which is the reported
     * "the track stays lit after the train has left". A sync gap is one or two ticks; two seconds is
     * already 40x that, so a real gap still looks continuous while a despawn stops shining quickly.
     */
    private static final long MISSING_LIT_MAX_TICKS = 40L;

    private static final Map<String, Tracked> ACTIVE = new HashMap<>();
    private static ClientWorld lastWorld;
    /** Monotonic client tick counter, used only to age lights that went missing. */
    private static long tickCounter;

    private LightController() {
    }

    public static void clear() {
        LightBackends.backend().clearAll();
        ACTIVE.clear();
    }

    /** Rebuild every light from scratch (config screen button). */
    public static void reload() {
        clear();
    }

    public static void tick() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientWorld world = client.world;

        if (world != lastWorld) {
            // The light engine drops everything on world change, so drop our bookkeeping too.
            ACTIVE.clear();
            LightBackends.backend().clearAll();
            TrainPose.clearCache();
            lastWorld = world;
            tickCounter = 0L;
        }

        if (world == null || client.player == null) {
            return;
        }

        // Five-second railbuild line. Placed before the master-switch returns below (and here
        // rather than in the 1.20.1-only scheduler) so it reports on every build and so a mixin
        // that silently stopped matching shows up as calls=0 instead of as silence.
        RailChunkBuildBoost.tick(cfg);

        if (!cfg.enabled || !cfg.dynamicLight) {
            // Master switch (or the dynamic-light child) off: drop everything right away, or the
            // last light stays lit forever where the train was.
            clear();
            return;
        }

        tickCounter++;
        final LightBackend backend = LightBackends.backend();
        // Diagnostics only, on this thread: never from inside an engine callback.
        backend.tick();
        final Vec3d eye = client.player.getPos();
        final Set<String> seen = new HashSet<>();
        final List<TrainClient> trains = snapshotTrains();
        // Every train the client knows about this tick, whether or not it passes the gates below:
        // the mass-clear test asks "did it leave the data set", not "did it survive our filters".
        // Built with DepartureState's key format, which is documented to match ours, so the two
        // sets can never disagree about a train's identity.
        final Set<String> known = new HashSet<>(Math.max(16, trains.size() * 2));
        for (final TrainClient train : trains) {
            if (train != null) {
                known.add(DepartureState.keyOf(train));
            }
        }

        for (final TrainClient train : trains) {
            if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
                continue;
            }
            if (cfg.onlyTrains && train.transportMode != TransportMode.TRAIN) {
                continue;
            }

            final String key = DepartureState.keyOf(train);

            // Global values with this siding's overrides folded in (distance, luminance, the two
            // switches). A per-siding master switch off means "no light for these trains" and is
            // applied immediately - waiting for the missing-tick timeout would leave a lit train
            // sitting in the depot for ten seconds after the player switched its siding off.
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
            if (!eff.enabled || !eff.dynamicLight) {
                final Tracked stale = ACTIVE.remove(key);
                if (stale != null) {
                    stale.remove(backend, key);
                }
                continue;
            }
            // A train that has not left its depot (still in the vehicle shed) must not shine:
            // MTR's isOnRoute flag, plus a strictly movement-based latch for a stale flag - see
            // DepartureState.
            if (!DepartureState.departed(train)) {
                final Tracked inShed = ACTIVE.remove(key);
                if (inShed != null) {
                    inShed.remove(backend, key);
                }
                continue;
            }
            // An unrendered car must not leave a light behind; maxDistance defaults to 192 (12
            // chunks), which matches the usual client render distance. A siding may override it.
            final double viewLimit = eff.maxDistance;

            Tracked tracked = ACTIVE.get(key);
            if (tracked == null) {
                tracked = new Tracked();
                ACTIVE.put(key, tracked);
            }

            // 折返时 railProgress 会反向；用它判断哪一端是车头（MTR 的 isReversed() 在终点站不一定会翻转）。
            // swapEnds 等价于"整列车朝反方向跑"，所以两者折叠成一个布尔量交给 TrainPose，
            // 头/尾端由 pose 自己给出，几何里不再有第二处换端分支。
            final boolean reversed = DepartureState.reversed(train, train.isReversed()) ^ cfg.swapEnds;
            final TrainPose pose = TrainPose.ofShared(train, reversed);
            if (pose.front() == Vec3d.ZERO && pose.rear() == Vec3d.ZERO) {
                // MTR still lists the train but its cars cannot be placed this tick (rail data or
                // chunks not available - typically a train that drove out of the loaded area). The
                // light from the previous tick must not stay where the train used to be, so put it
                // out after a short grace. Deliberately NOT added to `seen`: the missing-train path
                // below is what finally detaches the sources.
                if (++tracked.unplacedTicks >= POSE_LOST_TICKS) {
                    tracked.hide(backend, key);
                }
                continue;
            }
            tracked.unplacedTicks = 0;

            // Per-end judgement: the beam belongs to the front car, the tail light to the rear car.
            // Both distances use the same boundary the light is built from.
            final double frontDistance = pose.front().distanceTo(eye);
            final double rearDistance = pose.rear().distanceTo(eye);

            if (frontDistance > viewLimit && rearDistance > viewLimit) {
                if (tracked != null) {
                    seen.add(key);
                    tracked.lastSeen = tickCounter;
                    if (frontDistance > viewLimit * 1.25D && rearDistance > viewLimit * 1.25D) {
                        tracked.remove(backend, key);
                        ACTIVE.remove(key);
                        seen.remove(key);
                    } else {
                        tracked.hide(backend, key);
                    }
                }
                continue;
            }

            final EndLight head = headSegment(pose, cfg, eff);
            final EndLight tail = tailSegment(pose, cfg, eff);

            tracked.update(backend, key, head, tail,
                    frontDistance <= viewLimit, rearDistance <= viewLimit, eff);
            tracked.lastSeen = tickCounter;
            seen.add(key);
        }

        // ---- Mass-clear fast path (MTR "刷新线路" / "清除车辆") ---------------------------------
        // What left the data set this tick. One train going missing is exactly the case the staged
        // grace below exists for. A whole batch leaving inside one tick - half the tracked fleet,
        // or everything because ClientData.TRAINS went empty - is not jitter: waiting out the
        // 30-60 s grace would leave every one of those lights hanging in the air, which is the bug
        // this exists to fix.
        final int trackedCount = ACTIVE.size();
        final List<String> gone = new ArrayList<>();
        for (final String key : ACTIVE.keySet()) {
            if (!known.contains(key)) {
                gone.add(key);
            }
        }
        final int missingNow = gone.size();
        // max(2, ceil(tracked * ratio)): the floor keeps a lone despawn (and a lone range-based
        // sync gap) on the jitter path, the ratio makes the "batch" bar scale with the save - a
        // two-train test world and a fifty-train network react to the same event. At the 0.5
        // default, "two of two" and "twenty-five of fifty" both fire, "one of one" does not.
        final int massClearThreshold = Math.max(MASS_CLEAR_MIN,
                (int) Math.ceil(trackedCount * cfg.massClearRatio));
        if (missingNow > 0 && (known.isEmpty() || missingNow >= massClearThreshold)) {
            for (final String key : gone) {
                final Tracked dropped = ACTIVE.remove(key);
                if (dropped != null) {
                    // The one existing removal entry point (backend.removeLine per end). Reaching
                    // into the engine's own list from here would bypass that path - including the
                    // per-backend bookkeeping and the dirty-position marking a removal does - so a
                    // batch uses exactly the same call a timed-out single train uses.
                    dropped.remove(backend, key);
                }
            }
            // The beam sheet needs no handling: BeamGlow walks ClientData.TRAINS itself every
            // frame and keeps no per-train state, so a train that left the data set is simply not
            // drawn on that frame, and its Tracked is gone above so nothing can be redrawn from it.
            ClearDiag.cleared(gone.size());
        }

        // Trains missing from this frame's snapshot: sync churn first, real despawn later.
        for (final Iterator<Map.Entry<String, Tracked>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Tracked> entry = it.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            final Tracked tracked = entry.getValue();
            final long age = tickCounter - tracked.lastSeen;
            if (age <= cfg.missingKeepTicks) {
                continue;
            }
            // Still inside the player's view? Keep it - but only for a sync gap, never for the whole
            // grace: the light is frozen at the position the train was *last seen* at, so a long
            // window here is exactly the "track stays lit after the train left" report. See
            // MISSING_LIT_MAX_TICKS.
            // The siding is unknown here (the train is missing from the snapshot), so this uses the
            // global distance - it only decides whether to keep a light alive, never its length.
            if (tracked.near(eye, cfg.maxDistance) && age <= MISSING_LIT_MAX_TICKS) {
                continue;
            }
            if (age <= cfg.missingDimTicks) {
                tracked.hide(backend, entry.getKey());
                continue;
            }
            if (age <= cfg.missingDetachTicks) {
                continue;
            }
            tracked.remove(backend, entry.getKey());
            it.remove();
        }

        RailRelightHooks.tick(cfg, tickCounter);
        // tracked/missing are the numbers the decision above used (before the batch was removed),
        // so "tracked=12 missing=12 massClear=12" reads as one event rather than as an empty state.
        ClearDiag.tick(cfg.massClearDiagnostics, tickCounter, trackedCount, missingNow);
    }

    /**
     * Head light: on the leading face of the front car, along that car's own tangent. The end and
     * the heading both come from {@link TrainPose}, which resolved reversal and {@code swapEnds}
     * already - so this is the same geometry {@link BeamGlow} draws and the rail bake bakes.
     */
    private static EndLight headSegment(final TrainPose pose, final HeadlightConfig cfg,
                                        final HeadlightConfig.Effective eff) {
        return endSegment(pose.front(), pose.forward(), cfg.headOffsetClamped(), eff.headLength,
                HeadlightConfig.HEAD_TIP_DROP, cfg.heightOffset);
    }

    /** Tail light: on the trailing face of the last car, along that car's tangent, reversed. */
    private static EndLight tailSegment(final TrainPose pose, final HeadlightConfig cfg,
                                        final HeadlightConfig.Effective eff) {
        return endSegment(pose.rear(), pose.backward(), cfg.tailOffsetClamped(), eff.tailLength,
                HeadlightConfig.TAIL_TIP_DROP, cfg.heightOffset);
    }

    /** One lamp; {@link EndLight} owns the arithmetic, this only rejects a degenerate heading. */
    private static EndLight endSegment(final Vec3d base, final Vec3d dir, final double offset,
                                       final double length, final double tipDrop,
                                       final double height) {
        if (dir == Vec3d.ZERO) {
            return null;
        }
        return EndLight.of(base, dir, offset, length, tipDrop, height);
    }

    private static List<TrainClient> snapshotTrains() {
        try {
            return new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Per-train bookkeeping; the two light segments live inside the backend under {@code key#head/#tail}. */
    private static final class Tracked {
        long lastSeen;
        /** Consecutive ticks this train was in the data set but its pose could not be resolved. */
        int unplacedTicks;
        Vec3d lastHeadStart = Vec3d.ZERO;
        Vec3d lastHeadEnd = Vec3d.ZERO;
        // The travel direction is NOT kept here: DepartureState already derives it once per tick
        // from the rail-progress delta, and both the light tick and the beam renderer read it from
        // there, so the two cannot disagree about which end is the nose.

        void update(final LightBackend backend, final String key, final EndLight head, final EndLight tail,
                    final boolean showHead, final boolean showTail, final HeadlightConfig.Effective eff) {
            if (head != null) {
                lastHeadStart = head.start;
                lastHeadEnd = head.end;
                if (showHead) {
                    backend.updateLine(key + "#head", head.start, head.end, eff.headLuminance);
                } else {
                    backend.hideLine(key + "#head");
                }
            }
            if (tail != null) {
                if (showTail) {
                    backend.updateLine(key + "#tail", tail.start, tail.end, eff.tailLuminance);
                } else {
                    backend.hideLine(key + "#tail");
                }
            }
        }

        void hide(final LightBackend backend, final String key) {
            backend.hideLine(key + "#head");
            backend.hideLine(key + "#tail");
        }

        void remove(final LightBackend backend, final String key) {
            backend.removeLine(key + "#head");
            backend.removeLine(key + "#tail");
        }

        /** Whether the last known head beam is still close enough to be on screen. */
        boolean near(final Vec3d eye, final double limit) {
            return lastHeadStart.distanceTo(eye) <= limit || lastHeadEnd.distanceTo(eye) <= limit;
        }
    }
}
