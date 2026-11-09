package dev.js.mtrheadlight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The on-disk format of {@code assets/<namespace>/lte_vehicle_lights.json} - parser, writer and
 * the two helpers that find such files.
 *
 * <h2>The format (ours, not RTE's)</h2>
 * <pre>
 * {
 *   "version": 1,
 *   "vehicles": [
 *     { "id": "mypack_train_*",
 *       "beams": [
 *         { "type": "head", "color": "#FFF3D6", "pos": [0.85, 1.2, 10.8],
 *           "rot": [0, 0, 0], "length": 16, "width": 3, "alpha": 0.16 }
 *       ] }
 *   ]
 * }
 * </pre>
 *
 * <p>{@code id} is a vehicle model id (MTR's {@code baseTrainType}). A trailing {@code *} makes it a
 * prefix wildcard: {@code mypack_train_*} matches every id that starts with {@code mypack_train_}.
 * An exact id always beats a wildcard, and a longer wildcard prefix beats a shorter one.
 *
 * <p>{@code pos} is <b>car-local model space</b>: x lateral, y up, z along the car, with the car
 * centred on the origin and its end faces at {@code +/- length/2}. On a model whose length axis is
 * X the along component is x instead of z - i.e. the numbers are always in the model's own axes,
 * which is what a pack author reads off the bbmodel. Anything omitted is inherited from LTE's
 * automatic light detection (see {@link VehicleLights}), so a file may carry only the fields it
 * wants to change.
 *
 * <h2>Why the parser never throws past a file boundary</h2>
 * These files are user content: a broken one must cost its own beams and nothing else. {@link #parse}
 * returns a {@link Result} that carries either vehicles or one error string, and it never throws.
 */
public final class VehicleLightJson {

    /** File name, looked up at {@code assets/<namespace>/<FILE_NAME>}. */
    public static final String FILE_NAME = "lte_vehicle_lights.json";

    /** Format version this build writes and understands. */
    public static final int FORMAT_VERSION = 1;

    /**
     * Told to the reader of an exported file. Written into every {@code export()} so a player who
     * finds the file in their game directory knows where it goes and what the numbers mean.
     */
    private static final String[] COMMENT = {
        "LTE vehicle light layout - generated file, editable by hand.",
        "Place it at assets/<your_pack>/" + FILE_NAME + " in a resource pack (client) or in a",
        "datapack / config/lte_lights on the server.",
        "id supports a trailing '*' wildcard (prefix match); an exact id wins over a wildcard,",
        "and a longer wildcard prefix wins over a shorter one.",
        "Fields you leave out are filled in from LTE's automatic light detection.",
        "pos is car-local model space: [lateral, height, along] in the model's own axes,",
        "so on a model whose length axis is X the 'along' number is pos[0].",
        "rot is degrees [yaw, pitch, roll]; length/width are blocks; alpha is 0..1."
    };

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private VehicleLightJson() {
    }

    // --- results ---------------------------------------------------------------------------

    /**
     * One parsed file. {@code error} is empty when the file was usable; when it is not empty
     * {@code vehicles} is empty and the caller is expected to count the file as failed and warn
     * once about it.
     */
    public static final class Result {
        public final List<VehicleLightOverrides.Vehicle> vehicles = new ArrayList<>();
        /** Non-fatal problems (a beam with a malformed field that was skipped, ...). */
        public final List<String> notes = new ArrayList<>();
        public String error = "";

        public boolean ok() {
            return error.isEmpty();
        }
    }

    /** One piece of json text and where it came from - the input of {@link VehicleLightOverrides#load}. */
    public static final class Source {
        /** Pack / folder label, shown in diagnostics; later sources win over earlier ones. */
        public final String pack;
        /** Path inside that pack, for the one-warn-per-file bookkeeping. */
        public final String path;
        public final String json;

        public Source(final String pack, final String path, final String json) {
            this.pack = pack == null ? "?" : pack;
            this.path = path == null ? FILE_NAME : path;
            this.json = json == null ? "" : json;
        }

        @Override
        public String toString() {
            return pack + ":" + path;
        }
    }

    // --- parsing ---------------------------------------------------------------------------

    /** Parses one file. Never throws; a structural problem comes back as {@link Result#error}. */
    public static Result parse(final String json, final String label) {
        final Result out = new Result();
        try {
            final JsonElement rootEl = JsonParser.parseString(json);
            if (rootEl == null || !rootEl.isJsonObject()) {
                out.error = "root is not an object";
                return out;
            }
            final JsonObject root = rootEl.getAsJsonObject();
            final JsonElement versionEl = root.get("version");
            if (versionEl != null && versionEl.isJsonPrimitive()) {
                int version = 0;
                try {
                    version = versionEl.getAsInt();
                } catch (NumberFormatException nfe) {
                    out.notes.add("version is not a number");
                }
                if (version > FORMAT_VERSION) {
                    // Forward compatible by design: unknown fields are ignored, so keep going.
                    out.notes.add("version " + version + " is newer than " + FORMAT_VERSION
                            + " - reading it as " + FORMAT_VERSION);
                }
            }
            final JsonElement vehiclesEl = root.get("vehicles");
            if (vehiclesEl == null || !vehiclesEl.isJsonArray()) {
                out.error = "'vehicles' missing or not an array";
                return out;
            }
            int index = 0;
            for (final JsonElement ve : vehiclesEl.getAsJsonArray()) {
                index++;
                final String where = label + " vehicles[" + index + "]";
                if (!ve.isJsonObject()) {
                    out.notes.add(where + ": not an object");
                    continue;
                }
                final VehicleLightOverrides.Vehicle v = parseVehicle(ve.getAsJsonObject(), where, out.notes);
                if (v != null) {
                    out.vehicles.add(v);
                }
            }
        } catch (Throwable t) {
            out.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            out.vehicles.clear();
        }
        return out;
    }

    private static VehicleLightOverrides.Vehicle parseVehicle(final JsonObject o, final String where,
                                                             final List<String> notes) {
        final JsonElement idEl = o.get("id");
        if (idEl == null || !idEl.isJsonPrimitive()) {
            notes.add(where + ": missing 'id' - skipped");
            return null;
        }
        final String id = idEl.getAsString().trim();
        if (id.isEmpty()) {
            notes.add(where + ": empty 'id' - skipped");
            return null;
        }
        final VehicleLightOverrides.Vehicle v = new VehicleLightOverrides.Vehicle(id, "");
        final JsonElement beamsEl = o.get("beams");
        if (beamsEl == null || beamsEl.isJsonNull()) {
            notes.add(where + " (" + id + "): no 'beams' - entry ignored");
            return v;
        }
        if (!beamsEl.isJsonArray()) {
            notes.add(where + " (" + id + "): 'beams' is not an array - entry ignored");
            return v;
        }
        int i = 0;
        for (final JsonElement be : beamsEl.getAsJsonArray()) {
            i++;
            if (!be.isJsonObject()) {
                notes.add(where + " (" + id + ") beams[" + i + "]: not an object");
                continue;
            }
            final VehicleLightOverrides.Beam beam =
                    parseBeam(be.getAsJsonObject(), where + " (" + id + ") beams[" + i + "]", notes);
            if (beam != null) {
                v.beams.add(beam);
            }
        }
        return v;
    }

    private static VehicleLightOverrides.Beam parseBeam(final JsonObject o, final String where,
                                                       final List<String> notes) {
        final VehicleLightOverrides.Beam b = new VehicleLightOverrides.Beam();
        final JsonElement typeEl = o.get("type");
        if (typeEl != null && typeEl.isJsonPrimitive()) {
            final String type = typeEl.getAsString().trim();
            if (!type.isEmpty()) {
                b.type = type.toLowerCase(Locale.ROOT);
            }
        }
        b.color = color(o.get("color"), where, notes);
        b.pos = vec3(o.get("pos"), "pos", where, notes);
        b.rot = vec3(o.get("rot"), "rot", where, notes);
        b.length = positive(o.get("length"), "length", where, notes);
        b.width = positive(o.get("width"), "width", where, notes);
        b.alpha = alpha(o.get("alpha"), where, notes);
        return b;
    }

    private static Integer color(final JsonElement el, final String where, final List<String> notes) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                return el.getAsInt() & 0xFFFFFF;
            }
            final String s = el.getAsString().trim();
            String hex = s.startsWith("#") ? s.substring(1) : s;
            if (hex.startsWith("0x") || hex.startsWith("0X")) {
                hex = hex.substring(2);
            }
            if (hex.length() == 8) {
                hex = hex.substring(2);            // AARRGGBB -> RRGGBB
            }
            if (hex.length() != 6) {
                notes.add(where + ": color \"" + s + "\" is not #RRGGBB - inherited");
                return null;
            }
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (Throwable t) {
            notes.add(where + ": color unreadable - inherited");
            return null;
        }
    }

    private static double[] vec3(final JsonElement el, final String name, final String where,
                                 final List<String> notes) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonArray() || el.getAsJsonArray().size() != 3) {
            notes.add(where + ": '" + name + "' is not a 3-number array - inherited");
            return null;
        }
        final JsonArray a = el.getAsJsonArray();
        final double[] out = new double[3];
        try {
            for (int i = 0; i < 3; i++) {
                out[i] = a.get(i).getAsDouble();
            }
        } catch (Throwable t) {
            notes.add(where + ": '" + name + "' holds a non-number - inherited");
            return null;
        }
        return out;
    }

    private static Double positive(final JsonElement el, final String name, final String where,
                                   final List<String> notes) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            final double d = el.getAsDouble();
            if (d > 0.0D && !Double.isInfinite(d) && !Double.isNaN(d)) {
                return d;
            }
            notes.add(where + ": '" + name + "' must be > 0 - inherited");
        } catch (Throwable t) {
            notes.add(where + ": '" + name + "' is not a number - inherited");
        }
        return null;
    }

    private static Double alpha(final JsonElement el, final String where, final List<String> notes) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            final double d = el.getAsDouble();
            return Math.max(0.0D, Math.min(1.0D, d));
        } catch (Throwable t) {
            notes.add(where + ": 'alpha' is not a number - inherited");
            return null;
        }
    }

    // --- writing ---------------------------------------------------------------------------

    /** Serialises {@code vehicles} in the resource-pack format, comments included. */
    public static String write(final List<VehicleLightOverrides.Vehicle> vehicles) {
        final JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        final JsonArray comment = new JsonArray();
        for (final String line : COMMENT) {
            comment.add(line);
        }
        root.add("_comment", comment);
        final JsonArray list = new JsonArray();
        for (final VehicleLightOverrides.Vehicle v : vehicles) {
            final JsonObject vo = new JsonObject();
            vo.addProperty("id", v.id);
            final JsonArray beams = new JsonArray();
            for (final VehicleLightOverrides.Beam b : v.beams) {
                beams.add(beamJson(b));
            }
            vo.add("beams", beams);
            list.add(vo);
        }
        root.add("vehicles", list);
        return compactNumbers(GSON.toJson(root));
    }

    /** A pretty-printed array of nothing but numbers, one per line. */
    private static final java.util.regex.Pattern NUM_ARRAY = java.util.regex.Pattern.compile(
            "\\[\\s*\\n((?:\\s*-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?,?\\s*\\n)+)\\s*\\]");

    /**
     * Collapses numeric arrays back onto one line - {@code "pos": [0.85, 1.2, 10.8]} is what the
     * format documents and what a player can read, while Gson's pretty printer puts every element
     * on its own line. Only arrays whose every element is a bare number are touched, so strings
     * (ids, colours, the comment block) are left exactly as Gson escaped them.
     */
    private static String compactNumbers(final String pretty) {
        final java.util.regex.Matcher m = NUM_ARRAY.matcher(pretty);
        final StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String body = m.group(1).replaceAll("[\\s\\n]", "");
            body = body.replaceAll(",+$", "");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("[" + body + "]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static JsonObject beamJson(final VehicleLightOverrides.Beam b) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", b.type == null ? "head" : b.type);
        if (b.color != null) {
            o.addProperty("color", String.format(Locale.ROOT, "#%06X", b.color & 0xFFFFFF));
        }
        if (b.pos != null) {
            o.add("pos", vecJson(b.pos));
        }
        if (b.rot != null) {
            o.add("rot", vecJson(b.rot));
        }
        if (b.length != null) {
            o.addProperty("length", round(b.length));
        }
        if (b.width != null) {
            o.addProperty("width", round(b.width));
        }
        if (b.alpha != null) {
            o.addProperty("alpha", round(b.alpha));
        }
        return o;
    }

    private static JsonArray vecJson(final double[] v) {
        final JsonArray a = new JsonArray();
        for (final double d : v) {
            a.add(round(d));
        }
        return a;
    }

    /** Three decimals - enough for a block coordinate, and it keeps exported files diff-friendly. */
    private static double round(final double d) {
        return Math.round(d * 1000.0D) / 1000.0D;
    }

    // --- finding files ---------------------------------------------------------------------

    /**
     * Reads every {@code lte_vehicle_lights.json} at or below {@code root}, sorted by path so the
     * order is stable across machines. Unreadable files are skipped; a missing root is not an
     * error (a player may simply not have dropped anything in yet).
     *
     * @param label pack label for diagnostics, e.g. {@code config} or {@code datapacks}
     */
    public static List<Source> scan(final Path root, final String label, final int maxDepth) {
        final List<Source> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, Math.max(1, maxDepth))) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> FILE_NAME.equalsIgnoreCase(String.valueOf(p.getFileName())))
                    .forEach(files::add);
        } catch (Throwable t) {
            return out;
        }
        files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));
        for (final Path p : files) {
            try {
                final String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                out.add(new Source(label, root.relativize(p).toString().replace('\\', '/'), text));
            } catch (IOException ignored) {
                // A file that cannot even be read is not a layout - the caller's failed counter
                // only speaks about files it could see, and this one is invisible by definition.
            }
        }
        return out;
    }
}
