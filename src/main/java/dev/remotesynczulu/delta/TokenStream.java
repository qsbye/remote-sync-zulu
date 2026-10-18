package dev.remotesynczulu.delta;

import dev.remotesynczulu.io.WireIO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Token-stream codec between sender and receiver, translated from the
 * token plumbing in openrsync's sender.c/receiver.c (the data written
 * around blk_match()).
 *
 * <p>Per file, the sender emits:
 * <pre>
 *   i32 n &gt; 0 : literal run of n bytes follows
 *   i32 n &lt; 0 : copy block (-n - 1) from the basis file
 *   i32 0      : end of this file's tokens; a 16-byte whole-file
 *                MD4 checksum follows (written by the caller)
 * </pre>
 */
public final class TokenStream {

    private TokenStream() {
    }

    /** Adapt a {@link BlockMatcher.Sink} onto a wire stream. */
    public static BlockMatcher.Sink writer(OutputStream out) {
        return new BlockMatcher.Sink() {
            @Override
            public void literal(byte[] buf, int off, int len) {
                try {
                    WireIO.writeInt(out, len);
                    out.write(buf, off, len);
                } catch (IOException e) {
                    throw new TokenIOException(e);
                }
            }

            @Override
            public void copy(int blockIdx) {
                try {
                    WireIO.writeInt(out, -(blockIdx + 1));
                } catch (IOException e) {
                    throw new TokenIOException(e);
                }
            }
        };
    }

    /** Write the end-of-file token (i32 0). */
    public static void writeEnd(OutputStream out) throws IOException {
        WireIO.writeInt(out, 0);
    }

    /** Receiver-side token consumer. */
    public interface Handler {
        void literal(byte[] data) throws IOException;
        void copy(int blockIdx) throws IOException;
    }

    /**
     * Read tokens until the end-of-file marker, dispatching to
     * {@code handler}. Returns after the zero token; the whole-file
     * checksum is the caller's next read.
     */
    public static void read(InputStream in, Handler handler)
            throws IOException {
        for (;;) {
            int tok = WireIO.readInt(in);
            if (tok == 0) {
                return;
            }
            if (tok > 0) {
                byte[] data = WireIO.readBytes(in, tok);
                handler.literal(data);
            } else {
                handler.copy(-tok - 1);
            }
        }
    }

    /** Wraps IOException thrown from a Sink so matchers stay unchecked. */
    public static final class TokenIOException extends RuntimeException {
        public TokenIOException(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
