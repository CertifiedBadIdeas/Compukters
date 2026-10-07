---
layout: default
title: Tooling workers and IDE ownership
section: contributors
permalink: /ARCHITECTURE/tooling/
---

# Tooling workers and IDE ownership

[← Architecture](../architecture.md)

## Worker isolation and analysis

Compiler and analysis workers are pinned, isolated JVM processes. Their payloads are assembled into one bounded
`k2-tooling-workers.zip.xz`: nested runtime JARs and the carrier ZIP use canonical stored entries, then the complete
ZIP is compressed as one checksummed XZ stream. The outer runtime uses XZ for Java's pure-Java streaming decoder with
a fixed memory limit and feeds the decoded ZIP directly into bounded, hash-verified, atomic publication.
An external copy of `tooling.bundle` identifies the expected content-addressed cache directory before the carrier is
opened. A cache hit validates the complete file tree and hashes without decompressing XZ; a corrupt hit is
retained until a replacement has been fully decoded and verified, then replaced with the valid tree.
Kotlin compiler and Analysis API internals stay inside the workers and do not enter the mod runtime classpath.
`worker-client` owns the generic payload publication, process, framing, deadline, and immutable-byte machinery shared
by both worker clients.
The production mod archive embeds Tomlj under a private relocated Compukters namespace. Tomlj carries its own
relocated ANTLR runtime, so no standalone ANTLR or Checker Qual dependency is needed. These libraries do not enter
NeoForge's module layer as separate automatic modules and therefore cannot collide with loader-provided versions.

The two execution-producing paths are:

```text
In-computer source
  -> Rust captures source and output preconditions
  -> server-global ServerCompilerService
  -> isolated compiler K2 worker
  -> server-global persistent artifact cache
  -> Rust re-verifies and atomically installs the artifact

Client IDE project
  -> bounded project snapshot + manifest + lock + resolved platform profile
  -> client compilation service and client-local artifact cache
  -> isolated compiler K2 worker
  -> attach and verify against a server target
  -> revision-checked deployment into the Rust filesystem
  -> optional canonical terminal submission to run the executable
```

Both compilation services send ordered project sources, target settings, worker identity, platform-module identities,
and limits through the same bounded compiler protocol. `compiler-k2-engine` owns the shared FIR-to-IR and Compukter
lowering implementation; `compiler-k2` supplies the isolated compiler-worker entry point and payload.

The client IDE has a separate analysis path. `ide-analysis-client` owns the bounded protocol, scheduling,
cancellation, and worker lifetime without depending on K2. `ide-analysis-k2` owns the isolated incremental K2
workspace and answers diagnostics, completion, symbol, reference, expression, and semantic-token queries. Compilation
and analysis use the same resolved platform bundle and source-snapshot identities, but have separate worker sessions
and result contracts.

Analysis protocol v15 adds a peripheral-provider role to semantic tokens and completion kinds. The worker identifies providers through inheritance of `PeripheralProvider`, including companion values; type references retain their ordinary class role. The editor renders provider values in the dedicated palette color with a `P` completion badge.

