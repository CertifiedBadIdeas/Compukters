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

package ru.lazyhat.compukters.ide.client.analysis

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisResultSink
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.editor.EditorChange
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

fun interface IdeAnalysisInputLoader {
    fun load(project: ProjectHandle): CompletableFuture<IdeBuildInput>
}

fun interface IdeAnalysisSnapshotFactory {
    fun create(
        input: IdeBuildInput,
        activePath: VirtualSourcePath,
        activeText: String,
        target: TargetCompileProfile?,
    ): AdmittedAnalysisSnapshot
}

fun interface IdeAnalysisRequestFactory {
    fun create(resultSink: AnalysisResultSink): AnalysisRequestCoordinator
}

sealed interface IdeHighlightStyle {
    data class Lexical(
        val kind: KotlinLexicalKind,
    ) : IdeHighlightStyle

    data class Semantic(
        val category: SemanticCategory,
    ) : IdeHighlightStyle
}

class IdeAnalysisPresentation private constructor(
    diagnostics: List<EditorDiagnostic>,
    semanticTokens: List<SemanticToken>,
    methodUsages: List<ru.lazyhat.compukters.ide.analysis.MethodUsageCount> = emptyList(),
) {
    val diagnostics: List<EditorDiagnostic> = Collections.unmodifiableList(diagnostics.toList())
    val semanticTokens: List<SemanticToken> = Collections.unmodifiableList(semanticTokens.toList())
    val methodUsages: List<ru.lazyhat.compukters.ide.analysis.MethodUsageCount> =
        Collections.unmodifiableList(
            methodUsages.sortedBy {
                it.range.startUtf16
            },
        )

    fun styleAt(
        path: VirtualSourcePath,
        offsetUtf16: Int,
        lexicalFallback: KotlinLexicalKind,
    ): IdeHighlightStyle =
        semanticTokens
            .firstOrNull { token ->
                token.path == path && offsetUtf16 >= token.range.startUtf16 && offsetUtf16 < token.range.endUtf16
            }?.let { IdeHighlightStyle.Semantic(it.category) }
            ?: IdeHighlightStyle.Lexical(lexicalFallback)

    fun rebase(
        activePath: VirtualSourcePath,
        change: EditorChange,
    ): IdeAnalysisPresentation {
        val delta = Math.subtractExact(change.insertedCodeUnits, change.oldRange.length)
        val rebased =
            semanticTokens.mapNotNull { token ->
                if (token.path != activePath) return@mapNotNull token
                val range = token.range
                when {
                    range.endUtf16 <= change.oldRange.startUtf16 -> {
                        token
                    }

                    range.startUtf16 >= change.oldRange.endUtf16 -> {
                        val start = Math.addExact(range.startUtf16, delta)
                        val end = Math.addExact(range.endUtf16, delta)
                        token.copy(range = EditorRange(start, end))
                    }

                    else -> {
                        null
                    }
                }
            }
        return IdeAnalysisPresentation(emptyList(), rebased)
    }

    companion object {
        val Empty = IdeAnalysisPresentation(emptyList(), emptyList())

        fun of(
            diagnostics: List<EditorDiagnostic>,
            semanticTokens: List<SemanticToken>,
            methodUsages: List<ru.lazyhat.compukters.ide.analysis.MethodUsageCount> = emptyList(),
        ): IdeAnalysisPresentation = IdeAnalysisPresentation(diagnostics, semanticTokens, methodUsages)
    }
}

sealed interface IdeAnalysisState {
    data object Idle : IdeAnalysisState

    data class Loading(
        val path: VirtualSourcePath,
        val documentRevision: Long,
    ) : IdeAnalysisState

    data class Active(
        val identity: AnalysisSnapshotIdentity,
        val path: VirtualSourcePath,
        val documentRevision: Long,
        val presentation: IdeAnalysisPresentation,
        val completion: IdeCompletionState?,
        val interaction: IdeSemanticInteraction = IdeSemanticInteraction.None,
        val parameterInfo: IdeParameterInfoState? = null,
        val occurrenceRanges: List<EditorRange> = emptyList(),
    ) : IdeAnalysisState

    data class Unavailable(
        val path: VirtualSourcePath,
        val documentRevision: Long,
        val status: String,
        val detail: String,
    ) : IdeAnalysisState
}

