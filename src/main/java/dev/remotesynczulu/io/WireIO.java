package dev.remotesynczulu.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wire primitives, translated from openrsync's io.c.
 *
 * <p>All integers on the wire are little-endian (io_write_int() et al).
 * Also implements the multiplexed message channel: a 4-byte header of
 * {@code ((tag + MPLEX_BASE) << 24) | len} followed by {@code len} payload
 * bytes, exactly as io.c's io_write_buf()/io_read_buf() multiplex mode.
 */
public final class WireIO {

    /** Multiplex tag base (io.c: MPLEX_BASE). */
    public static final int MPLEX_BASE = 7;

    /** Maximum payload of one multiplex frame (io.c: MAX_CHUNK). */
    public static final int MAX_CHUNK = 32768;

    // Multiplex tags (io.h / extern.h enum itag).
    public static final int IT_DATA = 0;
    public static final int IT_ERROR_XFER = 1;
    public static final int IT_INFO = 2;
    public static final int IT_ERROR = 3;
    public static final int IT_WARNING = 4;
    public static final int IT_SUCCESS = 100;
    public static final int IT_DELETED = 101;
    public static final int IT_NO_SEND = 102;

    private WireIO() {
    }

    // ---- little-endian primitives ---------------------------------------

    public static void writeInt(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    /** Unsigned 32-bit write (same bit pattern as writeInt). */
    public static void writeUInt(OutputStream out, long v) throws IOException {
        writeInt(out, (int) (v & 0xffffffffL));
    }

    public static void writeLong(OutputStream out, long v) throws IOException {
        out.write((int) (v & 0xff));
        out.write((int) ((v >>> 8) & 0xff));
        out.write((int) ((v >>> 16) & 0xff));
        out.write((int) ((v >>> 24) & 0xff));
        out.write((int) ((v >>> 32) & 0xff));
        out.write((int) ((v >>> 40) & 0xff));
        out.write((int) ((v >>> 48) & 0xff));
        out.write((int) ((v >>> 56) & 0xff));
    }

    public static int readInt(InputStream in) throws IOException {
        int b0 = readByte(in);
        int b1 = readByte(in);
        int b2 = readByte(in);
        int b3 = readByte(in);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    /** Read a 32-bit value and widen it as unsigned. */
    public static long readUInt(InputStream in) throws IOException {
        return readInt(in) & 0xffffffffL;
    }

    public static long readLong(InputStream in) throws IOException {
        long b0 = readByte(in);
        long b1 = readByte(in);
        long b2 = readByte(in);
        long b3 = readByte(in);
        long b4 = readByte(in);
        long b5 = readByte(in);
        long b6 = readByte(in);
        long b7 = readByte(in);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
                | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56);
    }

    public static void readFully(InputStream in, byte[] buf, int off, int len)
            throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) {
                throw new EOFException(
                        "expected " + len + " bytes, got " + got);
            }
            got += n;
        }
    }

    public static byte[] readBytes(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        readFully(in, buf, 0, len);
        return buf;
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("unexpected end of stream");
        }
        return b;
    }

    // ---- multiplex framing -----------------------------------------------

    /** Write one multiplex frame: header {@code ((tag+7)<<24)|len} + data. */
    public static void writeMux(OutputStream out, int tag, byte[] data,
            int off, int len) throws IOException {
        if (len > MAX_CHUNK) {
            throw new IllegalArgumentException(
                    "multiplex frame too large: " + len);
        }
        writeInt(out, ((tag + MPLEX_BASE) << 24) | len);
        out.write(data, off, len);
    }

    /**
     * Read one multiplex frame header. Returns the raw 32-bit header;
     * decode with {@link #muxTag(int)} and {@link #muxLen(int)}.
     */
    public static int readMuxHeader(InputStream in) throws IOException {
        return readInt(in);
    }

    public static int muxTag(int header) {
        return (header >>> 24) - MPLEX_BASE;
    }

    public static int muxLen(int header) {
        return header & 0xffffff;
    }
}
