# Local-Only Graphical IDE Screen Implementation Plan

> Issue: [#540](https://github.com/CertifiedBadIdeas/Compukters/issues/540)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. The user explicitly requested inline execution without subagents or a separate worktree. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a reusable host-neutral IDE application controller and the first local-only Minecraft IDE Screen with secure arbitrary-file project editing, Resolve/Build/Cancel, diagnostics, semantic highlighting, and bounded completion.

**Architecture:** Generalise the existing `ide-core` project/document boundary, then add an `ide-client` module that owns application state and async workspace/compiler/analysis orchestration without Minecraft or K2 implementation dependencies. Keep `v26_1-neoforge` as a thin input/render/config/lifecycle adapter and package compiler/analysis workers only as inert payload resources.

**Tech Stack:** Kotlin 2.4.10, JDK 25, Gradle Kotlin DSL, Kotlin test/JUnit Platform, K2 Analysis API Standalone child JVM, NeoForge 26.1 client Screen APIs, Minecraft `GuiGraphicsExtractor`, existing terminal bitmap fonts.

---

## Execution Constraints

- Work inline on the current `dev` branch; do not create a worktree or delegate to subagents.
- Execute every `gh` command and every remote Git command outside the sandbox as required by `AGENTS.md`.
- Use `apply_patch` for source and build-file edits.
- Follow red-green-refactor for each task. Do not combine later UI behavior into an earlier foundational commit.
- Preserve user changes in a dirty worktree and stop if an unrelated edit overlaps a planned file.
- Use `/tmp/compukters-gradle-cache-540` as the Gradle project cache for focused verification.
- Keep #540 in Roadmap `Now` until the final manual `runClient` checklist is accepted by the user.

## Planned File Map

### Existing `ide-core`

- `project/fs/ProjectPath.kt`: one canonical relative project path plus explicit Kotlin-source classification.
- `project/ProjectLimits.kt`: entry-count, depth, path, metadata, arbitrary-file, and total-tree limits.
- `project/fs/SecureProjectFiles.kt`: race-resistant primitives only; no UI policy.
- `project/tree/ProjectTree.kt`: immutable tree entries and strict UTF-8/binary file metadata.
- `project/tree/ProjectTreeStore.kt`: bounded scan/create/mkdir/rename/delete/open operations.
- `project/document/ProjectDocumentStore.kt`: arbitrary strict UTF-8 document I/O with revisions.
- `project/document/ProjectDocumentSession.kt`: editor/autosave/conflict behavior independent of `.kt` extension.

### New `ide-client`

- `IdeClientLimits.kt`: queue, polling, preference, view, and message limits.
- `state/IdeViewState.kt`: immutable renderer-facing state.
- `state/IdeCommand.kt`: host-neutral user intent.
- `state/IdeEditorInput.kt`: explicit renderer-neutral editing/navigation operations.
- `state/IdeEvent.kt` and `BoundedIdeEventQueue.kt`: bounded async completion handoff.
- `workspace/IdeWorkspace.kt`: async catalog/tree/document/mutation boundary.
- `workspace/DefaultIdeWorkspace.kt`: one serial filesystem executor.
- `preferences/IdePreferences.kt`: bounded relative last-session/layout preferences.
- `controller/IdeClientController.kt`: UI-thread state machine and lifecycle.
- `build/IdeBuildCoordinator.kt`: Resolve, save barrier, canonical snapshot, cache/compiler state.
- `analysis/IdeAnalysisCoordinator.kt`: presentation/completion integration and stale-result rejection.
- `testing/Fake*` files remain under test sources only.

### `v26_1-neoforge`

- `impl/ide/IdeClientBootstrap.kt`: keybind, tick, open/close, path and service construction.
- `impl/ide/IdeScreen.kt`: Minecraft Screen adapter and rendering composition.
- `impl/ide/IdeRenderGeometry.kt`: deterministic panel/editor/popup rectangles and splitters.
- `impl/ide/IdeInputAdapter.kt`: Minecraft events to `IdeCommand`.
- `impl/ide/IdeRenderer.kt`: flat panels, tree, editor, diagnostics, completion, dialogs, and status.
- `impl/ide/IdeClientPreferences.kt`: NeoForge config adapter.
- `impl/terminal/TerminalScreen.kt` and `TerminalClientNetwork.kt`: explicit child-Screen suspension and resync.
- build scripts/resources: inert analysis worker packaging and isolation gates.

## Task 1: Generalise Canonical Project Paths and Strict File Admission

**Files:**
- Modify: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/fs/ProjectPath.kt`
- Modify: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/ProjectLimits.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/tree/ProjectTree.kt`
- Create: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/tree/ProjectTreeStore.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/project/tree/ProjectTreeStoreTest.kt`
- Modify: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/project/fs/SecureProjectFilesTest.kt`

- [ ] **Step 1: Write failing canonical-path and tree-admission tests**

Add tests with this public contract:

```kotlin
@Test
fun `canonical project paths classify sources without restricting ordinary files`() {
    assertEquals("notes/readme.txt", ProjectPath.file("notes/readme.txt").value)
    assertFalse(ProjectPath.file("notes/readme.txt").isKotlinSource)
    assertTrue(ProjectPath.file("src/main.kt").isKotlinSource)
    assertFalse(ProjectPath.file("main.kt").isKotlinSource)
    assertFailsWith<IllegalArgumentException> { ProjectPath.file("../escape.kt") }
    assertFailsWith<IllegalArgumentException> { ProjectPath.file("src\\main.kt") }
}

@Test
fun `tree is bounded ordered and distinguishes strict text from binary`() = withProject { project ->
    write(project, "src/z.kt", "fun z() = Unit".encodeToByteArray())
    write(project, "notes.txt", "hello".encodeToByteArray())
    write(project, "blob.bin", byteArrayOf(0xC3.toByte(), 0x28))

    val tree = ProjectTreeStore(project.handle, ProjectLimits()).scan()

    assertEquals(listOf("blob.bin", "compukter.toml", "notes.txt", "src", "src/main.kt", "src/z.kt"), tree.flatten().map { it.path.value })
    assertIs<ProjectFileKind.Binary>(tree.entry(ProjectPath.file("blob.bin")).kind)
    assertIs<ProjectFileKind.Text>(tree.entry(ProjectPath.file("notes.txt")).kind)
}
```

Also cover path depth, encoded path bytes, entry count, total metadata bytes, symlink, special file, oversized file, root replacement, and unsigned UTF-8 ordering.

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :ide-core:test --tests '*ProjectTreeStoreTest*' --tests '*SecureProjectFilesTest*'
```

Expected: compilation fails because `ProjectPath.file`, `ProjectTreeStore`, and `ProjectFileKind` do not exist.

- [ ] **Step 3: Implement the canonical path and immutable tree model**

Replace the source-only public path factory with this shape while retaining `source` as a checked compatibility factory:

```kotlin
class ProjectPath private constructor(
    val value: String,
    internal val components: List<String>,
) {
    val isKotlinSource: Boolean
        get() = components.size >= 2 && components.first() == "src" && components.last().endsWith(".kt")

    companion object {
        fun file(value: String): ProjectPath = parse(value)

        fun source(value: String): ProjectPath = file(value).also {
            require(it.isKotlinSource) { "source path must name Kotlin below src" }
        }

        internal fun direct(value: String): ProjectPath = file(value).also {
            require(it.components.size == 1) { "path must contain exactly one component" }
        }
    }
}
```

Add explicit validated limits to `ProjectLimits`:

```kotlin
val treeEntries: Int = 4 * 1024
val treeDepth: Int = 32
val pathUtf8Bytes: Int = 4 * 1024
val treeMetadataBytes: Int = 2 * 1024 * 1024
val projectFileBytes: Int = 16 * 1024 * 1024
val projectBytes: Long = 64L * 1024 * 1024
```

Create immutable entries:

```kotlin
sealed interface ProjectFileKind {
    data object Directory : ProjectFileKind
    data class Text(val utf8Bytes: Int) : ProjectFileKind
    data class Binary(val bytes: Long) : ProjectFileKind
}

data class ProjectTreeEntry(
    val path: ProjectPath,
    val kind: ProjectFileKind,
    val revision: FileRevision?,
)

class ProjectTree private constructor(val entries: List<ProjectTreeEntry>) {
    fun flatten(): List<ProjectTreeEntry> = entries
    fun entry(path: ProjectPath): ProjectTreeEntry = requireNotNull(entries.singleOrNull { it.path == path })
}
```

Implement `ProjectTreeStore.scan()` through `SecureDirectoryStream`, `NOFOLLOW_LINKS`, strict bounded UTF-8 probing, SHA-256 revisions for regular files, and the existing unsigned UTF-8 comparator. Read at most `projectFileBytes + 1` per file; classify decoder failure as Binary rather than returning replacement characters.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run the Step 2 command.

Expected: all path/tree/security tests pass.

- [ ] **Step 5: Commit the admitted tree boundary**

```bash
git add modules/ide-core
git commit -m "feat(ide): admit general project trees (#540)"
```

## Task 2: Add Secure Tree Mutation and Arbitrary Text Documents

**Files:**
- Modify: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/fs/SecureProjectFiles.kt`
- Modify: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/tree/ProjectTreeStore.kt`
- Modify: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/document/ProjectDocumentStore.kt`
- Modify: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/project/document/ProjectDocumentSession.kt`
- Test: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/project/tree/ProjectTreeMutationTest.kt`
- Modify: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/project/document/ProjectDocumentStoreTest.kt`
- Modify: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/project/document/ProjectDocumentSessionTest.kt`

- [ ] **Step 1: Write failing mutation and arbitrary-text tests**

Use the following result contract:

```kotlin
sealed interface ProjectMutationResult {
    data class Changed(val tree: ProjectTree) : ProjectMutationResult
    data class Conflict(val path: ProjectPath) : ProjectMutationResult
    data object ProjectInvalidated : ProjectMutationResult
}
```

Add tests proving:

```kotlin
@Test
fun `create rename and confirmed recursive delete stay inside the project`() = withProject { project ->
    val store = ProjectTreeStore(project.handle, ProjectLimits())
    assertIs<ProjectMutationResult.Changed>(store.createDirectory(ProjectPath.file("notes")))
    assertIs<ProjectMutationResult.Changed>(store.createText(ProjectPath.file("notes/a.txt")))
    assertIs<ProjectMutationResult.Changed>(store.rename(ProjectPath.file("notes/a.txt"), ProjectPath.file("notes/b.txt")))
    val admitted = store.admitDelete(ProjectPath.file("notes"))
    assertEquals(2, admitted.entries)
    assertIs<ProjectMutationResult.Changed>(store.delete(admitted))
}

@Test
fun `any strict UTF8 project file opens and saves with revisions`() = withProject { project ->
    val path = ProjectPath.file("compukter.lock")
    val store = ProjectDocumentStore(project.handle, ProjectLimits())
    val opened = store.open(path)
    val saved = store.save(path, opened.revision, "edited\n")
    assertEquals("edited\n", assertIs<DocumentSaveResult.Saved>(saved).snapshot.text)
}
```

Cover no-overwrite rename, stale admitted delete, unexpected symlink/type race, binary-open rejection, active-file rename/delete, non-empty recursive delete bounds, temporary-file cleanup, and root invalidation.

- [ ] **Step 2: Run the mutation/session tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :ide-core:test --tests '*ProjectTreeMutationTest*' \
  --tests '*ProjectDocumentStoreTest*' --tests '*ProjectDocumentSessionTest*'
```

Expected: compilation fails for mutation APIs and `ProjectPath.file` document use.

- [ ] **Step 3: Implement secure mutation and text admission**

Add an opaque delete admission token so UI confirmation cannot be replayed after the tree changes:

```kotlin
data class AdmittedProjectDelete internal constructor(
    val path: ProjectPath,
    val rootIdentity: ProjectRootIdentity,
    val entries: Int,
    internal val revisions: Map<ProjectPath, FileRevision?>,
)
```

Implement create with `CREATE_NEW`, mkdir with a real directory check, rename through same-root `SecureDirectoryStream.move`, and delete bottom-up after comparing every admitted path/type/revision. Never use `Path.toFile().deleteRecursively()` for project mutation. Return a fresh bounded scan only after success; on any uncertain failure require the caller to rescan.

Generalise `readSource`/`writeSource` names to `readFile`/`writeFile`, retain delegating compatibility methods until all callers migrate, and update messages from “source” to “project file”. `ProjectDocumentStore.open` must use a strict UTF-8 decoder and throw `ProjectDocumentException("project file is binary")` on malformed bytes.

Use `limits.projectFileBytes` for arbitrary documents and `limits.sourceFileBytes` again when assembling compiler sources.

- [ ] **Step 4: Run focused and complete `ide-core` checks**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 :ide-core:check
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit mutation and documents**

```bash
git add modules/ide-core
git commit -m "feat(ide): edit and mutate project files (#540)"
```

## Task 3: Create the Host-Neutral `ide-client` Module and Bounded State Primitives

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/ide-client/build.gradle.kts`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/IdeClientLimits.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/state/IdeViewState.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/state/IdeCommand.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/state/IdeEditorInput.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/state/IdeEvent.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/state/BoundedIdeEventQueue.kt`
- Create: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/state/BoundedIdeEventQueueTest.kt`
- Create: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/IdeClientRuntimeIsolationTest.kt`

- [ ] **Step 1: Add failing state/queue tests before including the module in consumers**

Define the expected bounded behavior:

```kotlin
@Test
fun `replaceable events coalesce and terminal events reject overflow`() {
    val queue = BoundedIdeEventQueue(capacity = 2)
    queue.offer(IdeEvent.PollCompleted(1, tree(1)))
    queue.offer(IdeEvent.PollCompleted(1, tree(2)))
    queue.offer(IdeEvent.BuildCompleted(1, buildResult()))
    assertEquals(listOf(IdeEvent.PollCompleted(1, tree(2)), IdeEvent.BuildCompleted(1, buildResult())), queue.drain())
    assertFalse(queue.offer(IdeEvent.BuildCompleted(1, buildResult())))
}

@Test
fun `view state never retains mutable collections`() {
    val input = mutableListOf(projectDescriptor())
    val state = IdeViewState.startPage(input)
    input.clear()
    val start = assertIs<IdePageState.Start>(state.page)
    assertEquals(1, start.projects.size)
}
```

- [ ] **Step 2: Include the new module and confirm RED**

Add `include("ide-client", modulesDir)` to `settings.gradle.kts`, create the build script with production dependencies on `ide-core`, `ide-analysis-client`, `compiler-runtime`, `compiler-client`, `worker-client`, and Kotlin stdlib, then run:

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 :ide-client:test
```

Expected: compilation fails because state types are missing.

- [ ] **Step 3: Implement immutable state, commands, events, and queue**

Use a small renderer-neutral state hierarchy:

```kotlin
sealed interface IdePageState {
    data class Start(val projects: List<IdeProjectSummary>, val error: IdeProblem?) : IdePageState
    data class Workspace(val value: IdeWorkspaceView) : IdePageState
}

data class IdeViewState(
    val generation: Long,
    val page: IdePageState,
    val dialog: IdeDialogState?,
    val busy: Set<IdeBusyOperation>,
) {
    companion object {
        fun startPage(projects: List<IdeProjectSummary>): IdeViewState =
            IdeViewState(
                generation = 0,
                page = IdePageState.Start(projects.toList(), error = null),
                dialog = null,
                busy = emptySet(),
            )
    }
}

sealed interface IdeEditorInput {
    data class Type(val text: String) : IdeEditorInput
    data class SetCaret(val offsetUtf16: Int, val extendSelection: Boolean) : IdeEditorInput
    data class Move(val direction: IdeMoveDirection, val extendSelection: Boolean) : IdeEditorInput
    data object Backspace : IdeEditorInput
    data object Delete : IdeEditorInput
    data object Enter : IdeEditorInput
    data object Tab : IdeEditorInput
    data object Undo : IdeEditorInput
    data object Redo : IdeEditorInput
    data object SelectAll : IdeEditorInput
}

enum class IdeMoveDirection { Left, Right, Up, Down, Home, End }

sealed interface IdeCommand {
    data class OpenProject(val directoryName: String) : IdeCommand
    data class OpenFile(val path: ProjectPath) : IdeCommand
    data class Edit(val input: IdeEditorInput) : IdeCommand
    data object Save : IdeCommand
    data object Resolve : IdeCommand
    data object Build : IdeCommand
    data object CancelBuild : IdeCommand
    data object ManualCompletion : IdeCommand
    data object CloseRequested : IdeCommand
}
```

`BoundedIdeEventQueue` must copy event payload collections, synchronise only queue admission/drain, coalesce only explicit `ReplaceableIdeEvent` keys, preserve FIFO among surviving events, and return `false` instead of blocking on non-replaceable overflow.

Add a Gradle runtime allowlist task patterned after `ide-analysis-client`: allow only expected project modules plus Kotlin/TOML/ANTLR/checker dependencies and reject `ide-analysis-k2`, `compiler-k2`, Analysis API, IntelliJ, PSI, and FIR fragments.

- [ ] **Step 4: Run module tests and isolation gate**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 :ide-client:check
```

Expected: BUILD SUCCESSFUL and `assertIdeClientRuntime` passes.

- [ ] **Step 5: Commit the module boundary**

```bash
git add settings.gradle.kts modules/ide-client
git commit -m "feat(ide): add host-neutral client state (#540)"
```

## Task 4: Add the Serial Async Workspace Boundary

**Files:**
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/workspace/IdeWorkspace.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/workspace/DefaultIdeWorkspace.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/preferences/IdePreferences.kt`
- Test: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/workspace/DefaultIdeWorkspaceTest.kt`
- Test: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/preferences/IdePreferencesTest.kt`

- [ ] **Step 1: Write failing executor, ordering, and preference-admission tests**

Target this boundary:

```kotlin
interface IdeWorkspace : AutoCloseable {
    fun projects(): CompletableFuture<List<ProjectDescriptor>>
    fun createProject(name: String): CompletableFuture<ProjectDescriptor>
    fun tree(project: ProjectHandle): CompletableFuture<ProjectTree>
    fun open(project: ProjectHandle, path: ProjectPath): CompletableFuture<ProjectFileOpenResult>
    fun save(request: IdeSaveRequest): CompletableFuture<IdeSaveResult>
    fun mutate(request: IdeMutationRequest): CompletableFuture<ProjectMutationResult>
    fun buildInput(project: ProjectHandle): CompletableFuture<IdeBuildInput>
}
```

Tests must prove all calls execute on exactly one named non-caller thread, preserve admission order, reject after close, bound submitted operations, and copy input bytes/text. Preferences accept only a project directory name and canonical relative path, clamp layout values, and discard invalid remembered state.

- [ ] **Step 2: Run the workspace tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :ide-client:test --tests '*DefaultIdeWorkspaceTest*' --tests '*IdePreferencesTest*'
```

Expected: compilation fails for `IdeWorkspace` and preference types.

- [ ] **Step 3: Implement the one-thread workspace**

Use one bounded `ThreadPoolExecutor`:

```kotlin
ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(limits.workspaceQueue),
    namedDaemonThreadFactory("compukters-ide-workspace"),
    ThreadPoolExecutor.AbortPolicy(),
)
```

Translate `RejectedExecutionException` into a typed `IdeWorkspaceFailure.Busy`; never run rejected work on the caller. `buildInput` rescans once and returns copied manifest/lock bytes plus only sorted admitted `src/**/*.kt` files, enforcing `WorkerLimits` separately from general project limits.

Define `IdePreferencesStore` as an injected load/save interface with a canonical bounded `IdePreferences` value; no filesystem or NeoForge config type enters `ide-client`.

- [ ] **Step 4: Run workspace and module checks**

Run Step 2, then `:ide-client:check`.

Expected: both commands pass.

- [ ] **Step 5: Commit the async workspace**

```bash
git add modules/ide-client
git commit -m "feat(ide): serialize local workspace IO (#540)"
```

## Task 5: Implement Project, Editor, Autosave, and Close State

**Files:**
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeClientController.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeControllerClock.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/state/IdeEditorView.kt`
- Test: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeClientControllerTest.kt`
- Test: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeAutosaveControllerTest.kt`

