# Compukter Kraft — Architecture

## Overview

Compukter Kraft is a Minecraft mod that adds programmable computers with a custom language, compiler, and bytecode VM.
The project is split into two Gradle modules:

| Module     | Purpose                                                                     |
|------------|-----------------------------------------------------------------------------|
| `compiler` | Language frontend (parser, type checker), bytecode compiler, and VM runtime |
| `mod`      | Minecraft integration: blocks, menus, networking, rendering                 |

---

## Data Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│  VM coroutine (background thread)                                    │
│                                                                      │
│  ComputerProgram.run(runtime)                                        │
│    ├─ runtime.terminal.write("hello")  ──►  ScreenBuffer (direct)    │
│    ├─ runtime.terminal.readLine()      ──►  suspends on VmEvent      │
│    ├─ runtime.filesystem.readText()    ──►  HostCall → HostResult    │
│    └─ runtime.system.shutdown()        ──►  HostCall → HostResult    │
└──────────────────────────────────────────┬───────────────────────────┘
                                           │
              ScreenBuffer.snapshot()      │  HostCallManager
              (volatile dirty flag)        │  (concurrent queues)
                                           │
┌──────────────────────────────────────────▼───────────────────────────┐
│  Server tick thread (main thread)                                    │
│                                                                      │
│  ServerComputer.serverTick()                                         │
│    ├─ vmHandle.requestSlice(gameTime)                                │
│    ├─ vmHandle.drainHostCalls() → HostCallDispatcher.dispatch()      │
│    │    └─ only filesystem ops remain (terminal writes are direct)   │
│    ├─ vmHandle.readScreenSnapshot()                                  │
│    │    └─ if dirty → ScreenBufferSnapshot → network packet          │
│    └─ check VM state (stopped/crashed/reboot)                        │
└──────────────────────────────────────────┬───────────────────────────┘
                                           │
              ComputerTerminalClientMessage │
              (FriendlyByteBuf)             │
                                           │
┌──────────────────────────────────────────▼───────────────────────────┐
│  Client (render thread)                                              │
│                                                                      │
│  ClientNetworkContextImpl                                            │
│    └─ ComputerMenu.updateTerminal(snapshot)                          │
│         └─ MenuSide.Client.screenSnapshot = snapshot                 │
│                                                                      │
│  ComputerWorkbenchScreen.renderBg()                                  │
│    └─ buildTerminalUi(layout, snapshot) → List<UiNode>               │
│         └─ UiRenderer.render(graphics, font, nodes)                  │
│              └─ FixedWidthFontRenderer.drawTerminal(snapshot)        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Computer Lifecycle

```
BlockEntity.use()
  └─ getOrCreateServerComputer()
       └─ ComputerManager.getOrCreateComputer()
            └─ ServerComputer(instanceID, level, properties)

ServerComputer.turnOn()
  ├─ ComputerManager.getOrCreateVm(id, profile, callbacks, logger)
  │    └─ BackgroundComputerVm(id, profile, dispatcher, callbacks, logger, workspace)
  │         └─ owns ScreenBuffer(width, height, colour)
  ├─ ComputerProgramCompiler.compile(bootScript)
  └─ vmHandle.start(program)
       └─ scope.launch { program.run(runtime) }

ServerComputer.serverTick()  [every game tick, 50ms]
  ├─ vmHandle.requestSlice()
  ├─ dispatch host calls (filesystem only)
  ├─ syncScreen() → readScreenSnapshot() → send to watching players
  └─ check for stop/crash/reboot

ServerComputer.close()
  └─ ComputerManager.removeVm() → vmHandle.stop()
```

---

## Package Structure

### `compiler` module

| Package                    | Responsibility                                               |
|----------------------------|--------------------------------------------------------------|
| `ck.lang.api`              | Bytecode format: `Instruction`, `BytecodeModule`, operators  |
| `ck.lang.frontend`         | Parser, type checker, code generator, IDE support            |
| `ck.lang.runtime`          | VM runtime: `BytecodeVirtualMachine`, `RuntimeHostBridge`    |
|                            | Data types: `ScreenBuffer`, `ScreenBufferSnapshot`           |
|                            | Interfaces: `ComputerRuntime`, `ComputerTerminalApi`         |
|                            | Models: `ComputerProfile`, `VmSnapshot`, `HostCall`          |

### `mod` module

