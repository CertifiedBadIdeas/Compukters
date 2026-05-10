/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.core.device.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DeviceStateSink
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.runtime.ports.GameTimeSource
import ru.lazyhat.compukterkraft.core.device.runtime.ports.NoopDisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVm
import ru.lazyhat.compukterkraft.core.device.vm.DeviceProfileRegistry
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmLogger
import ru.lazyhat.compukterkraft.lang.runtime.DeviceVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Platform-neutral runtime device implementation.
 *
 * Owns the [DeviceVmHandle] and orchestrates the VM lifecycle:
 * boot → tick → flush display sessions → detect stop/crash/reboot.
 *
 * All world-side interactions are abstracted via narrow host ports
 * ([GameTimeSource], [DisplayNetworkBridge], [DeviceStateSink]) so this
 * class can live in `:core` without depending on Minecraft types.
 *
 * Runtime UI output is sent to attached clients as display frame deltas.
 */
class RuntimeDeviceImpl(
    override val deviceId: Int,
    properties: DeviceProperties,
    private val manager: DeviceManager,
    private val gameTime: GameTimeSource,
    private val displayNetwork: DisplayNetworkBridge = NoopDisplayNetworkBridge,
    private val stateSink: DeviceStateSink,
    private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
) : RuntimeDevice,
    DeviceEvents.Receiver {
    override val family: DeviceFamily = properties.family
    private val profile = DeviceProfileRegistry.forFamily(family)

    private val logger = DeviceVmLogger { message -> LOGGER.info { message } }

    private var labelBacking: String? = properties.label
    override var label: String?
        get() = labelBacking
        set(value) {
            labelBacking = value
        }

    private var vmHandle: BackgroundDeviceVm? = null
    private var displayPumpJob: Job? = null
    private val pendingNativeDisplayFrameBytes = ConcurrentLinkedQueue<ByteArray>()

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val displaySessions = DisplaySessionTracker()

    private val hostCallDispatcher by lazy {
        HostCallDispatcher(deviceId, manager.workspace)
    }

    init {
        LOGGER.debug { "DeviceID: $deviceId init" }
    }

    /**
     * Whether the VM is currently running.
     * Derived from the VM handle snapshot — no separate boolean to keep in sync.
     */
    override val isOn: Boolean
        get() {
            val handle = vmHandle ?: return false
            return handle.snapshot().state.isActive
        }

    override fun queueEvent(
        event: String,
        arguments: Array<Any>,
    ) {
        if (!isOn) return
        val accepted = vmHandle?.enqueueEvent(VmEvent(event, arguments.toList())) == true
        if (!accepted) {
            LOGGER.warn { "DeviceID: $deviceId dropped event $event" }
        }
    }

    override fun shutdown() {
        LOGGER.debug { "DeviceID: $deviceId shutdown" }
        vmHandle?.stop(VmStopReason.REQUESTED)
    }

    override fun turnOn() {
        if (isOn) return
        LOGGER.debug { "DeviceID: $deviceId turnOn" }
        manager.ensureWorkspaceInitialized(deviceId)

        manager.removeVm(deviceId, VmStopReason.CLOSED)
        val handle = manager.getOrCreateVm(deviceId, profile, { labelBacking }, logger, runtimeMetricsCollector)
        vmHandle = handle

        reattachDisplaySessions(handle)

        handle.boot()
        observeLifecycle(handle)
        startNativeDisplayPump(handle)
        stateSink.onPowerStateChanged(true)
    }

    override fun reboot() {
        LOGGER.debug { "DeviceID: $deviceId reboot" }
        vmHandle?.stop(VmStopReason.REBOOT) ?: turnOn()
    }

    override fun close() {
        LOGGER.debug { "DeviceID: $deviceId close" }
        stopNativeDisplayPump()
        displaySessions.sessionKeysSnapshot().forEach { (playerUuid, displayId) -> detachDisplaySession(playerUuid, displayId) }
        manager.removeVm(deviceId, VmStopReason.CLOSED)
        vmHandle = null
        serverScope.cancel()
    }

    override fun serverTick() {
        val handle = vmHandle ?: return
        val tickStarted = System.nanoTime()
        serviceVmTick(handle, gameTime.gameTime(), hostCallDispatcher::dispatch, runtimeMetricsCollector)

        val (flushedFrames, flushNanos) =
            measureNanos {
                if (displayPumpJob?.isActive == true) {
                    flushPendingNativeDisplayFrameBytes() + flushDisplaySessions(handle)
                } else {
                    flushDisplaySessions(handle)
                }
            }
        runtimeMetricsCollector.recordDisplayFlush(frameCount = flushedFrames, nanos = flushNanos)
        runtimeMetricsCollector.recordServerTick(System.nanoTime() - tickStarted)
    }

    // ── Lifecycle observation ────────────────────────────────────────

    private fun observeLifecycle(handle: BackgroundDeviceVm) {
        serverScope.launch {
            LOGGER.debug { "DeviceID: $deviceId event listening start" }
            handle.terminalStates.collect { state ->
                LOGGER.debug { "DeviceID: $deviceId VM state: $state" }
                if (state is VmState.Stopped || state is VmState.Crashed) {
                    handleVmStopped(state)
                }
            }
        }
    }

    private fun handleVmStopped(terminalState: VmState) {
        if (terminalState is VmState.Crashed && terminalState.errorMessage != null) {
            LOGGER.warn { "DeviceID: $deviceId VM crash: ${terminalState.errorMessage}" }
        }

        LOGGER.debug { "DeviceID: $deviceId stop handling $terminalState" }

        stopNativeDisplayPump()
        manager.removeVm(deviceId, VmStopReason.CLOSED)
        vmHandle = null
        stateSink.onPowerStateChanged(false)

        if (terminalState is VmState.Stopped && terminalState.reason == VmStopReason.REBOOT) {
            LOGGER.debug { "DeviceID: $deviceId turning on because it was rebooted" }
            turnOn()
        }
    }

    // ── Internal ────────────────────────────────────────────────────

    override fun attachDisplaySession(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        val endpoint = displaySessions.attach(playerUuid, containerId, displayId, width, height)
        vmHandle?.attachDisplay(endpoint.displayId, endpoint.width, endpoint.height)
    }

    override fun resizeDisplaySession(
        playerUuid: UUID,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        val endpoint = displaySessions.resize(playerUuid, displayId, width, height) ?: return
        vmHandle?.resizeDisplay(endpoint.displayId, endpoint.width, endpoint.height)
    }

    override fun detachDisplaySession(
        playerUuid: UUID,
        displayId: Int,
    ) {
        val detachedDisplayId = displaySessions.detach(playerUuid, displayId) ?: return
        vmHandle?.detachDisplay(detachedDisplayId)
    }

    private fun reattachDisplaySessions(handle: BackgroundDeviceVm) {
        for (endpoint in displaySessions.activeEndpoints()) {
            handle.attachDisplay(endpoint.displayId, endpoint.width, endpoint.height)
        }
    }

    private fun flushDisplaySessions(handle: BackgroundDeviceVm): Int {
        if (displaySessions.isEmpty()) return 0
        val (frames, drainNanos) = measureNanos { handle.drainDisplayFrames() }
        runtimeMetricsCollector.recordDisplayFrameDrain(frames.size, drainNanos)
        if (frames.isEmpty()) return 0

        val sessionsByDisplay = displaySessions.sessionsSnapshot().groupBy { it.displayId }
        val toDetach = mutableListOf<Pair<UUID, Int>>()
        for (frame in frames) {
            val sessions = sessionsByDisplay[frame.displayId].orEmpty()
            for (session in sessions) {
                if (!displayNetwork.isDisplaySessionStillBound(session.playerUuid, session.containerId, deviceId, session.displayId)) {
                    toDetach += session.playerUuid to session.displayId
                    continue
                }
                displayNetwork.sendDisplayFrame(session.playerUuid, session.containerId, frame)
            }
        }
        toDetach.forEach { (playerUuid, displayId) -> detachDisplaySession(playerUuid, displayId) }
        return frames.size
    }

    private fun startNativeDisplayPump(handle: BackgroundDeviceVm) {
        stopNativeDisplayPump()
        if (!handle.supportsNativeDisplayFramePump()) return
        collectNativeDisplayFrameBytes(handle)
        displayPumpJob =
            serverScope.launch {
                var observed = handle.nativeDisplayWakeSequence() ?: return@launch
                while (isActive && vmHandle === handle) {
                    val started = System.nanoTime()
                    val next =
                        handle.waitForNativeDisplayWake(
                            observed,
                            NATIVE_DISPLAY_PUMP_TIMEOUT_MILLIS,
                        ) ?: break
                    val woke = next > observed
                    runtimeMetricsCollector.recordNativeDisplayPumpWait(System.nanoTime() - started, woke)
                    observed = next
                    if (!woke) {
                        delay(1)
                        continue
                    }
                    collectNativeDisplayFrameBytes(handle)
                }
            }
    }

    private fun stopNativeDisplayPump() {
        displayPumpJob?.cancel()
        displayPumpJob = null
    }

    private fun collectNativeDisplayFrameBytes(handle: BackgroundDeviceVm): Int {
        val payload = handle.drainNativeDisplayFrameBytes() ?: return 0
        if (payload.size <= EMPTY_NATIVE_FRAME_BATCH_BYTES) return 0
        runtimeMetricsCollector.recordNativeDisplayFrameBytes(payload.size)
        pendingNativeDisplayFrameBytes.add(payload)
        return 1
    }

    private fun flushPendingNativeDisplayFrameBytes(): Int {
        if (displaySessions.isEmpty()) return 0
        var flushed = 0
        while (true) {
            val payload = pendingNativeDisplayFrameBytes.poll() ?: break
            val sessions = displaySessions.sessionsSnapshot()
            if (sessions.isEmpty()) {
                pendingNativeDisplayFrameBytes.add(payload)
                break
            }
            val toDetach = mutableListOf<Pair<UUID, Int>>()
            for (session in sessions) {
                if (!displayNetwork.isDisplaySessionStillBound(session.playerUuid, session.containerId, deviceId, session.displayId)) {
                    toDetach += session.playerUuid to session.displayId
                    continue
                }
                displayNetwork.sendNativeDisplayFrameBytes(session.playerUuid, session.containerId, payload)
            }
            toDetach.forEach { (playerUuid, displayId) -> detachDisplaySession(playerUuid, displayId) }
            flushed += 1
        }
        return flushed
    }

    private companion object {
        const val NATIVE_DISPLAY_PUMP_TIMEOUT_MILLIS: Long = 50
        const val EMPTY_NATIVE_FRAME_BATCH_BYTES: Int = 4
    }
}

