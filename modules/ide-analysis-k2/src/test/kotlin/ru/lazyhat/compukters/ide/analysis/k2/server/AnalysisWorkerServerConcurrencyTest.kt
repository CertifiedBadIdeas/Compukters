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

package ru.lazyhat.compukters.ide.analysis.k2.server

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.k2.standalone.SnapshotAdmission
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailure
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisProtocolContext
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.CancelAnalysisRequest
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalysisWorkerServerConcurrencyTest {
    @Test
    fun `query is rejected before entering K2 when its snapshot is not active`() {
        val root = createTempDirectory("compukters-server-inactive-")
        val output = SignallingOutputStream()
        val invoked = AtomicBoolean()
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(projectSources()), AnalysisProfileIdentity(hash(2)))
        val limits = AnalysisLimits()
        val server =
            AnalysisWorkerServer(
                AnalysisWorkerIdentity("2.4.10", "2.4", hash(3)),
                limits,
                ByteArrayInputStream(ByteArray(0)),
                output,
                SnapshotAdmission(root, standardLibrary(), Path.of(System.getProperty("java.home"))),
                queryHandler =
                    AnalysisQueryHandler { request, _, _ ->
                        invoked.set(true)
                        error("handler must not run for an inactive snapshot: ${request.requestId}")
                    },
            )
        try {
            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(7uL), AnalysisQuery.Presentation(identity))))
            assertTrue(output.written.await(5, TimeUnit.SECONDS))

            val failure =
                assertIs<AnalysisFailure>(
                    AnalysisMessageCodec.decode(
                        AnalysisFrameCodec.decode(output.toByteArray(), limits.frameBytes),
                        AnalysisProtocolContext.unbound(limits),
                    ),
                )
            assertEquals(AnalysisFailureKind.InvalidSnapshot, failure.failure)
            assertFalse(invoked.get())
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `server dispatch accepts cancel while analysis handler is occupied`() {
        val root = createTempDirectory("compukters-server-concurrency-")
        val started = CountDownLatch(1)
        val observed = CompletableFuture<Unit>()
        val sources = projectSources()
        val profile = AnalysisProfileIdentity(hash(2))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        val server =
            AnalysisWorkerServer(
                AnalysisWorkerIdentity("2.4.10", "2.4", hash(3)),
                AnalysisLimits(),
                ByteArrayInputStream(ByteArray(0)),
                ByteArrayOutputStream(),
                SnapshotAdmission(root, standardLibrary(), Path.of(System.getProperty("java.home"))),
                queryHandler =
                    AnalysisQueryHandler { request, _, cancellation ->
                        started.countDown()
                        while (!cancellation.isCancelled) Thread.onSpinWait()
                        observed.complete(Unit)
                        AnalysisFailure(
                            request.requestId,
                            request.query.identity,
                            AnalysisFailureKind.Cancelled,
                            "cancelled",
                        )
                    },
            )
        try {
            assertTrue(
                server.accept(
                    OpenSnapshotRequest(
                        RequestId.of(6uL),
                        identity,
                        sources,
                        AdmittedAnalysisProfile(profile, emptyList()),
                        AnalysisLimits(),
                    ),
                ),
            )
            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(7uL), AnalysisQuery.Presentation(identity))))
            assertTrue(started.await(5, TimeUnit.SECONDS))

            assertTrue(server.accept(CancelAnalysisRequest(RequestId.of(7uL))))

            observed.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun standardLibrary(): Path =
        Path
            .of(
                Unit::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).toAbsolutePath()
            .normalize()

    private fun projectSources(): ProjectSnapshot =
        ProjectSnapshot.of(
            listOf(
                ProjectSource(
                    VirtualSourcePath.kotlin("main.kt"),
                    BinaryValue.of("val answer = 42".encodeToByteArray()),
                ),
            ),
            WorkerLimits(),
        )

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}

private class SignallingOutputStream : ByteArrayOutputStream() {
    val written = CountDownLatch(1)

    @Synchronized
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        super.write(bytes, offset, length)
        written.countDown()
    }
}
