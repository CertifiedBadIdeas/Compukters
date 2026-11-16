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

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.runtime.CompilerOutcome
import ru.lazyhat.compukters.compiler.runtime.CompilerServicePort
import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.compiler.runtime.CompilerTarget
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

class ServerComputerCompiler(
    private val service: CompilerServicePort,
    private val limits: WorkerLimits,
    private val maximumOutstanding: Int = 64,
) : ComputerCompiler {
    private val addresses = mutableMapOf<CompilerTarget, ComputerCompilationAddress>()
    private val targets = mutableMapOf<ComputerCompilationAddress, CompilerTarget>()
    private var nextOwner = 1L
    private val cancellations = ArrayDeque<CompilerTarget>()
    private var cancelling = 0
    private var cancellationWorker = false
    private var cancellationFailure: Throwable? = null
    private var cancellationIdle = CompletableFuture.completedFuture(Unit)

    init {
        require(maximumOutstanding > 0)
    }

    @Synchronized
    override fun submit(request: ComputerCompilationRequest): CompilerSubmissionResult {
        if (cancellationFailure != null) return CompilerSubmissionResult.CLOSED
        if (request.address in targets || targets.size + cancelling >= maximumOutstanding) return CompilerSubmissionResult.BUSY
        val target = CompilerTarget(nextOwner(), request.address.vmEpoch, request.address.token)
        val snapshot =
            ProjectSnapshot.of(
                request.sources.map { source ->
                    ProjectSource(
                        VirtualSourcePath.kotlin(source.path.removePrefix("/")),
                        BinaryValue.of(source.utf8Bytes()),
                    )
                },
                limits,
            )
        targets[request.address] = target
        addresses[target] = request.address
        return service.submit(target, snapshot).also { result ->
            if (result != CompilerSubmissionResult.ACCEPTED) remove(target, request.address)
        }
    }

    @Synchronized
    override fun drain(maximum: Int): List<ComputerCompilerCompletion> =
        service.drain(maximum).mapNotNull { completion ->
            val address = addresses.remove(completion.target) ?: return@mapNotNull null
            targets.remove(address)
            ComputerCompilerCompletion(address, completion.outcome.toComputerOutcome())
        }

    /** Invalidate delivery immediately; process termination may wait and therefore runs off the owner. */
    @Synchronized
    override fun cancel(address: ComputerCompilationAddress): Boolean {
        val target = targets.remove(address) ?: return false
        addresses.remove(target)
        cancellations.addLast(target)
        cancelling++ // Admission stays occupied until the potentially blocking process cancellation finishes.
        if (!cancellationWorker) {
            cancellationWorker = true
            cancellationIdle = CompletableFuture()
            Thread
                .ofPlatform()
                .daemon(true)
                .name("compukters-compiler-cancel")
                .start(::cancelPending)
        }
        return true
    }

    @Synchronized
    internal fun cancellationBarrier(): CompletableFuture<Unit> = cancellationIdle.copy()

    /** One transient worker per compiler, which exits when its bounded queue is empty. */
    private fun cancelPending() {
        while (true) {
            val target =
                synchronized(this) {
                    if (cancellations.isEmpty()) {
                        cancellationWorker = false
                        val failure = cancellationFailure
                        if (failure == null) cancellationIdle.complete(Unit) else cancellationIdle.completeExceptionally(failure)
                        return
                    }
                    cancellations.removeFirst()
                }
            val failure = runCatching { service.cancel(target) }.exceptionOrNull()
            synchronized(this) {
                cancelling--
                if (failure != null && cancellationFailure == null) cancellationFailure = failure
            }
        }
    }

    private fun nextOwner(): Long {
        val value = nextOwner
        check(value != 0L) { "compiler target ID space exhausted" }
        nextOwner = if (value == Long.MAX_VALUE) 0 else value + 1
        return value
    }

    private fun remove(
        target: CompilerTarget,
        address: ComputerCompilationAddress,
    ) {
        targets.remove(address)
        addresses.remove(target)
    }
}

private fun CompilerOutcome.toComputerOutcome(): ComputerCompilationOutcome =
    when (this) {
        is CompilerOutcome.Success -> {
            ComputerCompilationOutcome.Success(artifact.toByteArray())
        }

        is CompilerOutcome.Rejected -> {
            ComputerCompilationOutcome.Failure(
                when (val rejection = result) {
                    is CompilerFailure -> {
                        rejection.diagnostics.joinToString("\n") { diagnostic ->
                            listOfNotNull(diagnostic.path?.value, diagnostic.message).joinToString(": ")
                        }
                    }

                    is PlatformFailure -> {
                        rejection.detail
                    }

                    else -> {
                        "compiler rejected the request"
                    }
                }.boundedDiagnostics(),
            )
        }

        is CompilerOutcome.PlatformFailure -> {
            ComputerCompilationOutcome.Failure(detail.boundedDiagnostics())
        }

        CompilerOutcome.Busy -> {
            ComputerCompilationOutcome.Failure("compiler busy")
        }
    }

private fun String.boundedDiagnostics(): String {
    if (encodeToByteArray().size <= MAXIMUM_DIAGNOSTIC_BYTES) return this
    var end = length
    while (end > 0 && substring(0, end).encodeToByteArray().size > MAXIMUM_DIAGNOSTIC_BYTES) end--
    return substring(0, end)
}

private const val MAXIMUM_DIAGNOSTIC_BYTES = 64 * 1024
