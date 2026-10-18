package dev.remotesynczulu.delta;

import dev.remotesynczulu.checksum.Hashes;
import dev.remotesynczulu.checksum.RollingChecksum;
import dev.remotesynczulu.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Sender-side block matcher, translated from openrsync's blocks.c
 * blk_match()/blk_find() plus the blktab hashtable.
 *
 * <p>The source file is scanned with the fast rolling checksum; candidate
 * blocks (hint first, then the 65536-bucket hashtable) are verified with
 * the seeded MD4. Matches yield copy tokens, everything else is emitted
 * as literal data. The scan stops at {@code size + 1 - lastBlockLen},
 * exactly like the C code — a short tail never matches anything.
 */
public final class BlockMatcher {

    /** Hashtable bucket count (BLKTAB_SZ). */
    public static final int BLKTAB_SZ = 65536;

    /** Token consumer: literal runs and block-copy references. */
    public interface Sink {
        void literal(byte[] buf, int off, int len);
        void copy(int blockIdx);
    }

    private final BlockSet set;
    private final int seed;
    private final List<List<BlockSet.Block>> buckets;

    public BlockMatcher(BlockSet set, int seed) {
        this.set = set;
        this.seed = seed;
        this.buckets = new ArrayList<>(BLKTAB_SZ);
        for (int i = 0; i < BLKTAB_SZ; i++) {
            buckets.add(null);
        }
        // blkhash_set(): index blocks by fast-checksum modulo table size.
        for (BlockSet.Block b : set.blocks) {
            int idx = bucketOf(b.chksumShort);
            List<BlockSet.Block> q = buckets.get(idx);
            if (q == null) {
                q = new ArrayList<>();
                buckets.set(idx, q);
            }
            q.add(b);
        }
    }

    private static int bucketOf(int chksumShort) {
        // C: chksum_short (uint32_t) % 65536 == low 16 bits.
        return chksumShort & 0xffff;
    }

    /**
     * blk_match(): scan {@code src} (the whole local file) and emit the
     * token stream for it. {@code stats} may be null.
     */
    public void match(String path, byte[] src, Sink sink, Stats stats) {
        int size = src.length;
        if (stats != null) {
            stats.totalBytes += size;
        }

        if (size == 0 || set.blksz == 0) {
            // No basis blocks: send the whole file literally.
            if (size > 0) {
                sink.literal(src, 0, size);
                if (stats != null) {
                    stats.unmatched += size;
                }
            }
            return;
        }

        int lastBlockLen = set.blocks.get(set.blksz - 1).len;
        long end = (long) size + 1 - lastBlockLen;
        long last = 0;
        long offs = 0;
        int hint = 0;
        boolean recompute = true;
        int s1 = 0, s2 = 0;

        while (offs < end) {
            int remain = (int) (size - offs);
            int osz = Math.min(remain, set.len);

            int fhash;
            if (recompute) {
                fhash = RollingChecksum.sum(src, (int) offs, osz);
                s1 = fhash & 0xffff;
                s2 = fhash >>> 16;
                recompute = false;
            } else {
                fhash = (s1 & 0xffff) | (s2 << 16);
            }

            BlockSet.Block found = null;
            byte[] md = null;

            // blk_find(): try the hint first.
            if (hint < set.blksz) {
                BlockSet.Block h = set.blocks.get(hint);
                if (fhash == h.chksumShort && osz == h.len) {
                    md = Hashes.slow(src, (int) offs, osz, seed);
                    if (mdEquals(md, h.chksumLong, set.csum)) {
                        found = h;
                        Log.log4("%s: hinted match at %d, block %d",
                                path, offs, h.idx);
                    }
                }
            }

            // Then the hashtable bucket.
            if (found == null) {
                List<BlockSet.Block> q = buckets.get(bucketOf(fhash));
                if (q != null) {
                    for (BlockSet.Block b : q) {
                        if (fhash != b.chksumShort || osz != b.len) {
                            continue;
                        }
                        if (md == null) {
                            md = Hashes.slow(src, (int) offs, osz, seed);
                        }
                        if (mdEquals(md, b.chksumLong, set.csum)) {
                            found = b;
                            Log.log4("%s: hash match at %d, block %d",
                                    path, offs, b.idx);
                            break;
                        }
                    }
                }
            }

            if (found != null) {
                int litLen = (int) (offs - last);
                if (litLen > 0) {
                    sink.literal(src, (int) last, litLen);
                    if (stats != null) {
                        stats.unmatched += litLen;
                    }
                }
                sink.copy(found.idx);
                if (stats != null) {
                    stats.matched += found.len;
                }
                offs += found.len;
                last = offs;
                hint = found.idx + 1;
                recompute = true;
                continue;
            }

            // blk_find() failure path: roll the window one byte forward
            // (signed arithmetic, as with the C char *).
            byte outB = src[(int) offs];
            s1 -= outB;
            s2 -= (long) osz * outB;
            if (osz < remain) {
                s1 += src[(int) offs + osz];
                s2 += s1;
            }
            offs++;
        }

        // Emit the remaining tail.
        int tail = (int) (size - last);
        if (tail > 0) {
            sink.literal(src, (int) last, tail);
            if (stats != null) {
                stats.unmatched += tail;
            }
        }
    }

    private static boolean mdEquals(byte[] a, byte[] b, int len) {
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /** Transfer statistics (sess->total_matched/total_unmatched). */
    public static final class Stats {
        public long matched;
        public long unmatched;
        public long totalBytes;
    }
}
