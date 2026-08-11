# Compukter Kraft — Architecture

> Architecture direction: [ADR 0001](architecture-decisions/0001-retire-k16-adopt-rv32.md)
> retires K16 as the product ISA and selects `RV32IMA_Zicsr_Zifencei` with the
> standard ILP32/ELF32 toolchain. Issue #490 physically disconnected the K16
> implementation from the loadable Minecraft product. There is intentionally
> no VM-backed computer product until the RV32 integration replaces it.
>
> Note: as of issue #26 the legacy CKL language / CKIM bytecode VM /
> in-game Workbench IDE have been removed. As of issue #44 the legacy
> Image-VM (host-call opcode, multi-process device daemon, runtime
> kernel, host-imported filesystem) has also been retired. Older revisions in
> git history describe those previous architectures.

## Current Implementation Overview

The loadable NeoForge mod is currently a platform shell: it initializes the
remaining generic networking surface but registers no computer, notebook,
terminal, SDK item, computer menu, block entity, or K16 runtime. Development
runs and production jars do not build, load, or package a K16 JNI library or
KraftOS firmware. This empty product interval is deliberate: K16 is not a
fallback while the selected RV32 platform is integrated.

### RV32 migration status

Issue [#486](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/486)
established the first isolated production-shaped RV32 host machine. Issue
[#489](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/489)
transfers ownership of that machine, its execution core, and its decoder
benchmarks to the neutral `host/compukter-vm` crate.
It accepts a stock-Clang/LLD little-endian RV32IM/ILP32 `ET_EXEC` ELF32,
validates and loads page-separated `PT_LOAD` segments, enforces 4 KiB R/W/X
permissions at runtime, starts at the ELF entry point, and exposes only bounded
debug-serial plus control MMIO.

Machine construction explicitly selects one of two bounded execution backends:

- a fixed-capacity two-way decoded-instruction cache that decodes only reached
  executable PCs and uses deterministic replacement; or
- eager predecode over exact executable ranges without allocating entries for
  RAM holes or non-executable segments.

Both backends resolve instructions into the same fixed `Rv32MachineHart` and
therefore share architectural execution, trap entry, CSR state, and retirement
semantics. The hart implements RV32IM plus all six Zicsr forms, `MRET`, and an
M-only bank containing `mstatus`, `misa`, `mtvec`, `mscratch`, `mepc`,
`mcause`, `mtval`, and `mhartid`. `mtvec` is Direct-only and WARL. Synchronous
causes 0, 1, 2, 3, 4, 5, 6, 7, and 11 are precise: the faulting instruction
does not retire, `mepc` records its PC, and `mtval` records the cause-specific
address, word, breakpoint PC, or zero.

The run budget charges every instruction attempt, including a trap that
retires nothing. Retired totals remain a separate diagnostic measure, so an
inaccessible trap vector cannot escape or extend a finite server budget. Both
Cached and Predecoded modes pass the same stock-Clang/LLD trap firmware: it
installs `mtvec`, swaps to a trap stack through `mscratch`, handles an M-mode
`ECALL`, advances `mepc`, returns through `MRET`, and halts only after the
resumed C program prints its marker. The earlier stock-toolchain boot fixture
also remains valid. Cache storage, predecode storage, RAM, and debug output are
bounded before execution; successful steady-state execution performs no heap
growth.

This host slice is not wired to JNI or Minecraft and does not boot firmware or
KraftOS. The selected `A` and `Zifencei` extensions remain pending. `Zicntr`,
timer delivery, asynchronous interrupts, U/S privilege modes, Sv32 or another
protection model, persistent firmware flash, remaining devices, KraftOS, and
the product runtime integration are also absent.

## Historical K16 BIOS Flash Workflow

The remainder of this section documents removed behavior for archaeological
reference only. None of these Kotlin/JNI/product paths exists or executes after
issue #490; standalone K16 host, guest, and toolchain sources remain only until
issue #491 removes them.

Each K16 computer workspace stores its firmware in `bios.kflash`. On first
boot, `K16BiosFlashWorkspace.prepareBiosFlash` seeds that file from the bundled
`firmware/k16-bios.kflash` resource. On later boots, the existing file is used
as the flashed BIOS image after basic raw K16 validation.

The developer-facing flash operation is
`K16BiosFlashWorkspace.flashBiosFlash(workspace, source)`: it reads an explicit
`.kflash` source path, validates that the image is present, non-empty, and made
of whole 16-bit instruction bytes, then replaces the workspace `bios.kflash`.
Invalid or missing source images fail before the current flashed BIOS is
overwritten.

Recovery is explicit through `K16BiosFlashWorkspace.restoreBundledBiosFlash`.
It overwrites the per-computer flash with the bundled BIOS resource, giving the
development path a non-bricking re-flash story even when a custom BIOS image is
bad.

## Modules (Gradle)

| Module                  | Purpose                                                                          |
|-------------------------|----------------------------------------------------------------------------------|
| `native-runtime`        | Neutral device/VM data models; no JNI binding or concrete K16 runtime             |
| `core`                  | Shared platform-neutral logic and future device/runtime contracts; no Minecraft deps |
| `v1_21_1-common`        | Minecraft 1.21.1 common support; currently retains only generic non-computer surfaces |
| `v1_21_1-neoforge`      | Minimal NeoForge bootstrap and generic network handler; no computer registration or native packaging |
| `v1_21_1-create-neoforge` | Optional Create mod compatibility (NeoForge-only)                              |

## Native crates

| Crate                | Purpose                                                                  |
|----------------------|--------------------------------------------------------------------------|
| `host/compukter-vm`  | Neutral RV32 execution core, permissioned and budgeted ELF32 machine, and RV32 decoder benchmarks; no JNI or Minecraft ownership |
| `host/k16-vm`        | Disconnected historical Kraft16 host/JNI sources pending deletion in issue #491 |
| `host/k16-tools`     | Legacy Rux source checks plus K16 artifact tooling via `k16` for disassembly, volume, and filesystem commands |

For a code-level map of the removed K16 VM path, see the historical
[`k16-vm-code-flow.md`](k16-vm-code-flow.md).

## Module ownership rules

- `core` must not import `net.minecraft.*`.
- Loader leaf modules (`v1_21_1-neoforge`) stay limited to bootstrap, registry,
  network handler, hooks, and small unavoidable platform shims.
- Boundary rules are enforced by `ArchitectureBoundaryTest` in `modules/core`.

## Historical K16 device lifecycle

```
NotebookItem.use()
  └─ ServerContext.deviceRegistry[deviceId]
        └─ RuntimeDevice (native-backed)

RuntimeDevice.boot()
  └─ NativeVmBindings.createK16ComputerFromBiosFlash(biosFlashPath, storage0Path, ...)
        └─ native K16 computer handle
              ├─ Kraft16 CPU fetching instructions from mapped BIOS flash
              ├─ storage0 boot media
              ├─ flat RAM + MMIO bus (control, debug-serial, serial-input,
              │   gpu0, storage0, keyboard0, timer0, bios-flash)
              └─ exposes control / debug bytes / retained gpu0 payloads over JNI

RuntimeDevice.serverTick(gameTime)
  ├─ advance native VM until pause / halt
  ├─ drain retained gpu0 publications for authorized viewers
  └─ cache debug serial bytes for diagnostics

RuntimeDevice.close()
  └─ NativeVmBindings.freeK16Computer(handle)
```

## Historical K16 data flow

```
┌─────────────────────────────────────────────────────────────────────┐
│  Native K16 computer (driven on demand by JNI calls)                │
│                                                                     │
│  Kraft16 BIOS executing from mapped bios.kflash                     │
│    ├─ MMIO control device  ──►  status / exit / panic registers      │
│    ├─ MMIO debug serial    ──►  K16ComputerHandle.debug_output       │
│    ├─ MMIO serial input    ◄──  player keyboard events               │
│    ├─ MMIO storage0        ◄──► boot media                           │
│    ├─ MMIO bios flash      ──►  read-only firmware mapping           │
│    └─ MMIO gpu0            ──►  retained resources + draw list       │
└──────────────────────────────────────────┬──────────────────────────┘
                                           │ JNI run-until-signal
                                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Server tick thread (main thread)                                   │
│                                                                     │
│  RuntimeDevice.serverTick()                                          │
│    ├─ NativeVmBindings advances the VM until pause / halt            │
│    ├─ drain retained payloads / cache debug output                    │
│    ├─ forward opaque snapshot/delta bytes to authorized observers    │
│    └─ react to control register (halt / crash / reboot)              │
└──────────────────────────────────────────┬──────────────────────────┘
                                           │ network
                                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Client (render thread)                                             │
│                                                                     │
│  ClientRetainedDisplays installs snapshot/delta into one replica     │
│    └─ sends ACK or an explicit resync request                        │
│  Menu/block observers compile damage and render through GuiGraphics │
└─────────────────────────────────────────────────────────────────────┘
```

The gpu0 device validates atomic guest transactions and owns canonical retained
resources, not a framebuffer. Late observers receive a full snapshot; ACKs
gate coalesced deltas independently per viewer. Debug serial output remains a
byte stream for diagnostics and is not interpreted as display content by the
host. The client sends discrete input events into guest input devices. There is
no multi-process scheduling and no host-call opcode; host-side interaction
happens purely through memory-mapped registers.
