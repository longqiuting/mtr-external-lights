package dev.js.mtrheadlight.sss;

import dev.js.mtrheadlight.DepartureState;
import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.HeadlightSwitch;
import dev.js.mtrheadlight.TrainLamps;
import dev.js.mtrheadlight.TrainPose;
import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>The lamps the screen-space scattering pass integrates this frame.</b>
 *
 * <p>The pass is a full-screen shader, so every extra lamp is paid for by <em>every pixel</em>. That
 * makes this class the whole performance budget of the feature: it walks the same lit trains the
 * flare renderer walks (same gates, same {@link HeadlightSwitch} answer, same {@link TrainLamps}
 * positions), then keeps only the {@value #MAX} nearest lamps to the camera. A 19-car train at the
 * far end of the platform contributes nothing to a 12-block glow, so it must not cost anything
 * either.
 *
 * <h2>Why the nearest N, and not "the first N"</h2>
 * {@link ClientData#TRAINS} is iterated in whatever order MTR keeps it; capping on the way in keeps
 * whichever trains happened to be enumerated first. The sort is over at most a few dozen candidates
 * (a handful of trains x four lamps), so it is cheaper than the mistake it prevents.
 *
 * <h2>What one lamp becomes in the shader</h2>
 * <pre>
 *   pos   = lamp position, camera relative        w = glow radius R, blocks
 *   dir   = lamp beam (unit: the way the light travels, = Lamp.beam())   w = beam length, blocks
 *   color = lamp colour, 0..1 linear              w = intensity (flare alpha, faded)
 * </pre>
 * The radius is {@code scatterRadius} for a head lamp and a bit smaller for a tail lamp: a red tail
 * light is a marker, not a head light, and it must not paint a 12-block ball of fog behind the car.
 */
final class ScatterLights {

    /** Hard cap: the fragment shader loops over {@code uLightPos[16]}. */
    static final int MAX = 16;

    /**
     * Lamps further than this from the camera are dropped before the sort. It is also roughly the
     * longest view ray the shader integrates ({@link ScatterPass#MAX_RAY}), so a lamp outside it can
     * only ever contribute zero.
     */
    static final double MAX_DISTANCE = 96.0D;

    /** Distance fade starts at this share of the beam distance (same curve as the flare). */
    private static final double FADE_FROM = 0.70D;
    /** Tail lamps glow over a smaller volume and at lower intensity than head lamps. */
    private static final double TAIL_RADIUS_SCALE = 0.65D;
    private static final double TAIL_POWER_SCALE = 0.60D;
    private static final double MIN_POWER = 1.0E-3D;

    /** One candidate lamp, mutable and pooled: the per-frame path must not allocate. */
    private static final class Cand {
        double dist;
        double px;
        double py;
        double pz;
        double dx;
        double dy;
        double dz;
        double power;
        double radius;
        double length;
        int rgb;
    }

    private static final List<Cand> POOL = new ArrayList<>();
    private static int used;
    private static int candidates;

    private ScatterLights() {
    }

    /**
     * Fills {@code pos} / {@code dir} / {@code color} (each {@code MAX * 4} floats) with the nearest
     * lit lamps, and returns how many were written. Returns {@code 0} when nothing in the world is
     * worth a full-screen pass - the caller then does no GL work at all.
     */
    static int collect(final MinecraftClient client, final HeadlightConfig cfg,
                       final float[] pos, final float[] dir, final float[] color) {
        used = 0;
        candidates = 0;
        if (client == null || client.world == null || client.gameRenderer == null) {
            return 0;
        }
        final Vec3d camera = client.gameRenderer.getCamera().getPos();
        final Vec3d eye = client.player == null ? camera : client.player.getPos();

        final List<TrainClient> trains = snapshotTrains();
        for (int t = 0; t < trains.size(); t++) {
            final TrainClient train = trains.get(t);
            if (!drawable(cfg, train)) {
                continue;
            }
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
            /*
             * 总开关 + 散射自己的开关，<b>只有这两个</b>。
             *
             * 以前这里写的是 cfg.beamFlare：散射要围着"亮着的灯位"积分，于是拿灯晕开关当代理，
             * 结果「关灯晕」把空气散射一起关掉了 —— 三件光效说好正交，这里是唯一破例的地方。
             * 现在读 beamScatter（帧级门 drawScatter() 读的是同一个字段，这里再收一次只是不让
             * 任何侧线覆盖放宽它），灯晕开不开都影响不到这一帧的灯阵。
             */
            if (!eff.enabled || !cfg.beamScatter) {
                continue;
            }
            final boolean reversed = DepartureState.reversed(train, train.isReversed()) ^ cfg.swapEnds;
            final TrainPose pose = TrainPose.ofShared(train, reversed);
            if (pose.cars() <= 0) {
                continue;
            }
            if (!HeadlightSwitch.judge(client.world, pose, cfg, train).lit()) {
                // 同一张判定表：不在线路上 / AUTO 且不需要灯 / AUTO 且停着，都不会进这一帧的灯阵。
                continue;
            }
            final List<TrainLamps.Lamp> lamps = TrainLamps.of(train, pose, cfg, eff);
            for (int i = 0; i < lamps.size(); i++) {
                final TrainLamps.Lamp lamp = lamps.get(i);
                if (lamp == null) {
                    continue;
                }
                final double d = lamp.pos.distanceTo(eye);
                if (d > MAX_DISTANCE || d > eff.beamDistance || d < 1.0E-3D) {
                    continue;
                }
                final double radius = cfg.scatterRadiusClamped()
                        * (lamp.head ? 1.0D : TAIL_RADIUS_SCALE);
                if (radius < 0.05D) {
                    continue;
                }
                final double power = lamp.alpha * (lamp.head ? 1.0D : TAIL_POWER_SCALE)
                        * distanceFade(d, eff.beamDistance);
                if (power < MIN_POWER) {
                    continue;
                }
                candidates++;
                final Cand c = next();
                c.dist = lamp.pos.squaredDistanceTo(camera);
                c.px = lamp.pos.x - camera.x;
                c.py = lamp.pos.y - camera.y;
                c.pz = lamp.pos.z - camera.z;
                /*
                 * 锥轴 = 灯照出去的方向。用 Lamp.beam()（= -dir）而不是 Lamp.dir：
                 * dir 是灯面朝向（LightFlare 的 facing 公式要的那一侧，取反后的），锥轴要的是
                 * 光离开车体的方向。两者只差一个符号，所以这里取反之后，散射的照亮方向与
                 * dir 改动之前逐字相同（见 TrainLamps.place 的注释）。
                 */
                final Vec3d beam = lamp.beam();
                c.dx = beam.x;
                c.dy = beam.y;
                c.dz = beam.z;
                c.power = power;
                c.radius = radius;
                c.length = lamp.length;
                c.rgb = lamp.rgb;
            }
        }
        if (used == 0) {
            return 0;
        }
        sortByDistance();
        final int n = Math.min(used, MAX);
        for (int i = 0; i < n; i++) {
            final Cand c = POOL.get(i);
            final int o = i * 4;
            pos[o] = (float) c.px;
            pos[o + 1] = (float) c.py;
            pos[o + 2] = (float) c.pz;
            pos[o + 3] = (float) c.radius;
            dir[o] = (float) c.dx;
            dir[o + 1] = (float) c.dy;
            dir[o + 2] = (float) c.dz;
            dir[o + 3] = (float) c.length;
            color[o] = ((c.rgb >> 16) & 0xFF) / 255.0F;
            color[o + 1] = ((c.rgb >> 8) & 0xFF) / 255.0F;
            color[o + 2] = (c.rgb & 0xFF) / 255.0F;
            color[o + 3] = (float) c.power;
        }
        return n;
    }

    /** Straight insertion sort: {@code used} is a few dozen at most, and it allocates nothing. */
    private static void sortByDistance() {
        for (int i = 1; i < used; i++) {
            final Cand key = POOL.get(i);
            int j = i - 1;
            while (j >= 0 && POOL.get(j).dist > key.dist) {
                POOL.set(j + 1, POOL.get(j));
                j--;
            }
            POOL.set(j + 1, key);
        }
    }

    private static Cand next() {
        while (POOL.size() <= used) {
            POOL.add(new Cand());
        }
        return POOL.get(used++);
    }

    /** Same visibility gates the flare renderer uses, so "no scatter" has the same named reasons. */
    private static boolean drawable(final HeadlightConfig cfg, final TrainClient train) {
        if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
            return false;
        }
        return !cfg.onlyTrains || train.transportMode == TransportMode.TRAIN;
    }

    private static List<TrainClient> snapshotTrains() {
        try {
            return new ArrayList<>(ClientData.TRAINS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 1 until {@link #FADE_FROM} of the beam distance, then smoothly down to 0 at the limit. */
    private static double distanceFade(final double d, final double limit) {
        if (limit <= 0.0D) {
            return 1.0D;
        }
        final double from = limit * FADE_FROM;
        if (d <= from) {
            return 1.0D;
        }
        if (d >= limit) {
            return 0.0D;
        }
        final double t = (d - from) / (limit - from);
        final double inv = 1.0D - t;
        return inv * inv;
    }

    /** How many lamps survived the gates before the nearest-N cut - for the diagnostics line. */
    static int candidates() {
        return candidates;
    }
}
