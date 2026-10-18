package dev.remotesynczulu.checksum;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Rolling property: roll(in/out) must equal a full recomputation. */
class RollingChecksumTest {

    @Test
    void rollEqualsRecompute() {
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        int len = 700;

        RollingChecksum roll = new RollingChecksum();
        roll.init(data, 0, len);
        for (int i = len; i < data.length; i++) {
            roll.roll(data[i - len], data[i], len);
            int expect = RollingChecksum.sum(data, i - len + 1, len);
            assertEquals(expect, roll.digest(),
                    "mismatch at offset " + (i - len + 1));
        }
    }

    @Test
    void knownValues() {
        // Checked against the C hash_fast() semantics (signed bytes).
        assertEquals(0, RollingChecksum.sum(new byte[0]));
        byte[] one = {1};
        // a = 1, b = 1 → 1 + (1 << 16)
        assertEquals(1 + (1 << 16), RollingChecksum.sum(one));
        byte[] four = {1, 2, 3, 4};
        // a = 10; b = 1+3+6+10 = 20
        assertEquals(10 + (20 << 16), RollingChecksum.sum(four));
    }

    @Test
    void signedBytes() {
        // Bytes above 0x7f must be treated as signed, like the C code.
        byte[] neg = {(byte) 0xff};
        // a = -1 → a & 0xffff = 0xffff; b = -1 → 0xffff << 16
        assertEquals(0xffffffff, RollingChecksum.sum(neg));
    }
}
