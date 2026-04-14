# Compukter Kraft — Architecture

## Overview

Compukter Kraft is a Minecraft mod that adds programmable computers with a custom language, compiler, and bytecode VM.
The project is split into multiple Gradle modules across a multi-version, multi-loader architecture:

| Module               | Purpose                                                                     |
|----------------------|-----------------------------------------------------------------------------|
| `compiler`           | Language frontend (parser, type checker), bytecode compiler, and VM runtime |
| `core`               | Shared mod logic: bootstrap descriptors, platform port interfaces, no MC deps |
| `v1_x_x-common`     | Architectury common module per MC version: shared Minecraft-facing code     |
| `v1_x_x-{fabric,forge,neoforge}` | Loader leaf modules: bootstrap, event hooks, registry, network handler |
| `v1_x_x-create-neoforge` | Optional Create mod compat (NeoForge-only): guarded bootstrap, compat APIs |

### Module Ownership Rules

- **`core`** owns shared behavior, descriptors (`CommonBlockDescriptor`, `CommonMenuDescriptor`), and platform port interfaces (`PlatformBlockRegistrar`, `PlatformMenuRegistrar`, etc.)
- **`v1_x_x-common`** owns Minecraft-facing version adapters and classes: blocks, block entities, items, menus, screens, network messages, version-specific context adapters, and Minecraft rendering glue
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
    └─ ComputerManager.getOrCreateVm()
            └─ ServerComputer(instanceID, level, properties)

ServerComputer.turnOn()
  ├─ ComputerWorkspaceInitializer.ensureInitialized(id)
  ├─ ComputerManager.getOrCreateVm(id, profile, callbacks, logger)
  │    └─ BackgroundComputerVm(id, profile, dispatcher, callbacks, logger, workspace)
  │         └─ owns ScreenBuffer(width, height, colour)
  └─ vmHandle.boot()
       └─ load boot script → compile → scope.launch { program.run(runtime) }

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
| `ru.lazyhat.compukterkraft.lang.api`              | Bytecode format: `Instruction`, `BytecodeModule`, operators  |
| `ru.lazyhat.compukterkraft.lang.frontend`         | Parser, type checker, code generator, IDE support            |
| `ru.lazyhat.compukterkraft.lang.runtime`          | VM runtime: `BytecodeVirtualMachine`, `RuntimeHostBridge`    |
|                            | Data types: `ScreenBuffer`, `ScreenBufferSnapshot`           |
|                            | Interfaces: `ComputerRuntime`, `ComputerTerminalApi`         |
|                            | Models: `ComputerProfile`, `VmSnapshot`, `HostCall`          |

### `core` module

| Package                            | Responsibility                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `ck.mod.bootstrap`                 | `CommonModBootstrap`, content descriptors, `CommonNetworkProtocol` |
| `ck.mod.platform.api`              | Port interfaces: `PlatformBlockRegistrar`, `PlatformMenuRegistrar` |
| `ck.mod.block`                     | `ComputerFamily` enum (pure Kotlin, no MC deps)                    |

### `v1_x_x-common` modules

| Package                            | Responsibility                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `ck.mod.block`                     | Concrete blocks/block entities, `ComputerState`, `ComputerFamilyExt` |
| `ck.mod.computer`                  | `ServerComputer`                                                   |
| `ck.mod.context`                   | `ServerContext`, `ComputerManager`, `ComputerIdentitySavedData`    |
| `ck.mod.data`                      | `ComputerContainerData`, `IContainerData`                          |
| `ck.mod.gui`                       | `TerminalState`                                                    |
| `ck.mod.gui.screen`                | `ComputerScreen`, `ComputerWorkbenchScreen`                        |
| `ck.mod.infrastructure`            | Adapters: input gateway, workbench gateways, coroutine dispatcher  |
| `ck.mod.item`                      | `AbstractComputerItem`, `ComputerItem`                             |
| `ck.mod.menu`                      | `AbstractComputerMenu`, `ComputerMenu`, `ServerInputState`         |
| `ck.mod.network`                   | `NetworkMessages`, `MessageType`, `ClientNetworking`               |
| `ck.mod.network.client`            | Client-bound network messages                                      |
| `ck.mod.network.server`            | Server-bound network messages, `ServerNetworking`                  |
| `ck.mod.network.text`              | Table formatting utilities                                         |
| `ck.mod.ui.dsl`                    | Minecraft-side `UiRenderer`                                        |
| `ck.mod.ui.render`                 | `FixedWidthFontRenderer`, `WorkbenchTerminalRenderer` (Blaze3D)    |
| `ck.mod.utils`                     | `BlockEntityUtils`, `BufferUtils`, `CommandUtils`, `LevelUtils`    |

