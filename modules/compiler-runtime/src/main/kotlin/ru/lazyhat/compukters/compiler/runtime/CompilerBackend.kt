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
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import java.util.concurrent.CompletableFuture

interface CompilerBackend : AutoCloseable {
    fun compile(snapshot: ProjectSnapshot): CompletableFuture<CompileResult>

    fun cancel(future: CompletableFuture<CompileResult>) {
        future.cancel(false)
    }

    override fun close() = Unit
}
