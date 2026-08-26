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

package ru.lazyhat.compukters.ide.analysis.controller

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.ANALYSIS_PROTOCOL_VERSION
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisCancelled
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFeature
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisHandshake
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQuerySuccess
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.CancelAnalysisRequest
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReady
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalysisWorkerControllerTest {
    @Test
    fun `worker starts lazily opens once and returns a correlated result`() =
        withController(1) { controller, factory, processes, workerIdentity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(workerIdentity, limits))
            val snapshot = admitted("val answer = 42", limits)

            val opened = controller.open(snapshot)
            val openRequest = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
            worker.enqueue(SnapshotReady(openRequest.requestId, snapshot.identity))
            assertEquals(SnapshotOpenResult.Opened(snapshot.identity), opened.get(5, TimeUnit.SECONDS))

            val query = AnalysisQuery.Completion(snapshot.identity, path(), 6, CompletionTrigger.Manual)
            val result = controller.query(query)
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            val completion = AnalysisResult.Completion.create(snapshot.identity, EditorRange(4, 6), emptyList())
            worker.enqueue(AnalysisQuerySuccess(request.requestId, completion))

            assertEquals(AnalysisClientResult.Success(completion), result.get(5, TimeUnit.SECONDS))
            assertEquals(1, factory.starts.size)
        }

    @Test
    fun `active cancellation writes cancel then terminates and completes once`() =
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val worker = processes.single()
            val snapshot = open(controller, worker, workerIdentity, limits)
            val future = controller.query(AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())

            assertTrue(controller.cancel(future))

            assertEquals(request.requestId, assertIs<CancelAnalysisRequest>(worker.awaitWrite()).requestId)
            assertEquals(AnalysisClientResult.Cancelled, future.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(37L), worker.terminationGraces)
        }

    @Test
    fun `timeout invalidates process and next query restarts and reopens retained snapshot`() =
        withController(2) { controller, factory, processes, workerIdentity, limits ->
            val snapshot = open(controller, processes[0], workerIdentity, limits)
            val first = controller.query(AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(processes[0].awaitWrite())
            processes[0].enqueueTimeout()
            assertIs<AnalysisClientResult.Failure>(first.get(5, TimeUnit.SECONDS)).also {
                assertEquals(ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind.Timeout, it.kind)
            }

            processes[1].enqueue(handshake(workerIdentity, limits))
            val secondQuery = AnalysisQuery.Declaration(snapshot.identity, path(), 3)
            val second = controller.query(secondQuery)
            val reopened = assertIs<OpenSnapshotRequest>(processes[1].awaitWrite())
            processes[1].enqueue(SnapshotReady(reopened.requestId, snapshot.identity))
            val request = assertIs<AnalysisQueryRequest>(processes[1].awaitWrite())
            val declaration = AnalysisResult.Declaration.create(snapshot.identity, emptyList(), mapOf(path() to 15))
            processes[1].enqueue(AnalysisQuerySuccess(request.requestId, declaration))

            assertEquals(AnalysisClientResult.Success(declaration), second.get(5, TimeUnit.SECONDS))
            assertEquals(2, factory.starts.size)
        }

    @Test
    fun `malformed output and worker EOF have distinct recoverable failures`() {
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val snapshot = open(controller, processes[0], workerIdentity, limits)
            val result = controller.query(AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(processes[0].awaitWrite())
            processes[0].enqueueMalformed()

            assertEquals(
                ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind.Protocol,
                assertIs<AnalysisClientResult.Failure>(result.get(5, TimeUnit.SECONDS)).kind,
            )
            assertEquals(listOf(37L), processes[0].terminationGraces)
        }

        withController(1) { controller, _, processes, workerIdentity, limits ->
            val snapshot = open(controller, processes[0], workerIdentity, limits)
            val result = controller.query(AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(processes[0].awaitWrite())
            processes[0].enqueueEof()

            assertEquals(
                ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind.WorkerExit,
                assertIs<AnalysisClientResult.Failure>(result.get(5, TimeUnit.SECONDS)).kind,
            )
            assertEquals(listOf(37L), processes[0].terminationGraces)
        }
    }

    @Test
    fun `worker failure detail is bounded by encoded bytes without splitting text`() =
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val worker = processes[0]
            val snapshot = open(controller, worker, workerIdentity, limits)
            val result = controller.query(AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            worker.stderr = ("x" + "💥".repeat(3_000)).encodeToByteArray()
            worker.enqueueEof()

            val failure = assertIs<AnalysisClientResult.Failure>(result.get(5, TimeUnit.SECONDS))

            assertTrue(failure.detail.encodeToByteArray().size <= 4_096)
            assertTrue(failure.detail.codePoints().noneMatch { it in 0xD800..0xDFFF })
        }

    @Test
    fun `opening a new snapshot makes active and queued old work stale`() =
        withController(2) { controller, _, processes, workerIdentity, limits ->
            val first = open(controller, processes[0], workerIdentity, limits)
            val active = controller.query(AnalysisQuery.ExpressionInfo(first.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(processes[0].awaitWrite())
            val queued = controller.query(AnalysisQuery.Presentation(first.identity))

            processes[1].enqueue(handshake(workerIdentity, limits))
            val second = admitted("val changed = true", limits)
            val opening = controller.open(second)

            assertEquals(AnalysisClientResult.Stale, active.get(5, TimeUnit.SECONDS))
            assertEquals(AnalysisClientResult.Stale, queued.get(5, TimeUnit.SECONDS))
            val openRequest = assertIs<OpenSnapshotRequest>(processes[1].awaitWrite())
            processes[1].enqueue(SnapshotReady(openRequest.requestId, second.identity))
            assertEquals(SnapshotOpenResult.Opened(second.identity), opening.get(5, TimeUnit.SECONDS))
        }

    private fun open(
        controller: AnalysisWorkerController,
        worker: FakeAnalysisWorkerProcess,
        workerIdentity: AnalysisWorkerIdentity,
        limits: AnalysisLimits,
    ): AdmittedAnalysisSnapshot {
        worker.enqueue(handshake(workerIdentity, limits))
        val snapshot = admitted("val answer = 42", limits)
        val future = controller.open(snapshot)
        val request = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
        worker.enqueue(SnapshotReady(request.requestId, snapshot.identity))
        assertIs<SnapshotOpenResult.Opened>(future.get(5, TimeUnit.SECONDS))
        return snapshot
    }

    private fun withController(
        processCount: Int,
        block: (
            AnalysisWorkerController,
            FakeAnalysisWorkerProcessFactory,
            List<FakeAnalysisWorkerProcess>,
            AnalysisWorkerIdentity,
            AnalysisLimits,
        ) -> Unit,
    ) {
        val processes = List(processCount) { FakeAnalysisWorkerProcess() }
        val factory = FakeAnalysisWorkerProcessFactory(processes)
        val identity = AnalysisWorkerIdentity("2.4.10", "2.4", hash(8))
        val limits = AnalysisLimits()
        val launch = WorkerLaunch(Path.of("java"), listOf(Path.of("worker.jar")), "example.AnalysisMain", 256, 128, Path.of("tmp"))
        val policy = AnalysisWorkerPolicy(startupTimeoutNanos = 11, requestTimeoutNanos = 23, terminationGraceMillis = 37)
        AnalysisWorkerController(launch, identity, limits, factory, policy) { 100 }.use { controller ->
            block(controller, factory, processes, identity, limits)
        }
    }

    private fun admitted(
        text: String,
        limits: AnalysisLimits,
    ): AdmittedAnalysisSnapshot {
        val sources =
            ProjectSnapshot.of(
                listOf(ProjectSource(path(), BinaryValue.of(text.encodeToByteArray()))),
                WorkerLimits(),
            )
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), AnalysisProfileIdentity(hash(text.length)))
        return AdmittedAnalysisSnapshot(identity, sources, AdmittedAnalysisProfile(identity.profile, emptyList()), limits)
    }

    private fun handshake(
        identity: AnalysisWorkerIdentity,
        limits: AnalysisLimits,
    ) = AnalysisHandshake(ANALYSIS_PROTOCOL_VERSION, identity, AnalysisFeature.entries.toSet(), limits)

    private fun path() = VirtualSourcePath.kotlin("main.kt")

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}
