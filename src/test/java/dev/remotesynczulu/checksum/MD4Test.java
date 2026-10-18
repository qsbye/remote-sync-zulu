package dev.remotesynczulu.checksum;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RFC 1320 test vectors for the MD4 translation. */
class MD4Test {

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String md4(String s) {
        MD4 md4 = new MD4();
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        md4.update(b, 0, b.length);
        return hex(md4.digest());
    }

    @Test
    void rfc1320Vectors() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", md4(""));
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", md4("a"));
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", md4("abc"));
        assertEquals("d9130a8164549fe818874806e1c7014b", md4("message digest"));
        assertEquals("d79e1c308aa5bbcdeea8ed63df412da9",
                md4("abcdefghijklmnopqrstuvwxyz"));
        assertEquals("043f8582f241db351ce627e153e7f0e4",
                md4("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"));
        assertEquals("e33b4ddc9c38f2199c3e7b164fcc0536",
                md4("12345678901234567890123456789012345678901234567890123456789012345678901234567890"));
    }

    @Test
    void incrementalMatchesOneShot() {
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        String oneShot = hex(MD4.hash(data));
        MD4 md4 = new MD4();
        for (int i = 0; i < data.length; i += 7) {
            int n = Math.min(7, data.length - i);
            md4.update(data, i, n);
        }
        assertEquals(oneShot, hex(md4.digest()));
    }
}
