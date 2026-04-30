# Phase 2b — Runtime Device Decoupling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a platform-neutral `RuntimeDevice` role family in `:core`, decouple `ServerComputer` from `BlockEntity`/`ServerLevel` by extracting world dependencies into narrow ports, move the implementation into `:core`, and rename the residual `Computer*` VM/manager/properties/events identifiers to `Device*`.

**Architecture:** Composition of role interfaces (`RuntimeDeviceLifecycle`, `…Input`, `…Screen`, `…TerminalSessions`, `…Metadata`) plus an umbrella `RuntimeDevice`. World capabilities are exposed via three narrow ports: `GameTimeSource`, `TerminalNetworkBridge`, `DeviceStateSink`. The block-side carrier (`BlockEntityRuntimeDeviceHost`) assembles these ports for an `AbstractComputerBlockEntity`. Block / item / menu / screen / network-message layer keeps `Computer*` names — they model the in-game *Computer block* specifically.

**Tech Stack:** Kotlin/Gradle multi-module, Architectury Loom, Minecraft 1.21.1. Test command: `./gradlew test --no-daemon`.

**Spec:** [docs/superpowers/specs/2026-05-01-runtime-device-decoupling-design.md](../specs/2026-05-01-runtime-device-decoupling-design.md)

**Pre-flight (run once before Task 1):**

- [ ] Verify clean tree, on branch `phase2b-runtime-device-decoupling`, in worktree `.worktrees/phase2b-runtime-device-decoupling`. Command: `git status -s && git rev-parse --abbrev-ref HEAD`. Expected: empty status, branch matches.
- [ ] Verify build is green at start. Command: `./gradlew test --no-daemon`. Expected: `BUILD SUCCESSFUL`.

---

## Naming map (single source of truth for all renames)

| Old | New | Module / Path |
|---|---|---|
| `ComputerVmLogger` | `DeviceVmLogger` | `:core` `vm/BackgroundComputerVm.kt` (top-level fun interface) |
| `BackgroundComputerVm` | `BackgroundDeviceVm` | `:core` `vm/BackgroundComputerVm.kt` (class) + filename |
| `ComputerVmSupervisor` | `DeviceVmSupervisor` | `:core` `vm/ComputerVmSupervisor.kt` (class) + filename |
| `ComputerWorkspaceInitializer` | `DeviceWorkspaceInitializer` | `:core` `vm/ComputerWorkspaceInitializer.kt` + filename |
| `ComputerProgramSupport` | `DeviceProgramSupport` | `:core` `runtime/ComputerProgramSupport.kt` + filename |
| `ComputerEvents` | `DeviceEvents` | `:core` `computer/ComputerEvents.kt` + filename |
| `ComputerProperties` | `DeviceProperties` | `:core` `computer/ComputerProperties.kt` + filename |
| `ComputerManager` | `DeviceManager` | move from `:v1_21_1-common` `context/ComputerManager.kt` to `:core` `runtime/DeviceManager.kt` |
| `ServerComputer` | `RuntimeDeviceImpl` | move from `:v1_21_1-common` `context/ServerComputer.kt` to `:core` `runtime/RuntimeDeviceImpl.kt` |
| `ServerContext.computerManager` | `ServerContext.deviceManager` | `:v1_21_1-common` `context/ServerContext.kt` |
| `ServerContext.allocateComputerId()` | `ServerContext.allocateDeviceId()` | `:v1_21_1-common` `context/ServerContext.kt` |

**Out of scope (must remain `Computer*` after this plan):**
- `ComputerBlock`, `AbstractComputerBlockEntity`, `ComputerBlockEntity`, `NeoForgeComputerBlockEntity`, `ForgeComputerBlockEntity`
- `AbstractComputerItem`, `ComputerItem`
- `ComputerMenu`, `ComputerScreen`, `ComputerTerminalScreen`
- `ComputerState` (block-property enum)
- `ComputerActionServerMessage`, `KeyEventServerMessage`, `MouseEventServerMessage`, `PasteEventComputerMessage`
- `NetworkComputerInputGateway`, `ComputerInputDispatcher`
- All translation keys (`gui.compukterkraft.tooltip.computer_id`, etc.) and any methods generated from them (`Tooltip.computerId(...)`)
- `ComputerIdentitySavedData` NBT key (`_computerID`); the **class itself** also keeps its name (it persists "computer ID" terminology in the save format)
- Filename `core/computer/` directory path (kept to avoid noisy fs moves)

---

