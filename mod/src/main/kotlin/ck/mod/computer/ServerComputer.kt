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

package ck.mod.computer

import ck.lang.runtime.ComputerVmHandle
import ck.lang.runtime.ScreenBufferSnapshot
import ck.lang.runtime.VmEvent
import ck.lang.runtime.VmState
import ck.lang.runtime.VmStopReason
import ck.mod.LOGGER
import ck.mod.application.runtime.ComputerProgramCompiler
import ck.mod.application.runtime.HostCallDispatcher
import ck.mod.application.runtime.WorkspaceProgramLoader
import ck.mod.computer.vm.BackgroundComputerVm
import ck.mod.computer.vm.ComputerProfileRegistry
import ck.mod.computer.vm.ComputerVmLogger
import ck.mod.computer.vm.VmLifecycleEvent
import ck.mod.context.ServerContext
import ck.mod.language.LanguageServices
import ck.mod.menu.ComputerMenu
import ck.mod.network.client.ComputerTerminalClientMessage
import ck.mod.network.server.ServerNetworking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

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

    private val _screenSnapshot = MutableStateFlow<ScreenBufferSnapshot?>(null)
    /**
     * Last known screen snapshot — used for initial sync when new players open the GUI.
     * Observe via [screenSnapshotFlow] or read the current value with [lastScreenSnapshot].
     */
    val screenSnapshotFlow: StateFlow<ScreenBufferSnapshot?> = _screenSnapshot.asStateFlow()
    /** Current screen snapshot (synchronous read). */
    val lastScreenSnapshot: ScreenBufferSnapshot? get() = _screenSnapshot.value

    private val computerManager get() = ServerContext.computerManager

    private val programLoader by lazy {
        WorkspaceProgramLoader(computerManager.workspace, LanguageServices::bundledScript)
    }
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
            val state = handle.snapshot().state
            return state != VmState.COLD && state != VmState.STOPPED && state != VmState.CRASHED
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
        val bootScript = programLoader.load(instanceID, profile.bootScriptName)
        if (bootScript == null) {
            LOGGER.error { "Missing boot script in workspace: ${profile.bootScriptName}" }
            return
        }

        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        val handle = computerManager.getOrCreateVm(instanceID, profile, { label }, logger)
        val compiledProgram = ComputerProgramCompiler.compile(bootScript.path, bootScript.source)
        val program = compiledProgram.program
        if (program == null) {
            val message = compiledProgram.errorMessage.orEmpty()
            LOGGER.error { "Compilation Error: $message" }
            computerManager.removeVm(instanceID, VmStopReason.CLOSED)
            return
        }

        vmHandle = handle
        val started = handle.start(program)
        if (started) {
            handle.enqueueEvent(VmEvent("boot"))
            observeLifecycle(handle)
        }
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
            handle.lifecycleEvents.collect { event ->
                when (event) {
                    is VmLifecycleEvent.Stopped -> handleVmStopped(event.reason)
                }
            }
        }
    }

    private fun handleVmStopped(reason: VmStopReason) {
        val snapshot = vmHandle?.snapshot() ?: return
        if (snapshot.state == VmState.CRASHED && snapshot.errorMessage != null) {
            LOGGER.warn { "ComputerID: $instanceID VM crash: ${snapshot.errorMessage}" }
        }

        computerManager.removeVm(instanceID, VmStopReason.CLOSED)
        vmHandle = null

        if (reason == VmStopReason.REBOOT) {
            turnOn()
        }
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun syncScreen(handle: ComputerVmHandle) {
        val screenSnapshot = handle.readScreenSnapshot() ?: return
        _screenSnapshot.value = screenSnapshot
        val players = watchingPlayers()
        if (players.isEmpty()) return
        for (player in players) {
            ServerNetworking.sendToPlayer(
                ComputerTerminalClientMessage(player.containerMenu, screenSnapshot),
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
