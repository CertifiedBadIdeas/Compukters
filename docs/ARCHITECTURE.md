# Compukter Kraft — Architecture

> Note: as of issue #26 the legacy CKL language / CKIM bytecode VM /
> in-game Workbench IDE have been removed. As of issue #44 the legacy
> Image-VM (host-call opcode, multi-process device daemon, runtime
> kernel, host-imported filesystem) has also been retired. The active
> computer boot path is now Rux16 guest execution from BIOS flash and
> storage-backed boot media. Older revisions in git history describe the
> previous architectures.

## Overview

Compukter Kraft is a Minecraft mod that adds programmable computers backed
by a Rust virtual machine (`native/rux-vm`). The mod ships a single
player-facing computer item — **Notebook** — that starts a native
`RuxComputer` from a per-computer `bios.flash` file. The BIOS executes on
the Rux16 guest CPU, can inspect storage0 boot media, and exposes devices
through memory-mapped peripherals.

## Modules (Gradle)

| Module                  | Purpose                                                                          |
|-------------------------|----------------------------------------------------------------------------------|
| `native-runtime`        | Kotlin types shared with the Rust VM: device profile, VM state/events, JNI bindings (`NativeVmBindings`), IDE stub types kept for residual UI surfaces |
| `core`                  | Shared mod logic: server context / device registry, runtime device interfaces, platform port interfaces, no Minecraft deps |
| `v1_21_1-common`        | Minecraft 1.21.1 common code: blocks, items, menus, networking, rendering        |
| `v1_21_1-neoforge`      | NeoForge bootstrap, registries, network handler, native library packaging         |
| `v1_21_1-create-neoforge` | Optional Create mod compatibility (NeoForge-only)                              |

## Native crates

| Crate            | Purpose                                                                  |
|------------------|--------------------------------------------------------------------------|
| `native/rux-vm`  | Rust virtual machine: Rux16 CPU, memory-mapped devices, `RuxComputer` handle, JNI exports |
| `native/rux-compiler` | Rux language frontend plus `rux compile`, `rux disasm`, `rux volume`, and filesystem-specific `rux fs` tooling for Rux16 artifacts |

## Module ownership rules

- `core` must not import `net.minecraft.*`.
- Loader leaf modules (`v1_21_1-neoforge`) stay limited to bootstrap, registry,
  network handler, hooks, and small unavoidable platform shims.
- Boundary rules are enforced by `ArchitectureBoundaryTest` in `modules/core`.

## Device lifecycle

```
NotebookItem.use()
  └─ ServerContext.deviceRegistry[deviceId]
        └─ RuntimeDevice (native-backed)

RuntimeDevice.boot()
  └─ NativeVmBindings.createRux16Computer(biosFlashPath, storage0Path, ...)
        └─ Rust RuxComputerHandle
              ├─ Rux16 CPU fetching instructions from mapped BIOS flash
              ├─ storage0 ruxvol boot media
              ├─ flat RAM + MMIO bus (control, debug-serial, serial-input,
              │   text-display, storage0, bios-flash)
              └─ exposes control / debug / display snapshot over JNI

RuntimeDevice.serverTick(gameTime)
  ├─ runRux16ComputerUntilSignal() — advance guest CPU until pause / halt
  ├─ ruxComputerDisplay0Snapshot() — pull text display state
  └─ drainRuxComputerDebugOutput() — drain debug serial bytes

RuntimeDevice.close()
  └─ NativeVmBindings.freeRuxComputer(handle)
```

## Data flow

```
┌─────────────────────────────────────────────────────────────────────┐
│  Rust Rux16 computer (driven on demand by JNI calls)                │
│                                                                     │
│  Rux16 BIOS executing from mapped bios.flash                        │
│    ├─ MMIO control device  ──►  status / exit / panic registers      │
│    ├─ MMIO debug serial    ──►  RuxComputerHandle.debug_output       │
│    ├─ MMIO serial input    ◄──  player keyboard events               │
│    ├─ MMIO storage0        ◄──► ruxvol boot media                    │
│    ├─ MMIO bios flash      ──►  read-only firmware mapping           │
│    └─ MMIO text display    ──►  RuxComputerTextDisplaySnapshot       │
└──────────────────────────────────────────┬──────────────────────────┘
                                           │ JNI run-until-signal
                                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Server tick thread (main thread)                                   │
│                                                                     │
│  RuntimeDevice.serverTick()                                          │
│    ├─ NativeVmBindings.runRux16ComputerUntilSignal(handle)           │
│    ├─ poll display snapshot / debug output                           │
│    ├─ flushDisplaySessions → FrameDeltaClientMessage                 │
│    └─ react to control register (halt / crash / reboot)              │
└──────────────────────────────────────────┬──────────────────────────┘
                                           │ network
                                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Client (render thread)                                             │
│                                                                     │
│  ComputerMenu.handleDisplayFrame(frame)                              │
│    └─ ClientDisplayBuffer.apply(frame)                               │
│  NotebookScreen.renderBg() → draws ClientDisplayBuffer               │
└─────────────────────────────────────────────────────────────────────┘
```

The text display device exposes a character cell buffer with cursor and a
monotonic sequence number for delta detection. The client sends discrete
input events into the serial-input MMIO device. There is no multi-process
scheduling and no host-call opcode — host-side interaction happens purely
through memory-mapped registers.