### Task 1: Introduce role interfaces, umbrella, ports, and `PlayerHandle`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/RuntimeDevice.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/PlayerHandle.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ports/GameTimeSource.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ports/TerminalNetworkBridge.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ports/DeviceStateSink.kt`

These are pure additions; no existing code is modified yet, so the build stays green.

- [ ] **Step 1: Create `PlayerHandle.kt`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime

import java.util.UUID

/** Platform-neutral handle to a player, used by runtime devices for access checks
 *  without depending on net.minecraft.* types. */
interface PlayerHandle {
    val uuid: UUID
    val isStillValid: Boolean
}
```

- [ ] **Step 2: Create `ports/GameTimeSource.kt`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime.ports

/** Supplies the current server game time (in ticks) to a runtime device. */
fun interface GameTimeSource {
    fun gameTime(): Long
}
```

- [ ] **Step 3: Create `ports/TerminalNetworkBridge.kt`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime.ports

import java.util.UUID

/** Bridges per-player stdout byte streams from a runtime device to the network layer. */
interface TerminalNetworkBridge {
    /** True if the player is currently connected to the server. */
    fun isPlayerOnline(playerUuid: UUID): Boolean

    /** Send raw stdout bytes to the player; no-op if the player is offline. */
    fun sendStdoutBytes(playerUuid: UUID, containerId: Int, bytes: ByteArray)
}
```

- [ ] **Step 4: Create `ports/DeviceStateSink.kt`**

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime.ports

/** Sink notified when a runtime device's on/off power state changes.
 *  Block-side carriers translate this to their blockstate property; other carriers
 *  (e.g. item-resident devices) may persist it differently or ignore it. */
fun interface DeviceStateSink {
    fun onPowerStateChanged(isOn: Boolean)
}
```

- [ ] **Step 5: Create `RuntimeDevice.kt`** with role interfaces and the umbrella

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime

import java.util.UUID
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
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

/** Metadata role: family/label, access checks. */
interface RuntimeDeviceMetadata {
    val family: DeviceFamily
    var label: String?
    fun checkUsable(player: PlayerHandle): Boolean
}

/** Umbrella — every present-day runtime device implements every role.
 *  Future minimal carriers (e.g. Pocket without terminal sessions) may
 *  implement only a subset; the umbrella is then narrowed accordingly. */
interface RuntimeDevice :
    RuntimeDeviceLifecycle,
    RuntimeDeviceInput,
    RuntimeDeviceScreen,
    RuntimeDeviceTerminalSessions,
    RuntimeDeviceMetadata
```

> If `import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot` doesn't resolve, locate the type: `grep -rn "class ScreenBufferSnapshot\|data class ScreenBufferSnapshot" modules/`. Use the full FQN of whichever package owns it.

- [ ] **Step 6: Compile**

Run: `./gradlew :core:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`. The new files compile in isolation.

- [ ] **Step 7: Run full test suite**

Run: `./gradlew test --no-daemon`
Expected: `BUILD SUCCESSFUL`, all existing tests still green (no behavior change yet).

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/
git commit -m "feat(core): introduce RuntimeDevice role interfaces and host ports"
```

---

### Task 2: Rename VM-machinery file family in `:core/.../vm/`

Pure rename: `BackgroundComputerVm` → `BackgroundDeviceVm`, `ComputerVmLogger` → `DeviceVmLogger`, `ComputerVmSupervisor` → `DeviceVmSupervisor`, `ComputerWorkspaceInitializer` → `DeviceWorkspaceInitializer`. Filenames follow the type names.

**Files renamed (git mv):**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt` → `BackgroundDeviceVm.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerVmSupervisor.kt` → `DeviceVmSupervisor.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerWorkspaceInitializer.kt` → `DeviceWorkspaceInitializer.kt`

**Test files renamed:**
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt` → `BackgroundDeviceVmTest.kt`
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundComputerVmTest.kt` → `BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Rename files**

```bash
cd modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm
git mv BackgroundComputerVm.kt BackgroundDeviceVm.kt
git mv ComputerVmSupervisor.kt DeviceVmSupervisor.kt
git mv ComputerWorkspaceInitializer.kt DeviceWorkspaceInitializer.kt
cd -
git mv modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt \
       modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundDeviceVmTest.kt
git mv modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundComputerVmTest.kt \
       modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt
```

- [ ] **Step 2: Mass-replace identifiers across the source tree**

Order matters — `BackgroundComputerVm` and `BackgroundComputerVmTest` overlap; do the longer one first. Whole-word boundaries protect us from partial matches.

