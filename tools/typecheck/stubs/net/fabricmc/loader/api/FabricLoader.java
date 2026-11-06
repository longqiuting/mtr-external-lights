package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Stub mirroring net.fabricmc.loader.api.FabricLoader#getConfigDir(). */
public class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    public Path getConfigDir() {
        return Paths.get("config");
    }
}
