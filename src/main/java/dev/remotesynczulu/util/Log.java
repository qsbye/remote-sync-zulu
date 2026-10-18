package dev.remotesynczulu.util;

/**
 * Leveled logging, translated from openrsync's log.c.
 *
 * <p>Verbosity is a process-wide level: each {@code -v} raises it by one,
 * {@code -q} lowers it. {@link #log1}..{@link #log4} emit when the current
 * level is at least their number; {@link #warn} always emits unless the
 * level is negative; {@link #err} always emits.
 */
public final class Log {

    private static volatile int level = 0;

    private Log() {
    }

    public static void setLevel(int lvl) {
        level = lvl;
    }

    public static int level() {
        return level;
    }

    public static void log1(String fmt, Object... args) {
        if (level >= 1) {
            emit(fmt, args);
        }
    }

    public static void log2(String fmt, Object... args) {
        if (level >= 2) {
            emit(fmt, args);
        }
    }

    public static void log3(String fmt, Object... args) {
        if (level >= 3) {
            emit(fmt, args);
        }
    }

    public static void log4(String fmt, Object... args) {
        if (level >= 4) {
            emit(fmt, args);
        }
    }

    public static void warn(String fmt, Object... args) {
        if (level >= 0) {
            System.err.println("rsyncz: warning: " + fmt.formatted(args));
        }
    }

    public static void err(String fmt, Object... args) {
        System.err.println("rsyncz: error: " + fmt.formatted(args));
    }

    private static void emit(String fmt, Object... args) {
        System.err.println(fmt.formatted(args));
    }
}
