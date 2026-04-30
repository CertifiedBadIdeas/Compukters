# Compukter Kraft — Architecture

## Overview

Compukter Kraft is a Minecraft mod that adds programmable computers with a custom language, compiler, and bytecode VM.
The project is split into multiple Gradle modules across a multi-version, multi-loader architecture:

## Domain Model

The mod has **two orthogonal categories** of programming-related in-world entities. They are not subtypes of each other and never become each other.

### Category 1 — Runtime Devices

Things in the world that **execute** CKL programs. Each Runtime Device has a VM, a `DeviceProfile`, a `DeviceFamily`, a runtime workspace, a terminal, and optional peripherals.

- **Today:** Computer (block).
- **Planned:** Laptop (portable item), Turtle (entity with inventory and fuel), Pocket Computer.

A Runtime Device intentionally does not provide an in-device program editor. Authoring happens at an Authoring Station, like firmware development for embedded hardware.

### Category 2 — Authoring Stations

Things in the world that **help the player write** CKL programs and are themselves implemented natively (Kotlin), not in CKL. Each Authoring Station has a local development workspace, an IDE engine (parser/type checker/autocomplete from `compiler`), a target descriptor pointing at a Runtime Device, and explicit sync actions (`pull`, `push`, `run`, `attach terminal`).

- **Today:** Workbench (block).
- **Possible later:** networked / collaborative variants. They stay native.

An Authoring Station is **not** a Runtime Device. It has no VM and does not execute CKL.

### Bridge — Target Descriptor

An Authoring Station holds a **target descriptor** identifying a Runtime Device and exposing its `DeviceProfile`/`DeviceFamily`. Today the descriptor is the computer item inserted into the Workbench's target slot. There is no shared filesystem between the two categories — only the explicit sync actions.

### Shared substrate (used by both categories)

Lives outside both category packages:

- **Language tooling** (`compiler` module): parser, type checker, bytecode VM, `DeviceProfile`/`DeviceFamily` data classes.
- **Workspace storage abstraction** (`core`): file CRUD instantiated separately by each category with its own root.
- **Terminal text models and font rendering** (`v1_21_1-common/ui/render`): glyph layout, color tables, fixed-width rendering.
- **Input transport interfaces** (`core`): wire-level event delivery — interpretation differs per category.

### Naming rules

- Workbench code MUST NOT live under a `computer.*` package (and vice versa). They are peers.
- Cross-category bridge types use neutral prefixes (`Target*`, `Device*`), never a category-specific prefix.
- Shared infrastructure types are named for their function, not their consumer.

The full canonical reference for this model is [docs/superpowers/specs/2026-04-30-device-authoring-domain-model-design.md](superpowers/specs/2026-04-30-device-authoring-domain-model-design.md). The phased rollout plan (audit-driven cleanup → Runtime Device umbrella → Laptop) lives there.

## Modules

| Module               | Purpose                                                                     |
|----------------------|-----------------------------------------------------------------------------|
| `compiler`           | Language frontend (parser, type checker), bytecode compiler, and VM runtime |
| `core`               | Shared mod logic: bootstrap descriptors, platform port interfaces, no MC deps |
| `v1_x_x-common`     | Architectury common module per MC version: shared Minecraft-facing code     |
| `v1_x_x-{fabric,forge,neoforge}` | Loader leaf modules: bootstrap, event hooks, registry, network handler |
| `v1_x_x-create-neoforge` | Optional Create mod compat (NeoForge-only): guarded bootstrap, compat APIs |

### Module Ownership Rules

