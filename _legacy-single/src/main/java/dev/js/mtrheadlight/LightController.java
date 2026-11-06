package dev.js.mtrheadlight;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import dev.lambdaurora.lambdynlights.api.behavior.LineLightBehavior;
import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps one pair of {@link LineLightBehavior}s (front beam + rear light) per visible train,
 * pushed into LambDynamicLights' behaviour manager every client tick.
 *
 * <p>Nothing here writes to the world, to blocks, or to MTR: MTR is only read
 * ({@code ClientData.TRAINS} plus the public pose accessors in {@link TrainPose}).
 *
 * <p>Each end is judged on its own: the front beam follows the front car, the tail light the rear
 * car, and each is capped at the client's render distance. A single train-level distance made the
 * tail light stay lit on an unrendered car once the front car was out of range.
 *
 * <p>Lifecycle: {@code ClientData.TRAINS} is replaced wholesale on every client data sync, so a
 * train can be missing for a while while it is really still there. Absence is handled in stages:
 * short gaps are ignored, longer ones dim the light, and only a real despawn detaches it.
 */
public final class LightController {
    private static DynamicLightBehaviorManager manager;

    private static final Map<String, TrainLight> ACTIVE = new HashMap<>();
    private static ClientWorld lastWorld;
    /** Monotonic client tick counter, used only to age lights that went missing. */
    private static long tickCounter;

    private LightController() {
    }

    /** Called from the {@code lambdynlights:initializer} entrypoint. */
    public static void setManager(final DynamicLightBehaviorManager behaviorManager) {
        manager = behaviorManager;
        MtrHeadlightClient.LOGGER.info("[mtr-headlight] LambDynamicLights behaviour manager registered: {}", behaviorManager != null);
    }

    public static void clear() {
        if (manager != null) {
            for (final TrainLight light : ACTIVE.values()) {
                light.detach(manager);
            }
        }
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
            // LDL drops every registered behaviour on world change, so drop our bookkeeping too.
            ACTIVE.clear();
            lastWorld = world;
            tickCounter = 0L;
        }

        if (manager == null || world == null || client.player == null) {
            return;
        }

        if (!cfg.enabled) {
            // Master switch off: drop everything right away, or the last light stays lit forever
            // where the train was.
            clear();
            return;
        }

        tickCounter++;
        final Vec3d eye = client.player.getPos();
        // An unrendered car must not leave a light behind; maxDistance defaults to 192 (12 chunks),
        // which matches the usual client render distance.
        final double viewLimit = cfg.maxDistance;
        final Set<String> seen = new HashSet<>();

        for (final TrainClient train : snapshotTrains()) {
            if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
                continue;
            }
            if (cfg.onlyTrains && train.transportMode != TransportMode.TRAIN) {
                continue;
            }
            // Depots/sidings the player muted in the config screen.
            if (train.sidingId != 0L && cfg.mutedSidings.contains(train.sidingId)) {
                continue;
            }

            final TrainPose pose = TrainPose.of(train);
            if (pose.front() == Vec3d.ZERO && pose.rear() == Vec3d.ZERO) {
                continue;
            }

            // Per-end judgement: the beam belongs to the front car, the tail light to the rear car.
            final double frontDistance = pose.front().distanceTo(eye);
            final double rearDistance = pose.rear().distanceTo(eye);
            final String key = train.id != 0L ? ("id:" + train.id) : ("tid:" + train.trainId);

            TrainLight light = ACTIVE.get(key);

            if (frontDistance > viewLimit && rearDistance > viewLimit) {
                if (light != null) {
                    seen.add(key);
                    light.lastSeen = tickCounter;
                    if (frontDistance > viewLimit * 1.25D && rearDistance > viewLimit * 1.25D) {
                        light.detach(manager);
                        ACTIVE.remove(key);
                        seen.remove(key);
                    } else {
                        light.dim();
                    }
                }
                continue;
            }

