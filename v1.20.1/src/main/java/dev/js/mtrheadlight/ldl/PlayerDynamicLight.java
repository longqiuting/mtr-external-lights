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
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Vec3d;

/**
 * The player's own held-item dynamic light (MC 1.20.1), <b>optional at runtime</b>.
 *
 * <h2>What it does</h2>
 * While {@link HeadlightConfig#playerDynamicLight} is on and the player holds something that emits
 * light - judged by <em>the block's own default light level</em>, so torches (14), lanterns (15),
 * soul lanterns (10), campfires (15), glowstone and every modded light block are covered without a
 * hard-coded item list - one dynamic light follows the player. Left or right hand, whichever is
 * brighter. It is deliberately <b>not</b> gated by {@link HeadlightConfig#enabled}: that master
 * switch is about trains, and "turn the train lights off" must never take the player's own torch
 * away.
 *
 * <h2>Optional dependency</h2>
 * The light is published through LambDynamicLights 4.x ({@code lambdynlights}). Not installed means
 * the feature is inert - not broken: this class names no LDL type, the whole LDL side lives in
 * {@link LdlPlayerLight}, and every call into it sits inside {@code try/catch Throwable}. A client
 * without LDL therefore logs one line at startup and never touches the API classes.
 *
 * <h2>The one line that says whether it works</h2>
 * Every state change prints exactly one INFO line, so a bug report carries its own diagnosis:
 * <pre>
 *   [lte] ldl: ready   mod=lambdynlights light=14 item=minecraft:torch
 *   [lte] ldl: ready   mod=lambdynlights light=0  item=none
 *   [lte] ldl: missing mod=none light=0 item=none
 *   [lte] ldl: missing mod=lambdynlights light=0 item=none note=no-initializer-callback
 * </pre>
 * {@code ready} means LDL handed us its behaviour manager (its {@code lambdynlights:initializer}
 * entrypoint called back) and {@code light=} is the level we actually published. {@code missing}
 * with {@code mod=none} is the normal "no LDL installed" case; {@code missing} with
 * {@code mod=lambdynlights} plus {@code note=no-initializer-callback} is the failure that used to be
 * silent - the mod is there but our entrypoint was never invoked.
 *
 * <h2>Not the same thing as LDL's own item lights</h2>
 * LDL also lights items on its own (including the one in your hand) through its entity pipeline. This
 * path exists so the effect is guaranteed by <em>us</em>: it is what the config row switches off, it
 * survives LDL's entity-pipeline problems, and it keeps working when LDL's item luminance data for a
 * modded light block is missing. Turning the row off only removes our behaviour; LDL's vanilla item
 * lighting is its own setting.
 */
public final class PlayerDynamicLight {

    /** Real mod id of LambDynamicLights (read from its {@code fabric.mod.json}). Not the API library. */
    public static final String LDL_MOD_ID = "lambdynlights";

    /** The API library ships inside the mod jar as {@code META-INF/jars}; presence is not a separate check. */
    public static final String LDL_API_MOD_ID = "lambdynlights_api";

    /** Ticks to wait for the {@code lambdynlights:initializer} callback before reporting it as missing. */
    private static final int CALLBACK_GRACE_TICKS = 100;

    private static boolean modChecked;
    private static boolean modLoaded;

    /** Set once an LDL call blew up: stop trying for the rest of the session instead of spamming. */
    private static boolean tripped;

    /** Ticks seen while LDL was loaded but its behaviour manager had not been handed over yet. */
    private static int ticksWithoutCallback;

    /** Last published level, 0-15; also the {@code light=} value in the log line. */
    private static int lastPublished;

    /** Last world seen by {@link #tick}; a change means LDL dropped our behaviour. */
    private static Object lastWorld;

    /** The {@code ready|missing mod=.. light=.. item=..} line is printed only when this tuple changes. */
    private static String lastReport;

    private PlayerDynamicLight() {
    }

