package dev.remotesynczulu.net;

import dev.remotesynczulu.io.WireIO;
import dev.remotesynczulu.sync.Receiver;
import dev.remotesynczulu.sync.Sender;
import dev.remotesynczulu.sync.SyncOptions;
import dev.remotesynczulu.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * rsync daemon, translated from openrsync's server.c/socket.c.
 *
 * <p>Session handshake (all little-endian):
 * <pre>
 *   client → server: i32 protocol version (27)
 *   server → client: i32 protocol version, i32 checksum seed
 *   client → server: i32 mode (0 = pull, 1 = push),
 *                    i32 argc, argc × (i32 length + UTF-8 bytes)
 * </pre>
 * For a pull the server becomes the sender and the arguments are its
 * source paths; for a push the server becomes the receiver and the first
 * argument is the destination directory.
 */
public final class RsyncServer {

    public static final int PROTOCOL_VERSION = 27;

    public static final int MODE_PULL = 0;
    public static final int MODE_PUSH = 1;

    private final SyncOptions opts;

    public RsyncServer(SyncOptions opts) {
        this.opts = opts;
    }

    public void serve() throws IOException {
        try (ServerSocket ss = new ServerSocket(opts.port)) {
            Log.log1("rsyncz daemon listening on port %d", opts.port);
            for (;;) {
                Socket s = ss.accept();
                Thread t = new Thread(() -> handleQuietly(s),
                        "rsyncz-session-" + s.getPort());
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private void handleQuietly(Socket s) {
        try (s) {
            handle(s);
        } catch (Exception e) {
            Log.warn("session %s: %s", s.getRemoteSocketAddress(),
                    e.getMessage());
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        int proto = WireIO.readInt(in);
        if (proto != PROTOCOL_VERSION) {
            throw new IOException("unsupported protocol " + proto);
        }
        int seed = new SecureRandom().nextInt();
        WireIO.writeInt(out, PROTOCOL_VERSION);
        WireIO.writeInt(out, seed);

        int mode = WireIO.readInt(in);
        int argc = WireIO.readInt(in);
        if (argc < 0 || argc > 4096) {
            throw new IOException("bad argc " + argc);
        }
        List<String> args = new ArrayList<>();
        for (int i = 0; i < argc; i++) {
            int len = WireIO.readInt(in);
            args.add(new String(WireIO.readBytes(in, len),
                    StandardCharsets.UTF_8));
        }
        // The client sends its options (fargs.c); adopt them per session.
        int bits = WireIO.readInt(in);
        long maxSz = WireIO.readLong(in);
        long minSz = WireIO.readLong(in);
        long blkSz = WireIO.readLong(in);
        int nrules = WireIO.readInt(in);
        if (nrules < 0 || nrules > 65536) {
            throw new IOException("bad rule count " + nrules);
        }
        List<String> ruleLines = new ArrayList<>();
        for (int i = 0; i < nrules; i++) {
            int len = WireIO.readInt(in);
            ruleLines.add(new String(WireIO.readBytes(in, len),
                    StandardCharsets.UTF_8));
        }
        out.flush();

        SyncOptions session = opts.sessionCopy(
                bits, maxSz, minSz, blkSz, ruleLines);

        if (mode == MODE_PULL) {
            Sender.run(session, args, seed, in, out);
        } else if (mode == MODE_PUSH) {
            if (args.isEmpty()) {
                throw new IOException("push without destination");
            }
            Receiver.run(session, Path.of(args.get(0)), seed, in, out);
        } else {
            throw new IOException("unknown mode " + mode);
        }
    }
}
