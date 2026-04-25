# Workbench Async Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the workbench `Push` / `Pull` model with continuous CRDT-based async sync. Server holds the live
document, client mirrors via op deltas, UI surfaces sync status and a letter-by-letter highlight animation. Phase 1 —
single editor per file. See [2026-04-25-workbench-async-sync-design.md](../specs/2026-04-25-workbench-async-sync-design.md).

**Architecture:**

- Pure CRDT core in `modules/core/.../computer/workbench/crdt/`.
- Pure debouncer in `modules/core/.../computer/workbench/sync/`.
- Three new network messages in `v1_21_1-common/.../workbench/network/{client,server}/`.
- `ServerWorkbench` gains a session-keyed `ConcurrentHashMap<String, ServerCrdtReplica>`.
- `WorkbenchStore` rewires to op-based API; `EditorState` swaps `dirty` for `syncStatus` + cursor-as-`AtomId`.
- UI removes Save / Pull / Push, adds sync indicator and sync-glow highlight.

**Tech Stack:** Kotlin/JVM, junit5 in `:core` tests, `kotlin.test` for `:v1_21_1-common`,
`kotlinx.collections.immutable` for `PersistentList`, `FriendlyByteBuf` for wire encoding.

**Execution note:** Do not create commits unless the user explicitly asks for them.

---

## File Structure

- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/SiteId.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/AtomId.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/TextRun.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/Op.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/CrdtDocument.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/ClientCrdtReplica.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/ServerCrdtReplica.kt`
- New: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/CrdtDocumentTest.kt`
- New: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/crdt/CrdtConvergenceFuzzTest.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/sync/SyncStatus.kt`
- New: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/sync/OpOutbox.kt`
- New: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/sync/OpOutboxTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStore.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchEditorSupport.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchStoreTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchEditorViewModelTest.kt`
- New: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/network/server/WorkbenchOpsServerMessage.kt`
- New: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/network/client/WorkbenchOpsClientMessage.kt`
- New: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/network/client/WorkbenchDocumentSnapshotClientMessage.kt`
- New: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/.../workbench/network/WorkbenchOpsCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/network/server/WorkbenchWorkspaceServerMessage.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/context/ServerWorkbench.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/WorkbenchGateways.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../workbench/ui/WorkbenchUiBuilder.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/.../ui/dsl/elements/CodeEditor.kt` (or wherever
  highlight types live — confirm during Task 6)
- Delete (after migration): legacy WRITE / PULL / PUSH branches in `WorkbenchWorkspaceServerMessage`

---

## Task 1: CRDT primitives — `SiteId`, `AtomId`, `TextRun`, `Op`

**Files:**

- New: `modules/core/.../crdt/SiteId.kt`
- New: `modules/core/.../crdt/AtomId.kt`
- New: `modules/core/.../crdt/TextRun.kt`
- New: `modules/core/.../crdt/Op.kt`

- [ ] **Step 1: Add `kotlinx.collections.immutable` to `:core`**

In [modules/core/build.gradle.kts](modules/core/build.gradle.kts), add the dependency under `commonMain`/`main` deps:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")
```

Verify the version exists in [gradle/libs.versions.toml](gradle/libs.versions.toml); if not, add an entry and reference
the catalog accessor.

- [ ] **Step 2: Implement primitives**

```kotlin
// SiteId.kt
@JvmInline
value class SiteId(val raw: String) {
    init { require(raw.isNotEmpty() && raw.length <= 32) { "SiteId must be 1..32 chars" } }
    companion object {
        val ServerInit = SiteId("s:i")
        fun player(uuid: java.util.UUID): SiteId = SiteId("p:" + uuid.toString().replace("-", "").take(8))
        fun target(computerId: Int): SiteId = SiteId("t:$computerId")
    }
}

// AtomId.kt — data class(site, clock); implements Comparable<AtomId> by (site.raw, clock).

// TextRun.kt — data class(id: AtomId, leftId: AtomId?, text: String, deleted: Boolean).
//   text length must be > 0 unless deleted.

// Op.kt — sealed interface { Insert; Delete }, see spec for fields.
```

- [ ] **Step 3: Run :core tests to confirm compile**

Run: `./gradlew :core:compileKotlin`

