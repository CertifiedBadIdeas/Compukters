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

package ru.lazyhat.compukters.core.device.runtime.compiler

import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource

/** Admission remains reserved until the consumer takes or cancels a completion, not just until compilation finishes. */
class CompilerCompletionRouter(
    private val compiler: ComputerCompiler,
    private val maximumCompletionsPerDrain: Int = 32,
    private val maximumPendingCompilations: Int = 64,
    private val maximumCompletionBytes: Long = 64L * 1024 * 1024,
) {
    private val active = mutableSetOf<ComputerCompilationAddress>()
    private val routed = mutableMapOf<ComputerCompilationAddress, ComputerCompilationOutcome>()
    private var retainedBytes = 0L

    init {
        require(maximumCompletionsPerDrain > 0) { "compiler completion drain limit must be positive" }
        require(maximumPendingCompilations > 0 && maximumCompletionBytes >= maximumPendingCompilations * RESERVATION_BYTES)
    }

    @Synchronized
    fun submit(
        address: ComputerCompilationAddress,
        sources: List<VmCompilationSource>,
    ): CompilerSubmissionResult {
        if (address in active || address in routed || active.size + routed.size >= maximumPendingCompilations ||
            RESERVATION_BYTES > maximumCompletionBytes - retainedBytes
        ) {
            return CompilerSubmissionResult.BUSY
        }
        val result = compiler.submit(ComputerCompilationRequest(address, sources))
        if (result == CompilerSubmissionResult.ACCEPTED) {
            active += address
            retainedBytes += RESERVATION_BYTES
        }
        return result
    }

    @Synchronized
    fun routeCompletions(maximum: Int = maximumCompletionsPerDrain) {
        require(maximum in 0..maximumCompletionsPerDrain)
        repeat(maximum) {
            // At most one transient converted artifact, rather than a list of maximum-sized copies.
            val completion = compiler.drain(1).singleOrNull() ?: return
            if (!active.remove(completion.address)) return@repeat
            retainedBytes -= RESERVATION_BYTES
            val outcome =
                if (charge(completion.outcome) <= maximumCompletionBytes - retainedBytes) {
                    completion.outcome
                } else {
                    ComputerCompilationOutcome.Failure("compiler result exceeds completion memory limit")
                }
            retainedBytes += charge(outcome)
            routed[completion.address] = outcome
        }
    }

    @Synchronized
    internal fun peek(address: ComputerCompilationAddress): ComputerCompilationOutcome? = routed[address]

    @Synchronized
    fun take(address: ComputerCompilationAddress): ComputerCompilationOutcome? =
        routed.remove(address)?.also { retainedBytes -= charge(it) }

    @Synchronized
    fun cancel(address: ComputerCompilationAddress): Boolean {
        val wasActive = active.remove(address)
        if (wasActive) retainedBytes -= RESERVATION_BYTES
        val wasRouted = take(address) != null
        val compilerCancelled = compiler.cancel(address)
        return wasActive || wasRouted || compilerCancelled
    }

    private fun charge(outcome: ComputerCompilationOutcome): Long =
        maxOf(
            RESERVATION_BYTES,
            64L +
                when (outcome) {
                    is ComputerCompilationOutcome.Success -> outcome.artifactSize.toLong()
                    is ComputerCompilationOutcome.Failure -> 2L * outcome.diagnostics.length
                },
        )

    private companion object {
        const val RESERVATION_BYTES = 256L
    }
}
