/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import ru.lazyhat.compukters.ide.client.preferences.IdePreferences
import ru.lazyhat.compukters.ide.client.preferences.IdePreferencesStore
import ru.lazyhat.compukters.ide.client.state.BoundedIdeEventQueue
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeConflictAction
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.client.workspace.IdeMutationRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveResult
import ru.lazyhat.compukters.ide.client.workspace.IdeWorkspace
import ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.document.DocumentSaveResult
import ru.lazyhat.compukters.ide.project.document.DocumentSnapshot
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.document.ProjectDocumentStore
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.AdmittedProjectDelete
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeClientControllerTest {
    @Test
    fun `start restores remembered project and file`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))

        fixture.startAndTick()

        val workspace = assertIs<IdePageState.Workspace>(fixture.controller.viewState().page).value
        assertEquals("demo", workspace.project.directoryName)
        assertEquals(ProjectPath.file("src/main.kt"), workspace.activeFile)
        assertIs<IdeEditorView.Text>(workspace.editor)
    }

    @Test
    fun `start falls back to project catalog when remembered project is gone`() {
        val fixture = ControllerFixture(preferences = preferences("missing", "src/main.kt"))

        fixture.startAndTick()

        val start = assertIs<IdePageState.Start>(fixture.controller.viewState().page)
        assertEquals(listOf("demo"), start.projects.map { it.directoryName })
    }

    @Test
    fun `binary files are shown honestly and cannot be edited`() {
        val fixture = ControllerFixture()
        val binary = ProjectPath.file("asset.bin")
        fixture.workspace.openResults[binary] = ProjectFileOpenResult.Binary(binary, 17)
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.OpenProject("demo"))
        fixture.controller.tick()

        fixture.controller.dispatch(IdeCommand.OpenFile(binary))
        fixture.controller.tick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("ignored")))

        val editor = fixture.workspaceView().editor
        assertEquals(IdeEditorView.Binary(binary, 17), editor)
        assertTrue(fixture.workspace.saveRequests.isEmpty())
    }

    @Test
    fun `editor commands update bounded immutable view and preserve undo`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        val initial = fixture.textEditor().visibleLines.joinToString("\n")

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("one")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type(" two")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Undo))

        val editor = fixture.textEditor()
        assertEquals("one", editor.visibleLines.joinToString("\n"))
        assertTrue(editor.dirty)
        assertTrue(initial.isNotEmpty())
    }

    @Test
    fun `save completion marks only submitted editor revision`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("a")))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("b")))
        fixture.controller.dispatch(IdeCommand.Save)
        val submittedRevision = fixture.textEditor().contentRevision
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("c")))

        fixture.workspace.completeSave()
        fixture.controller.tick()

        val editor = fixture.textEditor()
        assertEquals("abc", editor.visibleLines.joinToString("\n"))
        assertTrue(editor.dirty)
        assertEquals(submittedRevision, editor.persistedContentRevision)
        assertTrue(editor.contentRevision > editor.persistedContentRevision)
    }

    @Test
    fun `controller rejects calls from another thread`() {
        val fixture = ControllerFixture()
        var failure: Throwable? = null
        Thread {
            failure = runCatching { fixture.controller.viewState() }.exceptionOrNull()
        }.apply {
            start()
            join()
        }

        assertIs<IllegalStateException>(failure)
    }

    @Test
    fun `clean external change reloads while dirty change enters conflict`() {
        val clean = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        clean.startAndTick()
        clean.workspace.replaceMainExternally("changed")
        clean.controller.dispatch(IdeCommand.Poll)
        clean.controller.tick()
        clean.controller.tick()
        assertEquals("changed", clean.textEditor().visibleLines.single())

        val dirty = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        dirty.startAndTick()
        dirty.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("local")))
        dirty.workspace.replaceMainExternally("external")
        dirty.controller.dispatch(IdeCommand.Poll)
        dirty.controller.tick()

        assertTrue(dirty.textEditor().conflict)
        assertIs<IdeDialogState.FileConflict>(dirty.controller.viewState().dialog)
    }

    @Test
    fun `dirty file switch waits for save then opens target`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("local")))

        fixture.controller.dispatch(IdeCommand.OpenFile(ProjectPath.file("notes.txt")))
        assertEquals(ProjectPath.file("src/main.kt"), fixture.workspaceView().activeFile)
        fixture.workspace.completeSave()
        fixture.controller.tick()
        fixture.controller.tick()

        assertEquals(ProjectPath.file("notes.txt"), fixture.workspaceView().activeFile)
        assertEquals("notes", fixture.textEditor().visibleLines.single())
    }

    @Test
    fun `close conflict offers cancel or explicit discard`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("local")))
        fixture.workspace.replaceMainExternally("external")
        fixture.controller.dispatch(IdeCommand.Poll)
        fixture.controller.tick()
        fixture.controller.dispatch(IdeCommand.CloseRequested)
        assertFalse(fixture.controller.isCloseReady())

        fixture.controller.dispatch(IdeCommand.ResolveConflict(IdeConflictAction.Cancel))
        assertFalse(fixture.controller.isCloseReady())
        fixture.controller.dispatch(IdeCommand.CloseRequested)
        fixture.controller.dispatch(IdeCommand.ResolveConflict(IdeConflictAction.DiscardAndClose))
        assertTrue(fixture.controller.isCloseReady())
    }

    @Test
    fun `rename updates active path only after admitted mutation succeeds`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        val renamed = ProjectPath.file("src/renamed.kt")

        fixture.controller.dispatch(IdeCommand.Rename(ProjectPath.file("src/main.kt"), renamed))
        assertEquals(ProjectPath.file("src/main.kt"), fixture.workspaceView().activeFile)
        fixture.controller.tick()

        assertEquals(renamed, fixture.workspaceView().activeFile)
        assertEquals(renamed, fixture.textEditor().path)
    }

    @Test
    fun `delete requires matching confirmation and clears clean active file`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()

        fixture.controller.dispatch(IdeCommand.RequestDelete(ProjectPath.file("src/main.kt")))
        fixture.controller.tick()
        val dialog = assertIs<IdeDialogState.Confirmation>(fixture.controller.viewState().dialog)
        fixture.controller.dispatch(IdeCommand.ConfirmDialog(dialog.actionId + 1))
        assertIs<IdeEditorView.Text>(fixture.workspaceView().editor)
        fixture.controller.dispatch(IdeCommand.ConfirmDialog(dialog.actionId))
        fixture.controller.tick()

        assertEquals(null, fixture.workspaceView().activeFile)
        assertEquals(IdeEditorView.Empty, fixture.workspaceView().editor)
    }

    @Test
    fun `invalidated project root returns to start page`() {
        val fixture = ControllerFixture(preferences = preferences("demo", "src/main.kt"))
        fixture.startAndTick()
        fixture.workspace.invalidateProjectRoot()

        fixture.controller.dispatch(IdeCommand.Poll)
        fixture.controller.tick()

        val page = assertIs<IdePageState.Start>(fixture.controller.viewState().page)
        assertTrue(requireNotNull(page.error).message.contains("root"))
    }
}

