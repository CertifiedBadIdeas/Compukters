# Phase 2b — Runtime Device Decoupling Design

**Status:** design
**Phase:** 2b (and absorbs 2d) of the Runtime Device / Authoring Station rollout
**Predecessors:** Phase 2a (substrate rename), Phase 2a-bis (Workspace/IDE rename)

## 1. Motivation

Today `ServerComputer` is the only "thing that owns a VM and pumps a terminal" in the codebase. It lives in `:v1_21_1-common` and is bound at construction time to `ServerLevel`. `ComputerManager` is the registry; identity is allocated as `Int` via `ComputerIdentitySavedData`. Every block-entity/item/menu artifact reaches into this single concrete class.

Phase 3 will introduce a second runtime device (Laptop) that does not live as a `BlockEntity` and does not have a `BlockPos`/`ServerLevel`. Before that can happen, the VM-owning, terminal-pumping abstraction must:

1. expose a stable interface in `:core` that does not mention `ServerLevel`, `MinecraftServer`, `BlockPos`, or any platform type;
2. take its world-side dependencies through narrow ports rather than reaching for `ServerContext` globally;
3. carry a name that is not tied to a specific in-game artifact ("Computer" the block).

This phase delivers exactly that, and folds in the rename portion previously reserved for Phase 2d.

## 2. Decisions

Resolved during brainstorming:

| Question | Decision |
|---|---|
| Scope | Maximum: introduce `RuntimeDevice` role interfaces, host ports, and **move** the impl from `:v1_21_1-common` to `:core`. |
| Rename | Both `ServerComputer` and `ComputerManager` (Phase 2d absorbed). |
| API shape | **Composition** — multiple role interfaces, with an umbrella interface aggregating them. |
| Host shape | **Narrow ports**: `GameTimeSource`, `TerminalNetworkBridge`, `DeviceStateSink`. |
| Rename breadth | All `Computer*` types inside `:core/.../vm` and `:v1_21_1-common/.../computer/context`. |
| Out of scope | Block / item / menu / screen / network-message layer keeps `Computer*` names. Translation strings untouched. |

## 3. Glossary

- **Runtime device** — a server-side entity that owns a VM, pumps a terminal, accepts input events, and delivers screen output. Today: a single block-resident concrete; tomorrow: also Laptop, Pocket, Turtle.
- **Host port** — a narrow interface implemented by the world-side carrier (BlockEntity, future ItemHost) that supplies one specific world capability to the runtime device.
- **Block-side carrier** — the BlockEntity that holds a runtime device alive while the block exists in the world; also referred to as "host adapter".

## 4. Target architecture

### 4.1 Module layout

```
modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/
  computer/                                  ← module retains "computer/" path (filesystem rename out of scope)
    vm/                                      ← VM machinery (renamed)
      BackgroundDeviceVm.kt                  ← was BackgroundComputerVm
      DeviceVmSupervisor.kt                  ← was ComputerVmSupervisor
      DeviceVmHandle.kt                      ← was ComputerVmHandle
      DeviceVmLogger.kt                      ← was ComputerVmLogger
      DeviceProgramSupport.kt                ← was ComputerProgramSupport
      DeviceWorkspaceInitializer.kt          ← was ComputerWorkspaceInitializer
      DeviceWorkspaceHost.kt                 ← unchanged from Phase 2a-bis
      WorkspaceDeviceIdeHost.kt              ← unchanged from Phase 2a-bis
    runtime/                                 ← NEW package
      RuntimeDevice.kt                       ← umbrella + role interfaces
      RuntimeDeviceImpl.kt                   ← single concrete (was ServerComputer)
      DeviceManager.kt                       ← was ComputerManager
      DeviceProperties.kt                    ← was ComputerProperties
      DeviceEvents.kt                        ← was ComputerEvents
      ports/
        GameTimeSource.kt
        TerminalNetworkBridge.kt
        DeviceStateSink.kt

modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/
  computer/
    context/
      ServerContext.kt                       ← stays; now holds DeviceManager
      BlockEntityRuntimeDeviceHost.kt        ← NEW: assembles ports for a BlockEntity
    block/
      AbstractComputerBlockEntity.kt         ← still here, still "Computer" (block layer)
      ComputerBlockEntity.kt                 ← creates RuntimeDeviceImpl via host
      ...
    input/
      NetworkComputerInputGateway.kt         ← still "Computer*" (network layer)
```