- [ ] **Step 1: Write failing state-machine tests**

Use fake workspace futures controlled by the test. Cover start-page fallback, last-project restoration, active file, arbitrary text versus binary, editor commands, newer edits during save, clean external reload, dirty conflict, file switch, rename/delete, invalidated root recovery, and close Save As/Discard/Cancel.

Representative assertion:

```kotlin
@Test
fun `save completion marks only the submitted editor revision`() {
    val fixture = controllerWithOpenText("a")
    fixture.controller.dispatch(type("b"))
    fixture.controller.dispatch(IdeCommand.Save)
    fixture.controller.dispatch(type("c"))
    fixture.workspace.completeSave()
    fixture.controller.tick()

    val editor = fixture.controller.viewState().workspace().editor
    assertEquals("abc", editor.textForTest)
    assertTrue(editor.dirty)
    assertEquals(2L, editor.persistedContentRevision)
}
```

- [ ] **Step 2: Run controller tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :ide-client:test --tests '*IdeClientControllerTest*' --tests '*IdeAutosaveControllerTest*'
```

Expected: compilation fails for the controller and editor view.

- [ ] **Step 3: Implement the UI-thread controller**

The controller constructor must make ownership explicit:

```kotlin
class IdeClientController(
    private val workspace: IdeWorkspace,
    private val preferences: IdePreferencesStore,
    private val clock: IdeControllerClock,
    private val events: BoundedIdeEventQueue,
    private val limits: IdeClientLimits = IdeClientLimits(),
) : AutoCloseable {
    fun start()
    fun dispatch(command: IdeCommand)
    fun tick()
    fun viewState(): IdeViewState
    override fun close()
}
```

Record the construction thread and `check` every `start`, `dispatch`, `tick`, `viewState`, and `close` call against it. Async callbacks only enqueue immutable `IdeEvent`; `tick` drains and validates session generation and operation IDs.

Materialise only bounded visible editor lines into `IdeEditorView`; do not expose `EditorDocument` or mutable collections to the renderer. Preserve editor history within the live session and persist only admitted last-session geometry/caret metadata.

- [ ] **Step 4: Run controller tests and all `ide-client` checks**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 :ide-client:check
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit the local editing workflow**

```bash
git add modules/ide-client
git commit -m "feat(ide): orchestrate local editing sessions (#540)"
```

## Task 6: Wire Explicit Resolve and Bounded Client Build

**Files:**
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/build/IdeBuildCoordinator.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/build/IdeBuildState.kt`
- Modify: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeClientController.kt`
- Modify: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/state/IdeViewState.kt`
- Test: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/build/IdeBuildCoordinatorTest.kt`
- Test: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeBuildFlowTest.kt`

