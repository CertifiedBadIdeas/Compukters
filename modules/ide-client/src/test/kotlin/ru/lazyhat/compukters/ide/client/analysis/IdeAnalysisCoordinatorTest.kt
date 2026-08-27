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
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentation
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisResultSink
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectHandle
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeAnalysisCoordinatorTest {
    @Test
    fun `current presentation overrides lexical style and admits diagnostics`() {
        val fixture = fixture("val answer = 42")
        val active = fixture.open()
        val token = SemanticToken(path(), EditorRange(4, 10), SemanticCategory.LocalVariable)
        val diagnostic = EditorDiagnostic(EditorDiagnosticSeverity.Warning, "unused", path(), EditorRange(4, 10))
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    active.identity,
                    SnapshotPresentation.create(
                        active.identity,
                        mapOf(path() to fixture.text.length),
                        diagnostics = listOf(diagnostic),
                        semanticTokens = listOf(token),
                    ),
                ),
            ),
        )

        val state = assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
        assertEquals(listOf(diagnostic), state.presentation.diagnostics)
        assertEquals(
            IdeHighlightStyle.Semantic(SemanticCategory.LocalVariable),
            state.presentation.styleAt(path(), 5, KotlinLexicalKind.Identifier),
        )
        assertEquals(
            IdeHighlightStyle.Lexical(KotlinLexicalKind.Keyword),
            state.presentation.styleAt(path(), 0, KotlinLexicalKind.Keyword),
        )
    }

    @Test
    fun `stale source and profile results cannot replace current presentation`() {
        val fixture = fixture("val answer = 42")
        val active = fixture.open()
        val staleSource = active.identity.copy(source = SourceSnapshotIdentity.of(source("val stale = 0")))
        val staleProfile = active.identity.copy(profile = AnalysisProfileIdentity(hash(99)))

        listOf(staleSource, staleProfile).forEach { identity ->
            fixture.publish(
                AnalysisClientResult.Success(
                    AnalysisResult.Presentation(
                        identity,
                        SnapshotPresentation.create(identity, mapOf(path() to fixture.text.length)),
                    ),
                ),
            )
        }

        val state = assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
        assertEquals(active.identity, state.identity)
        assertTrue(state.presentation.semanticTokens.isEmpty())
    }

    @Test
    fun `identifier and dot edits trigger automatic completion while manual completion is immediate`() {
        val fixture = fixture("val value = foo")
        fixture.open()

        fixture.coordinator.sourceChanged(fixture.project, path(), "val value = foo.", 1, ".")
        fixture.coordinator.sourceChanged(fixture.project, path(), "val value = foo.b", 2, "b")
        fixture.coordinator.sourceChanged(fixture.project, path(), "val value = foo.b ", 3, " ")
        fixture.coordinator.manualCompletion()

        assertEquals(listOf(16, 17), fixture.requests.automaticOffsets)
        assertEquals(listOf(18), fixture.requests.manualOffsets)

        fixture.coordinator.reload()
        assertEquals(listOf(18), fixture.requests.manualOffsets)
    }

    @Test
    fun `completion closes on focus and file changes while failures preserve lexical fallback`() {
        val fixture = fixture("pr")
        val active = fixture.open()
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(0, 2),
                    listOf(CompletionItem("println", "println", CompletionKind.Function)),
                ),
            ),
        )
        assertIs<IdeCompletionState>(assertIs<IdeAnalysisState.Active>(fixture.coordinator.state()).completion)

        fixture.coordinator.focusLost()
        assertNull(assertIs<IdeAnalysisState.Active>(fixture.coordinator.state()).completion)

        fixture.publish(AnalysisClientResult.Failure(AnalysisFailureKind.InternalAnalysis, "boom"))
        val unavailable = assertIs<IdeAnalysisState.Unavailable>(fixture.coordinator.state())
        assertEquals("Analysis unavailable", unavailable.status)

        fixture.coordinator.closeFile()
        assertIs<IdeAnalysisState.Idle>(fixture.coordinator.state())
        fixture.publish(AnalysisClientResult.Stale)
        assertIs<IdeAnalysisState.Idle>(fixture.coordinator.state())
    }

    @Test
    fun `an edit made while project input loads becomes the only admitted snapshot`() {
        val fixture = AnalysisFixture("val old = 1", deferredInput = true)
        fixture.coordinator.open(fixture.project, path(), fixture.text, 0)

        fixture.coordinator.sourceChanged(fixture.project, path(), "val newest = 2", 1, "2")
        fixture.completeInput()

        val snapshot = fixture.requests.snapshots.single()
        assertEquals(
            "val newest = 2",
            snapshot.sources.sources
                .single()
                .content
                .toByteArray()
                .decodeToString(),
        )
        assertEquals(listOf("val newest = 2".length), fixture.requests.automaticOffsets)
    }

    @Test
    fun `completion admission rejects a document revision not represented by its snapshot`() {
        val fixture = fixture("pr")
        val active = fixture.open()
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(0, 2),
                    listOf(CompletionItem("println", "println", CompletionKind.Function)),
                ),
            ),
        )
        val document = EditorDocument("pr")
        document.type("x")

        assertIs<IdeCompletionAcceptance.Stale>(fixture.coordinator.acceptCompletion(document, path()))
        assertEquals("xpr", document.materialize())
    }

    @Test
    fun `reload obtains fresh project input without closing the active editor`() {
        val fixture = fixture("val answer = 42")
        fixture.open()

        fixture.coordinator.reload()

        assertEquals(2, fixture.requests.snapshots.size)
        assertIs<IdeAnalysisState.Active>(fixture.coordinator.state())
    }

    @Test
    fun `failed replacement snapshot cannot revive presentation from the previous source`() {
        val fixture = AnalysisFixture("val old = 1", rejectedText = "val broken =")
        val old = fixture.open()

        fixture.coordinator.sourceChanged(fixture.project, path(), "val broken =", 1, "=")
        assertIs<IdeAnalysisState.Unavailable>(fixture.coordinator.state())
        fixture.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Presentation(
                    old.identity,
                    SnapshotPresentation.create(old.identity, mapOf(path() to "val old = 1".length)),
                ),
            ),
        )

        assertIs<IdeAnalysisState.Unavailable>(fixture.coordinator.state())
    }

    private fun fixture(text: String) = AnalysisFixture(text)

    private fun source(text: String) =
        ProjectSnapshot.of(listOf(ProjectSource(path(), BinaryValue.of(text.encodeToByteArray()))), ANALYSIS_LIMITS)
}

