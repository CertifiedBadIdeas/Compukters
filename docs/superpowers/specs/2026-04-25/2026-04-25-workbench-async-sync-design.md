# Workbench Async Sync Design

## Goal

Replace the workbench editor's manual `Push` / `Pull` model with continuous asynchronous CRDT-based synchronization
between the player's editor and the target computer's filesystem. The server holds the authoritative live document,
the client mirrors it, and operation deltas flow in both directions debounced to minimize network load. The UI shows a
sync-status indicator and a letter-by-letter highlight animation as remote ops apply.

This spec covers **Phase 1** only: a single editing player per file, with the script-running computer as a passive
collaborator. Phase 2 (multi-player presence, script as live collaborator) is sketched at the end but has its own
design.

## Current Context

- `WorkbenchStore` (`modules/core/.../workbench/WorkbenchStore.kt`) holds the client-side editor state and exposes
  `pullFromTarget()`, `pushToTarget()`, and `saveDocument()` as user-triggered actions.
- `EditorState` carries a `dirty: Boolean` flag and `(line, column)` cursor coordinates.
- `WorkbenchUiBuilder` renders Save / Pull / Push toolbar buttons and a `* ` dirty marker in the status bar.
- Network: `WorkbenchWorkspaceServerMessage` carries action enum (`LIST` / `READ` / `WRITE` / `PULL` / `PUSH` / `RUN` /
  `REBOOT` / `ATTACH_TERMINAL`).
- Server: `ServerWorkbench` (`v1_21_1-common/.../workbench/context/ServerWorkbench.kt`) stores documents in
  `ComputerWorkspace`, backed by `ComputerWorkspaceHost` against the computer's filesystem. There is no live in-memory
  document — every `READ` re-reads from disk, every `WRITE` re-writes to disk.
- `RUN` on the server reads files from disk, so unsaved client edits are lost.
- All sync today is user-triggered: there are no background timers.

## Design

### Source Of Truth

The authoritative document lives **in memory on the server** as a CRDT (`ServerCrdtReplica`) for as long as at least one
editor session is open against it. On disk we keep the same plain text file as today; the CRDT is built on session
open and flattened back to plain text when the last editor closes the file. No CRDT state persists on disk.

This trade is intentional: it means we never deal with disk-format migrations, GC of tombstones across restarts, or
recovery semantics. The cost is a re-atomize on every open, which for typical script files (≤ tens of KB) is
negligible.

### CRDT Model: RGA-with-runs

We use Replicated Growable Array (RGA), a well-known list CRDT, with the optimization of grouping consecutive
characters from the same author into a `TextRun` atom. Runs are split when a remote op inserts or deletes inside them.

```kotlin
@JvmInline
value class SiteId(val raw: String)            // "s:i" | "p:<8charUuid>" | "t:<computerId>"

data class AtomId(val site: SiteId, val clock: Int)

data class TextRun(
    val id: AtomId,
    val leftId: AtomId?,        // null = inserted at document start
    val text: String,
    val deleted: Boolean,        // tombstone
)

sealed interface Op {
    val author: SiteId
    val clock: Int

    data class Insert(
        override val author: SiteId,
        override val clock: Int,
        val leftId: AtomId?,
        val text: String,
    ) : Op

    data class Delete(
        override val author: SiteId,
        override val clock: Int,
        val targetId: AtomId,
        val length: Int,         // characters to delete starting at targetId
    ) : Op
}
```

Site categories:

- `s:i` — server-init replica, used to atomize the file when first loaded from disk.
- `p:<uuid8>` — a player editor session.
- `t:<computerId>` — the target computer (Phase 2; reserved now).

**Tie-break for the same `leftId`.** When two inserts share `leftId`, the one with the lexicographically larger
`(author.raw, clock)` pair is placed closer to `leftId` (i.e. inserted first when scanning left-to-right). This is
total, deterministic, and depends only on op identity.

**Convergence invariants** (property tests must verify):

- Commutativity of non-conflicting ops.
- Idempotency: applying the same op twice is a no-op.
- Eventual consistency: any two replicas that have observed the same set of ops produce the same `flatten()`.

### Client/Server Replicas

```kotlin
class CrdtDocument {
    val runs: PersistentList<TextRun>           // kotlinx.collections.immutable
    val clockBySite: Map<SiteId, Int>           // last clock seen per site
    val versionVector: Map<SiteId, Int>         // for snapshot bootstrap
    private val runIndexById: Map<AtomId, Int>  // mutated on apply, kept in sync

    fun apply(op: Op): CrdtDocument
    fun flatten(): String
}

class ClientCrdtReplica(siteId: SiteId, initial: CrdtDocument)
class ServerCrdtReplica(initial: CrdtDocument)
```