- **`core`** owns shared behavior, descriptors (`CommonBlockDescriptor`, `CommonMenuDescriptor`), and platform port interfaces (`PlatformBlockRegistrar`, `PlatformMenuRegistrar`, etc.)
- **`v1_x_x-common`** owns Minecraft-facing version adapters organized by **feature**: each block/device has its own package (e.g., `computer/`) containing block, block entity, item, menu, screen, input, network messages, context, data, and loot. Shared infrastructure lives in cross-cutting packages: `network/` (transport), `ui/` (rendering), `infrastructure/` (coroutines, gateways), `platform/`, `binding/`, `utils/`
- **Loader leaf modules** own bootstrap (`CompukterKraftMod`, `CompukterKraftClientMod`), loader-specific event hooks (`FabricCommonHooks`, `ForgeCommonHooks`, `ForgeClientHooks`), client registration/bootstrap (`ClientRegistry`, `ForgeClientRegistry`), registry (`ModRegistry`), loader helper extensions (`Extensions`), network handler (`NetworkHandler`), and tiny loader-only shims/adapters where the Minecraft API still differs (`ForgeComputerBlockEntity`, `NeoForgeComputerBlockEntity`, `ComputerIdentitySavedDataAccess`)

### Delegate Pattern for Cross-Module Dependencies

When common code needs to call loader-specific functionality (e.g., sending network packets), we use `lateinit` function references set by each loader during initialization:

```kotlin
// In v1_x_x-common
object ServerNetworking {
    lateinit var playerSender: (NetworkMessage<ClientNetworkContext>, ServerPlayer) -> Unit
    fun sendToPlayer(message, player) = playerSender(message, player)
}

// In loader CompukterKraftMod.kt
ServerNetworking.playerSender = NetworkHandler::sendToPlayer
```

### Boundary Enforcement

The repository treats these boundaries as executable rules, not just conventions:

- `core` must not import `net.minecraft.*`
- loader leaf modules must stay limited to bootstrap, registry, network, hooks, and tiny unavoidable shims

These rules are enforced by `ArchitectureBoundaryTest` in `modules/core`.

## Data Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│  VM coroutine (background thread)                                    │
│                                                                      │
│  DeviceProgram.run(runtime)                                          │
│    ├─ runtime.terminal.write("hello")  ──►  ScreenBuffer (direct)    │
│    ├─ runtime.terminal.readln()      ──►  suspends on VmEvent      │
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
│  ServerComputer.serverTick() — implemented by RuntimeDeviceImpl     │
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
│  ComputerTerminalScreen.renderBg()                                   │
│    └─ buildTerminalUi(layout, snapshot) → List<UiNode>               │
│         └─ UiRenderer.render(graphics, font, nodes)                  │
│              └─ FixedWidthFontRenderer.drawTerminal(snapshot)        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Computer Lifecycle

```
BlockEntity.use()
  └─ getOrCreateRuntimeDevice()
    └─ DeviceManager.getOrCreateVm()
            └─ RuntimeDeviceImpl(deviceId, properties, manager, gameTime, terminalNetwork, stateSink)
                  ↑ host ports come from BlockEntityRuntimeDeviceHost

RuntimeDeviceImpl.turnOn()
  ├─ DeviceWorkspaceInitializer.ensureInitialized(id)
  ├─ DeviceManager.getOrCreateVm(id, profile, callbacks, logger)
  │    └─ BackgroundDeviceVm(id, profile, dispatcher, callbacks, logger, workspace)
  │         └─ owns ScreenBuffer(width, height, colour)
  ├─ vmHandle.boot()
  │    └─ load boot script → compile → scope.launch { program.run(runtime) }
  └─ stateSink.onPowerStateChanged(true)

RuntimeDeviceImpl.serverTick()  [every game tick, 50ms]
  ├─ vmHandle.requestSlice(gameTime.gameTime())
  ├─ dispatch host calls (filesystem only)
  ├─ syncScreen() → readScreenSnapshot() → publish to lastScreenSnapshot
  ├─ flushTerminalSessions()
  │    ├─ terminalNetwork.isSessionStillBound(uuid, containerId, deviceId)
  │    └─ terminalNetwork.sendStdoutBytes(uuid, containerId, bytes)
  └─ check for stop/crash/reboot

RuntimeDeviceImpl.close()
  └─ DeviceManager.removeVm() → vmHandle.stop()
```

