## Plan: Simplify VM State Ownership & Client-Server Network Boundaries

> **Status: ✅ All 4 phases implemented and verified (build + tests pass)**

The codebase suffers from four core problems: (1) **lambda spaghetti** — `BackgroundComputerVm.createRuntime()` wires ~15 lambdas to connect internal managers; (2) **dual state ownership** — `ServerComputer` and `VmStateManager` both track VM lifecycle, cursor is tracked in both `VmTerminalApi` and `NetworkedTerminal`; (3) **parallel registries** — `ComputerRegistry` and `ComputerVmSupervisor` maintain separate maps for the same computer; (4) **unclear client-server split** — `AbstractComputerMenu` uses nullable fields with runtime exceptions instead of type-safe client/server separation. The plan is organized in 4 phases, each independently shippable.

---

### Phase 1 — Eliminate Lambda Spaghetti in the VM Layer

**Rationale:** `BackgroundComputerVm.createRuntime()` builds each API class by injecting 3–5 lambdas that close over internal managers. This makes it hard to trace data flow and test in isolation. Replace lambdas with explicit interface dependencies.

#### Step 1.1: Extract a `VmContext` interface
Create a new interface [VmContext](mod/src/main/kotlin/ck/mod/computer/vm/VmContext.kt) that bundles VM-internal services currently passed as lambdas:
- `suspend fun receiveEvent(): VmEvent`
- `fun deferEvent(event: VmEvent)`
- `suspend fun setState(state: VmState)`
- `suspend fun setSleepUntil(tick: Long?)`
- `suspend fun schedulingPoint()`
- `suspend fun <T> awaitHostCall(callFactory: (Long) -> HostCall): T`
- `fun resolvePath(path: String): String`

`BackgroundComputerVm` implements `VmContext`. Each Vm*Api class takes `VmContext` instead of individual lambdas.

#### Step 1.2: Refactor [VmSystemApi](mod/src/main/kotlin/ck/mod/computer/vm/VmSystemApi.kt)
Replace 4 lambdas (`currentTickProvider`, `labelProvider`, `eventEnqueuer`, `logger`, `stopper`) with a small `VmSystemContext` interface holding `computerId`, `profile`, and functions for `currentTick`, `label`, `enqueueEvent`, `log`, `stop`. `ServerComputer` already has all this data — make it implement `VmSystemContext` directly.

#### Step 1.3: Refactor [VmTerminalApi](mod/src/main/kotlin/ck/mod/computer/vm/VmTerminalApi.kt)
Replace `hostCallAwaiter`, `eventReceiver`, `eventDeferer`, `cursorUpdater`, `cursorProvider` lambdas with the `VmContext` interface from 1.1. Remove the duplicated `cursorUpdater`/`cursorProvider` lambdas entirely (see Phase 2).

#### Step 1.4: Refactor [VmFileSystemApi](mod/src/main/kotlin/ck/mod/computer/vm/VmFileSystemApi.kt) and [VmProcessApi](mod/src/main/kotlin/ck/mod/computer/vm/VmProcessApi.kt)
`VmFileSystemApi` takes `VmContext` instead of `hostCallAwaiter` + `pathResolver`. `VmProcessApi` takes `VmContext` + `WorkspaceProgramLoader` + `ComputerProgramCompiler` as explicit typed dependencies instead of 8 lambdas.

#### Step 1.5: Simplify [VmRuntime](mod/src/main/kotlin/ck/mod/computer/vm/VmRuntime.kt)
Remove the 5 standalone lambda parameters (`eventReceiver`, `eventDeferer`, `stateSetter`, `sleepSetter`, `schedulingPoint`). `VmRuntime` takes `VmContext` + the Api objects. The `pullEvent`/`sleep`/`yield` methods delegate to `VmContext`.