            if (light == null) {
                light = new TrainLight();
                manager.add(light.head);
                manager.add(light.tail);
                ACTIVE.put(key, light);
            }
            light.update(pose, cfg, frontDistance <= viewLimit, rearDistance <= viewLimit);
            light.lastSeen = tickCounter;
            seen.add(key);
        }

        // Trains missing from this frame's snapshot: sync churn first, real despawn later.
        for (final Iterator<Map.Entry<String, TrainLight>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, TrainLight> entry = it.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            final long age = tickCounter - entry.getValue().lastSeen;
            if (age <= cfg.missingKeepTicks) {
                continue;
            }
            // 列车暂时不在同步集合里（MTR 按范围同步车组）。只要它最后那束光还在玩家视野内，
            // 就不要熄灭——否则会表现为"光消失一会又出现"。远处的才熄。
            final Vector3d p = entry.getValue().head.getStartPoint();
            final double dx = p.x - eye.x;
            final double dy = p.y - eye.y;
            final double dz = p.z - eye.z;
            final double staleDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (staleDistance <= cfg.maxDistance && age <= cfg.missingDimTicks * 2L) {
                continue;
            }
            if (age <= cfg.missingDimTicks) {
                entry.getValue().dim();
                continue;
            }
            if (age <= cfg.missingDetachTicks) {
                continue;
            }
            entry.getValue().detach(manager);
            it.remove();
        }
    }

    private static List<TrainClient> snapshotTrains() {
        try {
            return new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** One train = one white beam in front + one dim (non-coloured) light at the back. */
    private static final class TrainLight {
        final LineLightBehavior head = new LineLightBehavior(new Vector3d(), new Vector3d(), 0);
        final LineLightBehavior tail = new LineLightBehavior(new Vector3d(), new Vector3d(), 0);
        long lastSeen;

        void update(final TrainPose pose, final HeadlightConfig cfg, final boolean showHead, final boolean showTail) {
            Vec3d fwd = pose.forward();
            if (fwd == Vec3d.ZERO) {
                dim();
                return;
            }
            if (cfg.swapEnds) {
                fwd = fwd.multiply(-1.0D);
            }

            final Vec3d front = cfg.swapEnds ? pose.rear() : pose.front();
            final Vec3d rear = cfg.swapEnds ? pose.front() : pose.rear();
            final double y = Math.max(front.y, rear.y) + cfg.heightOffset;

            if (showHead) {
                final Vec3d headStart = new Vec3d(
                        front.x + fwd.x * cfg.headOffset,
                        y,
                        front.z + fwd.z * cfg.headOffset
                );
                final Vec3d headEnd = new Vec3d(
                        headStart.x + fwd.x * cfg.headLength,
                        headStart.y - 0.60D,
                        headStart.z + fwd.z * cfg.headLength
                );
                head.setStartPoint(headStart.x, headStart.y, headStart.z);
                head.setEndPoint(headEnd.x, headEnd.y, headEnd.z);
                head.setLuminance(cfg.headLuminance);
            } else {
                head.setLuminance(0);
            }

            if (showTail) {
                final Vec3d back = fwd.multiply(-1.0D);
                final Vec3d tailStart = new Vec3d(
                        rear.x + back.x * cfg.tailOffset,
                        y,
                        rear.z + back.z * cfg.tailOffset
                );
                final Vec3d tailEnd = new Vec3d(
                        tailStart.x + back.x * cfg.tailLength,
                        tailStart.y - 0.35D,
                        tailStart.z + back.z * cfg.tailLength
                );
                tail.setStartPoint(tailStart.x, tailStart.y, tailStart.z);
                tail.setEndPoint(tailEnd.x, tailEnd.y, tailEnd.z);
                tail.setLuminance(cfg.tailLuminance);
            } else {
                tail.setLuminance(0);
            }
        }

        /** Light off without unregistering (used while a train is temporarily absent). */
        void dim() {
            head.setLuminance(0);
            tail.setLuminance(0);
        }

        void detach(final DynamicLightBehaviorManager behaviorManager) {
            try {
                behaviorManager.remove(head);
                behaviorManager.remove(tail);
            } catch (RuntimeException e) {
                MtrHeadlightClient.LOGGER.debug("[mtr-headlight] failed to detach light", e);
            }
        }
    }
}
