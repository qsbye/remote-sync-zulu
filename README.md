# RemoteSyncZulu

**[English](#english) · [中文](#中文)**

## English

RemoteSyncZulu is a continuous file synchronization program optimized for NAS. It synchronizes files between two or more computers in real time, safely protected from prying eyes.

`rsyncz` is a Java 25 + Gradle translation of [openrsync](https://github.com/kristapsdz/openrsync) (BSD's ISC-licensed rsync implementation, protocol 27). It mirrors the original's roles, algorithms, and on-disk semantics — sender/receiver, rolling-checksum delta transfer, MD4 strong checksums, wildcard filter rules — as a single self-contained JVM program with no third-party runtime dependencies.

### Features

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

### Requirements

- JDK 25 (developed and verified against Azul Zulu 25 — the project's namesake)
- Gradle 8.10+ to build; no other dependencies

### Build

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
gradle build        # compile + run the test suite (34 tests)
gradle installDist  # launcher in build/install/remote-sync-zulu/bin/rsyncz
```

### Usage

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

### Wire protocol

The exchange mirrors openrsync protocol 27 (all integers little-endian):

1. **Handshake** — client sends `i32` version (27); server answers `i32` version + `i32` random seed, then reads mode (pull/push), the argument vector, and the client's option set (flag bits, size limits, block size, filter rules) which the server adopts for the session — the role `fargs.c`'s option string plays in the C original.
2. **File list** — sender emits per entry: flag byte, name (`i32` length + UTF-8), `i64` size, `u32` mtime, `u32` mode, optional `u32` uid/gid (-o/-g), optional `i32` rdev (-D), optional symlink target, and with `-c` a 16-byte seedless MD4; a zero flag terminates, followed by an `i32` I/O-error count.
3. **Per file** — receiver sends `i32` index; sender reads the block set (`i32` count, `i32` block length, `i32` checksum bytes, `i32` remainder, then per block `i32` fast checksum + 2 MD4 bytes) and streams tokens: `i32 > 0` literal run, `i32 < 0` copy of block `-(n+1)`, `i32 0` terminator, then the 16-byte seeded whole-file MD4.
4. **Goodbye** — receiver sends `i32 -1`; sender mirrors a final `i32 -1` and closes.

#### Documented differences from the C original

These keep the translation maintainable without changing observable sync results:

- The C sender/receiver are event-driven (poll + iobuf); this translation uses blocking I/O with the same byte exchange, one thread per role.
- File-list name compression (shared-prefix flag bits) is not used; names are sent in full.
- Merge-file rules (`.rsync-filter`), perishable rules, and send/receive rule modifiers are not supported.
- Files are matched from memory rather than `mmap`; files larger than the available heap need `-W` (whole-file) mode.
- The daemon serves any path the client names — run it only on trusted networks, exactly like `rsync --daemon` without modules/auth, and front it with SSH or a VPN when it leaves the LAN ("protected from prying eyes" refers to the transfer being checkable and self-hosted; add a tunnel for confidentiality).
- Java↔C wire interoperability with openrsync itself is **not** guaranteed; the format mirrors the original but is versioned independently.

### Tests

```sh
gradle test
```

Covers RFC 1320 MD4 vectors, rolling-checksum roll/recompute equivalence, the wildcard matcher (`*`, `**`, classes, escapes), filter-rule semantics, block geometry and delta reconstruction of mutated files, end-to-end local sync (nested trees, symlinks, `--delete`, exclusions, `-t`, `-n`, `-W`, `-c`) and a loopback TCP push+pull round trip.

### License

Dual-licensed:

- New code in this repository (the Java implementation) is licensed under the MIT license — see [LICENSE](LICENSE).
- RemoteSyncZulu is a faithful translation of [openrsync](https://github.com/kristapsdz/openrsync), which is Copyright (c) 2019 Kristaps Dzonsons and licensed under the ISC license. The original ISC license text is preserved in [LICENSE.openrsync](LICENSE.openrsync) and applies to the parts derived from the openrsync sources.

---

## 中文

RemoteSyncZulu 是一个为 NAS 优化的持续文件同步程序。它在两台或多台计算机之间实时同步文件，安全可靠，远离窥探。

`rsyncz` 是 [openrsync](https://github.com/kristapsdz/openrsync)（BSD 的 ISC 许可 rsync 实现，协议 27）的 Java 25 + Gradle 翻译版。它以单一自包含 JVM 程序的形式复刻了原版的角色、算法与磁盘语义——sender/receiver、滚动校验和增量传输、MD4 强校验、通配符过滤规则——无任何第三方运行时依赖。

### 功能特性

翻译自 C 原版（括号内为对应源文件）：

- **增量传输** —— Tridgell 的滚动 32 位快速校验和 + 带种子 MD4 强校验，65536 桶块哈希表与命中提示（`hash.c`、`blocks.c`）
- **块几何** —— 最小 700 字节，`sqrt(size)` 向上取整为 8 的倍数的块尺寸，可用 `-B` 覆盖（`uploader.c: init_blkset`）
- **文件列表** —— 递归生成，支持尾斜杠语义，排序后线上传输（`flist.c`）
- **过滤规则** —— include/exclude 首中即胜，锚定（`/pat`）、仅目录（`pat/`）、前导目录（`pat/***`）、基名规则、`!` 取反（`rules.c`）
- **通配符** —— `*`、`**`（可跨 `/`）、`?`、`[a-z]`、`[!..]`、POSIX 字符类 `[[:alpha:]]`、反斜杠转义（`rmatch.c`）
- **文件属性** —— 权限、mtime、属主/属组（尽力数值 `chown`）、符号链接、`-D` 列出设备节点（`receiver.c`、`misc.c`）
- **原子更新** —— 先接收到 `.rsyncz*` 临时文件，带种子整文件 MD4 校验（失败时整文件重传一次），原子重命名（`receiver.c`、`mktemp.c`）
- **`--delete`** —— 清理目标端多余文件，被排除项受保护（`receiver.c`）
- **网络模式** —— TCP 守护进程（默认端口 873），支持 push 与 pull，协议 27 风格握手并交换随机传输种子（`socket.c`、`client.c`、`server.c`）
- **本地模式** —— sender 与 receiver 线程由全双工管道连接，与 C 版本地模式的 fork+socketpair 架构完全一致（`main.c`）
- **多路复用帧** —— `((tag+7)<<24)|len` 帧编解码，可用于带标签的日志/错误通道（`io.c`）
- **选项** —— `-a -r -l -p -t -g -o -D -v -q -n -c -W -u -B`、`--delete`、`--exclude/--include`、`--max-size/--min-size`、`--ignore-times`、`--size-only`、`--whole-file`、`--port`（`main.c`、`fargs.c`）

### 环境要求

- JDK 25（基于 Azul Zulu 25 开发与验证——项目名即由此而来）
- 构建需 Gradle 8.10+；无其他依赖

### 构建

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
gradle build        # 编译并运行测试套件（34 个测试）
gradle installDist  # 启动器位于 build/install/remote-sync-zulu/bin/rsyncz
```

### 使用方法

```sh
# 本地同步（增量传输，归档模式）
rsyncz -av /volume1/photos/ /backup/photos/

# 预览将要发生的变更
rsyncz -avn /src/ /dst/

# 在 NAS 上启动守护进程
rsyncz --daemon --port 8873

# 推送到远程守护进程
rsyncz -av /src/ nas.example.com:/volume1/backup/

# 从远程守护进程拉取
rsyncz -av nas.example.com:/volume1/photos/ /local/copy/

# 过滤传输内容
rsyncz -av --exclude='*.tmp' --exclude='cache/' --include='important.tmp' /src/ /dst/
```

远程参数形如 `host:path`（`user@` 前缀可接受但会被忽略）。不允许远程源与远程目标混用，也不允许两个源位于不同主机——与 C 客户端行为一致。

### 线路协议

交换流程复刻 openrsync 协议 27（所有整数均为小端序）：

1. **握手** —— 客户端发送 `i32` 版本号（27）；服务端回复 `i32` 版本号 + `i32` 随机种子，随后读取模式（pull/push）、参数向量以及客户端的选项集（标志位、大小限制、块大小、过滤规则），服务端在本会话中采用这些选项——对应 C 原版中 `fargs.c` 选项字符串的角色。
2. **文件列表** —— sender 逐条输出：标志字节、名称（`i32` 长度 + UTF-8）、`i64` 大小、`u32` mtime、`u32` mode、可选 `u32` uid/gid（-o/-g）、可选 `i32` rdev（-D）、可选符号链接目标；带 `-c` 时附加 16 字节无种子 MD4；以零标志字节结束，后跟 `i32` I/O 错误计数。
3. **逐文件** —— receiver 发送 `i32` 索引；sender 读取块集合（`i32` 块数、`i32` 块长、`i32` 校验字节数、`i32` 余数，随后每块 `i32` 快速校验和 + 2 字节 MD4），并流出 token：`i32 > 0` 为字面数据，`i32 < 0` 为复制第 `-(n+1)` 块，`i32 0` 为终止符，随后是 16 字节带种子整文件 MD4。
4. **告别** —— receiver 发送 `i32 -1`；sender 回送最终的 `i32 -1` 并关闭连接。

#### 与 C 原版的文档化差异

以下差异不改变可观察的同步结果，仅为保持翻译可维护：

- C 版 sender/receiver 为事件驱动（poll + iobuf）；本翻译使用阻塞 I/O 与相同的字节交换流程，每个角色一个线程。
- 不使用文件列表名称压缩（共享前缀标志位）；名称完整发送。
- 不支持 merge-file 规则（`.rsync-filter`）、perishable 规则及 send/receive 规则修饰符。
- 文件在内存中匹配而非 `mmap`；大于可用堆内存的文件需使用 `-W`（整文件）模式。
- 守护进程会服务客户端指定的任意路径——请仅在可信网络中运行，与不带 modules/auth 的 `rsync --daemon` 完全相同；离开局域网时请加一层 SSH 或 VPN（"远离窥探" 指传输可校验、可自托管；机密性请通过隧道保障）。
- **不保证**与 openrsync 本身的 Java↔C 线路互操作；格式复刻原版，但独立演进版本。

### 测试

```sh
gradle test
```

覆盖：RFC 1320 MD4 测试向量、滚动校验和的滚动/重算等价性、通配符匹配器（`*`、`**`、字符类、转义）、过滤规则语义、块几何与变异文件增量重构、本地端到端同步（嵌套目录树、符号链接、`--delete`、排除规则、`-t`、`-n`、`-W`、`-c`），以及回环 TCP push+pull 往返。

### 许可证

双许可：

- 本仓库中的新代码（Java 实现）采用 MIT 许可证 —— 见 [LICENSE](LICENSE)。
- RemoteSyncZulu 是 [openrsync](https://github.com/kristapsdz/openrsync) 的忠实翻译，原作版权所有 (c) 2019 Kristaps Dzonsons，采用 ISC 许可证。ISC 许可原文保存在 [LICENSE.openrsync](LICENSE.openrsync) 中，适用于派生自 openrsync 源码的部分。
