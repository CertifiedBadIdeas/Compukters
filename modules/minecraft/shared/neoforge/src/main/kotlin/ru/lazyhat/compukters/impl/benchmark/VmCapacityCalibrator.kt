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

package ru.lazyhat.compukters.impl.benchmark

import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEvent
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorProcessor
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorScheduler
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSubmission
import ru.lazyhat.compukters.core.device.runtime.actor.VmCapacityCalibration
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.minecraft.computer.HeadlessVmBenchmarkArtifact
import java.math.BigInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Samples the packaged VM workload through real actor workers, host sessions, and the active native transport. */
internal object VmCapacityCalibrator {
    fun start(workerCount: Int): CompletableFuture<VmCapacityCalibration> {
        require(workerCount > 0) { "calibration worker count must be positive" }
        val result = CompletableFuture<VmCapacityCalibration>()
        Thread
            .ofPlatform()
            .daemon(true)
            .name("compukters-vm-calibration")
            .start {
                try {
                    val integer = sample(workerCount, "1000000")
                    val allocation = sample(workerCount, "alloc")
                    val integerRate =
                        BigInteger
                            .valueOf(integer.retiredInstructions)
                            .multiply(BigInteger.valueOf(allocation.elapsedNanos))
                    val allocationRate =
                        BigInteger
                            .valueOf(allocation.retiredInstructions)
                            .multiply(BigInteger.valueOf(integer.elapsedNanos))
                    result.complete(if (integerRate <= allocationRate) integer else allocation)
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        return result
    }

    private fun sample(
        workerCount: Int,
        workload: String,
    ): VmCapacityCalibration {
        val artifact = HeadlessVmBenchmarkArtifact.packaged()
        val config =
            VmActorSchedulerConfig(
                workerCount = workerCount,
                maximumActors = workerCount,
                mailboxCapacity = 1,
                messagesPerTurn = 1,
                resultCapacityPerWorker = 2,
            )
        val hosts = ArrayList<ProgramRuntimeHost>(workerCount)
        try {
            VmActorScheduler<Int, Int, Long>(config).use { scheduler ->
                val endpoints = List(workerCount) { index -> VmActorEndpoint(ComputerId.fromLongs(0, index + 1L), 1) }
                endpoints.forEach { endpoint ->
                    val host = ProgramRuntimeHost(ProgramTickBudget(maximumAdvancesPerTick = 4))
                    hosts += host
                    check(host.start(artifact) == ProgramStartResult.Started) { "calibration VM failed to start" }
                    check(host.sendTerminalText(workload)) { "calibration input was rejected" }
                    check(scheduler.register(endpoint, CalibrationProcessor(host, workload))) { "calibration worker admission failed" }
                }
                val startedAt = System.nanoTime()
                val deadline = startedAt + SAMPLE_NANOS
                endpoints.forEach { endpoint ->
                    check(scheduler.submitWithPermit(endpoint, emptyList(), 1) == VmActorSubmission.ACCEPTED)
                }
                var outstanding = workerCount
                var retired = 0L
                while (outstanding > 0) {
                    val events = scheduler.drainEvents(workerCount)
                    if (events.isEmpty()) {
                        check(System.nanoTime() - deadline < MAXIMUM_OVERRUN_NANOS) { "calibration timed out" }
                        Thread.sleep(1)
                        continue
                    }
                    events.forEach { event ->
                        when (event) {
                            is VmActorEvent.Failed -> {
                                throw IllegalStateException("calibration actor failed", event.cause)
                            }

                            is VmActorEvent.Result -> {
                                retired = Math.addExact(retired, event.value)
                                if (System.nanoTime() - deadline < 0) {
                                    check(scheduler.submitWithPermit(event.endpoint, emptyList(), 1) == VmActorSubmission.ACCEPTED)
                                } else {
                                    outstanding--
                                }
                            }
                        }
                    }
                }
                return VmCapacityCalibration(retired, (System.nanoTime() - startedAt).coerceAtLeast(1), workerCount)
            }
        } finally {
            hosts.forEach(ProgramRuntimeHost::close)
        }
    }

    private class CalibrationProcessor(
        private val host: ProgramRuntimeHost,
        private val workload: String,
    ) : VmActorProcessor<Int, Int, Long> {
        private var tick = 0L

        override fun process(command: Int): Long? = null

        override fun advance(permit: Int): Long {
            val state = host.serverTick(tick++, retirementAllowance = 8_192)
            if (state == ProgramRuntimeState.WaitingForInput) {
                check(host.sendTerminalText(workload)) { "calibration VM did not resume" }
            }
            check(state == ProgramRuntimeState.Running || state == ProgramRuntimeState.WaitingForInput) {
                "calibration VM stopped: $state"
            }
            return host.retiredInstructionsLastTick
        }
    }

    private const val SAMPLE_NANOS = 250_000_000L
    private val MAXIMUM_OVERRUN_NANOS = TimeUnit.SECONDS.toNanos(2)
}
