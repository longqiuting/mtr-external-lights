package dev.js.mtrheadlight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtr.client.DynamicTrainModel;
import mtr.data.TrainClient;
import mtr.model.ModelTrainBase;
import mtr.render.JonModelTrainRenderer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the lamps of a dynamic train model are - production light-slot binding.
 *
 * <p>Earlier this class was a probe that answered "can the geometry be read at all". It can:
 * {@code DynamicTrainModel.partsInfo} (group name -&gt; set of {@code PartInfo}) holds the part
 * origins, and {@code DynamicTrainModel.properties} is the model JSON itself, so {@code parts[].stage}
 * and {@code parts[].render_condition} are available at run time without touching the resource pack.
 * This version answers the only question the beam renderer asks: <b>which point of the car is the
 * head light and which is the tail light</b>, and if the model does not say, where to put them.
 *
 * <h2>Why the name is the primary signal and the stage is not</h2>
 * Measured over 67 real packs / 318 vehicle definitions: <b>94.3% (300/318)</b> have a group named
 * after the head light and <b>90.9% (289/318)</b> one named after the tail light, but those groups
 * sit in {@code ALWAYS_ON_LIGHTS} on 225 of the models - {@code stage == LIGHTS} is mostly door,
 * roof and cabin lighting and is <em>not</em> a head-light criterion. So the stage is only a
 * tie-breaker here (and {@code render_condition} is not used at all: it distinguishes door
 * open/closed geometry, not lamp on/off).
 *
 * <h2>Black list</h2>
 * A name hit is not enough: {@code roof_head_light} (56 models) is the light strip on the
 * <em>roof</em> above the cab, {@code taillight} in {@code INTERIOR} (10 models) is the lens/inner
 * shell rather than the emitter, and {@code doorlight_on/off} (295 models) is a door lamp. Those
 * are rejected by name (see {@link #REJECT_WORDS}) and, independently, by geometry: a group above
 * {@value #ROOF_FRACTION} of the model's height is treated as a roof strip, not a lamp.
 *
 * <h2>Per car, not per model</h2>
 * The old code derived the offset from the whole-model bounding box. That is wrong twice: only
 * 40.4% of real head/tail groups sit within one block of the model end (median 1.84), and one
 * bbmodel may contain several cars - {@code sh15_pth.bbmodel} has its headlight 8.87 blocks from
 * the model end because the file holds a whole consist. A group is therefore assigned to the car
 * section it physically lies in (section length = model length / number of cars, from the model's
 * own {@code length}), its position is normalised inside <em>that</em> section, and the renderer
 * places it on the car's own face with that car's own <b>body orientation</b> (see
 * {@link TrainPose#carBodyForward(int)}), never on the train's overall bbox.
 *
 * <h2>A pair, never a mean</h2>
 * A car front has a lamp on each side, and a pack can say so in two ways: two placements of one
 * group ({@code headlights} with two {@code positions}) or two groups ({@code headlight_left} /
 * {@code headlight_right}). Averaging either gives the centreline, and picking only the
 * best-scoring group gives one side - both were reported as "only one side is lit". So a slot now
 * carries <b>one lateral entry per lamp</b>: the anchor group's lamp units (laterals clustered at
 * {@link #LAMP_CLUSTER_TOL}) plus every group on the same face, in the same stage, that is named
 * after that end or is the lateral mirror of a lamp already taken. Same-side lamps survive too; the
 * list is only capped at {@value #MAX_LAMPS} and deduplicated.
 *
 * <h2>Head by default</h2>
 * Both ends are resolved here (the dynamic light and the beam sheet need the tail lamp), but the
 * cone renderer only draws the head end unless {@code HeadlightConfig#coneOnTail} is on.
 *
 * <h2>Default position</h2>
 * 5.7% of the models have no head-light group at all (all of them buses) and 9.1% no tail group.
 * For those, the lamp is estimated from the car size: a <b>pair</b> at {@code +/-0.36 * width}
 * laterally, {@value #DEFAULT_HEIGHT} blocks above the car's reference point, {@value #FACE_GAP}
 * of a block outside the end face. The pair is emitted as two cones, because that is what a car
 * front with no lamp geometry most likely has.
 *
 * <h2>Caching</h2>
 * A {@link Layout} costs a dozen reflective field reads per part plus a JSON walk, so it is parsed
 * once per model object and cached ({@link #layoutFor}). While a train moves only the per-frame
 * vector arithmetic runs; the layout is never rebuilt.
 */
public final class VehicleLights {

    /** Gap between the car's end face and a lamp, blocks - the default slot's "outside the face". */
    public static final double FACE_GAP = 0.05D;

    /** Default lateral offset of each lamp of the estimated pair, as a share of the car width. */
    private static final double DEFAULT_LATERAL_FRACTION = 0.36D;
    /** Default lamp height above the car's reference point, blocks. */
    private static final double DEFAULT_HEIGHT = 0.55D;
    /** A group above this share of the model's height is a roof strip, not a lamp. */
    private static final double ROOF_FRACTION = 0.95D;
    /** "Near the end face" = within this share of the car length ... */
    private static final double END_FRACTION = 0.35D;
    /** ... but never less than this many blocks. */
    private static final double END_MIN_BLOCKS = 1.2D;
    /** A named slot is pulled at most this far behind the face (nose slope / recessed lamp). */
    private static final double MAX_INSET = 1.5D;
    /** Fallback car width when the model JSON does not carry one. */
    private static final double DEFAULT_WIDTH = 2.9D;
    /** Hard clamp on a named slot's lateral offset, as a share of the car width. */
    private static final double LATERAL_CLAMP_FRACTION = 0.45D;
    /** Absolute ceiling on that lateral offset, blocks (a lamp is never this far off the centre). */
    private static final double MAX_LATERAL_BLOCKS = 2.5D;
    /** A bbmodel that holds more cars than this is almost certainly not a train model. */
    private static final int MAX_SECTIONS = 8;
    private static final int CACHE_SIZE = 96;
    /** Below this the lateral offset is rounding noise between the two lamps of a pair. */
    private static final double LATERAL_DEAD_ZONE = 0.06D;
    /**
     * Placements within this many blocks along the car belong to the same lamp assembly, i.e. the
     * same end face. A group's cubes are spread over the housing, so this is a face band, not a
     * tolerance on the lamp itself.
     */
    private static final double LAMP_ALONG_TOL = 1.0D;
    /**
     * Two lateral positions closer than this are <b>one</b> lamp: a lamp is built from several
     * cubes (their centres differ by centimetres), while a left/right pair is at least a block
     * apart on every real car. Used both inside a group and when two groups name the same lamp.
     */
    private static final double LAMP_CLUSTER_TOL = 0.35D;
    /** Two groups sit on the same end face when their distances to it differ by less than this. */
    private static final double FACE_MATCH_BLOCKS = 0.6D;
    /** A lamp mirrors another when the two offsets from the centreline sum to ~0 (blocks). */
    private static final double MIRROR_TOL = 0.35D;
    /**
     * Two groups naming the same physical lamp differ by less than this (blocks) - deduplicated.
     * Same order as {@link #LAMP_CLUSTER_TOL}: two groups that both cover one housing (a {@code
     * headlight} group and a {@code headlight_on} one) must not become two nearly coincident cones.
     */
    private static final double LAMP_DUPLICATE_TOL = 0.35D;
    /** Hard cap on the lamps emitted per end - a full-width light bar must not become a fan. */
    private static final int MAX_LAMPS = 4;
    /**
     * How far outside the symmetric lamp pair another lamp may sit and still be treated as a lamp
     * (a second lamp on the same side): {@code pairTrack * this + 0.1} blocks.
     */
    private static final double LAMP_TRACK_SLACK = 1.3D;

    // --- name tables -----------------------------------------------------------------------

    /** Group names that mean "head light". {@code deng} alone is deliberately absent: it matches weideng. */
    private static final String[] HEAD_WORDS = {
        "headlight", "headlights", "head_light", "head_lights", "headlamp", "head_lamp",
        "lighthead", "head_led", "前照", "头灯", "toudeng", "qiandeng"
    };
    private static final String[] TAIL_WORDS = {
        "taillight", "taillights", "tail_light", "tail_lights", "weideng", "尾灯"
    };

    /**
     * The black list. Every entry was taken from a real counterexample, see the class comment:
     * door lamps, roof/window strips, cabin lighting, displays, licence-plate and marker lamps.
     */
    private static final String[] REJECT_WORDS = {
        "always_on", "alwayson", "always", "interior", "door", "roof", "window", "side", "cabin",
        "车内", "门", "顶", "窗", "logo", "display", "lcd", "tv", "screen", "plate", "牌照",
        "reading", "cargo", "under", "step", "handrail", "spot", "marker", "translucent",
        "stablization", "stabilization"
    };

    private VehicleLights() {
    }

    // --- data ------------------------------------------------------------------------------

    /** One group of the model: name, mean origin, stage, and its position inside its car section. */
    public static final class Group {
        public final String name;
        public final String lower;
        public final int count;
        /** Mean origin in model units. */
        public final double along;
        public final double lateral;
        public final double y;
        /** Stage of the highest-priority {@code parts[]} entry that declares this group. */
        public String stage = "?";
        public String condition = "?";
        /** Car section this group physically belongs to, 0-based. */
        public int section;
        /** Position inside its own section, -1 = one end face, +1 = the other. */
        public double s;
        /** Blocks from the nearer end face of its own section. */
        public double faceDist;
        /**
         * Lateral positions of the lamps of this group at the chosen face, model units, one entry
         * per lamp unit (mirrored pair = two entries; a second lamp on the same side = another
         * entry). Never averaged: the mirror pair has to reach the renderer as two lamps.
         */
        public double[] lamps = new double[0];

        Group(final String name, final int count, final double along, final double lateral,
              final double y) {
            this.name = name;
            this.lower = name.toLowerCase(Locale.ROOT);
            this.count = count;
            this.along = along;
            this.lateral = lateral;
            this.y = y;
        }
    }

    /** The chosen lamp for one end of a car. */
    public static final class Slot {
        /** Group name, or {@code (default)} for the estimated position. */
        public final String group;
        /** 2 = name + geometry, 3 = name only (geometry relaxed), 4 = stage+face geometry, 5 = estimated default. */
        public final int level;
        public final String how;
        /**
         * Lateral offsets in blocks, model +lateral: <b>one entry per lamp</b>. A mirrored pair is
         * two entries, so a car with a lamp on each side gets two beams; a single centre lamp gets
         * one. Never a mean of the two sides (a mean is what used to put one beam in the middle).
         */
        public final double[] lateral;
        /** Lamp height above the car's reference point, blocks. */
        public final double height;
        /** How far the lamp sits behind the end face, blocks (>= 0). */
        public final double inset;
        /** Distance of the chosen group from the nearer end face, blocks (diagnostic). */
        public final double faceDist;

        Slot(final String group, final int level, final String how, final double[] lateral,
             final double height, final double inset, final double faceDist) {
            this.group = group;
            this.level = level;
            this.how = how;
            this.lateral = lateral;
            this.height = height;
            this.inset = inset;
            this.faceDist = faceDist;
        }

        public boolean isDefault() {
            return level >= 5;
        }

        @Override
        public String toString() {
            return group + "(L" + level + "," + how
                    + (lateral.length > 1 ? ",x" + lateral.length : "") + ")";
        }
    }

    /** Parsed view of one model. Immutable once {@link #parse} returns it. */
    public static final class Layout {
        public final Map<String, Group> groups = new LinkedHashMap<>();
        public String lengthAxis = "Z";
        /** Cars packed into this one bbmodel, from span / {@code length}. */
        public int sections = 1;
        public double sectionLen = 1.0D;
        public double length = 0.0D;
        public double width = 0.0D;
        /**
         * Lateral centreline of the car body, in model units: the <b>median</b> of every part's
         * lateral position, not the bounding-box midpoint. A bbmodel's bbox is routinely lopsided
         * (a mirror, an open door leaf, a whole second car), and a midpoint 3 blocks off the body
         * pushes both lamps of a pair to the same side - which then deduplicates to one beam.
         */
        public double latCentre = 0.0D;
        /** Largest |lateral - latCentre| over all parts: how wide the car really is. */
        public double halfWidth = 0.0D;
        public double minY = 0.0D;
        public double maxY = 0.0D;
        public int frontSign = 1;
        public Slot head;
        public Slot tail;
        /** Empty when the model parsed cleanly; the reason otherwise (never thrown upwards). */
        public String error = "";

        /** {@code lights: model=... slots=N head=K tail=M source=name|default} */
        public String diagnostic(final String modelId) {
            final String source = isDefault(head) && isDefault(tail) ? "default" : "name";
            return "lights: model=" + modelId
                    + " slots=" + groups.size()
                    + " head=" + head
                    + " tail=" + tail
                    + " source=" + source;
        }

        private static boolean isDefault(final Slot slot) {
            return slot == null || slot.isDefault();
        }
    }

    // --- model plumbing --------------------------------------------------------------------

    /** Reads the model behind a train, or null when the train does not use a dynamic model. */
    public static DynamicTrainModel modelOf(final TrainClient train) {
        try {
            if (train == null || train.trainRenderer == null) {
                return null;
            }
            if (!(train.trainRenderer instanceof JonModelTrainRenderer renderer)) {
                return null;
            }
            final ModelTrainBase model = renderer.model;
            return model instanceof DynamicTrainModel dynamic ? dynamic : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Cached layout of a train's model, or null when it has none. */
    public static Layout layoutFor(final TrainClient train) {
        final DynamicTrainModel model = modelOf(train);
        if (model != null) {
            return layoutFor(model, train == null ? 0 : train.spacing);
        }
        // Built-in trains have no DynamicTrainModel: their lamp boxes are hardcoded in the
        // generated table (null when the vehicle is unknown / has no lamp geometry). Only the
        // Jon renderer can be a built-in model; a scripted/custom renderer with the same id must
        // not inherit built-in geometry.
        if (train != null && train.trainRenderer instanceof JonModelTrainRenderer) {
            return BuiltinLights.layoutFor(train);
        }
        return null;
    }

    /** Cached layout of a model. Bounded LRU on model identity - a re-synced client cannot grow it. */
    public static Layout layoutFor(final DynamicTrainModel model) {
        return layoutFor(model, 0);
    }

    /**
     * @param spacing MTR's own car pitch for this model, in blocks ({@code Train.spacing}). Used as
     *                the car length when the model JSON does not carry a usable {@code length};
     *                both are only consulted to split the model into car sections.
     */
    public static Layout layoutFor(final DynamicTrainModel model, final int spacing) {
        if (model == null) {
            return null;
        }
        Layout layout = CACHE.get(model);
        if (layout == null) {
            layout = parse(model, spacing);
            CACHE.put(model, layout);
        }
        return layout;
    }

    /**
     * Parses one model. Never throws: a model that cannot be read comes back with the estimated
     * slots, because this runs inside the frame loop and a broken pack must not break rendering.
     */
    public static Layout parse(final DynamicTrainModel model) {
        return parse(model, 0);
    }

    public static Layout parse(final DynamicTrainModel model, final int spacing) {
        final Layout out = new Layout();
        try {
            readParts(model, out, spacing);
        } catch (Throwable t) {
            out.error = "partsInfo: " + t;
            out.head = defaultSlot(out, true);
            out.tail = defaultSlot(out, false);
            return out;
        }
        try {
            readStages(model, out);
        } catch (Throwable t) {
            out.error = "properties: " + t;
        }
        try {
            analyse(out);
        } catch (Throwable t) {
            out.error = out.error + " analyse: " + t;
            out.head = defaultSlot(out, true);
            out.tail = defaultSlot(out, false);
        }
        if (out.head == null) {
            out.head = defaultSlot(out, true);
        }
        if (out.tail == null) {
            out.tail = defaultSlot(out, false);
        }
        return out;
    }

    private static final Map<DynamicTrainModel, Layout> CACHE =
            new LinkedHashMap<DynamicTrainModel, Layout>(32, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<DynamicTrainModel, Layout> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    // --- parsing ---------------------------------------------------------------------------

    /**
     * PartInfo's fields are private and its class is package-private: reflection is the only way in.
     *
     * <p>{@code origin} is the part's <b>pivot</b>, and bbmodel exporters frequently share one pivot
     * (the model root) between dozens of cubes - so the pivot alone is not a position. The cube's
     * own centre is {@code origin + offset}, and that is what this class uses. {@code height} is
     * the cube's vertical size, used to widen the model's Y extent so the "is it on the roof" test
     * is not measured against a bbox of pivots.
     */
    private static volatile Field fOriginX;
    private static volatile Field fOriginY;
    private static volatile Field fOriginZ;
    private static volatile Field fOffsetX;
    private static volatile Field fOffsetY;
    private static volatile Field fOffsetZ;
    private static volatile Field fHeight;

    private static void resolvePartFields(final Class<?> type) throws Exception {
        if (fOriginX != null) {
            return;
        }
        final Field ox = type.getDeclaredField("originX");
        final Field oy = type.getDeclaredField("originY");
        final Field oz = type.getDeclaredField("originZ");
        final Field dx = type.getDeclaredField("offsetX");
        final Field dy = type.getDeclaredField("offsetY");
        final Field dz = type.getDeclaredField("offsetZ");
        final Field h = type.getDeclaredField("height");
        ox.setAccessible(true);
        oy.setAccessible(true);
        oz.setAccessible(true);
        dx.setAccessible(true);
        dy.setAccessible(true);
        dz.setAccessible(true);
        h.setAccessible(true);
        fOriginX = ox;
        fOriginY = oy;
        fOriginZ = oz;
        fOffsetX = dx;
        fOffsetY = dy;
        fOffsetZ = dz;
        fHeight = h;
    }

    private static void readParts(final DynamicTrainModel model, final Layout out,
                                  final int spacing) throws Exception {
        final Map<String, ?> parts = model.partsInfo;
        if (parts == null) {
            throw new IllegalStateException("partsInfo is null");
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        /*
         * A group holds one PartInfo per placement, not one per cube: MTR instantiates the part
         * once for every entry of its "positions" array (a bbmodel can carry a whole consist, and
         * a lamp pair is two placements). Averaging the set therefore blends separate cars - the
         * exact mistake this class exists to avoid. So the instances are kept and resolved to ONE
         * car section further down, after the section length is known.
         */
        final Map<String, List<double[]>> instances = new LinkedHashMap<>();
        for (final Map.Entry<String, ?> entry : parts.entrySet()) {
            final Object raw = entry.getValue();
            if (!(raw instanceof Iterable<?> infos)) {
                continue;
            }
            List<double[]> list = instances.get(entry.getKey());
            for (final Object info : infos) {
                if (info == null) {
                    continue;
                }
                resolvePartFields(info.getClass());
                final double x = fOriginX.getDouble(info) + fOffsetX.getDouble(info);
                final double y = fOriginY.getDouble(info) + fOffsetY.getDouble(info);
                final double z = fOriginZ.getDouble(info) + fOffsetZ.getDouble(info);
                final double h = fHeight.getFloat(info);
                if (list == null) {
                    list = new ArrayList<>();
                    instances.put(entry.getKey(), list);
                }
                list.add(new double[]{x, y, z});
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y - Math.abs(h) * 0.5D);
                maxY = Math.max(maxY, y + Math.abs(h) * 0.5D);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        }
        if (instances.isEmpty()) {
            out.minY = 0.0D;
            out.maxY = 0.0D;
            return;
        }
        final double spanX = maxX - minX;
        final double spanZ = maxZ - minZ;
        final boolean z = spanZ >= spanX;
        out.lengthAxis = z ? "Z" : "X";
        out.minY = minY;
        out.maxY = maxY;
        /*
         * Centreline = MEDIAN of the per-group lateral means, not the bbox midpoint and not a
         * per-cube median. Measured over the packs: models carry mirrors, open door leaves and
         * sometimes a second car, so the x/z midpoint is routinely a block or more off the body;
         * a per-cube median skews the other way (one dense group of panels outvotes everything).
         * One vote per group is the body itself, and a wrong centreline is not cosmetic: with the
         * midpoint 3 blocks out, both lamps of a pair land on the same side, deduplicate into one
         * beam, and that is the "only one side is lit" report.
         */
        final List<Double> groupLaterals = new ArrayList<>();
        double half = 0.0D;
        for (final List<double[]> list : instances.values()) {
            double sum = 0.0D;
            for (final double[] in : list) {
                sum += z ? in[0] : in[2];
            }
            groupLaterals.add(list.isEmpty() ? 0.0D : sum / list.size());
        }
        out.latCentre = median(groupLaterals);
        for (final List<double[]> list : instances.values()) {
            for (final double[] in : list) {
                half = Math.max(half, Math.abs((z ? in[0] : in[2]) - out.latCentre));
            }
        }
        out.halfWidth = half;
        out.length = num(model.properties, "length", 0.0D);
        out.width = num(model.properties, "width", 0.0D);

        final double span = Math.max(1.0E-6D, z ? spanZ : spanX);
        final double uMin = z ? minZ : minX;
        // Car length: the model's own "length" is authoritative (it is what the renderer uses),
        // MTR's spacing is the fallback and only when it is plausible against the model size.
        double carLen = out.length;
        if (carLen <= 0.5D && spacing >= 3 && spacing <= span * 1.25D) {
            carLen = spacing;
        }
        int sections = carLen > 0.5D ? (int) Math.round(span / carLen) : 1;
        if (sections < 1) {
            sections = 1;
        }
        if (sections > MAX_SECTIONS) {
            sections = MAX_SECTIONS;
        }
        out.sections = sections;
        out.sectionLen = span / sections;

        final int[] count = new int[MAX_SECTIONS];
        final double[] farthest = new double[MAX_SECTIONS];
        for (final Map.Entry<String, List<double[]>> entry : instances.entrySet()) {
            final List<double[]> list = entry.getValue();
            java.util.Arrays.fill(count, 0);
            java.util.Arrays.fill(farthest, 0.0D);
            for (final double[] in : list) {
                final int sec = sectionOf(z ? in[2] : in[0], uMin, out.sectionLen, sections);
                count[sec]++;
                final double s = signed(alongOf(in, z), uMin, sec, out.sectionLen);
                farthest[sec] = Math.max(farthest[sec], Math.abs(s));
            }
            // The richest section wins (a lamp pair is two placements in one car); ties - the
            // usual case for a single cube per car - go to the one closest to a car face, i.e.
            // the copy that is actually a lamp rather than a mid-consist duplicate.
            int best = 0;
            for (int s = 1; s < sections; s++) {
                if (count[s] > count[best]
                        || (count[s] == count[best] && farthest[s] > farthest[best])) {
                    best = s;
                }
            }
            // Within the chosen car: anchor on the placement farthest from the car's centre - that
            // is the lamp on the face, not a twin that sits mid-body. Then take EVERY placement of
            // this group on that face: the mirrored partner of the pair and every cube of both
            // lamps. Their laterals are kept (clustered, never averaged) because the pair has to
            // survive to the renderer as two separate lamps.
            double anchorAlong = 0.0D;
            double bestAbs = -1.0D;
            for (final double[] in : list) {
                final double a = alongOf(in, z);
                if (sectionOf(a, uMin, out.sectionLen, sections) != best) {
                    continue;
                }
                final double abs = Math.abs(signed(a, uMin, best, out.sectionLen));
                if (abs > bestAbs) {
                    bestAbs = abs;
                    anchorAlong = a;
                }
            }
            final List<Double> onFace = new ArrayList<>();
            final List<Double> heights = new ArrayList<>();
            for (final double[] in : list) {
                final double a = alongOf(in, z);
                if (sectionOf(a, uMin, out.sectionLen, sections) != best
                        || Math.abs(a - anchorAlong) > LAMP_ALONG_TOL) {
                    continue;
                }
                onFace.add(z ? in[0] : in[2]);
                heights.add(in[1]);
            }
            if (onFace.isEmpty()) {
                continue;
            }
            final double[] lamps = clusters(onFace);
            final Group g = new Group(entry.getKey(), onFace.size(), anchorAlong,
                    mean(onFace), mean(heights));
            g.lamps = lamps;
            g.section = best;
            g.s = signed(anchorAlong, uMin, best, out.sectionLen);
            g.faceDist = (1.0D - Math.min(1.0D, Math.abs(g.s))) * out.sectionLen * 0.5D;
            out.groups.put(entry.getKey(), g);
        }
    }

    private static double alongOf(final double[] in, final boolean z) {
        return z ? in[2] : in[0];
    }

    private static int sectionOf(final double along, final double uMin, final double sectionLen,
                                 final int sections) {
        int sec = (int) Math.floor((along - uMin) / sectionLen);
        if (sec < 0) {
            return 0;
        }
        return Math.min(sec, sections - 1);
    }

    private static double signed(final double along, final double uMin, final int section,
                                 final double sectionLen) {
        final double local = (along - uMin) - (double) section * sectionLen;
        return 2.0D * local / sectionLen - 1.0D;
    }

    private static void readStages(final DynamicTrainModel model, final Layout out) {
        final JsonObject props = model.properties;
        if (props == null) {
            return;
        }
        final JsonElement partsEl = props.get("parts");
        if (partsEl == null || !partsEl.isJsonArray()) {
            return;
        }
        for (final JsonElement el : partsEl.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            final JsonObject part = el.getAsJsonObject();
            if (!part.has("name")) {
                continue;
            }
            final String name = str(part, "name");
            final Group group = out.groups.get(name);
            if (group == null) {
                continue;
            }
            final String stage = str(part, "stage");
            // Highest priority declaration wins: a group listed once as EXTERIOR and once as
            // ALWAYS_ON_LIGHTS is a lit lamp, not a shell.
            if (stageRank(stage) > stageRank(group.stage)) {
                group.stage = stage;
                group.condition = str(part, "render_condition");
            }
        }
    }

    private static int stageRank(final String stage) {
        if ("ALWAYS_ON_LIGHTS".equals(stage)) {
            return 4;
        }
        if ("LIGHTS".equals(stage)) {
            return 3;
        }
        if ("INTERIOR".equals(stage)) {
            return 1;
        }
        if ("?".equals(stage)) {
            return 2;
        }
        return 2;                                  // EXTERIOR and anything unknown
    }

    private static void analyse(final Layout out) {
        if (out.groups.isEmpty()) {
            out.head = defaultSlot(out, true);
            out.tail = defaultSlot(out, false);
            return;
        }
        // Which end of a car is the front? Vote with the groups the packs themselves call "head...".
        double votes = 0.0D;
        for (final Group g : out.groups.values()) {
            if (containsAny(g.lower, HEAD_WORDS) && !isRejected(g.lower) && !isRoof(out, g)) {
                votes += Math.signum(g.s);
            }
        }
        out.frontSign = votes < 0.0D ? -1 : 1;
        out.head = pick(out, true);
        out.tail = pick(out, false);
    }

    /**
     * The cascade, in the documented priority order:
     * <ol>
     *   <li>2 - group <b>name</b> + geometry: a head/tail-named group inside the end band;</li>
     *   <li>3 - group name only: the name is a lamp but it sits further from the face (recess);</li>
     *   <li>4 - <b>stage + end-face geometry</b>: no name, but a mirrored lamp pair declared as
     *       light geometry at the wanted end;</li>
     *   <li>5 - estimated from the car size (a symmetric pair).</li>
     * </ol>
     * The stage never filters a named group: a head/tail group declared INTERIOR still wins over the
     * default position, only with less weight than an ALWAYS_ON_LIGHTS one.
     */
    private static Slot pick(final Layout out, final boolean head) {
        final String[] words = head ? HEAD_WORDS : TAIL_WORDS;
        final double want = head ? out.frontSign : -out.frontSign;
        final double endBand = Math.max(END_MIN_BLOCKS, END_FRACTION * out.sectionLen);

        Group best = null;
        double bestScore = -Double.MAX_VALUE;
        Group relaxed = null;
        double relaxedScore = -Double.MAX_VALUE;
        for (final Group g : out.groups.values()) {
            if (!containsAny(g.lower, words) || isRejected(g.lower)) {
                continue;
            }
            if (isRoof(out, g)) {
                continue;                          // a strip on the roof, not a lamp
            }
            final double score = stageRank(g.stage) * 10.0D
                    + (Math.signum(g.s) == want ? 12.0D : 0.0D)
                    + Math.max(0.0D, 20.0D * (1.0D - g.faceDist / endBand))
                    + Math.min(4, g.count);
            if (g.faceDist <= endBand) {
                if (score > bestScore) {
                    bestScore = score;
                    best = g;
                }
            } else if (score > relaxedScore) {
                relaxedScore = score;
                relaxed = g;
            }
        }
        if (best != null) {
            return namedSlot(out, head, best, 2, "name+geo");
        }
        if (relaxed != null) {
            return namedSlot(out, head, relaxed, 3, "name");
        }
        return geoSlot(out, head);
    }

    /**
     * Stage + end-face geometry with no name match at all: a <b>mirrored pair</b> that is declared as
     * light geometry ({@code LIGHTS} / {@code ALWAYS_ON_LIGHTS}) at the wanted end. It exists for
     * packs whose lamp groups are named in a convention the tables do not know. A single unnamed
     * lamp is rejected in favour of the estimated pair - one beam on one side reads as a bug.
     *
     * <p>A model whose lamps all sit at one end cannot say which end is the front (the {@code
     * frontSign} vote needs a name). For the <b>head</b> end the other end is therefore tried as
     * well - a mirrored pair at some end is a head light. The tail slot deliberately does not do
     * this, so it can never steal the head lamp.
     */
    private static Slot geoSlot(final Layout out, final boolean head) {
        final double want = head ? out.frontSign : -out.frontSign;
        Slot slot = geoAtEnd(out, head, want);
        if (slot == null && head) {
            slot = geoAtEnd(out, head, -want);
        }
        return slot == null ? defaultSlot(out, head) : slot;
    }

    /** The mirrored-pair slot at the given end, or null when that end has no pair. */
    private static Slot geoAtEnd(final Layout out, final boolean head, final double want) {
        final double endBand = Math.max(END_MIN_BLOCKS, END_FRACTION * out.sectionLen);
        Group anchor = null;
        double bestFace = Double.MAX_VALUE;
        for (final Group g : out.groups.values()) {
            if (stageRank(g.stage) < 3 || Math.signum(g.s) != want) {
                continue;
            }
            if (isRejected(g.lower) || isRoof(out, g) || g.faceDist > endBand) {
                continue;
            }
            if (g.y < 0.3D || g.y > 3.4D || g.lamps.length == 0) {
                continue;                          // a roof strip or a floor lamp, not a head light
            }
            if (g.faceDist < bestFace) {
                bestFace = g.faceDist;
                anchor = g;
            }
        }
        if (anchor == null) {
            return null;
        }
        final Slot slot = namedSlot(out, head, anchor, 4, "geo");
        return slot.lateral.length >= 2 ? slot : null;
    }

    private static Slot namedSlot(final Layout out, final boolean head, final Group g,
                                  final int level, final String how) {
        final double width = out.width > 0.6D ? out.width : DEFAULT_WIDTH;
        /*
         * A lamp cannot sit outside the car, but it CAN sit further out than 0.45 x the JSON
         * "width" - real head lamps on a 2.9 block bus are at +/-1.05, and the declared width is
         * often 2. So the clamp is the larger of the declared-share limit and the body's measured
         * half-width (bounded, so a stray cube cannot open it up).
         */
        final double limit = Math.max(LATERAL_CLAMP_FRACTION * width,
                Math.min(out.halfWidth + 0.2D, MAX_LATERAL_BLOCKS));
        // The lamp may be recessed (sloped nose) - start the beam there, but never more than
        // MAX_INSET behind the face: past that the "lamp" is a mid-body part and the beam would
        // start inside the passenger compartment.
        final double inset = Math.min(MAX_INSET, Math.max(0.0D, g.faceDist));
        final double height = clamp(g.y, 0.35D, 3.2D);
        return new Slot(g.name, level, how, faceLamps(out, head, g, limit), height, inset,
                g.faceDist);
    }

    /**
     * Every lamp on the anchor's end face, as offsets from the centreline in blocks.
     *
     * <p>Starts with the anchor group's own lamp units and then absorbs other groups that sit on
     * <b>the same face</b> ({@link #FACE_MATCH_BLOCKS}), in the <b>same stage</b>, at the
     * <b>same end</b>, when they are either named after that end or are the lateral
     * <b>mirror</b> of a lamp already taken ({@link #MIRROR_TOL}). This is what turns
     * {@code headlight_left} + {@code headlight_right}, or the two placements of one
     * {@code headlights} group, into a real left/right pair - the old code bound only the
     * best-scoring group (one side) or averaged the two placements (one beam in the middle).
     *
     * <p>The result is per lamp, deduplicated at {@link #LAMP_DUPLICATE_TOL} and capped at
     * {@value #MAX_LAMPS}, outermost first, so a full-width light bar cannot become a fan.
     */
    private static double[] faceLamps(final Layout out, final boolean head, final Group anchor,
                                      final double limit) {
        final String[] words = head ? HEAD_WORDS : TAIL_WORDS;
        final double want = Math.signum(anchor.s);
        final List<Double> raw = new ArrayList<>();
        for (final double lamp : anchor.lamps) {
            raw.add(lamp);
        }
        for (final Group g : out.groups.values()) {
            if (g == anchor || stageRank(g.stage) != stageRank(anchor.stage)) {
                continue;
            }
            if (Math.signum(g.s) != want || Math.abs(g.faceDist - anchor.faceDist) > FACE_MATCH_BLOCKS) {
                continue;
            }
            if (isRejected(g.lower) || isRoof(out, g)) {
                continue;
            }
            if (!containsAny(g.lower, words) && !mirrors(g, raw, out.latCentre)) {
                continue;
            }
            for (final double lamp : g.lamps) {
                raw.add(lamp);
            }
        }
        final List<Double> out2 = new ArrayList<>(raw.size());
        for (final double lamp : raw) {
            double rel = clamp(lamp - out.latCentre, -limit, limit);
            if (Math.abs(rel) < LATERAL_DEAD_ZONE) {
                rel = 0.0D;
            }
            boolean duplicate = false;
            for (final double other : out2) {
                if (Math.abs(other - rel) <= LAMP_DUPLICATE_TOL) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out2.add(rel);
            }
        }
        if (out2.isEmpty()) {
            return new double[]{0.0D};
        }
        java.util.Collections.sort(out2);
        /*
         * Lamp-track sanity: when there are more than two, the pair that is most symmetric about
         * the centreline defines the track width, and anything much further out than that pair is
         * not a lamp (a mirror or a marker that happened to be grouped with the head lights). It
         * is dropped instead of turning into a cone half a meter off the car's side.
         */
        if (out2.size() > 2) {
            double pairTrack = 0.0D;
            double bestSum = Double.MAX_VALUE;
            for (int i = 0; i < out2.size(); i++) {
                for (int j = i + 1; j < out2.size(); j++) {
                    final double sum = Math.abs(out2.get(i) + out2.get(j));
                    if (sum < bestSum) {
                        bestSum = sum;
                        pairTrack = Math.max(Math.abs(out2.get(i)), Math.abs(out2.get(j)));
                    }
                }
            }
            final double keep = pairTrack * LAMP_TRACK_SLACK + 0.1D;
            out2.removeIf(value -> Math.abs(value) > keep);
        }
        final double[] result = new double[Math.min(out2.size(), MAX_LAMPS)];
        if (out2.size() <= MAX_LAMPS) {
            for (int i = 0; i < result.length; i++) {
                result[i] = out2.get(i);
            }
        } else {
            // Too many: keep the outermost of each side, which is where a car's lamps are.
            final int half = MAX_LAMPS / 2;
            for (int i = 0; i < half; i++) {
                result[i] = out2.get(i);
            }
            for (int i = 0; i < MAX_LAMPS - half; i++) {
                result[half + i] = out2.get(out2.size() - (MAX_LAMPS - half) + i);
            }
            java.util.Arrays.sort(result);
        }
        return result;
    }

    /** True when one of {@code g}'s lamps mirrors a lamp already taken (offset sums to ~0). */
    private static boolean mirrors(final Group g, final List<Double> taken, final double centre) {
        for (final double lamp : g.lamps) {
            final double rel = lamp - centre;
            for (final double other : taken) {
                if (Math.abs(rel + (other - centre)) <= MIRROR_TOL) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Slot defaultSlot(final Layout out, final boolean head) {
        final double width = out.width > 0.6D ? out.width : DEFAULT_WIDTH;
        final double half = DEFAULT_LATERAL_FRACTION * width;
        return new Slot("(default)", 5, "default",
                new double[]{-half, half}, DEFAULT_HEIGHT, 0.0D, 0.0D);
    }

    /** True when the group sits above {@value #ROOF_FRACTION} of the model's height. */
    private static boolean isRoof(final Layout out, final Group g) {
        final double span = out.maxY - out.minY;
        if (span <= 0.5D) {
            return false;
        }
        return (g.y - out.minY) / span > ROOF_FRACTION;
    }

    private static boolean isRejected(final String lower) {
        return containsAny(lower, REJECT_WORDS) || lower.endsWith("_off") || lower.endsWith("_closed");
    }

    private static boolean containsAny(final String haystack, final String[] needles) {
        for (final String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(final double value, final double min, final double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Median of a list of positions (0 when empty) - robust against a lopsided model bbox. */
    private static double median(final List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        final double[] sorted = new double[values.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = values.get(i);
        }
        java.util.Arrays.sort(sorted);
        final int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) * 0.5D;
    }

    private static double mean(final List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (final double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    /**
     * One entry per <b>lamp</b>: the laterals are sorted and greedily grouped with
     * {@link #LAMP_CLUSTER_TOL}, each group collapsing to its mean. A lamp built from several cubes
     * (their centres differ by centimetres) therefore yields one number, while the two sides of a
     * car yield two - which is the entire point: a mean of the pair is a beam in the middle.
     */
    private static double[] clusters(final List<Double> values) {
        if (values.isEmpty()) {
            return new double[0];
        }
        final double[] sorted = new double[values.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = values.get(i);
        }
        java.util.Arrays.sort(sorted);
        final List<Double> out = new ArrayList<>();
        double sum = sorted[0];
        double last = sorted[0];
        int n = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] - last <= LAMP_CLUSTER_TOL) {
                sum += sorted[i];
                n++;
            } else {
                out.add(sum / n);
                sum = sorted[i];
                n = 1;
            }
            last = sorted[i];
        }
        out.add(sum / n);
        final double[] result = new double[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "?" : e.getAsString();
    }

    private static double num(final JsonObject o, final String key, final double fallback) {
        try {
            final JsonElement e = o == null ? null : o.get(key);
            return e == null || e.isJsonNull() ? fallback : e.getAsDouble();
        } catch (Throwable t) {
            return fallback;
        }
    }

    // --- diagnostics -----------------------------------------------------------------------

    /** Model ids already printed; bounded so a long session cannot grow it without limit. */
    private static final Map<String, Boolean> ANNOUNCED =
            new LinkedHashMap<String, Boolean>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest) {
                    return size() > 512;
                }
            };

    /**
     * Prints {@code lights: model=<id> slots=N head=K tail=M source=name|default} once per model.
     *
     * <p>Called from {@link LightController} for trains that are actually being lit this tick, so
     * the line describes what the player sees rather than a catalogue of every pack installed.
     */
    public static void announce(final TrainClient train) {
        try {
            if (train == null) {
                return;
            }
            final String id = train.baseTrainType == null ? "?" : train.baseTrainType;
            if (ANNOUNCED.containsKey(id)) {
                return;
            }
            final Layout layout = layoutFor(train);
            if (layout == null) {
                return;
            }
            ANNOUNCED.put(id, Boolean.TRUE);
            MtrHeadlightClient.LOGGER.info("[lte] {}", layout.diagnostic(id));
            if (!layout.error.isEmpty()) {
                MtrHeadlightClient.LOGGER.warn("[lte] lights: {} parse note: {}", id, layout.error);
            }
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] lights: announce failed", t);
        }
    }

    /**
     * Number of lamp slots resolved for this train (0-2: one for the head end, one for the tail
     * end). Used by the 5-second debug line in {@link DebugProbe}: {@code slots=0} on every train
     * is the "灯位解析不出来" half of the failure, as opposed to {@code slots>0} with
     * {@code beams=0}, which is "解析出来了但没画".
     */
    public static int slotCount(final TrainClient train) {
        try {
            final Layout layout = layoutFor(train);
            if (layout == null) {
                return 0;
            }
            return (layout.head == null ? 0 : 1) + (layout.tail == null ? 0 : 1);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * One-line model description for the debug probe, in the same shape as
     * {@link Layout#diagnostic} but never null and never throwing - the three cases that matter
     * are "no model at all", "model but nothing parsed" and a normal binding.
     */
    public static String debugLine(final TrainClient train) {
        if (train == null) {
            return "lights: model=? slots=0 source=no-train";
        }
        final String id = train.baseTrainType == null ? "?" : train.baseTrainType;
        try {
            final Layout layout = layoutFor(train);
            return layout == null
                    ? "lights: model=" + id + " slots=0 head=null tail=null source=no-model"
                    : layout.diagnostic(id);
        } catch (Throwable t) {
            return "lights: model=" + id + " slots=0 source=error("
                    + t.getClass().getSimpleName() + ")";
        }
    }

    /** Drops every cached layout (world change / reload). */
    public static void clearCache() {
        CACHE.clear();
        ANNOUNCED.clear();
    }
}