---

## Package Structure

### `compiler` module

| Package                    | Responsibility                                               |
|----------------------------|--------------------------------------------------------------|
| `ru.lazyhat.compukterkraft.lang.api`              | Bytecode format: `Instruction`, `BytecodeModule`, operators  |
| `ru.lazyhat.compukterkraft.lang.frontend`         | Parser, type checker, code generator, IDE support            |
| `ru.lazyhat.compukterkraft.lang.runtime`          | VM runtime: `BytecodeVirtualMachine`, `RuntimeHostBridge`    |
|                            | Data types: `ScreenBuffer`, `ScreenBufferSnapshot`           |
|                            | Interfaces: `DeviceRuntime`, `DeviceTerminalApi`, `DeviceWorkspace`, `DeviceIdeHost` |
|                            | Models: `DeviceProfile`, `VmSnapshot`, `HostCall`            |

### `core` module

| Package                            | Responsibility                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `compukterkraft.core.bootstrap`               | `CommonModBootstrap`, content descriptors, `CommonNetworkProtocol` |
| `compukterkraft.core.platform.api`             | Port interfaces: `PlatformBlockRegistrar`, `PlatformMenuRegistrar` |
| `compukterkraft.core.block`                    | `DeviceFamily` enum (pure Kotlin, no MC deps)                      |
| `compukterkraft.core.device`                 | `DeviceEvents`, `DeviceProperties` — shared device context                        |
| `compukterkraft.core.device.runtime`         | `RuntimeDevice` (umbrella + role interfaces), `RuntimeDeviceImpl`, `DeviceManager`, host ports (`GameTimeSource`, `TerminalNetworkBridge`, `DeviceStateSink`) |
| `compukterkraft.core.device.input`           | Input dispatch: `ComputerInputDispatcher`, `ServerInputHandler`    |
| `compukterkraft.core.device.vm`              | Background VM host: `BackgroundDeviceVm`, `DeviceVmSupervisor`, `DeviceProfileRegistry`, `vm/api/*` |
| `compukterkraft.core.workbench`                | IDE/workbench contracts and state (Authoring Station, peer of computer) |

### `v1_x_x-common` modules

**Feature packages (computer-specific):**

| Package                               | Responsibility                                                     |
|---------------------------------------|--------------------------------------------------------------------|
| `compukterkraft.common.computer.block`            | Concrete blocks/block entities, `ComputerState`, `ComputerFamilyExt` |
| `compukterkraft.common.computer.item`             | `AbstractComputerItem`, `ComputerItem`                             |
| `compukterkraft.common.computer.menu`             | `AbstractComputerMenu`, `ComputerMenu`, `ServerInputState`         |
| `compukterkraft.common.computer.screen`           | `ComputerScreen`, `ComputerTerminalScreen`                         |
| `compukterkraft.common.computer.input`            | Computer input binding                                             |
| `compukterkraft.common.computer.context`          | `ServerContext`, `ComputerIdentitySavedData`, `BlockEntityRuntimeDeviceHost` (port adapter) |
| `compukterkraft.common.computer.data`             | `ComputerContainerData`, `IContainerData`                          |
| `compukterkraft.common.computer.loot`             | Loot functions and conditions                                      |
| `compukterkraft.common.computer.network.server`   | Server-bound computer network messages                             |
| `compukterkraft.common.computer.network.client`   | Client-bound computer network messages                             |

**Shared infrastructure packages:**

