# Compukters

![Mod logo (AI generated)](logo_1.png)

**Programmable computers for Minecraft with deterministic, resource-bounded Kotlin execution.**

Compukters is an in-game programming platform built around Kotlin `.kt`
projects, a pinned Kotlin K2/IR compiler pipeline, versioned Compukter bytecode,
and a managed Rust VM. The intended product loop runs from an in-game IDE to a
shell and programs executing on a computer inside Minecraft.

## Status

The compiler, canonical artifact, JDK 25 FFM session, managed Rust VM, standalone
playground, loader-independent `ProgramRuntimeHost`, and server computer block
form an executable vertical slice. A computer starts the source-visible no-std
`system/programs/boot.kt` from `/rom/boot`; boot launches `/rom/shell`, and the
shell can run verified extensionless programs in the foreground through
`Process.run`. The ordinary `/rom/kotlinc` program compiles one `.kt` source
from `/home` into an extensionless executable, using a server-global persistent
cache and an isolated K2 child process without blocking the server tick.
NeoForge GameTests cover registration, automatic boot, two computers compiling
and executing the same source, nested program execution, reboot, ticking,
removal, VM shutdown, and recovery of a tombstoned persistent filesystem.

The active game baseline is **Minecraft 26.1.2**, **NeoForge 26.1.2.97**, and
**JDK 25**. The production archive uses Minecraft's official names directly;
there is no remap stage or Architectury runtime dependency.

## Minecraft development

Run Gradle with JDK 25 selected through `JAVA_HOME` or a Gradle-discoverable
installation:

```bash
./gradlew-sandbox-dev-parallel :v26_1-neoforge:runClient
./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer
./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar
```

## Runtime boundary

`host/compukter-vm` is the pinned
[Compukter VM](https://github.com/CertifiedBadIdeas/Compukter-VM) submodule.
Kotlin compiler internals remain on the trusted JVM side; immutable verified
Compukter artifacts cross into the Rust runtime, which owns execution, quotas,
managed memory, scheduling, snapshots, and future optimization tiers.

The Rust runtime also owns each computer's filesystem. Minecraft persists only
a stable `ComputerId`; the immutable packaged `/rom` and isolated persistent
`/home` are mounted inside Rust. World data is rooted at
`<world>/compukters/filesystems`, flushed on saves and shutdown, and moved to a
recoverable tombstone when a player destroys the corresponding computer. Guest
code currently has bounded read-only `FileSystem.stat` and `FileSystem.list`
operations. Compilation is requested by the Rust machine from an immutable
source snapshot. The JVM service compiles or retrieves a verified artifact from
`<world>/compukters/compiler-cache`, then Rust re-verifies and atomically
installs it into the requesting computer's `/home`.

Inside a computer, compile and run a program with:

```text
kotlinc hello.kt
hello
```

Without `-o`, the output name is the source filename without `.kt`. An explicit
extensionless output is also supported:

```text
kotlinc hello.kt -o app
app
```

The current in-game compiler accepts exactly one source file per invocation.
Compiler failures leave an existing output untouched and are reported back in
the shell.

See [the current architecture](docs/ARCHITECTURE.md).

## Standalone playground

The playground exercises the same isolated compiler, artifact verifier, FFM
adapter, Rust VM, and terminal capability that the mod will use. Run the
included multi-file example from the repository root:

```bash
./gradlew :playground:run --args examples/hello
```

It prompts on stdout, reads one UTF-8 line from stdin, and executes the emitted
Compukter bytecode. To retain the verified compiler output for inspection:

```bash
./gradlew :playground:run --args="examples/hello --emit build/hello.cpkt"
```

Compilation diagnostics and runtime failures go to stderr. Add `--debug` to
show launcher stack traces. The process uses stable exit categories: `2` usage,
`3` project input, `4` compilation, `5` compiler platform, `6` artifact
verification, `7` VM admission/start, `8` guest trap, `9` VM fault, `10` host
failure or EOF, `11` quota, `12` allocation resource failure, and `13` launcher
or native platform failure.

## Links and credits

- Devlog (in Russian): https://t.me/lazyhatdev
- Source: https://github.com/CertifiedBadIdeas/Compukters
- License: GPL-3.0
