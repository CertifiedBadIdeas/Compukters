# Bounded Kotlin Editor Core Design

> Issue: [#542](https://github.com/CertifiedBadIdeas/Compukters/issues/542)

## Context

The client-local project foundation in #537 provides secure project discovery,
strict UTF-8 source persistence, optimistic file revisions, autosave, and one
active document session. It deliberately stores the active edit as a complete
`String`; #542 replaces that temporary representation with a bounded editor
model and adds the immediate presentation data needed by the first graphical
IDE.

The editor must remain usable without a target computer and testable without
Minecraft. It must also remain reusable as a separately published JVM
artifact. K2 code intelligence is valuable, but Analysis API, PSI, FIR, and
compiler implementation classes must not enter `ide-core` or the Minecraft
application classpath. The accepted architecture therefore separates the
synchronous lexical layer in this issue from the isolated semantic worker in
#543.

## Goals

- Edit one bounded Kotlin source document without rebuilding its complete text
  for each keystroke.
- Preserve valid Unicode scalar boundaries while using Kotlin/JVM-compatible
  UTF-16 offsets.
- Provide deterministic selection, navigation, clipboard, indentation,
  viewport, undo, and redo behavior for a future renderer.
- Produce immediate incremental lexical highlighting for incomplete source.
- Represent diagnostics and future semantic overlays as immutable, bounded,
  exact-snapshot results.
- Integrate the editor with the existing conflict-safe document session without
  weakening atomic persistence or autosave semantics.

## Non-Goals

- Minecraft screens, rendering, input bindings, fonts, or clipboard access.
- Running K2, Analysis API Standalone, PSI, FIR, or semantic resolution.
- Completion, hover execution, find usages execution, refactoring, LSP, or
  semantic token production. Those belong to #543.
- Multiple simultaneously open documents or tabs.
- Grapheme-cluster shaping, bidirectional layout, soft wrapping, or proportional
  font measurement.
- Diff/merge conflict resolution.

## Module Boundary

All new production types live in `modules/ide-core` under focused `editor`,
`highlight`, and `analysis` packages. They may depend on Kotlin/JVM standard
types and the existing bounded project/protocol value objects. They may not
depend on Minecraft, NeoForge, Architectury, coroutines, K2, Analysis API,
IntelliJ Platform, PSI, or FIR.

`ide-core` is confined to the client UI thread. It does not add internal locks
or background executors. Immutable snapshots are the only values intended to
cross into a worker or another thread.

## Editor Limits

`EditorLimits` is explicit and validated. Its defaults are:

- `maxCodeUnits = 256 * 1024`;
- `maxUtf8Bytes = 256 * 1024`;
- `initialGapCodeUnits = 4 * 1024`;
- `maxUndoEntries = 256`;
- `maxUndoCodeUnits = 256 * 1024`;
- `tabWidth = 4`.

The editor tracks both UTF-16 code units and the strict UTF-8 byte length that
will be persisted. Its default byte bound matches `ProjectLimits`, while the
project store independently rechecks the same admission at the trust boundary.
Constructors accept smaller limits for deterministic tests and future device
profiles.

Every public edit returns a typed applied/rejected result. Capacity and undo
admission are checked before mutation, so a rejected command changes neither
text, selection, viewport, revision, nor history. Oldest complete undo entries
are evicted to admit a new entry. An individual replacement whose retained old
and new text cannot fit the total undo budget is rejected; every accepted user
mutation therefore remains undoable until later history eviction.

## UTF-16 Text Storage

`EditorBuffer` uses one geometrically growing `CharArray` gap buffer bounded by
`maxCodeUnits`. The logical content is addressed by UTF-16 code-unit offset.
Reads, range copies, equality against a `String`, and bounded materialization do
not expose the backing array or the gap.

Initial text and inserted text must be well-formed UTF-16. A high surrogate
must be followed by a low surrogate, and a low surrogate must have its matching
high surrogate. Cursor, selection, replacement, Backspace, Delete, and
navigation endpoints are never permitted between the two units of a valid
pair. Left/Right and deletion operate on one Unicode scalar, while combining
marks remain separate because full grapheme navigation is out of scope.

The editor does not normalize source text. LF, CRLF, and lone CR line endings
are recognised and preserved. A CRLF pair is one logical line separator for
navigation and deletion. Enter inserts the first line-ending form found in the
document, or LF when the document contains none. This preserves direct host
edits while keeping new projects LF by default.

The complete `String` is materialized only for persistence, immutable recovery,
or an explicit worker snapshot. Normal typing, navigation, selection,
highlighting, and viewport queries read through the buffer and line index.

## Document, Selection, and Commands

`EditorDocument` owns:

- the bounded buffer;
- an incrementally maintained logical line index;
- caret and optional selection anchor;
- a monotonic content revision;
- the preferred visual column used by vertical navigation;
- bounded undo and redo journals;
- synchronous internal change listeners used by
  `ProjectDocumentSession` and `IncrementalKotlinHighlighter`.

Offsets are the canonical coordinate. A selection is the ordered range between
anchor and caret, while navigation retains which endpoint is active. A command
without Shift clears the selection; Shift navigation moves the caret and keeps
the anchor. Text input replaces the selection atomically.

The first command surface covers:

- scalar-aware Left/Right and line-aware Up/Down;
- Home/End and Page Up/Page Down;
- mouse placement from a viewport-relative row and visual column;
- select all and Shift navigation;
- type, paste, cut, Backspace, Delete, and selection replacement;
- Enter with the current logical line's leading spaces/tabs;
- Tab insertion as enough spaces to reach the next configured tab stop;
- undo and redo.

Existing tab characters advance to the next tab stop for visual-column and
mouse mapping. Every Unicode scalar otherwise occupies one editor cell in this
host-neutral model. The renderer may show a missing-glyph box, but cannot alter
the logical mapping.

Sequential ordinary typing and Backspace commands coalesce into bounded undo
groups until navigation, selection change, paste, newline, indentation, focus
boundary, save boundary, or another command kind closes the group. Paste,
cut, selection replacement, Enter, and Tab are each atomic undo entries. A new
edit after Undo clears Redo.

## Viewport

`EditorViewport` is separate from the document. It contains visible row/column
capacity plus non-negative first logical line and first visual column. Vertical
and horizontal origins change independently. It provides:

- clamped explicit scrolling;
- `revealCaret(document)` with the smallest required movement;
- viewport-relative mouse mapping through the document;
- visible logical line and clipped visual-column ranges.

Keeping viewport state outside the buffer prevents rendering concerns from
contaminating editing and allows a later screen to reconstruct or resize its
viewport without touching text or undo history.

## Change Model and Session Integration

Each accepted mutation emits one immutable `EditorChange` containing old and
new revisions, the replaced old range, inserted code-unit count, and old/new
affected logical line ranges. It also carries a stable origin: User, UndoRedo,
or ExternalReset. It never contains the complete document. Listener
registration returns a removable subscription so the session and highlighter
have independent lifetimes.

`ProjectDocumentSession` owns and exposes its `EditorDocument`. Its internal
listener compares buffer content with the last persisted snapshot without
allocating a full `String`, updates dirty state, and arms the existing autosave
controller for User and UndoRedo changes. Save, Save As, close recovery, and the build barrier materialize
the text only at their existing I/O boundary. A clean external reload replaces
the complete editor content through an ExternalReset that clears history,
updates highlighters, and does not arm autosave. A dirty external change retains
the editor and enters the existing Conflict state.

Undoing back to the persisted content returns the session to clean state and
disarms autosave. Root invalidation still closes the session with an immutable
recovery string. Closing the session removes its listener and closes the editor;
later mutation attempts return a typed Closed rejection.

## Incremental Lexical Highlighting

`IncrementalKotlinHighlighter` is a purpose-built bounded lexer, not a parser
and not K2. It classifies spans as:

- keyword;
- identifier and capitalised type-like identifier;
- number;
- ordinary string and escape;
- character literal and escape;
- multiline string;
- line comment;
- block comment;
- annotation marker;
- punctuation/operator.

Whitespace remains implicit. Malformed tokens consume a deterministic bounded
range and never throw. Unterminated ordinary strings and characters end at a
logical line boundary. Triple-quoted strings continue across lines. Kotlin
block comments support nesting, so the per-line lexical state records block
comment depth; it also records whether scanning starts/ends inside a multiline
string.

The cache stores immutable line records: source line identity, start state, end
state, and relative spans. An `EditorChange` invalidates the first affected
logical line, including the preceding line when an edit can change a CRLF
boundary. Scanning proceeds forward until line identity, incoming state,
outgoing state, and spans match an aligned cached suffix; the untouched suffix
is then reused. Line insertion/removal is accounted for before suffix
alignment.

The implementation exposes a full-scan test oracle. Deterministic randomized
edit tests must prove that every incremental result is exactly equal to a new
full scan, including normal code, nested comments, strings, multiline strings,
line-ending changes, and malformed Kotlin.

Lexical type-like coloring is deliberately heuristic. Precise declarations,
extension calls, inferred types, shadowing, and resolved references will arrive
as a semantic overlay from #543 and take precedence only for a matching
snapshot.

## Diagnostics and Semantic Overlays

`SourceSnapshotId` wraps the existing `Hash256` value type. Its canonical,
domain-separated identity hashes every strictly ordered virtual source path
and its exact strict UTF-8 bytes with explicit lengths. It excludes compiler,
target, and module identities: those affect whether semantic analysis may run,
but source staleness is solely a property of the immutable source set.
Presentation data is bound to one exact project snapshot:

- `EditorDiagnostic`: bounded severity, message, optional canonical source
  path, and optional UTF-16 range;
- `SemanticToken`: canonical source path, UTF-16 range, and stable semantic
  category;
- `SourceLocation`: canonical source path and UTF-16 range for declaration or
  reference navigation;
- `SnapshotPresentation`: snapshot ID plus bounded diagnostic, token, and
  navigation collections.

Semantic categories are Compukters-owned DTO values such as class, interface,
type parameter, function, extension function, property, local variable,
parameter, object, enum entry, and inferred/smart-cast expression. They are not
Analysis API enum ordinals and carry no PSI/FIR/`KaSymbol` object.

`SnapshotPresentation.accept(currentSnapshotId)` returns Active only on exact
identity equality. A mismatch returns Stale and exposes no positional spans or
navigation target. The future UI may retain a non-positional build summary, but
must remove underlines, semantic coloring, and jump actions immediately.

Navigation additionally validates that the canonical source path belongs to
the current project source set and that the range fits the current document.
Host filesystem paths never enter diagnostics or navigation DTOs.

## K2 Follow-Up Boundary

#543 will run pinned K2 Analysis API Standalone in an isolated, resource-bounded
client child process. Requests will contain an immutable source snapshot,
cursor/range query, resolved module lock, and guest API bundle identities.
Replies will contain only the DTOs defined above plus completion/hover data,
all tagged with snapshot ID and request generation.

This issue does not add the worker. The lexical result is immediate and always
available; a matching semantic result later overlays it. Stale, cancelled,
timed-out, or crashed semantic requests cannot block editing or erase lexical
highlighting.

## Error Handling

- Invalid limits and invalid initial UTF-16 are constructor errors.
- User operations that exceed text or undo bounds return typed rejection and
  perform no partial mutation.
- Invalid command offsets and ranges are rejected before mutation.
- Highlighting malformed Kotlin produces spans rather than failures.
- Invalid or oversized diagnostic/semantic DTOs are rejected at construction or
  protocol decoding, before publication.
- Stale snapshot-bound results are ordinary state, not exceptional failures.
- Existing save, conflict, invalidation, and recovery results remain the only
  persistence failures visible through `ProjectDocumentSession`.

## Verification

Pure `ide-core` tests cover:

- gap movement and growth at small limits;
- replacement and exact text materialization;
- capacity and undo admission atomicity;
- scalar-safe movement/deletion and malformed UTF-16 rejection;
- LF, CRLF, and CR preservation;
- selection, Shift navigation, mouse mapping, tabs, indentation, and clipping;
- bounded/coalesced undo and redo;
- independent viewport axes and caret reveal;
- session dirty/clean transitions, autosave, external reload/conflict, build,
  recovery, and editor replacement on clean external reload;
- lexical states and malformed Kotlin;
- randomized incremental/full-rescan equivalence;
- bounded diagnostic/semantic construction, exact-snapshot activation, stale
  invalidation, and safe navigation.

Architecture checks retain the existing `ide-core` runtime allowlist and add
explicit rejection of K2, Analysis API, IntelliJ, PSI, and FIR dependencies.
The NeoForge production archive continues to exclude `ide-core` until #540
deliberately packages it with reviewed licenses.

Required final commands are:

```bash
./gradlew-sandbox --parallel :ide-core:test
./gradlew-sandbox verifyLocalFull
git diff --check
```

## Delivery Boundary

#542 is complete when the host-neutral editor, viewport, lexical highlighter,
snapshot presentation model, and `ProjectDocumentSession` integration pass the
pure and repository-wide checks. It does not wait for rendering or semantic
analysis. #543 owns the isolated K2 code-intelligence implementation, while
#540 consumes this core in the first graphical screen.
