package dev.remotesynczulu.sync;

import dev.remotesynczulu.checksum.Hashes;
import dev.remotesynczulu.delta.BlockSet;
import dev.remotesynczulu.delta.TokenStream;
import dev.remotesynczulu.flist.FileEntry;
import dev.remotesynczulu.flist.FlistIO;
import dev.remotesynczulu.io.WireIO;
import dev.remotesynczulu.util.Files2;
import dev.remotesynczulu.util.Humanize;
import dev.remotesynczulu.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The receiver role, translated from openrsync's receiver.c
 * rsync_receiver() (plus the downloader/uploader halves, which this
 * blocking translation folds into one per-file cycle).
 *
 * <p>Flow: read the file list and io-error count, then for each entry —
 * directories and symlinks are handled locally; regular files are
 * quick-checked (size+mtime, or MD4 with -c) and, when stale, requested
 * from the sender one at a time. Data is reconstructed into a
 * {@code .rsyncz*} temporary file in the destination directory, verified
 * against the seeded whole-file MD4 (one whole-file retry on mismatch,
 * like the C code's second pass), then atomically renamed over the
 * target. A final i32 {@code -1} goodbye ends the exchange; --delete runs
 * afterwards.
 */
public final class Receiver {

    private Receiver() {
    }

    public static void run(SyncOptions opts, Path destArg, int seed,
            InputStream in, OutputStream out) throws IOException {
        List<FileEntry> flist = FlistIO.recv(opts, in);
        int ioerr = WireIO.readInt(in);
        if (ioerr != 0) {
            Log.warn("sender reported %d I/O errors", ioerr);
        }
        Log.log1("Transfer starting: %d files", flist.size());

        // receiver.c "implied_dir": a single regular file whose
        // destination is not an existing directory is renamed onto the
        // destination path itself.
        boolean singleFile = flist.size() == 1
                && flist.get(0).isReg()
                && !destArg.toString().endsWith("/")
                && !Files.isDirectory(destArg);
        Path root = singleFile
                ? (destArg.getParent() == null ? Path.of(".")
                        : destArg.getParent())
                : destArg;

        List<FileEntry> dirs = new ArrayList<>();
        long received = 0;

        for (int i = 0; i < flist.size(); i++) {
            FileEntry f = flist.get(i);
            Path target = singleFile ? destArg : root.resolve(f.path);

            if (f.isDir()) {
                if (!opts.dryRun) {
                    Files2.mkpath(target);
                }
                dirs.add(f);
                continue;
            }
            if (f.isSymlink()) {
                handleSymlink(opts, f, target);
                continue;
            }
            if (!f.isReg()) {
                Log.warn("%s: special file not transferred", f.path);
                continue;
            }

            if (upToDate(opts, f, target)) {
                Log.log2("%s is up to date", f.path);
                if (!opts.dryRun) {
                    applyAttrs(opts, f, target);
                }
                continue;
            }
            if (opts.update && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && mtimeSec(target) > f.mtime) {
                Log.log2("%s: receiver copy is newer, skipping (-u)", f.path);
                continue;
            }
            if (opts.dryRun) {
                Log.log1("%s", f.path);
                continue;
            }

            Log.log2("receiving %s (%s)", f.path, Humanize.bytes(f.size));
            boolean ok = transferOnce(opts, seed, in, out, i, f, target, false);
            if (!ok) {
                Log.warn("%s: checksum mismatch, retrying whole file",
                        f.path);
                ok = transferOnce(opts, seed, in, out, i, f, target, true);
            }
            if (!ok) {
                throw new IOException(f.path
                        + ": whole-file checksum failed twice");
            }
            applyAttrs(opts, f, target);
            received += f.size;
        }

        WireIO.writeInt(out, -1);
        out.flush();
        int bye = WireIO.readInt(in);
        if (bye != -1) {
            throw new IOException("protocol error: expected goodbye, got "
                    + bye);
        }

        // Directory attributes go on last (post_dir() in the C code).
        if (!opts.dryRun) {
            for (int i = dirs.size() - 1; i >= 0; i--) {
                FileEntry d = dirs.get(i);
                applyAttrs(opts, d, root.resolve(d.path));
            }
        }

        if (opts.del && !singleFile) {
            deletePass(opts, root, flist);
        }

        Log.log1("received %s in %d files", Humanize.bytes(received),
                flist.size());
    }

    /** Quick check: is the destination already current? */
    private static boolean upToDate(SyncOptions opts, FileEntry f, Path target)
            throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (opts.ignoreTimes) {
            return false;
        }
        if (opts.checksum) {
            if (f.md == null || Files.size(target) != f.size) {
                return false;
            }
            return Arrays.equals(Hashes.fileSeedless(target), f.md);
        }
        if (opts.sizeOnly) {
            return Files.size(target) == f.size;
        }
        return Files.size(target) == f.size && mtimeSec(target) == f.mtime;
    }

    private static long mtimeSec(Path p) throws IOException {
        return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS)
                .to(TimeUnit.SECONDS);
    }

    /**
     * One request/response cycle: send the file index and block set,
     * consume the token stream into a temp file, verify and rename.
     */
    private static boolean transferOnce(SyncOptions opts, int seed,
            InputStream in, OutputStream out, int idx, FileEntry f,
            Path target, boolean forceWhole) throws IOException {
        WireIO.writeInt(out, idx);
        BlockSet set = (forceWhole || opts.wholeFile)
                ? new BlockSet()
                : BlockSet.build(target, opts.blockSize, seed);
        set.send(out);
        out.flush();

        Path parent = target.getParent();
        if (parent != null) {
            Files2.mkpath(parent);
        }
        Path tmp = Files.createTempFile(
                parent == null ? Path.of(".") : parent, ".rsyncz", ".part");
        boolean ok = false;
        try (FileChannel tmpCh = FileChannel.open(tmp,
                StandardOpenOption.WRITE);
             FileChannel basis = set.blksz > 0
                     ? FileChannel.open(target, StandardOpenOption.READ)
                     : null) {
            TokenStream.read(in, new TokenStream.Handler() {
                @Override
                public void literal(byte[] data) throws IOException {
                    tmpCh.write(ByteBuffer.wrap(data));
                }

                @Override
                public void copy(int blockIdx) throws IOException {
                    BlockSet.Block b = set.blocks.get(blockIdx);
                    basis.transferTo(b.offs, b.len, tmpCh);
                }
            });
            byte[] remoteMd = WireIO.readBytes(in, Hashes.MD4_DIGEST_LENGTH);
            tmpCh.force(false);
            byte[] localMd = Hashes.file(tmp, seed);
            ok = Arrays.equals(remoteMd, localMd);
        } finally {
            if (ok) {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.deleteIfExists(tmp);
            }
        }
        return ok;
    }

    /** Create or replace a symlink (-l). */
    private static void handleSymlink(SyncOptions opts, FileEntry f,
            Path target) throws IOException {
        if (opts.dryRun) {
            Log.log1("%s -> %s", f.path, f.link);
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files2.mkpath(parent);
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Log.warn("%s: cannot replace directory with symlink", f.path);
            return;
        }
        Files.deleteIfExists(target);
        Files.createSymbolicLink(target, Path.of(f.link));
    }

    /** Apply -p/-t/-o/-g attributes, best effort like the C code. */
    private static void applyAttrs(SyncOptions opts, FileEntry f, Path target) {
        if (f.isSymlink()) {
            return; // lchmod/lutimes are not portable; skip like a no-op
        }
        if (opts.perms) {
            Files2.setPermissions(target, f.mode);
        }
        if (opts.times) {
            Files2.setMtime(target, f.mtime);
        }
        if (opts.owner) {
            Files2.setUid(target, f.uid);
        }
        if (opts.group) {
            Files2.setGid(target, f.gid);
        }
    }

    /**
     * --delete: remove everything under {@code root} that is not in the
     * sender's list and not protected by an exclusion.
     */
    private static void deletePass(SyncOptions opts, Path root,
            List<FileEntry> flist) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Set<String> keep = new HashSet<>();
        for (FileEntry f : flist) {
            keep.add(f.path);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                String rel = rel(root, file);
                if (!keep.contains(rel) && opts.rules.keep(rel, false)) {
                    Log.log1("deleting %s", rel);
                    if (!opts.dryRun) {
                        Files.delete(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                String rel = rel(root, dir);
                if (rel.isEmpty() || keep.contains(rel)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!opts.rules.keep(rel, true)) {
                    return FileVisitResult.CONTINUE;
                }
                Log.log1("deleting directory %s", rel);
                if (!opts.dryRun) {
                    try {
                        Files.delete(dir); // only if empty
                    } catch (IOException ignored) {
                        // Non-empty (protected) directory: leave it.
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