```bash
# Whole-word replacements — order: longest match first
rg -l '\bBackgroundComputerVmTest\b' modules/ \
  | xargs -r sed -i 's/\bBackgroundComputerVmTest\b/BackgroundDeviceVmTest/g'
rg -l '\bBackgroundComputerVm\b' modules/ \
  | xargs -r sed -i 's/\bBackgroundComputerVm\b/BackgroundDeviceVm/g'
rg -l '\bComputerVmSupervisor\b' modules/ \
  | xargs -r sed -i 's/\bComputerVmSupervisor\b/DeviceVmSupervisor/g'
rg -l '\bComputerVmLogger\b' modules/ \
  | xargs -r sed -i 's/\bComputerVmLogger\b/DeviceVmLogger/g'
rg -l '\bComputerWorkspaceInitializer\b' modules/ \
  | xargs -r sed -i 's/\bComputerWorkspaceInitializer\b/DeviceWorkspaceInitializer/g'
```

- [ ] **Step 3: Verify no leftovers of the renamed identifiers**

```bash
rg -n '\b(BackgroundComputerVm|ComputerVmSupervisor|ComputerVmLogger|ComputerWorkspaceInitializer)\b' modules/ docs/
```
Expected: zero hits in `modules/`. Hits inside `docs/superpowers/` (specs and historical plans) are fine — they are historical records.

- [ ] **Step 4: Compile + tests**

Run: `./gradlew test --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add modules/
git commit -m "refactor: rename ComputerVm machinery to DeviceVm (BackgroundDeviceVm, DeviceVmSupervisor, DeviceVmLogger, DeviceWorkspaceInitializer)"
```

---

### Task 3: Rename `ComputerProgramSupport` → `DeviceProgramSupport`, `ComputerEvents` → `DeviceEvents`, `ComputerProperties` → `DeviceProperties`

These three live in `:core` already.

**Files renamed:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupport.kt` → `DeviceProgramSupport.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/ComputerEvents.kt` → `DeviceEvents.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/ComputerProperties.kt` → `DeviceProperties.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt` → `DeviceProgramSupportTest.kt`
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/ComputerProgramSupportTest.kt` → `DeviceProgramSupportTest.kt`

- [ ] **Step 1: Rename files**

```bash
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupport.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceProgramSupport.kt
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/ComputerEvents.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/DeviceEvents.kt
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/ComputerProperties.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/DeviceProperties.kt
git mv modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt \
       modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceProgramSupportTest.kt
git mv modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/ComputerProgramSupportTest.kt \
       modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/DeviceProgramSupportTest.kt
```

- [ ] **Step 2: Mass-replace identifiers**

Order: `ComputerProgramSupportTest` → first (longest match), then the rest.

```bash
rg -l '\bComputerProgramSupportTest\b' modules/ \
  | xargs -r sed -i 's/\bComputerProgramSupportTest\b/DeviceProgramSupportTest/g'
rg -l '\bComputerProgramSupport\b' modules/ \
  | xargs -r sed -i 's/\bComputerProgramSupport\b/DeviceProgramSupport/g'
rg -l '\bComputerEvents\b' modules/ \
  | xargs -r sed -i 's/\bComputerEvents\b/DeviceEvents/g'
rg -l '\bComputerProperties\b' modules/ \
  | xargs -r sed -i 's/\bComputerProperties\b/DeviceProperties/g'
```

- [ ] **Step 3: Verify no leftovers**

```bash
rg -n '\b(ComputerProgramSupport|ComputerEvents|ComputerProperties)\b' modules/
```
Expected: zero hits.

- [ ] **Step 4: Compile + tests**

Run: `./gradlew test --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add modules/
git commit -m "refactor: rename ComputerEvents/ComputerProperties/ComputerProgramSupport to Device*"
```

---

### Task 4: Rename `ComputerManager` → `DeviceManager` and **move** to `:core/.../runtime/`

This is one of the two heavy moves. We `git mv` the file across modules, change the package declaration, then mass-replace the identifier and update import sites.

**Files:**
- Move + rename: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerManager.kt` → `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceManager.kt`

- [ ] **Step 1: Verify `:core` already has access to all transitive types `ComputerManager` uses**

Read the current file: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerManager.kt`. Confirm imports are limited to `:core` types (`DeviceVmSupervisor`, `BackgroundDeviceVm`, `DeviceProfile`, `VmStopReason`, `DeviceVmLogger`, `DeviceWorkspace`, `DeviceIdeHost`) and JDK types. The historical context inventory showed exactly these — no `net.minecraft.*` imports — but verify before moving.