> The directory name `core/.../computer/vm/` is intentionally kept to avoid a directory move that would obscure the rename diff. Filesystem path normalization is a future cleanup.

### 4.2 Role interfaces (`:core/.../runtime/RuntimeDevice.kt`)

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime

import java.util.UUID
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/** Lifecycle role: turn on/off, tick, query state. */
interface RuntimeDeviceLifecycle {
    val deviceId: Int
    val isOn: Boolean
    fun turnOn()
    fun shutdown()
    fun reboot()
    fun serverTick()
    fun close()
}

/** Input role: accept VM events. */
interface RuntimeDeviceInput {
    fun queueEvent(event: String, arguments: Array<Any>)
}

/** Screen role: read latest screen snapshot (used by workbench / legacy clients). */
interface RuntimeDeviceScreen {
    val lastScreenSnapshot: ScreenBufferSnapshot?
}

/** Terminal-session role: per-player byte-stream attachments. */
interface RuntimeDeviceTerminalSessions {
    fun attachTerminalSession(playerUuid: UUID, containerId: Int, cols: Int, rows: Int)
    fun resizeTerminalSession(playerUuid: UUID, cols: Int, rows: Int)
    fun detachTerminalSession(playerUuid: UUID)
}

/** Metadata role: family/label management. */
interface RuntimeDeviceMetadata {
    val family: DeviceFamily
    var label: String?
    fun checkUsable(player: PlayerHandle): Boolean   // see §4.4 about PlayerHandle
}

/** Umbrella: every present-day runtime device implements every role. */
interface RuntimeDevice :
    RuntimeDeviceLifecycle,
    RuntimeDeviceInput,
    RuntimeDeviceScreen,
    RuntimeDeviceTerminalSessions,
    RuntimeDeviceMetadata
```

Future Pocket-like devices that, e.g., have no terminal sessions, will implement only the roles that apply and the umbrella will be downgraded to a `typealias` of the relevant intersection. Out of scope for 2b.

### 4.3 Host ports (`:core/.../runtime/ports/`)

```kotlin
fun interface GameTimeSource {
    fun gameTime(): Long
}

interface TerminalNetworkBridge {
    /** True if the player is currently connected to the server. */
    fun isPlayerOnline(playerUuid: UUID): Boolean
    /** Send raw stdout bytes to the player; no-op if offline. */
    fun sendStdoutBytes(playerUuid: UUID, containerId: Int, bytes: ByteArray)
}

