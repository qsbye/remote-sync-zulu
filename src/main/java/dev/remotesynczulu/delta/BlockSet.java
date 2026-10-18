package dev.remotesynczulu.delta;

import dev.remotesynczulu.checksum.Hashes;
import dev.remotesynczulu.checksum.RollingChecksum;
import dev.remotesynczulu.io.WireIO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A receiver-side block-checksum set, translated from openrsync's
 * uploader.c init_blkset()/init_blk() and blocks.c blk_recv()/
 * blk_send_ack().
 *
 * <p>Wire format (little-endian):
 * <pre>
 *   i32 blksz   number of blocks
 *   i32 len     nominal block length
 *   i32 csum    strong-checksum bytes per block (CSUM_LENGTH_PHASE1 = 2)
 *   i32 rem     length of the short trailing block (0 = none)
 *   then per block: i32 fast checksum + csum bytes of the MD4
 * </pre>
 */
public final class BlockSet {

    /** Minimum (and default) block length: BLOCK_SIZE_MIN. */
    public static final int BLOCK_SIZE_MIN = 700;

    /** Strong-checksum bytes sent per block (CSUM_LENGTH_PHASE1). */
    public static final int BLOCK_CSUM_LENGTH = 2;

    /** One block's metadata (struct blk). */
    public static final class Block {
        public int idx;
        public long offs;
        public int len;
        public int chksumShort;
        public final byte[] chksumLong = new byte[Hashes.MD4_DIGEST_LENGTH];
    }

    public int blksz;
    public int len;
    public int csum = BLOCK_CSUM_LENGTH;
    public int rem;
    public long size;
    public final List<Block> blocks = new ArrayList<>();

    /**
     * init_blkset(): choose the block geometry for a file of {@code sz}
     * bytes. {@code blockSize > 0} forces the block length (-B).
     */
    public static BlockSet geometry(long sz, long blockSize) {
        BlockSet s = new BlockSet();
        if (blockSize > 0) {
            s.len = (int) blockSize;
        } else if (sz >= (long) BLOCK_SIZE_MIN * BLOCK_SIZE_MIN) {
            // Simple rounded-up integer square root, multiple of eight.
            long v = (long) Math.ceil(Math.sqrt((double) sz));
            if ((v % 8) > 0) {
                v += 8 - (v % 8);
            }
            s.len = (int) v;
        } else {
            s.len = BLOCK_SIZE_MIN;
        }
        s.size = sz;
        s.blksz = (int) (sz / s.len);
        if (s.blksz == 0) {
            s.rem = (int) sz;
        } else {
            s.rem = (int) (sz % s.len);
        }
        if (s.rem != 0) {
            s.blksz++;
        }
        return s;
    }

    /**
     * Build a block set from a basis file. Returns an empty set
     * (blksz = 0) when the basis cannot be used, which tells the sender
     * to transmit the file literally.
     */
    public static BlockSet build(Path basis, long blockSize, int seed) {
        BlockSet set;
        try {
            long sz = java.nio.file.Files.size(basis);
            if (sz == 0) {
                return new BlockSet();
            }
            set = geometry(sz, blockSize);
            try (FileChannel ch = FileChannel.open(basis,
                    StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.allocate(set.len);
                long offs = 0;
                for (int i = 0; i < set.blksz; i++) {
                    Block b = new Block();
                    b.idx = i;
                    b.offs = offs;
                    b.len = (i == set.blksz - 1 && set.rem != 0)
                            ? set.rem : set.len;
                    buf.clear();
                    buf.limit(b.len);
                    while (buf.hasRemaining()) {
                        if (ch.read(buf) < 0) {
                            throw new IOException("basis truncated");
                        }
                    }
                    b.chksumShort = RollingChecksum.sum(buf.array(), 0, b.len);
                    byte[] md = Hashes.slow(buf.array(), 0, b.len, seed);
                    System.arraycopy(md, 0, b.chksumLong, 0, md.length);
                    set.blocks.add(b);
                    offs += b.len;
                }
            }
            return set;
        } catch (IOException e) {
            return new BlockSet();
        }
    }

    /** blk_recv_ack()/blk_send_ack() metadata + per-block checksums. */
    public void send(OutputStream out) throws IOException {
        WireIO.writeInt(out, blksz);
        WireIO.writeInt(out, len);
        WireIO.writeInt(out, csum);
        WireIO.writeInt(out, rem);
        for (Block b : blocks) {
            WireIO.writeInt(out, b.chksumShort);
            out.write(b.chksumLong, 0, csum);
        }
        out.flush();
    }

    /** blk_recv(): read a block set from the wire. */
    public static BlockSet recv(InputStream in) throws IOException {
        BlockSet s = new BlockSet();
        s.blksz = WireIO.readInt(in);
        s.len = WireIO.readInt(in);
        s.csum = WireIO.readInt(in);
        s.rem = WireIO.readInt(in);
        if (s.blksz < 0 || s.len < 0 || s.rem < 0
                || (s.rem != 0 && s.rem >= s.len)
                || s.csum < 0 || s.csum > Hashes.MD4_DIGEST_LENGTH) {
            throw new IOException("invalid block-set prologue: blksz="
                    + s.blksz + " len=" + s.len + " rem=" + s.rem
                    + " csum=" + s.csum);
        }
        long offs = 0;
        for (int j = 0; j < s.blksz; j++) {
            Block b = new Block();
            b.chksumShort = WireIO.readInt(in);
            WireIO.readFully(in, b.chksumLong, 0, s.csum);
            b.offs = offs;
            b.idx = j;
            b.len = (j == s.blksz - 1 && s.rem != 0) ? s.rem : s.len;
            offs += b.len;
            s.blocks.add(b);
        }
        s.size = offs;
        return s;
    }
}
