package dev.remotesynczulu.sync;

import dev.remotesynczulu.rules.FilterRules;

/**
 * Session options, translated from openrsync's struct opts (main.c) and
 * the flag plumbing in fargs.c. Field names mirror the C originals.
 */
public final class SyncOptions {

    public boolean recursive;      // -r
    public boolean links;          // -l: copy symlinks as symlinks
    public boolean perms;          // -p: preserve permissions
    public boolean times;          // -t: preserve modification times
    public boolean group;          // -g: preserve group
    public boolean owner;          // -o: preserve owner
    public boolean devices;        // -D: preserve device/special files
    public boolean dryRun;         // -n
    public boolean checksum;       // -c: checksum-based quick check
    public boolean wholeFile;      // -W: disable the delta algorithm
    public boolean update;         // -u: skip newer receiver files
    public boolean ignoreTimes;    // --ignore-times
    public boolean sizeOnly;       // --size-only
    public boolean del;            // --delete
    public long maxSize = -1;      // --max-size
    public long minSize = -1;      // --min-size
    public long blockSize = 0;     // -B / --block-size, 0 = automatic
    public int port = 873;         // --port
    public int verbose = 0;        // -v count minus -q count

    public final FilterRules rules = new FilterRules();

    /** -a: archive mode equals -rlptgoD (fargs.c). */
    public void setArchive() {
        recursive = true;
        links = true;
        perms = true;
        times = true;
        group = true;
        owner = true;
        devices = true;
    }

    // Option bits exchanged during the network handshake (fargs.c sends
    // the same information as the argv string in the C original).
    public static final int BIT_RECURSIVE = 1;
    public static final int BIT_LINKS = 1 << 1;
    public static final int BIT_PERMS = 1 << 2;
    public static final int BIT_TIMES = 1 << 3;
    public static final int BIT_GROUP = 1 << 4;
    public static final int BIT_OWNER = 1 << 5;
    public static final int BIT_DEVICES = 1 << 6;
    public static final int BIT_DRY_RUN = 1 << 7;
    public static final int BIT_CHECKSUM = 1 << 8;
    public static final int BIT_WHOLE_FILE = 1 << 9;
    public static final int BIT_UPDATE = 1 << 10;
    public static final int BIT_IGNORE_TIMES = 1 << 11;
    public static final int BIT_SIZE_ONLY = 1 << 12;
    public static final int BIT_DELETE = 1 << 13;

    public int toBits() {
        int b = 0;
        if (recursive) b |= BIT_RECURSIVE;
        if (links) b |= BIT_LINKS;
        if (perms) b |= BIT_PERMS;
        if (times) b |= BIT_TIMES;
        if (group) b |= BIT_GROUP;
        if (owner) b |= BIT_OWNER;
        if (devices) b |= BIT_DEVICES;
        if (dryRun) b |= BIT_DRY_RUN;
        if (checksum) b |= BIT_CHECKSUM;
        if (wholeFile) b |= BIT_WHOLE_FILE;
        if (update) b |= BIT_UPDATE;
        if (ignoreTimes) b |= BIT_IGNORE_TIMES;
        if (sizeOnly) b |= BIT_SIZE_ONLY;
        if (del) b |= BIT_DELETE;
        return b;
    }

    /** A session copy with the client's flags applied (server side). */
    public SyncOptions sessionCopy(int bits, long maxSz, long minSz,
            long blkSz, java.util.List<String> ruleLines) {
        SyncOptions o = new SyncOptions();
        o.recursive = (bits & BIT_RECURSIVE) != 0;
        o.links = (bits & BIT_LINKS) != 0;
        o.perms = (bits & BIT_PERMS) != 0;
        o.times = (bits & BIT_TIMES) != 0;
        o.group = (bits & BIT_GROUP) != 0;
        o.owner = (bits & BIT_OWNER) != 0;
        o.devices = (bits & BIT_DEVICES) != 0;
        o.dryRun = (bits & BIT_DRY_RUN) != 0;
        o.checksum = (bits & BIT_CHECKSUM) != 0;
        o.wholeFile = (bits & BIT_WHOLE_FILE) != 0;
        o.update = (bits & BIT_UPDATE) != 0;
        o.ignoreTimes = (bits & BIT_IGNORE_TIMES) != 0;
        o.sizeOnly = (bits & BIT_SIZE_ONLY) != 0;
        o.del = (bits & BIT_DELETE) != 0;
        o.maxSize = maxSz;
        o.minSize = minSz;
        o.blockSize = blkSz;
        o.verbose = this.verbose;
        o.port = this.port;
        for (String line : ruleLines) {
            o.rules.addParsed(line);
        }
        return o;
    }

    /** Export the rule chain as parseable lines (for the handshake). */
    public java.util.List<String> ruleLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (var r : rules.all()) {
            lines.add(r.toString());
        }
        return lines;
    }
}