#### Step 1.6: Simplify [BackgroundComputerVm.createRuntime()](mod/src/main/kotlin/ck/mod/computer/vm/BackgroundComputerVm.kt)
`createRuntime()` becomes a straightforward constructor call: `VmRuntime(profile, VmSystemApi(systemCtx), VmTerminalApi(vmContext), VmFileSystemApi(vmContext), VmProcessApi(...), vmContext)`. No lambdas.

---

### Phase 2 — Consolidate State Ownership

**Rationale:** Terminal cursor position is tracked in both `VmTerminalApi` (VM coroutine) and `NetworkedTerminal` (server main thread). VM lifecycle is tracked in both `VmStateManager` and `ServerComputer.isOn`/`rebootRequested`. The `serverTick()` polls a snapshot and re-derives state that callbacks already provided.

#### Step 2.1: Make the server-side terminal the single cursor authority
Remove `cursorX`/`cursorY` from [VmTerminalApi](mod/src/main/kotlin/ck/mod/computer/vm/VmTerminalApi.kt). The `HostCallDispatcher` already advances the cursor on `NetworkedTerminal` via `TerminalHostWriter`. After a host call completes, the VM-side cursor doesn't need to track position — only the `NetworkedTerminal` should. Remove `cursorUpdater` and `cursorProvider` lambdas.

For `TerminalLineReader`, pass cursor position as `HostCall.TerminalGetCursor` requests (add a new HostCall variant) rather than storing local shadow copies. This eliminates the split-brain cursor state.

#### Step 2.2: Split [VmStateManager](mod/src/main/kotlin/ck/mod/computer/vm/VmStateManager.kt) into two concerns
Create `VmLifecycleState` (owns `state`, `stopReason`, `errorMessage` — the immutable lifecycle progression) and `VmSchedulingState` (owns `currentTick`, `sleepUntilTick`, `sliceDeadlineNanos` — the tick-based scheduling data). This makes lock scope narrower and ownership clearer.

#### Step 2.3: Remove `isOn`/`rebootRequested` from [ServerComputer](mod/src/main/kotlin/ck/mod/computer/ServerComputer.kt)
`ServerComputer.isOn` duplicates `VmStateManager.state != COLD/STOPPED/CRASHED`. Instead, expose a `val isRunning: Boolean` derived from the VM handle's snapshot. Replace the `rebootRequested` flag with a state in `VmLifecycleState` — when `onVmRebootRequested()` fires, it immediately re-queues the boot instead of deferring to the next `serverTick()` poll. Remove the snapshot-polling logic from `serverTick()` and rely solely on callbacks.

#### Step 2.4: Remove the `ComputerVmCallbacks` interface
Currently `ServerComputer` implements `ComputerVmCallbacks` to get `onVmStop`/`onVmRebootRequested`, but `onVmStop` is a no-op and `serverTick()` polls the snapshot anyway. Replace with a sealed `VmLifecycleEvent` that the VM posts to a channel, consumed by `serverTick()`. This is simpler than having both callbacks AND polling.

---

### Phase 3 — Merge Parallel Registries

**Rationale:** `ComputerRegistry` maps instanceID→`ServerComputer`, while `ComputerVmSupervisor` maps computerId→`ComputerVmHandle`. These are parallel maps for the same entity. `ServerComputer` reaches through `ServerContext.vmSupervisor` to manage VM handles, coupling it to the global singleton.

#### Step 3.1: Merge [ComputerRegistry](mod/src/main/kotlin/ck/mod/context/ComputerRegistry.kt) and [ComputerVmSupervisor](mod/src/main/kotlin/ck/mod/computer/vm/ComputerVmSupervisor.kt)
Create a unified `ComputerManager` that owns both the `ServerComputer` and its `ComputerVmHandle`. The `ServerComputer` should not reach into `ServerContext.vmSupervisor` — instead `ComputerManager` exposes methods like `boot(computerId)`, `shutdown(computerId)`, `tick(computerId)`.