| Package                               | Responsibility                                                     |
|---------------------------------------|--------------------------------------------------------------------|
| `compukterkraft.common.network`                   | `NetworkMessages`, `MessageType`, `ServerNetworking`, `ClientNetworking` |
| `compukterkraft.common.network.text`              | Table formatting utilities                                         |
| `compukterkraft.common.infrastructure`            | Adapters: input gateway, workbench gateways, coroutine dispatcher  |
| `compukterkraft.common.ui`                        | `TerminalState`                                                    |
| `compukterkraft.common.ui.dsl`                    | Minecraft-side `UiRenderer`                                        |
| `compukterkraft.common.ui.render`                 | `FixedWidthFontRenderer`, `WorkbenchTerminalRenderer` (Blaze3D)    |
| `compukterkraft.common.utils`                     | `BlockEntityUtils`, `BufferUtils`, `CommandUtils`, `LevelUtils`    |

### Loader leaf modules

| Package                            | Responsibility                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `compukterkraft.impl`                              | Mod entry points, `ModRegistry`, `Extensions`, client bootstrap helpers |
| `compukterkraft.impl.computer.block`              | Tiny loader-only shims for API drift (`ForgeComputerBlockEntity`, `NeoForgeComputerBlockEntity`) |
| `compukterkraft.impl.platform`                    | `NetworkHandler` — loader-specific packet registration             |

---

## Key Classes

### `BackgroundDeviceVm`

The main VM host. Runs the compiled program on a background coroutine dispatcher.

- **Thread model:** One coroutine per computer. The VM coroutine writes to `ScreenBuffer` directly (no HostCall roundtrip for terminal I/O). The server tick thread reads snapshots via `ScreenBuffer.snapshot()`.
- **Scheduling:** Each tick, the server calls `requestSlice()` which sends a permit through a `Channel`. The VM coroutine suspends at scheduling points when it exhausts its CPU budget.
- **Lifecycle:** Created by `DeviceManager`, booted by `RuntimeDeviceImpl`, stopped with `stop(reason)`.

### `DeviceManager`

Server-wide singleton (held by `ServerContext.deviceManager`) that manages all active runtime devices and their VMs.

- Maintains the registry of active `RuntimeDevice` instances (typed on the interface, not the impl).
- Delegates VM-handle lifecycle and shared workspace/IDE access to `DeviceVmSupervisor`.
- Provides `getOrCreateVm()`, `removeVm()`, `ensureWorkspaceInitialized()`, `add()`, and `remove()`.

### `RuntimeDevice` and `RuntimeDeviceImpl`

Platform-neutral runtime-device contract and its canonical implementation, both living in `:core/.../computer/runtime/`.

- `RuntimeDevice` is the umbrella interface composed from five role interfaces: `RuntimeDeviceLifecycle`, `RuntimeDeviceInput` (extends `DeviceEvents.Receiver`), `RuntimeDeviceScreen`, `RuntimeDeviceTerminalSessions`, `RuntimeDeviceMetadata`. Future minimal carriers (e.g. Pocket without terminal sessions) can implement a narrower subset.
- `RuntimeDeviceImpl` orchestrates the VM lifecycle: each game tick it requests a VM slice, dispatches host calls, syncs the latest screen snapshot, and flushes per-player terminal sessions. Reboot/shutdown/crash state transitions are handled here.
- All world-side interactions are abstracted via three narrow host ports — `GameTimeSource`, `TerminalNetworkBridge`, `DeviceStateSink` — so the impl has zero Minecraft imports.

### Host ports (`:core/.../computer/runtime/ports/`)

The ports decouple `RuntimeDeviceImpl` from Minecraft:

- **`GameTimeSource`** — `gameTime(): Long`. Replaces `ServerLevel.gameTime`.
- **`TerminalNetworkBridge`** — `isSessionStillBound(playerUuid, containerId, deviceId)` and `sendStdoutBytes(playerUuid, containerId, bytes)`. Replaces direct `MinecraftServer.playerList` lookups, `ContainerMenu` validity checks, and `ServerNetworking.sendToPlayer(StdoutBytesClientMessage(...))`.
- **`DeviceStateSink`** — `onPowerStateChanged(isOn)`. Used by the impl to notify the carrier (block entity) so it can update its block state property.

