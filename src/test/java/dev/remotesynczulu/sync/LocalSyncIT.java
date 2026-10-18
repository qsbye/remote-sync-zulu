package dev.remotesynczulu.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end local synchronisation over piped sender/receiver threads. */
class LocalSyncIT {

    @TempDir
    Path src;

    @TempDir
    Path dst;

    @Test
    void nestedTreeSyncs() throws Exception {
        byte[] big = new byte[300_000];
        new Random(3).nextBytes(big);
        write(src.resolve("a/b/c/deep.txt"), big);
        write(src.resolve("a/top.txt"), "top".getBytes());
        write(src.resolve("root.txt"), "root".getBytes());

        SyncOptions opts = new SyncOptions();
        opts.recursive = true;
        opts.times = true;
        opts.perms = true;
        LocalSync.run(opts, List.of(src.toString() + "/"), dst.resolve("out"));

        assertArrayEquals(big,
                Files.readAllBytes(dst.resolve("out/a/b/c/deep.txt")));
        assertArrayEquals("top".getBytes(),
                Files.readAllBytes(dst.resolve("out/a/top.txt")));
        assertArrayEquals("root".getBytes(),
                Files.readAllBytes(dst.resolve("out/root.txt")));
    }

    @Test
    void trailingSlashCopiesContents() throws Exception {
        write(src.resolve("sub/file.txt"), "x".getBytes());
        SyncOptions opts = new SyncOptions();
        opts.recursive = true;
        LocalSync.run(opts,
                List.of(src.toString() + "/"), dst.resolve("out"));
        assertTrue(Files.exists(dst.resolve("out/sub/file.txt")));
        assertFalse(Files.exists(dst.resolve("out/" + src.getFileName())));
    }

    @Test
    void deltaUpdatesOnlyChangedParts() throws Exception {
        byte[] original = new byte[200_000];
        new Random(5).nextBytes(original);
        write(src.resolve("data.bin"), original);

        SyncOptions opts = new SyncOptions();
        opts.recursive = true;
        LocalSync.run(opts, List.of(src.toString() + "/"), dst.resolve("out"));
        assertArrayEquals(original,
                Files.readAllBytes(dst.resolve("out/data.bin")));

        // Mutate a small region; a second sync must still converge.
        byte[] mutated = original.clone();
        mutated[50_000] ^= 0x55;
        mutated[150_000] ^= 0x55;
        write(src.resolve("data.bin"), mutated);
        Files.getLastModifiedTime(src.resolve("data.bin"));
        // Ensure the quick check sees a different mtime or use -c.
        opts.checksum = true;
        LocalSync.run(opts, List.of(src.toString() + "/"), dst.resolve("out"));
        assertArrayEquals(mutated,
                Files.readAllBytes(dst.resolve("out/data.bin")));
    }

    @Test
    void symlinksPreserved() throws Exception {
        write(src.resolve("real.txt"), "payload".getBytes());
        Files.createSymbolicLink(src.resolve("link.txt"),
                Path.of("real.txt"));

        SyncOptions opts = new SyncOptions();
        opts.recursive = true;
        opts.links = true;
        LocalSync.run(opts, List.of(src.toString() + "/"), dst.resolve("out"));

        Path link = dst.resolve("out/link.txt");
        assertTrue(Files.isSymbolicLink(link));
        assertArrayEquals("payload".getBytes(), Files.readAllBytes(link));
    }

    @Test
    void deleteRemovesExtraneous() throws Exception {
        write(src.resolve("keep.txt"), "k".getBytes());
        write(dst.resolve("out/stale.txt"), "s".getBytes());
        write(dst.resolve("out/staledir/old.txt"), "o".getBytes());

        SyncOptions opts = new SyncOptions();
        opts.recursive = true;
        opts.del = true;
        LocalSync.run(opts, List.of(src.toString() + "/"), dst.resolve("out"));

        assertTrue(Files.exists(dst.resolve("out/keep.txt")));
        assertFalse(Files.exists(dst.resolve("out/stale.txt")));
        assertFalse(Files.exists(dst.resolve("out/staledir")));
    }

    @Test
    void excludeAndIncludeRules() throws Exception {
        write(src.resolve("keep.c"), "c".getBytes());
        write(src.resolve("drop.o"), "o".getBytes());
        write(src.resolve("build/out.bin"), "b".getBytes());

        SyncOptions opts = new SyncOptions();
        opts.recursive = true;
        opts.rules.addParsed("- *.o");
        opts.rules.addParsed("- build/");
        LocalSync.run(opts, List.of(src.toString() + "/"), dst.resolve("out"));

        assertTrue(Files.exists(dst.resolve("out/keep.c")));
        assertFalse(Files.exists(dst.resolve("out/drop.o")));
        assertFalse(Files.exists(dst.resolve("out/build")));
    }

    @Test
    void wholeFileModeAndDryRun() throws Exception {
        write(src.resolve("f.txt"), "content".getBytes());

        SyncOptions dry = new SyncOptions();
        dry.recursive = true;
        dry.dryRun = true;
        LocalSync.run(dry, List.of(src.toString() + "/"), dst.resolve("dry"));
        assertFalse(Files.exists(dst.resolve("dry/f.txt")),
                "dry run must not write");

        SyncOptions whole = new SyncOptions();
        whole.recursive = true;
        whole.wholeFile = true;
        LocalSync.run(whole, List.of(src.toString() + "/"), dst.resolve("whole"));
        assertArrayEquals("content".getBytes(),
                Files.readAllBytes(dst.resolve("whole/f.txt")));
    }

    @Test
    void mtimePreservedWithTimes() throws Exception {
        Path f = src.resolve("timed.txt");
        write(f, "t".getBytes());
        long epochSec = 1_600_000_000L;
        Files.setLastModifiedTime(f,
                java.nio.file.attribute.FileTime.from(epochSec,
                        java.util.concurrent.TimeUnit.SECONDS));

        SyncOptions opts = new SyncOptions();
        opts.recursive = true;
        opts.times = true;
        LocalSync.run(opts, List.of(src.toString() + "/"), dst.resolve("out"));

        long got = Files.getLastModifiedTime(dst.resolve("out/timed.txt"))
                .to(java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(Math.abs(got - epochSec) <= 1,
                "mtime not preserved: " + got);
    }

    private static void write(Path p, byte[] data) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }

    static {
        // Files.exists() with NOFOLLOW for symlink assertions.
        assert System.getProperty("file.encoding") != null;
    }

    @SuppressWarnings("unused")
    private static boolean existsNoFollow(Path p) {
        return Files.exists(p, LinkOption.NOFOLLOW_LINKS);
    }
}