`ClientCrdtReplica` owns the next-clock counter for its site, produces ops for local edits, and tracks acked-clock to
gate `flushAndRun`. `ServerCrdtReplica` validates op causality (`leftId` and `targetId` must be known), applies, and
broadcasts ack.

`PersistentList<TextRun>` was chosen for `runs` because we need a list that supports cheap structural sharing under
high-frequency edits. `pods4k` immutable arrays are reserved for primitive-keyed ancillary structures (e.g. version
vectors) where boxing avoidance pays off; we are not using them for `runs` in Phase 1.

### Wire Protocol

Three new messages, replacing `WRITE` / `PULL` / `PUSH`:

```kotlin
// C → S
class WorkbenchOpsServerMessage(
    val containerId: Int,
    val path: String,
    val ops: List<Op>,
)

// S → C, ack of the client's own ops + relay of remote ops
class WorkbenchOpsClientMessage(
    val containerId: Int,
    val path: String,
    val ops: List<Op>,           // ops the client has not yet seen
    val ackedClock: Int,         // highest clock from this client that the server has applied
)

// S → C, sent on session open
class WorkbenchDocumentSnapshotClientMessage(
    val containerId: Int,
    val path: String,
    val initialRuns: List<TextRun>,
    val versionVector: Map<SiteId, Int>,
)
```

`Op` encodes via `FriendlyByteBuf` to ~30 bytes per op (variable for `Insert.text`). Site IDs use compact strings
(`"s:i"`, `"p:abcd1234"`, `"t:42"`).

### OpOutbox: Client-Side Debouncer

```kotlin
class OpOutbox(
    private val send: (List<Op>) -> Unit,
    private val debounceMs: Long = 50,
    private val maxBatch: Int = 64,
) {
    fun enqueue(op: Op)
    fun flushNow()
    val pendingCount: Int
}
```

Behavior:

- Each `enqueue` schedules a flush after `debounceMs`, but if `pendingCount` reaches `maxBatch`, flush synchronously.
- `flushNow()` is called by `flushAndRun` and by `closeFile`.

### Sync Status

```kotlin
enum class SyncStatus { Idle, Pending, Syncing, Stale }
```

State machine:

- **Idle** — no unacked ops.
- **Pending** — local edits queued in outbox, not yet sent.
- **Syncing** — ops sent, awaiting ack.
- **Stale** — no ack within 5s of `Syncing`. Editor still works; on next ack we drop back to `Idle`.

### Server Handling

On `WorkbenchOpsServerMessage`:

1. Look up `ServerCrdtReplica` for `(containerId, path)`. If absent, ignore (session not opened).
2. For each op, validate causality (referenced atoms exist, clock strictly increasing per site). Drop the message and
   re-snapshot the client if validation fails.
3. Apply ops, update version vector.
4. Compute `ackedClock` for the sender and the set of ops not yet seen by other connected editors.
5. Send `WorkbenchOpsClientMessage` to the sender with `ackedClock` and to other editors of the same file with the
   relay (Phase 2 — Phase 1 is single editor).

On session open: build `ServerCrdtReplica` from disk if not yet in memory, send
`WorkbenchDocumentSnapshotClientMessage`.

On session close (last editor leaves): `flatten()` to disk, drop `ServerCrdtReplica`.

### RUN Flow

`RUN` button calls `store.flushAndRun(): Job`:

1. `outbox.flushNow()`.
2. Wait until `ackedClock >= lastEnqueuedClock` (or 3s timeout → confirm dialog).
3. Send a regular `WorkbenchWorkspaceServerMessage(action = RUN, path)`.
4. Server materializes the latest CRDT to disk, then runs as today.

### Store / EditorState / UI

`WorkbenchStore` API surface changes:

- **Removed**: `pullFromTarget()`, `pushToTarget()`, public `saveDocument()`.
- **Added**: `applyLocalEdit(LocalEdit)`, `applyRemoteOps(List<Op>)`, `applyAck(ackedClock: Int)`,
  `flushAndRun(): Job`.
- **Internal**: holds `ClientCrdtReplica` and `OpOutbox`.

`EditorState` changes:

- **Removed**: `dirty: Boolean`.
- **Added**: `syncStatus: SyncStatus`, `pendingOpCount: Int`, `cursor: AtomId`, `cursorOffsetWithinRun: Int`.
- `(line, column)` becomes a derived view computed from cursor + materialized text.

`WorkbenchUiBuilder` changes:

