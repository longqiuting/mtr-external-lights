package dev.js.mtrheadlight;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.data.TrainClient;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hardcoded head/tail lamp slots for MTR's <b>built-in</b> train models.
 *
 * <p>Built-in trains ({@code TrainClientRegistry}'s 114 {@code TrainType}s) are hand-written
 * {@code ModelTrainBase} classes, not resource-pack {@code DynamicTrainModel}s: they expose no
 * {@code partsInfo} maps and no group-name JSON, so {@link VehicleLights}' name/stage heuristic
 * cannot see them at all. Their lamp geometry is nevertheless real - the decompiled models build
 * dedicated {@code headlights} / {@code tail_lights} {@code ModelMapper}s - so this class carries
 * the extracted box positions instead of guessing at run time.
 *
 * <p>{@code builtin_lights.json} is generated offline from the MTR jar (see
 * {@code tools/extract-builtin-lights.js} and {@code docs/builtin-light-table.md}). Each entry is
 * {@code {"head":[{x,y,z,w,h}...],"tail":[...]}} in <b>car-local model space</b>: x lateral,
 * y up from the car reference point (the same convention as {@code VehicleLights.Group.y}), z
 * along the car (negative = front end), and w/h the lamp box size, all in blocks. The list is one
 * entry per physical lamp, so a left/right pair stays two lamps.
 *
 * <p>The table is looked up by MTR's {@code trainId} (for example {@code a_train_tcl}); models
 * that are not in it fall through to the normal heuristic path (which, for built-in trains, means
 * "no beams" - they have no dynamic model to analyse). The remaining uncertain models are listed
 * in the report for hand configuration.
 */
public final class BuiltinLights {

    private static final String RESOURCE = "/assets/mtrlte/builtin_lights.json";

    /** How far behind the end face a lamp may sit before the inset is clamped, blocks. */
    private static final double MAX_INSET = 1.5D;

    /** One lamp box, car-local model space, blocks. */
    public static final class Lamp {
        public final double x;
        public final double y;
        public final double z;
        public final double w;
        public final double h;

        Lamp(final double x, final double y, final double z, final double w, final double h) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
        }
    }

    /** Both ends of one vehicle id. Immutable. */
    public static final class Entry {
        public final Lamp[] head;
        public final Lamp[] tail;

        Entry(final Lamp[] head, final Lamp[] tail) {
            this.head = head;
            this.tail = tail;
        }

        public boolean usable() {
            return head.length > 0 && tail.length > 0;
        }
    }

    private static volatile Map<String, Entry> table;
    private static final Map<String, VehicleLights.Layout> CACHE = new ConcurrentHashMap<>();

    private BuiltinLights() {
    }

    /** True when the table has a usable entry for this vehicle id. */
    public static boolean has(final String id) {
        return entry(id) != null;
    }

    /** Number of vehicles in the table, or 0 when the resource is missing. */
    public static int size() {
        return load().size();
    }

    /**
     * Layout for a built-in train, or null when the id is not in the table (unknown / vanilla
     * model). The layout carries only the two slots; there are no analysable groups.
     */
    public static VehicleLights.Layout layoutFor(final TrainClient train) {
        if (train == null) {
            return null;
        }
        final String id = idOf(train);
        if (id.isEmpty()) {
            return null;
        }
        final Entry e = entry(id);
        if (e == null) {
            return null;
        }
        final int spacing = Math.max(1, train.spacing);
        final int width = Math.max(1, train.width);
        final String key = id + '|' + spacing + '|' + width;
        VehicleLights.Layout cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        final double face = spacing * 0.5D;
        final VehicleLights.Slot head = slot("headlights", e.head, face);
        final VehicleLights.Slot tail = slot("tail_lights", e.tail, face);
        if (head == null || tail == null) {
            return null;
        }
        final VehicleLights.Layout out = new VehicleLights.Layout();
        out.lengthAxis = "Z";
        out.sections = 1;
        out.sectionLen = spacing;
        out.length = spacing;
        out.width = width;
        out.latCentre = 0.0D;
        out.minY = 0.0D;
        out.maxY = 0.0D;
        out.frontSign = -1;                        // 内置表里 z 负 = 车头（见 docs/builtin-light-table.md）
        out.head = head;
        out.tail = tail;
        CACHE.put(key, out);
        return out;
    }

    private static VehicleLights.Slot slot(final String group, final Lamp[] lamps, final double face) {
        if (lamps == null || lamps.length == 0) {
            return null;
        }
        final double[] lateral = new double[lamps.length];
        double sy = 0.0D;
        double sz = 0.0D;
        for (int i = 0; i < lamps.length; i++) {
            lateral[i] = lamps[i].x;
            sy += lamps[i].y;
            sz += Math.abs(lamps[i].z);
        }
        final double height = sy / lamps.length;
        final double zMean = sz / lamps.length;
        final double inset = clamp(face - zMean, 0.0D, MAX_INSET);
        final double faceDist = Math.max(0.0D, face - zMean);
        return new VehicleLights.Slot(group, 1, "builtin", lateral, clamp(height, 0.2D, 3.4D),
                inset, faceDist);
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String idOf(final TrainClient train) {
        String id = "";
        try {
            if (train.trainId != null) {
                id = train.trainId;
            } else if (train.baseTrainType != null) {
                id = train.baseTrainType;
            }
        } catch (Throwable t) {
            return "";
        }
        id = id.trim().toLowerCase(Locale.ROOT);
        final int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        return id;
    }

    private static Entry entry(final String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        final int colon = key.indexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        final Entry e = load().get(key);
        return e != null && e.usable() ? e : null;
    }

    private static Map<String, Entry> load() {
        Map<String, Entry> local = table;
        if (local != null) {
            return local;
        }
        synchronized (BuiltinLights.class) {
            local = table;
            if (local != null) {
                return local;
            }
            local = parse();
            table = local;
            return local;
        }
    }

    private static Map<String, Entry> parse() {
        final Map<String, Entry> out = new LinkedHashMap<>();
        try (InputStream in = BuiltinLights.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                MtrHeadlightClient.LOGGER.warn("[lte] builtin lights: {} missing", RESOURCE);
                return out;
            }
            final JsonElement root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                MtrHeadlightClient.LOGGER.warn("[lte] builtin lights: root is not an object");
                return out;
            }
            for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                final JsonObject v = e.getValue().getAsJsonObject();
                final Lamp[] head = lamps(v, "head");
                final Lamp[] tail = lamps(v, "tail");
                if (head.length > 0 && tail.length > 0) {
                    out.put(e.getKey().toLowerCase(Locale.ROOT), new Entry(head, tail));
                }
            }
            MtrHeadlightClient.LOGGER.info("[lte] builtin lights: {} vehicles", out.size());
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] builtin lights: failed to load", t);
        }
        return out;
    }

    private static Lamp[] lamps(final JsonObject entry, final String key) {
        final JsonElement el = entry.get(key);
        if (el == null || !el.isJsonArray()) {
            return new Lamp[0];
        }
        final JsonArray arr = el.getAsJsonArray();
        final List<Lamp> out = new ArrayList<>(arr.size());
        for (final JsonElement item : arr) {
            if (!item.isJsonObject()) {
                continue;
            }
            final JsonObject o = item.getAsJsonObject();
            out.add(new Lamp(num(o, "x"), num(o, "y"), num(o, "z"), num(o, "w"), num(o, "h")));
        }
        return out.toArray(new Lamp[0]);
    }

    private static double num(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0.0D : e.getAsDouble();
    }
}
