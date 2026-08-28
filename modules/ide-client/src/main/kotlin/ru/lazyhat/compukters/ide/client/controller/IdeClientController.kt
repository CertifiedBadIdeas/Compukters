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

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisCoordinator
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionAcceptance
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import ru.lazyhat.compukters.ide.client.build.IdeBuildFailureKind
import ru.lazyhat.compukters.ide.client.build.IdeBuildJob
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.build.IdeResolveResult
import ru.lazyhat.compukters.ide.client.preferences.IdePreferences
import ru.lazyhat.compukters.ide.client.preferences.IdePreferencesStore
import ru.lazyhat.compukters.ide.client.state.BoundedIdeEventQueue
import ru.lazyhat.compukters.ide.client.state.IdeBuildAction
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
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeTargetArtifact
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetCoordinator
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.client.workspace.IdeMutationRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeWorkspace
import ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
import ru.lazyhat.compukters.ide.editor.EditorChange
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorEditResult
import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.document.DocumentSaveResult
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.AdmittedProjectDelete
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

class IdeClientController(
    private val workspace: IdeWorkspace,
    private val preferences: IdePreferencesStore,
    private val clock: IdeControllerClock,
    private val events: BoundedIdeEventQueue,
    private val limits: IdeClientLimits = IdeClientLimits(),
    buildCoordinator: IdeBuildCoordinator? = null,
    analysisCoordinator: IdeAnalysisCoordinator? = null,
    private val targetCoordinator: IdeTargetCoordinator? = null,
    tooling: CompletionStage<IdeClientTooling>? = null,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var state =
        IdeViewState.startPage(emptyList()).copy(
            tooling =
                when {
                    tooling != null -> IdeToolingState.Preparing
                    buildCoordinator != null && analysisCoordinator != null -> IdeToolingState.Ready
                    else -> IdeToolingState.Unavailable("Kotlin tooling is unavailable")
                },
        )
    private var buildCoordinator = buildCoordinator
    private var analysisCoordinator = analysisCoordinator
    private var installedTooling: IdeClientTooling? = null
    private val acceptsTooling = AtomicBoolean(true)
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
    private var buildState: IdeBuildState = IdeBuildState.Idle
    private var pendingBuildAction: PendingBuildAction? = null
    private var latestBuildOperation = 0L
    private var activeBuild: IdeBuildJob? = null
    private val buildJobs = mutableMapOf<Long, IdeBuildJob>()
    private var observedAnalysisState: IdeAnalysisState = IdeAnalysisState.Idle
    private val targetBuildActions = mutableMapOf<Long, PendingBuildAction>()

    init {
        tooling?.whenComplete { ready, failure ->
            if (!acceptsTooling.get()) {
                ready?.close()
            } else if (failure == null && ready != null) {
                if (!events.offer(IdeEvent.ToolingReady(ready))) ready.close()
            } else {
                val actual = (failure as? CompletionException)?.cause ?: failure
                events.offer(IdeEvent.ToolingFailed(actual?.message ?: "Kotlin tooling failed to start"))
            }
        }
    }

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
            is IdeCommand.CreateProject -> {
                createProject(command.name)
            }

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
                if (state.dialog is IdeDialogState.TargetOverwrite) {
                    targetCoordinator?.cancelDeployment()
                    refreshTargetState()
                } else {
                    admittedDelete = null
                    state = state.copy(dialog = null)
                }
            }

            is IdeCommand.Edit -> {
                edit(command.input)
            }

            is IdeCommand.ScrollEditor -> {
                scrollEditor(command.lines, command.columns)
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

            IdeCommand.Resolve -> {
                requestBuildAction(IdeBuildAction.Resolve)
            }

            IdeCommand.ConfirmLockUpdate -> {
                if (state.dialog is IdeDialogState.LockUpdate) {
                    state = state.copy(dialog = null)
                    requestBuildAction(IdeBuildAction.UpdateLock)
                }
            }

            IdeCommand.Build -> {
                requestBuildAction(IdeBuildAction.Build)
            }

            IdeCommand.Verify -> {
                requestBuildAction(IdeBuildAction.Verify)
            }

            IdeCommand.Deploy -> {
                requestBuildAction(IdeBuildAction.Deploy)
            }

            IdeCommand.Run -> {
                requestBuildAction(IdeBuildAction.Run)
            }

            IdeCommand.ConfirmTargetDeployment -> {
                if (state.dialog is IdeDialogState.TargetOverwrite) {
                    state = state.copy(dialog = null)
                    targetCoordinator?.confirmDeployment()
                    refreshTargetState()
                }
            }

            IdeCommand.CancelTargetDeployment -> {
                if (state.dialog is IdeDialogState.TargetOverwrite) {
                    targetCoordinator?.cancelDeployment()
                    refreshTargetState()
                }
            }

            IdeCommand.CancelBuild -> {
                activeBuild?.cancel()
            }

            IdeCommand.ManualCompletion -> {
                analysisCoordinator?.manualCompletion()
                refreshAnalysisState()
            }

            IdeCommand.DismissCompletion -> {
                analysisCoordinator?.dismissCompletion()
                refreshAnalysisState()
                publishWorkspace()
            }

            IdeCommand.EditorFocusLost -> {
                analysisCoordinator?.focusLost()
                refreshAnalysisState()
            }
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
        targetCoordinator?.tick()
        refreshTargetState()
        val active = editor
        if (
            active != null && active.dirty && !active.conflict && active.saveInFlight == null &&
            clock.nowMillis() - active.lastEditMillis >= AUTOSAVE_DELAY_MILLIS
        ) {
            requestSave()
        }
        refreshAnalysisState()
    }

    fun viewState(): IdeViewState {
        checkOwner()
        return state
    }

    fun attachTarget(claim: IdeTargetClaim) {
        checkOwner()
        check(started && !closed) { "IDE controller is not active" }
        val coordinator = targetCoordinator
        if (coordinator == null) {
            publishProblem("Target integration is unavailable")
            return
        }
        coordinator.attach(claim)
        refreshTargetState()
    }

    fun detachTarget() {
        checkOwner()
        if (closed) return
        targetCoordinator?.detach()
        refreshTargetState()
    }

    fun isCloseReady(): Boolean {
        checkOwner()
        return closeReady
    }

    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        acceptsTooling.set(false)
        events.drain().forEach { event ->
            if (event is IdeEvent.ToolingReady) event.tooling.close()
        }
        generation = Math.incrementExact(generation)
        project?.let { persistPreferences(editor?.path ?: binary?.path) }
        editor?.close()
        editor = null
        analysisCoordinator?.closeFile()
        workspace.close()
        cancelBuildJobs()
        installedTooling?.close()
        if (installedTooling == null) {
            buildCoordinator?.close()
            analysisCoordinator?.close()
        }
        targetCoordinator?.close()
    }

    private fun openProject(directoryName: String) {
        val selected = catalog.singleOrNull { it.directoryName == directoryName }
        if (selected == null) {
            publishProblem("Project '$directoryName' is unavailable")
            return
        }
        generation = Math.incrementExact(generation)
        project?.let { persistPreferences(editor?.path ?: binary?.path) }
        cancelBuildJobs()
        pendingBuildAction = null
        buildState = IdeBuildState.Idle
        editor?.close()
        editor = null
        analysisCoordinator?.closeFile()
        observedAnalysisState = IdeAnalysisState.Idle
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

    private fun createProject(name: String) {
        generation = Math.incrementExact(generation)
        val operationId = nextOperationId++
        latestProjectOperation = operationId
        pendingFile = ProjectPath.file("src/main.kt")
        state = state.copy(generation = generation, busy = setOf(IdeBusyOperation.Project), dialog = null)
        val requestGeneration = generation
        workspace
            .createProject(name)
            .thenCompose { created -> workspace.tree(created.handle).thenApply { created to it } }
            .whenComplete { result, failure ->
                if (failure == null && result != null) {
                    enqueue(IdeEvent.ProjectOpened(requestGeneration, operationId, result.first, result.second))
                } else {
                    enqueueFailure(requestGeneration, IdeBusyOperation.Project, failure ?: IllegalStateException("project creation failed"))
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
        if ((input == IdeEditorInput.Tab || input == IdeEditorInput.Enter) && active.path.isKotlinSource) {
            val analysis = analysisCoordinator
            val accepted = analysis?.acceptCompletion(active.document, VirtualSourcePath.kotlin(active.path.value))
            if (accepted is IdeCompletionAcceptance.Applied) {
                active.lastEditMillis = clock.nowMillis()
                refreshAnalysisState()
                publishWorkspace()
                return
            }
            if (accepted != null) analysis.dismissCompletion()
        }
        if (input is IdeEditorInput.Move && (input.direction == IdeMoveDirection.Up || input.direction == IdeMoveDirection.Down)) {
            val analysis = analysisCoordinator
            val completion = (analysis?.state() as? IdeAnalysisState.Active)?.completion
            if (completion != null) {
                analysis.moveCompletion(if (input.direction == IdeMoveDirection.Up) -1 else 1)
                refreshAnalysisState()
                publishWorkspace()
                return
            }
        }
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
        if (result is EditorEditResult.Applied) {
            active.lastEditMillis = clock.nowMillis()
            updateAnalysis(active, (input as? IdeEditorInput.Type)?.text, result.change)
        } else if (input is IdeEditorInput.SetCaret || input is IdeEditorInput.Move) {
            analysisCoordinator?.dismissCompletion()
            refreshAnalysisState()
        }
        publishWorkspace()
    }

    private fun scrollEditor(
        lines: Int,
        columns: Int,
    ) {
        val active = editor ?: return
        active.firstVisibleLine =
            (active.firstVisibleLine.toLong() + lines)
                .coerceIn(0, (active.document.lineCount - 1).toLong())
                .toInt()
        active.firstVisibleColumn =
            (active.firstVisibleColumn.toLong() + columns)
                .coerceIn(0, active.document.maximumVisualWidth().toLong())
                .toInt()
        publishWorkspace()
        persistPreferences(active.path)
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

    private fun requestBuildAction(action: IdeBuildAction) {
        val selected = project ?: return
        if (buildCoordinator == null) {
            buildState = IdeBuildState.Failed(IdeBuildFailureKind.Platform, "local compiler is unavailable")
            publishWorkspace()
            return
        }
        val target = targetCoordinator?.attachedTarget()
        if (action.isTargetAction && target == null) {
            publishProblem("No target attached")
            return
        }
        val operationId = nextOperationId++
        latestBuildOperation = operationId
        pendingBuildAction = PendingBuildAction(operationId, action, target)
        buildState = IdeBuildState.Saving(operationId)
        val busy = if (action.isCompilation) IdeBusyOperation.Build else IdeBusyOperation.Resolve
        state = state.copy(busy = state.busy + busy)
        publishWorkspace()
        val active = editor
        if (active != null && active.dirty) {
            if (active.conflict) {
                failPendingBuild(IdeBuildFailureKind.Conflict, "save conflict must be resolved before build")
            } else {
                requestSave()
            }
            return
        }
        loadBuildInput(selected, operationId, action, target)
    }

    private fun loadBuildInput(
        selected: ProjectDescriptor,
        operationId: Long,
        action: IdeBuildAction,
        target: IdeAttachedTarget?,
    ) {
        val requestGeneration = generation
        workspace.buildInput(selected.handle).whenComplete { input, failure ->
            if (failure == null) {
                enqueue(IdeEvent.BuildInputLoaded(requestGeneration, operationId, action, input, target))
            } else {
                val detail = failure.message ?: "failed to load build input"
                if (action.isCompilation) {
                    enqueue(
                        IdeEvent.BuildStateChanged(
                            requestGeneration,
                            operationId,
                            IdeBuildState.Failed(IdeBuildFailureKind.Platform, detail),
                        ),
                    )
                } else {
                    enqueue(
                        IdeEvent.ResolveCompleted(
                            requestGeneration,
                            operationId,
                            IdeResolveResult.Failed(detail),
                        ),
                    )
                }
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
            is IdeEvent.ToolingReady -> acceptTooling(event.tooling)

            is IdeEvent.ToolingFailed -> acceptToolingFailure(event.detail)

            is IdeEvent.ProjectCatalogLoaded -> acceptCatalog(event)

            is IdeEvent.BuildInputLoaded -> acceptBuildInput(event)

            is IdeEvent.BuildStateChanged -> acceptBuildState(event)

            is IdeEvent.ResolveCompleted -> acceptResolve(event)

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

    private fun acceptTooling(tooling: IdeClientTooling) {
        if (installedTooling != null || closed) {
            tooling.close()
            return
        }
        installedTooling = tooling
        buildCoordinator = tooling.build
        analysisCoordinator = tooling.analysis
        state = state.copy(tooling = IdeToolingState.Ready)
        editor?.let(::openAnalysis)
        publishWorkspace()
    }

    private fun acceptToolingFailure(detail: String) {
        val message = "Kotlin tooling unavailable: $detail".boundedUtf8(limits.statusUtf8Bytes)
        state = state.copy(tooling = IdeToolingState.Unavailable(message))
        publishStatus(message, IdeProblemSeverity.Warning)
    }

    private fun acceptCatalog(event: IdeEvent.ProjectCatalogLoaded) {
        catalog = event.projects
        val summaries = catalog.take(limits.projectRows).map(::summary)
        state = IdeViewState(generation, IdePageState.Start(summaries, null), null, emptySet(), state.target, state.tooling)
        val remembered = restorePreferences?.lastProjectDirectory
        if (remembered != null && catalog.any { it.directoryName == remembered }) openProject(remembered)
    }

    private fun acceptBuildInput(event: IdeEvent.BuildInputLoaded) {
        if (event.operationId != latestBuildOperation) return
        val coordinator = buildCoordinator ?: return
        pendingBuildAction = null
        when (event.action) {
            IdeBuildAction.Build,
            IdeBuildAction.Verify,
            IdeBuildAction.Deploy,
            IdeBuildAction.Run,
            -> {
                val job = coordinator.build(event.operationId, event.input, event.target?.compileProfile)
                if (event.action.isTargetAction) {
                    targetBuildActions[event.operationId] = PendingBuildAction(event.operationId, event.action, event.target)
                }
                activeBuild = job
                buildJobs[event.operationId] = job
                job.started.whenComplete { compiling, failure ->
                    if (failure == null) {
                        enqueue(IdeEvent.BuildStateChanged(event.generation, event.operationId, compiling))
                    }
                }
                job.result.whenComplete { result, failure ->
                    val mapped =
                        if (failure == null) {
                            result
                        } else {
                            IdeBuildState.Failed(IdeBuildFailureKind.Platform, failure.message ?: "build failed")
                        }
                    enqueue(IdeEvent.BuildStateChanged(event.generation, event.operationId, mapped))
                }
            }

            IdeBuildAction.Resolve,
            IdeBuildAction.UpdateLock,
            -> {
                coordinator
                    .resolve(
                        event.input,
                        updateExisting = event.action == IdeBuildAction.UpdateLock,
                        target = event.target?.compileProfile,
                    ).whenComplete {
                        result,
                        failure,
                        ->
                        val mapped = if (failure == null) result else IdeResolveResult.Failed(failure.message ?: "resolve failed")
                        enqueue(IdeEvent.ResolveCompleted(event.generation, event.operationId, mapped))
                    }
            }
        }
    }

    private fun acceptBuildState(event: IdeEvent.BuildStateChanged) {
        if (event.operationId != latestBuildOperation) {
            if (event.state !is IdeBuildState.Compiling) {
                buildJobs.remove(event.operationId)
                targetBuildActions.remove(event.operationId)
            }
            return
        }
        buildState = event.state
        if (event.state !is IdeBuildState.Compiling) {
            state = state.copy(busy = state.busy - IdeBusyOperation.Build)
            val finishedBuild = buildJobs.remove(event.operationId)
            if (activeBuild === finishedBuild) activeBuild = null
            val targetAction = targetBuildActions.remove(event.operationId)
            if (event.state is IdeBuildState.Succeeded && targetAction != null) completeTargetBuild(targetAction, event.state)
        }
        publishWorkspace()
    }

    private fun acceptResolve(event: IdeEvent.ResolveCompleted) {
        if (event.operationId != latestBuildOperation) return
        state = state.copy(busy = state.busy - IdeBusyOperation.Resolve)
        buildState = IdeBuildState.Idle
        when (val result = event.result) {
            IdeResolveResult.Created -> {
                publishStatus("Created compukter.lock", IdeProblemSeverity.Info)
                requestPoll()
                analysisCoordinator?.reload()
            }

            IdeResolveResult.Updated -> {
                publishStatus("Updated compukter.lock", IdeProblemSeverity.Info)
                requestPoll()
                analysisCoordinator?.reload()
            }

            IdeResolveResult.UpToDate -> {
                publishStatus("Dependencies are up to date", IdeProblemSeverity.Info)
            }

            IdeResolveResult.ConfirmationRequired -> {
                val selected = project ?: return
                state = state.copy(dialog = IdeDialogState.LockUpdate(selected.directoryName))
            }

            is IdeResolveResult.Failed -> {
                publishStatus(result.detail, IdeProblemSeverity.Error)
            }
        }
        publishWorkspace()
    }

    private fun acceptProject(event: IdeEvent.ProjectOpened) {
        if (event.operationId != latestProjectOperation) return
        project = event.project
        tree = event.tree
        state = state.copy(busy = state.busy - IdeBusyOperation.Project)
        publishWorkspace()
        val restore = rememberedFile ?: pendingFile
        rememberedFile = null
        pendingFile = null
        if (restore != null && event.tree.flatten().any { it.path == restore }) openFile(restore)
        persistPreferences(restore)
    }

    private fun acceptFile(event: IdeEvent.FileOpened) {
        if (event.operationId != latestOpenOperation) return
        editor?.close()
        editor = null
        binary = null
        analysisCoordinator?.closeFile()
        observedAnalysisState = IdeAnalysisState.Idle
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
                        session.firstVisibleColumn = remembered.firstVisibleColumn
                    }
                editor = session
                openAnalysis(session)
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
                if (pendingBuildAction != null) {
                    failPendingBuild(IdeBuildFailureKind.Conflict, "save conflict must be resolved before build")
                }
            }

            DocumentSaveResult.ProjectInvalidated -> {
                recoverToStart("Project root changed; reopen the project")
            }
        }
        publishWorkspace()
        if (active.conflict) showConflictDialog(closeRequested)
        if (closeRequested && !active.dirty) closeReady = true
        continuePendingNavigation()
        continuePendingBuild()
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
                    active.close()
                    editor = null
                    analysisCoordinator?.closeFile()
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
            openAnalysis(active)
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
                active.close()
                editor = null
                analysisCoordinator?.closeFile()
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
        if (event.operation == IdeBusyOperation.Build || event.operation == IdeBusyOperation.Resolve) {
            pendingBuildAction = null
            buildState = IdeBuildState.Failed(IdeBuildFailureKind.Platform, event.problem.message)
            publishWorkspace()
        }
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

    private fun continuePendingBuild() {
        val pending = pendingBuildAction ?: return
        val active = editor
        if (active?.conflict == true) {
            failPendingBuild(IdeBuildFailureKind.Conflict, "save conflict must be resolved before build")
            return
        }
        if (active?.dirty == true || active?.saveInFlight != null) return
        val selected = project ?: return
        loadBuildInput(selected, pending.operationId, pending.action, pending.target)
    }

    private fun failPendingBuild(
        kind: IdeBuildFailureKind,
        detail: String,
    ) {
        val pending = pendingBuildAction ?: return
        pendingBuildAction = null
        buildState = IdeBuildState.Failed(kind, detail)
        val busy = if (pending.action.isCompilation) IdeBusyOperation.Build else IdeBusyOperation.Resolve
        state = state.copy(busy = state.busy - busy)
        publishWorkspace()
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
                            buildState,
                        ),
                    ),
            )
    }

    private fun EditorSession.toView(): IdeEditorView.Text {
        val first = firstVisibleLine.coerceIn(0, document.lineCount - 1)
        val lastExclusive = minOf(document.lineCount, first + limits.visibleEditorLines)
        val visible = (first until lastExclusive).map(document::materializeLine)
        val visibleStarts = (first until lastExclusive).map(document::lineStartOffset)
        val selection = document.selectionRange
        return IdeEditorView.Text(
            path,
            visible,
            visibleStarts,
            first,
            firstVisibleColumn,
            document.lineCount,
            document.caretOffset,
            selection?.startUtf16,
            selection?.endUtf16,
            document.revision,
            persistedRevision,
            dirty,
            conflict,
            highlighter.snapshot(),
            admittedAnalysisState(this),
        )
    }

    private fun openAnalysis(active: EditorSession) {
        val selected = project ?: return
        if (!active.path.isKotlinSource) {
            analysisCoordinator?.closeFile()
            observedAnalysisState = IdeAnalysisState.Idle
            return
        }
        analysisCoordinator?.open(
            selected.handle,
            VirtualSourcePath.kotlin(active.path.value),
            active.document.materialize(),
            active.document.revision,
        )
        refreshAnalysisState()
    }

    private fun updateAnalysis(
        active: EditorSession,
        insertedText: String?,
        change: EditorChange,
    ) {
        val selected = project ?: return
        if (!active.path.isKotlinSource) return
        analysisCoordinator?.sourceChanged(
            selected.handle,
            VirtualSourcePath.kotlin(active.path.value),
            active.document.materialize(),
            active.document.revision,
            insertedText,
            active.document.caretOffset,
            change,
        )
        refreshAnalysisState()
    }

    private fun refreshAnalysisState() {
        val current = analysisCoordinator?.state() ?: IdeAnalysisState.Idle
        if (current === observedAnalysisState) return
        observedAnalysisState = current
        if (current is IdeAnalysisState.Unavailable) {
            publishStatus(current.status, IdeProblemSeverity.Warning)
        }
        publishWorkspace()
    }

    private fun completeTargetBuild(
        pending: PendingBuildAction,
        build: IdeBuildState.Succeeded,
    ) {
        val coordinator = targetCoordinator ?: return
        if (coordinator.attachedTarget() != pending.target) {
            publishStatus("Target changed during build; artifact was not sent", IdeProblemSeverity.Warning)
            return
        }
        val artifact = IdeTargetArtifact(build.artifactHash, build.artifact.bytes())
        val path = IdeDeploymentPath.fromProgramName(build.programName)
        when (pending.action) {
            IdeBuildAction.Verify -> coordinator.verify(artifact)
            IdeBuildAction.Deploy -> coordinator.deploy(artifact, path)
            IdeBuildAction.Run -> coordinator.run(artifact, path)
            else -> Unit
        }
        refreshTargetState()
    }

    private fun refreshTargetState() {
        val current = targetCoordinator?.state() ?: IdeTargetState.LocalOnly
        val dialog =
            when {
                current is IdeTargetState.ConfirmationRequired &&
                    (state.dialog == null || state.dialog is IdeDialogState.TargetOverwrite) -> {
                    IdeDialogState.TargetOverwrite(current.path, current.revision)
                }

                current !is IdeTargetState.ConfirmationRequired && state.dialog is IdeDialogState.TargetOverwrite -> {
                    null
                }

                else -> {
                    state.dialog
                }
            }
        if (state.target != current || state.dialog != dialog) state = state.copy(dialog = dialog, target = current)
    }

    private fun admittedAnalysisState(active: EditorSession): IdeAnalysisState {
        if (!active.path.isKotlinSource) return IdeAnalysisState.Idle
        val current = observedAnalysisState
        val path = VirtualSourcePath.kotlin(active.path.value)
        return when (current) {
            is IdeAnalysisState.Active -> {
                current.takeIf { it.path == path && it.documentRevision == active.document.revision } ?: IdeAnalysisState.Idle
            }

            is IdeAnalysisState.Loading -> {
                current.takeIf { it.path == path && it.documentRevision == active.document.revision } ?: IdeAnalysisState.Idle
            }

            is IdeAnalysisState.Unavailable -> {
                current.takeIf { it.path == path && it.documentRevision == active.document.revision } ?: IdeAnalysisState.Idle
            }

            IdeAnalysisState.Idle -> {
                IdeAnalysisState.Idle
            }
        }
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
                active?.firstVisibleColumn ?: 0,
                DEFAULT_TREE_WIDTH,
                DEFAULT_DIAGNOSTICS_HEIGHT,
                true,
            )
        runCatching { preferences.save(admitted) }
    }

    private fun recoverToStart(message: String) {
        cancelBuildJobs()
        pendingBuildAction = null
        buildState = IdeBuildState.Idle
        editor?.close()
        editor = null
        analysisCoordinator?.closeFile()
        observedAnalysisState = IdeAnalysisState.Idle
        binary = null
        project = null
        tree = null
        state =
            IdeViewState(
                generation,
                IdePageState.Start(catalog.take(limits.projectRows).map(::summary), problem(message)),
                null,
                emptySet(),
                state.target,
                state.tooling,
            )
    }

    private fun publishProblem(message: String) {
        state = state.copy(page = pageWithProblem(problem(message)))
    }

    private fun publishStatus(
        message: String,
        severity: IdeProblemSeverity,
    ) {
        val status = IdeProblem(message.boundedUtf8(limits.statusUtf8Bytes), severity)
        state = state.copy(page = pageWithProblem(status))
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

    private fun cancelBuildJobs() {
        buildJobs.values.forEach(IdeBuildJob::cancel)
        buildJobs.clear()
        activeBuild = null
        targetBuildActions.clear()
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
        val highlighter = IncrementalKotlinHighlighter(document)
        var persistedRevision = document.revision
        var saveInFlight: Long? = null
        var conflict = false
        var lastEditMillis = 0L
        var firstVisibleLine = 0
        var firstVisibleColumn = 0
        val dirty: Boolean get() = document.revision != persistedRevision

        fun close() {
            highlighter.close()
            document.close()
        }
    }

    private data class PendingBuildAction(
        val operationId: Long,
        val action: IdeBuildAction,
        val target: IdeAttachedTarget?,
    )

    private companion object {
        const val AUTOSAVE_DELAY_MILLIS = 500L
        const val DEFAULT_TREE_WIDTH = 240
        const val DEFAULT_DIAGNOSTICS_HEIGHT = 160
    }
}

private val IdeBuildAction.isCompilation: Boolean
    get() = this == IdeBuildAction.Build || isTargetAction

private val IdeBuildAction.isTargetAction: Boolean
    get() = this == IdeBuildAction.Verify || this == IdeBuildAction.Deploy || this == IdeBuildAction.Run

private fun IdeEvent.generationOrNull(): Long? =
    when (this) {
        is IdeEvent.ToolingReady,
        is IdeEvent.ToolingFailed,
        -> null

        is IdeEvent.ProjectCatalogLoaded -> generation

        is IdeEvent.BuildInputLoaded -> generation

        is IdeEvent.BuildStateChanged -> generation

        is IdeEvent.ResolveCompleted -> generation

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
    val admitted = takeIf(String::isWellFormedUtf16) ?: return "Invalid error text".boundedUtf8(maxBytes)
    val result = StringBuilder()
    var offset = 0
    var bytes = 0
    while (offset < admitted.length) {
        val codePoint = admitted.codePointAt(offset)
        val scalar = String(Character.toChars(codePoint))
        val scalarBytes = scalar.encodeToByteArray().size
        if (bytes + scalarBytes > maxBytes) break
        result.append(scalar)
        bytes += scalarBytes
        offset += Character.charCount(codePoint)
    }
    return result.toString()
}

private fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        when {
            Character.isHighSurrogate(this[index]) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            }

            Character.isLowSurrogate(this[index]) -> {
                return false
            }

            else -> {
                index++
            }
        }
    }
    return true
}
