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

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisResultSink
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.editor.EditorChange
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.project.ProjectHandle
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
) {
    val diagnostics: List<EditorDiagnostic> = Collections.unmodifiableList(diagnostics.toList())
    val semanticTokens: List<SemanticToken> = Collections.unmodifiableList(semanticTokens.toList())

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
        ): IdeAnalysisPresentation = IdeAnalysisPresentation(diagnostics, semanticTokens)
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
) : AnalysisResultSink,
    AutoCloseable {
    private val lock = Any()
    private val publishedState = AtomicReference<IdeAnalysisState>(IdeAnalysisState.Idle)
    private val requests = requestFactory.create(this)
    private var session: Session? = null
    private var version = 0L
    private var closed = false

    fun state(): IdeAnalysisState = publishedState.get()

    fun open(
        project: ProjectHandle,
        path: VirtualSourcePath,
        text: String,
        documentRevision: Long,
    ) {
        require(documentRevision >= 0) { "document revision must not be negative" }
        val admittedPath = VirtualSourcePath.kotlin(path.value)
        val expectedVersion: Long
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            version = Math.incrementExact(version)
            expectedVersion = version
            session = Session(project, admittedPath, text, documentRevision, text.length, null, null)
            publishedState.set(IdeAnalysisState.Loading(admittedPath, documentRevision))
        }
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
    ) {
        require(documentRevision >= 0) { "document revision must not be negative" }
        require(caretOffsetUtf16 in 0..text.length) { "analysis caret exceeds current source" }
        val trigger = insertedText?.takeIf(::triggersAutomaticCompletion) != null
        val rebuild: Rebuild?
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            val current = session
            if (current == null || current.project !== project || current.path != path) {
                open(project, path, text, documentRevision)
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
                    pendingCompletion = if (trigger) PendingCompletion.Automatic else null,
                    provisionalPresentation = presentation,
                )
            session = updated
            publishedState.set(IdeAnalysisState.Loading(updated.path, documentRevision))
            rebuild = updated.input?.let { Rebuild(version, updated) }
        }
        rebuild?.let { pending -> rebuild(pending.version, requireNotNull(pending.session.input)) }
    }

    fun reload() {
        val project: ProjectHandle
        val expectedVersion: Long
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            val current = session ?: return
            version = Math.incrementExact(version)
            expectedVersion = version
            project = current.project
            session = current.copy(input = null, snapshot = null)
            publishedState.set(IdeAnalysisState.Loading(current.path, current.documentRevision))
        }
        inputLoader.load(project).whenComplete { input, failure ->
            if (failure == null && input != null) {
                acceptInput(expectedVersion, input)
            } else {
                unavailable(expectedVersion, failure?.message ?: "failed to reload analysis input")
            }
        }
    }

    fun manualCompletion() {
        val request: CompletionRequest?
        synchronized(lock) {
            check(!closed) { "analysis coordinator is closed" }
            val current = session ?: return
            request = current.snapshot?.let { CompletionRequest(current.path, current.caretOffsetUtf16) }
            session = current.copy(pendingCompletion = if (request == null) PendingCompletion.Manual else null)
        }
        request?.let { requests.manualCompletion(it.path, it.offsetUtf16) }
    }

    fun moveCompletion(delta: Int) = updateCompletion { it.move(delta) }

    fun moveCompletionPage(
        pages: Int,
        pageSize: Int,
    ) = updateCompletion { it.movePage(pages, pageSize) }

    fun focusLost() = dismissCompletion()

    fun dismissCompletion() = updateCompletion { null }

    fun acceptCompletion(
        document: EditorDocument,
        path: VirtualSourcePath,
    ): IdeCompletionAcceptance? {
        val state = publishedState.get() as? IdeAnalysisState.Active ?: return null
        val completion = state.completion ?: return null
        val current = synchronized(lock) { session }
        if (
            current == null || current.path != path || current.snapshot?.identity != state.identity ||
            current.documentRevision != document.revision || !document.contentEquals(current.text)
        ) {
            dismissCompletion()
            return IdeCompletionAcceptance.Stale
        }
        val result = completion.accept(document, state.identity, path)
        if (result is IdeCompletionAcceptance.Applied) {
            dismissCompletion()
            sourceChanged(
                current.project,
                current.path,
                document.materialize(),
                document.revision,
                insertedText = null,
                caretOffsetUtf16 = document.caretOffset,
                change = result.edit.change,
            )
        }
        return result
    }

    fun closeFile() {
        synchronized(lock) {
            if (closed) return
            version = Math.incrementExact(version)
            session = null
            publishedState.set(IdeAnalysisState.Idle)
        }
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
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
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
        val current = synchronized(lock) { session?.takeIf { !closed && version == expectedVersion && it.input === input } } ?: return
        val snapshot =
            try {
                snapshotFactory.create(input, current.path, current.text).also { validateSnapshot(it, current) }
            } catch (failure: Throwable) {
                unavailable(expectedVersion, failure.message ?: "invalid analysis snapshot")
                return
            }
        val completion: PendingCompletion?
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
            requests.sourceChanged(snapshot)
        }
        when (completion) {
            PendingCompletion.Automatic -> requests.automaticCompletion(current.path, current.caretOffsetUtf16)
            PendingCompletion.Manual -> requests.manualCompletion(current.path, current.caretOffsetUtf16)
            null -> Unit
        }
    }

    private fun publishSuccess(
        current: Session,
        snapshot: AdmittedAnalysisSnapshot,
        result: AnalysisResult,
    ) {
        if (result.identity != snapshot.identity) return
        when (result) {
            is AnalysisResult.Presentation -> {
                val accepted = result.value.accept(snapshot.identity) as? SnapshotPresentationAcceptance.Active ?: return
                val priorCompletion = (publishedState.get() as? IdeAnalysisState.Active)?.completion
                publishedState.set(
                    IdeAnalysisState.Active(
                        snapshot.identity,
                        current.path,
                        current.documentRevision,
                        IdeAnalysisPresentation.of(accepted.diagnostics, accepted.semanticTokens),
                        priorCompletion,
                    ),
                )
            }

            is AnalysisResult.Completion -> {
                if (result.items.isEmpty() || !validRange(current.text, result.replacement.startUtf16, result.replacement.endUtf16)) return
                val prior = publishedState.get() as? IdeAnalysisState.Active ?: return
                publishedState.set(
                    prior.copy(
                        completion =
                            IdeCompletionState.create(
                                snapshot.identity,
                                current.path,
                                result.replacement,
                                result.items,
                            ),
                    ),
                )
            }

            is AnalysisResult.Declaration,
            is AnalysisResult.ExpressionInfo,
            is AnalysisResult.References,
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

    private fun unavailable(
        expectedVersion: Long,
        detail: String,
    ) {
        synchronized(lock) {
            val current = session ?: return
            if (closed || version != expectedVersion) return
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
    )

    private data class Rebuild(
        val version: Long,
        val session: Session,
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

private fun caretBoundary(
    text: String,
    offset: Int,
): Boolean {
    if (offset == 0 || offset == text.length) return true
    if (text[offset - 1] == '\r' && text[offset] == '\n') return false
    return !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
}

private fun boundedDetail(value: String): String = value.takeIf(String::isNotBlank)?.take(4096) ?: "analysis failed"
