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
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisCoordinator
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionInsertion
import ru.lazyhat.compukters.ide.client.analysis.IdeCompletionSelection
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationOutcome
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationTarget
import ru.lazyhat.compukters.ide.client.analysis.IdeUsages
import ru.lazyhat.compukters.ide.client.analysis.IdeUsagesOutcome
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyTrace
import ru.lazyhat.compukters.ide.client.analysis.KotlinSourceTokenRange
import ru.lazyhat.compukters.ide.client.analysis.presentationOrNull
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import ru.lazyhat.compukters.ide.client.build.IdeBuildFailureKind
import ru.lazyhat.compukters.ide.client.build.IdeBuildJob
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.build.IdeResolveResult
import ru.lazyhat.compukters.ide.client.files.IdeComputerFileCoordinator
import ru.lazyhat.compukters.ide.client.files.IdeComputerPreviewState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTransferState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTreeState
import ru.lazyhat.compukters.ide.client.navigation.IdeNavigationHistory
import ru.lazyhat.compukters.ide.client.navigation.IdeNavigationPosition
import ru.lazyhat.compukters.ide.client.navigation.IdeNavigationSource
import ru.lazyhat.compukters.ide.client.preferences.IdePreferences
import ru.lazyhat.compukters.ide.client.preferences.IdePreferencesStore
import ru.lazyhat.compukters.ide.client.preferences.IdeProjectEditorState
import ru.lazyhat.compukters.ide.client.search.IdeFindSession
import ru.lazyhat.compukters.ide.client.search.IdeSelectionOccurrences
import ru.lazyhat.compukters.ide.client.state.BoundedIdeEventQueue
import ru.lazyhat.compukters.ide.client.state.IdeBuildAction
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeConflictAction
import ru.lazyhat.compukters.ide.client.state.IdeDiagnosticRow
import ru.lazyhat.compukters.ide.client.state.IdeDiagnostics
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorSource
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeEvent
import ru.lazyhat.compukters.ide.client.state.IdeGitView
import ru.lazyhat.compukters.ide.client.state.IdeHorizontalDirection
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeProblem
import ru.lazyhat.compukters.ide.client.state.IdeProblemSeverity
import ru.lazyhat.compukters.ide.client.state.IdeProjectRequest
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.state.IdeVerticalDirection
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
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.editor.EditorChange
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorEditResult
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.editor.EditorTextEdit
import ru.lazyhat.compukters.ide.editor.KotlinSmartTyping
import ru.lazyhat.compukters.ide.editor.ProjectEditHistory
import ru.lazyhat.compukters.ide.git.GitCancellation
import ru.lazyhat.compukters.ide.git.GitCredentials
import ru.lazyhat.compukters.ide.git.GitOperation
import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalSnapshot
import ru.lazyhat.compukters.ide.project.ProjectDependencyReceipt
import ru.lazyhat.compukters.ide.project.ProjectDependencyRollback
import ru.lazyhat.compukters.ide.project.ProjectDependencyUpdate
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.document.DocumentSaveResult
import ru.lazyhat.compukters.ide.project.document.FileRevision
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.AdmittedProjectDelete
import ru.lazyhat.compukters.ide.project.tree.ProjectImport
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTree
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

data class IdeAnalysisFailure(
    val path: VirtualSourcePath,
    val documentRevision: Long,
    val detail: String,
)