private class AnalysisFixture(
    initialText: String,
    private val deferredInput: Boolean = false,
    private val rejectedText: String? = null,
) {
    var text = initialText
    val descriptor = ProjectCatalog.open(createTempDirectory("compukters-analysis-")).create("demo")
    val project: ProjectHandle = descriptor.handle
    val requests = RecordingRequests()
    private val profile = AnalysisProfileIdentity(hash(2))
    private val inputFuture = CompletableFuture<IdeBuildInput>()
    val coordinator =
        IdeAnalysisCoordinator(
            inputLoader =
                IdeAnalysisInputLoader {
                    if (deferredInput) inputFuture else CompletableFuture.completedFuture(input())
                },
            snapshotFactory =
                IdeAnalysisSnapshotFactory { input, activePath, activeText ->
                    require(activeText != rejectedText) { "rejected analysis source" }
                    snapshot(input, activePath, activeText)
                },
            requestFactory = IdeAnalysisRequestFactory { sink -> requests.apply { this.sink = sink } },
        )

    fun open(): AdmittedAnalysisSnapshot {
        coordinator.open(project, path(), text, 0)
        return requests.snapshots.last()
    }

    fun publish(result: AnalysisClientResult) = requests.sink.publish(result)

    fun completeInput() {
        inputFuture.complete(input())
    }

    private fun input(): IdeBuildInput =
        IdeBuildInput(
            project,
            ProjectManifestCodec.encode(descriptor.manifest).encodeToByteArray(),
            null,
            source(text),
        )

    private fun snapshot(
        input: IdeBuildInput,
        activePath: VirtualSourcePath,
        activeText: String,
    ): AdmittedAnalysisSnapshot {
        text = activeText
        val sources =
            ProjectSnapshot.of(
                input.sources.sources.map { source ->
                    if (source.path == activePath) ProjectSource(activePath, BinaryValue.of(activeText.encodeToByteArray())) else source
                },
                ANALYSIS_LIMITS,
            )
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        return AdmittedAnalysisSnapshot(identity, sources, AdmittedAnalysisProfile(profile, emptyList()), AnalysisLimits())
    }
}

private class RecordingRequests : AnalysisRequestCoordinator {
    lateinit var sink: AnalysisResultSink
    val snapshots = mutableListOf<AdmittedAnalysisSnapshot>()
    val automaticOffsets = mutableListOf<Int>()
    val manualOffsets = mutableListOf<Int>()

    override fun sourceChanged(snapshot: AdmittedAnalysisSnapshot) {
        snapshots += snapshot
    }

    override fun automaticCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ) {
        automaticOffsets += offsetUtf16
    }

    override fun manualCompletion(
        path: VirtualSourcePath,
        offsetUtf16: Int,
    ): CompletableFuture<AnalysisClientResult> {
        manualOffsets += offsetUtf16
        return CompletableFuture()
    }

    override fun close() = Unit
}

private fun path() = VirtualSourcePath.kotlin("src/main.kt")

private fun source(text: String) =
    ProjectSnapshot.of(
        listOf(ProjectSource(path(), BinaryValue.of(text.encodeToByteArray()))),
        ANALYSIS_LIMITS,
    )

private fun hash(seed: Int) = Hash256.of(ByteArray(32) { seed.toByte() })

private val ANALYSIS_LIMITS = WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192)
