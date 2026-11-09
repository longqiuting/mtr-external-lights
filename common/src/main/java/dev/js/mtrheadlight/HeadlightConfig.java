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
 * Plain JSON config, written to {@code config/mtrlte.json} on first start.
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
    private static final int CONFIG_VERSION = 19;

    /**
     * {@code railChunkBuildPerFrame} 的上限（每帧最多重建几个轨道区块）。
     *
     * <p>保留在这里而不是引用 {@code RailChunkBuildBoost.MAX_PER_FRAME}：那个类是客户端渲染类，
     * 而 {@code HeadlightConfig} 会被服务端入口（{@code server.LteServer}）加载 —— 常量池里多一个
     * 渲染类，dedicated server 就多一份「加载了它」的风险，而这里只用到一个小整数。
     * 两处数值必须一致（{@code RailChunkBuildBoost} 有断言用的同一个 8）。
     */
    private static final int MAX_RAIL_CHUNK_BUILD_PER_FRAME = 8;

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
     * <p>Flippable in game - {@code /mtr_lte ldlreg off} - so the comparison needs no
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
     * Draw the head <b>beam cone</b> ({@code ProtoBeamCone}) - the volumetric light cone out of the
     * nose, on its own switch since it and the flat {@link #beamGlow} sheet are two different
     * effects (one is a soft ground sheet, the other a see-through cone).
     *
     * <p>Independent of {@link #beamGlow} in both directions: turning the sheet off leaves the cone
     * exactly as it was and vice versa. Both are still children of {@link #enabled}, so the master
     * switch off means neither draws. Default on.
     */
    public boolean beamCone = true;

    /** {@link #beamConeSource}：光柱用车型灯位（{@link VehicleLights}），默认。 */
    public static final String CONE_SOURCE_MODEL = "MODEL";
    /** {@link #beamConeSource}：光柱用光带（{@link BeamGlow}）的几何，完全不读车型灯位。 */
    public static final String CONE_SOURCE_GLOW = "GLOW";

    /**
     * 光柱的<b>几何来源</b>：{@code MODEL}（默认）或 {@code GLOW}。这是为了把
     * 「灯位绑错了」和「光柱这条路根本画不出来」分开而加的对照开关。
     *
     * <ul>
     *   <li>{@code MODEL} —— 光柱按车型的灯位槽（{@link VehicleLights}，含实测内缩、左右灯对、
     *       逐盏分摊 alpha）生成。用户看到光带在车上、光柱不在车上时，这一档就是原来的行为。</li>
     *   <li>{@code GLOW} —— 光柱<b>不读 {@link VehicleLights}</b>，直接拿光带算出来的同一组
     *       {@code base/axis/length/height/colour}（同一个 {@link BeamGeom} 工厂 +
     *       {@link BeamGlow#startHeight}），只按 {@code headOffset/tailOffset} 与
     *       {@code beamLift + beamHead/TailHeight} 的默认估算摆位。于是光柱的灯口顶点与光带的
     *       第一排中心线顶点<b>逐位重合</b>。</li>
     * </ul>
     *
     * <p><b>怎么读这个对照：</b>切到 {@code GLOW} 后光柱出现且和光带重合 → 几何没问题，问题在
     * 灯位绑定（{@code MODEL} 那条路）；仍然看不到 → 几何已经和一条看得见的光带完全相同，问题在
     * 光柱自己的渲染路径（raymarch / 壳层 / 深度 / GL 状态）。切换是实时的：
     * {@code /mtr_lte debug coneSource model|glow|status}。
     *
     * <p>字符串而不是 enum：Gson 反序列化非法枚举名会抛 {@code JsonSyntaxException}，把整份配置
     * 判成"读不出来"并回落默认值；字符串最坏只是这一个字段被
     * {@link #normalizeConeSource(String)} 收回 {@code MODEL}。全局字段，不逐侧线覆盖。
     */
    public String beamConeSource = CONE_SOURCE_MODEL;

    /** 归一化后的来源：{@code null} / 空串 / 拼错的值都回落 {@link #CONE_SOURCE_MODEL}。 */
    public String resolvedConeSource() {
        return normalizeConeSource(beamConeSource);
    }

    /** 光柱是否走光带的几何源（{@code GLOW}），即完全不读车型灯位。 */
    public boolean coneSourceGlow() {
        return CONE_SOURCE_GLOW.equals(resolvedConeSource());
    }

    /**
     * 把任意输入收回两种来源之一。空值 / 拼错一律 {@link #CONE_SOURCE_MODEL}——配置坏了就回到
     * 原来的行为，绝不因为一个字段抛异常。
     */
    public static String normalizeConeSource(final String raw) {
        if (raw == null) {
            return CONE_SOURCE_MODEL;
        }
        return CONE_SOURCE_GLOW.equalsIgnoreCase(raw.trim())
                ? CONE_SOURCE_GLOW : CONE_SOURCE_MODEL;
    }

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

    /**
     * Peak alpha of the <b>prototype beam cone</b> ({@code ProtoBeamCone}), clamped to [0.02, 0.5].
     *
     * <p>This is the alpha <em>budget</em> of the beam, not a single vertex value: before the
     * {@link ProtoBeamCone} attenuation floor, a sight line through the axis accumulated roughly
     * this much per wall. With the floor in place the darkest vertex is {@code 0.25 * this} and the
     * core still sums the four shells.
     *
     * <p>The attenuators that multiply it down:
     * <ul>
     *   <li>longitudinal {@code exp(-2.5 t) * (1 - t)} -&gt; 1.0 at the lamp, 0 at the tip;</li>
     *   <li>radial {@code (1 - r^2)^2} split over four nested shells (0.068 / 0.365 / 0.379 / 0.172);</li>
     *   <li>view angle {@code 0.45 + 0.55 * |n.v|^1.5} -&gt; 1.0 facing the camera, 0.45 at grazing;</li>
     *   <li>ground clearance smoothstep -&gt; 0 under the surface, 1 from 1 block up.</li>
     * </ul>
     * Radial × view × ground are floored at 0.25 of the budget (see
     * {@code ProtoBeamCone.MIN_ATTENUATION}); the longitudinal term is not, so the tip still fades
     * to nothing.
     *
     * <p>Default 0.30: at the old 0.16 the products above landed on ~0.003 per vertex and the beam
     * rendered as "10080 quads, nothing on screen". 0.5 already reads as fog.
     */
    public double beamConeAlpha = 0.30D;

    /**
     * Depth-aware occlusion for the beam cone (1.20.1 only): every ray-march sample is compared
     * against the scene depth, so a beam is cut off by a wall / platform / car body instead of
     * painting over it.
     *
     * <p>When off - and automatically whenever a shader pack (Iris / OptiFine) is active, or the
     * depth path failed to initialise - the beam falls back to the older additive shell cone,
     * which ignores occlusion. Nothing else changes; {@link #beamConeAlpha} still applies.
     *
     * <p>This is the A/B switch for verification: {@code /mtr_lte cone occl off} turns it off live.
     */
    public boolean beamConeOcclusion = true;

    /**
     * 光柱的<b>渲染路径</b>：{@code false}（默认）= 所有真实光束走加色壳层锥
     * （{@link ProtoBeamCone} 的四层同心壳 + {@code GlowCompat.draw}，和 {@code /mtr_lte debug cone}
     * 探针同一条被验证可见的管道）；{@code true} = 保留 1.20.1 的 raymarch 体积渲染
     * （{@code ConeVolumeRenderer}，可被墙/车体遮挡）。
     *
     * <p><b>为什么默认关掉 raymarch：</b>实机日志里它报 {@code volBeams>0 / ms≈2.3 / occluded=0/0}
     * 却屏幕上什么都没有——提交了光束、代价也算得出来，但输出是空的。壳层那条路用户已经用探针
     * 验证过可见，所以在 raymarch 修好之前，默认走壳层保证"至少能看见光柱"。
     *
     * <p>切回 raymarch：{@code /mtr_lte cone volume on}（写 {@code config/mtrlte.json}，下一帧生效）。
     * 遮罩开关 {@link #beamConeOcclusion} 只在 {@code volume=true} 时有意义；壳层路径没有深度测试。
     */
    public boolean beamConeVolume = false;

    /**
     * 尾灯也画光柱。默认 {@code false}：光柱只从<b>头灯</b>射出。
     *
     * <p>为什么默认关：实机反馈「尾灯也被同步画了光柱」。头灯是常亮的（夜间/隧道/雨天才开），
     * 尾灯只在刹车/驻车时亮，而自动匹配在两端都找得到灯位 —— 于是每列车背后都拖着一条红锥，
     * 既不符合车辆行为，也把「只给头灯」这一条要求直接破坏掉。
     *
     * <p>关掉只影响<b>光柱</b>：{@link VehicleLights.Layout#tail} 照旧解析（动态尾灯与地面光带
     * 都要用它），光带 {@code beamGlow} 也照旧画。打开：{@code /mtr_lte cone tail on}，
     * 或手改 {@code config/mtrlte.json} 的 {@code "coneOnTail": true}。
     */
    public boolean coneOnTail = false;

    /**
     * Ray-march samples per fragment for the depth-aware cone, clamped to [4, 16]. Default 10.
     *
     * <p>Each sample costs one texture fetch (scene depth) and one projection, so this is the
     * whole quality/cost dial of the effect: 4 still cuts the beam at a wall but bands visibly,
     * 16 is smooth and roughly 1.6x the cost of 10 on a full-screen beam. Lower it on a weak GPU;
     * the {@code [lte] cone: steps=... ms=...} line reports what it actually cost.
     */
    public int beamConeSteps = 10;

    /** {@link #beamConeSteps} clamped to the supported range [4, 16]. */
    public int beamConeSteps() {
        if (beamConeSteps < 4) {
            return 4;
        }
        return Math.min(beamConeSteps, 16);
    }

    /**
     * Bake the head beam into <b>vanilla terrain vertices</b> as real block light, so blocks and
     * rails actually get brighter instead of only having a translucent sheet drawn over them.
     *
     * <p><b>Prototype, default off, and off for a reason.</b> Minecraft bakes light into the chunk
     * mesh: there is no runtime "add a spot light" call. The only way to brighten a block is to
     * raise the block-light nibble while the section is being meshed and then <em>rebuild that
     * section</em>. A section is 16x16x16 blocks; a beam with a 12 block radius that moves 20
     * blocks/s touches roughly 30-40 sections per second, and each of those has to be rebuilt two
     * or three times as the beam sweeps through it. That is 60-120 full section rebuilds per
     * second - the same order as streaming a whole render distance - for one train. See
     * {@code docs/beam-block-light-report.md} for the numbers and the measured cost.
     *
     * <p>Vanilla render path only. With Sodium or an Iris/OptiFine shader pack the prototype logs
     * why it does nothing and stays out of the way.
     */
    public boolean beamBlockLight = false;
    /**
     * Radius of the beam as a <em>block light source</em>, in blocks, clamped to [4, 32].
     *
     * <p>Independent of {@link #beamHeadLength} (how far the drawn sheet reaches): this is the
     * distance at which the baked light has decayed to nothing, i.e. how many sections have to be
     * rebuilt. Every block of radius costs roughly {@code 2 * radius / 16} extra sections per
     * section of travel, which is why the default is 12 and not 24.
     */
    public int beamBlockLightRadius = 12;

    // ---------------------------------------------------------------------------------------------
    // 车灯开关模式 (headlightMode) - 判定见 RailWeather
    // ---------------------------------------------------------------------------------------------

    /** {@link #headlightMode}：自动（夜晚 / 隧道 / 雨天 / 雷暴），默认值。 */
    public static final String MODE_AUTO = "AUTO";
    /** {@link #headlightMode}：常亮，无视环境（车一出库就亮）。 */
    public static final String MODE_ALWAYS = "ALWAYS";
    /**
     * {@link #headlightMode}：不做自动关灯，保持"只要发车就亮"的手动行为。
     *
     * <p>名字容易读反，这里写清楚：<b>{@code OFF} 关掉的是"自动开关灯"这个功能，不是车灯</b>。
     * 灯仍然由发车闸门（{@code DepartureState}）和主开关决定，所以效果与 {@link #MODE_ALWAYS}
     * 相同，区别只在语义——{@code ALWAYS} 是"我要它一直亮"，{@code OFF} 是"别替我关"。
     * 想彻底关灯请用 {@code enabled} / {@code dynamicLight} / {@code beamGlow}。
     */
    public static final String MODE_OFF = "OFF";

    /**
     * 车灯开关模式：{@code AUTO} / {@code ALWAYS} / {@code OFF}，默认 {@code AUTO}。
     *
     * <p><b>为什么是字符串而不是 enum：</b>Gson 反序列化一个非法枚举名会抛
     * {@code JsonSyntaxException}，整个配置文件随即被当成"读不出来"回落默认值——玩家手改错一个
     * 字母就把全文件的调参丢了。字符串字段最坏只有一个字段非法，由
     * {@link #normalizeHeadlightMode(String)} 收回 {@code AUTO}，其它字段全部保留。大小写与前后
     * 空格不敏感（{@code "always"}、{@code " OFF "} 都认）。
     *
     * <p>语义（{@code AUTO} 走 {@link RailWeather#lightsNeeded}）：
     * <ul>
     *   <li>{@code AUTO}——夜（{@code getTimeOfDay() % 24000} 在 13000–23000）/ 天空亮度
     *       &lt; 0.5 / 雨 / 雷 / 隧道内开灯，白天露天关灯；</li>
     *   <li>{@code ALWAYS}——常亮；</li>
     *   <li>{@code OFF}——不自动关灯（等价旧的 {@code autoLightsInTunnel = false}）。</li>
     * </ul>
     */
    public String headlightMode = MODE_AUTO;

    /** 归一化后的模式：{@code null} 或任何不认识的字符串都回落 {@link #MODE_AUTO}。 */
    public String resolvedMode() {
        return normalizeHeadlightMode(headlightMode);
    }

    /**
     * 只有 {@code AUTO} 让环境判定接管车灯；{@code ALWAYS} / {@code OFF} 都返回 false，
     * 即"自动关灯不生效，车一发车就亮"。
     */
    public boolean autoHeadlights() {
        return MODE_AUTO.equals(resolvedMode());
    }

    // ---------------------------------------------------------------------------------------------
    // 车厂停放判定 (parkedLightsOff) - 判定见 DepartureState
    // ---------------------------------------------------------------------------------------------

    /**
     * 停放中的列车不亮灯：默认 {@code true}。
     *
     * <p>发车闸门 {@link DepartureState} 只有两个信号——MTR 自己的 {@code getIsOnRoute()}，
     * 以及本客户端<b>亲眼看到</b>这列车开动过的闩锁。闩锁一旦置位就不会自己掉（它记录的确实是
     * "出过库"这个事实），所以一列开回车辆段的列车仍然亮着灯。这个开关补的就是最后这一段：
     * <b>不在运行线路上 + 已经开动过 + 长时间静止</b> 三条同时成立 = 判定为停放，灯灭。
     *
     * <p>站台停靠不受影响：{@code getIsOnRoute()} 在站台一般是 {@code true}，闸门第一条就放行。
     *
     * <p>关掉它回到旧行为（只看 {@code moved} 闩锁），用于 A/B 对比。
     */
    public boolean parkedLightsOff = true;

    /**
     * 停放判定阈值：{@code |getSpeed()| < 0.01}（格/tick）持续超过这么多 tick 就算停放。
     * 默认 {@code 200}（10 秒 @ 20 tps）。{@code 0} = 只要停一下就算停放（很敏感），
     * 负数按 {@code 0} 处理（见 {@link #parkIdleTicks()}）。
     */
    public int parkedLightsOffTicks = 200;

    /** {@link #parkedLightsOffTicks} 夹到 {@code >= 0}，负数当作 0。 */
    public int parkIdleTicks() {
        return Math.max(0, this.parkedLightsOffTicks);
    }

    /**
     * 把任意输入收回三种模式之一。合法值大小写不敏感；{@code null}、空串、拼错的值一律
     * {@link #MODE_AUTO}——"配置坏了就回到默认"，绝不因为一个字段抛异常或关掉整车的灯。
     */
    public static String normalizeHeadlightMode(final String raw) {
        if (raw == null) {
            return MODE_AUTO;
        }
        final String trimmed = raw.trim();
        if (MODE_ALWAYS.equalsIgnoreCase(trimmed)) {
            return MODE_ALWAYS;
        }
        if (MODE_OFF.equalsIgnoreCase(trimmed)) {
            return MODE_OFF;
        }
        // 包含 "AUTO" 本身以及一切非法值。
        return MODE_AUTO;
    }

    /**
     * 旧开关，<b>已不参与任何判定</b>，只为让旧 JSON 还能读进来。
     *
     * <p>兼容映射在 {@link #migrate()} 里做（配置版本 8）：
     * {@code autoLightsInTunnel = true} → {@link #MODE_AUTO}，
     * {@code false} → {@link #MODE_OFF}（两者语义相同：不自动关灯）。一个版本之后连同
     * {@code migrate()} 里那一步一起删。
     *
     * <p>为什么不再"两个字段都读"：{@code AUTO} 展开成旧字段是 true，{@code ALWAYS}/{@code OFF}
     * 展开都是 false——双向映射不唯一，同时保留两份真相一定会漂移，所以旧字段只在迁移时读一次。
     *
     * @deprecated 用 {@link #headlightMode}，见上面的映射表。
     */
    @Deprecated
    public boolean autoLightsInTunnel = true;

    /**
     * 诊断开关：每 5 秒为每列车打一行
     * {@code auto light: mode=AUTO openSky=true night=true sky=0.20 lit=on}（INFO，最多 8 行 +
     * 一条 {@code auto light: sampled= trains=}）。
     *
     * <p>五个字段各自独立，一行就能定位问题出在哪一环：{@code mode} 是当前模式，{@code openSky}
     * 是露天判定的原始结论，{@code night} 是时钟判定，{@code sky} 是天空亮度（0.20 = 午夜，
     * 1.00 = 正午），{@code lit} 是这道闸的最终结论。默认开，在
     * {@code config/mtrlte.json} 里设为 {@code false} 可关。
     */
    public boolean weatherDiagnostics = true;


    // ---------------------------------------------------------------------------------------------
    // 内置车型过滤 (disableBuiltInTrains) - MC 1.20.1 的 TrainClientRegistry
    // ---------------------------------------------------------------------------------------------

    /**
     * 隐藏 MTR 自带的 114 个车型（sp1900 / m_train / light_rail_* / 船 / 矿车 …），车厂选车面板
     * 里只剩资源包 / 附属模组注册的车型。默认关（先不默认开）。
     *
     * <p>不是"一刀切不加载"：MTR 3.6.4.2 的内置车型是在
     * {@code TrainClientRegistry#reset()} 里整体注册的（资源重载时由 {@code CustomResources}
     * 调用），<b>当前存档正在用的车型必须留着</b>，否则列车没有渲染器。所以做法是
     * 「先让 reset() 正常跑完 → 立刻把没在用的内置 id 从注册表里剔掉」
     * （见 {@code BuiltInTrains}）。在用的判定同时看 {@code ClientData.TRAINS} 的
     * {@code trainId} 和 {@code ClientData.SIDINGS} 的 {@code getTrainId()}。
     *
     * <p>资源包注册的车型永远不会被动：内置 id 集合是 reset() 结束时（此时注册表里<b>只有</b>
     * 内置车型）抓的快照，资源包的注册发生在这之后。
     *
     * <p>打开后车厂面板里就选不到被剔掉的内置车型了——这正是本开关的目的；想再用内置车就把
     * 它关掉，下次客户端 tick 会把注册表恢复原样。
     */
    public boolean disableBuiltInTrains = false;

    /**
     * 诊断开关：每次裁剪都打一行
     * {@code builtin trains: snapshot=114 builtin=114 external=137/137 used=9 removed=105 kept=9}，
     * 并另起一行打印被删 id 的前 5 个（INFO）。默认开。
     *
     * <p>{@code external=P/T} 是关键那一列：{@code T} 是资源包 / 附属模组注册过、受保护的车型
     * 总数，{@code P} 是裁剪之后仍然在注册表里的数量。<b>两者相等</b>才说明资源包的车型一个
     * 都没掉；{@code removed} 只会是 114 个内置 id 里没在用的那些。被删 id 那一行用来核对
     * ——它必须全是 {@code sp1900_emu} / {@code m_train} 这类内置名。
     */
    public boolean builtinTrainsDiagnostics = true;

    // ---------------------------------------------------------------------------------------------
    // Rain spray (雨天露天行驶的水雾) - MC 1.20.1 only
    // ---------------------------------------------------------------------------------------------

    /**
     * 雨天露天行驶时，列车两侧刮起的水雾/水花粒子。默认开。
     *
     * <p>三个条件缺一不可，判定都在 {@code RainSpray} 里：
     * <ol>
     *   <li>世界正在下雨（{@code ClientWorld#isRaining()} 且雨量渐变 &gt; 0.2，避免雨刚起/刚停
     *       时闪一下）；</li>
     *   <li>列车速度 &gt; {@link #rainSprayMinSpeedKmh}（速度取自 {@code Train#getSpeed()}，
     *       单位 blocks/tick，乘 {@code 20 * 3.6} 换成 km/h —— 与 MTR 自己
     *       {@code RenderDrivingOverlay} 的换算完全一致）；</li>
     *   <li>该车厢所在位置露天（{@link RailWeather#openSky} —— 与车灯"进隧道开灯"同一判定），
     *       隧道、棚下、车库里都不会有。</li>
     * </ol>
     *
     * <p>强度随速度线性上升（{@link #rainSprayMinSpeedKmh} 起零星，120 km/h 明显），并且是
     * <b>硬限流</b>的：每列车每 tick 最多 2 个粒子、全局每 tick 最多 8 个、全局每秒约 60 个
     * （见 {@code RainSpray} 的常量），所以再多列车同时高速跑也不会把粒子系统打爆。
     */
    public boolean rainSpray = true;

    /**
     * 生成水雾的最低速度，单位 km/h，钳制在 0–200。默认 50。
     *
     * <p>速度来源是 {@code Train#getSpeed()}（blocks/tick）× 72；本值越大，只有真正跑起来的
     * 车才有水雾。设为 0 等于「任何在雨里露天行驶的车都溅水」（停车时速度为 0，仍然不会有）。
     */
    public int rainSprayMinSpeedKmh = 50;

    /**
     * 诊断开关：每 5 秒一条 {@code spray: trains=N spawned=M skipped=K}（INFO）。
     *
     * <p>{@code trains} 是这一窗内通过「下雨 + 超速 + 距离 64 格」的车次数（按列车去重），
     * {@code spawned} 是真正发出的粒子数，{@code skipped} 是被限流（每车每 tick 上限、
     * 全局每 tick/每秒预算、以及低速时的概率抽签）挡下来的候选数。默认开；在
     * {@code config/mtrlte.json} 里把 {@code rainSprayDiagnostics} 设为 {@code false}
     * 即可彻底闭嘴。
     */
    public boolean rainSprayDiagnostics = true;

    // ---------------------------------------------------------------------------------------------
    // 雨（在车厢里不画雨） - MC 1.20.1 only，见 dev.js.mtrheadlight.RainHush
    // ---------------------------------------------------------------------------------------------

    /**
     * 玩家在车厢内部时<b>不渲染雨</b>（也不渲染雪）。默认开。
     *
     * <p>做法是在 {@code WorldRenderer#renderWeather} 的 HEAD 直接 cancel，所以雨丝是真的不画，
     * 不是把粒子藏起来。判定用的是与碰撞系统同一套车厢几何（{@code TrainPose} 端点 + 配置高度），
     * 玩家整个 AABB 必须被车厢盒包住才算「在车内」：站车顶、站在门口门框上、站在两节车的贯通道
     * 正中间都不算。误差与连带影响见 {@code RainHush} 的类注释。
     *
     * <p>关掉这个开关就完全回到原版：渲染路径一行都不多走（{@code shouldSuppress} 第一件事就是
     * 读它）。
     */
    public boolean rainHush = true;

    /**
     * 「在车内不画雨」时是否<b>同时</b>停掉地面水花与雨声（{@code WorldRenderer#tickRainSplashing}）。
     * 默认开。
     *
     * <p>雨丝走 {@code renderWeather}，地面水花走 {@code tickRainSplashing} —— 两个入口。只关前者
     * 会出现「天上没雨、地上一直冒水圈」，一眼能看出来是关了一半。
     *
     * <p><b>这个开关同时管雨声</b>：{@code tickRainSplashing} 里除了 {@code ParticleTypes.RAIN}
     * 还播 {@code SoundEvents.WEATHER_RAIN} / {@code WEATHER_RAIN_ABOVE}。所以默认开 =
     * 车厢里既没有水花也没有雨声（关门的车厢，合理）；想保留雨声就设成 {@code false}。
     */
    public boolean rainHushSplash = true;

    /**
     * 诊断开关：每 5 秒一行
     * {@code rain: frames=N suppressed=M splash=K inside=? force=? switch=?}（INFO）。
     *
     * <p>{@code frames} 是 {@code renderWeather} 的注入点被调用的次数 —— 它恒为 0 说明 mixin 没
     * 落地或这条渲染路径没走（不是「开了但没生效」）。{@code suppressed} 是真正 cancel 掉的帧数。
     * 进/出车厢各多打一行 {@code rain: inside=true/false ...}。
     */
    public boolean rainHushDiagnostics = true;

    // ---------------------------------------------------------------------------------------------
    // 碰撞 (MC 1.20.1 only) - 见 dev.js.mtrheadlight.collision.CollisionSystem
    // ---------------------------------------------------------------------------------------------

    /**
     * 列车外部碰撞箱：玩家撞上车体时沿最小穿透轴被推出，车顶可以站人，车门开着时整侧放行。
     * 默认开。
     *
     * <p>碰撞箱由 {@code TrainPose} 的每节车厢两端点生成，沿车厢轴线切成若干短段
     * （<b>分段近似 OBB</b>：段数 = {@code ceil(车厢长 / 2)}，段间重叠 0.3 格，见
     * {@code CollisionSystem} 的类注释），每段在自己的局部坐标系里做侧向推出 —— 弯道上不再
     * 用「包住整节车」的轴对齐盒，因此不会被甩飞。高度取 {@link #trainCollisionHeight}，宽度按
     * MTR 车型的 {@code Train#width} 折算（下限 2.6 格宽），两端各外扩 {@link #trainCollisionEndPad}。
     *
     * <p>只在客户端生效：直接读写本地玩家的位置，不碰 MTR 的数据，也不发任何包。
     */
    public boolean trainCollision = true;

    /**
     * 车厢碰撞箱的高度（格，从轨面向上），钳制 1–8，默认 3.8。
     *
     * <p>3.8 覆盖 MTR 常见车型的车体高度（车顶 + 受电弓以下），站上车顶时脚底就落在
     * {@code 轨面 + 本值}。车厢在坡道上时这个平面随之倾斜（沿车厢方向线性插值），所以上坡的
     * 车顶不会被算成平的。
     */
    public double trainCollisionHeight = 3.8D;

    /** {@link #trainCollisionHeight} 钳制到 1–8 格（碰撞解算唯一读取的入口）。 */
    public double trainCollisionHeightClamped() {
        if (Double.isNaN(trainCollisionHeight)) {
            return 3.8D;
        }
        return Math.max(1.0D, Math.min(8.0D, trainCollisionHeight));
    }

    /**
     * 每节车厢两端各外扩的碰撞量（格），钳制 0–3，默认 1.0。
     *
     * <p>MTR 的车体模型比 {@code TrainPose} 的两个端点略长（车头整流罩、车尾端墙都画在端点
     * 之外），所以端面要往外补一段，否则贴着车头脸走过去会穿模。外扩段只做「端面」（沿轴向的
     * 推出），不做侧向加宽：宽度仍然等于车体宽度。两端各外扩 1 格后，相邻两节车在连接处的
     * 覆盖量是 {@code 2 × 1.0 + 0.3（段重叠）= 2.3} 格。
     */
    public double trainCollisionEndPad = 1.0D;

    /** {@link #trainCollisionEndPad} 钳制到 0–3 格。 */
    public double trainCollisionEndPadClamped() {
        if (Double.isNaN(trainCollisionEndPad)) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(3.0D, trainCollisionEndPad));
    }

    /**
     * 车头方向（<b>行进方向</b>最前一节车的前端）的碰撞延长量（格），钳制 0–4，默认 1.0。
     *
     * <p>和 {@link #trainCollisionEndPad} 是<b>同一个位置</b>的两种调法，所以两者取 {@code max}
     * 而不是相加：{@code max(endPad, headExtra)}。默认两者都是 1.0，因此不调的时候碰撞箱与只有
     * {@code endPad} 时完全一样。中间车的两端、头车的后端仍然只用 {@code endPad}。
     *
     * <p>「车头」按行驶方向算（{@code TrainPose} 的 {@code carStart(0)}），已经在终点站折返的列车
     * 会自动换端，不需要手动区分。
     */
    public double collisionHeadExtra = 1.0D;

    /** {@link #collisionHeadExtra} 钳制到 0–4 格。 */
    public double collisionHeadExtraClamped() {
        return clampEndExtra(collisionHeadExtra);
    }

    /**
     * 车尾方向（<b>行进方向</b>最后一节车的后端）的碰撞延长量（格），钳制 0–4，默认 1.0。
     *
     * <p>与 {@link #collisionHeadExtra} 对称：尾车后端取 {@code max(endPad, tailExtra)}，不叠加。
     * 单节车（只有一节）时前端看 headExtra、后端看 tailExtra，正好是列车的两头。
     */
    public double collisionTailExtra = 1.0D;

    /** {@link #collisionTailExtra} 钳制到 0–4 格。 */
    public double collisionTailExtraClamped() {
        return clampEndExtra(collisionTailExtra);
    }

    /**
     * 两个端部延长量的共用钳制：0–4 格，NaN 落到默认 1.0。全局值与侧线覆盖都走这里，
     * {@link Effective#collisionHeadExtra} / {@link Effective#collisionTailExtra} 里已经是钳制过的值。
     */
    static double clampEndExtra(final double value) {
        if (Double.isNaN(value)) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(4.0D, value));
    }

    /**
     * 车顶「滑落」的最低车速（km/h），钳制 0–300，默认 50。
     *
     * <p>玩家站在车顶上时默认被车带着走（移动平台语义）。只有车速高于本值<b>并且</b>该节车厢所在
     * 轨道的曲率半径小于 {@link #roofDropMaxRadiusM} 时才不载人 —— 高速急弯里车顶的离心趋势
     * 足够大，玩家被甩下列车；其余情况（停车、慢速、直道、缓弯）继续粘在车顶上。
     */
    public int roofDropMinSpeedKmh = 50;

    /**
     * 车顶滑落的曲率半径上限（格，1 格 = 1 m），钳制 10–1000，默认 100。
     *
     * <p>实际半径由轨道路径上三点估算（车厢中点 ±3 格取点，转角 / 弧长）。拿不到轨道、
     * 采样退化、或转角小于 ~1.1° 时一律当作<b>直道</b>（半径无穷大）—— 保守方向是「不甩人」。
     */
    public double roofDropMaxRadiusM = 100.0D;

    /** {@link #roofDropMaxRadiusM} 钳制到 10–1000 格。 */
    public double roofDropMaxRadiusMClamped() {
        if (Double.isNaN(roofDropMaxRadiusM)) {
            return 100.0D;
        }
        return Math.max(10.0D, Math.min(1000.0D, roofDropMaxRadiusM));
    }

    /**
     * 列车碰撞伤害总开关，默认开。
     *
     * <p>玩家 / 生物被<b>运行中</b>的列车撞到（解算真的把他推出去了一次）后按车速扣血，规则见
     * {@link #trainCollisionPushOnlyMaxKmh} 起的档位字段与 {@code CollisionSystem} /
     * {@code CollisionImpact}。创造 / 旁观 / 无敌实体不受影响（{@link #damageInCreative}
     * 可以把创造模式也放开）。
     * 走 {@code LivingEntity#damage(DamageSource, float)}，不直接改血量。
     */
    public boolean trainCollisionDamage = true;

    /**
     * 创造模式玩家是否也吃列车碰撞伤害，默认 <b>false</b>。
     *
     * <p>默认行为与原版一致：创造（{@code abilities.invulnerable}）/ 旁观 / 无敌实体免疫。
     * 打开后创造模式玩家也会被列车撞伤、撞飞并<b>能被撞死</b>（用于测试与特殊玩法），
     * 旁观模式仍然免疫。判定在 {@code CollisionImpact#damageable}，致死的那一记补刀走同一条路。
     */
    public boolean damageInCreative = false;

    /**
     * 只推开、不扣血的车速上限（km/h），钳制 0–300，默认 <b>5</b>。
     *
     * <p>这是指数伤害的起点 {@code v0}：{@code v < 本值} 时只把实体推出车体，不造成任何伤害。
     */
    public int trainCollisionPushOnlyMaxKmh = 5;

    /** 低速档上限（km/h），钳制到 {@code [pushOnly, 300]}，默认 <b>20</b>。5–20 为「低速率扣血」。 */
    public int trainCollisionLowDamageMaxKmh = 20;

    /**
     * 高速档上限（km/h），钳制到 {@code [low, 300]}，默认 <b>50</b>。
     *
     * <p>20–50 为「较高速率扣血」；{@code v ≥ 本值} 额外施加撞飞冲量，同时死亡消息切到高速那一段
     * （<b>本值也是两段死亡消息的分界</b>，见 {@code TrainImpactDamageSource}）。
     */
    public int trainCollisionHighDamageMaxKmh = 50;

    /**
     * 飞出档起点（km/h），钳制到 {@code [high, 400]}，默认 <b>80</b>。
     *
     * <p>{@code 50 ≤ v < 本值}：撞飞冲量（{@link #trainCollisionLaunchHorizontal} /
     * {@link #trainCollisionLaunchVertical}）。{@code v ≥ 本值}：更大的冲量
     * （{@link #trainCollisionFlyHorizontal} / {@link #trainCollisionFlyVertical}），并且飞行/落地
     * 过程中持续扣血直到死亡（伤害再乘 {@link #trainCollisionFlyDamageScale}）。
     */
    public int trainCollisionLaunchMaxKmh = 80;

    /**
     * 指数伤害的基准速率（hp/s，1 hp = 半颗心），钳制 0–20，默认 <b>0.56</b>。
     *
     * <p>公式（{@code v} 为车速 km/h，{@code v0 = trainCollisionPushOnlyMaxKmh}）：
     *
     * <pre>
     * hpPerSecond(v) = base · exp(k · (v − v0) / scale) · (飞出档 ? flyScale : 1)
     * hpPerTick(v)   = hpPerSecond(v) / 20        // 按 tick 积分，累积到整点血量再扣
     * </pre>
     *
     * <p>{@code scale} 默认取 100 km/h 而不是 {@code v0}：{@code v0 = 5} 时
     * {@code exp(k(v−5)/5)} 在 50 km/h 就已经是 {@code e^18}，人会瞬死。把 scale 调成等于 v0 就
     * 退化成需求里的示例式 {@code base·exp(k(v−v0)/v0)}。
     *
     * <p><b>0.56 / k=1.35 / scale=100 是按「58–80 km/h 约 15–25 秒致死」倒推的</b>（20 hp ÷
     * 速率）：58 → 1.15 hp/s（17 s，含自然回血约 22 s），80 → 1.55 hp/s（13 s / 15 s）。
     * 旧默认 0.5 / 2.0 / 3.0 倍 flyScale 让 58 只要 14 s、80 只要 3 s，但<b>接触只持续几 tick</b>，
     * 实际只掉 2–3 hp —— 那才是「扣血偏慢、死亡界面没机会出现」的根因，不是公式本身。
     */
    public double trainCollisionDamageBasePerSecond = 0.56D;

    /** 指数伤害的系数 {@code k}，钳制 0–8，默认 <b>1.35</b>。越大，高速档与低速档的差距越夸张。 */
    public double trainCollisionDamageExpK = 1.35D;

    /** 指数伤害的速度尺度（km/h），钳制 10–1000，默认 <b>100</b>。见 {@link #trainCollisionDamageBasePerSecond}。 */
    public double trainCollisionDamageSpeedScaleKmh = 100.0D;

    /**
     * 飞出档（{@code v ≥ trainCollisionLaunchMaxKmh}）的伤害倍率，钳制 1–10，默认 <b>1.0</b>。
     *
     * <p>默认 1.0 是刻意的：80 km/h 正好是「58–80 应 15–25 秒致死」这个区间的上端，在这里乘一个
     * 3 倍的跳变会让边界处直接掉到 4 秒、目标断成两截。速度越高伤害越快已经由指数项负责；
     * 想要 80+ 额外更狠就把这个值调大。
     */
    public double trainCollisionFlyDamageScale = 1.0D;

    /**
     * 撞击后的<b>持续掉血窗口</b>（tick），钳制 <b>40–600</b>，默认 <b>120</b>（6 s）。
     *
     * <p>列车撞人只接触几 tick（80 km/h 的车掠过 3 米只要 0.15 s），如果伤害只在接触的 tick 结算，
     * 总伤害只有 2–3 hp —— 一次撞击根本不疼。所以一次造成伤害的撞击会给实体记一个窗口：窗口内
     * <b>不再需要碰到车</b>，每 tick 都按撞击车速继续累积、<b>每 10 tick 用一次正规
     * {@code damage()} 结算</b>（{@code CollisionImpact#hurtDamage}），直到窗口结束或死亡。
     *
     * <p>旧默认 500 tick（25 s）是「15–25 秒致死」能成立的前提，但实际手感是「被撞飞以后抖很久才
     * 死亡」。现在改成 6 s：伤害速率公式完全不变，只是不再拖 25 秒；撞飞（真的给了冲量）的那些
     * 在<b>第一次落地</b>就把剩余预算一次结算掉（见 {@code CollisionImpact#settleOnLanding}），
     * 所以窗口长度不会再决定「多久才死」。
     *
     * <p>镜头抑制跟着这个窗口走：窗口内每 tick 清 {@code hurtTime}，窗口一结束立刻恢复。
     */
    public int trainCollisionHurtTicks = 120;

    /** {@link #trainCollisionHurtTicks} 钳制到 40–600 tick。 */
    public int trainCollisionHurtTicksClamped() {
        return clamp(trainCollisionHurtTicks, 40, 600);
    }

    /**
     * 一次接触最多扣多少血（hp，1 hp = 半颗心），钳制 0–200，默认 <b>0 = 不额外设限</b>。
     *
     * <p>和旧版（0.1.x）那个已删除的同名键不是一回事：旧版是「单次撞击的线性伤害上限」，
     * 现在伤害是按 tick 累积的指数式，这个值只作为<b>本次接触的累计上限</b>——
     * 累计到它之后不再扣血，直到接触结束（{@code CollisionImpact} 的 {@code SESSION_GRACE}）。
     * 0 表示只受实体自身最大生命的限制，也就是旧版行为。
     *
     * <p>侧线可以单独覆盖（{@link SidingSettings#trainCollisionDamageMax}），所以「这条支线的
     * 车撞不死人」不需要改全局。
     */
    public double trainCollisionDamageMax = 0.0D;

    /** {@link #trainCollisionDamageMax} 钳制到 0–200 hp。 */
    public double trainCollisionDamageMaxClamped() {
        return clamp(trainCollisionDamageMax, 0.0D, 200.0D);
    }

    /**
     * <b>撞飞距离</b>（格 = 方块，水平落地位移），钳制 <b>1–20</b>，默认 <b>5.5</b>。
     *
     * <p>这是「被撞飞多远」的<b>唯一</b>旋钮：水平初速不再手填，而是由它反推 ——
     * 竖直初速先决定滞空 tick 数 {@code n}，再用平抛公式反解水平初速：
     *
     * <pre>
     * 滞空 n tick（重力 0.08、竖直阻尼 0.98，见 CollisionImpact.flightTicks）
     * 水平每 tick 衰减 0.91（LivingEntity#travel 的空中摩擦）
     * 落地水平位移 = vx · Σ(0.91^k, k=0..n−1)   ⇒   vx = 距离 / Σ
     * </pre>
     *
     * <p>50–80 km/h（撞飞档）用它；&gt;= 80 km/h（飞出档）用它的 <b>1.5 倍</b>（更狠的那一下）。
     * 反解在 {@code CollisionImpact.knockbackHorizontal} 里做，离线积分见
     * {@code tools/knockback-sim.js}。
     */
    public double knockbackDistance = 5.5D;

    /** {@link #knockbackDistance} 钳制到 1–20 格。 */
    public double knockbackDistanceClamped() {
        return clamp(knockbackDistance, 1.0D, 20.0D);
    }

    /**
     * 撞飞档（50–80）的水平初速（格/tick）。
     *
     * @deprecated 不再参与计算：水平初速由 {@link #knockbackDistance} 与竖直初速反推
     *             （{@code CollisionImpact.knockbackHorizontal}）。字段保留只为让旧 JSON 还能读
     *             （Gson 会照常读进来），界面上也不再显示这一行。
     */
    @Deprecated
    public double trainCollisionLaunchHorizontal = 1.0D;

    /**
     * 撞飞档的竖直初速（格/tick，向上分量），钳制 0–4，默认 <b>0.45</b>。
     *
     * <p>0.45 是刻意的下限：原版跳跃初速 0.42，低于它「撞飞」看起来只是被平地推了一下。
     * 0.45 给出约 0.55 s 滞空 —— 玩家先看到自己飞起来，才在下一个伤害结算窗口（10 tick）掉血。
     *
     * <p>它同时决定 {@link #knockbackDistance} 需要多大的水平初速：滞空越久，同样的距离需要的
     * 水平速度越小。
     */
    public double trainCollisionLaunchVertical = 0.45D;

    /**
     * 飞出档（&gt;80）的水平初速（格/tick）。
     *
     * @deprecated 同 {@link #trainCollisionLaunchHorizontal}：由
     *             {@code knockbackDistance × 1.5} 反推，本字段不再参与计算。
     */
    @Deprecated
    public double trainCollisionFlyHorizontal = 1.8D;

    /** 飞出档的竖直初速（格/tick），钳制 0–6，默认 <b>0.60</b>（约 0.75 s 滞空，飞得比撞飞档更高）。 */
    public double trainCollisionFlyVertical = 0.60D;

    /**
     * 同一实体的冲量结算冷却（tick），钳制 1–200，默认 <b>20</b>（1 s）。
     *
     * <p>只限制<b>撞飞冲量</b>；伤害按 tick 累积、每 {@code DAMAGE_INTERVAL_TICKS}（10）tick
     * 用一次正规 {@code damage()} 结算，不受它限制。
     */
    public int trainCollisionImpactCooldownTicks = 20;

    /**
     * 把列车碰撞后果推广到<b>其它生物</b>（村民 / 猫 / 狗 / 动物 …），默认开。
     *
     * <p>只在单人（整合服务器）里生效：生物的位置与血量是服务端权威的，客户端改不动。玩家仍然由
     * 客户端解算（那条路本来就是客户端权威的移动）。
     */
    public boolean trainCollisionMobs = true;

    /** {@link #trainCollisionPushOnlyMaxKmh} 钳制到 0–300（指数伤害的起点 v0）。 */
    public int trainCollisionPushOnlyMaxKmhClamped() {
        return clamp(trainCollisionPushOnlyMaxKmh, 0, 300);
    }

    /** {@link #trainCollisionLowDamageMaxKmh} 钳制到 {@code [v0, 300]}；顺序由 {@link #sanitized()} 保证。 */
    public int trainCollisionLowDamageMaxKmhClamped() {
        return Math.max(trainCollisionPushOnlyMaxKmhClamped(),
                clamp(trainCollisionLowDamageMaxKmh, 0, 300));
    }

    /** {@link #trainCollisionHighDamageMaxKmh} 钳制到 {@code [low, 300]}。 */
    public int trainCollisionHighDamageMaxKmhClamped() {
        return Math.max(trainCollisionLowDamageMaxKmhClamped(),
                clamp(trainCollisionHighDamageMaxKmh, 0, 300));
    }

    /** {@link #trainCollisionLaunchMaxKmh} 钳制到 {@code [high, 400]}。 */
    public int trainCollisionLaunchMaxKmhClamped() {
        return Math.max(trainCollisionHighDamageMaxKmhClamped(),
                clamp(trainCollisionLaunchMaxKmh, 0, 400));
    }

    /** {@link #trainCollisionDamageBasePerSecond} 钳制到 0–20 hp/s。 */
    public double trainCollisionDamageBasePerSecondClamped() {
        return clamp(trainCollisionDamageBasePerSecond, 0.0D, 20.0D);
    }

    /** {@link #trainCollisionDamageExpK} 钳制到 0–8。 */
    public double trainCollisionDamageExpKClamped() {
        return clamp(trainCollisionDamageExpK, 0.0D, 8.0D);
    }

    /** {@link #trainCollisionDamageSpeedScaleKmh} 钳制到 10–1000 km/h。 */
    public double trainCollisionDamageSpeedScaleKmhClamped() {
        return clamp(trainCollisionDamageSpeedScaleKmh, 10.0D, 1000.0D);
    }

    /** {@link #trainCollisionFlyDamageScale} 钳制到 1–10。 */
    public double trainCollisionFlyDamageScaleClamped() {
        return clamp(trainCollisionFlyDamageScale, 1.0D, 10.0D);
    }

    /** {@link #trainCollisionLaunchHorizontal} 钳制到 0–8 格/tick。 */
    public double trainCollisionLaunchHorizontalClamped() {
        return clamp(trainCollisionLaunchHorizontal, 0.0D, 8.0D);
    }

    /** {@link #trainCollisionLaunchVertical} 钳制到 0–4 格/tick。 */
    public double trainCollisionLaunchVerticalClamped() {
        return clamp(trainCollisionLaunchVertical, 0.0D, 4.0D);
    }

    /** {@link #trainCollisionFlyHorizontal} 钳制到 0–12 格/tick。 */
    public double trainCollisionFlyHorizontalClamped() {
        return clamp(trainCollisionFlyHorizontal, 0.0D, 12.0D);
    }

    /** {@link #trainCollisionFlyVertical} 钳制到 0–6 格/tick。 */
    public double trainCollisionFlyVerticalClamped() {
        return clamp(trainCollisionFlyVertical, 0.0D, 6.0D);
    }

    /** {@link #trainCollisionImpactCooldownTicks} 钳制到 1–200 tick。 */
    public int trainCollisionImpactCooldownTicksClamped() {
        return clamp(trainCollisionImpactCooldownTicks, 1, 200);
    }

    /**
     * 轨道道床碰撞：玩家站在 MTR 轨道上时，轨面 + {@link #trackbedThickness} 作为可站立面。
     * 默认开。
     *
     * <p>三条硬规则（实现见 {@code collision.Trackbed}）：
     * <ul>
     *   <li>坡道按轨道参数取高 —— 每个采样点都是 {@code Rail#getPosition(t)} 的真实结果；</li>
     *   <li>桥下不生效 —— 只有「玩家从上方接近」（本 tick 起点在可站立面之上且下落穿过）才算，
     *       站在高架下面走过去不会被吸上去；</li>
     *   <li>真实方块优先 —— 脚下的方块顶面如果不低于道床面，就按原版碰撞走，不做抬升。</li>
     * </ul>
     */
    public boolean trackbedCollision = true;

    /**
     * 道床对<b>生物</b>（非玩家 LivingEntity）生效，默认开。
     *
     * <p>只在服务端生效（客户端的道床只作用于本地玩家）。与 {@link #trackbedCollision} 联动：
     * 总开关关掉时全都不生效。生物站在轨道上不再掉到轨道下方，也不再从高架下方被吸上来。
     */
    public boolean trackbedCollisionEntities = true;

    /**
     * 道床对<b>物品与下落的方块</b>生效，默认开。
     *
     * <p>玩家丢在轨道上的方块 / 沙子不再穿过轨面掉进虚空；落下的沙子会停在道床上并变成方块。
     * 与 {@link #trackbedCollision} 联动。被列车撞飞的实体有 10 tick 宽限期，飞行中不会被吸住。
     */
    public boolean trackbedCollisionItems = true;

    /** 道床厚度（格），钳制 0–1，默认 0.15。可站立面 = 轨面 + 本值。 */
    public double trackbedThickness = 0.15D;

    /** {@link #trackbedThickness} 钳制到 0–1 格。 */
    public double trackbedThicknessClamped() {
        if (Double.isNaN(trackbedThickness)) {
            return 0.15D;
        }
        return Math.max(0.0D, Math.min(1.0D, trackbedThickness));
    }

    /**
     * 碰撞诊断：每 5 秒一条 {@code collision: cars=N trackbed=Y pushed=N}（INFO）。默认开。
     *
     * <p>{@code cars} 是这一窗内为本地玩家生成的车厢碰撞箱数量的峰值，{@code trackbed} 是最近一次
     * 真正生效的道床面 y（没有生效过就是 {@code -}），{@code pushed} 是被推出车体的次数。窗口内
     * 什么都没发生也照常打印 0 —— 静默和「没在跑」在日志里必须能区分。
     */
    public boolean collisionDiagnostics = true;

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
        /** 车头方向碰撞延长（格）；{@code null} = 跟随全局。 */
        public Double collisionHeadExtra;
        /** 车尾方向碰撞延长（格）；{@code null} = 跟随全局。 */
        public Double collisionTailExtra;

        // ---- 光柱（ProtoBeamCone）：{@code null} = 跟随全局 ----
        /**
         * 这条侧线的车是否画体积光柱；{@code null} = 跟随全局 {@link #beamCone}。
         * 与光带（{@link #beamGlow}）互相独立，两者都是总开关 {@link #enabled} 的子项。
         */
        public Boolean beamCone;
        /** 光柱的 alpha 预算（0.02–0.5）；{@code null} = 跟随全局 {@link #beamConeAlpha}。 */
        public Double beamConeAlpha;
        /** 光柱的深度遮挡（只有 1.20.1 的 raymarch 路径吃这个值）；{@code null} = 跟随全局。 */
        public Boolean beamConeOcclusion;
        /** 光柱每像素的 raymarch 采样数（4–16）；{@code null} = 跟随全局 {@link #beamConeSteps}。 */
        public Integer beamConeSteps;

        // ---- 粒子（雨天水雾 / 挡雨）：{@code null} = 跟随全局 ----
        /** 这条侧线的车在雨天是否溅水雾；{@code null} = 跟随全局 {@link #rainSpray}。 */
        public Boolean rainSpray;
        /** 水雾的起始速度（km/h，0–200）；{@code null} = 跟随全局 {@link #rainSprayMinSpeedKmh}。 */
        public Double rainSprayMinSpeedKmh;
        /**
         * 玩家坐在这条侧线的车里时是否不画雨；{@code null} = 跟随全局 {@link #rainHush}。
         * 「坐在哪列车里」由 {@code RainHush} 判定的那节车厢所属列车决定。
         */
        public Boolean rainHush;

        // ---- 伤害：{@code null} = 跟随全局 ----
        /**
         * 这条侧线的车撞人是否扣血；{@code null} = 跟随全局 {@link #trainCollisionDamage}。
         * <b>关掉只是不扣血</b>：推开与被撞飞的冲量仍然生效（否则车会直接穿人）。
         */
        public Boolean trainCollisionDamage;
        /**
         * 伤害起点 {@code v0}（km/h，0–300）；{@code null} = 跟随全局
         * {@link #trainCollisionPushOnlyMaxKmh}。低于它只推开、不扣血。
         */
        public Double trainCollisionDamageMinSpeedKmh;
        /**
         * 一次接触的累计伤害上限（hp，0–200，0 = 不额外设限）；{@code null} = 跟随全局
         * {@link #trainCollisionDamageMax}。
         */
        public Double trainCollisionDamageMax;

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
                    && railRelightMaxPerSec == null && railRelightFrustumCos == null
                    && collisionHeadExtra == null && collisionTailExtra == null
                    && beamCone == null && beamConeAlpha == null
                    && beamConeOcclusion == null && beamConeSteps == null
                    && rainSpray == null && rainSprayMinSpeedKmh == null && rainHush == null
                    && trainCollisionDamage == null && trainCollisionDamageMinSpeedKmh == null
                    && trainCollisionDamageMax == null;
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
        /**
         * 车头方向碰撞延长（格，已钳制 0–4）：头车前端取 {@code max(trainCollisionEndPad, 本值)}。
         * 侧线没有覆盖时就是全局 {@link HeadlightConfig#collisionHeadExtra}。
         */
        public final double collisionHeadExtra;
        /** 车尾方向碰撞延长（格，已钳制 0–4）：尾车后端取 {@code max(trainCollisionEndPad, 本值)}。 */
        public final double collisionTailExtra;
        /** 光柱开关（这条侧线的车）；与 {@link #beamGlow} 各自独立。 */
        public final boolean beamCone;
        /** 光柱的 alpha 预算，已钳制到 [0.02, 0.5]。 */
        public final double beamConeAlpha;
        /** 光柱的深度遮挡；false = 这条侧线的车走旧的加色壳层锥（不遮挡）。 */
        public final boolean beamConeOcclusion;
        /** 光柱每像素的 raymarch 采样数，已钳制到 [4, 16]。 */
        public final int beamConeSteps;
        /** 雨天水雾开关（这条侧线的车）。 */
        public final boolean rainSpray;
        /** 水雾起始速度（km/h），已钳制到 [0, 200]。 */
        public final double rainSprayMinSpeedKmh;
        /** 坐在<em>这条侧线的车</em>里时是否不画雨。 */
        public final boolean rainHush;
        /**
         * 撞击是否扣血（这条侧线的车）。<b>false 只关掉扣血</b>：推开与撞飞冲量照旧，
         * 见 {@code CollisionImpact.tick}（冲量在伤害之前、且不看这个开关）。
         */
        public final boolean trainCollisionDamage;
        /** 伤害起点 {@code v0}（km/h，已钳制 0–300）：低于它只推开、不扣血。 */
        public final double trainCollisionDamageMinSpeedKmh;
        /** 一次接触的累计伤害上限（hp，已钳制 0–200）；0 = 只受实体最大生命限制。 */
        public final double trainCollisionDamageMax;
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
                  final double collisionHeadExtra, final double collisionTailExtra,
                  final boolean beamCone, final double beamConeAlpha,
                  final boolean beamConeOcclusion, final int beamConeSteps,
                  final boolean rainSpray, final double rainSprayMinSpeedKmh,
                  final boolean rainHush, final boolean trainCollisionDamage,
                  final double trainCollisionDamageMinSpeedKmh,
                  final double trainCollisionDamageMax,
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
            this.collisionHeadExtra = collisionHeadExtra;
            this.collisionTailExtra = collisionTailExtra;
            this.beamCone = beamCone;
            this.beamConeAlpha = beamConeAlpha;
            this.beamConeOcclusion = beamConeOcclusion;
            this.beamConeSteps = beamConeSteps;
            this.rainSpray = rainSpray;
            this.rainSprayMinSpeedKmh = rainSprayMinSpeedKmh;
            this.rainHush = rainHush;
            this.trainCollisionDamage = trainCollisionDamage;
            this.trainCollisionDamageMinSpeedKmh = trainCollisionDamageMinSpeedKmh;
            this.trainCollisionDamageMax = trainCollisionDamageMax;
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
                    railRelightFrustumCos, clampEndExtra(collisionHeadExtra),
                    clampEndExtra(collisionTailExtra),
                    beamCone, clamp(beamConeAlpha, 0.02D, 0.5D), beamConeOcclusion, beamConeSteps(),
                    rainSpray, (double) rainSprayMinSpeedKmh, rainHush,
                    trainCollisionDamage, (double) trainCollisionPushOnlyMaxKmhClamped(),
                    trainCollisionDamageMaxClamped(), sidingId, false);
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
                clampEndExtra(o.collisionHeadExtra != null
                        ? o.collisionHeadExtra.doubleValue() : collisionHeadExtra),
                clampEndExtra(o.collisionTailExtra != null
                        ? o.collisionTailExtra.doubleValue() : collisionTailExtra),
                o.beamCone != null ? o.beamCone.booleanValue() : beamCone,
                clamp(o.beamConeAlpha != null
                        ? o.beamConeAlpha.doubleValue() : beamConeAlpha, 0.02D, 0.5D),
                o.beamConeOcclusion != null ? o.beamConeOcclusion.booleanValue() : beamConeOcclusion,
                clamp(o.beamConeSteps != null
                        ? o.beamConeSteps.intValue() : beamConeSteps, 4, 16),
                o.rainSpray != null ? o.rainSpray.booleanValue() : rainSpray,
                clamp(o.rainSprayMinSpeedKmh != null
                        ? o.rainSprayMinSpeedKmh.doubleValue() : (double) rainSprayMinSpeedKmh,
                        0.0D, 200.0D),
                o.rainHush != null ? o.rainHush.booleanValue() : rainHush,
                o.trainCollisionDamage != null
                        ? o.trainCollisionDamage.booleanValue() : trainCollisionDamage,
                clamp(o.trainCollisionDamageMinSpeedKmh != null
                                ? o.trainCollisionDamageMinSpeedKmh.doubleValue()
                                : (double) trainCollisionPushOnlyMaxKmhClamped(),
                        0.0D, 300.0D),
                clamp(o.trainCollisionDamageMax != null
                        ? o.trainCollisionDamageMax.doubleValue() : trainCollisionDamageMax,
                        0.0D, 200.0D),
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
     * 光柱的「任一侧线打开」判定，作用同 {@link #beamAnySiding()}：全局关掉时光柱渲染不能提前
     * return，否则某条侧线打开也永远画不出来。{@code ProtoBeamCone} 用它在帧级放行，再逐车按
     * {@link Effective#beamCone} 过滤。
     */
    public boolean beamConeAnySiding() {
        return anySiding(o -> o.beamCone);
    }

    /**
     * 深度遮挡的「任一侧线打开」判定：{@code ConeVolumeRenderer.ready} 据此决定要不要复制深度图
     * 走 raymarch 路径（全局关、某条侧线开时仍要准备）。
     */
    public boolean beamConeOcclusionAnySiding() {
        return anySiding(o -> o.beamConeOcclusion);
    }

    /** 水雾的「任一侧线打开」判定，用法同 {@link #beamConeAnySiding()}。 */
    public boolean rainSprayAnySiding() {
        return anySiding(o -> o.rainSpray);
    }

    /** 挡雨的「任一侧线打开」判定（全局关、某条侧线开时雨照样要在那节车厢里停）。 */
    public boolean rainHushAnySiding() {
        return anySiding(o -> o.rainHush);
    }

    /**
     * True when at least one siding sets the given switch to {@code true}.
     *
     * <p>用 {@link java.util.function.Function} 而不是 {@code Predicate}：这些开关是
     * {@code Boolean} 的可空字段，{@code Predicate} 会把它自动拆箱，未覆盖（null）的那条侧线
     * 会直接抛 NPE。
     */
    private boolean anySiding(final java.util.function.Function<SidingSettings, Boolean> get) {
        if (sidingSettings == null) {
            return false;
        }
        for (final SidingSettings o : sidingSettings.values()) {
            if (o != null && Boolean.TRUE.equals(get.apply(o))) {
                return true;
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
     * a first start writes to {@code mtrlte.json}. Changing a default in exactly one place
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

    /** Name of the config file this build reads and writes. */
    private static final String CONFIG_FILE = "mtrlte.json";

    /**
     * Names this file shipped under before the mod was renamed, newest first. The first one that
     * exists is copied onto {@link #CONFIG_FILE}; see {@link #migrateLegacyFile}.
     */
    private static final String[] LEGACY_CONFIG_FILES = {
        "mtr-external-lights.json", "mtr-headlight.json"
    };

    public static HeadlightConfig load() {
        final Path configDir = FabricLoader.getInstance().getConfigDir();
        final Path path = configDir.resolve(CONFIG_FILE);
        migrateLegacyFile(configDir, path);
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
                LteLog.LOGGER.warn("Could not read {} - using defaults", path, e);
            }
        }
        final HeadlightConfig cfg = new HeadlightConfig();
        cfg.configVersion = CONFIG_VERSION;
        cfg.save(path);
        return cfg;
    }

    /**
     * Copies the config of a pre-rename release onto {@link #CONFIG_FILE} when that file does not
     * exist yet. The old file is <em>copied</em>, never moved or deleted: going back to an older
     * build still finds it, and a rename can never destroy a tuned config. A failed copy is not
     * fatal - {@link #load()} then falls back to the defaults and writes those.
     *
     * <p>Only the first existing legacy name is used, so a stale {@code mtr-headlight.json} cannot
     * overwrite data that came from the newer {@code mtr-external-lights.json}.
     */
    private static void migrateLegacyFile(final Path configDir, final Path target) {
        if (Files.exists(target)) {
            return;
        }
        for (final String name : LEGACY_CONFIG_FILES) {
            final Path legacy = configDir.resolve(name);
            if (!Files.isRegularFile(legacy)) {
                continue;
            }
            try {
                Files.copy(legacy, target);
                LteLog.LOGGER.info(
                        "[lte] config migrated from {} to {} (old file kept)",
                        legacy.getFileName(), target.getFileName());
            } catch (IOException e) {
                LteLog.LOGGER.warn(
                        "[lte] could not migrate {} - using defaults", legacy, e);
            }
            return;
        }
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
        if (configVersion < 16 && beamConeAlpha == 0.16D) {
            // 0.16 was the default while the shell cone had no attenuation floor: the products
            // (radial 0.068-0.379 x view 0.22 x ground) landed on ~0.003 per vertex and a real
            // beam rendered as invisible. 0.30 is the new default; an untouched file is moved onto
            // it, a hand-set value of any other size is left alone (same rule as version 5).
            beamConeAlpha = 0.30D;
        }
        if (configVersion < 8) {
            // autoLightsInTunnel（boolean）→ headlightMode（AUTO/ALWAYS/OFF）。旧 false 的含义是
            // "不自动关灯"，唯一正确的落点是 OFF（ALWAYS 效果相同但语义不同，迁移不该替玩家改
            // 意图）；旧 true 就是 AUTO。文件里没有这个键时字段停在初始值 true，同样落到 AUTO。
            // 已经是合法模式（新文件、或手写的新键）时不动，避免迁移覆盖玩家刚写的值。
            if (headlightMode == null || normalizeHeadlightMode(headlightMode).equals(MODE_AUTO)) {
                if (!autoLightsInTunnel) {
                    headlightMode = MODE_OFF;
                }
            }
        }
        // Version 6 only appends the rainSpray* block. Nothing has to be rewritten field by field
        // (Gson leaves absent keys at their initialisers), so the bump exists purely to make
        // load() write the new keys into an existing file - otherwise they would be invisible to
        // anyone editing config/mtrlte.json by hand. Version 7 does exactly the same for the
        // collision block (trainCollision / trainCollisionHeight / trackbedCollision /
        // trackbedThickness / collisionDiagnostics), which MC 1.20.1 is the only build to read.
        // Version 8 replaces the boolean autoLightsInTunnel with headlightMode; the mapping is the
        // block above, and the same bump is what writes the new key into every existing file.
        // Version 9 only appends collisionHeadExtra / collisionTailExtra (default 1.0 = the old
        // trainCollisionEndPad behaviour), so it too is purely a "write the new keys out" bump.
        // Version 10 does the same for beamConeOcclusion / beamConeSteps (depth-aware cone).
        // Version 11 does the same for beamCone (the cone's own switch, split off beamGlow).
        // Version 12 replaces trainCollisionDamageMinSpeedKmh / trainCollisionDamageMax (the old
        // single linear hit) with the speed-tier block: four tier boundaries, the exponential
        // damage constants (base / k / speed scale / fly scale), the two impulse pairs and the
        // impact cooldown. The old two keys are simply gone from the class, so Gson drops them on
        // the next write; nothing else has to be migrated (all new fields start at their defaults).
        // Version 13 re-adds trainCollisionDamageMax with a narrower meaning (per-contact damage
        // cap, 0 = no cap) and appends the per-siding block for beams / particles / damage. Its
        // only job is to write those keys into an existing file so they are discoverable by hand.
        // Version 14 only appends beamConeSource (MODEL / GLOW, the cone's geometry source) for the
        // same reason: write the key out once so switching it by hand is discoverable.
        // Version 15 appends beamConeVolume (false = additive shell cone, true = raymarch). A file
        // written by the previous build has no such key, so Gson leaves it at false - the same
        // "shell is the verified path" default. The bump writes the key out once.
        // Version 16 raises beamConeAlpha's default 0.16 -> 0.30 (the shell cone got an attenuation
        // floor; at 0.16 the beam was numerically drawn and visually black). Untouched files are
        // migrated above, hand-set values are not.
        // Version 17 only appends damageInCreative (default false = the old "creative is immune"
        // behaviour). Gson leaves an absent key at its initialiser, so nothing has to be rewritten;
        // the bump is what writes the key into an existing file so it is discoverable by hand.
        // Version 18 only appends coneOnTail (default false = cone from the head lamp only). Same
        // reason: an absent key already means false, the bump writes it out so it is discoverable.
        // Version 19 only appends parkedLightsOff / parkedLightsOffTicks (default true / 200 =
        // "a train that has been standing still in the depot for 10 s stops shining"). Absent keys
        // already mean exactly those defaults, so nothing has to be rewritten - the bump writes
        // them into an existing file so they are discoverable by hand.
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
                if (o.collisionHeadExtra != null) {
                    o.collisionHeadExtra = clamp(o.collisionHeadExtra, 0.0D, 4.0D);
                }
                if (o.collisionTailExtra != null) {
                    o.collisionTailExtra = clamp(o.collisionTailExtra, 0.0D, 4.0D);
                }
                if (o.beamConeAlpha != null) {
                    o.beamConeAlpha = clamp(o.beamConeAlpha, 0.02D, 0.5D);
                }
                if (o.beamConeSteps != null) {
                    o.beamConeSteps = clamp(o.beamConeSteps, 4, 16);
                }
                if (o.rainSprayMinSpeedKmh != null) {
                    o.rainSprayMinSpeedKmh = clamp(o.rainSprayMinSpeedKmh, 0.0D, 200.0D);
                }
                if (o.trainCollisionDamageMinSpeedKmh != null) {
                    o.trainCollisionDamageMinSpeedKmh =
                            clamp(o.trainCollisionDamageMinSpeedKmh, 0.0D, 300.0D);
                }
                if (o.trainCollisionDamageMax != null) {
                    o.trainCollisionDamageMax = clamp(o.trainCollisionDamageMax, 0.0D, 200.0D);
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
        // 8 = 每帧最多重建的轨道区块数上限。以前这里引用 RailChunkBuildBoost.MAX_PER_FRAME，
        // 那是个客户端渲染类；HeadlightConfig 会被服务端入口加载，一旦常量池里有那个类，dedicated
        // server 上就有多加载一整棵渲染类树的风险。数值写死在这里（两处必须一致）。
        railChunkBuildPerFrame = clamp(railChunkBuildPerFrame, 1, MAX_RAIL_CHUNK_BUILD_PER_FRAME);
        railRelightFrustumCos = clamp(railRelightFrustumCos, -1.0D, 0.95D);
        beamDistance = clamp(beamDistance, 16.0D, 512.0D);
        beamHeadLength = clamp(beamHeadLength, 0.0D, 64.0D);
        beamTailLength = clamp(beamTailLength, 0.0D, 64.0D);
        beamBlockLightRadius = clamp(beamBlockLightRadius, 4, 32);
        beamWidth = clamp(beamWidth, 0.5D, 8.0D);
        beamHeadHeight = clamp(beamHeadHeight, 0.0D, 4.0D);
        beamTailHeight = clamp(beamTailHeight, 0.0D, 4.0D);
        beamLift = clamp(beamLift, 0.0D, 0.5D);
        beamAlpha = clamp(beamAlpha, 0.0D, 1.0D);
        beamConeAlpha = clamp(beamConeAlpha, 0.02D, 0.5D);
        rainSprayMinSpeedKmh = clamp(rainSprayMinSpeedKmh, 0, 200);
        trainCollisionHeight = clamp(trainCollisionHeight, 1.0D, 8.0D);
        trainCollisionEndPad = clamp(trainCollisionEndPad, 0.0D, 3.0D);
        collisionHeadExtra = clampEndExtra(collisionHeadExtra);
        collisionTailExtra = clampEndExtra(collisionTailExtra);
        roofDropMinSpeedKmh = clamp(roofDropMinSpeedKmh, 0, 300);
        roofDropMaxRadiusM = clamp(roofDropMaxRadiusM, 10.0D, 1000.0D);
        // 速度分档：先各自钳到合理区间，再强制单调递增 —— 一个手改坏了的 JSON 不能让
        // 「20–50」这种档位反过来，否则档位判定会落进一个谁也说不清的区间。
        trainCollisionPushOnlyMaxKmh = clamp(trainCollisionPushOnlyMaxKmh, 0, 300);
        trainCollisionLowDamageMaxKmh = Math.max(trainCollisionPushOnlyMaxKmh,
                clamp(trainCollisionLowDamageMaxKmh, 0, 300));
        trainCollisionHighDamageMaxKmh = Math.max(trainCollisionLowDamageMaxKmh,
                clamp(trainCollisionHighDamageMaxKmh, 0, 300));
        trainCollisionLaunchMaxKmh = Math.max(trainCollisionHighDamageMaxKmh,
                clamp(trainCollisionLaunchMaxKmh, 0, 400));
        trainCollisionDamageBasePerSecond = clamp(trainCollisionDamageBasePerSecond, 0.0D, 20.0D);
        trainCollisionDamageExpK = clamp(trainCollisionDamageExpK, 0.0D, 8.0D);
        trainCollisionDamageSpeedScaleKmh = clamp(trainCollisionDamageSpeedScaleKmh, 10.0D, 1000.0D);
        trainCollisionFlyDamageScale = clamp(trainCollisionFlyDamageScale, 1.0D, 10.0D);
        trainCollisionDamageMax = clamp(trainCollisionDamageMax, 0.0D, 200.0D);
        knockbackDistance = clamp(knockbackDistance, 1.0D, 20.0D);
        trainCollisionLaunchHorizontal = clamp(trainCollisionLaunchHorizontal, 0.0D, 8.0D);
        trainCollisionLaunchVertical = clamp(trainCollisionLaunchVertical, 0.0D, 4.0D);
        trainCollisionFlyHorizontal = clamp(trainCollisionFlyHorizontal, 0.0D, 12.0D);
        trainCollisionFlyVertical = clamp(trainCollisionFlyVertical, 0.0D, 6.0D);
        trainCollisionImpactCooldownTicks = clamp(trainCollisionImpactCooldownTicks, 1, 200);
        trainCollisionHurtTicks = clamp(trainCollisionHurtTicks, 40, 600);
        trackbedThickness = clamp(trackbedThickness, 0.0D, 1.0D);
        // 停放阈值：负数没有意义（会变成"永远停放"），夹到 0；上限 20 分钟，防手改出一个
        // 永远判不出停放的数。
        parkedLightsOffTicks = clamp(parkedLightsOffTicks, 0, 24000);
        beamHeadColor = formatColor(parseColor(beamHeadColor, DEFAULT_HEAD_BEAM));
        beamTailColor = formatColor(parseColor(beamTailColor, DEFAULT_TAIL_BEAM));
        // 字符串枚举：null / 空串 / 拼错的值都收回 AUTO（Gson 不会因为一个字段读坏整份配置）。
        headlightMode = normalizeHeadlightMode(headlightMode);
        beamConeSource = normalizeConeSource(beamConeSource);
        return this;
    }

    private void save(final Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LteLog.LOGGER.warn("Could not write {}", path, e);
        }
    }

    /** Persist to the standard config file (used by the in-game config screen). */
    public void save() {
        save(FabricLoader.getInstance().getConfigDir().resolve("mtrlte.json"));
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
            LteLog.LOGGER.warn("Could not read a config snapshot - keeping the live one", e);
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
