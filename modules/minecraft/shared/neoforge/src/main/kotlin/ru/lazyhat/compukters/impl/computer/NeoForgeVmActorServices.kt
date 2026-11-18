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
import ru.lazyhat.compukters.core.device.runtime.actor.NativeProgramRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorMetrics
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeLifecycle
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.actor.VmCapacityCalibration
import ru.lazyhat.compukters.core.device.runtime.actor.VmCapacityGovernor
import ru.lazyhat.compukters.impl.benchmark.VmCapacityCalibrator
import ru.lazyhat.compukters.impl.compat.enqueueCompuktersTask
import ru.lazyhat.compukters.impl.config.CompuktersServerConfig
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Server-thread-owned lifecycle; a started server allocates workers only on first use. */
internal class VmActorServiceRegistry<S : Any, R : ProgramRuntimeLifecycle>(
    private val checkOwner: (S) -> Unit,
    private val opener: (() -> Unit) -> R,
    private val calibrator: (R) -> CompletableFuture<VmCapacityCalibration>? = { null },
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

    fun service(server: S): R {
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
        if ((entry.remainingEvents <= 0 && !service.supportsCommandOnlyPump) || remainingNanos <= 0) {
            entry.pumpAllowed.set(false)
            return 0
        }
        val started = nanoTime()
        val events = service.pump(entry.remainingEvents, started + remainingNanos)
        entry.remainingEvents -= events
        entry.spentPumpNanos += (nanoTime() - started).coerceAtLeast(0)
        if ((entry.remainingEvents <= 0 && !service.supportsCommandOnlyPump) || entry.spentPumpNanos >= maximumPumpNanosPerTick) {
            entry.pumpAllowed.set(false)
        }
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

    private inner class Entry {
        var service: R? = null
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
        VmActorServiceRegistry<MinecraftServer, ProgramRuntimeService>(
            checkOwner = { server ->
                check(server.isSameThread) { "VM service lifecycle must run on the server thread" }
            },
            opener = { ready ->
                openServerVmRuntime(
                    CompuktersServerConfig.schedulerConfig(),
                    VmCapacityGovernor(CompuktersServerConfig.capacityGovernorConfig()),
                    ready,
                )
            },
            calibrator = { _ ->
                VmCapacityCalibrator.start(CompuktersServerConfig.schedulerConfig().workerCount)
            },
            enqueueOnOwner = { server, task -> server.enqueueCompuktersTask(task) },
        )

    fun service(server: MinecraftServer): ProgramRuntimeService = registry.service(server)

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
        LOGGER.debug {
            "VM actors=${metrics.nativeActors}/${metrics.nativeMaximumActors}, " +
                "pending=${metrics.pendingRequests}, pumpLastUs=${metrics.lastPumpNanos / 1_000}, " +
                "capacity=${metrics.currentInstructionCapacity}/${metrics.calibratedInstructionCapacity}, " +
                "runnable=${metrics.runnableComputersLastFrame}, waiting=${metrics.waitingComputersLastFrame}, " +
                "throttled=${metrics.throttledComputersLastFrame}, retiredTotal=${metrics.retiredInstructionsTotal}, " +
                "worldTotal=${metrics.totalDeferredWorldRequests}, " +
                "inputRejected=${metrics.rejectedInputRequests}, inputCoalesced=${metrics.coalescedRedstoneInputs}"
        }
    }

    private const val METRICS_LOG_INTERVAL_TICKS = 100
}

/** Rust owns execution; the server carrier owns admission and world actions. */
internal fun openServerVmRuntime(
    config: VmActorSchedulerConfig,
    governor: VmCapacityGovernor = VmCapacityGovernor(),
    ready: () -> Unit = {},
): ProgramRuntimeService = NativeProgramRuntimeService(config, capacityGovernor = governor, onResultsReady = ready)
