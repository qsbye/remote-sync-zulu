package dev.remotesynczulu.util;

import java.util.Locale;

/**
 * Human-readable number formatting, translated from openrsync's
 * compat_humanize_number.c (the subset used for transfer statistics).
 */
public final class Humanize {

    private static final String[] SUFFIXES =
            {"", "K", "M", "G", "T", "P", "E"};

    private Humanize() {
    }

    /** Format a byte count like "1.23M". */
    public static String bytes(long bytes) {
        double v = bytes;
        int i = 0;
        while (v >= 1024.0 && i < SUFFIXES.length - 1) {
            v /= 1024.0;
            i++;
        }
        if (i == 0) {
            return Long.toString(bytes);
        }
        return String.format(Locale.ROOT, "%.2f%s", v, SUFFIXES[i]);
    }

    /** Format a rate in bytes/second like "1.23MB/s". */
    public static String rate(double bytesPerSec) {
        double v = bytesPerSec;
        int i = 0;
        while (v >= 1024.0 && i < SUFFIXES.length - 1) {
            v /= 1024.0;
            i++;
        }
        return String.format(Locale.ROOT, "%.2f%sB/s", v, SUFFIXES[i]);
    }
}
