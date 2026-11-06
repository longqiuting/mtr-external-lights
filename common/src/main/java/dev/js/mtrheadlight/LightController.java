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
 */
public final class LightController {
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

        for (final TrainClient train : snapshotTrains()) {
            if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
                continue;
            }
            if (cfg.onlyTrains && train.transportMode != TransportMode.TRAIN) {
                continue;
            }

            final String key = train.id != 0L ? ("id:" + train.id) : ("tid:" + train.trainId);

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
                continue;
            }

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
            // Still inside the player's view? Keep it: MTR syncs trains by range, so a train can drop
            // out of the data set while it is plainly visible, and dimming then looks like a flicker.
            // The siding is unknown here (the train is missing from the snapshot), so this uses the
            // global distance - it only decides whether to keep a light alive, never its length.
            if (tracked.near(eye, cfg.maxDistance) && age <= cfg.missingDimTicks * 2L) {
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
