# Epics 2 + 3 + 4 — Client-Driven Terminal, Peripheral Item, Cleanup (Implementation Plan)

> **Status (2026-04-24):** Epic 2 delivered (tasks 2.1–2.9). Tasks 2.5 (remove `screenBuffer` from the API surface) and 2.10 (retire `ComputerTerminalClientMessage`) are intentionally deferred to Epic 4 so the byte-stream path can ship alongside the legacy snapshot path without a big-bang cutover. Dual-path today: `BackgroundComputerVm` owns a `ComputerStdioBroadcaster`, feeds an internal consumer that keeps the server-side `ScreenBuffer` alive for existing Workbench/snapshot consumers, and simultaneously fans out raw bytes to attached clients through `StdoutBytesClientMessage`. Clients run their own `VtParser → ScreenBuffer` via `ClientTerminalBuffer`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Prerequisite:** Epic 1 (`docs/superpowers/plans/2026-04-24-terminal-stream-io-epic-1.md`) is complete. VM emits a VT-100 byte stream via `ComputerStdioApi`; server-side `VmStdioApi` currently feeds it back into a ScreenBuffer. That bridge is the thing Epic 2 replaces.

---

## Goals

- **Epic 2 — Network split:** `stdio.writeString(...)` on the VM is **broadcast as raw bytes** to attached clients. Each client runs its own [VtParser](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt) into its own `ScreenBuffer` sized from the client's UI. Multiple clients share a single session (tmux `attach -x` semantics). A scrollback ring buffer captures output while nobody is attached and replays to late joiners.
- **Epic 3 — Terminal as peripheral item:** A new `TerminalItem` (portable, stateless). Shift+RMB on a `ComputerBlock` with the terminal in hand pairs it with that computer for the current session. The `ComputerBlock` loses its built-in "open terminal" behaviour — a plain computer is a **headless** CPU. The `WorkbenchBlock` uses the same client renderer.
- **Epic 4 — Cleanup:** remove `ComputerTerminalApi.screenBuffer` public access; remove the hardcoded 77×27 `Config` defaults; delete the now-unused server-side `ScreenBuffer`; delete `VmTerminalApi`'s remaining cursor-blink hacks.

The original user intent "size from the client, multi-user" is satisfied end of Epic 2. Epics 3 and 4 are polish — Epic 3 makes the item UX match the spec, Epic 4 removes the compat seams Epic 2 leaves behind.

---

## Tech Stack & Conventions

- Kotlin/JVM, Gradle, `kotlin.test` for unit tests; NeoForge 1.21.1.
- Modules touched: `core` (runtime, stdio broadcaster, scrollback), `v1_21_1-common` (network payloads, menus, screens), `v1_21_1-neoforge` (block/item registration, block entity lifecycle).
- Packet registration pattern mirrors existing [ComputerTerminalClientMessage.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/ComputerTerminalClientMessage.kt) — registered in [NetworkMessages.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt).
- Data-component pattern mirrors [ComputerItem.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/item/ComputerItem.kt) — uses `DataComponents.CUSTOM_DATA` wrapper (no custom `DataComponentType` needed in Epic 3: binding is ephemeral per-session).

---

## Epic 2 — Network Stream I/O

### Target architecture

```
VM thread                       Server tick thread               Client
──────────                      ─────────────────                ──────
stdio.writeString(bytes)
  → ComputerStdioBroadcaster
       ├── append to ScrollbackRing (ring of N bytes)
       └── schedule flush
                                ServerComputer.serverTick()
                                  → drain pending bytes
                                  → for each attached session:
                                      StdoutBytesClientMessage(sessionId, bytes)
                                                                 → netHandler
                                                                 → session.rxBytes(bytes)
                                                                 → VtParser.feed(bytes)
                                                                 → ClientScreenBuffer mutates
                                                                 → screen re-renders
```

Key structural changes:

