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

package ru.lazyhat.compukters.playground

import ru.lazyhat.compukters.compiler.project.ProjectSnapshotLoader
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerPolicy
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface PlaygroundCompiler : AutoCloseable {
    fun compile(project: Path): CompileResult
}

class ForkedPlaygroundCompiler(
    payloadRoot: Path,
    javaExecutable: Path,
    private val limits: WorkerLimits = WorkerLimits(),
    policy: CompilerWorkerPolicy =
        CompilerWorkerPolicy(
            startupTimeoutNanos = 30_000_000_000,
            compilationTimeoutNanos = 60_000_000_000,
        ),
) : PlaygroundCompiler {
    private val temporaryRoot = Files.createTempDirectory("compukters-playground-worker-")
    private val controller: CompilerWorkerController

    init {
        try {
            val payload = WorkerPayloadLoader.loadToolingProfile(payloadRoot)
            val launch =
                WorkerLaunch(
                    javaExecutable = javaExecutable,
                    maximumHeapMiB = 512,
                    maximumMetaspaceMiB = 256,
                    temporaryDirectory = temporaryRoot.resolve("child"),
                    expectedIdentity = payload.manifest.identity,
                    maximumFrameBytes = limits.frameBytes,
                    maximumStderrBytes = limits.stderrBytes,
                )
            controller = CompilerWorkerController(payload, launch, limits, JdkWorkerProcessFactory(), policy)
        } catch (exception: Exception) {
            temporaryRoot.toFile().deleteRecursively()
            throw exception
        }
    }

    override fun compile(project: Path): CompileResult =
        controller.compile(ProjectSnapshotLoader.load(project, limits)).get(CONTROLLER_WAIT_SECONDS, TimeUnit.SECONDS)

    override fun close() {
        try {
            controller.close()
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val CONTROLLER_WAIT_SECONDS = 45L
    }
}
