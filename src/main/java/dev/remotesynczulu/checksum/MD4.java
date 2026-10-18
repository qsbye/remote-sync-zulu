package dev.remotesynczulu.checksum;

import java.security.MessageDigest;

/**
 * MD4 message digest, translated from openrsync's md4.c (RFC 1320).
 *
 * <p>Implemented as a {@link MessageDigest} so it can be used incrementally
 * the same way the C {@code MD4_CTX} API is used (Init/Update/Final).
 *
 * <p>MD4 is cryptographically broken; it is retained here only because the
 * rsync family of protocols uses it as a strong rolling-transfer checksum.
 */
public final class MD4 extends MessageDigest implements Cloneable {

    private int a, b, c, d;          // chaining state
    private long count;              // number of bytes processed
    private final byte[] buffer = new byte[64]; // partial block

    public MD4() {
        super("MD4");
        engineReset();
    }

    private MD4(MD4 other) {
        super("MD4");
        this.a = other.a;
        this.b = other.b;
        this.c = other.c;
        this.d = other.d;
        this.count = other.count;
        System.arraycopy(other.buffer, 0, this.buffer, 0, 64);
    }

    @Override
    protected void engineReset() {
        a = 0x67452301;
        b = 0xefcdab89;
        c = 0x98badcfe;
        d = 0x10325476;
        count = 0;
    }

    @Override
    protected void engineUpdate(byte input) {
        int idx = (int) (count % 64);
        buffer[idx] = input;
        count++;
        if (idx == 63) {
            process(buffer, 0);
        }
    }

    @Override
    protected void engineUpdate(byte[] input, int offset, int len) {
        if (input == null) {
            throw new IllegalArgumentException("input null");
        }
        if (offset < 0 || len < 0 || (long) offset + len > input.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (len == 0) {
            return;
        }

        int idx = (int) (count % 64);
        count += len;

        // Fill a pending partial block first.
        if (idx != 0) {
            int n = Math.min(len, 64 - idx);
            System.arraycopy(input, offset, buffer, idx, n);
            offset += n;
            len -= n;
            if (idx + n == 64) {
                process(buffer, 0);
            }
        }

        // Process whole blocks straight from the input.
        while (len >= 64) {
            process(input, offset);
            offset += 64;
            len -= 64;
        }

        // Stash the remainder.
        if (len > 0) {
            System.arraycopy(input, offset, buffer, 0, len);
        }
    }

    @Override
    protected byte[] engineDigest() {
        // Pad: 0x80 then zeros until length ≡ 56 (mod 64), then 64-bit
        // little-endian bit count.
        byte[] pad = new byte[64];
        pad[0] = (byte) 0x80;
        int padLen = (int) (count % 64) < 56
                ? 56 - (int) (count % 64)
                : 120 - (int) (count % 64);
        long bits = count * 8;
        byte[] lenBytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            lenBytes[i] = (byte) (bits >>> (8 * i));
        }
        engineUpdate(pad, 0, padLen);
        engineUpdate(lenBytes, 0, 8);

        byte[] out = new byte[16];
        encode(out, 0, a);
        encode(out, 4, b);
        encode(out, 8, c);
        encode(out, 12, d);
        engineReset();
        return out;
    }

    @Override
    public Object clone() {
        return new MD4(this);
    }

    /** One-shot convenience digest (named to avoid the final
     * MessageDigest.digest signatures). */
    public static byte[] hash(byte[] data) {
        MD4 md4 = new MD4();
        md4.engineUpdate(data, 0, data.length);
        return md4.engineDigest();
    }

    public static byte[] hash(byte[] data, int off, int len) {
        MD4 md4 = new MD4();
        md4.engineUpdate(data, off, len);
        return md4.engineDigest();
    }

    // ---- RFC 1320 round functions -------------------------------------

    private static int f(int x, int y, int z) {
        return (x & y) | (~x & z);
    }

    private static int g(int x, int y, int z) {
        return (x & y) | (x & z) | (y & z);
    }

    private static int h(int x, int y, int z) {
        return x ^ y ^ z;
    }

    private static int rotl(int x, int n) {
        return (x << n) | (x >>> (32 - n));
    }

    private static void encode(byte[] out, int off, int v) {
        out[off] = (byte) v;
        out[off + 1] = (byte) (v >>> 8);
        out[off + 2] = (byte) (v >>> 16);
        out[off + 3] = (byte) (v >>> 24);
    }

