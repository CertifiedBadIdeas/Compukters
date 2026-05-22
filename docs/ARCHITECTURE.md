# Compukter Kraft — Architecture

> Note: as of issue #26 the legacy CKL language / CKIM bytecode VM / in-game
> Workbench IDE have been removed. This document reflects the current
> Rux-only stack. Older revisions in git history describe the previous CKL
> architecture.

## Overview

Compukter Kraft is a Minecraft mod that adds programmable computers backed
by a Rust virtual machine (`native/rux-vm`). The mod ships a single
player-facing computer item — **Notebook** — that boots a precompiled
`rux-laptop.ruxi` image via JNI.

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
| `native/rux-vm`  | Rust virtual machine, low-image runner, device daemon, JNI exports       |
| `native/rux-compiler` | Compiler producing `rux-laptop.ruxi` images consumed by `rux-vm`   |

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
  └─ NativeVmBindings.bootDeviceDaemon(profile, image, mountPoints)
        └─ Rust device daemon thread
              ├─ DeviceDaemon (native/rux-vm/src/device_daemon.rs)
              ├─ owns LowImageVm + image_runner state
              └─ exposes signal/event/host-call protocol via VmValue

RuntimeDevice.serverTick(gameTime)
  ├─ pollDaemon() — drain signals, host calls, display deltas
  └─ flushDisplaySessions() — send framebuffer deltas to bound clients

RuntimeDevice.close()
  └─ NativeVmBindings.shutdownDeviceDaemon()
```

## Data flow

```
┌─────────────────────────────────────────────────────────────────────┐
│  Rust device daemon (background thread, JNI-attached)               │
│                                                                     │
│  LowImageVm executing rux-laptop.ruxi                               │
│    ├─ host-call signals  ──►  DeviceDaemon.signal_queue              │
│    ├─ display ops        ──►  DisplayRegistry frame deltas          │
│    └─ kernel syscalls    ──►  RuntimeKernel state                   │
└──────────────────────────────────────────┬──────────────────────────┘
                                           │ JNI poll
                                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Server tick thread (main thread)                                   │
│                                                                     │
│  RuntimeDevice.serverTick()                                          │
│    ├─ NativeVmBindings.poll(daemonHandle, gameTime)                  │
│    ├─ dispatch host calls via NativeDeviceDaemonRuntime              │
│    ├─ flushDisplaySessions → FrameDeltaClientMessage                 │
│    └─ react to VmStopReason (halt / crash / reboot)                  │
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

The display device exposes generic accelerated framebuffer primitives
(`fillRect`, `copyRect`, `blitMono`, `present`). The client sends discrete
input events (`key`, `key_up`, `char`, `paste`, mouse) into the VM event
queue. The server emits framebuffer deltas through display sessions.
