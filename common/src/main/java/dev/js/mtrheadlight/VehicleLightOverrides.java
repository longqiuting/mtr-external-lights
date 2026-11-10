package dev.js.mtrheadlight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The vehicle light layout store: everything the beam editor and the renderer read.
 *
 * <h2>Three layers, strict priority</h2>
 * <ol>
 *   <li><b>resource pack</b> - {@code assets/<namespace>/lte_vehicle_lights.json}, loaded by
 *       {@link #load(List)} (client: the pack stack, server: config folder / datapacks);</li>
 *   <li><b>runtime edit</b> - {@link #put}, i.e. what the editor screen changed but has not saved
 *       into a pack yet. It outranks the pack at the same specificity and survives {@link #reload()};</li>
 *   <li><b>automatic detection</b> - {@link VehicleLights} (named lamp groups in the model), and
 *       behind it its own estimated default pair. This is the <em>base</em>, never an override.</li>
 * </ol>
 * A file may declare a single field - {@code {"type":"head","color":"#FF0000"}} - and that field is
 * the only thing that changes: everything else is inherited from layer 3 through
 * {@link Beam#filled}. That is why the merge is per field and not per entry.
 *
 * <h2>Matching</h2>
 * {@code id} is matched against MTR's model id (the train's {@code baseTrainType}), case
 * insensitively. An exact id wins over a wildcard; among wildcards the longest prefix wins; ties
 * inside one layer go to the entry loaded last (pack stack order, later pack = higher priority).
 *
 * <h2>Threading</h2>
 * One {@link ReentrantReadWriteLock} guards every field. Reads happen on the render thread (once
 * per train per frame) and on the server tick, writes on the resource-reload thread and the client
 * thread. Readers get copies, never the live objects, so a UI that mutates a returned
 * {@link Beam} cannot corrupt what the renderer is holding; the UI has to call {@link #put} back.
 */
public final class VehicleLightOverrides {

    // --- fallbacks -------------------------------------------------------------------------
    //
    // Used ONLY when neither the pack nor the automatic detection supplies a value - e.g. a
    // server-side export where no model has ever been seen, or a json entry for a vehicle whose
    // layout could not be read.

    /** Beam length in blocks when nothing else says. */
    private static final double DEFAULT_HEAD_LENGTH = 16.0D;
    private static final double DEFAULT_TAIL_LENGTH = 8.0D;
    /** Beam diameter in blocks when nothing else says (the renderer's cone width). */
    private static final double DEFAULT_WIDTH = 3.0D;
    /** Peak alpha of one beam when nothing else says - the value the renderer has always used. */
    private static final double DEFAULT_ALPHA = 0.16D;
    /** Cap on remembered automatic layouts (one per vehicle id seen in this world). */
    private static final int AUTO_CACHE = 512;

    private VehicleLightOverrides() {
    }

    // --- data model ------------------------------------------------------------------------

    /**
     * One beam of one vehicle. Every field except {@link #type} is nullable, and null means
     * "inherit": from the automatic detection on the way out, from the pack entry on the way in.
     * The editor screen hands exactly this object back through {@link #put}.
     */
    public static final class Beam {
        /** {@code head}, {@code tail}, or a pack-defined name (marker, cab, ...). Lower case. */
        public String type = "head";
        /** 0xRRGGBB, or null when inherited. */
        public Integer color;
        /** Car-local model space, 3 doubles, in the model's own axes. Null when inherited. */
        public double[] pos;
        /** Degrees, [yaw, pitch, roll]. Null when inherited (= no extra rotation). */
        public double[] rot;
        public Double length;
        public Double width;
        public Double alpha;

        public Beam() {
        }

        public Beam(final String type) {
            if (type != null && !type.isEmpty()) {
                this.type = type;
            }
        }

        public Beam copy() {
            final Beam b = new Beam(type);
            b.color = color;
            b.pos = pos == null ? null : pos.clone();
            b.rot = rot == null ? null : rot.clone();
            b.length = length;
            b.width = width;
            b.alpha = alpha;
            return b;
        }

        /**
         * This beam with every inherited (null) field replaced by {@code base}'s value. {@code pos}
         * and {@code rot} move as a unit: a half-inherited vector would be meaningless.
         */
        public Beam filled(final Beam base) {
            final Beam b = copy();
            if (base != null) {
                if (b.color == null) {
                    b.color = base.color;
                }
                if (b.pos == null) {
                    b.pos = base.pos == null ? null : base.pos.clone();
                }
                if (b.rot == null) {
                    b.rot = base.rot == null ? null : base.rot.clone();
                }
                if (b.length == null) {
                    b.length = base.length;
                }
                if (b.width == null) {
                    b.width = base.width;
                }
                if (b.alpha == null) {
                    b.alpha = base.alpha;
                }
            }
            return b;
        }

        @Override
        public String toString() {
            return type + "(color=" + (color == null ? "-" : String.format(Locale.ROOT, "#%06X", color))
                    + " pos=" + vec(pos) + " len=" + length + " w=" + width + " a=" + alpha + ")";
        }
    }

    /** One vehicle id (exact or wildcard) and its beams. Immutable snapshot outside the lock. */
    public static final class Vehicle {
        public final String id;
        /** Where this entry came from: {@code pack:<name>} or {@code runtime}. */
        public final String source;
        public final List<Beam> beams = new ArrayList<>();

        public Vehicle(final String id, final String source) {
            this.id = id;
            this.source = source == null ? "" : source;
        }

        /** True when the id ends in {@code *} and therefore matches by prefix. */
        public boolean isPattern() {
            return id.endsWith("*");
        }

        /** Lower-cased prefix without the {@code *}; the whole id for an exact entry. */
        public String prefix() {
            return (isPattern() ? id.substring(0, id.length() - 1) : id).toLowerCase(Locale.ROOT);
        }

        public boolean matches(final String vehicleId) {
            if (vehicleId == null || vehicleId.isEmpty()) {
                return false;
            }
            final String lower = vehicleId.toLowerCase(Locale.ROOT);
            return isPattern() ? lower.startsWith(prefix()) : lower.equals(prefix());
        }

        public Vehicle copy() {
            final Vehicle v = new Vehicle(id, source);
            for (final Beam b : beams) {
                v.beams.add(b.copy());
            }
            return v;
        }

        @Override
        public String toString() {
            return id + " -> " + beams.size() + " beam(s) [" + source + "]";
        }
    }

    // --- state -----------------------------------------------------------------------------

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    /** Loaded from resource packs, in load order; a later entry with the same id replaces. */
    private static final Map<String, Vehicle> PACK = new LinkedHashMap<>();
    /** {@link #put} - the editor's unsaved edits. Outranks PACK, survives {@link #reload()}. */
    private static final Map<String, Vehicle> RUNTIME = new LinkedHashMap<>();
    /** Automatic detection per vehicle id, so {@link #export} can write an id it has never seen. */
    private static final Map<String, List<Beam>> AUTO =
            new LinkedHashMap<String, List<Beam>>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, List<Beam>> eldest) {
                    return size() > AUTO_CACHE;
                }
            };
    /** {@link #lookup} memo - matching is a linear scan otherwise, and it runs per train per frame. */
    private static final Map<String, Vehicle> MATCH = new LinkedHashMap<String, Vehicle>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Vehicle> eldest) {
            return size() > 1024;
        }
    };
    /** One warning per broken file; a pack that fails on every reload must not spam the log. */
    private static final Set<String> WARNED = new LinkedHashSet<>();

    /** Cached negative match - see {@link #lookup}. Never handed out. */
    private static final Vehicle MISS = new Vehicle("", "");

    private static final AtomicLong VERSION = new AtomicLong(1);

    /** Files that failed structurally during the last {@link #load}. */
    private static volatile int lastFailed;
    /** Pack labels that contributed at least one usable file during the last {@link #load}. */
    private static Set<String> packLabels = new LinkedHashSet<>();
    /** True after the first {@link #load}, even when it found nothing at all. */
    private static volatile boolean loaded;

    // --- loading ---------------------------------------------------------------------------

    /**
     * Replaces the pack layer with {@code sources} (ordered: earlier = lower priority).
     *
     * <p>A file that fails structurally is counted, warned about once, and skipped - it cannot
     * affect the other packs, the game, or the previously loaded layer (which is replaced only
     * after every source has been parsed).
     */
    public static void load(final List<VehicleLightJson.Source> sources) {
        final Map<String, Vehicle> parsed = new LinkedHashMap<>();
        final Set<String> labels = new LinkedHashSet<>();
        int failed = 0;
        final List<String> notes = new ArrayList<>();

        if (sources != null) {
            for (final VehicleLightJson.Source src : sources) {
                final VehicleLightJson.Result r = VehicleLightJson.parse(src.json, src.toString());
                if (!r.ok()) {
                    failed++;
                    warnOnce(src, r.error);
                    continue;
                }
                for (final String note : r.notes) {
                    warnOnce(src, note);
                }
                for (final Vehicle snapshot : r.vehicles) {
                    if (snapshot.beams.isEmpty()) {
                        continue;
                    }
                    final Vehicle v = new Vehicle(snapshot.id, "pack:" + src.pack);
                    v.beams.addAll(snapshot.beams);
                    final Vehicle replaced = parsed.put(v.id, v);
                    labels.add(src.pack);
                    if (replaced != null) {
                        // Same id from an earlier pack: the later one wins wholesale. Field-level
                        // merging across packs would make the result depend on install order in a
                        // way no one can see, which is exactly what the stack priority avoids.
                        notes.add("id '" + v.id + "' from " + src + " overrides an earlier pack");
                    }
                }
            }
        }

        LOCK.writeLock().lock();
        try {
            PACK.clear();
            PACK.putAll(parsed);
            MATCH.clear();
            packLabels = labels;
            VERSION.incrementAndGet();
            lastFailed = failed;
            loaded = true;
        } finally {
            LOCK.writeLock().unlock();
        }

        if (!notes.isEmpty()) {
            LteLog.LOGGER.info("[lte] lights json: {} note(s), first: {}", notes.size(), notes.get(0));
        }
    }

    /**
     * Drops everything derived from resource packs: the pack layer, the automatic-detection cache
     * (the models it came from are pack content and just changed too) and every memoised match.
     * Runtime edits from {@link #put} are kept - they are the player's unsaved work, not pack data.
     */
    public static void reload() {
        LOCK.writeLock().lock();
        try {
            PACK.clear();
            AUTO.clear();
            MATCH.clear();
            synchronized (WARNED) {
                WARNED.clear();
            }
            packLabels = new LinkedHashSet<>();
            lastFailed = 0;
            VERSION.incrementAndGet();
        } finally {
            LOCK.writeLock().unlock();
        }
        try {
            VehicleLights.clearCache();
        } catch (Throwable ignored) {
            // VehicleLights is client/model code; a server-side reload has nothing to clear.
        }
    }

    private static void warnOnce(final VehicleLightJson.Source src, final String message) {
        final String key = src.pack + "|" + src.path;
        synchronized (WARNED) {
            if (!WARNED.add(key)) {
                return;
            }
        }
        LteLog.LOGGER.warn("[lte] lights json: {} unreadable: {}", src, message);
    }

    // --- reading ---------------------------------------------------------------------------

    /**
     * Every entry, pack layer first then runtime edits (so the caller's last write is last).
     * Copies - mutating them changes nothing until {@link #put}.
     */
    public static List<Vehicle> list() {
        LOCK.readLock().lock();
        try {
            final List<Vehicle> out = new ArrayList<>(PACK.size() + RUNTIME.size());
            for (final Vehicle v : PACK.values()) {
                out.add(v.copy());
            }
            for (final Vehicle v : RUNTIME.values()) {
                out.add(v.copy());
            }
            return out;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    /** Every declared id, exact and wildcard, in the same order as {@link #list()}. */
    public static List<String> ids() {
        LOCK.readLock().lock();
        try {
            final List<String> out = new ArrayList<>(PACK.size() + RUNTIME.size());
            for (final String id : PACK.keySet()) {
                out.add(id);
            }
            for (final String id : RUNTIME.keySet()) {
                if (!PACK.containsKey(id)) {
                    out.add(id);
                }
            }
            return out;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    /**
     * The entry that governs {@code vehicleId} - exact id first, then the longest matching
     * wildcard, runtime layer before pack layer. Null when nothing declares this vehicle.
     */
    public static Vehicle get(final String vehicleId) {
        final Vehicle v = lookup(vehicleId);
        return v == null ? null : v.copy();
    }

    /** The declared beams for {@code vehicleId}, or an empty list. Never null. */
    public static List<Beam> beams(final String vehicleId) {
        final Vehicle v = lookup(vehicleId);
        if (v == null) {
            return Collections.emptyList();
        }
        final List<Beam> out = new ArrayList<>(v.beams.size());
        for (final Beam b : v.beams) {
            out.add(b.copy());
        }
        return out;
    }

    private static Vehicle lookup(final String vehicleId) {
        if (vehicleId == null || vehicleId.isEmpty()) {
            return null;
        }
        final String key = vehicleId.toLowerCase(Locale.ROOT);
        LOCK.readLock().lock();
        try {
            final Vehicle cached = MATCH.get(key);
            if (cached != null) {
                return cached == MISS ? null : cached;
            }
        } finally {
            LOCK.readLock().unlock();
        }
        final Vehicle found = matchUncached(vehicleId);
        LOCK.writeLock().lock();
        try {
            // A null result is cached as MISS: "nothing declares this id" is the answer for the
            // hundreds of trains a player never edited, and it is asked once per train per frame.
            MATCH.put(key, found == null ? MISS : found);
        } finally {
            LOCK.writeLock().unlock();
        }
        return found;
    }

    /** Caller must not hold the lock. */
    private static Vehicle matchUncached(final String vehicleId) {
        Vehicle best = null;
        int bestLen = -1;
        LOCK.readLock().lock();
        try {
            // Runtime layer first: at equal specificity the editor's edit beats the pack.
            for (final Map<String, Vehicle> layer : List.of(RUNTIME, PACK)) {
                for (final Vehicle v : layer.values()) {
                    if (!v.matches(vehicleId)) {
                        continue;
                    }
                    if (!v.isPattern()) {
                        return v;
                    }
                    final int len = v.prefix().length();
                    if (len > bestLen) {
                        bestLen = len;
                        best = v;
                    }
                }
            }
        } finally {
            LOCK.readLock().unlock();
        }
        return best;
    }

    /** True once any {@link #load} has run - the client uses it to avoid a second, poorer scan. */
    public static boolean loaded() {
        return loaded;
    }

    /** Bumped on every change; the editor screen/UI can use it to invalidate its own caches. */
    public static long version() {
        return VERSION.get();
    }

    // --- writing (the editor) --------------------------------------------------------------

    /**
     * Installs {@code beams} as the runtime override for {@code vehicleId}. They outrank a pack
     * entry for the same id and are what {@link #export} writes out.
     */
    public static void put(final String vehicleId, final List<Beam> beams) {
        if (vehicleId == null || vehicleId.trim().isEmpty()) {
            return;
        }
        final String id = vehicleId.trim();
        final Vehicle v = new Vehicle(id, "runtime");
        if (beams != null) {
            for (final Beam b : beams) {
                if (b != null) {
                    v.beams.add(b.copy());
                }
            }
        }
        LOCK.writeLock().lock();
        try {
            if (v.beams.isEmpty()) {
                RUNTIME.remove(id);
            } else {
                RUNTIME.put(id, v);
            }
            MATCH.clear();
            VERSION.incrementAndGet();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /**
     * Replaces the runtime beam of the same {@code type} (or appends the first one of that type).
     * This is what an editor row's "apply" does; the list overload is for whole-layout writes.
     */
    public static void put(final String vehicleId, final Beam beam) {
        if (beam == null) {
            return;
        }
        LOCK.writeLock().lock();
        try {
            Vehicle v = RUNTIME.get(vehicleId);
            if (v == null) {
                v = new Vehicle(vehicleId == null ? "" : vehicleId.trim(), "runtime");
                RUNTIME.put(v.id, v);
            }
            for (int i = 0; i < v.beams.size(); i++) {
                if (v.beams.get(i).type.equalsIgnoreCase(beam.type)) {
                    v.beams.set(i, beam.copy());
                    MATCH.clear();
                    VERSION.incrementAndGet();
                    return;
                }
            }
            v.beams.add(beam.copy());
            MATCH.clear();
            VERSION.incrementAndGet();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /** Drops the runtime override for {@code vehicleId}; the pack layer (if any) is visible again. */
    public static boolean remove(final String vehicleId) {
        if (vehicleId == null) {
            return false;
        }
        LOCK.writeLock().lock();
        try {
            final boolean removed = RUNTIME.remove(vehicleId) != null;
            if (removed) {
                MATCH.clear();
                VERSION.incrementAndGet();
            }
            return removed;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /** Drops every runtime override, leaving the pack layer untouched. */
    public static void clearRuntime() {
        LOCK.writeLock().lock();
        try {
            RUNTIME.clear();
            MATCH.clear();
            VERSION.incrementAndGet();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /** True when {@link #put} has unsaved edits - the editor uses it for its "unsaved" marker. */
    public static boolean hasRuntimeEdits() {
        LOCK.readLock().lock();
        try {
            return !RUNTIME.isEmpty();
        } finally {
            LOCK.readLock().unlock();
        }
    }

    // --- merge with the automatic detection -------------------------------------------------

    /**
     * The effective layout of a vehicle, in model space: the pack/runtime declaration laid over the
     * automatic detection, with the detection's own estimated default pair underneath.
     *
     * <p>A declared {@code type} replaces the detection's geometry for that type (the first declared
     * beam of a type inherits from the first detected one of the same type, extra declared beams
     * stand alone) while the detection's beams of types the file never mentions are kept, so
     * {@code {"type":"head","color":"#F00"}} recolours the head lamp and leaves the tail alone.
     */
    public static List<Beam> resolve(final String vehicleId, final VehicleLights.Layout layout) {
        final List<Beam> auto = autoBeams(layout);
        if (!auto.isEmpty()) {
            rememberAuto(vehicleId, auto);
        }
        final Vehicle declared = lookup(vehicleId);
        if (declared == null || declared.beams.isEmpty()) {
            return auto;
        }
        return merge(auto, declared.beams);
    }

    /**
     * {@link #resolve} in the numbers the renderer wants: lateral offset from the car centreline,
     * height above the car's reference plane, and how far behind the end face the lamp sits.
     */
    public static List<Resolved> resolved(final String vehicleId, final VehicleLights.Layout layout) {
        final List<Beam> beams = resolve(vehicleId, layout);
        final List<Resolved> out = new ArrayList<>(beams.size());
        for (final Beam b : beams) {
            out.add(Resolved.of(b, layout));
        }
        return out;
    }

    /**
     * The detection's beams in model space. Slot geometry is the car-local one the renderer uses:
     * lateral offsets around the model's centreline, lamp height above the reference plane, and the
     * recess of the lamp behind its own car face.
     *
     * <p><b>车头在 -Z。</b>MTR 资源包的建模约定是车头朝模型空间 -Z（{@code docs/builtin-light-table.md}
     * 「{@code z} 沿车长（负 = 车头端）」），所以车头灯放在 {@code layout.frontSign} 那一端
     * （默认 {@code -1} = -Z），车尾灯放在相反的一端。旧代码写死「车头 = +Z、车尾 = -Z」，
     * 结果编辑器里车头灯从车尾出来、尾灯光束朝车头射 —— 两端正好相反。
     *
     * <p>{@code rot} 保持 null：方向由 {@link Beam#type} 决定（头灯沿 -Z、尾灯沿 +Z，
     * 见 {@code TrainPreview#direction}），这样导出文件里不写死的 rot 也能被重新打开。
     */
    public static List<Beam> autoBeams(final VehicleLights.Layout layout) {
        final List<Beam> out = new ArrayList<>(2);
        if (layout == null) {
            return out;
        }
        final boolean z = !"X".equalsIgnoreCase(layout.lengthAxis);
        final double half = layout.sectionLen * 0.5D;
        final double front = layout.frontSign >= 0 ? 1.0D : -1.0D;
        addSlot(out, layout.head, "head", true, front, half, layout.latCentre, z);
        addSlot(out, layout.tail, "tail", false, front, half, layout.latCentre, z);
        return out;
    }

    private static void addSlot(final List<Beam> out, final VehicleLights.Slot slot, final String type,
                                final boolean head, final double front, final double half,
                                final double latCentre, final boolean z) {
        if (slot == null) {
            return;
        }
        // 车头端 = front * half（front = -1 → -Z），车尾端 = -front * half；inset 从端面往里收。
        final double along = (head ? front : -front) * (half - slot.inset);
        final double[] laterals = slot.lateral == null || slot.lateral.length == 0
                ? new double[]{0.0D} : slot.lateral;
        for (final double lateral : laterals) {
            final Beam b = new Beam(type);
            b.pos = z
                    ? new double[]{latCentre + lateral, slot.height, along}
                    : new double[]{along, slot.height, latCentre + lateral};
            b.length = head ? DEFAULT_HEAD_LENGTH : DEFAULT_TAIL_LENGTH;
            b.width = DEFAULT_WIDTH;
            b.alpha = DEFAULT_ALPHA;
            out.add(b);
        }
    }

    /**
     * Lays {@code declared} over {@code auto}.
     *
     * <p>Walked in the detection's own order, so the result reads head-to-tail: each detected beam
     * takes the next declared beam of its type as its override, and a detected beam with no
     * override survives - <em>unless</em> the file gives that type an explicit {@code pos}, in which
     * case the declared geometry replaces the detected geometry of that type wholesale (a file that
     * names one head lamp means one head lamp, not one plus the detected second one). A file that
     * only recolours therefore keeps both lamps of a detected pair.
     */
    private static List<Beam> merge(final List<Beam> auto, final List<Beam> declared) {
        final List<Beam> base = auto == null ? Collections.emptyList() : auto;
        final List<Beam> over = declared == null ? Collections.emptyList() : declared;
        final List<Beam> out = new ArrayList<>(Math.max(base.size(), over.size()));
        if (base.isEmpty()) {
            for (final Beam d : over) {
                out.add(d.copy());
            }
            return out;
        }

        final Set<String> declaredTypes = new LinkedHashSet<>();
        final Set<String> explicitGeometry = new LinkedHashSet<>();
        for (final Beam d : over) {
            final String type = d.type == null ? "head" : d.type;
            declaredTypes.add(type);
            if (d.pos != null) {
                explicitGeometry.add(type);
            }
        }

        final List<Boolean> used = new ArrayList<>(Collections.nCopies(over.size(), Boolean.FALSE));
        final Map<String, Integer> next = new LinkedHashMap<>();
        for (final Beam a : base) {
            final String type = a.type;
            final int index = next.getOrDefault(type, 0);
            final int declaredIndex = nthIndexOfType(over, type, index);
            if (declaredIndex >= 0) {
                out.add(over.get(declaredIndex).filled(a));
                used.set(declaredIndex, Boolean.TRUE);
                next.put(type, index + 1);
            } else if (!declaredTypes.contains(type) || !explicitGeometry.contains(type)) {
                out.add(a.copy());
            }
            // else: the file replaced this type's geometry and has no beam left for this one.
        }
        for (int i = 0; i < over.size(); i++) {
            if (!used.get(i)) {
                out.add(over.get(i).copy());
            }
        }
        return out;
    }

    /** Index in {@code list} of the {@code index}-th beam whose type is {@code type}, or -1. */
    private static int nthIndexOfType(final List<Beam> list, final String type, final int index) {
        int seen = 0;
        for (int i = 0; i < list.size(); i++) {
            final Beam b = list.get(i);
            if (b.type != null && b.type.equalsIgnoreCase(type)) {
                if (seen == index) {
                    return i;
                }
                seen++;
            }
        }
        return -1;
    }

    private static void rememberAuto(final String vehicleId, final List<Beam> beams) {
        if (vehicleId == null || vehicleId.isEmpty()) {
            return;
        }
        LOCK.writeLock().lock();
        try {
            // Called once per train per frame from the renderer, so the fast path must not
            // allocate: only a changed fingerprint (size + first beam's geometry) is written.
            final List<Beam> cached = AUTO.get(vehicleId);
            if (cached != null && sameShape(cached, beams)) {
                return;
            }
            final List<Beam> copy = new ArrayList<>(beams.size());
            for (final Beam b : beams) {
                copy.add(b.copy());
            }
            AUTO.put(vehicleId, copy);
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static boolean sameShape(final List<Beam> a, final List<Beam> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            final Beam x = a.get(i);
            final Beam y = b.get(i);
            if (!x.type.equals(y.type) || !Objects.equals(x.length, y.length)) {
                return false;
            }
            if (x.pos == null || y.pos == null) {
                if (x.pos != y.pos) {
                    return false;
                }
                continue;
            }
            for (int k = 0; k < 3; k++) {
                if (Math.abs(x.pos[k] - y.pos[k]) > 1.0E-9D) {
                    return false;
                }
            }
        }
        return true;
    }

    // --- export ----------------------------------------------------------------------------

    /**
     * Writes the effective layout of every known vehicle to {@code path}, in the resource-pack
     * format, ready to be dropped into {@code assets/<pack>/lte_vehicle_lights.json}.
     *
     * <p>Wildcard entries are expanded to the concrete vehicle ids actually seen at run time - an
     * exported file is a snapshot of this game, not a rule - and ids are written in sorted order so
     * two exports of the same world diff cleanly. {@code _comment} explains the format to the player.
     */
    public static Path export(final Path path) throws IOException {
        final String json = exportJson();
        final Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /** {@link #export(Path)} without the file: the json text. */
    public static String exportJson() {
        final List<Vehicle> declared;
        final Map<String, List<Beam>> auto;
        LOCK.readLock().lock();
        try {
            declared = list();
            auto = new LinkedHashMap<>();
            for (final Map.Entry<String, List<Beam>> e : AUTO.entrySet()) {
                auto.put(e.getKey(), e.getValue());
            }
        } finally {
            LOCK.readLock().unlock();
        }

        final Map<String, List<Beam>> effective = new TreeMap<>();
        // Patterns first, exact ids second: an exact declaration always wins over a wildcard.
        for (final Vehicle v : declared) {
            if (v.isPattern()) {
                for (final Map.Entry<String, List<Beam>> e : auto.entrySet()) {
                    if (v.matches(e.getKey())) {
                        effective.put(e.getKey(), merge(e.getValue(), v.beams));
                    }
                }
            }
        }
        for (final Vehicle v : declared) {
            if (!v.isPattern()) {
                effective.put(v.id, merge(auto.get(v.id), v.beams));
            } else if (!effective.keySet().stream().anyMatch(v::matches)) {
                // A wildcard nothing in this world matched: keep it as written rather than
                // silently dropping the player's rule.
                effective.put(v.id, merge(Collections.emptyList(), v.beams));
            }
        }
        for (final Map.Entry<String, List<Beam>> e : auto.entrySet()) {
            effective.putIfAbsent(e.getKey(), merge(e.getValue(), Collections.emptyList()));
        }

        final List<Vehicle> out = new ArrayList<>(effective.size());
        for (final Map.Entry<String, List<Beam>> e : effective.entrySet()) {
            final Vehicle v = new Vehicle(e.getKey(), "");
            v.beams.addAll(e.getValue());
            out.add(v);
        }
        return VehicleLightJson.write(out);
    }

    // --- diagnostics -----------------------------------------------------------------------

    /**
     * {@code lights json: packs=K vehicles=V entries=E failed=F}.
     *
     * <p>{@code packs} counts the pack labels that contributed at least one usable file,
     * {@code vehicles} the distinct ids, {@code entries} the beam entries, {@code failed} the files
     * that could not be parsed at all. Printed once by the loader at startup and on every reload.
     */
    public static String diagnosticLine() {
        LOCK.readLock().lock();
        try {
            int vehicles = 0;
            int entries = 0;
            for (final Vehicle v : PACK.values()) {
                vehicles++;
                entries += v.beams.size();
            }
            for (final Vehicle v : RUNTIME.values()) {
                vehicles++;
                entries += v.beams.size();
            }
            return "lights json: packs=" + packLabels.size()
                    + " vehicles=" + vehicles
                    + " entries=" + entries
                    + " failed=" + lastFailed;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    /** Prints {@link #diagnosticLine()} once - the startup line the pack reports quote. */
    public static void logDiagnostics() {
        LteLog.LOGGER.info("[lte] {}", diagnosticLine());
    }

    // --- renderer view ---------------------------------------------------------------------

    /**
     * One effective beam in the renderer's own terms (the same numbers {@link ProtoBeamCone} reads
     * off a {@link VehicleLights.Slot}). Produced by {@link #resolved}.
     */
    public static final class Resolved {
        public final String type;
        public final int color;
        public final double lateral;
        public final double height;
        /** Blocks behind the car's end face the beam starts at (may exceed {@link VehicleLights#FACE_GAP}). */
        public final double inset;
        public final double length;
        /** Cone diameter in blocks; {@code <= 0} means "use the renderer's own angle". */
        public final double width;
        public final double alpha;
        public final double[] rot;

        private Resolved(final String type, final int color, final double lateral, final double height,
                         final double inset, final double length, final double width,
                         final double alpha, final double[] rot) {
            this.type = type;
            this.color = color;
            this.lateral = lateral;
            this.height = height;
            this.inset = inset;
            this.length = length;
            this.width = width;
            this.alpha = alpha;
            this.rot = rot;
        }

        static Resolved of(final Beam b, final VehicleLights.Layout layout) {
            final String type = b.type == null ? "head" : b.type;
            final boolean head = !"tail".equalsIgnoreCase(type);
            final int color = b.color != null ? b.color
                    : head ? HeadlightConfig.DEFAULT_HEAD_BEAM : HeadlightConfig.DEFAULT_TAIL_BEAM;
            double lateral = 0.0D;
            double height = 0.0D;
            double inset = 0.0D;
            if (b.pos != null && layout != null) {
                final boolean z = !"X".equalsIgnoreCase(layout.lengthAxis);
                final double along = z ? b.pos[2] : b.pos[0];
                lateral = (z ? b.pos[0] : b.pos[2]) - layout.latCentre;
                height = b.pos[1];
                final double half = layout.sectionLen * 0.5D;
                inset = head ? half - along : along + half;
            } else if (b.pos != null) {
                // No model to interpret the coordinates against (server side): keep the lateral
                // and height numbers as written, they are already car-relative.
                lateral = b.pos[0];
                height = b.pos[1];
            }
            final double length = b.length != null ? b.length
                    : head ? DEFAULT_HEAD_LENGTH : DEFAULT_TAIL_LENGTH;
            final double width = b.width != null ? b.width : DEFAULT_WIDTH;
            final double alpha = b.alpha != null ? b.alpha : DEFAULT_ALPHA;
            return new Resolved(type, color & 0xFFFFFF, lateral, height, inset, length, width, alpha,
                    b.rot == null ? null : b.rot.clone());
        }

        public boolean isHead() {
            return !"tail".equalsIgnoreCase(type);
        }

        @Override
        public String toString() {
            return type + "(lat=" + fmt(lateral) + " y=" + fmt(height) + " inset=" + fmt(inset)
                    + " len=" + fmt(length) + " w=" + fmt(width) + " a=" + fmt(alpha) + ")";
        }
    }

    private static String vec(final double[] v) {
        if (v == null) {
            return "-";
        }
        return "[" + fmt(v[0]) + "," + fmt(v[1]) + "," + fmt(v[2]) + "]";
    }

    private static String fmt(final double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