| Package                            | Responsibility                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `ck.mod`                           | Mod entry point, config, logger                                    |
| `ck.mod.block`                     | Block entities, block definitions, `ComputerFamily`                |
| `ck.mod.computer`                  | `ServerComputer`, `ComputerEvents`, `ComputerProperties`           |
| `ck.mod.computer.vm`               | `BackgroundComputerVm`, `VmTerminalApi`, `VmContext`, schedulers   |
| `ck.mod.context`                   | `ServerContext`, `ComputerManager` — server-wide singletons        |
| `ck.mod.application.runtime`       | `HostCallDispatcher`, `WorkspaceProgramLoader`, compiler bridge    |
| `ck.mod.application.workbench`     | `WorkbenchStore`, editor/IDE state management                      |
| `ck.mod.menu`                      | `AbstractComputerMenu`, `MenuSide`, `ServerInputState`             |
| `ck.mod.network.client`            | Client-bound network messages                                      |
| `ck.mod.network.server`            | Server-bound network messages                                      |
| `ck.mod.gui`                       | Layout models, input controllers, `Palette`, `Colour`, `FrameInfo` |
| `ck.mod.gui.screen`                | `ComputerWorkbenchScreen` — Minecraft Screen implementation        |
| `ck.mod.ui.dsl`                    | Declarative UI: `UiNode`, `UiRenderer`, `buildTerminalUi()`       |
| `ck.mod.ui.render`                 | `FixedWidthFontRenderer`, `WorkbenchTerminalRenderer` (Blaze3D)    |
| `ck.mod.ui.workbench`              | `WorkbenchLayoutModel` — screen layout calculations                |
| `ck.mod.data`                      | `ComputerContainerData`, saved data                                |
| `ck.mod.language`                  | `LanguageServices` — bridge to compiler module                     |
| `ck.mod.infrastructure`            | Adapters: workspace gateway, IDE facade, input gateway             |

---

## Key Classes

### `BackgroundComputerVm`

The main VM host. Runs the compiled program on a background coroutine dispatcher.

- **Thread model:** One coroutine per computer. The VM coroutine writes to `ScreenBuffer` directly (no HostCall roundtrip for terminal I/O). The server tick thread reads snapshots via `ScreenBuffer.snapshot()`.
- **Scheduling:** Each tick, the server calls `requestSlice()` which sends a permit through a `Channel`. The VM coroutine suspends at scheduling points when it exhausts its CPU budget.
- **Lifecycle:** Created by `ComputerManager`, started with `start(program)`, stopped with `stop(reason)`.

### `ComputerManager`

Server-wide singleton that manages all active computers and their VMs.

- Maintains `ConcurrentHashMap<Int, BackgroundComputerVm>` keyed by computer ID.
- Owns the shared `ComputerWorkspace` (filesystem).
- Provides `getOrCreateVm()`, `removeVm()`, `ensureWorkspaceInitialized()`.

### `ServerComputer`

Server-side representation of one computer instance. Orchestrates the VM lifecycle.

- Calls `vmHandle.serverTick()` each game tick.
- Reads `ScreenBufferSnapshot` and sends it to watching players.
- Dispatches filesystem HostCalls through `HostCallDispatcher`.
- Handles reboot/shutdown/crash state transitions.

### `ScreenBuffer`

Flat character grid (`CharArray` + `ByteArray` colours) owned by `BackgroundComputerVm`.

- **Writer:** VM coroutine calls `write()`, `printLine()`, `clear()`, `setCursor()`, `scroll()`.
- **Reader:** Server tick thread calls `snapshot()` — atomic dirty-flag check + synchronized copy.
- **No HostCall roundtrip** for terminal writes — mutable state lives in the VM, read-only access from the server thread.

### `WorkbenchStore`

Client-side state management for the computer workbench GUI.

- Manages editor state, workspace file list, IDE features (diagnostics, completions).
- Pure state container — no Minecraft dependencies.

### `UiRenderer`

Converts `List<UiNode>` into Minecraft draw calls.

- `UiNode` is a sealed interface: `Rect`, `Text`, `RightAlignedText`, `TerminalView`, `Group`.
- `buildTerminalUi()` is a pure function that produces nodes from layout + snapshot.
- Only `UiRenderer` and `FixedWidthFontRenderer` know about `GuiGraphics` / Blaze3D.

---

## Design Decisions

### Terminal I/O is direct, not via HostCall

Terminal writes (`write`, `printLine`, `clear`, `setCursor`) go directly to `ScreenBuffer` on the VM coroutine thread. Previously they were routed through the HostCall mechanism (VM → suspend → queue → server tick → dispatch → mutate terminal → result → resume), adding 50ms+ latency per call. Since the screen buffer is VM-owned data (not a server resource like the filesystem), direct writes are simpler and faster.

### MenuSide sealed interface

Minecraft requires one class per `MenuType`. We can't have separate `ServerComputerMenu` / `ClientComputerMenu` classes. Instead, `MenuSide` is a sealed interface with `Server` and `Client` variants. Server-only code accesses `menu.serverSide.computer`, client-only code accesses `menu.clientSide.screenSnapshot`. The `as` cast is concentrated in one place.

### Declarative UI (UiNode / UiRenderer)

Minimal stateless UI layer. Business logic produces `List<UiNode>` (pure data), `UiRenderer` converts to draw calls. No layout engine, no reactivity, no widget tree — just a thin separation of "what to draw" from "how to draw". Makes the rendering logic testable without Minecraft.

