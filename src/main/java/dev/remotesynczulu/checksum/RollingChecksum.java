package dev.remotesynczulu.checksum;

/**
 * Fast 32-bit rolling checksum, translated from openrsync's hash.c
 * ({@code hash_fast()}), which in turn implements the checksum described in
 * Tridgell's "Efficient Algorithms for Sorting and Synchronization".
 *
 * <p>IMPORTANT: like the C original, which reads the buffer through a
 * {@code signed char *}, bytes are treated as <em>signed</em> quantities.
 * Java's {@code byte} is signed, so the translation is exact.
 */
public final class RollingChecksum {

    private int s1; // a(k, l)
    private int s2; // b(k, l)

    public RollingChecksum() {
        reset();
    }

    public void reset() {
        s1 = 0;
        s2 = 0;
    }

    /** Compute the checksum of a whole buffer (hash_fast). */
    public static int sum(byte[] buf, int off, int len) {
        int a = 0, b = 0;
        int i = off;
        int end = off + len;

        if (len > 4) {
            for (; i < end - 4; i += 4) {
                b += 4 * (a + buf[i])
                        + 3 * buf[i + 1]
                        + 2 * buf[i + 2]
                        + buf[i + 3];
                a += buf[i] + buf[i + 1] + buf[i + 2] + buf[i + 3];
            }
        }
        for (; i < end; i++) {
            a += buf[i];
            b += a;
        }
        return (a & 0xffff) + (b << 16);
    }

    public static int sum(byte[] buf) {
        return sum(buf, 0, buf.length);
    }

    /** Initialise this rolling state from a full window (recompute path). */
    public void init(byte[] buf, int off, int len) {
        int v = sum(buf, off, len);
        s1 = v & 0xffff;
        s2 = v >>> 16;
    }

    /**
     * Roll the window forward by one byte: drop {@code out}, the byte that
     * just left the front, and add {@code in}, the byte that just entered at
     * the back. {@code len} is the window length.
     *
     * <p>Derivation (signed arithmetic identical to the C reference):
     * a' = a - out + in; b' = b - len*out + a'.
     */
    public void roll(byte out, byte in, int len) {
        s1 = s1 - out + in;
        s2 = s2 - len * out + s1;
    }

    /** Current combined 32-bit checksum: (a & 0xffff) + (b << 16). */
    public int digest() {
        return (s1 & 0xffff) + (s2 << 16);
    }

    public int s1() {
        return s1 & 0xffff;
    }

    public int s2() {
        return s2;
    }
}