    private void process(byte[] block, int off) {
        int[] x = new int[16];
        for (int i = 0; i < 16; i++) {
            x[i] = (block[off + i * 4] & 0xff)
                    | ((block[off + i * 4 + 1] & 0xff) << 8)
                    | ((block[off + i * 4 + 2] & 0xff) << 16)
                    | ((block[off + i * 4 + 3] & 0xff) << 24);
        }

        int aa = a, bb = b, cc = c, dd = d;

        // Round 1.
        aa = rotl(aa + f(bb, cc, dd) + x[0], 3);
        dd = rotl(dd + f(aa, bb, cc) + x[1], 7);
        cc = rotl(cc + f(dd, aa, bb) + x[2], 11);
        bb = rotl(bb + f(cc, dd, aa) + x[3], 19);
        aa = rotl(aa + f(bb, cc, dd) + x[4], 3);
        dd = rotl(dd + f(aa, bb, cc) + x[5], 7);
        cc = rotl(cc + f(dd, aa, bb) + x[6], 11);
        bb = rotl(bb + f(cc, dd, aa) + x[7], 19);
        aa = rotl(aa + f(bb, cc, dd) + x[8], 3);
        dd = rotl(dd + f(aa, bb, cc) + x[9], 7);
        cc = rotl(cc + f(dd, aa, bb) + x[10], 11);
        bb = rotl(bb + f(cc, dd, aa) + x[11], 19);
        aa = rotl(aa + f(bb, cc, dd) + x[12], 3);
        dd = rotl(dd + f(aa, bb, cc) + x[13], 7);
        cc = rotl(cc + f(dd, aa, bb) + x[14], 11);
        bb = rotl(bb + f(cc, dd, aa) + x[15], 19);

        // Round 2.
        aa = rotl(aa + g(bb, cc, dd) + x[0] + 0x5a827999, 3);
        dd = rotl(dd + g(aa, bb, cc) + x[4] + 0x5a827999, 5);
        cc = rotl(cc + g(dd, aa, bb) + x[8] + 0x5a827999, 9);
        bb = rotl(bb + g(cc, dd, aa) + x[12] + 0x5a827999, 13);
        aa = rotl(aa + g(bb, cc, dd) + x[1] + 0x5a827999, 3);
        dd = rotl(dd + g(aa, bb, cc) + x[5] + 0x5a827999, 5);
        cc = rotl(cc + g(dd, aa, bb) + x[9] + 0x5a827999, 9);
        bb = rotl(bb + g(cc, dd, aa) + x[13] + 0x5a827999, 13);
        aa = rotl(aa + g(bb, cc, dd) + x[2] + 0x5a827999, 3);
        dd = rotl(dd + g(aa, bb, cc) + x[6] + 0x5a827999, 5);
        cc = rotl(cc + g(dd, aa, bb) + x[10] + 0x5a827999, 9);
        bb = rotl(bb + g(cc, dd, aa) + x[14] + 0x5a827999, 13);
        aa = rotl(aa + g(bb, cc, dd) + x[3] + 0x5a827999, 3);
        dd = rotl(dd + g(aa, bb, cc) + x[7] + 0x5a827999, 5);
        cc = rotl(cc + g(dd, aa, bb) + x[11] + 0x5a827999, 9);
        bb = rotl(bb + g(cc, dd, aa) + x[15] + 0x5a827999, 13);

        // Round 3.
        aa = rotl(aa + h(bb, cc, dd) + x[0] + 0x6ed9eba1, 3);
        dd = rotl(dd + h(aa, bb, cc) + x[8] + 0x6ed9eba1, 9);
        cc = rotl(cc + h(dd, aa, bb) + x[4] + 0x6ed9eba1, 11);
        bb = rotl(bb + h(cc, dd, aa) + x[12] + 0x6ed9eba1, 15);
        aa = rotl(aa + h(bb, cc, dd) + x[2] + 0x6ed9eba1, 3);
        dd = rotl(dd + h(aa, bb, cc) + x[10] + 0x6ed9eba1, 9);
        cc = rotl(cc + h(dd, aa, bb) + x[6] + 0x6ed9eba1, 11);
        bb = rotl(bb + h(cc, dd, aa) + x[14] + 0x6ed9eba1, 15);
        aa = rotl(aa + h(bb, cc, dd) + x[1] + 0x6ed9eba1, 3);
        dd = rotl(dd + h(aa, bb, cc) + x[9] + 0x6ed9eba1, 9);
        cc = rotl(cc + h(dd, aa, bb) + x[5] + 0x6ed9eba1, 11);
        bb = rotl(bb + h(cc, dd, aa) + x[13] + 0x6ed9eba1, 15);
        aa = rotl(aa + h(bb, cc, dd) + x[3] + 0x6ed9eba1, 3);
        dd = rotl(dd + h(aa, bb, cc) + x[11] + 0x6ed9eba1, 9);
        cc = rotl(cc + h(dd, aa, bb) + x[7] + 0x6ed9eba1, 11);
        bb = rotl(bb + h(cc, dd, aa) + x[15] + 0x6ed9eba1, 15);

        a += aa;
        b += bb;
        c += cc;
        d += dd;
    }
}
