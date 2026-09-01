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
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisResultSink
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisCoordinator
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisInputLoader
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisRequestFactory
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisSnapshotFactory
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeAnalysisFlowTest {
    @Test
    fun `completion Tab is atomic while Tab without popup indents and manual has priority`() {
        val requests = FlowAnalysisRequests()
        val fixture =
            ControllerFixture(preferences("demo", "src/main.kt"), analysisCoordinatorFactory = { workspace ->
                coordinator(workspace, requests)
            })
        fixture.startAndTick()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("pr")))

        val active = assertIs<IdeAnalysisState.Active>(fixture.analysisCoordinator?.state())
        assertEquals(listOf(2), requests.automaticOffsets)
        requests.publish(
            AnalysisClientResult.Success(
                AnalysisResult.Completion.create(
                    active.identity,
                    EditorRange(0, 2),
                    listOf(CompletionItem("println", "println", CompletionKind.Function)),
                ),
            ),
        )
        fixture.controller.tick()
        assertTrue(assertIs<IdeAnalysisState.Active>(fixture.textEditor().analysis).completion != null)

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Tab))
        assertEquals("println", fixture.textEditor().visibleLines.single())
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Undo))
        assertEquals("pr", fixture.textEditor().visibleLines.single())

        fixture.controller.dispatch(IdeCommand.ManualCompletion)
        assertEquals(listOf(2), requests.manualOffsets)
        fixture.controller.dispatch(IdeCommand.EditorFocusLost)
        assertNull((fixture.textEditor().analysis as? IdeAnalysisState.Active)?.completion)

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Tab))
        assertEquals("pr  ", fixture.textEditor().visibleLines.single())
        fixture.controller.close()
    }

    private fun coordinator(
        workspace: ControlledWorkspace,
        requests: FlowAnalysisRequests,
    ) = IdeAnalysisCoordinator(
        IdeAnalysisInputLoader(workspace::buildInput),
        IdeAnalysisSnapshotFactory { input, path, text -> analysisSnapshot(input.sources, path, text) },
        IdeAnalysisRequestFactory { sink -> requests.apply { this.sink = sink } },
    )
}

private class FlowAnalysisRequests : AnalysisRequestCoordinator {
    lateinit var sink: AnalysisResultSink
    val automaticOffsets = mutableListOf<Int>()
    val manualOffsets = mutableListOf<Int>()

    fun publish(result: AnalysisClientResult) = sink.publish(result)

    override fun sourceChanged(
        snapshot: AdmittedAnalysisSnapshot,
        activePath: VirtualSourcePath,
    ) = Unit

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

private fun analysisSnapshot(
    original: ProjectSnapshot,
    path: VirtualSourcePath,
    text: String,
): AdmittedAnalysisSnapshot {
    val limits = WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192)
    val sources =
        ProjectSnapshot.of(
            original.sources.map { source ->
                if (source.path == path) ProjectSource(path, BinaryValue.of(text.encodeToByteArray())) else source
            },
            limits,
        )
    val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 3 }))
    val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
    return AdmittedAnalysisSnapshot(
        identity,
        sources,
        AdmittedAnalysisProfile(
            profile,
            ru.lazyhat.compukters.ide.analysis.protocol
                .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
        ),
        AnalysisLimits(),
    )
}