The protocol also carries explicit Kotlin format, rename-admission, and parameter-information requests.
Completion records carry immutable ordered UTF-16 label-relative name-match ranges, derived by the same worker-owned
contiguous word-fragment matcher used for filtering and ranking. The client renders these ranges without repeating
the matching algorithm. Expression information carries optional bounded plain-text KDoc from the resolved project
declaration or its exact attached platform source. Both fields are validated at the worker/client boundary, including
Unicode boundaries, nonoverlapping ranges and negotiated UTF-8 documentation limits. Hover wraps documentation and
bounds its visible height; it does not execute HTML or fetch external content.
Callable completions carry bounded independent presentation fields for the declared extension receiver, package and
specialized return type. Type-scope member signatures retain K2 substitutions; extension applicability supplies
receiver substitutions through the pinned K2 completion checker. Explicit receiver checks use a query-only contextual
Kotlin expression fragment with a synthetic selector, so literals and incomplete safe-calls still receive K2 inference
without mutating admitted source PSI or changing diagnostic scheduling. Safe-call lookup uses the non-null receiver
type; unknown callable type parameters remain symbolic.
Member functions have a distinct completion kind. Presentation and insertion metadata share the same resolved
signature without making the client parse rendered Kotlin declarations.
Presentation requests explicitly choose whether to include diagnostics. During an automatic or manual completion
session the client cancels diagnostic work and requests semantic-only presentation; edited diagnostics are rebased
outside the changed range rather than discarded wholesale. Empty completion results, dismissal, insertion and
parameter-information transitions resume diagnostics for the latest snapshot. Semantic-only results cannot erase
previous diagnostics, and the protocol rejects mismatched diagnostics policies.
Completion items include an optional semantic call shape: presence of parameters, required arguments before a
trailing lambda, and a final non-null function parameter. K2 derives this from resolved symbols (including type
aliases), not rendered signatures; imports, callable references, type positions and shorthand string templates
receive name-only insertions. The client uses that shape to add or reuse call delimiters and records the primary
caret together with autoimports in one atomic editor history entry. Newly generated delimiters join smart typing's
tracked closers. Structural Enter expands an empty Kotlin brace pair even when it was inserted by completion or
loaded from source, removing inner padding and preserving the document's line endings and leading indentation.
Protocol v13 workers are rejected at handshake;
the platform bundle, compiler protocol and VM artifact ABI are unchanged. Rename
returns bounded editable project locations after validating a single Kotlin identifier and speculatively checking
project diagnostics and reference bindings. The worker restores its original PSI, source identity, and completion
state even on cancellation; a failed restoration invalidates the workspace and requires a fresh snapshot open.
Rename currently rejects projects with analysis errors, read-only library symbols, the `main` entry point,
and inheritance or convention-based declarations rather than attempting partial refactoring.
Presentation results
include bounded method-name ranges with non-negative project usage counts. K2 gathers declarations in the active
source and resolves references in one project traversal under the presentation session, keeping overloads and
same-named methods separate. Counts share the semantic-token item bound, are checked against the correlated source,
and appear as clickable same-line labels to the right of declarations; zero counts are hidden. During pending
analysis, exact edits rebase the last known counts for untouched method names, avoiding flicker. Edits touching
or extending a name discard its label; a fresh accepted presentation replaces provisional counts, while stale
responses remain rejected. The previous worker protocol is intentionally incompatible and rejected at handshake.
Parameter information
resolves the innermost call at a UTF-16 caret into a bounded, deterministically ordered set of K2-substituted callable
signatures with active-parameter spans; client-side snapshot, revision, path, caret, and call-range checks reject stale
popup results. The format request includes the exact editor text and
UTF-16 caret captured by the Reformat Code action, so formatting does not depend on whether the semantic snapshot has
caught up. The worker runs ktlint standard rules from a compiler-free nested runtime whose shaded IntelliJ references
are relocated back to the ordinary namespace. Its child classloader reloads the worker's existing Kotlin compiler JAR
files with a platform parent: the distribution stores one compiler copy, while formatter and Analysis API retain
separate IntelliJ global state. Formatted text is bounded by the negotiated source-file limit; the client applies a
current result as one undoable edit and leaves the document dirty for a separate save. Stale results are discarded,
and formatter failures warn without changing or saving the source. Ctrl+S, autosave, and implicit saves do not format.

## Client editor and terminal presentation

The client renders the fixed 51x19 grid in a centered compact panel while the world remains visible through a
translucent dim layer. A separate footer presents rolling `CPU` utilization, current Guest heap and virtual-disk
usage, and concise lifecycle activity. Here `CPU` is the virtual computer's consumed/granted semantic Guest-unit
budget, not physical host timing. Terminal windows, IDE terminal overlays and in-world text displays use
packaged JetBrains Mono NL with 6x13 cells and no font selector. The former terminal font preference is no longer
part of client configuration; old values are removed by NeoForge configuration correction without affecting IDE layout.
The IDE code editor, completion list and hover information use the same bundled TrueType face without ligatures,
with 6x12 editor cells (size 10 and line spacing 1.2); terminal/display cells remain 6x13.
Editor and terminal drawing use the same font resource ID directly, without a reference alias: Minecraft 1.21
warms font IDs in parallel and its FreeType provider does not synchronize access to a shared native face.
The editor metrics drive glyph placement, caret and selection geometry, and hit testing. IDE chrome, dialogs,
menus and tooltips use the same face and six-pixel advances, with the matching glyph baseline offset.
The Minecraft 1.21.1 adapter opts these explicit JetBrains Mono draws into linear texture filtering. A bounded
render-type wrapper delegates vanilla shader, blend, depth and geometry state, applies filtering after vanilla's
nearest-filter setup, and restores the previous texture filters, binding and active unit after drawing. Other fonts
remain unchanged. The 26.1 adapter submits prepared glyphs with a cached linear GUI sampler and uses NeoForge's
linear text render types for in-world displays, preserving clipping, transforms and polygon offset without global
font or texture-state changes. Visual quality still requires an in-client comparison at the chosen UI scale.
The IDE controller owns a bounded project-document cache (128 editor-limited documents by default), so returning to
a file preserves its caret, viewport and undo history. Dirty background buffers participate in autosave and are
drained before a build, project transition or ordinary close. Polling invalidates clean changed buffers and retains
dirty conflicts. Analysis snapshots overlay unsaved project buffers under the admitted source limits and recompute
their source identity; they never analyze a mix of renamed active text and stale background disk text.
Explicit Find Usages reuses the serialized reference/declaration analysis lane and requires a single resolved
declaration. The controller publishes bounded immutable source-context rows in the lower panel, navigates through
the existing project navigation history, and rejects queued results invalidated by source edits or project changes.
The controller also owns one deduplicated diagnostic view for the problems panel, gutter, counters and F2 navigation.
Navigable rows carry exact source evidence; build diagnostics retain bounded admitted source text. Opening a target
checks that evidence before applying a UTF-16 offset, including after an asynchronous open or implicit save. Stale
build messages remain visible but do not contribute clickable locations or gutter markers.
The terminal screen can suspend its observation and open the IDE, whose target terminal view consumes the same
replicated terminal state but does not yet display these standalone-terminal gauges. Returning from the IDE reopens
the standalone observation without reopening the screen and receives a fresh authoritative terminal state.

