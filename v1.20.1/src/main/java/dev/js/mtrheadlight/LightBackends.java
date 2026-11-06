package dev.js.mtrheadlight;

/** Version-local selection of the light backend implementation. */
public final class LightBackends {
    private static LightBackend instance;

    private LightBackends() {
    }

    public static LightBackend backend() {
        if (instance == null) {
            instance = new Ldl4Backend();
        }
        return instance;
    }
}
