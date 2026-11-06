package org.slf4j;

/** Stub mirroring slf4j Logger methods used by the mod. */
public interface Logger {
    void info(String message, Object... args);

    void warn(String message, Object... args);

    void debug(String message, Throwable throwable);

    void error(String message, Throwable throwable);
}
