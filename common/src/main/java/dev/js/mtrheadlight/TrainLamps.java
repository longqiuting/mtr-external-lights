package dev.js.mtrheadlight;

import mtr.data.TrainClient;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Where the lamps of a train are, in world space</b> - the one place the lamp position and the
 * lamp heading are computed, for every consumer (the flare renderer, the block-light beams, the
 * rail bake and the editor preview).
 *
 * <h2>Why this class exists</h2>
 * The old path took the <b>rail point at the car face</b> plus {@code HeadlightConfig#heightOffset}
 * and called that the lamp. That is not where the lamp is: it is where the <em>rails</em> are. Three
 * consequences, all reported as "this train has no light":
 * <ul>
 *   <li>the flare/beam started at the rail surface under the nose, so on a car whose lamp sits
 *       1.5 blocks up behind a sloped nose the light visually belonged to the coupler, not the
 *       lamp;</li>
 *   <li>every <b>built-in</b> MTR model (114 {@code TrainType}s, hand-written Java models, no
 *       {@code DynamicTrainModel}) produced exactly the same generic point, no matter what the
 *       model's own lamp boxes said - even though {@code builtin_lights.json} has 95 of them
 *       measured;</li>
 *   <li>the left/right pair of a real car front collapsed into one light at the centreline.</li>
 * </ul>
 *
 * <h2>The four sources, highest confidence first</h2>
 * <ol>
 *   <li><b>declared</b> - {@code assets/<ns>/lte_vehicle_lights.json} or an editor edit
 *       ({@link VehicleLightOverrides}), per vehicle id. Author intent, wins outright.</li>
 *   <li><b>model</b> - {@link VehicleLights} reads the {@code DynamicTrainModel}'s
 *       {@code partsInfo} geometry joined by name with {@code properties.parts[].stage}, and
 *       clusters the lamp boxes of the group that sits on the wanted end face.</li>
 *   <li><b>builtin</b> - the same class falls through to {@link BuiltinLights}
 *       ({@code builtin_lights.json}, 95/114 vanilla models measured offline from the jar).</li>
 *   <li><b>default</b> - a symmetric pair from the car size (see
 *       {@link VehicleLights#defaultSlot}).</li>
 * </ol>
 * The result of 1-4 is a {@link VehicleLightOverrides.Resolved}: lateral offset from the car's
 * centreline, height above the car's reference plane, and how far behind the end face the lamp sits.
 * This class turns that car-local description into a world-space point.
 *
 * <h2>The transform (car-local -> world)</h2>
 * <pre>
 * car     = head ? 0 : cars - 1                  // the head lamp belongs to the leading car
 * face    = head ? pose.carStart(car) : pose.carEnd(car)   // rail point ON that car's end face
 * out     = head ? pose.carBodyForward(car) : -pose.carBodyForward(car)   // unit, includes grade
 * right   = normalize(out x (0,1,0))             // horizontal, so a lamp never banks
 * lamp    = face + right * lateral + (0, height, 0) - out * (inset + FLARE_GAP)
 * dir     = out, then rotated by the entry's own yaw/pitch if it declares a rot
 * </pre>
 *
 * <p><b>Why the car body chord and not the rail tangent.</b> MTR rotates the car body by the chord
 * between its own two faces ({@code Train#calculateCar}: yaw from {@code positions[car]} and
 * {@code positions[car + 1]}), so the chord is the direction the player sees; the rail tangent at
 * the car's midpoint is a second-order approximation of it and visibly diverges on a tight curve.
 * {@link TrainPose#carBodyForward(int)} is that chord and already folds in reversal and
 * {@code swapEnds}, so nothing here has a second heading branch.
 *
 * <p><b>Why the chord is negated rather than reversed with a second axis.</b> For the tail the
 * whole basis is mirrored ({@code out} and {@code right} both flip), which keeps the pair symmetric
 * about the car's centreline - the only property the left/right lamps need.
 *
 * <h2>Minimum visibility</h2>
 * A lamp whose height comes out below {@value #MIN_LAMP_HEIGHT} (a light bar modelled at floor
 * level, or a model whose {@code y} origin is not the floor) is lifted to
 * {@code HeadlightConfig#heightOffset}, and one above {@value #MAX_LAMP_HEIGHT} is dropped to
 * {@value #MAX_LAMP_HEIGHT} - a lamp on the roof is not a head light. The rule is deliberately
 * "visible first, exact second": the editor can override any of it per vehicle.
 */
public final class TrainLamps {

    /** Gap between the lamp and the car's end face along the beam, blocks (anti z-fighting). */
    private static final double FLARE_GAP = 0.08D;
    /** A lamp lower than this is lifted to the configured height (it would be inside the car). */
    private static final double MIN_LAMP_HEIGHT = 0.30D;
    /** A lamp higher than this is not a head light (roof strip); clamped down to it. */
    private static final double MAX_LAMP_HEIGHT = 3.30D;
    /** Lateral clamp, blocks: a lamp cannot sit further out than this from the centreline. */
    private static final double MAX_LATERAL = 2.4D;
    /** A lamp may sit this far in front of the car face (a model whose nose overhangs the pitch). */
    private static final double MAX_LAMP_AHEAD = 2.0D;
    /** Car width used when no model could be read (the MTR default car is ~2.9 wide). */
    private static final double FALLBACK_WIDTH = 2.9D;
    /** Lateral share of the width for the estimated pair (same value as {@link VehicleLights}). */
    private static final double FALLBACK_LATERAL = 0.36D;
    /** The editor's per-beam alpha is relative to this value (its own default). */
    private static final double ALPHA_UNIT = 0.16D;

    private static final Vec3d UP = new Vec3d(0.0D, 1.0D, 0.0D);

    /** One lamp, world space. Immutable. */
    public static final class Lamp {
        /** Lamp centre, world space. */
        public final Vec3d pos;
        /** Unit heading out of the lamp (already contains the grade and any declared rot). */
        public final Vec3d dir;
        /** 0xRRGGBB. */
        public final int rgb;
        /** Brightness multiplier for the flare, {0.2 .. 3.0}; 1.0 = the configured base. */
        public final double alpha;
        /** Beam diameter in blocks from the layout (0 = unknown) - the flare's size hint. */
        public final double width;
        /** Beam length in blocks, for the block-light wedge. */
        public final double length;
        /** {@code true} for a lamp on the leading end of the leading car. */
        public final boolean head;
        /** {@code declared} / {@code model} / {@code builtin} / {@code default} - diagnostics. */
        public final String source;

        Lamp(final Vec3d pos, final Vec3d dir, final int rgb, final double alpha,
             final double width, final double length, final boolean head, final String source) {
            this.pos = pos;
            this.dir = dir;
            this.rgb = rgb;
            this.alpha = alpha;
            this.width = width;
            this.length = length;
            this.head = head;
            this.source = source;
        }

        @Override
        public String toString() {
            return (head ? "head" : "tail") + "(" + source
                    + " @" + fmt(pos.x) + "," + fmt(pos.y) + "," + fmt(pos.z)
                    + " dir=" + fmt(dir.x) + "," + fmt(dir.y) + "," + fmt(dir.z) + ")";
        }
    }

    private TrainLamps() {
    }

    /**
     * Every lamp of one train, head end first. Never null, never empty for a train that has a pose:
     * when nothing else is known a symmetric pair per end is estimated, because "a train with no
     * light" is the bug this class exists to fix.
     *
     * @param train the car whose lamps are wanted (only {@code baseTrainType} is read)
     * @param pose  this frame's pose for that train ({@link TrainPose#ofShared})
     * @param cfg   live config (heights, colours, beam lengths)
     * @param eff   the train's effective config (per-siding overrides folded in)
     */
    public static List<Lamp> of(final TrainClient train, final TrainPose pose,
                                final HeadlightConfig cfg, final HeadlightConfig.Effective eff) {
        final List<Lamp> out = new ArrayList<>(4);
        if (pose == null || pose.cars() <= 0 || train == null) {
            return out;
        }
        final VehicleLights.Layout layout = VehicleLights.layoutFor(train);
        final String id = train.baseTrainType == null ? "" : train.baseTrainType;
        List<VehicleLightOverrides.Resolved> resolved;
        try {
            resolved = VehicleLightOverrides.resolved(id, layout);
        } catch (Throwable t) {
            // A pack that cannot be parsed must not take the light out with it.
            resolved = new ArrayList<>(0);
        }
        if (resolved == null || resolved.isEmpty()) {
            resolved = estimated(layout, cfg);
        }
        final String source = sourceOf(layout, resolved);
        for (int i = 0; i < resolved.size(); i++) {
            final VehicleLightOverrides.Resolved r = resolved.get(i);
            if (r == null) {
                continue;
            }
            final Lamp lamp = place(pose, r, cfg, eff, source);
            if (lamp != null) {
                out.add(lamp);
            }
        }
        return out;
    }

    /**
     * The lamp pair used when neither a model, a built-in table entry nor a pack file says
     * anything: two lamps at {@code +/-0.36 * width} on each end face.
     */
    private static List<VehicleLightOverrides.Resolved> estimated(final VehicleLights.Layout layout,
                                                                  final HeadlightConfig cfg) {
        final double width = layout != null && layout.width > 0.6D ? layout.width : FALLBACK_WIDTH;
        final double lateral = FALLBACK_LATERAL * width;
        final double height = clamp(cfg.heightOffset, MIN_LAMP_HEIGHT, MAX_LAMP_HEIGHT);
        final List<VehicleLightOverrides.Resolved> out = new ArrayList<>(4);
        out.add(beam("head", lateral, height, cfg.beamHeadLength));
        out.add(beam("head", -lateral, height, cfg.beamHeadLength));
        out.add(beam("tail", lateral, height, cfg.beamTailLength));
        out.add(beam("tail", -lateral, height, cfg.beamTailLength));
        return out;
    }

    private static VehicleLightOverrides.Resolved beam(final String type, final double lateral,
                                                      final double height, final double length) {
        final VehicleLightOverrides.Beam b = new VehicleLightOverrides.Beam(type);
        b.length = Double.valueOf(length);
        b.pos = new double[]{lateral, height, 0.0D};
        return VehicleLightOverrides.Resolved.ofPublic(b, null);
    }

    /** {@code declared} beats the model, the model beats the built-in table, else {@code default}. */
    private static String sourceOf(final VehicleLights.Layout layout,
                                   final List<VehicleLightOverrides.Resolved> resolved) {
        for (final VehicleLightOverrides.Resolved r : resolved) {
            if (r != null && r.declared) {
                return "declared";
            }
        }
        if (layout == null) {
            return "default";
        }
        return layout.head != null && !layout.head.isDefault() ? "model" : "default";
    }

    /** One resolved lamp -> one world-space lamp, or null when the car has no usable heading. */
    private static Lamp place(final TrainPose pose, final VehicleLightOverrides.Resolved r,
                              final HeadlightConfig cfg, final HeadlightConfig.Effective eff,
                              final String source) {
        final int cars = Math.max(1, pose.cars());
        final int car = r.isHead() ? 0 : cars - 1;
        final Vec3d base = r.isHead() ? pose.carStart(car) : pose.carEnd(car);
        Vec3d out = pose.carBodyForward(car);
        if (out == Vec3d.ZERO) {
            out = pose.carForward(car);            // degenerate chord: the rail tangent
        }
        if (out == Vec3d.ZERO) {
            return null;
        }
        if (!r.isHead()) {
            out = out.multiply(-1.0D);
        }
        Vec3d right = out.crossProduct(UP);
        if (right.lengthSquared() < 1.0E-8D) {
            return null;                            // straight up/down: no car does this
        }
        right = right.normalize();

        final double lateral = clamp(r.lateral, -MAX_LATERAL, MAX_LATERAL);
        final double height = r.height < MIN_LAMP_HEIGHT
                ? clamp(cfg.heightOffset, MIN_LAMP_HEIGHT, MAX_LAMP_HEIGHT)
                : clamp(r.height, MIN_LAMP_HEIGHT, MAX_LAMP_HEIGHT);
        /*
         * Positive inset = behind the car face, negative = in front of it (a model whose nose
         * overhangs MTR's car pitch, see BuiltinLights#slot). Bounded on both sides: a lamp may sit
         * up to 1.5 blocks inside (a recessed lamp) and 2 blocks ahead (never further - that would
         * detach the glow from the car).
         */
        final double back = clamp(r.inset, -MAX_LAMP_AHEAD, 1.5D) + FLARE_GAP;
        final Vec3d pos = base
                .add(right.multiply(lateral))
                .add(0.0D, height, 0.0D)
                .subtract(out.multiply(back));

        Vec3d dir = out;
        if (r.rot != null && r.rot.length >= 2) {
            dir = rotate(dir, right, r.rot[0], r.rot[1]);
        }
        final int rgb = r.declared ? r.color
                : (r.isHead() ? eff.beamHeadColor : eff.beamTailColor);
        final double alpha = clamp(r.alpha / ALPHA_UNIT, 0.2D, 3.0D);
        final double length = r.declared ? r.length
                : (r.isHead() ? eff.beamHeadLength : eff.beamTailLength);
        return new Lamp(pos, dir, rgb & 0xFFFFFF, alpha, Math.max(0.0D, r.width),
                clamp(length, 1.0D, 64.0D), r.isHead(), source);
    }

    /**
     * Yaw about {@code up}, then pitch about {@code right} - the editor's own order (a beam is first
     * pointed left/right, then up/down), in degrees. Zero rotation returns {@code dir} unchanged, so
     * the common case allocates nothing.
     */
    private static Vec3d rotate(final Vec3d dir, final Vec3d right, final double yawDeg,
                                final double pitchDeg) {
        Vec3d out = dir;
        if (Math.abs(yawDeg) > 1.0E-6D) {
            final double a = Math.toRadians(yawDeg);
            final double cos = Math.cos(a);
            final double sin = Math.sin(a);
            // Rodrigues about the (fixed) vertical axis.
            out = new Vec3d(out.x * cos + out.z * sin, out.y, -out.x * sin + out.z * cos);
        }
        if (Math.abs(pitchDeg) > 1.0E-6D) {
            final double a = Math.toRadians(pitchDeg);
            final double cos = Math.cos(a);
            final double sin = Math.sin(a);
            // Rodrigues about `right`: the component along the axis is kept, the rest is rotated.
            final double d = out.dotProduct(right);
            final Vec3d perp = out.subtract(right.multiply(d));
            final Vec3d cross = right.crossProduct(perp);
            out = perp.multiply(cos).add(cross.multiply(sin)).add(right.multiply(d));
        }
        final double len = out.length();
        return len < 1.0E-6D ? dir : out.multiply(1.0D / len);
    }

    /**
     * Mean of the lamps of one end: the point a single block-light wedge should leave from, and the
     * centre of the flare pair. {@link Vec3d#ZERO} when that end has no lamp at all.
     */
    public static Vec3d centre(final List<Lamp> lamps, final boolean head) {
        Vec3d sum = Vec3d.ZERO;
        int n = 0;
        for (final Lamp lamp : lamps) {
            if (lamp.head == head) {
                sum = sum.add(lamp.pos);
                n++;
            }
        }
        return n == 0 ? Vec3d.ZERO : sum.multiply(1.0D / n);
    }

    /** Mean heading of one end (unit), or {@link Vec3d#ZERO} when that end has no lamp. */
    public static Vec3d heading(final List<Lamp> lamps, final boolean head) {
        Vec3d sum = Vec3d.ZERO;
        int n = 0;
        for (final Lamp lamp : lamps) {
            if (lamp.head == head) {
                sum = sum.add(lamp.dir);
                n++;
            }
        }
        if (n == 0) {
            return Vec3d.ZERO;
        }
        final double len = sum.length();
        return len < 1.0E-6D ? Vec3d.ZERO : sum.multiply(1.0D / len);
    }

    /** Longest wedge of one end, or 0 when that end has no lamp. */
    public static double wedgeLength(final List<Lamp> lamps, final boolean head) {
        double best = 0.0D;
        for (final Lamp lamp : lamps) {
            if (lamp.head == head && lamp.length > best) {
                best = lamp.length;
            }
        }
        return best;
    }

    private static double clamp(final double value, final double min, final double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return value < min ? min : (value > max ? max : value);
    }

    private static String fmt(final double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(d));
    }
}
