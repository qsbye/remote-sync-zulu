package dev.remotesynczulu.util;

/**
 * mode_t to "drwxr-xr-x" string conversion, translated from openrsync's
 * strmode.c. Uses the same Unix type/permission bit layout as the C code
 * (the Java side stores modes in the classic Unix encoding on the wire).
 */
public final class StrMode {

    // Unix file-type bits (as in <sys/stat.h> on BSD/macOS).
    public static final int S_IFMT = 0170000;
    public static final int S_IFIFO = 0010000;
    public static final int S_IFCHR = 0020000;
    public static final int S_IFDIR = 0040000;
    public static final int S_IFBLK = 0060000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IFSOCK = 0140000;

    private StrMode() {
    }

    public static String strmode(int mode) {
        StringBuilder sb = new StringBuilder(11);
        switch (mode & S_IFMT) {
            case S_IFDIR -> sb.append('d');
            case S_IFCHR -> sb.append('c');
            case S_IFBLK -> sb.append('b');
            case S_IFREG -> sb.append('-');
            case S_IFLNK -> sb.append('l');
            case S_IFSOCK -> sb.append('s');
            case S_IFIFO -> sb.append('p');
            default -> sb.append('?');
        }
        sb.append((mode & 0400) != 0 ? 'r' : '-');
        sb.append((mode & 0200) != 0 ? 'w' : '-');
        sb.append(suid(mode & 0100, (mode & 04000) != 0));
        sb.append((mode & 0040) != 0 ? 'r' : '-');
        sb.append((mode & 0020) != 0 ? 'w' : '-');
        sb.append(suid(mode & 0010, (mode & 02000) != 0));
        sb.append((mode & 0004) != 0 ? 'r' : '-');
        sb.append((mode & 0002) != 0 ? 'w' : '-');
        sb.append(suid(mode & 0001, (mode & 01000) != 0));
        return sb.toString();
    }

    private static char suid(int xbit, boolean special) {
        if (xbit != 0) {
            return special ? 's' : 'x';
        }
        return special ? 'S' : '-';
    }

    public static boolean isReg(int mode) {
        return (mode & S_IFMT) == S_IFREG;
    }

    public static boolean isDir(int mode) {
        return (mode & S_IFMT) == S_IFDIR;
    }

    public static boolean isLnk(int mode) {
        return (mode & S_IFMT) == S_IFLNK;
    }

    public static boolean isDev(int mode) {
        int t = mode & S_IFMT;
        return t == S_IFCHR || t == S_IFBLK;
    }

    public static boolean isFifo(int mode) {
        return (mode & S_IFMT) == S_IFIFO;
    }

    public static boolean isSock(int mode) {
        return (mode & S_IFMT) == S_IFSOCK;
    }
}
