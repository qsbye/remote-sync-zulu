package dev.remotesynczulu.flist;

import dev.remotesynczulu.util.StrMode;

/**
 * One file-list entry, translated from openrsync's struct flist (flist.c).
 *
 * <p>The path is transfer-root relative, '/'-separated, with no leading
 * slash. Mode is the classic Unix type+permission encoding, which also
 * tells receivers the entry kind (regular/dir/symlink/device).
 */
public final class FileEntry {

    public final String path;
    public int mode;
    public long size;
    public long mtime;   // epoch seconds
    public long uid = -1;
    public long gid = -1;
    public long rdev;
    public String link;  // symlink target, null unless S_ISLNK
    public byte[] md;    // seedless whole-file MD4, only with --checksum

    public FileEntry(String path, int mode, long size, long mtime) {
        this.path = path;
        this.mode = mode;
        this.size = size;
        this.mtime = mtime;
    }

    public boolean isDir() {
        return StrMode.isDir(mode);
    }

    public boolean isReg() {
        return StrMode.isReg(mode);
    }

    public boolean isSymlink() {
        return StrMode.isLnk(mode);
    }

    public boolean isDevice() {
        return StrMode.isDev(mode) || StrMode.isFifo(mode);
    }

    @Override
    public String toString() {
        return StrMode.strmode(mode) + " " + size + " " + path;
    }
}
