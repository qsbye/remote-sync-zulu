package dev.remotesynczulu.delta;

import dev.remotesynczulu.delta.BlockMatcher.Sink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delta reconstruction: the token stream from block-matching a mutated
 * source against a basis must rebuild the source exactly.
 */
class BlockMatcherTest {

    @TempDir
    Path dir;

    @Test
    void mutatedFileReconstructs() throws IOException {
        byte[] basis = new byte[100_000];
        new Random(7).nextBytes(basis);
        Path basisPath = dir.resolve("basis.bin");
        Files.write(basisPath, basis);

        // Mutated source: prefix + inserted chunk + middle of basis +
        // changed tail.
        byte[] inserted = new byte[3_333];
        new Random(8).nextBytes(inserted);
        byte[] source = concat(
                slice(basis, 0, 20_000),
                inserted,
                slice(basis, 25_000, 80_000),
                new byte[]{9, 9, 9});

        BlockSet set = BlockSet.build(basisPath, 0, 1234);
        assertTrue(set.blksz > 0);

        byte[] rebuilt = applyTokens(set, basis, source);
        assertArrayEquals(source, rebuilt);
    }

    @Test
    void identicalFileIsAllCopies() throws IOException {
        byte[] basis = new byte[50_000];
        new Random(11).nextBytes(basis);
        Path basisPath = dir.resolve("basis.bin");
        Files.write(basisPath, basis);

        BlockSet set = BlockSet.build(basisPath, 0, 99);
        List<String> trace = new ArrayList<>();
        BlockMatcher m = new BlockMatcher(set, 99);
        m.match("t", basis, new Sink() {
            @Override
            public void literal(byte[] buf, int off, int len) {
                trace.add("lit:" + len);
            }

            @Override
            public void copy(int blockIdx) {
                trace.add("copy:" + blockIdx);
            }
        }, null);
        assertTrue(trace.stream().noneMatch(t -> t.startsWith("lit:")),
                "identical file should produce no literals: " + trace);
        assertEquals(set.blksz,
                trace.stream().filter(t -> t.startsWith("copy:")).count());
    }

    @Test
    void emptyBasisSendsLiteral() {
        BlockSet empty = new BlockSet();
        byte[] source = "hello world".getBytes();
        BlockMatcher.Stats stats = new BlockMatcher.Stats();
        BlockMatcher m = new BlockMatcher(empty, 5);
        ByteArrayOutputStream lit = new ByteArrayOutputStream();
        m.match("t", source, new Sink() {
            @Override
            public void literal(byte[] buf, int off, int len) {
                lit.write(buf, off, len);
            }

            @Override
            public void copy(int blockIdx) {
                throw new AssertionError("unexpected copy");
            }
        }, stats);
        assertArrayEquals(source, lit.toByteArray());
        assertEquals(source.length, stats.unmatched);
    }

    @Test
    void blockGeometry() {
        // init_blkset(): small files get the 700-byte minimum.
        BlockSet small = BlockSet.geometry(1000, 0);
        assertEquals(700, small.len);
        assertEquals(2, small.blksz);
        assertEquals(300, small.rem);

        // Large files use the rounded sqrt, multiple of eight.
        long big = 700L * 700 * 40;
        BlockSet large = BlockSet.geometry(big, 0);
        long expect = (long) Math.ceil(Math.sqrt(big));
        if (expect % 8 != 0) {
            expect += 8 - expect % 8;
        }
        assertEquals(expect, large.len);

        // Forced block size (-B).
        BlockSet forced = BlockSet.geometry(10_000, 4096);
        assertEquals(4096, forced.len);
        assertEquals(3, forced.blksz);
        assertEquals(10_000 % 4096, forced.rem);

        // Exact multiple: no remainder.
        BlockSet exact = BlockSet.geometry(2100, 0);
        assertEquals(3, exact.blksz);
        assertEquals(0, exact.rem);
    }

    // ---- helpers ---------------------------------------------------------

    private static byte[] applyTokens(BlockSet set, byte[] basis,
            byte[] source) {
        BlockMatcher m = new BlockMatcher(set, 1234);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.match("t", source, new Sink() {
            @Override
            public void literal(byte[] buf, int off, int len) {
                out.write(buf, off, len);
            }

            @Override
            public void copy(int blockIdx) {
                BlockSet.Block b = set.blocks.get(blockIdx);
                out.write(basis, (int) b.offs, b.len);
            }
        }, null);
        return out.toByteArray();
    }

    private static byte[] slice(byte[] a, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(a, from, r, 0, r.length);
        return r;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] r = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, off, p.length);
            off += p.length;
        }
        return r;
    }
}
