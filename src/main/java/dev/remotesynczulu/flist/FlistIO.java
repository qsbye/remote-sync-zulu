package dev.remotesynczulu.flist;

import dev.remotesynczulu.io.WireIO;
import dev.remotesynczulu.sync.SyncOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * File-list wire codec, translated from openrsync's flist.c
 * flist_send()/flist_recv().
 *
 * <p>Wire format (all integers little-endian), per entry:
 * <pre>
 *   u8      flag (non-zero = entry follows; zero terminates the list)
 *   i32     name length, then name bytes (UTF-8)
 *   i64     file size
 *   u32     mtime (epoch seconds)
 *   u32     mode (Unix type + permissions)
 *   u32     uid     (only when -o)
 *   u32     gid     (only when -g)
 *   i32     rdev    (only when -D)
 *   i32     link target length + bytes (only for symlinks)
 * </pre>
 * After the terminating zero flag, the sender writes a further i32
 * io-error count (handled by the sender/receiver, not here).
 *
 * <p>Difference from the C original: the shared-prefix name compression
 * (the flag bits reusing the previous entry's name prefix) is not used —
 * every name is sent in full. The layout is otherwise identical.
 */
public final class FlistIO {

    private FlistIO() {
    }

    public static void send(SyncOptions opts, OutputStream out,
            List<FileEntry> flist) throws IOException {
        for (FileEntry f : flist) {
            out.write(1);
            byte[] name = f.path.getBytes(StandardCharsets.UTF_8);
            WireIO.writeInt(out, name.length);
            out.write(name);
            WireIO.writeLong(out, f.size);
            WireIO.writeUInt(out, f.mtime);
            WireIO.writeUInt(out, f.mode);
            if (opts.owner) {
                WireIO.writeUInt(out, f.uid < 0 ? 0 : f.uid);
            }
            if (opts.group) {
                WireIO.writeUInt(out, f.gid < 0 ? 0 : f.gid);
            }
            if (opts.devices) {
                WireIO.writeInt(out, (int) f.rdev);
            }
            if (f.isSymlink()) {
                byte[] target = (f.link == null ? "" : f.link)
                        .getBytes(StandardCharsets.UTF_8);
                WireIO.writeInt(out, target.length);
                out.write(target);
            }
            if (opts.checksum && f.isReg()) {
                // flist.c: with -c, each regular file's MD4 follows.
                out.write(f.md == null ? new byte[16] : f.md);
            }
        }
        out.write(0);
        out.flush();
    }

    public static List<FileEntry> recv(SyncOptions opts, InputStream in)
            throws IOException {
        List<FileEntry> flist = new ArrayList<>();
        for (;;) {
            int flag = in.read();
            if (flag < 0) {
                throw new IOException("flist: unexpected end of stream");
            }
            if (flag == 0) {
                break;
            }
            int nameLen = WireIO.readInt(in);
            if (nameLen < 0 || nameLen > 1 << 20) {
                throw new IOException("flist: bad name length " + nameLen);
            }
            String path = new String(WireIO.readBytes(in, nameLen),
                    StandardCharsets.UTF_8);
            long size = WireIO.readLong(in);
            long mtime = WireIO.readUInt(in);
            int mode = (int) WireIO.readUInt(in);
            FileEntry e = new FileEntry(path, mode, size, mtime);
            if (opts.owner) {
                e.uid = WireIO.readUInt(in);
            }
            if (opts.group) {
                e.gid = WireIO.readUInt(in);
            }
            if (opts.devices) {
                e.rdev = WireIO.readInt(in) & 0xffffffffL;
            }
            if (e.isSymlink()) {
                int linkLen = WireIO.readInt(in);
                e.link = new String(WireIO.readBytes(in, linkLen),
                        StandardCharsets.UTF_8);
            }
            if (opts.checksum && e.isReg()) {
                e.md = WireIO.readBytes(in, 16);
            }
            flist.add(e);
        }
        return flist;
    }
}