- [ ] **Step 1: Write failing Resolve/Build barrier tests**

Cover explicit missing-lock creation, confirmed existing-lock update, ordinary Build never rewriting lock, unsatisfied local profile, save-before-build, conflict stop, canonical source-only snapshot, cache hit, compiler diagnostics, Artifact summary, duplicate build sharing, one queued request, queue full, cancel, late result, and close.

Target states:

```kotlin
sealed interface IdeBuildState {
    data object Idle : IdeBuildState
    data class Saving(val operationId: Long) : IdeBuildState
    data class Compiling(val operationId: Long, val identity: Hash256) : IdeBuildState
    data class Succeeded(val identity: Hash256, val artifactHash: Hash256, val bytes: Int, val cacheHit: Boolean) : IdeBuildState
    data class Diagnostics(val identity: Hash256, val values: List<EditorDiagnostic>) : IdeBuildState
    data class Failed(val kind: IdeBuildFailureKind, val detail: String) : IdeBuildState
}
```

- [ ] **Step 2: Run focused build tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :ide-client:test --tests '*IdeBuildCoordinatorTest*' --tests '*IdeBuildFlowTest*'
```

Expected: compilation fails for build coordinator/state.

- [ ] **Step 3: Implement Resolve and Build orchestration**

Inject existing `GuestApiBundleCatalog`, `CompileProfileResolver`, `ProjectLockService`, and `ClientCompilationService` behind one constructor-owned `IdeBuildServices` aggregate. `resolve(updateExisting = false)` creates only an absent lock; a different existing lock returns `ConfirmationRequired` and performs no write until `ConfirmLockUpdate`.

Build must chain futures rather than block:

```kotlin
saveBarrier()
    .thenCompose { workspace.buildInput(project) }
    .thenApply(buildRequestFactory::prepare)
    .thenCompose(compilationService::build)
    .whenComplete { result, failure -> events.offer(IdeEvent.BuildCompleted(generation, operationId, result, failure)) }
