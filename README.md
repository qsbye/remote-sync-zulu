# RemoteSyncZulu

RemoteSyncZulu is a continuous file synchronization program optimized for NAS. It synchronizes files between two or more computers in real time, safely protected from prying eyes.

`rsyncz` is a Java 25 + Gradle translation of [openrsync](https://github.com/kristapsdz/openrsync) (BSD's ISC-licensed rsync implementation, protocol 27). It mirrors the original's roles, algorithms, and on-disk semantics — sender/receiver, rolling-checksum delta transfer, MD4 strong checksums, wildcard filter rules — as a single self-contained JVM program with no third-party runtime dependencies.

## Features

Translated from the C original (source file in parentheses):

- **Delta transfer** — Tridgell's rolling 32-bit fast checksum plus seeded MD4 strong checksum, 65536-bucket block hashtable, match hint (`hash.c`, `blocks.c`)
- **Block geometry** — 700-byte minimum, rounded `sqrt(size)` multiple-of-8 block sizing, `-B` override (`uploader.c: init_blkset`)
- **File lists** — recursive generation with trailing-slash semantics, sorted wire transfer (`flist.c`)
- **Filter rules** — include/exclude with first-match-wins, anchors (`/pat`), directory-only (`pat/`), leading-dir (`pat/***`), basename rules, `!` negation (`rules.c`)
- **Wildcards** — `*`, `**` (crosses `/`), `?`, `[a-z]`, `[!..]`, POSIX classes `[[:alpha:]]`, backslash escapes (`rmatch.c`)
- **Attributes** — permissions, mtimes, owner/group (best-effort numeric `chown`), symlinks, device nodes listed with `-D` (`receiver.c`, `misc.c`)
- **Atomic updates** — receive into `.rsyncz*` temp file, seeded whole-file MD4 verification with one whole-file retry, atomic rename (`receiver.c`, `mktemp.c`)
- **`--delete`** — prune extraneous destination files, exclusions protected (`receiver.c`)
- **Network mode** — TCP daemon (default port 873), push and pull, protocol-27 style handshake with random transfer seed (`socket.c`, `client.c`, `server.c`)
- **Local mode** — sender and receiver threads joined by a full-duplex pipe, exactly the fork+socketpair architecture of the C local mode (`main.c`)
- **Multiplex framing** — `((tag+7)<<24)|len` frame codec available for tagged log/error channels (`io.c`)
- **Options** — `-a -r -l -p -t -g -o -D -v -q -n -c -W -u -B`, `--delete`, `--exclude/--include`, `--max-size/--min-size`, `--ignore-times`, `--size-only`, `--whole-file`, `--port` (`main.c`, `fargs.c`)

## Requirements

- JDK 25 (developed and verified against Azul Zulu 25 — the project's namesake)
- Gradle 8.10+ to build; no other dependencies

## Build

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
gradle build        # compile + run the test suite (34 tests)
gradle installDist  # launcher in build/install/remote-sync-zulu/bin/rsyncz
```

## Usage

```sh
# Local sync (delta transfer, archive mode)
rsyncz -av /volume1/photos/ /backup/photos/

# See what would change
rsyncz -avn /src/ /dst/

# Start the daemon on the NAS
rsyncz --daemon --port 8873

# Push to a remote daemon
rsyncz -av /src/ nas.example.com:/volume1/backup/

# Pull from a remote daemon
rsyncz -av nas.example.com:/volume1/photos/ /local/copy/

# Filter what goes over
rsyncz -av --exclude='*.tmp' --exclude='cache/' --include='important.tmp' /src/ /dst/
```

A remote argument has the form `host:path` (`user@` is accepted and ignored). Mixing remote sources with a remote destination, or sources on two different hosts, is rejected — same as the C client.

## Wire protocol

The exchange mirrors openrsync protocol 27 (all integers little-endian):

1. **Handshake** — client sends `i32` version (27); server answers `i32` version + `i32` random seed, then reads mode (pull/push), the argument vector, and the client's option set (flag bits, size limits, block size, filter rules) which the server adopts for the session — the role `fargs.c`'s option string plays in the C original.
2. **File list** — sender emits per entry: flag byte, name (`i32` length + UTF-8), `i64` size, `u32` mtime, `u32` mode, optional `u32` uid/gid (-o/-g), optional `i32` rdev (-D), optional symlink target, and with `-c` a 16-byte seedless MD4; a zero flag terminates, followed by an `i32` I/O-error count.
3. **Per file** — receiver sends `i32` index; sender reads the block set (`i32` count, `i32` block length, `i32` checksum bytes, `i32` remainder, then per block `i32` fast checksum + 2 MD4 bytes) and streams tokens: `i32 > 0` literal run, `i32 < 0` copy of block `-(n+1)`, `i32 0` terminator, then the 16-byte seeded whole-file MD4.
4. **Goodbye** — receiver sends `i32 -1`; sender mirrors a final `i32 -1` and closes.

### Documented differences from the C original

These keep the translation maintainable without changing observable sync results:

- The C sender/receiver are event-driven (poll + iobuf); this translation uses blocking I/O with the same byte exchange, one thread per role.
- File-list name compression (shared-prefix flag bits) is not used; names are sent in full.
- Merge-file rules (`.rsync-filter`), perishable rules, and send/receive rule modifiers are not supported.
- Files are matched from memory rather than `mmap`; files larger than the available heap need `-W` (whole-file) mode.
- The daemon serves any path the client names — run it only on trusted networks, exactly like `rsync --daemon` without modules/auth, and front it with SSH or a VPN when it leaves the LAN ("protected from prying eyes" refers to the transfer being checkable and self-hosted; add a tunnel for confidentiality).
- Java↔C wire interoperability with openrsync itself is **not** guaranteed; the format mirrors the original but is versioned independently.

## Tests

```sh
gradle test
```

Covers RFC 1320 MD4 vectors, rolling-checksum roll/recompute equivalence, the wildcard matcher (`*`, `**`, classes, escapes), filter-rule semantics, block geometry and delta reconstruction of mutated files, end-to-end local sync (nested trees, symlinks, `--delete`, exclusions, `-t`, `-n`, `-W`, `-c`) and a loopback TCP push+pull round trip.

## License

ISC, matching the openrsync original.
