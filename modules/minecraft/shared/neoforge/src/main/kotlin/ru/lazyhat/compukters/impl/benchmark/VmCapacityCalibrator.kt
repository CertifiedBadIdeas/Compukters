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

import ru.lazyhat.compukters.core.device.runtime.actor.NativeProgramRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.actor.VmCapacityCalibration
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.minecraft.computer.HeadlessVmBenchmarkArtifact
import java.math.BigInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Samples the native execution service on its own owner, without Minecraft tick work. */
internal object VmCapacityCalibrator {
    fun start(workerCount: Int): CompletableFuture<VmCapacityCalibration> {
        require(workerCount > 0) { "calibration worker count must be positive" }
        val result = CompletableFuture<VmCapacityCalibration>()
        Thread.ofPlatform().daemon(true).name("compukters-vm-calibration").start {
            try {
                val integer = sample(workerCount, "1000000")
                val allocation = sample(workerCount, "alloc")
                val integerRate = BigInteger.valueOf(integer.retiredInstructions).multiply(BigInteger.valueOf(allocation.elapsedNanos))
                val allocationRate = BigInteger.valueOf(allocation.retiredInstructions).multiply(BigInteger.valueOf(integer.elapsedNanos))
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
                resultCapacityPerWorker = 2,
            )
        val quotas = ProgramTickBudget(maximumAdvancesPerTick = 4)
        val capacity = workerCount * INSTRUCTIONS_PER_COMPUTER.toLong()
        val service: ProgramRuntimeService =
            NativeProgramRuntimeService(
                config,
                perComputerEntitlement = INSTRUCTIONS_PER_COMPUTER,
                safeInstructionCapacity = capacity,
                tickBudget = quotas,
            )
        service.use {
            val endpoints = List(workerCount) { VmActorEndpoint(ComputerId.fromLongs(0, it + 1L), 1) }

            fun <T> await(future: CompletableFuture<T>): T {
                val deadline = System.nanoTime() + MAXIMUM_OVERRUN_NANOS
                while (!future.isDone) {
                    check(System.nanoTime() - deadline < 0) { "calibration timed out" }
                    if (service.pump(workerCount * 4) == 0) LockSupport.parkNanos(50_000)
                }
                return future.join()
            }
            // Bootstrap admission is byte bounded; prepare one artifact at a time even on large pools.
            endpoints.forEach { endpoint ->
                checkNotNull(service.attachStandalone(endpoint, quotas)) { "calibration actor admission failed" }
                val started = await(service.request(endpoint) { ProgramRuntimeActorCommand.Start(it, artifact) })
                check(
                    (started.value as? ProgramRuntimeActorValue.Start)?.result == ProgramStartResult.Started,
                ) { "calibration VM failed to start" }
                sendInput(service, endpoint, workload, ::await)
            }
            val startedAt = System.nanoTime()
            val deadline = startedAt + SAMPLE_NANOS
            var tick = 0L
            var replies = emptyList<Pair<VmActorEndpoint, ProgramRuntimeActorReply>>()
            do {
                replies.forEach { (endpoint, reply) ->
                    check(reply.state == ProgramRuntimeState.Running || reply.state == ProgramRuntimeState.WaitingForInput) {
                        "calibration VM stopped: ${reply.state}"
                    }
                    if (reply.state == ProgramRuntimeState.WaitingForInput) sendInput(service, endpoint, workload, ::await)
                }
                service.beginCapacityFrame(++tick)
                val pending = endpoints.map { it to service.turn(it, tick) }
                service.flushCapacityFrame()
                await(CompletableFuture.allOf(*pending.map { it.second }.toTypedArray()))
                replies = pending.map { it.first to it.second.join() }
            } while (System.nanoTime() - deadline < 0)
            return VmCapacityCalibration(
                service.runtimeMetrics().retiredInstructionsTotal,
                (System.nanoTime() - startedAt).coerceAtLeast(1),
                workerCount,
            )
        }
    }

    private fun sendInput(
        service: ProgramRuntimeService,
        endpoint: VmActorEndpoint,
        workload: String,
        await: (CompletableFuture<ProgramRuntimeActorReply>) -> ProgramRuntimeActorReply,
    ) {
        val reply = await(service.request(endpoint) { ProgramRuntimeActorCommand.SendTerminalText(it, workload) })
        check((reply.value as? ProgramRuntimeActorValue.Accepted)?.accepted == true) { "calibration input was rejected" }
    }

    private const val INSTRUCTIONS_PER_COMPUTER = 8_192
    private const val SAMPLE_NANOS = 250_000_000L
    private val MAXIMUM_OVERRUN_NANOS = TimeUnit.SECONDS.toNanos(2)
}