```

Do not write Artifact bytes into the project. Keep only hash, size, identity, cache-hit status, and bounded completion time in view state. Convert compiler worker diagnostics to the existing `EditorDiagnostic` DTO and bind them to the exact source snapshot.

- [ ] **Step 4: Run build flow and module checks**

Run Step 2, then `:ide-client:check`.

Expected: all tests pass.

- [ ] **Step 5: Commit Resolve/Build**

```bash
git add modules/ide-client
git commit -m "feat(ide): resolve and build local projects (#540)"
```

## Task 7: Publish Analysis Results and Implement Completion State

**Files:**
- Modify: `modules/ide-core/src/main/kotlin/ru/lazyhat/compukters/ide/editor/EditorDocument.kt`
- Modify: `modules/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/EditorDocumentTest.kt`
- Modify: `modules/ide-analysis-client/src/main/kotlin/ru/lazyhat/compukters/ide/analysis/controller/AnalysisRequestCoordinator.kt`
- Modify: `modules/ide-analysis-client/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/controller/AnalysisRequestCoordinatorTest.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/analysis/IdeAnalysisCoordinator.kt`
- Create: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/analysis/IdeCompletionState.kt`
- Modify: `modules/ide-client/src/main/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeClientController.kt`
- Test: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/analysis/IdeAnalysisCoordinatorTest.kt`
- Test: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/analysis/IdeCompletionStateTest.kt`