fun interface DeviceStateSink {
    /** Notified when the device's on/off state changes; block-side carriers
     *  use this to mirror the state into the block's blockstate property. */
    fun onPowerStateChanged(isOn: Boolean)
}
```

> Note `DeviceStateSink` carries the abstract on/off bit, NOT the platform-specific `ComputerState` enum (which lives in the block layer). The block-side carrier maps `Boolean` to `ComputerState`.

### 4.4 `PlayerHandle` (small helper)

`RuntimeDeviceMetadata.checkUsable(player)` currently takes `net.minecraft.world.entity.player.Player`. To keep `:core` free of platform types, introduce in `:core`:

```kotlin
interface PlayerHandle {
    val uuid: UUID
    val isStillValid: Boolean
}
```

`ServerContext` / BlockEntity wrap a vanilla `Player` into this when calling `checkUsable`. Trivial, but keeps the boundary clean.

### 4.5 `RuntimeDeviceImpl` constructor (was `ServerComputer`)

```kotlin
class RuntimeDeviceImpl(
    override val deviceId: Int,
    properties: DeviceProperties,
    private val manager: DeviceManager,
    // narrow host ports:
    private val gameTime: GameTimeSource,
    private val terminalNetwork: TerminalNetworkBridge,
    private val stateSink: DeviceStateSink,
) : RuntimeDevice { /* ... */ }
```

No `ServerLevel`, no `MinecraftServer`, no `ServerContext` lookups inside this class. All world facts come through the three ports.

### 4.6 `DeviceManager` (was `ComputerManager`)

API stays semantically identical, parameter names and return types updated:

```kotlin
class DeviceManager(private val vmSupervisor: DeviceVmSupervisor) {
    fun get(deviceId: Int): RuntimeDevice?
    fun add(device: RuntimeDevice)
    fun remove(deviceId: Int): RuntimeDevice?
    fun getOrCreateVm(deviceId, profile, labelProvider, logger): BackgroundDeviceVm
    fun removeVm(deviceId, reason)
    fun ensureWorkspaceInitialized(deviceId)
    val workspace: DeviceWorkspace
    val ide: DeviceIdeHost
}
```

The map is keyed by `Int` as today. No `BlockPos` reverse-mapping is added; `TransientPairing` keeps that workaround for now (Phase 2c will revisit it).

### 4.7 Block-side carrier (`:v1_21_1-common`)

`BlockEntityRuntimeDeviceHost` is a small builder that assembles the three ports for a concrete `AbstractComputerBlockEntity`:

```kotlin
class BlockEntityRuntimeDeviceHost(
    private val blockEntity: AbstractComputerBlockEntity,
) {
    val gameTime = GameTimeSource { (blockEntity.level as ServerLevel).gameTime }
    val terminalNetwork = object : TerminalNetworkBridge {
        override fun isPlayerOnline(uuid: UUID) =
            ServerContext.server.playerList.getPlayer(uuid) != null
        override fun sendStdoutBytes(uuid, containerId, bytes) {
            val p = ServerContext.server.playerList.getPlayer(uuid) ?: return
            ServerNetworking.sendToPlayer(StdoutBytesClientMessage(containerId, bytes), p)
        }
    }
    val stateSink = DeviceStateSink { isOn -> blockEntity.updateBlockState(isOn) }
}
```

`ComputerBlockEntity.createComputer(id)` is updated to construct `RuntimeDeviceImpl` with this host:

```kotlin
override fun createComputer(id: Int): RuntimeDevice {
    val host = BlockEntityRuntimeDeviceHost(this)
    return RuntimeDeviceImpl(
        deviceId = id,
        properties = DeviceProperties(family, label),
        manager = ServerContext.deviceManager,
        gameTime = host.gameTime,
        terminalNetwork = host.terminalNetwork,
        stateSink = host.stateSink,
    )
}
```

`AbstractComputerBlockEntity.updateBlockState(isOn: Boolean)` — small refactor: the block layer maps the `Boolean` to the existing `ComputerState` enum and writes the blockstate as today.

### 4.8 ServerContext

Renames:
- `ServerContext.computerManager` → `ServerContext.deviceManager`
- `ServerContext.allocateComputerId()` → `ServerContext.allocateDeviceId()`
- backing `ComputerIdentitySavedData` keeps its current NBT key (`_computerID`) to avoid save migration. Class name and method names rename freely; **NBT key is data-format-stable**.

> Same caution as Phase 2a-bis: any identifier that resolves to a translation-generated method or a persisted save key is left alone unless explicitly handled.

## 5. Out-of-scope artifacts (kept "Computer*")

- `ComputerBlock`, `AbstractComputerBlockEntity`, `ComputerBlockEntity`, `NeoForgeComputerBlockEntity`, `ForgeComputerBlockEntity`
- `AbstractComputerItem`, `ComputerItem`
- `ComputerMenu`, `ComputerScreen`, `ComputerTerminalScreen`
- `ComputerState` (block-property enum)
- `ComputerActionServerMessage`, `KeyEventServerMessage`, `MouseEventServerMessage`, `PasteEventComputerMessage` and other network messages whose payload identifies a "computer" instance
- `NetworkComputerInputGateway`, `ComputerInputDispatcher` (input-routing for the block-resident computer)
- All translation keys (`gui.compukterkraft.tooltip.computer_id`, etc.) and any methods generated from them (`Tooltip.computerId(...)`)
- `ComputerIdentitySavedData` NBT key (`_computerID`)

Justification: those types model the in-game *Computer* block specifically (a concrete game artifact), not the abstract notion of a runtime device. A future `Laptop` will get its own parallel `LaptopBlockEntity`/`LaptopItem`/etc. — the runtime device abstraction is what they will share, not the block-layer types.

## 6. Migration steps (overview, full plan separate)

The implementation plan will split this into ~7 small commits to keep diffs reviewable. High-level beats:

1. Introduce `:core/.../runtime/` package with role interfaces, `RuntimeDevice` umbrella, `PlayerHandle`, and the three port interfaces. **No moves yet.** Build green.
2. Rename the VM-machinery file family inside `:core/.../vm/` (`BackgroundComputerVm` → `BackgroundDeviceVm`, etc.). Pure rename. Build green.
3. Rename `ComputerManager` → `DeviceManager`, `ComputerProperties` → `DeviceProperties`, `ComputerEvents` → `DeviceEvents` *in place* in `:v1_21_1-common`. Build green.
4. Move + rename `ServerComputer` → `RuntimeDeviceImpl` into `:core/.../runtime/`. Replace direct `ServerLevel`/`ServerContext.server` accesses with port calls. Add `BlockEntityRuntimeDeviceHost` in `:v1_21_1-common`. Update `ComputerBlockEntity.createComputer`. Build + tests green.
5. Update `ServerContext` accessors (`computerManager` → `deviceManager`, `allocateComputerId` → `allocateDeviceId`) and propagate.
6. Remove now-unused imports of `net.minecraft.server.level.ServerLevel` from the moved file. Add architecture-test guard that asserts `:core/.../runtime/` files have zero `import net.minecraft.*` lines.
7. Documentation: update `docs/ARCHITECTURE.md` to mention `RuntimeDevice` umbrella, ports, and the `core ↔ host adapter` boundary.

## 7. Risks & rollback

- **Save compatibility**: NBT key `_computerID` stays. `ComputerIdentitySavedData` filename/dat key stays. Verified by archival save-load smoke test.
- **Translation-generated methods**: only one identifier (`Tooltip.computerId`) is in this neighbourhood; documented and out-of-scope per Phase 2a-bis lessons. The audit step before each commit will run `./gradlew test` which exercises the lang-generation smoke test.
- **NeoForge runtime visibility**: no new third-party kotlinx libraries are added, so the registration helpers (`neoForgeImplementation` / `fabricImplementation`) do not need touching.
- **Rollback granularity**: every step is its own commit; reverting any single step leaves the tree in a working state.

## 8. Acceptance criteria

- `:core/.../runtime/RuntimeDevice.kt` defines the role + umbrella interfaces; `:core` does not import `net.minecraft.*`.
- `RuntimeDeviceImpl` compiles in `:core` and depends only on ports + `:core/.../vm/` machinery.
- `BlockEntityRuntimeDeviceHost` exists in `:v1_21_1-common` and is the *only* place where `ServerLevel.gameTime`, `MinecraftServer.playerList`, and the `ComputerState` enum are touched on behalf of a runtime device.
- `ServerComputer`, `ComputerManager`, `ComputerProperties`, `ComputerEvents`, `BackgroundComputerVm`, `ComputerVmSupervisor`, `ComputerVmHandle`, `ComputerVmLogger`, `ComputerProgramSupport`, `ComputerWorkspaceInitializer` no longer exist as identifiers anywhere in the source tree.
- Block / item / menu / screen / network-message layer is untouched (sanity check: `grep -r 'class .*Computer' modules/v1_21_1` still finds the block-layer hits).
- `./gradlew clean test` green on `dev` after merge.
- Architecture-boundary test (existing or added): files under `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/` have zero `net.minecraft.*` imports.
- `docs/ARCHITECTURE.md` updated.

## 9. What this enables (Phase 3 preview)

After this lands, adding a `Laptop` runtime device requires only:
- a new host adapter (e.g., `ItemStackRuntimeDeviceHost`) implementing the three ports differently (game time from server, stdout-bridge same, state-sink writes to the item-stack NBT instead of a blockstate);
- a new `LaptopItem` carrying the `deviceId` in its NBT and constructing `RuntimeDeviceImpl` on use;
- no changes to `:core`.

Phase 2c (`TransientPairing` generalization) then becomes a small follow-up that swaps `BlockPos` for an opaque "device locator" inside that one map.
