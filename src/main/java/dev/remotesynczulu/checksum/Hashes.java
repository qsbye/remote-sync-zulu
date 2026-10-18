package dev.remotesynczulu.checksum;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Slow (strong) checksums, translated from openrsync's hash.c.
 *
 * <ul>
 *   <li>{@code hash_slow}: MD4 over {@code data || le32(seed)} — per-block
 *       strong checksum used during block matching.</li>
 *   <li>{@code hash_file}: MD4 over {@code le32(seed) || fileBytes} —
 *       whole-file checksum exchanged after each file transfer.</li>
 * </ul>
 */
public final class Hashes {

    /** MD4 digest length (CSUM_LENGTH_PHASE2). */
    public static final int MD4_DIGEST_LENGTH = 16;

    private Hashes() {
    }

    /** hash_slow(): MD4(buf, then little-endian trailing seed). */
    public static byte[] slow(byte[] buf, int off, int len, int seed) {
        MD4 md4 = new MD4();
        md4.update(buf, off, len);
        md4.update(le32(seed));
        return md4.digest();
    }

    public static byte[] slow(byte[] buf, int seed) {
        return slow(buf, 0, buf.length, seed);
    }

    /** hash_file_start(): new MD4 context primed with the leading seed. */
    public static MD4 fileStart(int seed) {
        MD4 md4 = new MD4();
        md4.update(le32(seed));
        return md4;
    }

    /**
     * Whole-file checksum of {@code path}: MD4(le32(seed) || bytes).
     * Equivalent to hash_file()/hash_fmap() in the C code.
     */
    public static byte[] file(Path path, int seed) throws IOException {
        MD4 md4 = fileStart(seed);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            while (ch.read(buf) != -1) {
                buf.flip();
                if (buf.hasRemaining()) {
                    md4.update(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
                }
                buf.clear();
            }
        }
        return md4.digest();
    }

    /** Seedless whole-file checksum used by --checksum comparisons. */
    public static byte[] fileSeedless(Path path) throws IOException {
        MD4 md4 = new MD4();
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            while (ch.read(buf) != -1) {
                buf.flip();
                if (buf.hasRemaining()) {
                    md4.update(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
                }
                buf.clear();
            }
        }
        return md4.digest();
    }

    /** Whole-stream checksum helper (used by tests and receivers). */
    public static byte[] stream(InputStream in, int seed) throws IOException {
        MD4 md4 = fileStart(seed);
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            md4.update(buf, 0, n);
        }
        return md4.digest();
    }

    /** Read exactly {@code len} bytes or throw EOFException. */
    public static void readFully(InputStream in, byte[] buf, int off, int len)
            throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) {
                throw new EOFException("expected " + len + " bytes, got " + got);
            }
            got += n;
        }
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }
}
