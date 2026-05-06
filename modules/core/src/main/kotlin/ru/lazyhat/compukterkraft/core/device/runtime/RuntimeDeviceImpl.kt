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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.ConcurrentHashMap

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

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class DisplaySession(
        val playerUuid: UUID,
        var containerId: Int,
        val displayId: Int,
        var width: Int,
        var height: Int,
    )

    private val displaySessions = ConcurrentHashMap<Pair<UUID, Int>, DisplaySession>()

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
        val handle = manager.getOrCreateVm(deviceId, profile, { labelBacking }, logger)
        vmHandle = handle

        reattachDisplaySessions(handle)

        handle.boot()
        observeLifecycle(handle)
        stateSink.onPowerStateChanged(true)
    }

    override fun reboot() {
        LOGGER.debug { "DeviceID: $deviceId reboot" }
        vmHandle?.stop(VmStopReason.REBOOT) ?: turnOn()
    }

    override fun close() {
        LOGGER.debug { "DeviceID: $deviceId close" }
        displaySessions.keys.toList().forEach { (playerUuid, displayId) -> detachDisplaySession(playerUuid, displayId) }
        manager.removeVm(deviceId, VmStopReason.CLOSED)
        vmHandle = null
        serverScope.cancel()
    }

    override fun serverTick() {
        val handle = vmHandle ?: return

        handle.requestSlice(gameTime.gameTime())

        // Dispatch filesystem host calls
        val results = handle.drainHostCalls().map(hostCallDispatcher::dispatch)
        if (results.isNotEmpty()) {
            handle.deliverHostResults(results)
        }

        flushDisplaySessions(handle)
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
        displaySessions[playerUuid to displayId] = DisplaySession(playerUuid, containerId, displayId, width, height)
        vmHandle?.attachDisplay(displayId, width, height)
    }

    override fun resizeDisplaySession(
        playerUuid: UUID,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        val session = displaySessions[playerUuid to displayId] ?: return
        session.width = width
        session.height = height
        vmHandle?.resizeDisplay(displayId, width, height)
    }

    override fun detachDisplaySession(
        playerUuid: UUID,
        displayId: Int,
    ) {
        displaySessions.remove(playerUuid to displayId)
        vmHandle?.detachDisplay(displayId)
    }

    private fun reattachDisplaySessions(handle: BackgroundDeviceVm) {
        for (session in displaySessions.values) {
            handle.attachDisplay(session.displayId, session.width, session.height)
        }
    }

    private fun flushDisplaySessions(handle: BackgroundDeviceVm) {
        if (displaySessions.isEmpty()) return
        val frames = handle.drainDisplayFrames()
        if (frames.isEmpty()) return

        val sessionsByDisplay = displaySessions.values.groupBy { it.displayId }
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
    }
}