internal class ControllerFixture(
    preferences: IdePreferences? = null,
    buildCoordinatorFactory: ((ControlledWorkspace, MutableClock) -> IdeBuildCoordinator)? = null,
) {
    val clock = MutableClock()
    val preferences = MemoryPreferences(preferences)
    val workspace = ControlledWorkspace()
    val buildCoordinator = buildCoordinatorFactory?.invoke(workspace, clock)
    val controller =
        IdeClientController(
            workspace,
            this.preferences,
            clock,
            BoundedIdeEventQueue(64),
            buildCoordinator = buildCoordinator,
        )

    fun startAndTick() {
        controller.start()
        controller.tick()
        controller.tick()
        controller.tick()
    }

    fun workspaceView() = assertIs<IdePageState.Workspace>(controller.viewState().page).value

    fun textEditor() = assertIs<IdeEditorView.Text>(workspaceView().editor)
}

internal class MutableClock(
    var now: Long = 0,
) : IdeControllerClock {
    override fun nowMillis(): Long = now
}

internal class MemoryPreferences(
    private var value: IdePreferences?,
) : IdePreferencesStore {
    override fun load(): IdePreferences? = value

    override fun save(preferences: IdePreferences) {
        value = preferences
    }
}

internal class ControlledWorkspace : IdeWorkspace {
    private val root = createTempDirectory("compukters-controller-")
    val descriptor = ProjectCatalog.open(root).create("demo")
    private val main = ProjectPath.file("src/main.kt")
    private val notes = ProjectPath.file("notes.txt")
    val openResults = mutableMapOf<ProjectPath, ProjectFileOpenResult>()
    val saveRequests = mutableListOf<IdeSaveRequest>()
    var buildInputRequests = 0
    private val pendingSaves = ArrayDeque<CompletableFuture<IdeSaveResult>>()

