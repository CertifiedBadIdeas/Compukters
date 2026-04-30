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

package ru.lazyhat.compukterkraft.common.computer.context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import ru.lazyhat.compukterkraft.common.computer.block.checkUsable
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.computer.network.client.StdoutBytesClientMessage
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.computer.DeviceEvents
import ru.lazyhat.compukterkraft.core.computer.DeviceProperties
import ru.lazyhat.compukterkraft.core.computer.runtime.HostCallDispatcher
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
 * Server-side representation of a single computer instance.
 *
 * Owns the [DeviceVmHandle] and orchestrates the VM lifecycle:
 * boot → tick → sync screen → detect stop/crash/reboot.
 *
 * Terminal output is read from the VM's [ScreenBuffer] as an immutable
 * [ScreenBufferSnapshot] each tick and forwarded to watching players.
 */
class ServerComputer(
    val instanceID: Int,
    val level: ServerLevel,
    properties: DeviceProperties,
) : DeviceEvents.Receiver {
    val family = properties.family
    private val profile = DeviceProfileRegistry.forFamily(family)

    private val logger = DeviceVmLogger { message -> LOGGER.info { message } }
    private var label: String? = properties.label

    private var vmHandle: BackgroundDeviceVm? = null

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val screenSnapshot = MutableStateFlow<ScreenBufferSnapshot?>(null)

    /** Current screen snapshot (synchronous read). */
    val lastScreenSnapshot: ScreenBufferSnapshot? get() = screenSnapshot.value

    /**
     * Epic 2 terminal sessions — one per attached player. A session is created
     * by [AttachTerminalServerMessage][ru.lazyhat.compukterkraft.common.computer.network.server.AttachTerminalServerMessage]
     * and torn down in [serverTick] when the player's open menu no longer
     * references this computer.
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

    private val computerManager get() = ServerContext.computerManager

    private val hostCallDispatcher by lazy {
        HostCallDispatcher(instanceID, computerManager.workspace)
    }

    init {
        LOGGER.debug { "ComputerID: $instanceID init" }
    }

    /**
     * Whether the VM is currently running.
     * Derived from the VM handle snapshot — no separate boolean to keep in sync.
     */
    val isOn: Boolean
        get() {
            val handle = vmHandle ?: return false
            return handle.snapshot().state.isActive
        }

    fun checkUsable(player: Player) = family.checkUsable(player)

    fun updateLabel(value: String?) {
        label = value
    }

    override fun queueEvent(
        event: String,
        arguments: Array<Any>,
    ) {
        if (!isOn) return
        val accepted = vmHandle?.enqueueEvent(VmEvent(event, arguments.toList())) == true
        if (!accepted) {
            LOGGER.warn { "ComputerID: $instanceID dropped event $event" }
        }
    }

    fun shutdown() {
        LOGGER.debug { "ComputerID: $instanceID shutdown" }
        vmHandle?.stop(VmStopReason.REQUESTED)
    }

    fun turnOn() {
        if (isOn) return
        LOGGER.debug { "ComputerID: $instanceID turnOn" }
        computerManager.ensureWorkspaceInitialized(instanceID)

        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        val handle = computerManager.getOrCreateVm(instanceID, profile, { label }, logger)
        vmHandle = handle

        // Rebind any already-attached terminal sessions to the new VM's broadcaster.
        // Consumers on the previous VM (if any) are discarded when the old
        // BackgroundDeviceVm is reaped; here we just create fresh consumers.
        rebindTerminalConsumers(handle)

        handle.boot()
        observeLifecycle(handle)
    }

    fun reboot() {
        LOGGER.debug { "ComputerID: $instanceID reboot" }
        vmHandle?.stop(VmStopReason.REBOOT) ?: turnOn()
    }

    fun close() {
        LOGGER.debug { "ComputerID: $instanceID close" }
        terminalSessions.keys.toList().forEach(::detachTerminalSession)
        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        vmHandle = null
        serverScope.cancel()
    }

    fun serverTick() {
        val handle = vmHandle ?: return

        handle.requestSlice(level.gameTime)

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
            LOGGER.debug { "ComputerID: $instanceID event listening start" }
            handle.terminalStates.collect { state ->
                LOGGER.debug { "ComputerID: $instanceID VM state: $state" }
                if (state is VmState.Stopped || state is VmState.Crashed) {
                    handleVmStopped(state)
                }
            }
        }
    }

    private fun handleVmStopped(terminalState: VmState) {
        if (terminalState is VmState.Crashed && terminalState.errorMessage != null) {
            LOGGER.warn { "ComputerID: $instanceID VM crash: ${terminalState.errorMessage}" }
        }

        LOGGER.debug { "ComputerID: $instanceID stop handling $terminalState" }

        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        vmHandle = null

        if (terminalState is VmState.Stopped && terminalState.reason == VmStopReason.REBOOT) {
            LOGGER.debug { "ComputerID: $instanceID turning on because it was rebooted" }
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

    fun attachTerminalSession(
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
                // the computer is off, the consumer is attached later by
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

    fun resizeTerminalSession(
        playerUuid: UUID,
        cols: Int,
        rows: Int,
    ) {
        terminalSessions[playerUuid]?.let {
            it.cols = cols
            it.rows = rows
        }
    }

    private fun detachTerminalSession(playerUuid: UUID) {
        val handle = vmHandle
        val session = terminalSessions.remove(playerUuid) ?: return
        session.consumer?.let { c -> handle?.stdioBroadcaster?.removeConsumer(c) }
    }

    private fun flushTerminalSessions() {
        if (terminalSessions.isEmpty()) return
        val server = ServerContext.server
        val toDetach = mutableListOf<UUID>()

        for ((uuid, session) in terminalSessions) {
            val player = server.playerList.getPlayer(uuid)
            val menu = player?.containerMenu
            val stillOpen =
                menu is ComputerMenu &&
                    menu.containerId == session.containerId &&
                    menu.serverSide.computer.instanceID == instanceID
            if (!stillOpen) {
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
            ServerNetworking.sendToPlayer(
                StdoutBytesClientMessage(session.containerId, acc),
                player,
            )
        }

        toDetach.forEach(::detachTerminalSession)
    }
}