Expected: PASS.

---

## Task 2: `CrdtDocument` — apply / flatten / index

**Files:**

- New: `modules/core/.../crdt/CrdtDocument.kt`
- New: `modules/core/.../crdt/CrdtDocumentTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
class CrdtDocumentTest {
    private val site = SiteId("p:test0001")

    @Test
    fun emptyDocumentFlattensToEmptyString() {
        assertEquals("", CrdtDocument.empty().flatten())
    }

    @Test
    fun insertAtStartProducesText() {
        val op = Op.Insert(site, clock = 1, leftId = null, text = "hi")
        assertEquals("hi", CrdtDocument.empty().apply(op).flatten())
    }

    @Test fun insertInMiddleSplitsRun() { /* ... */ }
    @Test fun deleteWholeRunMarksTombstoned() { /* ... */ }
    @Test fun deleteSpanningRunsTombstonesAll() { /* ... */ }
    @Test fun applyTwiceIsIdempotent() { /* ... */ }
    @Test fun tieBreakLargerSitewinsIfSameLeftId() { /* ... */ }
}
```

- [ ] **Step 2: Run :core tests to confirm red**

Run: `./gradlew :core:test --tests "*CrdtDocumentTest*"`

Expected: FAIL (class doesn't exist).

- [ ] **Step 3: Implement `CrdtDocument`**

API from spec:

```kotlin
data class CrdtDocument(
    val runs: PersistentList<TextRun>,
    val clockBySite: PersistentMap<SiteId, Int>,
    val versionVector: PersistentMap<SiteId, Int>,
) {
    private val runIndexById: Map<AtomId, Int> by lazy { runs.withIndex().associate { (i, r) -> r.id to i } }

    fun apply(op: Op): CrdtDocument
    fun flatten(): String
    companion object { fun empty(): CrdtDocument; fun fromText(text: String, site: SiteId): CrdtDocument }
}
```

Implementation notes:

- `apply(Insert)`: find insertion index after `leftId` and any concurrent inserts that lose the tie-break; insert as
  new run; if it falls inside an existing run, split the existing run into left+right halves with stable atoms.
- `apply(Delete)`: walk runs starting from `targetId`, accumulate `length` characters across runs (skipping deleted),
  marking each touched run as tombstoned. If `length` falls inside a run, split first.
- `apply` is a no-op if `op.clock <= clockBySite[op.author]`.
- `flatten()`: concat `text` of non-deleted runs in order.

- [ ] **Step 4: Run :core tests to confirm green**

Run: `./gradlew :core:test --tests "*CrdtDocumentTest*"`

Expected: PASS.

---

## Task 3: Convergence fuzz test

**Files:**

- New: `modules/core/.../crdt/CrdtConvergenceFuzzTest.kt`

- [ ] **Step 1: Write fuzz test**

```kotlin
class CrdtConvergenceFuzzTest {
    @Test
    fun twoReplicasConvergeRegardlessOfOpOrder() {
        val seed = 42L
        val opsA = generateRandomOps(SiteId("p:aaaaaaaa"), count = 500, seed = seed)
        val opsB = generateRandomOps(SiteId("p:bbbbbbbb"), count = 500, seed = seed + 1)

        val docA = (opsA + opsB).fold(CrdtDocument.empty()) { d, op -> d.apply(op) }
        val docB = (opsB.shuffled(Random(seed + 2)) + opsA.shuffled(Random(seed + 3)))
            .fold(CrdtDocument.empty()) { d, op -> d.apply(op) }

        assertEquals(docA.flatten(), docB.flatten())
    }
}
```

- [ ] **Step 2: Run fuzz; iterate on `CrdtDocument` until green**

Run: `./gradlew :core:test --tests "*CrdtConvergenceFuzzTest*"`

Expected: PASS. If FAIL, dump diverging op sequences and fix tie-break / split logic.

---

## Task 4: `ClientCrdtReplica` and `ServerCrdtReplica`

**Files:**

- New: `modules/core/.../crdt/ClientCrdtReplica.kt`
- New: `modules/core/.../crdt/ServerCrdtReplica.kt`

- [ ] **Step 1: Add unit tests for client replica**

Cover: `produceInsert(localOffset, text)` returns Op with correct `leftId` and clock; `produceDelete(localOffset, len)`
likewise; `applyAck(clock)` updates `lastAckedClock`; `relocateCursor(deletedAtomId)` snaps to right neighbour.

- [ ] **Step 2: Implement client replica**

```kotlin
class ClientCrdtReplica(val siteId: SiteId, initial: CrdtDocument) {
    var document: CrdtDocument = initial; private set
    var nextClock: Int = (document.clockBySite[siteId] ?: 0) + 1; private set
    var lastAckedClock: Int = document.clockBySite[siteId] ?: 0; private set

    fun produceInsert(charOffset: Int, text: String): Op.Insert
    fun produceDelete(charOffset: Int, length: Int): Op.Delete
    fun applyLocal(op: Op)         // updates document, advances nextClock
    fun applyRemote(op: Op)        // updates document only
    fun applyAck(clock: Int)
    fun cursorAtOffset(offset: Int): Pair<AtomId, Int>      // (atomId, offsetWithinRun)
    fun relocateCursor(cursor: Pair<AtomId, Int>): Pair<AtomId, Int>
}
```

- [ ] **Step 3: Implement server replica**

```kotlin
class ServerCrdtReplica(initial: CrdtDocument) {
    var document: CrdtDocument = initial; private set

    fun apply(ops: List<Op>): ApplyResult     // ApplyResult(applied: List<Op>, ackedClockBySite: Map<SiteId, Int>)
    fun flatten(): String = document.flatten()
    fun versionVector(): Map<SiteId, Int> = document.versionVector
}
```

Server replica rejects ops whose `leftId` / `targetId` are unknown by surfacing `ApplyResult(applied = ..., rejected =
[...])`; the caller (Task 7) responds with a fresh snapshot to the offending sender.

- [ ] **Step 4: Run :core tests**

Run: `./gradlew :core:test --tests "*Replica*"`

Expected: PASS.

---

## Task 5: `SyncStatus` and `OpOutbox`

**Files:**

- New: `modules/core/.../sync/SyncStatus.kt`
- New: `modules/core/.../sync/OpOutbox.kt`
- New: `modules/core/.../sync/OpOutboxTest.kt`

- [ ] **Step 1: Tests**

```kotlin
class OpOutboxTest {
    @Test fun debouncesEnqueuesIntoSingleSend() { /* virtual time, advance 49ms -> 0 sends, advance 51ms -> 1 send */ }
    @Test fun maxBatchTriggersSyncFlush() { /* enqueue 64 -> immediate send */ }
    @Test fun flushNowEmitsAndResetsPending() { /* pendingCount goes 0 */ }
    @Test fun ackBeyondLastEnqueuedTransitionsToIdle() { /* SyncStatus flow */ }
    @Test fun stalesAfter5sWithoutAck() { /* virtual time */ }
}
```

Use `kotlinx.coroutines.test.runTest` and `TestScope.advanceTimeBy`.

- [ ] **Step 2: Implement**

```kotlin
class OpOutbox(
    private val scope: CoroutineScope,
    private val send: suspend (List<Op>) -> Unit,
    private val debounceMs: Long = 50,
    private val maxBatch: Int = 64,
    private val staleAfterMs: Long = 5_000,
) {
    val status: StateFlow<SyncStatus>
    val pendingCount: StateFlow<Int>
    fun enqueue(op: Op)
    suspend fun flushNow()
    fun onAck(clockOfLastSent: Int, ackedClock: Int)
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :core:test --tests "*OpOutboxTest*"`

Expected: PASS.

---

## Task 6: Wire messages and `FriendlyByteBuf` codecs

**Files:**

- New: `v1_21_1-common/.../workbench/network/server/WorkbenchOpsServerMessage.kt`
- New: `v1_21_1-common/.../workbench/network/client/WorkbenchOpsClientMessage.kt`
- New: `v1_21_1-common/.../workbench/network/client/WorkbenchDocumentSnapshotClientMessage.kt`
- New: `v1_21_1-common/.../workbench/network/WorkbenchOpsCodecTest.kt`

- [ ] **Step 1: Round-trip codec tests**

```kotlin
class WorkbenchOpsCodecTest {
    @Test
    fun opsServerMessageRoundTrip() {
        val msg = WorkbenchOpsServerMessage(containerId = 7, path = "main.lua", ops = listOf(
            Op.Insert(SiteId("p:abcd1234"), 1, leftId = null, text = "hello"),
            Op.Delete(SiteId("p:abcd1234"), 2, AtomId(SiteId("s:i"), 1), 3),
        ))
        val buf = FriendlyByteBuf(Unpooled.buffer())
        msg.encode(buf)
        val back = WorkbenchOpsServerMessage.decode(buf)
        assertEquals(msg, back)
    }
    @Test fun opsClientMessageRoundTrip() { /* ... */ }
    @Test fun snapshotMessageRoundTrip() { /* runs of varying authors and tombstones */ }
}
```

- [ ] **Step 2: Implement messages**

Each message is a Kotlin `data class` with `encode(buf: FriendlyByteBuf)` and a `companion object` `decode`. Encoding
spec:

- `SiteId`: `writeUtf(raw, max = 32)`.
- `AtomId`: `SiteId` + `writeVarInt(clock)`.
- Nullable `AtomId`: `writeBoolean(present)` then payload.
- `Op`: `writeByte(kind: 0 = Insert | 1 = Delete)` then fields.
- `List<Op>`: `writeVarInt(size)` then each.
- `Map<SiteId, Int>`: `writeVarInt(size)` then pairs.
- `List<TextRun>`: `writeVarInt(size)` then each (`AtomId`, nullable `AtomId`, `writeUtf(text)`, `writeBoolean(deleted)`).

- [ ] **Step 3: Run tests**

Run: `./gradlew :v1_21_1-common:test --tests "*WorkbenchOpsCodecTest*"`

Expected: PASS.

- [ ] **Step 4: Register messages with the network registry**

Find existing message registration (alongside `WorkbenchWorkspaceServerMessage`) and add the three new messages. Server
handler stubs (`{ ctx, msg -> TODO("Task 7") }`) — actual logic lands in Task 7.

---

## Task 7: Server integration in `ServerWorkbench`

**Files:**

- Modify: `v1_21_1-common/.../workbench/context/ServerWorkbench.kt`
- Modify: `v1_21_1-common/.../workbench/network/server/WorkbenchWorkspaceServerMessage.kt`

- [ ] **Step 1: Add server-side replica registry**

```kotlin
class ServerWorkbench(/* ... */) {
    private val replicas = ConcurrentHashMap<String, ServerCrdtReplica>()  // key = "$containerId|$path"

    fun openSession(containerId: Int, path: String): WorkbenchDocumentSnapshotClientMessage {
        val replica = replicas.computeIfAbsent("$containerId|$path") {
            ServerCrdtReplica(CrdtDocument.fromText(readDisk(path), SiteId.ServerInit))
        }
        return WorkbenchDocumentSnapshotClientMessage(
            containerId = containerId,
            path = path,
            initialRuns = replica.document.runs,
            versionVector = replica.versionVector(),
        )
    }

    fun handleOps(containerId: Int, path: String, ops: List<Op>, sender: SiteId): WorkbenchOpsClientMessage {
        val replica = replicas["$containerId|$path"] ?: error("session not open")
        val result = replica.apply(ops)
        if (result.rejected.isNotEmpty()) {
            // re-snapshot path; for Phase 1 we just throw and let the client request reopen
        }
        return WorkbenchOpsClientMessage(
            containerId, path,
            ops = emptyList(), // Phase 1 has no other subscribers
            ackedClock = result.ackedClockBySite[sender] ?: 0,
        )
    }

    fun closeSession(containerId: Int, path: String) {
        val replica = replicas.remove("$containerId|$path") ?: return
        writeDisk(path, replica.flatten())
    }
}
```

- [ ] **Step 2: Wire packet handlers**

In the network bootstrap (where existing workbench messages are registered), wire:

- `WorkbenchOpsServerMessage` → call `serverWorkbench.handleOps(...)` and reply with the returned client message.
- A new `WorkbenchOpenSessionServerMessage` (or extend the existing READ flow) to call `openSession` and reply with
  `WorkbenchDocumentSnapshotClientMessage`.

If the existing `READ` action already serves "open file" semantics, repurpose it to also call `openSession`. Confirm
during this step which is cleaner; prefer the fewest new actions.

- [ ] **Step 3: Remove `WRITE` / `PULL` / `PUSH` action branches**

Delete the action-enum entries and their server handlers from
[modules/v1_21_1/v1_21_1-common/.../workbench/network/server/WorkbenchWorkspaceServerMessage.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/server/WorkbenchWorkspaceServerMessage.kt).
Keep `LIST` / `READ` (or rebadged open) / `RUN` / `REBOOT` / `ATTACH_TERMINAL`.

`RUN` handler: before executing, `closeSession` is **not** called (the editor remains open), but the server
materializes via `replica.flatten()` and writes to disk just before launching the script.

- [ ] **Step 4: Compile**

Run: `./gradlew :v1_21_1-common:compileKotlin`

Expected: PASS.

---

## Task 8: Client integration — `WorkbenchStore` rewire

**Files:**

- Modify: `modules/core/.../workbench/WorkbenchStore.kt`
- Modify: `modules/core/.../workbench/WorkbenchEditorSupport.kt`
- Modify: `modules/core/.../workbench/WorkbenchStoreTest.kt`
- Modify: `modules/core/.../workbench/WorkbenchEditorViewModelTest.kt`
- Modify: `v1_21_1-common/.../workbench/WorkbenchGateways.kt`

- [ ] **Step 1: Update `EditorState`**

```kotlin
data class EditorState(
    val text: String,                        // derived view, recomputed on apply
    val cursor: AtomId,                      // current cursor anchor
    val cursorOffsetWithinRun: Int,
    val syncStatus: SyncStatus,
    val pendingOpCount: Int,
    // ...other existing fields except `dirty`
)
```

`(line, column)` becomes a derived property computed from `text` + `flatCursorOffset`.

- [ ] **Step 2: Rewrite `WorkbenchStoreTest`**

Replace these tests:

- `saveClearsDirtyFlag` → delete.
- `pullFromTarget*` → delete.
- `pushToTarget*` → delete.

Add:

```kotlin
@Test fun applyLocalEditAddsToOutbox() { /* ... */ }
@Test fun applyAckClearsPendingCount() { /* ... */ }
@Test fun applyRemoteOpsUpdatesEditorText() { /* ... */ }
@Test fun flushAndRunWaitsForSync() { /* ... */ }
@Test fun cursorMovesWhenRemoteInsertHappensLeft() { /* ... */ }
```

Use a fake gateway exposing `sendOps`.

- [ ] **Step 3: Update `WorkbenchEditorViewModelTest`**

`onCharTyped` should now call `store.applyLocalEdit(LocalEdit.Insert(offset, text))` instead of mutating `text`
directly. Update existing assertions accordingly.

- [ ] **Step 4: Run :core tests; expect red**

Run: `./gradlew :core:test`

Expected: FAIL (compile errors and missing API).

- [ ] **Step 5: Implement new `WorkbenchStore` API**

```kotlin
class WorkbenchStore(/* deps */) {
    private val replica: ClientCrdtReplica = /* initialized on snapshot */
    private val outbox: OpOutbox = /* ... */

    fun applyLocalEdit(edit: LocalEdit) {
        val op = when (edit) {
            is LocalEdit.Insert -> replica.produceInsert(edit.offset, edit.text)
            is LocalEdit.Delete -> replica.produceDelete(edit.offset, edit.length)
        }
        replica.applyLocal(op)
        outbox.enqueue(op)
        // recompute EditorState
    }

    fun applyRemoteOps(ops: List<Op>) {
        ops.forEach { replica.applyRemote(it) }
        // relocate cursor if needed; recompute EditorState
    }

    fun applyAck(ackedClock: Int) {
        replica.applyAck(ackedClock)
        outbox.onAck(replica.nextClock - 1, ackedClock)
    }

    suspend fun flushAndRun(timeoutMs: Long = 3_000L): RunResult { /* outbox.flushNow(); wait until acked */ }

    fun onSnapshot(snapshot: WorkbenchDocumentSnapshot) { /* rebuild replica */ }
}

sealed interface LocalEdit {
    data class Insert(val offset: Int, val text: String) : LocalEdit
    data class Delete(val offset: Int, val length: Int) : LocalEdit
}
```

Remove `pullFromTarget`, `pushToTarget`, public `saveDocument`.

- [ ] **Step 6: Update `WorkbenchGateways`**

Replace `write/pull/push` actions with `sendOps(containerId, path, ops)` and a `sessionOpen(containerId, path)` that
returns the snapshot (or accepts a callback for snapshot delivery — match the existing message-receive idiom).

- [ ] **Step 7: Run :core and :v1_21_1-common tests; iterate to green**

Run: `./gradlew :core:test :v1_21_1-common:test`

Expected: PASS.

---

## Task 9: UI — sync indicator, removed buttons, sync-glow highlight

**Files:**

- Modify: `v1_21_1-common/.../workbench/ui/WorkbenchUiBuilder.kt`
- Modify: `v1_21_1-common/.../ui/dsl/elements/CodeEditor.kt` (or related — confirm in step 1)

- [ ] **Step 1: Identify CodeEditor highlight types**

```bash
grep -rn "CodeEditorHighlight\|HighlightType\|class CodeEditor" modules/v1_21_1/v1_21_1-common/src/main/kotlin/
```

Locate where highlight ranges are declared and rendered. Add a new variant `SyncingRun(start: Int, end: Int, alpha:
Float)`.

- [ ] **Step 2: Render sync glow**

In the rendering path, draw a soft-coloured background under runs in `SyncingRun` ranges. Animate alpha decay from
1.0 to 0.0 over 300ms once an op is acked. The store emits a `Flow<List<SyncingRun>>` derived from in-flight ops.

- [ ] **Step 3: Remove Save / Pull / Push and dirty marker**

In [WorkbenchUiBuilder.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/ui/WorkbenchUiBuilder.kt):

- Delete the three toolbar buttons.
- Replace the `* ` prefix in the status bar with a `syncStatusIndicator(syncStatus, pendingCount)` widget showing
  one of `✓` (Idle) / `⋯` (Pending) / `↻` (Syncing) / `⚠` (Stale), with `pendingCount` next to it when > 0.

- [ ] **Step 4: Smoke-test in dev runtime**

Run: `./gradlew :v1_21_1-neoforge:runClient`

Open workbench, type, observe: indicator transitions Idle → Pending → Syncing → Idle within ~150ms; sync glow appears
under typed letters and fades on ack. Disconnect network (simulate by killing server thread) to confirm Stale state.

---

## Task 10: Integration test and cleanup sweep

**Files:**

- New: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/.../workbench/WorkbenchSyncIntegrationTest.kt`

- [ ] **Step 1: Integration test**

Set up an in-memory `ServerWorkbench` and a `WorkbenchStore` connected by a direct callback gateway. Open file, apply
100 random local edits, call `flushAndRun()`. Assert: server's `replica.flatten()` equals the client's `editorState
.text`; `flushAndRun` completes within 3s; `RUN` was issued only after the last ack.

- [ ] **Step 2: Search and remove dead code**

```bash
grep -rn "pullFromTarget\|pushToTarget\|saveDocument\|dirtyLocal\|dirtyRemote\|WorkbenchAction\.WRITE\|WorkbenchAction\.PULL\|WorkbenchAction\.PUSH" modules/
```

Delete every match that is no longer reachable. Run `./gradlew build` to confirm nothing depends on the removed
symbols.

- [ ] **Step 3: Final test run**

Run: `./gradlew build`

Expected: PASS.

- [ ] **Step 4: Manual smoke**

Re-run the dev client, edit a file, hit RUN. Verify the script sees the latest edits without ever clicking a Save /
Pull / Push button.

---

## Notes

- **Phase 2 deliberately deferred:** multi-player presence, target-as-collaborator (Myers diff), tombstone GC, and
  CRDT disk persistence will land in a separate plan.
- **Performance hotspot to monitor:** if op-apply on large files is sluggish, swap `runs: PersistentList` for a
  skiplist or maintain `Map<AtomId, Int>` more aggressively.
- **No feature flag:** the migration is one PR; old clients fail to connect.