    init {
        descriptor.handle.canonicalPath
            .resolve(notes.value)
            .toFile()
            .writeText("notes")
        val store = ProjectDocumentStore(descriptor.handle)
        openResults[main] = ProjectFileOpenResult.Text(store.open(main))
        openResults[notes] = ProjectFileOpenResult.Text(store.open(notes))
    }

    override fun projects(): CompletableFuture<List<ProjectDescriptor>> = completed(listOf(descriptor))

    override fun createProject(name: String): CompletableFuture<ProjectDescriptor> = error("unused")

    override fun tree(project: ProjectHandle): CompletableFuture<ProjectTree> = completeCall { ProjectTreeStore(descriptor.handle).scan() }

    override fun open(
        project: ProjectHandle,
        path: ProjectPath,
    ): CompletableFuture<ProjectFileOpenResult> = completed(requireNotNull(openResults[path]) { "missing fake result for $path" })

    override fun save(request: IdeSaveRequest): CompletableFuture<IdeSaveResult> {
        saveRequests += request
        return CompletableFuture<IdeSaveResult>().also(pendingSaves::addLast)
    }

    override fun admitDelete(
        project: ProjectHandle,
        path: ProjectPath,
    ): CompletableFuture<AdmittedProjectDelete> = completeCall { ProjectTreeStore(descriptor.handle).admitDelete(path) }

    fun completeSave() {
        val request = saveRequests.last()
        val snapshot = DocumentSnapshot(request.path, request.text, revision(saveRequests.size + 1))
        pendingSaves.removeFirst().complete(DocumentSaveResult.Saved(snapshot))
    }

    fun completeSaveConflict() {
        val request = saveRequests.last()
        pendingSaves.removeFirst().complete(DocumentSaveResult.Conflict(request.expected, FileRevision.Absent))
    }

    fun installLock(bytes: ByteArray) {
        descriptor.handle.canonicalPath
            .resolve("compukter.lock")
            .toFile()
            .writeBytes(bytes)
    }

    fun replaceMainExternally(text: String) {
        descriptor.handle.canonicalPath
            .resolve(main.value)
            .toFile()
            .writeText(text)
        openResults[main] = ProjectFileOpenResult.Text(ProjectDocumentStore(descriptor.handle).open(main))
    }

    fun invalidateProjectRoot() {
        val oldRoot = descriptor.handle.canonicalPath
        val displaced = root.resolve("demo-displaced")
        java.nio.file.Files
            .move(oldRoot, displaced)
        java.nio.file.Files
            .createDirectory(oldRoot)
    }

    override fun mutate(request: IdeMutationRequest): CompletableFuture<ProjectMutationResult> =
        completeCall {
            val store = ProjectTreeStore(descriptor.handle)
            when (request) {
                is IdeMutationRequest.CreateText -> store.createText(request.path)
                is IdeMutationRequest.CreateDirectory -> store.createDirectory(request.path)
                is IdeMutationRequest.Rename -> store.rename(request.source, request.target)
                is IdeMutationRequest.Delete -> store.delete(request.admitted)
            }
        }

    override fun buildInput(project: ProjectHandle): CompletableFuture<IdeBuildInput> {
        buildInputRequests++
        val root = descriptor.handle.canonicalPath
        val source = saveRequests.lastOrNull()?.text ?: assertIs<ProjectFileOpenResult.Text>(openResults.getValue(main)).snapshot.text
        return completed(
            IdeBuildInput(
                descriptor.handle,
                root.resolve("compukter.toml").toFile().readBytes(),
                root
                    .resolve("compukter.lock")
                    .toFile()
                    .takeIf { it.exists() }
                    ?.readBytes(),
                ProjectSnapshot.of(
                    listOf(ProjectSource(VirtualSourcePath.kotlin(main.value), BinaryValue.of(source.encodeToByteArray()))),
                    WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192),
                ),
            ),
        )
    }

    override fun close() = Unit

    private fun <T> completed(value: T) = CompletableFuture.completedFuture(value)

    private fun <T> completeCall(action: () -> T): CompletableFuture<T> =
        runCatching(action).fold(::completed) { failure -> CompletableFuture<T>().also { it.completeExceptionally(failure) } }
}

internal fun preferences(
    project: String,
    file: String,
): IdePreferences = IdePreferences.admit(project, file, 0, 0, 0, 240, 160, true)

private fun revision(seed: Int) = FileRevision.Present(Hash256.of(ByteArray(32) { seed.toByte() }))