- Replace `VmStdioApi` (server-side VtParser into ScreenBuffer) with `ComputerStdioBroadcaster` (byte-level fan-out with ring buffer).
- Replace per-computer `ScreenBuffer` with a per-session client-side buffer. The **server no longer maintains a canonical grid** — only the byte stream and a cursor tracker used by `TerminalLineReader`.
- Add an "attached session" concept (`TerminalSession`) keyed independently from the container id. A session is opened when a `Terminal` item client opens the UI, carries `(cols, rows)` chosen by that client, and survives container churn.

---

### File Structure

**Create:**

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScrollbackRing.kt` — fixed-capacity byte ring buffer (`append(chunk)`, `snapshotBytes(): ByteArray`).
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/CursorTracker.kt` — cheap `VtSink` implementation that tracks cursor `(x, y)` on an unbounded abstract grid; used by `TerminalLineReader` to replace `screenBuffer.cursorX/Y`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ComputerStdioBroadcaster.kt` — new `ComputerStdioApi` impl with: scrollback, cursor tracker, list of attached consumers (`addConsumer` / `removeConsumer` / `drainPendingTo(consumer)`).
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/TerminalSession.kt` — server-side session record: `sessionId`, `cols`, `rows`, `playerUuid`, `pendingBytes`.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScrollbackRingTest.kt`.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/CursorTrackerTest.kt`.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ComputerStdioBroadcasterTest.kt`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/StdoutBytesClientMessage.kt` — server → client byte chunks.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/AttachTerminalServerMessage.kt` — client → server: "open a session of (cols, rows) on computer X". Carries `sessionId` (random UUID from client), computer instanceID, dimensions.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DetachTerminalServerMessage.kt` — session close.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ResizeTerminalServerMessage.kt` — client changed window size.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientTerminalBuffer.kt` — client-side `ScreenBuffer` + `VtParser` wrapper; exposes current snapshot for rendering.
- `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/StdoutBytesClientMessageTest.kt` — round-trip serialization.

**Modify:**

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt` — instantiate `ComputerStdioBroadcaster` instead of `VmStdioApi`; drop the owned `ScreenBuffer`; expose `attachSession(session)` / `detachSession(sessionId)` / `drainStdoutTo(session)`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt` — remove `screenBuffer` dependency; `readLine` uses `CursorTracker` for cursor queries, no cursor blink on server.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt` — **remove** `screenBuffer: ScreenBuffer` from `ComputerTerminalApi` (breaking change; needed for headless), or mark it `@Deprecated` for Epic 4 removal.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt` — replace `syncScreen` with `flushStdout`: pulls pending bytes from the broadcaster per attached session and sends `StdoutBytesClientMessage`. Remove `screenSnapshot` field.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt` — drop `clientSide.screenSnapshot`; menus hold a `ClientTerminalBuffer` instance that network messages feed into.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` — render from `ClientTerminalBuffer` snapshot; compute (cols, rows) from this screen's `imageWidth/imageHeight` on open and publish via `AttachTerminalServerMessage`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt` — register the three new server-bound messages and the one new client-bound message; retire `ComputerTerminalClientMessage` (Epic 4 deletion).

---

### Task 2.1 — `ScrollbackRing`

- [ ] **Test first:** `ScrollbackRingTest` covers: FIFO when under capacity, oldest drops when overflowing, `snapshotBytes()` returns data in correct temporal order, concurrent append is safe under `synchronized`.
- [ ] **Impl:** fixed-size `ByteArray`, `writePos` and `size`, synchronized on `this`. Capacity `64 * 1024` default (constructor param).
- [ ] **Verify:** `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.ScrollbackRingTest`
- [ ] **Commit:** `feat(stdio): scrollback ring buffer`

### Task 2.2 — `CursorTracker`

- [ ] **Test first:** feed through a `VtParser(CursorTracker)`: `"Hi"` → cursor at `(2, 0)`; `"Hi\n"` → `(0, 1)`; `"\u001B[5;3H"` → `(2, 4)` (1-based → 0-based); backspace decrements X; CR resets X to 0.
- [ ] **Impl:** `class CursorTracker : VtSink` with `var cursorX`, `var cursorY`, and a save/restore pair. Unbounded — no clamping. Colors / erase / SGR are ignored (no-ops).
- [ ] **Verify:** `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.CursorTrackerTest`
- [ ] **Commit:** `feat(stdio): CursorTracker VtSink for server-side line reader`