- **Removed**: `Save`, `Pull`, `Push` toolbar buttons; `* ` dirty marker in status bar.
- **Added**: `syncStatusIndicator(syncStatus, pendingCount)` rendering ✓ / ⋯ / ↻ / ⚠.
- `CodeEditor` element gains a `SyncingRun(start, end, alpha)` highlight type for the letter-by-letter sync glow,
  alpha-faded on ack.

### Module Boundaries

- `modules/core/.../computer/workbench/crdt/` — pure Kotlin, JVM-testable: `CrdtDocument`, `Op`, `TextRun`, `AtomId`,
  `SiteId`, `ClientCrdtReplica`, `ServerCrdtReplica`.
- `modules/core/.../computer/workbench/sync/` — pure: `OpOutbox`, `SyncStatus`.
- `modules/core/.../computer/workbench/WorkbenchStore.kt` — orchestrator.
- `modules/v1_21_1/v1_21_1-common/.../workbench/network/{client,server}/Workbench{Ops,DocumentSnapshot}*Message.kt` —
  new messages.
- `modules/v1_21_1/v1_21_1-common/.../workbench/context/ServerWorkbench.kt` — adds
  `ConcurrentHashMap<String, ServerCrdtReplica>`, removes dirty flags.

## Testing

**`crdt/`** (JVM, junit5):

- `CrdtDocumentTest` — insert at start/middle/end, delete spanning runs, idempotent apply, two-replica fuzz
  (1000 random edits in different orders → identical `flatten()`), tombstone semantics.

**`sync/`** (JVM, junit5):

- `OpOutboxTest` — debounce flush, max-batch synchronous flush, ack clears `pendingCount`, stale timeout.

**`WorkbenchStoreTest`** — extended:

- `applyLocalEditAddsToOutbox`
- `applyAckClearsPendingCount`
- `applyRemoteOpsUpdatesEditorText`
- `flushAndRunWaitsForSync`
- `cursorMovesWhenRemoteInsertHappensLeft`

**`WorkbenchEditorViewModelTest`** — `onCharTyped` no longer mutates `EditorState.text` directly; instead asserts
`store.applyLocalEdit` was called.

**Integration (Phase 1):** single player + single target. Open file → 100 edits → `RUN`. Assert: final text on
server matches expected; `RUN` does not start until the last op is acked.

## Migration

- Disk format unchanged.
- Network protocol gains the three new messages; `WRITE` / `PULL` / `PUSH` actions are removed from
  `WorkbenchWorkspaceServerMessage`. Old clients fail to connect to a new server; this is acceptable pre-1.0.
- Old `WorkbenchStoreTest` tests targeting `saveClearsDirtyFlag`, `pullFrom*`, `pushTo*` are rewritten under the new
  semantics.
- Migration is a single drop — no feature flag.

## Risks

1. **Op-apply performance.** A plain `PersistentList<TextRun>` with linear `findRun(leftId)` is O(N) per op. On a
   5000-line file that approaches ms-per-op. **Mitigation:** maintain `Map<AtomId, RunIndex>` inside `CrdtDocument`,
   updated on every split. If profiling still shows hotspots, migrate to a skiplist or finger-tree.

2. **Network backpressure.** A very fast typist can fill the outbox. **Mitigation:** `MAX_BATCH = 64` triggers a
   synchronous flush, bypassing debounce.

3. **Server-held CRDT on client crash.** Phase 1 has a single editor. On disconnect we `flatten()` to disk and drop
   the replica — the player's last-acked state is what persists. Unacked ops are lost; this is acceptable in single
   editor mode and will be revisited in Phase 2 where retain policies are needed.

4. **Cursor stability under remote deletion.** If a remote op tombstones the run my cursor sits on, we relocate to
   the nearest right non-deleted run via `ClientCrdtReplica.relocateCursor()`. Documented and tested.

5. **RUN deadlock on ack timeout.** `flushAndRun` has a 3s timeout. On expiry we surface a confirm dialog: "could not
   sync — run anyway?".

6. **Surrogate pairs.** Atoms hold `text: String`, not `Char`. Insert at a surrogate boundary is forbidden at the
   editor input layer. Tests cover BMP and non-BMP roundtrips.

## Out Of Scope (Phase 2 — separate design)

- Multiple players editing the same file (server broadcast to all subscribers).
- Presence / awareness — colored remote cursors, Live Share style.
- Script as a live collaborator: `fs.write` is diff'd via Myers and emitted as ops under `t:<computerId>`.
- Tombstone GC inside an active session.
- Disk-side persistence of CRDT state (for crash recovery).
