package dev.remotesynczulu.net;

import dev.remotesynczulu.io.WireIO;
import dev.remotesynczulu.sync.Receiver;
import dev.remotesynczulu.sync.Sender;
import dev.remotesynczulu.sync.SyncOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Client side of the network protocol, translated from openrsync's
 * client.c/socket.c (the {@code host:path} connect path).
 */
public final class RsyncClient {

    private RsyncClient() {
    }

    /** Pull: the remote host is the sender of {@code remoteSources}. */
    public static void pull(SyncOptions opts, String host,
            List<String> remoteSources, Path localDest) throws IOException {
        try (Socket s = new Socket(host, opts.port)) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            int seed = handshake(opts, out, in, RsyncServer.MODE_PULL,
                    remoteSources);
            Receiver.run(opts, localDest, seed, in, out);
        }
    }

    /** Push: the local side is the sender; {@code remoteDest} receives. */
    public static void push(SyncOptions opts, List<String> localSources,
            String host, String remoteDest) throws IOException {
        try (Socket s = new Socket(host, opts.port)) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            int seed = handshake(opts, out, in, RsyncServer.MODE_PUSH,
                    List.of(remoteDest));
            Sender.run(opts, localSources, seed, in, out);
        }
    }

    private static int handshake(SyncOptions opts, OutputStream out,
            InputStream in, int mode, List<String> args) throws IOException {
        WireIO.writeInt(out, RsyncServer.PROTOCOL_VERSION);
        out.flush();
        int version = WireIO.readInt(in);
        if (version != RsyncServer.PROTOCOL_VERSION) {
            throw new IOException("server protocol " + version
                    + " does not match " + RsyncServer.PROTOCOL_VERSION);
        }
        int seed = WireIO.readInt(in);

        WireIO.writeInt(out, mode);
        WireIO.writeInt(out, args.size());
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            WireIO.writeInt(out, b.length);
            out.write(b);
        }
        // fargs.c equivalent: the server adopts the client's options.
        WireIO.writeInt(out, opts.toBits());
        WireIO.writeLong(out, opts.maxSize);
        WireIO.writeLong(out, opts.minSize);
        WireIO.writeLong(out, opts.blockSize);
        List<String> ruleLines = opts.ruleLines();
        WireIO.writeInt(out, ruleLines.size());
        for (String line : ruleLines) {
            byte[] b = line.getBytes(StandardCharsets.UTF_8);
            WireIO.writeInt(out, b.length);
            out.write(b);
        }
        out.flush();
        return seed;
    }
}