### Task 2.3 — `ComputerStdioBroadcaster` (server)

- [ ] **Test first:** `ComputerStdioBroadcasterTest`
  - `writeString` appends to scrollback.
  - `addConsumer` returns existing scrollback bytes first (replay), then receives subsequent writes.
  - `removeConsumer` stops delivery.
  - Two consumers both receive the same stream (shared session).
  - `writeString` also updates internal `CursorTracker` accessible via `cursor(): Pair<Int, Int>`.
- [ ] **Impl:**
  ```kotlin
  class ComputerStdioBroadcaster(
      scrollbackBytes: Int = 64 * 1024,
  ) : ComputerStdioApi {
      private val ring = ScrollbackRing(scrollbackBytes)
      private val cursorTracker = CursorTracker()
      private val cursorParser = VtParser(cursorTracker)
      private val consumers = CopyOnWriteArrayList<Consumer>()
      // Consumer: a callback + per-consumer pending byte queue if you prefer pull-based
      override fun writeString(text: String) {
          val bytes = text.toByteArray(Charsets.UTF_8)
          ring.append(bytes)
          cursorParser.feed(text)
          for (c in consumers) c.enqueue(bytes)
      }
      fun cursor(): Pair<Int, Int> = cursorTracker.cursorX to cursorTracker.cursorY
      fun addConsumer(c: Consumer) { c.enqueue(ring.snapshotBytes()); consumers += c }
      fun removeConsumer(c: Consumer) { consumers -= c }
  }
  ```
- [ ] **Verify:** broadcaster test suite passes.
- [ ] **Commit:** `feat(stdio): broadcaster with shared-session fan-out`

### Task 2.4 — Wire broadcaster into VM + drop server `ScreenBuffer`

