# Local-Only Graphical IDE Screen Design

> Issue: [#540](https://github.com/CertifiedBadIdeas/Compukters/issues/540)

## Context

Compukters now has four completed host-side foundations for the first in-game
IDE. Issue #537 provides a secure client-local project catalog, canonical
manifest and lock models, optimistic file revisions, autosave, and conflict
handling. Issue #542 provides a bounded UTF-16 editor, viewport, undo/redo,
incremental Kotlin lexer, and exact-snapshot presentation DTOs. Issue #541
provides isolated client compilation, local profile resolution, a bounded
content-addressed Artifact cache, cancellation, and worker recovery. Issue #543
provides isolated K2 diagnostics, semantic tokens, completion, inferred
information, navigation, and references.

None of those foundations is player-visible. The Minecraft client currently
has only the independent computer terminal Screen. This issue builds the first
local-only graphical IDE without changing that terminal or requiring an
in-world computer. Target attachment and authoritative Artifact deployment
remain in #538.

## Goals

- Let a player open the IDE anywhere, create or restore a local project, browse
  and mutate its files, and edit one file at a time.
- Resolve a local compile profile, build through the isolated client compiler,
  display exact diagnostics, and retain the Artifact in the global cache.
- Present immediate lexical highlighting plus exact-snapshot semantic colors
  and bounded automatic/manual completion from the isolated analysis worker.
- Keep project, build, and analysis orchestration reusable and testable without
  Minecraft.
- Keep filesystem and worker latency off the Minecraft render path.
- Preserve the previous Screen, existing terminal state, server compiler, and
  guest execution behavior.

## Non-Goals

- Target leases, upload, server verification, deployment, launch, or a target
  filesystem explorer.
- Hover cards, Go to Declaration, or Find References. Those focused UI
  interactions follow in #544 using the already implemented #543 queries.
- Multiple open tabs, soft wrapping, refactoring, quick fixes, debugger, LSP,
  CRDT collaboration, or a dedicated IDE block.
- Hex editing or lossy replacement decoding for binary files.
- A project-local build directory or automatic Artifact export.

## Module Boundary

Add a new Minecraft-independent `ide-client` Gradle module. It depends on the
public `ide-core`, `ide-analysis-client`, and compiler client/runtime surfaces,
but never on `ide-analysis-k2`, `compiler-k2`, Minecraft, NeoForge,
Architectury, Analysis API, PSI, FIR, or IntelliJ classes.

`ide-client` owns:

- the active IDE application session;
- project catalog, tree, active-file, document, and conflict state;
- immutable renderer-facing view state and stable UI commands;
- workspace I/O orchestration;
- Resolve, Build, Cancel, compiler-cache, and Artifact summary state;
- analysis-session, presentation, and completion orchestration;
- exact snapshot/profile identity checks and stale-result rejection;
- bounded queues, lifecycle, cancellation, and typed failures.

`ide-core` continues to own reusable project/editor/value logic. This issue
generalises its path and document primitives where required for arbitrary
admitted project files rather than Kotlin sources alone. It still knows
nothing about processes or Minecraft.

The NeoForge client adapter owns only:

- key registration and client lifecycle;
- `.minecraft/compukters` paths and client config persistence;
- `IdeScreen`, render geometry, widgets, and tooltips;
- keyboard, character, mouse, wheel, clipboard, and focus translation;
- extraction/publication of inert compiler and analysis worker payloads;
- suspension and restoration of the previous Minecraft Screen.

The production mod may contain the two workers only as nested inert payload
data. Archive and class-loading gates prove that their implementation classes
are not visible to the Minecraft application classloader.

## Application Controller and Threading

`IdeClientController` is a host-neutral application controller. Its public
command surface represents user intent rather than Minecraft events: open or
create project, select file, edit, save, mutate the tree, resolve, build,
cancel, request completion, accept completion, change focus, resize viewport,
and close.

The controller publishes one immutable bounded `IdeViewState`. Rendering reads
only that state and never calls the filesystem or waits for a future. Editor
mutation and ephemeral UI state remain confined to the UI thread. Async
workspace/compiler/analysis completions enter one bounded event queue and are
applied on the next client tick. Overflow retains a typed degraded state and
coalesces replaceable polling/presentation events rather than growing without
limit.

One single-thread workspace executor serialises catalog scans, file reads,
writes, creates, moves, deletes, and external-change polling. Compiler and
analysis services keep their existing bounded worker/controller executors.
Closing the application session stops admission, cancels active work, drains
or rejects late events by session generation, and closes each owned service.

No callback mutates the editor or view state directly. Every completion carries
the session generation and the exact operation identity needed to reject a
late result.

## Client Service Lifetime

A client-global `CompuktersIdeClient` service owns configuration and opens one
IDE application session at a time. It does not keep a K2 worker alive merely
because Minecraft is running. Opening the Screen creates or resumes application
state; closing it cancels active build/completion work and releases analysis
after its existing idle timeout.

The client preferences persist only UI conveniences:

- last project directory name below the configured project root;
- last active relative file path;
- caret and viewport positions, admitted only after reopening the same file;
- configured outer padding;
- project-tree width and diagnostics-panel height;
- diagnostics collapsed state.

They do not persist project UUIDs, absolute paths outside the configured root,
source contents, Artifacts, target identities, or K2 objects. Invalid or
missing remembered state falls back to the catalog start page.

## Opening and Previous-Screen Lifecycle

Register a configurable `Ctrl+I` global key mapping. It may open the IDE from
the world or from another Screen. The IDE records the previous Screen instance
as a transient return target; it is never serialised.

Opening over the terminal uses an explicit child-Screen suspension hook. The
terminal stops accepting input while hidden but retains enough replica identity
to request a full resynchronisation when restored. It does not leave an
unbounded server viewer subscription or replay hidden input. Disconnect,
dimension change, terminal invalidation, or a failed resync returns safely to
the world instead of restoring a stale terminal.

Escape closes the topmost completion popup or dialog first. Closing the IDE
itself runs the document close flow. Dirty conflict-free text is saved;
conflicted or failed text offers Save As, Discard, or Cancel. Only a completed
close restores the previous Screen.

## Screen Geometry and Appearance

The IDE uses a dark compact, flat-panel visual language. Minecraft font renders
labels, buttons, tooltips, dialogs, diagnostics messages, and status text. The
selected Compukters terminal font renders source text and line numbers.

The main panel nearly fills the viewport. Its outer padding is a client option
expressed in GUI pixels. Geometry clamps the configured value to preserve the
minimum editor area. On a narrow viewport it first reduces padding, then
collapses diagnostics, then hides the project tree behind a toolbar toggle.
The editor always retains the final minimum area; below that minimum the Screen
shows an explicit unsupported-size message rather than overlapping controls.

The normal layout contains:

- a header with project, active file, and `Local only` state;
- a toolbar with Resolve, Build or Cancel, Verify, Deploy, and Run;
- a left project tree separated by a draggable vertical splitter;
- one central editor with line numbers and independent scrollbars;
- a collapsible diagnostics/build panel separated by a draggable horizontal
  splitter;
- a status bar with save, resolve, build, analysis, caret, and Artifact summary.

Splitter positions are clamped to component minimums and persisted after a
completed drag. Resize or GUI-scale changes recompute geometry without
recreating the controller, document, history, or active worker request. The
editor viewport is clamped and the caret is revealed with the smallest
necessary movement.

Verify, Deploy, and Run are always visible in the normal toolbar but disabled
in this issue. Their tooltip is `No target attached`. This reserves the
accepted #538 workflow without inventing a local authority substitute.

## Start Page and Project Restoration

If the remembered project root and file remain valid, the IDE opens them
directly. Otherwise it shows a start page containing the bounded sorted project
catalog, `Create project`, and `Open project` actions. `Open project` selects
only a directory already below the configured Compukters project root; this
issue does not expose an unrestricted host filesystem picker.

Project creation uses the existing atomic staged catalog operation and opens
the generated `src/main.kt`. Duplicate, malformed, unsafe, or invalid projects
remain visible only as bounded actionable catalog errors; one bad entry cannot
silently redirect or escape the root.

## General Project Tree

Generalise `ProjectPath` into one canonical relative path value with explicit
factories or validation for arbitrary entries and Kotlin source entries.
Canonical paths:

- are relative and non-empty;
- use `/` separators;
- contain no empty, `.` or `..` component;
- contain no control character or invalid strict UTF-8;
- obey per-component, depth, and encoded-length limits.

The tree recursively lists admitted directories and regular files in unsigned
UTF-8 order. Symlinks and special files are rejected, never followed. Tree
entry count, depth, total metadata, file size, and total project bytes are
bounded before publication to the controller.

The UI supports:

- create an empty text file;
- create a directory;
- rename an entry within the same project without overwrite;
- delete a file after confirmation;
- recursively delete a directory after a confirmation that reports the
  bounded admitted entry count;
- open any regular file.

Mutation uses `SecureDirectoryStream` and revision/root identity checks. A
recursive delete first admits the complete bounded subtree without following
links, then deletes children bottom-up. A race, unexpected type, stale root, or
I/O failure stops with a typed error; the UI rescans rather than claiming a
partial operation succeeded. Delete is intentionally permanent in v1 and its
confirmation says so.

Rename updates an active file path only after the filesystem move succeeds.
Deleting or externally removing the active file runs the same dirty recovery
rules as root invalidation.

## Text and Binary Files

Any regular file within the configured per-file bound is probed with a strict
UTF-8 decoder. A valid file opens in the existing bounded UTF-16 editor,
regardless of extension. Invalid UTF-8 is classified as binary and presents an
honest non-editable placeholder with path and bounded size; no replacement
characters are materialised and no save action is available.

Kotlin lexical and semantic behavior is enabled only for `.kt` files.
Other strict UTF-8 files are plain text in this issue. Existing line endings
are preserved, and a new file uses LF.

`compukter.toml` and `compukter.lock` are ordinary editable text files. Resolve
still treats the lock as generated canonical project data: creating a missing
lock is explicit, and replacing an existing lock requires explicit Update
Dependencies confirmation. Direct user edits are permitted and validated at
Resolve/Build just like external host-editor edits.

Compilation includes only canonical regular `src/**/*.kt` files. Other files,
including arbitrary Kotlin files outside `src`, never enter the source
snapshot.

## Async Persistence and External Changes

The UI-thread editor may continue changing while a write is in flight. A save
request contains:

- session generation;
- canonical path and expected disk revision;
- materialised strict UTF-8 text;
- editor content revision represented by those bytes.

On success, only that editor revision becomes persisted. If newer edits exist,
the document remains dirty and a later debounce is armed. A stale disk revision
enters Conflict without overwriting either side.

Autosave is requested 500 ms after the last accepted edit and immediately on
the first following mouse activity, file/focus change, Build, or close. The
workspace executor coalesces redundant pending saves per active document but
never rewrites the expected revision of an admitted request.

External-change polling runs through the same executor at a bounded interval.
A changed clean file reloads atomically. A changed dirty file enters Conflict.
Conflict actions are Reload From Disk, Save As to a new canonical path, or
Cancel. Discard appears only in the close flow.

## Resolve and Build Flow

Resolve reads the exact persisted manifest and local installed Guest API
catalog, constructs a deterministic local compile profile, and proposes a
canonical lock. A missing lock is written only after successful explicit
Resolve. Updating an existing different lock requires a separate confirmed
Update Dependencies action. Save and Build never resolve or rewrite the lock.

Build is an asynchronous barrier:

1. capture the visible editor revision;
2. atomically persist it or stop on Conflict/failure;
3. rescan and admit the complete project tree;
4. load and canonically sort only `src/**/*.kt` into `ProjectSnapshot`;
5. decode and validate the persisted manifest and lock;
6. resolve and compare the exact local compile profile;
7. compute the complete compilation identity;
8. return an admitted cache hit or invoke the isolated compiler worker;
9. publish a matching Artifact summary or bounded diagnostics.

Only one active and one queued build remain possible under the existing
client-compilation policy. Duplicate identities share work. The toolbar changes
Build to Cancel while the active request is cancellable. Closing the Screen
cancels owned work. A late build result must match session, project, snapshot,
profile, and build identities before it can update view state.

A successful Artifact remains solely in the global content-addressed cache.
The status bar reports its bounded hash, size, cache-hit state, and completion
time. The project tree receives no `build` directory or executable.

## Highlighting, Diagnostics, and Completion

The incremental lexical highlighter remains synchronous and immediate for the
active Kotlin document. It is the fallback presentation whenever semantic
analysis is unavailable.

After a bounded edit debounce, `ide-client` forms the exact immutable project
snapshot and admitted local profile and requests the coalesced presentation
query from `ide-analysis-client`. Semantic tokens override lexical categories
only for the matching source and profile identity. Any edit immediately makes
the previous semantic presentation stale.

Diagnostics appear as:

- an underline/range in the matching active editor;
- a bounded row in the diagnostics panel with severity, path, line, column,
  and message;
- a tooltip over a matching underlined range.

Selecting a row opens its project file and reveals its UTF-16 range only when
the diagnostic snapshot still matches. Failed Build automatically expands the
diagnostics panel; ordinary editing marks old build diagnostics stale rather
than presenting obsolete positions.

Completion supports:

- immediate higher-priority `Ctrl+Space` requests;
- replaceable automatic requests after identifier or dot edits and a debounce;
- popup placement at the caret, clamped to the editor panel;
- mouse selection, arrows, Page Up/Down, Enter or Tab acceptance, and Escape;
- bounded item label, kind, signature/type detail, and origin;
- one atomic replacement edit using the worker-provided UTF-16 range.

The complete acceptance is one undo entry. Tab inserts indentation when no
completion popup is active. The popup closes on edit identity mismatch, file or
project change, focus loss, Escape, empty result, cancellation, or worker
failure.

Hover, declaration navigation, and references are not partially implemented
here; #544 owns their coherent UI and navigation-history behavior.

## Fonts and Unicode

Source glyphs and line numbers use the selected terminal `TerminalFontProfile`
and the same cell geometry. Missing glyphs render that profile's configured
replacement glyph without changing source text, offsets, selection, clipboard,
or saved bytes. UI labels and diagnostic prose use Minecraft font.

Caret, selection, clipping, horizontal scroll, line numbers, popup anchoring,
and mouse mapping all derive from the same font cell width and line height.
Changing font or GUI scale recomputes geometry and reveals the caret; it does
not rewrite the editor or restart workers.

## Error Model and Degradation

The renderer consumes bounded typed states rather than exceptions or raw
worker codes. Important categories remain distinct:

- catalog/project invalidation;
- unsafe path or entry type;
- file missing, binary, too large, stale, conflicted, or I/O failure;
- manifest or lock syntax/semantic failure;
- local profile unavailable or lock unsatisfied;
- compiler diagnostics, cancellation, queue full, timeout, memory limit,
  worker exit, protocol failure, or internal platform failure;
- analysis unavailable, stale, cancelled, or failed.

Filesystem mutation failure triggers a rescan before the next visible tree
state. Compiler failure does not erase the last admitted cache Artifact summary
but marks it as belonging to its older identity. Analysis failure removes
semantic overlays/completion and leaves editor, lexical highlighting, save,
Resolve, and Build usable.

Messages are bounded and human-readable. Host paths outside the configured
project root, stack traces, arbitrary stderr, PSI/FIR objects, and worker
internals never enter view state.

## Verification

### `ide-core`

- arbitrary canonical paths and Kotlin-source classification;
- strict UTF-8 versus binary detection;
- bounded tree scans and deterministic ordering;
- secure create, mkdir, rename, file delete, and recursive delete;
- symlink, special-file, traversal, race, root replacement, depth/count/size,
  and partial-failure rejection;
- arbitrary text document persistence, revision conflict, external reload, and
  recovery.

### `ide-client`

Use fake workspace, compiler, analysis, clock, and event executor to test:

- start page and last-session restoration;
- immutable state and bounded event admission;
- autosave while newer edits continue;
- file/project switching and close conflict flow;
- Resolve and explicit lock replacement;
- Build barrier, cache hit/miss, cancellation, duplicate/queued requests;
- exact identity and stale-result rejection;
- analysis degradation and recovery;
- semantic presentation, diagnostics navigation, automatic/manual completion,
  atomic acceptance, and popup closure.

### Minecraft adapter

- layout at representative viewport and GUI scales;
- configurable padding clamp and too-small behavior;
- draggable splitter clamp and persistence;
- focus routing, keyboard/character/mouse/wheel/clipboard mapping;
- popup placement and clipping;
- `Ctrl+I` registration and previous-Screen restoration;
- terminal suspension, close cleanup, and full resynchronisation.

### Integration and Packaging

- real forked compiler Resolve/Build with Unicode diagnostics;
- real forked analysis semantic tokens and completion;
- worker timeout/cancel/crash recovery without render-thread blocking;
- exact payload inventory and licenses;
- production archive/classpath gates proving K2, Analysis API, PSI, FIR, and
  IntelliJ implementation classes exist only inside inert worker payloads;
- regression coverage for terminal, guest compiler, and server runtime paths.

Run `./gradlew-sandbox --parallel verifyLocalFast`, then
`./gradlew-sandbox --parallel verifyLocalFull`, and `git diff --check`.

Manual `runClient` acceptance covers opening over the world and terminal,
project creation/restoration, arbitrary text and binary files, tree mutations,
host-editor changes, autosave/conflict recovery, fonts, GUI scale, resize,
Resolve, Build/Cancel, diagnostics, completion, and safe return to the previous
Screen.

## Delivery Order

1. Generalise secure project paths, tree scanning, arbitrary text/binary file
   admission, and mutation.
2. Add `ide-client` state, commands, event queue, workspace adapter, lifecycle,
   and fake-backed tests.
3. Wire local resolution, compilation cache/worker, analysis worker, and inert
   payload packaging.
4. Add screen geometry, config, panels, splitters, and rendering primitives.
5. Connect start page, tree, editor, arbitrary-file input, dialogs, and
   persistence.
6. Connect Resolve, Build/Cancel, Artifact summary, and diagnostics.
7. Connect semantic overlays and completion popup.
8. Add keybind, previous-Screen/terminal lifecycle, restoration, archive gates,
   integration tests, and manual usability corrections.

Every implementation commit and plan references #540. The issue remains `Now`
through manual `runClient` acceptance; fully automated success alone does not
close a graphical workflow requiring user review.

## Follow-Ups

- #544: hover, declaration navigation, and project references UI.
- #538: target context, Artifact verification, deployment, and launch.
- #536: separate target filesystem explorer.
- #539: complete end-to-end IDE lifecycle and final playability validation.