internal fun serviceVmTick(
    handle: DeviceVmHandle,
    serverTick: Long,
    dispatchHostCall: (ru.lazyhat.compukterkraft.lang.runtime.HostCall) -> ru.lazyhat.compukterkraft.lang.runtime.HostResult,
    runtimeMetricsCollector: RuntimeMetricsCollector,
) {
    val (_, requestNanos) = measureNanos { handle.requestSlice(serverTick) }
    runtimeMetricsCollector.recordRequestSlice(requestNanos)

    val spinDeadline =
        System.nanoTime() +
            handle.profile.resources.cpu.wallTimeGuardNanosPerSlice
                .coerceAtLeast(1L)
    var remainingIdlePolls = 8
    var drainedCalls = 0
    var dispatchedCalls = 0
    var deliveredResults = 0
    var totalDrainNanos = 0L
    var totalDispatchNanos = 0L
    var totalDeliverNanos = 0L

    while (true) {
        val (calls, drainNanos) = measureNanos { handle.drainHostCalls() }
        totalDrainNanos += drainNanos
        drainedCalls += calls.size
        if (calls.isEmpty()) {
            if (remainingIdlePolls <= 0 || System.nanoTime() >= spinDeadline) {
                break
            }
            remainingIdlePolls -= 1
            Thread.onSpinWait()
            continue
        }

        remainingIdlePolls = 8
        val (results, dispatchNanos) = measureNanos { calls.map(dispatchHostCall) }
        totalDispatchNanos += dispatchNanos
        dispatchedCalls += calls.size

        val (_, deliverNanos) =
            measureNanos {
                if (results.isNotEmpty()) {
                    handle.deliverHostResults(results)
                }
            }
        totalDeliverNanos += deliverNanos
        deliveredResults += results.size

        if (System.nanoTime() >= spinDeadline) {
            break
        }
    }

    runtimeMetricsCollector.recordHostCallDrain(drainedCalls, totalDrainNanos)
    runtimeMetricsCollector.recordHostCallDispatch(dispatchedCalls, totalDispatchNanos)
    runtimeMetricsCollector.recordHostResultDelivery(deliveredResults, totalDeliverNanos)
}

private inline fun <T> measureNanos(block: () -> T): Pair<T, Long> {
    val started = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - started)
}
