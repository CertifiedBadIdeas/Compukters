# Compukter Kraft — Architecture

> Architecture direction: [ADR 0001](architecture-decisions/0001-adopt-rv32.md)
> selects `RV32IMA_Zicsr_Zifencei`, little-endian ELF32, and ILP32.

## Current Product Boundary

The loadable NeoForge mod is temporarily a platform shell. It registers no
computer, notebook, terminal, SDK item, computer menu, or VM-backed block
entity. This empty interval is deliberate: the retired architecture is not a
fallback while the RV32 platform is integrated.

`host/compukter-vm` is the sole active VM crate. It is not yet connected to JNI
or Minecraft and does not yet boot BIOS or KraftOS.

## RV32 Host Machine

The host accepts stock-Clang/LLD little-endian RV32IMA/ILP32 `ET_EXEC` ELF32.
It validates page-separated `PT_LOAD` segments, enforces 4 KiB R/W/X
permissions, begins execution at the ELF entry, and exposes only bounded debug
serial and control MMIO.

Machine construction selects one of two bounded backends:

- Cached uses a fixed-capacity two-way decoded-instruction cache and decodes
  only reached executable PCs with deterministic replacement.
- Predecoded eagerly decodes exact executable ranges without allocating
  entries for RAM holes or non-executable segments.

Both backends execute through the same `Rv32MachineHart`. The hart implements
RV32IMA, all six Zicsr forms, `MRET`, `FENCE`, and `FENCE.I`; `misa` reports the
implemented `IMA` base extensions. Precise synchronous traps preserve the
faulting PC and cause-specific `mtval`. Every instruction attempt consumes
budget even when it traps without retiring, while retired instruction totals
remain a separate diagnostic counter.

RV32A atomics are naturally aligned, 32-bit, and RAM-only. Each hart owns one
exact-word LR reservation; overlapping successful local writes, every SC
attempt, trap entry, and reset clear it as required. AMOs cross one indivisible
read-modify-write memory boundary. Atomic MMIO is rejected before device side
effects, and page permissions are checked before RAM mutation. The sequential
interpreter already orders memory more strongly than `aq`/`rl` require, while
immutable W^X executable pages keep `FENCE` and `FENCE.I` legal retiring no-ops
until an explicit executable-image replacement API exists.

Cache storage, predecode storage, RAM, and debug output are bounded before
execution. Successful steady-state execution performs no heap growth.

## Pending RV32 Platform Work

The platform still needs `Zicntr`, timer delivery, asynchronous interrupts, a
final protection model, persistent firmware flash, storage, display, input,
networking, snapshots, KraftOS, JNI, and Minecraft integration.

KraftOS remains the guest operating-system direction. Its product contracts
survive the ISA replacement: guest-owned firmware and software, deterministic
tick-relative execution, explicit CPU and I/O quotas, isolation, W^X memory,
portable programs, devices, storage, networking, shell, and an in-game
programming loop. Its next implementation is RV32-native and introduces no
compatibility layer for the retired architecture.

## Module Ownership

| Module | Purpose |
|---|---|
| `host/compukter-vm` | RV32 execution, permissioned ELF32 machine, and decoder benchmarks |
| `native-runtime` | Architecture-neutral device and VM data models |
| `core` | Shared platform-neutral logic and future runtime contracts |
| `v1_21_1-common` | Minecraft 1.21.1 common support |
| `v1_21_1-neoforge` | NeoForge bootstrap and generic networking; no VM packaging yet |

Ownership rules:

- `core` must not import `net.minecraft.*`.
- Loader leaf modules remain limited to bootstrap, registry, networking,
  hooks, and small unavoidable platform shims.
- VM architecture and guest execution belong in Rust, not on JVM threads.
- Boundary rules are enforced by `ArchitectureBoundaryTest` in `modules/core`.
