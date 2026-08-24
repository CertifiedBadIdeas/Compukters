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

package ru.lazyhat.compukters.core.device.runtime.compiler

import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource

class CompilerCompletionRouter(
    private val compiler: ComputerCompiler,
    private val maximumCompletionsPerDrain: Int = 32,
) {
    private val active = mutableSetOf<ComputerCompilationAddress>()
    private val routed = mutableMapOf<ComputerCompilationAddress, ComputerCompilationOutcome>()

    init {
        require(maximumCompletionsPerDrain > 0) { "compiler completion drain limit must be positive" }
    }

    @Synchronized
    fun submit(
        address: ComputerCompilationAddress,
        sources: List<VmCompilationSource>,
    ): CompilerSubmissionResult {
        if (address in active || address in routed) return CompilerSubmissionResult.BUSY
        val result = compiler.submit(ComputerCompilationRequest(address, sources))
        if (result == CompilerSubmissionResult.ACCEPTED) active += address
        return result
    }

    @Synchronized
    fun routeCompletions() {
        compiler.drain(maximumCompletionsPerDrain).forEach { completion ->
            if (active.remove(completion.address)) routed[completion.address] = completion.outcome
        }
    }

    @Synchronized
    fun take(address: ComputerCompilationAddress): ComputerCompilationOutcome? = routed.remove(address)

    @Synchronized
    fun cancel(address: ComputerCompilationAddress): Boolean {
        val wasActive = active.remove(address)
        val wasRouted = routed.remove(address) != null
        val compilerCancelled = compiler.cancel(address)
        return wasActive || wasRouted || compilerCancelled
    }
}
