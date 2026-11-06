package org.slf4j;

/** Stub mirroring slf4j LoggerFactory. */
public final class LoggerFactory {
    private LoggerFactory() {
    }

    public static Logger getLogger(String name) {
        return new Logger() {
            @Override
            public void info(String message, Object... args) {
            }

            @Override
            public void warn(String message, Object... args) {
            }

            @Override
            public void debug(String message, Throwable throwable) {
            }

            @Override
            public void error(String message, Throwable throwable) {
            }
        };
    }
}
