# Compukters

![Mod logo (AI generated)](logo_1.png)

**Programmable computers for Minecraft with deterministic, resource-bounded Kotlin execution.**

Compukters is an in-game programming platform built around Kotlin `.kt`
projects, a pinned Kotlin K2/IR compiler pipeline, versioned Compukter bytecode,
and a managed Rust VM. The intended product loop runs from an in-game IDE to a
shell and programs executing on a computer inside Minecraft.

## Status

The compiler, canonical artifact, JNI session, managed Rust VM, standalone
playground, and loader-independent `ProgramRuntimeHost` form an executable
vertical slice. The loadable NeoForge mod is temporarily a minimal bootstrap
while the server computer carrier and Minecraft integration are added.

Currently targets **NeoForge 1.21.1**.

## Runtime boundary

`host/compukter-vm` is the pinned
[Compukter VM](https://github.com/CertifiedBadIdeas/Compukter-VM) submodule.
Kotlin compiler internals remain on the trusted JVM side; immutable verified
Compukter artifacts cross into the Rust runtime, which owns execution, quotas,
managed memory, scheduling, snapshots, and future optimization tiers.

See [the current architecture](docs/ARCHITECTURE.md).

## Standalone playground

The playground exercises the same isolated compiler, artifact verifier, JNI
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
