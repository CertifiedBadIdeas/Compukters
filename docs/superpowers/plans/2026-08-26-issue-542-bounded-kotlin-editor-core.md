# Bounded Kotlin Editor Core Implementation Plan

> Issue: [#542](https://github.com/CertifiedBadIdeas/Compukters/issues/542)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the bounded host-neutral editor, incremental lexical Kotlin highlighter, and exact-snapshot diagnostic/semantic presentation foundation consumed by the future graphical IDE.

**Architecture:** `ide-core` owns a valid-UTF-16 gap buffer, editor state/history, viewport, lexer cache, and immutable presentation DTOs. `ProjectDocumentSession` embeds the editor and materializes complete text only at persistence/recovery boundaries. K2 remains outside this module and will supply matching semantic DTOs through #543.

**Tech Stack:** Kotlin 2.4/JVM 25, Kotlin test, `CharArray`, SHA-256, existing `compiler-client` bounded path/hash/snapshot types, Gradle dependency/archive gates.

---

## File Map

- `editor/EditorLimits.kt`, `Utf16.kt`, `EditorBuffer.kt`: validated bounds and the internal gap buffer.
- `editor/EditorRange.kt`, `EditorChange.kt`, `EditorEditResult.kt`: immutable editor values.
- `editor/EditorLineIndex.kt`, `EditorHistory.kt`, `EditorDocument.kt`: lines, commands, selection, and history.
- `editor/EditorViewport.kt`: renderer-independent scrolling, page movement, and mouse mapping.
- `highlight/KotlinLexicalModel.kt`, `KotlinLineLexer.kt`, `IncrementalKotlinHighlighter.kt`: immediate lexical presentation.
- `analysis/SourceSnapshotIdentity.kt`, `EditorPresentation.kt`: exact-source identity and K2-independent DTOs.
- `project/document/ProjectDocumentSession.kt`: editor ownership and persistence integration.

### Task 1: Bounded UTF-16 Gap Buffer

**Files:**
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorLimits.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/Utf16.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorBuffer.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/EditorBufferTest.kt`

- [ ] **Step 1: Write failing buffer tests**

Exercise this wished-for boundary:

```kotlin
val limits = EditorLimits(maxCodeUnits = 12, maxUtf8Bytes = 16, initialGapCodeUnits = 2)
val buffer = EditorBuffer("ab😀cd", limits)
assertEquals(BufferReplaceResult.Applied, buffer.replace(2, 4, "x"))
assertEquals("abxcd", buffer.materialize())
assertEquals(
    BufferReplaceResult.Rejected(EditorRejection.CodeUnitLimit),
    buffer.replace(0, 0, "0123456789"),
)
```

Cover gap growth/movement, indexed reads/copies, exact strict UTF-8 byte
accounting, content equality, rejection atomicity, empty limits, malformed
initial/inserted surrogate sequences, and a range endpoint inside a pair.

- [ ] **Step 2: Run RED**

```bash
./gradlew-sandbox :ide-core:test --tests '*EditorBufferTest*' --rerun-tasks
```

Expected: compilation fails because the editor types do not exist.

- [ ] **Step 3: Implement the minimal buffer**

```kotlin
data class EditorLimits(
    val maxCodeUnits: Int = 256 * 1024,
    val maxUtf8Bytes: Int = 256 * 1024,
    val initialGapCodeUnits: Int = 4 * 1024,
    val maxUndoEntries: Int = 256,
    val maxUndoCodeUnits: Int = 256 * 1024,
    val tabWidth: Int = 4,
)

enum class EditorRejection { CodeUnitLimit, Utf8ByteLimit, UndoLimit, InvalidUtf16, InvalidRange, Closed }
sealed interface BufferReplaceResult {
    data object Applied : BufferReplaceResult
    data class Rejected(val reason: EditorRejection) : BufferReplaceResult
}
```

`EditorBuffer` keeps `gapStart`, `gapEnd`, logical length, and exact UTF-8 byte
count. Validate the complete replacement and resulting limits before moving or
growing the gap. Grow geometrically but never beyond `maxCodeUnits`. Provide
internal `charAt`, `copyRange`, `contentEquals`, `materialize`,
`previousScalarBoundary`, and `nextScalarBoundary` operations.

- [ ] **Step 4: Run GREEN, format, and commit**

```bash
./gradlew-sandbox :ide-core:formatKotlin :ide-core:test --tests '*EditorBufferTest*' --rerun-tasks
git add modules/ide-core
git commit -m "feat(ide): add bounded UTF-16 editor buffer (#542)"
```

### Task 2: Editor Commands, Selection, Lines, and History

**Files:**
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorRange.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorChange.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorEditResult.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorLineIndex.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorHistory.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorDocument.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/EditorDocumentTest.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/EditorHistoryTest.kt`

- [ ] **Step 1: Write failing command tests**

Cover scalar-aware Left/Right/Backspace/Delete, atomic CRLF, Up/Down retained
visual column, Home/End, Shift selection, select-all, type/paste/cut,
Tab-to-next-stop, inherited indentation, immutable change events, monotonic
content revision, and exactly one listener event per accepted mutation. Prove
rejected edits and navigation do not notify or change revision.

- [ ] **Step 2: Write failing bounded-history tests**

Prove sequential typing and Backspace coalesce; paste/newline/tab/cut are
atomic; navigation closes a group; oldest complete entries are evicted by
count/unit budget; an oversized entry rejects the edit; Undo then input clears
Redo; Unicode and CRLF round-trip exactly.

- [ ] **Step 3: Run RED**

```bash
./gradlew-sandbox :ide-core:test --tests '*EditorDocumentTest*' --tests '*EditorHistoryTest*' --rerun-tasks
```

- [ ] **Step 4: Implement lines and transactions**

```kotlin
data class EditorRange(val startUtf16: Int, val endUtf16: Int)
data class EditorSelection(val anchorUtf16: Int, val caretUtf16: Int)
data class EditorChange(
    val oldRevision: Long,
    val newRevision: Long,
    val oldRange: EditorRange,
    val insertedCodeUnits: Int,
    val oldAffectedLines: IntRange,
    val newAffectedLines: IntRange,
    val origin: EditorChangeOrigin,
)
enum class EditorChangeOrigin { User, UndoRedo, ExternalReset }
sealed interface EditorEditResult {
    data class Applied(val change: EditorChange) : EditorEditResult
    data class Rejected(val reason: EditorRejection) : EditorEditResult
    data object NoChange : EditorEditResult
}
```

`EditorLineIndex` recognises LF, CRLF, and CR without normalization and updates
from the first touched line. It exposes line start/content end/separator end,
offset-to-line, scalar visual columns with tab stops, and line/column-to-offset.
`EditorHistory` stores exact replacement payloads and before/after selections,
charges removed+inserted units, and uses one non-recording Undo/Redo path.

`EditorDocument` owns buffer/index/history and exposes queries, commands, and
multiple removable change-listener subscriptions.
Invalid public offsets reject rather than clamp. A package-internal reset
replaces externally reloaded text, clears history, increments revision once,
and emits ExternalReset so lexers update without arming autosave. `close()`
makes subsequent mutations return `EditorRejection.Closed`.

- [ ] **Step 5: Run GREEN, format, and commit**

```bash
./gradlew-sandbox :ide-core:formatKotlin :ide-core:test --tests '*Editor*Test*' --rerun-tasks
git add modules/ide-core
git commit -m "feat(ide): add bounded editor commands and history (#542)"
```

### Task 3: Independent Viewport and Mouse Mapping

**Files:**
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorViewport.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/EditorViewportTest.kt`

- [ ] **Step 1: Write failing viewport tests**

Cover positive dimensions, independent and clamped horizontal/vertical scroll,
resize, minimal caret reveal, Page Up/Down using visible rows, mouse placement,
clicks outside line width, tabs, supplementary scalars as one cell, and no
document revision/history changes from scrolling.

- [ ] **Step 2: Run RED**

```bash
./gradlew-sandbox :ide-core:test --tests '*EditorViewportTest*' --rerun-tasks
```

- [ ] **Step 3: Implement and verify**

```kotlin
class EditorViewport(rows: Int, columns: Int) {
    var firstLine: Int
        private set
    var firstVisualColumn: Int
        private set
    fun resize(rows: Int, columns: Int)
    fun scrollLines(document: EditorDocument, delta: Int)
    fun scrollColumns(document: EditorDocument, delta: Int)
    fun revealCaret(document: EditorDocument)
    fun placeCaret(document: EditorDocument, row: Int, column: Int, extendSelection: Boolean = false)
}
```

Page commands live on the viewport because page size is presentation state.
Run format and `*EditorViewportTest*`, then commit as
`feat(ide): add renderer-independent editor viewport (#542)`.

### Task 4: Project Document Session Integration

**Files:**
- Modify: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/document/ProjectDocumentSession.kt`
- Modify: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/project/document/ProjectDocumentSessionTest.kt`

- [ ] **Step 1: Write failing integration tests**

Mutate `session.editor` rather than `edit(String)`. Prove delayed and boundary
autosave, Undo-to-clean, clean external reset of text/selection/history, dirty
external conflict, Save As preserving editor identity, build persistence,
exact invalidation recovery, and mutation rejection after close.

- [ ] **Step 2: Run RED**

```bash
./gradlew-sandbox :ide-core:test --tests '*ProjectDocumentSessionTest*' --rerun-tasks
```

- [ ] **Step 3: Embed the editor**

Expose `val editor: EditorDocument`, created from the snapshot with limits
mapped from `ProjectLimits`. Its listener computes dirty using
`editor.contentEquals(snapshot.text)` and arms/disarms autosave. Save and
recovery materialize only at existing boundaries. Clean reload uses the
non-user reset. Detach/close the editor with the session. Remove `edit(String)`.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew-sandbox :ide-core:formatKotlin :ide-core:test --tests '*ProjectDocument*Test*' --tests '*AutosaveControllerTest*' --rerun-tasks
git add modules/ide-core
git commit -m "feat(ide): integrate editor with project sessions (#542)"
```

### Task 5: Incremental Kotlin Lexical Highlighter

**Files:**
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/highlight/KotlinLexicalModel.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/highlight/KotlinLineLexer.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/highlight/IncrementalKotlinHighlighter.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/highlight/KotlinLineLexerTest.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/highlight/IncrementalKotlinHighlighterTest.kt`

- [ ] **Step 1: Write failing line-lexer tests**

```kotlin
enum class KotlinLexicalKind { Keyword, Identifier, TypeLike, Number, String, Escape, Character, MultilineString, LineComment, BlockComment, Annotation, Operator }
data class LexicalSpan(val startUtf16: Int, val endUtf16: Int, val kind: KotlinLexicalKind)
data class KotlinLexicalState(val blockCommentDepth: Int = 0, val inMultilineString: Boolean = false)
```

Cover every category, keyword boundaries, number suffix/exponents, escapes,
triple strings, nested comments, CRLF exclusion, and malformed/incomplete
tokens.

- [ ] **Step 2: Run RED, implement line transitions, and run GREEN**

```bash
./gradlew-sandbox :ide-core:test --tests '*KotlinLineLexerTest*' --rerun-tasks
```

Implement a hand-written scanner over one editor line returning relative
non-overlapping spans and exact next-line state; rerun the command.

- [ ] **Step 3: Write failing incremental-cache tests**

After edits around strings, triple strings, nested/line comments, line endings,
and malformed source, compare every cache snapshot with a fresh full scan. Add
a seeded 1,000-edit property test. Require a local stable-state edit to report
that it reused an unchanged suffix.

- [ ] **Step 4: Run RED, implement stabilization, and run GREEN**

```bash
./gradlew-sandbox :ide-core:test --tests '*IncrementalKotlinHighlighterTest*' --rerun-tasks
```

Cache line fingerprint, incoming/outgoing state, and spans. Start at the first
affected line (one earlier for CRLF), align by line delta, and stop on an exact
matching suffix record.

- [ ] **Step 5: Verify and commit**

Run format plus all `*Editor*Test*` and `*Kotlin*Test*`; commit as
`feat(ide): add incremental Kotlin lexical highlighting (#542)`.

### Task 6: Exact-Snapshot Presentation DTOs

**Files:**
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/analysis/SourceSnapshotIdentity.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/analysis/EditorPresentation.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/SourceSnapshotIdentityTest.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/EditorPresentationTest.kt`

- [ ] **Step 1: Write failing identity/presentation tests**

Prove the source identity is deterministic, domain-separated, path/content
sensitive, and independent of compiler/target/module identity. Cover bounded
diagnostic messages/counts, token/location counts, canonical source paths,
non-empty ranges, defensive copies, exact-hash Active, different-hash Stale,
no positional access while stale, source membership, and document bounds.

- [ ] **Step 2: Run RED**

```bash
./gradlew-sandbox :ide-core:test --tests '*SourceSnapshotIdentityTest*' --tests '*EditorPresentationTest*' --rerun-tasks
```

- [ ] **Step 3: Implement stable DTOs**

Use SHA-256 with `Compukters source snapshot v1\u0000`, explicit little-endian
lengths, canonical path UTF-8, and exact source bytes. Semantic categories are
Compukters-owned values, never Analysis API ordinals. `accept` returns a sealed
Active/Stale view; only Active exposes positional presentation/navigation.

- [ ] **Step 4: Verify and commit**

Run format and the two focused tests; commit as
`feat(ide): add snapshot-bound editor presentation (#542)`.

### Task 7: Architecture Gates and Repository Verification

**Files:**
- Modify: `modules/ide-core/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`

- [ ] **Step 1: Strengthen gates**

Keep the external runtime allowlist unchanged and explicitly reject Analysis
API, IntelliJ, PSI, FIR, K2, coroutine, logging, Minecraft, NeoForge, and
Architectury runtime modules. Keep all `ru/lazyhat/compukters/ide/` classes and
TOML transitive libraries absent from the production archive until #540.

- [ ] **Step 2: Run focused verification**

```bash
./gradlew-sandbox --parallel :compiler-client:test :ide-core:check --rerun-tasks
```

- [ ] **Step 3: Run full verification**

```bash
./gradlew-sandbox verifyLocalFull
git diff --check
git status --short
```

- [ ] **Step 4: Commit and hand off**

Commit gate changes as `build(ide): enforce editor core isolation (#542)`.
List every #542 commit, update the issue with evidence, set Roadmap Done, close
#542 completed, keep #543 Inbox, and leave parent #529 open.
