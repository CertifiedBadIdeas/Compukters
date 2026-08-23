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
form an executable vertical slice. Computers temporarily boot the packaged
artifact compiled from the source-visible no-std `system/programs/shell.kt`;
`boot.kt` and generic `process.run` are the next lifecycle layer. A real
NeoForge GameTest covers registration, automatic boot, ticking, removal, and
VM shutdown.

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
