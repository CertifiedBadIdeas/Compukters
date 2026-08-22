/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.compiler.worker.integration

import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerPolicy
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadManifest
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForkedWorkerRecoveryTest {
    @Test
    fun `hostile processes are classified and a later process succeeds`() {
        scenario("WRONG_ID", PlatformFailureClass.PROTOCOL)
        scenario("TRUNCATED", PlatformFailureClass.PROTOCOL)
        scenario("OVERSIZED", PlatformFailureClass.PROTOCOL)
        scenario("SLEEP", PlatformFailureClass.TIMEOUT, compilationTimeoutNanos = 100_000_000)
        scenario("STDERR", PlatformFailureClass.WORKER_EXIT)
        scenario("OOM", PlatformFailureClass.MEMORY_LIMIT)
    }

    @Test
    fun `duplicate terminal poisons the worker before the next request`() {
        withController { controller ->
            assertIs<CompileSuccess>(compile(controller, "DUPLICATE"))
            val duplicate = assertIs<PlatformFailure>(compile(controller, "SUCCESS"))
            assertEquals(PlatformFailureClass.PROTOCOL, duplicate.failureClass)
            assertIs<CompileSuccess>(compile(controller, "SUCCESS"))
        }
    }

    private fun scenario(
        source: String,
        expected: PlatformFailureClass,
        compilationTimeoutNanos: Long = 5_000_000_000,
    ) {
        withController(compilationTimeoutNanos) { controller ->
            val failure = assertIs<PlatformFailure>(compile(controller, source))
            assertEquals(expected, failure.failureClass, failure.detail)
            assertTrue(failure.detail.encodeToByteArray().size <= WorkerLimits().diagnosticTextBytes)
            val recovered = compile(controller, "SUCCESS")
            assertTrue(recovered is CompileSuccess, "$source recovery returned $recovered")
        }
    }

    private fun withController(
        compilationTimeoutNanos: Long = 5_000_000_000,
        block: (CompilerWorkerController) -> Unit,
    ) {
        val root = createTempDirectory("compukters-hostile-worker-")
        val limits = WorkerLimits()
        val payload = hostilePayload()
        val launch =
            WorkerLaunch(
                Path.of(checkNotNull(System.getProperty("compukters.worker.java"))),
                32,
                128,
                root.resolve("child"),
                payload.manifest.identity,
                limits.frameBytes,
                limits.stderrBytes,
            )
        try {
            CompilerWorkerController(
                payload,
                launch,
                limits,
                JdkWorkerProcessFactory(),
                CompilerWorkerPolicy(compilationTimeoutNanos = compilationTimeoutNanos, terminationGraceMillis = 50),
            ).use { controller ->
                block(controller)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun compile(
        controller: CompilerWorkerController,
        source: String,
    ) = controller.compile(BinaryValue.of(source.encodeToByteArray())).get(15, TimeUnit.SECONDS)

    private fun hostilePayload(): PublishedWorkerPayload {
        val classpath = checkNotNull(System.getProperty("compukters.worker.test-classpath")).split(File.pathSeparator).map(Path::of)
        val manifest =
            WorkerPayloadManifest(
                hostileIdentity(),
                "ru.lazyhat.compukters.compiler.worker.integration.HostileWorkerMainKt",
                emptyList(),
                hostileIdentity().payloadHash,
            )
        return PublishedWorkerPayload(Path.of("hostile-test-payload"), manifest, classpath)
    }
}
