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
    private static final int CONFIG_VERSION = 5;

    /** Default beam colours, also used when a JSON/override carries a malformed hex string. */
    public static final int DEFAULT_HEAD_BEAM = 0xFFF3D6;
    public static final int DEFAULT_TAIL_BEAM = 0xFF6A5A;

    /** Written by the mod; only used to migrate files that still hold old defaults. */
    public int configVersion = 0;

    /** Master switch. Gates both subsystems below ({@link #dynamicLight}, {@link #beamGlow}). */
    public boolean enabled = true;

    /**
     * Dynamic-light sub-switch, ANDed with {@link #enabled}.
     *
     * <p>The three switches are a strict hierarchy, not three independent flags:
     * master off means neither subsystem runs; master on plus this off means "no dynamic light,
     * but the light band may still be drawn"; and {@link #beamGlow} is the other child. Every
     * consumer reads {@code enabled && dynamicLight} (or {@code enabled && beamGlow}) rather than
     * either flag alone, so the combined state cannot be half-applied.
     *
     * <p>The baked rail light ({@link #railLight}) belongs to this subsystem: it is the same light
     * value written into ANTE's rail vertices, so it is gated by the same switch.
     */
    public boolean dynamicLight = true;

    /**
     * A/B switch: whether our sources are handed to LambDynamicLights at all
     * (MC 1.19.4 / LDL 2.3.1 only - the other versions' backends ignore it for now).
     *
     * <p>It exists to answer one question and nothing else: <b>is it us?</b> Turn it off and the
     * mod stops calling {@code LambDynLights#addLightSource} entirely - zero sources of ours in
     * LDL's set - while the beam band, the rail bake and everything else keep running. If the
     * player's torch/held light starts working in that state, our registration is what broke LDL;
     * if it does not, the cause is upstream of LDL and this switch proves it in one step.
     *
     * <p>Flippable in game - {@code /externallights ldlreg off} - so the comparison needs no
     * restart and no world reload. Default {@code true} (normal operation).
     */
    public boolean ldlRegisterSources = true;


    /** Only lights for transport mode TRAIN (ignore boats / cable cars / planes). */
    public boolean onlyTrains = true;

    /** Light level (0-15) of the front head light. */
    public int headLuminance = 15;
    /** How many blocks the front beam reaches (segment length, linear falloff). */
    public double headLength = 11.0D;
    /** Gap between the car's front face and the start of the beam, clamped to [0, 2] blocks. */
    public double headOffset = 0.6D;

    /**
     * Light level (0-15) of the rear light.
     * LambDynamicLights 4.x only carries a scalar luminance - there is no RGB channel,
     * so the tail light can only be dimmer, not red.
     */
    public int tailLuminance = 9;
    public double tailLength = 4.0D;
    public double tailOffset = 0.4D;

    /** {@link #headOffset} clamped to the supported range [0, 2] blocks (shared by every consumer). */
    public double headOffsetClamped() {
        return clampOffset(headOffset);
    }

    /** {@link #tailOffset} clamped to the supported range [0, 2] blocks. */
    public double tailOffsetClamped() {
        return clampOffset(tailOffset);
    }

    private static double clampOffset(final double value) {
        if (Double.isNaN(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(2.0D, value));
    }

    /** Height of the light segment relative to the rail top (block center height). */
    public double heightOffset = 0.85D;

    /**
     * How far a beam's tip drops below its lamp, in blocks. Shared by every consumer of the end
     * geometry (dynamic light, rail bake) so they cannot drift apart; the beam sheet draws its own
     * height from the ground instead.
     */
    public static final double HEAD_TIP_DROP = 0.60D;
    public static final double TAIL_TIP_DROP = 0.35D;

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
     * Share of the tracked trains that have to vanish in the <b>same tick</b> before the absence is
     * read as a wholesale clear rather than as sync jitter. Clamped to [0.2, 1.0]; the effective
     * condition is {@code missing >= max(2, ceil(tracked * massClearRatio))}.
     *
     * <p>Why a ratio and not a fixed count: "refresh routes" / "clear vehicles" in MTR drop the
     * client's whole train set at once, and how many trains that is depends entirely on the save -
     * a two-train test world and a fifty-train network have to behave the same. Half the tracked
     * fleet disappearing within one tick never happens by range-based sync (that drops trains one
     * or two at a time, at different ticks), so 0.5 separates the two cases with a wide margin.
     *
     * <p>Why the floor of 2: a single train leaving the data set is a normal despawn or a single
     * sync gap, and tearing its light down instantly would make ordinary despawn flicker. {@code
     * 1.0} effectively disables the fast path except for a full clear.
     */
    public double massClearRatio = 0.5D;

    /**
     * Temporary diagnostic: one {@code clear: tracked= missing= massClear=} INFO line every five
     * seconds, so a "刷新线路 / 清除车辆" run can be confirmed from the log without a debugger.
     *
     * <p>{@code tracked} and {@code missing} are the current tick's numbers, {@code massClear} is
     * how many trains' lights the fast path removed during the window (0 = it never fired). Default
     * on while the mass-clear link is being validated; set it to {@code false} for a quiet log.
     */
    public boolean massClearDiagnostics = true;

    /**
     * If the beam comes out of the wrong end of the train (front/back of the pose array
     * is an MTR implementation detail we mirror, not a documented contract), flip this.
     *
     * <p>Flipping is exactly the same thing as travelling the other way, so every consumer folds
     * it into one boolean - {@code reversed ^ swapEnds} - and {@link TrainPose} builds the ends
     * from that. There is no separate swap branch in the light or beam geometry.
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
     * Seconds after entering a world (or changing dimension) during which <b>no</b> rail rebuild is
     * requested at all - the load grace.
     *
     * <p>{@code RailRenderDispatcher#drawRails} rebuilds only a couple of rail chunks per frame
     * ({@code buffersRebuilt < railChunkBuildPerFrame}, stock 1) out of a single queue, served nearest-camera-first; the chunks of
     * newly generated terrain are in that same queue. Our requests sit right next to the player,
     * so they win every tie and a join/teleport that should stream a few hundred chunks instead
     * streams them at half speed. During the grace we queue nothing, sample nothing and make zero
     * host calls, so ANTE keeps the whole build slot for the new area.
     *
     * <p>It is deliberately a plain timer, not a heuristic: the only reliable signal we have in a
     * client-side mod is "the world object changed", which is exactly a join / respawn / dimension
     * change. The chunk mesh that ANTE builds during the grace already bakes the current dynamic
     * light (our mixin runs inside its rebuild), so the paused chunks come out correctly lit and
     * do not need a rebuild afterwards. {@code 0} disables the grace.
     */
    public double railRelightLoadGraceSec = 8.0D;

    /**
     * Only rebuild rail chunks whose centre is within this many blocks of the camera.
     *
     * <p>A 32x32 rail chunk rebuilt costs a full mesh bake + GPU upload; past this distance the
     * rails are a few pixels and the player cannot see a dynamic light on them anyway. Dropping
     * them frees host calls for the chunks that are actually on screen.
     */
    public int railRelightMaxBlockDist = 64;

    /**
     * Minimum milliseconds between two rebuilds of the <em>same</em> rail chunk.
     *
     * <p>The per-train cadence alone would re-ask for a chunk every 2 ticks at 25 blocks/s
     * (10 Hz), which is more than the eye can use: the light band moves a fraction of a block per
     * frame at that rate. 500 ms caps one chunk at 2 rebuilds/s, so one fast train costs ~2 host
     * calls/s instead of 10.
     */
    public int railRelightCooldownMs = 500;

    /**
     * Hard ceiling on host calls per second, across every train and the player chunk.
     *
     * <p>This is the only place where we account for time rather than queue opportunities, so it
     * holds even if {@link #railRelightEveryTicks} / {@link #railRelightBudget} are raised. The
     * number that matters is the host's own capacity: {@code railChunkBuildPerFrame} chunks per
     * frame, i.e. 60/s at 20 fps and 180/s at 60 fps with the default boost of 3 (20/s when the
     * boost is off). 16/s therefore keeps roughly 27% of the slot at the worst frame rate and 9% at
     * 60 fps - enough for the light band to follow a fast train, still leaving the host the majority
     * of its build slot for newly loaded terrain. The old 12 was calibrated against the stock
     * 1-per-frame host, where it already took 60% of a 20 fps slot; that ratio does not apply once
     * the per-frame limit is raised, and leaving it there is what made the queue drain slower than
     * the train moved. Lower it (6-8) on a weak client or a large modpack.
     */
    public int railRelightMaxPerSec = 16;

    /**
     * Master switch for the rail chunk rebuild boost (mixin on the host's per-frame gate).
     *
     * <p>When off, the host keeps its hard-coded {@code buffersRebuilt < 1}, i.e. exactly stock
     * behaviour - use it if raising the limit causes frame-time spikes (each extra chunk is a full
     * mesh bake on the {@code 华丽} path). Read every frame, so toggling it in the config file takes
     * effect without a restart.
     */
    public boolean railChunkBuildBoost = true;

    /**
     * How many rail chunks the host may rebuild per frame (stock: 1).
     *
     * <p>ANTE/NTE hard-code {@code buffersRebuilt < 1}. With LDL at 快速/最快 a rail chunk rebuild is
     * a cheap instance write and one per frame is plenty; at 华丽 the host drops to
     * {@code MeshBuildingRailChunk} (full mesh bake + upload), the queue a moving train fills is
     * deeper than one entry per frame drains, and the light band trails the train even at 80 km/h.
     * 3 drains that queue about three times as fast, which is the smallest value that kept up in
     * testing without turning a bad frame into three mesh bakes back to back.
     *
     * <p>Clamped to 1..8. Raise it only if the diagnostics line shows the queue still not draining
     * ({@code chunksPerSec} pinned at {@code perFrame x fps}) and the frame time stays flat; lower it
     * to 2 on a weak CPU.
     */
    public int railChunkBuildPerFrame = 3;


    /**
     * Cosine threshold of the horizontal view filter: a chunk is only rebuilt when the angle
     * between the player's yaw and the direction to the chunk is at most
     * {@code acos(threshold)}.
     *
     * <p>{@code 0.25} is +-75.5 degrees (a 151 degree window), i.e. comfortably wider than any
     * FOV in use - a chunk outside it is behind the player and cannot be on screen. Filtering only
     * the horizontal angle keeps the check stable when the player looks down at the rails, which a
     * full 3D cone would mis-handle. Chunks within 16 blocks bypass the test (the direction to
     * something under your feet is meaningless, and it is always on screen).
     */
    public double railRelightFrustumCos = 0.25D;

    /**
     * Temporary diagnostic: one {@code railq: req= rebuilt= skipped= deferred= paused= quota=}
     * line every five seconds (INFO). Default on while the load-sharing work is being validated;
     * set it to {@code false} for a quiet log. The counters are per window, not cumulative:
     *
     * <ul>
     *   <li>{@code req} - chunk requests accepted into the queue;</li>
     *   <li>{@code rebuilt} - host calls actually made (the number to compare with
     *       {@link #railRelightMaxPerSec});</li>
     *   <li>{@code skipped} - chunks whose sampled light already matched what is baked (free);</li>
     *   <li>{@code deferred} - requests refused by the distance/view filter or the per-chunk
     *       cooldown, plus requests still queued when the quota ran out;</li>
     *   <li>{@code paused} - client ticks spent in the load grace;</li>
     *   <li>{@code quota} - {@code used/cap} of the current one-second window.</li>
     * </ul>
     */
    public boolean railRelightDiagnostics = true;

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
     * Draw the head/tail <b>light beams</b> - our own additive geometry, independent of the baked
     * rail light ({@link #railLight}) and of the relight scheduler.
     *
     * <p>This used to be a band sampled <em>along the path</em>, which made it look like the rail
     * itself was glowing. A head light does not turn with the track, so the beam now leaves the
     * nose along the train's heading in a <b>straight line</b> (see {@link BeamGlow}): a 3-block
     * wide sheet that is brightest at the lamp and fades out along its length (exponential) and
     * towards both edges (vertex alpha 1.0 in the centre -&gt; 0 at the rim).
     *
     * <p>It is purely a car-light effect: the two can be switched on together and neither touches
     * the other's state.
     */
    public boolean beamGlow = true;
    /**
     * How far from the player a beam is still generated. Deliberately its own value, separate
     * from {@link #maxDistance} (the dynamic light / train visibility range): a long beam is
     * visible much further away than it is worth computing.
     */
    public double beamDistance = 128.0D;
    /** Length of the head beam in blocks, straight out of the nose. */
    public double beamHeadLength = 16.0D;
    /** Length of the tail beam in blocks. Kept much shorter than the head beam on purpose. */
    public double beamTailLength = 4.0D;
    /** Beam width in blocks (3 = the usual car width). Edges fade to fully transparent. */
    public double beamWidth = 3.0D;
    /** Head beam colour, {@code #RRGGBB}. Warm white by default (a halogen head light). */
    public String beamHeadColor = "#FFF3D6";
    /** Tail beam colour, {@code #RRGGBB}. Faint red by default. */
    public String beamTailColor = "#FF6A5A";
    /**
     * Extra height of the beam above the surface under it, per end, in blocks - on top of
     * {@link #beamLift}. The sheet itself always hugs the ground/rail surface (its x/z come from the
     * train's 3D heading, its y from the heightmap below each sample), so these are small trims for
     * "just clearing a raised rail bed", not a mounting height. 0 would put the sheet exactly on the
     * ballast and z-fight with it.
     */
    public double beamHeadHeight = 0.15D;
    public double beamTailHeight = 0.10D;
    /**
     * Base lift above the sampled surface, applied to both ends, so the sheet does not z-fight with
     * the rail bed or the block top.
     *
     * <p><b>Where the height comes from.</b> Each sample point's x/z is the train's 3D heading
     * advanced straight ahead (pitch included), and its y is the heightmap surface under that point
     * ({@code MOTION_BLOCKING_NO_LEAVES}). When that sample is unavailable - air, a viaduct far above
     * the terrain, an unloaded chunk, or a surface more than 3 blocks off the ray - the height of the
     * beam's own origin (the rail point at that end of the train) is used instead. Both cases then
     * get this lift plus that end's {@link #beamHeadHeight}/{@link #beamTailHeight}.
     */
    public double beamLift = 0.07D;
    /** Peak alpha of the beam at the lamp. Additive blending, so 0.55 is a bright but not solid sheet. */
    public double beamAlpha = 0.55D;

    // ---------------------------------------------------------------------------------------------
    // Per-siding overrides
    // ---------------------------------------------------------------------------------------------

    /**
     * One siding's overrides. Every field is a <b>wrapper type on purpose</b>: {@code null} means
     * "no override, use the global value", which an primitive default could not express (a siding
     * with {@code beamGlow = false} and a siding with no beam opinion both have to be told apart
     * from a global {@code true}).
     *
     * <p>Written to JSON only for the fields that were set - Gson skips nulls - so an entry the
     * player touched one slider on stays a one-line object.
     */
    public static final class SidingSettings {
        /** Master switch for this siding's trains; {@code null} = follow the global master switch. */
        public Boolean enabled;
        /** Dynamic-light sub-switch for this siding; {@code null} = follow the global one. */
        public Boolean dynamicLight;
        public Double maxDistance;
        public Integer headLuminance;
        public Integer tailLuminance;
        public Double headLength;
        public Double tailLength;
        /** Bake this siding's beams into rail light; {@code null} = follow the global switch. */
        public Boolean railLight;
        /** Draw the head/tail light beams for this siding's trains; {@code null} = follow the global switch. */
        public Boolean beamGlow;
        public Double beamDistance;
        public Double beamHeadLength;
        public Double beamTailLength;
        public Double beamWidth;
        public String beamHeadColor;
        public String beamTailColor;
        public Double beamHeadHeight;
        public Double beamTailHeight;
        /**
         * Rebuild-distance cap for this siding's trains; {@code null} = follow the global value.
         */
        public Integer railRelightMaxBlockDist;
        /** Per-chunk rebuild cooldown for this siding's trains; {@code null} = follow the global. */
        public Integer railRelightCooldownMs;
        /** This siding's share of the per-second host budget; {@code null} = follow the global. */
        public Integer railRelightMaxPerSec;
        /** View-filter cosine for this siding's trains; {@code null} = follow the global. */
        public Double railRelightFrustumCos;

        /** True when nothing is overridden, i.e. the entry is equivalent to deleting it. */
        public boolean isEmpty() {
            return enabled == null && dynamicLight == null && maxDistance == null
                    && headLuminance == null
                    && tailLuminance == null && headLength == null && tailLength == null
                    && railLight == null && beamGlow == null && beamDistance == null
                    && beamHeadLength == null && beamTailLength == null && beamWidth == null
                    && beamHeadColor == null && beamTailColor == null
                    && beamHeadHeight == null && beamTailHeight == null
                    && railRelightMaxBlockDist == null && railRelightCooldownMs == null
                    && railRelightMaxPerSec == null && railRelightFrustumCos == null;
        }
    }

    /**
     * The values one train actually lights with: the global config with its siding's overrides
     * applied. Immutable snapshot, built once per train per tick/frame so a config edit can never
     * land halfway through a beam.
     */
    public static final class Effective {
        public final boolean enabled;
        /** Dynamic light subsystem (the light band has its own {@link #beamGlow}). */
        public final boolean dynamicLight;
        public final double maxDistance;
        public final int headLuminance;
        public final int tailLuminance;
        public final double headLength;
        public final double tailLength;
        public final boolean railLight;
        /** Draw the light beams for this train at all. */
        public final boolean beamGlow;
        /** Distance from the player beyond which no beam is generated (own range). */
        public final double beamDistance;
        public final double beamHeadLength;
        public final double beamTailLength;
        public final double beamWidth;
        /** Resolved beam colours as 0xRRGGBB, already parsed and clamped. */
        public final int beamHeadColor;
        public final int beamTailColor;
        public final double beamHeadHeight;
        public final double beamTailHeight;
        /** Rail-rebuild distance cap (blocks from the camera) for this train's chunks. */
        public final int railRelightMaxBlockDist;
        /** Minimum ms between two rebuilds of the same chunk for this train's chunks. */
        public final int railRelightCooldownMs;
        /** This train's share of the per-second host budget (never above the global one). */
        public final int railRelightMaxPerSec;
        /** Horizontal view-filter cosine: below this dot product a chunk is skipped. */
        public final double railRelightFrustumCos;
        /** The siding these values belong to ({@code 0} = the globals / no siding). */
        public final long sidingId;
        /** True when at least one field came from {@link #sidingSettings} rather than the globals. */
        public final boolean overridden;

        Effective(final boolean enabled, final boolean dynamicLight, final double maxDistance,
                  final int headLuminance,
                  final int tailLuminance, final double headLength, final double tailLength,
                  final boolean railLight, final boolean beamGlow, final double beamDistance,
                  final double beamHeadLength, final double beamTailLength, final double beamWidth,
                  final int beamHeadColor, final int beamTailColor,
                  final double beamHeadHeight, final double beamTailHeight,
                  final int railRelightMaxBlockDist, final int railRelightCooldownMs,
                  final int railRelightMaxPerSec, final double railRelightFrustumCos,
                  final long sidingId, final boolean overridden) {
            this.enabled = enabled;
            this.dynamicLight = dynamicLight;
            this.maxDistance = maxDistance;
            this.headLuminance = headLuminance;
            this.tailLuminance = tailLuminance;
            this.headLength = headLength;
            this.tailLength = tailLength;
            this.railLight = railLight;
            this.beamGlow = beamGlow;
            this.beamDistance = beamDistance;
            this.beamHeadLength = beamHeadLength;
            this.beamTailLength = beamTailLength;
            this.beamWidth = beamWidth;
            this.beamHeadColor = beamHeadColor;
            this.beamTailColor = beamTailColor;
            this.beamHeadHeight = beamHeadHeight;
            this.beamTailHeight = beamTailHeight;
            this.railRelightMaxBlockDist = railRelightMaxBlockDist;
            this.railRelightCooldownMs = railRelightCooldownMs;
            this.railRelightMaxPerSec = railRelightMaxPerSec;
            this.railRelightFrustumCos = railRelightFrustumCos;
            this.sidingId = sidingId;
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
            return new Effective(enabled, dynamicLight, maxDistance, headLuminance, tailLuminance,
                    headLength, tailLength, railLight, beamGlow, beamDistance, beamHeadLength,
                    beamTailLength, beamWidth, parseColor(beamHeadColor, DEFAULT_HEAD_BEAM),
                    parseColor(beamTailColor, DEFAULT_TAIL_BEAM),
                    beamHeadHeight, beamTailHeight,
                    railRelightMaxBlockDist, railRelightCooldownMs, railRelightMaxPerSec,
                    railRelightFrustumCos, sidingId, false);
        }
        return new Effective(
                o.enabled != null ? o.enabled.booleanValue() : enabled,
                o.dynamicLight != null ? o.dynamicLight.booleanValue() : dynamicLight,
                o.maxDistance != null ? o.maxDistance.doubleValue() : maxDistance,
                o.headLuminance != null ? o.headLuminance.intValue() : headLuminance,
                o.tailLuminance != null ? o.tailLuminance.intValue() : tailLuminance,
                o.headLength != null ? o.headLength.doubleValue() : headLength,
                o.tailLength != null ? o.tailLength.doubleValue() : tailLength,
                o.railLight != null ? o.railLight.booleanValue() : railLight,
                o.beamGlow != null ? o.beamGlow.booleanValue() : beamGlow,
                o.beamDistance != null ? o.beamDistance.doubleValue() : beamDistance,
                o.beamHeadLength != null ? o.beamHeadLength.doubleValue() : beamHeadLength,
                o.beamTailLength != null ? o.beamTailLength.doubleValue() : beamTailLength,
                o.beamWidth != null ? o.beamWidth.doubleValue() : beamWidth,
                parseColor(o.beamHeadColor != null ? o.beamHeadColor : beamHeadColor, DEFAULT_HEAD_BEAM),
                parseColor(o.beamTailColor != null ? o.beamTailColor : beamTailColor, DEFAULT_TAIL_BEAM),
                o.beamHeadHeight != null ? o.beamHeadHeight.doubleValue() : beamHeadHeight,
                o.beamTailHeight != null ? o.beamTailHeight.doubleValue() : beamTailHeight,
                o.railRelightMaxBlockDist != null
                        ? o.railRelightMaxBlockDist.intValue() : railRelightMaxBlockDist,
                o.railRelightCooldownMs != null
                        ? o.railRelightCooldownMs.intValue() : railRelightCooldownMs,
                o.railRelightMaxPerSec != null
                        ? o.railRelightMaxPerSec.intValue() : railRelightMaxPerSec,
                o.railRelightFrustumCos != null
                        ? o.railRelightFrustumCos.doubleValue() : railRelightFrustumCos,
                sidingId,
                !o.isEmpty());
    }

    /**
     * True when <em>some</em> train may want rail light: either the global switch is on, or a siding
     * overrides it on. The rail relight scheduler must keep running in the second case even though
     * the global switch is off, exactly like {@link #beamAnySiding()} for the beams.
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

    /** True when a beam may be drawn for at least one siding (see {@link #railLightAnySiding()}). */
    public boolean beamAnySiding() {
        if (beamGlow) {
            return true;
        }
        if (sidingSettings != null) {
            for (final SidingSettings o : sidingSettings.values()) {
                if (o != null && Boolean.TRUE.equals(o.beamGlow)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Parses {@code #RRGGBB} / {@code RRGGBB} (case-insensitive, {@code #}-optional) into
     * {@code 0xRRGGBB}, falling back when the string is missing or malformed. Every read goes
     * through this, so a hand-edited JSON can never feed a NaN colour into a vertex buffer.
     */
    public static int parseColor(final String text, final int fallback) {
        if (text == null) {
            return fallback;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Formats a colour the way the config/JSON stores it: {@code #RRGGBB}, upper case. */
    public static String formatColor(final int rgb) {
        return String.format("#%06X", Integer.valueOf(rgb & 0xFFFFFF));
    }

    /**
     * <b>The single source of truth for the shipped defaults.</b>
     *
     * <p>A field's initialiser <em>is</em> its default, so this returns a plain {@code new
     * HeadlightConfig()} instead of a second table of numbers. The config screens' per-row 恢复默认
     * button and 全部恢复默认 copy the field they touch out of this instance, which is the same value
     * a first start writes to {@code mtr-headlight.json}. Changing a default in exactly one place
     * therefore changes both the fresh file and every reset button - hard-coding the numbers in the
     * screen would let the two drift apart after the next tuning pass.
     *
     * <p>The returned object is a throw-away: read fields from it, never write to it and never hand
     * it out as the live config.
     */
    public static HeadlightConfig defaults() {
        return new HeadlightConfig();
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
        if (configVersion < 5 && railRelightMaxPerSec == 12) {
            // 12 was the default while the host rebuilt one rail chunk per frame; it is now the
            // per-second share of a larger per-frame slot (see railChunkBuildPerFrame), so an
            // untouched file is moved onto the new default. A hand-tuned 12 is indistinguishable
            // from the old default here, which is the same rule the version-2 step uses.
            railRelightMaxPerSec = 16;
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
                if (o.beamDistance != null) {
                    o.beamDistance = clamp(o.beamDistance, 16.0D, 512.0D);
                }
                if (o.beamHeadLength != null) {
                    o.beamHeadLength = clamp(o.beamHeadLength, 0.0D, 64.0D);
                }
                if (o.beamTailLength != null) {
                    o.beamTailLength = clamp(o.beamTailLength, 0.0D, 64.0D);
                }
                if (o.beamWidth != null) {
                    o.beamWidth = clamp(o.beamWidth, 0.5D, 8.0D);
                }
                if (o.beamHeadHeight != null) {
                    o.beamHeadHeight = clamp(o.beamHeadHeight, 0.0D, 4.0D);
                }
                if (o.beamTailHeight != null) {
                    o.beamTailHeight = clamp(o.beamTailHeight, 0.0D, 4.0D);
                }
                if (o.beamHeadColor != null) {
                    o.beamHeadColor = formatColor(parseColor(o.beamHeadColor, DEFAULT_HEAD_BEAM));
                }
                if (o.beamTailColor != null) {
                    o.beamTailColor = formatColor(parseColor(o.beamTailColor, DEFAULT_TAIL_BEAM));
                }
                if (o.railRelightMaxBlockDist != null) {
                    o.railRelightMaxBlockDist = clamp(o.railRelightMaxBlockDist, 16, 256);
                }
                if (o.railRelightCooldownMs != null) {
                    o.railRelightCooldownMs = clamp(o.railRelightCooldownMs, 0, 5000);
                }
                if (o.railRelightMaxPerSec != null) {
                    o.railRelightMaxPerSec = clamp(o.railRelightMaxPerSec, 1, 40);
                }
                if (o.railRelightFrustumCos != null) {
                    o.railRelightFrustumCos = clamp(o.railRelightFrustumCos, -1.0D, 0.95D);
                }
            });
            sidingSettings.values().removeIf(SidingSettings::isEmpty);
        }
        headLuminance = clamp(headLuminance, 0, 15);
        tailLuminance = clamp(tailLuminance, 0, 15);
        headLength = clamp(headLength, 0.0D, 32.0D);
        tailLength = clamp(tailLength, 0.0D, 32.0D);
        maxDistance = clamp(maxDistance, 16.0D, 1024.0D);
        massClearRatio = clamp(massClearRatio, 0.2D, 1.0D);
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
        railRelightLoadGraceSec = clamp(railRelightLoadGraceSec, 0.0D, 60.0D);
        railRelightMaxBlockDist = clamp(railRelightMaxBlockDist, 16, 256);
        railRelightCooldownMs = clamp(railRelightCooldownMs, 0, 5000);
        railRelightMaxPerSec = clamp(railRelightMaxPerSec, 1, 40);
        railChunkBuildPerFrame = clamp(railChunkBuildPerFrame, 1, RailChunkBuildBoost.MAX_PER_FRAME);
        railRelightFrustumCos = clamp(railRelightFrustumCos, -1.0D, 0.95D);
        beamDistance = clamp(beamDistance, 16.0D, 512.0D);
        beamHeadLength = clamp(beamHeadLength, 0.0D, 64.0D);
        beamTailLength = clamp(beamTailLength, 0.0D, 64.0D);
        beamWidth = clamp(beamWidth, 0.5D, 8.0D);
        beamHeadHeight = clamp(beamHeadHeight, 0.0D, 4.0D);
        beamTailHeight = clamp(beamTailHeight, 0.0D, 4.0D);
        beamLift = clamp(beamLift, 0.0D, 0.5D);
        beamAlpha = clamp(beamAlpha, 0.0D, 1.0D);
        beamHeadColor = formatColor(parseColor(beamHeadColor, DEFAULT_HEAD_BEAM));
        beamTailColor = formatColor(parseColor(beamTailColor, DEFAULT_TAIL_BEAM));
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

    /**
     * Serialised copy of the whole config - the config screen's undo snapshot.
     *
     * <p>Opening a screen takes one snapshot; 取消 / 返回 hands it back to
     * {@link #fromJson(String)}, so every edit made while that screen was open is rolled back in
     * one go. Nothing is written to disk unless the player confirms, so a rollback can never
     * rewrite a file that was already saved before the screen opened.
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /** Rebuilds a config from a {@link #toJson()} snapshot (clamped like a freshly loaded file). */
    public static HeadlightConfig fromJson(final String json) {
        try {
            final HeadlightConfig cfg = GSON.fromJson(json, HeadlightConfig.class);
            if (cfg != null) {
                return cfg.sanitized();
            }
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.warn("Could not read a config snapshot - keeping the live one", e);
        }
        return new HeadlightConfig();
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
