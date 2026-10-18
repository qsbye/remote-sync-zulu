package dev.remotesynczulu.flist;

import dev.remotesynczulu.sync.SyncOptions;
import dev.remotesynczulu.util.Files2;
import dev.remotesynczulu.util.Log;
import dev.remotesynczulu.util.StrMode;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * File-list generation, translated from openrsync's flist.c flist_gen().
 *
 * <p>Semantics mirror the C original:
 * <ul>
 *   <li>a source ending in '/' contributes the directory's <em>contents</em>;
 *       otherwise the source itself appears as a top-level entry</li>
 *   <li>directories are always descended unless excluded by the filter
 *       rules (a non-recursive transfer only takes the top level)</li>
 *   <li>filter rules see root-relative, '/'-separated paths</li>
 *   <li>--max-size/--min-size reject regular files at listing time</li>
 *   <li>the final list is sorted by path</li>
 * </ul>
 */
public final class FlistGen {

    /** Generated list plus a way to find each entry's local file. */
    public static final class Result {
        public final List<FileEntry> entries;
        public final Map<String, Path> localPaths;

        Result(List<FileEntry> entries, Map<String, Path> localPaths) {
            this.entries = entries;
            this.localPaths = localPaths;
        }
    }

    private FlistGen() {
    }

    /**
     * Generate the sorted file list for the given sources. Sources are
     * raw argument strings so that a trailing '/' (copy contents) is
     * preserved — {@link Path#of} would normalise it away.
     */
    public static Result generate(SyncOptions opts, List<String> sources)
            throws IOException {
        TreeMap<String, FileEntry> out = new TreeMap<>();
        Map<String, Path> locals = new HashMap<>();
        for (String src : sources) {
            addSource(opts, src, out, locals);
        }
        if (opts.checksum) {
            for (FileEntry e : out.values()) {
                if (e.isReg()) {
                    Path local = locals.get(e.path);
                    if (local != null) {
                        e.md = dev.remotesynczulu.checksum.Hashes
                                .fileSeedless(local);
                    }
                }
            }
        }
        return new Result(new ArrayList<>(out.values()), locals);
    }

    private static void addSource(SyncOptions opts, String srcArg,
            TreeMap<String, FileEntry> out, Map<String, Path> locals)
            throws IOException {
        boolean contentsOnly = srcArg.endsWith("/")
                || srcArg.endsWith("/.");
        // Strip the trailing slash for filesystem access.
        String s = srcArg;
        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        Path src = Path.of(s);

        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            Log.warn("%s: no such file or directory", srcArg);
            return;
        }

        int mode = Files2.modeOf(src);
        boolean isDir = StrMode.isDir(mode);

        if (!isDir) {
            // Plain source: single entry under its basename.
            FileEntry e = entryFor(opts, src, src.getFileName().toString());
            if (e != null && accept(opts, e)) {
                out.put(e.path, e);
                locals.put(e.path, src);
            }
            return;
        }

        // "." (and the filesystem root) contribute their contents,
        // like "dir/". As in rsync, the list starts with the root
        // directory itself as entry "." — receivers use it to see that
        // the destination is a directory, not a file name.
        Path fn = src.getFileName();
        if (contentsOnly || fn == null || fn.toString().equals(".")
                || fn.toString().isEmpty()) {
            FileEntry rootEntry = entryFor(opts, src, ".");
            if (rootEntry != null) {
                out.put(".", rootEntry);
                locals.put(".", src);
            }
            walk(opts, src, "", out, locals);
            return;
        }

        {
            // "dir": the directory appears as its own basename entry.
            String base = fn.toString();
            FileEntry e = entryFor(opts, src, base);
            if (e == null || !accept(opts, e)) {
                return;
            }
            out.put(e.path, e);
            locals.put(e.path, src);
            if (opts.recursive) {
                walk(opts, src, base, out, locals);
            }
        }
    }

    /** Walk a directory tree, adding entries relative to {@code prefix}. */
    private static void walk(SyncOptions opts, Path root, String prefix,
            TreeMap<String, FileEntry> out, Map<String, Path> locals)
            throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                    BasicFileAttributes attrs) throws IOException {
                String rel = relative(root, dir, prefix);
                if (rel.isEmpty()) {
                    return FileVisitResult.CONTINUE; // the root itself
                }
                if (!opts.rules.keep(rel, true)) {
                    Log.log3("excluding directory %s", rel);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                FileEntry e = entryFor(opts, dir, rel);
                if (e != null) {
                    out.put(e.path, e);
                    locals.put(e.path, dir);
                }
                return opts.recursive || rel.equals(prefix)
                        ? FileVisitResult.CONTINUE
                        : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                String rel = relative(root, file, prefix);
                if (!opts.recursive && rel.substring(prefix.length())
                        .contains("/")) {
                    return FileVisitResult.CONTINUE;
                }
                FileEntry e = entryFor(opts, file, rel);
                if (e == null) {
                    return FileVisitResult.CONTINUE;
                }
                if (!accept(opts, e)) {
                    Log.log3("excluding file %s", rel);
                    return FileVisitResult.CONTINUE;
                }
                out.put(e.path, e);
                locals.put(e.path, file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                Log.warn("%s: %s", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String relative(Path root, Path p, String prefix) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        if (rel.isEmpty()) {
            return "";
        }
        return prefix.isEmpty() ? rel : prefix + "/" + rel;
    }

    /** Filter-rule and size-limit acceptance for non-directory entries. */
    private static boolean accept(SyncOptions opts, FileEntry e) {
        if (!opts.rules.keep(e.path, e.isDir())) {
            return false;
        }
        if (e.isReg()) {
            if (opts.maxSize >= 0 && e.size > opts.maxSize) {
                return false;
            }
            if (opts.minSize >= 0 && e.size < opts.minSize) {
                return false;
            }
        }
        return true;
    }

    /** Build an entry from filesystem metadata, honouring -l/-o/-g/-D. */
    private static FileEntry entryFor(SyncOptions opts, Path p, String rel)
            throws IOException {
        int mode = Files2.modeOf(p);
        long size = 0;
        long mtime = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS)
                .to(TimeUnitSeconds());
        String link = null;

        if (Files.isSymbolicLink(p)) {
            mode = StrMode.S_IFLNK | 0777;
            if (!opts.links) {
                return null; // symlinks skipped without -l (rsync semantics)
            }
            link = Files.readSymbolicLink(p).toString();
            size = link.length();
        } else if (StrMode.isReg(mode)) {
            size = Files.size(p);
        } else if (StrMode.isDev(mode) || StrMode.isFifo(mode)) {
            if (!opts.devices) {
                return null; // special files skipped without -D
            }
        }

        FileEntry e = new FileEntry(rel, mode, size, mtime);
        e.link = link;
        if (opts.owner) {
            e.uid = Files2.uidOf(p);
        }
        if (opts.group) {
            e.gid = Files2.gidOf(p);
        }
        if (opts.devices) {
            e.rdev = Files2.rdevOf(p);
        }
        return e;
    }

    private static java.util.concurrent.TimeUnit TimeUnitSeconds() {
        return java.util.concurrent.TimeUnit.SECONDS;
    }

    /** Sort helper (flist is sorted by path; TreeMap already orders). */
    public static Comparator<FileEntry> order() {
        return Comparator.comparing(f -> f.path);
    }
}
