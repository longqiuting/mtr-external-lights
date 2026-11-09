package dev.js.mtrheadlight;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The resource-manager side of {@link VehicleLightOverrides}: finds every
 * {@code assets/<namespace>/lte_vehicle_lights.json} and hands the text to the loader.
 *
 * <h2>Client: the real pack stack</h2>
 * {@link ResourceManager#getAllResources(Identifier)} returns one {@link Resource} per pack that
 * carries the file, <b>in stack order with the winning pack last</b> - that is exactly the
 * "later pack wins" rule the format specifies, so the list is fed through in that order and
 * {@code VehicleLightOverrides.load} needs no priority logic of its own. Namespaces are taken from
 * {@link ResourceManager#getAllNamespaces()}, so a pack can use any namespace it likes.
 *
 * <h2>Server: files, not packs</h2>
 * A dedicated server's {@code ResourceManager} only mounts {@code data/} (its type is
 * {@code server_data}), so an {@code assets/} path inside a datapack is invisible to it. The server
 * therefore reads the same format from two places it can actually see:
 * {@code config/lte_lights/**} and {@code <world>/datapacks/**}. Both are plain JSON files in the
 * same shape, so a layout authored and exported on the client drops straight in, and the two sides
 * run the identical parser and merge.
 *
 * <p>On a single-player world the client layer already holds the pack data in this same JVM, so
 * the server side deliberately does nothing there - it would only overwrite a richer layer with a
 * poorer one.
 */
public final class VehicleLightPacks {

    /** Folder under {@code config/} that both sides read. */
    public static final String CONFIG_DIR = "lte_lights";

    private static final int CONFIG_DEPTH = 3;
    private static final int DATAPACK_DEPTH = 9;

    /** Last line printed, so a startup scan and a reload of identical content do not log twice. */
    private static String lastLogged = "";

    private VehicleLightPacks() {
    }

    /** Scans the client pack stack plus the config folder. Safe to call off the render thread. */
    public static void reloadClient(final ResourceManager manager) {
        final List<VehicleLightJson.Source> sources = new ArrayList<>();
        // Lowest priority first: loose files in config/, then the pack stack bottom-up.
        sources.addAll(configSources());
        if (manager != null) {
            try {
                for (final String namespace : manager.getAllNamespaces()) {
                    final Identifier id = new Identifier(namespace, VehicleLightJson.FILE_NAME);
                    for (final Resource resource : manager.getAllResources(id)) {
                        final String json = read(resource);
                        if (json != null) {
                            sources.add(new VehicleLightJson.Source(
                                    resource.getResourcePackName(), namespace + "/" + VehicleLightJson.FILE_NAME, json));
                        }
                    }
                }
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] lights json: pack scan failed", t);
            }
        }
        apply(sources);
    }

    /** Scans the config folder and the world's datapacks. Dedicated server entry point. */
    public static void reloadServer(final MinecraftServer server) {
        final List<VehicleLightJson.Source> sources = new ArrayList<>(configSources());
        if (server != null) {
            try {
                final Path datapacks = server.getSavePath(WorldSavePath.DATAPACKS);
                sources.addAll(VehicleLightJson.scan(datapacks, "datapacks", DATAPACK_DEPTH));
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] lights json: datapack scan failed", t);
            }
        }
        apply(sources);
    }

    /** {@code config/lte_lights/**} - the one source both sides share. */
    public static List<VehicleLightJson.Source> configSources() {
        try {
            final Path dir = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_DIR);
            return VehicleLightJson.scan(dir, "config", CONFIG_DEPTH);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /**
     * Replaces the pack layer and prints the one-line diagnostic - but only when the result
     * differs from the previous print, so the startup scan and the resource-reload scan do not
     * produce two identical lines.
     */
    public static void apply(final List<VehicleLightJson.Source> sources) {
        VehicleLightOverrides.load(sources);
        logOnce();
    }

    /** {@link VehicleLightOverrides#reload()} + a fresh scan from the config folder. */
    public static void reload() {
        VehicleLightOverrides.reload();
        apply(configSources());
    }

    private static synchronized void logOnce() {
        final String line = VehicleLightOverrides.diagnosticLine();
        if (line.equals(lastLogged)) {
            return;
        }
        lastLogged = line;
        LteLog.LOGGER.info("[lte] {}", line);
    }

    private static String read(final Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] lights json: cannot read {} from {}", resource.getResourcePackName(),
                    VehicleLightJson.FILE_NAME);
            return null;
        }
    }
}
