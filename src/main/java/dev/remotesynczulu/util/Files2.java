package dev.remotesynczulu.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Filesystem helpers, translated from openrsync's misc.c/mkpath.c plus the
 * attribute handling scattered through receiver.c.
 */
public final class Files2 {

    private Files2() {
    }

    /** mkpath(): create a directory and all missing parents. */
    public static void mkpath(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    /** Recursive delete used by --delete and temp-file cleanup. */
    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Read the Unix mode (type + permissions) of a path, or -1 if unknown. */
    public static int modeOf(Path path) throws IOException {
        try {
            Object v = Files.getAttribute(path, "unix:mode",
                    LinkOption.NOFOLLOW_LINKS);
            if (v instanceof Integer i) {
                return i;
            }
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            // Non-Unix filesystem: fall through.
        }
        // Synthesise something sane for non-Unix filesystems.
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return StrMode.S_IFDIR | 0755;
        }
        if (Files.isSymbolicLink(path)) {
            return StrMode.S_IFLNK | 0777;
        }
        return StrMode.S_IFREG | (Files.isExecutable(path) ? 0755 : 0644);
    }

    /** Numeric uid, or -1 when the filesystem can't report one. */
    public static long uidOf(Path path) {
        try {
            Object v = Files.getAttribute(path, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS);
            if (v instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Numeric gid, or -1 when the filesystem can't report one. */
    public static long gidOf(Path path) {
        try {
            Object v = Files.getAttribute(path, "unix:gid",
                    LinkOption.NOFOLLOW_LINKS);
            if (v instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** st_rdev for device nodes, else 0. */
    public static long rdevOf(Path path) {
        try {
            Object v = Files.getAttribute(path, "unix:rdev",
                    LinkOption.NOFOLLOW_LINKS);
            if (v instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** Best-effort permission application (rsync -p semantics). */
    public static void setPermissions(Path path, int mode) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                return;
            }
            view.setPermissions(toPerms(mode & 07777));
        } catch (Exception ignored) {
            // Best effort, like the C code's non-fatal fchmod warnings.
        }
    }

    /** Best-effort mtime application (rsync -t semantics), seconds precision. */
    public static void setMtime(Path path, long epochSeconds) {
        try {
            Files.setLastModifiedTime(path,
                    FileTime.from(epochSeconds, TimeUnit.SECONDS));
        } catch (Exception ignored) {
        }
    }

    /** Best-effort numeric chown (rsync -o semantics). */
    public static void setUid(Path path, long uid) {
        if (uid < 0) {
            return;
        }
        try {
            Files.setAttribute(path, "unix:uid", (int) uid,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
        }
    }

    /** Best-effort numeric chgrp (rsync -g semantics). */
    public static void setGid(Path path, long gid) {
        if (gid < 0) {
            return;
        }
        try {
            Files.setAttribute(path, "unix:gid", (int) gid,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
        }
    }

    public static Set<PosixFilePermission> toPerms(int mode) {
        Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) set.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) set.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) set.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) set.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) set.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) set.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) set.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) set.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) set.add(PosixFilePermission.OTHERS_EXECUTE);
        return set;
    }

    /** Permissions mask from a freshly read posix attribute set. */
    public static int fromPerms(Set<PosixFilePermission> perms) {
        int m = 0;
        if (perms.contains(PosixFilePermission.OWNER_READ)) m |= 0400;
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) m |= 0200;
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) m |= 0100;
        if (perms.contains(PosixFilePermission.GROUP_READ)) m |= 0040;
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) m |= 0020;
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) m |= 0010;
        if (perms.contains(PosixFilePermission.OTHERS_READ)) m |= 0004;
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) m |= 0002;
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) m |= 0001;
        return m;
    }
}
