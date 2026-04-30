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

package ru.lazyhat.compukterkraft.core.computer.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.computer.DeviceEvents
import ru.lazyhat.compukterkraft.core.computer.DeviceProperties
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.DeviceStateSink
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.GameTimeSource
import ru.lazyhat.compukterkraft.core.computer.runtime.ports.TerminalNetworkBridge
import ru.lazyhat.compukterkraft.core.computer.vm.BackgroundDeviceVm
import ru.lazyhat.compukterkraft.core.computer.vm.DeviceProfileRegistry
import ru.lazyhat.compukterkraft.core.computer.vm.DeviceVmLogger
import ru.lazyhat.compukterkraft.core.computer.vm.api.ComputerStdioBroadcaster
import ru.lazyhat.compukterkraft.lang.runtime.DeviceVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Platform-neutral runtime device implementation.
 *
 * Owns the [DeviceVmHandle] and orchestrates the VM lifecycle:
 * boot → tick → sync screen → detect stop/crash/reboot.
 *
 * All world-side interactions are abstracted via narrow host ports
 * ([GameTimeSource], [TerminalNetworkBridge], [DeviceStateSink]) so this
 * class can live in `:core` without depending on Minecraft types.
 *
 * Terminal output is read from the VM's screen buffer as an immutable
 * [ScreenBufferSnapshot] each tick and forwarded to attached terminal
 * sessions via [TerminalNetworkBridge].
 */
class RuntimeDeviceImpl(
    override val deviceId: Int,
    properties: DeviceProperties,
    private val manager: DeviceManager,
    private val gameTime: GameTimeSource,
    private val terminalNetwork: TerminalNetworkBridge,
    private val stateSink: DeviceStateSink,
) : RuntimeDevice, DeviceEvents.Receiver {
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

    private val screenSnapshot = MutableStateFlow<ScreenBufferSnapshot?>(null)

    /** Current screen snapshot (synchronous read). */
    override val lastScreenSnapshot: ScreenBufferSnapshot? get() = screenSnapshot.value

    /**
     * Epic 2 terminal sessions — one per attached player. A session is created
     * by [AttachTerminalServerMessage][ru.lazyhat.compukterkraft.common.computer.network.server.AttachTerminalServerMessage]
     * and torn down in [serverTick] when the player's open menu no longer
     * references this device.
     */
    private data class TerminalSession(
        val playerUuid: UUID,
        var containerId: Int,
        var cols: Int,
        var rows: Int,
        val pending: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue(),
        var consumer: ComputerStdioBroadcaster.Consumer? = null,
    )

    private val terminalSessions = ConcurrentHashMap<UUID, TerminalSession>()

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

        // Rebind any already-attached terminal sessions to the new VM's broadcaster.
        // Consumers on the previous VM (if any) are discarded when the old
        // BackgroundDeviceVm is reaped; here we just create fresh consumers.
        rebindTerminalConsumers(handle)

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
        terminalSessions.keys.toList().forEach(::detachTerminalSession)
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

        // Sync screen buffer to watching players (legacy snapshot path)
        syncScreen(handle)

        // Flush stdout byte stream to attached terminal sessions (Epic 2)
        flushTerminalSessions()
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

    private fun syncScreen(handle: DeviceVmHandle) {
        val snapshot = handle.readScreenSnapshot() ?: return
        screenSnapshot.value = snapshot
        // Legacy client-bound ComputerTerminalClientMessage broadcast was removed
        // in Epic 4 — attached clients now receive bytes via StdoutBytesClientMessage
        // (see [flushTerminalSessions]). Workbench still reads [lastScreenSnapshot]
        // via its own pipeline.
    }

    // ── Epic 2 terminal sessions ────────────────────────────────────

    override fun attachTerminalSession(
        playerUuid: UUID,
        containerId: Int,
        cols: Int,
        rows: Int,
    ) {
        terminalSessions.compute(playerUuid) { _, existing ->
            if (existing != null) {
                existing.containerId = containerId
                existing.cols = cols
                existing.rows = rows
                existing
            } else {
                val session = TerminalSession(playerUuid, containerId, cols, rows)
                // Attach to the currently running VM's broadcaster (if any). If
                // the device is off, the consumer is attached later by
                // [rebindTerminalConsumers] as soon as [turnOn] creates a VM.
                vmHandle?.let { bindConsumer(session, it) }
                session
            }
        }
    }

    private fun bindConsumer(
        session: TerminalSession,
        handle: BackgroundDeviceVm,
    ) {
        if (session.consumer != null) return
        val consumer =
            ComputerStdioBroadcaster.Consumer { bytes ->
                session.pending.add(bytes)
            }
        session.consumer = consumer
        handle.stdioBroadcaster.addConsumer(consumer)
    }

    private fun rebindTerminalConsumers(handle: BackgroundDeviceVm) {
        if (terminalSessions.isEmpty()) return
        for (session in terminalSessions.values) {
            // Each session is bound to the previous VM's (now-defunct) broadcaster;
            // clear the reference so bindConsumer actually does its work.
            session.consumer = null
            bindConsumer(session, handle)
        }
    }

    override fun resizeTerminalSession(
        playerUuid: UUID,
        cols: Int,
        rows: Int,
    ) {
        terminalSessions[playerUuid]?.let {
            it.cols = cols
            it.rows = rows
        }
    }

    override fun detachTerminalSession(playerUuid: UUID) {
        val handle = vmHandle
        val session = terminalSessions.remove(playerUuid) ?: return
        session.consumer?.let { c -> handle?.stdioBroadcaster?.removeConsumer(c) }
    }

    private fun flushTerminalSessions() {
        if (terminalSessions.isEmpty()) return
        val toDetach = mutableListOf<UUID>()

        for ((uuid, session) in terminalSessions) {
            if (!terminalNetwork.isSessionStillBound(uuid, session.containerId, deviceId)) {
                toDetach += uuid
                continue
            }

            // Drain pending bytes and send as a single chunk (up to 8 KB per tick per session).
            var acc: ByteArray = session.pending.poll() ?: continue
            val cap = 8 * 1024
            while (acc.size < cap) {
                val next = session.pending.peek() ?: break
                if (acc.size + next.size > cap) break
                session.pending.poll()
                val merged = ByteArray(acc.size + next.size)
                System.arraycopy(acc, 0, merged, 0, acc.size)
                System.arraycopy(next, 0, merged, acc.size, next.size)
                acc = merged
            }
            terminalNetwork.sendStdoutBytes(uuid, session.containerId, acc)
        }

        toDetach.forEach(::detachTerminalSession)
    }
}
