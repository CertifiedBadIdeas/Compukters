/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
    policy: CompilerWorkerPolicy = CompilerWorkerPolicy(),
) : PlaygroundCompiler {
    private val temporaryRoot = Files.createTempDirectory("compukters-playground-worker-")
    private val controller: CompilerWorkerController

    init {
        try {
            val payload = WorkerPayloadLoader.load(payloadRoot)
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
