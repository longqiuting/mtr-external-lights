package dev.js.mtrheadlight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain JSON config, written to {@code config/mtr-headlight.json} on first start.
 * Everything the light placement depends on is here so it can be tuned in-game
 * without rebuilding the mod.
 */
public final class HeadlightConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Master switch. */
    public boolean enabled = true;

    /** Only lights for transport mode TRAIN (ignore boats / cable cars / planes). */
    public boolean onlyTrains = true;

    /** Light level (0-15) of the front head light. */
    public int headLuminance = 12;
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
     * Siding ids whose trains get no dynamic light. Picked in the {@code /externallights} screen;
     * a train is skipped when its {@code sidingId} is in here.
     */
    public java.util.List<Long> mutedSidings = new java.util.ArrayList<>();

    public static HeadlightConfig load() {
        final Path path = FabricLoader.getInstance().getConfigDir().resolve("mtr-headlight.json");
        if (Files.exists(path)) {
            try {
                final String json = Files.readString(path, StandardCharsets.UTF_8);
                final HeadlightConfig cfg = GSON.fromJson(json, HeadlightConfig.class);
                if (cfg != null) {
                    return cfg.sanitized();
                }
            } catch (IOException | RuntimeException e) {
                MtrHeadlightClient.LOGGER.warn("Could not read {} - using defaults", path, e);
            }
        }
        final HeadlightConfig cfg = new HeadlightConfig();
        cfg.save(path);
        return cfg;
    }

    private HeadlightConfig sanitized() {
        if (mutedSidings == null) {
            mutedSidings = new java.util.ArrayList<>();
        }
        headLuminance = clamp(headLuminance, 0, 15);
        tailLuminance = clamp(tailLuminance, 0, 15);
        headLength = clamp(headLength, 0.0D, 32.0D);
        tailLength = clamp(tailLength, 0.0D, 32.0D);
        maxDistance = clamp(maxDistance, 16.0D, 1024.0D);
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