- [ ] **Step 1: Write failing publication, stale, and completion tests**

First add a result sink to the request coordinator contract:

```kotlin
fun interface AnalysisResultSink {
    fun publish(result: AnalysisClientResult)
}
```

Tests must prove presentation and automatic completion reach the sink, manual completion preempts automatic work, cancelled/replaced work never publishes, and close suppresses late completion.

In `ide-client`, test lexical fallback, semantic-token precedence, stale source/profile rejection, diagnostics range acceptance, identifier/dot automatic trigger, manual priority, popup selection/page movement, atomic replacement, undo, Tab behavior, file/focus close, and analysis failure degradation.

Add a bounded public `EditorDocument.replaceRange(range, text)` operation in `ide-core`. It must validate UTF-16 caret boundaries, reuse the existing buffer/history limits, record the replacement as one atomic undo entry, and leave the previous public editing behavior unchanged.

- [ ] **Step 2: Run analysis tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :ide-analysis-client:test --tests '*AnalysisRequestCoordinatorTest*' \
  :ide-client:test --tests '*IdeAnalysisCoordinatorTest*' --tests '*IdeCompletionStateTest*'
```

Expected: compilation fails for the sink and IDE analysis types.

- [ ] **Step 3: Implement bounded result publication and IDE analysis state**

Make `DefaultAnalysisRequestCoordinator` call the injected sink from `whenComplete` only after checking the expected snapshot/task generation under its lock; invoke the sink outside the lock. Preserve existing cancellation and priority semantics.

Define popup state as immutable copied data:

```kotlin
data class IdeCompletionState(
    val identity: AnalysisSnapshotIdentity,
    val path: VirtualSourcePath,
    val replacement: EditorRange,
    val items: List<CompletionItem>,
    val selectedIndex: Int,
)
```

Acceptance validates current project/path/profile identity and range against the active editor, then invokes one `EditorDocument.replaceRange` command with `insertText`; the entire completion is exactly one undo entry. Analysis failure clears only semantic presentation and popup, publishing an `Analysis unavailable` status while lexical editing and Build remain enabled.

- [ ] **Step 4: Run client/analysis/K2 checks**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :ide-analysis-client:check :ide-analysis-k2:check :ide-client:check
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit semantic presentation and completion**

```bash
git add modules/ide-core modules/ide-analysis-client modules/ide-client
git commit -m "feat(ide): present semantic completion (#540)"
```

## Task 8: Package Both Workers and Construct Client IDE Services

**Files:**
- Modify: `modules/v26_1/v26_1-common/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/CompuktersMod.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeClientServices.kt`
- Create: `modules/worker-client/src/main/kotlin/ru/lazyhat/compukters/worker/payload/PackagedWorkerPayload.kt`
- Test: `modules/worker-client/src/test/kotlin/ru/lazyhat/compukters/worker/payload/PackagedWorkerPayloadTest.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeClientServicesTest.kt`

- [ ] **Step 1: Write failing generic payload and service-lifecycle tests**

Test secure ZIP publication into a content-addressed worker root, duplicate/traversal/symlink-shaped/unexpected entry rejection, byte/entry bounds, stable reuse, compiler-versus-analysis kind/identity checks, and service close/reopen.

The NeoForge service factory test must prove these distinct roots:

```text
<game>/compukters/ide/projects
<game>/compukters/ide/cache/compiler
<game>/compukters/ide/workers/compiler
<game>/compukters/ide/workers/analysis
<game>/compukters/ide/tmp/compiler
<game>/compukters/ide/tmp/analysis
```

- [ ] **Step 2: Run focused payload/service tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :worker-client:test --tests '*PackagedWorkerPayloadTest*' \
  :v26_1-neoforge:test --tests '*IdeClientServicesTest*'
```

