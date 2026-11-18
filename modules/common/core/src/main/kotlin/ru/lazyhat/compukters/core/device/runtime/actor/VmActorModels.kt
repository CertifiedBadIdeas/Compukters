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

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.fs.ComputerId

data class VmActorEndpoint(
    val computerId: ComputerId,
    val epoch: Long,
) {
    init {
        require(epoch > 0) { "VM actor epoch must be positive" }
    }
}

enum class VmActorSubmission {
    ACCEPTED,
    MAILBOX_FULL,
    PERMIT_PENDING,
    STALE_ENDPOINT,
    CLOSED,
}

data class VmActorSchedulerConfig(
    val workerCount: Int = defaultWorkerCount(),
    val maximumActors: Int = DEFAULT_MAXIMUM_ACTORS,
    val mailboxCapacity: Int = 64,
    val resultCapacityPerWorker: Int = 256,
    val shutdownTimeoutMillis: Long = 5_000,
) {
    init {
        require(workerCount > 0) { "worker count must be positive" }
        require(maximumActors > 0) { "maximum actors must be positive" }
        require(mailboxCapacity > 0) { "mailbox capacity must be positive" }
        require(resultCapacityPerWorker > 0) { "result capacity must be positive" }
        require(shutdownTimeoutMillis > 0) { "shutdown timeout must be positive" }
    }

    companion object {
        const val DEFAULT_MAXIMUM_ACTORS = 4_096

        fun defaultWorkerCount(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int {
            require(availableProcessors > 0) { "available processor count must be positive" }
            return (availableProcessors / 2).coerceIn(MINIMUM_DEFAULT_WORKERS, MAXIMUM_DEFAULT_WORKERS)
        }

        private const val MINIMUM_DEFAULT_WORKERS = 2
        private const val MAXIMUM_DEFAULT_WORKERS = 8
    }
}

data class ProgramRuntimeActorMetrics(
    val pendingRequests: Int,
    val deferredWorldRequests: Int?,
    val totalDeferredWorldRequests: Long,
    val hostContinuationSamples: Long,
    val totalHostContinuationDelayTicks: Long,
    val maximumHostContinuationDelayTicks: Long,
    val rejectedInputRequests: Long,
    val coalescedRedstoneInputs: Long,
    val lastPumpEvents: Int,
    val lastPumpNanos: Long,
    val calibratedInstructionCapacity: Long = 0,
    val currentInstructionCapacity: Long = 0,
    val measuredCapacityWorkers: Int = 0,
    val capacityFallbackReason: String? = null,
    val runnableComputersLastFrame: Int = 0,
    val waitingComputersLastFrame: Int = 0,
    val throttledComputersLastFrame: Int = 0,
    val requestedInstructionsLastFrame: Long = 0,
    val reservedInstructionsLastFrame: Long = 0,
    val missedInstructionsLastFrame: Long = 0,
    val retiredInstructionsTotal: Long = 0,
    val unusedReservationsTotal: Long = 0,
    val countersSaturated: Boolean = false,
    val nativeActors: Int? = null,
    val nativeMaximumActors: Int? = null,
)