#### Step 3.2: Remove `ServerContext` static accessors from [ServerComputer](mod/src/main/kotlin/ck/mod/computer/ServerComputer.kt)
`ServerComputer.turnOn()` currently calls `ServerContext.vmSupervisor.ensureWorkspaceInitialized(...)`, `ServerContext.vmSupervisor.remove(...)`, `ServerContext.vmSupervisor.getOrCreate(...)`. Inject the `ComputerManager` as a constructor dependency instead, removing the static coupling. This also makes `ServerComputer` testable.

#### Step 3.3: Inject `ComputerManager` into [AbstractComputerBlockEntity](mod/src/main/kotlin/ck/mod/block/AbstractComputerBlockEntity.kt)
Replace `ServerContext.registry.getServerComputer(...)` calls with `computerManager.get(...)`. The block entity receives `ComputerManager` through `ServerContext` but via an injected reference, not a static call chain.

---

### Phase 4 — Clarify Client-Server Network Boundary

**Rationale:** `AbstractComputerMenu` mixes client-only state (`terminal: NetworkedTerminal?`) and server-only state (`computer: ServerComputer?`, `input: ServerInputState?`). Methods throw `UnsupportedOperationException` at runtime based on which side. This is error-prone.

#### Step 4.1: Split [AbstractComputerMenu](mod/src/main/kotlin/ck/mod/menu/AbstractComputerMenu.kt) into typed sides
Extract a `ClientComputerMenuState` (owns `terminal: NetworkedTerminal`, `workspaceState`, workspace listeners) and a `ServerComputerMenuState` (owns `computer: ServerComputer`, `input: ServerInputState`). `AbstractComputerMenu` holds a `sealed interface MenuSide { class Client(...) : MenuSide; class Server(...) : MenuSide }`. Replace the nullable fields and runtime exceptions with exhaustive `when` checks.

#### Step 4.2: Make the [ComputerMenu](mod/src/main/kotlin/ck/mod/menu/ComputerMenu.kt) interface side-aware
Split into `ComputerMenuServer` (exposes `computer`, `input`) and `ComputerMenuClient` (exposes `updateTerminal`, `updateWorkspace*`). Network message handlers cast to the correct side-specific interface.

#### Step 4.3: Document the packet protocol
Add a `PROTOCOL.md` or KDoc block in [NetworkMessages](mod/src/main/kotlin/ck/mod/network/NetworkMessages.kt) summarizing:
- **Client → Server (serverbound):** `COMPUTER_ACTION` (turnOn/shutdown/reboot/terminate), `KEY_EVENT`, `MOUSE_EVENT`, `PASTE_EVENT`, `COMPUTER_WORKSPACE_REQUEST`
- **Server → Client (clientbound):** `COMPUTER_TERMINAL` (full terminal snapshot on dirty), `COMPUTER_WORKSPACE` (file listings/documents), `CHAT_TABLE`
- When each packet is sent and what state it modifies

---

### Further Considerations

1. **Phase ordering**: Phase 1 (lambda cleanup) and Phase 2 (state consolidation) can be done in parallel on separate branches. Phase 3 depends on Phase 2. Phase 4 is independent of all others.
2. **`TerminalLineReader` redesign**: The `readLine()` function in [VmRuntimeSupport.kt](mod/src/main/kotlin/ck/mod/computer/vm/VmRuntimeSupport.kt) currently takes 7 lambdas. After Phase 1 it should take `VmContext` + `ComputerTerminalApi`. Should `readLine` be moved to a host-call-based approach (server manages the input buffer) instead of VM-side character-by-character processing? This would simplify the VM but add complexity to `HostCallDispatcher`. **Recommend keeping it VM-side** for now but passing `VmContext` to eliminate lambdas.
3. **Testing strategy**: After Phase 3, `ServerComputer` will be injectable and testable without a running Minecraft server. Consider adding unit tests for VM lifecycle state transitions and host-call dispatching as part of the refactoring.