fun interface IdeAnalysisFailureReporter {
    fun report(failure: IdeAnalysisFailure)

    companion object {
        val None = IdeAnalysisFailureReporter { _ -> }
    }
}

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
    private val visibleLatency: IdeVisibleLatencyTrace = IdeVisibleLatencyTrace.None,
    private val analysisFailureReporter: IdeAnalysisFailureReporter = IdeAnalysisFailureReporter.None,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var state =
        IdeViewState.startPage(emptyList()).copy(
            page = IdePageState.Opening,
            tooling =
                when {
                    tooling != null -> IdeToolingState.Preparing
                    buildCoordinator != null && analysisCoordinator != null -> IdeToolingState.Ready
                    else -> IdeToolingState.Unavailable("Kotlin tooling is unavailable")
                },
        )
    private var buildCoordinator = buildCoordinator
    private var libraryPreparation: CompletableFuture<*>? = null
    private var latestLibraryPreparation = 0L
    private var preparationTarget: TargetCompileProfile? = null
    private var analysisCoordinator = analysisCoordinator
    private var installedTooling: IdeClientTooling? = null
    private val acceptsTooling = AtomicBoolean(true)
    private val acceptsCompletionResults = AtomicBoolean(true)
    private var generation = 0L
    private var nextOperationId = 1L
    private var started = false
    private var closed = false
    private var catalog = emptyList<ProjectDescriptor>()
    private var project: ProjectDescriptor? = null
    private var tree: ProjectTree? = null
    private var editor: EditorSession? = null
    private val documents = linkedMapOf<ProjectPath, EditorSession>()
    private val projectHistory = ProjectEditHistory()
    private val find = IdeFindSession()
    private val selectionOccurrences = IdeSelectionOccurrences()
    private var binary: IdeEditorView.Binary? = null
    private var computerPreview: ComputerPreviewSession? = null
    private var attachedSourcePreview: AttachedSourcePreviewSession? = null
    private var observedComputerPreview: IdeComputerPreviewState = IdeComputerPreviewState.Closed
    private var observedComputerTransfer: IdeComputerTransferState = IdeComputerTransferState.Idle
    private var latestProjectOperation = 0L
    private var latestOpenOperation = 0L
    private var latestDeclarationOperation = 0L
    private var latestUsagesOperation = 0L
    private var pendingRename: PendingRename? = null
    private var usages: IdeUsages? = null
    private var latestSaveOperation = 0L
    private var latestFormatOperation = 0L
    private var latestMutationOperation = 0L
    private var latestComputerImportOperation = 0L
    private var pendingFile: ProjectPath? = null
    private var pendingProjectNavigation: PendingProjectNavigation? = null
    private var pendingAttachedNavigation: PendingAttachedNavigation? = null
    private var openingProjectNavigation: PendingProjectNavigation? = null
    private var pendingProjectDirectory: String? = null
    private var pendingProjectCreation: IdeProjectRequest? = null
    private var gitView =
        IdeGitView()
    private val gitDraft =
        ru.lazyhat.compukters.ide.client.git
            .IdeGitDraftSession()
    private var bottomPanel =
        ru.lazyhat.compukters.ide.client.state
            .IdeBottomPanelView()
    private var inspectionCancellation: GitCancellation? = null
    private var latestInspection = 0L
    private var lastInspectionMillis = Long.MIN_VALUE
    private var inspectedPath: ProjectPath? = null
    private var inspectedRevision: Long? = null
    private var gitLineChanges = emptyList<ru.lazyhat.compukters.ide.git.GitLineChange>()
    private var pushAfterCommit = false
    private var pendingGit: GitOperation? = null
    private var latestGitOperation = 0L
    private var gitCancellation: GitCancellation? = null
    private var gitCredentials: GitCredentials? = null
    private var refreshAnalysisAfterGit = false
    private var creatingProject = false
    private var pendingSave = false
    private var pendingFormat = false
    private var restoreEditorState: IdeProjectEditorState? = null
    private var closeRequested = false
    private var closeReady = false
    private var admittedDelete: AdmittedProjectDelete? = null
    private var preferencesSnapshot = IdePreferences.empty(DEFAULT_TREE_WIDTH, DEFAULT_DIAGNOSTICS_HEIGHT, true)
    private val eventOverflow = AtomicBoolean()
    private var managingProjects = false
    private var pendingCatalogRemoval: Pair<Long, ProjectDescriptor>? = null
    private var buildState: IdeBuildState = IdeBuildState.Idle
    private var pendingBuildAction: PendingBuildAction? = null
    private var latestBuildOperation = 0L
    private var latestCompletionOperation = 0L
    private var activeBuild: IdeBuildJob? = null
    private val buildJobs = mutableMapOf<Long, IdeBuildJob>()
    private var observedAnalysisState: IdeAnalysisState = IdeAnalysisState.Idle
    private val targetBuildActions = mutableMapOf<Long, PendingBuildAction>()
    private val computerFiles = targetCoordinator?.let { IdeComputerFileCoordinator(it, limits.eventQueueCapacity) }
    private var computerTarget: IdeAttachedTarget? = null
    private val navigationHistory = IdeNavigationHistory(limits.navigationHistory)

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
        if (remembered != null) preferencesSnapshot = remembered
        gitDraft.restoreAuthor(preferencesSnapshot.gitAuthorName, preferencesSnapshot.gitAuthorEmail)
        bottomPanel =
            bottomPanel.copy(
                tab = if (preferencesSnapshot.diagnosticsExpanded) ru.lazyhat.compukters.ide.client.state.IdeBottomTab.Problems else null,
            )
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
        if (managingProjects) return
        if (creatingProject && command !is IdeCommand.CancelGit && command !is IdeCommand.OpenProject) return
        if (IdeBusyOperation.Git in state.busy && command !is IdeCommand.CancelGit &&
            command !is IdeCommand.GitVisible && command !is IdeCommand.ScrollGit && command !is IdeCommand.OpenProject &&
            command !is IdeCommand.BottomTab && command !is IdeCommand.ScrollBottom
        ) {
            return
        }
        when (command) {
            is IdeCommand.ImportProject -> {
                createProject(
                    IdeProjectRequest
                        .Existing(command.root),
                )
            }

            is IdeCommand.CloneProject -> {
                createProject(
                    IdeProjectRequest
                        .Clone(command.name, command.remote),
                )
            }

            is IdeCommand.Git -> {
                requestGit(command.operation)
            }

            is IdeCommand.GitTab -> {
                gitDraft.focus(null)
                gitView = gitView.copy(tab = command.tab, scroll = 0, menu = null)
                publishWorkspace()
                when (command.tab) {
                    ru.lazyhat.compukters.ide.client.state.IdeGitTab.Changes -> {
                        requestGit(GitOperation.Status)
                    }

                    ru.lazyhat.compukters.ide.client.state.IdeGitTab.Diff -> {
                        gitView.previewPath?.let {
                            requestGit(
                                GitOperation.Diff(it, againstHead = true),
                            )
                        }
                    }
                }
            }

            is IdeCommand.BottomTab -> {
                gitDraft.focus(null)
                bottomPanel = bottomPanel.copy(tab = command.tab, scroll = 0)
                if (command.tab == ru.lazyhat.compukters.ide.client.state.IdeBottomTab.GitLog) {
                    gitView = gitView.copy(visible = false, menu = null)
                }
                publishWorkspace()
                if (command.tab == ru.lazyhat.compukters.ide.client.state.IdeBottomTab.GitLog && state.busy.isEmpty()) {
                    requestGit(GitOperation.History)
                }
            }

            is IdeCommand.ScrollBottom -> {
                bottomPanel =
                    bottomPanel.copy(
                        scroll =
                            (bottomPanel.scroll.toLong() + command.lines)
                                .coerceIn(0, command.maximum.coerceIn(0, 1_000_000).toLong())
                                .toInt(),
                    )
                publishWorkspace()
            }

            is IdeCommand.GitMenu -> {
                gitDraft.focus(null)
                gitView = gitView.copy(menu = command.menu, scroll = 0)
                publishWorkspace()
            }

            is IdeCommand.GitCheck -> {
                val available =
                    gitView.result
                        ?.status
                        ?.changes
                        ?.mapTo(linkedSetOf()) { it.path } ?: emptySet()
                val checked = gitView.checkedPaths.toMutableSet()
                if (command.path == null) {
                    if (checked.containsAll(available)) checked.clear() else checked.addAll(available)
                } else if (command.path in available) {
                    if (!checked.add(command.path)) checked.remove(command.path)
                }
                gitView = gitView.copy(checkedPaths = java.util.Collections.unmodifiableSet(checked))
                publishWorkspace()
            }

            is IdeCommand.GitPreview -> {
                if (gitView.result
                        ?.status
                        ?.changes
                        ?.any { it.path == command.path } == true
                ) {
                    gitDraft.focus(null)
                    gitView =
                        gitView.copy(
                            previewPath = command.path,
                            menu = null,
                            tab = if (command.showDiff) ru.lazyhat.compukters.ide.client.state.IdeGitTab.Diff else gitView.tab,
                        )
                    publishWorkspace()
                    requestGit(GitOperation.Diff(command.path, againstHead = true))
                }
            }

            is IdeCommand.GitFocusField -> {
                gitDraft.focus(command.field)
                command.scroll?.let { gitView = gitView.copy(scroll = it.coerceIn(0, 1_000_000)) }
                publishWorkspace()
            }

            is IdeCommand.EditGitDraft -> {
                gitDraft.edit(command.input)
                publishWorkspace()
            }

            is IdeCommand.GitCommitDraft -> {
                val draft = gitDraft.view()
                val email = draft.authorEmail.text.trim()
                if (!draft.canCommit || '@' !in email || email.any { it.isWhitespace() || it == '<' || it == '>' } ||
                    gitView.checkedPaths.isEmpty()
                ) {
                    publishStatus("Select files and enter a commit message, author name and email", IdeProblemSeverity.Warning)
                } else {
                    pushAfterCommit = command.push
                    persistGitAuthor()
                    gitView = gitView.copy(menu = null)
                    requestGit(
                        GitOperation.CommitSelected(
                            gitView.checkedPaths.sortedBy { it.value },
                            draft.message.text,
                            draft.authorName.text.trim(),
                            email,
                        ),
                    )
                }
            }

            is IdeCommand.GitVisible -> {
                gitView = gitView.copy(visible = command.visible)
                publishWorkspace()
                if (command.visible && IdeBusyOperation.Git !in state.busy) requestGit(GitOperation.Status)
            }

            is IdeCommand.ScrollGit -> {
                val preview = command.area == ru.lazyhat.compukters.ide.client.state.IdeGitScrollArea.Preview
                val previous = if (preview) gitView.previewScroll else gitView.scroll
                val scroll = (previous.toLong() + command.lines).coerceIn(0, command.maximum.coerceIn(0, 1_000_000).toLong()).toInt()
                gitView =
                    if (preview) gitView.copy(previewScroll = scroll) else gitView.copy(scroll = scroll)
                publishWorkspace()
            }

            is IdeCommand.SetGitCredentials -> {
                gitCredentials?.close()
                gitCredentials = command.credentials
                gitView = gitView.copy(authenticated = command.credentials != null)
                publishWorkspace()
                publishStatus(
                    if (command.credentials ==
                        null
                    ) {
                        "HTTPS credentials forgotten"
                    } else {
                        "HTTPS credentials set for this IDE session"
                    },
                    IdeProblemSeverity.Info,
                )
            }

            IdeCommand.CancelGit -> {
                gitCancellation?.cancel()
                inspectionCancellation?.cancel()
            }

            is IdeCommand.CreateProject -> {
                createProject(command.name)
            }

            is IdeCommand.RenameProject -> {
                manageProject(command.directoryName, command.name)
            }

            is IdeCommand.RequestRemoveProject -> {
                val selected = catalog.singleOrNull { it.directoryName == command.directoryName } ?: return
                if (!canManageProject(selected)) return
                val actionId = nextOperationId++
                pendingCatalogRemoval = actionId to selected
                state =
                    state.copy(
                        dialog =
                            IdeDialogState.Confirmation(
                                if (selected.external) "Forget external project" else "Delete project folder",
                                if (selected.external) {
                                    "Remove ${selected.handle.canonicalPath} from the list? Files will be kept."
                                } else {
                                    "Permanently delete ${selected.handle.canonicalPath} and ALL files inside?"
                                },
                                actionId,
                            ),
                    )
            }

            is IdeCommand.OpenProject -> {
                requestProjectSwitch(command.directoryName)
            }

            is IdeCommand.OpenFile -> {
                requestFileSwitch(command.path)
            }

            is IdeCommand.ExpandComputerDirectory -> {
                computerFiles?.expand(command.path)
                publishWorkspace()
            }

            IdeCommand.RefreshComputerTree -> {
                computerFiles?.refresh()
                publishWorkspace()
            }

            is IdeCommand.OpenComputerFile -> {
                closeAttachedSourcePreview()
                computerFiles?.open(command.path)
                publishStatus("Opening ${command.path.value}", IdeProblemSeverity.Info)
            }

            is IdeCommand.DropComputerEntry -> {
                if (IdeBusyOperation.Project in state.busy) return
                if (computerFiles?.drop(command.source, command.destinationDirectory) == true) {
                    state = state.copy(dialog = null)
                    publishWorkspace()
                }
            }

            IdeCommand.ConfirmComputerImport -> {
                confirmComputerImport()
            }

            IdeCommand.CancelComputerImport -> {
                computerFiles?.cancelTransfer()
                state = state.copy(dialog = null)
                publishWorkspace()
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

            is IdeCommand.RenameSymbol -> {
                renameSymbol(command.newName)
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
                    pendingCatalogRemoval = null
                    state = state.copy(dialog = null)
                }
            }

            is IdeCommand.Edit -> {
                edit(command.input)
            }

            is IdeCommand.ScrollEditor -> {
                scrollEditor(command.lines, command.columns)
            }

            IdeCommand.OpenFind -> {
                usages = usages?.unfocus()
                currentDocument()?.let(find::open)
                analysisCoordinator?.dismissCompletion()
                analysisCoordinator?.dismissParameterInfo()
                refreshAnalysisState()
                publishWorkspace()
            }

            IdeCommand.CloseFind -> {
                find.dismiss()
                publishWorkspace()
            }

            is IdeCommand.FocusFind -> {
                find.focus(command.focused)
                publishWorkspace()
            }

            is IdeCommand.EditFind -> {
                if (!find.visible) return
                find.edit(command.input)
                navigateFind(backwards = false, includeCurrent = true)
            }

            is IdeCommand.NavigateFind -> {
                if (find.visible) navigateFind(command.backwards)
            }

            IdeCommand.Save -> {
                requestSave()
            }

            IdeCommand.Format -> {
                requestFormat()
            }

            is IdeCommand.OpenDiagnostic -> {
                openDiagnostic(command.row)
            }

            is IdeCommand.NavigateDiagnostic -> {
                currentDiagnostics().next(editor?.path, editor?.document?.caretOffset ?: 0, command.backwards)?.let(::openDiagnostic)
            }

            IdeCommand.FindUsages -> {
                findUsages()
            }

            is IdeCommand.FindUsagesAt -> {
                findUsages(command.offsetUtf16)
            }

            IdeCommand.CloseUsages -> {
                invalidateUsages()
                latestUsagesOperation = nextOperationId++
                publishWorkspace()
            }

            IdeCommand.UnfocusUsages -> {
                usages = usages?.unfocus()
                publishWorkspace()
            }

            is IdeCommand.MoveUsage -> {
                usages = usages?.move(command.delta)
                publishWorkspace()
            }

            is IdeCommand.OpenUsage -> {
                openUsage(command.index)
            }

            IdeCommand.Poll -> {
                requestPoll()
            }

            IdeCommand.PointerActivity -> {
                if (documents.values.any { it.dirty }) requestSave()
            }

            IdeCommand.CloseRequested -> {
                closeRequested = true
                continueClosing()
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

            IdeCommand.ShowParameterInfo -> {
                analysisCoordinator?.showParameterInfo()
                refreshAnalysisState()
                publishWorkspace()
            }

            IdeCommand.DismissParameterInfo -> {
                analysisCoordinator?.dismissParameterInfo()
                refreshAnalysisState()
                publishWorkspace()
            }

            is IdeCommand.SourcePointer -> {
                sourcePointer(command.offsetUtf16, command.controlDown)
            }

            is IdeCommand.GoToDeclaration -> {
                goToDeclaration(command.offsetUtf16)
            }

            IdeCommand.ControlReleased -> {
                analysisCoordinator?.controlReleased()
                refreshAnalysisState()
                publishWorkspace()
            }

            is IdeCommand.MoveDeclarationChoice -> {
                analysisCoordinator?.moveDeclarationChoice(command.delta)
                refreshAnalysisState()
                publishWorkspace()
            }

            IdeCommand.AcceptDeclarationChoice -> {
                analysisCoordinator?.acceptDeclarationChoice()?.let(::navigateToDeclaration)
                refreshAnalysisState()
                publishWorkspace()
            }

            IdeCommand.DismissSemanticInteraction -> {
                analysisCoordinator?.dismissSemanticInteraction()
                refreshAnalysisState()
                publishWorkspace()
            }

            IdeCommand.NavigateBack -> {
                navigateHistory(NavigationDirection.Back)
            }

            IdeCommand.NavigateForward -> {
                navigateHistory(NavigationDirection.Forward)
            }

            IdeCommand.EditorFocusLost -> {
                analysisCoordinator?.focusLost()
                refreshAnalysisState()
                publishWorkspace()
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
        refreshComputerFiles()
        val active =
            documents.values.firstOrNull {
                it.dirty && !it.conflict && it.saveInFlight == null && it.formatInFlight == null &&
                    clock.nowMillis() - it.lastEditMillis >= AUTOSAVE_DELAY_MILLIS
            }
        if (active != null && pendingRename == null && IdeBusyOperation.Git !in state.busy) saveDocument(active)
        refreshAnalysisState()
        inspectGit()
    }

    fun viewState(): IdeViewState {
        checkOwner()
        return state
    }

    fun selectedText(): String? {
        checkOwner()
        return when {
            attachedSourcePreview != null -> attachedSourcePreview?.document?.copySelection()
            computerPreview != null -> computerPreview?.document?.copySelection()
            else -> editor?.document?.copySelection()
        }
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
        refreshComputerFiles()
    }

    fun isCloseReady(): Boolean {
        checkOwner()
        return closeReady
    }

    override fun close() {
        checkOwner()
        if (closed) return
        persistGitAuthor()
        closed = true
        cancelLibraryPreparation()
        gitCancellation?.cancel()
        inspectionCancellation?.cancel()
        gitCredentials?.close()
        gitCredentials = null
        gitDraft.close()
        find.close()
        selectionOccurrences.clear()
        acceptsTooling.set(false)
        acceptsCompletionResults.set(false)
        events.drain().forEach { event ->
            if (event is IdeEvent.ToolingReady) event.tooling.close()
            if (event is IdeEvent.CompletionModuleEnabled) {
                (event.result as? ProjectDependencyUpdate.Published)?.let { published ->
                    buildCoordinator?.rollbackModuleNow(event.project, published.receipt)
                }
            }
        }
        generation = Math.incrementExact(generation)
        project?.let { persistPreferences(editor?.path ?: binary?.path) }
        closeProjectDocuments()
        closeComputerPreview()
        closeAttachedSourcePreview()
        closeAnalysisFile(forceDrop = true)
        workspace.close()
        cancelBuildJobs()
        installedTooling?.close()
        if (installedTooling == null) {
            buildCoordinator?.close()
            analysisCoordinator?.close()
        }
        targetCoordinator?.close()
        computerFiles?.detach()
    }

    private fun openProject(directoryName: String) {
        creatingProject = false
        pendingGit = null
        gitCancellation?.cancel()
        gitView =
            IdeGitView(authenticated = gitCredentials != null)
        val selected = catalog.singleOrNull { it.directoryName == directoryName }
        if (selected == null) {
            publishProblem("Project '$directoryName' is unavailable")
            return
        }
        gitDraft.clearMessage()
        pushAfterCommit = false
        generation = Math.incrementExact(generation)
        navigationHistory.clear()
        cancelComputerTransfer()
        project?.let { persistPreferences(editor?.path ?: binary?.path) }
        restoreEditorState = preferencesSnapshot.projectState(directoryName)
        cancelBuildJobs()
        pendingBuildAction = null
        pendingSave = false
        pendingFormat = false
        buildState = IdeBuildState.Idle
        closeProjectDocuments()
        closeComputerPreview()
        closeAttachedSourcePreview()
        closeAnalysisFile()
        binary = null
        pendingProjectNavigation = null
        pendingAttachedNavigation = null
        openingProjectNavigation = null
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

    private fun createProject(name: String) =
        createProject(
            IdeProjectRequest
                .Create(name),
        )

    private fun createProject(request: IdeProjectRequest) {
        val remaining = documents.values.firstOrNull { it.dirty || it.saveInFlight != null || it.formatInFlight != null }
        if (remaining != null) {
            pendingProjectCreation = request
            saveDocument(remaining)
            return
        }
        project?.let { persistPreferences(editor?.path ?: binary?.path) }
        cancelBuildJobs()
        buildState = IdeBuildState.Idle
        creatingProject = true
        generation = Math.incrementExact(generation)
        navigationHistory.clear()
        cancelComputerTransfer()
        closeComputerPreview()
        closeAttachedSourcePreview()
        val operationId = nextOperationId++
        latestProjectOperation = operationId
        restoreEditorState = null
        pendingFile = ProjectPath.file("src/main.kt")
        state =
            state.copy(
                generation = generation,
                busy =
                    if (request is IdeProjectRequest.Clone) {
                        setOf(IdeBusyOperation.Project, IdeBusyOperation.Clone)
                    } else {
                        setOf(IdeBusyOperation.Project)
                    },
                dialog = null,
            )
        val requestGeneration = generation
        pendingGit = null
        gitCancellation?.cancel()
        val creation =
            when (request) {
                is IdeProjectRequest.Create -> {
                    workspace.createProject(request.name)
                }

                is IdeProjectRequest.Existing -> {
                    workspace.importProject(request.root)
                }

                is IdeProjectRequest.Clone -> {
                    val cancellation =
                        GitCancellation()
                    gitCancellation = cancellation
                    workspace.cloneProject(request.name, request.remote, gitCredentials, cancellation)
                }
            }
        creation
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
        pendingProjectCreation = null
        val active = documents.values.firstOrNull { it.dirty }
        if (active != null && active.dirty) {
            pendingProjectDirectory = directoryName
            saveDocument(active)
        } else {
            openProject(directoryName)
        }
    }

    private fun requestFileSwitch(path: ProjectPath) {
        closeComputerPreview()
        closeAttachedSourcePreview()
        pendingProjectNavigation = null
        pendingAttachedNavigation = null
        openingProjectNavigation = null
        val active = editor
        if (active != null && active.path != path && active.dirty) {
            pendingFile = path
            requestSave()
            return
        }
        openFile(path)
    }

    private fun openFile(
        path: ProjectPath,
        navigation: PendingProjectNavigation? = null,
    ) {
        val selected = project ?: return
        closeComputerPreview()
        closeAttachedSourcePreview()
        val operationId = nextOperationId++
        latestOpenOperation = operationId
        openingProjectNavigation = navigation
        documents[path]?.let { cached ->
            activateDocument(cached, navigation)
            return
        }
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

    private fun currentDocument(): EditorDocument? = attachedSourcePreview?.document ?: computerPreview?.document ?: editor?.document

    private fun activateDocument(
        session: EditorSession,
        navigation: PendingProjectNavigation? = null,
    ) {
        if (navigation?.expectedText != null && !admitNavigation(navigation, session.document.materialize())) {
            openingProjectNavigation = null
            return
        }
        closeAnalysisFile()
        editor = session
        binary = null
        openingProjectNavigation = null
        navigation?.let {
            restoreCaret(session.document, it.caretUtf16)
            session.firstVisibleLine = it.firstVisibleLine ?: session.document.lineContaining(session.document.caretOffset)
            session.firstVisibleColumn = it.firstVisibleColumn
        }
        openAnalysis(session)
        state = state.copy(busy = state.busy - IdeBusyOperation.Project)
        publishWorkspace()
        persistPreferences(session.path)
        if (navigation != null) completeNavigation(navigation)
    }

    private fun closeProjectDocuments() {
        cancelLibraryPreparation()
        pendingRename = null
        documents.values.forEach(EditorSession::close)
        documents.clear()
        projectHistory.clear()
        invalidateUsages()
        pendingProjectCreation = null
        editor = null
    }

    private fun removeDocument(session: EditorSession) {
        documents.remove(session.path)
        if (editor === session) editor = null
        session.close()
    }

    private fun admitDocument(): Boolean {
        if (documents.size < limits.projectDocuments) return true
        val disposable =
            documents.values.firstOrNull {
                !it.dirty && it.saveInFlight == null && it.formatInFlight == null && !projectHistory.retains(it.document)
            } ?: return false
        removeDocument(disposable)
        return true
    }

    private fun navigateFind(
        backwards: Boolean,
        includeCurrent: Boolean = false,
    ) {
        val document = currentDocument() ?: return
        val match = find.navigate(document, backwards, includeCurrent)
        if (match != null) {
            document.setCaret(match.startUtf16, false)
            val line = document.lineContaining(match.startUtf16)
            val column = (document.caretVisualColumn - 4).coerceAtLeast(0)
            document.setCaret(match.endUtf16, true)
            when {
                attachedSourcePreview != null -> {
                    attachedSourcePreview!!.let {
                        it.firstVisibleLine = line
                        it.firstVisibleColumn = column
                    }
                }

                computerPreview != null -> {
                    computerPreview!!.let {
                        it.firstVisibleLine = line
                        it.firstVisibleColumn = column
                    }
                }

                else -> {
                    editor?.let {
                        it.firstVisibleLine = line
                        it.firstVisibleColumn = column
                    }
                }
            }
            analysisCoordinator?.caretMoved(document.caretOffset)
            analysisCoordinator?.dismissCompletion()
            refreshAnalysisState()
        }
        publishWorkspace()
    }

    private fun edit(input: IdeEditorInput) {
        attachedSourcePreview?.let { preview ->
            editAttachedSourcePreview(preview, input)
            return
        }
        computerPreview?.let { preview ->
            editComputerPreview(preview, input)
            return
        }
        val active = editor ?: return
        if ((input == IdeEditorInput.Tab || input == IdeEditorInput.Enter) && active.path.isKotlinSource) {
            val analysis = analysisCoordinator
            val selection = analysis?.selectCompletion(active.document, VirtualSourcePath.kotlin(active.path.value))
            if (selection != null) {
                acceptCompletion(selection)
                return
            }
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
                    if (active.path.isKotlinSource) active.smartTyping.type(input.text) else active.document.type(input.text)
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

                is IdeEditorInput.MoveWord -> {
                    when (input.direction) {
                        IdeHorizontalDirection.Left -> active.document.moveWordLeft(input.extendSelection)
                        IdeHorizontalDirection.Right -> active.document.moveWordRight(input.extendSelection)
                    }
                    null
                }

                is IdeEditorInput.Page -> {
                    page(active.document, input)
                    active.firstVisibleLine = pageFirstVisibleLine(active.firstVisibleLine, active.document, input)
                    null
                }

                is IdeEditorInput.SelectToken -> {
                    active.document.selectToken(input.offsetUtf16)
                    null
                }

                IdeEditorInput.Backspace -> {
                    if (active.path.isKotlinSource) active.smartTyping.backspace() else active.document.backspace()
                }

                IdeEditorInput.Delete -> {
                    active.document.delete()
                }

                IdeEditorInput.DeleteWordBackward -> {
                    active.document.deleteWordBackward()
                }

                IdeEditorInput.DeleteWordForward -> {
                    active.document.deleteWordForward()
                }

                IdeEditorInput.Enter -> {
                    if (active.path.isKotlinSource) active.smartTyping.enter() else active.document.enter()
                }

                IdeEditorInput.Tab -> {
                    active.document.indent()
                }

                IdeEditorInput.Outdent -> {
                    active.document.outdent()
                }

                IdeEditorInput.ToggleLineComment -> {
                    when {
                        active.path.isKotlinSource -> active.document.toggleLineComments()
                        active.path.value.endsWith(".toml") -> active.document.toggleLineComments("#")
                        else -> EditorEditResult.NoChange
                    }
                }

                IdeEditorInput.Cut -> {
                    active.document.cut()
                }

                IdeEditorInput.Undo -> {
                    moveProjectHistory(active, redo = false)
                }

                IdeEditorInput.Redo -> {
                    moveProjectHistory(active, redo = true)
                }

                IdeEditorInput.SelectAll -> {
                    active.document.selectAll()
                    null
                }
            }
        if (result is EditorEditResult.Applied) {
            invalidateUsages()
            active.lastEditMillis = clock.nowMillis()
            if (active.path.isKotlinSource && analysisCoordinator != null) {
                visibleLatency.editApplied(active.document.revision)
            }
            updateAnalysis(active, (input as? IdeEditorInput.Type)?.text, result.change)
        } else if (
            input is IdeEditorInput.SetCaret || input is IdeEditorInput.Move || input is IdeEditorInput.MoveWord ||
            input is IdeEditorInput.Page || input is IdeEditorInput.SelectToken || input is IdeEditorInput.Type
        ) {
            analysisCoordinator?.caretMoved(active.document.caretOffset, requestOccurrences = input !is IdeEditorInput.Type)
            analysisCoordinator?.dismissCompletion()
            refreshAnalysisState()
        }
        publishWorkspace()
    }

    private fun moveProjectHistory(
        active: EditorSession,
        redo: Boolean,
    ): EditorEditResult =
        when (val result = if (redo) projectHistory.redo(active.document) else projectHistory.undo(active.document)) {
            is ProjectEditHistory.Result.Applied -> {
                documents.values.filter { it.document in result.changes }.forEach { it.lastEditMillis = clock.nowMillis() }
                result.changes[active.document]?.let { EditorEditResult.Applied(it) } ?: EditorEditResult.NoChange
            }

            ProjectEditHistory.Result.NoChange -> {
                EditorEditResult.NoChange
            }

            is ProjectEditHistory.Result.Rejected -> {
                publishStatus("Cannot ${if (redo) "redo" else "undo"}: ${result.detail}", IdeProblemSeverity.Warning)
                EditorEditResult.NoChange
            }
        }

    private fun acceptCompletion(selection: IdeCompletionSelection) {
        val requirement = selection.entry.addonRequirement
        if (requirement == null) {
            applyCompletionSelection(selection)
            refreshAnalysisState()
            publishWorkspace()
            return
        }
        if (IdeBusyOperation.Resolve in state.busy) {
            publishStatus("Another dependency operation is already running", IdeProblemSeverity.Warning)
            return
        }
        val selected = project ?: return
        val coordinator = buildCoordinator
        if (coordinator == null) {
            publishStatus("Local dependency resolver is unavailable", IdeProblemSeverity.Warning)
            return
        }
        val operationId = nextOperationId++
        latestCompletionOperation = operationId
        val requestGeneration = generation
        val target = targetCoordinator?.attachedTarget()
        state = state.copy(busy = state.busy + IdeBusyOperation.Resolve)
        publishWorkspace()
        coordinator
            .enableAddon(selected.handle, requirement.id, target?.compileProfile)
            .whenComplete { result, failure ->
                val mapped =
                    if (failure == null) {
                        result
                    } else {
                        ProjectDependencyUpdate.Conflict(failure.message ?: "module enablement failed")
                    }
                val event =
                    IdeEvent.CompletionModuleEnabled(
                        requestGeneration,
                        operationId,
                        selected.handle,
                        target,
                        selection,
                        mapped,
                    )
                if (!acceptsCompletionResults.get()) {
                    (mapped as? ProjectDependencyUpdate.Published)?.let { published ->
                        coordinator.rollbackModuleNow(selected.handle, published.receipt)
                    }
                } else if (!events.offer(event)) {
                    eventOverflow.set(true)
                    (mapped as? ProjectDependencyUpdate.Published)?.let { published ->
                        coordinator.rollbackModuleNow(selected.handle, published.receipt)
                    }
                }
            }
    }

    private fun acceptCompletionModule(event: IdeEvent.CompletionModuleEnabled) {
        if (event.operationId != latestCompletionOperation || event.generation != generation || project?.handle != event.project) {
            rollbackCompletionModule(
                event.operationId,
                event.project,
                (event.result as? ProjectDependencyUpdate.Published)?.receipt,
                clearBusy = false,
            )
            return
        }
        val update = event.result
        if (update is ProjectDependencyUpdate.Conflict) {
            state = state.copy(busy = state.busy - IdeBusyOperation.Resolve)
            publishStatus("Cannot enable completion module: ${update.detail}", IdeProblemSeverity.Warning)
            return
        }
        val active = editor
        val current =
            event.generation == generation &&
                project?.handle == event.project &&
                targetCoordinator?.attachedTarget() == event.target &&
                active != null &&
                analysisCoordinator?.isCompletionSelectionCurrent(event.selection, active.document) == true
        if (!current || !applyCompletionSelection(event.selection)) {
            rollbackCompletionModule(
                event.operationId,
                event.project,
                (update as? ProjectDependencyUpdate.Published)?.receipt,
                clearBusy = true,
            )
            return
        }
        state = state.copy(busy = state.busy - IdeBusyOperation.Resolve)
        if (update is ProjectDependencyUpdate.Published) {
            val module =
                event.selection.entry.addonRequirement
                    ?.id
                    ?.value
                    .orEmpty()
            publishStatus("Enabled module $module", IdeProblemSeverity.Info)
            requestPoll()
            analysisCoordinator?.reload()
        } else {
            refreshAnalysisState()
            publishWorkspace()
        }
    }

    private fun applyCompletionSelection(selection: IdeCompletionSelection): Boolean {
        val active = editor ?: return false
        val insertion =
            IdeCompletionInsertion.plan(
                selection.entry.proposal,
                selection.replacement,
                active.document.copyRange(
                    EditorRange(
                        selection.replacement.endUtf16,
                        minOf(
                            active.document.length,
                            selection.replacement.endUtf16 + IdeCompletionInsertion.SUFFIX_LIMIT,
                        ),
                    ),
                ),
            )
        val result =
            active.document.replaceRanges(
                insertion.replacement,
                insertion.text,
                selection.entry.proposal.additionalEdits
                    .map { EditorTextEdit(it.range, it.text) },
                insertion.caretUtf16,
            )
        if (result !is EditorEditResult.Applied) {
            publishStatus("Completion edit was rejected", IdeProblemSeverity.Warning)
            return false
        }
        val insertedStart = active.document.caretOffset - insertion.caretUtf16
        insertion.automaticClosers.forEach { active.smartTyping.rememberAutomaticCloser(insertedStart + it) }
        active.lastEditMillis = clock.nowMillis()
        visibleLatency.editApplied(active.document.revision)
        updateAnalysis(active, null, result.change)
        return true
    }

    private fun rollbackCompletionModule(
        operationId: Long,
        project: ru.lazyhat.compukters.ide.project.ProjectHandle,
        receipt: ProjectDependencyReceipt?,
        clearBusy: Boolean,
    ) {
        if (receipt == null) {
            if (clearBusy) {
                state = state.copy(busy = state.busy - IdeBusyOperation.Resolve)
                publishWorkspace()
            }
            return
        }
        val coordinator = buildCoordinator
        if (coordinator == null) {
            if (clearBusy) {
                state = state.copy(busy = state.busy - IdeBusyOperation.Resolve)
                publishStatus("Completion became stale and dependency rollback is unavailable", IdeProblemSeverity.Warning)
            }
            return
        }
        coordinator.rollbackModule(project, receipt).whenComplete { result, failure ->
            val mapped =
                if (failure == null) result else ProjectDependencyRollback.Conflict(failure.message ?: "dependency rollback failed")
            enqueue(IdeEvent.CompletionModuleRollbackCompleted(operationId, clearBusy, mapped))
        }
    }

    private fun acceptCompletionRollback(event: IdeEvent.CompletionModuleRollbackCompleted) {
        if (!event.clearBusy || event.operationId != latestCompletionOperation) return
        state = state.copy(busy = state.busy - IdeBusyOperation.Resolve)
        when (val result = event.result) {
            ProjectDependencyRollback.Restored -> {
                publishStatus("Completion was cancelled because its context changed", IdeProblemSeverity.Info)
            }

            is ProjectDependencyRollback.Conflict -> {
                publishStatus("Completion was cancelled; dependency rollback conflicted: ${result.detail}", IdeProblemSeverity.Warning)
            }
        }
        requestPoll()
        analysisCoordinator?.reload()
    }

    private fun scrollEditor(
        lines: Int,
        columns: Int,
    ) {
        analysisCoordinator?.dismissSemanticInteraction()
        refreshAnalysisState()
        attachedSourcePreview?.let { preview ->
            scrollAttachedSourcePreview(preview, lines, columns)
            return
        }
        computerPreview?.let { preview ->
            preview.firstVisibleLine =
                (preview.firstVisibleLine.toLong() + lines)
                    .coerceIn(0, (preview.document.lineCount - 1).toLong())
                    .toInt()
            preview.firstVisibleColumn =
                (preview.firstVisibleColumn.toLong() + columns)
                    .coerceIn(0, preview.document.maximumVisualWidth().toLong())
                    .toInt()
            publishWorkspace()
            return
        }
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
        if (attachedSourcePreview != null) {
            publishStatus("Attached API sources are read-only", IdeProblemSeverity.Info)
            return
        }
        if (computerPreview != null) {
            publishStatus("Computer files are read-only", IdeProblemSeverity.Info)
            return
        }
        val active = editor?.takeIf { it.dirty } ?: documents.values.firstOrNull { it.dirty } ?: return
        saveDocument(active)
    }

    private fun saveDocument(active: EditorSession) {
        val selected = project ?: return
        if (IdeBusyOperation.Project in state.busy) {
            pendingSave = true
            return
        }
        if (active.conflict) {
            showConflictDialog(closeRequested, active)
            return
        }
        if (!active.dirty || documents.values.any { it.saveInFlight != null } || active.formatInFlight != null) return
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

    private fun requestFormat() {
        if (attachedSourcePreview != null || computerPreview != null) {
            publishStatus("Only writable Kotlin sources can be formatted", IdeProblemSeverity.Info)
            return
        }
        val active = editor ?: return
        if (!active.path.isKotlinSource) {
            publishStatus("Only writable Kotlin sources can be formatted", IdeProblemSeverity.Info)
            return
        }
        if (IdeBusyOperation.Project in state.busy || active.formatInFlight != null) {
            pendingFormat = true
            return
        }
        if (active.conflict) return
        val analysis = analysisCoordinator
        if (analysis == null) {
            publishStatus("Kotlin formatter unavailable", IdeProblemSeverity.Warning)
            return
        }
        val operationId = nextOperationId++
        latestFormatOperation = operationId
        val submittedRevision = active.document.revision
        val source = active.document.materialize()
        val path = active.path
        val requestGeneration = generation
        active.formatInFlight = submittedRevision
        state = state.copy(busy = state.busy + IdeBusyOperation.Format)
        publishWorkspace()
        analysis
            .format(
                VirtualSourcePath.kotlin(path.value),
                source,
                active.document.caretOffset,
                submittedRevision,
            ).whenComplete { result, failure ->
                val outcome =
                    if (failure == null && result != null) {
                        result
                    } else {
                        val actual = (failure as? CompletionException)?.cause ?: failure
                        AnalysisClientResult.Failure(
                            AnalysisFailureKind.InternalAnalysis,
                            actual?.message ?: "Kotlin formatter failed",
                        )
                    }
                enqueue(IdeEvent.FormatCompleted(requestGeneration, operationId, path, submittedRevision, outcome))
            }
    }

    private fun requestGit(operation: GitOperation) {
        if (project == null || state.busy.isNotEmpty() || pendingGit != null) {
            publishStatus("Finish the current operation before using Git", IdeProblemSeverity.Warning)
            return
        }
        gitLineChanges = emptyList()
        inspectedRevision = null
        lastInspectionMillis = Long.MIN_VALUE
        gitView = gitView.copy(menu = null)
        pendingGit = operation
        continueGit()
    }

    private fun continueGit() {
        val operation = pendingGit ?: return
        val selected = project ?: return
        val remaining = documents.values.firstOrNull { it.conflict || it.dirty || it.saveInFlight != null || it.formatInFlight != null }
        if (remaining != null) {
            if (remaining.conflict) {
                showConflictDialog(false, remaining)
            } else if (remaining.saveInFlight == null && remaining.formatInFlight == null) {
                saveDocument(remaining)
            }
            return
        }
        pendingGit = null
        val id = nextOperationId++
        latestGitOperation = id
        val capturedGeneration = generation
        val cancellation =
            GitCancellation()
        gitCancellation = cancellation
        if (operation == GitOperation.Pull || operation is GitOperation.SwitchBranch || operation is GitOperation.CreateBranch) {
            invalidateUsages()
            closeAnalysisFile()
        }
        state = state.copy(busy = state.busy + IdeBusyOperation.Git)
        publishWorkspace()
        workspace.git(selected.handle, operation, gitCredentials, cancellation).whenComplete { result, failure ->
            val detail = failure?.let { (it.cause?.message ?: it.message ?: "Git operation failed").take(1024) }
            enqueue(IdeEvent.GitFinished(capturedGeneration, id, operation, result, detail))
        }
    }

    private fun acceptGit(event: IdeEvent.GitFinished) {
        if (event.operationId != latestGitOperation) return
        gitCancellation = null
        state = state.copy(busy = state.busy - IdeBusyOperation.Git)
        if (event.operation == GitOperation.History && event.result != null) {
            gitView = gitView.copy(history = java.util.Collections.unmodifiableList(event.result.history.toList()))
            bottomPanel = bottomPanel.copy(scroll = 0)
        } else {
            gitView = gitView.copy(result = event.result ?: gitView.result, scroll = 0, previewScroll = 0)
        }
        event.result?.status?.let { status ->
            gitView = gitView.copy(status = status)
            val available = status.changes.mapTo(mutableSetOf()) { it.path }
            gitView =
                gitView.copy(
                    checkedPaths = java.util.Collections.unmodifiableSet(gitView.checkedPaths.intersect(available)),
                    previewPath = gitView.previewPath?.takeIf { it in available },
                )
        }
        val commit = event.operation as? GitOperation.CommitSelected
        val push = commit != null && event.failure == null && pushAfterCommit
        if (commit != null) {
            pushAfterCommit = false
            if (event.failure == null) {
                gitDraft.clearMessage()
                gitView = gitView.copy(checkedPaths = gitView.checkedPaths - commit.paths.toSet(), previewPath = null)
            }
        }
        val changesFiles =
            event.operation == GitOperation.Pull ||
                event.operation is GitOperation.SwitchBranch ||
                event.operation is GitOperation.CreateBranch
        if (changesFiles) {
            cancelBuildJobs()
            pendingBuildAction = null
            buildState = IdeBuildState.Idle
            refreshAnalysisAfterGit = true
        }
        publishWorkspace()
        if (event.failure != null) {
            publishStatus(event.failure, IdeProblemSeverity.Error)
        } else {
            event.result?.message?.let { publishStatus(it, IdeProblemSeverity.Info) }
        }
        requestPoll()
        if (event.failure != null && event.operation != GitOperation.Status) requestGit(GitOperation.Status)
        if (push) requestGit(GitOperation.Push)
    }

    private fun inspectGit() {
        val selected = project ?: return
        if (creatingProject || inspectionCancellation != null || pendingGit != null || IdeBusyOperation.Git in state.busy ||
            IdeBusyOperation.Project in state.busy
        ) {
            return
        }
        val now = clock.nowMillis()
        if (lastInspectionMillis != Long.MIN_VALUE && now - lastInspectionMillis < 1000) return
        lastInspectionMillis = now
        val cancellation = GitCancellation()
        inspectionCancellation = cancellation
        val operationId = nextOperationId++
        latestInspection = operationId
        val current = editor
        val source = current?.let { GitOperation.SourceChanges(it.path, it.document.materialize()) }
        val revision = current?.document?.revision
        val capturedGeneration = generation
        val foreground = latestGitOperation
        workspace.inspectGit(selected.handle, source, cancellation).whenComplete { result, _ ->
            enqueue(IdeEvent.GitInspected(capturedGeneration, operationId, foreground, source?.path, revision, result))
        }
    }

    private fun requestPoll() {
        if (creatingProject || IdeBusyOperation.Git in state.busy) return
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
        if (IdeBusyOperation.Git in state.busy) return
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
        val active = documents.values.firstOrNull { it.conflict || it.dirty || it.formatInFlight != null }
        if (active != null && (active.dirty || active.formatInFlight != null)) {
            if (active.conflict) {
                failPendingBuild(IdeBuildFailureKind.Conflict, "save conflict must be resolved before build")
            } else if (active.formatInFlight == null) {
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

    private fun canManageProject(selected: ProjectDescriptor): Boolean {
        if (state.busy.isNotEmpty()) {
            publishProblem("Wait for the current IDE operation before managing project folders")
            return false
        }
        if (selected.directoryName == project?.directoryName && documents.values.any { it.dirty }) {
            publishProblem("Save changed files before renaming or removing this project")
            return false
        }
        return true
    }

    private fun manageProject(
        directoryName: String,
        name: String?,
        admitted: ProjectDescriptor? = null,
    ) {
        val selected = admitted ?: catalog.singleOrNull { it.directoryName == directoryName } ?: return
        if (!canManageProject(selected)) return
        val active = project?.directoryName == directoryName
        if (active) {
            generation = Math.incrementExact(generation)
            cancelBuildJobs()
            closeAnalysisFile(forceDrop = true)
        }
        managingProjects = true
        state = state.copy(generation = generation, dialog = null, busy = state.busy + IdeBusyOperation.Project)
        val requestGeneration = generation
        val activeDirectory = if (active) name else project?.directoryName
        val operation = if (name == null) workspace.removeProject(selected) else workspace.renameProject(selected, name)
        operation.whenComplete { projects, failure ->
            if (failure == null) {
                enqueue(IdeEvent.ProjectsManaged(requestGeneration, directoryName, name, projects, activeDirectory, active))
            } else {
                enqueueFailure(requestGeneration, IdeBusyOperation.Project, failure)
            }
        }
    }

    private fun acceptManagedProjects(event: IdeEvent.ProjectsManaged) {
        managingProjects = false
        catalog = event.projects
        if (event.reopenActive) project?.let { persistPreferences(editor?.path ?: binary?.path) }
        preferencesSnapshot = preferencesSnapshot.replaceProjectDirectory(event.previousDirectory, event.renamedDirectory)
        runCatching { preferences.save(preferencesSnapshot) }
        state = state.copy(busy = state.busy - IdeBusyOperation.Project)
        if (event.reopenActive) {
            closeProjectDocuments()
            closeComputerPreview()
            closeAttachedSourcePreview()
            cancelComputerTransfer()
            project = null
            tree = null
            binary = null
            state = state.copy(page = IdePageState.Start(catalog.take(limits.projectRows).map(::summary), null))
            event.activeDirectory?.let(::openProject)
        } else if (project == null) {
            state = state.copy(page = IdePageState.Start(catalog.take(limits.projectRows).map(::summary), null))
        } else {
            publishWorkspace()
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
        pendingCatalogRemoval?.let { (expectedAction, selected) ->
            if (actionId != expectedAction || dialog.actionId != actionId) return
            pendingCatalogRemoval = null
            state = state.copy(dialog = null)
            manageProject(selected.directoryName, null, selected)
            return
        }
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
            is IdeEvent.ToolingReady -> {
                acceptTooling(event.tooling)
            }

            is IdeEvent.ToolingFailed -> {
                acceptToolingFailure(event.detail)
            }

            is IdeEvent.GitInspected -> {
                if (event.operationId != latestInspection) return
                inspectionCancellation = null
                if (event.foregroundOperation != latestGitOperation || IdeBusyOperation.Git in state.busy || pendingGit != null) return
                gitView =
                    gitView.copy(
                        status =
                            event.result?.status ?: ru.lazyhat.compukters.ide.git
                                .GitStatus(false),
                    )
                if (editor?.path == event.path && editor?.document?.revision == event.documentRevision) {
                    inspectedPath = event.path
                    inspectedRevision = event.documentRevision
                    gitLineChanges =
                        java.util.Collections.unmodifiableList(
                            event.result
                                ?.lineChanges
                                .orEmpty()
                                .toList(),
                        )
                    val status = gitView.status
                    val path = event.path
                    if (status?.available == true && path != null && gitLineChanges.isNotEmpty() &&
                        status.changes.none { it.path == path }
                    ) {
                        gitView =
                            gitView.copy(
                                status =
                                    status.copy(
                                        changes =
                                            status.changes +
                                                ru.lazyhat.compukters.ide.git
                                                    .GitChange(path, null, "modified"),
                                    ),
                            )
                    }
                }
                publishWorkspace()
            }

            is IdeEvent.GitFinished -> {
                acceptGit(event)
            }

            is IdeEvent.ProjectsManaged -> {
                acceptManagedProjects(event)
            }

            is IdeEvent.ProjectCatalogLoaded -> {
                acceptCatalog(event)
            }

            is IdeEvent.LibraryConfigurationLoaded -> {
                if (event.operationId != latestLibraryPreparation || project?.handle !== event.input.project ||
                    targetCoordinator?.attachedTarget() != event.target
                ) {
                    return
                }
                libraryPreparation = buildCoordinator?.prepareLibraries(event.input, event.target?.compileProfile)
            }

            is IdeEvent.BuildInputLoaded -> {
                acceptBuildInput(event)
            }

            is IdeEvent.BuildStateChanged -> {
                acceptBuildState(event)
            }

            is IdeEvent.ResolveCompleted -> {
                acceptResolve(event)
            }

            is IdeEvent.CompletionModuleEnabled -> {
                acceptCompletionModule(event)
            }

            is IdeEvent.CompletionModuleRollbackCompleted -> {
                acceptCompletionRollback(event)
            }

            is IdeEvent.ProjectOpened -> {
                acceptProject(event)
            }

            is IdeEvent.FileOpened -> {
                acceptFile(event)
            }

            is IdeEvent.DeclarationResolved -> {
                acceptDeclaration(event)
            }

            is IdeEvent.UsagesResolved -> {
                if (event.operationId != latestUsagesOperation) return
                when (val result = event.outcome) {
                    is IdeUsagesOutcome.Found -> {
                        usages = result.value
                        bottomPanel = bottomPanel.copy(tab = ru.lazyhat.compukters.ide.client.state.IdeBottomTab.Problems, scroll = 0)
                    }

                    is IdeUsagesOutcome.Failed -> {
                        publishStatus(result.detail, IdeProblemSeverity.Warning)
                    }
                }
                publishWorkspace()
            }

            is IdeEvent.RenameResolved -> {
                acceptRename(event)
            }

            is IdeEvent.RenameLoaded -> {
                applyRename(event)
            }

            is IdeEvent.SaveCompleted -> {
                acceptSave(event)
            }

            is IdeEvent.FormatCompleted -> {
                acceptFormat(event)
            }

            is IdeEvent.DeleteAdmitted -> {
                acceptDeleteAdmitted(event)
            }

            is IdeEvent.MutationCompleted -> {
                acceptMutation(event)
            }

            is IdeEvent.ComputerImportCompleted -> {
                acceptComputerImport(event)
            }

            is IdeEvent.ComputerImportFailed -> {
                acceptComputerImportFailure(event)
            }

            is IdeEvent.PollCompleted -> {
                acceptPoll(event)
            }

            is IdeEvent.Failed -> {
                val managementFailed = managingProjects && event.operation == IdeBusyOperation.Project
                if (managementFailed) managingProjects = false
                acceptFailure(event)
                if (managementFailed && project?.handle?.isValid() == true) editor?.let(::openAnalysis)
            }

            is IdeEvent.CatalogLoaded,
            is IdeEvent.BuildCompleted,
            -> {
                Unit
            }
        }
    }

    private fun cancelLibraryPreparation() {
        latestLibraryPreparation = nextOperationId++
        libraryPreparation?.cancel(false)
        libraryPreparation = null
    }

    private fun requestLibraryPreparation() {
        cancelLibraryPreparation()
        if (closed || buildCoordinator == null) return
        val selected = project?.handle ?: return
        val operationId = latestLibraryPreparation
        val capturedGeneration = generation
        val target = targetCoordinator?.attachedTarget()
        preparationTarget = target?.compileProfile
        workspace.projectConfiguration(selected).whenComplete { input, failure ->
            if (failure == null && input != null && acceptsTooling.get()) {
                events.offer(IdeEvent.LibraryConfigurationLoaded(capturedGeneration, operationId, input, target))
            }
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
        requestLibraryPreparation()
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
        val remembered = preferencesSnapshot.lastProjectDirectory
        val restoreProject = remembered != null && catalog.any { it.directoryName == remembered }
        val page = if (restoreProject) IdePageState.Opening else IdePageState.Start(summaries, null)
        state = IdeViewState(generation, page, null, emptySet(), state.target, state.tooling)
        if (restoreProject) openProject(requireNotNull(remembered))
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
        if (event.result == IdeResolveResult.Created || event.result == IdeResolveResult.Updated ||
            event.result == IdeResolveResult.UpToDate
        ) {
            requestLibraryPreparation()
        }
        publishWorkspace()
    }

    private fun acceptProject(event: IdeEvent.ProjectOpened) {
        if (event.operationId != latestProjectOperation) return
        if (creatingProject) {
            closeProjectDocuments()
            closeAnalysisFile()
            creatingProject = false
        }
        inspectionCancellation?.cancel()
        inspectionCancellation = null
        latestInspection = nextOperationId++
        lastInspectionMillis = Long.MIN_VALUE
        inspectedPath = null
        inspectedRevision = null
        gitLineChanges = emptyList()
        gitView = IdeGitView(authenticated = gitCredentials != null)
        bottomPanel =
            ru.lazyhat.compukters.ide.client.state.IdeBottomPanelView(
                tab = if (preferencesSnapshot.diagnosticsExpanded) ru.lazyhat.compukters.ide.client.state.IdeBottomTab.Problems else null,
            )
        gitDraft.clearMessage()
        pushAfterCommit = false
        gitCancellation = null
        refreshAnalysisAfterGit = false
        project = event.project
        catalog =
            if (catalog.any { it.directoryName == event.project.directoryName }) {
                catalog.map { if (it.directoryName == event.project.directoryName) event.project else it }
            } else {
                catalog + event.project
            }
        tree = event.tree
        requestLibraryPreparation()
        state = state.copy(busy = state.busy - IdeBusyOperation.Project - IdeBusyOperation.Clone)
        publishWorkspace()
        val restore = restoreEditorState?.file ?: pendingFile
        pendingFile = null
        if (restore != null && event.tree.flatten().any { it.path == restore }) {
            openFile(restore)
        } else {
            restoreEditorState = null
            persistPreferences(null)
        }
    }

    private fun acceptFile(event: IdeEvent.FileOpened) {
        if (event.operationId != latestOpenOperation) return
        val navigation = openingProjectNavigation
        openingProjectNavigation = null
        if (navigation?.expectedText != null &&
            !admitNavigation(navigation, (event.result as? ProjectFileOpenResult.Text)?.snapshot?.text)
        ) {
            state = state.copy(busy = state.busy - IdeBusyOperation.Project)
            publishWorkspace()
            return
        }
        if (event.result is ProjectFileOpenResult.Text && !admitDocument()) {
            state = state.copy(busy = state.busy - IdeBusyOperation.Project)
            publishStatus("Project document limit reached; reopen the project to release history", IdeProblemSeverity.Warning)
            return
        }
        editor = null
        binary = null
        closeAnalysisFile()
        when (val result = event.result) {
            is ProjectFileOpenResult.Text -> {
                val document = EditorDocument(result.snapshot.text)
                val session = EditorSession(event.path, document, result.snapshot.revision)
                documents[event.path] = session
                val remembered = restoreEditorState
                restoreEditorState = null
                remembered
                    ?.takeIf { it.file == event.path }
                    ?.let { remembered ->
                        restoreCaret(document, remembered.caretUtf16)
                        session.firstVisibleLine = remembered.firstVisibleLine
                        session.firstVisibleColumn = remembered.firstVisibleColumn
                    }
                editor = session
                navigation?.let { pending ->
                    restoreCaret(document, pending.caretUtf16)
                    session.firstVisibleLine = pending.firstVisibleLine ?: document.lineContaining(document.caretOffset)
                    session.firstVisibleColumn = pending.firstVisibleColumn
                }
                openAnalysis(session)
            }

            is ProjectFileOpenResult.Binary -> {
                binary = IdeEditorView.Binary(result.path, result.bytes)
                if (navigation != null) publishStatus("Declaration source is not text", IdeProblemSeverity.Warning)
            }
        }
        state = state.copy(busy = state.busy - IdeBusyOperation.Project)
        publishWorkspace()
        persistPreferences(event.path)
        if (navigation != null && editor != null) completeNavigation(navigation)
    }

    private fun acceptSave(event: IdeEvent.SaveCompleted) {
        if (event.operationId != latestSaveOperation) return
        val active = documents[event.path] ?: return
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
        if (active.conflict) showConflictDialog(closeRequested, active)
        continueClosing()
        continuePendingSave()
        continuePendingNavigation()
        continuePendingBuild()
    }

    private fun continueClosing() {
        if (!closeRequested) return
        val remaining = documents.values.firstOrNull { it.conflict || it.dirty || it.saveInFlight != null || it.formatInFlight != null }
        if (remaining == null) closeReady = true else saveDocument(remaining)
    }

    private fun acceptFormat(event: IdeEvent.FormatCompleted) {
        if (event.operationId != latestFormatOperation) return
        state = state.copy(busy = state.busy - IdeBusyOperation.Format)
        val active = editor
        if (active == null || active.path != event.path || active.formatInFlight != event.editorRevision) {
            publishWorkspace()
            return
        }
        active.formatInFlight = null
        if (active.document.revision != event.editorRevision) {
            if (pendingFormat) {
                pendingFormat = false
                requestFormat()
            } else {
                publishWorkspace()
                continueAfterFormat()
            }
            return
        }
        pendingFormat = false
        when (val result = event.result) {
            is AnalysisClientResult.Success -> {
                val formatted = result.result as? AnalysisResult.Format
                if (formatted == null) {
                    formatFailed("analysis worker returned an unexpected result")
                    return
                }
                when (val edit = active.document.replaceAll(formatted.source, formatted.caretOffsetUtf16)) {
                    is EditorEditResult.Applied -> {
                        active.lastEditMillis = clock.nowMillis()
                        visibleLatency.editApplied(active.document.revision)
                        updateAnalysis(active, null, edit.change)
                    }

                    EditorEditResult.NoChange -> {
                        Unit
                    }

                    is EditorEditResult.Rejected -> {
                        formatFailed("formatted source was rejected by the editor")
                        return
                    }
                }
            }

            is AnalysisClientResult.Failure -> {
                formatFailed(result.detail)
                return
            }

            AnalysisClientResult.Cancelled,
            AnalysisClientResult.Stale,
            -> {
                formatFailed("format request did not complete")
                return
            }
        }
        publishWorkspace()
        continueAfterFormat()
    }

    private fun formatFailed(detail: String) {
        publishStatus(
            "Kotlin formatting failed: ${detail.boundedUtf8(limits.statusUtf8Bytes)}",
            IdeProblemSeverity.Warning,
        )
        continueAfterFormat()
    }

    private fun continueAfterFormat() {
        continuePendingSave()
        val active = editor
        if (pendingBuildAction != null && active?.dirty == true && active.saveInFlight == null) {
            requestSave()
        } else {
            continuePendingBuild()
        }
        continuePendingNavigation()
        continueClosing()
    }

    private fun acceptPoll(event: IdeEvent.PollCompleted) {
        if (creatingProject || IdeBusyOperation.Git in state.busy) return
        val previousConfiguration =
            tree
                ?.flatten()
                ?.filter { it.path.value in setOf("compukter.toml", "compukter.lock") }
                ?.map { it.path to it.revision }
        val nextConfiguration =
            event.tree
                .flatten()
                .filter { it.path.value in setOf("compukter.toml", "compukter.lock") }
                .map { it.path to it.revision }
        val previousSources = tree?.flatten()?.filter { it.path.isKotlinSource }?.map { it.path to it.revision }
        val nextSources =
            event.tree
                .flatten()
                .filter { it.path.isKotlinSource }
                .map { it.path to it.revision }
        tree = event.tree
        val activeBefore = editor
        documents.values.toList().forEach { active ->
            val diskRevision =
                event.tree
                    .flatten()
                    .singleOrNull { it.path == active.path }
                    ?.revision
            if (diskRevision != active.diskRevision) {
                invalidateUsages()
                if (active.dirty) {
                    active.conflict = true
                    if (active === editor || closeRequested) showConflictDialog(closeRequested, active)
                } else if (diskRevision == null) {
                    val wasActive = active === editor
                    removeDocument(active)
                    if (wasActive) {
                        closeAnalysisFile()
                        publishProblem("Active file was removed outside the IDE")
                    }
                } else {
                    val wasActive = active === editor
                    removeDocument(active)
                    if (wasActive) openFile(active.path)
                }
            }
        }
        if (activeBefore == null) {
            val shownBinary = binary
            if (shownBinary != null && event.tree.flatten().none { it.path == shownBinary.path }) binary = null
        }
        if (previousConfiguration != null && previousConfiguration != nextConfiguration) requestLibraryPreparation()
        if ((
                refreshAnalysisAfterGit || (previousSources != null && previousSources != nextSources) ||
                    (previousConfiguration != null && previousConfiguration != nextConfiguration)
            ) && editor === activeBefore &&
            IdeBusyOperation.Project !in state.busy
        ) {
            invalidateUsages()
            if (refreshAnalysisAfterGit) editor?.let(::openAnalysis) else analysisCoordinator?.reload(sourceOverlays())
            refreshAnalysisAfterGit = false
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
                invalidateUsages()
                editor?.let(::openAnalysis)
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

    private fun acceptComputerImport(event: IdeEvent.ComputerImportCompleted) {
        if (event.operationId != latestComputerImportOperation) return
        state = state.copy(busy = state.busy - IdeBusyOperation.Project, dialog = null)
        when (val result = event.result) {
            is ProjectMutationResult.Changed -> {
                tree = result.tree
                computerFiles?.finishTransfer()
                publishStatus("Imported ${event.import.destination.value}", IdeProblemSeverity.Info)
            }

            is ProjectMutationResult.Conflict -> {
                computerFiles?.finishTransfer("Project entry changed: ${result.path.value}")
                publishProblem("Project entry changed: ${result.path.value}")
            }

            ProjectMutationResult.ProjectInvalidated -> {
                computerFiles?.finishTransfer("Project root changed")
                recoverToStart("Project root changed; reopen the project")
            }
        }
        publishWorkspace()
    }

    private fun acceptComputerImportFailure(event: IdeEvent.ComputerImportFailed) {
        if (event.operationId != latestComputerImportOperation) return
        state = state.copy(busy = state.busy - IdeBusyOperation.Project, dialog = null)
        computerFiles?.finishTransfer(event.detail)
        publishProblem(event.detail)
        publishWorkspace()
    }

    private fun acceptComputerTransfer(transfer: IdeComputerTransferState) {
        if (transfer == observedComputerTransfer) return
        when (transfer) {
            IdeComputerTransferState.Idle,
            is IdeComputerTransferState.Downloading,
            -> {}

            is IdeComputerTransferState.ConfirmationRequired -> {
                if (IdeBusyOperation.Project in state.busy) return
                val conflict = tree?.flatten()?.any { it.path == transfer.import.destination } == true
                if (conflict) {
                    state = state.copy(dialog = IdeDialogState.ComputerImport(transfer.import.destination))
                } else {
                    importComputerTree(transfer.import)
                }
            }

            is IdeComputerTransferState.Failed -> {
                publishProblem(transfer.detail)
            }
        }
        observedComputerTransfer = transfer
    }

    private fun confirmComputerImport() {
        val transfer = computerFiles?.transfer() as? IdeComputerTransferState.ConfirmationRequired ?: return
        if (state.dialog !is IdeDialogState.ComputerImport) return
        state = state.copy(dialog = null)
        importComputerTree(transfer.import.replacingExisting())
    }

    private fun importComputerTree(import: ProjectImport) {
        if (IdeBusyOperation.Project in state.busy) return
        val operationId = nextOperationId++
        latestComputerImportOperation = operationId
        val requestGeneration = generation
        state = state.copy(busy = state.busy + IdeBusyOperation.Project)
        workspace.importTree(requireProject(), import).whenComplete { result, failure ->
            if (failure == null) {
                enqueue(IdeEvent.ComputerImportCompleted(requestGeneration, operationId, import, result))
            } else {
                enqueue(IdeEvent.ComputerImportFailed(requestGeneration, operationId, failure.message ?: "Project import failed"))
            }
        }
    }

    private fun applyRename(
        source: ProjectPath,
        target: ProjectPath,
    ) {
        invalidateUsages()
        documents.values.filter { it.path.isWithin(source) }.forEach { active ->
            val wasKotlinSource = active.path.isKotlinSource
            documents.remove(active.path)
            active.path = active.path.rebase(source, target)
            documents[active.path] = active
            if (active === editor) {
                if (wasKotlinSource && active.path.isKotlinSource) visibleLatency.dropActive()
                openAnalysis(active)
            }
        }
        binary?.takeIf { it.path.isWithin(source) }?.let { active ->
            binary = IdeEditorView.Binary(active.path.rebase(source, target), active.bytes)
        }
    }

    private fun applyDelete(deleted: ProjectPath) {
        invalidateUsages()
        documents.values.filter { it.path.isWithin(deleted) }.forEach { active ->
            if (active.dirty) {
                active.conflict = true
                publishProblem("Active file was deleted; use Save As to keep local edits")
                showConflictDialog(closing = false, active)
            } else {
                val wasActive = active === editor
                removeDocument(active)
                if (wasActive) closeAnalysisFile()
            }
        }
        if (binary?.path?.isWithin(deleted) == true) binary = null
    }

    private fun acceptFailure(event: IdeEvent.Failed) {
        if (event.operation == IdeBusyOperation.Save) {
            pendingGit = null
            documents.values.filter { it.saveInFlight != null }.forEach {
                it.saveInFlight = null
                it.lastEditMillis = clock.nowMillis()
            }
        }
        if (event.operation == IdeBusyOperation.Project && creatingProject) {
            creatingProject = false
            state = state.copy(busy = state.busy - IdeBusyOperation.Clone)
            gitCancellation = null
            pendingFile = null
            pendingProjectCreation = null
            editor?.let(::openAnalysis)
        }
        if (event.operation == IdeBusyOperation.Project) openingProjectNavigation = null
        if (project?.handle?.isValid() == false) {
            recoverToStart("Project root changed; reopen the project")
            return
        }
        val failedPage =
            if (state.page == IdePageState.Opening &&
                (event.operation == IdeBusyOperation.Catalog || event.operation == IdeBusyOperation.Project)
            ) {
                IdePageState.Start(catalog.take(limits.projectRows).map(::summary), event.problem)
            } else {
                pageWithProblem(event.problem)
            }
        state = state.copy(busy = state.busy - event.operation, page = failedPage)
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
                removeDocument(active)
                openFile(active.path)
            }

            is IdeConflictAction.SaveAs -> {
                if (documents[action.path]?.let { it !== active } == true) {
                    publishStatus("Destination is already open in the IDE", IdeProblemSeverity.Warning)
                    return
                }
                documents.remove(active.path)
                active.path = action.path
                documents[active.path] = active
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
                pendingGit = null
                closeRequested = false
                state = state.copy(dialog = null)
            }
        }
        publishWorkspace()
    }

    private fun showConflictDialog(
        closing: Boolean,
        session: EditorSession? = editor,
    ) {
        val active = session ?: return
        if (active !== editor) {
            closeComputerPreview()
            closeAttachedSourcePreview()
            activateDocument(active)
        }
        state = state.copy(dialog = IdeDialogState.FileConflict(active.path, closing))
    }

    private fun continuePendingNavigation() {
        if (pendingGit != null) {
            continueGit()
            return
        }
        val creation = pendingProjectCreation
        if (creation != null) {
            val remaining = documents.values.firstOrNull { it.dirty || it.saveInFlight != null || it.formatInFlight != null }
            if (remaining != null) {
                saveDocument(remaining)
            } else {
                pendingProjectCreation = null
                createProject(creation)
            }
            return
        }
        val targetProject = pendingProjectDirectory
        if (targetProject != null) {
            val active = documents.values.firstOrNull { it.dirty || it.saveInFlight != null || it.formatInFlight != null }
            if (active?.conflict == true) return
            if (active != null) {
                saveDocument(active)
            } else {
                pendingProjectDirectory = null
                pendingFile = null
                openProject(targetProject)
            }
            return
        }
        val declaration = pendingProjectNavigation
        if (declaration != null) {
            val active = editor
            if (active?.conflict == true) return
            if (active?.dirty == true) {
                requestSave()
            } else {
                pendingProjectNavigation = null
                openFile(declaration.path, declaration)
            }
            return
        }
        val attached = pendingAttachedNavigation
        if (attached != null) {
            val active = editor
            if (active?.conflict == true) return
            if (active?.dirty == true) {
                requestSave()
            } else {
                pendingAttachedNavigation = null
                openAttached(attached)
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
        if (pendingFormat) {
            pendingFormat = false
            pendingSave = false
            requestFormat()
            return
        }
        if (!pendingSave) return
        pendingSave = false
        requestSave()
    }

    private fun continuePendingBuild() {
        val pending = pendingBuildAction ?: return
        val active = documents.values.firstOrNull { it.conflict || it.dirty || it.saveInFlight != null || it.formatInFlight != null }
        if (active?.conflict == true) {
            failPendingBuild(IdeBuildFailureKind.Conflict, "save conflict must be resolved before build")
            return
        }
        if (active != null) {
            saveDocument(active)
            return
        }
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
        if (currentDocument() == null) {
            find.dismiss()
            selectionOccurrences.clear()
        }
        val selected = project ?: return
        val selectedTree = tree ?: return
        val editorView = attachedSourcePreview?.toView() ?: computerPreview?.toView() ?: editor?.toView() ?: binary ?: IdeEditorView.Empty
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
                            computerFiles?.state() ?: IdeComputerTreeState.NoTarget,
                            computerFiles?.transfer() ?: IdeComputerTransferState.Idle,
                            catalog.take(limits.projectRows).map(::summary),
                            usages,
                            currentDiagnostics(),
                            gitView.copy(draft = gitDraft.view()),
                            bottomPanel,
                        ),
                    ),
            )
    }

    private fun occurrenceRanges(
        document: EditorDocument,
        lexical: KotlinLexicalSnapshot,
    ): List<EditorRange> =
        when {
            find.visible -> {
                emptyList()
            }

            document.selectionRange != null -> {
                selectionOccurrences.matches(document, lexical)
            }

            else -> {
                val active = observedAnalysisState as? IdeAnalysisState.Active
                val source = editor
                if (active != null && source != null && attachedSourcePreview == null && computerPreview == null &&
                    source.document === document &&
                    active.documentRevision == document.revision && active.path.value == source.path.value
                ) {
                    active.occurrenceRanges
                } else {
                    emptyList()
                }
            }
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
            find = find.view(document),
            occurrenceRanges = occurrenceRanges(document, highlighter.snapshot()),
            gitLineChanges = if (inspectedPath == path && inspectedRevision == document.revision) gitLineChanges else emptyList(),
        )
    }

    private fun ComputerPreviewSession.toView(): IdeEditorView.Text {
        val first = firstVisibleLine.coerceIn(0, document.lineCount - 1)
        val lastExclusive = minOf(document.lineCount, first + limits.visibleEditorLines)
        val visible = (first until lastExclusive).map(document::materializeLine)
        val visibleStarts = (first until lastExclusive).map(document::lineStartOffset)
        val selection = document.selectionRange
        return IdeEditorView.Text(
            path = null,
            visibleLines = visible,
            visibleLineStartsUtf16 = visibleStarts,
            firstVisibleLine = first,
            firstVisibleColumn = firstVisibleColumn,
            totalLines = document.lineCount,
            caretUtf16 = document.caretOffset,
            selectionStartUtf16 = selection?.startUtf16,
            selectionEndUtf16 = selection?.endUtf16,
            contentRevision = document.revision,
            persistedContentRevision = document.revision,
            dirty = false,
            conflict = false,
            lexical = highlighter.snapshot(),
            analysis = IdeAnalysisState.Idle,
            source = IdeEditorSource.Computer(path, targetId, generation),
            readOnly = true,
            find = find.view(document),
            occurrenceRanges = occurrenceRanges(document, highlighter.snapshot()),
        )
    }

    private fun AttachedSourcePreviewSession.toView(): IdeEditorView.Text {
        val first = firstVisibleLine.coerceIn(0, document.lineCount - 1)
        val lastExclusive = minOf(document.lineCount, first + limits.visibleEditorLines)
        val visible = (first until lastExclusive).map(document::materializeLine)
        val visibleStarts = (first until lastExclusive).map(document::lineStartOffset)
        val selection = document.selectionRange
        return IdeEditorView.Text(
            path = null,
            visibleLines = visible,
            visibleLineStartsUtf16 = visibleStarts,
            firstVisibleLine = first,
            firstVisibleColumn = firstVisibleColumn,
            totalLines = document.lineCount,
            caretUtf16 = document.caretOffset,
            selectionStartUtf16 = selection?.startUtf16,
            selectionEndUtf16 = selection?.endUtf16,
            contentRevision = document.revision,
            persistedContentRevision = document.revision,
            dirty = false,
            conflict = false,
            lexical = highlighter.snapshot(),
            analysis = IdeAnalysisState.Idle,
            source = IdeEditorSource.AttachedApi(module, path),
            readOnly = true,
            find = find.view(document),
            occurrenceRanges = occurrenceRanges(document, highlighter.snapshot()),
        )
    }

    private fun sourcePointer(
        offsetUtf16: Int?,
        controlDown: Boolean,
    ) {
        val active = editor
        val source =
            active
                ?.takeIf {
                    attachedSourcePreview == null && computerPreview == null && binary == null && it.path.isKotlinSource
                }?.document
                ?.materialize()
        val token = if (source != null && offsetUtf16 != null) KotlinSourceTokenRange.find(source, offsetUtf16) else null
        analysisCoordinator?.pointerMoved(token, offsetUtf16, controlDown)
        refreshAnalysisState()
        publishWorkspace()
    }

    private fun goToDeclaration(offsetUtf16: Int?) {
        if (attachedSourcePreview != null || computerPreview != null || binary != null) return
        val active = editor?.takeIf { it.path.isKotlinSource } ?: return
        val offset = offsetUtf16 ?: active.document.caretOffset
        val token = KotlinSourceTokenRange.find(active.document.materialize(), offset) ?: return
        val coordinator = analysisCoordinator ?: return
        val operationId = nextOperationId++
        latestDeclarationOperation = operationId
        val requestGeneration = generation
        coordinator.goToDeclaration(token, offset).whenComplete { outcome, failure ->
            val admitted =
                if (failure == null && outcome != null) {
                    outcome
                } else {
                    IdeDeclarationOutcome.Failed(failure?.message?.takeIf(String::isNotEmpty) ?: "Declaration request failed")
                }
            enqueue(IdeEvent.DeclarationResolved(requestGeneration, operationId, admitted))
        }
    }

    private fun findUsages(offsetUtf16: Int? = null) {
        if (attachedSourcePreview != null || computerPreview != null || binary != null) return
        val active = editor?.takeIf { it.path.isKotlinSource } ?: return
        val coordinator = analysisCoordinator ?: return
        val text = active.document.materialize()
        val caret = offsetUtf16 ?: active.document.caretOffset
        if (caret !in 0..text.length) return
        val token =
            KotlinSourceTokenRange.find(text, caret)
                ?: caret.takeIf { it > 0 }?.let { KotlinSourceTokenRange.find(text, it - Character.charCount(text.codePointBefore(it))) }
                ?: return
        latestUsagesOperation = nextOperationId++
        val operation = latestUsagesOperation
        val requestGeneration = generation
        coordinator.dismissCompletion()
        coordinator.dismissParameterInfo()
        coordinator.findUsages(token.startUtf16, active.document.revision).whenComplete { result, failure ->
            enqueue(
                IdeEvent.UsagesResolved(
                    requestGeneration,
                    operation,
                    result ?: IdeUsagesOutcome.Failed(failure?.message ?: "Find Usages failed"),
                ),
            )
        }
    }

    private fun invalidateUsages() {
        usages = null
        latestUsagesOperation = nextOperationId++
    }

    private fun renameSymbol(newName: String) {
        if (attachedSourcePreview != null || computerPreview != null || binary != null) return
        val active = editor?.takeIf { it.path.isKotlinSource } ?: return
        val coordinator = analysisCoordinator ?: return
        if (pendingRename != null || state.busy.isNotEmpty() ||
            documents.values.any { it.conflict || it.saveInFlight != null || it.formatInFlight != null }
        ) {
            publishStatus("Finish the current operation before Rename", IdeProblemSeverity.Warning)
            return
        }
        val text = active.document.materialize()
        val caret = active.document.caretOffset
        val token =
            KotlinSourceTokenRange.find(text, caret)
                ?: caret.takeIf { it > 0 }?.let { KotlinSourceTokenRange.find(text, it - Character.charCount(text.codePointBefore(it))) }
                ?: return
        if (newName.isEmpty() || newName.encodeToByteArray().size > ru.lazyhat.compukters.ide.analysis.MAX_RENAME_NAME_BYTES) {
            publishStatus("Rename name exceeds limit or is empty", IdeProblemSeverity.Warning)
            return
        }
        val operation = nextOperationId++
        val requestGeneration = generation
        val selected = requireProject()
        val sourceTree = requireNotNull(tree)
        pendingRename =
            PendingRename(
                operation,
                selected,
                documents.mapValues { (_, session) -> session.document.revision },
                sourceTree.flatten().filter { it.path.isKotlinSource }.associate { it.path to it.revision },
            )
        coordinator.dismissCompletion()
        coordinator.dismissParameterInfo()
        publishStatus("Checking Rename…", IdeProblemSeverity.Info)
        coordinator.rename(token.startUtf16, active.document.revision, newName).whenComplete { result, failure ->
            enqueue(
                IdeEvent.RenameResolved(
                    requestGeneration,
                    operation,
                    result ?: ru.lazyhat.compukters.ide.client.analysis.IdeRenameOutcome
                        .Failed(failure?.message ?: "Rename failed"),
                ),
            )
        }
    }

    private fun acceptRename(event: IdeEvent.RenameResolved) {
        val pending = pendingRename?.takeIf { it.operation == event.operationId } ?: return
        val outcome = event.outcome
        if (outcome is ru.lazyhat.compukters.ide.client.analysis.IdeRenameOutcome.Failed) {
            pendingRename = null
            publishStatus(outcome.detail, IdeProblemSeverity.Warning)
            return
        }
        val plan = (outcome as ru.lazyhat.compukters.ide.client.analysis.IdeRenameOutcome.Prepared).plan
        if (!renameIsFresh(pending, plan)) {
            pendingRename = null
            publishStatus("Sources changed; run Rename again", IdeProblemSeverity.Warning)
            return
        }
        val opened = plan.edits.keys.associate { path -> ProjectPath.file(path.value).let { it to workspace.open(pending.project, it) } }
        val input = workspace.buildInput(pending.project)
        CompletableFuture.allOf(input, *opened.values.toTypedArray()).whenComplete { _, failure ->
            enqueue(
                IdeEvent.RenameLoaded(
                    event.generation,
                    event.operationId,
                    plan,
                    if (failure == null) input.join() else null,
                    if (failure == null) opened.mapValues { it.value.join() } else emptyMap(),
                    failure?.message,
                ),
            )
        }
    }

    private fun renameIsFresh(
        pending: PendingRename,
        plan: ru.lazyhat.compukters.ide.client.analysis.IdeRenamePlan,
    ): Boolean =
        project?.handle == pending.project &&
            documents.mapValues { (_, session) -> session.document.revision } == pending.revisions &&
            tree?.flatten()?.filter { it.path.isKotlinSource }?.associate { it.path to it.revision } == pending.diskRevisions &&
            documents.values.none { it.conflict || it.saveInFlight != null || it.formatInFlight != null } &&
            state.busy.isEmpty() &&
            analysisCoordinator?.isCurrent(plan.identity, plan.activePath, plan.documentRevision) == true

    private fun applyRename(event: IdeEvent.RenameLoaded) {
        val pending = pendingRename?.takeIf { it.operation == event.operationId } ?: return
        pendingRename = null
        val plan = event.plan

        fun reject(detail: String) = publishStatus(detail, IdeProblemSeverity.Warning)
        if (event.failure != null || !renameIsFresh(pending, plan)) {
            reject(event.failure ?: "Sources changed; run Rename again")
            return
        }
        val input = event.input ?: return
        val freshSources =
            input.sources.sources
                .associate { source ->
                    source.path to source.content.toByteArray().decodeToString()
                }.toMutableMap()
        sourceOverlays().forEach { (path, text) -> if (path in freshSources) freshSources[path] = text }
        if (freshSources != plan.sources || !plan.matchesConfiguration(input)) {
            reject("Project sources or configuration changed; run Rename again")
            return
        }
        val missing = event.files.keys.filter { it !in documents }
        if (documents.size + missing.size > limits.projectDocuments) {
            reject("Project document limit reached; Rename was not applied")
            return
        }
        val added = linkedMapOf<ProjectPath, EditorSession>()
        val replacements = arrayListOf<ProjectEditHistory.Replacement>()
        try {
            event.files.forEach { (path, result) ->
                val opened = result as? ProjectFileOpenResult.Text ?: error("Rename source is not text")
                check(opened.snapshot.revision == pending.diskRevisions[path]) { "A Rename source changed on disk" }
                val virtualPath = VirtualSourcePath.kotlin(path.value)
                val session =
                    documents[path]
                        ?: EditorSession(path, EditorDocument(opened.snapshot.text), opened.snapshot.revision).also { added[path] = it }
                check(session.document.materialize() == plan.sources.getValue(virtualPath)) { "A Rename document changed" }
                replacements +=
                    ProjectEditHistory.Replacement(
                        session.document,
                        session.document.revision,
                        plan.replacement(virtualPath),
                        plan.caret(virtualPath, session.document.caretOffset),
                    )
            }
            when (val result = projectHistory.apply(replacements)) {
                is ProjectEditHistory.Result.Rejected -> {
                    error("Rename was not applied: ${result.detail}")
                }

                ProjectEditHistory.Result.NoChange -> {
                    added.values.forEach { it.close() }
                    publishStatus("Name is unchanged", IdeProblemSeverity.Info)
                    return
                }

                is ProjectEditHistory.Result.Applied -> {
                    documents.putAll(added)
                    documents.values.filter { it.document in result.changes }.forEach { it.lastEditMillis = clock.nowMillis() }
                    invalidateUsages()
                    editor?.let { active ->
                        result.changes[active.document]?.let { updateAnalysis(active, null, it) }
                            ?: openAnalysis(active)
                    }
                    publishStatus("Renamed in ${result.changes.size} files", IdeProblemSeverity.Info)
                    publishWorkspace()
                }
            }
        } catch (failure: RuntimeException) {
            added.values.filter { it.path !in documents }.forEach { it.close() }
            reject(failure.message ?: "Rename could not be applied")
        }
    }

    private fun currentDiagnostics(): IdeDiagnostics {
        val rows = mutableListOf<IdeDiagnosticRow>()
        val active = editor
        if (attachedSourcePreview == null && computerPreview == null && active != null) {
            val values = observedAnalysisState.presentationOrNull()?.diagnostics.orEmpty()
            if (values.isNotEmpty()) {
                val text = active.document.materialize()
                rows +=
                    values.map { value ->
                        IdeDiagnosticRow(value, text.takeIf { value.path?.value == active.path.value })
                    }
            }
        }
        (buildState as? IdeBuildState.Diagnostics)?.let { build ->
            val paths =
                tree
                    ?.flatten()
                    ?.map { it.path.value }
                    ?.toSet()
                    .orEmpty()
            val currentTexts =
                build.values.mapNotNull { it.path }.distinct().associate { path ->
                    path.value to documents[ProjectPath.file(path.value)]?.document?.materialize()
                }
            rows +=
                build.values.map { value ->
                    val source = build.sourceTexts[value.path]
                    val current = currentTexts[value.path?.value]
                    IdeDiagnosticRow(value, source?.takeIf { value.path?.value in paths && (current == null || it == current) })
                }
        }
        return IdeDiagnostics(rows)
    }

    private fun openDiagnostic(row: IdeDiagnosticRow) {
        if (!row.navigable || row !in currentDiagnostics().rows) return
        val path = ProjectPath.file(row.diagnostic.path!!.value)
        if (tree?.flatten()?.none { it.path == path } != false) return
        val from = currentNavigationPosition()
        analysisCoordinator?.dismissCompletion()
        analysisCoordinator?.dismissParameterInfo()
        find.focus(false)
        usages = usages?.unfocus()
        navigateToProject(
            PendingProjectNavigation(
                path,
                row.diagnostic.range!!.startUtf16,
                row.line,
                0,
                from?.let { NavigationTransition.Fresh(it) } ?: NavigationTransition.Untracked,
                row.sourceText,
            ),
        )
    }

    private fun admitNavigation(
        navigation: PendingProjectNavigation?,
        text: String?,
    ): Boolean {
        if (navigation?.expectedText == null || navigation.expectedText == text) return true
        (buildState as? IdeBuildState.Diagnostics)?.let { build ->
            val path = VirtualSourcePath.kotlin(navigation.path.value)
            if (build.sourceTexts[path] == navigation.expectedText) {
                buildState = IdeBuildState.Diagnostics(build.identity, build.sourceSnapshotId, build.values, build.sourceTexts - path)
            }
        }
        publishStatus("Diagnostic source changed; refresh diagnostics before navigating", IdeProblemSeverity.Warning)
        publishWorkspace()
        return false
    }

    private fun openUsage(index: Int?) {
        val results = usages ?: return
        val row = results.rows.getOrNull(index ?: results.selectedIndex) ?: return
        usages = results.unfocus()
        val from = currentNavigationPosition() ?: return
        navigateToProject(PendingProjectNavigation(row.path, row.range.startUtf16, row.line, 0, NavigationTransition.Fresh(from)))
    }

    private fun acceptDeclaration(event: IdeEvent.DeclarationResolved) {
        if (event.operationId != latestDeclarationOperation) return
        when (val outcome = event.outcome) {
            IdeDeclarationOutcome.NotFound -> {
                publishStatus("Declaration not found", IdeProblemSeverity.Info)
            }

            is IdeDeclarationOutcome.SourceUnavailable -> {
                publishStatus("Source for ${outcome.module.name} is unavailable", IdeProblemSeverity.Warning)
            }

            is IdeDeclarationOutcome.Failed -> {
                publishStatus(outcome.detail, IdeProblemSeverity.Warning)
            }

            is IdeDeclarationOutcome.Targets -> {
                if (outcome.values.size == 1) navigateToDeclaration(outcome.values.single())
            }
        }
        refreshAnalysisState()
        publishWorkspace()
    }

    private fun navigateToDeclaration(target: IdeDeclarationTarget) {
        val from = currentNavigationPosition() ?: return
        val transition = NavigationTransition.Fresh(from)
        when (target) {
            is IdeDeclarationTarget.Project -> {
                navigateToProject(
                    PendingProjectNavigation(
                        target.path,
                        target.range.startUtf16,
                        firstVisibleLine = null,
                        firstVisibleColumn = 0,
                        transition,
                    ),
                )
            }

            is IdeDeclarationTarget.AttachedSource -> {
                navigateToAttached(
                    target.module,
                    target.path,
                    target.range.startUtf16,
                    firstVisibleLine = null,
                    firstVisibleColumn = 0,
                    transition,
                )
            }
        }
    }

    private fun navigateHistory(direction: NavigationDirection) {
        val current = currentNavigationPosition() ?: return
        val target =
            when (direction) {
                NavigationDirection.Back -> navigationHistory.peekBack()
                NavigationDirection.Forward -> navigationHistory.peekForward()
            } ?: return
        val transition = NavigationTransition.History(current, target, direction)
        when (val source = target.source) {
            is IdeNavigationSource.Project -> {
                navigateToProject(
                    PendingProjectNavigation(
                        source.path,
                        target.caretUtf16,
                        target.firstVisibleLine,
                        target.firstVisibleColumn,
                        transition,
                    ),
                )
            }

            is IdeNavigationSource.Attached -> {
                navigateToAttached(
                    source.module,
                    source.path,
                    target.caretUtf16,
                    target.firstVisibleLine,
                    target.firstVisibleColumn,
                    transition,
                )
            }
        }
    }

    private fun navigateToProject(navigation: PendingProjectNavigation) {
        pendingFile = null
        pendingAttachedNavigation = null
        val active = editor
        if (active != null && active.path == navigation.path) {
            if (navigation.expectedText != null && !admitNavigation(navigation, active.document.materialize())) return
            closeComputerPreview()
            closeAttachedSourcePreview()
            restoreCaret(active.document, navigation.caretUtf16)
            active.firstVisibleLine = navigation.firstVisibleLine ?: active.document.lineContaining(active.document.caretOffset)
            active.firstVisibleColumn = navigation.firstVisibleColumn
            analysisCoordinator?.dismissSemanticInteraction()
            analysisCoordinator?.caretMoved(active.document.caretOffset)
            refreshAnalysisState()
            completeNavigation(navigation)
            publishWorkspace()
            persistPreferences(active.path)
            return
        }
        closeComputerPreview()
        closeAttachedSourcePreview()
        if (active?.dirty == true) {
            pendingProjectNavigation = navigation
            requestSave()
        } else {
            openFile(navigation.path, navigation)
        }
    }

    private fun navigateToAttached(
        module: AnalysisModuleIdentity,
        path: VirtualSourcePath,
        caretUtf16: Int,
        firstVisibleLine: Int?,
        firstVisibleColumn: Int,
        transition: NavigationTransition,
    ) {
        pendingFile = null
        pendingProjectNavigation = null
        val navigation =
            PendingAttachedNavigation(module, path, caretUtf16, firstVisibleLine, firstVisibleColumn, transition)
        if (editor?.dirty == true) {
            pendingAttachedNavigation = navigation
            requestSave()
            return
        }
        openAttached(navigation)
    }

    private fun openAttached(navigation: PendingAttachedNavigation) {
        val module = navigation.module
        val path = navigation.path
        val text = analysisCoordinator?.attachedSource(module, path)
        if (text == null) {
            publishStatus("Source for ${module.name} is unavailable", IdeProblemSeverity.Warning)
            return
        }
        closeComputerPreview()
        closeAttachedSourcePreview()
        val preview = AttachedSourcePreviewSession(module, path, text)
        restoreCaret(preview.document, navigation.caretUtf16)
        preview.firstVisibleLine = navigation.firstVisibleLine ?: preview.document.lineContaining(preview.document.caretOffset)
        preview.firstVisibleColumn = navigation.firstVisibleColumn
        attachedSourcePreview = preview
        analysisCoordinator?.dismissSemanticInteraction()
        completeNavigation(navigation.transition)
        publishWorkspace()
    }

    private fun completeNavigation(navigation: PendingProjectNavigation) {
        completeNavigation(navigation.transition)
    }

    private fun completeNavigation(transition: NavigationTransition) {
        val current = currentNavigationPosition() ?: return
        when (transition) {
            NavigationTransition.Untracked -> {
                return
            }

            is NavigationTransition.Fresh -> {
                navigationHistory.record(transition.from, current)
            }

            is NavigationTransition.History -> {
                when (transition.direction) {
                    NavigationDirection.Back -> navigationHistory.commitBack(transition.from, transition.target)
                    NavigationDirection.Forward -> navigationHistory.commitForward(transition.from, transition.target)
                }
            }
        }
    }

    private fun currentNavigationPosition(): IdeNavigationPosition? {
        attachedSourcePreview?.let { preview ->
            return IdeNavigationPosition(
                IdeNavigationSource.Attached(preview.module, preview.path),
                preview.document.caretOffset,
                preview.firstVisibleLine,
                preview.firstVisibleColumn,
            )
        }
        if (computerPreview != null || binary != null) return null
        val active = editor ?: return null
        return IdeNavigationPosition(
            IdeNavigationSource.Project(active.path),
            active.document.caretOffset,
            active.firstVisibleLine,
            active.firstVisibleColumn,
        )
    }

    private fun openAnalysis(active: EditorSession) {
        val selected = project ?: return
        if (!active.path.isKotlinSource) {
            closeAnalysisFile()
            return
        }
        analysisCoordinator?.open(
            selected.handle,
            VirtualSourcePath.kotlin(active.path.value),
            active.document.materialize(),
            active.document.revision,
            sourceOverlays(),
        )
        analysisCoordinator?.caretMoved(active.document.caretOffset)
        refreshAnalysisState()
    }

    private fun closeAnalysisFile(forceDrop: Boolean = false) {
        if (forceDrop || observedAnalysisState !== IdeAnalysisState.Idle) visibleLatency.dropActive()
        analysisCoordinator?.closeFile()
        observedAnalysisState = IdeAnalysisState.Idle
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
            sourceOverlays(),
        )
        refreshAnalysisState()
    }

    private fun sourceOverlays(): Map<VirtualSourcePath, String> =
        documents.values.filter { it.dirty && it.path.isKotlinSource }.associate {
            VirtualSourcePath.kotlin(it.path.value) to it.document.materialize()
        }

    private fun refreshAnalysisState() {
        val current = analysisCoordinator?.state() ?: IdeAnalysisState.Idle
        if (current === observedAnalysisState) return
        observedAnalysisState = current
        if (current is IdeAnalysisState.Active) visibleLatency.controllerObserved(current.documentRevision)
        if (current is IdeAnalysisState.Unavailable) {
            val message = current.detail.takeIf(String::isNotBlank)?.let { "${current.status}: $it" } ?: current.status
            publishStatus(message, IdeProblemSeverity.Warning)
            analysisFailureReporter.report(IdeAnalysisFailure(current.path, current.documentRevision, current.detail))
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
        val compileProfile = targetCoordinator?.attachedTarget()?.compileProfile
        analysisCoordinator?.updateTargetProfile(compileProfile)
        if (preparationTarget != compileProfile) {
            preparationTarget = compileProfile
            requestLibraryPreparation()
        }
    }

    private fun refreshComputerFiles() {
        val files = computerFiles ?: return
        val attached = targetCoordinator?.attachedTarget()
        if (attached != computerTarget) {
            computerTarget = attached
            if (attached != null) {
                files.attach(attached)
            } else {
                val targetState = targetCoordinator?.state()
                if (targetState is IdeTargetState.Detached) files.targetLost(targetState.failure.detail) else files.detach()
            }
        }
        val before = files.state()
        val previewBefore = files.preview()
        val transferBefore = files.transfer()
        files.tick()
        acceptComputerPreview(files.preview())
        acceptComputerTransfer(files.transfer())
        if (
            files.state() != before || files.preview() != previewBefore || files.transfer() != transferBefore ||
            workspaceComputerTree() != files.state()
        ) {
            publishWorkspace()
        }
    }

    private fun workspaceComputerTree(): IdeComputerTreeState? = (state.page as? IdePageState.Workspace)?.value?.computerTree

    private fun acceptComputerPreview(preview: IdeComputerPreviewState) {
        if (preview == observedComputerPreview) return
        observedComputerPreview = preview
        when (preview) {
            IdeComputerPreviewState.Closed -> {
                closeComputerPreview()
            }

            is IdeComputerPreviewState.Loading -> {}

            is IdeComputerPreviewState.Available -> {
                closeComputerPreview()
                computerPreview = ComputerPreviewSession(preview)
                analysisCoordinator?.dismissCompletion()
                refreshAnalysisState()
            }

            is IdeComputerPreviewState.TooLarge -> {
                closeComputerPreview()
                publishStatus(
                    "Computer file is too large to preview (${preview.logicalBytes} bytes)",
                    IdeProblemSeverity.Warning,
                )
            }

            is IdeComputerPreviewState.Failed -> {
                closeComputerPreview()
                publishStatus(preview.detail, IdeProblemSeverity.Warning)
            }
        }
    }

    private fun editComputerPreview(
        preview: ComputerPreviewSession,
        input: IdeEditorInput,
    ) {
        when (input) {
            is IdeEditorInput.SetCaret -> {
                preview.document.setCaret(input.offsetUtf16, input.extendSelection)
            }

            is IdeEditorInput.Move -> {
                when (input.direction) {
                    IdeMoveDirection.Left -> preview.document.moveLeft(input.extendSelection)
                    IdeMoveDirection.Right -> preview.document.moveRight(input.extendSelection)
                    IdeMoveDirection.Up -> preview.document.moveUp(input.extendSelection)
                    IdeMoveDirection.Down -> preview.document.moveDown(input.extendSelection)
                    IdeMoveDirection.Home -> preview.document.moveHome(input.extendSelection)
                    IdeMoveDirection.End -> preview.document.moveEnd(input.extendSelection)
                }
            }

            is IdeEditorInput.MoveWord -> {
                moveWordReadOnlyCaret(preview.document, input)
            }

            is IdeEditorInput.Page -> {
                page(preview.document, input)
                preview.firstVisibleLine = pageFirstVisibleLine(preview.firstVisibleLine, preview.document, input)
            }

            is IdeEditorInput.SelectToken -> {
                preview.document.selectToken(input.offsetUtf16)
            }

            IdeEditorInput.SelectAll -> {
                preview.document.selectAll()
            }

            else -> {
                publishStatus("Computer files are read-only", IdeProblemSeverity.Info)
                return
            }
        }
        publishWorkspace()
    }

    private fun editAttachedSourcePreview(
        preview: AttachedSourcePreviewSession,
        input: IdeEditorInput,
    ) {
        when (input) {
            is IdeEditorInput.SetCaret -> {
                preview.document.setCaret(input.offsetUtf16, input.extendSelection)
            }

            is IdeEditorInput.Move -> {
                moveReadOnlyCaret(preview.document, input)
            }

            is IdeEditorInput.MoveWord -> {
                moveWordReadOnlyCaret(preview.document, input)
            }

            is IdeEditorInput.Page -> {
                page(preview.document, input)
                preview.firstVisibleLine = pageFirstVisibleLine(preview.firstVisibleLine, preview.document, input)
            }

            is IdeEditorInput.SelectToken -> {
                preview.document.selectToken(input.offsetUtf16)
            }

            IdeEditorInput.SelectAll -> {
                preview.document.selectAll()
            }

            else -> {
                publishStatus("Attached API sources are read-only", IdeProblemSeverity.Info)
                return
            }
        }
        publishWorkspace()
    }

    private fun moveReadOnlyCaret(
        document: EditorDocument,
        input: IdeEditorInput.Move,
    ) {
        when (input.direction) {
            IdeMoveDirection.Left -> document.moveLeft(input.extendSelection)
            IdeMoveDirection.Right -> document.moveRight(input.extendSelection)
            IdeMoveDirection.Up -> document.moveUp(input.extendSelection)
            IdeMoveDirection.Down -> document.moveDown(input.extendSelection)
            IdeMoveDirection.Home -> document.moveHome(input.extendSelection)
            IdeMoveDirection.End -> document.moveEnd(input.extendSelection)
        }
    }

    private fun moveWordReadOnlyCaret(
        document: EditorDocument,
        input: IdeEditorInput.MoveWord,
    ) {
        when (input.direction) {
            IdeHorizontalDirection.Left -> document.moveWordLeft(input.extendSelection)
            IdeHorizontalDirection.Right -> document.moveWordRight(input.extendSelection)
        }
    }

    private fun page(
        document: EditorDocument,
        input: IdeEditorInput.Page,
    ) {
        repeat(input.rows) {
            val moved =
                when (input.direction) {
                    IdeVerticalDirection.Up -> document.moveUp(input.extendSelection)
                    IdeVerticalDirection.Down -> document.moveDown(input.extendSelection)
                }
            if (!moved) return
        }
    }

    private fun pageFirstVisibleLine(
        current: Int,
        document: EditorDocument,
        input: IdeEditorInput.Page,
    ): Int {
        val delta = if (input.direction == IdeVerticalDirection.Up) -input.rows else input.rows
        return (current + delta).coerceIn(0, (document.lineCount - input.rows).coerceAtLeast(0))
    }

    private fun scrollAttachedSourcePreview(
        preview: AttachedSourcePreviewSession,
        lines: Int,
        columns: Int,
    ) {
        preview.firstVisibleLine =
            (preview.firstVisibleLine.toLong() + lines)
                .coerceIn(0, (preview.document.lineCount - 1).toLong())
                .toInt()
        preview.firstVisibleColumn =
            (preview.firstVisibleColumn.toLong() + columns)
                .coerceIn(0, preview.document.maximumVisualWidth().toLong())
                .toInt()
        publishWorkspace()
    }

    private fun closeComputerPreview() {
        computerPreview?.close()
        computerPreview = null
    }

    private fun closeAttachedSourcePreview() {
        attachedSourcePreview?.close()
        attachedSourcePreview = null
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

    private fun persistGitAuthor() {
        val draft = gitDraft.view()
        val remembered = preferencesSnapshot.rememberGitAuthor(draft.authorName.text, draft.authorEmail.text)
        if (remembered === preferencesSnapshot) return
        preferencesSnapshot = remembered
        runCatching { preferences.save(preferencesSnapshot) }
    }

    private fun persistPreferences(file: ProjectPath?) {
        val selected = project ?: return
        val active = editor
        preferencesSnapshot =
            preferencesSnapshot.remember(
                selected.directoryName,
                file?.value,
                active?.document?.caretOffset ?: 0,
                active?.firstVisibleLine ?: 0,
                active?.firstVisibleColumn ?: 0,
            )
        runCatching { preferences.save(preferencesSnapshot) }
    }

    private fun recoverToStart(message: String) {
        managingProjects = false
        pendingCatalogRemoval = null
        cancelComputerTransfer()
        cancelBuildJobs()
        pendingBuildAction = null
        pendingSave = false
        pendingFormat = false
        buildState = IdeBuildState.Idle
        closeProjectDocuments()
        closeComputerPreview()
        closeAttachedSourcePreview()
        closeAnalysisFile()
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

    private fun cancelComputerTransfer() {
        computerFiles?.cancelTransfer()
        observedComputerTransfer = IdeComputerTransferState.Idle
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
            IdePageState.Opening -> page
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
        IdeProjectSummary(
            descriptor.directoryName,
            descriptor.handle.canonicalPath.fileName
                .toString(),
            descriptor.handle.canonicalPath.toString(),
            descriptor.external,
        )

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "IDE controller may only be used from its construction thread" }
    }

    private fun requireProject() = requireNotNull(project) { "no project is open" }.handle

    private data class PendingRename(
        val operation: Long,
        val project: ru.lazyhat.compukters.ide.project.ProjectHandle,
        val revisions: Map<ProjectPath, Long>,
        val diskRevisions: Map<ProjectPath, FileRevision?>,
    )

    private class EditorSession(
        var path: ProjectPath,
        val document: EditorDocument,
        var diskRevision: FileRevision,
    ) {
        val highlighter = IncrementalKotlinHighlighter(document)
        val smartTyping = KotlinSmartTyping(document, highlighter)
        var persistedRevision = document.revision
        var saveInFlight: Long? = null
        var formatInFlight: Long? = null
        var conflict = false
        var lastEditMillis = 0L
        var firstVisibleLine = 0
        var firstVisibleColumn = 0
        val dirty: Boolean get() = document.revision != persistedRevision

        fun close() {
            smartTyping.close()
            highlighter.close()
            document.close()
        }
    }

    private class ComputerPreviewSession(
        preview: IdeComputerPreviewState.Available,
    ) {
        val path = preview.path
        val targetId = preview.targetId
        val generation = preview.generation
        val document = EditorDocument(preview.text)
        val highlighter = IncrementalKotlinHighlighter(document)
        var firstVisibleLine = 0
        var firstVisibleColumn = 0

        fun close() {
            highlighter.close()
            document.close()
        }
    }

    private class AttachedSourcePreviewSession(
        val module: AnalysisModuleIdentity,
        val path: VirtualSourcePath,
        text: String,
    ) {
        val document = EditorDocument(text)
        val highlighter = IncrementalKotlinHighlighter(document)
        var firstVisibleLine = 0
        var firstVisibleColumn = 0

        fun close() {
            highlighter.close()
            document.close()
        }
    }

    private data class PendingProjectNavigation(
        val path: ProjectPath,
        val caretUtf16: Int,
        val firstVisibleLine: Int?,
        val firstVisibleColumn: Int,
        val transition: NavigationTransition,
        val expectedText: String? = null,
    )

    private data class PendingAttachedNavigation(
        val module: AnalysisModuleIdentity,
        val path: VirtualSourcePath,
        val caretUtf16: Int,
        val firstVisibleLine: Int?,
        val firstVisibleColumn: Int,
        val transition: NavigationTransition,
    )

    private sealed interface NavigationTransition {
        data object Untracked : NavigationTransition

        data class Fresh(
            val from: IdeNavigationPosition,
        ) : NavigationTransition

        data class History(
            val from: IdeNavigationPosition,
            val target: IdeNavigationPosition,
            val direction: NavigationDirection,
        ) : NavigationTransition
    }

    private enum class NavigationDirection {
        Back,
        Forward,
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
        is IdeEvent.CompletionModuleEnabled,
        is IdeEvent.CompletionModuleRollbackCompleted,
        -> null

        is IdeEvent.GitInspected -> generation

        is IdeEvent.GitFinished -> generation

        is IdeEvent.ProjectsManaged -> generation

        is IdeEvent.ProjectCatalogLoaded -> generation

        is IdeEvent.LibraryConfigurationLoaded -> generation

        is IdeEvent.BuildInputLoaded -> generation

        is IdeEvent.BuildStateChanged -> generation

        is IdeEvent.ResolveCompleted -> generation

        is IdeEvent.ProjectOpened -> generation

        is IdeEvent.FileOpened -> generation

        is IdeEvent.DeclarationResolved -> generation

        is IdeEvent.UsagesResolved -> generation

        is IdeEvent.RenameResolved -> generation

        is IdeEvent.RenameLoaded -> generation

        is IdeEvent.SaveCompleted -> generation

        is IdeEvent.FormatCompleted -> generation

        is IdeEvent.DeleteAdmitted -> generation

        is IdeEvent.MutationCompleted -> generation

        is IdeEvent.ComputerImportCompleted -> generation

        is IdeEvent.ComputerImportFailed -> generation

        is IdeEvent.CatalogLoaded -> generation

        is IdeEvent.PollCompleted -> generation

        is IdeEvent.BuildCompleted -> generation

        is IdeEvent.Failed -> generation
    }

private fun ProjectPath.isWithin(parent: ProjectPath): Boolean = value == parent.value || value.startsWith("${parent.value}/")

private fun EditorDocument.lineContaining(offsetUtf16: Int): Int {
    val admitted = offsetUtf16.coerceIn(0, length)
    var low = 0
    var high = lineCount
    while (low < high) {
        val middle = (low + high) ushr 1
        if (lineStartOffset(middle) <= admitted) low = middle + 1 else high = middle
    }
    return (low - 1).coerceAtLeast(0)
}

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
