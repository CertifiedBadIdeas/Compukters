# Compukter Kraft — Architecture

> Note: as of issue #26 the legacy CKL language / CKIM bytecode VM /
> in-game Workbench IDE have been removed. As of issue #44 the legacy
> Image-VM (host-call opcode, multi-process device daemon, runtime
> kernel, host-imported filesystem) has also been retired in favour of a
> single LowVM runtime with flat RAM and MMIO devices. Older revisions
> in git history describe the previous architectures.

## Overview

Compukter Kraft is a Minecraft mod that adds programmable computers backed
by a Rust virtual machine (`native/rux-vm`). The mod ships a single
player-facing computer item — **Notebook** — that boots a precompiled
`rux-laptop.ruxi` image via JNI. The image is a LowVM program executing
over flat RAM with memory-mapped peripherals.

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
| `native/rux-vm`  | Rust virtual machine: LowVM (flat RAM + MMIO), `RuxComputer` handle, JNI exports |
| `native/rux-compiler` | Compiler producing `rux-laptop.ruxi` LowVM images consumed by `rux-vm`   |

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
  └─ NativeVmBindings.createRuxComputer(image, memorySize, sliceBudgetNanos)
        └─ Rust RuxComputerHandle
              ├─ LowImageVm executing rux-laptop.ruxi
              ├─ flat RAM + MMIO bus (control, debug-serial, serial-input,
              │   text-display) — single process, no scheduler
              └─ exposes control / debug / display snapshot over JNI

RuntimeDevice.serverTick(gameTime)
  ├─ runRuxComputerUntilSignal() — advance VM until pause / halt
  ├─ ruxComputerDisplay0Snapshot() — pull text display state
  └─ drainRuxComputerDebugOutput() — drain debug serial bytes

RuntimeDevice.close()
  └─ NativeVmBindings.freeRuxComputer(handle)
```

## Data flow

```
┌─────────────────────────────────────────────────────────────────────┐
│  Rust LowVM (driven on demand by JNI calls, single process)         │
│                                                                     │
│  LowImageVm executing rux-laptop.ruxi over flat RAM                 │
│    ├─ MMIO control device  ──►  status / exit / panic registers      │
│    ├─ MMIO debug serial    ──►  RuxComputerHandle.debug_output       │
│    ├─ MMIO serial input    ◄──  player keyboard events               │
│    └─ MMIO text display    ──►  RuxComputerTextDisplaySnapshot       │
└──────────────────────────────────────────┬──────────────────────────┘
                                           │ JNI run-until-signal
                                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Server tick thread (main thread)                                   │
│                                                                     │
│  RuntimeDevice.serverTick()                                          │
│    ├─ NativeVmBindings.runRuxComputerUntilSignal(handle)             │
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
input events into the serial-input MMIO device. There is no filesystem,
no multi-process scheduling, and no host-call opcode — host-side
interaction happens purely through memory-mapped registers.
