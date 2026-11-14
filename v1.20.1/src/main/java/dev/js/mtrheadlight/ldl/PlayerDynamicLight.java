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
 * <h2>The two lines that say whether it works</h2>
 * <pre>
 *   [lte] ldl: ready   item=minecraft:torch level=14 behavior=on  via=entrypoint mode=FANCY ldlSources=1
 *   [lte] ldl: missing item=none            level=0  behavior=off reason=no-ldl
 *   [lte] ldl: missing item=minecraft:torch level=14 behavior=off reason=no-manager mode=OFF ldlSources=0
 *   [lte] ldl: tick    item=minecraft:torch level=14 behavior=on  via=entrypoint mode=FANCY ldlSources=1 pos=-191.5,64.0,470.3
 * </pre>
 * <ul>
 *   <li>{@code ready} = a manager exists (the {@code lambdynlights:initializer} entrypoint called back,
 *       or the reflection fallback found one) <b>and</b> the behaviour is registered in it.
 *       {@code via=} names the door.</li>
 *   <li>{@code missing reason=no-ldl} = LambDynamicLights is not installed. Normal, not an error.</li>
 *   <li>{@code missing reason=no-manager} = LDL is installed but never handed us a manager.</li>
 *   <li>{@code mode=OFF} = LDL's own global dynamic-light mode is off: nothing (not even LDL's built-in
 *       entity/item lights) can light anything until the player turns it back on in LDL's settings.
 *       This line exists because that failure is otherwise indistinguishable from "our code is dead".</li>
 *   <li>{@code ldlSources=0} with {@code behavior=on} means LDL just emptied its source set (world
 *       change) - the next tick re-adds us.</li>
 * </ul>
 * The {@code tick} line is printed once every 5 seconds so a report carries a live snapshot even when
 * nothing changes.
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

    /** Ticks between two {@code [lte] ldl: tick} lines: 100 ticks = 5 s. */
    private static final int TICK_LOG_INTERVAL = 100;

    /** Ticks to wait for a manager before reporting {@code reason=no-manager}. */
    private static final int MANAGER_GRACE_TICKS = 100;

    private static boolean modChecked;
    private static boolean modLoaded;

    /** Set once an LDL call blew up: stop trying for the rest of the session instead of spamming. */
    private static boolean tripped;

    /** Ticks seen while LDL was loaded but no manager had been handed over yet. */
    private static int ticksWithoutManager;

    /** Last published level, 0-15; also the {@code level=} value in the log line. */
    private static int lastPublished;

    /** Last world seen by {@link #tick}; a change means LDL dropped our behaviour. */
    private static Object lastWorld;

    /** The change line is printed only when this tuple changes. */
    private static String lastReport;

    /** Tick counter for the 5-second {@code tick} line. */
    private static int tickCounter;

    /** Last item id, so a hand swap is logged even when the level matches. */
    private static String lastItem = "none";

    private PlayerDynamicLight() {
    }

    /**
     * Whether a light can be published at all: LDL loaded <em>and</em> we hold its behaviour manager.
     *
     * <p>The manager normally arrives from LDL's {@code lambdynlights:initializer} entrypoint at client
     * init. If it has not (wrong order, a Fabric metadata hiccup, a renamed key), this method falls back
     * to {@link LdlPlayerLight#acquireReflectively()} once per call - cheap, and it is the difference
     * between "one log line and no light" and a working feature.
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
                reportMissing("none", 0, "no-ldl");
            }
        }
        if (!modLoaded || tripped) {
            return false;
        }
        try {
            if (!LdlPlayerLight.ready()) {
                LdlPlayerLight.acquireReflectively();
            }
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
        ticksWithoutManager = 0;
        reportReady("none", lastPublished);
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
            if (cfg == null || player == null) {
                publish(0, 0.0D, 0.0D, 0.0D, "none");
                return;
            }
            if (!cfg.playerDynamicLight) {
                // The config row is off. One line, once, so "I turned it off months ago" cannot be
                // mistaken for a broken feature.
                reportMissing(idOf(brighterHand(player)), 0, "config-off");
                publish(0, 0.0D, 0.0D, 0.0D, "none");
                return;
            }
            if (!available()) {
                // Distinguish "no LDL" (logged once at startup) from "LDL present but no manager was
                // ever handed over" - the latter used to be completely silent.
                if (modLoaded && !tripped && ++ticksWithoutManager == MANAGER_GRACE_TICKS) {
                    LteLog.LOGGER.info("[lte] ldl: missing item={} level=0 behavior=off "
                                    + "reason=no-manager after {} ticks ({})",
                            idOf(player.getMainHandStack()), MANAGER_GRACE_TICKS, ldlStatus());
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

    /**
     * Publishes the level and, on a state change or every {@link #TICK_LOG_INTERVAL} ticks, writes one
     * log line. Both the change line and the periodic line answer "does this client actually have a
     * light?" without asking anybody to reproduce it.
     */
    private static void publish(final int luminance, final double x, final double y, final double z,
                                final String itemId) {
        if (!modLoaded || tripped) {
            return;
        }
        int published = 0;
        boolean up = false;
        try {
            published = LdlPlayerLight.set(x, y, z, luminance);
            up = published > 0 && LdlPlayerLight.attached();
        } catch (Throwable t) {
            tripped = true;
            LteLog.LOGGER.warn("[lte] player dynamic light: publishing failed ({}) - feature off for this "
                    + "session, the client keeps running", t.toString());
            return;
        }
        final boolean changed = published != lastPublished;
        lastPublished = published;
        if (changed || !itemId.equals(lastItem)) {
            lastItem = itemId;
            if (published > 0 && up) {
                reportReady(itemId, published);
            } else if (published > 0) {
                reportMissing(itemId, published, "not-registered");
            } else if (!"none".equals(itemId)) {
                // A non-light item in hand: one line per swap, so "I was holding dirt" is visible in
                // the log instead of the feature just going quiet. An empty hand stays silent - the
                // 5-second tick line already carries item=none level=0.
                reportMissing(itemId, 0, "no-light-item");
            }
        }
        if (++tickCounter >= TICK_LOG_INTERVAL) {
            tickCounter = 0;
            LteLog.LOGGER.info("[lte] ldl: tick item={} level={} behavior={} via={} {} pos={},{},{}",
                    itemId, published, up ? "on" : "off", originSafely(), ldlStatus(),
                    fmt(x), fmt(y), fmt(z));
        }
    }

    private static String originSafely() {
        try {
            return LdlPlayerLight.origin();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String ldlStatus() {
        try {
            return LdlPlayerLight.status();
        } catch (Throwable t) {
            return "mode=? ldlSources=?";
        }
    }

    private static String fmt(final double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /**
     * The line a bug report needs: {@code ready|missing item=<id> level=<n> behavior=on|off ...}.
     * A repeated identical tuple is dropped, so holding a torch does not write a line per tick.
     */
    private static void reportReady(final String itemId, final int level) {
        report(true, itemId, level, null);
    }

    private static void reportMissing(final String itemId, final int level, final String reason) {
        report(false, itemId, level, reason);
    }

    private static void report(final boolean ready, final String itemId, final int level,
                               final String reason) {
        final String key = (ready ? "ready" : "missing") + '|' + itemId + '|' + level + '|' + reason;
        if (key.equals(lastReport)) {
            return;
        }
        lastReport = key;
        if (reason == null) {
            LteLog.LOGGER.info("[lte] ldl: ready item={} level={} behavior=on via={} {}",
                    itemId, level, originSafely(), ldlStatus());
        } else {
            LteLog.LOGGER.info("[lte] ldl: missing item={} level={} behavior=off reason={} {}",
                    itemId, level, reason, ldlStatus());
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