> If unexpected `net.minecraft.*` or `:v1_21_1-common` imports are found, **stop** and surface the discrepancy. The spec assumed clean dependencies.

- [ ] **Step 2: Move the file**

```bash
git mv modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerManager.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceManager.kt
```

- [ ] **Step 3: Update package declaration and class name in the moved file**

In `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/DeviceManager.kt`:
- Change `package ru.lazyhat.compukterkraft.common.computer.context` → `package ru.lazyhat.compukterkraft.core.computer.runtime`
- Change `class ComputerManager(` → `class DeviceManager(`
- Change every internal self-reference (e.g. companion factory if any) from `ComputerManager` to `DeviceManager`
- Update field/parameter type `ServerComputer` to `RuntimeDevice` (Task 5 will provide the impl; the umbrella interface is enough here). Specifically, the public API in the spec is:
  - `fun get(deviceId: Int): RuntimeDevice?`
  - `fun add(device: RuntimeDevice)`
  - `fun remove(deviceId: Int): RuntimeDevice?`

  This means the move depends on Task 5's interface. Resolve by **at this step using `RuntimeDevice` from `:core/.../runtime/`** — Task 1 already created it.

- [ ] **Step 4: Mass-replace `ComputerManager` → `DeviceManager` everywhere else**

```bash
rg -l '\bComputerManager\b' modules/ \
  | xargs -r sed -i 's/\bComputerManager\b/DeviceManager/g'
```

- [ ] **Step 5: Update `ServerContext` accessor name**

In `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerContext.kt`:

Find the line `val computerManager = ComputerManager(vmSupervisor)` and change to `val deviceManager = DeviceManager(vmSupervisor)`.

Find the companion: `val computerManager: ComputerManager = context().computerManager` → `val deviceManager: DeviceManager = context().deviceManager`.

Then propagate:
```bash
rg -l '\bcomputerManager\b' modules/ \
  | xargs -r sed -i 's/\bcomputerManager\b/deviceManager/g'
```

- [ ] **Step 6: Adjust call sites that used `ServerComputer` typed return**

`ServerComputer` is renamed in Task 5, but Task 4 already changes `DeviceManager.get()` to return `RuntimeDevice?`. Concretely: any caller doing `manager.get(id)?.someServerComputerOnlyMethod()` will break.

Audit:
```bash
rg -n '\b(ServerContext\.deviceManager|deviceManager)\b' modules/ --include='*.kt' \
  | rg -v '/test/'
```

For each hit, confirm the call uses only `RuntimeDevice` interface methods (turnOn/shutdown/reboot/close/serverTick/queueEvent/lastScreenSnapshot/attach…/detach…/resize…/family/label/checkUsable/isOn/deviceId). If a caller uses something like a public field unique to `ServerComputer`, surface it — Task 5's interface design must accommodate it.

> Likely hits and fixes:
> - `AbstractComputerBlockEntity.kt`: stores `serverComputer: ServerComputer?` as a field — leave the type annotation as `ServerComputer` for now; Task 5 renames it.
> - `WorkbenchBlockEntity.kt` / `ServerInputState.kt`: read `lastScreenSnapshot` and `queueEvent` — both are on the `RuntimeDevice` interface ✓.

- [ ] **Step 7: Verify imports**

`DeviceManager` moved across module boundaries. Any file that previously did `import ru.lazyhat.compukterkraft.common.computer.context.ComputerManager` is now broken. Run:

```bash
rg -n 'import ru\.lazyhat\.compukterkraft\.common\.computer\.context\.ComputerManager' modules/
```
Expected: zero hits (sed already rewrote `ComputerManager` → `DeviceManager`, but the package path is still wrong). Now fix the package:
```bash
rg -l 'ru\.lazyhat\.compukterkraft\.common\.computer\.context\.DeviceManager' modules/ \
  | xargs -r sed -i 's|ru\.lazyhat\.compukterkraft\.common\.computer\.context\.DeviceManager|ru.lazyhat.compukterkraft.core.computer.runtime.DeviceManager|g'
```

- [ ] **Step 8: Compile + tests**

Run: `./gradlew test --no-daemon`
Expected: `BUILD SUCCESSFUL`.

> If compilation fails on `:v1_21_1-common` for unresolved references in `ServerComputer.kt` (since it still uses the old `ComputerManager` name through internal `this.serverComputer.something` paths), the issue should already be sed-resolved. If it isn't, stop and inspect.