## Client-local projects and Git

`ide-core` owns backend-independent Git contracts and the project catalog. Owned projects live beneath the client
catalog root; external roots are registered in place through bounded catalog records and retain their filesystem
identity checks. `.git` components are reserved and excluded before tree/content budget accounting, including in
compiler source scanning. `.gitignore` remains an ordinary editable file. Clone materializes an owned staging root,
validates the manifest and tree, then publishes it through the catalog's secure directory operation. Failure cleanup
never follows directory symlinks. Unavailable external roots do not hide other catalog projects.

`ide-git` is the JGit implementation leaf; `ide-client` depends only on the contracts. The NeoForge client injects this
backend into `DefaultIdeWorkspace`, whose bounded single I/O executor serializes Git and document operations. Git
results carry controller generation/operation identities. The controller drains dirty buffers first, pauses edits
while Git runs, rejects results from a departed project and invalidates affected tree/document/analysis/build state.
A failed create/import/clone retains the current project instead of clearing its buffers prematurely. File/folder
renames rebase cached descendant paths while preserving editor state.

Read-only `inspectGit` shares the bounded I/O executor but bypasses foreground save barriers and edit suspension.
The controller admits at most one inspection, throttled to one second, and checks project generation, foreground
operation identity and buffer revision before displaying line changes. `SourceChanges` compares the supplied
buffer with HEAD using bounded HistogramDiff, normalizes CRLF/LF and leaves disk/index untouched. Binary,
ignored new files and over-budget comparisons omit markers. Status colors share one projection for tree,
parent folders, active-file title and Commit rows; gutter markers do not replace syntax or semantic coloring.

Remote operations admit HTTPS URLs without embedded credentials, query or fragment. Tokens live in masked client
session input and an explicitly closeable credential object; no credential data enters preferences, project files,
worker snapshots or server payloads. JGit copies are cleared after each operation. Commit author identity is explicit.
The UI commits selected saved working files, preserving unrelated staged content. The backend never force-pushes and
disables commit/push hooks without saving its temporary hook-path override. Linked worktrees, submodule/LFS integration,
SSH and merge UI are deferred.

Pull uses JGit `FF_ONLY` with rebase disabled and requires a clean working tree. Divergence preserves local HEAD,
index and working files; tests exercise that exact failure boundary. Cancellation and a 60-second transport/progress
timeout bound cooperative operations; already completed mutations are not rolled back. Git defaults bound status and
branch lists to 4096 entries, history to 100 commits and diff output to 256 KiB. A separate 512 MiB repository metadata
check runs before/after repository operations and after clone; it is post-transfer validation, not a streaming quota. Metadata validation also limits entries to 100,000 and depth to 32, rejecting symlinks.

The shared Git panel and prompt workflows feed both version-specific screens. Production archives relocate JGit,
JavaEWAH and Commons Codec into the private vendor namespace and retain their license notices; SLF4J remains loader
provided. Both archive gates load and exercise the relocated Git/resource runtime. Behavioral evidence lives in
`JGitBackendTest`, `GitWorkspaceIntegrationTest`, catalog/compiler/controller tests and shared prompt/input/renderer
tests. In-client layout and interaction still require an observed scenario under the verification policy.

The Git UI owns session-local checkbox selection and bounded commit/author drafts. It uses `CommitSelected` to commit
current saved working files for exactly the selected changed paths, with JGit's path-only commit preserving unrelated
index entries. New selected files are admitted to the index first; failed operations can change selected index entries
but never implicitly commit unrelated staged content. Preview compares HEAD with the working tree, including an unborn
HEAD. The draft survives a commit failure and its message clears only on success. Author fields remain in the IDE session;
project changes clear message/selection. Commit & Push submits push only after the matching successful commit reply.
Draft editing reuses `EditorDocument`, and controller ownership/save barriers/generation checks remain authoritative.


The left tool stripe uses shared vector icons for Project, Commit, Terminal and Problems, with Git Log anchored at
its bottom. `IdeBottomPanelView` owns the active Problems/GitLog tab (or a hidden window) and bounded history scroll.
History is cached separately from the Commit result/preview and resets per project. Opening Git Log returns to the
editor without clearing its commit draft; history replies cannot replace the saved-working-file preview. The bottom
window reuses the diagnostics splitter/height and shows Problems/Find Usages or commit history in its content area.
Both target screens derive bottom visibility from controller state; the splitter owns height only. Geometry fallback
may collapse this window when there is insufficient room for the source editor.
