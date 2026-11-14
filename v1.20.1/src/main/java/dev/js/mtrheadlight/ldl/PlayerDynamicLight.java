package dev.js.mtrheadlight.ldl;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.MtrHeadlightClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

/**
 * The player's own held-item dynamic light (MC 1.20.1), <b>optional at runtime</b>.
 *
 * <h2>What it does</h2>
 * While {@link HeadlightConfig#playerDynamicLight} is on and the player holds something that emits
 * light - judged by <em>the block's own default light level</em>, so torches (14), lanterns (15),
 * soul lanterns (10), campfires (15), glowstone and every modded light block are covered without a
 * hard-coded item list - one dynamic light follows the player. Left or right hand, whichever is
 * brighter. It is deliberately <b>not</b> gated by {@link HeadlightConfig#enabled}: that master switch
 * is about trains, and "turn the train lights off" must never take the player's own torch away.
 *
 * <h2>Optional dependency</h2>
 * The light is published through LambDynamicLights 4.x ({@code lambdynlights}). Not installed means
 * the feature is inert - not broken: this class names no LDL type, the whole LDL side lives in
 * {@link LdlPlayerLight}, and every call into it sits inside {@code try/catch Throwable}. A client
 * without LDL therefore logs one line at startup and never touches the API classes:
 * <pre>
 *   [lte] player dynamic light: off - LambDynamicLights (lambdynlights) is not installed ...
 * </pre>
 * The config key is read and saved either way, so installing LDL later needs no config edit.
 *
 * <h2>Not the same thing as LDL's own item lights</h2>
 * LDL also lights items on its own (including the one in your hand). This path exists so the effect
 * is guaranteed by <em>us</em> - it is what the config row switches off, and it keeps working when
 * LDL's item luminance data for a modded light block is missing. Turning the row off only removes our
 * behaviour; LDL's vanilla item lighting is its own setting.
 */
public final class PlayerDynamicLight {

    /** Real mod id of LambDynamicLights (read from its {@code fabric.mod.json}). Not the API library. */
    public static final String LDL_MOD_ID = "lambdynlights";

    /** The API library ships inside the mod jar as {@code META-INF/jars}; presence is not a separate check. */
    public static final String LDL_API_MOD_ID = "lambdynlights_api";

    private static boolean modChecked;
    private static boolean modLoaded;

    /** Set once an LDL call blew up: stop trying for the rest of the session instead of spamming. */
    private static boolean tripped;

    private static boolean loggedOn;
    private static int lastPublished;

    private PlayerDynamicLight() {
    }

    /**
     * Whether a light can be published at all: LDL loaded <em>and</em> it already handed us its
     * behaviour manager (its {@code lambdynlights:initializer} entrypoint runs on its own schedule,
     * so "installed" and "ready" are two different questions - only the second one is asked per tick).
     */
    public static boolean available() {
        if (!modChecked) {
            modChecked = true;
            try {
                modLoaded = FabricLoader.getInstance().isModLoaded(LDL_MOD_ID);
            } catch (Throwable t) {
                modLoaded = false;
            }
            if (!modLoaded) {
                LteLog.LOGGER.info("[lte] player dynamic light: off - LambDynamicLights ({}) is not "
                        + "installed, so the held-item light stays inert (config key "
                        + "playerDynamicLight is still honoured/saved; nothing else changes)",
                        LDL_MOD_ID);
            }
        }
        if (!modLoaded || tripped) {
            return false;
        }
        try {
            return LdlPlayerLight.ready();
        } catch (Throwable t) {
            // NoClassDefFoundError / NoSuchMethodError: the mod id is there but the API is not what we
            // compiled against. Degrade to "no held light" instead of a crash loop.
            tripped = true;
            LteLog.LOGGER.warn("[lte] player dynamic light: disabled - LambDynamicLights is installed "
                    + "but its API could not be used ({})", t.toString());
            return false;
        }
    }

    /**
     * Whether LambDynamicLights is installed at all - the cheap question the config GUI asks, without
     * touching a single LDL class (so it is safe to call on a client that has no LDL and never logs
     * the "inert" line itself).
     */
    public static boolean modPresent() {
        try {
            return FabricLoader.getInstance().isModLoaded(LDL_MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called by {@link PlayerLightInitializer} once LDL hands over its manager. */
    static void reportInstalled() {
        if (!loggedOn) {
            loggedOn = true;
            LteLog.LOGGER.info("[lte] player dynamic light: on - LambDynamicLights {} behaviour manager "
                            + "registered; held light blocks (torch / lantern / campfire / ...) light the "
                            + "ground while the playerDynamicLight row is on",
                    LDL_API_MOD_ID);
        }
    }

    /** Once per client tick, from {@code PlayerLightClient}. Never throws. */
    public static void tick(final MinecraftClient client) {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            final ClientPlayerEntity player = client == null ? null : client.player;
            if (cfg == null || !cfg.playerDynamicLight || player == null || !available()) {
                publish(0, 0.0D, 0.0D, 0.0D);
                return;
            }
            final Vec3d pos = player.getPos();
            publish(luminanceOf(player), pos.x, pos.y, pos.z);
        } catch (Throwable t) {
            // A dynamic light must never be able to take the client down.
            tripped = true;
            LteLog.LOGGER.warn("[lte] player dynamic light: stopped after an error ({})", t.toString());
            publish(0, 0.0D, 0.0D, 0.0D);
        }
    }

    /** Drops the light (called when the client stops / the world changes). */
    public static void clear() {
        lastPublished = 0;
        if (!modLoaded || tripped) {
            return;
        }
        try {
            LdlPlayerLight.clear();
        } catch (Throwable ignored) {
            // Nothing registered on a client without the API.
        }
    }

    private static void publish(final int luminance, final double x, final double y, final double z) {
        if (!modLoaded || tripped) {
            return;
        }
        try {
            final int published = LdlPlayerLight.set(x, y, z, luminance);
            if (published != lastPublished) {
                lastPublished = published;
                if (published > 0) {
                    LteLog.LOGGER.debug("[lte] player dynamic light: held light level {}", published);
                } else {
                    LteLog.LOGGER.debug("[lte] player dynamic light: detached (nothing lit in hand)");
                }
            }
        } catch (Throwable t) {
            tripped = true;
            LteLog.LOGGER.warn("[lte] player dynamic light: publishing failed ({}) - feature off for this "
                    + "session, the client keeps running", t.toString());
        }
    }

    /**
     * Brightest light level of the two hands, 0-15. A block item answers with its block's own default
     * luminance (torch 14, lantern 15, campfire 15, glowstone 15, shroomlight 15, ...), which is both
     * data-driven and exactly the number the vanilla light engine would use for a placed one.
     */
    private static int luminanceOf(final ClientPlayerEntity player) {
        final int main = luminanceOf(player.getMainHandStack());
        final int off = luminanceOf(player.getOffHandStack());
        final int best = Math.max(main, off);
        return best < 0 ? 0 : Math.min(best, 15);
    }

    private static int luminanceOf(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        final Item item = stack.getItem();
        if (!(item instanceof BlockItem)) {
            return 0;
        }
        try {
            return ((BlockItem) item).getBlock().getDefaultState().getLuminance();
        } catch (Throwable ignored) {
            // A modded block that throws on getDefaultState is simply not a light source for us.
            return 0;
        }
    }
}
