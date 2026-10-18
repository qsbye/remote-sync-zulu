package dev.remotesynczulu.cli;

import dev.remotesynczulu.net.RsyncClient;
import dev.remotesynczulu.net.RsyncServer;
import dev.remotesynczulu.sync.LocalSync;
import dev.remotesynczulu.sync.SyncOptions;
import dev.remotesynczulu.util.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line front end, translated from openrsync's main.c and fargs.c.
 *
 * <p>Usage:
 * <pre>
 *   rsyncz [options] SRC... DEST            local sync
 *   rsyncz [options] SRC... HOST:DEST       push over TCP
 *   rsyncz [options] HOST:SRC... DEST       pull over TCP
 *   rsyncz --daemon [--port N]              serve connections
 * </pre>
 * A "remote" argument has the form {@code host:path} (user@ is accepted
 * and ignored, as are IPv6 literals in brackets).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (UsageException e) {
            Log.err("%s", e.getMessage());
            usage();
            System.exit(2);
        } catch (Exception e) {
            Log.err("%s", e.getMessage() == null ? e : e.getMessage());
            System.exit(1);
        }
    }

    private static int run(String[] args) throws Exception {
        SyncOptions opts = new SyncOptions();
        List<String> positional = new ArrayList<>();
        boolean daemon = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help" -> {
                    usage();
                    return 0;
                }
                case "--daemon" -> daemon = true;
                case "--delete" -> opts.del = true;
                case "--ignore-times" -> opts.ignoreTimes = true;
                case "--size-only" -> opts.sizeOnly = true;
                case "--whole-file" -> opts.wholeFile = true;
                case "--exclude" -> opts.rules.addParsed(
                        "- " + requireValue(args, ++i, a));
                case "--include" -> opts.rules.addParsed(
                        "+ " + requireValue(args, ++i, a));
                case "--max-size" -> opts.maxSize =
                        parseSize(requireValue(args, ++i, a));
                case "--min-size" -> opts.minSize =
                        parseSize(requireValue(args, ++i, a));
                case "--block-size" -> opts.blockSize =
                        parseSize(requireValue(args, ++i, a));
                case "--port" -> opts.port =
                        Integer.parseInt(requireValue(args, ++i, a));
                default -> {
                    if (a.startsWith("--exclude=")) {
                        opts.rules.addParsed("- " + a.substring(10));
                    } else if (a.startsWith("--include=")) {
                        opts.rules.addParsed("+ " + a.substring(10));
                    } else if (a.startsWith("--max-size=")) {
                        opts.maxSize = parseSize(a.substring(11));
                    } else if (a.startsWith("--min-size=")) {
                        opts.minSize = parseSize(a.substring(11));
                    } else if (a.startsWith("--block-size=")) {
                        opts.blockSize = parseSize(a.substring(13));
                    } else if (a.startsWith("--port=")) {
                        opts.port = Integer.parseInt(a.substring(7));
                    } else if (a.startsWith("--")) {
                        throw new UsageException("unknown option: " + a);
                    } else if (a.startsWith("-") && a.length() > 1) {
                        i = parseShort(opts, args, i);
                    } else {
                        positional.add(a);
                    }
                }
            }
        }

        Log.setLevel(opts.verbose);

        if (daemon) {
            new RsyncServer(opts).serve();
            return 0;
        }

        if (positional.size() < 2) {
            throw new UsageException(
                    "need at least a source and a destination");
        }

        List<String> srcs = positional.subList(0, positional.size() - 1);
        String dest = positional.get(positional.size() - 1);

        boolean srcRemote = srcs.stream().anyMatch(Main::isRemote);
        boolean destRemote = isRemote(dest);
        if (srcRemote && destRemote) {
            throw new UsageException(
                    "cannot sync between two remote hosts");
        }

        if (destRemote) {
            String[] hp = splitHost(dest);
            RsyncClient.push(opts, srcs, hp[0], hp[1]);
        } else if (srcRemote) {
            // All sources must be on the same host (same restriction as
            // the C client, which opens one connection).
            List<String> remotePaths = new ArrayList<>();
            String host = null;
            for (String s : srcs) {
                if (!isRemote(s)) {
                    throw new UsageException(
                            "cannot mix local and remote sources");
                }
                String[] hp = splitHost(s);
                if (host == null) {
                    host = hp[0];
                } else if (!host.equals(hp[0])) {
                    throw new UsageException(
                            "all remote sources must be on one host");
                }
                remotePaths.add(hp[1]);
            }
            RsyncClient.pull(opts, host, remotePaths, Path.of(dest));
        } else {
            LocalSync.run(opts, srcs, Path.of(dest));
        }
        return 0;
    }

    /** Bundle of short flags, e.g. -avz; -B takes a value. */
    private static int parseShort(SyncOptions opts, String[] args, int i) {
        String a = args[i];
        for (int j = 1; j < a.length(); j++) {
            switch (a.charAt(j)) {
                case 'a' -> opts.setArchive();
                case 'r' -> opts.recursive = true;
                case 'l' -> opts.links = true;
                case 'p' -> opts.perms = true;
                case 't' -> opts.times = true;
                case 'g' -> opts.group = true;
                case 'o' -> opts.owner = true;
                case 'D' -> opts.devices = true;
                case 'v' -> opts.verbose++;
                case 'q' -> opts.verbose--;
                case 'n' -> opts.dryRun = true;
                case 'c' -> opts.checksum = true;
                case 'W' -> opts.wholeFile = true;
                case 'u' -> opts.update = true;
                case 'B' -> {
                    String rest = a.substring(j + 1);
                    if (rest.isEmpty()) {
                        rest = requireValue(args, i + 1, "-B");
                        opts.blockSize = parseSize(rest);
                        return i + 1;
                    }
                    opts.blockSize = parseSize(rest);
                    return i;
                }
                default -> throw new UsageException(
                        "unknown option: -" + a.charAt(j));
            }
        }
        return i;
    }

    private static String requireValue(String[] args, int i, String opt) {
        if (i >= args.length) {
            throw new UsageException(opt + " requires a value");
        }
        return args[i];
    }

    /** Sizes accept K/M/G suffixes (compat_humanize_number in reverse). */
    static long parseSize(String s) {
        long mult = 1;
        String t = s.trim();
        if (t.isEmpty()) {
            throw new UsageException("empty size");
        }
        char last = Character.toLowerCase(t.charAt(t.length() - 1));
        if (last == 'k' || last == 'm' || last == 'g') {
            mult = switch (last) {
                case 'k' -> 1L << 10;
                case 'm' -> 1L << 20;
                default -> 1L << 30;
            };
            t = t.substring(0, t.length() - 1);
        }
        return (long) (Double.parseDouble(t) * mult);
    }

    static boolean isRemote(String arg) {
        int colon = arg.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        // A Windows drive letter ("C:\...") is not a remote spec.
        return !(colon == 1 && Character.isLetter(arg.charAt(0)));
    }

    static String[] splitHost(String arg) {
        int colon = arg.indexOf(':');
        String host = arg.substring(0, colon);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1); // user is accepted and ignored
        }
        return new String[]{host, arg.substring(colon + 1)};
    }

    private static void usage() {
        System.err.println("""
                usage: rsyncz [options] SRC... DEST
                       rsyncz [options] SRC... HOST:DEST
                       rsyncz [options] HOST:SRC... DEST
                       rsyncz --daemon [--port N]
                  -a  archive (-rlptgoD)   -r  recursive
                  -l  preserve symlinks    -p  preserve permissions
                  -t  preserve times       -g  preserve group
                  -o  preserve owner       -D  preserve devices
                  -v  verbose (repeatable) -q  quiet
                  -n  dry run              -c  checksum quick check
                  -W  whole file (no delta) -u update only
                  -B  block size           --delete  delete extraneous files
                  --exclude PAT  --include PAT
                  --max-size N   --min-size N
                  --ignore-times --size-only
                  --port N (default 873)""");
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String msg) {
            super(msg);
        }
    }
}
