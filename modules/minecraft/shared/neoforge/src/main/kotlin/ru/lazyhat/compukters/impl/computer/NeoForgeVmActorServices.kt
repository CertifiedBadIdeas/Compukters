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
import java.util.concurrent.atomic.AtomicBoolean

/** Server-thread-owned lifecycle; a started server allocates workers only on first use. */
internal class VmActorServiceRegistry<S : Any>(
    private val checkOwner: (S) -> Unit,
    private val opener: (() -> Unit) -> ProgramRuntimeActorService = { ready ->
        ProgramRuntimeActorService(onResultsReady = ready, capacityFramesRequired = true)
    },
    private val calibrator: (ProgramRuntimeActorService) -> CompletableFuture<VmCapacityCalibration>? = { null },
    private val maximumEventsPerTick: Int = 1_024,
    private val enqueueOnOwner: ((S, Runnable) -> Unit)? = null,
    private val maximumPumpNanosPerTick: Long = 2_000_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val servers = IdentityHashMap<S, Entry>()

    init {
        require(maximumEventsPerTick > 0) { "VM result budget must be positive" }
        require(maximumPumpNanosPerTick > 0) { "VM owner pump time budget must be positive" }
    }

    fun start(server: S) {
        checkOwner(server)
        check(server !in servers) { "VM service lifecycle already started" }
        servers[server] = Entry()
    }

    fun service(server: S): ProgramRuntimeActorService {
        checkOwner(server)
        val entry = checkNotNull(servers[server]) { "VM service requires a running server" }
        return entry.service ?: opener { resultsReady(server, entry) }.also { service ->
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
        entry.remainingEvents = maximumEventsPerTick
        entry.spentPumpNanos = 0
        entry.pumpAllowed.set(true)
        if (worldTick != null) {
            entry.frameTick = worldTick
            entry.service?.beginCapacityFrame(worldTick)
        }
        val pumped = pumpEntry(server, entry)
        if (worldTick != null) entry.service?.observePreviousCapacityFrame()
        return pumped
    }

    /** Workers only enqueue a notification; every reply and world action is handled on the owner. */
    private fun resultsReady(
        server: S,
        entry: Entry,
    ) {
        val enqueue = enqueueOnOwner ?: return
        if (entry.closed.get() || !entry.pumpAllowed.get() || !entry.pumpQueued.compareAndSet(false, true)) return
        try {
            enqueue(
                server,
                Runnable {
                    checkOwner(server)
                    entry.pumpQueued.set(false)
                    if (!entry.closed.get()) pumpEntry(server, entry)
                },
            )
        } catch (failure: Exception) {
            entry.pumpQueued.set(false)
            if (!entry.closed.get()) LOGGER.warn(failure) { "Could not enqueue VM result delivery; pre-tick delivery remains available" }
        }
    }

    private fun pumpEntry(
        server: S,
        entry: Entry,
    ): Int {
        checkOwner(server)
        val service = entry.service ?: return 0
        val remainingNanos = maximumPumpNanosPerTick - entry.spentPumpNanos
        if (entry.remainingEvents <= 0 || remainingNanos <= 0) {
            entry.pumpAllowed.set(false)
            return 0
        }
        val started = nanoTime()
        val events = service.pump(entry.remainingEvents, started + remainingNanos)
        entry.remainingEvents -= events
        entry.spentPumpNanos += (nanoTime() - started).coerceAtLeast(0)
        if (entry.remainingEvents <= 0 || entry.spentPumpNanos >= maximumPumpNanosPerTick) entry.pumpAllowed.set(false)
        return events
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
        servers.remove(server)?.let { entry ->
            entry.closed.set(true)
            entry.pumpAllowed.set(false)
            entry.service?.close()
        }
    }

    private class Entry {
        var service: ProgramRuntimeActorService? = null
        var frameTick: Long? = null
        val closed = AtomicBoolean()
        val pumpQueued = AtomicBoolean()
        val pumpAllowed = AtomicBoolean()
        var remainingEvents = 0
        var spentPumpNanos = 0L
        var calibration: CompletableFuture<VmCapacityCalibration>? = null
    }
}

internal object NeoForgeVmActorServices {
    private val registry =
        VmActorServiceRegistry<MinecraftServer>(
            checkOwner = { server ->
                check(server.isSameThread) { "VM service lifecycle must run on the server thread" }
            },
            opener = { ready ->
                ProgramRuntimeActorService(
                    CompuktersServerConfig.schedulerConfig(),
                    capacityGovernor = VmCapacityGovernor(CompuktersServerConfig.capacityGovernorConfig()),
                    onResultsReady = ready,
                    capacityFramesRequired = true,
                )
            },
            calibrator = { VmCapacityCalibrator.start(CompuktersServerConfig.schedulerConfig().workerCount) },
            enqueueOnOwner = { server, task -> server.execute(task) },
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
                "queueP95Us=${scheduler.queueLatencyP95Nanos / 1_000}, " +
                "executionP95Us=${scheduler.executionLatencyP95Nanos / 1_000}, " +
                "resultP95Us=${scheduler.resultLatencyP95Nanos / 1_000}, " +
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
