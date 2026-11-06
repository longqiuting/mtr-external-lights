package dev.js.mtrheadlight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain JSON config, written to {@code config/mtr-headlight.json} on first start.
 * Everything the light placement depends on is here so it can be tuned in-game
 * without rebuilding the mod.
 *
 * <h2>Global values + per-siding overrides</h2>
 * Every field on this class is the <em>global</em> value. {@link #sidingSettings} holds sparse
 * per-siding overrides: an absent entry, or a null field inside one, means "use the global value".
 * {@link #effectiveFor(long)} folds the two together and is what the renderer, the light controller
 * and the rail relight scheduler read, so a siding override can never be half-applied.
 */
public final class HeadlightConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Bumped when a default changes in a way an existing file has to be migrated onto. */
    private static final int CONFIG_VERSION = 3;

    /** Written by the mod; only used to migrate files that still hold old defaults. */
    public int configVersion = 0;

    /** Master switch. */
    public boolean enabled = true;

    /** Only lights for transport mode TRAIN (ignore boats / cable cars / planes). */
    public boolean onlyTrains = true;

    /** Light level (0-15) of the front head light. */
    public int headLuminance = 15;
    /** How many blocks the front beam reaches (segment length, linear falloff). */
    public double headLength = 11.0D;
    /** Gap between the car's front face and the start of the beam. */
    public double headOffset = 0.6D;

    /**
     * Light level (0-15) of the rear light.
     * LambDynamicLights 4.x only carries a scalar luminance - there is no RGB channel,
     * so the tail light can only be dimmer, not red.
     */
    public int tailLuminance = 9;
    public double tailLength = 4.0D;
    public double tailOffset = 0.4D;

    /** Height of the light segment relative to the rail top (block center height). */
    public double heightOffset = 0.85D;

    /** Trains farther than this from the player get no light behaviour at all. */
    public double maxDistance = 192.0D;

    /**
     * Client data syncs replace the whole train set; a train can be missing for a few ticks while
     * it is really still there. Up to this many ticks of absence the light is left untouched.
     */
    public int missingKeepTicks = 600;
    /** Beyond {@link #missingKeepTicks} the light is switched off (no ghost beam) but kept. */
    public int missingDimTicks = 1200;
    /** Only after this many missing ticks is the behaviour really detached (then a returning train rebuilds it). */
    public int missingDetachTicks = 2400;

    /**
     * If the beam comes out of the wrong end of the train (front/back of the pose array
     * is an MTR implementation detail we mirror, not a documented contract), flip this.
     */
    public boolean swapEnds = false;

    /**
     * Deprecated: the old "no light on this siding" set.
     *
     * <p>Kept so an existing JSON still parses, but no longer written or read - on load every id in
     * here is migrated into {@link #sidingSettings} with {@code enabled = false} and the list is
     * emptied (see {@link #migrate()}). The per-siding switch below expresses the same thing and
     * also carries the other parameters, so two sources of truth would only drift apart.
     */
    @Deprecated
    public java.util.List<Long> mutedSidings = new java.util.ArrayList<>();

    /**
     * Sparse per-siding overrides, keyed by {@code Train.sidingId} (the id of the siding a train was
     * dispatched from - that is what the client knows about a train).
     *
     * <p>An entry only carries the fields the player actually changed; {@code null} means "follow
     * the global value". Deleting the entry (the "恢复为跟随全局" button) drops every override for
     * that siding at once.
     */
    public Map<Long, SidingSettings> sidingSettings = new LinkedHashMap<>();

    /**
     * Bake the dynamic light into ANTE's rail models (mixin, 1.20.1 only).
     * The rails are rebuilt on a throttle instead of every frame, see the options below.
     */
    public boolean railLight = true;
    /**
     * Poll the light around the player and the train heads every N client ticks.
     *
     * <p>This is only the <em>poll</em> rate and the floor of every per-train relight interval: a
     * poll whose light equals what is already baked costs nothing and never reaches the host. It
     * also bounds how often the light band can move, because a new beam position only enters the
     * queue at a poll.
     *
     * <p>Default 1 (every client tick): the per-train interval below is expressed in ticks and can
     * never be shorter than one poll, so polling every tick is what makes "every 2 ticks" at 25
     * blocks/s possible. The poll itself is cheap - it only walks the visible trains and queues
     * chunk keys; the 8x8 light samples happen once per queued chunk at drain time, and a chunk
     * whose light did not move costs no host call. Raise it to 2-4 only to save CPU on a weak
     * client; a fast train then follows at that interval instead of every 2 ticks.
     */
    public int railRelightTicks = 1;
    /**
     * Minimum number of client ticks between two <em>opportunities</em> to hand chunks to the
     * host, i.e. the cap on how many rail chunks per second we take out of its build slot.
     *
     * <p>ANTE rebuilds at most one rail chunk per frame and serves the chunks nearest the camera
     * first, so our chunks (right next to the player) otherwise always beat the chunks of newly
     * loaded terrain. {@code 20 / every * budget} is the ceiling:
     * {@code 3 * 2 = 13.3/s} by default, i.e. two thirds of the frame budget at 60 fps and about
     * two thirds at 20 fps too. Raise {@code every} to 4-6 if terrain loading feels slow.
     */
    public int railRelightEveryTicks = 3;
    /**
     * Kept so existing config files still parse; no longer used.
     *
     * <p>A request already covers the whole 32x32 rail chunk the target sits in - that is the unit
     * {@code registerLightUpdate} rebuilds - so a radius around a position could only ever add
     * neighbouring chunks (9x the host calls for ring 1) with no benefit to the beam.
     */
    public int railRelightRadius = 8;
    /** Upper bound on trains relit per throttle tick, keeps a busy junction from stalling a frame. */
    public int railRelightMaxTrains = 4;
    /**
     * How much a sampled light level has to move before the section is rebuilt. 1 means "any
     * change"; raise it if a single block of jitter causes rebuilds you can hear.
     */
    public int railLightDelta = 1;
    /**
     * Host calls allowed per drain opportunity (see {@code railRelightEveryTicks}).
     * {@code 20 / railRelightEveryTicks * railRelightBudget} is the rebuild ceiling, so the
     * default {@code 3 * 2} is ~13 host calls per second.
     *
     * <p>Spent in priority order: the chunk in front of a fast train first, then its head chunk,
     * then slow/stationary trains, then the player's own chunk, and the chunks a beam just left
     * last. When more than two trains are running fast the budget is dropped by one (see the
     * scheduler): no amount of queueing produces 4 fresh heads out of a 20 fps build slot, and
     * taking the whole slot would stall ANTE's own chunk builds.
     */
    public int railRelightBudget = 2;
    /**
     * How many blocks a beam may travel between two relights of the same chunk. The per-train
     * interval is {@code ceil(railRelightMaxMove / speed)}, so the position error is the same at
     * every speed and a slow train simply costs fewer builds per second:
     * 25 blk/s -&gt; every 2 ticks, 10 blk/s -&gt; every 4, 5 blk/s -&gt; every 8, 1 blk/s -&gt; every 10.
     */
    public double railRelightMaxMove = 2.0D;
    /** Upper bound on the per-train relight interval while the train is moving (ticks). */
    public int railRelightMaxInterval = 10;
    /**
     * Relight interval for a train below 1 block/s, and the refresh period of the player's own
     * chunk: nothing in the rails moves, so all this has to catch is a held/placed light source
     * changing, and 1.2 s is fast enough for that.
     */
    public int railRelightIdleInterval = 24;
    /**
     * Keep relighting while an Iris/OptiFine shader pack is active. The baked light ends up in the
     * pack's own lightmap sample, so whether it is visible is up to the pack; the flag exists so
     * the whole subsystem can be switched off in that setup without editing the JSON by hand.
     */
    public boolean railLightUnderShaders = true;

    /**
     * Under an Iris/OptiFine shader pack, also write the raised block light into the <em>sky</em>
     * half of the vertex lightmap ({@code LightTexture.pack(block, max(sky, block))}).
     *
     * <p>Why this exists: a pack can read both lightmap components and derive its ambient from the
     * sky one. MakeUp UltraFast, for example, computes {@code candle_color} from {@code lmcoord.x}
     * (block) but {@code visible_sky} from {@code lmcoord.y}, and then
     * <ul>
     *   <li>damps the candle term in "cave" geometry ({@code gbuffers_entities} defines
     *       {@code CAVEENTITY_V}) to {@code candle * mix(0.5, 1.0, visible_sky)}, and</li>
     *   <li>collapses the whole omni/ambient term to its darkest value
     *       ({@code omni_light = mix(omni_color_min, omni_color, visible_sky)}).</li>
     * </ul>
     * With {@code sky = 0} - a tunnel, or any rail not directly under open sky - a baked block
     * light of 12 therefore ends up as roughly half a candle and no ambient at all. Duplicating the
     * value into the sky half makes the pack treat the lit rail like a torch-lit surface.
     *
     * <p>That reasoning still holds; the reason it used to look mandatory does not. The old
     * symptom - rails that stayed dark under a pack no matter what was baked - was mostly a class
     * loading bug in our own mixin plugin ({@code Class.forName} pulled ANTE's classes in early,
     * so the other mixins could not inject at all). With that fixed the baked light shows up under
     * a pack on its own, so the boost is now off by default and stays as an opt-in for packs that
     * still render the rails too dark ({@code sky=0} really does halve the candle term).
     *
     * <p>Only applied where our dynamic light actually raised the block value, and only while a
     * pack is active - without a pack the sky half feeds the vanilla sky lightmap and a forged sky
     * value would brighten ordinary daytime rendering. Turn it off if a pack renders the rails as
     * if they were standing in the open.
     */
    public boolean railLightShaderBoost = false;
    /**
     * Multiplier applied to the train beam when it is baked into rail light. Player light sources
     * (LambDynamicLights) are never scaled; this only compensates for the beam being dimmer than a
     * held torch once distance falloff and a shader pack's super-linear lightmap are applied.
     */
    public double railLightGain = 1.4D;

    /**
     * Draw our own glowing band along the rails ahead of each train.
     *
     * <p>This is the fallback that used to take over automatically under a shader pack. It is a
     * plain additive quad strip on top of the rail bed, so it costs nothing to the relight
     * scheduler and never touches the baked light - the two are completely independent. It is
     * <b>off by default</b> (the baked light is the normal path); turn it on only when a pack
     * renders the baked light too dark, and tune it with the five values below.
     *
     * <p>Nothing here switches itself on: an active shader pack does not enable it.
     */
    public boolean railGlow = false;
    /** Band width in blocks (the quads are centred on the rail bed's centre line). */
    public double railGlowWidth = 1.2D;
    /** Peak alpha of the band; additive blending, so 0.12 is "hint of light", not a plastic strip. */
    public double railGlowAlpha = 0.12D;
    /** How far the band is lifted above the rail surface, in blocks. Below 0.06 it is clipped. */
    public double railGlowLift = 0.07D;
    /** How many blocks ahead of the nose the band reaches. */
    public double railGlowAhead = 14.0D;
    /** Sample spacing along the path, in blocks. Smaller = smoother curve, more vertices. */
    public double railGlowStep = 1.5D;

    // ---------------------------------------------------------------------------------------------
    // Per-siding overrides
    // ---------------------------------------------------------------------------------------------

    /**
     * One siding's overrides. Every field is a <b>wrapper type on purpose</b>: {@code null} means
     * "no override, use the global value", which an primitive default could not express (a siding
     * with {@code railGlow = false} and a siding with no glow opinion both have to be told apart
     * from a global {@code true}).
     *
     * <p>Written to JSON only for the fields that were set - Gson skips nulls - so an entry the
     * player touched one slider on stays a one-line object.
     */
    public static final class SidingSettings {
        /** Master switch for this siding's trains; {@code null} = follow the global master switch. */
        public Boolean enabled;
        public Double maxDistance;
        public Integer headLuminance;
        public Integer tailLuminance;
        public Double headLength;
        public Double tailLength;
        /** Bake this siding's beams into rail light; {@code null} = follow the global switch. */
        public Boolean railLight;
        /** Draw the glowing band for this siding's trains; {@code null} = follow the global switch. */
        public Boolean railGlow;

        /** True when nothing is overridden, i.e. the entry is equivalent to deleting it. */
        public boolean isEmpty() {
            return enabled == null && maxDistance == null && headLuminance == null
                    && tailLuminance == null && headLength == null && tailLength == null
                    && railLight == null && railGlow == null;
        }
    }

    /**
     * The values one train actually lights with: the global config with its siding's overrides
     * applied. Immutable snapshot, built once per train per tick/frame so a config edit can never
     * land halfway through a beam.
     */
    public static final class Effective {
        public final boolean enabled;
        public final double maxDistance;
        public final int headLuminance;
        public final int tailLuminance;
        public final double headLength;
        public final double tailLength;
        public final boolean railLight;
        public final boolean railGlow;
        /** True when at least one field came from {@link #sidingSettings} rather than the globals. */
        public final boolean overridden;

        Effective(final boolean enabled, final double maxDistance, final int headLuminance,
                  final int tailLuminance, final double headLength, final double tailLength,
                  final boolean railLight, final boolean railGlow, final boolean overridden) {
            this.enabled = enabled;
            this.maxDistance = maxDistance;
            this.headLuminance = headLuminance;
            this.tailLuminance = tailLuminance;
            this.headLength = headLength;
            this.tailLength = tailLength;
            this.railLight = railLight;
            this.railGlow = railGlow;
            this.overridden = overridden;
        }
    }

    /**
     * Folds the global values and this siding's overrides into one snapshot. {@code sidingId == 0}
     * (a train MTR did not tie to a siding) always gets the globals.
     */
    public Effective effectiveFor(final long sidingId) {
        final SidingSettings o = sidingId == 0L || sidingSettings == null
                ? null : sidingSettings.get(Long.valueOf(sidingId));
        if (o == null) {
            return new Effective(enabled, maxDistance, headLuminance, tailLuminance, headLength,
                    tailLength, railLight, railGlow, false);
        }
        return new Effective(
                o.enabled != null ? o.enabled.booleanValue() : enabled,
                o.maxDistance != null ? o.maxDistance.doubleValue() : maxDistance,
                o.headLuminance != null ? o.headLuminance.intValue() : headLuminance,
                o.tailLuminance != null ? o.tailLuminance.intValue() : tailLuminance,
                o.headLength != null ? o.headLength.doubleValue() : headLength,
                o.tailLength != null ? o.tailLength.doubleValue() : tailLength,
                o.railLight != null ? o.railLight.booleanValue() : railLight,
                o.railGlow != null ? o.railGlow.booleanValue() : railGlow,
                !o.isEmpty());
    }

    /**
     * True when <em>some</em> train may want rail light: either the global switch is on, or a siding
     * overrides it on. The rail relight scheduler must keep running in the second case even though
     * the global switch is off, exactly like {@link #railGlowAnySiding()} for the band.
     */
    public boolean railLightAnySiding() {
        if (railLight) {
            return true;
        }
        if (sidingSettings != null) {
            for (final SidingSettings o : sidingSettings.values()) {
                if (o != null && Boolean.TRUE.equals(o.railLight)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the band may be drawn for at least one siding (see {@link #railLightAnySiding()}). */
    public boolean railGlowAnySiding() {
        if (railGlow) {
            return true;
        }
        if (sidingSettings != null) {
            for (final SidingSettings o : sidingSettings.values()) {
                if (o != null && Boolean.TRUE.equals(o.railGlow)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Get-or-create the override entry for a siding (used by the detail screen on the first edit). */
    public SidingSettings settingsFor(final long sidingId) {
        if (sidingSettings == null) {
            sidingSettings = new LinkedHashMap<>();
        }
        SidingSettings settings = sidingSettings.get(Long.valueOf(sidingId));
        if (settings == null) {
            settings = new SidingSettings();
            sidingSettings.put(Long.valueOf(sidingId), settings);
        }
        return settings;
    }

    /** Current overrides for a siding, or null when it simply follows the globals. */
    public SidingSettings overrides(final long sidingId) {
        return sidingSettings == null ? null : sidingSettings.get(Long.valueOf(sidingId));
    }

    /** Drops every override for a siding ("恢复为跟随全局"). Returns true when something was removed. */
    public boolean clearSiding(final long sidingId) {
        return sidingSettings != null && sidingSettings.remove(Long.valueOf(sidingId)) != null;
    }

    public static HeadlightConfig load() {
        final Path path = FabricLoader.getInstance().getConfigDir().resolve("mtr-headlight.json");
        if (Files.exists(path)) {
            try {
                final String json = Files.readString(path, StandardCharsets.UTF_8);
                final HeadlightConfig cfg = GSON.fromJson(json, HeadlightConfig.class);
                if (cfg != null) {
                    final HeadlightConfig ready = cfg.sanitized();
                    if (ready.migrate()) {
                        ready.save(path);
                    }
                    return ready;
                }
            } catch (IOException | RuntimeException e) {
                MtrHeadlightClient.LOGGER.warn("Could not read {} - using defaults", path, e);
            }
        }
        final HeadlightConfig cfg = new HeadlightConfig();
        cfg.configVersion = CONFIG_VERSION;
        cfg.save(path);
        return cfg;
    }

    /**
     * Brings an older JSON up to the current defaults.
     *
     * <p>Gson leaves absent fields at their initialiser, so a file that predates a field picks up
     * its new default for free. The exception is a field that <em>exists</em> with the old default:
     * the relight tuning was re-calibrated (old {@code 4 / 1} → {@code 1 / 2}), and a file still
     * holding the old numbers would silently keep the old, too-slow behaviour. Only values that are
     * exactly the old defaults are touched, so a hand-tuned file keeps its own numbers.
     *
     * <p>Version 3 moved the "muted sidings" list onto per-siding overrides: every id that used to
     * be muted becomes {@code sidingSettings[id].enabled = false}, so a player who had switched a
     * depot off keeps it off. The old list is then emptied - it is only kept as a field so a stale
     * {@code mutedSidings} key in the JSON does not fail to parse. Nothing reads it any more.
     *
     * @return true when something was changed and the file should be written back
     */
    private boolean migrate() {
        if (configVersion >= CONFIG_VERSION) {
            return false;
        }
        if (configVersion < 2) {
            if (railRelightTicks == 4) {
                railRelightTicks = 1;
            }
            if (railRelightBudget == 1) {
                railRelightBudget = 2;
            }
        }
        if (configVersion < 3 && mutedSidings != null && !mutedSidings.isEmpty()) {
            for (final Long id : mutedSidings) {
                if (id == null || id.longValue() == 0L) {
                    continue;
                }
                settingsFor(id.longValue()).enabled = Boolean.FALSE;
            }
            mutedSidings.clear();
        }
        configVersion = CONFIG_VERSION;
        return true;
    }

    private HeadlightConfig sanitized() {
        if (mutedSidings == null) {
            mutedSidings = new java.util.ArrayList<>();
        }
        if (sidingSettings == null) {
            sidingSettings = new LinkedHashMap<>();
        } else {
            sidingSettings.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
            sidingSettings.values().forEach(o -> {
                if (o.maxDistance != null) {
                    o.maxDistance = clamp(o.maxDistance, 16.0D, 1024.0D);
                }
                if (o.headLuminance != null) {
                    o.headLuminance = clamp(o.headLuminance, 0, 15);
                }
                if (o.tailLuminance != null) {
                    o.tailLuminance = clamp(o.tailLuminance, 0, 15);
                }
                if (o.headLength != null) {
                    o.headLength = clamp(o.headLength, 0.0D, 32.0D);
                }
                if (o.tailLength != null) {
                    o.tailLength = clamp(o.tailLength, 0.0D, 32.0D);
                }
            });
            sidingSettings.values().removeIf(SidingSettings::isEmpty);
        }
        headLuminance = clamp(headLuminance, 0, 15);
        tailLuminance = clamp(tailLuminance, 0, 15);
        headLength = clamp(headLength, 0.0D, 32.0D);
        tailLength = clamp(tailLength, 0.0D, 32.0D);
        maxDistance = clamp(maxDistance, 16.0D, 1024.0D);
        railRelightTicks = clamp(railRelightTicks, 1, 40);
        railRelightEveryTicks = clamp(railRelightEveryTicks, 1, 20);
        railRelightRadius = clamp(railRelightRadius, 2, 32);
        railRelightMaxTrains = clamp(railRelightMaxTrains, 1, 16);
        railLightGain = clamp(railLightGain, 1.0D, 3.0D);
        railLightDelta = clamp(railLightDelta, 0, 15);
        railRelightBudget = clamp(railRelightBudget, 1, 4);
        railRelightMaxMove = clamp(railRelightMaxMove, 0.5D, 8.0D);
        railRelightMaxInterval = clamp(railRelightMaxInterval, 2, 40);
        railRelightIdleInterval = clamp(railRelightIdleInterval, 4, 60);
        railGlowWidth = clamp(railGlowWidth, 0.2D, 4.0D);
        railGlowAlpha = clamp(railGlowAlpha, 0.0D, 0.4D);
        railGlowLift = clamp(railGlowLift, 0.0D, 0.5D);
        railGlowAhead = clamp(railGlowAhead, 1.0D, 48.0D);
        railGlowStep = clamp(railGlowStep, 0.5D, 8.0D);
        return this;
    }

    private void save(final Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MtrHeadlightClient.LOGGER.warn("Could not write {}", path, e);
        }
    }

    /** Persist to the standard config file (used by the in-game config screen). */
    public void save() {
        save(FabricLoader.getInstance().getConfigDir().resolve("mtr-headlight.json"));
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