class IdeAnalysisCoordinator(
    private val inputLoader: IdeAnalysisInputLoader,
    private val snapshotFactory: IdeAnalysisSnapshotFactory,
    requestFactory: IdeAnalysisRequestFactory,
    private val visibleLatency: IdeVisibleLatencyTrace = IdeVisibleLatencyTrace.None,
    private val limits: IdeClientLimits = IdeClientLimits(),
    private val attachedSources: IdeAttachedSourceCatalog = IdeAttachedSourceCatalog.empty(),
    platformCatalog: PlatformCatalog,
) : AnalysisResultSink,
    AutoCloseable {
    private val lock = Any()
    private val publishedState = AtomicReference<IdeAnalysisState>(IdeAnalysisState.Idle)
    private val requests = requestFactory.create(this)
    private val completionPlanner = IdeCompletionPlanner(platformCatalog)
    private var activeAttachedSources = attachedSources
    private var session: Session? = null
    private var version = 0L
    private var semanticOperation = 0L
    private var pointer: PointerInteraction? = null
    private var caretOccurrences: CaretOccurrences? = null
    private var caretOccurrencesEnabled = false
    private var navigationResult: CompletableFuture<IdeDeclarationOutcome>? = null
    private var parameterInfoRequested = false
    private var parameterInfoOperation = 0L
    private var parameterInfoRequest: ParameterInfoRequest? = null
    private var closed = false
    private var targetProfile: TargetCompileProfile? = null
    private var targetRevision = 0L

    fun state(): IdeAnalysisState = publishedState.get()

    fun attachedSource(
        module: ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity,
        path: VirtualSourcePath,
    ): String? = synchronized(lock) { activeAttachedSources.text(module, path) }

    fun open(
        project: ProjectHandle,
        path: VirtualSourcePath,
        text: String,
        documentRevision: Long,
        sourceOverlays: Map<VirtualSourcePath, String> = emptyMap(),
    ) {
        require(documentRevision >= 0) { "document revision must not be negative" }
        val admittedPath = VirtualSourcePath.kotlin(path.value)
        val expectedVersion: Long
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            invalidateSemanticLocked()
            caretOccurrencesEnabled = false
            invalidateParameterInfoLocked(close = true)
            version = Math.incrementExact(version)
            expectedVersion = version
            session = Session(project, admittedPath, text, documentRevision, text.length, null, null, overlays = sourceOverlays.toMap())
            publishedState.set(IdeAnalysisState.Loading(admittedPath, documentRevision))
        }
        cancelPointerRequests()
        requests.cancelSymbolOccurrences()
        requests.cancelParameterInfo()
        inputLoader.load(project).whenComplete { input, failure ->
            if (failure == null && input != null) {
                acceptInput(expectedVersion, input)
            } else {
                unavailable(expectedVersion, failure?.message ?: "failed to load analysis input")
            }
        }
    }

    fun sourceChanged(
        project: ProjectHandle,
        path: VirtualSourcePath,
        text: String,
        documentRevision: Long,
        insertedText: String?,
        caretOffsetUtf16: Int = text.length,
        change: EditorChange? = null,
        sourceOverlays: Map<VirtualSourcePath, String> = emptyMap(),
    ) {
        require(documentRevision >= 0) { "document revision must not be negative" }
        require(caretOffsetUtf16 in 0..text.length) { "analysis caret exceeds current source" }
        val trigger = insertedText?.takeIf(::triggersAutomaticCompletion) != null
        val rebuild: Rebuild?
        val completionExpected: Boolean
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            invalidateSemanticLocked()
            invalidateParameterInfoLocked(close = false)
            val current = session
            if (current == null || current.project !== project || current.path != path) {
                open(project, path, text, documentRevision, sourceOverlays)
                return
            }
            if (current.input != null) version = Math.incrementExact(version)
            val presentation =
                change?.let { exactChange ->
                    (publishedState.get() as? IdeAnalysisState.Active)
                        ?.presentation
                        ?.rebase(current.path, exactChange)
                } ?: IdeAnalysisPresentation.Empty
            val updated =
                current.copy(
                    text = text,
                    documentRevision = documentRevision,
                    caretOffsetUtf16 = caretOffsetUtf16,
                    snapshot = null,
                    pendingCompletion =
                        if (trigger && !parameterInfoRequested) PendingCompletion.Automatic else null,
                    provisionalPresentation = presentation,
                    overlays = sourceOverlays.toMap(),
                )
            completionExpected = updated.pendingCompletion == PendingCompletion.Automatic
            session = updated
            publishedState.set(IdeAnalysisState.Loading(updated.path, documentRevision))
            rebuild = updated.input?.let { Rebuild(version, updated) }
        }
        cancelPointerRequests()
        requests.cancelSymbolOccurrences()
        requests.cancelParameterInfo()
        if (completionExpected) visibleLatency.automaticCompletionExpected(documentRevision)
        rebuild?.let { pending -> rebuild(pending.version, requireNotNull(pending.session.input)) }
    }

    fun reload() {
        val project: ProjectHandle
        val expectedVersion: Long
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            val current = session ?: return
            invalidateSemanticLocked()
            invalidateParameterInfoLocked(close = true)
            version = Math.incrementExact(version)
            expectedVersion = version
            project = current.project
            session = current.copy(input = null, snapshot = null)
            publishedState.set(IdeAnalysisState.Loading(current.path, current.documentRevision))
        }
        cancelPointerRequests()
        requests.cancelSymbolOccurrences()
        requests.cancelParameterInfo()
        inputLoader.load(project).whenComplete { input, failure ->
            if (failure == null && input != null) {
                acceptInput(expectedVersion, input)
            } else {
                unavailable(expectedVersion, failure?.message ?: "failed to reload analysis input")
            }
        }
    }

    fun manualCompletion() {
        dismissParameterInfo()
        val request: CompletionRequest?
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            val current = session ?: return
            request = current.snapshot?.let { CompletionRequest(current.path, current.caretOffsetUtf16) }
            session = current.copy(pendingCompletion = if (request == null) PendingCompletion.Manual else null)
        }
        request?.let { requests.manualCompletion(it.path, it.offsetUtf16) }
    }

    fun rename(
        offsetUtf16: Int,
        documentRevision: Long,
        newName: String,
    ): CompletableFuture<IdeRenameOutcome> {
        val current =
            synchronized(lock) { session?.takeIf { !closed && it.documentRevision == documentRevision && it.snapshot != null } }
                ?: return CompletableFuture.completedFuture(IdeRenameOutcome.Failed("Analysis is not ready"))
        val snapshot = requireNotNull(current.snapshot)
        return requests.rename(current.path, offsetUtf16, newName).handle { result, failure ->
            val references = (result as? AnalysisClientResult.Success)?.result as? AnalysisResult.References
            if (!isCurrent(snapshot.identity, current.path, documentRevision) || failure != null ||
                references?.identity != snapshot.identity ||
                references.locations.isEmpty()
            ) {
                IdeRenameOutcome.Failed(
                    (result as? AnalysisClientResult.Failure)?.detail ?: failure?.message ?: "Sources changed or Rename did not complete",
                )
            } else {
                IdeRenameOutcome.Prepared(
                    IdeRenamePlan(
                        snapshot.identity,
                        current.path,
                        documentRevision,
                        newName,
                        snapshot.sources.sources.associate { it.path to it.content.toByteArray().decodeToString() },
                        references.locations.filterIsInstance<DeclarationLocation.Source>(),
                        requireNotNull(current.input).manifestBytes,
                        current.input.lockBytes,
                    ),
                )
            }
        }
    }

    fun isCurrent(
        identity: AnalysisSnapshotIdentity,
        path: VirtualSourcePath,
        revision: Long,
    ): Boolean =
        synchronized(lock) {
            !closed && session?.let { it.snapshot?.identity == identity && it.path == path && it.documentRevision == revision } == true
        }

    fun findUsages(
        offsetUtf16: Int,
        documentRevision: Long,
    ): CompletableFuture<IdeUsagesOutcome> {
        val current =
            synchronized(lock) { session?.takeIf { !closed && it.documentRevision == documentRevision && it.snapshot != null } }
                ?: return CompletableFuture.completedFuture(IdeUsagesOutcome.Failed("Analysis is not ready"))
        val snapshot = requireNotNull(current.snapshot)
        val result = CompletableFuture<IdeUsagesOutcome>()
        requests.cancelSymbolOccurrences()
        requests.symbolOccurrences(current.path, offsetUtf16).whenComplete { values, failure ->
            val fresh = synchronized(lock) { !closed && session?.snapshot === snapshot }
            val references =
                values
                    ?.filterIsInstance<AnalysisClientResult.Success>()
                    ?.map { it.result }
                    ?.filterIsInstance<AnalysisResult.References>()
                    ?.singleOrNull()
                    ?.takeIf { it.identity == snapshot.identity }
            val declarations =
                values
                    ?.filterIsInstance<AnalysisClientResult.Success>()
                    ?.map { it.result }
                    ?.filterIsInstance<AnalysisResult.Declaration>()
                    ?.singleOrNull()
                    ?.takeIf { it.identity == snapshot.identity }
            if (!fresh || failure != null || references == null || declarations?.locations?.distinct()?.size != 1) {
                result.complete(
                    IdeUsagesOutcome.Failed(if (!fresh) "Sources changed; run Find Usages again" else "Find Usages did not complete"),
                )
            } else {
                val texts = snapshot.sources.sources.associate { it.path to it.content.toByteArray().decodeToString() }
                val locations =
                    references.locations.filterIsInstance<DeclarationLocation.Source>().filter {
                        it.origin ==
                            DeclarationOrigin.Project
                    }
                val rows =
                    locations.take(limits.usageRows).map { location ->
                        val text = texts.getValue(location.path)
                        val start = text.lastIndexOf('\n', (location.range.startUtf16 - 1).coerceAtLeast(-1)) + 1
                        val end = text.indexOf('\n', location.range.endUtf16).takeIf { it >= 0 } ?: text.length
                        IdeUsage(
                            ProjectPath.file(location.path.value),
                            location.range,
                            text.substring(0, start).count {
                                it == '\n'
                            },
                            text.substring(start, end).trim().take(256),
                        )
                    }
                result.complete(IdeUsagesOutcome.Found(IdeUsages(snapshot.identity, rows, locations.size)))
            }
        }
        return result
    }

    fun showParameterInfo() {
        val request: ParameterInfoRequest?
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            val current = session ?: return
            parameterInfoRequested = true
            invalidatePointerLocked()
            request = current.snapshot?.let { snapshot -> beginParameterInfoLocked(current, snapshot) }
            val active = publishedState.get() as? IdeAnalysisState.Active
            if (active != null) publishedState.set(active.copy(completion = null, parameterInfo = null))
        }
        cancelPointerRequests()
        requests.cancelParameterInfo()
        request?.let(::dispatchParameterInfo)
    }

    fun caretMoved(offsetUtf16: Int) {
        val request: ParameterInfoRequest?
        synchronized(lock) {
            val current = session ?: return
            require(offsetUtf16 in 0..current.text.length) { "analysis caret exceeds current source" }
            val updated = current.copy(caretOffsetUtf16 = offsetUtf16)
            session = updated
            caretOccurrencesEnabled = true
            request =
                if (parameterInfoRequested) {
                    updated.snapshot?.let { snapshot -> beginParameterInfoLocked(updated, snapshot) }
                } else {
                    null
                }
            val active = publishedState.get() as? IdeAnalysisState.Active
            if (active != null && parameterInfoRequested) publishedState.set(active.copy(parameterInfo = null))
        }
        request?.let(::dispatchParameterInfo)
        refreshCaretOccurrences()
    }

    fun dismissParameterInfo() {
        synchronized(lock) {
            invalidateParameterInfoLocked(close = true)
        }
        requests.cancelParameterInfo()
    }

    fun format(
        path: VirtualSourcePath,
        source: String,
        caretOffsetUtf16: Int,
        documentRevision: Long,
    ): CompletableFuture<AnalysisClientResult> =
        try {
            val current =
                synchronized(lock) {
                    check(!closed) { "analysis coordinator is closed" }
                    val active = checkNotNull(session) { "analysis source is not open" }
                    check(
                        active.path == path &&
                            active.text == source &&
                            active.documentRevision == documentRevision,
                    ) { "analysis source changed before formatting" }
                    active
                }
            requests.format(current.path, source, caretOffsetUtf16)
        } catch (failure: RuntimeException) {
            CompletableFuture.failedFuture(failure)
        }

    fun pointerMoved(
        tokenRange: EditorRange?,
        offsetUtf16: Int?,
        controlDown: Boolean,
    ) {
        val request: PointerInteraction?
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            val current = session
            val snapshot = current?.snapshot
            val active = publishedState.get() as? IdeAnalysisState.Active
            if (
                tokenRange == null || offsetUtf16 == null || current == null || snapshot == null || active == null ||
                active.identity != snapshot.identity || active.path != current.path ||
                active.documentRevision != current.documentRevision || !validToken(current.text, tokenRange, offsetUtf16)
            ) {
                invalidatePointerLocked()
                request = null
            } else {
                val anchor =
                    IdeSemanticAnchor(
                        snapshot.identity,
                        current.path,
                        current.documentRevision,
                        offsetUtf16,
                        tokenRange,
                    )
                val prior = pointer
                if (
                    prior != null && prior.snapshot === snapshot && sameToken(prior.anchor, anchor) &&
                    prior.controlDown == controlDown
                ) {
                    return
                }
                semanticOperation = Math.incrementExact(semanticOperation)
                request = PointerInteraction(semanticOperation, snapshot, anchor, controlDown)
                pointer = request
                publishedState.set(active.copy(interaction = IdeSemanticInteraction.None))
            }
        }
        cancelPointerRequests()
        request ?: return
        val future =
            if (request.controlDown) {
                requests.declarationProbe(request.anchor.path, request.anchor.offsetUtf16)
            } else {
                requests.hoverInfo(request.anchor.path, request.anchor.offsetUtf16)
            }
        future.whenComplete { result, failure -> acceptPointer(request, result, failure) }
    }

    fun controlReleased() {
        val current = synchronized(lock) { pointer?.takeIf(PointerInteraction::controlDown) } ?: return
        pointerMoved(current.anchor.tokenRange, current.anchor.offsetUtf16, controlDown = false)
    }

    fun goToDeclaration(
        tokenRange: EditorRange,
        offsetUtf16: Int,
    ): CompletableFuture<IdeDeclarationOutcome> {
        val request: NavigationInteraction
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            val current = session
            val snapshot = current?.snapshot
            val active = publishedState.get() as? IdeAnalysisState.Active
            if (
                current == null || snapshot == null || active == null || active.identity != snapshot.identity ||
                active.path != current.path || active.documentRevision != current.documentRevision ||
                !validToken(current.text, tokenRange, offsetUtf16)
            ) {
                return CompletableFuture.completedFuture(IdeDeclarationOutcome.Failed("Declaration target is stale"))
            }
            val anchor = IdeSemanticAnchor(snapshot.identity, current.path, current.documentRevision, offsetUtf16, tokenRange)
            val link = active.interaction as? IdeSemanticInteraction.Link
            if (link != null && sameToken(link.anchor, anchor)) {
                return CompletableFuture.completedFuture(declarationOutcome(anchor, link.locations, publishChooser = true))
            }
            navigationResult?.cancel(false)
            val result = CompletableFuture<IdeDeclarationOutcome>()
            navigationResult = result
            request = NavigationInteraction(Math.incrementExact(semanticOperation), snapshot, anchor, result)
        }
        requests.declaration(request.anchor.path, request.anchor.offsetUtf16).whenComplete { result, failure ->
            acceptNavigation(request, result, failure)
        }
        return request.result
    }

    fun moveDeclarationChoice(delta: Int) {
        synchronized(lock) {
            val active = publishedState.get() as? IdeAnalysisState.Active ?: return
            val chooser = active.interaction as? IdeSemanticInteraction.Chooser ?: return
            val selected = (chooser.selectedIndex.toLong() + delta).coerceIn(0, chooser.targets.lastIndex.toLong()).toInt()
            publishedState.set(
                active.copy(
                    interaction =
                        IdeSemanticInteraction.Chooser(
                            chooser.anchor,
                            chooser.targets,
                            selected,
                            limits.declarationChoices,
                        ),
                ),
            )
        }
    }

    fun acceptDeclarationChoice(): IdeDeclarationTarget? =
        synchronized(lock) {
            val active = publishedState.get() as? IdeAnalysisState.Active ?: return@synchronized null
            val chooser = active.interaction as? IdeSemanticInteraction.Chooser ?: return@synchronized null
            publishedState.set(active.copy(interaction = IdeSemanticInteraction.None))
            chooser.targets[chooser.selectedIndex]
        }

    fun dismissSemanticInteraction() {
        synchronized(lock) {
            invalidatePointerLocked()
        }
        cancelPointerRequests()
    }

    fun moveCompletion(delta: Int) = updateCompletion { it.move(delta) }

    fun moveCompletionPage(
        pages: Int,
        pageSize: Int,
    ) = updateCompletion { it.movePage(pages, pageSize) }

    fun focusLost() {
        synchronized(lock) {
            caretOccurrencesEnabled = false
            invalidateCaretOccurrencesLocked()
        }
        requests.cancelSymbolOccurrences()
        dismissCompletion()
        dismissSemanticInteraction()
        dismissParameterInfo()
    }

    fun dismissCompletion() = updateCompletion { null }

    fun selectCompletion(
        document: EditorDocument,
        path: VirtualSourcePath,
    ): IdeCompletionSelection? {
        val state = publishedState.get() as? IdeAnalysisState.Active ?: return null
        val completion = state.completion ?: return null
        val (current, currentTargetRevision) = synchronized(lock) { (session ?: return null) to targetRevision }
        if (
            current.path != path || current.snapshot?.identity != state.identity ||
            current.documentRevision != document.revision || !document.contentEquals(current.text)
        ) {
            dismissCompletion()
            return null
        }
        val selection = completion.select(state.identity, path, document.revision, currentTargetRevision)
        dismissCompletion()
        return selection
    }

    fun isCompletionSelectionCurrent(
        selection: IdeCompletionSelection,
        document: EditorDocument,
    ): Boolean =
        synchronized(lock) {
            val current = session ?: return@synchronized false
            current.path == selection.path &&
                current.snapshot?.identity == selection.identity &&
                current.documentRevision == selection.documentRevision &&
                targetRevision == selection.targetRevision &&
                document.revision == selection.documentRevision &&
                document.contentEquals(current.text)
        }

    fun updateTargetProfile(profile: TargetCompileProfile?) {
        synchronized(lock) {
            if (targetProfile == profile) return
            activeAttachedSources = profile?.let { attachedSources.withAddonBundles(it.addonBundles) } ?: attachedSources
            targetProfile = profile
            targetRevision = Math.incrementExact(targetRevision)
            val active = publishedState.get() as? IdeAnalysisState.Active ?: return
            publishedState.set(active.copy(completion = null))
        }
    }

    fun closeFile() {
        synchronized(lock) {
            if (closed) return
            invalidateSemanticLocked()
            invalidateParameterInfoLocked(close = true)
            version = Math.incrementExact(version)
            session = null
            publishedState.set(IdeAnalysisState.Idle)
        }
        cancelPointerRequests()
        requests.cancelSymbolOccurrences()
        requests.cancelParameterInfo()
    }

    override fun publish(result: AnalysisClientResult) {
        synchronized(lock) {
            if (closed) return
            val current = session ?: return
            val snapshot = current.snapshot ?: return
            when (result) {
                is AnalysisClientResult.Success -> {
                    publishSuccess(current, snapshot, result.result)
                }

                is AnalysisClientResult.Failure -> {
                    visibleLatency.resultUnavailable(IdeVisibleLatencyKind.Presentation, current.documentRevision)
                    visibleLatency.resultUnavailable(IdeVisibleLatencyKind.AutomaticCompletion, current.documentRevision)
                    publishedState.set(
                        IdeAnalysisState.Unavailable(
                            current.path,
                            current.documentRevision,
                            "Analysis unavailable",
                            boundedDetail(result.detail),
                        ),
                    )
                }

                AnalysisClientResult.Cancelled,
                AnalysisClientResult.Stale,
                -> {
                    Unit
                }
            }
        }
        if (result is AnalysisClientResult.Success &&
            (result.result is AnalysisResult.Presentation || result.result is AnalysisResult.Completion)
        ) {
            refreshCaretOccurrences()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            invalidateSemanticLocked()
            invalidateParameterInfoLocked(close = true)
            closed = true
            version = Math.incrementExact(version)
            session = null
            publishedState.set(IdeAnalysisState.Idle)
        }
        requests.close()
    }

    private fun acceptInput(
        expectedVersion: Long,
        input: IdeBuildInput,
    ) {
        val rebuild: Rebuild
        synchronized(lock) {
            val current = session ?: return
            if (closed || version != expectedVersion || current.project !== input.project) return
            val updated = current.copy(input = input)
            session = updated
            rebuild = Rebuild(expectedVersion, updated)
        }
        rebuild(rebuild.version, input)
    }

    private fun rebuild(
        expectedVersion: Long,
        input: IdeBuildInput,
    ) {
        val (current, target) =
            synchronized(lock) {
                val active = session?.takeIf { !closed && version == expectedVersion && it.input === input } ?: return
                active to targetProfile
            }
        val snapshot =
            try {
                overlaySnapshot(snapshotFactory.create(input, current.path, current.text, target), current).also {
                    validateSnapshot(it, current)
                }
            } catch (failure: Throwable) {
                unavailable(expectedVersion, failure.message ?: "invalid analysis snapshot")
                return
            }
        val completion: PendingCompletion?
        val parameterInfo: ParameterInfoRequest?
        synchronized(lock) {
            val latest = session ?: return
            if (closed || version != expectedVersion || latest !== current) return
            session = latest.copy(snapshot = snapshot, pendingCompletion = null)
            completion = latest.pendingCompletion
            publishedState.set(
                IdeAnalysisState.Active(
                    snapshot.identity,
                    latest.path,
                    latest.documentRevision,
                    latest.provisionalPresentation,
                    null,
                ),
            )
            requests.sourceChanged(snapshot, current.path)
            parameterInfo = if (parameterInfoRequested) beginParameterInfoLocked(latest, snapshot) else null
        }
        when (completion) {
            PendingCompletion.Automatic -> requests.automaticCompletion(current.path, current.caretOffsetUtf16)
            PendingCompletion.Manual -> requests.manualCompletion(current.path, current.caretOffsetUtf16)
            null -> Unit
        }
        parameterInfo?.let(::dispatchParameterInfo)
        if (completion == null && parameterInfo == null) refreshCaretOccurrences()
    }

    private fun publishSuccess(
        current: Session,
        snapshot: AdmittedAnalysisSnapshot,
        result: AnalysisResult,
    ) {
        if (result.identity != snapshot.identity) return
        when (result) {
            is AnalysisResult.Presentation -> {
                val accepted = result.value.accept(snapshot.identity) as? SnapshotPresentationAcceptance.Active
                if (accepted == null) {
                    visibleLatency.resultUnavailable(IdeVisibleLatencyKind.Presentation, current.documentRevision)
                    return
                }
                val prior = publishedState.get() as? IdeAnalysisState.Active
                val next =
                    IdeAnalysisState.Active(
                        snapshot.identity,
                        current.path,
                        current.documentRevision,
                        IdeAnalysisPresentation.of(accepted.diagnostics, accepted.semanticTokens, accepted.methodUsages),
                        prior?.completion,
                        prior?.interaction ?: IdeSemanticInteraction.None,
                        prior?.parameterInfo,
                        prior?.occurrenceRanges ?: emptyList(),
                    )
                visibleLatency.analysisPublished(IdeVisibleLatencyKind.Presentation, current.documentRevision)
                publishedState.set(next)
            }

            is AnalysisResult.Completion -> {
                if (result.items.isEmpty() || !validRange(current.text, result.replacement.startUtf16, result.replacement.endUtf16)) {
                    visibleLatency.resultUnavailable(IdeVisibleLatencyKind.AutomaticCompletion, current.documentRevision)
                    return
                }
                val prior = publishedState.get() as? IdeAnalysisState.Active
                if (prior == null) {
                    visibleLatency.resultUnavailable(IdeVisibleLatencyKind.AutomaticCompletion, current.documentRevision)
                    return
                }
                val input = current.input ?: return
                val manifest =
                    try {
                        ProjectManifestCodec.decode(input.manifestBytes.toString(Charsets.UTF_8))
                    } catch (_: IllegalArgumentException) {
                        visibleLatency.resultUnavailable(IdeVisibleLatencyKind.AutomaticCompletion, current.documentRevision)
                        return
                    }
                val entries = completionPlanner.plan(result.items, manifest, targetProfile)
                if (entries.isEmpty()) {
                    visibleLatency.resultUnavailable(IdeVisibleLatencyKind.AutomaticCompletion, current.documentRevision)
                    return
                }
                val next =
                    prior.copy(
                        completion =
                            IdeCompletionState.create(
                                snapshot.identity,
                                current.path,
                                current.documentRevision,
                                targetRevision,
                                result.replacement,
                                entries,
                            ),
                    )
                visibleLatency.analysisPublished(IdeVisibleLatencyKind.AutomaticCompletion, current.documentRevision)
                publishedState.set(next)
            }

            is AnalysisResult.Declaration,
            is AnalysisResult.ExpressionInfo,
            is AnalysisResult.ParameterInfo,
            is AnalysisResult.References,
            is AnalysisResult.Format,
            -> {}
        }
    }

    private fun updateCompletion(transform: (IdeCompletionState) -> IdeCompletionState?) {
        synchronized(lock) {
            val active = publishedState.get() as? IdeAnalysisState.Active ?: return
            val completion = active.completion ?: return
            publishedState.set(active.copy(completion = transform(completion)))
        }
    }

    private fun acceptPointer(
        expected: PointerInteraction,
        result: AnalysisClientResult?,
        failure: Throwable?,
    ) {
        synchronized(lock) {
            if (!currentPointer(expected)) return
            val active = publishedState.get() as? IdeAnalysisState.Active ?: return
            val interaction =
                if (failure != null) {
                    IdeSemanticInteraction.None
                } else {
                    when (result) {
                        is AnalysisClientResult.Success -> pointerInteraction(expected, result.result)

                        is AnalysisClientResult.Failure,
                        AnalysisClientResult.Cancelled,
                        AnalysisClientResult.Stale,
                        null,
                        -> IdeSemanticInteraction.None
                    }
                }
            publishedState.set(active.copy(interaction = interaction))
        }
    }

    private fun refreshCaretOccurrences() {
        val request: CaretOccurrences?
        synchronized(lock) {
            val current = session ?: return
            if (closed || !caretOccurrencesEnabled) return
            val snapshot = current.snapshot ?: return
            val offset = current.caretOffsetUtf16
            val token =
                KotlinSourceTokenRange.find(current.text, offset)
                    ?: if (offset > 0) {
                        KotlinSourceTokenRange
                            .find(
                                current.text,
                                offset - Character.charCount(current.text.codePointBefore(offset)),
                            )?.takeIf { it.endUtf16 == offset }
                    } else {
                        null
                    }
            if (token != null && caretOccurrences?.let {
                    it.snapshot === snapshot && it.anchor.tokenRange == token && it.anchor.documentRevision == current.documentRevision
                } == true
            ) {
                return
            }
            invalidateCaretOccurrencesLocked()
            request =
                token?.let {
                    CaretOccurrences(
                        snapshot,
                        IdeSemanticAnchor(
                            snapshot.identity,
                            current.path,
                            current.documentRevision,
                            it.startUtf16,
                            it,
                        ),
                    )
                }
            caretOccurrences = request
        }
        requests.cancelSymbolOccurrences()
        request ?: return
        requests.symbolOccurrences(request.anchor.path, request.anchor.offsetUtf16).whenComplete { results, failure ->
            acceptOccurrences(request, results, failure)
        }
    }

    private fun acceptOccurrences(
        expected: CaretOccurrences,
        results: List<AnalysisClientResult>?,
        failure: Throwable?,
    ) {
        synchronized(lock) {
            val current = session ?: return
            if (closed || caretOccurrences !== expected || current.snapshot !== expected.snapshot ||
                current.path != expected.anchor.path || current.documentRevision != expected.anchor.documentRevision
            ) {
                return
            }
            val active = publishedState.get() as? IdeAnalysisState.Active ?: return
            if (failure != null || results == null) return
            val values = results.mapNotNull { (it as? AnalysisClientResult.Success)?.result }
            if (values.size != 2 || values.any { it.identity != expected.snapshot.identity }) return
            val references = values.filterIsInstance<AnalysisResult.References>().singleOrNull() ?: return
            val declaration = values.filterIsInstance<AnalysisResult.Declaration>().singleOrNull() ?: return
            val ranges =
                (references.locations + declaration.locations)
                    .filterIsInstance<DeclarationLocation.Source>()
                    .filter { it.origin == DeclarationOrigin.Project && it.path == expected.anchor.path }
                    .map { it.range }
                    .filter { validRange(current.text, it.startUtf16, it.endUtf16) }
                    .distinct()
                    .sortedWith(compareBy({ it.startUtf16 }, { it.endUtf16 }))
            // A surrounding call must not turn literal text, comments or unresolved words into a symbol occurrence.
            if (expected.anchor.tokenRange !in ranges) return
            publishedState.set(active.copy(occurrenceRanges = Collections.unmodifiableList(ranges)))
        }
    }

    private fun pointerInteraction(
        expected: PointerInteraction,
        result: AnalysisResult,
    ): IdeSemanticInteraction {
        if (result.identity != expected.snapshot.identity) return IdeSemanticInteraction.None
        return if (expected.controlDown) {
            val declaration = result as? AnalysisResult.Declaration ?: return IdeSemanticInteraction.None
            val available = declaration.locations.filterIsInstance<DeclarationLocation.Source>()
            if (available.isEmpty()) IdeSemanticInteraction.None else IdeSemanticInteraction.Link(expected.anchor, available)
        } else {
            val info = (result as? AnalysisResult.ExpressionInfo)?.value ?: return IdeSemanticInteraction.None
            if (
                info.path != expected.anchor.path ||
                expected.anchor.offsetUtf16 !in info.range.startUtf16 until info.range.endUtf16
            ) {
                IdeSemanticInteraction.None
            } else {
                IdeSemanticInteraction.Hover(expected.anchor, info)
            }
        }
    }

    private fun acceptNavigation(
        expected: NavigationInteraction,
        result: AnalysisClientResult?,
        failure: Throwable?,
    ) {
        synchronized(lock) {
            if (!currentNavigation(expected)) return
            navigationResult = null
            val outcome =
                when {
                    failure != null -> {
                        IdeDeclarationOutcome.Failed(boundedDetail(failure.message ?: "declaration request failed"))
                    }

                    result is AnalysisClientResult.Success && result.result is AnalysisResult.Declaration -> {
                        val declaration = result.result as AnalysisResult.Declaration
                        if (declaration.identity != expected.snapshot.identity) {
                            IdeDeclarationOutcome.Failed("Declaration result is stale")
                        } else {
                            declarationOutcome(expected.anchor, declaration.locations, publishChooser = true)
                        }
                    }

                    result is AnalysisClientResult.Failure -> {
                        IdeDeclarationOutcome.Failed(boundedDetail(result.detail))
                    }

                    result === AnalysisClientResult.Stale -> {
                        IdeDeclarationOutcome.Failed("Declaration result is stale")
                    }

                    else -> {
                        IdeDeclarationOutcome.Failed("Declaration request was cancelled")
                    }
                }
            expected.result.complete(outcome)
        }
    }

    private fun declarationOutcome(
        anchor: IdeSemanticAnchor,
        locations: List<DeclarationLocation>,
        publishChooser: Boolean,
    ): IdeDeclarationOutcome {
        val unavailableModules = mutableListOf<ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity>()
        val targets =
            locations
                .mapNotNull { location ->
                    when (location) {
                        is DeclarationLocation.Source -> {
                            when (val origin = location.origin) {
                                DeclarationOrigin.Project -> {
                                    IdeDeclarationTarget.Project(ProjectPath.file(location.path.value), location.range)
                                }

                                is DeclarationOrigin.Platform -> {
                                    if (activeAttachedSources.text(origin.identity, location.path) != null) {
                                        IdeDeclarationTarget.AttachedSource(origin.identity, location.path, location.range)
                                    } else {
                                        unavailableModules += origin.identity
                                        null
                                    }
                                }
                            }
                        }

                        is DeclarationLocation.SourceUnavailable -> {
                            unavailableModules += (location.origin as DeclarationOrigin.Platform).identity
                            null
                        }
                    }
                }.take(limits.declarationChoices)
        if (targets.isEmpty()) {
            return unavailableModules.firstOrNull()?.let(IdeDeclarationOutcome::SourceUnavailable)
                ?: IdeDeclarationOutcome.NotFound
        }
        if (publishChooser && targets.size > 1) {
            val active = publishedState.get() as? IdeAnalysisState.Active
            if (active != null && sameAnchor(active, anchor)) {
                publishedState.set(
                    active.copy(
                        interaction = IdeSemanticInteraction.Chooser(anchor, targets, 0, limits.declarationChoices),
                    ),
                )
            }
        }
        return IdeDeclarationOutcome.Targets(anchor, targets)
    }

    private fun currentPointer(expected: PointerInteraction): Boolean {
        val current = session ?: return false
        return !closed && pointer?.operation == expected.operation && current.snapshot === expected.snapshot &&
            current.path == expected.anchor.path && current.documentRevision == expected.anchor.documentRevision &&
            current.snapshot.identity == expected.anchor.identity
    }

    private fun currentNavigation(expected: NavigationInteraction): Boolean {
        val current = session ?: return false
        return !closed && navigationResult === expected.result && current.snapshot === expected.snapshot &&
            current.path == expected.anchor.path && current.documentRevision == expected.anchor.documentRevision &&
            current.snapshot.identity == expected.anchor.identity
    }

    private fun invalidateSemanticLocked() {
        invalidatePointerLocked()
        invalidateCaretOccurrencesLocked()
        navigationResult?.cancel(false)
        navigationResult = null
    }

    private fun beginParameterInfoLocked(
        current: Session,
        snapshot: AdmittedAnalysisSnapshot,
    ): ParameterInfoRequest =
        ParameterInfoRequest(
            Math.incrementExact(parameterInfoOperation),
            snapshot,
            current.path,
            current.documentRevision,
            current.caretOffsetUtf16,
        ).also { parameterInfoRequest = it }

    private fun dispatchParameterInfo(expected: ParameterInfoRequest) {
        requests.parameterInfo(expected.path, expected.caretOffsetUtf16).whenComplete { result, failure ->
            acceptParameterInfo(expected, result, failure)
        }
    }

    private fun acceptParameterInfo(
        expected: ParameterInfoRequest,
        result: AnalysisClientResult?,
        failure: Throwable?,
    ) {
        synchronized(lock) {
            if (!currentParameterInfo(expected)) return
            parameterInfoRequest = null
            val active = publishedState.get() as? IdeAnalysisState.Active ?: return
            val info =
                if (failure == null && result is AnalysisClientResult.Success) {
                    (result.result as? AnalysisResult.ParameterInfo)?.value
                } else {
                    null
                }
            if (
                info == null || info.path != expected.path ||
                expected.caretOffsetUtf16 !in info.callRange.startUtf16..info.callRange.endUtf16
            ) {
                parameterInfoRequested = false
                publishedState.set(active.copy(parameterInfo = null))
                return
            }
            publishedState.set(
                active.copy(
                    completion = null,
                    parameterInfo =
                        IdeParameterInfoState(
                            expected.snapshot.identity,
                            expected.path,
                            expected.documentRevision,
                            expected.caretOffsetUtf16,
                            info.callRange,
                            info.items,
                            limits.parameterInfoItems,
                        ),
                ),
            )
        }
    }

    private fun currentParameterInfo(expected: ParameterInfoRequest): Boolean {
        val current = session ?: return false
        return !closed && parameterInfoRequested && parameterInfoRequest?.operation == expected.operation &&
            current.snapshot === expected.snapshot && current.path == expected.path &&
            current.documentRevision == expected.documentRevision && current.caretOffsetUtf16 == expected.caretOffsetUtf16 &&
            current.snapshot.identity == expected.snapshot.identity
    }

    private fun invalidateParameterInfoLocked(close: Boolean) {
        parameterInfoOperation = Math.incrementExact(parameterInfoOperation)
        parameterInfoRequest = null
        if (close) parameterInfoRequested = false
        val active = publishedState.get() as? IdeAnalysisState.Active ?: return
        publishedState.set(active.copy(parameterInfo = null))
    }

    private fun invalidatePointerLocked() {
        semanticOperation = Math.incrementExact(semanticOperation)
        pointer = null
        val active = publishedState.get() as? IdeAnalysisState.Active ?: return
        publishedState.set(active.copy(interaction = IdeSemanticInteraction.None))
    }

    private fun invalidateCaretOccurrencesLocked() {
        caretOccurrences = null
        val active = publishedState.get() as? IdeAnalysisState.Active ?: return
        publishedState.set(active.copy(occurrenceRanges = emptyList()))
    }

    private fun cancelPointerRequests() {
        requests.cancelPointerInteraction()
    }

    private fun sameAnchor(
        active: IdeAnalysisState.Active,
        anchor: IdeSemanticAnchor,
    ): Boolean = active.identity == anchor.identity && active.path == anchor.path && active.documentRevision == anchor.documentRevision

    private fun sameToken(
        left: IdeSemanticAnchor,
        right: IdeSemanticAnchor,
    ): Boolean =
        left.identity == right.identity && left.path == right.path && left.documentRevision == right.documentRevision &&
            left.tokenRange == right.tokenRange

    private fun unavailable(
        expectedVersion: Long,
        detail: String,
    ) {
        synchronized(lock) {
            val current = session ?: return
            if (closed || version != expectedVersion) return
            visibleLatency.resultUnavailable(IdeVisibleLatencyKind.Presentation, current.documentRevision)
            visibleLatency.resultUnavailable(IdeVisibleLatencyKind.AutomaticCompletion, current.documentRevision)
            publishedState.set(
                IdeAnalysisState.Unavailable(
                    current.path,
                    current.documentRevision,
                    "Analysis unavailable",
                    boundedDetail(detail),
                ),
            )
        }
    }

    private fun validateSnapshot(
        snapshot: AdmittedAnalysisSnapshot,
        current: Session,
    ) {
        val active = snapshot.sources.sources.singleOrNull { it.path == current.path }
        requireNotNull(active) { "analysis snapshot does not contain the active source" }
        require(active.content.toByteArray().contentEquals(current.text.encodeToByteArray())) {
            "analysis snapshot does not contain the active editor revision"
        }
    }

    private fun overlaySnapshot(
        snapshot: AdmittedAnalysisSnapshot,
        current: Session,
    ): AdmittedAnalysisSnapshot {
        if (current.overlays.isEmpty()) return snapshot
        require(current.overlays.keys.all { path -> snapshot.sources.sources.any { it.path == path } }) {
            "unsaved source no longer belongs to the project"
        }
        val sources =
            ProjectSnapshot.of(
                snapshot.sources.sources.map { source ->
                    val text = current.overlays[source.path]?.takeUnless { source.path == current.path }
                    if (text == null) source else ProjectSource(source.path, BinaryValue.of(text.encodeToByteArray()))
                },
                WorkerLimits(
                    sourceFiles = snapshot.limits.sourceFiles,
                    sourceFileBytes = snapshot.limits.sourceFileBytes,
                    sourceBytes = snapshot.limits.sourceBytes,
                    frameBytes = snapshot.limits.frameBytes,
                ),
            )
        return snapshot.copy(identity = snapshot.identity.copy(source = SourceSnapshotIdentity.of(sources)), sources = sources)
    }

    private data class Session(
        val project: ProjectHandle,
        val path: VirtualSourcePath,
        val text: String,
        val documentRevision: Long,
        val caretOffsetUtf16: Int,
        val input: IdeBuildInput?,
        val snapshot: AdmittedAnalysisSnapshot?,
        val pendingCompletion: PendingCompletion? = null,
        val provisionalPresentation: IdeAnalysisPresentation = IdeAnalysisPresentation.Empty,
        val overlays: Map<VirtualSourcePath, String> = emptyMap(),
    )

    private data class ParameterInfoRequest(
        val operation: Long,
        val snapshot: AdmittedAnalysisSnapshot,
        val path: VirtualSourcePath,
        val documentRevision: Long,
        val caretOffsetUtf16: Int,
    )

    private data class Rebuild(
        val version: Long,
        val session: Session,
    )

    private data class PointerInteraction(
        val operation: Long,
        val snapshot: AdmittedAnalysisSnapshot,
        val anchor: IdeSemanticAnchor,
        val controlDown: Boolean,
    )

    private data class CaretOccurrences(
        val snapshot: AdmittedAnalysisSnapshot,
        val anchor: IdeSemanticAnchor,
    )

    private data class NavigationInteraction(
        val operation: Long,
        val snapshot: AdmittedAnalysisSnapshot,
        val anchor: IdeSemanticAnchor,
        val result: CompletableFuture<IdeDeclarationOutcome>,
    )

    private data class CompletionRequest(
        val path: VirtualSourcePath,
        val offsetUtf16: Int,
    )

    private enum class PendingCompletion { Automatic, Manual }
}

private fun triggersAutomaticCompletion(text: String): Boolean {
    if (text.isEmpty()) return false
    val codePoint = text.codePointBefore(text.length)
    return codePoint == '.'.code || Character.isJavaIdentifierPart(codePoint)
}

private fun validRange(
    text: String,
    start: Int,
    end: Int,
): Boolean = start in 0..text.length && end in start..text.length && caretBoundary(text, start) && caretBoundary(text, end)

private fun validToken(
    text: String,
    range: EditorRange,
    offsetUtf16: Int,
): Boolean =
    range.length > 0 && range.endUtf16 <= text.length && offsetUtf16 in range.startUtf16 until range.endUtf16 &&
        caretBoundary(text, range.startUtf16) && caretBoundary(text, range.endUtf16) && caretBoundary(text, offsetUtf16)

private fun caretBoundary(
    text: String,
    offset: Int,
): Boolean {
    if (offset == 0 || offset == text.length) return true
    if (text[offset - 1] == '\r' && text[offset] == '\n') return false
    return !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
}

private fun boundedDetail(value: String): String = value.takeIf(String::isNotBlank)?.take(4096) ?: "analysis failed"
