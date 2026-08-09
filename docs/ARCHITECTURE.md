# Compukter Kraft — Architecture

> Architecture direction: [ADR 0001](architecture-decisions/0001-retire-k16-adopt-rv64.md)
> retires K16 as the product ISA and selects `RV64IMA_Zicsr_Zifencei` with the
> standard LP64/ELF64 toolchain. This document describes the still-running K16
> implementation during the incremental migration; it is not the long-term
> target contract.
>
> Note: as of issue #26 the legacy CKL language / CKIM bytecode VM /
> in-game Workbench IDE have been removed. As of issue #44 the legacy
> Image-VM (host-call opcode, multi-process device daemon, runtime
> kernel, host-imported filesystem) has also been retired. The active
> computer boot path is now Kraft16 guest execution from BIOS flash and
> storage-backed boot media. Older revisions in git history describe the
> previous architectures.

## Current Implementation Overview

Compukter Kraft is a Minecraft mod that adds programmable computers backed
by a Rust virtual machine (`host/k16-vm`). The mod ships a single
player-facing computer item — **Notebook** — that starts a native
`K16Computer` from a per-computer `bios.kflash` file. The BIOS executes on
the Kraft16 guest CPU, can inspect storage0 boot media, and exposes devices
through memory-mapped peripherals.

## K16 BIOS Flash Workflow

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
| `native-runtime`        | Kotlin types shared with the Rust VM: device profile, VM state/events, JNI bindings (`NativeVmBindings`), IDE stub types kept for residual UI surfaces |
| `core`                  | Shared mod logic: server context / device registry, runtime device interfaces, platform port interfaces, no Minecraft deps |
| `v1_21_1-common`        | Minecraft 1.21.1 common code: blocks, items, menus, networking, rendering        |
| `v1_21_1-neoforge`      | NeoForge bootstrap, registries, network handler, native library packaging         |
| `v1_21_1-create-neoforge` | Optional Create mod compatibility (NeoForge-only)                              |

## Native crates

| Crate            | Purpose                                                                  |
|------------------|--------------------------------------------------------------------------|
| `host/k16-vm`  | Rust virtual machine: Kraft16 CPU, memory-mapped devices, `K16Computer` handle, JNI exports |
| `host/k16-tools` | Legacy Rux source checks plus K16 artifact tooling via `k16` for disassembly, volume, and filesystem commands |

For a code-level map of the active Rust VM path, see
[`k16-vm-code-flow.md`](k16-vm-code-flow.md).

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

## Data flow

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