    /**
     * Whether a light can be published at all: LDL loaded <em>and</em> it already handed us its
     * behaviour manager (its {@code lambdynlights:initializer} entrypoint runs from LDL's own client
     * initialiser, so "installed" and "ready" are two different questions - only the second one is
     * asked per tick).
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
                // No LDL at all: the feature is inert by design, and this is the whole story.
                report(false, "none", 0, "none", null);
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
    static void onInitializerRan() {
        ticksWithoutCallback = 0;
        report(true, LDL_MOD_ID, lastPublished, "none", null);
    }

    /** Once per client tick, from {@code PlayerLightClient}. Never throws. */
    public static void tick(final MinecraftClient client) {
        try {
            final Object world = client == null ? null : client.world;
            if (world != lastWorld) {
                // LDL emptied its light source set inside setWorld (see LdlPlayerLight#resetAttachment).
                lastWorld = world;
                lastPublished = 0;
                if (modLoaded && !tripped) {
                    try {
                        LdlPlayerLight.resetAttachment();
                    } catch (Throwable ignored) {
                        // API missing: nothing was ever registered.
                    }
                }
            }
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            final ClientPlayerEntity player = client == null ? null : client.player;
            if (cfg == null || !cfg.playerDynamicLight || player == null) {
                publish(0, 0.0D, 0.0D, 0.0D, "none");
                return;
            }
            if (!available()) {
                // Distinguish "no LDL" (logged once at startup) from "LDL present but our initializer
                // never ran" - the latter is the failure mode that used to be completely silent.
                if (modLoaded && !tripped && ++ticksWithoutCallback == CALLBACK_GRACE_TICKS) {
                    report(false, LDL_MOD_ID, 0, "none", "no-initializer-callback");
                }
                publish(0, 0.0D, 0.0D, 0.0D, "none");
                return;
            }
            final Vec3d pos = player.getPos();
            final ItemStack held = brighterHand(player);
            publish(luminanceOf(held), pos.x, pos.y, pos.z, idOf(held));
        } catch (Throwable t) {
            // A dynamic light must never be able to take the client down.
            tripped = true;
            LteLog.LOGGER.warn("[lte] player dynamic light: stopped after an error ({})", t.toString());
            publish(0, 0.0D, 0.0D, 0.0D, "none");
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

    private static void publish(final int luminance, final double x, final double y, final double z,
                                final String itemId) {
        if (!modLoaded || tripped) {
            return;
        }
        try {
            final int published = LdlPlayerLight.set(x, y, z, luminance);
            final boolean changed = published != lastPublished;
            lastPublished = published;
            if (changed) {
                report(LdlPlayerLight.ready(), LDL_MOD_ID, published, itemId, null);
            } else if (published > 0) {
                // Same level, but the hand may have moved to another light of equal brightness.
                report(true, LDL_MOD_ID, published, itemId, null);
            }
        } catch (Throwable t) {
            tripped = true;
            LteLog.LOGGER.warn("[lte] player dynamic light: publishing failed ({}) - feature off for this "
                    + "session, the client keeps running", t.toString());
        }
    }

    /**
     * One INFO line per state change: {@code [lte] ldl: ready|missing mod=&lt;id&gt; light=&lt;level&gt; item=&lt;id&gt;}.
     * A repeated identical tuple is dropped, so holding a torch does not write a line per tick.
     */
    private static void report(final boolean ready, final String modId, final int light,
                               final String itemId, final String note) {
        final String key = (ready ? "ready" : "missing") + '|' + modId + '|' + light + '|' + itemId
                + '|' + note;
        if (key.equals(lastReport)) {
            return;
        }
        lastReport = key;
        if (note == null) {
            LteLog.LOGGER.info("[lte] ldl: {} mod={} light={} item={}",
                    ready ? "ready" : "missing", modId, light, itemId);
        } else {
            LteLog.LOGGER.info("[lte] ldl: {} mod={} light={} item={} note={}",
                    ready ? "ready" : "missing", modId, light, itemId, note);
        }
    }

    /** The hand with the brighter light item; main hand wins ties. */
    private static ItemStack brighterHand(final ClientPlayerEntity player) {
        final ItemStack main = player.getMainHandStack();
        final ItemStack off = player.getOffHandStack();
        return luminanceOf(off) > luminanceOf(main) ? off : main;
    }

    /** Registry id of the held item for the log line ({@code minecraft:torch}); "none" when empty. */
    private static String idOf(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "none";
        }
        try {
            return Registries.ITEM.getId(stack.getItem()).toString();
        } catch (Throwable t) {
            return "?";
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
