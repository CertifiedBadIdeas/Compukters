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

package ru.lazyhat.compukters.impl.computer

import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorMetrics
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorService
import ru.lazyhat.compukters.core.device.runtime.actor.VmCapacityCalibration
import ru.lazyhat.compukters.core.device.runtime.actor.VmCapacityGovernor
import ru.lazyhat.compukters.impl.benchmark.VmCapacityCalibrator
import ru.lazyhat.compukters.impl.config.CompuktersServerConfig
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

/** Server-thread-owned lifecycle; a started server allocates workers only on first use. */
internal class VmActorServiceRegistry<S : Any>(
    private val checkOwner: (S) -> Unit,
    private val opener: () -> ProgramRuntimeActorService = ::ProgramRuntimeActorService,
    private val calibrator: (ProgramRuntimeActorService) -> CompletableFuture<VmCapacityCalibration>? = { null },
    private val maximumEventsPerTick: Int = 1_024,
) {
    private val servers = IdentityHashMap<S, Entry>()

    init {
        require(maximumEventsPerTick > 0) { "VM result budget must be positive" }
    }

    fun start(server: S) {
        checkOwner(server)
        check(server !in servers) { "VM service lifecycle already started" }
        servers[server] = Entry()
    }

    fun service(server: S): ProgramRuntimeActorService {
        checkOwner(server)
        val entry = checkNotNull(servers[server]) { "VM service requires a running server" }
        return entry.service ?: opener().also { service ->
            entry.frameTick?.let(service::beginCapacityFrame)
            entry.service = service
            entry.calibration = calibrator(service)
        }
    }

    fun tick(
        server: S,
        worldTick: Long? = null,
    ): Int {
        checkOwner(server)
        val entry = servers[server] ?: return 0
        entry.calibration?.takeIf { it.isDone }?.let { calibration ->
            entry.calibration = null
            val service = checkNotNull(entry.service)
            try {
                service.acceptCapacityCalibration(calibration.join())
            } catch (failure: Exception) {
                service.rejectCapacityCalibration(failure.cause?.message ?: failure.message ?: "calibration failed")
            }
        }
        val pumped = entry.service?.pump(maximumEventsPerTick) ?: 0
        if (worldTick != null) {
            entry.service?.observePreviousCapacityFrame()
            entry.frameTick = worldTick
            entry.service?.beginCapacityFrame(worldTick)
        }
        return pumped
    }

    fun afterTick(server: S) {
        checkOwner(server)
        val entry = servers[server] ?: return
        entry.service?.flushCapacityFrame()
        entry.frameTick = null
    }

    fun metrics(server: S): ProgramRuntimeActorMetrics? {
        checkOwner(server)
        return servers[server]?.service?.runtimeMetrics()
    }

    fun stop(server: S) {
        checkOwner(server)
        servers.remove(server)?.service?.close()
    }

    private class Entry {
        var service: ProgramRuntimeActorService? = null
        var frameTick: Long? = null
        var calibration: CompletableFuture<VmCapacityCalibration>? = null
    }
}

internal object NeoForgeVmActorServices {
    private val registry =
        VmActorServiceRegistry<MinecraftServer>(
            checkOwner = { server ->
                check(server.isSameThread) { "VM service lifecycle must run on the server thread" }
            },
            opener = {
                ProgramRuntimeActorService(
                    CompuktersServerConfig.schedulerConfig(),
                    capacityGovernor = VmCapacityGovernor(CompuktersServerConfig.capacityGovernorConfig()),
                )
            },
            calibrator = { VmCapacityCalibrator.start(CompuktersServerConfig.schedulerConfig().workerCount) },
        )

    fun service(server: MinecraftServer): ProgramRuntimeActorService = registry.service(server)

    fun metrics(server: MinecraftServer): ProgramRuntimeActorMetrics? = registry.metrics(server)

    fun onServerStarting(event: ServerStartingEvent) = registry.start(event.server)

    fun beforeServerTick(event: ServerTickEvent.Pre) {
        registry.tick(event.server, event.server.tickCount.toLong())
        if (event.server.tickCount % METRICS_LOG_INTERVAL_TICKS == 0) {
            registry.metrics(event.server)?.let(::logMetrics)
        }
    }

    fun afterServerTick(event: ServerTickEvent.Post) = registry.afterTick(event.server)

    fun onServerStopping(event: ServerStoppingEvent) = registry.stop(event.server)

    private fun logMetrics(metrics: ProgramRuntimeActorMetrics) {
        val scheduler = metrics.scheduler
        val processed = (scheduler.processedMessages + scheduler.processedPermits).coerceAtLeast(1)
        val drained = scheduler.drainedEvents.coerceAtLeast(1)
        val continuationSamples = metrics.hostContinuationSamples.coerceAtLeast(1)
        LOGGER.debug {
            "VM actors: registered=${scheduler.registeredActors}/${scheduler.maximumActors}, " +
                "runnable=${scheduler.scheduledActors}, " +
                "mailbox=${scheduler.queuedMessages}, permits=${scheduler.pendingPermits}, " +
                "results=${scheduler.queuedResults}, workers=${scheduler.busyWorkers}, " +
                "queueAvgUs=${scheduler.totalQueueLatencyNanos / processed / 1_000}, " +
                "queueMaxUs=${scheduler.maximumQueueLatencyNanos / 1_000}, " +
                "executionAvgUs=${scheduler.totalExecutionNanos / processed / 1_000}, " +
                "executionMaxUs=${scheduler.maximumExecutionNanos / 1_000}, " +
                "resultAvgUs=${scheduler.totalResultLatencyNanos / drained / 1_000}, " +
                "resultMaxUs=${scheduler.maximumResultLatencyNanos / 1_000}, " +
                "drainedLast=${metrics.lastPumpEvents}, pumpLastUs=${metrics.lastPumpNanos / 1_000}, " +
                "worldDeferred=${metrics.deferredWorldRequests}, worldTotal=${metrics.totalDeferredWorldRequests}, " +
                "hostToAdvance=n=${metrics.hostContinuationSamples}/" +
                "avg=${metrics.totalHostContinuationDelayTicks / continuationSamples}/" +
                "max=${metrics.maximumHostContinuationDelayTicks} ticks, " +
                "inputRejected=${metrics.rejectedInputRequests}, inputCoalesced=${metrics.coalescedRedstoneInputs}, " +
                "mailboxRejected=${scheduler.mailboxFullRejections}, " +
                "permitRejected=${scheduler.permitPendingRejections}, " +
                "capacity=${metrics.currentInstructionCapacity}/${metrics.calibratedInstructionCapacity}, " +
                "runnableComputers=${metrics.runnableComputersLastFrame}, waitingComputers=${metrics.waitingComputersLastFrame}, " +
                "throttled=${metrics.throttledComputersLastFrame}, " +
                "requested=${metrics.requestedInstructionsLastFrame}, reserved=${metrics.reservedInstructionsLastFrame}, " +
                "missed=${metrics.missedInstructionsLastFrame}, retiredTotal=${metrics.retiredInstructionsTotal}, " +
                "unusedTotal=${metrics.unusedReservationsTotal}"
        }
    }

    private const val METRICS_LOG_INTERVAL_TICKS = 100
}