Expected: compilation fails for generic packaged payload and IDE services.

- [ ] **Step 3: Implement payload publication and client service composition**

Move only the generic bounded ZIP-to-published-payload algorithm into `worker-client`; do not delete the compiler runtime facade until its tests migrate or delegate to the generic implementation.

Package `ide-analysis-k2-worker.zip` at `analysis/worker/ide-analysis-k2-worker.zip` next to the existing compiler worker resource. Construct `DefaultClientCompilationService`, `AnalysisServiceLifetime`, `DefaultIdeWorkspace`, and `IdeClientController` lazily on client open. Use JDK 25 `WorkerLaunch` with separate heap/metaspace/temp limits for compiler and analysis.

Add `implementation(projects.ideClient)` to the NeoForge client module. Keep the analysis worker build dependency and payload-copy task in `v26_1-common`; neither `ide-analysis-k2` nor `compiler-k2` may become a runtime dependency of `v26_1-common`, `v26_1-neoforge`, or `ide-client`.

Guard client-only construction with NeoForge client lifecycle registration rather than referencing Minecraft client classes from common/server initialization.

- [ ] **Step 4: Run payload, IDE service, and server-start regression checks**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :worker-client:check :ide-client:check :v26_1-neoforge:test \
  :v26_1-neoforge:verifyNeoForgeRuntimeDependencies \
  :v26_1-neoforge:verifyAnalysisWorkerClassIsolation
```

Expected: BUILD SUCCESSFUL; no K2 implementation class becomes loadable.

- [ ] **Step 5: Commit client worker services**

```bash
git add modules/worker-client modules/v26_1 modules/ide-client
git commit -m "feat(ide): host isolated client workers (#540)"
```

## Task 9: Implement Deterministic IDE Geometry and Persistent Client Layout

**Files:**
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeRenderGeometry.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeClientPreferences.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/config/CompuktersClientConfig.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRenderGeometryTest.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeClientPreferencesTest.kt`

- [ ] **Step 1: Write failing geometry and preference tests**

Cover normal 1920×1080-equivalent GUI dimensions, small viewport padding reduction, diagnostics collapse, tree toggle, too-small message, every terminal font profile, GUI scale, splitter min/max clamps, drag persistence, completion popup above/below caret, and invalid config recovery.

Target the pure geometry constructor:

```kotlin
val geometry = IdeRenderGeometry.compute(
    viewportWidth = 960,
    viewportHeight = 540,
    padding = 24,
    treeWidth = 180,
    diagnosticsHeight = 120,
    diagnosticsExpanded = true,
    treeVisible = true,
    font = TerminalFontProfile.DINA,
)
assertTrue(geometry.editor.width >= IdeRenderGeometry.MINIMUM_EDITOR_WIDTH)
assertTrue(geometry.editor.height >= IdeRenderGeometry.MINIMUM_EDITOR_HEIGHT)
```

- [ ] **Step 2: Run geometry tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :v26_1-neoforge:test --tests '*IdeRenderGeometryTest*' --tests '*IdeClientPreferencesTest*'
```

Expected: compilation fails for geometry/preferences.

- [ ] **Step 3: Implement geometry and config adapter**

Add client config keys `ide.padding`, `ide.tree_width`, `ide.diagnostics_height`, and `ide.diagnostics_expanded` with strict validators and clamped adapters. Keep last project/file/caret/viewport in a separate bounded client preference file under the IDE root so frequent session changes do not rewrite the NeoForge config.

Use integer half-open rectangles, one-pixel splitters, and a deterministic clamp order: configured padding → zero padding → collapsed diagnostics → hidden tree → unsupported-size state. Derive code cell geometry from `TerminalFontProfile` only.

- [ ] **Step 4: Run geometry/config and existing terminal config tests**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :v26_1-neoforge:test --tests '*Ide*GeometryTest*' \
  --tests '*IdeClientPreferencesTest*' --tests '*CompuktersClientConfigTest*'
```

Expected: all tests pass.

- [ ] **Step 5: Commit geometry and preferences**

```bash
git add modules/v26_1/v26_1-neoforge
git commit -m "feat(ide): define responsive IDE geometry (#540)"
```

## Task 10: Render the Start Page, Project Tree, Editor, Panels, and Status

**Files:**
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeScreen.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeRenderer.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeColors.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeScreenFocusTest.kt`

- [ ] **Step 1: Write failing renderer-state and focus tests**

Do not screenshot-test pixels. Test deterministic draw-model extraction: visible project rows, clipped editor lines/spans, line numbers, lexical-versus-semantic precedence, selection, caret, diagnostics rows, disabled target actions/tooltips, Artifact summary, binary placeholder, modal focus, and z-order.

Expose an internal pure render model:

```kotlin
data class IdeDrawModel(
    val panels: List<IdePanelDraw>,
    val text: List<IdeTextDraw>,
    val fills: List<IdeFillDraw>,
    val scissors: List<IdeScissorDraw>,
    val hitTargets: List<IdeHitTarget>,
)
```

- [ ] **Step 2: Run renderer tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :v26_1-neoforge:test --tests '*IdeRendererStateTest*' --tests '*IdeScreenFocusTest*'
```

Expected: compilation fails for Screen/renderer/draw model.

