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
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import ru.lazyhat.compukterkraft.common.block.checkUsable
import ru.lazyhat.compukterkraft.common.context.ServerContext
import ru.lazyhat.compukterkraft.common.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.client.ComputerTerminalClientMessage
import ru.lazyhat.compukterkraft.common.network.server.ServerNetworking
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.computer.runtime.HostCallDispatcher
import ru.lazyhat.compukterkraft.core.computer.ComputerEvents
import ru.lazyhat.compukterkraft.core.computer.ComputerProperties
import ru.lazyhat.compukterkraft.core.computer.vm.BackgroundComputerVm
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerProfileRegistry
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerVmLogger
import ru.lazyhat.compukterkraft.lang.runtime.ComputerVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason

/**
 * Server-side representation of a single computer instance.
 *
 * Owns the [ComputerVmHandle] and orchestrates the VM lifecycle:
 * boot → tick → sync screen → detect stop/crash/reboot.
 *
 * Terminal output is read from the VM's [ScreenBuffer] as an immutable
 * [ScreenBufferSnapshot] each tick and forwarded to watching players.
 */
class ServerComputer(
    val instanceID: Int,
    val level: ServerLevel,
    properties: ComputerProperties,
) : ComputerEvents.Receiver {
    val family = properties.family
    private val profile = ComputerProfileRegistry.forFamily(family)

    private val logger = ComputerVmLogger { message -> LOGGER.info { message } }
    private var label: String? = properties.label

    private var vmHandle: BackgroundComputerVm? = null

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val screenSnapshot = MutableStateFlow<ScreenBufferSnapshot?>(null)

    /** Current screen snapshot (synchronous read). */
    val lastScreenSnapshot: ScreenBufferSnapshot? get() = screenSnapshot.value

    private val computerManager get() = ServerContext.computerManager

    private val hostCallDispatcher by lazy {
        HostCallDispatcher(instanceID, computerManager.workspace)
    }

    init {
        LOGGER.info { "ComputerID: $instanceID init" }
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
        LOGGER.info { "ComputerID: $instanceID shutdown" }
        vmHandle?.stop(VmStopReason.REQUESTED)
    }

    fun turnOn() {
        if (isOn) return
        LOGGER.info { "ComputerID: $instanceID turnOn" }
        computerManager.ensureWorkspaceInitialized(instanceID)

        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        val handle = computerManager.getOrCreateVm(instanceID, profile, { label }, logger)
        vmHandle = handle

        handle.boot()
        observeLifecycle(handle)
    }

    fun reboot() {
        LOGGER.info { "ComputerID: $instanceID reboot" }
        vmHandle?.stop(VmStopReason.REBOOT) ?: turnOn()
    }

    fun close() {
        LOGGER.info { "ComputerID: $instanceID close" }
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

        // Sync screen buffer to watching players
        syncScreen(handle)
    }

    // ── Lifecycle observation ────────────────────────────────────────

    private fun observeLifecycle(handle: BackgroundComputerVm) {
        serverScope.launch {
            LOGGER.info { "ComputerID: $instanceID event listening start" }
            handle.terminalStates.collect { state ->
                LOGGER.info { "ComputerID: $instanceID VM state: $state" }
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

        LOGGER.info { "ComputerID: $instanceID stop handling $terminalState" }

        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        vmHandle = null

        if (terminalState is VmState.Stopped && terminalState.reason == VmStopReason.REBOOT) {
            LOGGER.info { "ComputerID: $instanceID turning on because it was rebooted" }
            turnOn()
        }
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun syncScreen(handle: ComputerVmHandle) {
        val snapshot = handle.readScreenSnapshot() ?: return
        screenSnapshot.value = snapshot
        val players = watchingPlayers().takeIf { it.isNotEmpty() } ?: return
        for (player in players) {
            ServerNetworking.sendToPlayer(
                ComputerTerminalClientMessage(player.containerMenu, snapshot),
                player,
            )
        }
    }

    private fun watchingPlayers(): List<ServerPlayer> =
        ServerContext.server.playerList.players.filter { player ->
            val menu = player.containerMenu
            menu is ComputerMenu && menu.serverSide.computer.instanceID == instanceID
        }
}