- [ ] **Step 9: Commit**

```bash
git add modules/
git commit -m "refactor: move and rename ComputerManager to :core DeviceManager"
```

---

### Task 5: Move and rename `ServerComputer` → `RuntimeDeviceImpl`, decouple from `ServerLevel`/`ServerContext`

This is the heart of the phase. We move `ServerComputer` into `:core/.../runtime/`, replace its world dependencies with the three ports from Task 1, and rename it to `RuntimeDeviceImpl`. We also create `BlockEntityRuntimeDeviceHost` in `:v1_21_1-common` that supplies the ports and update `ComputerBlockEntity.createComputer(id)`.

**Files:**
- Move + rename: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt` → `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/RuntimeDeviceImpl.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerContext.kt` (accessor names, allocateDeviceId)
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt` (createComputer signature/body)
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/AbstractComputerBlockEntity.kt` (typing of `serverComputer` field, new `updateBlockState(isOn: Boolean)` overload)

**Approach:** make changes on a single working tree, compile incrementally per substep, but only commit at the end of the task.

- [ ] **Step 1: Read the current `ServerComputer.kt` end-to-end**

Capture every reference to `level: ServerLevel`, `ServerContext.server`, `level.gameTime`, and any other world-side access. Inventory expected from the architecture report:
1. `level.gameTime` at the slice-request site (`serverTick`)
2. `ServerContext.server.playerList.getPlayer(uuid)` in `flushTerminalSessions`
3. `ServerNetworking.sendToPlayer(message, player)` for stdout bytes
4. `ServerContext.computerManager.…` (now `deviceManager`) for VM lifecycle calls
5. The block-state change notification path (whatever currently observes `isOn` transitions)

If any **other** world-side reference is found, stop and surface it before continuing.

- [ ] **Step 2: Move the file**

```bash
git mv modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/RuntimeDeviceImpl.kt
```

- [ ] **Step 3: Edit the moved file — header**

Change:
- `package ru.lazyhat.compukterkraft.common.computer.context` → `package ru.lazyhat.compukterkraft.core.computer.runtime`
- `class ServerComputer(` → `class RuntimeDeviceImpl(`
- Add the umbrella interface: `class RuntimeDeviceImpl(...) : RuntimeDevice {`
- Remove any `: ComputerEvents.Receiver` if present — `RuntimeDeviceInput` already covers `queueEvent`. (If `ComputerEvents.Receiver` is referenced from outside, leave the explicit conformance and rely on Task 3's rename to `DeviceEvents.Receiver`.)

- [ ] **Step 4: Edit the moved file — constructor**

Replace:
```kotlin
class ServerComputer(
    val instanceID: Int,
    val level: ServerLevel,
    val properties: ComputerProperties,
)
```
with:
```kotlin
class RuntimeDeviceImpl(
    override val deviceId: Int,
    private val properties: DeviceProperties,
    private val manager: DeviceManager,
    private val gameTime: GameTimeSource,
    private val terminalNetwork: TerminalNetworkBridge,
    private val stateSink: DeviceStateSink,
) : RuntimeDevice {
```

- [ ] **Step 5: Edit the moved file — replace world calls with port calls**

| Old call | New call |
|---|---|
| `level.gameTime` | `gameTime.gameTime()` |
| `ServerContext.server.playerList.getPlayer(uuid) != null` | `terminalNetwork.isPlayerOnline(uuid)` |
| `val player = ServerContext.server.playerList.getPlayer(uuid) ?: return; ServerNetworking.sendToPlayer(StdoutBytesClientMessage(containerId, bytes), player)` | `terminalNetwork.sendStdoutBytes(uuid, containerId, bytes)` |
| `ServerContext.computerManager.…` / `ServerContext.deviceManager.…` | `manager.…` |

The state-change notification (whatever previously called `level.setBlock(...)` or required the BlockEntity to react to state flips) becomes `stateSink.onPowerStateChanged(isOn)` invoked at the same point in the lifecycle.

Add overrides required by `RuntimeDevice`:
- `override val deviceId: Int` — already in primary constructor.
- `override val isOn: Boolean` — keep the existing computed property (derived from VM handle).
- `override val family: DeviceFamily get() = properties.family`
- `override var label: String? get() = properties.label; set(v) { properties = properties.copy(label = v); /* persist via existing mechanism */ }`

  > If `DeviceProperties` is currently a `class` with `var label`, keep that pattern. If it's a `data class` with `val label`, the existing `updateLabel` method's body still works — just expose it through the `var label` setter in the override.

- `override fun checkUsable(player: PlayerHandle): Boolean` — change the body to use `player.uuid` / `player.isStillValid` instead of the old `Player` parameter. The previous distance-check / removal-check ported as-is from `properties.family.checkUsable(...)` should now accept `PlayerHandle` — see Step 6 for the upstream change.

- [ ] **Step 6: Update `DeviceFamily.checkUsable` signature (if it currently takes `net.minecraft.world.entity.player.Player`)**

Check `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/DeviceFamily.kt`:

```bash
rg -n 'checkUsable' modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/DeviceFamily.kt
```

If it takes `Player`, change to `PlayerHandle`. Update callers (the in-game block-entity `mayInteractWith` site) to wrap the vanilla `Player` in a `PlayerHandle` — a small adapter in `:v1_21_1-common`:

```kotlin
// In :v1_21_1-common, e.g. in the block-entity package or a util file
internal fun net.minecraft.world.entity.player.Player.toRuntimeHandle(): PlayerHandle =
    object : PlayerHandle {
        override val uuid: UUID = this@toRuntimeHandle.uuid
        override val isStillValid: Boolean = !this@toRuntimeHandle.isRemoved
    }
```

> If `DeviceFamily.checkUsable` doesn't currently exist or doesn't take a `Player`, skip this substep — `RuntimeDeviceMetadata.checkUsable` becomes a thin pass-through that doesn't need this adapter.

- [ ] **Step 7: Mass-rename `ServerComputer` → `RuntimeDeviceImpl` everywhere**

```bash
rg -l '\bServerComputer\b' modules/ \
  | xargs -r sed -i 's/\bServerComputer\b/RuntimeDeviceImpl/g'
```

Then audit field/local types: many call sites do `var serverComputer: ServerComputer? = null`. After sed they read `var serverComputer: RuntimeDeviceImpl? = null`. That's *technically* fine but the field name is now mismatched with the type. Fix the field-name mismatches in `AbstractComputerBlockEntity` to `runtimeDevice: RuntimeDevice?` (use the interface, not the impl) and propagate via:

```bash
rg -l '\bserverComputer\b' modules/ \
  | xargs -r sed -i 's/\bserverComputer\b/runtimeDevice/g'
```

Then change the type of the field from `RuntimeDeviceImpl?` to `RuntimeDevice?` in `AbstractComputerBlockEntity.kt` manually (read the file, find `var runtimeDevice: RuntimeDeviceImpl?`, replace with `var runtimeDevice: RuntimeDevice?`).

- [ ] **Step 8: Update `ServerContext.kt`**

In `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerContext.kt`:
- Rename method `allocateComputerId(): Int` → `allocateDeviceId(): Int` (the inner call to `ComputerIdentitySavedData.get(server).allocateComputerId()` keeps its old name — that's the persisted format).
- Update internal usages: 
  ```bash
  rg -l '\ballocateComputerId\b' modules/ \
    | xargs -r sed -i 's/\ballocateComputerId(\(\))\?/allocateDeviceId()/g'
  ```
  Then verify the method *inside* `ComputerIdentitySavedData.kt` is **not** renamed:
  ```bash
  rg -n 'fun allocateComputerId' modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ComputerIdentitySavedData.kt
  ```
  Expected: one hit (the persisted-format method). If sed touched it, manually revert.

- [ ] **Step 9: Create `BlockEntityRuntimeDeviceHost.kt`**

```kotlin
// modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt
package ru.lazyhat.compukterkraft.common.computer.context

import java.util.UUID
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.network.message.client.StdoutBytesClientMessage
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.DeviceStateSink
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.GameTimeSource
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.TerminalNetworkBridge

/** Bundles the world-side ports a runtime device needs while it lives inside a block entity. */
class BlockEntityRuntimeDeviceHost(
    private val blockEntity: AbstractComputerBlockEntity,
) {
    val gameTime = GameTimeSource {
        (blockEntity.level as ServerLevel).gameTime
    }

    val terminalNetwork: TerminalNetworkBridge = object : TerminalNetworkBridge {
        override fun isPlayerOnline(playerUuid: UUID): Boolean =
            ServerContext.server.playerList.getPlayer(playerUuid) != null

        override fun sendStdoutBytes(playerUuid: UUID, containerId: Int, bytes: ByteArray) {
            val player = ServerContext.server.playerList.getPlayer(playerUuid) ?: return
            ServerNetworking.sendToPlayer(StdoutBytesClientMessage(containerId, bytes), player)
        }
    }

    val stateSink = DeviceStateSink { isOn ->
        blockEntity.updateBlockState(isOn)
    }
}
```

> Verify `StdoutBytesClientMessage` and `ServerNetworking.sendToPlayer` exist at those FQNs (they were the call sites in the old `ServerComputer.flushTerminalSessions`). If the package/path differs, copy from the previous `ServerComputer.kt` content (now `RuntimeDeviceImpl.kt`) before any edits.

- [ ] **Step 10: Update `AbstractComputerBlockEntity.kt`**

Add (or refactor existing) the method `updateBlockState(isOn: Boolean)` so it accepts a boolean and maps it to the existing `ComputerState` enum. Concretely: the previous code path had a call site where `RuntimeDeviceImpl` was setting block state via `level`. That site is now replaced by `stateSink.onPowerStateChanged(isOn)`. The block entity needs to expose `updateBlockState(isOn: Boolean)` publicly enough to be called from `BlockEntityRuntimeDeviceHost` (same package — `internal` is fine, but `internal` won't cross modules; here both are `:v1_21_1-common`, so package-private `internal` works).

Read the current `AbstractComputerBlockEntity.kt` and `ComputerBlockEntity.kt` to find the existing `updateBlockState` (if any). Add the boolean wrapper if needed:

```kotlin
internal fun updateBlockState(isOn: Boolean) {
    val newState = if (isOn) ComputerState.ON else ComputerState.OFF
    // existing body that writes the block property — copy from the prior implementation
    val lvl = level ?: return
    lvl.setBlock(blockPos, blockState.setValue(ComputerBlock.state, newState), Block.UPDATE_CLIENTS)
}
```

> Source for the body: it's the old `ComputerBlockEntity.updateBlockState()` from the architecture report. Adapt as needed.

- [ ] **Step 11: Update `ComputerBlockEntity.createComputer(id)`**

Replace the body with:

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

`createComputer`'s declared return type in the abstract parent (`AbstractComputerBlockEntity`) must change from `ServerComputer` (now sed'd to `RuntimeDeviceImpl`) to `RuntimeDevice` to align with the field type from Step 7. Update the abstract declaration:

```kotlin
abstract fun createComputer(id: Int): RuntimeDevice
```

- [ ] **Step 12: Compile + tests, iteratively**

Run: `./gradlew :core:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :v1_21_1-common:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL. Address compile errors one by one. The most likely remaining issues:
- An import line still pointing at `ru.lazyhat.compukterkraft.common.computer.context.ServerComputer` or `…ComputerProperties` — fix the package path.
- A test stub that constructs `ServerComputer(id, level, …)` directly — update to the new constructor with stubbed ports (`GameTimeSource { 0L }`, a `TerminalNetworkBridge` no-op, `DeviceStateSink {}`).

Run: `./gradlew test --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 13: Commit**

```bash
git add modules/
git commit -m "refactor: move and rename ServerComputer to :core RuntimeDeviceImpl with host ports"
```

---

### Task 6: Architecture-test guard — `:core/.../runtime/` has zero `net.minecraft.*` imports

A regression-protection mechanical test. Place it in `:core` test source.

**Files:**
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/RuntimePackagePurityTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package ru.lazyhat.compukterkraft.core.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Asserts that the :core/.../computer/runtime/ package is platform-neutral —
 *  it must never import net.minecraft.* . If this test breaks, you've leaked
 *  a Minecraft-bound type into shared substrate. Decouple it through a port. */
class RuntimePackagePurityTest {

    @Test
    fun runtimePackageHasNoMinecraftImports() {
        val root = locateRuntimePackage()
        val violations = root.walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) -> line.trimStart().startsWith("import net.minecraft.") }
                    .map { (idx, line) -> "${file.relativeTo(root)}:${idx + 1}: $line" }
            }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Found net.minecraft.* imports in :core/.../runtime/:\n${violations.joinToString("\n")}",
        )
    }

    private fun locateRuntimePackage(): File {
        // Walk up from the test working directory until we find the module root.
        var dir = File(".").canonicalFile
        repeat(6) {
            val candidate = File(
                dir,
                "modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime",
            )
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate :core runtime package from ${File(".").canonicalPath}")
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.architecture.RuntimePackagePurityTest' --no-daemon`
Expected: PASS.

- [ ] **Step 3: Negative-control check**

Manually grep first to confirm the test's invariant:
```bash
rg -n '^\s*import net\.minecraft\.' modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/
```
Expected: zero hits.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/RuntimePackagePurityTest.kt
git commit -m "test(core): guard :core runtime package against net.minecraft.* leaks"
```

---

### Task 7: Update `docs/ARCHITECTURE.md`

**Files:**
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Read the current architecture doc**

Look for the section that describes `:core` responsibilities and the runtime substrate row (added in Phase 2a-bis it mentions `DeviceWorkspace`, `DeviceIdeHost`).

- [ ] **Step 2: Add `RuntimeDevice` umbrella + ports to that section**

Append a paragraph like:

```markdown
- **Runtime device abstraction (`core/computer/runtime/`).** `RuntimeDevice` is the
  composition of role interfaces (`Lifecycle`, `Input`, `Screen`, `TerminalSessions`,
  `Metadata`) implemented by `RuntimeDeviceImpl`. World-side dependencies are
  injected through three narrow ports: `GameTimeSource`, `TerminalNetworkBridge`,
  and `DeviceStateSink`. The block-side carrier
  (`v1_21_1-common/.../BlockEntityRuntimeDeviceHost`) bundles those ports for an
  `AbstractComputerBlockEntity`; future Laptop/Pocket carriers will provide their
  own bundles without changing `:core`.
- **Manager (`DeviceManager`)** lives in `:core` and is keyed by `Int deviceId`.
  Identity persistence stays in `:v1_21_1-common`'s `ComputerIdentitySavedData`
  (NBT key `_computerID` is part of the save format and must remain).
```

Update the lang.runtime row (or table) to include `RuntimeDevice`, `RuntimeDeviceImpl`, `DeviceManager`, the three ports, and `PlayerHandle`.

- [ ] **Step 3: Verify markdown renders sensibly**

Run: `git diff docs/ARCHITECTURE.md`. Sanity-check formatting.

- [ ] **Step 4: Commit**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs(architecture): document RuntimeDevice umbrella, host ports, and DeviceManager move"
```

---

## Final verification

- [ ] **Final 1: Full clean build**

```bash
./gradlew clean test --no-daemon
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Final 2: Sanity — out-of-scope artifacts still `Computer*`**

```bash
rg -l 'class\s+(Abstract)?Computer(Block|BlockEntity|Item|Menu|Screen|TerminalScreen|State|ActionServerMessage|InputDispatcher)' modules/v1_21_1/
```
Expected: non-empty (block / item / menu / screen / network classes still exist with `Computer*` names).

- [ ] **Final 3: Sanity — in-scope artifacts gone**

```bash
rg -n '\b(ServerComputer|ComputerManager|ComputerProperties|ComputerEvents|BackgroundComputerVm|ComputerVmSupervisor|ComputerVmLogger|ComputerProgramSupport|ComputerWorkspaceInitializer)\b' modules/
```
Expected: zero hits in `modules/` (historical hits in `docs/` are fine).

- [ ] **Final 4: Translation-method audit**

```bash
rg -n '\bTooltip\.computerId\b|allocateComputerId\b' modules/
```
Expected hits:
- `AbstractComputerItem.kt` (or wherever it lives) — `Tooltip.computerId(...)` — out-of-scope, intact.
- `ComputerIdentitySavedData.kt` — `fun allocateComputerId()` — out-of-scope (persisted-format method), intact.
- `CompukterLangGenerationSmokeTest.kt` — `Tooltip.computerId("42")` — out-of-scope, intact.

If anything else surfaces, surface it before merging.

- [ ] **Final 5: NBT key intact**

```bash
rg -n '_computerID' modules/
```
Expected: at least one hit in `ComputerIdentitySavedData.kt` (and possibly in `AbstractComputerBlockEntity.kt` for tag read/write). All intact.

- [ ] **Final 6: Architecture-test green**

```bash
./gradlew :core:test --tests 'ru.lazyhat.compukterkraft.core.architecture.RuntimePackagePurityTest' --no-daemon
```
Expected: PASS.

- [ ] **Final 7: Commit count check**

```bash
git log --oneline dev..HEAD
```
Expected: 7 commits on `phase2b-runtime-device-decoupling` (one per task).

---

## Handoff

After all tasks pass, branch is ready to merge into `dev` via `git merge --no-ff phase2b-runtime-device-decoupling`. The worktree may then be removed with `git worktree remove .worktrees/phase2b-runtime-device-decoupling`.