### Loader leaf modules

| Package                            | Responsibility                                                     |
|------------------------------------|--------------------------------------------------------------------|
| `ck.mod`                           | Mod entry points, `ModRegistry`, `Extensions`, client bootstrap helpers |
| `ck.mod.block`                     | Tiny loader-only shims for API drift (`ForgeComputerBlockEntity`, `NeoForgeComputerBlockEntity`) |
| `ck.mod.context`                   | Tiny saved-data access adapters where loader APIs still diverge    |
| `ck.mod.platform`                  | `NetworkHandler` — loader-specific packet registration             |

---

## Key Classes

### `BackgroundComputerVm`

The main VM host. Runs the compiled program on a background coroutine dispatcher.

- **Thread model:** One coroutine per computer. The VM coroutine writes to `ScreenBuffer` directly (no HostCall roundtrip for terminal I/O). The server tick thread reads snapshots via `ScreenBuffer.snapshot()`.
- **Scheduling:** Each tick, the server calls `requestSlice()` which sends a permit through a `Channel`. The VM coroutine suspends at scheduling points when it exhausts its CPU budget.
- **Lifecycle:** Created by `ComputerManager`, booted by `ServerComputer`, stopped with `stop(reason)`.

### `ComputerManager`

Server-wide singleton that manages all active computers and their VMs.

- Maintains the registry of active `ServerComputer` instances.
- Delegates VM-handle lifecycle and shared workspace/IDE access to `ComputerVmSupervisor`.
- Provides `getOrCreateVm()`, `removeVm()`, `ensureWorkspaceInitialized()`, `add()`, and `remove()`.

### `ServerComputer`

Server-side representation of one computer instance. Orchestrates the VM lifecycle.

- Each game tick it requests a VM slice, dispatches host calls, and synchronizes the latest screen snapshot.
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
- Minecraft rendering glue such as `UiRenderer`, `FixedWidthFontRenderer`, `WorkbenchTerminalRenderer`, and screen classes is where `GuiGraphics` / Blaze3D knowledge remains.

---

## Design Decisions

### Terminal I/O is direct, not via HostCall

Terminal writes (`write`, `printLine`, `clear`, `setCursor`) go directly to `ScreenBuffer` on the VM coroutine thread. Previously they were routed through the HostCall mechanism (VM → suspend → queue → server tick → dispatch → mutate terminal → result → resume), adding 50ms+ latency per call. Since the screen buffer is VM-owned data (not a server resource like the filesystem), direct writes are simpler and faster.

### MenuSide sealed interface

Minecraft requires one class per `MenuType`. We can't have separate `ServerComputerMenu` / `ClientComputerMenu` classes. Instead, `MenuSide` is a sealed interface with `Server` and `Client` variants. Server-only code accesses `menu.serverSide.computer`, client-only code accesses `menu.clientSide.screenSnapshot`. The `as` cast is concentrated in one place.

### Declarative UI (UiNode / UiRenderer)

Minimal stateless UI layer. Business logic produces `List<UiNode>` (pure data), `UiRenderer` converts to draw calls. No layout engine, no reactivity, no widget tree — just a thin separation of "what to draw" from "how to draw". Makes the rendering logic testable without Minecraft.