- [ ] **Step 3: Implement Screen extraction and rendering**

Follow the existing 26.1 `TerminalScreen` pattern: override `extractBackground` and `extractRenderState`, render through `GuiGraphicsExtractor`, use scissor rectangles for tree/editor/diagnostics/popup, and call `super.extractRenderState` for registered widgets last.

Keep buttons limited to toolbar/start-page/dialog controls. Draw high-frequency editor/tree rows directly from `IdeDrawModel`; do not create one Minecraft widget per line or completion item. Use Minecraft font for UI and `TerminalFontProfile.fontDescription` for code glyph components. Replacement glyph projection must not mutate source strings.

- [ ] **Step 4: Run renderer, font-resource, and terminal regression tests**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :v26_1-neoforge:test --tests '*Ide*' --tests '*TerminalFont*' --tests '*TerminalScreenFocusTest*'
```

Expected: all tests pass.

- [ ] **Step 5: Commit the visible IDE shell**

```bash
git add modules/v26_1/v26_1-neoforge
git commit -m "feat(ide): render the local IDE workspace (#540)"
```

## Task 11: Connect Input, Tree Mutations, Splitters, Dialogs, and Completion

**Files:**
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeInputAdapter.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeScreen.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeRenderer.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeInputAdapterTest.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeCompletionInteractionTest.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeSplitterInteractionTest.kt`

- [ ] **Step 1: Write failing interaction tests**

Cover character input, surrogate pairs, navigation with modifiers, clipboard bounds, Ctrl+S/B/Space/Z/Y, Escape priority, Enter/Tab completion acceptance, Tab indentation without popup, mouse caret/selection, wheel routing, project row selection, create/rename/delete dialogs, permanent-delete confirmation, splitter capture/drag/release, focus loss, and mouse-triggered autosave.

Target one translation boundary:

```kotlin
fun interface IdeCommandSink {
    fun dispatch(command: IdeCommand)
}

class IdeInputAdapter(
    private val sink: IdeCommandSink,
    private val clipboard: IdeClipboard,
    private val limits: IdeClientLimits,
) {
    fun keyPressed(event: KeyEvent, focus: IdeFocusState): Boolean
    fun charTyped(event: CharacterEvent, focus: IdeFocusState): Boolean
    fun mouseClicked(event: MouseButtonEvent, geometry: IdeRenderGeometry): Boolean
}
```

- [ ] **Step 2: Run interaction tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :v26_1-neoforge:test --tests '*IdeInputAdapterTest*' \
  --tests '*IdeCompletionInteractionTest*' --tests '*IdeSplitterInteractionTest*'
```

Expected: compilation fails for input adapter and interaction states.

- [ ] **Step 3: Implement focus-first event routing**

Route in this strict order: modal dialog → completion popup → captured splitter → focused editor/tree/panel → Screen fallback. Bound clipboard text with the editor UTF-16/UTF-8 limits before dispatch. Never let a focused button consume Space intended for the editor; clear Minecraft widget focus after toolbar activation, matching the terminal focus fix.

During splitter drag update transient geometry only; persist the clamped value on release. Delete dispatches only after the controller returns an admitted subtree count and the user accepts the permanent-delete dialog.

- [ ] **Step 4: Run interaction and full NeoForge unit tests**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 :v26_1-neoforge:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit interactions**

```bash
git add modules/v26_1/v26_1-neoforge
git commit -m "feat(ide): connect IDE input and dialogs (#540)"
```

## Task 12: Register `Ctrl+I` and Preserve the Previous Screen and Terminal

**Files:**
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeClientBootstrap.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalScreen.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalClientNetwork.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeClientBootstrapTest.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalChildScreenTest.kt`

- [ ] **Step 1: Write failing keybind and Screen lifecycle tests**

Cover configurable Control+I registration, edge-triggered open, no duplicate IDE instance, world parent, generic Screen parent, close-cancel retaining IDE, completed close restoring parent, terminal suspension disabling input, hidden terminal update routing, resync on restore, disconnect/invalidation fallback, and final close cleanup.

Use an explicit terminal contract instead of inspecting concrete fields:

```kotlin
interface ChildScreenParent {
    fun suspendForChild(): Screen
    fun resumeFromChild(): Boolean
    fun abandonChild()
}
```

`TerminalScreen` implements it; `IdeScreen` keeps only the returned transient parent reference.

- [ ] **Step 2: Run lifecycle tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :v26_1-neoforge:test --tests '*IdeClientBootstrapTest*' --tests '*TerminalChildScreenTest*'
```

Expected: compilation fails for bootstrap/child-Screen APIs.

- [ ] **Step 3: Implement client events and terminal suspension**

Register `KeyMapping` in `RegisterKeyMappingsEvent` with Control modifier and GLFW `I`. On client tick, consume click exactly once and open `IdeScreen` with the current Screen as parent. `IdeScreen.removed` must distinguish temporary Minecraft replacement during successful return from application close.

While suspended, `TerminalClientNetwork` continues applying matching full/delta updates to the retained terminal replica but never forwards input. Resume sends one bounded resync request before enabling input. If machine identity/position is invalid or client connection changed, abandon the terminal and return to the world.

- [ ] **Step 4: Run lifecycle, terminal, and client tests**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :v26_1-neoforge:test --tests '*Ide*' --tests '*Terminal*'
```

Expected: all focused tests pass.

- [ ] **Step 5: Commit keybind and lifecycle**

```bash
git add modules/v26_1/v26_1-neoforge
git commit -m "feat(ide): open IDE without losing its parent screen (#540)"
```

## Task 13: Enforce Packaging Isolation and Run End-to-End Automated Verification

**Files:**
- Modify: `build.gradle.kts`
- Modify: `modules/ide-client/build.gradle.kts`
- Modify: `modules/ide-analysis-k2/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`
- Modify: `licenses/distribution-components.tsv` only if the actual packaged external inventory changed
- Create: `modules/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/integration/LocalIdeWorkflowTest.kt`
- Create: `modules/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/integration/IdeScreenAnalysisIntegrationTest.kt`

