# Compukters

![Mod logo (AI generated)](logo_1.png)

**Programmable computers for Minecraft with deterministic, resource-bounded Kotlin execution.**

Compukters is an in-game programming platform built around Kotlin `.kts`
scripts, a pinned Kotlin K2/IR compiler pipeline, versioned Compukter bytecode,
and a managed Rust VM. The intended product loop runs from an in-game IDE to a
shell and programs executing on a computer inside Minecraft.

## Status

The project is in a clean-break managed-runtime bootstrap. The retired custom
ISA, RISC-V machine, ELF/KraftOS runtime, and standalone Playground are not
fallback execution paths. The loadable NeoForge mod is temporarily a platform
shell while the verified artifact loader and Tier 0 runtime are built.

Currently targets **NeoForge 1.21.1**.

## Runtime boundary

`host/compukter-vm` is the pinned
[Compukter VM](https://github.com/CertifiedBadIdeas/Compukter-VM) submodule.
Kotlin compiler internals remain on the trusted JVM side; immutable verified
Compukter artifacts cross into the Rust runtime, which owns execution, quotas,
managed memory, scheduling, snapshots, and future optimization tiers.

See [the current architecture](docs/ARCHITECTURE.md).

## Links and credits

- Devlog (in Russian): https://t.me/lazyhatdev
- Source: https://github.com/CertifiedBadIdeas/Compukters
- Texture tools: [Piskel](https://www.piskelapp.com) and [LibreSprite](https://libresprite.github.io/)
- License: GPL-3.0
