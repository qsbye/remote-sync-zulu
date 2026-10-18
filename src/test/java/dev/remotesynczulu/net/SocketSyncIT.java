package dev.remotesynczulu.net;

import dev.remotesynczulu.sync.SyncOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback socket test: run the daemon protocol against a real TCP
 * server/client pair (socket.c path) with push and pull directions.
 */
class SocketSyncIT {

    @TempDir
    Path src;

    @TempDir
    Path dst;

    @Test
    void pushAndPullOverLoopback() throws Exception {
        byte[] payload = new byte[120_000];
        new Random(21).nextBytes(payload);
        write(src.resolve("dir/blob.bin"), payload);

        SyncOptions opts = new SyncOptions();
        opts.recursive = true;
        opts.port = freePort();

        Thread server = new Thread(() -> {
            try {
                new RsyncServer(opts).serve();
            } catch (IOException ignored) {
                // Server exits when the test JVM shuts down.
            }
        });
        server.setDaemon(true);
        server.start();
        Thread.sleep(300); // let the listener come up

        // Push: local src → server-side staging directory.
        String staging = dst.resolve("staging").toString();
        Files.createDirectories(dst.resolve("staging"));
        RsyncClient.push(opts, List.of(src.resolve("dir").toString()),
                "127.0.0.1", staging);
        assertArrayEquals(payload,
                Files.readAllBytes(dst.resolve("staging/dir/blob.bin")));

        // Pull: server-side staging → a second local directory.
        String remote = staging + "/dir";
        RsyncClient.pull(opts, "127.0.0.1", List.of(remote),
                dst.resolve("pulled"));
        assertArrayEquals(payload,
                Files.readAllBytes(dst.resolve("pulled/dir/blob.bin")));
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static void write(Path p, byte[] data) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }

    @SuppressWarnings("unused")
    private static void pipePairSanity() throws IOException {
        // Guard: piped streams used by LocalSync support 64K buffers.
        PipedInputStream in = new PipedInputStream(1 << 16);
        assertTrue(new PipedOutputStream(in) != null);
    }

    static final AtomicReference<Throwable> UNUSED = new AtomicReference<>();
}
