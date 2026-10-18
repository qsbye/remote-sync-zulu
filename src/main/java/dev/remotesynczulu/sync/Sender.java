package dev.remotesynczulu.sync;

import dev.remotesynczulu.checksum.Hashes;
import dev.remotesynczulu.delta.BlockMatcher;
import dev.remotesynczulu.delta.BlockSet;
import dev.remotesynczulu.delta.TokenStream;
import dev.remotesynczulu.flist.FileEntry;
import dev.remotesynczulu.flist.FlistGen;
import dev.remotesynczulu.flist.FlistIO;
import dev.remotesynczulu.io.WireIO;
import dev.remotesynczulu.util.Humanize;
import dev.remotesynczulu.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The sender role, translated from openrsync's sender.c rsync_sender().
 *
 * <p>The C original is event-driven (poll + iobuf); this translation keeps
 * the same wire exchange but drives it with plain blocking I/O, one
 * request/response cycle per file:
 *
 * <ol>
 *   <li>send the file list, then the i32 io-error count</li>
 *   <li>loop: read i32 file index ({@code -1} = goodbye), read the
 *       receiver's block set, stream block-match tokens, write the i32 0
 *       terminator and the seeded whole-file MD4</li>
 *   <li>on goodbye, answer with an i32 {@code -1} marker and finish</li>
 * </ol>
 */
public final class Sender {

    private Sender() {
    }

    public static void run(SyncOptions opts, List<String> sources, int seed,
            InputStream in, OutputStream out) throws IOException {
        FlistGen.Result fl = FlistGen.generate(opts, sources);
        FlistIO.send(opts, out, fl.entries);
        WireIO.writeInt(out, 0); // io_errors
        out.flush();
        Log.log1("Transfer starting: %d files", fl.entries.size());

        BlockMatcher.Stats stats = new BlockMatcher.Stats();
        for (;;) {
            int idx = WireIO.readInt(in);
            if (idx == -1) {
                WireIO.writeInt(out, -1); // final goodbye marker
                out.flush();
                break;
            }
            if (idx < 0 || idx >= fl.entries.size()) {
                throw new IOException("sender: bad file index " + idx);
            }
            FileEntry f = fl.entries.get(idx);
            BlockSet set = BlockSet.recv(in);
            Path local = fl.localPaths.get(f.path);
            if (local == null) {
                throw new IOException("sender: no local path for " + f.path);
            }
            Log.log2("sending %s (%s)", f.path, Humanize.bytes(f.size));

            byte[] data = Files.readAllBytes(local);
            BlockMatcher matcher = new BlockMatcher(set, seed);
            try {
                matcher.match(f.path, data, TokenStream.writer(out), stats);
            } catch (TokenStream.TokenIOException e) {
                throw e.getCause();
            }
            TokenStream.writeEnd(out);
            out.write(Hashes.file(local, seed));
            out.flush();
        }

        Log.log1("sent %s  received-data 0  total size %s",
                Humanize.bytes(stats.unmatched + stats.matched),
                Humanize.bytes(stats.totalBytes));
        if (stats.totalBytes > 0) {
            Log.log1("speedup is %.2f", (double) stats.totalBytes
                    / Math.max(1, stats.unmatched));
        }
    }
}
