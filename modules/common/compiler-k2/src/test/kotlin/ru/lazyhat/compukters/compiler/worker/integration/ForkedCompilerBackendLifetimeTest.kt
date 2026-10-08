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

package ru.lazyhat.compukters.compiler.worker.integration

import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.controller.WorkerProcess
import ru.lazyhat.compukters.compiler.worker.controller.WorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.compiler.ClientCompilerBackendLifetime
import ru.lazyhat.compukters.ide.compiler.ControllerClientCompilerBackend
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ForkedCompilerBackendLifetimeTest {
    @Test
    fun `changed sources reuse the real compiler process across closed IDE backend sessions`() {
        val payload = WorkerPayloadLoader.loadToolingProfile(Path.of(checkNotNull(System.getProperty("compukters.worker.payload"))))
        val root = createTempDirectory("compukters-compiler-lifetime-").toAbsolutePath().normalize()
        val limits = WorkerLimits()
        val processes = mutableListOf<WorkerProcess>()
        val processFactory = WorkerProcessFactory { launch -> JdkWorkerProcessFactory().start(launch).also(processes::add) }
        val owner =
            ClientCompilerBackendLifetime(TimeUnit.MINUTES.toNanos(2)) {
                ControllerClientCompilerBackend(
                    CompilerWorkerController(
                        payload,
                        WorkerLaunch(
                            Path.of(checkNotNull(System.getProperty("compukters.worker.java"))),
                            256,
                            256,
                            root.resolve("worker-temp"),
                            payload.manifest.identity,
                            limits.frameBytes,
                            limits.stderrBytes,
                        ),
                        limits,
                        processFactory,
                    ),
                )
            }

        fun compile(value: Int): CompileSuccess =
            owner.openSession().use { backend ->
                val request =
                    CompileRequest(
                        RequestId.of(value.toULong()),
                        listOf(
                            ProjectSource(
                                VirtualSourcePath.kotlin("src/main.kt"),
                                BinaryValue.of("fun main() { val answer: Int = $value }".encodeToByteArray()),
                            ),
                        ),
                        TargetSettings.KOTLIN_2_4_JVM_17,
                        payload.manifest.identity,
                        limits,
                    )
                assertIs<CompileSuccess>(backend.compile(request).get(60, TimeUnit.SECONDS))
            }
        try {
            val first = compile(42)
            assertTrue(processes.single().isAlive)
            val changed = compile(43)
            assertNotEquals(first.artifactHash, changed.artifactHash)
            val repeated = compile(42)
            assertEquals(first.artifactHash, repeated.artifactHash)
            assertEquals(1, processes.size)
            assertTrue(processes.single().isAlive)
            owner.close()
            assertFalse(processes.single().isAlive)
        } finally {
            owner.close()
            root.toFile().deleteRecursively()
        }
    }
}