- [ ] **Step 1: Write failing end-to-end and archive assertions**

The host-neutral workflow test must create a real temporary project, edit `src/main.kt`, resolve a local lock, build through the real forked compiler, observe either a cache miss then hit, open the same exact snapshot in the real analysis worker, receive semantic presentation and completion, apply completion, rebuild, and close both workers.

Extend production archive verification to require exactly:

```text
compiler/worker/compiler-k2-worker.zip
analysis/worker/ide-analysis-k2-worker.zip
```

and recursively reject direct/nested application entries beginning with `com/intellij/`, `org/jetbrains/kotlin/analysis/`, `org/jetbrains/kotlin/fir/`, `org/jetbrains/kotlin/psi/`, or the analysis worker main package outside the inert analysis ZIP.

- [ ] **Step 2: Run new integration/isolation tests and confirm RED**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :ide-client:test --tests '*LocalIdeWorkflowTest*' \
  :ide-analysis-k2:forkedWorkerTest --tests '*IdeScreenAnalysisIntegrationTest*' \
  :v26_1-neoforge:verifyPackagedCompukterFfi
```

Expected: at least the archive assertion fails until analysis payload packaging is wired through the production JAR; fix any earlier integration omission without weakening the assertion.

- [ ] **Step 3: Complete exact inventory and isolation gates**

Compare the external `lib/*.jar` inventory inside each worker payload with the matching `jvm-worker` or `jvm-analysis-worker` rows in `licenses/distribution-components.tsv`. Do not add duplicate outer-JAR inventory rows for libraries that remain only in inert payloads. Prove the compiler and analysis main classes are both unloadable from the Minecraft application classpath.

Add `:ide-client:check`, `:ide-analysis-client:check`, and `:ide-analysis-k2:check` as explicit dependencies of root `verifyLocalFast` or `verifyLocalFull`, so the final aggregate command cannot pass while the new IDE boundary is unverified.

- [ ] **Step 4: Run focused checks**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 \
  :worker-client:check :compiler-client:check :ide-core:check \
  :ide-analysis-client:check :ide-analysis-k2:check :ide-client:check \
  :v26_1-neoforge:check
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run full verification**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 verifyLocalFull
```

Expected: BUILD SUCCESSFUL, including Rust VM/FFI/conformance, production archive gates, and every required GameTest.

- [ ] **Step 6: Check repository state and commit automated completion**

```bash
git diff --check
git status --short
git add build.gradle.kts settings.gradle.kts modules licenses
git commit -m "test(ide): verify the local IDE workflow (#540)"
```

Expected: no whitespace errors and only intentional #540 changes before the commit.

## Task 14: Manual `runClient` Acceptance, Documentation, and Roadmap Gate

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/ide/*` only for focused defects found by manual review
- Modify: issue #540 through `gh` outside the sandbox

- [ ] **Step 1: Start the real client**

Run outside the sandbox if the client requires GUI/native access:

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 :v26_1-neoforge:runClient
```

Expected: Minecraft reaches the title/world UI with no IDE worker started before first IDE use.

- [ ] **Step 2: Execute the manual acceptance checklist**

Verify and record each result:

```text
[ ] Ctrl+I opens from the world and from the terminal.
[ ] First open shows catalog; later open restores the last valid project/file/layout.
[ ] Padding, GUI scale, resize, all terminal fonts, splitter drag, and small-screen collapse remain usable.
[ ] Create project, directory, text file; rename; confirmed recursive delete.
[ ] UTF-8 text edits and saves; binary file is refused without replacement decoding.
[ ] External clean edit reloads; external dirty edit enters Conflict; Reload/Save As/Cancel work.
[ ] Autosave occurs after debounce and mouse activity without per-key writes.
[ ] Resolve creates a missing lock; changed lock requires explicit Update Dependencies.
[ ] Build and Cancel stay responsive; success shows cache/hash/size and creates no project-local Artifact.
[ ] Kotlin lexical/semantic colors, Unicode diagnostics, diagnostic navigation, Ctrl+Space, automatic completion, Enter/Tab/Escape, and Undo work.
[ ] Analysis failure leaves editing/highlighting/build usable.
[ ] Verify/Deploy/Run stay disabled with No target attached.
[ ] Closing returns safely to the world/parent Screen; terminal resyncs and accepts later input.
```

- [ ] **Step 3: Fix only acceptance-blocking defects with focused regression tests**

For each defect, first add a failing test in the owning module, run it to prove RED, apply the smallest fix, and rerun the focused test to GREEN. Do not add #544 hover/navigation/reference UI or #538 target features.

- [ ] **Step 4: Document the player and architecture entry points**

Update README with `Ctrl+I`, local project root, Resolve/Build behavior, no-target limitation, and binary-file behavior. Update architecture with the `ide-client` boundary, inert worker payloads, UI-thread/event-queue rule, and source/cache authority.

- [ ] **Step 5: Run final fresh evidence**

```bash
./gradlew-sandbox --project-cache-dir /tmp/compukters-gradle-cache-540 verifyLocalFull
git diff --check
git status --short
```

Expected: full success and only documentation/manual-fix changes.

- [ ] **Step 6: Commit the accepted player-facing workflow**

```bash
git add README.md docs modules
git commit -m "docs(ide): document the local IDE workflow (#540)"
```

- [ ] **Step 7: Update Roadmap without bypassing protected-branch policy**

Outside the sandbox, comment on #540 with all commit links, focused/full verification output, worker inventory result, and the completed manual checklist. If every acceptance criterion is satisfied and the verified commits are reachable on GitHub through the repository's accepted integration path, set Roadmap status Done and close as completed. Otherwise leave #540 in Now and state the exact remaining manual or integration step.

Do not retry a rejected direct push to protected `dev` without separate explicit user approval. Prefer the repository's required PR path unless the user explicitly authorises bypassing protection.
