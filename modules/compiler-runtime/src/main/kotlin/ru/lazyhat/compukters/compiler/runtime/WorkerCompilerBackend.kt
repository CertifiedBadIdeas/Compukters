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

package ru.lazyhat.compukters.compiler.runtime

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import java.util.concurrent.CompletableFuture

class WorkerCompilerBackend(
    private val controller: CompilerWorkerController,
    private val target: TargetSettings = TargetSettings.KOTLIN_2_4_JVM_17,
    trustedApiBundles: List<TrustedBundleIdentity> = emptyList(),
    trustedAddonBundles: List<TrustedBundleIdentity> = emptyList(),
) : CompilerBackend {
    private val trustedApiBundles = trustedApiBundles.toList()
    private val trustedAddonBundles = trustedAddonBundles.toList()

    override fun compile(snapshot: ProjectSnapshot): CompletableFuture<CompileResult> =
        controller.compile(snapshot, target, trustedApiBundles, trustedAddonBundles)

    override fun cancel(future: CompletableFuture<CompileResult>) {
        controller.cancel(future)
    }

    override fun close() = controller.close()
}