- [ ] **Read:** [BackgroundComputerVm.kt#L100-L300](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt).
- [ ] **Modify:** replace `private val screenBuffer = ScreenBuffer(...)` with `private val stdioBroadcaster = ComputerStdioBroadcaster(...)`. Replace `VmStdioApi` construction with the broadcaster directly. `VmTerminalApi`'s constructor loses `screenBuffer`; readLine uses `stdioBroadcaster.cursor()` via a lightweight `CursorSource` abstraction.
- [ ] **Modify:** `readScreenSnapshot()` / `forceScreenSnapshot()` on `ComputerVmHandle` are **removed** (breaking; their callers are in `ServerComputer.syncScreen` which we rewrite in Task 2.6).
- [ ] **Fix compile:** delete `VmStdioApi.kt` and `ScreenBufferVtSink.kt` from Epic 1 once nothing references them (they served only as a bridge). Update the Epic 1 `VmStdioApiTest` / `ScreenBufferVtSinkTest` — delete them.
- [ ] **Fix `VmTerminalApi`:** remove `screenBuffer` override (needs `ComputerTerminalApi.screenBuffer` to go first — see Task 2.5).
- [ ] **Verify:** `./gradlew :core:compileKotlin` succeeds.

### Task 2.5 — Remove `screenBuffer` from `ComputerTerminalApi`

- [ ] **Read:** [ComputerRuntime.kt](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt).
- [ ] **Modify:** remove the `val screenBuffer: ScreenBuffer` line from `ComputerTerminalApi`. Update `VmTerminalApi` to drop the `override`. Update the `RecordingRuntime` test fixture in `LanguageRuntimeTest.kt` accordingly.
- [ ] **Verify:** `./gradlew :core:test :compiler:test` green.
- [ ] **Commit:** `refactor(runtime): ComputerTerminalApi no longer exposes ScreenBuffer`

### Task 2.6 — New network packets

- [ ] **Test first:** write `StdoutBytesClientMessageTest` — serialize a payload carrying `(sessionId = UUID, bytes = ByteArray)` → deserialize → equal. Same for `AttachTerminalServerMessage` (`sessionId`, `computerInstanceId: Int`, `cols: Int`, `rows: Int`), `DetachTerminalServerMessage` (just `sessionId`), `ResizeTerminalServerMessage` (`sessionId`, new `cols`, `rows`).
- [ ] **Impl:** follow [KeyEventServerMessage.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/KeyEventServerMessage.kt) pattern. Register ids in [NetworkMessages.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt).
- [ ] **Verify:** new serialization tests pass.
- [ ] **Commit:** `feat(net): stdout byte stream + attach/detach/resize messages`

### Task 2.7 — Server-side session table on `ServerComputer`

- [ ] **Read:** [ServerComputer.kt#L140-L200](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt).
- [ ] **Modify:**
  - Delete `screenSnapshot: MutableStateFlow<ScreenBufferSnapshot?>` field and `syncScreen()` method.
  - Add `private val sessions = ConcurrentHashMap<UUID, TerminalSession>()`.
  - New public methods: `attachSession(session)`, `detachSession(sessionId)`, `resizeSession(sessionId, cols, rows)`. Each registers / unregisters a consumer on the broadcaster.
  - In `serverTick()`: iterate sessions, poll pending bytes per session, send `StdoutBytesClientMessage(sessionId, bytes)`. Skip empty queues.
- [ ] **Session creation/lookup path:** `AttachTerminalServerMessage.handle` calls `ComputerManager.findByInstanceId(instanceId)?.attachSession(session)`. Permission check: the sending player must have the bound terminal item in hand OR be the block owner (Epic 3 concern — stub with "always allow" for Epic 2, revisit in Epic 3 Task 3.5).
- [ ] **Verify:** compile-time — tests wait for Task 2.8.
- [ ] **Commit (after 2.8):** combined.

### Task 2.8 — Client-side buffer + screen rendering

- [ ] **Impl `ClientTerminalBuffer`:** owns `ScreenBuffer(cols, rows)` + `VtParser` over `ScreenBufferVtSink(screenBuffer)` (note: this file **stays** — it's moved from server to client in Task 2.4; update its package if it makes sense, keep in `core`).
  - Method `applyStdoutBytes(bytes: ByteArray)` decodes UTF-8 and feeds the parser.
  - Method `snapshot(): ScreenBufferSnapshot` for the screen to read.
- [ ] **Modify `ComputerMenu.ClientSide`:** replace `screenSnapshot: ScreenBufferSnapshot?` with `terminalBuffer: ClientTerminalBuffer?`. Setter call-site: `handleStdoutBytes(containerId, sessionId, bytes)` routes to the currently open menu's buffer.
- [ ] **Modify `ComputerTerminalScreen`:**
  - On `init`, compute `(cols, rows)` from `imageWidth / 8` and `imageHeight / 10` (using existing `WorkbenchTerminalMetrics`); instantiate a `ClientTerminalBuffer(cols, rows)`; send `AttachTerminalServerMessage` with a freshly generated `UUID.randomUUID()` as `sessionId`.
  - On `onClose` / `removed`, send `DetachTerminalServerMessage(sessionId)`.
  - Rendering reads from `terminalBuffer.snapshot()`.
- [ ] **Handler:** `StdoutBytesClientMessage.handle` finds the active menu (by matching `sessionId` against the client's open `ComputerTerminalScreen.sessionId`) and calls `applyStdoutBytes(bytes)`.
- [ ] **Verify:** `./gradlew :v1_21_1-neoforge:runClient` — manually place a computer, open the terminal, verify BIOS boots and shell output appears. Two Minecraft clients on the same LAN world open the same computer simultaneously; both see the same output (shared session).
- [ ] **Commit:** `feat(terminal): client-driven size + shared session streaming`

### Task 2.9 — Resize on client UI change

- [ ] **Modify `ComputerTerminalScreen`:** when `init()` runs a second time (e.g., window resize) with different `(cols, rows)`, re-instantiate the `ClientTerminalBuffer` with new dims **and** send `ResizeTerminalServerMessage(sessionId, cols, rows)`. On server, `TerminalSession` updates dimensions and the client will replay the full scrollback on next render cycle — so the server does not need to re-send anything special; but it **must** clear `cursorTracker` alignment assumptions if any. Verify empirically.
- [ ] **Commit:** `feat(terminal): client resize updates server session dims`

### Task 2.10 — Retire `ComputerTerminalClientMessage` path

- [ ] **Modify `ServerComputer`:** remove all references to the old message (should be gone already after 2.7; this step is a grep-and-verify).
- [ ] **Modify `NetworkMessages.kt`:** unregister the old id; leave the class file for one commit then delete in a follow-up commit (avoids network-wire compat issues for existing save testing).
- [ ] **Verify:** `./gradlew :v1_21_1-neoforge:runClient` still works.
- [ ] **Commit:** `refactor(net): remove legacy ComputerTerminalClientMessage`

### Epic 2 exit criteria

- `./gradlew :core:test :compiler:test :v1_21_1-neoforge:test` all green.
- Manual: two clients simultaneously attached see identical output; resizing the GUI window causes server to fan out future bytes at the same rate and client re-renders using its own buffer size; closing the UI and reopening later shows the scrollback (up to N bytes).
- No references to `ScreenBufferSnapshot` on the server outside of the VT parser's sink (client uses it; server does not).

---

## Epic 3 — Terminal as a Peripheral Item

### Target UX

- Craft a **Terminal** item. Stateless; no NBT. The player holds the item, Shift+RMB a `ComputerBlock` — the terminal UI opens bound to that computer's `instanceID`.
- If the player opens the terminal UI **not** from a Shift+RMB (e.g., RMB in the air with the item), a "last bound computer" is remembered per-player in **session memory only** (server-side `TransientPairing` map, cleared on logout); Shift+RMB on air re-opens the last-bound UI; RMB on air does nothing if no last-bound.
- `ComputerBlock` RMB (without terminal item) now does **nothing** (or future: LED/peripheral poke). It no longer opens the terminal menu.
- `WorkbenchBlock` continues to open its own menu but reuses the Epic 2 `ClientTerminalBuffer` + `ComputerTerminalScreen` rendering.

### Scope caveats

- Persisted pairing is a non-goal (per spec: ephemeral). Terminal item holds no NBT component.
- Inter-dimensional / long-distance access: **radius check** enforced on `AttachTerminalServerMessage`; configurable via a new `Config.TERMINAL_CONNECT_RADIUS_BLOCKS` (default 32). Out-of-range attach rejects with a chat message.

### File Structure

**Create:**

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/item/TerminalItem.kt` — `Item` subclass. `use()` handles air-RMB (reopen last bound). `useOn()` handles block interaction (Shift check + ComputerBlock check + attach).
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/session/TransientPairing.kt` — server-only registry `ConcurrentHashMap<UUID playerUuid, TerminalBinding>`. `TerminalBinding(computerInstanceId: Int, dimensionId: ResourceKey<Level>, pairedAt: Long)`. Cleared on `PlayerLoggedOutEvent`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/menu/TerminalItemMenu.kt` — a `Menu` without an inventory slot; holds client-side `ClientTerminalBuffer`, reuses the Epic 2 rendering through a refactored common base.
- `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/terminal/TerminalItemRegistration.kt` — NeoForge registration for the item + menu type.
- `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/terminal/session/TransientPairingTest.kt`.

**Modify:**

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlock.kt` — remove the "open menu on use" behaviour. `use()` / `useWithoutItem()` / `useItemOn()` return `InteractionResult.PASS` when the player doesn't hold a `TerminalItem`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt` — `attachSession()` now takes the player + dimension; enforce `Config.TERMINAL_CONNECT_RADIUS_BLOCKS` radius and same-dimension check.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/Config.kt` — add `TERMINAL_CONNECT_RADIUS_BLOCKS: Int = 32`.

### Task 3.1 — `TerminalItem` + registration

- [ ] Create `TerminalItem` with `useOn(context)` that short-circuits when not-sneaking. When sneaking and `context.blockState.block is ComputerBlock`, look up the block entity, get `instanceId`, call `openTerminalForPlayer(player, instanceId, computerPos, dimension)`. Set `TransientPairing` on the server.
- [ ] Register item via NeoForge's `DeferredRegister<Item>`; add to creative tab.
- [ ] **Manual smoke test:** craft/`/give` the terminal, Shift+RMB a computer block → terminal UI opens; computer's BIOS streams in.
- [ ] **Commit:** `feat(terminal-item): portable terminal opens UI on shift+RMB`

### Task 3.2 — `TransientPairing` server registry

- [ ] **Test first:** `TransientPairingTest` — set pairing, look up, clear on logout simulation.
- [ ] **Impl:** small class with `set(uuid, binding)`, `get(uuid)`, `clear(uuid)`. Hook `PlayerLoggedOutEvent` via NeoForge event bus to auto-clear.
- [ ] **Integrate:** `TerminalItem.use()` (air-RMB) reads pairing; if present, open menu; if absent, no-op.
- [ ] **Commit:** `feat(terminal-item): session-memory pairing`

### Task 3.3 — Headless ComputerBlock

- [ ] Strip menu-opening from `ComputerBlock.use*()`. Leave placement / break behaviour unchanged.
- [ ] Delete `ComputerMenuWithoutInventory` + its menu type registration **once** `TerminalItemMenu` is the sole entry point (defer final deletion to Epic 4 Task 4.3).
- [ ] **Manual test:** plain RMB on a computer block does nothing. Place a terminal, Shift+RMB → works.
- [ ] **Commit:** `refactor(computer-block): headless — no built-in terminal UI`

### Task 3.4 — Workbench reuses new renderer

- [ ] Review [WorkbenchTerminalClientMessage.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/client/WorkbenchTerminalClientMessage.kt) and remove. Workbench opens a `TerminalItemMenu`-alike but bound to the workbench's "detached target" concept; rendering path is identical to Task 2.8.
- [ ] Tests: existing `WorkbenchTerminalViewStateTest` should still pass after migrating to the stream-based buffer.
- [ ] **Commit:** `refactor(workbench): reuse stream-I/O terminal renderer`

### Task 3.5 — Permission + radius check

- [ ] **Test first:** server-side attach with a player further than `TERMINAL_CONNECT_RADIUS_BLOCKS` from the target computer position → attach is rejected (send a `ChatComponent` "out of range" back to the player; no session created).
- [ ] **Impl:** in `ServerComputer.attachSession`, compare `player.position.distanceToSqr(computerPos)` to `radius^2`. Cross-dimension → reject.
- [ ] **Commit:** `feat(terminal-item): enforce connect radius`

### Epic 3 exit criteria

- Placing a `ComputerBlock` + plain RMB: nothing opens.
- Terminal item Shift+RMB on the block: UI opens; computer streams in.
- Air-RMB with a terminal while bound: UI reopens; while unbound: no-op.
- Two players both binding to the same computer see the same shared session.
- Walking 32+ blocks away and trying to reopen: rejected.

---

## Epic 4 — Cleanup

### Task 4.1 — Remove hardcoded 77×27

- [ ] `Config.DEFAULT_COMPUTER_TERM_WIDTH` and `DEFAULT_COMPUTER_TERM_HEIGHT` — **delete**. Grep for remaining references.
- [ ] `ComputerProfileRegistry` no longer constructs a ScreenBuffer; replace `terminalWidth/terminalHeight` fields on `ComputerProfile` with a single `initialScrollbackBytes` (default 64 KiB).
- [ ] `WorkbenchTerminalMetrics` — width/height come from the open screen's GUI size, not a constant.
- [ ] **Commit:** `cleanup: remove hardcoded terminal dimensions`

### Task 4.2 — Delete dead code from the Epic 2/3 compat seams

- [ ] Delete `VmStdioApi.kt` + test (was a bridge; broadcaster replaced it).
- [ ] Delete `ScreenBufferVtSink.kt` from `core` if the client package took ownership; if the file is shared, keep it in `core` and delete the duplicate.
- [ ] Delete `ComputerTerminalClientMessage.kt` and its test.
- [ ] Delete `ComputerMenuWithoutInventory.kt` if no longer referenced.
- [ ] **Commit:** `cleanup: remove Epic 2 compat seams`

### Task 4.3 — `VmTerminalApi` honest to its shape

- [ ] `VmTerminalApi` no longer takes `screenBuffer`. `readLine` uses `CursorTracker` via `stdio.cursorSource`. Cursor-blink was a ScreenBuffer thing; implement it as a VT escape emitted once at the start of `readLine` and once at the end (client-side parser sets the `cursorBlink` bit on its own buffer). Define a minor extension: `CSI ? 25 h` / `CSI ? 25 l` (DECTCEM) for cursor visibility — the client's `VtParser` needs a new branch.
- [ ] **Test first:** extend `VtParserTest` with the DECTCEM sequences → sink receives `setCursorVisible(true/false)`. Extend `VtSink` with `setCursorVisible(Boolean)`. Default impl in server's `CursorTracker` ignores it; client's `ScreenBufferVtSink` maps to `screenBuffer.setCursorBlink(...)`.
- [ ] **Commit:** `refactor(readline): cursor blink via VT DECTCEM`

### Task 4.4 — Final sweep

- [ ] `grep -rn "screenBuffer" modules/` — verify only client-side code and the (now renamed) sink.
- [ ] `./gradlew :core:test :compiler:test :v1_21_1-neoforge:test` green.
- [ ] Manual in-game: no regressions.
- [ ] **Commit:** `docs: mark Epics 2+3+4 as complete`

### Epic 4 exit criteria

- `Config` contains no hardcoded terminal size.
- Server-side owns no `ScreenBuffer` at all.
- `ComputerTerminalApi` is a pure stream I/O surface.
- Code grep shows only client-side code importing `ScreenBuffer`.

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Per-tick byte broadcast floods network on spammy programs | Scrollback ring tees writes; per-session `pendingBytes` is drained once per tick with a hard cap (e.g., 8 KB/tick/session); overflow causes a one-time `CLEAR` resync rather than dropping bytes silently |
| Client resize races with in-flight bytes | Resize message on server is idempotent; if a byte chunk is already queued for the session, the client applies it to the newly sized buffer — the `VtParser` clamping in `ScreenBufferVtSink` already handles "cursor out of bounds" cases |
| `TransientPairing` leaking across reloads | Clear the map on `ServerStoppingEvent` and `PlayerLoggedOutEvent`; never persist to NBT |
| Two players on opposite sides of the world share a session → grief | `TERMINAL_CONNECT_RADIUS_BLOCKS` enforcement (Task 3.5) is per-attach, not continuous; walking out of range does not detach but stops future attach attempts (good enough for Epic 3) |
| Cursor-blink on server-side `readLine` is lost | Task 4.3 replaces it with `VtParser`'s DECTCEM branch; client-only concern thereafter |
| Save/load of scrollback | Not persisted. Documented as intentional — on world reload, the scrollback ring starts empty. Long-running programs that print a banner on boot (e.g., `bios.ck`) will re-print it on next attach anyway |

---

## Non-Goals (explicitly out of scope)

- Bidirectional stdin (client → server input as a byte stream). Keys continue to flow as structured `ComputerEvents`. A future Epic 5 can unify this into a `stdin` byte stream if `term.readByte()` becomes useful.
- Persisted pairing / named terminals / terminal labels.
- Color terminal renegotiation (client with mono display attached to a color session). All terminals are currently colour-capable.
- Over-the-network VM mobility (moving a computer to another dimension / server).

---

## Execution notes

- This plan spans three epics. Prefer **Subagent-Driven** execution (one subagent per task with a review break between tasks) — inline execution will accumulate too much context over the 20+ tasks.
- Tasks within an epic are strictly ordered; tasks across epics are also ordered (Epic 2 → 3 → 4). Do not parallelize epics.
- After each commit, re-run `./gradlew :core:test :compiler:test` as a guardrail. Run the full `:v1_21_1-neoforge:test` suite at epic boundaries and before declaring an epic complete.