In `:v1_21_1-common`, `BlockEntityRuntimeDeviceHost` implements all three ports against an `AbstractComputerBlockEntity` + `ServerLevel`. The detached-computer path inside `WorkbenchBlockEntity` provides its own no-op terminal/state ports.

### `ServerComputer`

The historical name for `RuntimeDeviceImpl`. Renamed in Phase 2b; the class no longer exists under that name.

### `ScreenBuffer`

- **Writer:** VM coroutine calls `write()`, `println()`, `clear()`, `setCursor()`, `scroll()`.
- **Reader:** Server tick thread calls `snapshot()` — atomic dirty-flag check + synchronized copy.
- **No HostCall roundtrip** for terminal writes — mutable state lives in the VM, read-only access from the server thread.

Flat character grid (`CharArray` + `ByteArray` colours) owned by `BackgroundDeviceVm`.

### `WorkbenchStore`

Client-side state management for the Workbench authoring GUI.

- Manages editor state, workspace file list, IDE features (diagnostics, completions).
- Pure state container — no Minecraft dependencies.

### `UiRenderer`

Converts `List<UiNode>` into Minecraft draw calls.

- `UiNode` is a sealed interface: `Rect`, `Text`, `RightAlignedText`, `TerminalView`, `Group`.
- `buildTerminalUi()` is a pure function that produces nodes from layout + snapshot.
- Minecraft rendering glue such as `UiRenderer`, `FixedWidthFontRenderer`, `WorkbenchTerminalRenderer`, and screen classes is where `GuiGraphics` / Blaze3D knowledge remains.

---

## Design Decisions

### Terminal I/O is direct, not via HostCall

Terminal writes (`write`, `println`, `clear`, `setCursor`) go directly to `ScreenBuffer` on the VM coroutine thread. Previously they were routed through the HostCall mechanism (VM → suspend → queue → server tick → dispatch → mutate terminal → result → resume), adding 50ms+ latency per call. Since the screen buffer is VM-owned data (not a server resource like the filesystem), direct writes are simpler and faster.

### MenuSide sealed interface

Minecraft requires one class per `MenuType`. We can't have separate `ServerComputerMenu` / `ClientComputerMenu` classes. Instead, `MenuSide` is a sealed interface with `Server` and `Client` variants. Server-only code accesses `menu.serverSide.computer`, client-only code accesses `menu.clientSide.screenSnapshot`. The `as` cast is concentrated in one place.

### Declarative UI (UiNode / UiRenderer)

Minimal stateless UI layer. Business logic produces `List<UiNode>` (pure data), `UiRenderer` converts to draw calls. No layout engine, no reactivity, no widget tree — just a thin separation of "what to draw" from "how to draw". Makes the rendering logic testable without Minecraft.

### Screen-First Compiled UI

The repository now also contains a new UI path intended to replace the legacy `UiNode` slice incrementally.

This stack has four layers:

1. `core.ui.foundation` authoring DSL for layout, render intent, semantics, and interaction.
2. `core.ui.program` compiler output with phased layout, render, hit-test, input, and focus programs.
3. `core.ui.program.ScreenRuntimeExecutor` as the minimal runtime host for live handler and focus state.
4. `v1_21_1-common` bridge code that renders compiled ops through `GuiGraphics` and terminal-specific adapters.

The key distinction from the earlier screen-first prototype is that UI structure is compiled into a `ScreenProgram` rather than rebuilt as a live tree every frame. The key distinction from the older render-only architecture is that authoring now starts from one screen-first DSL that includes interaction semantics.

`ComputerTerminalScreen` is the first migration target on this new path. Legacy `core.ui.dsl.UiNode` and `common.ui.dsl.UiRenderer` remain transitional and are still valid for screens that have not yet moved to the compiled stack, especially `WorkbenchEditorScreen`.

