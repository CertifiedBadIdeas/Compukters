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

import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.preferences.IdePreferences
import ru.lazyhat.compukters.ide.client.preferences.IdePreferencesStore
import ru.lazyhat.compukters.ide.client.state.BoundedIdeEventQueue
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeConflictAction
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeEvent
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeProblem
import ru.lazyhat.compukters.ide.client.state.IdeProblemSeverity
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.client.workspace.IdeMutationRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeWorkspace
import ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorEditResult
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.document.DocumentSaveResult
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.AdmittedProjectDelete
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

class IdeClientController(
    private val workspace: IdeWorkspace,
    private val preferences: IdePreferencesStore,
    private val clock: IdeControllerClock,
    private val events: BoundedIdeEventQueue,
    private val limits: IdeClientLimits = IdeClientLimits(),
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var state = IdeViewState.startPage(emptyList())
    private var generation = 0L
    private var nextOperationId = 1L
    private var started = false
    private var closed = false
    private var catalog = emptyList<ProjectDescriptor>()
    private var project: ProjectDescriptor? = null
    private var tree: ProjectTree? = null
    private var editor: EditorSession? = null
    private var binary: IdeEditorView.Binary? = null
    private var latestProjectOperation = 0L
    private var latestOpenOperation = 0L
    private var latestSaveOperation = 0L
    private var latestMutationOperation = 0L
    private var pendingFile: ProjectPath? = null
    private var pendingProjectDirectory: String? = null
    private var pendingSave = false
    private var rememberedFile: ProjectPath? = null
    private var closeRequested = false
    private var closeReady = false
    private var admittedDelete: AdmittedProjectDelete? = null
    private var restorePreferences: IdePreferences? = null
    private val eventOverflow = AtomicBoolean()

    fun start() {
        checkOwner()
        check(!closed) { "IDE controller is closed" }
        if (started) return
        started = true
        val remembered = runCatching(preferences::load).getOrNull()
        restorePreferences = remembered
        rememberedFile = remembered?.lastFile
        state = state.copy(busy = setOf(IdeBusyOperation.Catalog))
        val requestGeneration = generation
        workspace.projects().whenComplete { projects, failure ->
            if (failure == null) {
                enqueue(IdeEvent.ProjectCatalogLoaded(requestGeneration, projects))
            } else {
                enqueueFailure(requestGeneration, IdeBusyOperation.Catalog, failure)
            }
        }
    }

    fun dispatch(command: IdeCommand) {
        checkOwner()
        check(started && !closed) { "IDE controller is not active" }
        when (command) {
            is IdeCommand.OpenProject -> {
                requestProjectSwitch(command.directoryName)
            }

            is IdeCommand.OpenFile -> {
                requestFileSwitch(command.path)
            }

            is IdeCommand.CreateText -> {
                mutate(IdeMutationRequest.CreateText(requireProject(), command.path))
            }

            is IdeCommand.CreateDirectory -> {
                mutate(IdeMutationRequest.CreateDirectory(requireProject(), command.path))
            }

            is IdeCommand.Rename -> {
                mutate(IdeMutationRequest.Rename(requireProject(), command.source, command.target))
            }

            is IdeCommand.RequestDelete -> {
                requestDelete(command.path)
            }

            is IdeCommand.ConfirmDialog -> {
                confirmDialog(command.actionId)
            }

            IdeCommand.CancelDialog -> {
                admittedDelete = null
                state = state.copy(dialog = null)
            }

            is IdeCommand.Edit -> {
                edit(command.input)
            }

            IdeCommand.Save -> {
                requestSave()
            }

            IdeCommand.Poll -> {
                requestPoll()
            }

            IdeCommand.PointerActivity -> {
                if (editor?.dirty == true) requestSave()
            }

            IdeCommand.CloseRequested -> {
                closeRequested = true
                val active = editor
                when {
                    active == null || !active.dirty -> closeReady = true
                    active.conflict -> showConflictDialog(closing = true)
                    else -> requestSave()
                }
            }

            is IdeCommand.ResolveConflict -> {
                resolveConflict(command.action)
            }

            IdeCommand.Resolve,
            IdeCommand.Build,
            IdeCommand.CancelBuild,
            IdeCommand.ManualCompletion,
            -> {}
        }
    }

    fun tick() {
        checkOwner()
        check(started && !closed) { "IDE controller is not active" }
        if (eventOverflow.getAndSet(false)) {
            generation = Math.incrementExact(generation)
            events.drain()
            recoverToStart("IDE event queue overflow; reopen the project")
            return
        }
        events.drain().forEach(::accept)
        val active = editor
        if (
            active != null && active.dirty && !active.conflict && active.saveInFlight == null &&
            clock.nowMillis() - active.lastEditMillis >= AUTOSAVE_DELAY_MILLIS
        ) {
            requestSave()
        }
    }

    fun viewState(): IdeViewState {
        checkOwner()
        return state
    }

    fun isCloseReady(): Boolean {
        checkOwner()
        return closeReady
    }

    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        generation = Math.incrementExact(generation)
        project?.let { persistPreferences(editor?.path ?: binary?.path) }
        editor?.document?.close()
        editor = null
        workspace.close()
    }

    private fun openProject(directoryName: String) {
        val selected = catalog.singleOrNull { it.directoryName == directoryName }
        if (selected == null) {
            publishProblem("Project '$directoryName' is unavailable")
            return
        }
        generation = Math.incrementExact(generation)
        project?.let { persistPreferences(editor?.path ?: binary?.path) }
        editor?.document?.close()
        editor = null
        binary = null
        project = null
        tree = null
        val operationId = nextOperationId++
        latestProjectOperation = operationId
        state = state.copy(generation = generation, busy = setOf(IdeBusyOperation.Project))
        val requestGeneration = generation
        workspace.tree(selected.handle).whenComplete { loadedTree, failure ->
            if (failure == null) {
                enqueue(IdeEvent.ProjectOpened(requestGeneration, operationId, selected, loadedTree))
            } else {
                enqueueFailure(requestGeneration, IdeBusyOperation.Project, failure)
            }
        }
    }

    private fun requestProjectSwitch(directoryName: String) {
        val active = editor
        if (active != null && active.dirty) {
            pendingProjectDirectory = directoryName
            requestSave()
        } else {
            openProject(directoryName)
        }
    }

    private fun requestFileSwitch(path: ProjectPath) {
        val active = editor
        if (active != null && active.path != path && active.dirty) {
            pendingFile = path
            requestSave()
            return
        }
        openFile(path)
    }

    private fun openFile(path: ProjectPath) {
        val selected = project ?: return
        val operationId = nextOperationId++
        latestOpenOperation = operationId
        state = state.copy(busy = state.busy + IdeBusyOperation.Project)
        val requestGeneration = generation
        workspace.open(selected.handle, path).whenComplete { result, failure ->
            if (failure == null) {
                enqueue(IdeEvent.FileOpened(requestGeneration, operationId, path, result))
            } else {
                enqueueFailure(requestGeneration, IdeBusyOperation.Project, failure)
            }
        }
    }

    private fun edit(input: IdeEditorInput) {
        val active = editor ?: return
        val result =
            when (input) {
                is IdeEditorInput.Type -> {
                    active.document.type(input.text)
                }

                is IdeEditorInput.SetCaret -> {
                    active.document.setCaret(input.offsetUtf16, input.extendSelection)
                    null
                }

                is IdeEditorInput.Move -> {
                    when (input.direction) {
                        IdeMoveDirection.Left -> active.document.moveLeft(input.extendSelection)
                        IdeMoveDirection.Right -> active.document.moveRight(input.extendSelection)
                        IdeMoveDirection.Up -> active.document.moveUp(input.extendSelection)
                        IdeMoveDirection.Down -> active.document.moveDown(input.extendSelection)
                        IdeMoveDirection.Home -> active.document.moveHome(input.extendSelection)
                        IdeMoveDirection.End -> active.document.moveEnd(input.extendSelection)
                    }
                    null
                }

                IdeEditorInput.Backspace -> {
                    active.document.backspace()
                }

                IdeEditorInput.Delete -> {
                    active.document.delete()
                }

                IdeEditorInput.Enter -> {
                    active.document.enter()
                }

                IdeEditorInput.Tab -> {
                    active.document.tab()
                }

                IdeEditorInput.Undo -> {
                    active.document.undo()
                }

                IdeEditorInput.Redo -> {
                    active.document.redo()
                }

                IdeEditorInput.SelectAll -> {
                    active.document.selectAll()
                    null
                }
            }
        if (result is EditorEditResult.Applied) active.lastEditMillis = clock.nowMillis()
        publishWorkspace()
    }

    private fun requestSave() {
        val selected = project ?: return
        val active = editor ?: return
        if (IdeBusyOperation.Project in state.busy) {
            pendingSave = true
            return
        }
        if (!active.dirty || active.conflict || active.saveInFlight != null) return
        val operationId = nextOperationId++
        latestSaveOperation = operationId
        val submittedRevision = active.document.revision
        active.saveInFlight = submittedRevision
        val request =
            IdeSaveRequest(
                selected.handle,
                active.path,
                active.diskRevision,
                active.document.materialize(),
            )
        state = state.copy(busy = state.busy + IdeBusyOperation.Save)
        publishWorkspace()
        val requestGeneration = generation
        val requestPath = request.path
        workspace.save(request).whenComplete { result, failure ->
            if (failure == null) {
                enqueue(IdeEvent.SaveCompleted(requestGeneration, operationId, requestPath, submittedRevision, result))
            } else {
                enqueueFailure(requestGeneration, IdeBusyOperation.Save, failure)
            }
        }
    }

    private fun requestPoll() {
        val selected = project ?: return
        val requestGeneration = generation
        workspace.tree(selected.handle).whenComplete { loadedTree, failure ->
            if (failure == null) {
                enqueue(IdeEvent.PollCompleted(requestGeneration, loadedTree))
            } else {
                enqueueFailure(requestGeneration, IdeBusyOperation.Project, failure)
            }
        }
    }

    private fun requestDelete(path: ProjectPath) {
        val selected = project ?: return
        val operationId = nextOperationId++
        latestMutationOperation = operationId
        val actionId = nextOperationId++
        val requestGeneration = generation
        state = state.copy(busy = state.busy + IdeBusyOperation.Project)
        workspace.admitDelete(selected.handle, path).whenComplete { admitted, failure ->
            if (failure == null) {
                enqueue(IdeEvent.DeleteAdmitted(requestGeneration, operationId, actionId, admitted))
            } else {
                enqueueFailure(requestGeneration, IdeBusyOperation.Project, failure)
            }
        }
    }

    private fun confirmDialog(actionId: Long) {
        val dialog = state.dialog as? IdeDialogState.Confirmation ?: return
        val admitted = admittedDelete ?: return
        if (dialog.actionId != actionId) return
        state = state.copy(dialog = null)
        admittedDelete = null
        mutate(IdeMutationRequest.Delete(requireProject(), admitted))
    }

    private fun mutate(request: IdeMutationRequest) {
        val operationId = nextOperationId++
        latestMutationOperation = operationId
        val requestGeneration = generation
        state = state.copy(busy = state.busy + IdeBusyOperation.Project)
        workspace.mutate(request).whenComplete { result, failure ->
            if (failure == null) {
                enqueue(IdeEvent.MutationCompleted(requestGeneration, operationId, request, result))
            } else {
                enqueueFailure(requestGeneration, IdeBusyOperation.Project, failure)
            }
        }
    }

    private fun accept(event: IdeEvent) {
        if (event.generationOrNull() != null && event.generationOrNull() != generation) return
        when (event) {
            is IdeEvent.ProjectCatalogLoaded -> acceptCatalog(event)

            is IdeEvent.ProjectOpened -> acceptProject(event)

            is IdeEvent.FileOpened -> acceptFile(event)

            is IdeEvent.SaveCompleted -> acceptSave(event)

            is IdeEvent.DeleteAdmitted -> acceptDeleteAdmitted(event)

            is IdeEvent.MutationCompleted -> acceptMutation(event)

            is IdeEvent.PollCompleted -> acceptPoll(event)

            is IdeEvent.Failed -> acceptFailure(event)

            is IdeEvent.CatalogLoaded,
            is IdeEvent.BuildCompleted,
            -> Unit
        }
    }

    private fun acceptCatalog(event: IdeEvent.ProjectCatalogLoaded) {
        catalog = event.projects
        val summaries = catalog.take(limits.projectRows).map(::summary)
        state = IdeViewState(generation, IdePageState.Start(summaries, null), null, emptySet())
        val remembered = restorePreferences?.lastProjectDirectory
        if (remembered != null && catalog.any { it.directoryName == remembered }) openProject(remembered)
    }

    private fun acceptProject(event: IdeEvent.ProjectOpened) {
        if (event.operationId != latestProjectOperation) return
        project = event.project
        tree = event.tree
        state = state.copy(busy = state.busy - IdeBusyOperation.Project)
        publishWorkspace()
        val restore = rememberedFile
        rememberedFile = null
        if (restore != null && event.tree.flatten().any { it.path == restore }) openFile(restore)
        persistPreferences(restore)
    }

    private fun acceptFile(event: IdeEvent.FileOpened) {
        if (event.operationId != latestOpenOperation) return
        editor?.document?.close()
        editor = null
        binary = null
        when (val result = event.result) {
            is ProjectFileOpenResult.Text -> {
                val document = EditorDocument(result.snapshot.text)
                val session = EditorSession(event.path, document, result.snapshot.revision)
                val remembered = restorePreferences
                restorePreferences = null
                remembered
                    ?.takeIf { it.lastProjectDirectory == project?.directoryName && it.lastFile == event.path }
                    ?.let { remembered ->
                        restoreCaret(document, remembered.caretUtf16)
                        session.firstVisibleLine = remembered.firstVisibleLine
                    }
                editor = session
            }

            is ProjectFileOpenResult.Binary -> {
                binary = IdeEditorView.Binary(result.path, result.bytes)
            }
        }
        state = state.copy(busy = state.busy - IdeBusyOperation.Project)
        publishWorkspace()
        persistPreferences(event.path)
    }

    private fun acceptSave(event: IdeEvent.SaveCompleted) {
        if (event.operationId != latestSaveOperation) return
        val active = editor ?: return
        if (active.path != event.path || active.saveInFlight != event.editorRevision) return
        active.saveInFlight = null
        state = state.copy(busy = state.busy - IdeBusyOperation.Save)
        when (val result = event.result) {
            is DocumentSaveResult.Saved -> {
                val created = active.diskRevision == FileRevision.Absent
                active.diskRevision = result.snapshot.revision
                active.persistedRevision = event.editorRevision
                active.conflict = false
                if (active.dirty) active.lastEditMillis = maxOf(active.lastEditMillis, clock.nowMillis())
                if (created) requestPoll()
            }

            is DocumentSaveResult.Conflict -> {
                active.conflict = true
            }

            DocumentSaveResult.ProjectInvalidated -> {
                recoverToStart("Project root changed; reopen the project")
            }
        }
        publishWorkspace()
        if (active.conflict) showConflictDialog(closeRequested)
        if (closeRequested && !active.dirty) closeReady = true
        continuePendingNavigation()
    }

    private fun acceptPoll(event: IdeEvent.PollCompleted) {
        tree = event.tree
        val active = editor
        if (active != null) {
            val diskRevision =
                event.tree
                    .flatten()
                    .singleOrNull { it.path == active.path }
                    ?.revision
            if (diskRevision != active.diskRevision) {
                if (active.dirty) {
                    active.conflict = true
                    showConflictDialog(closeRequested)
                } else if (diskRevision == null) {
                    active.document.close()
                    editor = null
                    publishProblem("Active file was removed outside the IDE")
                } else {
                    openFile(active.path)
                }
            }
        } else {
            val shownBinary = binary
            if (shownBinary != null && event.tree.flatten().none { it.path == shownBinary.path }) binary = null
        }
        publishWorkspace()
    }

    private fun acceptDeleteAdmitted(event: IdeEvent.DeleteAdmitted) {
        if (event.operationId != latestMutationOperation) return
        admittedDelete = event.admitted
        state =
            state.copy(
                busy = state.busy - IdeBusyOperation.Project,
                dialog =
                    IdeDialogState.Confirmation(
                        "Delete permanently?",
                        "Delete ${event.admitted.path.value} and ${event.admitted.entries} admitted entries permanently",
                        event.actionId,
                    ),
            )
        continuePendingSave()
    }

    private fun acceptMutation(event: IdeEvent.MutationCompleted) {
        if (event.operationId != latestMutationOperation) return
        state = state.copy(busy = state.busy - IdeBusyOperation.Project)
        when (val result = event.result) {
            is ProjectMutationResult.Changed -> {
                tree = result.tree
                when (val request = event.request) {
                    is IdeMutationRequest.Rename -> applyRename(request.source, request.target)

                    is IdeMutationRequest.Delete -> applyDelete(request.admitted.path)

                    is IdeMutationRequest.CreateText,
                    is IdeMutationRequest.CreateDirectory,
                    -> Unit
                }
            }

            is ProjectMutationResult.Conflict -> {
                publishProblem("Project entry changed: ${result.path.value}")
            }

            ProjectMutationResult.ProjectInvalidated -> {
                recoverToStart("Project root changed; reopen the project")
            }
        }
        publishWorkspace()
        continuePendingSave()
    }

    private fun applyRename(
        source: ProjectPath,
        target: ProjectPath,
    ) {
        editor?.takeIf { it.path.isWithin(source) }?.let { active ->
            active.path = active.path.rebase(source, target)
        }
        binary?.takeIf { it.path.isWithin(source) }?.let { active ->
            binary = IdeEditorView.Binary(active.path.rebase(source, target), active.bytes)
        }
    }

    private fun applyDelete(deleted: ProjectPath) {
        val active = editor
        if (active != null && active.path.isWithin(deleted)) {
            if (active.dirty) {
                active.conflict = true
                publishProblem("Active file was deleted; use Save As to keep local edits")
                showConflictDialog(closing = false)
            } else {
                active.document.close()
                editor = null
            }
        }
        if (binary?.path?.isWithin(deleted) == true) binary = null
    }

    private fun acceptFailure(event: IdeEvent.Failed) {
        if (event.operation == IdeBusyOperation.Save) {
            editor?.saveInFlight = null
            editor?.lastEditMillis = clock.nowMillis()
        }
        if (project?.handle?.isValid() == false) {
            recoverToStart("Project root changed; reopen the project")
            return
        }
        state = state.copy(busy = state.busy - event.operation, page = pageWithProblem(event.problem))
        if (event.operation == IdeBusyOperation.Project) continuePendingSave()
    }

    private fun resolveConflict(action: IdeConflictAction) {
        val active = editor ?: return
        when (action) {
            IdeConflictAction.ReloadFromDisk -> {
                state = state.copy(dialog = null)
                closeRequested = false
                openFile(active.path)
            }

            is IdeConflictAction.SaveAs -> {
                active.path = action.path
                active.diskRevision = FileRevision.Absent
                active.conflict = false
                state = state.copy(dialog = null)
                requestSave()
            }

            IdeConflictAction.DiscardAndClose -> {
                if (closeRequested) {
                    state = state.copy(dialog = null)
                    closeReady = true
                }
            }

            IdeConflictAction.Cancel -> {
                closeRequested = false
                state = state.copy(dialog = null)
            }
        }
        publishWorkspace()
    }

    private fun showConflictDialog(closing: Boolean) {
        val active = editor ?: return
        state = state.copy(dialog = IdeDialogState.FileConflict(active.path, closing))
    }

    private fun continuePendingNavigation() {
        val targetProject = pendingProjectDirectory
        if (targetProject != null) {
            val active = editor
            if (active?.conflict == true) return
            if (active?.dirty == true) {
                requestSave()
            } else {
                pendingProjectDirectory = null
                pendingFile = null
                openProject(targetProject)
            }
            return
        }
        val target = pendingFile ?: return
        val active = editor
        if (active?.conflict == true) return
        if (active?.dirty == true) {
            requestSave()
        } else {
            pendingFile = null
            openFile(target)
        }
    }

    private fun continuePendingSave() {
        if (!pendingSave) return
        pendingSave = false
        requestSave()
    }

    private fun publishWorkspace() {
        val selected = project ?: return
        val selectedTree = tree ?: return
        val editorView = editor?.toView() ?: binary ?: IdeEditorView.Empty
        state =
            state.copy(
                page =
                    IdePageState.Workspace(
                        IdeWorkspaceView(
                            summary(selected),
                            selectedTree,
                            editor?.path ?: binary?.path,
                            editorView,
                            (state.page as? IdePageState.Workspace)?.value?.status,
                            (state.page as? IdePageState.Workspace)?.value?.build,
                        ),
                    ),
            )
    }

    private fun EditorSession.toView(): IdeEditorView.Text {
        val first = firstVisibleLine.coerceIn(0, document.lineCount - 1)
        val lastExclusive = minOf(document.lineCount, first + limits.visibleEditorLines)
        val visible = (first until lastExclusive).map(document::materializeLine)
        val selection = document.selectionRange
        return IdeEditorView.Text(
            path,
            visible,
            first,
            document.lineCount,
            document.caretOffset,
            selection?.startUtf16,
            selection?.endUtf16,
            document.revision,
            persistedRevision,
            dirty,
            conflict,
        )
    }

    private fun persistPreferences(file: ProjectPath?) {
        val selected = project ?: return
        val active = editor
        val admitted =
            IdePreferences.admit(
                selected.directoryName,
                file?.value,
                active?.document?.caretOffset ?: 0,
                active?.firstVisibleLine ?: 0,
                0,
                DEFAULT_TREE_WIDTH,
                DEFAULT_DIAGNOSTICS_HEIGHT,
                true,
            )
        runCatching { preferences.save(admitted) }
    }

    private fun recoverToStart(message: String) {
        editor?.document?.close()
        editor = null
        binary = null
        project = null
        tree = null
        state =
            IdeViewState(
                generation,
                IdePageState.Start(catalog.take(limits.projectRows).map(::summary), problem(message)),
                null,
                emptySet(),
            )
    }

    private fun publishProblem(message: String) {
        state = state.copy(page = pageWithProblem(problem(message)))
    }

    private fun pageWithProblem(problem: IdeProblem): IdePageState =
        when (val page = state.page) {
            is IdePageState.Start -> IdePageState.Start(page.projects, problem)
            is IdePageState.Workspace -> IdePageState.Workspace(page.value.copy(status = problem))
        }

    private fun enqueueFailure(
        eventGeneration: Long,
        operation: IdeBusyOperation,
        failure: Throwable,
    ) {
        val actual = (failure as? CompletionException)?.cause ?: failure
        enqueue(IdeEvent.Failed(eventGeneration, operation, problem(actual.message ?: actual::class.simpleName.orEmpty())))
    }

    private fun enqueue(event: IdeEvent) {
        if (!events.offer(event)) eventOverflow.set(true)
    }

    private fun restoreCaret(
        document: EditorDocument,
        requested: Int,
    ) {
        var candidate = requested.coerceIn(0, document.length)
        while (candidate > 0 && !document.setCaret(candidate)) candidate--
        if (candidate == 0) document.setCaret(0)
    }

    private fun problem(message: String): IdeProblem = IdeProblem(message.boundedUtf8(limits.statusUtf8Bytes), IdeProblemSeverity.Error)

    private fun summary(descriptor: ProjectDescriptor): IdeProjectSummary =
        IdeProjectSummary(descriptor.directoryName, descriptor.manifest.name)

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "IDE controller may only be used from its construction thread" }
    }

    private fun requireProject() = requireNotNull(project) { "no project is open" }.handle

    private class EditorSession(
        var path: ProjectPath,
        val document: EditorDocument,
        var diskRevision: FileRevision,
    ) {
        var persistedRevision = document.revision
        var saveInFlight: Long? = null
        var conflict = false
        var lastEditMillis = 0L
        var firstVisibleLine = 0
        val dirty: Boolean get() = document.revision != persistedRevision
    }

    private companion object {
        const val AUTOSAVE_DELAY_MILLIS = 500L
        const val DEFAULT_TREE_WIDTH = 240
        const val DEFAULT_DIAGNOSTICS_HEIGHT = 160
    }
}

private fun IdeEvent.generationOrNull(): Long? =
    when (this) {
        is IdeEvent.ProjectCatalogLoaded -> generation
        is IdeEvent.ProjectOpened -> generation
        is IdeEvent.FileOpened -> generation
        is IdeEvent.SaveCompleted -> generation
        is IdeEvent.DeleteAdmitted -> generation
        is IdeEvent.MutationCompleted -> generation
        is IdeEvent.CatalogLoaded -> generation
        is IdeEvent.PollCompleted -> generation
        is IdeEvent.BuildCompleted -> generation
        is IdeEvent.Failed -> generation
    }

private fun ProjectPath.isWithin(parent: ProjectPath): Boolean = value == parent.value || value.startsWith("${parent.value}/")

private fun ProjectPath.rebase(
    source: ProjectPath,
    target: ProjectPath,
): ProjectPath =
    if (this == source) {
        target
    } else {
        ProjectPath.file(target.value + value.removePrefix(source.value))
    }

private fun String.boundedUtf8(maxBytes: Int): String {
    if (maxBytes == 0) return ""
    var bounded = take(maxBytes)
    while (bounded.encodeToByteArray().size > maxBytes) bounded = bounded.dropLast(1)
    return bounded
}
