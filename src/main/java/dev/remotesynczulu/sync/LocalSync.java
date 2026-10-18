package dev.remotesynczulu.sync;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local-mode driver: sender and receiver in one process, translated from
 * openrsync's local mode (main.c forking a sender and a receiver
 * connected by socketpair()).
 *
 * <p>Instead of fork + socketpair, this translation runs the sender on a
 * worker thread connected to the receiver by a pair of piped streams —
 * the same byte-oriented full-duplex channel, the same wire format, the
 * same code paths as the network mode.
 */
public final class LocalSync {

    private LocalSync() {
    }

    public static void run(SyncOptions opts, List<String> sources, Path dest)
            throws IOException, InterruptedException {
        PipedInputStream s2rIn = new PipedInputStream(1 << 16);
        PipedOutputStream s2rOut = new PipedOutputStream(s2rIn);
        PipedInputStream r2sIn = new PipedInputStream(1 << 16);
        PipedOutputStream r2sOut = new PipedOutputStream(r2sIn);

        int seed = new SecureRandom().nextInt();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread sender = new Thread(() -> {
            try {
                Sender.run(opts, sources, seed, r2sIn, s2rOut);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                try {
                    s2rOut.close();
                } catch (IOException ignored) {
                }
            }
        }, "rsyncz-sender");
        sender.setDaemon(true);
        sender.start();

        Throwable receiverFailure = null;
        try {
            Receiver.run(opts, dest, seed, s2rIn, r2sOut);
        } catch (Throwable t) {
            receiverFailure = t;
            // Unblock the sender if it is mid-write to us.
            try {
                r2sOut.close();
            } catch (IOException ignored) {
            }
            try {
                s2rIn.close();
            } catch (IOException ignored) {
            }
        }
        sender.join(30_000);

        // Prefer the sender's failure: a receiver-side EOF is often just
        // the echo of a sender crash closing the pipe.
        Throwable t = failure.get() != null ? failure.get()
                : receiverFailure;
        if (t != null) {
            if (t instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("local sync failed", t);
        }
        if (sender.isAlive()) {
            throw new IOException("sender did not finish");
        }
    }
}
